# ZC Standard off-chain rules

The contracts fix what must be true for a box to move. Everything else is decided off-chain: what a
client writes into the boxes it creates, how a batcher finds and serves work, and how a wallet or UI
shows bonds and shares. This document is that off-chain part of the ZC Standard, versioned as profile
`zcs1`. [SigmaBonds.MD](../SigmaBonds.MD) describes the contracts.

Status: **draft**. The CLI follows all of it, specced offline in `StandardOffchainSpec`; nothing has
run against a live node yet. See "CLI status" at the end.

Four principles:

- **Off-chain rules serve user experience and batching.** They decide how boxes are written, found,
  grouped and displayed. They never decide a price or a risk. Yield, collateral floor, liquidation
  ratio, principal, repayment, an ask's price, a yield's two points, an offer's curve, the prices of a Dutch auction or a lot are always the
  user's own input. No client should default them.
- **Required rules** are the ones a batcher cannot work without. A batcher following this profile
  skips boxes that break one. The owner can still act on such a box with their own client.
- **Recommended rules** keep the market uniform. Clients always write them; readers flag boxes that
  break one, but batchers still serve those boxes.
- **Defaults** are what a client writes when the user chooses nothing else: the maturity grid and the
  values under "Defaults". The user can always choose otherwise, and a departure from a default is
  not flagged.

## What the contracts leave off-chain

| Area | Fixed on-chain | Left off-chain |
|---|---|---|
| Deployment | Each tree pins the hashes of the trees it reads | Whether each market's constants agree (an order's repayment token and its bond's, an offer's five constants). Pinned in [ZC_STANDARD_REFERENCE.md](./ZC_STANDARD_REFERENCE.md). |
| Expressions | Their hashes only (bond R8, fixed order R8, priced order R8, offer R6, priced ask R6) | The expression bytes a spender supplies; whether a bond's liquidation rule is the one compiled for its market |
| Verification | Origin and state checks inside the lend offer | Every other reader (wallet, UI, a direct purchase) runs the recipe in SigmaBonds.MD itself |
| Scripts behind hashes | Borrower, owner and seller as blake2b256 of their scripts | Finding the script to pay: **script disclosure** below |
| Token metadata | A bond unit's EIP-4 R4 and R5 exist and R6 is "0"; a share's R4 to R6 copy its request's R9 | Every name and description: **token metadata** below |
| Share count | 1 ≤ N ≤ 1,000,000,000 | N and decimals: **share count** below |
| Fees | 0.002 ERG floors on batching fees (asks have none) | Amounts above the floors; the miner fee |
| Heights | Expiries, maturity at least one block away, start within 8 blocks, oracle age at most 100,000 | Expiry lengths, the maturity grid, oracle-age defaults |
| Matching | Each offer requires its own tagged delivery and continuation, across updated deployments | Which order or ask each offer takes, matching order and transaction packing |
| Display | Nothing | Units, rates, dates, prices of shares |

## Expression-priced orders and asks

[ZC_STANDARD_PRICING.md](./ZC_STANDARD_PRICING.md) specifies fixed and two-point simple block-yield
pricing, the lend offer's yield curve, principal-only resolution, register layouts, exact comparisons,
on-close behavior, fees and CLI examples. NAV pricing and price margins were removed on 2026-11-16.
`Order` and `SaleOrder` retain fixed terms. `PricedOrder` is a separate accepted issuer; `PricedAsk`
is the generic expression shell formerly named `NavAsk`. A reader authenticates either accepted
issuing tree, including when verifying a bond's secondary sale or fractional shares.

Clients resolve priced principal before checking bids, ranking matches, building metadata or paying
the borrower. They never use its stored principal floor as its fill price. A two-point yield can move
a price either way, so a quote takes the most it asks at any height from now to three blocks ahead;
the bond records the actual amount paid. Unknown policies never fall back to another formula: direct
fills/buys refuse them, and matching skips them. Cancel and expiry refunds remain available without policy witnesses.
Pricing and collateral-guard commitments are independent. No rate or pricing mode is chosen for the
user. Block yields use 262800 blocks as a scale, with no compounding or calendar-time guarantee.

## Script disclosure (required)

