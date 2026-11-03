# ErgoScript Rules for Agents

Working ruleset for writing and reviewing ErgoScript contracts. Re-read it at the start of every
contract-writing session. It is a lookup table, not a language tutorial.

This document is intentionally project-neutral. Before using it, discover the target repository's
layout, build system, compiler and node versions, contract conventions, deployment model, and test
harness. Repository instructions and protocol specifications take precedence over the examples here.

---

## 0. Establish the source of truth

Do not reconstruct language or protocol behavior from memory. Locate and read:

| What | What to find in the target project |
|---|---|
| ErgoScript language definition | The specification matching the compiler and node version in use |
| Protocol specification | State machine, token model, trust assumptions, invariants, and open questions |
| Contract sources | The live or deployable `.ergo` files, not only drafts or generated copies |
| Constant bindings | The code that injects token ids, scripts, periods, sizes, and economic parameters |
| Script loader/compiler | The code mapping resource names to compiled contracts |
| Transaction builders | The code fixing input order, output order, context variables, fees, and change |
| Test harness | Offline interpreter tests, live-node tests, fixtures, and assertion helpers |
| Deployment record | Network, ErgoTree version, addresses, hashes, and active parameter set |

Before editing:

1. Read all repository-level agent instructions.
2. Read the nearest sibling contract and its tests.
3. Trace every injected constant from source to compiler binding.
4. Trace every box position and context variable from builder to contract.
5. Record which facts are source-proven, test-proven, live-observed, or still unverified.

Never assume a draft, test resource, builder stub, or old address represents the deployed contract.

---

## 1. Working rules

1. Preserve the existing contract unless the task explicitly authorizes an in-place change. For
   exploratory work, use a clearly named draft or a separate branch/worktree according to the
   repository's conventions.
2. Put new contracts where the project's script loader can find them. Confirm the resource name and
   builder binding rather than inferring them from the folder name.
3. State what remains unverified: byte offsets, register types, input/output indices, cost limits,
   storage-rent assumptions, version behavior, and live-node compatibility.
4. Match the surrounding style unless the task includes a broader refactor.
5. Do not change contract logic and transaction-building logic independently when they share a shape
   assumption. Treat them as one interface.
6. A successful compile is necessary but not sufficient. A passing offline suite is necessary but
   does not prove live-node serialization, mempool behavior, cost headroom, or deployment correctness.

---

## 2. The ErgoScript execution model

An ErgoScript contract is one expression returning `SigmaProp`. It declares the conditions under
which a UTXO may be spent.

- **A missing condition is a spending path.** Unless constrained, output count and order, extra
  inputs and outputs, register values, token amounts, scripts, and context variables are chosen by
  the transaction builder.
- **Omission can be intentional.** If a branch deliberately leaves a value, output, spender, or
  token unconstrained, record that decision and its consequence in the assumptions comment.
- A contract sees consensus context such as `SELF`, `INPUTS`, `OUTPUTS`, `dataInputs`, `HEIGHT`,
  `CONTEXT.preHeader`, `headers`, and context variables. It cannot see who assembled the transaction,
  wall-clock time, service state, or other off-chain facts.
- **The script runs once for every input carrying it.** Multiple inputs see the same output set. If
  two instances can both accept one successor, the transaction may consume two states while
  preserving only one.
- Boolean truth is not authentication. `sigmaProp(condition)` proves only the data condition. A
  signature or another real `SigmaProp` must be composed explicitly when authorization is required.
- Data inputs are not spent and their scripts are not evaluated. The consuming contract must
  authenticate all data-input contents it trusts.

---

## 3. Types and syntax hazards

### Expression and numeric behavior

- ErgoScript is statically typed, Scala-like, and eager. Blocks are expressions; the last expression
  is the value. Every `if` needs an `else`, and both branches must have the same type.
- Numeric types include `Byte`, `Short`, `Int`, `Long`, `BigInt`, and, in supported versions,
  `UnsignedBigInt`.
- Widening can be implicit, but same-width arithmetic can overflow before assignment. For example,
  `500 * 1000000000` remains `Int * Int`; make an operand wide before the operation.
- Narrowing conversions can fail. Bound attacker-controlled values before `.toInt`, `.toByte`, or
  `BigInt.toLong`.
