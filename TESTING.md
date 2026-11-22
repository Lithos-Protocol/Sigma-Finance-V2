# Offline contract specs

Ported from c-bonds, which took it from the Lithos client's spec harness. You don't need a node,
dust or mainnet. A suite runs in a few seconds and is safe to put in CI.

```bash
sbt test                                                       # everything
sbt "testOnly org.lithosprotocol.sigmafi.specs.harness.*"      # the harness probe
```

The [Lithos integration reference](./docs/LITHOS_SIGMAFI_V2_REFERENCE.md) pins compiled bytes for the
client rewrite. Regenerate its marked block with
`sbt "Test / runMain org.lithosprotocol.sigmafi.specs.bonds.LithosReference --write"`, or verify it with
`--check`. `BondProbeSpec` checks for drift in the default suite. The exporter uses the same offline
V6 context and `ScriptGenerator` as the contract specs; it never contacts a live node.

The ZC Standard set is pinned the same way. `StandardContracts.compile` is the one compile path: the
specs use it, and `ZcStandardReference` writes its output to the trees the CLI loads
(`src/main/resources/zc-standard/contracts.tsv`) and to
[docs/ZC_STANDARD_REFERENCE.md](./docs/ZC_STANDARD_REFERENCE.md). `ZcStandardProbeSpec` fails while either
is stale, and also checks that every cross-contract hash appears in the tree that pins it. After any
change to a `ZC_Standard_*` contract, regenerate both with
`sbt "Test / runMain org.lithosprotocol.sigmafi.specs.bonds.ZcStandardReference --write"`. A comment-only
change leaves them as they are.

The rules behind the method are in [ERGOSCRIPT_TESTING_GENERAL.MD](./ERGOSCRIPT_TESTING_GENERAL.MD)
and [ERGOSCRIPT_RULES_GENERAL.MD](./ERGOSCRIPT_RULES_GENERAL.MD). Both are project-neutral and
shared with c-bonds; anything in this file or the repository takes precedence over them.

## Layout

| Path | What |
|---|---|
| `src/main/scala/org/lithosprotocol/sigmafi/mutations/` | the box and transaction API ported from Lithos, including the miner-reward-delay constant in `NodeWallet.scala` |
| `src/test/scala/org/lithosprotocol/sigmafi/specs/harness/` | `ContractSpecBase`, `HarnessProbeSpec` |
| `src/test/scala/org/lithosprotocol/sigmafi/specs/<product>/` | one `<Product>SpecBase` plus one suite per contract |
| `src/test/scala/org/lithosprotocol/sigmafi/specs/bonds/` | `BondSpecBase`; suites for the first-generation bonds, orders, `MaturityLiquidation`, `BondAssetVault`, template verification and collateral conversion |
| `src/test/scala/org/lithosprotocol/sigmafi/specs/bonds/ZcStandard*` | `ZcStandardSpecBase`, which views `StandardContracts.compile` per market (`scripts`, `exchange`, `marketOf`) and builds an order-to-bond `Flow`; suites for the bond, order and vault, the fractional pair, the asks, the lend offer, the Dutch auction pair and the fill and sale guards (`ZcStandardFillGuardSpec`); `ZcStandardProbeSpec` and `ZcStandardReference` for the pins |
| `src/test/scala/org/lithosprotocol/sigmafi/specs/app/` | `SigmaFiAppSpec` (first generation), and `StandardAppSpec` (bond lifecycle) and `StandardExchangeAppSpec` (shares, asks, lend offers, the batch scan) and `StandardOffchainSpec` (the zcs1 off-chain rules: script disclosure, token metadata, share count, face prices and lots, the maturity grid, cover at the fill and the guards as the CLI builds them, packed refunds and liquidations, ranked and chained batch runs, mempool lookups) for the ZC Standard: the CLI's apps end to end, over `FakeNode`, an in-memory chain served as the node's index and wallet JSON, whose transactions keep each input's spending proof and context extension as the node renders them. `StandardSpecSupport` holds the standard specs' world and seeding helpers. `SigmaFiCliSpec` covers argument and shell-line parsing. `LivePlanSpec` checks the live test's plan (`sbt livetest`, see the README), which only runs against a node: every command parses, every variable is found by a step it builds on, no step that may run first can rebind what a transaction reads, and every standard command is exercised |

When copying updates from Lithos or c-bonds, use the local `org.lithosprotocol.sigmafi.mutations`
namespace. `BoxLoader`, `UTXOSelector` and `NotEnoughInputsException` were left behind because they
call the Lithos node API and nothing here needs them.

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

## Measuring transaction cost

