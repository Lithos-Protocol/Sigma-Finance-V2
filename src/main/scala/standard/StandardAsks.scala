package ksingh.sigmabonds
package standard

import ksingh.sigmabonds.app.Protocol._
import ksingh.sigmabonds.app._
import org.ergoplatform.appkit.ErgoValue
import org.ergoplatform.sdk.ErgoId
import scorex.util.encode.Base16
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import scala.util.Try

/** How an ask is priced: a fixed total, or NAV/block yield with a signed price margin and optional floor. */
sealed trait AskPrice
/** `total` raw units of the market's asset for all the ask's units: the price per unit is total / amount. */
case class FixedPrice(total: Long) extends AskPrice
/** `marginBps` over NAV (signed); `floorTotal`, when given, is the least the whole amount may sell for. */
case class NavPrice(marginBps: Long, floorTotal: Option[Long]) extends AskPrice
/** Reference simple yield in bps per 262800 blocks, signed price margin, and optional floor. */
case class YieldPrice(yieldBps: Long, floorTotal: Option[Long] = None, marginBps: Long = 0L) extends AskPrice

/**
 * A seller's sale guard: the ask sells only while the bond's collateral is worth at least `minBps` of its
 * repayment at an oracle price at most `maxAge` blocks old (ZC_Standard_ERG_SigUSD_GuardedNav_Pricing).
 */
case class SaleGuard(minBps: Long, maxAge: Long)

/**
 * Asks: ZC_Standard_SaleOrder at a fixed price, and ZC_Standard_PricedAsk at a bond's accrued value through
 * ZC_Standard_AccruedValue_Pricing. Either sells any token; a NAV ask sells a bond unit or shares of one.
 */
trait StandardAsks extends AppBase { self: StandardApp =>

  import StandardProtocol._

  def asks(): Seq[StdAsk] =
    (decoded(nodeApi.unspentByTemplateHash(Base16.encode(protocol.askTemplateHash))) ++
      decoded(nodeApi.unspentByTemplateHash(Base16.encode(protocol.pricedAskTemplateHash)))).flatMap(protocol.asAsk)

  /** A bond asset and how many raw units make one bond: the unit itself (1), or a share (N of its vault). */
  def bondOf(token: ErgoId): Option[(ErgoId, Long)] =
    if (assetMarket(token).isDefined) Some(token -> 1L)
    else shareVault(token).map(v => v.bondAsset -> v.n)

  /** The live bond a NAV ask prices, which its expression reads as a data input. */
  def pricedBond(ask: StdAsk): StdBond = {
    val asset = ask.pricedAsset.getOrElse(fail("the ask names no bond"))
    decoded(nodeApi.unspentByTokenId(asset.toString)).flatMap(protocol.asBond).find(_.asset == asset)
      .getOrElse(fail(s"bond $asset is not live: a NAV ask sells only while its bond is"))
  }

  /** What a NAV ask asks for `units` at height `at`: accrued value times units over N, plus the margin. */
  def navOwed(ask: StdAsk, bond: StdBond, units: Long, at: Long): BigInt = {
    val term = bond.maturity - bond.start
    val elapsed = at - bond.start
    if (term <= 0L || elapsed < 0L || elapsed >= term) fail("the bond is past maturity, or not started: a NAV ask cannot price it")
    val accrued = BigInt(bond.principal) * (term - elapsed) + BigInt(bond.repayment) * elapsed
    val num = BigInt(units) * accrued * (10000 + ask.margin)
    val den = BigInt(ask.wholeUnits) * term * 10000
    (num + den - 1) / den
  }

  /**
   * What a buyer pays now for `units`, with the bond's state box for a NAV ask. A NAV price rises each block,
   * so it is priced a few blocks ahead to survive the mempool; overpaying reaches the seller.
   */
  def askPrice(ask: StdAsk, units: Long): (Long, Option[StdBond]) =
    if (!ask.priced) (ask.fixedPrice(units).toLong, None)
    else {
      val bond = pricedBond(ask)
      protocol.pricingOf(ask) // Refuse unknown policies rather than applying an unrelated formula.
      val at = height.toLong + 3L
      val raw = if (!protocol.yieldAsk(ask)) navOwed(ask, bond, units, at) else {
        if (ask.terms.size < 7 || ask.terms(6) <= -10000L || ask.terms(6) > 1000000L || ask.terms(4) < 0L || bond.maturity <= at || at < bond.start || ask.wholeUnits < 1L || ask.wholeUnits > 1000000000L)
          fail("invalid yield pricing terms or no blocks left to maturity")
        val num = BigInt(bond.repayment) * units * 2628000000L * (10000L + ask.terms(6))
        val den = BigInt(ask.wholeUnits) * (BigInt(2628000000L) + BigInt(ask.terms(4)) * (bond.maturity - at)) * 10000L
        (num + den - 1) / den
      }
      val owed = raw.max(ask.fixedPrice(units))
      if (!owed.isValidLong || owed <= 0) fail("the ask price is outside the supported amount range")
      (owed.toLong, Some(bond))
    }

