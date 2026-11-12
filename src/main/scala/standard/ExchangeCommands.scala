package ksingh.sigmabonds
package standard

import ksingh.sigmabonds.app.SigmaFiCli._
import ksingh.sigmabonds.app._
import org.ergoplatform.sdk.ErgoId

import scala.util.Try

/** The CLI's commands for shares, asks, lend offers and the batch scan. */
object ExchangeCommands {

  val DefaultShareFee = "0.005"
  val DefaultBatcherFee = "0.005"
  val DefaultRefundFee = "0.002"
  val DefaultOfferFees = "0.5"
  val DefaultOfferOracleAge = 30L
  val DefaultMaxBlocks = 262800L

  val Commands: Set[String] = Set("shares", "fractionalize", "mint-shares", "cancel-share-request", "pull", "buy-lot",
    "claim-shares", "asks", "sell", "buy", "cancel-ask", "refund-ask", "offers", "create-offer", "cancel-offer",
    "refund-offer", "match-offer", "batch")

  /** `run` executes a follow-up command line, as `batch --run` does with each piece of work. */
  def dispatch(app: StandardApp, a: Args, run: String => Boolean): Unit = a.command.get match {
    case "shares" => listShares(app, a.flag("mine"))
    case "fractionalize" => app.submit(app.fractionalize(one(a, "bond asset id"), shareTerms(a, None)))
    case "mint-shares" => app.submit(app.mintShares(one(a, "request box id")))
    case "cancel-share-request" => app.submit(app.cancelShareRequest(one(a, "request box id")))
    case "pull" => app.submit(app.pull(one(a, "share vault box id")))
    case "buy-lot" => app.submit(app.buyLot(one(a, "Dutch vault box id")))
    case "claim-shares" => app.submit(app.claimShares(one(a, "share token id"), a.opt("amount").map(parseLong(_, "--amount"))))

    case "asks" => listAsks(app, a.flag("mine"))
    case "sell" => app.submit(sell(app, a))
    case "buy" => app.submit(app.buy(one(a, "ask box id"), a.opt("amount").map(parseLong(_, "--amount"))))
    case "cancel-ask" => app.submit(app.cancelAsk(one(a, "ask box id")))
    case "refund-ask" => app.submit(app.refundAsk(one(a, "ask box id")))

    case "offers" => listOffers(app, a.flag("mine"))
    case "create-offer" => app.submit(app.createOffer(offerSpec(app, a)))
    case "cancel-offer" => app.submit(app.cancelOffer(one(a, "offer box id")))
    case "refund-offer" => app.submit(app.refundOffer(one(a, "offer box id")))
    case "match-offer" =>
      val offer = one(a, "offer box id")
      (a.opt("order"), a.opt("ask")) match {
        case (Some(order), None) => app.submit(app.matchOrder(offer, order))
        case (None, Some(ask)) => app.submit(app.matchAsk(offer, ask, a.opt("amount").map(parseLong(_, "--amount"))))
        case _ => throw CliError("match-offer needs exactly one of --order <order box id> or --ask <ask box id>")
      }

    case "batch" => batch(app, a, run)
    case other => throw CliError(s"unknown command $other; run help for the list")
  }

  // ═══ ARGUMENTS ════════════════════════════════════════════════════════════

  private def percent(a: Args, key: String): Option[Long] = a.opt(key).map { s =>
    val bps = Try(BigDecimal(s) * 100).getOrElse(throw CliError(s"--$key: $s is not a percentage"))
    if (!bps.isWhole || bps < 0) throw CliError(s"--$key: $s must not be negative, with at most 2 decimals")
    Try(bps.toLongExact).getOrElse(throw CliError(s"--$key: $s is too large"))
  }

  private def needPercent(a: Args, key: String): Long = percent(a, key).getOrElse(throw CliError(s"missing --$key <percent>"))

  /** Share terms from --shares (or the given count), the fees, the EIP-4 fields and any --dutch auction. */
  private def shareTerms(a: Args, count: Option[String]): ShareTerms = {
    val shares = parseLong(count.orElse(a.opt("shares")).getOrElse(throw CliError("missing --shares <count>")), "--shares")
    val auction = if (!a.flag("dutch")) {
      if (Seq("auction-opening", "auction-floor", "auction-length").exists(a.opt(_).isDefined))
        throw CliError("the auction options need --dutch")
      None
    } else Some(AuctionTerms(needPercent(a, "auction-opening"), needPercent(a, "auction-floor"),
      parseLong(a.need("auction-length"), "--auction-length")))
    val decimals = a.opt("decimals").getOrElse("0")
    if (Try(decimals.toInt).filter(d => d >= 0 && d <= 18).isFailure) throw CliError("--decimals takes 0 to 18")
    ShareTerms(shares, parseErg(a.opt("pull-fee").getOrElse(DefaultShareFee)), parseErg(a.opt("mint-fee").getOrElse(DefaultShareFee)),
      a.opt("share-name").getOrElse("SigmaFi bond shares"), a.opt("share-description").getOrElse("Shares of one SigmaFi ZC Standard bond"),
      decimals, auction)
  }

