package ksingh.sigmabonds
package standard

import ksingh.sigmabonds.app.Protocol._
import ksingh.sigmabonds.app._
import org.ergoplatform.appkit.ErgoValue
import org.ergoplatform.sdk.ErgoId
import scorex.util.encode.Base16
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import scala.util.Try

/** How an ask is priced: a fixed total for its units, or accrued value plus a margin with an optional floor. */
sealed trait AskPrice
/** `total` raw units of the market's asset for all the ask's units: the price per unit is total / amount. */
case class FixedPrice(total: Long) extends AskPrice
/** `marginBps` over NAV (signed); `floorTotal`, when given, is the least the whole amount may sell for. */
case class NavPrice(marginBps: Long, floorTotal: Option[Long]) extends AskPrice

/**
 * Asks: ZC_Standard_SaleOrder at a fixed price, and ZC_Standard_NavAsk at a bond's accrued value through
 * ZC_Standard_AccruedValue_Pricing. Either sells any token; a NAV ask sells a bond unit or shares of one.
 */
trait StandardAsks extends AppBase { self: StandardApp =>

  import StandardProtocol._

  def asks(): Seq[StdAsk] =
    (decoded(nodeApi.unspentByTemplateHash(Base16.encode(protocol.askTemplateHash))) ++
      decoded(nodeApi.unspentByTemplateHash(Base16.encode(protocol.navAskTemplateHash)))).flatMap(protocol.asAsk)

  /** A bond asset and how many raw units make one bond: the unit itself (1), or a share (N of its vault). */
  def bondOf(token: ErgoId): Option[(ErgoId, Long)] =
    if (assetMarket(token).isDefined) Some(token -> 1L)
    else shareVault(token).map(v => v.bondAsset -> v.n)