The contracts name the scripts they pay by hash only, but a batcher must build an output under the
script itself: an expiry refund, a fill's delivery, a payout, a share mint. The Ergo indexed node
cannot look a script up by its hash, so every box must lead to its scripts in one lookup.

**Named scripts:**

| Box | Register | Script |
|---|---|---|
| Order, PricedOrder | R6 | Borrower |
| Bond | R5 | Borrower (copied from the order) |
| Lend offer | R4 | Owner |
| Sale order, PricedAsk | R4 | Seller |
| Fractional or Dutch mint request | R7 | Owner |

**Writers.** A transaction that creates any of these boxes, including a continuation or a request a
fill delivers into, puts **context variable 64** on the extension of `INPUTS(0)`: a `Coll[Coll[Byte]]`
holding the ErgoTree bytes of each named script that no input or output of the transaction already has
as its script. When every named script is already there, as it is when a wallet posts from its own
address, variable 64 is left out.

- The bytes are the whole ErgoTree, the `propositionBytes` the hash is taken of, not a serialized
  `SigmaProp`. Owners that are contracts work the same way.
- A P2PK ErgoTree is 36 bytes, so a disclosure adds about 40 bytes to the transaction and nothing to
  any box. It does not ride along in continuations or count towards box minimums.
- Context extensions are part of the bytes a transaction's signatures cover, so a disclosure cannot be
  changed after signing.
- Each transaction discloses for the boxes it creates, so a batcher always holds what the next
  transaction needs. For example, a lend offer fill that delivers into a mint request names the
  owner in the request's R7 and in the continuation's R4, and pays no output to the owner. The batcher
  read the owner's script from the offer's creating transaction and discloses it again in the fill.

**Readers** resolve a hash in this order:

1. A local cache of hash to ErgoTree. A hash never changes meaning, so entries never expire.
2. The box's creating transaction, from the `transactionId` in the box's JSON:
   `/blockchain/transaction/byId/{txId}` on the indexed node, or
   `/transactions/unconfirmed/byTransactionId/{txId}` for one still in the mempool. Match the hash
   against variable 64 in `INPUTS(0)`'s `spendingProof.extension`, then against every input's and
   output's `ergoTree`. Writers always use `INPUTS(0)`; a reader may accept variable 64 on any input,
   as the CLI does.
3. Nothing else. A box whose script is not found this way breaks the rule, and batchers skip it.

**Reserved variables.** Context variables 64 to 127 are reserved for off-chain data. No standard
contract or expression reads them, and new ones must not.

This replaced the borrower-tree hint the CLI used to write in an order's R8, and its walk back through
up to 64 earlier transactions. The walk's cost grew with every partial fill. A lend offer that
delivers shares never pays an output to its owner, so its owner's script was found only by walking
back to where the offer was posted.

## Token metadata (recommended)

Only two kinds of token are issued: **bond units** (3 per fill) and **shares** (2N+1 per mint). Offers
and asks issue nothing. An offer carries the metadata of shares it will have minted, and an ask only
sells an existing token. One template covers every path. Fields the writer cannot know yet are
omitted by rule, so any two clients write the same bytes for the same token.

### Where metadata is written

| Box | Registers | Written by | Form |
|---|---|---|---|
| A fill's `OUTPUTS(0)`, the unit going to a wallet | R4, R5 bond metadata; R6 "0"; optional R7 offer input id, R8-R9 empty | Whoever builds the fill: the lender's client, or a batcher matching an offer | Bond |
| A fill's `OUTPUTS(0)`, the unit going into a mint request | R4, R5 bond metadata; R6 "0"; R7 to R9 the request's data | The same | Bond |
| Lend offer that delivers shares | R8 share metadata | The lender | Shares, without the bond |
| Mint request from `fractionalize` | R9 share metadata; R4 to R6 a copy of it (registers are dense) | The owner | Shares, with the bond |
| Mint request an offer delivers a bought unit into | R9 = the offer's R8; R4 to R6 a copy of it | The batcher | Shares, without the bond |
| A mint's `OUTPUTS(0)`, the share issuing box | R4 to R6 = the request's R9 (the mint enforces this); R7 to R9 empty | The batcher | As the request |

An offer's metadata is fixed when it is posted, before it knows which bond it will buy, and every
delivery copies it exactly. So shares minted through an offer never name their bond. Their identity
comes from the chain (see "Displaying a token").