  // ═══ SELL ═════════════════════════════════════════════════════════════════

  /**
   * Put `amount` raw units of a token this wallet holds up for sale, paid in `market`'s asset. A NAV ask needs
   * the token to be a standard bond unit or its shares, and is paid in that bond's own asset.
   */
  def sell(tokenId: String, amount: Long, market: Market, price: AskPrice, minFillAsked: Option[Long], expiry: Long,
           refundFee: Long, guard: Option[SaleGuard] = None): Prepared = {
    val token = Try(ErgoId.create(tokenId)).getOrElse(fail(s"$tokenId is not a token id"))
    val held = walletBoxes.flatMap(_.tokens).filter(_.id == token).map(_.amount).sum
    if (amount < 1L || amount > held) fail(s"this wallet holds $held of $tokenId; sell 1 to $held")
    // zcs1: shares sell in lots that pay at least one raw unit of the repayment, unless the seller says otherwise.
    val lot = shareLot(token)
    val minFill = minFillAsked.getOrElse(lot.getOrElse(1L))
    if (minFill < 1L) fail("the minimum fill must be at least 1")
    val lotNotes = lot.filter(l => amount < l || minFill < l).map(l =>
      s"WARNING: fills under $l shares claim less than one raw unit of the bond's repayment, which rounds to nothing").toSeq
    if (expiry <= height.toLong + 1L) fail("the ask's expiry must be above the current height")
    if (refundFee < 0L) fail("the refund fee cannot be negative")
    val seller = bytesValue(hashOf(myTree))
    guard.foreach { g =>
      if (g.minBps <= 0L) fail("the sale guard's minimum cover must be positive")
      if (g.maxAge < 1L || g.maxAge > 100000L) fail("the sale guard's oracle age must be 1 to 100000 blocks")
    }
    // A NAV ask over a standard bond: plain accrued-value pricing, or with a sale guard the guarded expression,
    // with NAV guards at R5(6..7), or yield margin at R5(6) and guards at R5(7..8).
    def pricedAsk(num: Long, den: Long, margin: Long, yieldBased: Boolean = false, priceMargin: Long = 0L): (Contract, Seq[ErgoValue[_]]) = {
      if (num <= 0L || den <= 0L) fail("the price floor must be positive")
      val (asset, whole) = bondOf(token).getOrElse(fail(s"$tokenId is not a standard bond unit or its shares"))
      val bondMarket = assetMarket(asset).getOrElse(fail(s"bond $asset was not minted by a standard order"))
      if (bondMarket != market) fail(s"a NAV ask or a guarded ask is paid in its bond's asset: use --market $bondMarket")
      val c = protocol.market(market)
      val pricing = if (yieldBased) { if (guard.isDefined) c.guardedYieldPricing else c.yieldPricing }
        else if (guard.isDefined) c.guardedPricing else c.pricing
      (protocol.market(market).pricedAsk,
        Seq(seller, longsValue(Seq(num, den, minFill, expiry, margin, whole) ++ (if (yieldBased) Seq(priceMargin) else Seq.empty) ++ guard.toSeq.flatMap(g => Seq(g.minBps, g.maxAge)): _*),
          bytesValue(pricing.hashedValueBytes), bytesValue(asset.getBytes)))
    }
    val (script, regs, note) = price match {
      case FixedPrice(total) if guard.isEmpty =>
        if (total <= 0L) fail("the price must be positive")
        (protocol.market(market).saleOrder, Seq(seller, longsValue(total, amount, minFill, expiry)),
          s"price: ${Units.amount(total, market)} for all $amount units")
      case FixedPrice(total) =>
        if (total <= 0L) fail("the price must be positive")
        // A guarded fixed price: a NAV ask at -99.99%, so its floor, the fixed price, is what a buyer pays.
        val (script, regs) = pricedAsk(total, amount, GuardedFixedMargin)
        (script, regs, s"price: ${Units.amount(total, market)} for all $amount units (a NAV ask whose floor is the price)")
      case NavPrice(margin, floor) =>
        if (margin <= -10000L || margin > 1000000L) fail("the margin must be above -100% and at most 10000%")
        val (num, den) = floor.fold((1L, 1000000000000L))(t => (t, amount))
        val (script, regs) = pricedAsk(num, den, margin)
        val whole = bondOf(token).fold(1L)(_._2)
        (script, regs, s"price: the bond's accrued value ${if (margin >= 0) "plus" else "minus"} ${Units.percent(margin.abs)}, per " +
          s"$whole unit(s) of a whole bond" + floor.fold("")(f => s", never under ${Units.amount(f, market)} for all"))
      case YieldPrice(rate, floor, margin) =>
        if (rate < 0L) fail("the yield cannot be negative")
        val (num, den) = floor.fold((1L, 1000000000000L))(t => (t, amount))
        if (margin <= -10000L || margin > 1000000L) fail("the margin must be above -100% and at most 10000%")
        val (script, regs) = pricedAsk(num, den, rate, yieldBased = true, priceMargin = margin)
        (script, regs, s"price: ${Units.percent(rate)} reference simple buyer yield per 262800 blocks, price margin ${Units.percent(margin)}, excluding fees" +
          floor.fold("")(f => s", never under ${Units.amount(f, market)} for all"))
    }
    val guardNote = guard.map(g => s"sale guard: it sells only while the bond's collateral is worth at least " +
      s"${Units.percent(g.minBps)} of its repayment at an oracle price at most ${g.maxAge} blocks old; below that, " +
      "anyone may liquidate the bond and you claim its collateral with the unit").toSeq
    val ask = UTXO(script, MinBox + refundFee, Seq(Token(token, amount)), regs)
    prepare(s"Sell $amount of $tokenId", plan(Seq.empty, Seq(ask)), Seq(note,
      s"paid in $market; minimum fill $minFill; expires at height $expiry, then anyone may return it for the " +
        s"${Fmt.erg(refundFee)} refund fee",
      "Output 0 is the ask. Cancel it with `cancel-ask <its box id>`.") ++ guardNote ++ lotNotes)
  }

