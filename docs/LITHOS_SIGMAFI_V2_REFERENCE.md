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

All values are nanoERG. Monetary amounts and token quantities are `Long`; heights and durations are `Int`.
Hashes and token IDs are `Coll[Byte]` (32 bytes for the protocol's hash/id values).

| Bond field | Meaning |
|---|---|
| `value` | Collateral ERG |
| `tokens(0)` | `(bondAssetId, 1)`; this is the bond singleton, not collateral |
| `tokens(1..)` | Collateral tokens, preserving the original order's sequence |
| R4: `Coll[Byte]` | Originating order id, equal to the bond asset id for an authentic fill |
| R5: `Coll[Byte]` | Borrower full-ErgoTree hash |
| R6: `Coll[Long]` | Exactly `(repayment, batchingFee)` |
| R7: `Int` | Maturity height; repayment requires `HEIGHT < R7` |
| R8: `Coll[Byte]` | Liquidation expression hash |

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
| Vault settlement | R4: closing bond box id, R5: bond asset id, both `Coll[Byte]` |
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
| `principal` | `{ "nanoErgs": Long, "tokens": [{ "id": hex, "amount": Long }] }` for the planned principal output |
| `repayment` / `repayToken` | Raw repayment amount and token-id hex, or `"ERG"` for nanoERG repayment |
| `maturity` | Actual stamped bond R7 for either schedule |
| `durationBlocks` | Requested duration, present only for on-close orders |
| `collateral` | Same nanoERG/tokens shape as principal; excludes the minted bond singleton |
| `batchingFee` | Maximum nanoERG fee used by the standard maturity liquidation |
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
| Fill order | Order must be input 0; lender funds principal and extra boxes/fee | Output 0 lender unit; output 1 bond; output 2 principal to borrower | Mint exactly 2 units with id `order.id`; one in output 0, one in output 1 |
| Cancel order | Order plus a box guarded by borrower script | Refund as borrower chooses | No units minted; borrower authorization is by co-spend |
| Repay bond at input i | Bond plus repayment funding | Var 1 selects repayment to vault; var 2 selects full collateral return | Context var 0 absent; vars 1/2 are distinct in-range Int indices; explicitly burn the bond singleton |
| Liquidate bond at input i | Bond with its agreed expression in context var 0; optional fee funding | Var 1 (Int) selects settlement to vault, tagged with bond id and asset id | Burn the singleton; expression decides eligibility, ERG and tokens |
| Claim vault | All vault boxes for an asset plus the holder's circulating unit | Holder chooses outputs | Burn the circulating unit |

For every settlement, vault R4 must equal the closing bond's box id, and vault R5 the bond's token id.
Collateral-return R4 must equal that same bond box id. A full repayment returns `tokens.drop(1)` and at
least the bond's ERG value. An ERG repayment pays at least R6(0). A token repayment pays exactly one
token ID, the compiled `CONST_TOKEN_ID`, at least R6(0), with at least 0.001 ERG.

Settlement output positions are selected per input through context var 1 (Int), independently of
input positions. Repayment also requires var 2 (Int) for a distinct collateral-return output; no filler
inputs are needed. Liquidation expressions must check the same var-1 output as the bond, tag extra
bond-specific payouts with R4 = SELF.id, and keep separate payout roles distinct within each bond.
Builders must update context indices when rearranging outputs. Specify burns explicitly in Appkit;
letting the singleton escape into change violates the bond on both settlement paths. Avoid selecting
the lender's circulating unit as automatic settlement funding: no bond-asset units may survive in the
settlement transaction's outputs. Burning the claim unit along with the singleton loses the lender's claim.

Orders preserve their complete collateral token list after prepending the singleton. The order pins
the new bond's borrower, repayment, fee, schedule and liquidation hash. An ERG principal has no tokens;
a token principal uses one token and an ERG floor. Pair the principal with the correct repayment bond
off-chain: the order trusts its `CONST_BOND_HASH` and does not enforce that pairing itself.

Fixed-height orders require maturity at least inclusion height + 2. On-close duration must be at
least 30 blocks, and accepted maturity lies in `[HEIGHT + duration - 8, HEIGHT + duration]`. The builder
may stamp `buildHeight + duration`; a fill can confirm through buildHeight + 8, then must be rebuilt.
Use checked arithmetic in client code and account for tip height versus intended inclusion height.

`MaturityLiquidation` requires `HEIGHT >= R7`, exact collateral tokens after removing the singleton,
and vault value at least `bond.value - batchingFee`. The originating order bounds fee to a nonnegative
amount leaving at least 0.001 ERG. A batch executor retains at most the sum of fees, less the miner fee;
if fees do not cover that fee, supply additional funding. Validate the actual serialized outputs against
the node's minimum value rules rather than treating 0.001 ERG as a consensus guarantee for every size.

For custom liquidation expressions, do not assume the maturity rule's output assets, ERG floor, or
fee economics. They may atomically convert collateral to ERG or another asset. The bond still pins
the vault, the R4/R5 tags, and the burn. Initially enable automatic batching only for the exact known
maturity expression hash. A custom builder requires separate review and signing tests.

## Lithos migration map

These paths were inspected in Lithos-Client at `572d2cc016a68ec2b4b5e4653b6e2df77aaa8f32`.
They identify rewrite surfaces; no Lithos files were changed while producing this document.

| Lithos surface | Required change |
|---|---|
| `lithos-lib/src/main/scala/sigmafi/contracts/SigmaFiContracts.scala` and its bundled `.ergo` resources | Replace pinned trees, templates, constant indices, source copies and maturity expression together; remove offer definitions |
| `app/transactions/batching/sigmafi/SigmaFiBoxes.scala` | Read asset from first token, validate amount 1 and agreement with R4, expose collateral as `assets.drop(1)`; remove offer parsing |
| `app/transactions/batching/sigmafi/SigmaFiUserTransactions.scala` | Prepend bond singleton on fill, mint supply 2, return only collateral tokens on repayment, and burn singleton on repayment; remove offer creation/close/cancel flows |
| `app/transactions/batching/sigmafi/SigmaFiTransactions.scala` | Set new expression bytes, strip singleton from vault assets, and explicitly burn one unit per liquidated bond; preserve batching indices |
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
| ZC_ERG_Bond | 357 | `82c9bab2e4751c46637ec4bb66cbee688a3b7e752f28b3b614de5d757dbf3fde` | `fd0a057ff2032fa474b5fab9178e4eb1c4c0005abc605aa4fec5abfe3ea1186f` |
| ZC_Token_Bond prototype | 438 | `c3be862c4a37708e29689f0c9490c4aabc00f605371191585bb7f3af99ffff7c` | `1f63d7956fdbfb3e66503818bbe5ebf210ccc9b18f77e1ede4434ea56ffa0e79` |
| ZC_Token_Bond SigUSD example | 438 | `1633e4d330fcc0e5fcd178810e3303e31809e029ffe7470bcc98d025c2f5f7f7` | `1f63d7956fdbfb3e66503818bbe5ebf210ccc9b18f77e1ede4434ea56ffa0e79` |
| ZC_Order prototype | 613 | `050a5dd27b33ef127039900228e1fb767901d25f20f7177ed1b423957c29c4e6` | `5e82ac73dba7b8dbe463d8f05fd8058604542c00fecc0eade894785a24934bcf` |
| ZC_OrderOnClose prototype | 630 | `9a7d44d46f8b3cdb41b6b8caf6a190549b5ccd01b68cd21398b9811974a06b38` | `f5e91924fcc12cce98913e60a43d9c03b8d6495fd88409143aed94373e35499f` |
| MaturityLiquidation compilation tree | 89 | `c254f95a416d8a9ca21b1f1045fffaa7c11da35676f808a49694dbc788f767d9` | `cb95a9f4bc4c86d5dafe14ada9a6521db65f22ece98ee65749e4344df0059dce` |

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

Header: `0x1b`; constant count: `11`.

Full serialized ErgoTree hex:
```text
1be2020b0400040001000e20ed132c371e35881b1b4fde03f092bc2eb8d9deb96df89cd9a5a32ab4f94b3c6b0500050005000400010004000402d803d601e4e30104d602db6308a7d6038cb272027300000195ec8f72017301927201b1a5d17302d806d604e3000ed605b2a5720100d60693cbc272057303d607c5a7d608ed93e4c67205040e720793e4c67205050e7203d60993dad901090eb0a57304d9010b41639a8c720b01b0db63088c720b027305d9010d414d0ed802d60f8c720d02d6108c720d0195938c720f0172099a72108c720f027210017203730695e67204ea02d19683040193cbe47204e4c6a7080e720672087209d40800d801d60ae4e3020495ecec8f720a730792720ab1a593720a7201d17308d801d60bb2a5720a00d1968309018fa3e4c6a707047206720892c17205b2e4c6a7061173090093cbc2720be4c6a7050e93e4c6720b040e720793db6308720bb47202730ab1720292c1720bc1a77209
```

Raw template hex (`ErgoTree.template`, excludes all constants):
```text
d803d601e4e30104d602db6308a7d6038cb272027300000195ec8f72017301927201b1a5d17302d806d604e3000ed605b2a5720100d60693cbc272057303d607c5a7d608ed93e4c67205040e720793e4c67205050e7203d60993dad901090eb0a57304d9010b41639a8c720b01b0db63088c720b027305d9010d414d0ed802d60f8c720d02d6108c720d0195938c720f0172099a72108c720f027210017203730695e67204ea02d19683040193cbe47204e4c6a7080e720672087209d40800d801d60ae4e3020495ecec8f720a730792720ab1a593720a7201d17308d801d60bb2a5720a00d1968309018fa3e4c6a707047206720892c17205b2e4c6a7061173090093cbc2720be4c6a7050e93e4c6720b040e720793db6308720bb47202730ab1720292c1720bc1a77209
```

Mainnet address:
```text
49FQkrJaE2uq53fJW3htDRJ1yNDh8mpjf5HLvSBYW3yeJ88doXT4nozDsJvCBB26CB1DgsDAPRqwpuXF3hxJ3vo5MX8TqfnAUEvX4ssHbAWceXf3wigakme62mBE9GiD4ZsCptUYTg88S1W4MsqSAKyNSwxt1PbzyvE6StPgYSVQozUZckWJz29nJWn2UWGtwH4om8pFY4H9i5VCowmhPuaqMoAok389KKc8TMsxbeWXCsxSBwd3E8AejZkFf9HzSUSkdXUw9mYJh4cLKuDJgbE7nYAw8KQdJ1oMK1CHohnufNES4taU7g5LJC9r4Bvrjh9mEqxrxSs6yNfbf1STsZ3SxdgV2CGTnQbF346GaLirXbDNTL8bgXw1A7rWk25DSpCJqW2kWHFvMTMj79d49pg5s7vqNwRXixgR2Uk13Pcdp19Qip3KFs4T8r3C83bPvPtU1T3jQb9Rt6hNxAeC3mtz62VN3Q25yuW4qhV4AUR8Aj
```

Testnet address:
```text
LKBsnrdyYh1EKntZDHqRsqT2znhfan9JhvfCcib586gZKG5m46Bw5JWUpZ8G7P4F1BGAVbYqMZk8bMicoLJ7p2pnQsyyAa6QrqP5u2qG6o7hbd5JXAUD3PWR6FoCFyeFxrMN284oB5poCYrCfCLJwmNHe5TAMASkD25CdwXXV5w8vL4mCfHR8GtttRWrGXjahyATX4V9uekuKkgJuVgDfV7M4mgubt3tjgUiFx1vAVt8DgRviVxKC8UvZhm2kFS4g8yqpp618J1B8kHMRveEX1JbL13LK5CHo1LcKgv4T6YwcLADSdKPY841pVTJ2mfZTAKQ48G6SBtn4sGq2fQg1qtZ4S9B3pFwcvwtFAcNDHaGU9eHcLFon6yQNYgozqtzB2vK73WBZk5tdEMEaVLMNpqewVqcyM8RW2EkybfynHDz67qLsjSpUXRRxxrUGiRFhnBXAA6D8UZEpFj2ahbNgvUjBSbtEmfVuqbUV4JY8NDzVu
```

### ZC_Token_Bond prototype

Header: `0x1b`; constant count: `16`.

Full serialized ErgoTree hex:
```text
1bb303100400040001000e20ed132c371e35881b1b4fde03f092bc2eb8d9deb96df89cd9a5a32ab4f94b3c6b050005000500040001000580897a040204000e207e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e040004000402d803d601e4e30104d602db6308a7d6038cb272027300000195ec8f72017301927201b1a5d17302d806d604e3000ed605b2a5720100d60693cbc272057303d607c5a7d608ed93e4c67205040e720793e4c67205050e7203d60993dad901090eb0a57304d9010b41639a8c720b01b0db63088c720b027305d9010d414d0ed802d60f8c720d02d6108c720d0195938c720f0172099a72108c720f027210017203730695e67204ea02d19683040193cbe47204e4c6a7080e720672087209d40800d801d60ae4e3020495ecec8f720a730792720ab1a593720a7201d17308d802d60bdb63087205d60cb2a5720a00d196830a018fa3e4c6a707047206720892c172057309eded93b1720b730a938cb2720b730b0001730c928cb2720b730d0002b2e4c6a70611730e0093cbc2720ce4c6a7050e93e4c6720c040e720793db6308720cb47202730fb1720292c1720cc1a77209
```

Raw template hex (`ErgoTree.template`, excludes all constants):
```text
d803d601e4e30104d602db6308a7d6038cb272027300000195ec8f72017301927201b1a5d17302d806d604e3000ed605b2a5720100d60693cbc272057303d607c5a7d608ed93e4c67205040e720793e4c67205050e7203d60993dad901090eb0a57304d9010b41639a8c720b01b0db63088c720b027305d9010d414d0ed802d60f8c720d02d6108c720d0195938c720f0172099a72108c720f027210017203730695e67204ea02d19683040193cbe47204e4c6a7080e720672087209d40800d801d60ae4e3020495ecec8f720a730792720ab1a593720a7201d17308d802d60bdb63087205d60cb2a5720a00d196830a018fa3e4c6a707047206720892c172057309eded93b1720b730a938cb2720b730b0001730c928cb2720b730d0002b2e4c6a70611730e0093cbc2720ce4c6a7050e93e4c6720c040e720793db6308720cb47202730fb1720292c1720cc1a77209
```

Constant registry. Only named terms may change; every `fixed` constant must match this prototype exactly.
Values below are serialized Sigma constants, including their type prefix; they are not raw token IDs.

| Zero-based constant index | Role | Sigma type | Serialized constant hex |
|---:|---|---|---|
| 0 | `fixed` | `SInt$` | `0400` |
| 1 | `fixed` | `SInt$` | `0400` |
| 2 | `fixed` | `SBoolean` | `0100` |
| 3 | `fixed` | `Coll[SByte$]` | `0e20ed132c371e35881b1b4fde03f092bc2eb8d9deb96df89cd9a5a32ab4f94b3c6b` |
| 4 | `fixed` | `SLong$` | `0500` |
| 5 | `fixed` | `SLong$` | `0500` |
| 6 | `fixed` | `SLong$` | `0500` |
| 7 | `fixed` | `SInt$` | `0400` |
| 8 | `fixed` | `SBoolean` | `0100` |
| 9 | `fixed` | `SLong$` | `0580897a` |
| 10 | `fixed` | `SInt$` | `0402` |
| 11 | `fixed` | `SInt$` | `0400` |
| 12 | `CONST_TOKEN_ID` | `Coll[SByte$]` | `0e207e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e` |
| 13 | `fixed` | `SInt$` | `0400` |
| 14 | `fixed` | `SInt$` | `0400` |
| 15 | `fixed` | `SInt$` | `0402` |

### ZC_Token_Bond SigUSD example

Header: `0x1b`; constant count: `16`.

Full serialized ErgoTree hex:
```text
1bb303100400040001000e20ed132c371e35881b1b4fde03f092bc2eb8d9deb96df89cd9a5a32ab4f94b3c6b050005000500040001000580897a040204000e2003faf2cb329f2e90d6d23b58d91bbb6c046aa143261cc21f52fbe2824bfcbf04040004000402d803d601e4e30104d602db6308a7d6038cb272027300000195ec8f72017301927201b1a5d17302d806d604e3000ed605b2a5720100d60693cbc272057303d607c5a7d608ed93e4c67205040e720793e4c67205050e7203d60993dad901090eb0a57304d9010b41639a8c720b01b0db63088c720b027305d9010d414d0ed802d60f8c720d02d6108c720d0195938c720f0172099a72108c720f027210017203730695e67204ea02d19683040193cbe47204e4c6a7080e720672087209d40800d801d60ae4e3020495ecec8f720a730792720ab1a593720a7201d17308d802d60bdb63087205d60cb2a5720a00d196830a018fa3e4c6a707047206720892c172057309eded93b1720b730a938cb2720b730b0001730c928cb2720b730d0002b2e4c6a70611730e0093cbc2720ce4c6a7050e93e4c6720c040e720793db6308720cb47202730fb1720292c1720cc1a77209
```

Raw template hex (`ErgoTree.template`, excludes all constants):
```text
d803d601e4e30104d602db6308a7d6038cb272027300000195ec8f72017301927201b1a5d17302d806d604e3000ed605b2a5720100d60693cbc272057303d607c5a7d608ed93e4c67205040e720793e4c67205050e7203d60993dad901090eb0a57304d9010b41639a8c720b01b0db63088c720b027305d9010d414d0ed802d60f8c720d02d6108c720d0195938c720f0172099a72108c720f027210017203730695e67204ea02d19683040193cbe47204e4c6a7080e720672087209d40800d801d60ae4e3020495ecec8f720a730792720ab1a593720a7201d17308d802d60bdb63087205d60cb2a5720a00d196830a018fa3e4c6a707047206720892c172057309eded93b1720b730a938cb2720b730b0001730c928cb2720b730d0002b2e4c6a70611730e0093cbc2720ce4c6a7050e93e4c6720c040e720793db6308720cb47202730fb1720292c1720cc1a77209
```

Mainnet address:
```text
fgUe81JcqHNHUYNHVKpQTqFBdPicEFTB9ypvzuLjjWKttPR5kwkxiraNJnDadLc1JBXsGDyK6HUWKR1bQzADup4y1hRAWRsfoxSiZ4etSm1PTJVqY3UGfoZFrs95cxnKFusNjR7TUgD9RzTT5YWcZ1H4rT9P7ZEgXU738pdSx266Ci3RKJEYFPbsKYwYVF2fmGvmuMrXTWi5B5498n3FzZwZkQSQs93D2K1UAd5dqMp3LFgEcPExp57fJeV9Vi2WdxZKtqbGC5vyhRmJ5aP91s66SszwSW5dHS6octTnF8t5yTsfj354HNqR8YghVHtWicZaXtK551qqm5X9Aq4fVsJva8FeRGLmsGJmHuE5kyKZuvWtTqEhskDK3Rrwvv6c2zkjmggEggSuaCHRgLBJer3Kxn1tHej9EbTonDmTv4YgnFcSaNEGs9xAUpF4ciK2pKXqhrm8b8gzhpEwdVmnbWXrtCRkMvtjYzkcXJyBGcFyKu42fTLmwmkCmXKEvS8EdgSJR3KgSrTibje7A9Xa2fGYmxM2QPVuETmVKXupzKrJor1agWR6QbXo9D9ZTuTwQk3gVfUSxzc8UdbmdzRunFN9GcEU
```

Testnet address:
```text
56oAMZ1tu2ZcapSNsY4t6K8oD8ajdiaErfPvQ9QhDt51wmYg3SAq6jWMJALjUE8VbWtyL1ceS5PSSf11PLVmuEEuTsG2G9KPh1VHmspkKD6ZEQLc4T6TViKiN5yq7cMPJKjNWENP4TvnVwtMYdbiwRmfkpgNu5U3FstYGF1v6btx1jCsW9XdXAGiqc2Wbh2nM1dWCBCSFdtMbn9GSvbUn1naoDBJXnxdrswhHYNDH9BY6G6VU7BjEYPUdQsT7DAX1MaW1uFqu1LSEjsgGcBm5wc8b9McLC5356FmDKZzF8cw8oczFFQSmaZZx83JpfjSuryjatLSR3uoq326iaqtELsH1wx5RfTfCssK5APfjvrQ96MvoG4N4TpW3yxv9DwsPCSuMN7q4aPoxaXQPP2bmSSbBPFtEqCRftAxv4DRcskuEmWkz5gdU8X9rtiS4AcGebJatS6wUkEpGcoGZ5AETnUq27RZ2GhZcPYk6rf9aNM8DKcSN41EhjM15BQADTnRoXwvaRKyYn7gWqGwaNVKEac5kpce1UxfgfZBTZc5hmgpqey6ygLtLQmxUbzevwW8apGX4PDX68g28gi3VMUw3VcH7rbb6
```

Constant registry. Only named terms may change; every `fixed` constant must match this prototype exactly.
Values below are serialized Sigma constants, including their type prefix; they are not raw token IDs.

| Zero-based constant index | Role | Sigma type | Serialized constant hex |
|---:|---|---|---|
| 0 | `fixed` | `SInt$` | `0400` |
| 1 | `fixed` | `SInt$` | `0400` |
| 2 | `fixed` | `SBoolean` | `0100` |
| 3 | `fixed` | `Coll[SByte$]` | `0e20ed132c371e35881b1b4fde03f092bc2eb8d9deb96df89cd9a5a32ab4f94b3c6b` |
| 4 | `fixed` | `SLong$` | `0500` |
| 5 | `fixed` | `SLong$` | `0500` |
| 6 | `fixed` | `SLong$` | `0500` |
| 7 | `fixed` | `SInt$` | `0400` |
| 8 | `fixed` | `SBoolean` | `0100` |
| 9 | `fixed` | `SLong$` | `0580897a` |
| 10 | `fixed` | `SInt$` | `0402` |
| 11 | `fixed` | `SInt$` | `0400` |
| 12 | `CONST_TOKEN_ID` | `Coll[SByte$]` | `0e2003faf2cb329f2e90d6d23b58d91bbb6c046aa143261cc21f52fbe2824bfcbf04` |
| 13 | `fixed` | `SInt$` | `0400` |
| 14 | `fixed` | `SInt$` | `0400` |
| 15 | `fixed` | `SInt$` | `0402` |

### ZC_Order prototype

Header: `0x1b`; constant count: `32`.

Full serialized ErgoTree hex:
```text
1be20420050005000e206262626262626262626262626262626262626262626262626262626262626262050004000402040204040400050404000400040005020e206161616161616161616161616161616161616161616161616161616161616161040004000400050204020404040005e4b88d9d42040205e6b88d9d4204e8c06c0e20636363636363636363636363636363636363636363636363636363636363636305000580897a040205e2b88d9d420c4d0e01206464646464646464646464646464646464646464646464646464646464646464eab88d9d42d803d601c5a7d602dad901020eb0a57300d9010441639a8c720401b0db63088c7204027301d90106414d0ed802d6088c720602d6098c72060195938c72080172029a72098c7208027209017201d6037302959172027303d805d604b2a5730400d605b2a5730500d606e4c672050611d607b27206730600d608b2a5730700d1ededededededededed937201c5b2a4730800937202730991b1db63087204730a938cb2db63087204730b00017201938cb2db63087204730c0002730dededededed93cbc27205730e91b1db63087205730f938cb2db63087205731000017201938cb2db63087205731100027312d801d609db6308720593b472097313b17209db6308a792c17205c1a7edededededed93e4c67205040e720193e4c67205050e720393b17206731493b27206731500731693b27206731700731893e4c672050704731993e4c67205080e731aed927207731b9299c172057207731c91e4c6720507049aa3731deded93cbc27208720392c17208731e93db63087208731fd1aea4d901046393cbc272047203
```

Raw template hex (`ErgoTree.template`, excludes all constants):
```text
d803d601c5a7d602dad901020eb0a57300d9010441639a8c720401b0db63088c7204027301d90106414d0ed802d6088c720602d6098c72060195938c72080172029a72098c7208027209017201d6037302959172027303d805d604b2a5730400d605b2a5730500d606e4c672050611d607b27206730600d608b2a5730700d1ededededededededed937201c5b2a4730800937202730991b1db63087204730a938cb2db63087204730b00017201938cb2db63087204730c0002730dededededed93cbc27205730e91b1db63087205730f938cb2db63087205731000017201938cb2db63087205731100027312d801d609db6308720593b472097313b17209db6308a792c17205c1a7edededededed93e4c67205040e720193e4c67205050e720393b17206731493b27206731500731693b27206731700731893e4c672050704731993e4c67205080e731aed927207731b9299c172057207731c91e4c6720507049aa3731deded93cbc27208720392c17208731e93db63087208731fd1aea4d901046393cbc272047203
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
| 20 | `fixed` | `SInt$` | `0404` |
| 21 | `fixed` | `SInt$` | `0400` |
| 22 | `CONST_REPAYMENT` | `SLong$` | `05e4b88d9d42` |
| 23 | `fixed` | `SInt$` | `0402` |
| 24 | `CONST_BATCHING_FEE` | `SLong$` | `05e6b88d9d42` |
| 25 | `CONST_MATURITY` | `SInt$` | `04e8c06c` |
| 26 | `CONST_LIQ_SCRIPT_HASH` | `Coll[SByte$]` | `0e206363636363636363636363636363636363636363636363636363636363636363` |
| 27 | `fixed` | `SLong$` | `0500` |
| 28 | `fixed` | `SLong$` | `0580897a` |
| 29 | `fixed` | `SInt$` | `0402` |
| 30 | `CONST_PRINCIPAL_ERG` | `SLong$` | `05e2b88d9d42` |
| 31 | `CONST_PRINCIPAL_TOKENS` | `Coll[(Coll[SByte$],SLong$)]` | `0c4d0e01206464646464646464646464646464646464646464646464646464646464646464eab88d9d42` |

### ZC_OrderOnClose prototype

Header: `0x1b`; constant count: `33`.

Full serialized ErgoTree hex:
```text
1bf30421050005000e207272727272727272727272727272727272727272727272727272727272727272050004000402040204f4887a04040400050404000400040005020e207171717171717171717171717171717171717171717171717171717171717171040004000400050204020404040005f08fdfc04a040205f28fdfc04a0e20737373737373737373737373737373737373737373737373737373737373737305000580897a043c041005ee8fdfc04a0c4d0e01207474747474747474747474747474747474747474747474747474747474747474f68fdfc04ad803d601c5a7d602dad901020eb0a57300d9010441639a8c720401b0db63088c7204027301d90106414d0ed802d6088c720602d6098c72060195938c72080172029a72098c7208027209017201d6037302959172027303d807d604b2a5730400d605b2a5730500d606e4c672050611d607b27206730600d6089aa37307d609e4c672050704d60ab2a5730800d1ededededededededed937201c5b2a4730900937202730a91b1db63087204730b938cb2db63087204730c00017201938cb2db63087204730d0002730eededededed93cbc27205730f91b1db630872057310938cb2db63087205731100017201938cb2db63087205731200027313d801d60bdb6308720593b4720b7314b1720bdb6308a792c17205c1a7ededededed93e4c67205040e720193e4c67205050e720393b17206731593b27206731600731793b27206731800731993e4c67205080e731aed927207731b9299c172057207731ceded92997208a3731d9072097208927209997208731eeded93cbc2720a720392c1720a731f93db6308720a7320d1aea4d901046393cbc272047203
```

Raw template hex (`ErgoTree.template`, excludes all constants):
```text
d803d601c5a7d602dad901020eb0a57300d9010441639a8c720401b0db63088c7204027301d90106414d0ed802d6088c720602d6098c72060195938c72080172029a72098c7208027209017201d6037302959172027303d807d604b2a5730400d605b2a5730500d606e4c672050611d607b27206730600d6089aa37307d609e4c672050704d60ab2a5730800d1ededededededededed937201c5b2a4730900937202730a91b1db63087204730b938cb2db63087204730c00017201938cb2db63087204730d0002730eededededed93cbc27205730f91b1db630872057310938cb2db63087205731100017201938cb2db63087205731200027313d801d60bdb6308720593b4720b7314b1720bdb6308a792c17205c1a7ededededed93e4c67205040e720193e4c67205050e720393b17206731593b27206731600731793b27206731800731993e4c67205080e731aed927207731b9299c172057207731ceded92997208a3731d9072097208927209997208731eeded93cbc2720a720392c1720a731f93db6308720a7320d1aea4d901046393cbc272047203
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
| 7 | `CONST_DURATION` | `SInt$` | `04f4887a` |
| 8 | `fixed` | `SInt$` | `0404` |
| 9 | `fixed` | `SInt$` | `0400` |
| 10 | `fixed` | `SLong$` | `0504` |
| 11 | `fixed` | `SInt$` | `0400` |
| 12 | `fixed` | `SInt$` | `0400` |
| 13 | `fixed` | `SInt$` | `0400` |
| 14 | `fixed` | `SLong$` | `0502` |
| 15 | `CONST_BOND_HASH` | `Coll[SByte$]` | `0e207171717171717171717171717171717171717171717171717171717171717171` |
| 16 | `fixed` | `SInt$` | `0400` |
| 17 | `fixed` | `SInt$` | `0400` |
| 18 | `fixed` | `SInt$` | `0400` |
| 19 | `fixed` | `SLong$` | `0502` |
| 20 | `fixed` | `SInt$` | `0402` |
| 21 | `fixed` | `SInt$` | `0404` |
| 22 | `fixed` | `SInt$` | `0400` |
| 23 | `CONST_REPAYMENT` | `SLong$` | `05f08fdfc04a` |
| 24 | `fixed` | `SInt$` | `0402` |
| 25 | `CONST_BATCHING_FEE` | `SLong$` | `05f28fdfc04a` |
| 26 | `CONST_LIQ_SCRIPT_HASH` | `Coll[SByte$]` | `0e207373737373737373737373737373737373737373737373737373737373737373` |
| 27 | `fixed` | `SLong$` | `0500` |
| 28 | `fixed` | `SLong$` | `0580897a` |
| 29 | `fixed` | `SInt$` | `043c` |
| 30 | `fixed` | `SInt$` | `0410` |
| 31 | `CONST_PRINCIPAL_ERG` | `SLong$` | `05ee8fdfc04a` |
| 32 | `CONST_PRINCIPAL_TOKENS` | `Coll[(Coll[SByte$],SLong$)]` | `0c4d0e01207474747474747474747474747474747474747474747474747474747474747474f68fdfc04a` |

### MaturityLiquidation compilation tree

Header: `0x1b`; constant count: `4`.

Full serialized ErgoTree hex:
```text
1b57040400010004020402d801d601e4e3010495ec8f72017300927201b1a5d17301d802d602b2a5720100d603db6308a7d1eded92a3e4c6a7070493db63087202b472037302b1720392c1720299c1a7b2e4c6a70611730300
```

Raw template hex (`ErgoTree.template`, excludes all constants):
```text
d801d601e4e3010495ec8f72017300927201b1a5d17301d802d602b2a5720100d603db6308a7d1eded92a3e4c6a7070493db63087202b472037302b1720392c1720299c1a7b2e4c6a70611730300
```

### MaturityLiquidation runtime expression

Attach these raw expression bytes to the bond input as context variable 0 of type `Coll[Byte]`.
Do not attach the compilation ErgoTree or a serialized Sigma `Coll[Byte]` constant in their place.

```text
d801d601e4e3010495ec8f72010400927201b1a5d10100d802d602b2a5720100d603db6308a7d1eded92a3e4c6a7070493db63087202b472030402b1720392c1720299c1a7b2e4c6a70611040200
```

Expression blake2b256 (bond R8 / order `CONST_LIQ_SCRIPT_HASH`): `6b640d3ae3788c1dbe308a4d7f8a8daeddb3ba0c486e773f93ef807e5ccc86d0`.

### Source fingerprints

blake2b256 over UTF-8 source text after normalizing CRLF to LF; these fingerprints are not script hashes.

| Compiler input | Normalized source blake2b256 |
|---|---|
| `contracts/bonds/P2P/ZC_ERG_Bond.ergo` | `a380df8b2fd3f1c16826483fa9bc06963f8e3e7fa9493cf6fe8243ea2b39a5f1` |
| `contracts/bonds/P2P/ZC_Token_Bond.ergo` | `fb1a1c2e01e8f13fa9f4be7027c4c50a6ac4ca942bda5de219bc05f5cfc5654a` |
| `contracts/bonds/P2P/ZC_Order.ergo` | `f3dfd2d00c9b82e71ab34af3db1d43704079962bfa88d79e78682a3e76b29ca1` |
| `contracts/bonds/P2P/ZC_OrderOnClose.ergo` | `fc0fb63936762ff15c2bf9472606b8648a3d948ccb2f59e716a1cb80b1adb3d2` |
| `contracts/bonds/lender-scripts/BondAssetVault.ergo` | `71cd992bf06c84f2fc4de34ec18ad683518d27dbf648b4e9dd3287bbaf06647e` |
| `contracts/bonds/liquidations/MaturityLiquidation.ergo` | `cfe39325bdbec444c822587848d96aa99af2a6c35b230f2577d5faac38a2ddc0` |
| `src/main/scala/client/ScriptGenerator.scala` | `c5ad147c4cb04b29b8fc5692ea056be5781539c6b356da331462352a7a372bc3` |
| `src/main/scala/work/lithos/mutations/Contract.scala` | `fe1e473783454eab1b759feb7ea3ab9c757d8e26ec0aef615dd45a5573021213` |
| `build.sbt` | `24bff2ad1f970a11d84ac547b66b8b1e469299c18029476e447f3e16c723b6ef` |
| `project/build.properties` | `e6023cb99f0ff57fdcb183b400632402e711320ee722372eb7a6a844ba9f4897` |

<!-- END GENERATED CONTRACT REFERENCE -->
