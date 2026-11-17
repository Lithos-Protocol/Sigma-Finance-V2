# SigmaBonds: the ZC Standard

The ZC Standard is SigmaFi V2's contract set for P2P zero-coupon bonds on Ergo. A borrower posts an
order with collateral; a lender fills it and receives a tradeable bond asset; the bond is repaid or
liquidated into a vault, and whoever holds the asset claims the vault. Around that core sit
fractional shares with a Selective or Dutch auction for liquidated collateral, asks for selling bonds
and shares at a fixed price or a moving yield, a lot auction for selling collateral paid in kind,
a lend offer that buys on a yield curve, window orders whose lender picks the maturity, custody wraps
for capability tokens, and a paid refresh that keeps long-held boxes ahead of storage rent.

Status: the set is **not deployed**. Every contract has offline specs (see [TESTING.md](./TESTING.md)),
and its compiled trees are pinned in [docs/ZC_STANDARD_REFERENCE.md](./docs/ZC_STANDARD_REFERENCE.md).
The CLI builds every contract from those pinned trees: the bond lifecycle (orders, fills, repayment
also from collateral, recollateralization, liquidation, expiry refunds, claims), fractional and Dutch
auction shares, both asks and the lend offer, window orders, wraps, refreshes, and a `batch` scan that
lists, and with `--run` performs, every mint, pull, refund, liquidation, refresh and offer match that
pays its runner. It follows the off-chain rules below. The CLI's `--old` commands still serve the
first-generation contracts in `contracts/old/`, described in [docs/SIGMABONDS_OLD.md](./docs/SIGMABONDS_OLD.md).

Each contract's header comment is its authoritative schema: registers, context variables and every
condition. This document explains how the pieces fit together.

## Layout

All paths are under `contracts/bonds/`. Names drop the `ZC_Standard_` prefix.

| Folder | Contract | Role |
|---|---|---|
| `core/` | `Bond` | The live bond: holds the collateral, repays or liquidates into the vault |
| `core/` | `BondVault` | Holds a settlement; the asset holder claims it |
| `P2P/` | `Order`, `PricedOrder` | A borrower's request for a loan; the fill mints the bond asset |
| `P2P/` | `SaleOrder` | A fixed-price ask for any token |
| `P2P/` | `PricedAsk` | An ask priced by an expression the seller picks |
| `P2P/` | `LendOffer` | A lender's yield-curve bid: fills orders and buys from asks |
| `P2P/` | `LotAuction` | A Dutch-priced ask for a whole basket of tokens and ERG |
| `lender-scripts/` | `FractionalMint`, `FractionalVault` | Turn one bond unit into N fungible shares, paid in kind |
| `lender-scripts/` | `DutchAuctionMint`, `DutchAuctionVault` | The same, with a Selective or Dutch auction of liquidated collateral; the only shares lend offers buy |
| `wrappers/` | `WrapMint`, `WrapVault` | Custody for a capability token (a LithosDex position's owner NFT): released only by burning its receipt |
| `liquidations/` | `MaturityLiquidation` | Liquidation rule: at or after maturity |
| `liquidations/` | `ERG_SigUSD_Liquidation` | Liquidation rule: an oracle collateral ratio, or maturity |
| `policies/` | `ERG_SigUSD_CollateralPolicy` | The lend offer's risk rule |
| `policies/` | `YieldAsk_Pricing` | A priced ask's two-point simple block-yield rule |
| `policies/` | `YieldOrder_Pricing` | A priced order's two-point principal rule |
| `policies/` | `CurveOrder_Pricing` | A window order's principal rule: the borrower's yield curve at the maturity the lender picks |
| `policies/` | `ERG_SigUSD_GuardedYield_Pricing` | Yield pricing with a sale collateral guard |
| `policies/` | `ERG_SigUSD_FillGuard` | The borrower's guard: an order fills only at a minimum collateral ratio |

Expressions in `liquidations/` and `policies/` are not box scripts. A host box (the bond, the
offer, the ask) commits to an expression's hash in a register, and a spender supplies the
serialized expression as a context variable, which the host runs with `executeFromVar`. New rules
are new expressions; the host contracts do not change.

## Design principles

**Core and edge.** The bond and its vault are the core: every contract that verifies a bond pins
their script hashes, so changing them starts a new generation of bonds that existing contracts
cannot read. Everything else is edge. Orders, offers, asks and mints hold funds only while open, and
they meet through conventions rather than each other's script hashes, so new ones can be added
without migrating anything.

**The 3-unit rule.** A fill mints exactly 3 units of the bond asset: 2 locked in the bond and 1
circulating. The 2 units never split. They move from the bond to the vault at settlement and are
burned only when the holder claims. The holder of the circulating unit can never assemble 2. So
for a genuine asset X, *the box holding 2 units of X is the bond's state box*: the live bond before
settlement, the vault after. That is one lookup by token id off-chain and one data-input check
on-chain, with no ancestry walk.

**One asset per loan.** A standard loan is lent and repaid in the same single asset, the bond's
repayment asset. Every bond therefore has its principal P and repayment R in one unit, so rate,
accrued value and NAV are defined for every bond, and readers need no asset filter.

**Constants for markets, registers for terms.** The SigmaFi API is the Ergo indexed node, which
looks boxes up by ErgoTree, template hash and token id but not by register. The repayment asset is
compiled in as a constant, so each market (ERG, SigUSD, ...) has one address, and every market of
a contract shares one template hash. Per-loan terms are registers, so all orders of a market share
that one address and other contracts can read the terms. Comparisons against the asset constant
use a runtime value (`CONST == SELF.id.slice(0, 0)`), because a constant-only test is folded at
compile time and would split the template.

**Nobody is assumed online.** After posting, a user is never needed again. Every step a third
party can run (a mint, a pull, a liquidation, a refund) carries a fee the user committed up front,
with a 0.002 ERG minimum where the contract sets one. Every box that waits on a user also has an
exit a batcher can run without the user's signature (see "Exits").

## The bond's life

### Order

The borrower funds an `Order` box with the collateral (nanoERG and any tokens) plus an expiry fee.

| Register | Contents |
|---|---|
| R4 `Coll[Long]` | Borrower terms, 7 to 64 values: 0 repayment, 1 principal nanoERG, 2 repayment ERG floor, 3 maturity height (mode 0) or duration of at least 30 blocks (mode 1), 4 order expiry height, 5 expiry fee (at least 0.002 ERG), 6 maturity mode, 7 and up a payload read only by the liquidation rule |
| R5 `Coll[(Coll[Byte], Long)]` | Principal tokens |
| R6 `Coll[Byte]` | Hash of the borrower's script |
| R7 `Coll[Byte]` | Hash of the serialized liquidation expression |
| R8 `Coll[Byte]`, optional | Hash of a serialized fill guard expression; not copied to the bond (see "Fill and sale guards") |

Constants: `CONST_BOND_HASH` (the bond for this market) and `CONST_REPAYMENT_TOKEN` (empty for ERG).

The one-asset rule, checked on a fill: an ERG order has no principal tokens, a principal above 0,
and a floor no higher than the repayment. A token order has exactly one principal token, the
repayment token, with a positive amount, and at most 1000000 nanoERG in both R4(1) and R4(2).

**Fill.** The order must be `INPUTS(0)`, so the bond asset's id is the order's box id. No context
variables, except var 1 carrying the fill guard when R8 names one; the guard then runs too. Exactly 3
units are minted.

| Output | Contents |
|---|---|
| `OUTPUTS(0)` | The circulating unit, to any script the lender chooses: a wallet, a lend offer's owner, or a mint request. Its R4 to R6 are the asset's EIP-4 name, description and decimals ("0") |
| `OUTPUTS(1)` | The bond: `(asset, 2)` then the order's tokens; value = collateral plus the lender's liquidation fee reserve |
| `OUTPUTS(2)` | The principal plus the unused expiry fee, to the borrower |

The bond records the start height, which must lie within 8 blocks of the fill's `HEIGHT` (a script
cannot pin `HEIGHT` exactly), and the maturity: the fixed height in mode 0, or start plus duration
in mode 1. Maturity must leave at least one full block to repay. The bond box's creation height
must equal the start height, because the bond counts its recollateralize wait from it; a lender
cannot stamp a later one to lock the borrower out.