  /**
   * Why a guarded ask's expression would refuse a sale now, or None when it has no guard or would pass: the
   * confirmed oracle must be fresh enough for the ask, and the bond's cover at least the seller's minimum.
   */
  private[standard] def saleGuardRefusal(ask: StdAsk, bond: StdBond, oracle: Option[OracleState]): Option[String] =
    protocol.saleGuard(ask).flatMap { case (minBps, maxAge) =>
      oracle match {
        case None => Some("a guarded ask sells only with the USD oracle pool as a data input, and the node's index has " +
          "no single pool box")
        case Some(o) if height.toLong + 1L - o.created > maxAge => Some(s"the oracle price is older than the ask's $maxAge blocks")
        case Some(o) if !protocol.cover(bond, o.price).exists { case (c, d) => c * 10000 >= d * minBps } =>
          Some(s"the bond is at ${protocol.ratioBps(bond, o.price).fold("?")(Units.percent)} of its repayment, under " +
            s"the seller's ${Units.percent(minBps)} sale guard")
        case _ => None
      }
    }

  /**
   * zcs1's least lot of a bond's shares: ceil(N / R) raw shares claim at least one raw unit of the repayment
   * R. None for a token that is not shares of a standard bond.
   */
  def shareLot(token: ErgoId): Option[Long] =
    bondOf(token).filter(_._2 > 1L).flatMap { case (asset, n) =>
      bondRecord(asset).filter(_.repayment > 0L).map(b => math.max(1L, (n + b.repayment - 1L) / b.repayment))
    }

  /**
   * A price as a percentage of face, in basis points: `price` for `units` of a token that is `whole` units of
   * a bond repaying R is worth price * whole / (units * R). None when the token is not a standard bond's.
   */
  def faceBps(token: ErgoId, price: Long, units: Long): Option[Long] =
    bondOf(token).flatMap { case (asset, whole) =>
      bondRecord(asset).filter(b => b.repayment > 0L && units > 0L)
        .map(b => (BigInt(price) * whole * 10000 / (BigInt(units) * b.repayment)).toLong)
    }

  // ═══ BUY, CANCEL, REFUND ══════════════════════════════════════════════════

  /** The ask convention's payout: at the seller's script, R4 the ask's id, R5 the units it pays for. */
  private[standard] def askPayout(ask: StdAsk, units: Long, paid: Long): UTXO = {
    val seller = ownerTree(ask.sellerHash, ask.box, "seller")
    val regs = Seq(bytesValue(ask.box.id.getBytes), ErgoValue.of(units))
    ask.market.token match {
      case None => UTXO(Contract(seller), math.max(paid, MinBox), Seq.empty, regs)
      case Some(id) => UTXO(Contract(seller), MinBox, Seq(Token(id, paid)), regs)
    }
  }