- Integer division truncates. Define where rounding dust goes and test both sides of every boundary.
- If overflow or conversion failure is intended to reject a spend, document and test it explicitly.
- `powHit` returns `UnsignedBigInt` in versions that support it; compare compatible types.

### Registers and serialized size

- Optional registers R4-R9 are read as `Option[T]`. `.get` on an absent register fails the script.
  A wrong register type also fails; it does not behave like `None`.
- Use `getOrElse` when an attacker controls presence and the safe default should make a condition
  false. Treat every `.get` as an assertion that needs justification.
- Registers must be contiguous.
- A collection has no useful protocol-specific length bound beyond the box limit. Constrain the
  length of every collection whose length affects correctness, cost, or storage rent.
- In common Sigma implementations, a `BigInt` payload is bounded to 32 bytes, but its serialized
  register representation has length overhead. Verify the exact serialization rules for the versions
  in use before deriving byte limits.
- Typical consensus constants include a maximum box size, proposition size, register count, token
  count, and BigInt payload size. Read them from the matching node/Sigma source; do not copy numbers
  from a different release without verification.

Box length is otherwise influenced by the transaction builder. If an idle box needs a storage-rent
size bound, prefer a structural bound:

```ergoscript
val noUnexpectedTail = successor.R8[Coll[Byte]].isDefined == false
```

Because optional registers are contiguous, refusing the first unused register refuses the tail. Read
the sentinel as an unbounded collection type so an unexpected register of another type also fails.
This is useful only when all preceding registers are themselves type-bounded or explicitly
length-bounded. A raw `bytesWithoutRef.size <= N` cap is more fragile: a number derived from one
observed box may reject a legitimate future value.

### Collections, tuples, and higher-order calls

- Common collection operations include `map`, `filter`, `fold`, `exists`, `forall`, `zip`, `slice`,
  `append`, `indices`, and `getOrElse`.
- Direct indexing throws on an out-of-range index. Check the size first or use an optional access
  method when available.
- There are no ordinary unbounded loops or recursion. Fixed iteration is commonly expressed as a
  fold over a literal collection.
- Bind a higher-order call to a `val` before using it as an operator operand if the compiler version
  mis-associates the expression. For example:

```ergoscript
val hasForbiddenOutput = OUTPUTS.exists { (box: Box) =>
  box.value < 0L
}
val noForbiddenOutput = !hasForbiddenOutput
```

An equivalent `forall` formulation may also avoid the issue. If an error reports an operator applied
to a function type and points inside the lambda, inspect the surrounding operator first.
- Tuples are pairs accessed with `._1` and `._2`. Nest pairs for larger records.

### Context variables and compile-time values

- Use `getVar[T](id)` for the current input and, where supported, `getVarFromInput[T](inputIdx,
  varId)` for another input.
- Context variables belong to a specific input. A cross-input read needs an input index that the
  contract itself validates.
- Some compiler versions adapt bare integer literals to the required `Short`/`Byte` parameters but
  fail when only one argument is explicitly converted. Annotate both arguments or neither, and prove
  the chosen form by compilation.
- Types such as `Option[T]`, `Header`, and `UnsignedBigInt` may not be storable in registers or context
  variables. Pass a supported serialized representation and deserialize only after validating the
  expected shape.
- Functions such as `fromBase16`, `fromBase58`, `fromBase64`, `PK`, `deserialize`, and `bigInt` are
  compile-time operations in common ErgoScript versions and require string literals.

### Dynamic scripts and authenticated trees

- `executeFromVar[SigmaProp](n)` executes a script supplied at spend time. Use it only when the exact
  script or its hash is authenticated against protocol state or an immutable constant.
- AVL operations return optional results and may fail on invalid proofs or illegal operations. Do not
  assume a malformed proof merely returns `None`; test the actual version.
- Proof generation and verification are order-sensitive. Generate lookup, insert, update, and delete
  proofs against the intended digest in the intended sequence.
- Comparing only `.digest` does not necessarily preserve operation flags, key length, or value-length
  configuration. Conserve every metadata field required by the protocol.
- Language features are gated by ErgoTree and node versions. Confirm the compiled ErgoTree version
  and test the exact target network configuration.

---

## 4. Recommended contract conventions

