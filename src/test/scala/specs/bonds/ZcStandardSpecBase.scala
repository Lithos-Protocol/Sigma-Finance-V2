package org.lithosprotocol.sigmafi
package specs.bonds

import ksingh.sigmabonds.app.Protocol
import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import sigma.Colls
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import scala.collection.JavaConverters._

/**
 * Views of the ZC_Standard_* contracts for the specs. They compile through `StandardContracts.compile`,
 * the same code path the client's pinned trees are checked against, never a spec-local re-derivation.
 */
object ZcStandardSpecBase {
  import ksingh.sigmabonds.standard.{StandardContracts, Market => StdMarket}

  val SigUSD: ErgoId = StdMarket.SigUsdId
  val OracleNFT: ErgoId = ErgoId.create("6a2b821b5727e85beb5e78b4efb9f0250d59cd48481d2ded2c23e91ba1d07c66")

  /** One bond and one order tree per repayment asset; each pair shares a template. */
  case class Scripts(vault: Contract, ergBond: Contract, tokenBond: Contract,
                     ergOrder: Contract, tokenOrder: Contract,
                     fractionalVault: Contract, fractionalMint: Contract,
                     maturity: Contract, ergOracle: Contract, tokenOracle: Contract)

  /**
   * The contracts compiled per repayment (or payment) asset beyond the bond and order: the lend offer,
   * both asks, the two expressions they run, and the Dutch auction mint.
   */
  case class Market(offer: Contract, saleOrder: Contract, navAsk: Contract, pricing: Contract,
                    policy: Contract, dutchMint: Contract, fillGuard: Contract, guardedPricing: Contract)

  case class Exchange(erg: Market, token: Market, dutchVault: Contract)

  /** Compiled once per JVM; every script is named, with its market, for the opt-in cost log. */
  def compiled(ctx: BlockchainContext): StandardContracts = synchronized {
    val set = StandardContracts.compile(ctx)
    set.entries.foreach(e => specs.harness.ContractSpecBase.nameScript(e.contract, e.label))
    set
  }

  def scripts(ctx: BlockchainContext): Scripts = {
    val s = compiled(ctx)
    val (erg, token) = (s.market(StdMarket.Erg), s.market(StdMarket.SigUsd))
    Scripts(s.vault, erg.bond, token.bond, erg.order, token.order, s.fractionalVault, s.fractionalMint,
      s.maturity, erg.oracleRule, token.oracleRule)
  }

  /** The P2P exchange, the Dutch auction pair and the policies. */
  def exchange(ctx: BlockchainContext): Exchange = {
    val s = compiled(ctx)
    def market(m: StdMarket): Market = {
      val c = s.market(m)
      Market(c.offer, c.saleOrder, c.navAsk, c.pricing, c.policy, c.dutchMint, c.fillGuard, c.guardedPricing)
    }
    Exchange(market(StdMarket.Erg), market(StdMarket.SigUsd), s.dutchVault)
  }
}

/**
 * One order-to-bond flow of the standard contracts, and the transactions that move it on. A flow is
 * built unfilled; `filled` signs the fill so later steps spend the boxes the order itself approved.
 */
trait ZcStandardSpecBase extends BondSpecBase {
  import BondSpecBase._
  import ZcStandardSpecBase._

  protected case class Flow(ctx: BlockchainContext, kind: Kind, style: OrderStyle,
                            order: UTXO, orderIn: InputUTXO, fund: InputUTXO, unit: UTXO, bond: UTXO,
                            principal: UTXO, data: Seq[Long], height: Int, start: Long, maturity: Long,
                            expiry: Long, collateralValue: Long, expiryFee: Long, fee: Long,
                            liq: Contract, owner: ErgoProver, holder: ErgoProver) {
    def asset: ErgoId = orderIn.id
    def collateral: Seq[Token] = order.tokens
  }

  protected def bondOf(ctx: BlockchainContext, kind: Kind): Contract =
    if (kind == ErgBond) scripts(ctx).ergBond else scripts(ctx).tokenBond

