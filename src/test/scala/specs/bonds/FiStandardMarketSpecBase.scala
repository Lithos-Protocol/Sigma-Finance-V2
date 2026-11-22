package org.lithosprotocol.sigmafi
package specs.bonds

import ksingh.sigmabonds.app.Protocol
import ksingh.sigmabonds.client.ScriptGenerator
import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import sigma.Colls
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

/**
 * FI_Standard_Order, FI_Standard_LendOffer, FI_Standard_ERG_SigUSD_CollateralPolicy and
 * FI_Standard_ERG_SigUSD_FillGuard, compiled from their files per repayment asset against the FI bonds of
 * [[FiStandardSpecBase]]: the order pins its market's FI bond tree, the offer that order and bond, the
 * policy FI_Standard_Liquidation; the guard pins only its asset.
 */
object FiStandardMarketSpecBase {
  val OrderPath = "bonds/fi/FI_Standard_Order"
  val OfferPath = "bonds/fi/FI_Standard_LendOffer"
  val PolicyPath = "bonds/fi/FI_Standard_ERG_SigUSD_CollateralPolicy"
  val GuardPath = "bonds/fi/FI_Standard_ERG_SigUSD_FillGuard"

  case class Market(order: Contract, offer: Contract, policy: Contract, guard: Contract)

  /** `swappedBond`: the ERG FI bond tree compiled with another covenant expression, which no order pins. */
  case class Markets(erg: Market, token: Market, swappedBond: Contract)

  private var cache: Option[Markets] = None

  /**
   * The FI order compiled from its file with `edits` applied, pinning `bond`, the repayment asset `token` and
   * FI_Standard_Liquidation as the only liquidation expression an order may name.
   */
  def orderFor(ctx: BlockchainContext, bond: Contract, token: Array[Byte], edits: (String, String)*): Contract =
    FiStandardSpecBase.synchronized {
      Contract.fromErgoContract(ctx.compileContract(ConstantsBuilder.create()
        .item("CONST_BOND_HASH", Colls.fromArray(bond.hashedPropBytes))
        .item("CONST_REPAYMENT_TOKEN", Colls.fromArray(token))
        .item("CONST_LIQUIDATION_HASH", Colls.fromArray(FiStandardSpecBase.compiled(ctx).payout.hashedValueBytes)).build(),
        FiStandardSpecBase.edited(ScriptGenerator.mkScript(OrderPath), edits)))
    }

  /** The FI collateral policy for the repayment asset `token`, compiled from its file with `edits` applied. */
  def policyFor(ctx: BlockchainContext, token: Array[Byte], edits: (String, String)*): Contract =
    FiStandardSpecBase.synchronized {
      Contract.fromErgoContract(ctx.compileContract(ConstantsBuilder.create()
        .item("CONST_REPAYMENT_TOKEN", Colls.fromArray(token))
        .item("CONST_LIQUIDATION_HASH", Colls.fromArray(FiStandardSpecBase.compiled(ctx).payout.hashedValueBytes)).build(),
        FiStandardSpecBase.edited(ScriptGenerator.mkScript(PolicyPath), edits)))
    }

  /** The FI share mint of the repayment asset `token`: an offer refuses an owner hash naming it. */
  def mintOf(ctx: BlockchainContext, token: Array[Byte]): Contract = {
    val s = FiStandardSharesSpecBase.compiled(ctx)
    if (token.isEmpty) s.ergMint else s.mint
  }

  /** The FI lend offer compiled from its file with `edits` applied, pinning `order`, `bond` and its market's share mint. */
  def offerFor(ctx: BlockchainContext, token: Array[Byte], order: Contract, bond: Contract,
               edits: (String, String)*): Contract = {
    val mint = mintOf(ctx, token)
    FiStandardSpecBase.synchronized {
      Contract.fromErgoContract(ctx.compileContract(ConstantsBuilder.create()
        .item("CONST_REPAYMENT_TOKEN", Colls.fromArray(token))
        .item("CONST_ORDER_HASH", Colls.fromArray(order.hashedPropBytes))
        .item("CONST_BOND_HASH", Colls.fromArray(bond.hashedPropBytes))
        .item("CONST_MINT_HASH", Colls.fromArray(mint.hashedPropBytes)).build(),
        FiStandardSpecBase.edited(ScriptGenerator.mkScript(OfferPath), edits)))
    }
  }

