package org.lithosprotocol.sigmafi
package specs.bonds

import ksingh.sigmabonds.app.Protocol
import ksingh.sigmabonds.client.ScriptGenerator
import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import sigma.Colls
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import scala.collection.JavaConverters._

/**
 * The FI Standard contracts for the specs: FI_Standard_Bond (one tree per repayment asset), the
 * ERG/SigUSD covenant expression it runs on Record breach and Cure, and FI_Standard_CouponVault.
 *
 * `StandardContracts.compile` does not know these contracts yet, so they are compiled here, from
 * their files, with the same constant bindings it would use (the ZC bond vault's tree hash, the
 * repayment asset, the coupon vault's tree hash, the covenant's expression hash).
 *
 * ZC's two liquidation expressions read R9 as exactly 3 Longs, so neither can run on an FI bond (R9
 * is 7). `payout` is FI_Standard_Liquidation, read from its file: ZC's maturity payout with no
 * eligibility of its own, so the bond's `liquidatable` alone decides. `MaturitySrc` adds ZC's
 * maturity condition to it, to show that an expression narrows eligibility and cannot widen it.
 */
object FiStandardSpecBase {
  val BondPath = "bonds/fi/FI_Standard_Bond"
  val CovenantPath = "bonds/fi/FI_Standard_ERG_SigUSD_Covenant"
  val CouponVaultPath = "bonds/fi/FI_Standard_CouponVault"
  val LiquidationPath = "bonds/fi/FI_Standard_Liquidation"
  /** CONST_RECORD_BOUNTY of the standard markets: 0.5 ERG. */
  val RecordBounty: Long = 500000000L

  lazy val PayoutSrc: String = ScriptGenerator.mkScript(LiquidationPath)

  lazy val MaturitySrc: String = {
    val marker = "sigmaProp(lender(0) >= 0L"
    require(PayoutSrc.contains(marker), s"$LiquidationPath no longer contains `$marker`")
    PayoutSrc.replace(marker, "sigmaProp(HEIGHT.toLong >= lender(2) && lender(0) >= 0L")
  }

  /** `src` with each (from, to) replaced; every `from` must occur. */
  def edited(src: String, edits: Seq[(String, String)]): String = edits.foldLeft(src) { case (s, (a, b)) =>
    require(s.contains(a), s"source no longer contains `$a`")
    s.replace(a, b)
  }

  /** A coupon pool compiled from its file with `edits` applied: a mutant, to show which clause refuses. */
  def poolVariant(ctx: BlockchainContext, edits: (String, String)*): Contract = synchronized {
    Contract.fromErgoContract(ctx.compileContract(ConstantsBuilder.empty(),
      edited(ScriptGenerator.mkScript(CouponVaultPath), edits)))
  }

  /**
   * A bond compiled from its file with `edits` applied, for the repayment asset `token` (empty for ERG),
   * pinning `pool` as its coupon pool script and the real covenant of that asset.
   */
  def bondVariant(ctx: BlockchainContext, token: Array[Byte], pool: Contract, edits: (String, String)*): Contract = {
    val f = compiled(ctx)
    val covenant = if (token.isEmpty) f.ergCovenant else f.tokenCovenant
    synchronized {
      Contract.fromErgoContract(ctx.compileContract(ConstantsBuilder.create()
        .item("CONST_VAULT_HASH", Colls.fromArray(ZcStandardSpecBase.scripts(ctx).vault.hashedPropBytes))
        .item("CONST_REPAYMENT_TOKEN", Colls.fromArray(token))
        .item("CONST_COUPON_VAULT_HASH", Colls.fromArray(pool.hashedPropBytes))
        .item("CONST_COVENANT_HASH", Colls.fromArray(covenant.hashedValueBytes))
        .item("CONST_RECORD_BOUNTY", RecordBounty).build(),
        edited(ScriptGenerator.mkScript(BondPath), edits)))
    }
  }

  private var otherCache: Option[Contract] = None

