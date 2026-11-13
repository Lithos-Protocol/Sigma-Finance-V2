package ksingh.sigmabonds
package livetest

/** What a step does, and when it counts as done. */
sealed trait Kind
object Kind {
  /** The setup transaction: the node wallet mints the test token and splits ERG into boxes. */
  case object Setup extends Kind
  /** A CLI command that submits transactions (run with --send); done once every one of them confirms. */
  case object Tx extends Kind
  /** A listing; done once it runs and prints everything in `expect`. */
  case object Read extends Kind
  /** A command the CLI must refuse with an error containing `expect`. Run without --send. */
  case object Refusal extends Kind
}

/** The kind of box whose expiry a gate reads. */
sealed trait BoxKind
object BoxKind {
  case object Order extends BoxKind
  case object Offer extends BoxKind
  case object Ask extends BoxKind
}

/** A condition on the chain a step waits for, beyond the steps it builds on. Names are variables. */
sealed trait Gate
object Gate {
  case object Open extends Gate
  /** From the expiry of the order, offer or ask whose box id is in `box`. */
  case class Expired(kind: BoxKind, box: String) extends Gate
  /** Only before that expiry; once it has passed the step is skipped, too late to show the refusal. */
  case class Unexpired(kind: BoxKind, box: String) extends Gate
  /** From the height at which the bond in `box` may take collateral again. */
  case class Recollateralizable(box: String) extends Gate
  /** From the height held in `height`. */
  case class Reached(height: String) extends Gate
  /** Only before the height held in `height`; afterwards the step is skipped. */
  case class Before(height: String) extends Gate
  /**
   * When a batch scan finds work and every piece of it spends only the boxes in `boxes`, so a batch run
   * does exactly the planned work and nothing else (in particular, nobody else's boxes).
   */
  case class QuietBatch(boxes: Seq[String]) extends Gate
}

/** What must hold once a step's transactions confirm. */
sealed trait Check
object Check {
  case object NoCheck extends Check
  /** No unspent box holds any of the token: every unit was burned. */
  case class TokenGone(token: String) extends Check
  /** The share vault in `box` records `state` in R6 and no longer holds its bond unit. */
  case class VaultState(box: String, state: Int) extends Check
  /** The bond in `box` holds exactly `amount` of the token in `token`. */
  case class BondHolds(box: String, token: String, amount: Long) extends Check
}

/**
 * Binds variable `name` to output `output` of one of the step's transactions: its last one, or with
 * `title`, the last whose title starts with it (a batch run submits several).
 */
case class Bind(name: String, output: Int, title: String = "")

/**
 * One step of the live test. `command` is a CLI command line with `{name}` for variables bound by earlier
 * steps. It runs once the steps in `after` have submitted (with mempool chaining) or confirmed (without), the
 * steps in `settled` have confirmed, and `gate` is open. `settled` is for a CLI check that reads the chain's
 * history rather than the mempool, chiefly the bond asset's origin: the order box must be spent on chain.
 * A listing or refusal never changes the chain or binds anything, so a step waiting on one only waits for it
 * to have run, whatever its outcome. `skipIf` skips the step when that variable is already bound. `noDefer`
 * fails the step at once rather than retrying it after the transactions in flight confirm. `last` steps run
 * once every other step is over. `expect` may name variables too. `about` says in a sentence or two what the
 * step does and what it shows off; the runner prints it under the step's title.
 */
case class Step(id: String, track: String, title: String, kind: Kind, command: String,
                after: Seq[String] = Nil, settled: Seq[String] = Nil, gate: Gate = Gate.Open,
                binds: Seq[Bind] = Nil, expect: Seq[String] = Nil, check: Check = Check.NoCheck,
                skipIf: Option[String] = None, noDefer: Boolean = false, last: Boolean = false,
                about: String = "") {
  /** The variables the command and the gate read. */
  def reads: Seq[String] = (LivePlan.placeholders(command) ++ expect.flatMap(LivePlan.placeholders) ++ (gate match {
    case Gate.Expired(_, b) => Seq(b)
    case Gate.Unexpired(_, b) => Seq(b)
    case Gate.Recollateralizable(b) => Seq(b)
    case Gate.Reached(h) => Seq(h)
    case Gate.Before(h) => Seq(h)
    case Gate.QuietBatch(bs) => bs
    case Gate.Open => Seq.empty
  }) ++ (check match {
    case Check.TokenGone(t) => Seq(t)
    case Check.VaultState(b, _) => Seq(b)
    case Check.BondHolds(b, t, _) => Seq(b, t)
    case Check.NoCheck => Seq.empty
  })).distinct
}