  /** Compiled once per JVM; the sigma compiler mutates shared AST nodes. */
  def compiled(ctx: BlockchainContext): Markets = synchronized {
    cache.getOrElse {
      val f = FiStandardSpecBase.compiled(ctx)
      def build(path: String, terms: (String, Any)*): Contract = {
        val constants = terms.foldLeft(ConstantsBuilder.create()) { case (b, (k, v)) => b.item(k, v) }.build()
        Contract.fromErgoContract(ctx.compileContract(constants, ScriptGenerator.mkScript(path)))
      }
      def hash(c: Contract) = Colls.fromArray(c.hashedPropBytes)
      def market(token: Array[Byte], bond: Contract): Market = {
        val asset = Colls.fromArray(token)
        val order = orderFor(ctx, bond, token)
        Market(order,
          offerFor(ctx, token, order, bond),
          build(PolicyPath, "CONST_REPAYMENT_TOKEN" -> asset,
            "CONST_LIQUIDATION_HASH" -> Colls.fromArray(f.payout.hashedValueBytes)),
          // The guard reads the repayment asset from the new bond's R7: no constant, one guard for both markets.
          build(GuardPath))
      }
      val swapped = build(FiStandardSpecBase.BondPath,
        "CONST_VAULT_HASH" -> Colls.fromArray(ZcStandardSpecBase.scripts(ctx).vault.hashedPropBytes),
        "CONST_REPAYMENT_TOKEN" -> Colls.fromArray(Array.emptyByteArray),
        "CONST_COUPON_VAULT_HASH" -> hash(f.couponVault),
        "CONST_COVENANT_HASH" -> Colls.fromArray(FiStandardSpecBase.otherCovenant(ctx).hashedValueBytes),
        "CONST_RECORD_BOUNTY" -> FiStandardSpecBase.RecordBounty)
      val m = Markets(market(Array.emptyByteArray, f.ergBond), market(ZcStandardSpecBase.SigUSD.getBytes, f.tokenBond), swapped)
      Seq(m.erg.order -> "FI Order ERG", m.token.order -> "FI Order SigUSD",
        m.erg.offer -> "FI LendOffer ERG", m.token.offer -> "FI LendOffer SigUSD")
        .foreach { case (c, n) => specs.harness.ContractSpecBase.nameScript(c, n) }
      cache = Some(m)
      m
    }
  }
}

/**
 * Builders for FI order fills and FI lend-offer fills and purchases. A world `W` (FiStandardSpecBase)
 * holds the order's R4 terms and the bond's start and maturity; the fill creates the bond those
 * describe, with the order's id as the asset, so every FiStandardSpecBase builder (Pay, Record,
 * Repay, Liquidate) runs on the box the order itself approved.
 */
trait FiStandardMarketSpecBase extends FiStandardSpecBase {
  import BondSpecBase._
  import ZcStandardSpecBase._

  protected def mk(ctx: BlockchainContext): FiStandardMarketSpecBase.Markets = FiStandardMarketSpecBase.compiled(ctx)
  protected def fiMarket(w: W): FiStandardMarketSpecBase.Market = if (w.erg) mk(w.ctx).erg else mk(w.ctx).token

  /** The fill height of a default world: its start. Every input is created 10 blocks earlier. */
  protected def fillHeight(w: W): Int = w.ctx.getHeight + 10
  protected def created(w: W): Int = w.ctx.getHeight

  // ─── worlds ───────────────────────────────────────────────────────────────

  protected def withTerm(w: W, i: Int, v: Long): W = w.copy(terms = w.terms.updated(i, v))

