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
Asset names and EIP-4 metadata are the lender's choice and prove nothing.

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
| `value` | Collateral nanoERG |
| R4: `Coll[Byte]` | Originating order id, also the bond asset id |
| R5: `Coll[Byte]` | Borrower's script hash; collateral returns here on repayment |
| R6: `Coll[Long]` | Exactly `(repayment, batching fee)`; fee is nanoERG |
| R7: `Int` | Maturity height; repayment requires `HEIGHT < R7` |
| R8: `Coll[Byte]` | Hash of the serialized liquidation expression |

The vault is a compiled constant. This layout replaces the earlier `Generators` layout with vault hash
in R8 and liquidation hash in R9. Builders, readers and scripts must use the matching contract version;
old boxes are not migrated by these source changes.

## Create, fill or cancel an order

The borrower funds a `ZC_Order` or `ZC_OrderOnClose` box with the collateral. Its entire token list and
at least its entire ERG value must pass into the resulting bond. The terms are:

- `CONST_BOND_HASH`: full hash of the ERG bond or the token bond for the repayment asset.
- `CONST_BORROWER_HASH`: principal recipient, collateral recipient and cancellation authority.
- `CONST_PRINCIPAL_ERG`, `CONST_PRINCIPAL_TOKENS`: principal ERG floor and exact principal tokens.
- `CONST_REPAYMENT`, `CONST_BATCHING_FEE`, `CONST_LIQ_SCRIPT_HASH`: the bond's repayment and liquidation terms.
- `CONST_MATURITY` for fixed orders, or `CONST_DURATION` for on-close orders.

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
| `OUTPUTS(1)` | Bond: `(order.id, 1)`, followed by the order's unchanged tokens, value at least the order's |
| `OUTPUTS(2)` | Principal to the borrower |

The order pins bond R4-R8 and requires exactly two new units across **all** outputs. The lender unit
is the first output carrying the asset, so its R4-R6 define EIP-4 name, description and decimals.
One order fills per transaction.

Fixed maturity must be at least two blocks above inclusion height, leaving a full block to repay.
Borrowers should cancel orders left open near maturity. An on-close order requires at least 30 blocks
of duration; its bond maturity may trail `HEIGHT + duration` by up to 8 blocks. A fill built at height
h remains valid through h + 8, then must be rebuilt.

Both order types bound the fee to `0 <= fee <= bond.value - 0.001 ERG`, preserving the existing order
terms. `MaturityLiquidation` uses this ceiling; a custom expression determines its own payout rule.

The borrower may cancel before fill by co-spending a box guarded by the borrower script, without
minting the asset. Cancellation leaves outputs unconstrained. Use an appropriate borrower script:
if anyone can spend that script, anyone can satisfy the cancellation authorization.

## Repay a bond

Repayment is permissionless and must occur strictly below maturity. Leave context variable 0 unset.
For the bond at `INPUTS(i)`:

- `OUTPUTS(i)` pays the vault, with R4 = bond box id and R5 = bond asset id. ERG bonds pay at least
  the repayment value. Token bonds pay exactly one token id, `CONST_TOKEN_ID`, at least the repayment
  quantity, plus at least 0.001 ERG.
- `OUTPUTS(i + 1)` returns all collateral tokens and at least the bond's value to the borrower, with
  R4 = bond box id.
- No output may carry the bond asset: the singleton is burned.

Multiple repayments need filler inputs between bond inputs, since each bond requires two outputs.
Bond-specific R4 tags prevent one output from satisfying two different settlements or returns.

## Liquidate a bond

Attach the agreed serialized liquidation **expression** as context variable 0 on each bond input.
Its `blake2b256` must match R8, and the bond runs it through `executeFromVar[SigmaProp](0)`.
The bytes are an expression, not an ErgoTree; `ContractClient` prints both bytes and hash.

For the bond at `INPUTS(i)`, the bond always requires `OUTPUTS(i)` to use the compiled vault script,
R4 = bond box id, and R5 = bond asset id. It also requires the singleton to be burned. **The liquidation
expression controls eligibility, vault ERG value and vault tokens.** It can therefore enforce a
conversion of collateral tokens into ERG or another agreed asset in the same transaction.

`MaturityLiquidation` implements the standard rule:

- `HEIGHT >= R7`.
- Vault tokens exactly equal the collateral tokens after the singleton.
- Vault value is at least `bond.value - R6(1)`.

The executor keeps up to the batching fee and pays the miner fee from it. With a zero fee, the executor
supplies the miner fee separately. Multiple bonds may liquidate together with bond input i settling
into output i. The CLI currently builds only the standard maturity liquidation, including batching.
Custom swap expressions and their transaction builders require their own review and implementation.

Both parties must inspect the liquidation expression: an always-true expression can liquidate
immediately and permit any vault payout value or tokens. The bond protects the destination and claim
identity, while the chosen expression protects the economics.

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
