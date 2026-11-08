package org.lithosprotocol.sigmafi
package specs.bonds

import ksingh.sigmabonds.client.ScriptGenerator
import ksingh.sigmabonds.client.ScriptGenerator.{ContractTemplate, OnCloseOrderTerms, OrderTerms}
import org.ergoplatform.appkit._
import org.ergoplatform.appkit.scalaapi._
import org.ergoplatform.sdk.ErgoId
import scorex.crypto.hash.Blake2b256
import sigma.Colls
import specs.harness.ContractSpecBase
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import java.math.BigInteger

object BondSpecBase {

  /** Ids belonging to nothing. Never reach for a real protocol id in a "some other token" case. */
  val stableToken: ErgoId = ErgoId.create("cc" * 32)
  val collateralToken: ErgoId = ErgoId.create("aa" * 32)
  val secondCollateral: ErgoId = ErgoId.create("bb" * 32)

  /** Stand in for bond assets a past mint produced, in specs that start from a live bond. */
  val bondAsset: ErgoId = ErgoId.create("f00d" * 16)
  val otherBondAsset: ErgoId = ErgoId.create("f11d" * 16)

  /**
   * Liquidation expressions. `matured` is MaturityLiquidation, SigmaFi V1's rule, compiled from its
   * contract file; `alwaysTrue` stands in for an attacker's script that would pass but was never agreed
   * to; `neverTrue` is a listed script that fails.
   */
  case class Compiled(ergBond: Contract, tokenBond: Contract, vault: Contract,
                      orderTemplate: ContractTemplate,
                      onCloseTemplate: ContractTemplate,
                      matured: Contract, alwaysTrue: Contract, neverTrue: Contract, converted: Contract)

  private var cache: Option[Compiled] = None

  /** Compiled once per JVM through the client's own path: the sigma compiler mutates shared AST nodes. */
  def compiled(ctx: BlockchainContext): Compiled = synchronized {
    cache.getOrElse {
      def expr(src: String) = Contract.fromErgoContract(ctx.compileContract(ConstantsBuilder.empty(), src))
      val c = Compiled(
        Contract.fromErgoContract(ScriptGenerator.mkZcBondContract(ctx, None)),
        Contract.fromErgoContract(ScriptGenerator.mkZcBondContract(ctx, Some(stableToken.toString))),
        Contract.fromErgoContract(ScriptGenerator.mkBondAssetVault(ctx)),
        ScriptGenerator.mkOrderTemplate(ctx),
        ScriptGenerator.mkOnCloseOrderTemplate(ctx),
        Contract(ScriptGenerator.mkLiquidationScript(ctx, "MaturityLiquidation").tree),
        expr("{ sigmaProp(HEIGHT > 0) }"),
        expr("{ sigmaProp(HEIGHT < 0) }"),
        expr("""{
          val payout = OUTPUTS(getVar[Int](1).get)
          val principal = SELF.R7[Coll[(Coll[Byte], Long)]].get
          val principalERG = SELF.R6[Coll[Long]].get(2)
          val principalMatches = if (principal.size == 0) principalERG == 6000000000L else
            principal.size == 1 && principalERG == 1000000L && principal(0)._2 == 600L &&
            principal(0)._1 == fromBase16("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")
          sigmaProp(principalMatches && HEIGHT.toLong >= SELF.R6[Coll[Long]].get(3) && payout.tokens.size == 0 &&
            payout.value >= SELF.value + 2000000000L)
        }"""))
      cache = Some(c)
      c
    }
  }

  sealed trait Kind
  case object ErgBond extends Kind
  case object TokenBond extends Kind

  /** A fixed-height order names its maturity; an on-close order names a duration counted from the fill. */
  sealed trait OrderStyle
  case object FixedHeight extends OrderStyle
  case object OnClose extends OrderStyle
}

trait BondSpecBase extends ContractSpecBase {

  import BondSpecBase._

  // ─── contracts ────────────────────────────────────────────────────────────

  protected def bondContract(ctx: BlockchainContext, kind: Kind): Contract = kind match {
    case ErgBond => compiled(ctx).ergBond
    case TokenBond => compiled(ctx).tokenBond
  }
  protected def vault(ctx: BlockchainContext): Contract = compiled(ctx).vault
  protected def orderTemplate(ctx: BlockchainContext): ContractTemplate = compiled(ctx).orderTemplate
  protected def onCloseTemplate(ctx: BlockchainContext): ContractTemplate = compiled(ctx).onCloseTemplate
  protected def matured(ctx: BlockchainContext): Contract = compiled(ctx).matured
  protected def alwaysTrue(ctx: BlockchainContext): Contract = compiled(ctx).alwaysTrue
  protected def neverTrue(ctx: BlockchainContext): Contract = compiled(ctx).neverTrue
  protected def converted(ctx: BlockchainContext): Contract = compiled(ctx).converted

