# SigmaFi V2 reference for the Lithos client rewrite

This document describes the borrower-order protocol committed in
`23676ab6ba0f5b19912694eb83ce912fbc3109e7` and made canonical on `master` by merge
`bcd9ef215978719cc7644698a076fd03ed974cd2` on November 6, 2026. It replaces the earlier
one-unit bond implementation currently consumed by Lithos. It is an integration reference, not a
deployment manifest. [SigmaBonds.MD](../SigmaBonds.MD) describes the protocol in full.

The generated block contains full ErgoTrees, raw template hex, both kinds of hash, every order/token
constant with its type and serialized value, concrete ERG/SigUSD addresses, and liquidation expression bytes.
The authored sections describe how to use them. Do not hand-edit the generated block.

## Compiler and regeneration

The reference uses this repository's Scala 2.12.18, Appkit 6.0.0, resolved sigma-state 6.0.2,
and sbt 1.9.8. Compilation uses ErgoTree v3 with constant segregation and the size bit.
The tree bytes are network-independent here; addresses encode the chosen network.

From the Sigma-Finance-V2 repository root, using cached dependencies and loopback access for the mock context:

```text
sbt "Test / runMain org.lithosprotocol.sigmafi.specs.bonds.LithosReference --write"
sbt "Test / runMain org.lithosprotocol.sigmafi.specs.bonds.LithosReference --check"
sbt test
```

The default suite checks that the generated block still matches the current compiler output and that
substituting the repayment-token constant in the exported prototype reproduces a directly compiled
SigUSD bond. Generation does not query an Ergo node, explorer, or wallet; its HTTP traffic is to local
mock servers only. Hashes below are computed over decoded bytes, never over hex text.

Lithos currently declares Appkit 6.0.1 and sigma-state 6.0.6 in `lithos-lib/build.sbt`. Import the exact
pinned trees and verify serialization and substitution on that dependency set. Do not assume a fresh
compilation with another compiler version gives identical scripts or addresses. Such a cross-version
comparison has not been performed as part of producing this reference.

## How Lithos should consume the registry

- Pin the complete vault and ERG bond ErgoTrees. Verify them by full bytes or full-tree hash.
- Instantiate token bonds from the `7e`-sentinel prototype, replacing only `CONST_TOKEN_ID` at the
  generated constant index. Keep every other constant fixed, including the vault hash.
- Instantiate orders from their full prototype ErgoTrees, replacing exactly their eight named terms
  with values of the same Sigma types. The sentinel borrower/hash/principal values are placeholders.
- Discover token bonds and orders by template hash, then verify the candidate's header, raw template,
  constant count, every fixed constant, and every term's type. Discovery is not authentication.
- A raw template hex string omits the constant pool and is not a complete ErgoTree. It cannot be
  used as an address, an executable guard, or the input to `CONST_BOND_HASH`.
- `CONST_BOND_HASH`, borrower hashes and the compiled vault hash use the complete ErgoTree bytes.
  `CONST_LIQ_SCRIPT_HASH` and bond R8 use raw serialized expression bytes from `Contract.compileValue`.
  Those expression bytes differ from the liquidation compilation tree listed for tooling.
- Token IDs are 32 raw bytes. The serialized `Coll[Byte]` constant in a registry row includes a Sigma
  type/length prefix; decode it before treating its value as a token ID.

The canonical client entry points are `ScriptGenerator.ContractTemplate.matches`, `withTerms`,
`mkZcBondContract`, `mkLiquidationScript`, and the readers/builders in `app/Protocol.scala` and
`app/SigmaFiApp.scala`. Always replace constants in the compiled prototype rather than replacing hex
substrings or recompiling terms that might be folded by the compiler.

## Box schemas