  /**
   * Another covenant expression: the real one with its "below" test made non-strict. The covenant reads the
   * repayment asset from the bond's R7, so both markets run one expression; this one has another hash.
   */
  def otherCovenant(ctx: BlockchainContext): Contract = synchronized {
    otherCache.getOrElse {
      val c = Contract.fromErgoContract(ctx.compileContract(ConstantsBuilder.empty(),
        edited(ScriptGenerator.mkScript(CovenantPath), Seq("cover * 10000L.toBigInt < debt" -> "cover * 10000L.toBigInt <= debt"))))
      otherCache = Some(c)
      c
    }
  }

  private var openCache: Option[Contract] = None

  /**
   * The coupon pool with every rule but refresh replaced by `true`. A spend of a pool that the real pool
   * refuses and that signs with the pool moved here was refused by the pool, and by nothing else in it.
   */
  def openPool(ctx: BlockchainContext): Contract = synchronized {
    openCache.getOrElse {
      val c = poolVariant(ctx, "sigmaProp(collect || topUp)" -> "sigmaProp(true)")
      openCache = Some(c)
      c
    }
  }

  case class Fi(couponVault: Contract, ergBond: Contract, tokenBond: Contract,
                ergCovenant: Contract, tokenCovenant: Contract, payout: Contract, maturityOnly: Contract)

  private var cache: Option[Fi] = None

  /** Compiled once per JVM; the sigma compiler mutates shared AST nodes. */
  def compiled(ctx: BlockchainContext): Fi = synchronized {
    cache.getOrElse {
      def build(path: String, terms: (String, Any)*): Contract = {
        val constants = terms.foldLeft(ConstantsBuilder.create()) { case (b, (k, v)) => b.item(k, v) }.build()
        Contract.fromErgoContract(ctx.compileContract(constants, ScriptGenerator.mkScript(path)))
      }
      def expr(src: String): Contract = Contract.fromErgoContract(ctx.compileContract(ConstantsBuilder.empty(), src))
      val vaultHash = Colls.fromArray(ZcStandardSpecBase.scripts(ctx).vault.hashedPropBytes)
      val couponVault = build(CouponVaultPath)
      def market(token: Array[Byte]): (Contract, Contract) = {
        val asset = Colls.fromArray(token)
        // The covenant reads the repayment asset from the bond's R7, so it has no constant: one expression
        // serves both markets.
        val covenant = build(CovenantPath)
        val bond = build(BondPath, "CONST_VAULT_HASH" -> vaultHash, "CONST_REPAYMENT_TOKEN" -> asset,
          "CONST_COUPON_VAULT_HASH" -> Colls.fromArray(couponVault.hashedPropBytes),
          "CONST_COVENANT_HASH" -> Colls.fromArray(covenant.hashedValueBytes), "CONST_RECORD_BOUNTY" -> RecordBounty)
        (bond, covenant)
      }
      val (ergBond, ergCovenant) = market(Array.emptyByteArray)
      val (tokenBond, tokenCovenant) = market(ZcStandardSpecBase.SigUSD.getBytes)
      val fi = Fi(couponVault, ergBond, tokenBond, ergCovenant, tokenCovenant, expr(PayoutSrc), expr(MaturitySrc))
      Seq(couponVault -> "FI CouponVault", ergBond -> "FI Bond ERG", tokenBond -> "FI Bond SigUSD")
        .foreach { case (c, n) => specs.harness.ContractSpecBase.nameScript(c, n) }
      cache = Some(fi)
      fi
    }
  }
}

/**
 * Builders for FI bond transactions. The FI order does not exist yet, so every bond box here is built
 * directly with the registers an order would write (R9 = fee, start, maturity, 0, -1, 0, start),
 * created at its start height, rather than signed into existence by a fill.
 *
 * Every builder returns a [[Tx]]: the unsigned transaction, the inclusion height, and how many of its
 * outputs are the ones the spec planned (appkit appends change and the miner fee after them). `ok`
 * signs it and then applies the node rules the offline harness does not run, to the planned outputs.
 */
trait FiStandardSpecBase extends ZcStandardSpecBase {
  import BondSpecBase._
  import ZcStandardSpecBase._