Set `SIGMAFI_COST` to a file path and run the suite:

```bash
SIGMAFI_COST=costs.tsv sbt test
```

Every transaction a spec accepts appends one tab-separated line: appkit's cost estimate from
signing, the input scripts in order (ZC Standard contracts by name and market, `wallet` for a
public key, `other` for anything unnamed), the data-input and output counts, and the signed size in
bytes. Without the variable nothing is written. The mocked node's `maxBlockCost` is 1,000,000 and
appkit enforces it while signing, so every accepted transaction is already under the reference
node's default per-transaction limit (`maxTransactionCost`, also 1,000,000). Group the lines by
their second column to get the heaviest case of each transaction shape.

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
- **Offline signing runs scripts at the context's own height.** appkit 6.0.0's default pre-header has
  `ctx.getHeight`, where a node includes a transaction at `ctx.getHeight + 1` at the earliest. The apps
  plan for the node's `+ 1`, so an app spec that seeds a box at a boundary (a recollateralize wait, an
  expiry) seeds it one block further back than the contract alone would need.
- **Attach context vars in ascending id order.** Offline signing does not care. A live node does,
  because appkit signs them in attachment order and transmits them in `HashMap` order. Exposure is
  exactly two to four variables.
- **`allOf(Coll(...))` evaluates every element.** It builds the collection before testing it, so an
  `isDefined` next to its `.get` in one `allOf` guards nothing. Use a lazy `&&` chain for any
  condition that reads a register or an index off a foreign box. c-bonds shipped exactly this bug:
  cancel died on `None.get` while every negative still passed, because a throw reads as a
  rejection.
- **A template term must never sit in a constant-only expression.** Orders are compiled
  once with sentinel values and stamped by swapping constants. The compiler folds any expression made
  only of constants, so `CONST_DURATION >= 30` became `true` at the sentinel and disappeared: every
  order stamped from that template, a 29-block one included, skipped the check. Write such checks
  against runtime data (`HEIGHT + CONST_DURATION - HEIGHT >= 30`, or a value read off the bond), and
  test the boundary on a *stamped* instance, never only on a direct compile.
- **A named value in ErgoScript is always evaluated**, so a value belonging to one path still runs
  on the other. Laziness has to live *inside* each named condition, not between them.

The bond suites also cover lender-selected fee witnesses on order fills (including zero, negative,
missing, incorrectly typed and unfunded fees), preserving borrower collateral, refunding unused
reserves to the vault on repayment, pinned principal registers, Long maturity/duration arithmetic and
overflow rejection, and settlement R6 Byte markers (0 repayment / 1 liquidation). The conversion
expression reads principal data directly from bond R6(2)/R7. Application tests exercise fee funding,
refund balances, metadata precision and schedules above Int.MaxValue end to end.

## Expression pricing (2026-11-14)

`ZcStandardPricedOrderSpec` signs both principal policies in ERG and SigUSD, including exact
one-unit price boundaries, independent guard/pricing witnesses, immutable non-price fields,
principal records, delayed on-close NAV, fixed principal floors and malformed-policy exits.
`ZcStandardAskSpec` also signs yield-priced units and partial shares. `StandardPricedAppSpec`
exercises both policies/markets through wallet and lend-offer fills, guarded resale with new
issuer origins, metadata, fractional trading, repayment and claims. `ZcStandardProbeSpec` verifies
pinned trees, both issuer hashes and template consistency of the priced order. See
[docs/ZC_STANDARD_PRICING.md](docs/ZC_STANDARD_PRICING.md) for the full interface.

Regenerate the pins before testing a contract change:

```text
sbt "Test / runMain org.lithosprotocol.sigmafi.specs.bonds.ZcStandardReference --write"
sbt "testOnly org.lithosprotocol.sigmafi.specs.bonds.ZcStandard* org.lithosprotocol.sigmafi.specs.app.Standard*"
sbt test
```

These are offline interpreter checks, not proof of live-node admission or a deployment. Existing
live-test flows remain available, but this session does not broadcast transactions or claim the
new priced paths have passed a live run.

Initial pricing validation on 2026-11-14: all 461 tests across 23 suites passed, and CLI help ran successfully.

### Yield margins and live-plan commands

`LivePlanSpec` asserts both pricing policies and maturity modes are covered, with margins, payment
bindings, ask-policy checks and sale-before-repayment ordering. `LivePricingSpec` signs the actual
A/B/E CLI commands over FakeNode and G's offer match, including guarded yield-priced Dutch shares,
then runs the same historical confirmation checks as the live runner. Interpreter pricing specs
cover signed margins before rounding and unchanged floor/guard enforcement. This remains offline
validation: adding the plan does not broadcast transactions or establish live-node evidence.

