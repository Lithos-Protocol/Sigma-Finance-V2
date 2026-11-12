# SigmaBonds

SigmaBonds is a P2P zero-coupon bond protocol on Ergo. Borrowers post orders funded with collateral;
lenders fill them by paying the principal. Collateral can combine ERG, fungible tokens and NFTs.
There are no lender offers or bond-asset generators in the current protocol.

Every bond repays one fixed amount before maturity, in ERG or one token. Repayment or liquidation
always settles into `BondAssetVault`, where the holder of the circulating bond asset claims it.
There are no protocol fees.

## Bond assets and verification

Filling an order mints exactly **two units** of a new SigmaFi bond asset:

- One unit goes to the lender and may be traded.
- One unit sits at `tokens(0)` in the bond and must be burned when the bond settles.

The order must be `INPUTS(0)`, so its box id becomes the asset id. The bond also records this id in R4.
An order fills once and creates one bond. The circulating unit claims the bond's vault settlement by
being spent and burned alongside it. Never send that unit into the vault: a vault box carrying its own
claim asset is spendable by anyone.

Verify the mint's genesis box as a genuine `ZC_Order` or `ZC_OrderOnClose` instance, using
`ScriptGenerator.ContractTemplate.matches`. A template hash alone omits **every** constant: a forged
order with a different mint amount can share the genuine template hash. Also verify the named bond
contract, repayment asset and liquidation expression against the intended protocol and terms.
The CLI generates EIP-4 metadata from the fill's terms, as described below. Other clients may choose
different metadata; names and descriptions prove nothing.

The two-unit split is a starting point for on-chain association with an unspent bond, not a complete
on-chain authentication mechanism. Anyone can fund a box under a genuine bond script with arbitrary
registers or create a forged mint template. Checking only a bond's script and a shared token id does
not authenticate the genesis or enforce the original supply. The client currently recognizes bond
scripts and shapes; its bond listings do not prove mint provenance. An on-chain consumer needs a
separate authenticated link to the mint and current bond UTXO before treating a token as a verified bond.

## Contracts and compilation

| Contract | Role | Discovery |
|---|---|---|
| `P2P/ZC_ERG_Bond` | A bond repaid in nanoERG | Exact address |
| `P2P/ZC_Token_Bond` | A bond repaid in `CONST_TOKEN_ID` | Exact address per repayment token |
| `P2P/ZC_Order` | Collateral-backed borrower order with fixed maturity | Template, then `matches` |
| `P2P/ZC_OrderOnClose` | Borrower order with duration counted from fill | Template, then `matches` |
| `lender-scripts/BondAssetVault` | Settlement claimed by burning the lender's unit | Exact address |
| `liquidations/MaturityLiquidation` | Maturity, collateral payout and executor fee rules | Expression hash |

`ScriptGenerator` compiles the vault's full ErgoTree hash into each bond as `CONST_VAULT_HASH`.
Orders commit to their bond's full script hash. Every recognized bond therefore pins the vault directly;
there is no lender destination register. Hashes of wallets and contracts are `blake2b256` of full
ErgoTree bytes.

Order terms are constants, not registers. Compile each template once and instantiate it with
`ContractTemplate.withTerms`; recompilation can change the template through constant folding.
`ContractClient` prints current addresses, hashes, expression bytes and term constant positions.
V2 is not deployed. Contract edits change script hashes and addresses.

## The bond box

| Field | Meaning |
|---|---|
| `tokens(0)` | Bond asset, amount 1; burned on settlement |
| `tokens(1..)` | Collateral tokens |
| `value` | Borrower collateral nanoERG plus lender-funded batching reserve |
| R4: `Coll[Byte]` | Originating order id, also the bond asset id |
| R5: `Coll[Byte]` | Borrower's script hash; collateral returns here on repayment |
| R6: `Coll[Long]` | Exactly `(repayment, batchingFee, principalErg, maturityHeight)`; ERG fields are nanoERG |
| R7: `Coll[(Coll[Byte], Long)]` | Agreed principal token list; empty for an ERG loan |
| R8: `Coll[Byte]` | Hash of the serialized liquidation expression |

R6(3) is a `Long` height; comparisons widen `HEIGHT` to `Long`. R6(2) and R7 record the agreed
principal terms, not collateral or the fee reserve. An accidental principal overpayment does not change
these terms. Custom liquidation expressions can read these fields directly, after authenticating the
bond origin as needed. The borrower collateral ERG is `bond.value - R6(1)`.

The vault is a compiled constant. This layout replaces the earlier two-element R6 / Int-maturity R7
layout, as well as the `Generators` layout with vault hash
in R8 and liquidation hash in R9. Builders, readers and scripts must use the matching contract version;
old boxes are not migrated by these source changes.

## Create, fill or cancel an order

The borrower funds a `ZC_Order` or `ZC_OrderOnClose` box with the collateral. Its entire token list and
at least its entire ERG value must pass into the resulting bond. The terms are:

- `CONST_BOND_HASH`: full hash of the ERG bond or the token bond for the repayment asset.
- `CONST_BORROWER_HASH`: principal recipient, collateral recipient and cancellation authority.
- `CONST_PRINCIPAL_ERG`, `CONST_PRINCIPAL_TOKENS`: principal ERG floor and exact principal tokens.
- `CONST_REPAYMENT`, `CONST_LIQ_SCRIPT_HASH`: the bond's repayment and liquidation terms.
- There is no batching-fee order constant. On fill, context variable 0 on the **order input** is a
  required `Long` specifying the lender-selected, nonnegative nanoERG fee reserve.
- `CONST_MATURITY` (`Long`) for fixed orders, or `CONST_DURATION` (`Long`) for on-close orders.

An ERG loan has an empty principal token list; a token loan pays one token plus the box's ERG floor.
The client checks that principal and repayment asset match. The order contract itself trusts the
borrower's chosen bond hash and does not check that pairing.

An order may carry an optional R4 hint of type `Coll[Coll[Byte]]`, containing the borrower's ErgoTree.
The client verifies that it hashes to the borrower term. Contracts never read the hint. Without it,
the client looks for that tree in the transaction that created the order.

For a fill, the order is `INPUTS(0)` and the lender supplies the principal:

| Output | Contents |
|---|---|
| `OUTPUTS(0)` | Lender's unit: `(order.id, 1)` at tokens(0), to the lender's chosen script |
| `OUTPUTS(1)` | Bond: `(order.id, 1)`, followed by the order's unchanged tokens, value at least the order's plus the lender-selected reserve |
| `OUTPUTS(2)` | Principal to the borrower |

The order pins bond R4-R8 and requires exactly two new units across **all** outputs. The lender unit
is the first output carrying the asset, so its R4-R6 define EIP-4 name, description and decimals.
One order fills per transaction.

The CLI names the lender's issuing unit `SigmaFi-ZC-Bond-` followed by the first six hexadecimal
characters of its token id, matching LithosDex's `LD-NFT-` suffix convention. R5 is compact UTF-8 JSON,
using the older Lithos SigmaFi bond field names for principal, repayment, collateral and liquidation.
It includes the full bond asset id, borrower hash, principal nanoERG/tokens, repayment amount and asset,
actual stamped maturity, collateral nanoERG/tokens, batching fee, liquidation name/hash and build height.
On-close orders additionally include their requested `durationBlocks`; both schedules include the actual
maturity. Amounts use integer nanoERG or raw token units. Build height is not a confirmation height,
and the minted bond box id is not included because it is not known before the transaction is assembled.
R6 remains UTF-8 `"0"` for zero decimals. The description is generated at fill time, not a static default.

Fixed maturity must be at least two blocks above inclusion height, leaving a full block to repay.
Borrowers should cancel orders left open near maturity. An on-close order requires at least 30 blocks
of duration; its bond maturity may trail `HEIGHT + duration` by up to 8 blocks. A fill built at height
h remains valid through h + 8, then must be rebuilt.

Both order types pin the fee in bond R6(1) to the lender's context variable and require
`bond.value - order.value >= fee`, with `fee >= 0`. This subtraction avoids addition overflow and
preserves the entire borrower collateral. The lender supplies the reserve in addition to the principal,
issuing-unit minimum ERG and miner fee; the reserve is not deducted from the principal. It can exceed
the collateral value if fully funded. Cancellation does not require the fill context variable.

The CLI accepts `fill-order <order id> --batching-fee <ERG>` (minimum and default 0.05 ERG). It rejects
`--batching-fee` on `create-order`. The fill stores agreed principal ERG/tokens in bond R6(2)/R7,
pins maturity in R6(3), and uses checked addition when funding the reserve.

The borrower may cancel before fill by co-spending a box guarded by the borrower script, without
minting the asset. Cancellation leaves outputs unconstrained. Use an appropriate borrower script:
if anyone can spend that script, anyone can satisfy the cancellation authorization.

## Repay a bond

Repayment is permissionless and must occur strictly below maturity. Leave context variable 0 unset.
Set context variable 1 (`Int`) to the settlement output index and variable 2 (`Int`) to the
collateral-return output index on each bond input. The indices must be in range and distinct:

- `OUTPUTS(getVar[Int](1).get)` pays the vault, with R4 = bond box id and R5 = bond asset id. ERG bonds pay at least
  the repayment value **plus the fee reserve**. Token bonds pay exactly one token id, `CONST_TOKEN_ID`,
  at least the repayment quantity, plus at least **0.001 ERG + the fee reserve**. Settlement R6 must be
  `0.toByte`. This refunds the unused lender-funded reserve to the circulating bond asset holder.
- `OUTPUTS(getVar[Int](2).get)` returns all collateral tokens and at least `bond.value - bond.R6(1)` to the borrower, with
  R4 = bond box id.
- No output may carry the bond asset: the singleton is burned.

