package ksingh.sigmabonds
package standard

import ksingh.sigmabonds.app.Protocol._
import ksingh.sigmabonds.app._
import org.ergoplatform.sdk.ErgoId
import scorex.util.encode.Base16
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import scala.util.Try

/** Shares a lend offer asks for its bond units: minted by a batcher into this owner's wallet. */
case class OfferDelivery(terms: ShareTerms)

/**
 * A lender's yield bid. `capital` and `minPrice` are raw units of the market's asset; `fees` is the nanoERG a
 * token offer carries for batcher fees and box minimums (an ERG offer pays them from its capital).
 */
case class OfferSpec(market: Market, capital: Long, fees: Long, yieldBps: Long, minBlocks: Long, maxBlocks: Long,
                     batcherFee: Long, feeReserve: Long, floorBps: Long, maxAge: Long, expiry: Long, minPrice: Long,
                     auctionFloorBps: Long, auctionLength: Long, delivery: Option[OfferDelivery])

/**
 * Lend offers: ZC_Standard_LendOffer with ZC_Standard_ERG_SigUSD_CollateralPolicy. Creating, cancelling and
 * refunding one, and matching it, which any batcher does for the offer's fee: against a standard order (a new
 * bond) or against an ask selling a bond unit or shares of one.
 */
trait StandardOffers extends AppBase { self: StandardApp =>

  import StandardProtocol._

  /** One year of 2-minute blocks, times the basis-point denominator, as the offer's yield test uses it. */
  private val Year = BigInt(2628000000L)

  def offers(): Seq[StdOffer] =
    decoded(nodeApi.unspentByTemplateHash(Base16.encode(protocol.offerTemplateHash))).flatMap(protocol.asOffer)

  // ═══ CREATE, CANCEL, REFUND ═══════════════════════════════════════════════

  def createOffer(s: OfferSpec): Prepared = {
    val m = s.market
    if (s.capital <= 0L) fail("the offer's capital must be positive")
    if (s.yieldBps < 0L) fail("the yield cannot be negative")
    if (s.minBlocks < 1L || s.maxBlocks < s.minBlocks) fail("the maturity window needs 1 <= min blocks <= max blocks")
    if (s.batcherFee < MinBatchingFee) fail(s"the batcher fee must be at least ${Fmt.erg(MinBatchingFee)}")
    if (s.feeReserve < 0L) fail("the fee reserve cannot be negative")
    if (s.floorBps <= 0L) fail("the collateral floor must be positive")
    if (s.maxAge < 1L || s.maxAge > 100000L) fail("the oracle age must be 1 to 100000 blocks")
    if (s.expiry <= height.toLong + 1L) fail("the offer's expiry must be above the current height")
    if (s.minPrice < 1L) fail("the minimum price must be at least 1")
    if (s.auctionFloorBps < 0L || s.auctionLength < 0L) fail("the auction bounds cannot be negative")
    // An offer is posted before it knows which bond it buys, so zcs1 metadata here leaves the bond out.
    val standard = s.delivery.map(d => StandardMetadata.shares(m, d.terms.auction, None))
    val (fractional, metadata, mint) = s.delivery match {
      case None => (Seq.empty[Long], textsValue(), Array.empty[Byte])
      case Some(OfferDelivery(t)) =>
        if (t.shares < 1L || t.shares > 1000000000L) fail("the share count must be 1 to 1000000000")
        if (t.pullFee < MinBatchingFee || t.mintFee < MinBatchingFee) fail(s"the pull and mint fees must each be at least ${Fmt.erg(MinBatchingFee)}")
        (t.longs, textsValue(t.metadata(standard.get): _*),
          if (t.auction.isDefined) protocol.market(m).dutchMint.hashedPropBytes else protocol.contracts.fractionalMint.hashedPropBytes)
    }
    val terms = Seq(s.yieldBps, s.minBlocks, s.maxBlocks, s.batcherFee, s.feeReserve, s.floorBps, s.expiry,
      s.minPrice, s.maxAge, s.auctionFloorBps, s.auctionLength)
    val regs = Seq(bytesValue(hashOf(myTree)), longsValue(terms: _*),
      bytesValue(protocol.market(m).policy.hashedValueBytes), longsValue(fractional: _*), metadata, bytesValue(mint))
    val offer = m.token match {
      case None => UTXO(protocol.market(m).offer, s.capital, Seq.empty, regs)
      case Some(id) => UTXO(protocol.market(m).offer, s.fees, Seq(Token(id, s.capital)), regs)
    }
    prepare("Create lend offer", plan(Seq.empty, Seq(offer)), Seq(
      s"lend up to ${Units.amount(s.capital, m)} at ${Units.percent(s.yieldBps)} simple yield per 262800 blocks or better",
      s"bonds maturing ${s.minBlocks} to ${s.maxBlocks} blocks after a fill, with the oracle rule at a ratio of at " +
        s"least ${Units.percent(s.floorBps)} and collateral worth that now; minimum price ${Units.amount(s.minPrice, m)}",
      s"batcher fee ${Fmt.erg(s.batcherFee)} per fill; new bonds carry a ${Fmt.erg(s.feeReserve)} fee reserve",
      if (s.auctionLength == 0L) "buys no Dutch auction shares"
      else s"buys Dutch auction shares with a floor of at least ${Units.percent(s.auctionFloorBps)} and at most ${s.auctionLength} blocks",
      s.delivery.fold("bond units come to this wallet")(d => s"bond units are delivered into ${d.terms.shares} shares" +
        d.terms.auction.fold("")(_ => " with a Dutch auction") + s", named '${d.terms.metadata(standard.get).head}'"),
      s"expires at height ${s.expiry}; cancel it any time with `cancel-offer <its box id>`") ++
      s.delivery.toSeq.flatMap(_.terms.departures(standard.get)))
  }