Yield-margin/live-plan follow-up on 2026-11-14: all 467 tests across 24 suites passed; CLI help and
pinned-reference checks passed. No live-node transactions were broadcast.

## Two-point yields, curve offers, Selective vaults and lots (2026-11-16)

NAV pricing and price margins were removed, so the specs that covered them now cover their
replacements:

- `ZcStandardPricedOrderSpec`: two-point yield orders in ERG and SigUSD, exact to one raw unit, with
  flat, rising and falling lines and both clamps checked against one known price; malformed lines;
  on-close duration; floors, overpayment records and immutable terms as before.
- `ZcStandardAskSpec`: yield asks on units and shares over flat, rising and falling lines, a falling
  yield that leaves an earlier payment short, live-bond-only state checks, floors and continuations.
- `ZcStandardFillGuardSpec`: the guarded yield expression, including a guarded fixed price through a
  10^15 bps yield.
- `ZcStandardLendOfferSpec`: shares are bought only from the Dutch-pair mint, bounded by floor and
  expiry; plain fractional shares are refused; yield-ask purchases replace NAV-ask ones.
- `ZcStandardDutchAuctionSpec`: the eight mint terms and their bounds (expiry, threshold), the seven-long
  vault R7, a Selective pull that pays a 100-unit token in kind and auctions a 99-unit one, and claims
  that wait for the expiry while the lot stays on sale at the floor.
- App specs and `LivePricingSpec`: the CLI's `--yield/--end-yield`, `--curve`, `--vault`, Selective
  delivery and the updated live plan, signed offline.

Not yet covered by dedicated specs: `ZC_Standard_LotAuction` and the lend offer's curve beyond the
live plan's use. Both were checked once with throwaway probes (exact falling price, floor hold, expiry,
refund, cancel, own-currency refusal; curve midpoints, uneven segments, flat ends, the minimum,
ordering and the 20-point limit), which were not committed.

On 2026-11-16 all 488 tests across 26 suites passed. No live-node transactions were broadcast.

## Refresh, window orders, wraps and excess auctions (2026-11-16)

New suites:

- `ZcStandardRefreshSpec`: the paid refresh on the bond (age boundary, the 0.01 ERG bounty and the
  reserve-plus-0.001 floor, every register, token and the script kept, the 60-to-999-block stamp, no
  refresh in the 720 blocks before maturity and again from it), and what a refreshed bond still does
  (recollateralize in the same block, repay, liquidate into a vault naming the refreshed box, be
  claimed). Bond vaults, fractional vaults (holding: the pull fee kept; pulled: the path byte kept)
  and Dutch vaults (every register; a refresh midway through an excess auction leaves its price).
- `ZcStandardWrapSpec`: the receipt mint (3 units, metadata, a bare vault, the request as the only
  input, the fee floor), a stand-in for LithosDex's "owner NFT in any input" rule that is spent beside
  the raw token and never beside the vault or a bond holding the receipt, unwrap by burning all three,
  cancel, and the wrap vault's refresh, alone.
- `ZcStandardLotAuctionSpec`: the lot auction's falling price to one raw unit before, during and after
  the fall in both currencies, the payout's seller and tag, own-currency lots refused, term bounds,
  buys stopping at expiry, tagged refunds, cancels.
- `StandardWindowRefreshWrapAppSpec`: the CLI and app for window orders (options, the default grid
  maturity, a chosen maturity, refusals, a batch match at zcs1's maturity), refreshes (a copy of a bond
  whose borrower is found through its fill, the 60-back stamp, disclosure, a third party's repayment
  after it, the maturity gap, packed vaults, a wrap vault alone), wraps and Dutch excess auctions.
  Refresh properties run the app 600,000 blocks after the mocked height; `prepare` adds a preheader when
  an app's height differs from its context's.

Additions to existing suites: the lend offer's curve (segments exact, breakpoints, flat ends, the
R5(0) floor, ordering, 20 points, odd payloads) and its purchase of a refreshed bond's unit; window
orders in `ZcStandardPricedOrderSpec`; the Dutch excess auction (state 4 at the pull, its price to one
raw unit, no sale at zero, claims at the end of the fall) and the Dutch mint reading a refreshed bond,
live and settled, in `ZcStandardDutchAuctionSpec`; the wrap and curve-order cross-hashes in
`ZcStandardProbeSpec`. The throwaway LotAuction and curve probes are replaced by these.

On 2026-11-16 all 521 tests across 30 suites passed. Costs from `SIGMAFI_COST` are in SigmaBonds.MD,
"Cost and size". No live-node transactions were broadcast.