/**
 * The live test: every ZC Standard command, in ERG only, against a real node and wallet, with this wallet as
 * borrower, lender, seller, buyer and batcher. Amounts are small (0.1 ERG loans); everything but miner fees
 * comes back to the wallet. Tracks run side by side:
 *
 *  - A: a repaid bond's unit fractionalized; shares sold, bought, pulled and claimed
 *  - B: an on-close bond with token collateral, recollateralized after the 60-block wait, repaid from collateral
 *  - C: an oracle-rule bond below its ratio, refused by an offer, liquidated early in a pack with a refund
 *  - D/E: a lend offer filling an order and buying a unit from a NAV ask and Dutch shares from an ask; two
 *    bonds liquidated at maturity together; the Dutch auction pulled, its lot bought, the shares claimed
 *  - K: expiry refunds and cancels of orders, offers and asks, and a SigUSD-market order and ask
 *  - G: a batch run filling an order from an offer that delivers shares, and minting them; the final claim
 *    before the pull returns the unit, which claims the repaid bond
 *
 * Variables bound by the setup: `token` (the test token, collateral that the oracle values at nothing),
 * `T0` (the height the setup ran at) and `M` (T0 plus the maturity blocks: when bonds D and E mature).
 */
object LivePlan {

  import Kind._
  import Gate._

  /** Variables the setup binds. */
  val SetupVars: Seq[String] = Seq("token", "T0", "M")

  /** Blocks from the setup to the maturity of bonds D and E, unless the runner is told otherwise. */
  val DefaultMaturityBlocks = 40

  /** ERG the setup splits off into separate boxes, so independent steps need not share one. */
  val SplitBoxes = 8
  val SplitValue = "0.3"

  /** The test token the setup mints: it stands in for collateral the oracle does not price. */
  val TokenAmount = 1000000L
  val TokenName = "SigmaFi live test"
  val TokenDescription = "Test collateral minted by the SigmaFi live test. It has no value."

  /** 1% of a zcs1 share token in raw units (1.0 shares), so share steps read as percentages. */
  val SharePct: Long = standard.StandardMetadata.ShareCount / 100

  /** The least the wallet should hold before starting: about twice the most the plan locks at once. */
  val MinimumBalance = "4"

  private val Placeholder = """\{([A-Za-z0-9]+)\}""".r

  def placeholders(command: String): Seq[String] = Placeholder.findAllMatchIn(command).map(_.group(1)).toSeq

  /** `command` with every `{name}` replaced, or the first name `vars` lacks. */
  def fill(command: String, vars: Map[String, String]): Either[String, String] =
    placeholders(command).find(n => !vars.contains(n)).toLeft(Placeholder.replaceAllIn(command, m =>
      scala.util.matching.Regex.quoteReplacement(vars(m.group(1)))))

  private val oracleOrder = "--liquidation oracle --ratio 150 --oracle-age 720"
  private val offerTerms = "--yield 5 --collateral-floor 150 --oracle-age 720"

  private def tx(id: String, track: String, title: String, command: String, after: Seq[String] = Nil,
                 settled: Seq[String] = Nil, gate: Gate = Open, binds: Seq[Bind] = Nil,
                 check: Check = Check.NoCheck, skipIf: Option[String] = None): Step =
    Step(id, track, title, Tx, command, after, settled, gate, binds, check = check, skipIf = skipIf)

  private def read(id: String, track: String, title: String, command: String, expect: Seq[String] = Nil,
                   after: Seq[String] = Nil, gate: Gate = Open, noDefer: Boolean = false): Step =
    Step(id, track, title, Read, command, after, gate = gate, expect = expect, noDefer = noDefer)

  private def refusal(id: String, track: String, title: String, command: String, expect: String,
                      after: Seq[String] = Nil, gate: Gate = Open): Step =
    Step(id, track, title, Refusal, command, after, gate = gate, expect = Seq(expect))

  private def end(id: String, title: String, command: String, expect: String): Step =
    Step(id, "end", title, Read, command, expect = Seq(expect), last = true)

  private def out(name: String, i: Int) = Bind(name, i)

  /** Every step, each with its explanation from `About`. */
  lazy val steps: Seq[Step] = defined.map(s => s.copy(about = About.getOrElse(s.id, "")))