**Cancel and expire.** The borrower cancels at any height by co-spending a box under their script.
From the expiry height on, anyone may return the order, tagged with its id, to the borrower and keep
the expiry fee.

### Priced order

`PricedOrder` is an additional issuer, with the same collateral, lifecycle, three-unit mint and
output positions as `Order`. Repayment and all non-price terms remain fixed. Its expression prices
**principal only**; the bond records the actual payment, excluding the returned expiry fee.
An ERG payment minus that fee equals bond R6(1), and its tokens equal bond R7 exactly.
For a token loan only the principal token amount may change; the nanoERG beside it stays fixed.

R4-R7 follow `Order`, except principal amounts are floors. R8 is a
`Coll[Coll[Byte]]` of exactly `(pricing expression hash, fill guard hash or empty bytes)`.
R9 is a `Coll[Long]` payload of at most 64 values. Fill context var 2 supplies mandatory pricing,
var 1 the optional collateral guard. They run separately with the order as `SELF`.
Pricing cannot change repayment, collateral, maturity rules, borrower, liquidation expression or fees.
Cancel and expiry need neither expression, even if pricing data are malformed.

`YieldOrder_Pricing` uses R9 `(start height, start yield, end height, end yield)`, yields in bps per
262800 blocks. The yield moves in a straight line between the two heights and holds outside them, so
an order can rise or fall without updates. It calculates principal from the fixed repayment and
remaining blocks, exactly, and supports fixed and on-close maturity. The original principal floor
always applies. See [pricing rules and examples](docs/ZC_STANDARD_PRICING.md).

**Window orders** (maturity mode 2) let the lender pick the maturity: any duration from R4(3), at
least 30 blocks, to R9(0) after the fill's start. The shell enforces the window; the bond records the
maturity chosen in R9(2), like any other. `CurveOrder_Pricing` prices them: R9 is
`(maximum duration, maximum yield, then up to 20 (yield bps, blocks left) points)`, the lend offer's
curve read the other way round, a ceiling rather than a floor. Repayment stays fixed, so a longer
maturity pays the borrower less principal now; the principal floor still guarantees a minimum.
`YieldOrder_Pricing` refuses mode 2, since the shell would read its start height as the maximum. One
order, and one pot of collateral, then meets lend offers at many maturities instead of one. Nothing on
chain says which maturity a batcher picks; zcs1 does (see the off-chain rules below).