  // ─── identities ───────────────────────────────────────────────────────────

  protected def borrower(ctx: BlockchainContext): ErgoProver = proverWith(ctx, BigInteger.valueOf(1001L))
  protected def lender(ctx: BlockchainContext): ErgoProver = proverWith(ctx, BigInteger.valueOf(2002L))
  /** No relationship to either party. Liquidation must work for it. */
  protected def executor(ctx: BlockchainContext): ErgoProver = proverWith(ctx, BigInteger.valueOf(3003L))
  protected def other(ctx: BlockchainContext): ErgoProver = proverWith(ctx, BigInteger.valueOf(4004L))
  protected def borrowerTree(ctx: BlockchainContext): Contract = contractOf(borrower(ctx))
  protected def lenderTree(ctx: BlockchainContext): Contract = contractOf(lender(ctx))
  protected def otherTree(ctx: BlockchainContext): Contract = contractOf(other(ctx))

  // ─── register values ──────────────────────────────────────────────────────

  protected def bytesValue(bytes: Array[Byte]): ErgoValue[_] = ErgoValue.of(Colls.fromArray(bytes), scalaByteType)
  protected def longsValue(v: Long*): ErgoValue[_] = ErgoValue.of(Colls.fromArray(v.toArray), scalaLongType)
  protected def hashOf(c: Contract): Array[Byte] = Blake2b256.hash(c.propBytes)
  protected def liqVar(script: Contract): ErgoValue[_] = bytesValue(script.valueBytes)

  // ─── amounts ──────────────────────────────────────────────────────────────

  protected val minBox: Long = 1000000L
  protected val collateralErg: Long = 10L * Parameters.OneErg
  protected val repaymentErg: Long = 7L * Parameters.OneErg
  protected val repaymentTokens: Long = 700L
  protected val principalErg: Long = 6L * Parameters.OneErg
  protected val principalTokens: Long = 600L
  protected val slack: Long = 20L * Parameters.OneErg
  protected val defaultCollateral: Seq[Token] = Seq(Token(collateralToken, 100L))
  protected val originOrderId: Array[Byte] = Array.fill(32)(9.toByte)

  /** Above MinFee on purpose: a fee at or below the miner fee pays nobody to act. */
  protected val batchingFee: Long = 5L * Parameters.MinFee

  protected def maturityOf(ctx: BlockchainContext): Int = ctx.getHeight + 1000
  protected def repaymentOf(kind: Kind): Long = kind match {
    case ErgBond => repaymentErg
    case TokenBond => repaymentTokens
  }

  // ─── box builders ─────────────────────────────────────────────────────────

  protected def bondUTXO(ctx: BlockchainContext,
                         kind: Kind,
                         asset: ErgoId = bondAsset,
                         value: Long = collateralErg,
                         collateral: Seq[Token] = defaultCollateral,
                         borrowerScript: Contract = null,
                         fee: Long = batchingFee,
                         maturity: Int = -1,
                         liqScript: Contract = null,
                         origin: Array[Byte] = originOrderId): UTXO =
    UTXO(bondContract(ctx, kind), value + fee, Seq(Token(asset, 1L)) ++ collateral,
      Seq(
        bytesValue(origin),
        bytesValue(hashOf(if (borrowerScript == null) borrowerTree(ctx) else borrowerScript)),
        longsValue(repaymentOf(kind), fee, principalOf(kind)._1, if (maturity < 0) maturityOf(ctx).toLong else maturity.toLong),
        ksingh.sigmabonds.app.Protocol.tokensValue(principalOf(kind)._2),
        bytesValue((if (liqScript == null) matured(ctx) else liqScript).hashedValueBytes)))

  /** A collateral return: names the bond it settles in R4. */
  protected def tagged(to: Contract, value: Long, bondId: ErgoId, tokens: Seq[Token] = Seq.empty[Token]): UTXO =
    UTXO(to, value, tokens, Seq(bytesValue(bondId.getBytes)))