  /** The lender's liquidation reserve, the offer's fees and the order's expiry fee: the FI floor, 0.1 ERG. */
  override protected val batchingFee: Long = 100000000L

  protected def fi(ctx: BlockchainContext): FiStandardSpecBase.Fi = FiStandardSpecBase.compiled(ctx)

  // ─── terms ────────────────────────────────────────────────────────────────

  protected val firstOffset: Int = 200
  protected val period: Int = 200
  /** At least 30: a covenant order refuses a shorter grace (the window a checkpoint is recordable in). */
  protected val grace: Int = 30
  protected val couponCount: Long = 3L
  protected val minRatio: Long = 15000L
  protected val cureBlocks: Int = 100
  protected val maxAge: Int = 30
  protected val Bounty: Long = 10000000L
  protected val RefreshAge: Int = 525600
  /** Blocks after maturity at which the specs try to take a pool: about 30 days, and about 19 years. */
  protected val LongAfter: Seq[Int] = Seq(21600, 10000000)
  protected val MaxBox: Int = 4096

  /**
   * One FI bond's world. Token bond (SigUSD repayment): 800 raw SigUSD ($8) owed, 7 ERG of collateral
   * and 100 of an unpriced token; at 0.5 ERG/USD that is 175%, at 0.6 it is 145.8%, and 0.2 ERG more
   * restores exactly 150%. ERG bond: 4 ERG owed, 1 ERG and 1000 raw SigUSD of collateral; at 0.6
   * ERG/USD 175%, at 0.4 125%, and 250 raw SigUSD more restores exactly 150%.
   */
  protected case class W(ctx: BlockchainContext, kind: Kind, start: Int, maturity: Long, terms: Seq[Long],
                         collateralErg: Long, collateral: Seq[Token]) {
    def due(k: Int): Int = start + firstOffset + k * period
    def erg: Boolean = kind == ErgBond
    def bond: Contract = if (erg) fi(ctx).ergBond else fi(ctx).tokenBond
    def covenant: Contract = if (erg) fi(ctx).ergCovenant else fi(ctx).tokenCovenant
    def otherCovenant: Contract = FiStandardSpecBase.otherCovenant(ctx)
    def coupon: Long = terms(7)
    def repayment: Long = terms(0)
    def calm: Long = if (erg) 600000000L else 500000000L
    def slump: Long = if (erg) 400000000L else 600000000L
    def tokens: Seq[Token] = Token(bondAsset, 2L) +: collateral
  }

  protected def world(ctx: BlockchainContext, kind: Kind = TokenBond, life: Long = 2000L,
                      payload: Seq[Long] = Seq.empty, collateralErg: Long = -1L,
                      collateral: Seq[Token] = null): W = {
    val s = ctx.getHeight + 10
    val maturity = s + life
    val erg = kind == ErgBond
    val loan = if (erg) Seq(4000000000L, 3000000000L, 4000000000L) else Seq(800L, minBox, minBox)
    val coupon = if (erg) 100000000L else 20L
    val terms = loan ++ Seq(maturity, s + 490L, 100000000L, 0L,
      coupon, couponCount, firstOffset.toLong, period.toLong, grace.toLong, minRatio, cureBlocks.toLong,
      maxAge.toLong) ++ payload
    W(ctx, kind, s, maturity, terms,
      if (collateralErg >= 0L) collateralErg else if (erg) Parameters.OneErg else 7000000000L,
      if (collateral != null) collateral
      else if (erg) Seq(Token(SigUSD, 1000L)) else Seq(Token(collateralToken, 100L)))
  }

  protected def loanTokens(w: W): Seq[Token] = if (w.erg) Seq.empty else Seq(Token(SigUSD, 600L))

  /** The R9 an order writes, then the state given. `clock` -1 is the start height. */
  protected def fiState(w: W, paid: Long = 0L, last: Long = -1L, deadline: Long = 0L, clock: Long = -1L): Seq[Long] =
    Seq(batchingFee, w.start.toLong, w.maturity, paid, last, deadline, if (clock < 0L) w.start.toLong else clock)

