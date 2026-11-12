# ZC Standard off-chain rules

The contracts fix what must be true for a box to move. Everything else is decided off-chain: what a
client writes into the boxes it creates, how a batcher finds and serves work, and how a wallet or UI
shows bonds and shares. This document is that off-chain part of the ZC Standard, versioned as profile
`zcs1`. [SigmaBonds.MD](../SigmaBonds.MD) describes the contracts.

Status: **draft**. The CLI follows only some of it so far; see "CLI status" at the end.

Three principles:

- **Off-chain rules serve user experience and batching.** They decide how boxes are written, found,
  grouped and displayed. They never decide a price or a risk. Yield, collateral floor, liquidation
  ratio, principal, repayment, an ask's price or margin and a Dutch auction's terms are always the
  user's own input. No client should default them.
- **Required rules** are the ones a batcher cannot work without. A batcher following this profile
  skips boxes that break one. The owner can still act on such a box with their own client.
- **Recommended rules** keep the market uniform. Clients always write them; readers flag boxes that
  break one, but batchers still serve those boxes.

## What the contracts leave off-chain

| Area | Fixed on-chain | Left off-chain |
|---|---|---|
| Deployment | Each tree pins the hashes of the trees it reads | Whether each market's constants agree (an order's repayment token and its bond's, an offer's five constants). Pinned in [ZC_STANDARD_REFERENCE.md](./ZC_STANDARD_REFERENCE.md). |
| Expressions | Their hashes only (bond R8, offer R6, NAV ask R6) | The expression bytes a spender supplies; whether a bond's liquidation rule is the one compiled for its market |
| Verification | Origin and state checks inside the lend offer | Every other reader (wallet, UI, a direct purchase) runs the recipe in SigmaBonds.MD itself |
| Scripts behind hashes | Borrower, owner and seller as blake2b256 of their scripts | Finding the script to pay: **script disclosure** below |
| Token metadata | A bond unit's EIP-4 R4 and R5 exist and R6 is "0"; a share's R4 to R6 copy its request's R9 | Every name and description: **token metadata** below |
| Share count | 1 ≤ N ≤ 1,000,000,000 | N and decimals: **share count** below |
| Fees | 0.002 ERG floors on batching fees (asks have none) | Amounts above the floors; the miner fee |
| Heights | Expiries, maturity at least one block away, start within 8 blocks, oracle age at most 100,000 | Expiry lengths, the maturity grid, oracle-age defaults |
| Matching | An offer never does worse than its terms; one offer per transaction | Which order or ask each offer takes, in what order, and what is packed together |
| Display | Nothing | Units, rates, dates, prices of shares |

## Script disclosure (required)

The contracts name the scripts they pay by hash only, but a batcher must build an output under the
script itself: an expiry refund, a fill's delivery, a payout, a share mint. The Ergo indexed node
cannot look a script up by its hash, so every box must lead to its scripts in one lookup.

**Named scripts:**

| Box | Register | Script |
|---|---|---|
| Order | R6 | Borrower |
| Bond | R5 | Borrower (copied from the order) |
| Lend offer | R4 | Owner |
| Sale order, NAV ask | R4 | Seller |
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
   output's `ergoTree`.
3. Nothing else. A box whose script is not found this way breaks the rule, and batchers skip it.

**Reserved variables.** Context variables 64 to 127 are reserved for off-chain data. No standard
contract or expression reads them, and new ones must not.

This replaces the borrower-tree hint the CLI writes in an order's R8, and the CLI's walk back through
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
| A fill's `OUTPUTS(0)`, the unit going to a wallet | R4, R5 bond metadata; R6 "0"; R7 to R9 empty | Whoever builds the fill: the lender's client, or a batcher matching an offer | Bond |
| A fill's `OUTPUTS(0)`, the unit going into a mint request | R4, R5 bond metadata; R6 "0"; R7 to R9 the request's data | The same | Bond |
| Lend offer that delivers shares | R8 share metadata | The lender | Shares, without the bond |
| Mint request from `fractionalize` | R9 share metadata; R4 to R6 a copy of it (registers are dense) | The owner | Shares, with the bond |
| Mint request an offer delivers a bought unit into | R9 = the offer's R8; R4 to R6 a copy of it | The batcher | Shares, without the bond |
| A mint's `OUTPUTS(0)`, the share issuing box | R4 to R6 = the request's R9 (the mint enforces this); R7 to R9 empty | The batcher | As the request |

