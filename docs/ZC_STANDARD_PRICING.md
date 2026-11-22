# ZC Standard pricing

Updated 2026-11-16. Pricing is **fixed** or **yield**, nothing else: NAV (accrued-value) pricing and
price margins were removed on that date. A yield can move in a straight line between two points in
time, so an order or ask can rise or fall without updates, and a lend offer bids a yield **curve**
across maturities. The interpreter and client specs validate these paths offline; none of the new
paths has been broadcast or tested against a live node. Current trees, hashes and addresses are
generated in [ZC_STANDARD_REFERENCE.md](./ZC_STANDARD_REFERENCE.md).

## Contracts and policies

| Contract | What is fixed | What is priced |
|---|---|---|
| `Order` | Principal, repayment and every borrower term | Nothing: fixed principal |
| `PricedOrder` + `YieldOrder_Pricing` | Repayment, maturity rule, collateral, borrower, liquidation rule, fees | Minimum principal at fill, from a two-point yield |
| `PricedOrder` (mode 2) + `CurveOrder_Pricing` | Repayment, maturity window, collateral, borrower, liquidation rule, fees | Minimum principal at the maturity the lender picks, from the borrower's yield curve |
| `SaleOrder` | Price per raw token unit | Nothing: fixed price |
| `PricedAsk` + `YieldAsk_Pricing` (or `ERG_SigUSD_GuardedYield_Pricing`) | Asset, seller, floor, minimum fill and expiry | Minimum payment for the units sold, from a two-point yield |
| `LendOffer` | Yield curve, minimum yield, maturity window, collateral policy and fees | Maximum cost allowed by its yield test |
| `LotAuction` | Seller, currency, refund fee | A basket of ERG and tokens at a falling absolute price (not a bond price; see the last section) |