  protected def fiBox(w: W, paid: Long = 0L, last: Long = -1L, deadline: Long = 0L, clock: Long = -1L,
                      stamp: Int = -1, value: Long = -1L, tokens: Seq[Token] = null, terms: Seq[Long] = null,
                      liq: Contract = null, r9: Seq[Long] = null, borrowerHash: Array[Byte] = null): UTXO =
    UTXO(w.bond, if (value >= 0L) value else w.collateralErg + batchingFee,
      if (tokens != null) tokens else w.tokens,
      Seq(bytesValue(bondAsset.getBytes),
        bytesValue(if (borrowerHash != null) borrowerHash else hashOf(borrowerTree(w.ctx))),
        longsValue((if (terms != null) terms else w.terms): _*), Protocol.tokensValue(loanTokens(w)),
        bytesValue((if (liq != null) liq else fi(w.ctx).payout).hashedValueBytes),
        longsValue((if (r9 != null) r9 else fiState(w, paid, last, deadline, clock)): _*)),
      Some(if (stamp < 0) w.start else stamp))

  protected def fiIn(w: W, box: UTXO): InputUTXO = inputAt(box, w.ctx, 0)

  protected def r9(b: InputUTXO): Seq[Long] = b.registers(5).getValue.asInstanceOf[sigma.Coll[Long]].toArray.toSeq
  protected def r6(b: InputUTXO): Seq[Long] = b.registers(2).getValue.asInstanceOf[sigma.Coll[Long]].toArray.toSeq

  /** The bond re-created with R9 replaced, stamped at `stamp`. */
  protected def succ(b: InputUTXO, state: Seq[Long], stamp: Int): UTXO =
    b.toUTXO.withRegNum(9, longsValue(state: _*)).setCreationHeight(stamp)

  protected def oracle(ctx: BlockchainContext, price: Long, created: Int, nft: ErgoId = OracleNFT,
                       amount: Long = 1L, epoch: Int = 1, index: Int = 20): InputUTXO =
    inputAt(UTXO(Contract.SIGMA_TRUE, minBox, Seq(Token(nft, amount)),
      Seq(ErgoValue.of(price), ErgoValue.of(epoch)), Some(created)), ctx, index)

  /** A key-held box; `created` is its creation height (the node's monotonic rule reads it). */
  protected def wallet(ctx: BlockchainContext, p: ErgoProver, index: Int, created: Int,
                       tokens: Seq[Token] = Seq.empty, value: Long = slack): InputUTXO =
    inputAt(UTXO(contractOf(p), value, tokens, Seq.empty, Some(created)), ctx, index)

  /** What the borrower's co-spent box brings: SigUSD for coupons and top-ups, ERG as slack. */
  protected val borrowerFunds: Seq[Token] = Seq(Token(SigUSD, 5000L), Token(secondCollateral, 10L))

  protected def vars(in: InputUTXO, vs: Seq[(Int, ErgoValue[_])]): InputUTXO =
    vs.sortBy(_._1).foldLeft(in) { case (i, (id, v)) => i.withCtxVar(id.toByte, v) }

  protected def int(i: Int): ErgoValue[_] = ErgoValue.of(i)

  // ─── transactions ─────────────────────────────────────────────────────────

  /** A transaction, its inclusion height, and the number of planned outputs at its front. */
  protected case class Tx(unsigned: UnsignedTransaction, height: Int, planned: Int)

  protected def txAt(ctx: BlockchainContext, h: Int, ins: Seq[InputUTXO], outs: Seq[UTXO], changeTo: Address,
                     data: Seq[InputUTXO] = Seq.empty, burn: Seq[Token] = Seq.empty): Tx = {
    val stamped = outs.map(o => if (o.creationHeight.isDefined) o else o.setCreationHeight(h))
    Tx(buildAt(ctx, h, ins, stamped, changeTo, data, burn), h, outs.size)
  }