  protected def oracleRuleOf(ctx: BlockchainContext, kind: Kind): Contract =
    if (kind == ErgBond) scripts(ctx).ergOracle else scripts(ctx).tokenOracle

  protected def orderOf(ctx: BlockchainContext, kind: Kind): Contract =
    if (kind == ErgBond) scripts(ctx).ergOrder else scripts(ctx).tokenOrder

  /** The offer, asks, expressions and Dutch mint compiled for this kind's repayment asset. */
  protected def marketOf(ctx: BlockchainContext, kind: Kind): Market =
    if (kind == ErgBond) exchange(ctx).erg else exchange(ctx).token

  /** A `Coll[Coll[Byte]]` register of UTF-8 strings, such as share EIP-4 metadata; empty with no parts. */
  protected def textsValue(parts: String*): ErgoValue[_] =
    ErgoValue.of(Colls.fromArray(parts.map(p => Colls.fromArray(p.getBytes("UTF-8"))).toArray)(
      sigma.collRType(sigma.ByteType)), ErgoType.collType(org.ergoplatform.appkit.scalaapi.scalaByteType))

  /** The smallest x with x * den >= num, for positive den. */
  protected def ceilDiv(num: BigInt, den: BigInt): Long = ((num + den - 1) / den).toLong

  /**
   * `fixedMaturity` overrides a fixed-height order's maturity. `extra` is the borrower payload from
   * index 7: the oracle rule reads its ratio and maximum age there, and a fill guard its minimum at 9.
   * `guard` puts a fill guard expression's hash in the order's R8.
   */
  protected def flow(ctx: BlockchainContext, kind: Kind = TokenBond, style: OrderStyle = FixedHeight,
                     collateralValue: Long = collateralErg, collateral: Seq[Token] = defaultCollateral,
                     expiryFee: Long = 2000000L, expiry: Long = -1L, fee: Long = batchingFee,
                     duration: Long = 1000L, fixedMaturity: Long = -1L,
                     principalValue: Long = -1L, principalAssets: Seq[Token] = null,
                     repayment: Long = -1L, extra: Seq[Long] = Seq(15000L, 30L, 777L),
                     liq: Contract = null, ergFloor: Long = -1L, guard: Contract = null): Flow = {
    val s = scripts(ctx)
    val height = ctx.getHeight + 10
    val maturity =
      if (style == OnClose) height.toLong + duration
      else if (fixedMaturity >= 0L) fixedMaturity else ctx.getHeight.toLong + 1000L
    val deadline = if (expiry < 0L) ctx.getHeight.toLong + 500L else expiry
    val principalERG = if (principalValue >= 0L) principalValue else if (kind == ErgBond) principalErg else minBox
    val principalTokens = if (principalAssets != null) principalAssets else
      if (kind == ErgBond) Seq.empty[Token] else Seq(Token(SigUSD, 600L))
    val repaymentAmount = if (repayment >= 0L) repayment else if (kind == ErgBond) repaymentErg else 700L
    val floor = if (ergFloor >= 0L) ergFloor else if (kind == ErgBond) repaymentAmount else minBox
    val expression = if (liq == null) s.maturity else liq
    val data = Seq(repaymentAmount, principalERG, floor,
      if (style == FixedHeight) maturity else duration, deadline, expiryFee,
      if (style == FixedHeight) 0L else 1L) ++ extra
    val borrowerHash = bytesValue(hashOf(borrowerTree(ctx)))
    val liqHash = bytesValue(expression.hashedValueBytes)
    val order = UTXO(orderOf(ctx, kind), collateralValue + expiryFee, collateral,
      Seq(longsValue(data: _*), Protocol.tokensValue(principalTokens), borrowerHash, liqHash) ++
        Option(guard).map(g => bytesValue(g.hashedValueBytes)).toSeq)
    val input = inputAt(order, ctx, 0)
    // Created at the start height, as the order requires: the bond's recollateralize wait counts from it.
    val bond = UTXO(bondOf(ctx, kind), collateralValue + fee, Token(input.id, 2L) +: collateral,
      Seq(bytesValue(input.id.getBytes), borrowerHash, longsValue(data: _*),
        Protocol.tokensValue(principalTokens), liqHash, longsValue(fee, height.toLong, maturity)), Some(height))
    Flow(ctx, kind, style, order, input,
      funding(ctx, lender(ctx), 1, principalERG + fee + slack, principalTokens),
      UTXO(lenderTree(ctx), minBox, Seq(Token(input.id, 1L)), eip4()),
      bond, UTXO(borrowerTree(ctx), principalERG + expiryFee, principalTokens), data,
      height, height.toLong, maturity, deadline, collateralValue, expiryFee, fee, expression,
      borrower(ctx), lender(ctx))
  }

