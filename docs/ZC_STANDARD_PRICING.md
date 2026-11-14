# ZC Standard expression pricing

Implemented locally on 2026-11-14. The interpreter and client specs validate these paths offline;
the new priced paths have not been broadcast or tested against a live node. Current trees, hashes
and addresses are generated in [ZC_STANDARD_REFERENCE.md](./ZC_STANDARD_REFERENCE.md).

## Contracts and policies

| Contract | What is fixed | What a policy prices |
|---|---|---|
| `Order` | Principal, repayment and every borrower term | Nothing: fixed principal |
| `PricedOrder` | Repayment, maturity rule, collateral, borrower, liquidation rule, fees | Minimum principal at fill |
| `SaleOrder` | Price per raw token unit | Nothing: fixed price |
| `PricedAsk` | Asset, seller, floor, minimum fill and expiry | Minimum payment for the units sold |
| `LendOffer` | Minimum yield, maturity window, collateral policy and fees | Maximum cost allowed by its yield test |

`PricedAsk` is the generic name of the former `NavAsk`; the shell's tree bytes and address are
unchanged. An expression is a validator returning `SigmaProp`, not a function returning a price.
The filler supplies bytes matching the expression hash the owner committed when posting. Each box
selects one pricing expression, independently of other boxes. Adding another supported pricing
policy does not require another ask shell. Pricing an unissued loan uses the separate `PricedOrder`
interface; ask and order policies share mathematics but read different boxes.

The fixed order remains an accepted issuer. The updated lend offer pins **both** issuing hashes,
for primary fills and secondary origin checks. Arbitrary scripts are not accepted as issuers.
The bond/vault, fractional and Dutch lifecycle contracts do not change. Existing offer boxes keep
their original hashes and issuer restrictions; changing source does not upgrade them.

## Principal resolution

`PricedOrder` has the fixed order's collateral, expiry, cancellation, three-unit mint, output positions
and maturity checks. The resulting bond records the **actual principal**, not the initial quote:

- ERG: bond R6(1) equals borrower payout nanoERG minus the refunded expiry fee; bond R7 is empty.
- Token: bond R7 is exactly the repayment token and actual principal amount; payout tokens match it
  exactly. The nanoERG principal component remains the committed box funding, at most 1000000.
- Bond R6(0), R6(2..) and the liquidation expression are unchanged. Repayment never reprices.
- The quoted principal is always a floor. Paying above the expression's minimum is permitted only
  when the actual principal is recorded consistently. This gives a buyer room for block inclusion
  and avoids stale principal in future NAV, yield or metadata reads.

The shell validates these rules before executing pricing. A permissive pricing expression cannot
weaken them. Collateral guards execute separately, so pricing cannot turn one off.

### PricedOrder registers and context

| Register | Meaning |
|---|---|
| R4 `Coll[Long]` | Fixed order borrower terms (7..64); principal nanoERG is a minimum/quote |
| R5 `Coll[(Coll[Byte], Long)]` | Minimum principal tokens; empty for ERG, one repayment token for a token loan |
| R6 `Coll[Byte]` | Borrower script hash |
| R7 `Coll[Byte]` | Liquidation expression hash |
| R8 `Coll[Coll[Byte]]` | Exactly `(pricing hash, optional guard hash)`; pricing is 32 bytes, guard empty or 32 |
| R9 `Coll[Long]` | Pricing payload, at most 64 values |

Input 0 is the issuing order. Output 0 is the circulating unit, output 1 the standard bond, output 2
the principal and expiry refund. On fills, context var 1 carries the optional fill guard; var 2
carries mandatory pricing. Both run with the **order** as `SELF`. The guard uses the existing oracle
payload R4(7..9). On expiry var 0 selects the refund output. Refund/cancel do not read R8/R9, so even
malformed or unknown policies can be exited without their bytes.

## NAV pricing