  /**
   * The node's output rules the offline harness does not run (ergo-core ErgoTransaction.verifyOutput):
   * no output stamped below the highest creation height of a spent input (txMonotonicHeight), none
   * above the inclusion height (txFuture), and no box over 4096 bytes. Checked on the planned outputs:
   * appkit stamps its own change and fee outputs at the mocked context height, not the preheader's.
   */
  protected def nodeRules(t: Tx, signed: SignedTransaction): Seq[String] = {
    val maxIn = t.unsigned.getInputs.asScala.map(_.getCreationHeight).max
    (0 until t.planned).flatMap { i =>
      val c = t.unsigned.getOutputs.get(i).getCreationHeight
      val size = signed.getOutputsToSpend.get(i).getBytes.length
      (if (c < maxIn) Seq(s"out$i stamped $c below input creation height $maxIn") else Nil) ++
        (if (c > t.height) Seq(s"out$i stamped $c above inclusion height ${t.height}") else Nil) ++
        (if (size > MaxBox) Seq(s"out$i is $size bytes, over $MaxBox") else Nil)
    }
  }

  /** Accepted by every contract, and by the node rules above. */
  protected def ok(p: ErgoProver, t: Tx): SignedTransaction = {
    val signed = accepts(p, t.unsigned)
    withClue("node rules: ")(nodeRules(t, signed) shouldBe empty)
    signed
  }

  /** Built, then refused by a contract when signing. */
  protected def no(p: ErgoProver, t: => Tx): Throwable = rejectsAtSigning(p, t.unsigned)

  /** Byte length of planned output `i` of a signed transaction. */
  protected def outBytes(s: SignedTransaction, i: Int): Int = s.getOutputsToSpend.get(i).getBytes.length

  // ── Pay. Outputs: 0 successor, 1 coupon pool.

  /** The coupon pool's one register: R4, the bond asset. */
  protected def poolRegs(asset: ErgoId = bondAsset): Seq[ErgoValue[_]] = Seq(bytesValue(asset.getBytes))

  /** A coupon pool holding `amount` (default one coupon) of the repayment asset; a token pool also holds `erg`. */
  protected def couponOut(w: W, amount: Long = -1L, asset: ErgoId = bondAsset, erg: Long = -1L): UTXO = {
    val a = if (amount < 0L) w.coupon else amount
    val regs = poolRegs(asset)
    if (w.erg) UTXO(fi(w.ctx).couponVault, a, Seq.empty, regs)
    else UTXO(fi(w.ctx).couponVault, if (erg < 0L) minBox else erg, Seq(Token(SigUSD, a)), regs)
  }

  /** The pool a Pay must create when it spends `pools`: the coupon on top of everything they hold. */
  protected def toppedPool(w: W, pools: Seq[InputUTXO], extra: Long = 0L, ergExtra: Long = 0L): UTXO = {
    val erg = pools.map(_.value).sum
    if (w.erg) couponOut(w, amount = w.coupon + erg + extra)
    else couponOut(w, amount = w.coupon + pools.flatMap(_.tokens).filter(_.id == SigUSD).map(_.amount).sum + extra,
      erg = erg + ergExtra)
  }

  protected def paid(w: W, b: InputUTXO, h: Int): UTXO = { val c = r9(b); succ(b, c.updated(3, c(3) + 1L), h) }

  protected def payTx(w: W, b: InputUTXO, h: Int, next: UTXO = null, coupon: UTXO = null, by: ErgoProver = null,
                      vs: Seq[(Int, ErgoValue[_])] = null, extraOuts: Seq[UTXO] = Seq.empty,
                      extraIns: Seq[InputUTXO] = Seq.empty, data: Seq[InputUTXO] = Seq.empty,
                      burn: Seq[Token] = Seq.empty, coSpend: Boolean = true, payerCreated: Int = -1): Tx = {
    val p = if (by == null) borrower(w.ctx) else by
    val outs = Seq(if (next == null) paid(w, b, h) else next, if (coupon == null) couponOut(w) else coupon) ++ extraOuts
    val payer = wallet(w.ctx, p, 6, if (payerCreated < 0) h else payerCreated, borrowerFunds)
    txAt(w.ctx, h, (vars(b, if (vs == null) Seq(4 -> int(0), 5 -> int(1)) else vs) +: extraIns) ++
      (if (coSpend) Seq(payer) else Seq(wallet(w.ctx, executor(w.ctx), 7, h, borrowerFunds))),
      outs, p.getAddress, data, burn)
  }