These conventions are not consensus rules. Adapt them to the repository, but keep one consistent
vocabulary across contracts, builders, tests, and documentation.

- `CONST_*`: a compile-time injected constant. Declare it in comments rather than shadowing it with a
  local value.
- `CTX_*`: a context variable, with its id documented.
- `next*`: a successor-state value.
- `onlyOne`: a first-input uniqueness guard when the protocol intentionally permits only one instance
  of the contract in a transaction.
- `selfOut`: an index-paired successor when batching is intentional.

Suggested skeleton:

```ergoscript
{
  // <ContractName>
  // Value: <what ERG this box holds>
  // Purpose: <one sentence>

  // REGISTERS
  // R4: <meaning and transition rule>
  val state = SELF.R4[T].get

  // CONSTANTS
  // CONST_STATE_NFT: <authentication role>

  // CONTEXT VARS
  // CTX_ACTION(0): <meaning>
  val CTX_ACTION = getVar[Byte](0).getOrElse((-1).toByte)

  // NEXT VALUES
  val successor = OUTPUTS(0)
  val nextState = successor.R4[T].get

  // DATA INPUTS
  // <role, expected token/script, and actor binding>

  // ASSUMPTIONS
  // <creation-time guarantees, transaction-shape requirements, trusted off-chain behavior,
  // and intentionally unconstrained fields>

  if (CTX_ACTION == 0) {
    val scriptPreserved = successor.propositionBytes == SELF.propositionBytes
    val tokenPreserved = successor.tokens == SELF.tokens
    val stateValid = nextState == state
    val conditions = allOf(Coll(scriptPreserved, tokenPreserved, stateValid))
    sigmaProp(conditions)
  } else {
    sigmaProp(false)
  }
}
```

- Use a named `val` for each condition and combine them once. The names form an audit trail and map
  directly to test properties.
- Add a helper function only when it removes repeated logic. Copy parsing functions only from a
  verified source matching the same serialization format.
- Comment why a condition exists, not merely what the expression says.
- Include an assumptions section in every contract. State what a predecessor enforced, what the
  transaction shape must be, what off-chain actors are expected to do, and what is deliberately not
  enforced.
- Unrecognized operation codes should fail closed unless forward-compatible behavior is explicitly
  part of the design.

---

## 5. Reusable design patterns

### First-input uniqueness guard

When a transaction must contain at most one instance of a contract and its role is fixed at input 0:

```ergoscript
val onlyOne = SELF.id == INPUTS(0).id
```

This prevents two instances from accepting the same successor. It also prevents batching those
instances. Verify that every collaborating contract agrees on the same positional convention.

### Index pairing for batchable state

When multiple instances must move in one transaction, pair each input with an output at its own index:

```ergoscript
val outputPresent = OUTPUTS.size > CONTEXT.selfBoxIndex
val selfOut = OUTPUTS(CONTEXT.selfBoxIndex)
val scriptPreserved = selfOut.propositionBytes == SELF.propositionBytes
```

Distinct input indices make the mapping injective, but the pattern has system-wide requirements:

1. Every contract that can share the transaction must use a compatible convention.
2. Fixed partner indices must become explicit, validated indices.
3. Output bounds must be checked before indexing.
4. Another condition must own the transaction's overall shape.
5. Per-box value headroom must not multiply across batched inputs.

Before migration, write a double-satisfaction test that spends two instances and proves neither can
claim the other's output. Do not mix first-input uniqueness and index pairing in the same transaction.

### Spent-box certificate

On Ergo, a newly minted token id is derived from the first input's box id. A protocol can use this as
a commitment to a spent box's serialized content: later, supplied bytes can be hashed and compared to
the token id.

Use this only after verifying the exact box-id and serialization rules in the target versions.

Constraints:

- Existence is not legitimacy. Anyone can create and spend their own box. Authenticate the claimed
  box's protocol role through its tokens, script, or other protected contents.
- Only the first input determines the new token id.
- Deserialization support and byte layout must be confirmed, not guessed.
- Carrying full box or proposition bytes may cost more than an authenticated lookup proof. Measure it.

### Credential token

A token minted while spending an authenticated authority box can represent membership or admission.
Holding the token proves presence, but not absence or current validity.