An offer's metadata is fixed when it is posted, before it knows which bond it will buy, and every
delivery copies it exactly. So shares minted through an offer never name their bond. Their identity
comes from the chain (see "Displaying a token").

### Names

```
name     = "ZCS-" market "-" kind [ "-" maturity "-" id6 ]
market   = the market's name: "ERG" or "SigUSD"
kind     = "B"            a bond unit
         | "S"            shares from ZC_Standard_FractionalMint
         | "D" floor      shares from ZC_Standard_DutchAuctionMint; floor in percent of the repayment
maturity = the bond's maturity height
id6      = the first 6 lowercase hexadecimal characters of the bond asset id
```

Bond units always carry the suffix. Shares carry it when the writer knows the bond (`fractionalize`)
and leave it out when the writer does not (an offer). Wallets that sort by name then group tokens by
market, then by kind, then by maturity.

| Token | Name |
|---|---|
| Bond unit | `ZCS-SigUSD-B-1733600-3fa2c1` |
| Shares of that bond, from `fractionalize` | `ZCS-SigUSD-S-1733600-3fa2c1` |
| Dutch shares of that bond, 90% floor | `ZCS-SigUSD-D90-1733600-3fa2c1` |
| Shares delivered through a SigUSD offer | `ZCS-SigUSD-S`, or `ZCS-SigUSD-D90` |

### Descriptions

Each description is one sentence a wallet can show, tagged with the profile version. Machines read
the chain, not the description.

| Token | Description |
|---|---|
| Bond unit | `zcs1 bond: repays {R} {market} at height {maturity}; lent {P} at height {start}; {rule}. Verify on chain.` |
| Shares, bond known | `zcs1 shares: 1.000000 = one bond {id6} repaying {R} {market} at height {maturity}.{auction} Verify on chain.` |
| Shares, bond unknown | `zcs1 shares: 1.000000 = one {market} bond.{auction} Verify on chain.` |

- `{rule}` is `liquidates at maturity` for `MaturityLiquidation`, `liquidates below {ratio}% or at
  maturity` for an oracle rule, and `liquidation rule {first 8 hex of its hash}` for any other.
- `{auction}` is empty for standard shares, and for Dutch shares
  ` If liquidated, auctioned from {opening}% to {floor}% of the repayment over {length} blocks.`
- Example, about 120 bytes: `zcs1 bond: repays 105 SigUSD at height 1733600; lent 100 at height
  1712000; liquidates below 150% or at maturity. Verify on chain.`

**Formatting**, so that every client writes the same bytes: ASCII only, encoded as EIP-4 `Coll[Byte]`.
Amounts are in the market's unit (ERG has 9 decimals, SigUSD 2), with trailing zeros and a trailing
point removed and no thousands separators. Percentages are basis points over 100, formatted the same
way (`90`, `90.5`). Heights and block counts are plain integers.

