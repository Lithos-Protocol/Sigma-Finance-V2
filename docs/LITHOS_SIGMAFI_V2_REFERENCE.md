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

## Transaction assembly and index pairing

| Operation | Inputs | Required outputs | Context and burn |
|---|---|---|---|
| Create order | Borrower funding | Collateral under the instantiated order | No mint |
| Fill order | Order must be input 0; lender funds principal and extra boxes/fee | Output 0 lender unit; output 1 bond; output 2 principal to borrower | Mint exactly 2 units with id `order.id`; one in output 0, one in output 1 |
| Cancel order | Order plus a box guarded by borrower script | Refund as borrower chooses | No units minted; borrower authorization is by co-spend |
| Repay bond at input i | Bond plus repayment funding | Output i repayment to vault; output i+1 full collateral to borrower | Context var 0 absent; explicitly burn the bond singleton |
| Liquidate bond at input i | Bond with its agreed expression in context var 0; optional fee funding | Output i settlement to vault, tagged with bond id and asset id | Burn the singleton; expression decides eligibility, ERG and tokens |
| Claim vault | All vault boxes for an asset plus the holder's circulating unit | Holder chooses outputs | Burn the circulating unit |

For every settlement, vault R4 must equal the closing bond's box id, and vault R5 the bond's token id.
Collateral-return R4 must equal that same bond box id. A full repayment returns `tokens.drop(1)` and at
least the bond's ERG value. An ERG repayment pays at least R6(0). A token repayment pays exactly one
token ID, the compiled `CONST_TOKEN_ID`, at least R6(0), with at least 0.001 ERG.

Liquidations batch as bond inputs 0..n-1 to settlement outputs 0..n-1. Repayments take two outputs each,
so multiple bond inputs need filler inputs between them to keep the output positions distinct. Fee,
change and executor outputs must not shift protocol output indices. Specify burns explicitly in Appkit;
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
| ZC_ERG_Bond | 308 | `3839341bb246c79a8a4b98feffbf7805cbc41a8ca877920f2abade3507e92235` | `ad2b49034b49d7854aae0aae100e8c8e16c392637fdef5e1973a87c4992fcb6c` |
| ZC_Token_Bond prototype | 389 | `7bde37bd0eb58aba341db5a85e1aa970c1f9c2383330acf068191b5ad1f46d8d` | `6e9bdb6db48c3986a0f01b11876264a1c7aad6e547090e6238c91b793121290a` |
| ZC_Token_Bond SigUSD example | 389 | `9b2b3ca15eb9042a3ca8f5956d139a8feb6639dae56f74195c405f66962ad512` | `6e9bdb6db48c3986a0f01b11876264a1c7aad6e547090e6238c91b793121290a` |
| ZC_Order prototype | 613 | `050a5dd27b33ef127039900228e1fb767901d25f20f7177ed1b423957c29c4e6` | `5e82ac73dba7b8dbe463d8f05fd8058604542c00fecc0eade894785a24934bcf` |
| ZC_OrderOnClose prototype | 630 | `9a7d44d46f8b3cdb41b6b8caf6a190549b5ccd01b68cd21398b9811974a06b38` | `f5e91924fcc12cce98913e60a43d9c03b8d6495fd88409143aed94373e35499f` |
| MaturityLiquidation compilation tree | 64 | `0d660a043f63577e13763cc63d6ac21261232cf9e02392e5a7925f20971da7ba` | `76a44685dd68b00c6b54ced7529a84d72596b02a3d8d95150650ee51570e2f2a` |

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

Header: `0x1b`; constant count: `8`.