  protected def withTerms(w: W, changes: (Int, Long)*): W = changes.foldLeft(w) { case (x, (i, v)) => withTerm(x, i, v) }

  /** The world as an on-close order: R4(3) is the duration, R4(6) = 1, maturity counted from the start. */
  protected def onClose(w: W, duration: Long): W =
    w.copy(maturity = w.start + duration, terms = w.terms.updated(3, duration).updated(6, 1L))

  /** The bond starting at `start`; a fixed maturity stays, an on-close one moves with it. */
  protected def startedAt(w: W, start: Int): W =
    w.copy(start = start, maturity = if (w.terms(6) == 1L) start + w.terms(3) else w.maturity)

  /** A fixed-height world maturing at `maturity`. */
  protected def maturingAt(w: W, maturity: Long): W = w.copy(maturity = maturity, terms = w.terms.updated(3, maturity))

  // ─── the order and its fill ───────────────────────────────────────────────

  protected case class FiFill(w: W, order: UTXO, orderIn: InputUTXO, fund: InputUTXO, unit: UTXO, bond: UTXO,
                            principal: UTXO, height: Int, guard: Contract) {
    def asset: ErgoId = orderIn.id
    def ctx: BlockchainContext = w.ctx
  }

  /**
   * The fill guard an order of `w` names in R8: `guard` when given; otherwise, for a covenant order, the explicit
   * opt-out (an always-true expression), since the order refuses a covenant fill with no R8; none for an order
   * without a covenant, or when `unguarded`.
   */
  protected def namedGuard(w: W, guard: Contract, unguarded: Boolean = false): Contract =
    if (unguarded) null else if (guard != null) guard else if (w.terms(12) > 0L) alwaysTrue(w.ctx) else null

  protected def orderBox(w: W, guard: Contract = null, liq: Contract = null, contract: Contract = null,
                         unguarded: Boolean = false): UTXO = {
    val ctx = w.ctx
    val named = namedGuard(w, guard, unguarded)
    UTXO(if (contract != null) contract else fiMarket(w).order, w.collateralErg + w.terms(5), w.collateral,
      Seq(longsValue(w.terms: _*), Protocol.tokensValue(loanTokens(w)), bytesValue(hashOf(borrowerTree(ctx))),
        bytesValue((if (liq != null) liq else fi(ctx).payout).hashedValueBytes)) ++
        Option(named).map(g => bytesValue(g.hashedValueBytes)).toSeq,
      Some(created(w)))
  }

  /**
   * `expr` (a guard or a policy) run as the script of a box of its own, SELF, beside a bond-shaped OUTPUTS(1):
   * how it reads a bond, without an order or an offer around it. `selfTokens` ride in the box and go back to
   * the executor at OUTPUTS(0); the bond's tokens(0) is minted from the box's id, its other tokens and its
   * value come from the executor's box.
   */
  protected def runAsScript(w: W, expr: Contract, bond: UTXO, selfTokens: Seq[Token] = Seq.empty,
                            selfRegs: Seq[ErgoValue[_]] = Seq.empty, data: Seq[InputUTXO] = Seq.empty): Tx = {
    val ctx = w.ctx
    val box = inputAt(UTXO(expr, minBox, selfTokens, selfRegs, Some(created(w))), ctx, 30)
    val out = bond.setTokens(Token(box.id, 2L) +: bond.tokens.drop(1): _*)
    val funds = wallet(ctx, executor(ctx), 31, created(w), bond.tokens.drop(1), bond.value + slack)
    txAt(ctx, fillHeight(w), Seq(box, funds), Seq(UTXO(contractOf(executor(ctx)), minBox, selfTokens), out),
      executor(ctx).getAddress, data)
  }

  /** The R9 the order requires: fee, start, maturity, 0 paid, -1 no breach, 0 no deadline, start. */
  protected def bornState(w: W, fee: Long = batchingFee): Seq[Long] =
    Seq(fee, w.start.toLong, w.maturity, 0L, -1L, 0L, w.start.toLong)