  // ── Record breach. Output 0 successor.

  protected def covVar(c: Contract): ErgoValue[_] = liqVar(c)

  protected def recorded(w: W, b: InputUTXO, h: Int, k: Long, deadline: Long = -1L): UTXO = {
    val c = r9(b)
    succ(b, c.updated(4, k).updated(5, if (deadline < 0L) h.toLong + cureBlocks else deadline), h)
  }

  protected def recordTx(w: W, b: InputUTXO, h: Int, k: Long, next: UTXO = null, price: Long = -1L, priced: Int = -1,
                         vs: Seq[(Int, ErgoValue[_])] = null, data: Seq[InputUTXO] = null,
                         by: ErgoProver = null, extraOuts: Seq[UTXO] = Seq.empty, burn: Seq[Token] = Seq.empty,
                         extraIns: Seq[InputUTXO] = Seq.empty): Tx = {
    val p = if (by == null) executor(w.ctx) else by
    txAt(w.ctx, h, (vars(b, if (vs == null) Seq(6 -> int(0), 8 -> covVar(w.covenant)) else vs) +: extraIns) :+
      wallet(w.ctx, p, 7, h),
      (if (next == null) recorded(w, b, h, k) else next) +: extraOuts, p.getAddress,
      if (data != null) data else Seq(oracle(w.ctx, if (price < 0L) w.slump else price, if (priced < 0) h - 5 else priced)),
      burn)
  }

  // ── Cure. Output 0 successor.

  /** Collateral that restores exactly the 150% at the slump price; `short` leaves it one unit under. */
  protected def toppedUp(w: W, u: UTXO, short: Boolean = false): UTXO =
    if (w.erg) u.setTokens(u.tokens.map(t => if (t.id == SigUSD) Token(SigUSD, t.amount + (if (short) 249L else 250L)) else t): _*)
    else u.addValue(if (short) 199999999L else 200000000L)

  protected def cured(w: W, b: InputUTXO, h: Int, short: Boolean = false): UTXO =
    toppedUp(w, succ(b, r9(b).updated(5, 0L), h), short)

  protected def cureTx(w: W, b: InputUTXO, h: Int, next: UTXO = null, price: Long = -1L, priced: Int = -1,
                       by: ErgoProver = null, vs: Seq[(Int, ErgoValue[_])] = null,
                       data: Seq[InputUTXO] = null, extraOuts: Seq[UTXO] = Seq.empty, burn: Seq[Token] = Seq.empty): Tx = {
    val p = if (by == null) borrower(w.ctx) else by
    txAt(w.ctx, h, Seq(vars(b, if (vs == null) Seq(7 -> int(0), 8 -> covVar(w.covenant)) else vs),
      wallet(w.ctx, p, 6, h, borrowerFunds)),
      (if (next == null) cured(w, b, h) else next) +: extraOuts, p.getAddress,
      if (data != null) data else Seq(oracle(w.ctx, if (price < 0L) w.slump else price, if (priced < 0) h - 5 else priced)),
      burn)
  }

  // ── Recollateralize. Output 0 successor.

  protected def regrown(w: W, b: InputUTXO, h: Int, clock: Long = -1L): UTXO =
    succ(b, r9(b).updated(6, if (clock < 0L) h.toLong else clock), h).addValue(1L)

  protected def recollatTx(w: W, b: InputUTXO, h: Int, next: UTXO = null, by: ErgoProver = null,
                           vs: Seq[(Int, ErgoValue[_])] = null, burn: Seq[Token] = Seq.empty): Tx = {
    val p = if (by == null) borrower(w.ctx) else by
    txAt(w.ctx, h, Seq(vars(b, if (vs == null) Seq(3 -> int(0)) else vs), wallet(w.ctx, p, 6, h, borrowerFunds)),
      Seq(if (next == null) regrown(w, b, h) else next), p.getAddress, burn = burn)
  }

