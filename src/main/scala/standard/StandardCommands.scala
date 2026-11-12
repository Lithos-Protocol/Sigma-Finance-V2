package ksingh.sigmabonds
package standard

import ksingh.sigmabonds.app.SigmaFiCli._
import ksingh.sigmabonds.app._
import scorex.util.encode.Base16

import scala.util.Try

/** The CLI's default commands: the ZC Standard contracts, built from the pinned trees. */
object StandardCommands {

  /** An order expires this many blocks after it is created unless --expires-in says otherwise: a week. */
  val DefaultExpiry = 5040L
  val DefaultExpiryFee = "0.005"
  val DefaultOracleAge = 60L

  /** `run` executes a follow-up command line in the same session (used by `batch --run`). */
  def dispatch(app: StandardApp, node: NodeApi, a: Args, hasApiKey: Boolean,
               run: String => Boolean = _ => false): Unit = a.command.get match {
    case c if ExchangeCommands.Commands.contains(c) => ExchangeCommands.dispatch(app, a, run)

    case "info" => info(app, node, hasApiKey)
    case "orders" => listOrders(app, node, a.flag("mine"))
    case "bonds" => listBonds(app, node, a.flag("mine"), a.flag("liquidatable"))
    case "claims" => listClaims(app, node)

    case "create-order" => app.submit(app.createOrder(loan(a, app)))
    case "cancel-order" => app.submit(app.cancelOrder(one(a, "order box id")))
    case "refund-order" => app.submit(app.refundOrder(one(a, "order box id")))
    case "repay" => app.submit(app.repay(one(a, "bond box id"), a.flag("from-collateral")))
    case "recollateralize" =>
      app.submit(app.recollateralize(one(a, "bond box id"), a.opt("add-erg").map(parseErg).getOrElse(0L),
        a.opts("add-token").map(parseToken)))

    case "fill-order" => app.submit(app.fillOrder(one(a, "order box id"), reserve(a, "fee-reserve")))
    case "claim" => app.submit(app.claim(one(a, "bond asset id")))

    case "liquidate" => app.submit(app.liquidate(a.positional))

    case other => throw CliError(s"unknown command $other; run help for the list")
  }

  // ═══ ARGUMENTS ════════════════════════════════════════════════════════════

  private def loan(a: Args, app: StandardApp): StdLoan = {
    if (a.opt("principal-token").isDefined)
      throw CliError("a ZC Standard loan is lent in its market's asset: use --market SigUSD --principal <amount>")
    val market = a.opt("market").map(n => Market.named(n).getOrElse(
      throw CliError(s"unknown market $n; known: ${Market.all.mkString(", ")}"))).getOrElse(Market.Erg)
    // zcs1: a fixed maturity snaps to the 21,600-block grid unless --exact-maturity keeps it.
    val asked = SigmaFiCli.schedule(a, app.height)
    val schedule = asked match {
      case FixedMaturity(h) if !a.flag("exact-maturity") => FixedMaturity(StandardProtocol.snapMaturity(h, app.height.toLong))
      case other =>
        if (a.flag("exact-maturity") && other.isInstanceOf[OnClose]) throw CliError("--exact-maturity needs --maturity or --term")
        other
    }
    val rule = a.opt("liquidation").getOrElse("maturity").toLowerCase match {
      case "maturity" =>
        if (a.opt("ratio").isDefined || a.opt("oracle-age").isDefined)
          throw CliError("--ratio and --oracle-age need --liquidation oracle")
        MaturityChoice
      case "oracle" =>
        val ratio = a.opt("ratio").map(percentToBps).getOrElse(throw CliError("--liquidation oracle needs --ratio <percent>"))
        OracleChoice(ratio, a.opt("oracle-age").map(parseLong(_, "--oracle-age")).getOrElse(DefaultOracleAge))
      case other => throw CliError(s"unknown liquidation $other; use maturity or oracle")
    }
    val expiry = a.opt("expires-in").map(n => app.height.toLong + parseLong(n, "--expires-in")).getOrElse {
      val week = app.height.toLong + DefaultExpiry
      // By default an order expires before a fixed maturity: after it, nobody could fill it.
      schedule match {
        case FixedMaturity(h) => math.max(app.height.toLong + 2L, math.min(week, h - 2L))
        case OnClose(_) => week
      }
    }
    StdLoan(market,
      principal = Units.parse(a.need("principal"), market, "--principal"),
      repayment = Units.parse(a.need("repayment"), market, "--repayment"),
      collateralErg = parseErg(a.need("collateral-erg")),
      collateralTokens = a.opts("collateral-token").map(parseToken),
      schedule = schedule, rule = rule, expiry = expiry,
      expiryFee = parseErg(a.opt("expiry-fee").getOrElse(DefaultExpiryFee)),
      requestedMaturity = asked match {
        case FixedMaturity(h) => Some(h)
        case OnClose(_) => None
      })
  }

  /** "150" or "150.5" percent as basis points. */
  private def percentToBps(s: String): Long = {
    val bps = Try(BigDecimal(s) * 100).getOrElse(throw CliError(s"--ratio: $s is not a percentage"))
    if (!bps.isWhole || bps <= 0) throw CliError(s"--ratio: $s must be positive, with at most 2 decimals")
    Try(bps.toLongExact).getOrElse(throw CliError(s"--ratio: $s is too large"))
  }

  // ═══ LISTINGS ═════════════════════════════════════════════════════════════