  private val defined: Seq[Step] = Seq(
    // ─── basics: listings that need nothing ──────────────────────────────────
    read("R1", "basics", "Pinned contracts, network and wallet", "info",
      Seq("ZC Standard, from pinned trees", "MAINNET", "Wallet address")),
    read("R2", "basics", "First-generation contracts compile", "info --old", Seq("First-generation contracts")),
    read("R3", "basics", "First-generation orders", "orders --old"),
    read("R4", "basics", "First-generation bonds of this wallet", "bonds --old --mine"),
    read("R5", "basics", "First-generation claims", "claims --old"),

    Step("S1", "setup", s"Mint $TokenAmount test tokens and split $SplitBoxes boxes of $SplitValue ERG", Setup, ""),

    // ─── D/E first: the longest chain before a fixed maturity ────────────────
    tx("O1", "D/E Dutch", "Lend offer: 0.6 ERG at 5%, oracle-rule bonds at 150%, Dutch shares too",
      s"create-offer --capital 0.6 $offerTerms --min-auction-floor 100 --max-auction-length 100",
      after = Seq("S1"), binds = Seq(out("offer1", 0))),
    tx("E1", "D/E Dutch", "Order E: borrow 0.1, repay 0.12, 0.25 ERG + 1000 test tokens, oracle rule, matures at M",
      s"create-order --principal 0.1 --repayment 0.12 --collateral-erg 0.25 --collateral-token {token}:1000 " +
        s"$oracleOrder --maturity {M} --exact-maturity --expires-in 30",
      after = Seq("S1"), binds = Seq(out("orderE", 0))),
    tx("D1", "D/E Dutch", "Order D: borrow 0.1, repay 0.11, 0.15 ERG, maturity rule, matures at M",
      "create-order --principal 0.1 --repayment 0.11 --collateral-erg 0.15 --maturity {M} --exact-maturity --expires-in 30",
      after = Seq("S1"), binds = Seq(out("orderD", 0))),

    // ─── C, first part: the offer must refuse an undercollateralized order ───
    tx("C1", "C oracle", "Order C: borrow 0.1, repay 0.12, only 0.1 ERG collateral, oracle rule at 150%",
      s"create-order --principal 0.1 --repayment 0.12 --collateral-erg 0.1 $oracleOrder --term 400 --exact-maturity " +
        "--expires-in 200",
      after = Seq("S1"), binds = Seq(out("orderC", 0))),
    refusal("C1x", "C oracle", "The offer refuses order C: its collateral is under the offer's floor",
      "match-offer {offer1} --order {orderC}", "under the offer's floor", after = Seq("O1", "C1")),

    tx("E2", "D/E Dutch", "Offer fills order E (a new bond): the unit comes to this wallet",
      "match-offer {offer1} --order {orderE}", after = Seq("O1", "E1"),
      binds = Seq(out("bondE", 1), out("offer1", 3))),
    tx("D2", "D/E Dutch", "Fill order D from the wallet", "fill-order {orderD}", after = Seq("D1"),
      binds = Seq(out("bondD", 1))),
    refusal("D2x", "D/E Dutch", "Bond D cannot be liquidated before maturity", "liquidate {bondD}",
      "it can be liquidated from then on", after = Seq("D2"), gate = Before("M")),
    read("E3", "D/E Dutch", "The offer lists with its remaining capital", "offers --mine", Seq("{offer1}"),
      after = Seq("E2")),
    tx("E4", "D/E Dutch", "Sell bond E's unit on a NAV ask at accrued value less 1%",
      "sell {orderE} --amount 1 --nav --margin -1", settled = Seq("E2"), binds = Seq(out("navAskE", 0))),
    read("E5", "D/E Dutch", "The NAV ask lists with its price", "asks --mine", Seq("{navAskE}", "NAV"), after = Seq("E4")),
    tx("E6", "D/E Dutch", "The offer buys the unit from the NAV ask; the unit comes back to this wallet",
      "match-offer {offer1} --ask {navAskE}", after = Seq("E4", "E5"), settled = Seq("E2"),
      binds = Seq(out("offer1", 2))),
    tx("E7", "D/E Dutch", "Fractionalize bond E's unit with a Dutch auction: 300% down to 250% over 20 blocks",
      "fractionalize {orderE} --dutch --auction-opening 300 --auction-floor 250 --auction-length 20",
      after = Seq("E6"), settled = Seq("E2"), binds = Seq(out("reqE", 0))),
    tx("E8", "D/E Dutch", "Mint the Dutch shares (this wallet as batcher)", "mint-shares {reqE}", after = Seq("E7"),
      binds = Seq(out("vaultE", 1))),
    tx("E9", "D/E Dutch", "Sell 40% of the Dutch shares at a fixed 0.04 ERG",
      s"sell {reqE} --amount ${40 * SharePct} --price 0.04", after = Seq("E8"), binds = Seq(out("askE", 0))),
    tx("E10", "D/E Dutch", "The offer buys the Dutch shares from the ask", "match-offer {offer1} --ask {askE}",
      after = Seq("E9", "E6"), settled = Seq("E7"), binds = Seq(out("offer1", 2))),
    tx("E11", "D/E Dutch", "Cancel what is left of the offer", "cancel-offer {offer1}", after = Seq("E10")),
    tx("DE1", "D/E Dutch", "Liquidate bonds D and E together at maturity", "liquidate {bondD} {bondE}",
      after = Seq("D2", "E10"), gate = Reached("M")),
    tx("D3", "D/E Dutch", "Claim liquidated bond D with its unit", "claim {orderD}", after = Seq("DE1"),
      check = Check.TokenGone("orderD")),
    tx("E12", "D/E Dutch", "Pull bond E's liquidation into the Dutch vault: the auction opens",
      "pull {vaultE}", after = Seq("DE1", "E8"), binds = Seq(out("vaultE", 0)), check = Check.VaultState("vaultE", 1)),
    read("E13", "D/E Dutch", "The open auction lists with its price", "shares --mine", Seq("AUCTION OPEN"),
      after = Seq("E12")),
    tx("E14", "D/E Dutch", "Buy the auction's lot (the test tokens) at its current price", "buy-lot {vaultE}",
      after = Seq("E12", "E13"), binds = Seq(out("vaultE", 0)), check = Check.VaultState("vaultE", 2)),
    tx("E15", "D/E Dutch", "Claim 30% of the Dutch shares", s"claim-shares {reqE} --amount ${30 * SharePct}",
      after = Seq("E14", "E10"), binds = Seq(out("vaultE", 1))),
    tx("E16", "D/E Dutch", "Final claim on the Dutch shares", "claim-shares {reqE}", after = Seq("E15"),
      check = Check.TokenGone("reqE")),

    // ─── A: a repaid bond's shares ────────────────────────────────────────────
    tx("A1", "A shares", "Order A: borrow 0.1, repay 0.11, 0.15 ERG, maturity rule, exact maturity in 400 blocks",
      "create-order --principal 0.1 --repayment 0.11 --collateral-erg 0.15 --term 400 --exact-maturity --expires-in 200",
      after = Seq("S1"), binds = Seq(out("orderA", 0))),
    read("A1m", "A shares", "Order A lists from the mempool, before it confirms", "orders --mine --mempool",
      Seq("{orderA}"), after = Seq("A1"), noDefer = true),
    tx("A2", "A shares", "Fill order A from the wallet", "fill-order {orderA}", after = Seq("A1"),
      binds = Seq(out("bondA", 1))),
    refusal("A2x", "A shares", "Bond A cannot be claimed before it settles", "claim {orderA}",
      "has not been repaid or liquidated yet", after = Seq("A2")),
    tx("A3", "A shares", "Fractionalize bond A's unit into 100 shares (zcs1)", "fractionalize {orderA}",
      settled = Seq("A2"), binds = Seq(out("reqA", 0))),
    read("A4", "A shares", "The share request lists", "shares --mine", Seq("{reqA}"), after = Seq("A3")),
    tx("A5", "A shares", "Mint bond A's shares (this wallet as batcher)", "mint-shares {reqA}", after = Seq("A3", "A4"),
      binds = Seq(out("vaultA", 1))),
    tx("A6", "A shares", "Sell 40% of the shares at a fixed 0.04 ERG",
      s"sell {reqA} --amount ${40 * SharePct} --price 0.04", after = Seq("A5"), binds = Seq(out("askA", 0))),
    read("A7", "A shares", "The ask lists, priced against face value", "asks --mine", Seq("{askA}", "of face"),
      after = Seq("A6")),
    tx("A8", "A shares", "Buy 10% of the shares back with the wallet", s"buy {askA} --amount ${10 * SharePct}",
      after = Seq("A6", "A7"), binds = Seq(out("askA", 1))),
    tx("A9", "A shares", "Cancel the rest of the ask", "cancel-ask {askA}", after = Seq("A8")),
    tx("A10", "A shares", "Repay bond A", "repay {bondA}", after = Seq("A5", "A2x")),
    tx("A11", "A shares", "Pull the repaid bond into the share vault", "pull {vaultA}", after = Seq("A10", "A5"),
      binds = Seq(out("vaultA", 0)), check = Check.VaultState("vaultA", 0)),
    tx("A12", "A shares", "Claim 25% of the shares", s"claim-shares {reqA} --amount ${25 * SharePct}", after = Seq("A11"),
      binds = Seq(out("vaultA", 1))),
    tx("A13", "A shares", "Final claim on the shares", "claim-shares {reqA}", after = Seq("A12", "A9"),
      check = Check.TokenGone("reqA")),

    // ─── B: recollateralize after the 60-block wait, repay from collateral ───
    tx("B1", "B recollateralize", "Order B: borrow 0.1, repay 0.11, 0.3 ERG + 1000 test tokens, on close, 90 blocks",
      "create-order --principal 0.1 --repayment 0.11 --collateral-erg 0.3 --collateral-token {token}:1000 " +
        "--duration 90 --expires-in 200",
      after = Seq("S1"), binds = Seq(out("orderB", 0))),
    tx("B2", "B recollateralize", "Fill order B from the wallet: its 90 blocks start", "fill-order {orderB}",
      after = Seq("B1"), binds = Seq(out("bondB", 1))),
    refusal("B2x", "B recollateralize", "Bond B cannot take collateral within 60 blocks of its fill",
      "recollateralize {bondB} --add-erg 0.01", "can take collateral again from height", after = Seq("B2")),
    tx("B3", "B recollateralize", "Recollateralize bond B with 0.02 ERG and 500 test tokens (60 blocks after its fill)",
      "recollateralize {bondB} --add-erg 0.02 --add-token {token}:500", after = Seq("B2"),
      gate = Recollateralizable("bondB"), binds = Seq(out("bondB", 0)), check = Check.BondHolds("bondB", "token", 1500L)),
    tx("B4", "B recollateralize", "Repay bond B out of its own ERG collateral", "repay {bondB} --from-collateral",
      after = Seq("B3")),
    tx("B5", "B recollateralize", "Claim repaid bond B", "claim {orderB}", after = Seq("B4"),
      check = Check.TokenGone("orderB")),

    // ─── C, the rest: early liquidation by the oracle rule ────────────────────
    tx("C2", "C oracle", "Fill order C from the wallet: the bond starts below its ratio", "fill-order {orderC}",
      after = Seq("C1x"), binds = Seq(out("bondC", 1))),
    read("C3", "C oracle", "Bond C lists as liquidatable", "bonds --liquidatable", Seq("{bondC}"), after = Seq("C2")),
    tx("C4", "C oracle", "Fractionalize bond C's unit", "fractionalize {orderC}", settled = Seq("C2"),
      binds = Seq(out("reqC", 0))),
    tx("C5", "C oracle", "Cancel the share request: the unit comes back", "cancel-share-request {reqC}",
      after = Seq("C4")),

    // ─── K: expiries and cancels ──────────────────────────────────────────────
    tx("K1", "K expiries", "Order K1, expiring in 10 blocks", "create-order --principal 0.1 --repayment 0.11 " +
      "--collateral-erg 0.01 --term 400 --exact-maturity --expires-in 10", after = Seq("S1"),
      binds = Seq(out("orderK1", 0))),
    refusal("K1x", "K expiries", "Order K1 cannot be refunded before it expires", "refund-order {orderK1}",
      "it can be refunded from then on", after = Seq("K1"), gate = Unexpired(BoxKind.Order, "orderK1")),
    read("K1b", "K expiries", "The batch scan lists the expired order", "batch", Seq("{orderK1}"), after = Seq("K1"),
      gate = Expired(BoxKind.Order, "orderK1")),
    tx("K1r", "K expiries", "Refund expired order K1 to its borrower", "refund-order {orderK1}", after = Seq("K1b"),
      gate = Expired(BoxKind.Order, "orderK1")),
    tx("K2", "K expiries", "Order K2, expiring in 10 blocks (refunded in a pack with bond C)",
      "create-order --principal 0.1 --repayment 0.11 --collateral-erg 0.01 --term 400 --exact-maturity --expires-in 10",
      after = Seq("S1"), binds = Seq(out("orderK2", 0))),
    tx("K3", "K expiries", "Order on the SigUSD market: borrow 1 SigUSD against 0.05 ERG (never filled)",
      s"create-order --market SigUSD --principal 1 --repayment 1.1 --collateral-erg 0.05 $oracleOrder " +
        "--term 400 --exact-maturity --expires-in 200",
      after = Seq("S1"), binds = Seq(out("orderS", 0))),
    read("K4", "K expiries", "The SigUSD order lists", "orders --mine", Seq("{orderS}", "SigUSD"), after = Seq("K3")),
    tx("K5", "K expiries", "Cancel the SigUSD order", "cancel-order {orderS}", after = Seq("K3", "K4")),
    tx("K6", "K expiries", "Lend offer of 0.05 ERG expiring in 10 blocks",
      "create-offer --capital 0.05 --yield 5 --collateral-floor 150 --expires-in 10", after = Seq("S1"),
      binds = Seq(out("offer4", 0))),
    tx("K7", "K expiries", "Refund the expired offer to its owner", "refund-offer {offer4}", after = Seq("K6"),
      gate = Expired(BoxKind.Offer, "offer4")),
    tx("K8", "K expiries", "Ask selling 100 test tokens for 0.01 ERG, expiring in 10 blocks",
      "sell {token} --amount 100 --price 0.01 --expires-in 10", after = Seq("S1"), binds = Seq(out("askT", 0))),
    tx("K9", "K expiries", "Refund the expired ask to its seller", "refund-ask {askT}", after = Seq("K8"),
      gate = Expired(BoxKind.Ask, "askT")),
    tx("K10", "K expiries", "Ask selling 100 test tokens for 1 SigUSD", "sell {token} --amount 100 --market SigUSD --price 1",
      after = Seq("S1"), binds = Seq(out("askS", 0))),
    tx("K11", "K expiries", "Cancel the SigUSD ask", "cancel-ask {askS}", after = Seq("K10")),

    tx("C6", "C oracle", "One transaction: liquidate bond C by the oracle and refund expired order K2",
      "pack {bondC} {orderK2}", after = Seq("C2", "C3", "K2"), gate = Expired(BoxKind.Order, "orderK2")),
    read("C7", "C oracle", "Bond C lists as claimable", "claims", Seq("{orderC}"), after = Seq("C6", "C5")),
    tx("C8", "C oracle", "Claim liquidated bond C: its collateral comes to the unit's holder", "claim {orderC}",
      after = Seq("C7"), check = Check.TokenGone("orderC")),

    // ─── G: a batch run ───────────────────────────────────────────────────────
    tx("G1", "G batch", "Order G: borrow 0.1, repay 0.12, 0.25 ERG, oracle rule, on close, 60 blocks",
      s"create-order --principal 0.1 --repayment 0.12 --collateral-erg 0.25 $oracleOrder --duration 60 --expires-in 300",
      after = Seq("S1"), binds = Seq(out("orderG", 0))),
    tx("O3", "G batch", "Lend offer of 0.3 ERG that delivers each bond unit into shares (batcher fee 0.006)",
      s"create-offer --capital 0.3 $offerTerms --batcher-fee 0.006 --deliver-shares", after = Seq("S1"),
      binds = Seq(out("offer3", 0))),
    read("G2", "G batch", "The batch scan finds the match of order G with the share-delivering offer, and nothing else",
      "batch", Seq("{orderG}", "{offer3}"), after = Seq("G1", "O3"), gate = QuietBatch(Seq("offer3", "orderG"))),
    tx("G3", "G batch", "Batch run: fill order G from the offer, then mint the delivered shares", "batch --run",
      after = Seq("G2"), gate = QuietBatch(Seq("offer3", "orderG")),
      binds = Seq(Bind("requestG", 0, "Match offer"), Bind("bondG", 1, "Match offer"), Bind("vaultG", 1, "Mint shares"))),
    tx("G4", "G batch", "Mint the delivered shares, if the batch run did not", "mint-shares {requestG}",
      after = Seq("G3"), skipIf = Some("vaultG"), binds = Seq(out("vaultG", 1))),
    tx("G5", "G batch", "Final claim before the pull: the shares burn and the bond unit comes back",
      "claim-shares {requestG}", after = Seq("G4"), check = Check.TokenGone("requestG")),
    tx("G6", "G batch", "Repay bond G", "repay {bondG}", after = Seq("G3")),
    tx("G7", "G batch", "Claim repaid bond G with the unit", "claim {orderG}", after = Seq("G5", "G6"),
      check = Check.TokenGone("orderG")),

    // ─── end: nothing of this wallet's is left open ───────────────────────────
    end("Z1", "No open orders left", "orders --mine", "No open orders."),
    end("Z2", "No bonds left", "bonds --mine", "No bonds."),
    end("Z3", "No lend offers left", "offers --mine", "No lend offers."),
    end("Z4", "No asks left", "asks --mine", "No asks."),
    end("Z5", "No share requests or vaults left", "shares --mine", "No share requests or vaults."),
    end("Z6", "Nothing left to claim", "claims", "Nothing to claim.")
  )