  // ── Refresh. Output 0 successor; the executor's box pays the miner fee; a post-maturity bounty is change.

  /**
   * The Refresh successor: before maturity the box unchanged (a Refresh takes nothing then); from maturity on it
   * keeps up to the 0.01 ERG bounty. Stamped 60 blocks back.
   */
  protected def refreshed(b: InputUTXO, h: Int): UTXO = {
    val u = b.toUTXO.setCreationHeight(h - 60)
    if (h.toLong >= r9(b)(2)) u.subValue(Bounty) else u
  }

  /** The executor's box that pays the miner fee of a Refresh, created early enough for the successor's stamp. */
  protected def refreshFee(w: W, h: Int): InputUTXO = wallet(w.ctx, executor(w.ctx), 7, h - 2000)

  protected def refreshTx(w: W, b: InputUTXO, h: Int, next: UTXO = null,
                          vs: Seq[(Int, ErgoValue[_])] = null, burn: Seq[Token] = Seq.empty,
                          extraIns: Seq[InputUTXO] = Seq.empty, extraOuts: Seq[UTXO] = Seq.empty): Tx =
    txAt(w.ctx, h, (vars(b, if (vs == null) Seq(9 -> int(0)) else vs) +: extraIns) :+ refreshFee(w, h),
      (if (next == null) refreshed(b, h) else next) +: extraOuts, executor(w.ctx).getAddress, burn = burn)

  // ── Settlements, into ZC_Standard_BondVault.

  protected def settled(w: W, b: InputUTXO, value: Long, tokens: Seq[Token], path: Byte): UTXO =
    UTXO(scripts(w.ctx).vault, value, tokens, Seq(bytesValue(b.id.getBytes), ErgoValue.of(path)))

  protected def proceeds(w: W, b: InputUTXO): UTXO = settled(w, b, b.value - batchingFee, b.tokens, 1.toByte)

  protected def fiLiquidateTx(w: W, b: InputUTXO, h: Int, out: UTXO = null, ran: Contract = null,
                            vs: Seq[(Int, ErgoValue[_])] = null, data: Seq[InputUTXO] = Seq.empty,
                            burn: Seq[Token] = Seq.empty): Tx =
    txAt(w.ctx, h, Seq(vars(b, if (vs == null) Seq(0 -> liqVar(if (ran == null) fi(w.ctx).payout else ran), 1 -> int(0)) else vs)),
      Seq(if (out == null) proceeds(w, b) else out), executor(w.ctx).getAddress, data, burn)

  protected def repaid(w: W, b: InputUTXO): UTXO =
    if (w.erg) settled(w, b, w.repayment + batchingFee, Seq(b.tokens.head), 0.toByte)
    else settled(w, b, minBox + batchingFee, Seq(b.tokens.head, Token(SigUSD, w.repayment)), 0.toByte)

  protected def returned(w: W, b: InputUTXO): UTXO = tagged(borrowerTree(w.ctx), b.value - batchingFee, b.id, b.tokens.drop(1))

  protected def fiRepayTx(w: W, b: InputUTXO, h: Int, settle: UTXO = null, back: UTXO = null, payer: ErgoProver = null,
                        vs: Seq[(Int, ErgoValue[_])] = null, payerCreated: Int = -1): Tx = {
    val p = if (payer == null) borrower(w.ctx) else payer
    txAt(w.ctx, h, Seq(vars(b, if (vs == null) Seq(1 -> int(0), 2 -> int(1)) else vs),
      wallet(w.ctx, p, 6, if (payerCreated < 0) h else payerCreated, borrowerFunds)),
      Seq(if (settle == null) repaid(w, b) else settle, if (back == null) returned(w, b) else back), p.getAddress)
  }

  // ── Coupon vault.

