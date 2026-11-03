# SigmaBonds

SigmaBonds is a decentralized, P2P bond protocol built on the Ergo blockchain.
Borrowers post bond orders, setting the maturity, repayment, principal and collateral. Lenders post
bond offers, setting the same terms from the other side. Either side may close what the other posted.
SigmaBonds may be collateralized by *any* asset on the Ergo blockchain: ERG, tokens (like SIGUSD,
Ergopad, Neta, etc.) and NFTs, in any combination in a single bond.

Every V2 bond is a zero-coupon bond: the borrower repays one fixed amount before maturity, or the
collateral may be liquidated to the lender. Every V2 bond also mints a **bond asset**, a single token
that is the lender's claim on the bond. The lender may hold it, or sell it.

## Utility
SigmaBonds can be used for a variety of different purposes, including the raising of funds,
access to quick liquid assets, improvements to mining rigs/farms,
arbitrage opportunities, and many other unique DeFi usecases. For lenders, SigmaBonds
can be used to slowly gain yield on ERG or a native asset of their choice. Flexibility in collateralization also allows
for lenders to have more options when evaluating loans (e.g. A lender may be willing to take less collateral
from entities they deem to be "trusted". Borrower identities can be verified via Public Key or ErgoNames).

## Bond Assets
A bond asset is minted when its bond is made, with a supply of exactly 1, and the lender receives it. The
order or offer that makes the bond is the transaction's first input, so the asset's id is that order's or
offer's box id. The bond holds no unit of it: it names the asset in R4 instead.

