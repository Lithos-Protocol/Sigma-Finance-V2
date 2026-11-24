package org.lithosprotocol.sigmafi
package standard

import org.lithosprotocol.sigmafi.app.Protocol._
import org.lithosprotocol.sigmafi.app._
import org.ergoplatform.appkit.ErgoValue
import org.ergoplatform.sdk.ErgoId
import scorex.util.encode.Base16
import org.lithosprotocol.sigmafi.mutations.{Contract, InputUTXO, Token, UTXO}

import scala.util.Try

/** How an ask is priced: a fixed total, or a buyer's simple block yield with an optional floor. */
sealed trait AskPrice
/** `total` raw units of the market's asset for all the ask's units: the price per unit is total / amount. */
case class FixedPrice(total: Long) extends AskPrice
/**
 * A buyer's simple yield to maturity, bps per 262800 blocks, moving in a straight line from `startBps` now to
 * `endBps` (default `startBps`) at `endHeight` (default the ask's expiry), then holding. `floorTotal`, when
 * given, is the least the whole amount may sell for.
 */
case class YieldPrice(startBps: Long, floorTotal: Option[Long] = None, endBps: Option[Long] = None,
                      endHeight: Option[Long] = None) extends AskPrice

/**
 * A seller's sale guard: the ask sells only while the bond's collateral is worth at least `minBps` of its
 * repayment at an oracle price at most `maxAge` blocks old (ZC_Standard_ERG_SigUSD_GuardedYield_Pricing).
 */
case class SaleGuard(minBps: Long, maxAge: Long)

/**
 * Asks: ZC_Standard_SaleOrder at a fixed price, and ZC_Standard_PricedAsk at a buyer's yield through
 * ZC_Standard_YieldAsk_Pricing. The sale order sells any token; a yield ask sells a bond unit or shares of one.
 */
trait StandardAsks extends AppBase { self: StandardApp =>

  import StandardProtocol._

  def asks(): Seq[StdAsk] =
    (decoded(nodeApi.unspentByTemplateHash(Base16.encode(protocol.askTemplateHash))) ++
      decoded(nodeApi.unspentByTemplateHash(Base16.encode(protocol.pricedAskTemplateHash)))).flatMap(protocol.asAsk)

  /**
   * A bond asset and how many raw units make one bond: the unit itself (1), or a share (N of its vault). Only
   * genuine ones: a unit minted by a standard order, or a share minted by a standard share mint.
   */
  def bondOf(token: ErgoId): Option[(ErgoId, Long)] =
    if (assetMarket(token).isDefined) Some(token -> 1L)
    else verifiedShareVault(token).map(v => v.bondAsset -> v.n)

  /** A token some share vault names a bond for, though no standard share mint minted it: the vault proves nothing. */
  def unverifiedShares(token: ErgoId): Boolean =
    assetMarket(token).isEmpty && shareVault(token).exists(v => !shareVerified(v))

  /** The live bond a yield ask prices, which its expression reads as a data input. */
  def pricedBond(ask: StdAsk): StdBond = {
    val asset = ask.pricedAsset.getOrElse(fail("the ask names no bond"))
    decoded(nodeApi.unspentByTokenId(asset.toString)).flatMap(protocol.asBond).find(_.asset == asset)
      .getOrElse(fail(s"bond $asset is not live: a yield ask sells only while its bond is"))
  }

  /**
   * What a buyer pays now for `units`, with the bond's state box for a yield ask. The price moves each block,
   * so it is the most over the next few blocks, to survive the mempool; overpaying reaches the seller.
   */
  def askPrice(ask: StdAsk, units: Long): (Long, Option[StdBond]) =
    if (!ask.priced) {
      val owed = ask.fixedPrice(units)
      if (!owed.isValidLong || owed <= 0) fail("the ask price is outside the supported amount range")
      (owed.toLong, None)
    } else {
      val bond = pricedBond(ask)
      protocol.pricingOf(ask) // Refuse unknown policies rather than applying an unrelated formula.
      val line = ask.yieldLine.filter(_.valid).getOrElse(fail("invalid yield pricing terms"))
      if (ask.wholeUnits < 1L || ask.wholeUnits > 1000000000L) fail("invalid units per bond")
      val window = (0L to QuoteBlocks).map(height.toLong + _)
      if (bond.maturity <= window.last) fail("no blocks left to maturity")
      val raw = window.map(at => line.price(bond.repayment, units, ask.wholeUnits, bond.maturity - at, at)).max
      val owed = raw.max(ask.fixedPrice(units))
      if (!owed.isValidLong || owed <= 0) fail("the ask price is outside the supported amount range")
      (owed.toLong, Some(bond))
    }