Decimals (R6) are `0` for a bond unit, which the order enforces, and `6` for shares (see "Share
count").

The description is short on purpose. The bond's registers already hold every term, and a fill is
built by batchers, whose block space is limited by size before cost (see "Cost and size" in
SigmaBonds.MD).

### Checking metadata

Every field above comes from chain data. A bond unit's issuing box and its first bond box are
outputs of the same fill transaction, so one lookup gives a reader everything it needs to rebuild the
expected bytes.

- A bond unit whose R4 or R5 differs from the template is flagged. This catches a batcher that gave a
  bond a misleading name while matching an offer.
- Shares must match the template with or without the bond, computed from their own bond and mint
  terms.
- R7 to R9 on an issuing box that goes to a wallet, or on a share issuing box, are ignored and
  flagged. Whoever builds a fill or a mint can set them, for example to an EIP-4 asset type with a
  link, and a wallet might display that.

Metadata is display only. A mismatch never changes what verification says about a token.

### Displaying a token

Clients show a SigmaFi token from the chain and use EIP-4 only when they cannot resolve it:

- Bond asset X: the box holding 2 units of X is its state box (live bond or vault), and the box whose id
  is X is the order that minted it.
- Share token Y: the box whose id is Y is the spent mint request. Its R8 gives N and any auction terms,
  and its unit, or the vault's R4, names the bond asset X.

## Share count (recommended)

**N = 1,000,000 and 6 decimals**, for every share token, on every path: `fractionalize`, an offer's
delivery and a Dutch mint. A wallet then shows `1.000000` as one whole bond.

- Share prices are quoted as a **percentage of face**: a price p for u raw shares of a bond repaying R
  is `p × N / (u × R)`. That figure compares across bonds of any size.
- Rounding: a claim of u raw shares takes `floor(balance × u / N)` of each asset. Clients sell and
  claim in lots of at least `ceil(N / R)` raw shares, so the repayment asset never rounds to zero, and
  they warn when an in-kind claim would round a collateral token to zero.
- It fits every bond the contracts allow. With R in raw units, one raw share is worth `R / 1,000,000`:
  1,000 nanoERG on a 1 ERG bond, or one cent on a 10,000 SigUSD bond.

**Considered: par shares**, where `1` share is one unit of the repayment asset at maturity. They quote
more naturally, but N then depends on R, which a lend offer does not know when it is posted, and an
ERG bond above 1 ERG would need N scaled to stay under 1,000,000,000.

**Why not in the contracts.** Enforcing N = 1,000,000 and decimals `6` in both mints would make
nonstandard shares impossible, at these costs:

- It protects no value. The vaults pay pro rata for any N, and the offer's yield test (`R × u` against
  `P × N`) is exact for any N.
- Both mint trees change, so the lend offer's two mint-hash constants change, and so does its tree. The
  pins, the reference and the specs are then regenerated. Before deployment that is only work, but it
  is a change to edge contracts for a display rule.
- It closes off other share designs, such as a small group splitting a bond 10 ways or par shares.

Readers already learn N from the chain (the request's R8, the vault's R5), so a nonstandard N costs
nobody anything but uniformity. The off-chain rule keeps that uniformity: clients always write
1,000,000, and readers flag anything else.

## Maturity grid (recommended)

Fixed-maturity orders snap to a grid of **21,600 blocks**, about 30 days of 2-minute blocks: the
maturity height is a multiple of 21,600.

- A client asked for a maturity, as a height or a duration, rounds it to the nearest grid height.
  The order's expiry still has to come before it. An explicit flag keeps an exact height.
- Example: at height 1,700,000, "about 60 days" is height 1,743,200. The nearest grid heights are
  1,728,000 (80 × 21,600, about 39 days) and 1,749,600 (81 × 21,600, about 69 days). The order
  matures at 1,749,600.
- On-close orders mature at their start plus a duration, which is unknown until the fill, so they stay
  off the grid.

Why: without a grid, two bonds maturing 17 blocks apart are separate maturities for every quote,
listing and share market. With it, every bond of a market maturing in a given month shares one height.
Asks and offers then quote on one curve, and shares of different bonds that mature together compare
directly. A lend offer's maturity window at least 21,600 blocks wide always contains a grid height.
Series pools are the later on-chain version of the same idea: a pool can require
`maturity % 21600 == 0`.

## Batching

A batcher following this profile:

- **Packs independent work into one transaction** where the contracts allow it. Each input reads its
  own context variables, so expiry refunds of orders, offers and asks, liquidations, and probably
  pulls can share one transaction, one miner fee and one change box. Share mints and new-order fills
  cannot: each mints the id of `INPUTS(0)`, and a transaction mints only one token. Two lend offers
  never share a transaction. Packed transactions are not yet specced.
