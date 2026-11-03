# ErgoScript Testing Rules for Agents

Working ruleset for writing ErgoScript contract tests. Read `ERGOSCRIPT_RULES_GENERAL.MD` first for
the execution model, language hazards, design patterns, and security checklist.

This document is project-neutral. Adapt package names, fixture types, builders, dependencies, and
commands to the repository under test. Never paste an example unchanged without checking the Appkit,
Sigma, node, Scala, and ErgoTree versions.

---

## 0. Map the target repository before writing tests

Locate these surfaces and write down their paths:

| Surface | What to verify |
|---|---|
| Contract sources | Which `.ergo` or `.es` files are live, proposed, generated, or stubs |
| Contract compiler/builder | Resource lookup, injected constants, ErgoTree version, caching |
| Transaction builders | Input/output order, context variables, burns, fees, change, data inputs |
| Test packages | Which tests are offline specs, integration tests, or manual live-node levers |
| Box fixtures/mutators | Register indexing, token operations, creation height, scripts, values |
| Node fixtures | Block version, height, network type, parameters, headers, preheader |
| Protocol serializers | Canonical encoders/decoders for every byte structure contracts parse |
| Build filters | Suites excluded from default test runs or disabled by tags |
| Deployment metadata | Active script hashes, addresses, token ids, and network |

Do not extend a manual sandbox or live-node script when an asserted offline spec is required. Do not
report a passing default test command until you confirm it actually includes the new suite.

---

## 1. Pin versions and build a deterministic context

### Dependencies

Use the dependency versions already selected by the target project. A typical Scala/Appkit test setup
may need:

```scala
libraryDependencies ++= Seq(
  "org.ergoplatform" %% "ergo-appkit" % appkitVersion % Test classifier "tests",
  "com.squareup.okhttp3" % "mockwebserver" % mockWebServerVersion % Test
)
```

The Appkit test classifier can contain mocked clients and node-response fixtures. Its test-scope
dependencies may not be transitive, so inspect the resolved graph when a harness fails with a missing
test-only class. Do not hard-code the versions shown by another repository.

### Offline `BlockchainContext`

Where the selected Appkit version provides `FileMockedErgoClient`, load response fixtures from the
classpath rather than assuming Appkit's own source-tree paths exist:

```scala
private def loadResponse(name: String): String = {
  val src = Source.fromResource(s"mockwebserver/node_responses/$name")
  try src.mkString finally src.close()
}

new FileMockedErgoClient(
  Seq(
    loadResponse("<node-info-fixture>.json"),
    loadResponse("<headers-fixture>.json")
  ).asJava,
  Seq.empty[String].asJava,
  true
).execute { ctx =>
  // Build and sign deterministic transactions here.
}
```

Verify the fixture's block version, network type, height, node parameters, and headers. Language
features are gated by the compiled ErgoTree version; assert it directly:

```scala
assert(contract.getErgoTree.version == expectedVersion)
```

An offline context proves interpreter behavior for that fixture. It does not prove live-node JSON
serialization, mempool admission, miner behavior, current network parameters, or deployment ids.

### Running focused suites

Use the repository's build tool and package layout. For sbt projects, examples are:

```bash
sbt "testOnly example.contracts.specs.*"
sbt "testOnly example.contracts.specs.StateContractSpec"
```

Run the focused suite after each relevant edit, then the containing module, then the full enabled
suite. Record the exact command, result, property count if available, and timestamp. Keep historical
results separate from validation performed after the last source change.

---

## 2. What an interpreter test proves

The usual Appkit flow is:

1. build candidate boxes with the project's fixture or Appkit builder;
2. turn the contract box into an input;
3. attach context variables to the correct input;
4. assemble inputs, data inputs, outputs, fee, burns, and change;
5. ask a prover to sign the unsigned transaction.

Signing reduces each input script. A false or failed script makes signing throw. Therefore:

- a positive interpreter test expects signing to succeed;
- a negative interpreter test expects transaction construction to succeed and signing to fail.

Do not call every thrown exception a contract rejection. Building can fail first because the
transaction is underfunded, token arithmetic is invalid, a burn was omitted, registers are malformed,
or an output violates minimum-value rules.

### Assertion helpers

ScalaTest-style helpers:

```scala
protected def accepts(
  prover: ErgoProver,
  tx: UnsignedTransaction
): SignedTransaction =
  Try(prover.sign(tx)) match {
    case Success(signed) => signed
    case Failure(ex) =>
      fail(
        s"expected the contract to accept this spend, but signing rejected it: ${ex.getMessage}",
        ex
      )
  }

protected def rejectsAtSigning(
  prover: ErgoProver,
  tx: => UnsignedTransaction
): Throwable = {
  val built = Try(tx) match {
    case Success(unsigned) => unsigned
    case Failure(ex) =>
      fail(
        s"expected a well-formed transaction, but construction failed: ${ex.getMessage}",
        ex
      )
  }

  Try(prover.sign(built)) match {
    case Success(_) => fail("expected the contract to reject this spend, but it was accepted")
    case Failure(ex) => ex
  }
}
```

Take the transaction by name so the helper can classify build-time failures. Use a more permissive
`rejects` helper only when the property intentionally covers an unbuildable shape, and name that fact
in the test.

---

## 3. Structure tests around one perturbation

Use one property per named contract condition where practical. Name the property after the branch and
condition it isolates:

```scala
property("update: rejects a changed successor value (valuePreserved)") {
  withContext { ctx =>
    val scenario = validUpdate(ctx)
    rejectsAtSigning(
      scenario.prover,
      updateTx(scenario, scenario.successorWithReducedValue)
    )
  }
}
```

The central pattern is:

1. Build one known-good scenario.
2. Prove its positive transaction signs.
3. Perturb exactly one field for each negative.
4. Require the perturbed transaction to build.
5. Require signing to reject it.

If each negative builds a fresh transaction from scratch, a rejection may come from an unrelated
mistake. A positive control for the same shape is mandatory.

Useful fixture mutations include:

- add or subtract value;
- replace the proposition/script;
- add, remove, reorder, or change a token;
- replace exactly one register;
- add an unexpected trailing register;
- remove, reorder, mistype, or reattach a context variable;
- replace one input, output, or data input;
- reorder inputs or outputs;
- change creation height or preheader height;
- alter one serialized field while keeping the structure buildable.

Document whether fixture register indices are logical R4-R9 numbers or zero-based positions in a
sequence. Mixing these conventions produces tests that perturb the wrong field.

---

## 4. Harness recipes

### Cache contract compilation

Some Sigma compiler versions mutate source context on shared AST nodes. Compiling one parsed script
twice, especially from parallel suites, can fail even when the contract is valid. Follow the target
project's compiler behavior; common containment is:

```scala
Test / parallelExecution := false
```

```scala
object CompiledContracts {
  private var cache: Option[Contracts] = None

  def get(ctx: BlockchainContext, constants: Constants): Contracts = synchronized {
    cache.getOrElse {
      val compiled = compileAll(ctx, constants)
      cache = Some(compiled)
      compiled
    }
  }
}
```

Do not cache across distinct constant sets unless the cache key includes every value that changes the
compiled script.

### AVL trees

When using a toolkit-backed prover, create trees with the same key size, optional value size, and
operation flags as production:

```scala
protected def treeWith(
  entries: Seq[(Array[Byte], Array[Byte])],
  flags: AvlTreeFlags,
  params: PlasmaParameters
): Tree = {
  val tree = PlasmaMap[Array[Byte], Array[Byte]](flags, params)
  if (entries.nonEmpty) tree.insert(entries: _*)
  tree.prover.generateProof() // flush setup operations
  tree
}
```

Rules to test explicitly:

- Capture the input `AvlTree` value before mutating the prover.
- Flush fixture-setup operations before generating the proof under test.
- Generate operations in the same order the contract verifies them.
- A read may leave the digest unchanged but still affect which proof bytes are generated next.
- Digest equality alone may not preserve flags, key length, or optional value length.
- Invalid proofs, duplicate inserts, and missing deletes may throw rather than return a normal false.
- Test both allowed and forbidden operations for each flag configuration the protocol can create.

### Registers

Typical Appkit encodings:

```scala
Seq(
  tree.ergoValue,
  ErgoValue.of(counter),
  ErgoValue.of(BigInt(total).bigInteger),
  ErgoValue.of(startHeight)
)
```

Use `java.math.BigInteger` where the Appkit API requires it. Keep optional registers contiguous; omit
an unused trailing register rather than inserting a placeholder.

Test each register for:

- absent value;
- wrong type;
- boundary value;
- unexpected mutation;
- unexpected trailing register;
- oversized collection where the builder can still construct the box.