An expression is a validator returning `SigmaProp`, not a function returning a price. The filler
supplies bytes matching the expression hash the owner committed when posting. Adding another pricing
policy needs no new shell. The `PricedOrder` and `PricedAsk` shells did not change when NAV was
removed. `PricedOrder` changed later the same day, when it gained the window mode (and its bond hash
changed with the bond's refresh path); the lend offer pins its new hash.

The fixed order remains an accepted issuer. The lend offer pins **both** issuing hashes, for primary
fills and secondary origin checks. Existing boxes keep their original scripts and hashes; changing
source upgrades nothing on chain. A box committed to a removed NAV expression can still be cancelled
or refunded, since those paths read no pricing.

## Principal resolution

`PricedOrder` has the fixed order's collateral, expiry, cancellation, three-unit mint, output positions
and maturity checks. The resulting bond records the **actual principal**, not the initial quote:

- ERG: bond R6(1) equals borrower payout nanoERG minus the refunded expiry fee; bond R7 is empty.
- Token: bond R7 is exactly the repayment token and actual principal amount; payout tokens match it
  exactly. The nanoERG principal component remains the committed box funding, at most 1000000.
- Both issuing orders require payout R4 `Coll[Byte]` to equal the issuing order's box id. This prevents
  a refund, collateral return or sale payout owed by another input from also counting as principal.
- Bond R6(0), R6(2..) and the liquidation expression are unchanged. Repayment never reprices.
- The quoted principal is always a floor. Paying above the expression's minimum is permitted only
  when the actual principal is recorded consistently. This gives a buyer room for block inclusion.

The shell validates these rules before executing pricing. A permissive pricing expression cannot
weaken them. Collateral guards execute separately, so pricing cannot turn one off.

### PricedOrder registers and context

| Register | Meaning |
|---|---|
| R4 `Coll[Long]` | Fixed order borrower terms (7..64); principal nanoERG is a minimum |
| R5 `Coll[(Coll[Byte], Long)]` | Minimum principal tokens; empty for ERG, one repayment token for a token loan |
| R6 `Coll[Byte]` | Borrower script hash |
| R7 `Coll[Byte]` | Liquidation expression hash |
| R8 `Coll[Coll[Byte]]` | Exactly `(pricing hash, optional guard hash)`; pricing is 32 bytes, guard empty or 32 |
| R9 `Coll[Long]` | Pricing payload; for `YieldOrder_Pricing` exactly `(start height, start yield, end height, end yield)` |

Input 0 is the issuing order. Output 0 is the circulating unit, output 1 the standard bond, output 2
the principal and expiry refund. On fills, context var 1 carries the optional fill guard; var 2
carries mandatory pricing. Both run with the **order** as `SELF`. On expiry var 0 selects the refund
output. Refund/cancel do not read R8/R9, so even malformed or unknown policies can be exited.

## Two-point yield pricing

A yield is a simple return in basis points per 262800 blocks (one year of 2-minute blocks), the
lend offer's own convention. Each priced order or ask commits two points in time:

```text
(start height h0, start yield y0, end height h1, end yield y1),   y0, y1 >= 0,   h1 > h0
```

The yield is y0 up to h0, y1 from h1 on, and on the straight line between them in between. Equal
yields give one fixed rate. Clients put h0 at the current height and h1 at the box's expiry, so the
yield reaches its endpoint as the order or ask is about to expire. With

```text
B  = 10000 * 262800
L  = h1 - h0
at = HEIGHT clamped to [h0, h1]
Y  = y0 * L + (y1 - y0) * (at - h0)          (the yield at HEIGHT is exactly Y / L)
D  = maturity - HEIGHT
F  = R for a whole bond; R * units / N for shares
```

the minimum principal or payment satisfies

```text
paid * (B * L + Y * D) >= F * B * L          i.e.  paid >= F / (1 + yield * D / 262800)
```

Products are widened to BigInt; there is no on-chain division, so the boundary is exact to one raw
unit. The fixed principal or ask floor still binds. Fees are not included.

- **Rising yield**: the price falls while the box waits. A borrower who wants 8% now but would accept
  12% by expiry posts `--yield 8 --end-yield 12`.
- **Falling yield**: the price rises. A seller who opens cheap and firms up posts the reverse.
- **Fixed**: `--yield` alone.

`YieldOrder_Pricing` reads R9 as above. `YieldAsk_Pricing` reads ask R5: `(floor numerator, floor
denominator, minimum fill, expiry, N, h0, y0, h1, y1)`, with R7 the bond asset id and context var 3
the live bond's data-input index. The ask's payout and continuation conventions are unchanged. Note
that because `YieldAsk_Pricing` (and `ERG_SigUSD_GuardedYield_Pricing`) requires the live bond in
`CONTEXT.dataInputs`, a borrower buying their own bond unit off a `PricedAsk` to run `Bond`'s
Extinguish path (which spends the bond in `INPUTS`) should not assume single-tx composition without
live-node/wallet verification of `INPUTS`/`dataInputs` overlap; a two-transaction buy-then-extinguish
still avoids sourcing full face-value repayment.

Both maturity modes work for orders. Fixed: D shrinks while the order waits. On-close: D is the
committed duration whenever the fill lands, so only the yield's own movement changes the quote.

This is simple return over **blocks**, not a wall-clock promise or compounding. A quoted yield is
conditional on full repayment at maturity. It is not a market rate, collateral valuation, guarantee
of recovery, or the selling holder's historical return.

### Why margins were removed

A margin multiplied the yield price by `(10000 + margin) / 10000`. On the yield axis that is just a
different yield: a seller wanting a discount quotes a higher yield, and a borrower leaving room for a
lend offer's batcher fee quotes a little above the offer's yield. Removing it leaves one number to
compare on both sides of the book.

## Collateral guards

`ERG_SigUSD_GuardedYield_Pricing` adds a minimum cover at a fresh oracle to the same yield price. Ask
R5(9..10) is `(minimum cover bps, maximum oracle age)`. It values collateral against the fixed
repayment, excluding the lender reserve, exactly as the oracle liquidation rule does. Order guards
use the independent R8 second hash and the existing `ERG_SigUSD_FillGuard` expression.

A guarded fixed price, formerly a NAV ask at margin -9999, is now a guarded yield ask with a yield of
10^15 bps: its yield price is next to nothing, so the floor, the fixed price, is what a buyer pays,
and the guard still holds the sale to its cover. The CLI does this for `sell --price ... --min-cover`.

## The lend offer's yield curve

`LendOffer` R5 holds its fixed terms (11 for ERG, 15 for a token offer) and then 0 to 20 points of
`(yield bps, blocks left to maturity)`, blocks strictly increasing. For a bond with D blocks left:

- Between two neighbouring points the required yield is on the straight line joining them.
- Below the first point it is the first point's yield; above the last, the last's.
- It is never below R5(0), the offer's minimum yield. With no points, R5(0) is the one yield.
- R5(1) and R5(2) still bound which maturities are bought at all.

On a segment from (y1, D1) to (y2, D2), L = D2 - D1 and Y = y1 * L + (y2 - y1) * (D - D1), and the
test is `R * B * L >= cost * (B * L + Y * D)`, again exact. For u of N shares both sides scale by u
and N. Because the axis is blocks left, the curve rolls with time: a resting offer keeps bidding the
same rate for a 30-day bond whatever day it is. A lender wanting a compounded curve states each
maturity's simple equivalent.

The minimum yield is a hard floor (a mistyped low point cannot undercut it) and a key indexers can
filter offers by without parsing the curve. A badly made curve can only pay too much of the lender's
own capital; points out of order are refused so every batcher reads a curve the same way.

`create-offer --yield 5 --curve 6:21600,8:131400,10:262800` bids at least 6% for a 30-day bond, 8% for
six months and 10% for a year, interpolated in between, and never under 5%.

## Window orders: the borrower's curve

A window order (`PricedOrder` maturity mode 2) leaves the maturity to the lender: any duration from
R4(3), at least 30 blocks, to R9(0) after the fill's recorded start. The shell checks the window on
`maturity - start`, so no sum can overflow, and the bond records the maturity chosen in R9(2) as for
every mode. `CurveOrder_Pricing` prices whatever the lender picks:

```text
R9 = (maximum duration, maximum yield, then 0..20 points (yield bps, blocks left))
```

It is the lend offer's curve read the other way round. On the offer each point is the least yield the
lender accepts and R5(0) is a floor; here each point is the most the borrower pays and R9(1) is a
ceiling. With D = maturity - HEIGHT, the yield is the curve's at D, capped at the maximum, exactly as
`Y / L` with the same segment arithmetic, and the minimum principal is

```text
principal * (B * L + Y * D) >= R * B * L          i.e.  principal >= R / (1 + yield * D / 262800)
```

Points out of order or negative yields never fill. Repayment stays fixed, so a longer maturity pays
less principal now; the principal floor R4(1) still binds at every maturity, which is the borrower's
guaranteed minimum cash. `YieldOrder_Pricing` refuses mode 2, since the shell would read its start
height as the window's maximum.

Why fixed repayment: it keeps the "price principal only" rule, the collateral ratio is known when the
order is posted, and lend offers already judge it on the same axis. A borrower thinking in cash sets
the principal floor to what they need and quotes the most they pay at each term.

### Which maturity a batcher picks

Nothing on chain says; zcs1 does, so every batcher picks the same one. For a window order and an
offer, the candidates are the ends of the heights both accept (the window, and the offer's minimum and
maximum blocks left), both curves' breakpoints and the grid heights between them: between those, both
yields are straight lines, so the best maturity is always one of them. Of the candidates the offer
accepts (its yield, price, budget and policy, as for any fill):

1. a grid height (a multiple of 21,600) first, so bonds gather on series maturities;
2. then the widest gap between the yield the offer would earn at that price and the yield it requires,
   the most room for the inclusion height to move;
3. then the shorter maturity.

A wallet lender picks freely; the client's default is the earliest grid height in the window, else the
window's shortest maturity. A window fill is one transaction like any other, so it adds no batching
step and no fee.

```text
create-order --principal 4 --repayment 6 --collateral-erg 10 --window 21600:131400 --yield 12 --curve 8:21600,12:131400
fill-order <order> --maturity <height>
match-offer <offer> --order <order> [--maturity <height>]
```

## Fee treatment

Borrower/seller yield is an upper limit on the gross yield they offer a buyer (a minimum price).
Lender yield is a lower limit on the return it accepts (a maximum cost). Ignoring fees and rounding,
an ask offering 10% can cross a bid requiring 8%; one offering 6% cannot. For an ERG offer its cost
includes the batcher fee: `cost = price + batcherFee`. A token offer pays fees separately in ERG,
with a reward proportional to original capital filled, a proportional total ERG spending cap, and a
minimum 1% fill of original capital. See [the fee-budget rules](./AUDIT_REVIEW_2026-11-14.md#token-rewards-and-total-erg-protection).
Equal rates may fail to match: short durations or small share fills may earn so little interest
that the batcher fee exceeds it. On a 60-block loan, leaving room for a 0.005 ERG fee on a 0.12 ERG
repayment takes a yield in the tens of thousands of percent.

## Choosing pricing and maturity

APR can be an input calculator as well as an on-chain policy. Given principal P, a simple APR y as a
decimal and D remaining blocks, a borrower-facing form calculates `R = P * (1 + y * D / 262800)`,
rounds R up to a raw unit and commits it. Repayment is fixed when the order is posted; the actual
principal paid at fill is recorded in the bond.

| Loan timing | Pricing | Effect of waiting for funding |
|---|---|---|
| Term from funding | Fixed principal | Principal, repayment and duration stay constant, so APR stays constant. |
| Term from funding | Yield | The duration stays constant; only a moving yield changes the quote. |
| Fixed maturity height | Fixed principal | Remaining time shrinks, so the borrowing APR rises. Contract-valid, but a creation UI should avoid it. |
| Fixed maturity height | Yield | Principal follows the fixed debt at the stated yield as time shrinks. |

A moving yield adds a second dimension to either yield row: the borrower's acceptable rate itself
changes while the order waits. With a fixed maturity, principal, repayment and APR cannot all stay
constant as funding time changes; yield pricing adjusts principal to the fixed debt.

For illustration, a bond repays 110 after 262800 blocks. Halfway to maturity, ignoring floors, fees
and raw-unit rounding, a 10% yield prices it at 110 / 1.05 = 104.7619..., and a fixed 100 is a 20%
gross APR at entry.

### Resale asks and lender offers

An existing bond already has fixed repayment and maturity. A resale ask cannot restart the duration
or change the debt; its seller chooses fixed or yield pricing regardless of how it was issued. Whole
bonds and shares use the same proportional mathematics; share rounding and per-fill fees can change
small-lot returns materially.

A lend offer's curve and minimum are the lower limit on the return it accepts. Rate overlap alone
does not establish an executable match: maturity windows, budgets, collateral, fees and rounding
decide eligibility. Floors can hide dynamic pricing: while the yield quote is below a floor, the
floor sets the price. A zero yield prices at repayment value regardless of the remaining term.

## Building, matching and examples

```text
create-order --principal 5 --repayment 6 --collateral-erg 10 --term 21600 --yield 10
create-order --principal 5 --repayment 6 --collateral-erg 10 --duration 21600 --yield 8 --end-yield 12
create-order --market SigUSD --principal 5 --repayment 6 --collateral-erg 10 --duration 21600 --yield 10
sell <bond-or-share-id> --amount 1 --yield 10 --end-yield 6 --end-in 720
sell <bond-or-share-id> --amount 1 --yield 10 --price 5 --min-cover 160 --oracle-age 60
create-offer --capital 20 --yield 5 --curve 6:21600,8:131400,10:262800 --collateral-floor 150
```

`--yield` and `--end-yield` are percentages with up to two decimals per 262800 blocks; zero is
permitted. The end defaults to the box's expiry; `--end-height` or `--end-in` sets it. `--principal`
is still required for a priced order, as its floor. With a yield ask, `--price` is an optional floor
for the entire offered amount. `--nav` and `--margin` no longer exist.

`orders` shows the floor, the yield now and where it is heading, and the current fill principal;
`asks` does the same for asks; `offers` prints each curve. A two-point yield can move the price either
way between signing and inclusion, so builders take the most the next three blocks ask. Wallet fills,
offer matches, metadata and batch ranking all use the resolved actual principal. Unknown expression
hashes are refused for fills/buys and skipped by matching; their owners keep cancel/refund exits.
After an expression change, regenerate pins/reference with:

```text
sbt "Test / runMain org.lithosprotocol.sigmafi.specs.bonds.ZcStandardReference --write"
sbt test
```

## Lot auctions are not bond pricing

`ZC_Standard_LotAuction` sells a basket of tokens (and, for a token currency, ERG) whole to one buyer
at an absolute price falling from an opening to a floor and then held until expiry. It has no
maturity or repayment, so no yield applies; a client may set its prices from an oracle. It exists
so a holder paid collateral in kind can turn it back into the repayment asset. See
[SigmaBonds.MD](../SigmaBonds.MD) and [the off-chain rules](./ZC_STANDARD_OFFCHAIN.md).

## Live-test coverage

| Track | Pricing exercised |
|---|---|
| A | Fixed-maturity order at a yield falling from 50% to 20%; Selective shares sold at a yield rising from 5% to 10%, partial wallet fill, repayment and claims |
| B | On-close order at a 50% yield; recollateralization and repayment from collateral |
| D/E | A curve offer fills a guarded order whose yield rises from 100000% to 120000%, buys the unit back from an ask falling from 100000% to 80000%, and buys guarded Dutch shares at 400000%; bond D's Dutch vault already holds its floor, so its thin lot sells in an excess auction (state 4) |
| G | On-close order at 50000%, matched by the batch runner with delivery into Selective shares; final claim un-fractionalizes the unit and `extinguish` closes the live bond directly |
| K | A rising-yield order refunded at expiry, and a SigUSD yield order cancelled after a refused guarded fill |
| L | Lot auctions: one bought, one refunded after expiry, one cancelled |
| V | Window orders priced by a curve: one filled from the wallet at a chosen maturity about 600 blocks out (5000% to 8000%), one matched by a 5% lend offer at zcs1's default maturity (50000% to 80000%); a moving yield on a window order and a maturity outside the window are refused |
| W | No pricing: wraps, and a refresh refused as too early |

The live loans last tens to hundreds of blocks, so yields that leave room for batcher fees are in the
thousands of percent. Confirmation checks compare actual principal payments with the bond records,
preserve checks of non-price terms, and verify each ask's committed yield points. `LivePricingSpec`
executes the actual plan commands offline; the updated plan still needs a user-run node session for
live evidence.