  // ═══ SELL ═════════════════════════════════════════════════════════════════

  /**
   * Put `amount` raw units of a token this wallet holds up for sale, paid in `market`'s asset. A yield ask needs
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
    // A yield ask over a standard bond: R5 = (floor numerator, floor denominator, minimum fill, expiry, units per
    // bond, start height, start yield, end height, end yield), then a sale guard's (minimum cover, oracle age).
    def pricedAsk(num: Long, den: Long, line: YieldLine): (Contract, Seq[ErgoValue[_]]) = {
      if (num <= 0L || den <= 0L) fail("the price floor must be positive")
      if (!line.valid) fail("the yield cannot be negative, and its end height must be above the current height")
      val (asset, whole) = bondOf(token).getOrElse(fail(s"$tokenId is not a standard bond unit or its shares"))
      val bondMarket = assetMarket(asset).getOrElse(fail(s"bond $asset was not minted by a standard order"))
      if (bondMarket != market) fail(s"a yield ask or a guarded ask is paid in its bond's asset: use --market $bondMarket")
      val c = protocol.market(market)
      val pricing = if (guard.isDefined) c.guardedYieldPricing else c.yieldPricing
      (c.pricedAsk,
        Seq(seller, longsValue(Seq(num, den, minFill, expiry, whole) ++ line.terms ++ guard.toSeq.flatMap(g => Seq(g.minBps, g.maxAge)): _*),
          bytesValue(pricing.hashedValueBytes), bytesValue(asset.getBytes)))
    }
    val (script, regs, note) = price match {
      case FixedPrice(total) if guard.isEmpty =>
        if (total <= 0L) fail("the price must be positive")
        (protocol.market(market).saleOrder, Seq(seller, longsValue(total, amount, minFill, expiry)),
          s"price: ${Units.amount(total, market)} for all $amount units")
      case FixedPrice(total) =>
        if (total <= 0L) fail("the price must be positive")
        // A guarded fixed price: a yield so large the yield price is next to nothing, so the floor is the price.
        val (script, regs) = pricedAsk(total, amount, YieldLine(height.toLong, GuardedFixedYield, expiry, GuardedFixedYield))
        (script, regs, s"price: ${Units.amount(total, market)} for all $amount units (a guarded yield ask whose floor is the price)")
      case YieldPrice(startBps, floor, endBps, endHeight) =>
        val line = YieldLine(height.toLong, startBps, endHeight.getOrElse(expiry), endBps.getOrElse(startBps))
        val (num, den) = floor.fold((1L, 1000000000000L))(t => (t, amount))
        val (script, regs) = pricedAsk(num, den, line)
        val rate = if (line.fixed) s"${Units.percent(line.startBps)} simple buyer yield per 262800 blocks"
          else s"a simple buyer yield per 262800 blocks moving in a straight line from ${Units.percent(line.startBps)} " +
            s"now to ${Units.percent(line.endBps)} at height ${line.end}"
        (script, regs, s"price: $rate to the bond's maturity, excluding fees" +
          floor.fold("")(f => s", never under ${Units.amount(f, market)} for all"))
    }
    val guardNote = guard.map(g => s"sale guard: it sells only while the bond's collateral is worth at least " +
      s"${Units.percent(g.minBps)} of its repayment at an oracle price at most ${g.maxAge} blocks old; below that, " +
      "anyone may liquidate the bond and you claim its collateral with the unit").toSeq
    val ask = UTXO(script, addAmount(MinBox, refundFee), Seq(Token(token, amount)), regs)
    val feeWarnings =
      (if (refundFee > 20000000L)
        Seq(s"WARNING: refund fee ${Fmt.erg(refundFee)} is large; the ask's box ERG (${Fmt.erg(MinBox + refundFee)}) " +
          "is a seller-paid closing/expiry fee and is not returned on a full fill")
      else Seq.empty) ++
      (price match {
        case FixedPrice(total) if market.token.isEmpty && total <= MinBox + refundFee =>
          Seq(s"WARNING: total price ${Units.amount(total, market)} is at or below the ask box's ${Fmt.erg(MinBox + refundFee)}; " +
            "on a full fill the box's ERG counts toward the payout, so a buyer pays little or no additional ERG")
        case _ => Seq.empty
      })
    prepare(s"Sell $amount of $tokenId", plan(Seq.empty, Seq(ask)), Seq(note,
      s"paid in $market; minimum fill $minFill; expires at height $expiry, then anyone may return it for the " +
        s"${Fmt.erg(refundFee)} refund fee (on a full fill, the ask box's ${Fmt.erg(MinBox + refundFee)} is not returned separately)",
      "Output 0 is the ask. Cancel it with `cancel-ask <its box id>`.") ++ guardNote ++ lotNotes ++ feeWarnings)
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
      bondRecord(asset).filter(_.repayment > 0L)
        .map(b => ((BigInt(n) + b.repayment - 1) / b.repayment).min(BigInt(Long.MaxValue)).toLong.max(1L))
    }

  /**
   * A price as a percentage of face, in basis points: `price` for `units` of a token that is `whole` units of
   * a bond repaying R is worth price * whole / (units * R). None when the token is not a standard bond's.
   */
  def faceBps(token: ErgoId, price: Long, units: Long): Option[Long] =
    bondOf(token).flatMap { case (asset, whole) =>
      bondRecord(asset).filter(b => b.repayment > 0L && units > 0L)
        .map(b => (BigInt(price) * whole * 10000 / (BigInt(units) * b.repayment)).min(BigInt(Long.MaxValue)).toLong)
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
    else Some(UTXO(Contract(ask.box.input.getErgoTree), ask.box.value, Seq(Token(ask.token.id, left)), ask.box.registers)
      .withRegNum(if (ask.priced) 8 else 6, bytesValue(ask.box.id.getBytes)))
  }