Multiple repayments can use adjacent bond inputs without filler inputs; each maps its own two outputs.
Bond-specific R4 tags prevent one output from satisfying two different settlements or returns.

## Liquidate a bond

Attach the agreed serialized liquidation **expression** as context variable 0 on each bond input.
Its `blake2b256` must match R8, and the bond runs it through `executeFromVar[SigmaProp](0)`.
The bytes are an expression, not an ErgoTree; `ContractClient` prints both bytes and hash.

Set context variable 1 (`Int`) to the settlement output index on each bond input. The bond always
requires `OUTPUTS(getVar[Int](1).get)` to use the compiled vault script,
R4 = bond box id, R5 = bond asset id, and R6 = `1.toByte`. It also requires the singleton to be burned. **The liquidation
expression controls eligibility, vault ERG value and vault tokens.** It can therefore enforce a
conversion of collateral tokens into ERG or another agreed asset in the same transaction.

`MaturityLiquidation` implements the standard rule:

- `HEIGHT.toLong >= R6(3)`.
- Vault tokens exactly equal the collateral tokens after the singleton.
- Vault value is at least `bond.value - R6(1)`, preserving all borrower collateral ERG.

The executor keeps up to the lender-funded reserve and pays the miner fee from it. With a zero fee, the executor
supplies the miner fee separately. Multiple bonds may liquidate together, each selecting its own
settlement output through context variable 1. The CLI currently builds only the standard maturity liquidation, including batching.
Custom swap expressions and their transaction builders require their own review and implementation.

Both parties must inspect the liquidation expression: an always-true expression can liquidate
immediately and permit any vault payout value or tokens. The bond protects the destination and claim
identity, while the chosen expression protects the economics.

### Rules for liquidation expressions

- Read the settlement index from `getVar[Int](1)`, exactly as the bond does. Do not use
  `CONTEXT.selfBoxIndex` or an independent mapping when checking its payout economics.
- Treat context variables as untrusted hints. Require the correct types and validate index bounds
  before indexing outputs. Missing or incorrectly typed required variables must reject the spend.
- Require `R4[Coll[Byte]] == SELF.id` on every additional bond-specific payout. The bond already
  enforces this on the settlement, along with vault script, R5 claim asset, R6 = `1.toByte`, and singleton burn.
- Require distinct indices for obligations that need separate payouts within one bond. R4 prevents
  sharing across different bonds, but cannot distinguish two roles belonging to the same bond.
- Read principal ERG from bond R6(2), principal tokens from R7, and Long maturity from R6(3).
  Use `bond.value - bond.R6(1)` when valuing borrower collateral; the lender-funded reserve may be
  arbitrarily large and must not inflate collateral ratios. Authenticate the bond origin before trusting terms.
- Enforce eligibility and the required value, assets and recipients of the selected outputs.
  Input-ID tags establish association; they do not establish payout amounts or authorization.
- Reserve context variable 0 for the serialized expression, 1 for settlement and 2 for the repayment
  collateral return. Additional liquidation-specific variables must document their types and meanings.

These mappings change the bond and maturity-expression hashes. Builders and orders must use the
matching compiled contracts; existing boxes retain their original scripts and mapping rules.

### Settlement registers

| Register | Meaning |
|---|---|
| R4: `Coll[Byte]` | Closing bond box id |
| R5: `Coll[Byte]` | Bond asset that claims the vault |
| R6: `Byte` | `0` for repayment; `1` for liquidation |

Both bond scripts enforce the path marker, including for custom liquidation expressions. Missing,
wrongly typed, opposite-path and other marker values reject the bond spend. The vault does not use
R6 to authorize a claim. Anyone can create arbitrary vault boxes, so a marker alone does not prove a
genuine bond settlement; verify the closing bond transaction and its provenance.

## Claim from the vault

The circulating asset holder spends the vault settlement alongside their unit and burns it. The
outputs are theirs to choose. Every vault box for the asset must be claimed in that same transaction;
boxes sent there later are locked because the claim unit is gone.

The vault assumes a verified two-unit mint and that the bond burned its own unit. The settlement
transaction can also sweep any pre-existing vault boxes for that asset using the bond singleton;
the protocol creates no vault settlement before closing the bond. Arbitrary donations to the vault
and boxes carrying their own claim asset do not carry the protocol's provenance guarantees.

## Development and evidence

See [TESTING.md](./TESTING.md) for offline interpreter signing tests and CLI tests over `FakeNode`.
The suites exercise both repayment assets, both order schedules, mint splitting, cancellation,
repayment, maturity liquidation, batching, vault claims and an atomic collateral-to-ERG conversion
with a funded counterparty. That conversion is a test expression, not a deployed DEX integration.
Offline tests do not establish live-node admission, maximum transaction cost, storage-rent safety,
deployment validity or a complete on-chain proof of bond provenance and current unspent status.

SigmaFi V1 contracts and addresses remain in repository history through commit `5210d0c`.
Lenders and borrowers must evaluate collateral authenticity and their own risk before agreeing to a loan.