  /** The same flow filled at another height: the bond's start, and an on-close maturity, move with it. */
  protected def moveFill(f: Flow, height: Int): Flow = {
    val maturity = if (f.style == FixedHeight) f.maturity else height.toLong + f.data(3)
    f.copy(height = height, start = height.toLong, maturity = maturity,
      bond = f.bond.withRegNum(9, longsValue(f.fee, height.toLong, maturity)).setCreationHeight(height))
  }

  /** A bond starting at `start`: R9's start height and the box's creation height, which must agree. */
  protected def startingAt(f: Flow, start: Long, maturity: Long = -1L): UTXO =
    f.bond.withRegNum(9, longsValue(f.fee, start, if (maturity < 0L) f.maturity else maturity))
      .setCreationHeight(start.toInt)

  protected def standardFillTx(f: Flow, unit: UTXO = null, bond: UTXO = null, principal: UTXO = null,
                               inputs: Seq[InputUTXO] = null, height: Int = -1): UnsignedTransaction =
    buildAt(f.ctx, if (height < 0) f.height else height,
      if (inputs == null) Seq(f.orderIn, f.fund) else inputs,
      Seq(if (unit == null) f.unit else unit, if (bond == null) f.bond else bond,
        if (principal == null) f.principal else principal), f.holder.getAddress)

  /** Outputs of a fill: 0 the circulating unit, 1 the bond, 2 the principal. */
  protected def filled(f: Flow): SignedTransaction = accepts(f.holder, standardFillTx(f))

  // ─── settlement ───────────────────────────────────────────────────────────

  /** A settlement at the vault: the bond's 2-unit reserve first, then the proceeds. */
  protected def vaultBox(f: Flow, bondId: ErgoId, value: Long, tokens: Seq[Token], path: Byte): UTXO =
    UTXO(scripts(f.ctx).vault, value, Token(f.asset, 2L) +: tokens,
      Seq(bytesValue(bondId.getBytes), ErgoValue.of(path)))

  protected def repaymentAssets(f: Flow): Seq[Token] =
    if (f.kind == ErgBond) Seq.empty[Token] else Seq(Token(SigUSD, f.data(0)))

  protected def repaymentValue(f: Flow): Long =
    if (f.kind == ErgBond) f.data(0) + f.fee else minBox + f.fee

  protected def repayInput(bond: InputUTXO): InputUTXO =
    bond.withCtxVar(1.toByte, ErgoValue.of(0)).withCtxVar(2.toByte, ErgoValue.of(1))

  protected def repaidBox(f: Flow, bond: InputUTXO): UTXO =
    vaultBox(f, bond.id, repaymentValue(f), repaymentAssets(f), 0.toByte)

  protected def returnedBox(f: Flow, bond: InputUTXO): UTXO =
    tagged(borrowerTree(f.ctx), bond.value - f.fee, bond.id, bond.tokens.drop(1))

