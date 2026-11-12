# SigmaBonds: the ZC Standard

The ZC Standard is SigmaFi V2's contract set for P2P zero-coupon bonds on Ergo. A borrower posts an
order with collateral; a lender fills it and receives a tradeable bond asset; the bond is repaid or
liquidated into a vault, and whoever holds the asset claims the vault. Around that core sit
fractional shares, a Dutch auction for liquidated collateral, asks for selling bonds and shares, and
a lend offer that buys them on a yield target.

Status: the set is **not deployed**. Every contract has offline specs (see [TESTING.md](./TESTING.md)),
and its compiled trees are pinned in [docs/ZC_STANDARD_REFERENCE.md](./docs/ZC_STANDARD_REFERENCE.md).
The CLI builds the core lifecycle from those pinned trees: orders, fills, repayment (also from
collateral), recollateralization, liquidation, expiry refunds and claims. The exchange, fractional
shares and the Dutch auction have no off-chain code yet. The CLI's `--old` commands still serve the
first-generation contracts in `contracts/old/`, described in [docs/SIGMABONDS_OLD.md](./docs/SIGMABONDS_OLD.md).

Each contract's header comment is its authoritative schema: registers, context variables and every
condition. This document explains how the pieces fit together.

## Layout

All paths are under `contracts/bonds/`. Names drop the `ZC_Standard_` prefix.

| Folder | Contract | Role |
|---|---|---|
| `core/` | `Bond` | The live bond: holds the collateral, repays or liquidates into the vault |
| `core/` | `BondVault` | Holds a settlement; the asset holder claims it |
| `P2P/` | `Order` | A borrower's request for a loan; the fill mints the bond asset |
| `P2P/` | `SaleOrder` | A fixed-price ask for any token |
| `P2P/` | `NavAsk` | An ask priced by an expression the seller picks |
| `P2P/` | `LendOffer` | A lender's yield bid: fills orders and buys from asks |
| `lender-scripts/` | `FractionalMint`, `FractionalVault` | Turn one bond unit into N fungible shares |
| `lender-scripts/` | `DutchAuctionMint`, `DutchAuctionVault` | The same, with a Dutch auction of liquidated collateral |
| `liquidations/` | `MaturityLiquidation` | Liquidation rule: at or after maturity |
| `liquidations/` | `ERG_SigUSD_Liquidation` | Liquidation rule: an oracle collateral ratio, or maturity |
| `policies/` | `ERG_SigUSD_CollateralPolicy` | The lend offer's risk rule |
| `policies/` | `AccruedValue_Pricing` | The NAV ask's first pricing rule |

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

Constants: `CONST_BOND_HASH` (the bond for this market) and `CONST_REPAYMENT_TOKEN` (empty for ERG).

The one-asset rule, checked on a fill: an ERG order has no principal tokens, a principal above 0,
and a floor no higher than the repayment. A token order has exactly one principal token, the
repayment token, with a positive amount, and at most 1000000 nanoERG in both R4(1) and R4(2).

**Fill.** The order must be `INPUTS(0)`, so the bond asset's id is the order's box id. No context
variables. Exactly 3 units are minted.

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

### Bond

| Register | Contents |
|---|---|
| R4 | Originating order id, which is also the asset id |
| R5 | Borrower script hash |
| R6 | The order's borrower terms, copied exactly |
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

### Vault and claim

`BondVault` holds `(asset, 2)` and the settlement. The holder claims by spending the vault with their
circulating unit and burning all 3 units; the outputs are theirs to choose. One transaction can sweep
many vaults, each by its own unit, and a contract (such as a fractional vault) can direct a claim
into itself.

### Liquidation rules

| Expression | Fires | Notes |
|---|---|---|
| `MaturityLiquidation` | At or after maturity | Everything to the vault |
| `ERG_SigUSD_Liquidation` | At or after maturity, or before it when collateral is strictly below the borrower's ratio R6(7) on a USD oracle no older than R6(8) blocks | Measures collateral against the **repayment**, not the principal; compiled per repayment asset (ERG or SigUSD) |

Both parties must inspect the expression: its hash is a commitment, not a proof that it is fair.

## Verifying a bond asset

A token X is a genuine standard bond if two things hold:

1. **Origin.** The box whose id is X was spent as a standard `Order`. Off-chain, fetch the box by id
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

Share count N is free and decimals are display only.

## Dutch auction shares

`DutchAuctionMint` is a fractional mint with three more terms in R8: opening and floor prices in basis
points of the bond's repayment, and an auction length in blocks. The mint reads the repayment from
the live bond as a data input. If the bond has settled while the request waited, it reads the bond
from its serialized bytes (context variable 1), proved by hashing to the settlement's R4. So a batcher
can always run the mint.

`DutchAuctionVault` pays out exactly as the fractional vault when the bond was repaid. When it was
liquidated, the pull opens an auction that sells the collateral for the bond's repayment asset:

- The auction sets a **target** for the currency the vault must hold after a sale: the repayment
  times a basis-point figure falling in a straight line from the opening to the floor over the
  length, then staying at the floor. A buyer brings the vault's currency up to the target and takes
  the rest of the collateral. Currency already in the vault counts towards the target.
- An auction opens only if the vault holds less currency than the floor target. Otherwise holders
  already have the floor and take the collateral in kind. So a floor of 0 means no auction.
- Partial claims wait until the auction has run its length. A claim after that takes the unsold
  collateral in kind and closes the auction. The final claim works in every state.