  /** A lender's settlement: names the bond in R4 and, for the vault, the asset that claims it in R5. */
  protected def settlement(to: Contract, value: Long, bondId: ErgoId, asset: ErgoId,
                           tokens: Seq[Token] = Seq.empty[Token], path: Byte = 1.toByte): UTXO =
    UTXO(to, value, tokens, Seq(bytesValue(bondId.getBytes), bytesValue(asset.getBytes), ErgoValue.of(path)))

  protected def funding(ctx: BlockchainContext, prover: ErgoProver, index: Int,
                        value: Long = slack, tokens: Seq[Token] = Seq.empty[Token]): InputUTXO =
    inputAt(UTXO(contractOf(prover), value, tokens), ctx, index)

  /** What a payer must bring to repay: nothing extra for ERG, the repayment token for a token bond. */
  protected def repaymentFunds(kind: Kind): Seq[Token] = kind match {
    case ErgBond => Seq.empty[Token]
    case TokenBond => Seq(Token(stableToken, 1000L))
  }

  protected def repaymentBox(kind: Kind, to: Contract, bondId: ErgoId, asset: ErgoId = bondAsset,
                             short: Long = 0L): UTXO = kind match {
    case ErgBond => settlement(to, repaymentErg + batchingFee - short, bondId, asset, path = 0.toByte)
    case TokenBond => settlement(to, minBox + batchingFee, bondId, asset, Seq(Token(stableToken, repaymentTokens - short)), path = 0.toByte)
  }

  // ═══ REPAY ════════════════════════════════════════════════════════════════

  protected case class Repay(ctx: BlockchainContext,
                             kind: Kind,
                             bond: UTXO,
                             bondIn: InputUTXO,
                             payerIn: InputUTXO,
                             lenderScript: Contract,
                             settleOut: UTXO,
                             returnOut: UTXO,
                             maturity: Int,
                             prover: ErgoProver)

  /** No var 0: its absence selects repayment; vars 1 and 2 map outputs. */
  protected def repayScenario(ctx: BlockchainContext,
                              kind: Kind,
                              collateral: Seq[Token] = defaultCollateral,
                              payer: ErgoProver = null): Repay = {
    val p = if (payer == null) borrower(ctx) else payer
    val bond = bondUTXO(ctx, kind, collateral = collateral)
    val bondIn = inputAt(bond, ctx, 0).withCtxVar(1.toByte, ErgoValue.of(0)).withCtxVar(2.toByte, ErgoValue.of(1))
    Repay(ctx, kind, bond, bondIn, funding(ctx, p, 1, slack, repaymentFunds(kind)), vault(ctx),
      repaymentBox(kind, vault(ctx), bondIn.id),
      tagged(borrowerTree(ctx), bond.value - batchingFee, bondIn.id, collateral),
      maturityOf(ctx), p)
  }

  protected def repayTx(r: Repay)(settleOut: UTXO = r.settleOut,
                                  returnOut: UTXO = r.returnOut,
                                  inputs: Seq[InputUTXO] = null,
                                  outputs: Seq[UTXO] = null,
                                  burn: Seq[Token] = Seq(Token(bondAsset, 1L)),
                                  height: Int = -1): UnsignedTransaction =
    buildAt(r.ctx, if (height < 0) r.maturity - 1 else height,
      if (inputs == null) Seq(r.bondIn, r.payerIn) else inputs,
      if (outputs == null) Seq(settleOut, returnOut) else outputs,
      r.prover.getAddress, Seq.empty[InputUTXO], burn)

  // ═══ LIQUIDATE ════════════════════════════════════════════════════════════

  protected case class Liquidate(ctx: BlockchainContext,
                                 kind: Kind,
                                 bond: UTXO,
                                 bondIn: InputUTXO,
                                 lenderScript: Contract,
                                 settleOut: UTXO,
                                 maturity: Int,
                                 prover: ErgoProver)