### Context variables

Common encodings:

```scala
// Coll[Byte]
ContextVar.of(
  0.toByte,
  ErgoValue.of(Colls.fromArray(bytes), scalaByteType)
)

// (Coll[Byte], Coll[Byte])
ContextVar.of(
  1.toByte,
  ErgoValue.pairOf(
    ErgoValue.of(Colls.fromArray(key), scalaByteType),
    ErgoValue.of(Colls.fromArray(value), scalaByteType)
  )
)

// Coll[Coll[Byte]]
ContextVar.of(
  2.toByte,
  ErgoValue.of(
    Colls.fromArray(items.map(x => Colls.fromArray(x)).toArray),
    ErgoType.collType(scalaByteType)
  )
)
```

Scala-facing byte types can differ from Java boxed byte types even when their runtime representation
matches. Use the type expected by the selected Appkit Scala API.

Test missing ids, wrong ids, wrong types, wrong input attachment, reordered attachment, duplicate
semantic roles, and values at legal length boundaries.

### Context-extension wire compatibility

At least one Appkit/Scala/JSON combination has signed context-extension entries in one iteration order
and transmitted them in another. Offline signing can pass while a node rejects the same transaction,
sometimes reporting failure on a signature input rather than the input carrying the variables.

Treat this as version-specific, not a universal fixed range or ordering rule:

1. inspect the selected Appkit conversion and serializer code;
2. attach variables deterministically;
3. serialize the transaction exactly as it will be sent;
4. compare the signed extension order with the JSON/wire order;
5. keep a live-node integration test for representative one-, multi-, and larger-variable shapes.

Do not infer safety from ascending ids unless the implementation proves that ordering contract.

### `executeFromVar`

Where a host contract executes supplied script bytes:

```scala
ContextVar.of(
  scriptVarId,
  ErgoValue.of(Colls.fromArray(candidate.valueBytes), scalaByteType)
)
```

Distinguish the bytes the interpreter executes from the proposition bytes or hash stored in the
whitelist. Use known-true and known-false scripts to isolate two independent claims:

- the host authenticates exactly the intended script bytes;
- the host requires the authenticated script to succeed.

Also test missing variables, malformed bytes, a valid but unlisted script, and a listed script whose
result is false.

### Transaction assembly

Wrap the project's builder so every test makes the same fee, burn, change, and data-input decisions:

```scala
protected def build(
  ctx: BlockchainContext,
  inputs: Seq[InputBox],
  dataInputs: Seq[InputBox],
  outputs: Seq[OutBox],
  fee: Long,
  changeAddress: Address,
  burn: Seq[ErgoToken]
): UnsignedTransaction = {
  // Adapt to the Appkit version and the repository's transaction builder.
  ???
}
```

Important boundaries:

- Reordering inputs is the direct negative for a first-input uniqueness guard.
- Two same-script inputs are required to test double satisfaction.
- Reducing a token output requires a matching explicit burn where the builder enforces conservation.
- Overpaying an output needs enough funding to build; otherwise the negative proves only imbalance.
- Fee and change outputs may be appended after caller-supplied outputs.
- A change box can accidentally satisfy an `OUTPUTS.exists` condition. Send change to a script and
  token set that cannot impersonate any protocol role.
- Test the no-change tail, minimum viable change, exact funding, one-unit shortfall, and fee-free paths
  if the protocol supports them.

### Provers and identities

```scala
protected def proverWith(
  ctx: BlockchainContext,
  secret: BigInteger
): ErgoProver =
  ctx.newProverBuilder().withDLogSecret(secret).build()

protected def contractOf(prover: ErgoProver): Contract =
  Contract.fromAddress(prover.getAddress)
```

Use distinct secrets for distinct actors. Test authentication with the expected signer, a different
signer, no signer where buildable, and a data input copied from another actor.

### Height and preheader

Contracts reading `CONTEXT.preHeader` need a preheader in the test transaction. Setting a preheader
can also fix the effective `HEIGHT`; choose one height and reuse it in every input, output, register,
and boundary calculation.

For height-sensitive tests, cover:

- just before, at, and just after each boundary;
- a transaction built at one height and evaluated at a later plausible height;
- successor creation height;
- missing or inconsistent preheader data;
- miner key or other preheader authentication, if used.