Full serialized ErgoTree hex:
```text
1bb102080e20ed132c371e35881b1b4fde03f092bc2eb8d9deb96df89cd9a5a32ab4f94b3c6b0400050005000500040204000402d809d601e3000ed602db6508fed603b2a5720200d60493cbc272037300d605c5a7d606db6308a7d6078cb2720673010001d608ed93e4c67203040e720593e4c67203050e7207d60993dad901090eb0a57302d9010b41639a8c720b01b0db63088c720b027303d9010d414d0ed802d60f8c720d02d6108c720d0195938c720f0172099a72108c720f027210017207730495e67201ea02d19683040193cbe47201e4c6a7080e720472087209d40800d801d60ab2a59a7202730500d1968309018fa3e4c6a707047204720892c17203b2e4c6a7061173060093cbc2720ae4c6a7050e93e4c6720a040e720593db6308720ab472067307b1720692c1720ac1a77209
```

Raw template hex (`ErgoTree.template`, excludes all constants):
```text
d809d601e3000ed602db6508fed603b2a5720200d60493cbc272037300d605c5a7d606db6308a7d6078cb2720673010001d608ed93e4c67203040e720593e4c67203050e7207d60993dad901090eb0a57302d9010b41639a8c720b01b0db63088c720b027303d9010d414d0ed802d60f8c720d02d6108c720d0195938c720f0172099a72108c720f027210017207730495e67201ea02d19683040193cbe47201e4c6a7080e720472087209d40800d801d60ab2a59a7202730500d1968309018fa3e4c6a707047204720892c17203b2e4c6a7061173060093cbc2720ae4c6a7050e93e4c6720a040e720593db6308720ab472067307b1720692c1720ac1a77209
```

Mainnet address:
```text
5PxqVLKJBKWqownSfVSv9cRj7Zcr4KWhNWZQC4EkA8VDfkJeuEbgz1KX7ReDquJ155rS3NNuDFebR9NU68VfACdaBFMJxfVBVaHNhYqWNcfS1mPipnW23uHhADe2Wm2ULqs854Pd2XZibiGDaEyQmgfE6n3xHdMUvfDpMyDB8VyUdHTpUH6iduT2kmrkeWo9JFHUx5cyqa5TJeGAeYJnzuACWcrxT3pKUAbDMduLbJgAGhfiD2M3efKjXwUQhMJAaTwnwXLE63S7i8YkuHVL4nJDrjXWF3niKoMchS2HHwSqdbBUXviFgfSzTnmx8kWaTV4AC7MgR6p2snnrRrRBUi61uxpMj8VcwhLw9SzHTsUpbr1cJ9FA7dTpc5Nb8VFKu91gEKQgio18PGfEv1UBB2f4HFWjDb2wsYwTNEtEKc3
```

Testnet address:
```text
U2SY6G1HpSE28sQNqFvyLZ12GSFAjZnCj8VvxVJs5BFLKYGiq2ziNfC1k9aWVyBv4SPfvC2K7PjXXhNNmcPhY6nVAWd9UQuqnjWYKbnGegCy2fhrxJAn61KeSFYd3cBtJHgQ4UP4FEATqniGxLU5GEdQ2NuArqBC8oF3tUheXwoE7ubzcAdVuxfwF3aSj9RbAnBNqkxY9nhY5JQQxuDdUT7ekSNya5uY62Qq8k1defydCocRb8NsNEnjgoZnGo1bBU9XZSC67razhuNpoPct1sJtnch8gduGEAaehfZqyMWmGpmePLUvPiPEP6a8sJLvFmpPS7nYjLvBJCHT259fChmRqsmaVu8wsSKSkGQ14CmteDGHEVQLuztDNU8Gkr3PRTo4n7wgXdUC2XjQXgzP5MU5ftr6CwZBxcuJowbVcgW
```

### ZC_Token_Bond prototype

Header: `0x1b`; constant count: `13`.