  /** How an offer's share delivery departs from zcs1: its count and its metadata, which omit the bond. */
  def offerMetadataIssues(o: StdOffer): Seq[String] =
    if (!o.delivers) Seq.empty
    else {
      val auction = if (o.fractional.size >= 6) Some(AuctionTerms(o.fractional(3), o.fractional(4), o.fractional(5))) else None
      val standard = StandardMetadata.shares(o.market, auction, None)
      val found = o.metadata.map(StandardMetadata.text)
      (if (o.fractional.head != StandardMetadata.ShareCount)
        Seq(s"it delivers ${o.fractional.head} shares, not zcs1's ${StandardMetadata.ShareCount}") else Seq.empty) ++
        (if (found != standard) Seq(s"its share name is '${found.headOption.getOrElse("")}', not zcs1's '${standard.head}'")
        else Seq.empty)
    }

  def cancelOffer(offerId: String): Prepared = {
    val o = protocol.asOffer(unspent(offerId)).getOrElse(fail(s"$offerId is not a ZC Standard lend offer"))
    prepare(s"Cancel offer $offerId", sweep(Seq(o.box, walletBoxAt(o.ownerHash, "owner"))))
  }

  /** From an offer's expiry on, anyone returns it to its owner, tagged, and keeps its batcher fee. */
  def refundOffer(offerId: String): Prepared = {
    val o = protocol.asOffer(unspent(offerId)).getOrElse(fail(s"$offerId is not a ZC Standard lend offer"))
    packed(s"Refund offer $offerId", Seq(offerRefund(o)))
  }

  // ═══ THE OFFER'S OWN CHECKS, OFF CHAIN ════════════════════════════════════