  private def market(a: Args): Market = a.opt("market").map(n => Market.named(n).getOrElse(
    throw CliError(s"unknown market $n; known: ${Market.all.mkString(", ")}"))).getOrElse(Market.Erg)

  private def expiry(app: StandardApp, a: Args): Long =
    app.height.toLong + a.opt("expires-in").map(parseLong(_, "--expires-in")).getOrElse(StandardCommands.DefaultExpiry)

  private def sell(app: StandardApp, a: Args): Prepared = {
    val m = market(a)
    val price = if (a.flag("nav")) NavPrice(a.opt("margin").map(signedBps).getOrElse(0L),
      a.opt("price").map(Units.parse(_, m, "--price")))
    else FixedPrice(Units.parse(a.opt("price").getOrElse(throw CliError("give --price <total> or --nav")), m, "--price"))
    app.sell(one(a, "token id"), parseLong(a.need("amount"), "--amount"), m, price,
      a.opt("min-fill").map(parseLong(_, "--min-fill")).getOrElse(1L), expiry(app, a),
      parseErg(a.opt("refund-fee").getOrElse(DefaultRefundFee)))
  }

  /** A signed margin in percent, e.g. -2 or 1.5, as basis points. */
  private def signedBps(s: String): Long = {
    val bps = Try(BigDecimal(s) * 100).getOrElse(throw CliError(s"--margin: $s is not a percentage"))
    if (!bps.isWhole) throw CliError(s"--margin: $s has more than 2 decimals")
    Try(bps.toLongExact).getOrElse(throw CliError(s"--margin: $s is too large"))
  }

  private def offerSpec(app: StandardApp, a: Args): OfferSpec = {
    val m = market(a)
    val delivery = a.opt("deliver-shares").map(n => OfferDelivery(shareTerms(a, Some(n))))
    if (delivery.isEmpty && a.flag("dutch")) throw CliError("--dutch on an offer needs --deliver-shares <count>")
    OfferSpec(m,
      capital = Units.parse(a.need("capital"), m, "--capital"),
      fees = if (m.token.isDefined) parseErg(a.opt("fees").getOrElse(DefaultOfferFees)) else 0L,
      yieldBps = needPercent(a, "yield"),
      minBlocks = a.opt("min-blocks").map(parseLong(_, "--min-blocks")).getOrElse(1L),
      maxBlocks = a.opt("max-blocks").map(parseLong(_, "--max-blocks")).getOrElse(DefaultMaxBlocks),
      batcherFee = parseErg(a.opt("batcher-fee").getOrElse(DefaultBatcherFee)),
      feeReserve = reserve(a, "fee-reserve"),
      floorBps = needPercent(a, "collateral-floor"),
      maxAge = a.opt("oracle-age").map(parseLong(_, "--oracle-age")).getOrElse(DefaultOfferOracleAge),
      expiry = expiry(app, a),
      minPrice = a.opt("min-price").map(Units.parse(_, m, "--min-price")).getOrElse(1L),
      auctionFloorBps = percent(a, "min-auction-floor").getOrElse(0L),
      auctionLength = a.opt("max-auction-length").map(parseLong(_, "--max-auction-length")).getOrElse(0L),
      delivery = delivery)
  }

  // ═══ LISTINGS ═════════════════════════════════════════════════════════════

  private def listShares(app: StandardApp, mine: Boolean): Unit = {
    val requests = app.shareRequests().filter(r => !mine || app.isOwnHash(r.ownerHash))
    val vaults = app.shareVaults().filter(v => !mine || (app.walletAvailable && app.walletHolds(v.shareId)))
    if (requests.isEmpty && vaults.isEmpty) println("No share requests or vaults.")
    requests.foreach { r =>
      println(s"Request ${r.box.id}  ${r.shares} shares of bond ${r.bondAsset}" + r.dutch.fold("")(m => s" (Dutch, $m)"))
      println(s"  mint fee ${Fmt.erg(r.mintFee)}, pull fee ${Fmt.erg(r.pullFee)}: anyone may `mint-shares ${r.box.id}`")
      if (app.isOwnHash(r.ownerHash)) println("  (your request)")
    }
    vaults.foreach { v =>
      println(s"Share ${v.shareId}  vault ${v.box.id}  (${if (v.dutch) "Dutch auction" else "fractional"})")
      println(s"  bond ${v.bondAsset}: ${v.outstanding} of ${v.n} shares outstanding")
      val status =
        if (v.holding) "holds the bond unit; pull its settlement once the bond is repaid or liquidated"
        else if (v.auctionOpen) {
          val m = Market.of(v.currencyToken)
          val end = v.auction(4) + v.auction(3)
          val target = app.lotTarget(v, app.height.toLong)
          s"AUCTION OPEN until height $end: the lot costs ${m.fold(target.toString)(Units.amount(target - app.currencyHeld(v), _))} " +
            s"now (`buy-lot ${v.box.id}`); claims wait for its end"
        } else v.state.map {
          case 0 => "repaid"
          case 1 if !v.dutch => "liquidated: paid in kind"
          case 2 => "liquidated: the lot sold, or the floor was already held"
          case 3 => "liquidated: unsold, paid in kind"
          case s => s"state $s"
        }.getOrElse("")
      println(s"  $status")
      println(s"  holds: ${Fmt.contents(v.box.value, v.assets)}")
      if (app.walletAvailable && app.walletHolds(v.shareId)) println(s"  (you hold shares: `claim-shares ${v.shareId}`)")
    }
  }