Full serialized ErgoTree hex:
```text
1b82030d0e20ed132c371e35881b1b4fde03f092bc2eb8d9deb96df89cd9a5a32ab4f94b3c6b040005000500050004020580897a040204000e207e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e040004000402d809d601e3000ed602db6508fed603b2a5720200d60493cbc272037300d605c5a7d606db6308a7d6078cb2720673010001d608ed93e4c67203040e720593e4c67203050e7207d60993dad901090eb0a57302d9010b41639a8c720b01b0db63088c720b027303d9010d414d0ed802d60f8c720d02d6108c720d0195938c720f0172099a72108c720f027210017207730495e67201ea02d19683040193cbe47201e4c6a7080e720472087209d40800d802d60adb63087203d60bb2a59a7202730500d196830a018fa3e4c6a707047204720892c172037306eded93b1720a7307938cb2720a730800017309928cb2720a730a0002b2e4c6a70611730b0093cbc2720be4c6a7050e93e4c6720b040e720593db6308720bb47206730cb1720692c1720bc1a77209
```

Raw template hex (`ErgoTree.template`, excludes all constants):
```text
d809d601e3000ed602db6508fed603b2a5720200d60493cbc272037300d605c5a7d606db6308a7d6078cb2720673010001d608ed93e4c67203040e720593e4c67203050e7207d60993dad901090eb0a57302d9010b41639a8c720b01b0db63088c720b027303d9010d414d0ed802d60f8c720d02d6108c720d0195938c720f0172099a72108c720f027210017207730495e67201ea02d19683040193cbe47201e4c6a7080e720472087209d40800d802d60adb63087203d60bb2a59a7202730500d196830a018fa3e4c6a707047204720892c172037306eded93b1720a7307938cb2720a730800017309928cb2720a730a0002b2e4c6a70611730b0093cbc2720be4c6a7050e93e4c6720b040e720593db6308720bb47206730cb1720692c1720bc1a77209
```

Constant registry. Only named terms may change; every `fixed` constant must match this prototype exactly.
Values below are serialized Sigma constants, including their type prefix; they are not raw token IDs.

| Zero-based constant index | Role | Sigma type | Serialized constant hex |
|---:|---|---|---|
| 0 | `fixed` | `Coll[SByte$]` | `0e20ed132c371e35881b1b4fde03f092bc2eb8d9deb96df89cd9a5a32ab4f94b3c6b` |
| 1 | `fixed` | `SInt$` | `0400` |
| 2 | `fixed` | `SLong$` | `0500` |
| 3 | `fixed` | `SLong$` | `0500` |
| 4 | `fixed` | `SLong$` | `0500` |
| 5 | `fixed` | `SInt$` | `0402` |
| 6 | `fixed` | `SLong$` | `0580897a` |
| 7 | `fixed` | `SInt$` | `0402` |
| 8 | `fixed` | `SInt$` | `0400` |
| 9 | `CONST_TOKEN_ID` | `Coll[SByte$]` | `0e207e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e` |
| 10 | `fixed` | `SInt$` | `0400` |
| 11 | `fixed` | `SInt$` | `0400` |
| 12 | `fixed` | `SInt$` | `0402` |

### ZC_Token_Bond SigUSD example

Header: `0x1b`; constant count: `13`.

Full serialized ErgoTree hex:
```text
1b82030d0e20ed132c371e35881b1b4fde03f092bc2eb8d9deb96df89cd9a5a32ab4f94b3c6b040005000500050004020580897a040204000e2003faf2cb329f2e90d6d23b58d91bbb6c046aa143261cc21f52fbe2824bfcbf04040004000402d809d601e3000ed602db6508fed603b2a5720200d60493cbc272037300d605c5a7d606db6308a7d6078cb2720673010001d608ed93e4c67203040e720593e4c67203050e7207d60993dad901090eb0a57302d9010b41639a8c720b01b0db63088c720b027303d9010d414d0ed802d60f8c720d02d6108c720d0195938c720f0172099a72108c720f027210017207730495e67201ea02d19683040193cbe47201e4c6a7080e720472087209d40800d802d60adb63087203d60bb2a59a7202730500d196830a018fa3e4c6a707047204720892c172037306eded93b1720a7307938cb2720a730800017309928cb2720a730a0002b2e4c6a70611730b0093cbc2720be4c6a7050e93e4c6720b040e720593db6308720bb47206730cb1720692c1720bc1a77209
```