  /** The ask's context vars: 0 the payout, 1 the continuation, and for a yield ask 2 its expression, 3 the state. */
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
    if (unverifiedShares(ask.token.id))
      fail(s"${ask.token.id} sits in a share vault that names a bond, but no standard share mint minted it: anyone can " +
        "make such a vault, so it claims nothing. This client does not buy it")
    val live = liveBondOf(ask.token.id)
    if (verifiedShareVault(ask.token.id).exists(_.dutch) && live.exists(_.collateral.size > MaxCollateralTokens))
      fail(s"bond ${live.get.asset} carries ${live.get.collateral.size} distinct collateral tokens (over the " +
        s"$MaxCollateralTokens-token limit for Dutch/Selective share vaults to pull under Ergo's 4,096-byte box limit); " +
        "this client does not buy its shares")
    val coverNotes = live.toSeq.flatMap(b => coverCheck(b, allowLiquidatable, s"bond ${b.box.id} is",
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

  // ═══ LOT AUCTIONS ═════════════════════════════════════════════════════════

  def lots(): Seq[StdLot] =
    decoded(nodeApi.unspentByTemplateHash(Base16.encode(protocol.lotTemplateHash))).flatMap(protocol.asLot)

  /**
   * Put a basket this wallet holds up for one Dutch auction (ZC_Standard_LotAuction), paid in `market`'s asset:
   * `tokens`, and for a token market `erg` nanoERG beside them. The price is `opening` until `start`, falls in a
   * straight line to `floor` at `end`, and holds there until `expiry`; from then anyone may return the lot for
   * `refundFee`. The lot sells whole, to the first buyer.
   */
  def sellLot(market: Market, tokens: Seq[Token], erg: Long, opening: Long, floor: Long, start: Long, end: Long,
              expiry: Long, refundFee: Long): Prepared = {
    if (tokens.isEmpty && (market.token.isEmpty || erg <= 0L))
      fail("name at least one token to sell" + market.token.fold("")(_ => ", or some ERG"))
    if (tokens.map(_.id).distinct.size != tokens.size) fail("list each token once")
    tokens.foreach { t =>
      val held = walletBoxes.flatMap(_.tokens).filter(_.id == t.id).map(_.amount).sum
      if (t.amount < 1L || t.amount > held) fail(s"this wallet holds $held of ${t.id}; sell 1 to $held")
    }
    market.token.foreach(id => if (tokens.exists(_.id == id)) fail(s"a lot paid in $market cannot hold $market itself"))
    if (market.token.isEmpty && erg != 0L) fail("an ERG lot sells tokens only: its box's nanoERG returns to you with the payment")
    if (erg < 0L) fail("the ERG to sell cannot be negative")
    if (floor < 1L || floor > opening) fail("the lot needs 1 <= floor <= opening")
    if (end <= start) fail("the price must fall over at least one block: the end height must be above the start")
    if (expiry < end) fail("the expiry cannot come before the end of the fall")
    if (expiry <= height.toLong + 1L) fail("the expiry must be above the current height")
    if (refundFee < 0L) fail("the refund fee cannot be negative")
    // A token-currency lot sells its nanoERG too; an ERG lot keeps its box's nanoERG for the seller. The refund
    // fee sits above the box minimum, so an expiry refund still leaves the seller a valid box.
    val value = addAmount(math.max(erg, MinBox), refundFee)
    val box = UTXO(protocol.market(market).lotAuction, value, tokens,
      Seq(bytesValue(hashOf(myTree)), longsValue(opening, floor, start, end, expiry, refundFee)))
    val refundNote =
      if (market.token.isDefined && refundFee > 0L)
        s" (note: on a $market lot, the box's full ${Fmt.erg(value)}, including the refund fee, goes to the buyer if bought)"
      else ""
    prepare("Sell a lot", plan(Seq.empty, Seq(box)), Seq(
      s"lot: ${Fmt.contents(if (market.token.isEmpty) 0L else value, tokens)}",
      s"price in $market: ${Units.amount(opening, market)} until height $start, falling in a straight line to " +
        s"${Units.amount(floor, market)} at height $end, then held there until height $expiry",
      s"sold whole to the first buyer; from height $expiry anyone may return it to you for the ${Fmt.erg(refundFee)} " +
        s"refund fee$refundNote",
      "Output 0 is the lot. Cancel it any time with `cancel-lot <its box id>`."))
  }

  /**
   * Buy a lot with this wallet at the current price: pay the seller and take everything in the box. The price only
   * falls, so a purchase priced at the current height stays valid at any later one before the expiry.
   */
  def takeLot(lotId: String): Prepared = {
    val lot = protocol.asLot(unspent(lotId)).getOrElse(fail(s"$lotId is not a ZC Standard lot auction"))
    if (!lot.valid) fail("the lot's terms are malformed, so only its seller can cancel it")
    if (lot.market.token.exists(id => lot.lot.exists(_.id == id)))
      fail(s"the lot holds ${lot.market} itself, so it cannot be bought; only its seller can cancel it")
    if (height.toLong + 1L >= lot.expiry) fail(s"the lot expired at height ${lot.expiry}")
    val price = lot.price(height.toLong)
    val seller = Contract(ownerTree(lot.sellerHash, lot.box, "seller"))
    val tag = Seq(bytesValue(lot.box.id.getBytes))
    val payout = lot.market.token match {
      case None => UTXO(seller, addAmount(lot.box.value, price), Seq.empty, tag)
      case Some(id) => UTXO(seller, MinBox, Seq(Token(id, price)), tag)
    }
    prepare(s"Take lot $lotId", plan(Seq(lot.box.withCtxVar(0.toByte, intValue(0))), Seq(payout)), Seq(
      s"pay ${Units.amount(price, lot.market)} to the seller" +
        lot.market.token.fold(s" (plus the lot box's ${Fmt.erg(lot.box.value)}, which returns to them)")(_ => ""),
      s"receive ${Fmt.contents(if (lot.market.token.isEmpty) 0L else lot.box.value, lot.lot)}"))
  }

  def cancelLot(lotId: String): Prepared = {
    val lot = protocol.asLot(unspent(lotId)).getOrElse(fail(s"$lotId is not a ZC Standard lot auction"))
    prepare(s"Cancel lot $lotId", sweep(Seq(lot.box, walletBoxAt(lot.sellerHash, "seller"))))
  }

  /** From a lot's expiry on, anyone returns it to the seller and keeps its refund fee. */
  def refundLot(lotId: String): Prepared = {
    val lot = protocol.asLot(unspent(lotId)).getOrElse(fail(s"$lotId is not a ZC Standard lot auction"))
    packed(s"Refund lot $lotId", Seq(lotRefund(lot)))
  }
}