  /**
   * A fill of the order `w` describes, at `height` (default: the world's own fill height). Outputs:
   * 0 the circulating unit, 1 the bond, 2 the principal. The lender's key box funds it. `orderScript`
   * replaces the market's FI order (a mutant, to show which clause refuses).
   */
  protected def fillOf(w: W, height: Int = -1, guard: Contract = null, liq: Contract = null,
                       orderScript: Contract = null, unguarded: Boolean = false): FiFill = {
    val ctx = w.ctx
    val named = namedGuard(w, guard, unguarded)
    val order = orderBox(w, named, liq, orderScript, unguarded)
    val orderIn = inputAt(order, ctx, 0)
    val asset = orderIn.id
    val liqHash = bytesValue((if (liq != null) liq else fi(ctx).payout).hashedValueBytes)
    val borrowerHash = bytesValue(hashOf(borrowerTree(ctx)))
    val bond = UTXO(w.bond, w.collateralErg + batchingFee, Token(asset, 2L) +: w.collateral,
      Seq(bytesValue(asset.getBytes), borrowerHash, longsValue(w.terms: _*), Protocol.tokensValue(loanTokens(w)),
        liqHash, longsValue(bornState(w): _*)), Some(w.start))
    val principal = UTXO(borrowerTree(ctx), w.terms(1) + w.terms(5), loanTokens(w), Seq(bytesValue(asset.getBytes)))
    FiFill(w, order, orderIn,
      wallet(ctx, lender(ctx), 1, created(w), loanTokens(w), w.terms(1) + batchingFee + slack),
      UTXO(lenderTree(ctx), minBox, Seq(Token(asset, 1L)), eip4()), bond, principal,
      if (height < 0) fillHeight(w) else height, named)
  }

  /** The order input, carrying the fill guard in var 1 when its R8 names one (or `ran` when given). */
  protected def orderInput(f: FiFill, ran: Contract = null): InputUTXO =
    if (ran != null) f.orderIn.withCtxVar(1.toByte, liqVar(ran))
    else if (f.guard != null) f.orderIn.withCtxVar(1.toByte, liqVar(f.guard))
    else f.orderIn

  protected def fiFillTx(f: FiFill, unit: UTXO = null, bond: UTXO = null, principal: UTXO = null, height: Int = -1,
                       ran: Contract = null, data: Seq[InputUTXO] = Seq.empty, burn: Seq[Token] = Seq.empty): Tx =
    txAt(f.ctx, if (height < 0) f.height else height, Seq(orderInput(f, ran), f.fund),
      Seq(if (unit == null) f.unit else unit, if (bond == null) f.bond else bond,
        if (principal == null) f.principal else principal), lender(f.ctx).getAddress, data, burn)

  /** The bond with R9(i) replaced. */
  protected def bondR9(f: FiFill, i: Int, v: Long): UTXO = f.bond.withRegNum(9, longsValue(bornState(f.w).updated(i, v): _*))

  // ─── the lend offer ───────────────────────────────────────────────────────

  protected val ergCapital: Long = 20L * Parameters.OneErg
  protected val tokenCapital: Long = 1000L

  /** The offer's R5, the ZC layout. `expiry` < 0 means 5000 blocks above the mocked height. */
  protected case class OTerms(yieldBps: Long = 0L, minBlocks: Long = 1L, maxBlocks: Long = 100000L,
                              batcherFee: Long = 100000000L, reserve: Long = batchingFee, floorBps: Long = 13000L,
                              expiry: Long = -1L, minFill: Long = 1L, maxAge: Long = 30L,
                              minFillBps: Long = 100L, rewardBudget: Long = 400000000L,
                              curve: Seq[(Long, Long)] = Seq.empty)

  /** The offer's R7. */
  protected case class Bounds(minCoupon: Long, minCount: Long, maxGap: Long, maxGrace: Long, maxCure: Long) {
    def values: Seq[Long] = Seq(minCoupon, minCount, maxGap, maxGrace, maxCure)
  }