For an ask, `AccruedValue_Pricing` reads original principal P, repayment R, start S and maturity M
from the existing live bond:

```text
NAV(H) = [P * (M-H) + R * (H-S)] / (M-S)
minimum ask payment = NAV(H) * units/N * (10000 + margin_bps)/10000
```

For an order, `NavOrder_Pricing` reads the quoted principal P0 from the order, a reference height H0
equal to its creation height, and the new bond's **actual** maturity M:

```text
minimum principal = max(P0, ceil([P0*(M-H) + R*(H-H0)] * (10000+margin_bps) / [(M-H0)*10000]))
```

Order R9 is `(H0, margin_bps)`. Margins are signed, greater than -10000 and at most 1000000 bps.
The reference cannot be changed by the filler. Both maturity modes work:

- Fixed: M is the committed absolute maturity height.
- On-close: M is the validated fill start plus the committed duration. Waiting raises the NAV
  price toward R, while the duration after fill stays fixed. There is no separate expiry of the
  NAV curve at H0 + duration: the endpoint moves with the fill. The order's actual expiry still applies.

Pricing requires H0 <= H < M. An ask additionally authenticates the live bond state; after settlement
or at maturity its price is unavailable and the seller cancels/refunds and claims the proceeds.

## Simple block-yield pricing

Use the same normalization as `LendOffer`:

```text
B = 10000 * 262800
y = nonnegative simple yield in basis points per 262800 blocks
D = actual maturity - HEIGHT
F = R for a whole bond; R * units/N for shares
minimum payment = ceil(F * B * (10000+margin_bps) / ((B + y*D)*10000))
```

`YieldOrder_Pricing` uses order R9 `(y, margin_bps)` and prices principal. `YieldAsk_Pricing` uses ask R5(4)=y,
R5(5)=N, R5(6)=signed price margin bps, R7=bond asset id and context var 3=live bond data-input index. The ask's existing payout
and continuation conventions are unchanged. Both policies widen products to BigInt before
multiplication and check inequalities without on-chain division. Integer rounding favors the
borrower/seller. The fixed principal/ask floor may bind. A margin is applied to the exact rational price before
rounding, with the same bounds as NAV: -9999..1000000 bps. Zero preserves the chosen block yield
before floors and fees. A positive margin raises the price and lowers the buyer's resulting yield;
a negative margin discounts the price and raises the resulting yield, subject to the floor. The
quoted yield is therefore a reference rate when a nonzero price margin is selected.

This is simple return over **blocks**, not a wall-clock promise or compounding. A quoted yield is
conditional on full repayment at maturity. It is not a market rate, collateral valuation, guarantee
of recovery, or the selling holder's historical return. A seller chooses its prospective buyer rate
independently of the original loan rate. Yield pricing needs no original principal or start anchor.

For a 110-unit repayment and y=1000 bps at zero margin, ignoring floors and atomic rounding:

| Remaining blocks | Yield-priced principal/payment | Straight-line NAV from 100 over 262800 blocks |
|---:|---:|---:|
| 262800 | 100 | 100 |
| 131400 | 104.761904... | 105 |
| 65700 | 107.317073... | 107.5 |

For an on-close yield order, the duration stays constant, so waiting alone does not change the
quote, unlike on-close NAV. Once filled, the recorded P and R are fixed for either policy.
Generic token sales and liquidation collateral auctions have no defined future cash flow/maturity
to which this formula applies; their existing fixed and Dutch pricing remain available.

## Collateral guards and fee treatment

`ERG_SigUSD_GuardedYield_Pricing` adds the same oracle and minimum-cover checks as guarded NAV.
For guarded yield, ask R5(7..8) is `(minimum cover bps, maximum oracle age)` after the margin at R5(6).
Guarded NAV retains its original guard positions R5(6..7). It needs a fresh oracle and values
collateral against fixed repayment, excluding the lender reserve. Order guards use the independent
R8 second hash and existing `ERG_SigUSD_FillGuard` expression.