  private def info(app: StandardApp, node: NodeApi, hasApiKey: Boolean): Unit = {
    val c = app.protocol.contracts
    val network = app.networkType
    println(s"ZC Standard, from pinned trees. Network: $network; height ${app.height}")
    indexStatus(node).foreach(println)
    def show(name: String, contract: work.lithos.mutations.Contract): Unit = {
      println(f"$name%-22s ${contract.address(network)}")
      println(s"  hash: ${contract.hashedPropBytesHex}")
    }
    show("BondVault", c.vault)
    Market.all.foreach { m =>
      show(s"Bond ($m)", c.market(m).bond)
      show(s"Order ($m)", c.market(m).order)
    }
    println(s"Bond template hash:  ${Base16.encode(app.protocol.bondTemplateHash)}")
    println(s"Order template hash: ${Base16.encode(app.protocol.orderTemplateHash)}")
    app.protocol.rules.foreach(r => println(s"Liquidation ${r.name}: ${Base16.encode(r.expression.hashedValueBytes)}"))
    println("Every pinned tree, with addresses: docs/ZC_STANDARD_REFERENCE.md")
    if (hasApiKey) println(s"Wallet address: ${app.myAddress}")
  }

  private def listOrders(app: StandardApp, node: NodeApi, mine: Boolean): Unit = {
    indexStatus(node).foreach(System.err.println)
    val orders = app.orders().filter(o => !mine || app.isWalletHash(o.borrowerHash))
    if (orders.isEmpty) println("No open orders.")
    orders.foreach { o =>
      val m = o.market
      println(s"Order ${o.box.id}  ($m, ${if (o.onClose) "on-close" else "fixed maturity"})")
      println(s"  borrow:      ${Units.amount(o.principal, m)}; repay ${Units.amount(o.repayment, m)}")
      println("  maturity:    " + (if (o.onClose) s"${o.terms(3)} blocks after the fill"
        else s"height ${o.terms(3)} (${o.terms(3) - app.height} blocks from now)"))
      println(s"  collateral:  ${Fmt.contents(o.collateralErg, o.collateral)}")
      println(s"  liquidation: ${app.protocol.ruleOf(o.liqHash).fold(app.protocol.ruleName(o.liqHash))(app.describeRule(_, o.payload))}")
      println(s"  expiry:      height ${o.expiry}; fee ${Fmt.erg(o.expiryFee)}" +
        (if (app.height.toLong + 1L >= o.expiry) " (EXPIRED: anyone may refund-order it for the fee)" else ""))
      if (app.isOwnHash(o.borrowerHash)) println("  (your order)")
    }
  }

  private def listBonds(app: StandardApp, node: NodeApi, mine: Boolean, liquidatable: Boolean): Unit = {
    indexStatus(node).foreach(System.err.println)
    val oracle = Try(app.oracle()).toOption.flatten
    val next = app.height.toLong + 1L
    def oracleBelow(b: StdBond): Boolean = (app.protocol.ruleOf(b.liqHash), oracle) match {
      case (Some(OracleRatio(_, _)), Some(o)) if b.payload.size >= 2 =>
        next - o.created <= b.payload(1) && app.protocol.cover(b, o.price).exists { case (c, d) => c * 10000 < d * b.payload(0) }
      case _ => false
    }
    def canLiquidate(b: StdBond): Boolean = app.protocol.ruleOf(b.liqHash).isDefined && (next >= b.maturity || oracleBelow(b))
    val bonds = app.bonds()
      .filter(b => !liquidatable || canLiquidate(b))
      .filter(b => !mine || app.isWalletHash(b.borrowerHash) || app.walletHolds(b.asset))
    if (bonds.isEmpty) println("No bonds.")
    bonds.foreach { b =>
      val m = b.market
      println(s"Bond ${b.box.id}  bond asset ${b.asset}  ($m)")
      println(s"  repay:       ${Units.amount(b.repayment, m)} " +
        (if (next >= b.maturity) s"(matured at ${b.maturity}: liquidatable)"
        else s"before height ${b.maturity} (${b.maturity - app.height} blocks left)"))
      println(s"  principal:   ${Units.amount(b.principal, m)}; started at height ${b.start}")
      println(s"  collateral:  ${Fmt.contents(b.collateralErg, b.collateral)}; lender fee reserve ${Fmt.erg(b.feeReserve)}")
      val rule = app.protocol.ruleOf(b.liqHash)
      println(s"  liquidation: ${rule.fold(app.protocol.ruleName(b.liqHash))(app.describeRule(_, b.payload))}")
      (rule, oracle) match {
        case (Some(OracleRatio(_, _)), Some(o)) =>
          println(s"  ratio now:   ${app.protocol.ratioBps(b, o.price).fold("?")(Units.percent)} at the oracle price from " +
            s"height ${o.created}" + (if (oracleBelow(b)) " (BELOW its ratio: liquidatable)" else ""))
        case _ =>
      }
      if (next < b.maturity)
        println("  recollateralize: " + (if (next >= b.recollateralizableFrom) "open now"
          else s"from height ${b.recollateralizableFrom}"))
      if (!app.originVerified(b)) println("  WARNING: its asset was not minted by a standard order: this box proves nothing")
      else ExchangeCommands.metadataLine(app.bondMetadataIssues(b.asset))
      if (app.isOwnHash(b.borrowerHash)) println("  (you are its borrower)")
      if (app.walletAvailable && app.walletHolds(b.asset)) println("  (you hold its bond asset)")
    }
  }

  private def listClaims(app: StandardApp, node: NodeApi): Unit = {
    indexStatus(node).foreach(System.err.println)
    val claimable = app.settlements().filter(s => app.walletHolds(s.asset))
    if (claimable.isEmpty) println("Nothing to claim.")
    claimable.foreach { s =>
      println(s"Bond asset ${s.asset}: ${Fmt.contents(s.box.value, s.proceeds)} " +
        s"(${if (s.liquidated) "liquidated" else "repaid"}). Claim with: claim ${s.asset}")
    }
  }
}