  private def listAsks(app: StandardApp, mine: Boolean): Unit = {
    val asks = app.asks().filter(a => !mine || app.isOwnHash(a.sellerHash))
    if (asks.isEmpty) println("No asks.")
    asks.foreach { a =>
      println(s"Ask ${a.box.id}  ${a.token.amount} of ${a.token.id}  (${if (a.nav) "NAV" else "fixed price"}, paid in ${a.market})")
      val price = Try(app.askPrice(a, a.token.amount)._1).toOption
      println("  price:  " + price.fold("not fillable now (its bond is not live)")(p => s"${Units.amount(p, a.market)} for all") +
        (if (a.nav) s"; NAV ${if (a.margin >= 0) "+" else "-"}${Units.percent(a.margin.abs)}, ${a.wholeUnits} unit(s) per bond" else ""))
      println(s"  minimum fill ${a.minFill}; expires at height ${a.expiry}" +
        (if (app.height.toLong + 1L >= a.expiry) " (EXPIRED: anyone may refund-ask it)" else ""))
      app.bondOf(a.token.id).foreach { case (asset, n) =>
        println(s"  a claim on bond $asset" + (if (n > 1L) s" ($n shares per bond)" else ""))
      }
      if (app.isOwnHash(a.sellerHash)) println("  (your ask)")
    }
  }

  private def listOffers(app: StandardApp, mine: Boolean): Unit = {
    val offers = app.offers().filter(o => !mine || app.isOwnHash(o.ownerHash))
    if (offers.isEmpty) println("No lend offers.")
    offers.foreach { o =>
      val m = o.market
      println(s"Offer ${o.box.id}  (${m})")
      println(s"  capital ${Units.amount(o.capital, m)} at ${Units.percent(o.yieldBps)} a year or better; " +
        s"bonds maturing in ${o.terms(1)} to ${o.terms(2)} blocks; minimum price ${Units.amount(o.minPrice, m)}")
      println(s"  oracle-rule bonds at a ratio of at least ${Units.percent(o.floorBps)}, oracle at most ${o.maxAge} blocks old")
      println(s"  batcher fee ${Fmt.erg(o.batcherFee)}; new-bond fee reserve ${Fmt.erg(o.feeReserve)}" +
        (if (o.terms(10) > 0L) s"; Dutch shares with a floor of at least ${Units.percent(o.terms(9))} and at most ${o.terms(10)} blocks" else ""))
      if (o.delivers) println(s"  delivers bond units into ${o.fractional.headOption.getOrElse(0L)} shares" +
        (if (o.fractional.size > 3) " with a Dutch auction" else ""))
      println(s"  expires at height ${o.expiry}" + (if (app.height.toLong + 1L >= o.expiry) " (EXPIRED: anyone may refund-offer it)" else ""))
      if (app.isOwnHash(o.ownerHash)) println("  (your offer)")
    }
  }

  /** List the work, or with --run do each piece, skipping any that would spend a box an earlier one spent. */
  private def batch(app: StandardApp, a: Args, run: String => Boolean): Unit = {
    val work = app.batchable()
    if (work.isEmpty) println("Nothing to batch.")
    work.foreach(w => println(f"${Fmt.erg(w.reward)}%-14s ${w.summary}%n               ${w.command}"))
    if (a.flag("run") && work.nonEmpty) {
      val spent = scala.collection.mutable.Set.empty[String]
      val done = work.filter { w =>
        if (w.spends.exists(spent.contains)) false
        else {
          println(s"\n> ${w.command}")
          val ok = run(w.command)
          if (ok) spent ++= w.spends
          ok
        }
      }
      println(s"\n${done.size} of ${work.size} built" + (if (a.flag("send")) " and submitted." else "; run with --send to submit them."))
    }
  }
}