The guarantee to holders: after a liquidation the vault holds at least `floor × repayment` in the
repayment asset, unless an auction runs its full length with no buyer.

The vault's state is R6: 0 repaid, 1 auction open, 2 sold or no auction needed, 3 paid out in kind.
Registers are dense, so the mint places some R6 to carry R7 and R8; nothing reads it before the pull.

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

### Sale order and NAV ask

`SaleOrder` sells at a fixed price per raw unit, `ceil(units × numerator / denominator)`, with
partial fills and a minimum fill. `NavAsk` does the same with a fixed floor price, plus a pricing
expression the seller pins in R6; a new pricing method is a new expression, not a new ask.

`AccruedValue_Pricing` prices a live bond, or u of its N shares, at accrued value plus a signed
margin: `P + (R − P) × (HEIGHT − start) / (maturity − start)`, all read from the bond. It stops at
maturity and at settlement, because the state box is then no longer a live bond. The price rises
every block, so a fill pays for the height it lands at.

Both asks are paid in one asset, compiled in per market.

### Lend offer

`LendOffer` holds a lender's capital and buys at a minimum **simple-interest yield**:

```
R × 10000 × 262800 ≥ P × (10000 × 262800 + yield_bps × D)
```

where R is the repayment, P the price, D the blocks left to maturity and 262800 blocks one year at
2 minutes. For u of N shares, `R × u` is compared against `P × N`. On an ERG offer, P includes the
batcher fee. The price ceiling rises towards R as maturity nears, so one number follows a bond's
value with no updates. A lender can ladder offers with different maturity windows.

The batcher fee per fill, R5(3), is at least 0.002 ERG, the floor every standard batching fee
shares. An offer set below it never fills; its owner cancels it, or anyone refunds it after expiry.

The offer buys from three sources, chosen by context variables:

1. **A new bond**: it fills a standard order. The order's own script validates the bond.
2. **An existing bond unit** from any ask: it verifies the origin from the order's serialized box
   and requires the live bond as a data input.
3. **Shares** from any ask: it also verifies the mint request's serialized box. Dutch auction shares
   must also clear the lender's bounds: an auction floor at least R5(9) and a length at most R5(10).

It delivers the unit to the owner, or into a mint request at the script the lender names (standard or
Dutch), so a batcher mints shares for an offline lender. Bought shares go straight to the owner.

The **collateral policy** is an expression the offer pins and runs. `ERG_SigUSD_CollateralPolicy`
requires the bond to carry the oracle liquidation rule, that rule to fire at or above the lender's
floor ratio and accept the lender's oracle age, and the collateral to be worth the floor now.

Only one offer may take part in a fill, so one bond cannot be charged to two offers. Best execution
is not enforced: a batcher chooses which ask it meets, and an offer never does worse than its terms.

## Exits

Every box that waits on someone has a way out.

| Box | Owner, any time | Anyone |
|---|---|---|
| Order | Borrower cancels | From expiry: refund to the borrower for the expiry fee |
| Lend offer | Owner cancels | From expiry: refund to the owner for the offer's batcher fee |
| Sale order, NAV ask | Seller cancels | From expiry: refund to the seller for the nanoERG funded above 0.001 ERG |
| Fractional or Dutch mint request | Owner cancels | The mint itself, for the mint fee, at any time |
| Bond | Borrower repays or recollateralizes | Liquidation, paid by the lender's reserve |
| Bond vault, fractional and Dutch vaults | Holders claim | Pulls, for the pull fee; Dutch auction purchases |

Refunds are tagged with the box's id, so an owner that is a contract, which cannot co-sign a cancel,
can recognise its funds. Nothing sweeps lost shares or a lost circulating unit: their claims stay
stranded rather than going to someone else.

## Cost and size

Measured in the offline harness with appkit's signing estimate (see [TESTING.md](./TESTING.md)).
The reference node's default per-transaction limit is 1,000,000; mainnet's block limits are a cost
of 7,030,268 and a size of 1,271,009 bytes.

| Transaction | Cost | Bytes |
|---|---|---|
| Offer buys shares from a NAV ask (offer, policy, pricing, two serialized boxes, oracle) | 24,521 | 6,251 |
| Offer fills a new bond, with policy and oracle | 21,655 | 4,258 |
| Offer buys from a sale order | 20,626 | 5,226 |
| Dutch vault claim / pull / sale | 18,858 / 18,533 / 16,503 | up to 2,272 |
| Bond repayment / liquidation | 19,096 / 16,841 | 743 / 1,019 |
| Order fill by a wallet | 16,777 | 2,638 |
| Wallet-to-wallet payment, for scale | 12,205 | 166 |

The heaviest match uses 2.5% of the per-transaction limit. A block holds about 200 of them by size.

## Limits and assumptions

- The deployer compiles each contract's constants consistently (an order's repayment token must
  match its bond's, an offer's hashes must be its market's); scripts cannot check one another's
  constants.
- Liquidation expressions, collateral policies and pricing expressions are commitments, not proofs:
  the parties inspect them.
- The ERG/SigUSD rules trust the USD oracle pool's identity NFT and value SigUSD at $1.
- Only live bonds can be bought by an offer or priced by the NAV ask; settled bonds cannot.
- A share's value in the offer and the NAV ask ignores the fractional vault's own nanoERG.
- Storage rent is outside script evaluation and is not modelled.
- Nothing has run on a live node yet; the offline harness runs the real interpreter.

Lenders and borrowers must evaluate collateral authenticity and their own risk before agreeing to a
loan.