  /**
   * Fired by the executor with the bond as its only input: the batching fee pays the miner fee and the
   * rest comes back as the executor's change. `listed` is the script R8 commits to, `ran` the one
   * supplied in var 0.
   */
  protected def liquidateScenario(ctx: BlockchainContext,
                                  kind: Kind,
                                  collateral: Seq[Token] = defaultCollateral,
                                  fee: Long = batchingFee,
                                  listed: Contract = null,
                                  ran: Contract = null): Liquidate = {
    val maturity = maturityOf(ctx)
    val listedScript = if (listed == null) matured(ctx) else listed
    val bond = bondUTXO(ctx, kind, collateral = collateral, fee = fee, maturity = maturity, liqScript = listedScript)
    val bondIn = inputAt(bond, ctx, 0).withCtxVar(0.toByte, liqVar(if (ran == null) listedScript else ran)).withCtxVar(1.toByte, ErgoValue.of(0))
    Liquidate(ctx, kind, bond, bondIn, vault(ctx),
      settlement(vault(ctx), bond.value - fee, bondIn.id, bondAsset, collateral), maturity, executor(ctx))
  }

  protected def liquidateTx(l: Liquidate)(settleOut: UTXO = l.settleOut,
                                          inputs: Seq[InputUTXO] = null,
                                          outputs: Seq[UTXO] = null,
                                          burn: Seq[Token] = Seq(Token(bondAsset, 1L)),
                                          height: Int = -1,
                                          changeTo: Address = null): UnsignedTransaction =
    buildAt(l.ctx, if (height < 0) l.maturity else height,
      if (inputs == null) Seq(l.bondIn) else inputs,
      if (outputs == null) Seq(settleOut) else outputs,
      if (changeTo == null) l.prover.getAddress else changeTo, Seq.empty[InputUTXO], burn)

  protected val tokenName: String = "SigmaFi Bond"
  protected val tokenDescription: String = "Zero-coupon ERG bond. Claims its BondAssetVault."

  protected def utf8(s: String): ErgoValue[_] = bytesValue(s.getBytes("UTF-8"))

  /** EIP-4 issuance registers R4-R6, on the lender's unit. */
  protected def eip4(name: String = tokenName, description: String = tokenDescription,
                     decimals: String = "0"): Seq[ErgoValue[_]] =
    Seq(utf8(name), utf8(description), utf8(decimals))

  protected def principalOf(kind: Kind): (Long, Seq[Token]) = kind match {
    case ErgBond => (principalErg, Seq.empty[Token])
    case TokenBond => (minBox, Seq(Token(stableToken, principalTokens)))
  }

  // ═══ ORDER ════════════════════════════════════════════════════════════════

  protected def orderTerms(ctx: BlockchainContext,
                           kind: Kind,
                           maturity: Int = -1): OrderTerms = {
    val (value, tokens) = principalOf(kind)
    OrderTerms(
      bondHash = hashOf(bondContract(ctx, kind)),
      borrowerHash = hashOf(borrowerTree(ctx)),
      principalErg = value,
      principalTokens = tokens.map(t => t.id.getBytes -> t.amount),
      repayment = repaymentOf(kind),
      maturity = if (maturity < 0) maturityOf(ctx) else maturity,
      liqScriptHash = matured(ctx).hashedValueBytes)
  }

  protected def orderContract(ctx: BlockchainContext, terms: OrderTerms): Contract =
    Contract(orderTemplate(ctx).withTerms(terms))

  protected val defaultDuration: Int = 1000

  /** On-close fills run at an explicit height, since the bond's maturity is counted from it. */
  protected def fillHeightOf(ctx: BlockchainContext): Int = ctx.getHeight + 10

  protected def onCloseTerms(ctx: BlockchainContext,
                             kind: Kind,
                             duration: Int = defaultDuration): OnCloseOrderTerms = {
    val t = orderTerms(ctx, kind)
    OnCloseOrderTerms(t.bondHash, t.borrowerHash, t.principalErg, t.principalTokens, t.repayment,
      duration, t.liqScriptHash)
  }

  protected def onCloseContract(ctx: BlockchainContext, terms: OnCloseOrderTerms): Contract =
    Contract(onCloseTemplate(ctx).withTerms(terms))

  /**
   * One fill of either order style. `maturity` is what the bond carries in R6(3), `height` the height the
   * fill runs at: -1 means the mocked context's own, which is all a fixed-height order needs.
   */
  protected case class Fill(ctx: BlockchainContext,
                            kind: Kind,
                            style: OrderStyle,
                            order: UTXO,
                            orderIn: InputUTXO,
                            lenderIn: InputUTXO,
                            asset: ErgoId,
                            unitOut: UTXO,
                            bondOut: UTXO,
                            principalOut: UTXO,
                            repayment: Long,
                            fee: Long,
                            maturity: Int,
                            height: Int,
                            prover: ErgoProver)