### Names

Names are short and say only what a token is. The terms go in the description.

```
name     = "ZCS-Bond-" kind [ "-" id6 ]
kind     = "Unit"       a bond unit
         | "Shares"     shares from ZC_Standard_FractionalMint or ZC_Standard_DutchAuctionMint (Selective or Dutch)
id6      = the first 6 lowercase hexadecimal characters of the bond asset id
```

Plain, Selective and Dutch shares share a name on purpose: a holder sees shares of a bond, and the
auction is a detail of how a liquidation pays out. The description's `type` and `auction` fields tell
them apart.

Bond units always carry the suffix, and for them it is the start of the token's own id. Shares carry
the bond's suffix when the writer knows the bond (`fractionalize`) and leave it out when the writer
does not (an offer). A share's own token id cannot be the suffix: it is the mint request's box id,
which is a hash over the request's registers, the name among them.

| Token | Name |
|---|---|
| Bond unit | `ZCS-Bond-Unit-3fa2c1` |
| Shares of that bond, plain, Selective or Dutch | `ZCS-Bond-Shares-3fa2c1` |
| Shares delivered through an offer | `ZCS-Bond-Shares` |

### Descriptions

Each description is one compact JSON object: no whitespace, keys in the fixed order below, keys that do
not apply left out. Wallets can show it as it is or parse it. Machines read the chain, not the
description.

| Key | Value | In |
|---|---|---|
| `zcs` | `1`, the profile's version | every description |
| `type` | `"bond unit"`, `"shares"` (plain fractional), `"selective shares"` or `"auction shares"` (Dutch) | every description |
| `shares` | `100`, the whole share supply in display units, so each share is 1% of the bond | shares |
| `bond` | id6 of the bond asset | shares whose bond the writer knows |
| `market` | `"ERG"` or `"SigUSD"`, the unit of every amount | every description |
| `via` | `"lend offer"` | shares whose bond the writer does not know |
| `principal`, `repayment` | amounts in the market's unit | bond units, shares whose bond is known |
| `start`, `maturity` | heights | the same |
| `rule` | `"maturity"`; `"oracle"`; or `"other"` | the same |
| `ratio` | the oracle rule's ratio in percent (borrower value 7): liquidated below it, or at maturity if unpaid | with `"rule":"oracle"` |
| `ruleHash` | the first 8 lowercase hexadecimal characters of the rule's hash | with `"rule":"other"` |
| `auction` | `{"from":opening,"to":floor,"blocks":length,"expiry":expiry}`, and for Selective shares `"threshold":units` last: if liquidated, the collateral is auctioned for `from`% falling to `to`% of the repayment over `length` blocks and held at `to`% until `expiry` blocks after it opens; Selective shares auction only when some collateral item holds fewer than `threshold` raw units, and are otherwise paid in kind | selective and auction shares |
| `note` | `"Verify on chain."` | every description |

| Token | Description |
|---|---|
| Bond unit, oracle rule (169 bytes) | `{"zcs":1,"type":"bond unit","market":"SigUSD","principal":100,"repayment":105.5,"start":1712000,"maturity":1749600,"rule":"oracle","ratio":150,"note":"Verify on chain."}` |
| Bond unit, maturity rule | `{"zcs":1,"type":"bond unit","market":"ERG","principal":0.1,"repayment":0.11,"start":1915530,"maturity":1922400,"rule":"maturity","note":"Verify on chain."}` |
| Shares, bond known | `{"zcs":1,"type":"shares","shares":100,"bond":"3fa2c1","market":"SigUSD","principal":100,"repayment":105.5,"start":1712000,"maturity":1749600,"rule":"oracle","ratio":150,"note":"Verify on chain."}` |
| Auction shares, bond known | `{"zcs":1,"type":"auction shares","shares":100,"bond":"3fa2c1","market":"SigUSD","principal":100,"repayment":105.5,"start":1712000,"maturity":1749600,"rule":"oracle","ratio":150,"auction":{"from":100,"to":90,"blocks":720,"expiry":2160},"note":"Verify on chain."}` |
| Selective shares, bond known (279 bytes, the longest) | `{"zcs":1,"type":"selective shares","shares":100,"bond":"3fa2c1","market":"SigUSD","principal":100,"repayment":105.5,"start":1712000,"maturity":1749600,"rule":"oracle","ratio":150,"auction":{"from":100,"to":90,"blocks":720,"expiry":2160,"threshold":100},"note":"Verify on chain."}` |
| Shares, bond unknown | `{"zcs":1,"type":"shares","shares":100,"market":"SigUSD","via":"lend offer","note":"Verify on chain."}` |
| Selective shares, bond unknown | `{"zcs":1,"type":"selective shares","shares":100,"market":"ERG","via":"lend offer","auction":{"from":100,"to":90,"blocks":720,"expiry":2160,"threshold":100},"note":"Verify on chain."}` |