- **Counts fees per transaction.** Work is worth running when the fees it collects exceed the miner
  fee. Then a 0.002 ERG fee is worth collecting as part of a packed transaction even though it barely
  covers a transaction of its own.
- **Chains through continuations.** An offer that can take three orders needs three transactions,
  each spending the last one's continuation. The batcher builds on its own unconfirmed outputs, so
  all three can land in one block.
- **Uses a fixed matching order.** Offers are taken by batcher fee, highest first. Each takes the order
  or ask that gives it the highest yield, `(R / P − 1) × 262800 / D`. Ties go to the lower box id. No
  contract enforces this, but every batcher following the profile behaves the same way.
- **Prices ahead.** A transaction lands at the next height or later. A purchase from a NAV ask pays
  that ask's price 3 blocks ahead, because the price rises every block. A Dutch lot is bought at the
  current height's target, because the target only falls.
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
| Order expiry | 5,040 blocks (about 7 days) | — |
| Order expiry fee | 0.005 ERG | 0.002 ERG |
| Oracle age written into an order's rule | 60 blocks | 1 to 100,000 |
| Oracle age on an offer | 30 blocks | at most the bond's |
| Offer maturity window | 1 to 262,800 blocks | — |
| Offer and ask expiry | 5,040 blocks | — |
| Offer batcher fee | 0.005 ERG | 0.002 ERG |
| Liquidation fee reserve | 0.05 ERG (also the CLI's minimum) | 0 |
| Share mint fee, pull fee | 0.005 ERG each | 0.002 ERG |
| Ask refund fee | 0.002 ERG | none |
| ERG carried by a SigUSD offer for fees | 0.5 ERG | — |
| Ask minimum fill, offer minimum price | 1 raw unit | — |
| NAV ask margin | 0% | above −100% |
| Share count, decimals | 1,000,000, 6 | 1 to 1,000,000,000 |

No defaults, ever: principal, repayment, collateral, maturity, liquidation ratio, yield, collateral
floor, an ask's price or `--nav`, and a Dutch auction's opening, floor and length.

## Display

- Amounts in the market's unit: ERG with 9 decimals, SigUSD with 2.
- Yields as simple APR per 262,800 blocks. A year is a block count, so slow blocks mean a lower
  calendar rate than quoted. Say so wherever a rate is shown.
- Heights with an approximate date at 2 minutes per block.
- Share prices as a percentage of face (see "Share count").
- Flags for anything a reader could not verify: an unverified origin, an unknown expression,
  nonstandard metadata, a share count other than 1,000,000.

## Open items

1. Confirm on a live indexed node that `/blockchain/transaction/byId` returns each input's
   `spendingProof.extension`. The node renders inputs as indexed boxes with their `spendingProof`, and
   sigma's JSON codecs write `proofBytes` and `extension`, but neither has been checked against a live
   node.
2. Confirm that browser wallets (EIP-12) keep context extensions on the user's own inputs when they
   sign, so a dApp can write variable 64.
3. Specs for packed transactions: several refunds, liquidations or pulls in one transaction.
4. Check how wallets show the supply of a bond unit (3, issued across two outputs) and of shares (2N+1).

## CLI status

| Rule | The CLI today |
|---|---|
| Script disclosure | Not written. Orders carry the borrower's tree in R8; other boxes are resolved by walking back up to 64 transactions. |
| Bond metadata | Name `SigmaFi-ZCS-{id6}`; a JSON description of about 500 bytes plus about 85 per collateral token |
| Share metadata | `SigmaFi bond shares` and `Shares of one SigmaFi ZC Standard bond`, decimals 0 unless `--decimals` |
| Share count | Required: `--shares` or `--deliver-shares` |
| Maturity grid | None |
| Batching | One piece of work per transaction; each fee must exceed the miner fee; the first match found is taken |
| Defaults | As in the table above |