### Live plan: window orders and the Dutch excess auction (2026-11-17)

The live plan gained track V (a window order filled from the wallet at maturity `V`, a setup variable
600 blocks after the setup; a second matched by a lend offer at zcs1's default maturity; refusals of a
moving yield on a window order and of a maturity outside the window), a Dutch excess auction on bond D
(pull to state 4, a claim refused while it runs, the lot bought, the final claim) and an end check
that no wraps are left. `LivePlanSpec` asserts these paths; `LivePricingSpec` signs the V commands and
D's Dutch request offline. All 524 tests passed. Nothing was broadcast.

## Audit fixes (2026-11-17)

The fixes from `docs/ZC_STANDARD_AUDIT_2026-11-17.md` changed the fractional and Dutch mints, the Dutch vault
and the lot auction (and so the lend offer's tree); pins and the reference were regenerated. The existing 524
tests pass unchanged. Each fix was also checked by throwaway probes, not kept in the suite: both mints refuse a
vault R6 of the wrong type or value; a Selective excess sale must leave its fat token (ERG currency) and its
nanoERG (SigUSD currency) and claims then split them, while a Dutch one still sells everything; a lot payout
with an R5 is refused; the client refuses a hand-made share vault naming a genuine bond, and a bond refresh
beside a box younger than its stamp. Dedicated specs for these are listed in the audit report as test cases
to add.

## First live run without mempool chaining (2026-11-17)

A mainnet run with `chain off` (setup at height 1918919) failed ten steps; three causes, all fixed:

- The batch scan paired a lend offer with a share ask it refuses: it never checked that the offer buys shares or
  that their auction lies inside its floor and expiry bounds, which `match-offer` checks. O3 (delivers shares, buys
  none) claimed ask E, so order G went to O1. `StandardExchangeAppSpec` now has the scan list no purchase for three
  refusing offers and exactly one for the offer that accepts the shares; it failed before the fix.
- The test token sits in one box, and each step that spends it leaves the rest in unconfirmed change, which the
  wallet does not list without chaining. Seven token steps spent their three attempts waiting their turn. Waiting
  on other steps' transactions now spends no attempt (up to 20 times); `LivePlanSpec` round-trips the new count.
- G2 and G3 ran while E9, A10 and V10 were in flight; their block reached the index between G2's scan and G3's
  batch run, which then pulled vault A, spent O1 and bound the wrong box as G's share request. Without chaining
  the batch gate now waits until nothing is in flight, and a step that ran makes the gate scan again.

The runner changes are checked only by compiling and the state round trip; the next `chain off` run is their
test. All 524 tests pass. Nothing was broadcast.

## Early extinguish and pull-vault pinning (2026-11-22)

Added offline specs for `ZC_Standard_Bond`'s Extinguish path and `CONST_VAULT_HASH` pinning on both share vaults:

- `ZcStandardBondSpec`: Extinguish in ERG and SigUSD (fixed and on-close) before, at, and after maturity;
  unconstrained outputs; unit held at borrower address satisfying `borrowerAuth` directly and unit held at a
  non-borrower script with a separate borrower co-spend; negatives for lender-only, stranger co-spend,
  borrower without the 3rd unit, partial burns leaving 1 or 2 units alive, wrong R4, reserve != 2, zero-token
  box, and context-var routing isolation (`var 1/3/9`); atomic 1-tx `SaleOrder` collateral-funded buyback +
  Extinguish; atomic 1-tx Extinguish + `WrapVault` unwrap.
- `ZcStandardRefreshSpec`: a refreshed bond extinguishes cleanly.
- `ZcStandardFractionalSpec` and `ZcStandardDutchAuctionSpec`: adversarial `PULL` negatives pairing a live
  bond + `borrowerAuth` (Extinguish hijack) and a fake non-`BondVault` source carrying a valid `Byte` R5,
  proving `CONST_VAULT_HASH` rejects both.
- `ZcStandardProbeSpec`: asserts `CONST_VAULT_HASH` is pinned in both `FractionalVault` and `DutchAuctionVault`,
  and verifies all regenerated pins and `docs/ZC_STANDARD_REFERENCE.md`.
- `StandardAppSpec` and `LivePlanSpec`: end-to-end `extinguish` on ERG and SigUSD bonds (before and at maturity,
  with zero repayment cash and no `BondVault` created), non-borrower and missing-unit refusals, and Track G
  updated to exercise `extinguish`.

All 533 tests across 30 suites pass, with updated cost measurements recorded in `SigmaBonds.MD`. Nothing was
broadcast.