### Bond

| Register | Contents |
|---|---|
| R4 | Originating order id, which is also the asset id |
| R5 | Borrower script hash |
| R6 | Borrower terms; copied exactly from `Order`, or with actual principal resolved by `PricedOrder` |
| R7 | Principal tokens |
| R8 | Liquidation expression hash |
| R9 `Coll[Long]` | Lender values: (liquidation fee reserve, start height, maturity height) |

Constants: `CONST_VAULT_HASH` and `CONST_REPAYMENT_TOKEN`. The bond has three paths, chosen by
context variables:

1. **Repayment** (variables 1 and 2), anyone, before maturity. The settlement pays the vault with
   the bond's `(asset, 2)` first, tagged R4 = bond id and R5 = `0` (repaid), holding the repayment
   plus the lender's reserve back. The collateral returns to the borrower, tagged with the bond id:
   in full, unless the borrower co-spends. With a borrower co-spend the return may hold up to the
   repayment less of the repayment asset (nanoERG for an ERG bond, the token for a token bond), so
   the borrower **repays from collateral** and brings only the shortfall. The vault is paid the
   same either way, and every other collateral token returns exactly. Without this, a borrower
   whose spare funds sit in the bond would have to find the repayment elsewhere or lose all of it
   to the maturity liquidation.
2. **Liquidation** (variables 0 and 1), anyone. Variable 0 is the expression R8 names, and it
   decides eligibility and what the vault receives. The bond still pins the vault script, the
   reserve at `tokens(0)`, the R4 tag and R5 = `1` (liquidated). The lender's reserve pays whoever
   runs it.
3. **Recollateralize** (variable 3), the borrower only, before maturity. Same script, registers
   and tokens, nothing lower, and more of something: ERG, a held token, or, for a token bond that
   holds none, its repayment token appended last. No other new token, since a token nobody prices
   adds nothing. An open path would let anyone respend the bond and void pending repayments.
   **At most once per 60 blocks**, counted from the box's creation height: the order sets the
   first bond's to its start height, and each successor must stamp one within 15 blocks of
   inclusion. Moves therefore land at least 45 blocks apart, and a bond maturing within 60 blocks
   of its start can never be recollateralized. The wait is what stops a borrower dodging a
   liquidation: moving the bond voids the liquidator's transaction, but the liquidator rebuilds
   against the new box, which the borrower cannot move again in time. (It replaced a 1% growth
   floor, which was cheap to meet whenever one collateral holding was small.)
4. **Refresh** (variable 9), anyone, once the box is more than 525,600 blocks old. See "Storage
   rent and refresh" below. A bond's refresh stamps its successor 60 to 999 blocks back, so the
   borrower may recollateralize at once, and is not allowed in the 720 blocks before maturity, where
   voiding a pending repayment could push the borrower into liquidation.

### Vault and claim

`BondVault` holds `(asset, 2)` and the settlement. The holder claims by spending the vault with their
circulating unit and burning all 3 units; the outputs are theirs to choose. One transaction can sweep
many vaults, each by its own unit, and a contract (such as a fractional vault) can direct a claim
into itself. Anyone may refresh a vault more than two years old (variable 9); its R4 keeps naming the
bond box that settled.

### Liquidation rules

| Expression | Fires | Notes |
|---|---|---|
| `MaturityLiquidation` | At or after maturity | Everything to the vault |
| `ERG_SigUSD_Liquidation` | At or after maturity, or before it when collateral is strictly below the borrower's ratio R6(7) on a USD oracle no older than R6(8) blocks | Measures collateral against the **repayment**, not the principal; compiled per repayment asset (ERG or SigUSD) |

Both parties must inspect the expression: its hash is a commitment, not a proof that it is fair.

## Verifying a bond asset

A token X is a genuine standard bond if two things hold:

1. **Origin.** The box whose id is X was spent as an accepted `Order` or `PricedOrder`. Off-chain, fetch the box by id
   from the indexed node and compare its script. On-chain, a contract takes the serialized box as a
   context variable and checks `blake2b256(bytes) == X` and that `Global.deserializeTo[Box](bytes)`
   carries the order script. Because that box minted X under the order's rules, the supply is 3
   with 2 locked.
2. **State.** The box holding 2 units of X is the state box. Under the bond script it is live, and
   its registers are the terms; under the vault script it has settled.

A hand-made box under the bond script proves nothing by itself: always check the origin.

Shares are verified one link further out. A share token Y is genuine if the box whose id is Y was
spent under a standard mint script. That box held the bond unit X, and its R8 gives the share count
N (and, for the Dutch mint, the auction terms). Then X is verified as above.

## Fractional shares

`FractionalMint` is a request box holding one bond unit. It commits to everything a mint needs in
R7 to R9: the owner script hash, `(N, pull fee, mint fee)`, and the share token's EIP-4 metadata.
Any batcher runs the mint for the mint fee, with the request as `INPUTS(0)`, so the share id is the
request's box id. A fill can deliver its unit straight into a request.

The mint creates 2N+1 shares: N to the owner, and N+1 locked in a `FractionalVault` with the bond
unit. As with the bond, the box holding N+1 shares is the shares' state box.

`FractionalVault` paths:

- **Pull** (anyone, for the pull fee): spends the settled bond vault with this vault, burns all 3 bond
  units, and takes the settlement in.
- **Partial claim**: burn u shares and take `floor(balance * u / outstanding)` of the nanoERG and of
  each token. Small claims can round an asset to zero; the dust stays for later claimants.
- **Final claim**: burn every outstanding share and the reserve, and take everything. Before the pull,
  holding all shares returns the bond unit itself.

Share count N is free and decimals are display only. The off-chain rules set N = 100,000,000 with 6
decimals, 100 shares in all (see "Off-chain rules").

## Selective and Dutch auction shares

`DutchAuctionMint` is a fractional mint with five more terms in R8: opening and floor prices in basis
points of the bond's repayment, an auction length and an expiry in blocks from the auction's start,
and an **in-kind threshold** in raw units, at least 100. The mint reads the repayment from the live
bond as a data input. If the bond has settled while the request waited, it reads the bond from its
serialized bytes (context variable 1), proved by hashing to the settlement's R4. So a batcher can
always run the mint.

The threshold names two settings of the one contract pair:

- **Selective** (threshold 100, the CLI's default): a liquidated bond's collateral is auctioned only
  if some item holds fewer than 100 raw units, such as an NFT or a token too thin to split pro rata.
  Collateral that splits is paid in kind.
- **Dutch** (threshold `Long` max): liquidated collateral is always auctioned.

The decision is made at the pull, on what the settlement actually holds. The bond allows no new
collateral token but its repayment token (the auction's currency, which the test leaves out) and
never lowers an amount, so a borrower cannot flip a vault either way after the fill. The plain
`FractionalMint` still works, but pays any collateral in kind, so lend offers no longer buy its shares.

`DutchAuctionVault` pays out exactly as the fractional vault when the bond was repaid. When it was
liquidated, the pull opens an auction that sells the collateral for the bond's repayment asset:

- The auction sets a **target** for the currency the vault must hold after a sale: the repayment
  times a basis-point figure falling in a straight line from the opening to the floor over the
  length, then staying at the floor. A buyer brings the vault's currency up to the target and takes
  the rest of the collateral. Currency already in the vault counts towards the target.
- An auction opens only if some lot item is under the threshold. Otherwise holders take the
  collateral in kind.
- If the vault holds less currency than the floor target, it is the floor auction (state 1). The lot
  stays on sale at the floor from the end of the fall until the **expiry**. Partial claims wait for the
  expiry; a claim after that takes the unsold collateral in kind and closes the auction.
- If the vault already holds the floor target, it is the **excess auction** (state 4). Only the thin
  items are sold: the lot tokens under the threshold, and for a token currency the nanoERG if that is
  under it too. They are surplus that pro rata claims would round away, leaving them all to the last
  claimant: an NFT, a wrapped LithosDex receipt. Every other item stays in the vault and splits pro
  rata, so one thin item (a dust token a borrower put in the collateral, say) cannot put the rest of
  the collateral up at a falling price. With the Dutch threshold every item is thin, so the whole lot
  is sold. Nothing values the thin items, so the price is not measured against the currency held: it
  falls from `opening × repayment` to zero over the length, and a sale must pay more than zero. When
  it reaches zero, claims open and take everything in kind. An opening of 0 opens no auction.
- Which auction opens is fixed at the pull and recorded in R6, so nothing later (a refresh's bounty
  out of an ERG currency) can move an auction from one case to the other. The final claim works in
  every state.

The guarantee to holders: after a liquidation the vault holds at least `floor × repayment` in the
repayment asset, unless every collateral item splits pro rata (Selective), or a floor auction reaches
its expiry with no buyer. A thin item now goes in kind only when nobody pays anything for it in an
excess auction, or a floor auction expires unsold; both are unavoidable without a price reference,
and the floor is the lenders' own reserve, which offers rely on.

The vault's state is R6: 0 repaid, 1 floor auction open, 2 sold or nothing to sell, 3 paid out in
kind, 4 excess auction open. Registers are dense, so the mint places R6 to carry R7 and R8, and pins
it to the byte 0: the vault's refresh reads it as a byte before the pull, and a register of another
type would make that read fail and leave the box to storage rent. The plain fractional mint, for the
same reason, gives its vault no R6.

## Custody wraps

`LD_Provision` (LithosDex) authorizes Claim and Resize by "the owner NFT in any input". Any ZC path
in which a third party spends a box holding that NFT (an order fill, a liquidation, a vault pull or
claim, an ask or lot refund) could therefore drain the position while the NFT moves on, passing every
ZC check. A wrap keeps such a capability token out of every transaction but its own:

- `WrapMint` is a request holding the token, an owner hash (R4), a mint fee (R5) and the receipt's
  EIP-4 (R6). Any batcher mints 3 receipt units, whose id is the request's: 1 to the owner, 2 and the
  token into a bare `WrapVault`. The request must be the transaction's **only input**, so nothing the
  token has authority over can be spent beside it; it pays the vault, the receipt and the miner itself.
- `WrapVault` releases the token only when all 3 receipt units burn: the holder's and the vault's
  two. Its refresh, too, must be the only input. Nothing claims or compounds a LithosDex position's
  fees while it is wrapped (that would put the NFT in an input); they keep accruing and are claimed
  after unwrapping.
- Verifying a receipt Y is the share pattern: the request's serialized bytes hash to Y under
  `WrapMint`, so the supply is 3; the box holding 2 of Y under `WrapVault` is the state box, and its
  `tokens(1)` is the wrapped token. A collateral policy that values a LithosDex position follows it
  from there to the provision box naming it in R6 and to the pool.

The receipt, not the raw token, goes into orders, bonds and asks: their own rules keep every
collateral token in their successors and settlements, so none can be burned there, and whoever buys
or claims a receipt outright may unwrap it. The contract is generic: it serves any token whose
presence among inputs grants authority elsewhere.

## Storage rent and refresh

Ergo lets anyone take a box four years old (1,051,200 blocks) whose value does not cover its rent
(about 0.00125 ERG per byte at the launch parameter), **tokens included**. For a bond, a vault or a
wrap that would free the authority tokens: a taken bond vault frees the 2-unit reserve, and a
hand-made box under the bond script holding it would pass every state-box check.

Bonds, bond vaults, fractional, Selective and Dutch vaults, and wrap vaults therefore have a paid
refresh, matching LithosDex's T2T provisions so one refresher rule covers both: once a box is more than
525,600 blocks (about two years) old, anyone may re-create it unchanged (script, tokens, registers),
with a creation height within 1,000 blocks of inclusion, keeping up to **0.01 ERG** of its value as a
bounty. A refresh never takes a box below its own floor: a bond's fee reserve plus 0.001 ERG, a
vault's 0.001 ERG plus its pull fee while it still holds the bond unit, 0.001 ERG otherwise. At or under
the floor a box is renewed for nothing. Each extra 0.01 ERG in a box buys about two more years.

Boxes need not hold a whole rent payment (1.5 to 2.5 ERG for these 1 to 2 KB boxes): whoever refreshes
first takes the bounty, so the refresh lands as soon as it opens, two years before rent is due. The
safety rests on someone refreshing within that window; the bounty, and Lithos clients' batch scans, are
the reason to. Orders, offers, asks and lots expire long before, so they have no refresh.

What a refresh does not change: the bond asset, its origin, its state box (the box holding 2 units),
the Dutch mint's settled-bond proof (the vault names the bond box that actually settled), share and
wrap state boxes, or any price. What it does change is the box id, so pending transactions against
the old box are rebuilt; the bond's two limits (stamped at least 60 blocks back, none in the 720 blocks
before maturity) keep a third party from using that against the borrower.

## The exchange

Everything a lender can buy is an **ask**, and the lend offer is the one **bid** that hits any of them.
A borrow order is already an ask: it sells a new bond for its principal.

### The ask convention

An ask is any box that sells its `tokens(0)` this way. R4 is the seller's script hash. A fill pays one
payout box at the seller's script with R4 = the ask's box id and R5 (a `Long`) = the raw units it
pays for. The payment is the payout's nanoERG for an ERG price, or its `tokens(0)` for a token price.
The ask's own script enforces this.

A buyer reads the payout, not the ask's price, and requires R5 to equal the units it received. So a
batcher cannot have an ask sell 10 units, deliver 6 and charge the buyer for all 10. Any new kind of
ask that keeps the convention works with existing offers.

### Sale order and priced ask

`SaleOrder` sells at a fixed price per raw unit, `ceil(units × numerator / denominator)`, with
partial fills and a minimum fill. `PricedAsk` does the same with a fixed floor price, plus a pricing
expression the seller pins in R6; a new pricing method is a new expression, not a new ask.

`YieldAsk_Pricing` prices a live bond, or u of its N shares, at a buyer's simple block yield to
maturity that moves in a straight line between two points in time. Ask R5 is `(floor numerator,
floor denominator, minimum fill, expiry, N, start height, start yield, end height, end yield)`; the
yield at HEIGHT is exactly `Y / L` with `L = end − start` and `Y = y0 × L + (y1 − y0) × (at − start)`,
`at` being HEIGHT clamped to the two heights. A fill must satisfy `paid × N × (B × L + Y × D) ≥ sold × R × B × L`,
`B = 10000 × 262800` and D the remaining blocks: exact, with no division. It stops at maturity and at
settlement, because the state box is then no longer a live bond. Yields are quoted returns
conditional on repayment, not collateral valuations or guaranteed returns.

Both asks are paid in one asset, compiled in per market. `PricedAsk` retains the former `NavAsk`
script bytes: it is a generic policy shell. NAV pricing and price margins were removed on 2026-11-16;
a margin was only a different yield, so one number now describes every quote. See
[docs/ZC_STANDARD_PRICING.md](./docs/ZC_STANDARD_PRICING.md).

### Lot auction

`LotAuction` sells everything in its box whole, to the first buyer, for one currency (compiled in per
market like the asks). For an ERG currency the lot is the tokens, and the box's nanoERG returns to the
seller with the payment; for a token currency the lot is the tokens and all the nanoERG, and the box
may not hold the currency itself. R5 is `(opening, floor, start height, end height, expiry, refund
fee)`: the price is the opening until the start, falls in a straight line to the floor at the end, and
holds there until the expiry, when anyone may refund the lot to the seller for the refund fee. The
price only falls, so a purchase priced now stays valid. Prices are absolute; a client may set them
from an oracle. It is how a holder paid collateral in kind, by a Selective vault, a Dutch auction past
its expiry or several bonds at once, gets the repayment asset back without staying online. It does
not follow the ask convention: its payout carries R4 and no R5, and a lend offer requires the payout's
R5 to state the units it receives, so no offer can be matched against a lot.

### Lend offer

`LendOffer` holds a lender's capital and buys at a **simple block yield curve** (normalized over
262800 blocks). R5 holds the offer's fixed terms and then up to 20 points of `(yield bps, blocks left
to maturity)`, blocks strictly increasing. For a bond with D blocks left the required yield is on the
straight line between the two points around D, flat beyond the first and last, and never below the
minimum yield R5(0); with no points R5(0) is the one yield. On a segment of span L the yield is
exactly `Y / L`, and the test is

```
R × 10000 × 262800 × L ≥ P × (10000 × 262800 × L + Y × D)
```

where R is the repayment, P the price and 262800 blocks one year at 2 minutes. For u of N shares,
`R × u` is compared against `P × N`. On an ERG offer, P includes the batcher fee. The curve is keyed
by blocks left, so it rolls with time: a resting offer keeps bidding the same rate per maturity.
R5(1) and R5(2) still bound which maturities are bought.

The batcher fee per fill, R5(3), is at least 0.002 ERG, the floor every standard batching fee
shares. An offer set below it never fills; its owner cancels it, or anyone refunds it after expiry.

The offer buys from three sources, chosen by context variables:

1. **A new bond**: it fills an accepted `Order` or `PricedOrder`. The issuing script validates the bond.
2. **An existing bond unit** from any ask: it verifies the origin from the order's serialized box
   and requires the live bond as a data input.
3. **Shares** from any ask: it also verifies the mint request's serialized box, which must be the
   Dutch-pair mint (Selective or Dutch; plain fractional shares are not bought), and its auction must
   clear the lender's bounds: a floor at least R5(9) and an expiry at most R5(10). An R5(10) of 0 buys
   no shares.

It delivers the unit to the owner, or into a mint request at the script the lender names (the CLI
writes Selective or Dutch), so a batcher mints shares for an offline lender. Bought shares go straight
to the owner.

The **collateral policy** is an expression the offer pins and runs. `ERG_SigUSD_CollateralPolicy`
requires the bond to carry the oracle liquidation rule, that rule to fire at or above the lender's
floor ratio and accept the lender's oracle age, and the collateral to be worth the floor now.

Only one offer may take part in a fill, so one bond cannot be charged to two offers. Best execution
is not enforced: a batcher chooses which ask it meets, and an offer never does worse than its terms.

### Fill and sale guards

Nothing else stops a trade that hands a bond's collateral surplus to the other side. A fill checks
nothing about the collateral's value, so an order left open while the price falls can be filled into
a bond that is liquidatable at once, or after a small move, and a liquidation sends all the
collateral to the lender. The bond's 60-block recollateralize wait counts from its start, so the
borrower cannot top up in time. An ask on a bond near its line has the same problem the other way:
a buyer pays at most the repayment, then liquidates for all the collateral. The
guards let the owner of the resting box set a collateral ratio under which it does not trade.

**Fill guard.** An order's optional R8 names a guard expression; a fill must pass it, run from the
order's var 1 with the order as `SELF`. `ERG_SigUSD_FillGuard` requires the new bond, `OUTPUTS(1)`,
to hold collateral worth at least R4(9) of the repayment, measured exactly as the oracle rule
measures it (net of the lender's fee reserve) at one USD oracle no older than the rule's age R4(8).
R4(9) must be at least the rule's ratio R4(7), so a guarded order never starts a bond already
liquidatable; a higher one buys the borrower room after the fill. Without R8 a fill is unchanged.
The guard binds every fill, a lend offer's included, and the order still cancels and refunds as
before.

**Sale guard.** `ERG_SigUSD_GuardedYield_Pricing` is `YieldAsk_Pricing` plus the same check on
the live bond the ask sells, against the seller's minimum R5(9) at an oracle no older than R5(10).
While the guard holds the ask still, anyone may liquidate the bond and the seller claims the vault
with the unit. A yield of 10^15 bps prices next to nothing and leaves the ask's fixed floor as the
price, so the same expression gives a guarded fixed-price ask without another contract.

Both are compiled per repayment asset and, like the oracle rule, support ERG and SigUSD only. Each
needs a fresh oracle at the trade, so a guarded box cannot trade while the pool is stale.

## Exits

Every box that waits on someone has a way out.

| Box | Owner, any time | Anyone |
|---|---|---|
| Order, PricedOrder | Borrower cancels | From expiry: refund to the borrower for the expiry fee |
| Lend offer | Owner cancels | From expiry: refund to the owner for the offer's batcher fee |
| Sale order, priced ask | Seller cancels | From expiry: refund to the seller for the nanoERG funded above 0.001 ERG |
| Fractional, Selective or Dutch mint request | Owner cancels | The mint itself, for the mint fee, at any time |
| Wrap request | Owner cancels | The mint itself, for the mint fee, at any time |
| Bond | Borrower repays or recollateralizes | Liquidation, paid by the lender's reserve; refresh after two years |
| Bond vault, fractional, Selective and Dutch vaults | Holders claim | Pulls, for the pull fee; auction purchases; refresh after two years |
| Wrap vault | Receipt holder unwraps | Refresh after two years, as the only input |
| Lot auction | Seller cancels | Purchase at the current price; from expiry, refund to the seller for the refund fee |

Refunds are tagged with the box's id, so an owner that is a contract, which cannot co-sign a cancel,
can recognise its funds. Nothing sweeps lost shares or a lost circulating unit: their claims stay
stranded rather than going to someone else.

## Cost and size

Measured in the offline harness with appkit's signing estimate (see [TESTING.md](./TESTING.md)).
The reference node's default per-transaction limit is 1,000,000; mainnet's block limits are a cost
of 7,030,268 and a size of 1,271,009 bytes.

Highest cost and size per shape, measured 2026-11-16 over the whole suite (521 tests):

| Transaction | Cost | Bytes |
|---|---|---|
| Two offers buying from two priced asks in one transaction | 37,343 | 12,352 |
| Two offers buying from two sale orders in one transaction | 34,547 | 12,150 |
| Offer fills a new SigUSD bond, with policy and oracle | 27,684 | 5,313 |
| Offer buys shares from a SigUSD priced ask (two serialized boxes, oracle) | 26,304 | 7,578 |
| Offer fills a priced order (curve offer, up to 20 points) | 26,115 | 2,161 |
| Offer fills a new ERG bond | 25,907 | 5,434 |
| Two expired offers refunded together | 26,333 | 490 |
| Priced order filled by a wallet (two-point or window curve) | 21,179 | 2,643 |
| Order filled by a wallet | 20,197 | 2,600 |
| Bond repayment / oracle liquidation | 19,605 / 17,350 | 929 / 1,205 |
| Dutch vault claim / sale / pull | 18,870 / 16,517 / 18,580 | up to 2,714 |
| Unwrap (vault, receipt, wallet) | 17,933 | 458 |
| Bond refresh | 16,100 | 1,814 |
| Lot auction purchase | 15,824 | 443 |
| Dutch, fractional, bond and wrap vault refresh | 13,478 / 13,434 / 13,245 / 13,215 | 2,443 / 1,511 / 683 / 628 |
| Wrap mint (the request alone) | 13,297 | 657 |
| Wallet-to-wallet payment, for scale | 12,205 | 166 |

The heaviest transaction, two matched pairs at once, uses 3.7% of the per-transaction limit and 0.53%
of a block's cost; a block holds about 100 of them by size and 188 by cost, so size binds first. A
single match stays under 2.8%. These are appkit's estimates; a node adds signature checks, which are
small next to them.

## Off-chain rules

The contracts fix what must hold for a box to move. What clients write, how batchers find and serve
work, and how tokens are displayed is set by the off-chain profile in
[docs/ZC_STANDARD_OFFCHAIN.md](./docs/ZC_STANDARD_OFFCHAIN.md) (draft, `zcs1`), which the CLI follows.
Off-chain rules cover user experience and batching only; prices and risk terms are always the user's
input. In brief:

- **Script disclosure** (required). A transaction that creates a box naming a borrower, owner or
  seller by hash puts that script's ErgoTree in context variable 64 of `INPUTS(0)`, unless an input or
  output already carries it. Any batcher then finds every script in one lookup. Batchers skip boxes
  that do not do this. Variables 64 to 127 are reserved for off-chain data.
- **Token metadata** (recommended). Short names, `ZCS-Bond-Unit-3fa2c1` for a bond unit and
  `ZCS-Bond-Shares-3fa2c1` for its shares, plain, Selective or Dutch, and compact JSON
  descriptions of the bond's terms (and any auction) with a fixed key order. Both are built only from
  chain data, so any client writes the same bytes and readers can flag a mismatch. Shares minted
  through a lend offer omit the bond, which the offer cannot know when it is posted.
- **Share count** (recommended). 100 shares with 6 decimals (N = 100,000,000), so `1.000000` is 1%
  of the bond, and share prices are quoted as a percentage of face. It stays off-chain: it protects
  no value, and enforcing it would change both mints and the lend offer.
- **Maturity grid** (default). New fixed maturities snap to multiples of 21,600 blocks (about 30
  days) unless the user keeps an exact height, so most bonds of a market maturing in the same month
  share one height and can join a series later. Every maturity stays matchable: offers, batchers and
  readers ignore the grid.
- **Cover at the fill.** Oracle orders carry the fill guard by default, at their own ratio. Clients
  and batchers never fill or buy a bond under its own ratio (they liquidate it instead), and a
  fixed-maturity order's default expiry leaves at least half its term.
- **Batching.** Pack refunds, liquidations and refreshes into one transaction (the CLI's `pack`),
  chain fills through continuations in one block, use one published matching order, and price yield
  asks and orders at the most they ask over the next 3 blocks.
- **Window maturity.** A batcher filling a window order from an offer picks, among the maturities both
  accept, a grid height first, then the widest gap between the yield the offer earns and the yield it
  requires, then the shorter. Between the window's ends, both curves' breakpoints and the grid heights
  both yields are straight lines, so the best maturity is always one of those. A wallet lender picks
  freely; the client defaults to the earliest grid height in the window.
- **Refresh.** A bond's successor is stamped 60 blocks back, and the refresh discloses the borrower's
  script. A client that cannot find it there finds it in the fill that minted the bond asset, which
  paid the principal to it, so a refresh by anyone, zcs1-aware or not, never hides a borrower.
- **Wraps.** A raw capability token never goes into an order, bond or ask; its receipt does. Receipts
  are named `ZCS-Wrap-<id6 of the token>`, with a JSON description of the token and amount.

## Limits and assumptions

- The deployer compiles each contract's constants consistently (an order's repayment token must
  match its bond's, an offer's hashes must be its market's); scripts cannot check one another's
  constants.
- Liquidation expressions, collateral policies and pricing expressions are commitments, not proofs:
  the parties inspect them.
- The ERG/SigUSD rules trust the USD oracle pool's identity NFT and value SigUSD at $1.
- Only live bonds can be bought by an offer or priced by a yield ask; settled bonds cannot.
- A share's value in the offer and a yield ask ignores the fractional vault's own nanoERG.
- Storage rent is outside script evaluation. The paid refresh keeps bonds and vaults ahead of it only
  if someone sends it within two years of its opening; a box at its floor is renewed for nothing.
- A capability token put raw into an order, bond or ask lends its authority to whoever spends that box;
  wrap it first. Clients cannot yet tell a LithosDex owner NFT from any other token by themselves.
- Nothing has run on a live node yet; the offline harness runs the real interpreter.

Lenders and borrowers must evaluate collateral authenticity and their own risk before agreeing to a
loan.

## Pricing extension compatibility (2026-11-14)

The fixed order, bond, bond vault, fractional and Dutch contracts are unchanged. `PricedAsk` is a
source/pin-name rename of `NavAsk` with identical tree bytes. `PricedOrder` and its pricing expressions
are new. `LendOffer` now pins both accepted issuing hashes for primary fills and secondary origin
checks; its tree/address changes. Existing boxes cannot be upgraded by replacing source files.
The CLI pins and generated reference include the new set. Validation is offline; new priced paths
have not been run against a live node. Historical cost figures above predate this extension.

## Liquidity and collateral changes (2026-11-16)

- **Selective and Dutch vaults.** `DutchAuctionMint` and `DutchAuctionVault` gained an auction expiry
  and an in-kind threshold. Selective (threshold 100) is the CLI's default vault; lend offers buy
  shares only from this mint pair, bounding its floor and expiry. Plain fractional shares remain
  claimable but are no longer bought by offers, since a token too thin to split goes to the last
  claimant.
- **Lot auction.** New `LotAuction` contract for selling collateral received in kind.
- **Pricing.** NAV expressions and price margins removed; yield expressions take two points in time.
  `PricedOrder`, `PricedAsk`, `SaleOrder`, `Order`, the bond and both fractional contracts keep their
  trees.
- **Lend offer.** Replaced by the yield-curve offer under the same name. It pins the Dutch mint only.
  Its tree and address change; existing offer boxes keep their old scripts.

Validation is offline (the real interpreter, by signing); nothing here has run on a live node.
Cost and size figures above were remeasured after the change that follows.

## Refresh, window orders, wraps and excess auctions (2026-11-16)

- **Paid refresh** on the bond, bond vault, fractional, Dutch and wrap vaults (variable 9): after
  525,600 blocks anyone re-creates the box unchanged for a 0.01 ERG bounty, never under its floor. The
  bond's refresh is stamped 60 to 999 blocks back and is not allowed in the 720 blocks before maturity.
- **Window orders.** `PricedOrder` gains maturity mode 2 and the new `CurveOrder_Pricing`;
  `YieldOrder_Pricing` refuses mode 2.
- **Custody wraps.** New `WrapMint` and `WrapVault` under `contracts/bonds/wrappers/`.
- **Excess auction.** `DutchAuctionVault` state 4 sells thin items when the floor is already held,
  falling from the opening to zero; the mint's comments changed, not its logic.

Every tree that pins another changed with them: the bond (through the vault), both orders, the offer,
the Dutch mint and both share mints. Existing boxes keep their old scripts. Validation is offline
(521 tests across 30 suites); nothing has run on a live node.

## Audit fixes (2026-11-17)

From [the audit](./docs/ZC_STANDARD_AUDIT_2026-11-17.md):

- **Share mints pin the vault's R6.** `FractionalMint` gives its vault no R6; `DutchAuctionMint` pins it to
  the byte 0. Before, a minter could put a register of another type there, which made the holding vault's
  refresh fail and left it to storage rent.
- **Excess auctions sell only thin items.** In state 4 a sale takes only the lot items under the threshold;
  every other token, and a token currency's nanoERG unless it is thin, stays in the vault for pro rata
  claims. With the Dutch threshold every item is thin, so a Dutch vault sells everything as before.
- **Lot payouts carry no R5**, so no lend offer can be matched against a `LotAuction`.
- **Bond refresh inputs.** The bond's tree is unchanged; its header now says that every input of a refresh
  must be at least 60 blocks old (EIP-39), and the client picks only such inputs.
- **Client.** Wallet buys, sells, offer matches and listings verify a share's origin before treating it as
  a claim on a bond.

Changed trees: `FractionalMint`, `DutchAuctionVault`, `DutchAuctionMint`, `LotAuction`, and through the
Dutch mint's hash, `LendOffer`. The bond, bond vault, orders and asks keep their trees. Existing boxes keep
their old scripts. Validation is offline (524 tests across 30 suites, plus throwaway probes of each fix);
nothing has run on a live node.