ERG values are in nanoERG. Monetary amounts, token quantities and bond/fixed-order maturity heights are `Long`.
On-close durations are also `Long`. Context `HEIGHT` is `Int`; arithmetic widens to `Long` before addition,
with overflow checks for schedule calculations.
Hashes and token IDs are `Coll[Byte]` (32 bytes for the protocol's hash/id values).

| Bond field | Meaning |
|---|---|
| `value` | Borrower collateral ERG plus lender-funded batching reserve |
| `tokens(0)` | `(bondAssetId, 1)`; this is the bond singleton, not collateral |
| `tokens(1..)` | Collateral tokens, preserving the original order's sequence |
| R4: `Coll[Byte]` | Originating order id, equal to the bond asset id for an authentic fill |
| R5: `Coll[Byte]` | Borrower full-ErgoTree hash |
| R6: `Coll[Long]` | Exactly `(repayment, batchingFee, principalErg, maturityHeight)` |
| R7: `Coll[(Coll[Byte], Long)]` | Agreed principal tokens; empty for an ERG loan |
| R8: `Coll[Byte]` | Liquidation expression hash |

R6(2)/R7 are pinned to agreed order principal terms, even if the principal output is overfunded.
Borrower collateral ERG is `bond.value - R6(1)`. R6(3) is a Long height.
This replaces the prior two-element R6 and Int-maturity R7 schema; old boxes do not migrate.

The vault is compiled into the bond. There is no lender destination register and no protocol R9.
Do not confuse this layout with the earlier Generators layout that used R8 for the vault and R9 for
liquidation. Readers should validate types/lengths and singleton quantity before exposing a bond.
Check `tokens(0).id == R4` when claiming an authentic order association; the bond spend script itself
does not read R4 or prove its origin.

| Other box | Contents and registers |
|---|---|
| Borrower order | Entire ERG/token contents are collateral; terms are script constants |
| Optional order hint | R4: `Coll[Coll[Byte]]`, containing the borrower's full ErgoTree; hash-check the hint |
| Lender's minted unit | `tokens(0) = (order.id, 1)`; R4/R5/R6 are EIP-4 UTF-8 name/description/`"0"` |
| Vault settlement | R4: closing bond box id, R5: bond asset id (both `Coll[Byte]`); R6: `Byte`, 0 repaid / 1 liquidated |
| Collateral return | R4: closing bond box id; script is the borrower hash's preimage |

An order's optional hint is never read by the contracts. Without it, resolve the borrower's tree from
wallet knowledge or the order's creating transaction; verify its full-tree hash before using it.
Names/descriptions and any client metadata never authenticate an asset.

### Generated EIP-4 metadata

`src/main/scala/app/BondAssetMetadata.scala` generates issuance metadata when an order is filled. The name is
`SigmaFi-ZC-Bond-<first6>`, with the first six hexadecimal characters of the asset id, matching LithosDex's
LD-NFT suffix length. The CLI generates both name and description; it takes no manual metadata flags.
The compact JSON description keeps the older Lithos SigmaFi field names and adds identifiers/build context:

| JSON field | Value |
|---|---|
| `type` | `"SigmaFi ZC-Bond"`, retained for compatibility with the older JSON description |
| `bondAsset` | Full token id, equal to the spent order box id |
| `borrowerHash` | Borrower full-ErgoTree blake2b256, hex |
| `principal` | `{ "nanoErgs": Long, "tokens": [{ "id": hex, "amount": Long }] }` for the agreed principal terms |
| `repayment` / `repayToken` | Raw repayment amount and token-id hex, or `"ERG"` for nanoERG repayment |
| `maturity` | Actual stamped bond R6(3), as a Long for either schedule |
| `durationBlocks` | Requested duration, present only for on-close orders |
| `collateral` | Same nanoERG/tokens shape as principal; excludes the minted bond singleton and lender-funded reserve |
| `batchingFee` | Lender-selected nanoERG reserve added on fill; refunded to vault on repayment |
| `liquidation` / `liquidationHash` | Known expression name and raw-expression blake2b256, hex |
| `height` | Build-context height, not confirmed inclusion height |

R4/R5/R6 on the lender's issuing unit remain UTF-8 name, JSON description and `"0"` decimals. Long
amounts are emitted as exact integer JSON numbers; consumers must preserve integer precision. The bond
box id is not included: it depends on the assembled transaction, which itself includes this metadata.
Metadata is a display snapshot and must not replace authenticated order/bond reads.

## Transaction assembly and index pairing

| Operation | Inputs | Required outputs | Context and burn |
|---|---|---|---|
| Create order | Borrower funding | Collateral under the instantiated order | No mint |
| Fill order | Order must be input 0; lender funds principal and extra boxes/fee | Output 0 lender unit; output 1 bond; output 2 principal to borrower | Required order-input ctx var 0: Long fee reserve, funded above order value; mint exactly 2 units with id `order.id`; one in output 0, one in output 1 |
| Cancel order | Order plus a box guarded by borrower script | Refund as borrower chooses | No units minted; borrower authorization is by co-spend |
| Repay bond at input i | Bond plus repayment funding | Var 1 selects repayment to vault; var 2 selects full collateral return | Context var 0 absent; vars 1/2 are distinct in-range Int indices; explicitly burn the bond singleton |
| Liquidate bond at input i | Bond with its agreed expression in context var 0; optional fee funding | Var 1 (Int) selects settlement to vault, tagged with bond id and asset id | Burn the singleton; expression decides eligibility, ERG and tokens |
| Claim vault | All vault boxes for an asset plus the holder's circulating unit | Holder chooses outputs | Burn the circulating unit |

For every settlement, vault R4 must equal the closing bond's box id, and vault R5 the bond's token id.
The bond enforces settlement R6: Byte = 0 for repayment or 1 for liquidation. The vault ignores that
marker for authorization; verify the originating bond spend before trusting it.
Collateral-return R4 must equal that same bond box id. Repayment returns `tokens.drop(1)` and at
least `bond.value - bond.R6(1)` ERG to the borrower. The unused reserve is refunded to the lender's
vault: an ERG repayment pays at least `bond.R6(0) + bond.R6(1)`; a token repayment pays exactly one
token ID, `CONST_TOKEN_ID`, at least bond R6(0), plus at least `0.001 ERG + bond.R6(1)`.
Use checked additions off-chain and subtraction-based comparisons on-chain to avoid overflow.

Settlement output positions are selected per input through context var 1 (Int), independently of
input positions. Repayment also requires var 2 (Int) for a distinct collateral-return output; no filler
inputs are needed. Liquidation expressions must check the same var-1 output as the bond, tag extra
bond-specific payouts with R4 = SELF.id, and keep separate payout roles distinct within each bond.
Builders must update context indices when rearranging outputs. Specify burns explicitly in Appkit;
letting the singleton escape into change violates the bond on both settlement paths. Avoid selecting
the lender's circulating unit as automatic settlement funding: no bond-asset units may survive in the
settlement transaction's outputs. Burning the claim unit along with the singleton loses the lender's claim.

Orders preserve their complete collateral token list after prepending the singleton. The order pins
the new bond's borrower, repayment, agreed principal ERG/tokens, schedule and liquidation hash.
There is no `CONST_BATCHING_FEE`. Required ctx var 0: Long on the order input selects a nonnegative
fee; bond R6(1) must match it, and `bond.value - order.value >= fee` funds it without consuming
collateral. The CLI chooses it with `fill-order --batching-fee` (default 0.002 ERG). Cancellation
requires no fill context variable. An ERG principal has no tokens;
a token principal uses one token and an ERG floor. Pair the principal with the correct repayment bond
off-chain: the order trusts its `CONST_BOND_HASH` and does not enforce that pairing itself.

Fixed-height orders require maturity at least inclusion height + 2. On-close duration must be at
least 30 blocks, and accepted maturity lies in `[HEIGHT + duration - 8, HEIGHT + duration]`. The builder
may stamp `buildHeight + duration`; a fill can confirm through buildHeight + 8, then must be rebuilt.
Use checked arithmetic in client code and account for tip height versus intended inclusion height.

`MaturityLiquidation` requires `HEIGHT.toLong >= bond.R6(3)`, exact collateral tokens after removing the singleton,
and vault value at least `bond.value - batchingFee`. The originating order requires that nonnegative
reserve on top of its entire collateral value, preserving at least 0.001 ERG after removing it. A batch executor retains at most the sum of fees, less the miner fee;
if fees do not cover that fee, supply additional funding. Validate the actual serialized outputs against
the node's minimum value rules rather than treating 0.001 ERG as a consensus guarantee for every size.

For custom liquidation expressions, do not assume the maturity rule's output assets, ERG floor, or
fee economics. They may atomically convert collateral to ERG or another asset. The bond still pins
the vault, the R4/R5 tags, R6 = 1.toByte, and the burn. Initially enable automatic batching only for the exact known
maturity expression hash. A custom builder requires separate review and signing tests.

## Lithos migration map

These paths were inspected in Lithos-Client at `572d2cc016a68ec2b4b5e4653b6e2df77aaa8f32`.
They identify rewrite surfaces; no Lithos files were changed while producing this document.

| Lithos surface | Required change |
|---|---|
| `lithos-lib/src/main/scala/sigmafi/contracts/SigmaFiContracts.scala` and its bundled `.ergo` resources | Replace pinned trees, templates, constant indices, source copies and maturity expression together; remove offer definitions |
| `app/transactions/batching/sigmafi/SigmaFiBoxes.scala` | Read asset from first token, validate amount 1 and agreement with R4, read four R6 Longs and R7 principal tokens, expose collateral as `assets.drop(1)` and ERG as value minus reserve; remove offer parsing |
| `app/transactions/batching/sigmafi/SigmaFiUserTransactions.scala` | Prepend bond singleton on fill, mint supply 2, fund the lender-selected reserve using order ctx var 0, pin principal fields, refund reserve to vault on repayment, stamp settlement R6 = 0.toByte, and burn singleton; remove offer creation/close/cancel flows |
| `app/transactions/batching/sigmafi/SigmaFiTransactions.scala` | Set new expression bytes and output-index ctx vars, strip singleton from vault assets, stamp settlement R6 = 1.toByte, and explicitly burn one unit per liquidated bond |
| `app/transactions/batching/sigmafi/SigmaFiBatcher.scala` | Refresh recognized script/template hashes and maturity-expression allowlist; preserve candidate and authoritative-UTXO checks |
| `app/transactions/engine/execution/SigmaFiAPIExecution.scala` | Rework order/fill, verification and settlement paths for the new layout; remove offers |
| `app/transactions/engine/SigmaFiIntent.scala`, SigmaFi API/controller/models/routes/UI | Remove lender-offer operations and representations; retain borrower orders, fill, repay, liquidate and claim |
| `app/stats/BatchingStatistics.scala` | Revalidate parsing and fee attribution; custom conversions can change vault ERG, so `input.value - vault.value` is not a general liquidation-revenue measure |
| `test/contracts/specs/sigmafi/SigmaFiContractsSpec.scala` and other SigmaFi specs | Re-pin bytes, addresses, hashes, positions and source compilation comparisons; add real two-unit fill/settle/claim regressions |

In the inspected Lithos snapshot, `SigmaFiBond.collateral` returns every asset, `bondBox` omits the
singleton, and repayment/liquidation omit its burn. Its hardcoded maturity expression is still the old
height-only expression. The current reference replaces those assumptions even though R4-R8 names look
similar. Replace the full pinned set rather than updating one hash independently.

The vault's executable tree is unchanged from that Lithos snapshot. Retain its full-byte equality
check and asset-burn claim logic. Always claim all vault boxes for an asset together, because the
circulating unit is gone afterwards. Do not send a claim unit into its own vault box.

## Authentication boundaries for the rewrite

Recognizing a script and box shape does not prove mint provenance. Off-chain verification can fetch
the indexed genesis box by asset id, authenticate its complete order instance and approved bond-hash
term, confirm its spending transaction created the bond at output index 1, and compare the bonded
terms/collateral. Confirm current spendability against the authoritative UTXO API when building;
index and mempool freshness are separate from historical genesis evidence.

The proposed on-chain proof uses a typed spent-order `Box` witness in a context variable, an
authenticated bond data input, and a separate circulating unit held/transferred by the verifier.
It must authenticate the whole order instance, approved bond script, order-id/asset-id equality,
singleton quantity, bond terms and separation from spending inputs. This verifier is not implemented
by the current contract set. Storage-rent bypasses and data-input visibility when another transaction
settles the bond in the same block require explicit handling before claiming an unconditional live-bond proof.

The vault and approved bond scripts enforce the settlement destination, tags and ordinary-path burns.
The selected liquidation expression controls economic eligibility and output contents. An arbitrary
always-true expression can permit immediate liquidation and any payout contents; a matching script
hash only proves that this was the expression the order selected.

Evidence here is offline compilation, exact-byte regeneration, substitution checks and interpreter
signing tests. It does not establish deployment, live-node acceptance, complete storage-rent safety,
maximum transaction cost, or interoperability under Lithos's different compiler dependency versions.

<!-- BEGIN GENERATED CONTRACT REFERENCE -->

## Generated compiler output

Generated offline through `ScriptGenerator` using the repository's V6 mock context (height 123413).
All entries have ErgoTree version 3. Full-tree hashes and template hashes are different inputs to blake2b256.
Order and token prototypes contain sentinels; replace only their listed terms before creating boxes.

| Entry | Tree bytes | Full ErgoTree blake2b256 | Template blake2b256 |
|---|---:|---|---|
| BondAssetVault | 171 | `ed132c371e35881b1b4fde03f092bc2eb8d9deb96df89cd9a5a32ab4f94b3c6b` | `0df4042ad7c434fb388cafdc2e6cc44c02dc80952687e0b2b374e5fc001f024e` |
| ZC_ERG_Bond | 448 | `1bceba97922f67d50e2718f39696c3f43f21279d971c83381a14ea0334df93b9` | `22268fef788f04e22c1207747560c635fc0dfca701082d2b55aa7cdda1e0812c` |
| ZC_Token_Bond prototype | 529 | `c3a4acc24e2e9c1647a6b39ebb9adf36ed0e6cf5e2b0b4c14b6421f7b59ef017` | `01dc8c5e62641965839a9b984bb7f73a7a9e24928b8483a6642459ba13dc1181` |
| ZC_Token_Bond SigUSD example | 529 | `9425199824c3985ccbda2614ebcf4e4f17336e5e8abdb58be0830982f9a507cd` | `01dc8c5e62641965839a9b984bb7f73a7a9e24928b8483a6642459ba13dc1181` |
| ZC_Order prototype | 673 | `b1a94c32f8aa5297ba8fe6477e281ea75145197d02dc2cdb5c6e00f38a5d3989` | `49dd9de79ddf81c435b42703a884abb7f212b20ad052f2a50d9d779f4171bf4d` |
| ZC_OrderOnClose prototype | 704 | `e7545cbd6d4b033c8c57058586146612c47a59eea5538ca693cb587dd1516cc4` | `362744a608dc1c7f872a616f1a6ad88c92240500ba5ec9a9498d74ef031bcc52` |
| MaturityLiquidation compilation tree | 98 | `f162f30a43b941d0d751cc90bfc07bdf05563b36110af3fb0251bc9ec8a26cd2` | `34148160d1c3bc4bfcd502ee103744958077b3cbabf695a469b77b5fdd0325d8` |

### BondAssetVault

Header: `0x1b`; constant count: `6`.

Full serialized ErgoTree hex:
```text
1ba80106050005000502050005000500d801d601e4c6a7050ed1ed92dad901020eb0a47300d9010441639a8c720401b0db63088c7204027301d90106414d0ed802d6088c720602d6098c72060195938c72080172029a72098c7208027209017201730293dad901020eb0a57303d9010441639a8c720401b0db63088c7204027304d90106414d0ed802d6088c720602d6098c72060195938c72080172029a72098c72080272090172017305
```

Raw template hex (`ErgoTree.template`, excludes all constants):
```text
d801d601e4c6a7050ed1ed92dad901020eb0a47300d9010441639a8c720401b0db63088c7204027301d90106414d0ed802d6088c720602d6098c72060195938c72080172029a72098c7208027209017201730293dad901020eb0a57303d9010441639a8c720401b0db63088c7204027304d90106414d0ed802d6088c720602d6098c72060195938c72080172029a72098c72080272090172017305
```

Mainnet address:
```text
3zECWKFRn9xN1djLR4kBGr8fzqX7jtzFU2q4xmSeGPQqVXBG6vGs92apVncKpxF8u8E9ksW1uCQcYwbVnhkMyUchVds2xS9M97XamNjKtmWqLCRQ8ZdZsbsqeSHhhPngStjbivVHMFTiw8BYbGXuBKhpyt2DWuJCqXyzChNhUYtKtP8VPxsn269aWr7CHCDnGaJkPqRaQWqkxqw1Wo2Hg44JB9KdDR8wBvYkEkWaNvJXKtqg
```

Testnet address:
```text
KMzh25p8Gr3oHePavSvfANo7bbWt6XH5YDWFfw76cRK4BipeJ9MVvyhWUjZGYS8i4auKwRdtPdE782Zud11CCrN5CrX6odqAX1Se7HL2XB2wRpfWbvgkEFx1shZgj4QrqZzFa5LogVPuKMMt5UGyFpGXHz9VuTFM1Ft4A5bk45KuGAQryHULCSJnjvk2cgpiST3wCnSwA9o9cEfgqamx2ZTZ9eTay6WhfVdPjp8n9kkJGgBk
```

### ZC_ERG_Bond

Header: `0x1b`; constant count: `18`.

Full serialized ErgoTree hex:
```text
1bbd031204020400040805000580897a040001000e20ed132c371e35881b1b4fde03f092bc2eb8d9deb96df89cd9a5a32ab4f94b3c6b0500050005000201040001000406020004000402d806d601e4c6a70611d602b27201730000d603c1a7d604e4e30104d605db6308a7d6068cb272057301000195ecececec94b1720173028f7202730391720299720373048f72047305927204b1a5d17306d806d607e3000ed608b2a5720400d60993cbc272087307d60ac5a7d60bed93e4c67208040e720a93e4c67208050e7206d60c93dad9010c0eb0a57308d9010e41639a8c720e01b0db63088c720e027309d90110414d0ed802d6128c721002d6138c72100195938c721201720c9a72138c7212027213017206730a95e67207ea02d19683050193cbe47207e4c6a7080e7209720b93e4c672080602730b720cd40800d801d60de4e3020495ecec8f720d730c92720db1a593720d7204d1730dd802d60ec17208d60fb2a5720d00d196830a018f7ea305b27201730e007209720b93e4c672080602730fed92720e72029299720e7202b2720173100093cbc2720fe4c6a7050e93e4c6720f040e720a93db6308720fb472057311b1720592c1720f9972037202720c
```

Raw template hex (`ErgoTree.template`, excludes all constants):
```text
d806d601e4c6a70611d602b27201730000d603c1a7d604e4e30104d605db6308a7d6068cb272057301000195ecececec94b1720173028f7202730391720299720373048f72047305927204b1a5d17306d806d607e3000ed608b2a5720400d60993cbc272087307d60ac5a7d60bed93e4c67208040e720a93e4c67208050e7206d60c93dad9010c0eb0a57308d9010e41639a8c720e01b0db63088c720e027309d90110414d0ed802d6128c721002d6138c72100195938c721201720c9a72138c7212027213017206730a95e67207ea02d19683050193cbe47207e4c6a7080e7209720b93e4c672080602730b720cd40800d801d60de4e3020495ecec8f720d730c92720db1a593720d7204d1730dd802d60ec17208d60fb2a5720d00d196830a018f7ea305b27201730e007209720b93e4c672080602730fed92720e72029299720e7202b2720173100093cbc2720fe4c6a7050e93e4c6720f040e720a93db6308720fb472057311b1720592c1720f9972037202720c
```

Mainnet address:
```text
AbMVCU6L2xciF5BfFo19UscGtuzpyGTNtPiEWAh84N8h2aP6EGHgfdioSUz9Y4DvPTE59LjSxC5KgKcRskLPoGnmkEGJwroYq5FEzLJoMyRNZdi2D5ZxgupVGuVWzxjwc1cj4hfD4Rh3xSW5Mc2AbdF9XH61URkYYGDHnumNKQhTkzwNNX1b4AWjcn84TJeqD3AUtLiGQHfLVMWcnuNYSynXZPRaaSpWLMn8wE7jkDpKpx6fC4T1wp7cw6SpCbDqxLNcMia8aGCqRMCQKrLRt32toQbV7ZZAVpc21A7ct3Y1Y1CBMmdXqpk6exQATpdt2wMqiedKoshvAke5sMN23mr1BuFAasbRtKSgiiFuYwtXFr5qwS831KytNRH5U4Ts1mWpfzaVoq869NUiveR8cPtdbsVNcp5DvmUUjNofKKyqBjZpp4poZooQCV25fvhrWuGVqG8SftDpg1kAgqTP3Lxn8MnSYJ8rNN9qY64kuk3qCHLgTPrTXmzucPcn5v5T9qpzWPjLARNQByNvxXvP5bCdSUNYMJgz6qCveADLppv2jzBLqSoSxHdF7T6ZMYX2kT43QbQ9oJZCNiUrgztyuGHWpFZr4W72Mp2dPGHfra
```

Testnet address:
```text
21y8xon7v4gwBKZQhYaKJYKNefBtqDckQRPjhyeWAsLiS1zQ6kx4xJkrsApfLXesVMsqevktYh74A4Ltq5YTP4zjkgSVrXFFGHM4k6NaRcvt5NeAFmAed3WutNZJPtDzRFXwENVQs1fHqbBcjddHSVBRiNyXjnnepijVMi5a2rmXnuFrSNP4yX9NoDGTsX3qQLtkEFnpZaukzYrtNRDjynd4b382YkBnMHpUzQjjRr9K9oxD3ADCNgCJDqGcqwW1g4QuxEJgEjhV8Zx9oAp6HkgVv9zfAUurhfzHFbbBtcaK6T6EGcqQqrzC9Q39o9MPCjTdK8d4P4GXxJWKeh4nL27Aopwi9EDAUHU2gAVimycWbZ4XTzEvLGchob6m8zZrnt3Aq6yckiTtkEBrFBcQpVZk87M2KB1Ec2XXQ2etNkmS3Rj6fAPmz9c8vDXxoxBr6Z4xCqPAXpT1tHEop1C7QW741vcwMeeBA7D6kmvcwraCVHMBvJiZiCVAoGVVa4zymjwyBxMNAeGzai67C3VhjxCABiSFu6NSC1tChWPTqTQwY8qgwZDc4uzZY3tDrwCrEwvhkaGDrYsEsAi9BakMCJoA2trAvRDkCpnVqsyCrmC
```

### ZC_Token_Bond prototype

Header: `0x1b`; constant count: `23`.

Full serialized ErgoTree hex:
```text
1b8e041704020400040805000580897a040001000e20ed132c371e35881b1b4fde03f092bc2eb8d9deb96df89cd9a5a32ab4f94b3c6b050005000500020104000100040602000580897a040204000e207e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e040004000402d806d601e4c6a70611d602b27201730000d603c1a7d604e4e30104d605db6308a7d6068cb272057301000195ecececec94b1720173028f7202730391720299720373048f72047305927204b1a5d17306d806d607e3000ed608b2a5720400d60993cbc272087307d60ac5a7d60bed93e4c67208040e720a93e4c67208050e7206d60c93dad9010c0eb0a57308d9010e41639a8c720e01b0db63088c720e027309d90110414d0ed802d6128c721002d6138c72100195938c721201720c9a72138c7212027213017206730a95e67207ea02d19683050193cbe47207e4c6a7080e7209720b93e4c672080602730b720cd40800d801d60de4e3020495ecec8f720d730c92720db1a593720d7204d1730dd803d60ec17208d60fdb63087208d610b2a5720d00d196830b018f7ea305b27201730e007209720b93e4c672080602730fed92720e72029299720e72027310eded93b1720f7311938cb2720f731200017313928cb2720f73140002b2720173150093cbc27210e4c6a7050e93e4c67210040e720a93db63087210b472057316b1720592c172109972037202720c
```

Raw template hex (`ErgoTree.template`, excludes all constants):
```text
d806d601e4c6a70611d602b27201730000d603c1a7d604e4e30104d605db6308a7d6068cb272057301000195ecececec94b1720173028f7202730391720299720373048f72047305927204b1a5d17306d806d607e3000ed608b2a5720400d60993cbc272087307d60ac5a7d60bed93e4c67208040e720a93e4c67208050e7206d60c93dad9010c0eb0a57308d9010e41639a8c720e01b0db63088c720e027309d90110414d0ed802d6128c721002d6138c72100195938c721201720c9a72138c7212027213017206730a95e67207ea02d19683050193cbe47207e4c6a7080e7209720b93e4c672080602730b720cd40800d801d60de4e3020495ecec8f720d730c92720db1a593720d7204d1730dd803d60ec17208d60fdb63087208d610b2a5720d00d196830b018f7ea305b27201730e007209720b93e4c672080602730fed92720e72029299720e72027310eded93b1720f7311938cb2720f731200017313928cb2720f73140002b2720173150093cbc27210e4c6a7050e93e4c67210040e720a93db63087210b472057316b1720592c172109972037202720c
```

Constant registry. Only named terms may change; every `fixed` constant must match this prototype exactly.
Values below are serialized Sigma constants, including their type prefix; they are not raw token IDs.

| Zero-based constant index | Role | Sigma type | Serialized constant hex |
|---:|---|---|---|
| 0 | `fixed` | `SInt$` | `0402` |
| 1 | `fixed` | `SInt$` | `0400` |
| 2 | `fixed` | `SInt$` | `0408` |
| 3 | `fixed` | `SLong$` | `0500` |
| 4 | `fixed` | `SLong$` | `0580897a` |
| 5 | `fixed` | `SInt$` | `0400` |
| 6 | `fixed` | `SBoolean` | `0100` |
| 7 | `fixed` | `Coll[SByte$]` | `0e20ed132c371e35881b1b4fde03f092bc2eb8d9deb96df89cd9a5a32ab4f94b3c6b` |
| 8 | `fixed` | `SLong$` | `0500` |
| 9 | `fixed` | `SLong$` | `0500` |
| 10 | `fixed` | `SLong$` | `0500` |
| 11 | `fixed` | `SByte$` | `0201` |
| 12 | `fixed` | `SInt$` | `0400` |
| 13 | `fixed` | `SBoolean` | `0100` |
| 14 | `fixed` | `SInt$` | `0406` |
| 15 | `fixed` | `SByte$` | `0200` |
| 16 | `fixed` | `SLong$` | `0580897a` |
| 17 | `fixed` | `SInt$` | `0402` |
| 18 | `fixed` | `SInt$` | `0400` |
| 19 | `CONST_TOKEN_ID` | `Coll[SByte$]` | `0e207e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e` |
| 20 | `fixed` | `SInt$` | `0400` |
| 21 | `fixed` | `SInt$` | `0400` |
| 22 | `fixed` | `SInt$` | `0402` |

### ZC_Token_Bond SigUSD example

Header: `0x1b`; constant count: `23`.

Full serialized ErgoTree hex:
```text
1b8e041704020400040805000580897a040001000e20ed132c371e35881b1b4fde03f092bc2eb8d9deb96df89cd9a5a32ab4f94b3c6b050005000500020104000100040602000580897a040204000e2003faf2cb329f2e90d6d23b58d91bbb6c046aa143261cc21f52fbe2824bfcbf04040004000402d806d601e4c6a70611d602b27201730000d603c1a7d604e4e30104d605db6308a7d6068cb272057301000195ecececec94b1720173028f7202730391720299720373048f72047305927204b1a5d17306d806d607e3000ed608b2a5720400d60993cbc272087307d60ac5a7d60bed93e4c67208040e720a93e4c67208050e7206d60c93dad9010c0eb0a57308d9010e41639a8c720e01b0db63088c720e027309d90110414d0ed802d6128c721002d6138c72100195938c721201720c9a72138c7212027213017206730a95e67207ea02d19683050193cbe47207e4c6a7080e7209720b93e4c672080602730b720cd40800d801d60de4e3020495ecec8f720d730c92720db1a593720d7204d1730dd803d60ec17208d60fdb63087208d610b2a5720d00d196830b018f7ea305b27201730e007209720b93e4c672080602730fed92720e72029299720e72027310eded93b1720f7311938cb2720f731200017313928cb2720f73140002b2720173150093cbc27210e4c6a7050e93e4c67210040e720a93db63087210b472057316b1720592c172109972037202720c
```

Raw template hex (`ErgoTree.template`, excludes all constants):
```text
d806d601e4c6a70611d602b27201730000d603c1a7d604e4e30104d605db6308a7d6068cb272057301000195ecececec94b1720173028f7202730391720299720373048f72047305927204b1a5d17306d806d607e3000ed608b2a5720400d60993cbc272087307d60ac5a7d60bed93e4c67208040e720a93e4c67208050e7206d60c93dad9010c0eb0a57308d9010e41639a8c720e01b0db63088c720e027309d90110414d0ed802d6128c721002d6138c72100195938c721201720c9a72138c7212027213017206730a95e67207ea02d19683050193cbe47207e4c6a7080e7209720b93e4c672080602730b720cd40800d801d60de4e3020495ecec8f720d730c92720db1a593720d7204d1730dd803d60ec17208d60fdb63087208d610b2a5720d00d196830b018f7ea305b27201730e007209720b93e4c672080602730fed92720e72029299720e72027310eded93b1720f7311938cb2720f731200017313928cb2720f73140002b2720173150093cbc27210e4c6a7050e93e4c67210040e720a93db63087210b472057316b1720592c172109972037202720c
```

Mainnet address:
```text
335hjjFkLYzyY99TeUyNeUzWBdkxb4ZSLXRwpG6uXJDYefQQrASucVdcJ2B7Aiv5kagCBUoyTzozVmvACff9gEg79YyoPBtKBUt7nYFpCmnKbc2RzFvvsbANvq25kyuwZ3qCYM2ZVnXaVnXduNH3C7GE9wdPn2mwn5SS3fUj8ECAYe3xWEcy5pt94o7aWUPBbdNKxxuWk7pUt2EBNYFWhFaJfxvwqK4Vae3VJHVqoqZNnuGWRm4o615yRnRP67v5AgPbEEyE5CnWAHGd7LQik9CATe8FRq4hDuB9mx1ogMyx9fT8ATURbNY6uYp1QKqo9euSP85RR47SFpjEWbCCGQa9zmY34BYaMjNAaWHc88fjLwZWa3a3qj8aeaq59Zr6CkrgffKF7qXM9NUKrAsa1xa1e86AnV3i6Zzqv2WgHTg1nAqHreRokAJ3xtMRnW5Y4KNFj36PLsXp9yz5bj977aMHcTnSvZesZ5KTNHvPcEkeZyBYUSJhyrxyRhzACJLZZnFXjsxwhcb5LNvNv452P2nap8HEtqSsQmnS23YNXT2RAnUxiFx2cowjdU15dRGTk5kAwx5GCtnWc9tFUscbdHXzufKvzogGGmDm7CxcGyM2KNDYEAeNmwHSuCXC3QxuZezeZuD8zTbpGcYpAL3cq2TyTGvJTaoXzKtVc1fLqVsDbpU88VX2iV18aTjsHtmgS2kVAYLmBk77HWTiSb6eJFWBh
```

Testnet address:
```text
DX2zRHKqmjFu95QabVWeqpUnbUPRhCfYrdanFW6ADMZafeRXdptCvb8tXHMD4Lc9obnqj94kbXM97zVQEYQTbLm6orehLDyPKtjGvr9RKhGTjYX8JuxCyi6gWf296Rgen6tR2AcMoTp3CDWqDQFY4u6nsAtTv5yRb7LqAgo79R9RC8DFUcSVs4HhDyJu3i6Fb5Tbuxexmuw12QchCszAg8saNcN74rndykK3oiZMeAujzstXLPcuYAi48k3BgmZaJ2N2rQiAhuyVudkEUZn3HZ7JAZks63qBWnMKehKjwn4GuaiZ1Ex5C9U89seHeH5kcGjZtqHrRn85m2D7QWuH4nnLuFZ1UufvGZH2ji5Xo3aoRUeLdFUaE7BwPbsKZ2QRqhNr6b6384dMBD7C7wrnShRsr4qq7eArfKPhZP9jSe7hTRjuaTRcLBTBPYmgxuTiwds8cM8x4ofnAeNM8vysY3VwkoRmWWgGsJcY8DsL4sPH7ijvp9cnWnStGJpgkzSk6B3bGHLAzXnCMLpeeWppC5VutoFW1aoG8GVKgB5aA7rUwctijyLJtjp2k8dvLsccqjCmnd2sTMDVhgvmUjPzHQb969GPddZgPvp7T6SPPso3mW4Fh29RWCExCq4JVqLaMexbMGZs1XdjLqVKiemvC9EuHe8eMeE3QL88btGm5KZwTHzwMF2S8vhF1ZogAJjL7yB6bmn3Hvqb7k98bctcsLyx8
```

Constant registry. Only named terms may change; every `fixed` constant must match this prototype exactly.
Values below are serialized Sigma constants, including their type prefix; they are not raw token IDs.

| Zero-based constant index | Role | Sigma type | Serialized constant hex |
|---:|---|---|---|
| 0 | `fixed` | `SInt$` | `0402` |
| 1 | `fixed` | `SInt$` | `0400` |
| 2 | `fixed` | `SInt$` | `0408` |
| 3 | `fixed` | `SLong$` | `0500` |
| 4 | `fixed` | `SLong$` | `0580897a` |
| 5 | `fixed` | `SInt$` | `0400` |
| 6 | `fixed` | `SBoolean` | `0100` |
| 7 | `fixed` | `Coll[SByte$]` | `0e20ed132c371e35881b1b4fde03f092bc2eb8d9deb96df89cd9a5a32ab4f94b3c6b` |
| 8 | `fixed` | `SLong$` | `0500` |
| 9 | `fixed` | `SLong$` | `0500` |
| 10 | `fixed` | `SLong$` | `0500` |
| 11 | `fixed` | `SByte$` | `0201` |
| 12 | `fixed` | `SInt$` | `0400` |
| 13 | `fixed` | `SBoolean` | `0100` |
| 14 | `fixed` | `SInt$` | `0406` |
| 15 | `fixed` | `SByte$` | `0200` |
| 16 | `fixed` | `SLong$` | `0580897a` |
| 17 | `fixed` | `SInt$` | `0402` |
| 18 | `fixed` | `SInt$` | `0400` |
| 19 | `CONST_TOKEN_ID` | `Coll[SByte$]` | `0e2003faf2cb329f2e90d6d23b58d91bbb6c046aa143261cc21f52fbe2824bfcbf04` |
| 20 | `fixed` | `SInt$` | `0400` |
| 21 | `fixed` | `SInt$` | `0400` |
| 22 | `fixed` | `SInt$` | `0402` |

### ZC_Order prototype

Header: `0x1b`; constant count: `36`.

Full serialized ErgoTree hex:
```text
1b9e0524050005000e206262626262626262626262626262626262626262626262626262626262626262050004000402040204040400050404000400040005020e2061616161616161616161616161616161616161616161616161616161616161610400040004000502040205000408040005e4b88d9d420402040405e2b88d9d420c4d0e01206464646464646464646464646464646464646464646464646464646464646464eab88d9d42040605e8c06c0e20636363636363636363636363636363636363636363636363636363636363636305000580897a040605020404d803d601c5a7d602dad901020eb0a57300d9010441639a8c720401b0db63088c7204027301d90106414d0ed802d6088c720602d6098c72060195938c72080172029a72098c7208027209017201d6037302959172027303d806d604b2a5730400d605b2a5730500d606e4e30005d607e4c672050611d608b27207730600d609b2a5730700d1ededededededededed937201c5b2a4730800937202730991b1db63087204730a938cb2db63087204730b00017201938cb2db63087204730c0002730dededededededed93cbc27205730e91b1db63087205730f938cb2db63087205731000017201938cb2db63087205731100027312d801d60adb6308720593b4720a7313b1720adb6308a792c17205c1a792720673149299c17205c1a77206edededededededed93e4c67205040e720193e4c67205050e720393b17207731593b27207731600731793b27207731800720693b27207731900731a93e4c67205070c4d0e731b93b27207731c00731d93e4c67205080e731eed927208731f9299c172057208732091b272077321009a7ea3057322eded93cbc27209720392c17209b2720773230093db63087209e4c67205070c4d0ed1aea4d901046393cbc272047203
```

Raw template hex (`ErgoTree.template`, excludes all constants):
```text
d803d601c5a7d602dad901020eb0a57300d9010441639a8c720401b0db63088c7204027301d90106414d0ed802d6088c720602d6098c72060195938c72080172029a72098c7208027209017201d6037302959172027303d806d604b2a5730400d605b2a5730500d606e4e30005d607e4c672050611d608b27207730600d609b2a5730700d1ededededededededed937201c5b2a4730800937202730991b1db63087204730a938cb2db63087204730b00017201938cb2db63087204730c0002730dededededededed93cbc27205730e91b1db63087205730f938cb2db63087205731000017201938cb2db63087205731100027312d801d60adb6308720593b4720a7313b1720adb6308a792c17205c1a792720673149299c17205c1a77206edededededededed93e4c67205040e720193e4c67205050e720393b17207731593b27207731600731793b27207731800720693b27207731900731a93e4c67205070c4d0e731b93b27207731c00731d93e4c67205080e731eed927208731f9299c172057208732091b272077321009a7ea3057322eded93cbc27209720392c17209b2720773230093db63087209e4c67205070c4d0ed1aea4d901046393cbc272047203
```

Constant registry. Only named terms may change; every `fixed` constant must match this prototype exactly.
Values below are serialized Sigma constants, including their type prefix; they are not raw token IDs.

| Zero-based constant index | Role | Sigma type | Serialized constant hex |
|---:|---|---|---|
| 0 | `fixed` | `SLong$` | `0500` |
| 1 | `fixed` | `SLong$` | `0500` |
| 2 | `CONST_BORROWER_HASH` | `Coll[SByte$]` | `0e206262626262626262626262626262626262626262626262626262626262626262` |
| 3 | `fixed` | `SLong$` | `0500` |
| 4 | `fixed` | `SInt$` | `0400` |
| 5 | `fixed` | `SInt$` | `0402` |
| 6 | `fixed` | `SInt$` | `0402` |
| 7 | `fixed` | `SInt$` | `0404` |
| 8 | `fixed` | `SInt$` | `0400` |
| 9 | `fixed` | `SLong$` | `0504` |
| 10 | `fixed` | `SInt$` | `0400` |
| 11 | `fixed` | `SInt$` | `0400` |
| 12 | `fixed` | `SInt$` | `0400` |
| 13 | `fixed` | `SLong$` | `0502` |
| 14 | `CONST_BOND_HASH` | `Coll[SByte$]` | `0e206161616161616161616161616161616161616161616161616161616161616161` |
| 15 | `fixed` | `SInt$` | `0400` |
| 16 | `fixed` | `SInt$` | `0400` |
| 17 | `fixed` | `SInt$` | `0400` |
| 18 | `fixed` | `SLong$` | `0502` |
| 19 | `fixed` | `SInt$` | `0402` |
| 20 | `fixed` | `SLong$` | `0500` |
| 21 | `fixed` | `SInt$` | `0408` |
| 22 | `fixed` | `SInt$` | `0400` |
| 23 | `CONST_REPAYMENT` | `SLong$` | `05e4b88d9d42` |
| 24 | `fixed` | `SInt$` | `0402` |
| 25 | `fixed` | `SInt$` | `0404` |
| 26 | `CONST_PRINCIPAL_ERG` | `SLong$` | `05e2b88d9d42` |
| 27 | `CONST_PRINCIPAL_TOKENS` | `Coll[(Coll[SByte$],SLong$)]` | `0c4d0e01206464646464646464646464646464646464646464646464646464646464646464eab88d9d42` |
| 28 | `fixed` | `SInt$` | `0406` |
| 29 | `CONST_MATURITY` | `SLong$` | `05e8c06c` |
| 30 | `CONST_LIQ_SCRIPT_HASH` | `Coll[SByte$]` | `0e206363636363636363636363636363636363636363636363636363636363636363` |
| 31 | `fixed` | `SLong$` | `0500` |
| 32 | `fixed` | `SLong$` | `0580897a` |
| 33 | `fixed` | `SInt$` | `0406` |
| 34 | `fixed` | `SLong$` | `0502` |
| 35 | `fixed` | `SInt$` | `0404` |

### ZC_OrderOnClose prototype

Header: `0x1b`; constant count: `37`.

Full serialized ErgoTree hex:
```text
1bbd0525050005000e207272727272727272727272727272727272727272727272727272727272727272050004000402040205f4887a040604040400050404000400040005020e2071717171717171717171717171717171717171717171717171717171717171710400040004000502040205000408040005f08fdfc04a0402040405ee8fdfc04a0c4d0e01207474747474747474747474747474747474747474747474747474747474747474f68fdfc04a0e20737373737373737373737373737373737373737373737373737373737373737305000580897a053c053c05100404d803d601c5a7d602dad901020eb0a57300d9010441639a8c720401b0db63088c7204027301d90106414d0ed802d6088c720602d6098c72060195938c72080172029a72098c7208027209017201d6037302959172027303d809d604b2a5730400d605b2a5730500d606e4e30005d607e4c672050611d608b27207730600d6097ea305d60a9a72097307d60bb27207730800d60cb2a5730900d1ededededededededed937201c5b2a4730a00937202730b91b1db63087204730c938cb2db63087204730d00017201938cb2db63087204730e0002730fededededededed93cbc27205731091b1db630872057311938cb2db63087205731200017201938cb2db63087205731300027314d801d60ddb6308720593b4720d7315b1720ddb6308a792c17205c1a792720673169299c17205c1a77206ededededededed93e4c67205040e720193e4c67205050e720393b17207731793b27207731800731993b27207731a00720693b27207731b00731c93e4c67205070c4d0e731d93e4c67205080e731eed927208731f9299c1720572087320ededed92720a9a720973219299720a7209732290720b720a92720b99720a7323eded93cbc2720c720392c1720cb2720773240093db6308720ce4c67205070c4d0ed1aea4d901046393cbc272047203
```

Raw template hex (`ErgoTree.template`, excludes all constants):
```text
d803d601c5a7d602dad901020eb0a57300d9010441639a8c720401b0db63088c7204027301d90106414d0ed802d6088c720602d6098c72060195938c72080172029a72098c7208027209017201d6037302959172027303d809d604b2a5730400d605b2a5730500d606e4e30005d607e4c672050611d608b27207730600d6097ea305d60a9a72097307d60bb27207730800d60cb2a5730900d1ededededededededed937201c5b2a4730a00937202730b91b1db63087204730c938cb2db63087204730d00017201938cb2db63087204730e0002730fededededededed93cbc27205731091b1db630872057311938cb2db63087205731200017201938cb2db63087205731300027314d801d60ddb6308720593b4720d7315b1720ddb6308a792c17205c1a792720673169299c17205c1a77206ededededededed93e4c67205040e720193e4c67205050e720393b17207731793b27207731800731993b27207731a00720693b27207731b00731c93e4c67205070c4d0e731d93e4c67205080e731eed927208731f9299c1720572087320ededed92720a9a720973219299720a7209732290720b720a92720b99720a7323eded93cbc2720c720392c1720cb2720773240093db6308720ce4c67205070c4d0ed1aea4d901046393cbc272047203
```

Constant registry. Only named terms may change; every `fixed` constant must match this prototype exactly.
Values below are serialized Sigma constants, including their type prefix; they are not raw token IDs.

| Zero-based constant index | Role | Sigma type | Serialized constant hex |
|---:|---|---|---|
| 0 | `fixed` | `SLong$` | `0500` |
| 1 | `fixed` | `SLong$` | `0500` |
| 2 | `CONST_BORROWER_HASH` | `Coll[SByte$]` | `0e207272727272727272727272727272727272727272727272727272727272727272` |
| 3 | `fixed` | `SLong$` | `0500` |
| 4 | `fixed` | `SInt$` | `0400` |
| 5 | `fixed` | `SInt$` | `0402` |
| 6 | `fixed` | `SInt$` | `0402` |
| 7 | `CONST_DURATION` | `SLong$` | `05f4887a` |
| 8 | `fixed` | `SInt$` | `0406` |
| 9 | `fixed` | `SInt$` | `0404` |
| 10 | `fixed` | `SInt$` | `0400` |
| 11 | `fixed` | `SLong$` | `0504` |
| 12 | `fixed` | `SInt$` | `0400` |
| 13 | `fixed` | `SInt$` | `0400` |
| 14 | `fixed` | `SInt$` | `0400` |
| 15 | `fixed` | `SLong$` | `0502` |
| 16 | `CONST_BOND_HASH` | `Coll[SByte$]` | `0e207171717171717171717171717171717171717171717171717171717171717171` |
| 17 | `fixed` | `SInt$` | `0400` |
| 18 | `fixed` | `SInt$` | `0400` |
| 19 | `fixed` | `SInt$` | `0400` |
| 20 | `fixed` | `SLong$` | `0502` |
| 21 | `fixed` | `SInt$` | `0402` |
| 22 | `fixed` | `SLong$` | `0500` |
| 23 | `fixed` | `SInt$` | `0408` |
| 24 | `fixed` | `SInt$` | `0400` |
| 25 | `CONST_REPAYMENT` | `SLong$` | `05f08fdfc04a` |
| 26 | `fixed` | `SInt$` | `0402` |
| 27 | `fixed` | `SInt$` | `0404` |
| 28 | `CONST_PRINCIPAL_ERG` | `SLong$` | `05ee8fdfc04a` |
| 29 | `CONST_PRINCIPAL_TOKENS` | `Coll[(Coll[SByte$],SLong$)]` | `0c4d0e01207474747474747474747474747474747474747474747474747474747474747474f68fdfc04a` |
| 30 | `CONST_LIQ_SCRIPT_HASH` | `Coll[SByte$]` | `0e207373737373737373737373737373737373737373737373737373737373737373` |
| 31 | `fixed` | `SLong$` | `0500` |
| 32 | `fixed` | `SLong$` | `0580897a` |
| 33 | `fixed` | `SLong$` | `053c` |
| 34 | `fixed` | `SLong$` | `053c` |
| 35 | `fixed` | `SLong$` | `0510` |
| 36 | `fixed` | `SInt$` | `0404` |

### MaturityLiquidation compilation tree

Header: `0x1b`; constant count: `5`.

Full serialized ErgoTree hex:
```text
1b600504000100040604020402d801d601e4e3010495ec8f72017300927201b1a5d17301d803d602e4c6a70611d603b2a5720100d604db6308a7d1eded927ea305b2720273020093db63087203b472047303b1720492c1720399c1a7b27202730400
```

Raw template hex (`ErgoTree.template`, excludes all constants):
```text
d801d601e4e3010495ec8f72017300927201b1a5d17301d803d602e4c6a70611d603b2a5720100d604db6308a7d1eded927ea305b2720273020093db63087203b472047303b1720492c1720399c1a7b27202730400
```

### MaturityLiquidation runtime expression

Attach these raw expression bytes to the bond input as context variable 0 of type `Coll[Byte]`.
Do not attach the compilation ErgoTree or a serialized Sigma `Coll[Byte]` constant in their place.

```text
d801d601e4e3010495ec8f72010400927201b1a5d10100d803d602e4c6a70611d603b2a5720100d604db6308a7d1eded927ea305b2720204060093db63087203b472040402b1720492c1720399c1a7b27202040200
```

Expression blake2b256 (bond R8 / order `CONST_LIQ_SCRIPT_HASH`): `ea301f93d1a806e8fedd21ef0ffae39bdbfc15671c41247c4476ccb942a1485b`.

### Source fingerprints

blake2b256 over UTF-8 source text after normalizing CRLF to LF; these fingerprints are not script hashes.

| Compiler input | Normalized source blake2b256 |
|---|---|
| `contracts/old/ZC_ERG_Bond.ergo` | `5a331aa821cd0f8af6f3fbf1867c8e3f96f7ecf007e2d53b54e989d167d9d160` |
| `contracts/old/ZC_Token_Bond.ergo` | `f4e6b8ab7ed2536c9762068ccd265531a54a10192dbcdd4c5d075cd84be2cf27` |
| `contracts/old/ZC_Order.ergo` | `18ef26f52222f0deb3fe130dd2228caa4d3f39a6acae709c3fa011561fe56a83` |
| `contracts/old/ZC_OrderOnClose.ergo` | `61209c052c8107b6c3300bdea9e3d3a39dc031019db9360f1d61525818aaca4c` |
| `contracts/old/BondAssetVault.ergo` | `3297b063e1287b33732e2f6f4bbe0a70a836460aea0af716ce6385010e7e505a` |
| `contracts/old/MaturityLiquidation.ergo` | `ba5f0f3647f5f73c3d81a61ffb599119731c89efab5dc1c65d125144520af19e` |
| `src/main/scala/client/ScriptGenerator.scala` | `9e4865d047444921bb85e57562d193602e197a60dacfd5d1eae3e1250a949dfe` |
| `src/main/scala/work/lithos/mutations/Contract.scala` | `fe1e473783454eab1b759feb7ea3ab9c757d8e26ec0aef615dd45a5573021213` |
| `build.sbt` | `24bff2ad1f970a11d84ac547b66b8b1e469299c18029476e447f3e16c723b6ef` |
| `project/build.properties` | `e6023cb99f0ff57fdcb183b400632402e711320ee722372eb7a6a844ba9f4897` |

<!-- END GENERATED CONTRACT REFERENCE -->