  /** What each step does and what it shows off, a sentence or two, printed under the step's title. */
  val About: Map[String, String] = Map(
    // basics
    "R1" -> ("Prints the pinned ZC Standard contracts, the network and the wallet this run acts as. Shows the " +
      "CLI building every contract from the pinned trees and talking to a mainnet node."),
    "R2" -> ("Compiles the first-generation production contracts behind --old. Shows the old contracts still " +
      "build next to the standard set."),
    "R3" -> "Lists first-generation orders on the node. Shows the --old commands still read the production boxes.",
    "R4" -> "Lists this wallet's first-generation bonds. Read only, like every R step.",
    "R5" -> "Lists first-generation bonds ready to claim. Read only, like every R step.",
    "S1" -> ("The node wallet mints a worthless test token and splits ERG into separate boxes for this wallet, in " +
      "one transaction. The token stands in for collateral the oracle can't price, and the boxes let independent " +
      "steps run side by side."),

    // D/E: an offer, a NAV ask, Dutch shares, liquidation at maturity, an auction
    "O1" -> ("Posts a lend offer: 0.6 ERG that any batcher may lend at 5% a year or better to oracle-rule bonds " +
      "collateralized at 150% or more. It also accepts Dutch auction shares (floor at least 100%, auction at most " +
      "100 blocks), so this track can use it three different ways."),
    "E1" -> ("A borrower asks for 0.1 ERG, repaying 0.12 at height M, against 0.25 ERG and 1,000 test tokens under " +
      "the oracle rule at 150%. The ERG alone covers about 208% of the repayment, so the O1 offer will fund it."),
    "D1" -> ("A plain order for 0.1 ERG, repaying 0.11 at height M, under the maturity liquidation rule. This wallet " +
      "fills it by hand, and it is liquidated at maturity together with bond E."),
    "C1" -> ("An order backed by only 0.1 ERG against a 0.12 ERG repayment, about 83%, well under its 150% oracle " +
      "ratio. It exists to be refused by an offer, then filled by hand and liquidated early."),
    "C1x" -> ("Tries to match the O1 offer with order C, which the CLI must refuse. Shows an offer's collateral floor " +
      "is checked before anything is built, so no batcher can fund a bond the lender wouldn't accept."),
    "E2" -> ("This wallet, as batcher, matches the O1 offer with order E: a new bond whose unit goes to the offer's " +
      "owner. Shows an offer funding a brand-new bond in one transaction, with the yield test, the collateral policy " +
      "reading the oracle, and the offer continuing with what is left."),
    "D2" -> ("This wallet fills order D itself, paying the principal and taking the bond's unit. Shows the basic " +
      "peer-to-peer fill: three bond units minted, one to the lender and two kept as the bond's reserve."),
    "D2x" -> ("Tries to liquidate bond D before its maturity, which the CLI must refuse. Shows a maturity-rule bond " +
      "is safe from liquidation until its maturity height."),
    "E3" -> "Lists the O1 offer after it funded bond E. Shows the offer's continuation with its remaining capital.",
    "E4" -> ("Lists bond E's unit on a NAV ask at the bond's accrued value less 1%. Shows an ask whose price follows " +
      "the bond's value block by block instead of a fixed number."),
    "E5" -> "Lists the NAV ask with its current price. Shows readers computing the accrued-value price as the chain does.",
    "E6" -> ("The O1 offer buys bond E's unit from the NAV ask; the unit returns to this wallet, which owns both. " +
      "Shows an offer buying an existing bond: the origin check that proves the bond genuine, and the ask's pricing " +
      "expression running on chain."),
    "E7" -> ("Puts bond E's unit into a Dutch auction mint request: if the bond is liquidated, its collateral is " +
      "auctioned from 300% down to 250% of the repayment over 20 blocks. The high percentages make sure the auction " +
      "really opens, since the vault will hold less ERG than that."),
    "E8" -> ("Mints the Dutch shares, with this wallet as the batcher keeping the mint fee. The owner gets 100 shares, " +
      "and the share vault locks the bond unit."),
    "E9" -> ("Lists 40% of the Dutch shares on a fixed-price ask for 0.04 ERG. Shows a share holding sold in part " +
      "on a plain sale order."),
    "E10" -> ("The O1 offer buys the Dutch shares from the ask. Shows an offer buying auction shares, checking their " +
      "auction terms against the floor and length it accepts."),
    "E11" -> ("Cancels what is left of the O1 offer, returning its capital. Shows the owner can withdraw an offer " +
      "at any time."),
    "DE1" -> ("Waits for height M, then liquidates bonds D and E in one transaction. Shows two bonds with different " +
      "rules (maturity, and oracle at maturity) settled together for one miner fee."),
    "D3" -> ("Claims liquidated bond D with its unit: the collateral goes to the unit's holder, and all three bond " +
      "units burn. Shows the lender's side of a liquidation."),
    "E12" -> ("Pulls bond E's liquidated collateral into its Dutch share vault; anyone can, for the pull fee. The vault " +
      "holds less ERG than the auction floor, so the auction of the test tokens opens."),
    "E13" -> "Lists the share vault with its auction open and the current price. Shows the falling price as readers see it.",
    "E14" -> ("Buys the auction's lot, the test tokens, by topping the vault's ERG up to the current target. Shows " +
      "anyone can buy a lot, and since the price only falls, a purchase priced now stays valid."),
    "E15" -> ("Claims with 30% of the Dutch shares, taking that share of the vault's ERG. Shows a partial claim paying " +
      "pro rata and leaving the vault open for the rest."),
    "E16" -> ("Claims with every remaining share, burning them and the vault's reserve. Shows the final claim closing " +
      "the vault completely."),

    // A: shares of a repaid bond
    "A1" -> ("An order for 0.1 ERG, repaying 0.11 in exactly 400 blocks, under the maturity rule. --exact-maturity " +
      "keeps it off the 21,600-block maturity grid, so the test doesn't wait a month."),
    "A1m" -> ("Lists order A from the mempool right after it is submitted, before it confirms. Shows the mempool " +
      "lookups that let later steps chain without waiting for blocks."),
    "A2" -> ("This wallet fills order A, paying 0.1 ERG and taking bond A's unit. That unit is split into shares " +
      "next."),
    "A2x" -> ("Tries to claim bond A before it is repaid or liquidated, which the CLI must refuse. Shows a unit claims " +
      "nothing until its bond settles."),
    "A3" -> ("Puts bond A's unit into a mint request for 100 shares with 6 decimals. Shows the zcs1 share format: " +
      "1.0 shares is 1% of the bond, and the name and description are built from the bond."),
    "A4" -> "Lists the share request. Shows it waiting for any batcher to mint it.",
    "A5" -> ("Mints bond A's shares, with this wallet as the batcher keeping the mint fee. The owner gets 100 shares, " +
      "and the vault locks the unit until the bond settles."),
    "A6" -> ("Lists 40% of the shares on a fixed-price ask for 0.04 ERG. The ask's minimum fill defaults to the " +
      "smallest lot that still claims a raw unit of the repayment."),
    "A7" -> ("Lists the ask with its price as a percentage of face value, about 91% here. Shows share prices quoted " +
      "so they compare across bonds of any size."),
    "A8" -> ("Buys 10% of the shares back from the ask, a partial fill. Shows the ask paying its seller and carrying " +
      "on with the rest."),
    "A9" -> "Cancels the rest of the ask, returning the unsold shares. Shows the seller can withdraw an ask at any time.",
    "A10" -> ("The borrower repays bond A: the collateral comes back, and the repayment waits in the bond vault for " +
      "the unit, which the share vault holds. The normal, happy ending of a bond."),
    "A11" -> ("Pulls the repayment into the share vault; anyone can, for the pull fee. All three bond units burn, and " +
      "the vault now holds ERG for the shareholders."),
    "A12" -> "Claims with 25% of the shares, taking that share of the repayment. Shows a pro-rata claim after a repayment.",
    "A13" -> ("Claims with every remaining share, closing the vault. It waits for A9, so every unsold share is back in " +
      "this wallet."),

    // B: recollateralize, repay from collateral
    "B1" -> ("An on-close order for 0.1 ERG against 0.3 ERG and 1,000 test tokens: its 90-block term starts when it " +
      "is filled. Shows an order whose maturity is set by the fill rather than in advance."),
    "B2" -> ("This wallet fills order B, starting its 90-block term. It also starts the 60-block wait before the bond " +
      "can take more collateral."),
    "B2x" -> ("Tries to add collateral to bond B within 60 blocks of its fill, which the CLI must refuse. Shows the " +
      "wait between moves that stops a borrower dodging a liquidation by moving the bond again and again."),
    "B3" -> ("Adds 0.02 ERG and 500 more test tokens to bond B once the 60 blocks have passed. The check confirms " +
      "the bond then holds 1,500 tokens."),
    "B4" -> ("Repays bond B out of the ERG already locked in it as collateral, so the borrower brings nothing from " +
      "outside. The rest of the collateral, tokens included, comes back."),
    "B5" -> "Claims repaid bond B with its unit, taking the 0.11 ERG repayment. All three bond units burn.",

    // C: early liquidation by the oracle rule
    "C2" -> ("This wallet fills order C anyway, starting a bond that is already below its 150% ratio. It is what C6 " +
      "liquidates early."),
    "C3" -> ("Lists bond C as liquidatable. Shows readers applying the oracle rule to find bonds anyone may " +
      "liquidate now."),
    "C4" -> "Puts bond C's unit into a share mint request. It is never minted: C5 takes it back.",
    "C5" -> ("Cancels the share request, returning the unit. Shows the owner can take a unit back out of a request " +
      "nobody has minted yet."),
    "C6" -> ("One transaction liquidates bond C by the oracle rule and refunds expired order K2. Shows unrelated work " +
      "packed together for one miner fee, as a batcher would."),
    "C7" -> "Lists bond C as claimable after its liquidation.",
    "C8" -> ("Claims liquidated bond C with its unit: the collateral comes to the unit's holder and all three bond " +
      "units burn."),

    // K: expiries and cancels
    "K1" -> "An order that expires in 10 blocks, set up to be refunded.",
    "K1x" -> ("Tries to refund order K1 before it expires, which the CLI must refuse. Shows that until its expiry, " +
      "only the borrower can take an order back."),
    "K1b" -> ("Runs the batch scan once K1 has expired and finds its refund. Shows a batcher discovering work that " +
      "pays it."),
    "K1r" -> ("Refunds expired order K1 to its borrower. Anyone may do this from the expiry height and keep the expiry " +
      "fee; here this wallet is both."),
    "K2" -> "Another order expiring in 10 blocks. Its refund rides along with bond C's liquidation in C6.",
    "K3" -> ("An order on the SigUSD market: borrow 1 SigUSD against 0.05 ERG. Nobody fills it; it shows the SigUSD " +
      "market's contracts taking an order."),
    "K4" -> "Lists the SigUSD order, with amounts in SigUSD.",
    "K5" -> "Cancels the SigUSD order, returning its collateral. Shows the borrower can cancel at any time.",
    "K6" -> "A small lend offer that expires in 10 blocks, set up to be refunded.",
    "K7" -> ("Refunds the expired offer to its owner. Anyone may, keeping the batcher fee; it is how a contract-owned " +
      "offer gets its funds back without signing."),
    "K8" -> "An ask selling 100 test tokens for 0.01 ERG that expires in 10 blocks, set up to be refunded.",
    "K9" -> "Refunds the expired ask to its seller. Anyone may, keeping the ask's refund fee.",
    "K10" -> "An ask selling 100 test tokens for 1 SigUSD. Shows the sale order on the SigUSD market.",
    "K11" -> "Cancels the SigUSD ask, returning the tokens.",

    // G: a batch run
    "G1" -> ("An on-close order for 0.1 ERG, repaying 0.12 after 60 blocks, under the oracle rule. It is the order " +
      "the batch run fills."),
    "O3" -> ("A lend offer of 0.3 ERG that has every bond unit it buys minted into shares for its owner, paying " +
      "batchers 0.006 ERG. Shows an offer that ends in shares rather than a unit."),
    "G2" -> ("Runs the batch scan and waits until its only work is matching order G with offer O3. That way the batch " +
      "run touches nobody else's boxes."),
    "G3" -> ("A batch run fills order G from the offer, delivering the unit into a share mint request, then mints the " +
      "shares in a second, chained transaction. Shows a batcher doing the whole job with no input from the lender."),
    "G4" -> "Mints the delivered shares, in case the batch run stopped before it did. Skipped when it already did.",
    "G5" -> ("Claims with every share before the bond settles: the shares and the vault's reserve burn, and the bond " +
      "unit comes back. Shows whoever holds every share can turn them back into the unit until the pull."),
    "G6" -> "The borrower repays bond G.",
    "G7" -> "Claims repaid bond G with the unit that came back in G5. All three bond units burn.",

    // end
    "Z1" -> "Checks this wallet has no open orders left.",
    "Z2" -> "Checks this wallet has no bonds left.",
    "Z3" -> "Checks this wallet has no lend offers left.",
    "Z4" -> "Checks this wallet has no asks left.",
    "Z5" -> "Checks this wallet has no share requests or vaults left.",
    "Z6" -> "Checks nothing is left for this wallet to claim."
  )
}