  /**
   * Why `offer` would refuse paying `price` for `units` of `whole` units of `bond`, or None if it would not:
   * expiry, the maturity window, the minimum price, the yield test, capital, and the collateral policy at the
   * oracle's price. A refusal here saves a failed transaction; the contract has the last word.
   */
  def offerRefusal(o: StdOffer, bond: StdBond, price: Long, units: Long, whole: Long,
                   oracle: Option[OracleState]): Option[String] = {
    val at = height.toLong + 1L
    val remaining = bond.maturity - at
    val cost = if (o.market.token.isEmpty) BigInt(price) + o.batcherFee else BigInt(price)
    val rule = protocol.market(o.market).oracleRule.hashedValueBytes
    lazy val cover = oracle.flatMap(p => protocol.cover(bond, p.price))
    if (at >= o.expiry) Some(s"the offer expired at height ${o.expiry}")
    else if (o.market != bond.market) Some(s"the offer lends in ${o.market}, the bond repays in ${bond.market}")
    else if (remaining <= 0L || remaining < o.terms(1) || remaining > o.terms(2))
      Some(s"the bond matures in $remaining blocks, outside the offer's ${o.terms(1)} to ${o.terms(2)}")
    else if (price <= 0L || price < o.minPrice) Some(s"the price ${Units.amount(price, o.market)} is under the offer's minimum")
    else if (BigInt(bond.repayment) * units * Year < cost * whole * (Year + BigInt(o.yieldBps) * remaining))
      Some(s"${Units.amount(price, o.market)} for $units of $whole is under the offer's ${Units.percent(o.yieldBps)} yield")
    else if (o.capital < price) Some(s"the offer holds only ${Units.amount(o.capital, o.market)}")
    else if (!bond.liqHash.sameElements(rule)) Some("the bond's liquidation rule is not the oracle rule the offer's policy requires")
    else if (bond.payload.size < 2 || bond.payload(0) < o.floorBps || bond.payload(1) < o.maxAge || bond.payload(1) > 100000L)
      Some(s"the bond's own ratio or oracle age is under the offer's ${Units.percent(o.floorBps)} and ${o.maxAge} blocks")
    else if (oracle.isEmpty) Some("the node's index has no single USD oracle pool box")
    else if (at - oracle.get.created > o.maxAge) Some(s"the oracle price is older than the offer's ${o.maxAge} blocks")
    else if (!cover.exists { case (c, d) => c * 10000 >= d * o.floorBps })
      Some(s"the collateral is at ${protocol.ratioBps(bond, oracle.get.price).fold("?")(Units.percent)}, under the offer's floor")
    // zcs1, not the contract: the policy passes a bond under its own ratio but over the floor, which anyone could
    // then liquidate at once for all its collateral. A batcher liquidates such a bond instead of buying or making it.
    else if (protocol.belowOwnRatio(bond, oracle.get.price))
      Some(s"the bond is at ${protocol.ratioBps(bond, oracle.get.price).fold("?")(Units.percent)}, under its own " +
        s"${Units.percent(bond.payload(0))} ratio, so it could be liquidated at once")
    else None
  }

  /** The offer's context vars common to every match: 0 its continuation, 1 the policy expression. */
  private def offerInput(o: StdOffer, nextIndex: Int): InputUTXO =
    o.box.withCtxVar(0.toByte, intValue(nextIndex)).withCtxVar(1.toByte, bytesValue(protocol.market(o.market).policy.valueBytes))

  /** The offer after paying `price` and `ergOut`. */
  private def offerRest(o: StdOffer, price: Long, ergOut: Long): UTXO = {
    val tokens = o.market.token match {
      case None => Seq.empty[Token]
      case Some(id) => if (o.capital - price > 0L) Seq(Token(id, o.capital - price)) else Seq.empty
    }
    val value = o.box.value - ergOut
    if (value < MinBox) fail(s"the offer's ${Fmt.erg(o.box.value)} cannot cover ${Fmt.erg(ergOut)} of fees and box minimums")
    UTXO(Contract(o.box.input.getErgoTree), value, tokens, o.box.registers)
  }

  /** Where the offer sends a bond unit: to its owner, or into the share mint request it names. */
  private def unitDelivery(o: StdOffer, asset: ErgoId, eip4: Seq[org.ergoplatform.appkit.ErgoValue[_]]): (UTXO, Long) = {
    val owner = ownerTree(o.ownerHash, o.box, "offer owner")
    if (!o.delivers) (UTXO(Contract(owner), MinBox, Seq(Token(asset, 1L)), eip4), MinBox)
    else {
      val mint = if (o.mintHash.sameElements(protocol.contracts.fractionalMint.hashedPropBytes)) protocol.contracts.fractionalMint
        else protocol.market(o.market).dutchMint
      if (!mint.hashedPropBytes.sameElements(o.mintHash)) fail("the offer delivers into a mint script this client does not know")
      val funding = 2L * MinBox + o.fractional(1) + o.fractional(2)
      // Registers are dense: R7 to R9 need something in R4 to R6. A fill puts the asset's EIP-4 there; a
      // unit bought from an ask has none, so the shares' own metadata stands in.
      val front = if (eip4.nonEmpty) eip4 else o.metadata.map(bytesValue)
      (UTXO(mint, funding, Seq(Token(asset, 1L)),
        front ++ Seq(bytesValue(o.ownerHash), longsValue(o.fractional: _*), bytesListValue(o.metadata))), funding)
    }
  }