  /** The ask's continuation with what is left, or None when the fill takes everything. */
  private[standard] def askRest(ask: StdAsk, units: Long): Option[UTXO] = {
    val left = ask.token.amount - units
    if (left <= 0L) None
    else Some(UTXO(Contract(ask.box.input.getErgoTree), ask.box.value, Seq(Token(ask.token.id, left)), ask.box.registers))
  }

  /** The ask's context vars: 0 the payout, 1 the continuation, and for a NAV ask 2 its expression, 3 the state. */
  private[standard] def askInput(ask: StdAsk, payoutIndex: Int, restIndex: Option[Int]): InputUTXO = {
    val base = restIndex.foldLeft(ask.box.withCtxVar(0.toByte, intValue(payoutIndex)))((b, i) => b.withCtxVar(1.toByte, intValue(i)))
    if (!ask.priced) base
    else base.withCtxVar(2.toByte, bytesValue(protocol.pricingOf(ask).valueBytes)).withCtxVar(3.toByte, intValue(0))
  }

  private[standard] def checkFill(ask: StdAsk, units: Long): Unit = {
    if (height.toLong + 1L >= ask.expiry) fail(s"the ask expired at height ${ask.expiry}")
    if (units < 1L || units > ask.token.amount) fail(s"the ask holds ${ask.token.amount}; buy 1 to ${ask.token.amount}")
    if (units < ask.minFill && units != ask.token.amount)
      fail(s"the ask's minimum fill is ${ask.minFill} unless a fill takes all ${ask.token.amount}")
  }

  /**
   * Buy `units` (default all) from an ask with this wallet: the units arrive as change. zcs1: a claim on a live
   * bond under its own oracle ratio is not bought unless `allowLiquidatable`; liquidating it serves the seller.
   */
  def buy(askId: String, units: Option[Long], allowLiquidatable: Boolean = false): Prepared = {
    val ask = protocol.asAsk(unspent(askId)).getOrElse(fail(s"$askId is not a ZC Standard ask"))
    val n = units.getOrElse(ask.token.amount)
    checkFill(ask, n)
    val coverNotes = liveBondOf(ask.token.id).toSeq.flatMap(b => coverCheck(b, allowLiquidatable, s"bond ${b.box.id} is",
      s"Liquidate it instead (`liquidate ${b.box.id}`): its holder, the seller, then claims all the collateral"))
    val (paid, bond) = askPrice(ask, n)
    // A guarded ask's expression reads the confirmed oracle after the bond; refuse here what it would refuse.
    val guardOracle = if (protocol.saleGuard(ask).isEmpty) None else Try(oracle()).toOption.flatten.map(readableOracle)
    bond.flatMap(saleGuardRefusal(ask, _, guardOracle)).foreach(r => fail(s"the ask's sale guard refuses this sale: $r"))
    val rest = askRest(ask, n)
    val outputs = askPayout(ask, n, paid) +: rest.toSeq
    prepare(s"Buy $n of ${ask.token.id}", plan(Seq(askInput(ask, 0, rest.map(_ => 1))), outputs,
      dataInputs = bond.map(_.box).toSeq ++ guardOracle.map(_.box)),
      s"pay ${Units.amount(paid, ask.market)} to the seller for $n of ${ask.token.id}" +: coverNotes)
  }

  /** The live bond behind a bond unit or its shares, or None for any other token or a settled bond. */
  private[standard] def liveBondOf(token: ErgoId): Option[StdBond] =
    Try(bondOf(token)).toOption.flatten.flatMap { case (asset, _) =>
      decoded(nodeApi.unspentByTokenId(asset.toString)).flatMap(protocol.asBond).find(_.asset == asset)
    }

  def cancelAsk(askId: String): Prepared = {
    val ask = protocol.asAsk(unspent(askId)).getOrElse(fail(s"$askId is not a ZC Standard ask"))
    prepare(s"Cancel ask $askId", sweep(Seq(ask.box, walletBoxAt(ask.sellerHash, "seller"))))
  }

  /** From an ask's expiry on, anyone returns its tokens to the seller and keeps the nanoERG above 0.001 ERG. */
  def refundAsk(askId: String): Prepared = {
    val ask = protocol.asAsk(unspent(askId)).getOrElse(fail(s"$askId is not a ZC Standard ask"))
    packed(s"Refund ask $askId", Seq(askRefund(ask)))
  }
}