Raw template hex (`ErgoTree.template`, excludes all constants):
```text
d809d601e3000ed602db6508fed603b2a5720200d60493cbc272037300d605c5a7d606db6308a7d6078cb2720673010001d608ed93e4c67203040e720593e4c67203050e7207d60993dad901090eb0a57302d9010b41639a8c720b01b0db63088c720b027303d9010d414d0ed802d60f8c720d02d6108c720d0195938c720f0172099a72108c720f027210017207730495e67201ea02d19683040193cbe47201e4c6a7080e720472087209d40800d802d60adb63087203d60bb2a59a7202730500d196830a018fa3e4c6a707047204720892c172037306eded93b1720a7307938cb2720a730800017309928cb2720a730a0002b2e4c6a70611730b0093cbc2720be4c6a7050e93e4c6720b040e720593db6308720bb47206730cb1720692c1720bc1a77209
```

Mainnet address:
```text
w7b7Ht8ELRboQhcFmcV5ZwjpGJS4JL7dhjywVZwYd76CanXf33eBosWC71etNib7zoenYpUM9KMXuqAjmVjuxFDaECkobiZiGwBRKMXYVNo24m6ij8kfKGYpiZgTjLFBpseQdYbF9h3AQHhpAQtx9Pits1YXCj27QoxwJVSAw3oXnYXkTxyFudynWy42We3BXqfjcKTie3N9MHVQ2gWaRgbcDte3zeHEPcSLZe1yu6736HZHVz72LjGvRsNeiboKVBJGD7YMbUTmu77p1CorhcSbqdTCtVxGh8gBW8Xzicat6ecz8JhoSinwJdYMFMpsKrSr4W9rx6C5jrZMAZjynnE6bDLJuya2SY7iSaAJukir9z9e8tKtTcTjJLkATFUwDWVugbC7yzZYRDV3K6qZgmywmcByUXdPMm4BX2TP4jWNF9Yk3QCstErr8MhCgYgnSxLx24aBDFU38wWWKxwkzB27iwpZh4z8pPdi19F2e6GF5zYPPvjrPkLaQGhYGdnoBLm111r3jubUEztcVU8am8YfM
```

Testnet address:
```text
6jjuKU6TdANP5pbYRNStU91Akzuf2bA7N7fynDwC9nhmSYJH3o7UKMwzSXv7GZy6b8kSLxxQZNCN3rWY7eEAa7xGxdVwrxErNXWnr4Gfect1ernX24Z1mE5WaAG9L1dXCfCjogLWuHRVGesmpnBibXaA1msAEQRJfEaAHnUuEs7cYomhLp7PaxacWHDSVSNc9QDY6JKFfadLEnHeXp2Mr99FoE8UExt8trVxYrenoLHhHZe2XZLqLLB87RbaocTsgJh1b2Nr3dcnwsVBvMwscZmqsFPLy9i99ojcx3Az3mb3EGzywhmzFyVRugUrq9dLcoigafyT3cHo2NxzaoED7FghKr8rA2jKPfh8oX2GByphJDqgryXNop6ZqJPhsJdDGgcu7qhqJXTDk6EZyaGtttLhg5NAbZEjHc2JoxAroQ6q2vAptn7sfArXopTvL2w1bMLBTzveG1ctry9yBxL7YKmRpznoi6r5i6JXXecKQzZFHDyemD4iX6qpECben6iuH7A6nbgp8nrBe58CoqXNX6MpAn
```

Constant registry. Only named terms may change; every `fixed` constant must match this prototype exactly.
Values below are serialized Sigma constants, including their type prefix; they are not raw token IDs.