  private def bytesListValue(items: Seq[Array[Byte]]): org.ergoplatform.appkit.ErgoValue[_] =
    org.ergoplatform.appkit.ErgoValue.of(sigma.Colls.fromArray(items.map(i => sigma.Colls.fromArray(i)).toArray)(
      sigma.collRType(sigma.ByteType)), org.ergoplatform.appkit.ErgoType.collType(org.ergoplatform.appkit.scalaapi.scalaByteType))

  // ═══ MATCHES ══════════════════════════════════════════════════════════════

  /**
   * Fill a standard order from an offer: the order at INPUTS(0) mints the bond asset, the offer pays the
   * principal and the new bond's fee reserve, and this wallet keeps the offer's batcher fee. Outputs: 0 the
   * delivery, 1 the bond, 2 the principal, 3 the offer's continuation.
   */
  def matchOrder(offerId: String, orderId: String): Prepared = {
    val o = protocol.asOffer(unspent(offerId)).getOrElse(fail(s"$offerId is not a ZC Standard lend offer"))
    val original = protocol.asOrder(unspent(orderId)).getOrElse(fail(s"$orderId is not a ZC Standard order"))
    checkOrder(original)
    val order = resolveOrder(original, height.toLong)
    val start = height.toLong
    val maturity = if (order.onClose) addAmount(start, order.terms(3)) else order.terms(3)
    val asset = order.box.id
    val bond = prospective(order, start, maturity, o.feeReserve)
    val oracle = Try(self.oracle()).toOption.flatten
    offerRefusal(o, bond, order.principal, 1L, 1L, oracle).foreach(r => fail(s"the offer refuses this order: $r"))
    guardRefusal(order, bond, oracle).foreach(r => fail(s"the order's fill guard refuses this fill: $r"))
    oracle.foreach(readableOracle)

    val rule = protocol.ruleOf(order.liqHash).getOrElse(fail("the order names a liquidation expression this client does not know"))
    val (delivery, funding) = unitDelivery(o, asset, bondMetadata(order, rule, start, maturity))
    val bondOut = UTXO(protocol.market(order.market).bond, addAmount(order.collateralErg, o.feeReserve),
      Token(asset, 2L) +: order.collateral,
      Seq(bytesValue(asset.getBytes), bytesValue(order.borrowerHash), longsValue(order.terms: _*),
        tokensValue(order.principalTokens), bytesValue(order.liqHash), longsValue(o.feeReserve, start, maturity)),
      Some(start.toInt))
    val principal = UTXO(Contract(borrowerTree(order.borrowerHash, order.box)),
      addAmount(order.expiryFee, order.principalErg), order.principalTokens)
    val ergOut = o.batcherFee + funding + o.feeReserve + (if (o.market.token.isEmpty) order.principal else order.principalErg)
    val rest = offerRest(o, order.principal, ergOut)
    prepare(s"Match offer $offerId with order $orderId",
      plan(Seq(orderInput(order), offerInput(o, 3)), Seq(delivery, bondOut, principal, rest), minted = Some(asset),
        dataInputs = oracle.map(_.box).toSeq),
      Seq(s"the offer lends ${Units.amount(order.principal, o.market)} for ${Units.amount(order.repayment, o.market)} " +
        s"at height $maturity", s"you keep the offer's ${Fmt.erg(o.batcherFee)} batcher fee, less the miner fee"))
  }