Avoid ambiguous fixtures whose result changes depending on whether a harness interprets height as
the current header height or the next block height.

---

## 5. Build serialized fixtures through canonical code

If a contract parses headers, proofs, transactions, VLQ fields, Merkle paths, or application records,
do not fabricate arbitrary byte arrays unless malformed bytes are the subject of the test.

1. Construct the semantic object through the protocol's real model.
2. Serialize it with the production serializer.
3. Deserialize it with the production parser.
4. Assert round-trip equality or the exact fields the contract consumes.
5. Only then use the bytes in an interpreter test.

Keep a small fixture-probe suite independent from contract logic. If canonical bytes do not round-trip
there, no contract test using them is trustworthy.

For every variable-length format, cover:

- the shortest valid encoding;
- every length-prefix transition relevant to offsets;
- the longest protocol-valid encoding;
- one truncated value;
- one inconsistent declared length;
- trailing bytes if the parser may ignore them;
- field order and endianness;
- valid structure with invalid semantics.

Producing invalid evidence is often easier than producing valid evidence. A negative case for a fraud
condition needs data that is valid in exactly the property under test. If a synthetic boundary value
stands in for a realistic proof, say so explicitly.

---

## 6. Minimum test matrix

### Compilation and binding

- Every deployable contract resource resolves through its production builder.
- Missing resources fail with an informative error, not a null dereference.
- No builder silently substitutes `sigmaProp(true)`, `sigmaProp(false)`, or an old draft.
- Injected constants match the intended network and environment.
- The compiled ErgoTree version matches the feature set.
- Contract hash/address changes are detected and surfaced.

### Each spending path

- One positive control.
- One negative per named condition.
- Unknown operation code.
- Missing and wrong-typed register/context variable.
- Wrong signer or missing proof.
- Wrong script with otherwise correct value, tokens, and registers.
- Wrong token id and wrong token quantity, separately.
- Unexpected extra input, output, data input, token, register, and context variable.
- Reordered inputs and outputs.

### State conservation and composition

- Value preserved or changed only by the defined amount.
- Every state token and quantity preserved, minted, or burned exactly as specified.
- Every unchanged register preserved.
- Successor script pinned.
- Counterparty output script pinned.
- Data input authenticated and bound to the current actor/state.
- Two contract instances cannot accept one successor.
- Every pair of collaborating operation branches is tested, including mismatched operation codes.
- A handoff test spends a box produced by one real contract under the next real contract.

### Arithmetic and economics

- Zero, one, maximum, and one-beyond-boundary values where buildable.
- Negative signed deltas where the type permits them.
- Same-width multiplication overflow and narrowing conversion boundaries.
- Integer-division rounding in both directions.
- Positive-domain invariants such as reserves, supply, counters, and denominators.
- Exact fee, one unit below, and one unit above.
- Storage-rent floor against the largest contract-accepted box, using target network parameters.
- Economic cost of spam, cleanup, revocation, and permissionless refresh paths.

### AVL and proof structures

- Valid lookup, insert, update, and delete as applicable.
- Invalid proof and proof generated against the wrong digest.
- Duplicate insert and missing key.
- Reordered keys for batch proofs.
- Flags, key length, and value length preserved.
- Maximum supported batch or proof size with cost evidence.

### Time and lifecycle

- Before/at/after every height boundary.
- Build-height to inclusion-height slack.
- Empty state and last-item removal.
- First transition and repeated transition.
- Terminal state, cleanup, and value-drain liveness.
- Old-version box spent after an upgrade, if upgrades are possible.

### Dynamic scripts

- Exact listed script succeeds.
- Exact listed script returns false.
- Unlisted script returns true but is rejected.
- Missing, malformed, or substituted script bytes.
- Hash computed over the same byte representation the interpreter executes.

### Live-node qualification

Offline tests cannot establish all of these. Use a controlled live environment for:

- context-extension serialization and ordering;
- transaction JSON round trip;
- mempool admission at height boundaries;
- actual node cost and size rejection;
- fee and change behavior;
- deployed address/hash verification;
- multi-input signatures and proofs;
- network parameter differences.

Record the network, node/Appkit versions, height, transaction ids, and which branches were actually
exercised. Do not present an offline property as live evidence or a past live transaction as current
deployment validation.

---

## 7. Failure classification

When a test fails, classify it before changing the contract:

| Phase | Typical evidence | Likely class |
|---|---|---|
| Contract compilation | All properties fail with the same compiler error | Syntax, typing, constants, or unsupported ErgoTree feature |
| Fixture construction | Serializer/parser or register encoding fails | Invalid fixture or version mismatch |
| Transaction build | Balance, token, minimum value, burn, or index error | Test transaction is not well formed |
| Offline signing | Script reduces false or throws | Contract rejection, missing proof, or interpreter mismatch |
| JSON serialization | Signed transaction changes shape/order | Client serialization defect |
| Node submission | Offline pass but node reject | Version, wire, mempool, cost, or network-parameter difference |
| Confirmation | Mempool accept but no inclusion | Fee, conflict, timing, miner policy, or replaced inputs |

If every property in a suite fails identically, suspect compilation or shared harness setup before
assuming every contract condition regressed. If one negative unexpectedly passes, first verify that
the positive scenario and the one intended perturbation are exactly what reached signing.

---

## 8. Reusable bug archetypes to encode as regression tests

These patterns recur across ErgoScript systems:

1. **Operator precedence opens a branch.** An expression such as `a && b && c || d` may let `d`
   bypass all preceding checks. Give every disjunction its own truth-table tests.
2. **Digest-only equality loses AVL metadata.** Matching contents can still change flags or key/value
   shape and brick later transitions.
3. **Empty state locks value.** A partial-withdrawal branch may remain true at zero while the terminal
   branch becomes unreachable. Test empty, one-item, and last-item removal states.
4. **A builder compiles a stub.** The source may be complete while the production builder loads a
   placeholder or missing resource. Compile through the production entry point.
5. **Offline and wire ordering differ.** Context extensions or maps may be signed in one order and
   serialized in another. Preserve a live-node compatibility test.
6. **Storage-rent floor uses an honest observed size.** If the contract accepts extra registers or
   longer collections, the attacker chooses a larger box. Prove a maximum accepted size structurally.
7. **Signed arithmetic crosses the formula's intended domain.** A negative reserve, supply, or delta
   can flip an inequality and turn a bound into permission. Assert the domain separately.
8. **Counterparty contents are pinned but its script is not.** Combining different operation branches
   can let a credential or state token escape to an arbitrary contract.
9. **One output satisfies multiple inputs.** Repeated contract instances accept the same successor.
   Test two instances in one transaction.
10. **A negative fails before the interpreter.** Underfunding, an omitted burn, or invalid token supply
    creates a false-positive rejection. Require transaction construction to succeed first.
11. **A byte cap is smaller than a legal typed value.** An observed-size cap can make a valid future
    state permanently unspendable. Derive bounds from accepted types and lengths.
12. **A higher-order expression is parsed as a method value.** Compiler errors may point inside a
    lambda even though the surrounding `!`, arithmetic operator, or nested fold is the cause.
13. **Change satisfies an existential condition.** An automatically appended change output can rescue
    a negative test unless its script and tokens are deliberately unrelated.
14. **Protocol components are tested only in isolation.** Stand-in scripts can hide a register, token,
    or shape mismatch at the real handoff. Keep at least one full-composition property.

---

## 9. Comparing a deployed and proposed contract set

When evaluating a fix, compile both versions from one harness and run the same scenario matrix against
each. Parameterize:

- contract resource prefix or explicit source path;
- injected constants;
- expected script hashes;
- value floors and version-specific parameters;
- builder choice if the transaction shape changed.

The test should demonstrate:

1. the deployed version accepts the exploit or violates the intended invariant;
2. the proposed version rejects that exact transaction;
3. the proposed version still accepts every known-good path;
4. no unrelated boundary moved without an explicit decision.

Delete temporary comparison scaffolding only after the fix becomes the canonical source and the
regression properties run through the production compiler path.

---

## 10. Test handoff

For each build or audit pass, record:

- files and contract versions tested;
- exact focused and full-suite commands;
- results after the last edit;
- property or suite counts where the runner reports them;
- compiler, Appkit, Sigma, node, Scala, and ErgoTree versions;
- mocked network parameters and height;
- live-node transactions, if any;
- known flaky tests and isolation evidence;
- excluded suites and why they were excluded;
- untested branches, cost ceilings, maximum shapes, and deployment gaps;
- any script hash/address change caused by the work.

Keep four evidence classes distinct: source-proven, offline-tested, live-observed, and unverified.
