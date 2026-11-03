# Offline contract specs

Ported from c-bonds, which took it from the Lithos client's spec harness. You don't need a node,
dust or mainnet. A suite runs in a few seconds and is safe to put in CI.

```bash
sbt test                                                       # everything
sbt "testOnly org.lithosprotocol.sigmafi.specs.harness.*"      # the harness probe
```

The rules behind the method are in [ERGOSCRIPT_TESTING_GENERAL.MD](./ERGOSCRIPT_TESTING_GENERAL.MD)
and [ERGOSCRIPT_RULES_GENERAL.MD](./ERGOSCRIPT_RULES_GENERAL.MD). Both are project-neutral and
shared with c-bonds; anything in this file or the repository takes precedence over them.

## Layout

| Path | What |
|---|---|
| `src/main/scala/work/lithos/mutations/` | the box/transaction API, copied from Lithos unchanged |
| `src/main/scala/mutations/NodeWallet.scala` | the miner-reward-delay constant the above needs |
| `src/test/scala/specs/harness/` | `ContractSpecBase`, `HarnessProbeSpec` |
| `src/test/scala/specs/<product>/` | one `<Product>SpecBase` plus one suite per contract |
| `src/test/scala/specs/bonds/` | `BondSpecBase`; suites for the ZC bonds, offer, order, `MaturityLiquidation`, `BondAssetVault`, `BondNFT_Generator`, and template verification |
| `src/test/scala/specs/app/` | `SigmaFiAppSpec`: the CLI's App end to end, over `FakeNode`, an in-memory chain served as the node's index and wallet JSON |

The mutations package keeps its Lithos name rather than the `org.lithosprotocol.sigmafi` prefix so
it can be re-synced with Lithos and c-bonds by copying. `BoxLoader`, `UTXOSelector` and
`NotEnoughInputsException` were left behind because they call the Lithos node API and nothing here
needs them.

`HarnessProbeSpec` touches no SigmaFi contract. It checks the harness itself: the mocked context,
offline signing, 6.0 compilation at ErgoTree v3, and that `rejectsAtSigning` really separates a
contract rejection from a transaction that never balanced. If it fails, distrust every other suite.

## How a spec works

There is no mockchain and no interpreter to call. Build boxes as `UTXO`, turn the one under test
into an input, attach context vars, assemble with `TxBuilder`, call `prover.sign`. Signing runs the
interpreter, so a positive is "sign succeeds" and a negative is "sign throws".

**Build one known-good transaction, then perturb exactly one field per negative.** That is the
whole method. If each negative builds its own transaction from scratch, a rejection might come
from an unrelated mistake and the test proves nothing. Give each spending path one scenario case
class and one curried assembler with a default for every field, so a negative names only the field
it changes. c-bonds' `V2SpecBase` (`matchScenario` / `matchTx`) is the worked example.

Use **`rejectsAtSigning`**, not `rejects`. `rejects` cannot tell a contract rejection from a
transaction that never balanced, so a negative with an arithmetic slip passes while proving
nothing. `rejectsAtSigning` splits it: building must succeed, signing must not. Reserve `rejects`
for negatives that genuinely cannot produce a buildable transaction, and say so in the property
name.

## Writing a suite for a SigmaFi contract

1. Add a `<Product>SpecBase extends ContractSpecBase` under `specs/<product>/`.
2. Compile each contract **once per JVM** in its companion object and cache it (see below). Compile
   through the same code path the client uses to deploy, never a spec-local re-derivation, or the
   spec tests a different tree from the deployed one.
3. Give it the identities (`proverWith` over fixed `BigInteger` secrets), the register builders and
   one scenario per spending path.
4. Add a probe for the contract set: ErgoTree v3, the size bit, and that every cross-contract hash
   actually appears in the tree that pins it.

## Things that cost time to rediscover

- **Use the `_v6` node fixtures.** The plain ones report `blockVersion 3`, which compiles ErgoTree
  v2 and rejects every 6.0 feature with `MethodNotFound`. `withCtx` already loads the `_v6` pair;
  `HarnessProbeSpec` fails if that ever changes.
- **Compile each contract once per JVM.** The sigma compiler mutates `_sourceContext` on shared
  AST nodes, so compiling the same script twice throws `can be set only once`. This is also why
  `Test / parallelExecution := false` is in `build.sbt`.
- **`executeFromVar` needs a literal id.** `executeFromVar[SigmaProp](0)` compiles;
  `executeFromVar[SigmaProp](0.toByte)` fails with `Variable executeFromVar not found in
  environment`. The compiler only resolves it when the argument is a constant.
- **appkit 6 sets the size bit itself on v3 trees.** `ctx.compileContract` already yields header
  `0x1b`, so c-bonds' `Kit.sized` is a no-op here. Assert the bit in the probe anyway, because it
  changes every address and every hash a contract compares against.
- **`withReg` is 0-based over the register `Seq`**, so R4 is index 0 and R9 is index 5. `withRegNum`
  takes the register number.
- **Reducing a token amount in an output is a burn.** Pass it in `burn` or `TxBuilder` rejects the
  transaction before the contract runs. Swapping one token for another needs the replacement to
  arrive on a funding input, or Ergo treats it as a mint and only `INPUTS(0).id` is mintable.
- **Fund every transaction.** Inputs must cover outputs plus the fee. A negative that overpays an
  output fails on funding, not on the contract, which is exactly what `rejectsAtSigning` catches.
- **A preHeader is the only way to move HEIGHT** off the mocked context's `123413`. Every maturity
  and expiry boundary needs `buildAt`.
- **Attach context vars in ascending id order.** Offline signing does not care. A live node does,
  because appkit signs them in attachment order and transmits them in `HashMap` order. Exposure is
  exactly two to four variables.
- **`allOf(Coll(...))` evaluates every element.** It builds the collection before testing it, so an
  `isDefined` next to its `.get` in one `allOf` guards nothing. Use a lazy `&&` chain for any
  condition that reads a register or an index off a foreign box. c-bonds shipped exactly this bug:
  cancel died on `None.get` while every negative still passed, because a throw reads as a
  rejection.
- **A template term must never sit in a constant-only expression.** Offers and orders are compiled
  once with sentinel values and stamped by swapping constants. The compiler folds any expression made
  only of constants, so `CONST_DURATION >= 30` became `true` at the sentinel and disappeared: every
  order stamped from that template, a 29-block one included, skipped the check. Write such checks
  against runtime data (`HEIGHT + CONST_DURATION - HEIGHT >= 30`, or a value read off the bond), and
  test the boundary on a *stamped* instance, never only on a direct compile.
- **A named value in ErgoScript is always evaluated**, so a value belonging to one path still runs
  on the other. Laziness has to live *inside* each named condition, not between them.