  /**
   * Buy from an ask with an offer: a bond unit, or shares of one, priced by the ask. The offer verifies the
   * asset's origin (var 4), the live bond (data input 0) and for shares their mint request (var 6). Inputs:
   * 0 the ask, 1 the offer. Outputs: 0 the payout, 1 the delivery, 2 the offer, 3 the ask's continuation.
   */
  def matchAsk(offerId: String, askId: String, units: Option[Long]): Prepared = {
    val o = protocol.asOffer(unspent(offerId)).getOrElse(fail(s"$offerId is not a ZC Standard lend offer"))
    val ask = protocol.asAsk(unspent(askId)).getOrElse(fail(s"$askId is not a ZC Standard ask"))
    if (ask.market != o.market) fail(s"the ask is paid in ${ask.market}, the offer lends ${o.market}")
    val token = ask.token.id
    val shares = shareVault(token)
    val asset = shares.fold(token)(_.bondAsset)
    val whole = shares.fold(1L)(_.n)
    val n = units.getOrElse(ask.token.amount)
    checkFill(ask, n)
    if (shares.isEmpty && n != 1L) fail("an offer buys one bond unit at a time")
    val bond = decoded(nodeApi.unspentByTokenId(asset.toString)).flatMap(protocol.asBond).find(_.asset == asset)
      .getOrElse(fail(s"bond $asset is not live: an offer buys only claims on a live bond"))
    // The offer judges a purchase at the payout's whole value, so an ERG price under the box minimum is that minimum.
    val price = if (o.market.token.isEmpty) math.max(askPrice(ask, n)._1, MinBox) else askPrice(ask, n)._1
    val oracle = Try(self.oracle()).toOption.flatten
    offerRefusal(o, bond, price, n, whole, oracle).foreach(r => fail(s"the offer refuses this ask: $r"))
    saleGuardRefusal(ask, bond, oracle).foreach(r => fail(s"the ask's sale guard refuses this sale: $r"))
    oracle.foreach(readableOracle)

    val originBytes = nodeApi.boxById(asset.toString).filter(j => NodeJson.spentBy(j).isDefined)
      .flatMap(j => Try(NodeJson.box(j)).toOption).map(_.bytes).getOrElse(fail(s"the order that minted $asset is not in the node's index"))
    val requestBytes = shares.map { v =>
      val request = nodeApi.boxById(v.shareId.toString).flatMap(j => Try(NodeJson.box(j)).toOption)
        .getOrElse(fail("the request that minted the shares is not in the node's index"))
      val req = protocol.asShareRequest(request).getOrElse(fail("the shares were not minted by a standard share mint"))
      req.dutch.foreach { _ =>
        if (o.terms(10) == 0L) fail("the offer buys no Dutch auction shares")
        if (req.auction(1) < o.terms(9) || req.auction(2) > o.terms(10))
          fail(s"the shares' auction (floor ${Units.percent(req.auction(1))}, ${req.auction(2)} blocks) is outside the offer's bounds")
      }
      request.bytes
    }
    val rest = askRest(ask, n)
    val (delivery, funding) =
      if (shares.isDefined) (UTXO(Contract(ownerTree(o.ownerHash, o.box, "offer owner")), MinBox, Seq(Token(token, n))), MinBox)
      else unitDelivery(o, asset, Seq.empty)
    val ergOut = o.batcherFee + funding + (if (o.market.token.isEmpty) price else MinBox)
    val offerIn = requestBytes.foldLeft(offerInput(o, 2).withCtxVar(2.toByte, intValue(0)).withCtxVar(3.toByte, intValue(0))
      .withCtxVar(4.toByte, bytesValue(originBytes)).withCtxVar(5.toByte, intValue(1)))((b, bytes) => b.withCtxVar(6.toByte, bytesValue(bytes)))
      .withCtxVar(7.toByte, intValue(0))
    prepare(s"Match offer $offerId with ask $askId",
      plan(Seq(askInput(ask, 0, rest.map(_ => 3)), offerIn),
        Seq(askPayout(ask, n, price), delivery, offerRest(o, price, ergOut)) ++ rest,
        dataInputs = bond.box +: oracle.map(_.box).toSeq),
      Seq(s"the offer pays ${Units.amount(price, o.market)} for $n of $token" + shares.fold("")(v => s" (shares of bond ${v.bondAsset})"),
        s"you keep the offer's ${Fmt.erg(o.batcherFee)} batcher fee, less the miner fee"))
  }
}