  /**
   * The borrower's collateral sits in the order; the lender fills it with the order at INPUTS(0), which
   * mints the asset. The lender brings the principal, and keeps a unit with EIP-4 metadata at OUTPUTS(0).
   * An on-close fill stamps R6(3) as fill height + duration.
   */
  protected def fillScenario(ctx: BlockchainContext,
                             kind: Kind,
                             fee: Long = batchingFee,
                             style: OrderStyle = FixedHeight,
                             duration: Int = defaultDuration,
                             liqScript: Contract = null): Fill = {
    val liq = if (liqScript == null) matured(ctx) else liqScript
    val (script, maturity, height) = style match {
      case FixedHeight =>
        val terms = orderTerms(ctx, kind).copy(liqScriptHash = liq.hashedValueBytes)
        (orderContract(ctx, terms), terms.maturity.toInt, -1)
      case OnClose =>
        val terms = onCloseTerms(ctx, kind, duration).copy(liqScriptHash = liq.hashedValueBytes)
        (onCloseContract(ctx, terms), fillHeightOf(ctx) + duration, fillHeightOf(ctx))
    }
    val order = UTXO(script, collateralErg, defaultCollateral)
    val orderIn = inputAt(order, ctx, 0).withCtxVar(0.toByte, ErgoValue.of(fee))
    val asset = orderIn.id
    val (value, tokens) = principalOf(kind)
    val l = lender(ctx)
    Fill(ctx, kind, style, order, orderIn, funding(ctx, l, 1, slack, tokens), asset,
      UTXO(lenderTree(ctx), minBox, Seq(Token(asset, 1L)), eip4()),
      bondUTXO(ctx, kind, asset = asset, fee = fee, maturity = maturity, liqScript = liq, origin = orderIn.id.getBytes),
      UTXO(borrowerTree(ctx), value, tokens),
      repaymentOf(kind), fee, maturity, height, l)
  }

  protected def fillTx(f: Fill)(unitOut: UTXO = f.unitOut,
                                bondOut: UTXO = f.bondOut,
                                principalOut: UTXO = f.principalOut,
                                inputs: Seq[InputUTXO] = null,
                                outputs: Seq[UTXO] = null,
                                burn: Seq[Token] = Seq.empty[Token],
                                height: Int = f.height): UnsignedTransaction = {
    val ins = if (inputs == null) Seq(f.orderIn, f.lenderIn) else inputs
    val outs = if (outputs == null) Seq(unitOut, bondOut, principalOut) else outputs
    if (height < 0) build(f.ctx, ins, outs, f.prover.getAddress, Seq.empty[InputUTXO], burn)
    else buildAt(f.ctx, height, ins, outs, f.prover.getAddress, Seq.empty[InputUTXO], burn)
  }

  // ═══ CLAIM ════════════════════════════════════════════════════════════════

  protected case class Claim(ctx: BlockchainContext,
                             vaultIn: InputUTXO,
                             holderIn: InputUTXO,
                             payoutOut: UTXO,
                             asset: ErgoId,
                             prover: ErgoProver)

  /** A settled repayment sitting in the vault, claimed by whoever holds the circulating unit. */
  protected def claimScenario(ctx: BlockchainContext,
                              holderHasAsset: Boolean = true,
                              holder: ErgoProver = null): Claim = {
    val h = if (holder == null) lender(ctx) else holder
    val settled = settlement(vault(ctx), repaymentErg, ErgoId.create("ab" * 32), bondAsset)
    Claim(ctx, inputAt(settled, ctx, 0),
      funding(ctx, h, 1, Parameters.OneErg, if (holderHasAsset) Seq(Token(bondAsset, 1L)) else Seq.empty[Token]),
      UTXO(contractOf(h), repaymentErg), bondAsset, h)
  }

  protected def claimTx(c: Claim)(payoutOut: UTXO = c.payoutOut,
                                  inputs: Seq[InputUTXO] = null,
                                  burn: Seq[Token] = null): UnsignedTransaction =
    build(c.ctx,
      if (inputs == null) Seq(c.vaultIn, c.holderIn) else inputs,
      Seq(payoutOut),
      c.prover.getAddress, Seq.empty[InputUTXO],
      if (burn == null) Seq(Token(c.asset, 1L)) else burn)

  /** Spend an output of a signed transaction, so a handoff runs on the box the first contract made. */
  protected def spendOutput(tx: SignedTransaction, index: Int): InputUTXO =
    InputUTXO(tx.getOutputsToSpend.get(index))
}