Every bond pays its repayment, or its liquidated collateral, into the `BondAssetVault`, and whoever holds
the bond asset claims it by burning the asset. Each bond asset therefore unlocks exactly what its bond pays
out. The position on the bond is simply the token: lenders may trade it, and buyers may speculate on the
value of the bond. The cost is one more transaction for the lender: being paid means claiming from the
vault ([Tx 6](#tx-6-claim-from-the-vault)).

A bond asset is only worth anything if its supply really is 1, and its bond is real. Verify one by its
genesis box, the box whose id is the asset's id. It must be a `ZC_Offer`, `ZC_Order` or `ZC_OrderOnClose`
instance, and the bond is the second output of the transaction that spent it.

Check offers and orders with `ScriptGenerator.ContractTemplate.matches`, never by template hash alone.
A template hash leaves out every constant, not just the terms, so a forged order can share the genuine
template hash while minting 2 units. `matches` allows only the terms to differ. Anyone can also build a
bond box naming any asset in R4. It only ever pays that asset's holder, but it is not the asset's bond:
only the bond the mint created is. **Never trust a bond asset by its name.** The name is chosen by the
lender and proves nothing.

## Fees
SigmaBonds V2 takes no protocol fees. The only fee pays whoever executes a transaction for someone else:

- **Batching fee** (bond R6(1), set as an order or offer term): the most a liquidator may keep when it
  liquidates a bond. The lender receives the collateral less at most this amount, in nanoERG, and
  whoever builds the liquidation keeps the difference. A batcher can liquidate many matured bonds in one
  transaction with no funds of its own: each bond pays its fee, and one miner fee comes out of the total.
  It is a ceiling, so a lender who liquidates their own bond simply keeps everything.

## Token
SigmaBonds has no plans to make a token, and likely never will.

# Protocol

## Contracts
All contracts are in [`contracts/bonds`](./contracts/bonds). V2 is not deployed yet. Addresses, script
hashes and template hashes all change with any edit to a contract, so they are not listed here: run
`ContractClient` to print the current ones, along with each order and offer term's constant index.

| Contract | Role | Found by |
|---|---|---|
| `P2P/ZC_ERG_Bond` | A bond repaid in nanoERG | Address |
| `P2P/ZC_Token_Bond` | A bond repaid in one token, `CONST_TOKEN_ID` | Address (one per token) |
| `P2P/ZC_Offer` | A lender's offer, closed by a borrower | Template hash |
| `P2P/ZC_Order` | A borrower's order with a fixed maturity height, filled by a lender | Template hash |
| `P2P/ZC_OrderOnClose` | A borrower's order with a duration counted from the fill | Template hash |
| `lender-scripts/BondAssetVault` | Holds repayments and collateral for the holder of a bond asset | Address |
| `liquidations/MaturityLiquidation` | Liquidation from the maturity height on, as in V1 | Script hash |

Offers and orders keep their terms as compiled-in constants rather than registers. Every offer is the
same ErgoTree template with different constants, and likewise for each order type, so UIs find them by
template hash and read the terms from the constants. Clients must build an offer or order by replacing
the constants of the compiled template (`ScriptGenerator.ContractTemplate.withTerms`), never by
recompiling the script, and must verify one found by template hash with `ContractTemplate.matches`.

Every bond compiles in `CONST_VAULT_HASH`, so a bond can only ever pay the vault. That constant is part of
the bond's address: orders and offers name the bond by its hash (`CONST_BOND_HASH`), which commits to it.

Hashes of contracts and wallets are always `blake2b256` of the full ErgoTree bytes.

## The Bond Box
- tokens: the collateral tokens, and nothing else
- value: the collateral nanoERG
- R4: `Coll[Byte]` the bond asset's id, which is also the box id of the originating order or offer
- R5: `Coll[Byte]` borrower hash: receives the collateral back on repayment
- R6: `Coll[Long]` (repayment, batching fee). Repayment is in nanoERG, or in `CONST_TOKEN_ID` for a token bond.
- R7: `Int` maturity height. Repayment is open strictly below it.
- R8: `Coll[Byte]` liquidation script hash. See [Liquidation Scripts](#liquidation-scripts).

## Lender Side: Offers

### Tx 1A: Create an Offer
The lender sends the principal to an instance of `ZC_Offer`, plus 0.001 ERG for the bond asset's box.
For a token loan, the principal tokens go in the same box, with 0.002 ERG: 0.001 for the borrower's
principal box and 0.001 for the bond asset's. The terms are constants:
- `CONST_LENDER_HASH`: the lender's wallet; receives the bond asset, authorizes a cancel
- `CONST_ASSET_NAME` and `CONST_ASSET_DESCRIPTION`: the bond asset's EIP-4 name and description (UTF-8)
- `CONST_BOND_HASH`: which bond, ERG or token
- `CONST_REPAYMENT`, `CONST_BATCHING_FEE`, `CONST_MATURITY`, `CONST_LIQ_SCRIPT_HASH`: copied to the bond
- `CONST_COLLATERAL_ERG` and `CONST_COLLATERAL_TOKENS`: the least nanoERG and the exact tokens the bond
  must hold

The offer may carry R4 (optional, never read): `Coll[Coll[Byte]]` holding the lender's ErgoTree, the
preimage of `CONST_LENDER_HASH`, which a borrower needs to send the bond asset. Without it, a borrower
looks for that tree among the boxes of the transaction that created the offer.

### Tx 2A: Close an Offer
A borrower takes the principal by creating the exact bond the lender asked for. Whoever posts the
collateral is the borrower, so the principal may go wherever they send it. The offer is `INPUTS(0)`, so
the close mints the bond asset, with the offer's box id as its id. One offer closes per transaction.
- `OUTPUTS(0)`: the bond asset, (asset, 1) at tokens(0), to `CONST_LENDER_HASH`, at least 0.001 ERG. R4
  and R5 are the offer's name and description, R6 is `"0"` (decimals), and there are no further registers.
  As the first output carrying the new token, this box sets the asset's EIP-4 metadata, so the borrower
  cannot choose it.
- `OUTPUTS(1)`: the bond, under `CONST_BOND_HASH`, carrying exactly `CONST_COLLATERAL_TOKENS` and at least
  `CONST_COLLATERAL_ERG`. R4 is the offer's box id, R5 is the borrower's hash, and R6, R7 and R8 are the
  offer's terms (`CONST_REPAYMENT`, `CONST_BATCHING_FEE`, `CONST_MATURITY`, `CONST_LIQ_SCRIPT_HASH`).

### Tx 3A: Cancel an Offer
The lender takes the principal back, in a transaction that also spends a box belonging to their wallet.

## Borrower Side: Orders

### Tx 1B: Create an Order
The borrower sends the collateral (ERG and tokens) to an instance of `ZC_Order` or `ZC_OrderOnClose`.
The box's whole contents become the bond's collateral. The terms are constants:
- `CONST_BOND_HASH`: which bond, ERG or token
- `CONST_BORROWER_HASH`: the borrower's wallet; receives the principal, authorizes a cancel
- `CONST_PRINCIPAL_ERG` and `CONST_PRINCIPAL_TOKENS`: the least nanoERG and the exact tokens the borrower
  receives. An ERG loan has no tokens; a token loan pays one token and a minimal amount of ERG.
- `CONST_REPAYMENT`, `CONST_BATCHING_FEE`, `CONST_LIQ_SCRIPT_HASH`: copied to the bond
- `CONST_MATURITY` (fixed height), or `CONST_DURATION` (on-close): see below

The order may carry R4 (optional, never read): `Coll[Coll[Byte]]` holding the borrower's ErgoTree, the
preimage of `CONST_BORROWER_HASH`, which a lender needs to pay the principal. Without it, a lender looks
for that tree among the boxes of the transaction that created the order.

### Tx 2B: Fill an Order
A lender pays the principal and creates the bond. The order is `INPUTS(0)`, so the fill mints the bond
asset, with the order's box id as its id. One order fills per transaction.
- `OUTPUTS(0)`: the bond asset, (asset, 1) at tokens(0), anywhere the lender likes. It sets the asset's
  EIP-4 metadata, which is the lender's own choice here.
- `OUTPUTS(1)`: the bond, under `CONST_BOND_HASH`: the order's tokens unchanged, and at least the order's
  value. R4 is the order's box id, R5 is `CONST_BORROWER_HASH`, and R6 and R8 are the order's terms.
- `OUTPUTS(2)`: the principal, to the borrower.

Maturity (bond R7) depends on the order type:
- **Fixed height:** exactly `CONST_MATURITY`, which must be at least two blocks above the fill, so the
  borrower always has a block in which to repay. Borrowers should cancel orders left open near maturity.
- **On-close:** `HEIGHT + CONST_DURATION`, stamped by the lender when building the fill, and allowed to
  trail by up to 8 blocks so the fill can still confirm. A fill unconfirmed after 8 blocks must be
  rebuilt. `CONST_DURATION` must be at least 30 blocks.

### Tx 3B: Cancel an Order
The borrower takes the collateral back, in a transaction that also spends a box belonging to their wallet.

## Bonds

### Tx 4: Repay a Bond
Anyone may repay a bond before its maturity height; it is normally the borrower. No context variable is
attached: its absence is what selects repayment. For the bond at `INPUTS(i)`:
- `OUTPUTS(i)`: the repayment, to the vault. R4 is the bond's box id, R5 its bond asset's id (the bond's
  R4). For an ERG bond it holds at least the repayment; for a token bond, exactly one token,
  `CONST_TOKEN_ID`, at least the repayment, and at least 0.001 ERG.
- `OUTPUTS(i + 1)`: the collateral, to the borrower hash in R5, with exactly the bond's tokens, at least
  the bond's value, and R4 the bond's box id.

Each repayment takes two outputs, so repaying several bonds in one transaction needs a filler input
between bond inputs.

### Tx 5: Liquidate a Bond
Anyone may liquidate a bond once its liquidation script allows it. The liquidation script's bytes are
attached to the bond input as context variable 0. For the bond at `INPUTS(i)`:
- `OUTPUTS(i)`: the collateral, to the vault, with exactly the bond's tokens, and at least the bond's
  value less the batching fee. R4 is the bond's box id, R5 its bond asset's id.

Liquidations batch freely: bonds at `INPUTS(0..n)` settle into `OUTPUTS(0..n)`.

### Tx 6: Claim from the Vault
The holder of a bond asset spends every `BondAssetVault` box whose R5 names that asset, alongside the
asset, and burns it. The outputs are theirs to choose. All of an asset's vault boxes must be claimed in
one transaction, since the asset is gone afterwards.

## Liquidation Scripts
A bond's R8 is the `blake2b256` of a serialized liquidation *expression* (not an ErgoTree). A liquidator
attaches those bytes as context variable 0, and the bond runs them with `executeFromVar`, with the bond
as `SELF`. The script decides only *when* a bond may be liquidated; where the collateral goes, the
batching fee and the settlement tags are always the bond's. `ContractClient` prints each script's hash
and bytes.

- `MaturityLiquidation`: `sigmaProp(HEIGHT >= SELF.R7[Int].get)`. Anyone may liquidate from the maturity
  height on, exactly as V1 bonds allowed. Repayment closes at the same height, so there is never a block
  in which both or neither are open.

A bond trusts its liquidation script completely. A script that is always true makes a bond liquidatable
at once, so orders and offers name their script as a term, and both sides must check it.

## Changes from V1
| | V1 | V2 |
|---|---|---|
| Lender | A public key in R8 | Whoever holds the bond asset; every bond pays the vault |
| Lender's position | Not transferable | A bond asset, minted with the bond, tradeable and claimed through the vault |
| Repayment | Signed by the borrower | Anyone may pay it |
| Liquidation | From maturity on, by anyone, collateral in full | Set by a liquidation script; `MaturityLiquidation` is V1's rule, less at most the batching fee |
| Protocol fees | 0.5% dev fee, plus up to 0.5% UI fee | None |
| Order and offer terms | Registers, one address per contract | Constants, one template per contract type |
| Offers | Funded directly by the lender | Funded directly by the lender; closing one mints the lender's bond asset |

SigmaFi V1's contracts, documentation and mainnet addresses remain in this repository's history, up to
commit `5210d0c`.

## KYA / Note To Users
Lenders should always be wary to ensure that the bond orders they accept match
their own personal risk/reward ratio. Lenders of NFT-backed loans should also
be sure that the NFTs used as collateral are real and minted by their original artists.
Both sides should check a bond's liquidation script before agreeing to it, and buyers of a bond asset
should verify it by its genesis box rather than its name.
The creator of SigmaBonds is not responsible for the gain/loss of any digital
assets and/or perceived value incurred through the use of SigmaBonds.