  /** The live bond a NAV ask prices, which its expression reads as a data input. */
  def navBond(ask: StdAsk): StdBond = {
    val asset = ask.navAsset.getOrElse(fail("the ask names no bond"))
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
    if (!ask.nav) (ask.fixedPrice(units).toLong, None)
    else {
      val bond = navBond(ask)
      val owed = navOwed(ask, bond, units, height.toLong + 3L).max(ask.fixedPrice(units))
      (owed.toLong, Some(bond))
    }

  // ═══ SELL ═════════════════════════════════════════════════════════════════

  /**
   * Put `amount` raw units of a token this wallet holds up for sale, paid in `market`'s asset. A NAV ask needs
   * the token to be a standard bond unit or its shares, and is paid in that bond's own asset.
   */
  def sell(tokenId: String, amount: Long, market: Market, price: AskPrice, minFill: Long, expiry: Long,
           refundFee: Long): Prepared = {
    val token = Try(ErgoId.create(tokenId)).getOrElse(fail(s"$tokenId is not a token id"))
    val held = walletBoxes.flatMap(_.tokens).filter(_.id == token).map(_.amount).sum
    if (amount < 1L || amount > held) fail(s"this wallet holds $held of $tokenId; sell 1 to $held")
    if (minFill < 1L) fail("the minimum fill must be at least 1")
    if (expiry <= height.toLong + 1L) fail("the ask's expiry must be above the current height")
    if (refundFee < 0L) fail("the refund fee cannot be negative")
    val seller = bytesValue(hashOf(myTree))
    val (script, regs, note) = price match {
      case FixedPrice(total) =>
        if (total <= 0L) fail("the price must be positive")
        (protocol.market(market).saleOrder, Seq(seller, longsValue(total, amount, minFill, expiry)),
          s"price: ${Units.amount(total, market)} for all $amount units")
      case NavPrice(margin, floor) =>
        if (margin <= -10000L || margin > 1000000L) fail("the margin must be above -100% and at most 10000%")
        val (asset, whole) = bondOf(token).getOrElse(fail(s"$tokenId is not a standard bond unit or its shares"))
        val bondMarket = assetMarket(asset).getOrElse(fail(s"bond $asset was not minted by a standard order"))
        if (bondMarket != market) fail(s"a NAV ask is paid in its bond's asset: use --market $bondMarket")
        val (num, den) = floor.fold((1L, 1000000000000L))(t => (t, amount))
        (protocol.market(market).navAsk,
          Seq(seller, longsValue(num, den, minFill, expiry, margin, whole), bytesValue(protocol.market(market).pricing.hashedValueBytes),
            bytesValue(asset.getBytes)),
          s"price: the bond's accrued value ${if (margin >= 0) "plus" else "minus"} ${Units.percent(margin.abs)}, per " +
            s"$whole unit(s) of a whole bond" + floor.fold("")(f => s", never under ${Units.amount(f, market)} for all"))
    }
    val ask = UTXO(script, MinBox + refundFee, Seq(Token(token, amount)), regs)
    prepare(s"Sell $amount of $tokenId", plan(Seq.empty, Seq(ask)), Seq(note,
      s"paid in $market; minimum fill $minFill; expires at height $expiry, then anyone may return it for the " +
        s"${Fmt.erg(refundFee)} refund fee",
      "Output 0 is the ask. Cancel it with `cancel-ask <its box id>`."))
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
    if (!ask.nav) base
    else base.withCtxVar(2.toByte, bytesValue(protocol.market(ask.market).pricing.valueBytes)).withCtxVar(3.toByte, intValue(0))
  }

  private[standard] def checkFill(ask: StdAsk, units: Long): Unit = {
    if (height.toLong + 1L >= ask.expiry) fail(s"the ask expired at height ${ask.expiry}")
    if (units < 1L || units > ask.token.amount) fail(s"the ask holds ${ask.token.amount}; buy 1 to ${ask.token.amount}")
    if (units < ask.minFill && units != ask.token.amount)
      fail(s"the ask's minimum fill is ${ask.minFill} unless a fill takes all ${ask.token.amount}")
  }

  /** Buy `units` (default all) from an ask with this wallet: the units arrive as change. */
  def buy(askId: String, units: Option[Long]): Prepared = {
    val ask = protocol.asAsk(unspent(askId)).getOrElse(fail(s"$askId is not a ZC Standard ask"))
    val n = units.getOrElse(ask.token.amount)
    checkFill(ask, n)
    val (paid, bond) = askPrice(ask, n)
    val rest = askRest(ask, n)
    val outputs = askPayout(ask, n, paid) +: rest.toSeq
    prepare(s"Buy $n of ${ask.token.id}", plan(Seq(askInput(ask, 0, rest.map(_ => 1))), outputs,
      dataInputs = bond.map(_.box).toSeq),
      Seq(s"pay ${Units.amount(paid, ask.market)} to the seller for $n of ${ask.token.id}"))
  }

  def cancelAsk(askId: String): Prepared = {
    val ask = protocol.asAsk(unspent(askId)).getOrElse(fail(s"$askId is not a ZC Standard ask"))
    prepare(s"Cancel ask $askId", sweep(Seq(ask.box, walletBoxAt(ask.sellerHash, "seller"))))
  }

  /** From an ask's expiry on, anyone returns its tokens to the seller and keeps the nanoERG above 0.001 ERG. */
  def refundAsk(askId: String): Prepared = {
    val ask = protocol.asAsk(unspent(askId)).getOrElse(fail(s"$askId is not a ZC Standard ask"))
    if (height.toLong + 1L < ask.expiry) fail(s"the ask expires at height ${ask.expiry}; it can be refunded from then on")
    val refund = UTXO(Contract(ownerTree(ask.sellerHash, ask.box, "seller")), math.min(MinBox, ask.box.value),
      Seq(ask.token), Seq(bytesValue(ask.box.id.getBytes)))
    prepare(s"Refund ask $askId", plan(Seq(ask.box.withCtxVar(0.toByte, intValue(0))), Seq(refund)),
      Seq(s"returns ${ask.token.amount} of ${ask.token.id} to the seller; you keep ${Fmt.erg(ask.box.value - refund.value)}, " +
        "less the miner fee"))
  }
}