  /**
   * The longest stretch of `w`'s life without a covenant test: start to first due, the period, and the
   * last checkpoint whose whole window (due + grace) lies at or before maturity - 721, to maturity.
   */
  protected def gapOf(w: W): Long = {
    val firstDue = w.start + w.terms(9)
    val recordable = w.maturity - 721L - firstDue - w.terms(11)
    val lastTested = if (recordable < 0L) w.start.toLong
      else firstDue + math.min(w.terms(8) - 1L, recordable / w.terms(10)) * w.terms(10)
    Seq(w.terms(9), w.terms(10), w.maturity - lastTested).max
  }

  /** Bounds every term of `w` meets exactly: one step tighter in any of them refuses. */
  protected def tightBounds(w: W): Bounds = Bounds(w.terms(7), w.terms(8), gapOf(w), w.terms(11), w.terms(13))

  protected def offerTerms(ctx: BlockchainContext, kind: Kind, t: OTerms): Seq[Long] = {
    val expiry = if (t.expiry < 0L) ctx.getHeight + 5000L else t.expiry
    val budget = if (kind == ErgBond) Seq.empty else
      Seq(tokenCapital, Parameters.OneErg - minBox - t.batcherFee, t.minFillBps, t.rewardBudget)
    Seq(t.yieldBps, t.minBlocks, t.maxBlocks, t.batcherFee, t.reserve, t.floorBps, expiry, t.minFill,
      t.maxAge, 0L, 0L) ++ budget ++ t.curve.flatMap { case (y, d) => Seq(y, d) }
  }

  protected def fiOffer(w: W, t: OTerms = OTerms(), bounds: Bounds = null, policy: Contract = null,
                        r7: Seq[Long] = null): UTXO = {
    val ctx = w.ctx
    val regs = Seq(bytesValue(hashOf(lenderTree(ctx))), longsValue(offerTerms(ctx, w.kind, t): _*),
      bytesValue((if (policy == null) alwaysTrue(ctx) else policy).hashedValueBytes),
      longsValue((if (r7 != null) r7 else (if (bounds != null) bounds else tightBounds(w)).values): _*))
    if (w.erg) UTXO(fiMarket(w).offer, ergCapital, Seq.empty[Token], regs)
    else UTXO(fiMarket(w).offer, Parameters.OneErg, Seq(Token(SigUSD, tokenCapital)), regs)
  }

  protected def offerTermsOf(offer: UTXO): Seq[Long] = offer.parseCollReg[Long](1).toSeq

  /** What the offer pays for a new bond of `w`: the principal, nanoERG or raw SigUSD. */
  protected def priceOf(w: W): Long = if (w.erg) w.terms(1) else loanTokens(w).head.amount

  protected def batcherFeeOf(w: W, offer: UTXO, price: Long): Long = {
    val t = offerTermsOf(offer)
    if (w.erg) t(3) else (BigInt(t(14)) * price / t(11)).toLong
  }

  /** The continuation after `ergOut` nanoERG and `price` raw repayment units left the box. */
  protected def continuationOf(w: W, offer: UTXO, offerId: ErgoId, ergOut: Long, price: Long): UTXO = {
    val next = offerSuccessor(offer.setValue(offer.value - ergOut), offerId)
    if (w.erg) next
    else {
      val left = offer.tokens.head.amount - price
      if (left == 0L) next.setTokens() else next.setTokens(Token(SigUSD, left))
    }
  }

  protected def offerIn(offer: UTXO, ctx: BlockchainContext, policy: Contract, next: Int): InputUTXO =
    inputAt(offer, ctx, 1).withCtxVar(0.toByte, ErgoValue.of(next))
      .withCtxVar(1.toByte, liqVar(if (policy == null) alwaysTrue(ctx) else policy))