With zero price margin, borrower/seller yield y is an upper limit on the gross yield they offer a buyer (a minimum price).
Lender yield is a lower limit on the return it accepts (a maximum cost). Ignoring fees and rounding,
an ask offering 10% can cross a bid requiring 8%; one offering 6% cannot. For an ERG offer its cost
includes the batcher fee: `cost = price + batcherFee`. A token offer pays fees separately in ERG.
Other reserve/box funding is not included in this yield test. Do not label it a fully fee-adjusted
return. Equal rate limits may fail to match; short durations or small share fills may have so little
interest that the batcher fee exceeds it, even with a lender yield floor of zero.

## Building, matching and examples

The CLI accepts an explicit pricing choice; fixed orders remain the default. `--principal` is still
required for a priced order, as its floor and NAV reference principal. `--yield` is a percentage
with up to two decimals, interpreted per 262800 blocks; zero is permitted. `--nav` and `--yield`
are mutually exclusive. `--margin` accepts a signed price adjustment with either `--nav` or `--yield`; its default is zero. With an expression-priced ask, `--price` is an
optional floor for the entire offered amount. Floors and guards are independently enforced.

```text
create-order --principal 5 --repayment 6 --collateral-erg 10 --term 21600 --nav
create-order --principal 5 --repayment 6 --collateral-erg 10 --duration 21600 --nav --margin -1
create-order --principal 5 --repayment 6 --collateral-erg 10 --term 21600 --yield 10 --margin -2
create-order --market SigUSD --principal 5 --repayment 6 --collateral-erg 10 --duration 21600 --yield 10
sell <bond-or-share-id> --amount 1 --yield 10 --margin -2
sell <bond-or-share-id> --amount 1 --yield 10 --price 5 --min-cover 160 --oracle-age 60
```

Amounts are in the chosen market's raw-display unit (ERG or SigUSD). The usual fixed-maturity grid
and `--exact-maturity` apply. `orders` shows the quoted floor and resolved current principal;
`asks` identifies NAV/yield policies and their current price. Builders price three blocks ahead to
survive inclusion delays; late transactions require rebuilding. There is no single-height exact
equality on the formula. The bond's start still uses the existing eight-block validation window.

Wallet fills, offer matches, metadata and batch ranking all use resolved actual principal.
Readers verify either accepted issuing tree before trusting the asset. Unknown expression hashes
are refused for fills/buys and skipped by matching rather than treated as NAV. Their owners retain
cancel/refund exits. After an expression change, regenerate pins/reference with:

```text
sbt "Test / runMain org.lithosprotocol.sigmafi.specs.bonds.ZcStandardReference --write"
sbt test
```

## Live-test coverage

The existing `sbt livetest` tracks now exercise these paths without adding funding requirements:

| Track | Pricing exercised |
|---|---|
| A | Fixed-maturity NAV order, +1% margin; yield-priced shares, -5% margin, partial wallet fill, repayment and claims |
| B | On-close NAV order, +1% margin; recollateralization and repayment from collateral |
| D/E | Fixed order D remains; E is a guarded fixed-maturity yield order, -10% margin, filled by an offer; NAV unit resale and guarded yield-priced Dutch shares at -25% margin |
| G | On-close yield order, -10% margin, matched by the batch runner with delivery into fractional shares |
| K | NAV-priced expiry refund and a SigUSD yield-priced order cancellation/refused guarded fill |

The live confirmation checks compare actual principal payments (excluding expiry refunds) with the
bond records and preserve checks of non-price terms. Ask checks verify the committed policy and
margin. Checks read the step's historical outputs even when later steps spend or rebind them.
Yield-price discounts in E/G leave room for batcher fees on short, small trades; they are explicit
live-test choices, not client defaults. `LivePricingSpec` executes the actual plan commands offline,
but the updated plan still requires a user-run node/wallet session for live confirmation evidence.