  /** The circulating unit, in the lender's (or `holder`'s) key box. */
  protected def unit(ctx: BlockchainContext, created: Int, holder: ErgoProver = null, asset: ErgoId = bondAsset,
                     amount: Long = 1L, index: Int = 8): InputUTXO =
    wallet(ctx, if (holder == null) lender(ctx) else holder, index, created, Seq(Token(asset, amount)), minBox * 5L)

  /** A coupon pool input holding one coupon (or `amount`); `index` makes its box id. */
  protected def vaultIn(w: W, created: Int, asset: ErgoId = bondAsset, index: Int = 1, amount: Long = -1L): InputUTXO =
    inputAt(couponOut(w, amount = amount, asset = asset).setCreationHeight(created), w.ctx, index, "ab" * 32)

  /** A coupon pool input at `pool` (default the real script), holding `amount` and, for a token pool, `erg`. */
  protected def poolIn(w: W, created: Int, index: Int = 1, amount: Long = -1L, erg: Long = -1L,
                       asset: ErgoId = bondAsset, pool: Contract = null): InputUTXO = {
    val u = couponOut(w, amount = amount, asset = asset, erg = erg)
    inputAt((if (pool == null) u else u.setContract(pool)).setCreationHeight(created), w.ctx, index, "ab" * 32)
  }

  /** What a contract-home collect may leave out of an amount the pools hold: 0.5% (1/200), rounded down. */
  protected def cut(amount: Long): Long = amount / 200L

  protected def repaymentBytes(w: W): Array[Byte] = if (w.erg) Array.emptyByteArray else SigUSD.getBytes

  /** Collect: the coupon vaults and the unit box in; the unit back out to its holder. */
  protected def collectTx(w: W, vaults: Seq[InputUTXO], shown: Seq[InputUTXO], h: Int, by: ErgoProver = null,
                          outs: Seq[UTXO] = null, burn: Seq[Token] = Seq.empty): Tx = {
    val p = if (by == null) lender(w.ctx) else by
    txAt(w.ctx, h, vaults ++ shown,
      if (outs != null) outs else Seq(UTXO(contractOf(p), minBox, shown.flatMap(_.tokens))), p.getAddress, burn = burn)
  }

  /**
   * `by` (default the borrower, so the transaction spends an input at the borrower's script) tries to take
   * `pool` outright: the pool and a key box of `by` in, everything the pool holds out to `by`, no bond unit.
   */
  protected def takeTx(w: W, pool: InputUTXO, h: Int, by: ErgoProver = null): Tx = {
    val p = if (by == null) borrower(w.ctx) else by
    txAt(w.ctx, h, Seq(pool, wallet(w.ctx, p, 6, h)), Seq(UTXO(contractOf(p), pool.value, pool.tokens)), p.getAddress)
  }

  /** `pool` with the same contents and creation height at `FiStandardSpecBase.openPool`. */
  protected def opened(w: W, pool: InputUTXO, index: Int = 1): InputUTXO =
    inputAt(pool.toUTXO.setContract(FiStandardSpecBase.openPool(w.ctx)).setCreationHeight(pool.input.getCreationHeight),
      w.ctx, index, "cd" * 32)

  /** `tx` on `pool` is refused, and signs on the same pool moved to the open pool: the pool's own rules refused it. */
  protected def poolRefuses(p: ErgoProver, w: W, pool: InputUTXO)(tx: InputUTXO => Tx): Unit = {
    no(p, tx(pool))
    ok(p, tx(opened(w, pool)))
  }

  /** Anyone renews `pool` (old enough) unchanged at `h`, var 9 naming output 0; the fee is `by`'s. */
  protected def renewTx(w: W, pool: InputUTXO, h: Int, by: ErgoProver = null): Tx = {
    val p = if (by == null) executor(w.ctx) else by
    txAt(w.ctx, h, Seq(pool.withCtxVar(9.toByte, int(0)), wallet(w.ctx, p, 7, h)),
      Seq(pool.toUTXO.setCreationHeight(h)), p.getAddress)
  }
}
