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
  /** zcs1 client defaults for a Selective or Dutch vault's auction windows, in blocks. Its prices have none. */
  val DefaultAuctionLength = 720L
  val DefaultAuctionExpiry = 2160L
  /** zcs1 client defaults for a lot auction: a day's fall, then three days at the floor. */
  val DefaultLotFall = 720L
  val DefaultLotHold = 2160L

  val Commands: Set[String] = Set("shares", "fractionalize", "mint-shares", "cancel-share-request", "pull", "buy-lot",
    "claim-shares", "asks", "sell", "buy", "cancel-ask", "refund-ask", "offers", "create-offer", "cancel-offer",
    "refund-offer", "match-offer", "lots", "sell-lot", "take-lot", "cancel-lot", "refund-lot", "pack", "batch",
    "wraps", "wrap", "mint-wrap", "cancel-wrap", "unwrap", "refresh")

  /** zcs1 client default for a wrap's mint fee. */
  val DefaultWrapFee = "0.005"

  /** `run` executes a follow-up command line, as `batch --run` does with each piece of work. */
  def dispatch(app: StandardApp, a: Args, run: String => Boolean): Unit = a.command.get match {
    case "shares" => listShares(app, a.flag("mine"))
    case "fractionalize" => app.submit(app.fractionalize(one(a, "bond asset id"), shareTerms(a)))
    case "mint-shares" => app.submit(app.mintShares(one(a, "request box id")))
    case "cancel-share-request" => app.submit(app.cancelShareRequest(one(a, "request box id")))
    case "pull" => app.submit(app.pull(one(a, "share vault box id")))
    case "buy-lot" => app.submit(app.buyLot(one(a, "Dutch vault box id")))
    case "claim-shares" => app.submit(app.claimShares(one(a, "share token id"), a.opt("amount").map(parseLong(_, "--amount"))))

    case "asks" => listAsks(app, a.flag("mine"))
    case "sell" => app.submit(sell(app, a))
    case "buy" => app.submit(app.buy(one(a, "ask box id"), a.opt("amount").map(parseLong(_, "--amount")),
      a.flag("allow-liquidatable")))
    case "cancel-ask" => app.submit(app.cancelAsk(one(a, "ask box id")))
    case "refund-ask" => app.submit(app.refundAsk(one(a, "ask box id")))

    case "offers" => listOffers(app, a.flag("mine"))
    case "create-offer" => app.submit(app.createOffer(offerSpec(app, a)))
    case "cancel-offer" => app.submit(app.cancelOffer(one(a, "offer box id")))
    case "refund-offer" => app.submit(app.refundOffer(one(a, "offer box id")))
    case "match-offer" =>
      val offer = one(a, "offer box id")
      (a.opt("order"), a.opt("ask")) match {
        case (Some(order), None) => app.submit(app.matchOrder(offer, order, a.opt("maturity").map(parseLong(_, "--maturity"))))
        case (None, Some(ask)) => app.submit(app.matchAsk(offer, ask, a.opt("amount").map(parseLong(_, "--amount"))))
        case _ => throw CliError("match-offer needs exactly one of --order <order box id> or --ask <ask box id>")
      }

    case "lots" => listLots(app, a.flag("mine"))
    case "sell-lot" => app.submit(sellLot(app, a))
    case "take-lot" => app.submit(app.takeLot(one(a, "lot box id")))
    case "cancel-lot" => app.submit(app.cancelLot(one(a, "lot box id")))
    case "refund-lot" => app.submit(app.refundLot(one(a, "lot box id")))

    case "wraps" => listWraps(app, a.flag("mine"))
    case "wrap" => app.submit(app.wrap(one(a, "token id"), a.opt("amount").map(parseLong(_, "--amount")),
      parseErg(a.opt("mint-fee").getOrElse(DefaultWrapFee)),
      a.opt("refresh-reserve").map(parseErg).getOrElse(StandardProtocol.DefaultRefreshReserve)))
    case "mint-wrap" => app.submit(app.mintWrap(one(a, "wrap request box id")))
    case "cancel-wrap" => app.submit(app.cancelWrap(one(a, "wrap request box id")))
    case "unwrap" => app.submit(app.unwrap(one(a, "receipt token id")))
    case "refresh" =>
      if (a.positional.isEmpty) throw CliError("refresh takes the box ids of bonds, bond vaults, share vaults or one wrap vault")
      app.submit(app.refresh(a.positional))

    case "pack" =>
      if (a.positional.isEmpty)
        throw CliError("pack takes the box ids of expired orders, offers, asks and lots, of liquidatable bonds, and of " +
          "bonds and vaults old enough to refresh")
      app.submit(app.pack(a.positional))
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

  /**
   * Share terms: --shares (default zcs1's 100,000,000), the fees, the vault (--vault selective, the default, or
   * dutch; --dutch is short for the latter) with its auction terms, and the EIP-4 fields, which default to zcs1's
   * template; giving one overrides it, and the command then says the shares are nonstandard. The CLI makes only
   * Selective and Dutch vaults: a plain fractional vault pays a token too thin to split to its last claimant.
   */
  private def shareTerms(a: Args): ShareTerms = {
    val threshold = (a.opt("vault").map(_.toLowerCase), a.flag("dutch")) match {
      case (Some("dutch"), _) | (None, true) => StandardProtocol.DutchThreshold
      case (Some("selective") | None, false) => StandardProtocol.SelectiveThreshold
      case (Some("selective"), true) => throw CliError("--dutch means --vault dutch; choose one")
      case (Some(other), _) => throw CliError(s"--vault takes selective or dutch, not $other (lend offers buy neither " +
        "plain fractional shares nor any other)")
    }
    val length = a.opt("auction-length").map(parseLong(_, "--auction-length")).getOrElse(DefaultAuctionLength)
    // zcs1: a client never defaults a price, so the auction's opening and floor are the user's own.
    def price(key: String): Long = percent(a, key).getOrElse(throw CliError(s"missing --$key <percent of the repayment>: " +
      "a Selective or Dutch vault auctions a liquidated bond's collateral, and its prices are yours to choose"))
    val auction = Some(AuctionTerms(
      price("auction-opening"),
      price("auction-floor"),
      length,
      a.opt("auction-expiry").map(parseLong(_, "--auction-expiry")).getOrElse(math.max(DefaultAuctionExpiry, length)),
      threshold))
    val decimals = a.opt("decimals")
    if (decimals.exists(d => Try(d.toInt).filter(n => n >= 0 && n <= 18).isFailure)) throw CliError("--decimals takes 0 to 18")
    ShareTerms(a.opt("shares").map(parseLong(_, "--shares")).getOrElse(StandardMetadata.ShareCount),
      parseErg(a.opt("pull-fee").getOrElse(DefaultShareFee)), parseErg(a.opt("mint-fee").getOrElse(DefaultShareFee)),
      a.opt("share-name"), a.opt("share-description"), decimals, auction)
  }

  private def market(a: Args): Market = a.opt("market").map(n => Market.named(n).getOrElse(
    throw CliError(s"unknown market $n; known: ${Market.all.mkString(", ")}"))).getOrElse(Market.Erg)

  private def expiry(app: StandardApp, a: Args): Long =
    app.height.toLong + a.opt("expires-in").map(parseLong(_, "--expires-in")).getOrElse(StandardCommands.DefaultExpiry)

  private def sell(app: StandardApp, a: Args): Prepared = {
    val m = market(a)
    val (endBps, endHeight) = yieldEnd(app, a)
    val price = if (a.opt("yield").isDefined) YieldPrice(nonNegativeBps(a.need("yield")),
      a.opt("price").map(Units.parse(_, m, "--price")), endBps, endHeight)
    else FixedPrice(Units.parse(a.opt("price").getOrElse(throw CliError("give --price <total> or --yield <percent>")), m, "--price"))
    if (a.opt("oracle-age").isDefined && a.opt("min-cover").isEmpty) throw CliError("--oracle-age on an ask needs --min-cover")
    val guard = percent(a, "min-cover").map { bps =>
      if (bps <= 0L) throw CliError("--min-cover must be positive")
      SaleGuard(bps, a.opt("oracle-age").map(parseLong(_, "--oracle-age")).getOrElse(DefaultOfferOracleAge))
    }
    app.sell(one(a, "token id"), parseLong(a.need("amount"), "--amount"), m, price,
      a.opt("min-fill").map(parseLong(_, "--min-fill")), expiry(app, a),
      parseErg(a.opt("refund-fee").getOrElse(DefaultRefundFee)), guard)
  }

  private[standard] def nonNegativeBps(s: String, key: String = "yield"): Long = {
    val bps = Try(BigDecimal(s) * 100).getOrElse(throw CliError(s"--$key: $s is not a percentage"))
    if (!bps.isWhole || bps < 0) throw CliError(s"--$key must be nonnegative, with at most 2 decimals")
    Try(bps.toLongExact).getOrElse(throw CliError(s"--$key is too large"))
  }


  /**
   * A moving yield's second point: --end-yield (default --yield), reached at --end-height or --end-in blocks from
   * now (default the order's or ask's expiry). Between now and then the yield moves in a straight line.
   */
  private[standard] def yieldEnd(app: StandardApp, a: Args): (Option[Long], Option[Long]) = {
    val endBps = a.opt("end-yield").map(nonNegativeBps(_, "end-yield"))
    val endHeight = (a.opt("end-height"), a.opt("end-in")) match {
      case (Some(h), None) => Some(parseLong(h, "--end-height"))
      case (None, Some(d)) => Some(app.height.toLong + parseLong(d, "--end-in"))
      case (None, None) => None
      case _ => throw CliError("give --end-height or --end-in, not both")
    }
    if ((endBps.isDefined || endHeight.isDefined) && a.opt("yield").isEmpty)
      throw CliError("--end-yield, --end-height and --end-in need --yield")
    (endBps, endHeight)
  }

  /** A curve as "yield%:blocks,..." points, e.g. "8:21600,10:131400,12:262800". */
  private[standard] def curve(a: Args): Seq[(Long, Long)] = a.opt("curve").toSeq.flatMap(_.split(",").map(_.trim).filter(_.nonEmpty)).map { p =>
    p.split(":") match {
      case Array(y, d) => (nonNegativeBps(y, "curve"), parseLong(d, "--curve blocks"))
      case _ => throw CliError(s"--curve: $p is not <yield percent>:<blocks to maturity>")
    }
  }

  /** --token <token id>:<raw units>, as many as the lot holds. */
  private def lotTokens(a: Args): Seq[work.lithos.mutations.Token] = a.opts("token").map { s =>
    s.split(":") match {
      case Array(id, n) => work.lithos.mutations.Token(Try(ErgoId.create(id)).getOrElse(throw CliError(s"--token: $id is not a token id")),
        parseLong(n, "--token amount"))
      case _ => throw CliError(s"--token: $s is not <token id>:<raw units>")
    }
  }

  private def sellLot(app: StandardApp, a: Args): Prepared = {
    val m = market(a)
    val start = app.height.toLong + a.opt("starts-in").map(parseLong(_, "--starts-in")).getOrElse(0L)
    val end = start + a.opt("falls-for").map(parseLong(_, "--falls-for")).getOrElse(DefaultLotFall)
    val expiry = end + a.opt("holds-for").map(parseLong(_, "--holds-for")).getOrElse(DefaultLotHold)
    app.sellLot(m, lotTokens(a), a.opt("erg").map(parseErg).getOrElse(0L),
      Units.parse(a.need("opening"), m, "--opening"), Units.parse(a.need("floor"), m, "--floor"), start, end, expiry,
      parseErg(a.opt("refund-fee").getOrElse(DefaultRefundFee)))
  }

  private def offerSpec(app: StandardApp, a: Args): OfferSpec = {
    val m = market(a)
    if (m.token.isDefined && a.opt("batcher-fee").isDefined)
      throw CliError("token offers use --batcher-budget for proportional rewards; --refund-fee sets their expiry refund fee")
    if (m.token.isEmpty && Seq("batcher-budget", "min-fill-percent").exists(a.opt(_).isDefined))
      throw CliError("--batcher-budget and --min-fill-percent apply only to token offers")
    if (a.positional.nonEmpty)
      throw CliError(s"create-offer takes no arguments, but was given ${a.positional.mkString(" ")}; " +
        "--deliver-shares is a flag, and --shares <N> sets the count")
    val delivery = if (a.flag("deliver-shares")) Some(OfferDelivery(shareTerms(a))) else None
    if (delivery.isEmpty && (a.flag("dutch") || Seq("shares", "share-name", "share-description", "decimals", "vault",
      "auction-opening", "auction-floor", "auction-length", "auction-expiry").exists(a.opt(_).isDefined)))
      throw CliError("share options on an offer need --deliver-shares")
    val points = curve(a)
    if (a.opt("yield").isEmpty && points.isEmpty) throw CliError("give --yield <percent>, --curve <points>, or both")
    OfferSpec(m,
      capital = Units.parse(a.need("capital"), m, "--capital"),
      fees = if (m.token.isDefined) parseErg(a.opt("fees").getOrElse(DefaultOfferFees)) else 0L,
      yieldBps = percent(a, "yield").getOrElse(0L),
      curve = points,
      minBlocks = a.opt("min-blocks").map(parseLong(_, "--min-blocks")).getOrElse(1L),
      maxBlocks = a.opt("max-blocks").map(parseLong(_, "--max-blocks")).getOrElse(DefaultMaxBlocks),
      batcherFee = parseErg(a.opt(if (m.token.isDefined) "refund-fee" else "batcher-fee").getOrElse(DefaultBatcherFee)),
      feeReserve = reserve(a, "fee-reserve"),
      floorBps = needPercent(a, "collateral-floor"),
      maxAge = a.opt("oracle-age").map(parseLong(_, "--oracle-age")).getOrElse(DefaultOfferOracleAge),
      expiry = expiry(app, a),
      minPrice = a.opt("min-price").map(Units.parse(_, m, "--min-price")).getOrElse(1L),
      auctionFloorBps = percent(a, "min-auction-floor").getOrElse(0L),
      auctionExpiry = a.opt("max-auction-expiry").map(parseLong(_, "--max-auction-expiry")).getOrElse(0L),
      delivery = delivery,
      minFillBps = percent(a, "min-fill-percent").getOrElse(100L),
      batcherBudget = a.opt("batcher-budget").map(parseErg))
  }

  // ═══ LISTINGS ═════════════════════════════════════════════════════════════

  private def listShares(app: StandardApp, mine: Boolean): Unit = {
    val requests = app.shareRequests().filter(r => !mine || app.isOwnHash(r.ownerHash))
    val vaults = app.shareVaults().filter(v => !mine || (app.walletAvailable && app.walletHolds(v.shareId)))
    if (requests.isEmpty && vaults.isEmpty) println("No share requests or vaults.")
    requests.foreach { r =>
      println(s"Request ${r.box.id}  ${r.shares} shares of bond ${r.bondAsset}" + r.dutch.fold(" (plain fractional)")(m =>
        s" (${AuctionTerms.fromLongs(r.auction).fold("Dutch pair")(_.kind)}, $m)"))
      AuctionTerms.fromLongs(r.auction).foreach(t => println(s"  ${t.describe}"))
      println(s"  named '${r.metadata.headOption.map(StandardMetadata.text).getOrElse("")}'")
      println(s"  mint fee ${Fmt.erg(r.mintFee)}, pull fee ${Fmt.erg(r.pullFee)}: anyone may `mint-shares ${r.box.id}`")
      metadataLine(app.requestMetadataIssues(r))
      if (app.isOwnHash(r.ownerHash)) println("  (your request)")
    }
    vaults.foreach { v =>
      val kind = if (!v.dutch) "plain fractional" else if (v.threshold == StandardProtocol.DutchThreshold) "Dutch" else "Selective"
      println(s"Share ${v.shareId}  vault ${v.box.id}  ($kind)")
      println(s"  bond ${v.bondAsset}: ${v.outstanding} of ${v.n} shares outstanding")
      metadataLine(app.shareMetadataIssues(v.shareId))
      val status =
        if (v.holding) "holds the bond unit; pull its settlement once the bond is repaid or liquidated"
        else if (v.excess) {
          val m = Market.of(v.currencyToken)
          app.excessPrice(v, app.height.toLong) match {
            case Some(p) => s"EXCESS AUCTION OPEN (the floor is already held): the thin lot costs " +
              s"${m.fold(p.toString)(Units.amount(p, _))} now (`buy-lot ${v.box.id}`), falling to nothing at height " +
              s"${v.claimableFrom}, when claims take it in kind"
            case None => s"excess auction over: its price reached zero at height ${v.claimableFrom}; claims take the lot in kind"
          }
        } else if (v.auctionOpen) {
          val m = Market.of(v.currencyToken)
          val fallEnd = v.auctionStart + v.auctionLength
          val target = app.lotTarget(v, app.height.toLong)
          s"AUCTION OPEN: the lot costs ${m.fold(target.toString)(Units.amount(target - app.currencyHeld(v), _))} now " +
            s"(`buy-lot ${v.box.id}`), falling to the floor at height $fallEnd; claims wait for its expiry at height " +
            s"${v.claimableFrom}"
        } else v.state.map {
          case 0 => "repaid"
          case 1 if !v.dutch => "liquidated: paid in kind"
          case 2 => "liquidated: the lot sold, or paid in kind (nothing to sell, or every item splits pro rata)"
          case 3 => "liquidated: unsold by the auction's end, paid in kind"
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
      val guard = app.protocol.saleGuard(a)
      val line = a.yieldLine
      val fixed = !a.priced || (guard.isDefined && line.exists(l => l.startBps == StandardProtocol.GuardedFixedYield && l.fixed))
      val known = !a.priced || Try(app.protocol.pricingOf(a)).isSuccess
      println(s"Ask ${a.box.id}  ${a.token.amount} of ${a.token.id}  (${if (fixed) "fixed price" else if (!known) "unknown pricing" else "block yield"}" +
        s"${if (guard.isDefined) ", guarded" else ""}, paid in ${a.market})")
      val price = Try(app.askPrice(a, a.token.amount)._1).toOption
      val face = price.flatMap(p => app.faceBps(a.token.id, p, a.token.amount))
      val now = app.height.toLong
      println("  price:  " + price.fold("not fillable now (its bond is not live)")(p => s"${Units.amount(p, a.market)} for all") +
        face.fold("")(f => s" (${Units.percent(f)} of face)") +
        (if (fixed || !known) "" else line.fold("") { l =>
          s"; ${Units.percent(l.bpsAt(now))} simple buyer yield per 262800 blocks now" +
            (if (l.fixed) "" else s", moving to ${Units.percent(l.endBps)} at height ${l.end}") + s"; fees excluded"
        }))
      guard.foreach { case (minBps, age) =>
        println(s"  sale guard: sells only while its bond is at ${Units.percent(minBps)} cover or more " +
          s"(oracle at most $age blocks old)")
      }
      println(s"  minimum fill ${a.minFill}; expires at height ${a.expiry}" +
        (if (app.height.toLong >= a.expiry) " (EXPIRED: anyone may refund-ask it)" else ""))
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
      println(s"  capital ${Units.amount(o.capital, m)} at a simple yield per 262800 blocks of ${o.curve.describe}, or better; " +
        s"bonds maturing in ${o.minBlocks} to ${o.maxBlocks} blocks; minimum price ${Units.amount(o.minPrice, m)}")
      println(s"  oracle-rule bonds at a ratio of at least ${Units.percent(o.floorBps)}, oracle at most ${o.maxAge} blocks old")
      println((if (m.token.isEmpty) s"  batcher fee ${Fmt.erg(o.batcherFee)}"
        else s"  batcher budget ${Fmt.erg(o.batcherBudget)}; total ERG spend budget ${Fmt.erg(o.spendBudget)}; " +
          s"minimum fill ${Units.percent(o.minFillBps)} of original ${Units.amount(o.originalCapital, m)}; " +
          s"expiry refund fee ${Fmt.erg(o.batcherFee)}") + s"; new-bond fee reserve ${Fmt.erg(o.feeReserve)}" +
        (if (o.terms(10) > 0L) s"; Selective/Dutch shares with an auction floor of at least ${Units.percent(o.terms(9))} " +
          s"and an expiry of at most ${o.terms(10)} blocks" else ""))
      if (o.delivers) {
        println(s"  delivers bond units into ${o.fractional.headOption.getOrElse(0L)} shares" +
          AuctionTerms.fromLongs(o.fractional.drop(3)).fold(" (plain fractional)")(t => s" (${t.kind})") +
          s", named '${o.metadata.headOption.map(StandardMetadata.text).getOrElse("")}'")
        metadataLine(app.offerMetadataIssues(o))
      }
      println(s"  expires at height ${o.expiry}" + (if (app.height.toLong >= o.expiry) " (EXPIRED: anyone may refund-offer it)" else ""))
      if (app.isOwnHash(o.ownerHash)) println("  (your offer)")
    }
  }

  private def listLots(app: StandardApp, mine: Boolean): Unit = {
    val lots = app.lots().filter(l => !mine || app.isOwnHash(l.sellerHash))
    if (lots.isEmpty) println("No lots.")
    val now = app.height.toLong
    lots.foreach { l =>
      val m = l.market
      println(s"Lot ${l.box.id}  (paid in $m)")
      println(s"  lot:    ${Fmt.contents(if (m.token.isEmpty) 0L else l.box.value, l.lot)}")
      if (!l.valid) println("  MALFORMED terms: it cannot be bought; only its seller can cancel it")
      else println(s"  price:  ${Units.amount(l.price(now), m)} now (`take-lot ${l.box.id}`); " +
        s"${Units.amount(l.opening, m)} until height ${l.start}, falling to ${Units.amount(l.floor, m)} at height ${l.end}")
      println(s"  expires at height ${l.expiry}" +
        (if (now >= l.expiry) s" (EXPIRED: anyone may refund-lot it for ${Fmt.erg(l.refundFee)})" else ""))
      if (app.isOwnHash(l.sellerHash)) println("  (your lot)")
    }
  }

  private def listWraps(app: StandardApp, mine: Boolean): Unit = {
    val requests = app.wrapRequests().filter(r => !mine || app.isOwnHash(r.ownerHash))
    val vaults = app.wrapVaults().filter(v => !mine || (app.walletAvailable && app.walletHolds(v.receiptId)))
    if (requests.isEmpty && vaults.isEmpty) println("No wrap requests or vaults.")
    requests.foreach { r =>
      println(s"Wrap request ${r.box.id}  ${r.wrapped.amount} of ${r.wrapped.id}")
      println(s"  receipt named '${r.metadata.headOption.map(StandardMetadata.text).getOrElse("")}'; mint fee " +
        s"${Fmt.erg(r.mintFee)}: anyone may `mint-wrap ${r.box.id}`")
      if (app.isOwnHash(r.ownerHash)) println("  (your request)")
    }
    val now = app.height.toLong
    vaults.foreach { v =>
      println(s"Receipt ${v.receiptId}  vault ${v.box.id}")
      println(s"  holds ${v.wrapped.amount} of ${v.wrapped.id} and ${Fmt.erg(v.box.value)}" +
        (if (app.wrapVerified(v.receiptId)) "" else " (WARNING: not minted by a wrap request: this vault proves nothing)"))
      val made = v.box.input.getCreationHeight.toLong
      println(s"  made at height $made; refreshable from height ${made + StandardProtocol.RefreshAge + 1}" +
        (if (StandardProtocol.refreshable(made, now)) " (NOW: `refresh " + v.box.id + "`)" else ""))
      if (app.walletAvailable && app.walletHolds(v.receiptId)) println(s"  (you hold the receipt: `unwrap ${v.receiptId}`)")
    }
  }

  /** "metadata: zcs1", or what departs from it. */
  private[standard] def metadataLine(issues: Seq[String]): Unit =
    println(if (issues.isEmpty) "  metadata: zcs1" else s"  metadata: NONSTANDARD: ${issues.mkString("; ")}")

  /**
   * List the work, and with --run do it in rounds (StandardBatch.runBatch): refunds and liquidations packed into
   * shared transactions, the rest one by one, and a fresh scan after each round so fills chain through an
   * offer's continuation.
   */
  private def batch(app: StandardApp, a: Args, run: String => Boolean): Unit = {
    val scan = app.batchScan()
    if (scan.work.isEmpty) println("Nothing to batch.")
    scan.work.foreach(w => println(f"${Fmt.erg(w.reward)}%-14s ${w.summary}" + (if (w.packable) " (packs)" else "") +
      f"%n               ${w.command}"))
    if (scan.skipped.nonEmpty) {
      println("\nPassed over:")
      scan.skipped.foreach(s => println(s"  $s"))
    }
    if (a.flag("run") && scan.work.nonEmpty) {
      val log = app.runBatch { command =>
        println(s"\n> $command")
        run(command)
      }
      println(s"\n${log.count(_._2)} of ${log.size} built" + (if (a.flag("send")) " and submitted." else "; run with --send to submit them."))
    }
  }
}