1. Pin the total mint across every output, not only the intended recipient. Otherwise one authorized
   transaction can create arbitrary extra credentials.
2. A token cannot prove non-membership. Use an authenticated accumulator when absence matters.
3. Authenticate the issuing authority; a token id alone proves only that some first input was spent.
4. Design revocation separately. A holder can front-run a transaction that must spend the holder's
   box. Actions that must always land should not depend on cooperation from the accused party.

### Counterparty output authentication

When one contract constrains an output intended for another contract, pin the counterparty script as
well as its value, tokens, and registers. The statement "the other contract checks it" is valid only
if the other contract checks it on every spending path the transaction can select.

---

## 6. Security checklist

Run this before calling a contract complete.

1. **Enumerate every branch.** Each side of `||` and each operation code is an independent spending
   path. State what an attacker can take or rewrite on each path.
2. **Close double satisfaction.** Use a first-input uniqueness guard or a fully compatible index-pairing
   design.
3. **Conserve explicitly.** Check successor value, token ids and quantities, proposition bytes, and
   every register not deliberately changed.
4. **Authenticate every input and data input.** Check role tokens or script hashes and bind the data
   to the actor or state instance using it.
5. **Bind output positions.** An output index has meaning only because the contract authenticates the
   output found there.
6. **Pin counterparty scripts.** Do not trust only output contents or a token that can escape to an
   arbitrary script.
7. **Audit every `.get` and direct index.** Decide whether failure is a safe rejection, an avoidable
   denial-of-service surface, or a liveness failure.
8. **Prove arithmetic domains.** Widen before multiplication, bound signed deltas, require nonnegative
   or strictly positive reserves where the formula needs them, and define rounding.
9. **Treat `HEIGHT` as inclusion-time state.** Transactions may be built at one height and included at
   another. Prefer inequalities and deliberate slack to exact equality unless the transaction fixes a
   consensus value in a justified way.
10. **Bound cost and collection lengths.** Test maximum legal shapes and one step beyond. Avoid folds
    over attacker-unbounded collections.
11. **Evaluate storage rent.** Determine whether the box can remain idle for a storage period while
    the protocol is healthy. If so, derive its value floor from a proven maximum serialized size and
    the relevant network parameters. Prefer structural size bounds to observed-byte caps.
12. **Analyze writable entry points.** Ask what it costs to post garbage and who can clean it up.
13. **Authenticate dynamic execution.** Every `executeFromVar` script must be pinned by an authenticated
    hash or exact bytes.
14. **Conserve AVL metadata.** A matching digest alone may not preserve flags or shape parameters.
15. **Check upgrade behavior.** Identify which constants and script hashes can change, who authorizes
    the change, and whether old boxes remain spendable.
16. **Fail closed.** Unknown operations, missing partners, and malformed structures should reject unless
    the specification explicitly defines another behavior.

---

## 7. Transaction-building awareness

The same contract can behave differently depending on transaction assembly.

- Fix `INPUTS.size` and input roles when correctness depends on them.
- Outputs are positional. Account for fee and change outputs appended by the builder.
- A context variable is attached to one input. Verify attachment to the same input from which the
  contract reads it.
- Reducing an output token amount may require an explicit burn declaration in the builder.
- Fund every negative test so the transaction builds. Otherwise a balance failure can masquerade as
  contract rejection.
- Ensure every output clears the current minimum box-value requirements.
- Treat input and output order as a protocol interface. Document it alongside the contract.
- Anyone may assemble a different transaction around a box unless the contract forbids it. Always
  reason from an adversarial builder, not only the project's normal builder.

---

## 8. Definition of done

A contract is not done until the handoff records:

- contract source and compiler binding;
- injected constants and their source;
- required input, output, data-input, and context-variable shapes;
- every spending path and deliberately unconstrained field;
- focused positive and negative interpreter tests;
- double-satisfaction and counterparty-composition tests;
- boundary tests for arithmetic, collections, height, tokens, value, and storage rent;
- maximum-cost evidence on the target version;
- live-node evidence for serialization-sensitive behavior, or an explicit statement that it remains
  unverified;
- resulting script hashes or addresses and whether deployment artifacts were updated.

Keep source-proven, offline-tested, live-observed, and unverified claims separate.