| Zero-based constant index | Role | Sigma type | Serialized constant hex |
|---:|---|---|---|
| 0 | `fixed` | `Coll[SByte$]` | `0e20ed132c371e35881b1b4fde03f092bc2eb8d9deb96df89cd9a5a32ab4f94b3c6b` |
| 1 | `fixed` | `SInt$` | `0400` |
| 2 | `fixed` | `SLong$` | `0500` |
| 3 | `fixed` | `SLong$` | `0500` |
| 4 | `fixed` | `SLong$` | `0500` |
| 5 | `fixed` | `SInt$` | `0402` |
| 6 | `fixed` | `SLong$` | `0580897a` |
| 7 | `fixed` | `SInt$` | `0402` |
| 8 | `fixed` | `SInt$` | `0400` |
| 9 | `CONST_TOKEN_ID` | `Coll[SByte$]` | `0e2003faf2cb329f2e90d6d23b58d91bbb6c046aa143261cc21f52fbe2824bfcbf04` |
| 10 | `fixed` | `SInt$` | `0400` |
| 11 | `fixed` | `SInt$` | `0400` |
| 12 | `fixed` | `SInt$` | `0402` |

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

Header: `0x1b`; constant count: `2`.

Full serialized ErgoTree hex:
```text
1b3e0204020402d802d601b2a5db6508fe00d602db6308a7d1eded92a3e4c6a7070493db63087201b472027300b1720292c1720199c1a7b2e4c6a70611730100
```

Raw template hex (`ErgoTree.template`, excludes all constants):
```text
d802d601b2a5db6508fe00d602db6308a7d1eded92a3e4c6a7070493db63087201b472027300b1720292c1720199c1a7b2e4c6a70611730100
```

### MaturityLiquidation runtime expression

Attach these raw expression bytes to the bond input as context variable 0 of type `Coll[Byte]`.
Do not attach the compilation ErgoTree or a serialized Sigma `Coll[Byte]` constant in their place.

```text
d802d601b2a5db6508fe00d602db6308a7d1eded92a3e4c6a7070493db63087201b472020402b1720292c1720199c1a7b2e4c6a70611040200
```

Expression blake2b256 (bond R8 / order `CONST_LIQ_SCRIPT_HASH`): `23b131585209a670a79817292233ed8c30618c3c8046ba174b655afee0e828a3`.

### Source fingerprints

blake2b256 over UTF-8 source text after normalizing CRLF to LF; these fingerprints are not script hashes.

| Compiler input | Normalized source blake2b256 |
|---|---|
| `contracts/bonds/P2P/ZC_ERG_Bond.ergo` | `dde49b648029f6597c275840841813e258cb498e5f3ca2cc88b340876e508a38` |
| `contracts/bonds/P2P/ZC_Token_Bond.ergo` | `1ae29b47c6071aa9689f1c230dc8d665149a8b7bb143b12496776504c39171ce` |
| `contracts/bonds/P2P/ZC_Order.ergo` | `f3dfd2d00c9b82e71ab34af3db1d43704079962bfa88d79e78682a3e76b29ca1` |
| `contracts/bonds/P2P/ZC_OrderOnClose.ergo` | `fc0fb63936762ff15c2bf9472606b8648a3d948ccb2f59e716a1cb80b1adb3d2` |
| `contracts/bonds/lender-scripts/BondAssetVault.ergo` | `71cd992bf06c84f2fc4de34ec18ad683518d27dbf648b4e9dd3287bbaf06647e` |
| `contracts/bonds/liquidations/MaturityLiquidation.ergo` | `fdb55d3319822d5d1873ecc638fd8803527d2e5fed548aae7135d8858a8cd561` |
| `src/main/scala/client/ScriptGenerator.scala` | `c5ad147c4cb04b29b8fc5692ea056be5781539c6b356da331462352a7a372bc3` |
| `src/main/scala/work/lithos/mutations/Contract.scala` | `fe1e473783454eab1b759feb7ea3ab9c757d8e26ec0aef615dd45a5573021213` |
| `build.sbt` | `24bff2ad1f970a11d84ac547b66b8b1e469299c18029476e447f3e16c723b6ef` |
| `project/build.properties` | `e6023cb99f0ff57fdcb183b400632402e711320ee722372eb7a6a844ba9f4897` |

<!-- END GENERATED CONTRACT REFERENCE -->