  /**
   * `bond` must already carry the repayment context vars. Outputs: 0 the vault, 1 the collateral return.
   * The payer funds it and takes the change; by default that is the borrower, whose box is then a
   * co-spend that lets the return keep back up to the repayment.
   */
  protected def standardRepayTx(f: Flow, bond: InputUTXO, settle: UTXO = null, returned: UTXO = null,
                                height: Int = -1, payer: ErgoProver = null,
                                funds: Seq[Token] = null): UnsignedTransaction = {
    val p = if (payer == null) f.owner else payer
    buildAt(f.ctx, if (height < 0) (f.maturity - 1L).toInt else height,
      // One unit of an unrelated token rides along, for negatives that try to slip it into the vault.
      Seq(bond, funding(f.ctx, p, 7, slack,
        (if (funds == null) repaymentAssets(f) else funds) :+ Token(secondCollateral, 1L))),
      Seq(if (settle == null) repaidBox(f, bond) else settle,
        if (returned == null) returnedBox(f, bond) else returned), p.getAddress)
  }

  // ─── recollateralize ──────────────────────────────────────────────────────

  /** Blocks a bond must rest between moves, and how old the successor's creation stamp may be. */
  protected val recollateralizeWait: Int = 60
  protected val stampSlack: Int = 15

  /**
   * The borrower (or `by`) moves the bond on to `next`, output 0, at `height`. The successor is
   * stamped at `stamp`, by default the inclusion height itself. `funds` arrive on the funding input.
   */
  protected def recollateralizeTx(f: Flow, bond: InputUTXO, next: UTXO, height: Int, by: ErgoProver = null,
                                  stamp: Int = -1, funds: Seq[Token] = Seq.empty[Token]): UnsignedTransaction = {
    val p = if (by == null) f.owner else by
    buildAt(f.ctx, height, Seq(bond.withCtxVar(3.toByte, ErgoValue.of(0)), funding(f.ctx, p, 6, slack, funds)),
      Seq(next.setCreationHeight(if (stamp < 0) height else stamp)), p.getAddress)
  }

  protected def liquidationInput(f: Flow, bond: InputUTXO): InputUTXO =
    bond.withCtxVar(0.toByte, liqVar(f.liq)).withCtxVar(1.toByte, ErgoValue.of(0))

  protected def proceedsBox(f: Flow, bond: InputUTXO): UTXO =
    vaultBox(f, bond.id, bond.value - f.fee, bond.tokens.drop(1), 1.toByte)

  /** Fired by the executor with the bond alone: the lender's fee reserve pays the miner and the executor. */
  protected def standardLiquidationTx(f: Flow, bond: InputUTXO, data: Seq[InputUTXO] = Seq.empty,
                                      out: UTXO = null, height: Int = -1,
                                      inputs: Seq[InputUTXO] = null): UnsignedTransaction =
    buildAt(f.ctx, if (height < 0) f.maturity.toInt else height, if (inputs == null) Seq(bond) else inputs,
      Seq(if (out == null) proceedsBox(f, bond) else out), executor(f.ctx).getAddress, dataInputs = data)

  /** The holder presents the circulating unit and burns all 3 units to take the vault. */
  protected def standardClaimTx(f: Flow, vaultIn: InputUTXO, unitIn: InputUTXO, burn: Long = 3L): UnsignedTransaction =
    build(f.ctx, Seq(vaultIn, unitIn, funding(f.ctx, f.holder, 8)),
      Seq(UTXO(lenderTree(f.ctx), vaultIn.value, vaultIn.tokens.drop(1))), f.holder.getAddress,
      burn = Seq(Token(f.asset, burn)))

  protected def oracleBox(f: Flow, price: Long = 500000000L, epoch: Int = 1,
                          created: Int = -1, nft: ErgoId = OracleNFT, amount: Long = 1L): InputUTXO =
    inputAt(UTXO(Contract.SIGMA_TRUE, minBox, Seq(Token(nft, amount)),
      Seq(ErgoValue.of(price), ErgoValue.of(epoch)), Some(if (created < 0) f.height else created)), f.ctx, 20)

  /** The first output of `tx` at `tree` holding `token`: where change carrying leftover tokens landed. */
  protected def outputWith(tx: SignedTransaction, token: ErgoId, tree: Contract): InputUTXO =
    InputUTXO(tx.getOutputsToSpend.asScala.find { b =>
      b.getErgoTree == tree.ergoTree && b.getTokens.asScala.exists(_.getId == token)
    }.get)
}