A share's bond terms are the same fields as its bond unit's, so a holder reads the whole bond from the
share token. An offer's metadata is fixed before it buys anything, so shares it delivers name only the
market and any auction.

**Formatting**, so that every client writes the same bytes: ASCII only, encoded as EIP-4 `Coll[Byte]`.
Amounts are JSON numbers in the market's unit (ERG has 9 decimals, SigUSD 2), with trailing zeros and
a trailing point removed, no exponent and no thousands separators. Percentages are basis points over
100, formatted the same way (`90`, `90.5`). Heights and block counts are plain integers. Strings are
written without escapes, since every one is ASCII without quotes or backslashes.

Decimals (R6) are `0` for a bond unit, which the order enforces, and `6` for shares (see "Share
count").

Descriptions are kept short. The bond's registers already hold every term, and a fill is built by
batchers, whose block space is limited by size before cost (see "Cost and size" in SigmaBonds.MD). A
bond unit's description is about 150 to 170 bytes. Shares that name their bond have the longest
descriptions, but the owner writes those with `fractionalize`, not a batcher in a fill. Shares an offer
delivers, which a batcher's fill does write, carry the short form.

### Checking metadata

Every field above comes from chain data. A bond unit's issuing box and its first bond box are
outputs of the same fill transaction, so one lookup gives a reader everything it needs to rebuild the
expected bytes.

- A bond unit whose R4 or R5 differs from the template is flagged. This catches a batcher that gave a
  bond a misleading name while matching an offer.
- Shares must match the template with or without the bond, computed from their own bond and mint
  terms.
- A bond issuing box may carry the matching offer's input id in R7; its EIP-4 data stays in R4-R6.
  Other extra registers on that box, or any R7-R9 on a share issuing box, are ignored and flagged.
  Whoever builds a fill or mint can set extra registers, and a wallet might display them.

Metadata is display only. A mismatch never changes what verification says about a token.

### Displaying a token

Clients show a SigmaFi token from the chain and use EIP-4 only when they cannot resolve it:

- Bond asset X: the box holding 2 units of X is its state box (live bond or vault), and the box whose id
  is X is the order that minted it.
- Share token Y: the box whose id is Y is the spent mint request. Its R8 gives N and any auction terms,
  and its unit, or the vault's R4, names the bond asset X.

## Share count (recommended)

**100 shares with 6 decimals, so N = 100,000,000 raw shares**, for every share token, on every path:
`fractionalize`, an offer's delivery and a Dutch mint. A wallet then shows `100.000000` as the whole
bond, and `1.000000` (1.0 shares) as 1% of the vault's value.

- Share prices are quoted as a **percentage of face**: a price p for u raw shares of a bond repaying R
  is `p × N / (u × R)`. That figure compares across bonds of any size.
- Rounding: a claim of u raw shares takes `floor(balance × u / N)` of each asset. Clients sell and
  claim in lots of at least `ceil(N / R)` raw shares, so the repayment asset never rounds to zero, and
  they warn when an in-kind claim would round a collateral token to zero. On a bond repaying 105.5
  SigUSD that lot is 9,479 raw shares (0.009479 shares, one cent).
- It fits every bond the contracts allow: N is under their 1,000,000,000 cap, and every contract
  product involving N (claims, Dutch pricing, the offer's yield test, yield pricing) is computed in
  `BigInt`, so a large N cannot overflow. With R in raw units, one raw share is worth
  `R / 100,000,000`: 10 nanoERG on a 1 ERG bond, or one cent on a 1,000,000 SigUSD bond.

**Considered: par shares**, where `1` share is one unit of the repayment asset at maturity. They quote
more naturally, but N then depends on R, which a lend offer does not know when it is posted, and an
ERG bond above 1 ERG would need N scaled to stay under 1,000,000,000.

**Why not in the contracts.** Enforcing N = 100,000,000 and decimals `6` in both mints would make
nonstandard shares impossible, at these costs:

- It protects no value. The vaults pay pro rata for any N, and the offer's yield test (`R × u` against
  `P × N`) is exact for any N.
- Both mint trees change, so the lend offer's two mint-hash constants change, and so does its tree. The
  pins, the reference and the specs are then regenerated. Before deployment that is only work, but it
  is a change to edge contracts for a display rule.
- It closes off other share designs, such as a small group splitting a bond 10 ways or par shares.

Readers already learn N from the chain (the request's R8, the vault's R5), so a nonstandard N costs
nobody anything but uniformity. The off-chain rule keeps that uniformity: clients always write
100,000,000, and readers flag anything else.

## Maturity grid (default)

The grid is a client default, not a conformance rule. Every maturity the contracts allow is a
standard maturity, and batchers, lend offers and readers treat an off-grid bond exactly like one on
the grid.

**What a client does.** A fixed maturity snaps to a grid of **21,600 blocks**, about 30 days of
2-minute blocks, so the maturity height is a multiple of 21,600.

- A client asked for a maturity at least one grid step (21,600 blocks) away, as a height or a
  duration, rounds it to the nearest grid height; a tie goes to the later height. The term then
  stays at least 10,800 blocks. A nearer maturity keeps its exact height: 10 days asked 10 blocks
  before a grid line would otherwise become a 20-minute loan.
- Example: at height 1,700,000, "about 60 days" is height 1,743,200. The nearest grid heights are
  1,728,000 (80 × 21,600, about 39 days) and 1,749,600 (81 × 21,600, about 69 days). The order
  matures at 1,749,600.
- The grid rounds the maturity the user asked for; it never supplies one. Rounding can move it by
  up to 10,800 blocks (15 days), which changes the rate at a fixed repayment, so the client shows the
  snapped height before signing, and the user can always keep an exact height.
- An order meant for a particular lend offer takes a grid height inside that offer's maturity window,
  or keeps its exact height. Snapping out of the window would lose the fill. A window at least 21,600
  blocks wide always contains a grid height.
- On-close orders mature at their start plus a duration, which is unknown until the fill, so they stay
  off the grid.

**What nobody does.** No batcher, lend offer or reader refuses an off-grid maturity, ranks it lower or
flags it. The standard lend offer checks only the blocks left to maturity against its window, and the
matching order under "Batching" ranks by yield alone.

**Why a grid at all.** Not for pricing. The lend offer's yield test reads the blocks left to maturity,
and the yield pricing expressions read each bond's own maturity, so both price any maturity with
no setup. Comparing quotes across maturities needs no grid either: a UI shows each ask's yield to
maturity, the figure the matching order already ranks by. A lend offer's curve is keyed by blocks left
to maturity, not by height, so it needs no grid either.

What the grid gives is identical maturities. Bonds can only be pooled into one fungible token when
they mature at the same height. Series, a planned layer, group bonds of one market, one maturity
height and one collateral policy that way, and a series pool can require `maturity % 21600 == 0`.
The default puts most new bonds on series heights, so they can join a series later. An off-grid bond
trades on its own, as every bond does today, and stays outside series.

## Cover at the fill

Without a guard, no contract stops a fill from starting a bond that can be liquidated at once. The
order checks nothing about the collateral's value, and the lend offer's policy passes a bond under
its own oracle ratio as long as it clears the lender's floor. A liquidation sends all the collateral
to the lender, and the bond's 60-block recollateralize wait counts from its start, so the borrower
cannot top up in time. An order left open while the price moves is a free option for lenders: the
longer it rests and the nearer its cover sits to its ratio, the more it is worth. An ask on a bond
near its line is the same option for buyers. On a fixed-maturity order the same happens with time:
a fill just before maturity leaves blocks, not days, to repay.

**On-chain: the guards (default for oracle orders).** The fill guard and the sale guard, described
in [SigmaBonds.MD](../SigmaBonds.MD) under "Fill and sale guards", let the owner of a resting order
or ask set a collateral ratio under which the contract refuses to trade it. They bind everyone, a
lender who wants to snipe included. A client gives every oracle order the fill guard, at the
order's own liquidation ratio unless the borrower asks for more, so no fill can start a bond
already liquidatable. A ratio above the rule's buys room after the fill; choosing it is the
borrower's call. The sale guard is the seller's choice and has no default.

**Off-chain: for unguarded boxes, and for the user's sake.** A client or batcher following this
profile:

- **Never fills an order, by hand or from a lend offer, whose bond would start under its own oracle
  ratio** at the confirmed oracle price. Nor does it buy, from any ask, a claim on a bond under its
  own ratio: it liquidates that bond instead, which pays the bond's holder, often the seller, all
  the collateral.
- **Never posts an unguarded oracle order already under its own ratio**, and tells the borrower the
  ERG price at which a fill would start a bond liquidatable at once, or for a guarded order, the
  price at which the guard stops fills. It warns when a guarded order cannot be filled now.
- **Checks a guard before building**, so a fill or sale the guard would refuse is never sent.
- **Gives a fixed-maturity order an expiry within half its term** by default (and at most a week),
  so a fill leaves the borrower at least half the term. It warns when a chosen expiry leaves less.

The user can override the off-chain refusals for their own transaction; the CLI's flag is
`--allow-liquidatable`. Nothing overrides a guard.

## Batching

A batcher following this profile:

- **Packs independent work into one transaction** where the contracts allow it. Each input reads its
  own context variables, so expiry refunds of orders, offers and asks and liquidations share one
  transaction, one miner fee and one change box (the CLI's `pack`, at most 20 boxes; specced). Pulls
  probably can too, but are not packed yet. Share mints and new-order fills cannot: each mints the id
  of `INPUTS(0)`, and a transaction mints only one token. Independent existing-bond/share matches can
  share a transaction: each offer's delivery and continuation, and each ask continuation, carry its
  own input id. Updated deployments can coexist in those batches. Wallet funding and unrelated spends
  are allowed. The CLI currently executes match commands individually, chaining continuations.
- **Counts fees per transaction.** Work is worth running when the fees it collects exceed the miner
  fee. Then a 0.002 ERG fee is worth collecting as part of a packed transaction even though it barely
  covers a transaction of its own. A pack that fails is retried piece by piece, for the pieces that
  pay their own miner fee.
- **Chains through continuations.** An offer that can take three orders needs three transactions,
  each spending the last one's continuation. The batcher runs in rounds, scanning again after each,
  and reads the mempool: unspent listings with `includeUnconfirmed=true&excludeMempoolSpent=true`,
  a box by id from `/utxo/withPool/byId/{boxId}`, and wallet boxes with `minConfirmations=-1`. So
  all three can land in one block. It never builds on a box it has already spent. It pays fees from
  confirmed wallet boxes first: a transaction paying with unconfirmed change fails with whatever made
  that change. (Seen live: one dropped transaction took 14 unrelated ones with it.)
- **Reads the oracle from the chain, never the mempool.** Oracles race to refresh the USD pool, and the
  losing refresh's box never exists, so a data input read from the mempool can vanish, and with it the
  transaction and everything chained on it (seen live). While a refresh in the mempool spends the
  confirmed pool box, anything reading it waits for the next block: it would fail once the refresh
  confirms.
- **Checks heights where the signer runs scripts.** The node wallet signs at the current height h and a
  transaction lands at h + 1 or later. A path open only before height X needs h + 1 < X; a path open
  from X on (an expiry refund, a maturity liquidation, the end of a wait) needs h ≥ X. Planning the
  latter for h + 1 makes the signer refuse at X − 1 (seen live, on every kind of refund).
- **Uses a fixed matching order.** Offers are taken by maximum remaining batcher reward, highest first. Each takes the order
  or ask that gives it the highest yield, `(R / P − 1) × 262800 / D`. Ties go to the lower box id. No
  contract enforces this, but every batcher following the profile behaves the same way.
- **Prices ahead.** A transaction lands at the next height or later. A purchase from a yield ask or a
  priced order's fill pays the most its price asks from now to 3 blocks ahead, since a two-point yield can move it either way. A Dutch lot or a lot auction is bought at the
  current height's price, because that price only falls.
- **Skips** boxes that break script disclosure and boxes that name an expression missing from the
  reference (it cannot supply the bytes).

## Expressions

[ZC_STANDARD_REFERENCE.md](./ZC_STANDARD_REFERENCE.md) lists the bytes and hash of every liquidation
rule, collateral policy and pricing expression, per market. It is regenerated from the pinned trees by
`ZcStandardReference --write`. A spender needs the bytes. A reader also checks that a bond's
liquidation rule is the one compiled for the bond's market, which no contract checks.

## Defaults

These values coordinate clients and batchers. In particular, an order and an offer can meet only
when the order's oracle age is at least the offer's. The values are the CLI's current defaults.

| Value | Default | Floor in the contracts |
|---|---|---|
| Miner fee | 0.001 ERG | — |
| Order expiry | 5,040 blocks (about 7 days), or half a fixed maturity's term if that is less | — |
| Order expiry fee | 0.005 ERG | 0.002 ERG |
| Oracle age written into an order's rule | 60 blocks | 1 to 100,000 |
| Oracle age on an offer | 30 blocks | at most the bond's |
| Offer maturity window | 1 to 262,800 blocks | — |
| Offer and ask expiry | 5,040 blocks | — |
| ERG offer batcher fee; token offer expiry refund fee | 0.005 ERG | 0.002 ERG |
| Token offer minimum fill, percentage of original capital | 1% | 1% |
| Token offer batcher reward budget | 0.2 ERG at a 1% minimum fill | Calculated reward per fill must reach 0.002 ERG |
| Liquidation fee reserve | 0.05 ERG (also the CLI's minimum) | 0 |
| Share mint fee, pull fee | 0.005 ERG each | 0.002 ERG |
| Ask refund fee | 0.002 ERG | none |
| ERG carried by a SigUSD offer for fees | 0.5 ERG | — |
| Ask minimum fill, offer minimum price | 1 raw unit | — |
| Fill guard on an oracle order | on, at the order's own liquidation ratio | at least that ratio |
| Sale guard | off; its oracle age 30 blocks when set | 1 to 100,000 blocks |
| Share count, decimals | 100,000,000 (100 shares), 6 | 1 to 1,000,000,000 |
| Share vault | Selective (in-kind threshold 100); `--vault dutch` always auctions | threshold at least 100 |
| Auction length, expiry | 720 and 2,160 blocks | length 1 to 1,000,000; expiry from the length to 1,000,000 |
| Offer: most auction expiry it buys | 0: buys no shares | — |
| Yield end point | the order's or ask's expiry | after the start |
| Lot auction fall, hold | 720 and 2,160 blocks | the fall at least 1 block |
| Lot refund fee | 0.002 ERG | none |

Token offers also enforce a proportional total ERG budget covering rewards, box funding and reserves.
The original capital and both budgets survive every continuation. A 1% minimum therefore permits at
most 100 fills, while splitting cannot increase total batcher reward. Fixed box/reserve costs may require
a larger fill. See [the review's formulas and CLI options](./AUDIT_REVIEW_2026-11-14.md#token-rewards-and-total-erg-protection).

No defaults, ever: principal, repayment, collateral, maturity, liquidation ratio, yield, collateral
floor, a yield curve, an ask's price, the opening and floor of a Dutch or Selective auction, and the
opening and floor of a lot.

## Display

- Amounts in the market's unit: ERG with 9 decimals, SigUSD with 2.
- Yields as simple return normalized over 262,800 blocks, never an exact calendar rate or compounded
  APY. Display the block normalization wherever a yield is shown; fee treatment is described in the pricing guide.
- Heights with an approximate date at 2 minutes per block.
- Share prices as a percentage of face (see "Share count").
- Flags for anything a reader could not verify: an unverified origin, an unknown expression,
  nonstandard metadata, a share count other than 100,000,000.

## Open items

1. Confirm on a live indexed node that `/blockchain/transaction/byId` returns each input's
   `spendingProof.extension`. The node renders inputs as indexed boxes with their `spendingProof`, and
   sigma's JSON codecs write `proofBytes` and `extension`, but neither has been checked against a live
   node. The offline specs render it with the same codec.
2. Confirm that browser wallets (EIP-12) keep context extensions on the user's own inputs when they
   sign, so a dApp can write variable 64. The node wallet's `/wallet/transaction/sign` takes them.
3. Confirm the mempool lookups on a live node. The parameter names come from the node's source
   (`BlockchainApiRoute`, `UtxoApiRoute`, `WalletApiRoute`). A live node (6.1.7) accepts the unspent
   lookups' `includeUnconfirmed` and `excludeMempoolSpent`. On 2026-11-12 the first `sbt livetest` run
   chained 38 transactions through them, with no chained step failing to build: the lookups find
   unconfirmed boxes and leave out mempool spends (the oracle pool box spent by a pending refresh was
   left out), the wallet lists unconfirmed change, and `/transactions/check` accepts chains. Whether the
   wallet's `minConfirmations=-1` also leaves out boxes spent in the mempool is still unknown, since the
   CLI remembers what its own session spent.
4. Specs for packing pulls, and for several refunds or liquidations of one owner in a single
   transaction, in the contract harness.
5. Check how wallets show the supply of a bond unit (3, issued across two outputs) and of shares (2N+1).

## CLI status

| Rule | The CLI |
|---|---|
| Script disclosure | Every transaction it builds discloses, in variable 64 of `INPUTS(0)`, each script its outputs name that no input or output carries; a summary line says so. It resolves a script in one lookup, keeps what it found for the session, and refuses or passes over a box that does not disclose. Orders no longer carry the R8 hint. |
| Bond metadata | The zcs1 template on every fill, by a wallet or a matched offer. `bonds` prints `metadata: zcs1` or what departs from it. |
| Share metadata | The zcs1 template: with the bond for `fractionalize`, without it for an offer's delivery. `--share-name`, `--share-description` and `--decimals` override it and are flagged NONSTANDARD. `shares` and `offers` check every request, vault and offer. |
| Share count | 100,000,000 (100 shares) by default (`--shares` overrides, flagged). `create-offer --deliver-shares` is a flag. `asks` quotes a percentage of face; a share ask's `--min-fill` defaults to the least lot; a partial claim warns when an asset rounds to nothing. |
| Maturity grid | `create-order --maturity` and `--term` at least 21,600 blocks out snap to the grid, and the summary says when the height moved; nearer ones and `--exact-maturity` keep the height. `create-order` does not look at open offers, so aiming at an offer's window takes `--exact-maturity` or a grid height chosen by hand. `match-offer`, `batch` and the listings ignore the grid. |
| Cover at the fill | `create-order` gives an oracle order the fill guard at its `--ratio`, or at `--fill-ratio`; `--no-fill-guard` leaves it out. It notes the cover now and the ERG price at which the guard stops fills, and warns when it would refuse one now; an unguarded order already under its own ratio is refused. `fill-order`, `match-offer` and `batch` supply the guard and the oracle and check the guard first. `sell --min-cover <percent> [--oracle-age <blocks>]` adds the sale guard at a yield or a fixed price; `buy`, `match-offer` and `batch` check it. `fill-order` and `buy` refuse a bond under its own ratio, and `buy` names the `liquidate` command instead; `--allow-liquidatable` overrides those and the unguarded create-order refusal, never a guard. A fixed-maturity order's default expiry is half its term at most, and a later `--expires-in` warns. Without a readable oracle, unguarded boxes are not checked and a note says so. |
| Shares | `fractionalize` and `create-offer --deliver-shares` make only Selective (default) or Dutch (`--vault dutch`) requests, and need `--auction-opening` and `--auction-floor`. Plain fractional vaults made earlier stay listable, pullable and claimable. `match-offer` buys shares only from the Dutch-pair mint, within the offer's floor and expiry bounds. |
| Lots | `sell-lot`, `lots`, `take-lot`, `cancel-lot`, `refund-lot`; `pack` and `batch` include expired lots. |
| Batching | `pack` for refunds and liquidations; `batch --run` packs, ranks offers and their sources, and runs up to 5 rounds with mempool lookups. `--mempool` turns those lookups on for any command. |
| Defaults | As in the table above |