  /** Outputs: 0 delivery, 1 bond, 2 principal, 3 continuation. Vars: 0 continuation index, 1 policy. */
  protected def offerFillTx(f: FiFill, offer: UTXO, policy: Contract = null, delivery: UTXO = null, next: UTXO = null,
                            bond: UTXO = null, data: Seq[InputUTXO] = Seq.empty, height: Int = -1,
                            extra: Seq[InputUTXO] = Seq.empty, order: InputUTXO = null,
                            principal: UTXO = null): Tx = {
    val w = f.w
    val in = offerIn(offer, f.ctx, policy, 3)
    val price = priceOf(w)
    val ergOut = batcherFeeOf(w, offer, price) + minBox + batchingFee + w.terms(1)
    txAt(f.ctx, if (height < 0) f.height else height, Seq(if (order == null) orderInput(f) else order, in) ++ extra,
      Seq(offerDelivery(if (delivery == null) f.unit else delivery, in.id), if (bond == null) f.bond else bond,
        if (principal == null) f.principal else principal,
        if (next != null) next else continuationOf(w, offer, in.id, ergOut, price)),
      executor(f.ctx).getAddress, data)
  }

  /** The highest yield the offer can demand and still pay `cost` for repayment `repayment` with `blocks` left. */
  protected def maxYield(repayment: Long, cost: Long, blocks: Long): Long = {
    val year = BigInt(2628000000L)
    ((BigInt(repayment) * year - BigInt(cost) * year) / (BigInt(cost) * blocks)).toLong
  }

  // ─── buying an existing bond from a ZC sale order ─────────────────────────

  protected def saleBox(w: W, token: ErgoId, price: Long): UTXO =
    UTXO(marketOf(w.ctx, w.kind).saleOrder, minBox, Seq(Token(token, 1L)),
      Seq(bytesValue(hashOf(otherTree(w.ctx))), longsValue(price, 1L, 1L, w.ctx.getHeight + 5000L)))

  protected def payoutBox(w: W, askId: ErgoId, paid: Long, units: Long = 1L): UTXO = {
    val regs = Seq(bytesValue(askId.getBytes), ErgoValue.of(units))
    if (w.erg) UTXO(otherTree(w.ctx), paid, Seq.empty[Token], regs)
    else UTXO(otherTree(w.ctx), minBox, Seq(Token(SigUSD, paid)), regs)
  }

  /** A price that clears a 0 bps offer: under the repayment, less the ERG fee. */
  protected def unitPrice(w: W): Long = if (w.erg) 3500000000L else 650L

  /**
   * The offer buys the unit of the bond whose state box is `state` from a sale order. Inputs: 0 the ask,
   * 1 the offer. Outputs: 0 payout, 1 delivery, 2 offer continuation. Data inputs: the state box, then `data`.
   */
  protected def buyTx(w: W, origin: Array[Byte], state: InputUTXO, offer: UTXO, paid: Long, h: Int,
                      ask: UTXO = null, policy: Contract = null, data: Seq[InputUTXO] = Seq.empty,
                      payout: UTXO = null, delivery: UTXO = null, offerNext: UTXO = null): Tx = {
    val ctx = w.ctx
    val asset = state.tokens.head.id
    val sale = if (ask != null) ask else saleBox(w, asset, paid)
    val askIn = inputAt(sale, ctx, 2).withCtxVar(0.toByte, ErgoValue.of(0))
    val in = offerIn(offer, ctx, policy, 2)
      .withCtxVar(2.toByte, ErgoValue.of(0)).withCtxVar(3.toByte, ErgoValue.of(0))
      .withCtxVar(4.toByte, bytesValue(origin))
      .withCtxVar(5.toByte, ErgoValue.of(1)).withCtxVar(7.toByte, ErgoValue.of(0))
    val ergOut = batcherFeeOf(w, offer, paid) + minBox + (if (w.erg) paid else minBox)
    txAt(ctx, h, Seq(askIn, in),
      Seq(if (payout != null) payout else payoutBox(w, askIn.id, paid),
        offerDelivery(if (delivery != null) delivery else UTXO(lenderTree(ctx), minBox, Seq(Token(sale.tokens.head.id, 1L))), in.id),
        if (offerNext != null) offerNext else continuationOf(w, offer, in.id, ergOut, paid)),
      executor(ctx).getAddress, state +: data)
  }
}
