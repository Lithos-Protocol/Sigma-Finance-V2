package org.lithosprotocol.sigmafi
package specs.bonds

import ksingh.sigmabonds.client.ScriptGenerator
import ksingh.sigmabonds.client.ScriptGenerator.{ContractTemplate, OfferTerms, OnCloseOrderTerms, OrderTerms}
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
  case class Compiled(ergBond: Contract, tokenBond: Contract, generator: Contract, vault: Contract,
                      offerTemplate: ContractTemplate, orderTemplate: ContractTemplate,
                      onCloseTemplate: ContractTemplate,
                      matured: Contract, alwaysTrue: Contract, neverTrue: Contract)

  private var cache: Option[Compiled] = None

  /** Compiled once per JVM through the client's own path: the sigma compiler mutates shared AST nodes. */
  def compiled(ctx: BlockchainContext): Compiled = synchronized {
    cache.getOrElse {
      def expr(src: String) = Contract.fromErgoContract(ctx.compileContract(ConstantsBuilder.empty(), src))
      val c = Compiled(
        Contract.fromErgoContract(ScriptGenerator.mkZcBondContract(ctx, None)),
        Contract.fromErgoContract(ScriptGenerator.mkZcBondContract(ctx, Some(stableToken.toString))),
        Contract.fromErgoContract(ScriptGenerator.mkBondAssetGenerator(ctx)),
        Contract.fromErgoContract(ScriptGenerator.mkBondAssetVault(ctx)),
        ScriptGenerator.mkOfferTemplate(ctx),
        ScriptGenerator.mkOrderTemplate(ctx),
        ScriptGenerator.mkOnCloseOrderTemplate(ctx),
        Contract(ScriptGenerator.mkLiquidationScript(ctx, "MaturityLiquidation").tree),
        expr("{ sigmaProp(HEIGHT > 0) }"),
        expr("{ sigmaProp(HEIGHT < 0) }"))
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

/**
 * Harness for the zero-coupon bonds, the bond asset vault, the bond asset generator and the offer.
 *
 * Positions are load bearing:
 *
 *   repay       IN  bond(i), payer           OUT settlement(i) to lender, return(i+1) to borrower
 *   liquidate   IN  bond(i) + var 0          OUT settlement(i) to lender; the executor keeps the change
 *   mint        IN  generator(0)             OUT lender unit(0), order(1); the executor keeps the change
 *   refund      IN  generator, lender box    OUT anything
 *   close       IN  offer, borrower          OUT bond(0), principal anywhere
 *   cancel      IN  offer, lender's unit     OUT anything, both units burned
 *   claim       IN  vault, holder's unit     OUT anything, unit burned
 *
 * A lender's settlement box names its bond in R4 and the bond's asset in R5; the one generic vault
 * opens for whoever burns the asset R5 names. Borrower orders do not exist yet.
 */
trait BondSpecBase extends ContractSpecBase {

  import BondSpecBase._

  // ─── contracts ────────────────────────────────────────────────────────────

  protected def bondContract(ctx: BlockchainContext, kind: Kind): Contract = kind match {
    case ErgBond => compiled(ctx).ergBond
    case TokenBond => compiled(ctx).tokenBond
  }
  protected def generator(ctx: BlockchainContext): Contract = compiled(ctx).generator
  protected def vault(ctx: BlockchainContext): Contract = compiled(ctx).vault
  protected def offerTemplate(ctx: BlockchainContext): ContractTemplate = compiled(ctx).offerTemplate
  protected def orderTemplate(ctx: BlockchainContext): ContractTemplate = compiled(ctx).orderTemplate
  protected def onCloseTemplate(ctx: BlockchainContext): ContractTemplate = compiled(ctx).onCloseTemplate
  protected def matured(ctx: BlockchainContext): Contract = compiled(ctx).matured
  protected def alwaysTrue(ctx: BlockchainContext): Contract = compiled(ctx).alwaysTrue
  protected def neverTrue(ctx: BlockchainContext): Contract = compiled(ctx).neverTrue

  // ─── identities ───────────────────────────────────────────────────────────

  protected def borrower(ctx: BlockchainContext): ErgoProver = proverWith(ctx, BigInteger.valueOf(1001L))
  protected def lender(ctx: BlockchainContext): ErgoProver = proverWith(ctx, BigInteger.valueOf(2002L))
  /** No relationship to either party. Liquidation and minting must both work for it. */
  protected def executor(ctx: BlockchainContext): ErgoProver = proverWith(ctx, BigInteger.valueOf(3003L))
  protected def other(ctx: BlockchainContext): ErgoProver = proverWith(ctx, BigInteger.valueOf(4004L))
  /** Where a generator sends the principal in specs that are not about offers. */
  protected def orderStandIn(ctx: BlockchainContext): Contract = contractOf(proverWith(ctx, BigInteger.valueOf(5005L)))

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
  protected val executorFee: Long = 5L * Parameters.MinFee

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
                         lenderScript: Contract = null,
                         fee: Long = batchingFee,
                         maturity: Int = -1,
                         liqScript: Contract = null,
                         origin: Array[Byte] = originOrderId): UTXO =
    UTXO(bondContract(ctx, kind), value, Seq(Token(asset, 1L)) ++ collateral,
      Seq(
        bytesValue(origin),
        bytesValue(hashOf(if (borrowerScript == null) borrowerTree(ctx) else borrowerScript)),
        longsValue(repaymentOf(kind), fee),
        ErgoValue.of(if (maturity < 0) maturityOf(ctx) else maturity),
        bytesValue(hashOf(if (lenderScript == null) vault(ctx) else lenderScript)),
        bytesValue((if (liqScript == null) matured(ctx) else liqScript).hashedValueBytes)))

  /** A collateral return: names the bond it settles in R4. */
  protected def tagged(to: Contract, value: Long, bondId: ErgoId, tokens: Seq[Token] = Seq.empty[Token]): UTXO =
    UTXO(to, value, tokens, Seq(bytesValue(bondId.getBytes)))

  /** A lender's settlement: names the bond in R4 and, for the vault, the asset that claims it in R5. */
  protected def settlement(to: Contract, value: Long, bondId: ErgoId, asset: ErgoId,
                           tokens: Seq[Token] = Seq.empty[Token]): UTXO =
    UTXO(to, value, tokens, Seq(bytesValue(bondId.getBytes), bytesValue(asset.getBytes)))

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
    case ErgBond => settlement(to, repaymentErg - short, bondId, asset)
    case TokenBond => settlement(to, minBox, bondId, asset, Seq(Token(stableToken, repaymentTokens - short)))
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

  /** No context variable: its absence is what selects the repay path. */
  protected def repayScenario(ctx: BlockchainContext,
                              kind: Kind,
                              collateral: Seq[Token] = defaultCollateral,
                              payer: ErgoProver = null): Repay = {
    val p = if (payer == null) borrower(ctx) else payer
    val bond = bondUTXO(ctx, kind, collateral = collateral)
    val bondIn = inputAt(bond, ctx, 0)
    Repay(ctx, kind, bond, bondIn, funding(ctx, p, 1, slack, repaymentFunds(kind)), vault(ctx),
      repaymentBox(kind, vault(ctx), bondIn.id),
      tagged(borrowerTree(ctx), bond.value, bondIn.id, collateral),
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
   * rest comes back as the executor's change. `listed` is the script R9 commits to, `ran` the one
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
    val bondIn = inputAt(bond, ctx, 0).withCtxVar(0.toByte, liqVar(if (ran == null) listedScript else ran))
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

  // ═══ MINT / REFUND ════════════════════════════════════════════════════════

  protected val tokenName: String = "SigmaFi Bond"
  protected val tokenDescription: String = "Zero-coupon ERG bond. Claims its BondAssetVault."

  protected def utf8(s: String): ErgoValue[_] = bytesValue(s.getBytes("UTF-8"))

  /** EIP-4 issuance registers R4-R6, as the generator requires them on the lender's unit. */
  protected def eip4(name: String = tokenName, description: String = tokenDescription,
                     decimals: String = "0"): Seq[ErgoValue[_]] =
    Seq(utf8(name), utf8(description), utf8(decimals))

  /**
   * `withMetadata = false` leaves off R7-R8, as a generator made before they existed would. The value
   * covers the principal, the lender unit's box and the executor fee.
   */
  protected def generatorUTXO(ctx: BlockchainContext,
                              tokens: Seq[Token] = Seq.empty[Token],
                              fee: Long = executorFee,
                              lenderScript: Contract = null,
                              withMetadata: Boolean = true,
                              orderScript: Contract = null,
                              principal: Long = principalErg): UTXO =
    UTXO(generator(ctx), principal + minBox + executorFee, tokens,
      Seq(
        bytesValue(hashOf(if (orderScript == null) orderStandIn(ctx) else orderScript)),
        bytesValue(hashOf(if (lenderScript == null) lenderTree(ctx) else lenderScript)),
        ErgoValue.of(fee)) ++
        (if (withMetadata) Seq(utf8(tokenName), utf8(tokenDescription)) else Seq.empty[ErgoValue[_]]))

  protected case class Mint(ctx: BlockchainContext,
                            gen: UTXO,
                            genIn: InputUTXO,
                            asset: ErgoId,
                            lenderOut: UTXO,
                            orderOut: UTXO,
                            prover: ErgoProver)

  /** The executor spends the generator alone: the fee covers the miner fee and leaves it the rest. */
  protected def mintScenario(ctx: BlockchainContext,
                             genTokens: Seq[Token] = Seq.empty[Token],
                             fee: Long = executorFee,
                             orderScript: Contract = null,
                             principal: Long = principalErg): Mint = {
    val gen = generatorUTXO(ctx, genTokens, fee, orderScript = orderScript, principal = principal)
    val genIn = inputAt(gen, ctx, 0)
    val asset = genIn.id
    Mint(ctx, gen, genIn, asset,
      UTXO(lenderTree(ctx), minBox, Seq(Token(asset, 1L)), eip4()),
      UTXO(if (orderScript == null) orderStandIn(ctx) else orderScript,
        gen.value - minBox - fee, Seq(Token(asset, 1L)) ++ genTokens),
      executor(ctx))
  }

  protected def mintTx(m: Mint)(lenderOut: UTXO = m.lenderOut,
                                orderOut: UTXO = m.orderOut,
                                inputs: Seq[InputUTXO] = null,
                                outputs: Seq[UTXO] = null,
                                burn: Seq[Token] = Seq.empty[Token]): UnsignedTransaction =
    build(m.ctx,
      if (inputs == null) Seq(m.genIn) else inputs,
      if (outputs == null) Seq(lenderOut, orderOut) else outputs,
      m.prover.getAddress, Seq.empty[InputUTXO], burn)

  // ═══ OFFER ════════════════════════════════════════════════════════════════

  protected def offerTerms(ctx: BlockchainContext,
                           kind: Kind,
                           collateral: Seq[Token] = defaultCollateral,
                           fee: Long = batchingFee,
                           collateralValue: Long = collateralErg): OfferTerms =
    OfferTerms(
      bondHash = hashOf(bondContract(ctx, kind)),
      repayment = repaymentOf(kind),
      batchingFee = fee,
      maturity = maturityOf(ctx),
      liqScriptHash = matured(ctx).hashedValueBytes,
      collateralErg = collateralValue,
      collateralTokens = collateral.map(t => t.id.getBytes -> t.amount))

  protected def offerContract(ctx: BlockchainContext, terms: OfferTerms): Contract =
    Contract(offerTemplate(ctx).withTerms(terms))

  /** What sits in an offer beside its unit: nanoERG for an ERG loan, the loan token for a token loan. */
  protected def principalOf(kind: Kind): (Long, Seq[Token]) = kind match {
    case ErgBond => (principalErg, Seq.empty[Token])
    case TokenBond => (minBox, Seq(Token(stableToken, principalTokens)))
  }

  protected case class Close(ctx: BlockchainContext,
                             kind: Kind,
                             terms: OfferTerms,
                             offer: UTXO,
                             offerIn: InputUTXO,
                             borrowerIn: InputUTXO,
                             bondOut: UTXO,
                             principalOut: UTXO,
                             prover: ErgoProver)

  /** The borrower brings the collateral, creates the bond at OUTPUTS(0) and takes the principal. */
  protected def closeScenario(ctx: BlockchainContext,
                              kind: Kind,
                              fee: Long = batchingFee): Close = {
    val terms = offerTerms(ctx, kind, fee = fee)
    val (value, tokens) = principalOf(kind)
    val offer = UTXO(offerContract(ctx, terms), value, Seq(Token(bondAsset, 1L)) ++ tokens)
    val offerIn = inputAt(offer, ctx, 0)
    val b = borrower(ctx)
    Close(ctx, kind, terms, offer, offerIn, funding(ctx, b, 1, slack, defaultCollateral),
      bondUTXO(ctx, kind, fee = fee, origin = offerIn.id.getBytes),
      UTXO(borrowerTree(ctx), value, tokens), b)
  }

  protected def closeTx(c: Close)(bondOut: UTXO = c.bondOut,
                                  principalOut: UTXO = c.principalOut,
                                  inputs: Seq[InputUTXO] = null,
                                  outputs: Seq[UTXO] = null,
                                  burn: Seq[Token] = Seq.empty[Token]): UnsignedTransaction =
    build(c.ctx,
      if (inputs == null) Seq(c.offerIn, c.borrowerIn) else inputs,
      if (outputs == null) Seq(bondOut, principalOut) else outputs,
      c.prover.getAddress, Seq.empty[InputUTXO], burn)

  // ═══ ORDER ════════════════════════════════════════════════════════════════

  protected def orderTerms(ctx: BlockchainContext,
                           kind: Kind,
                           fee: Long = batchingFee,
                           maturity: Int = -1): OrderTerms = {
    val (value, tokens) = principalOf(kind)
    OrderTerms(
      bondHash = hashOf(bondContract(ctx, kind)),
      borrowerHash = hashOf(borrowerTree(ctx)),
      principalErg = value,
      principalTokens = tokens.map(t => t.id.getBytes -> t.amount),
      repayment = repaymentOf(kind),
      batchingFee = fee,
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
                             fee: Long = batchingFee,
                             duration: Int = defaultDuration): OnCloseOrderTerms = {
    val t = orderTerms(ctx, kind, fee)
    OnCloseOrderTerms(t.bondHash, t.borrowerHash, t.principalErg, t.principalTokens, t.repayment,
      t.batchingFee, duration, t.liqScriptHash)
  }

  protected def onCloseContract(ctx: BlockchainContext, terms: OnCloseOrderTerms): Contract =
    Contract(onCloseTemplate(ctx).withTerms(terms))

  /**
   * One fill of either order style. `maturity` is what the bond carries in R7, `height` the height the
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
   * An on-close fill stamps R7 as fill height + duration.
   */
  protected def fillScenario(ctx: BlockchainContext,
                             kind: Kind,
                             fee: Long = batchingFee,
                             style: OrderStyle = FixedHeight,
                             duration: Int = defaultDuration): Fill = {
    val (script, maturity, height) = style match {
      case FixedHeight =>
        val terms = orderTerms(ctx, kind, fee)
        (orderContract(ctx, terms), terms.maturity, -1)
      case OnClose =>
        (onCloseContract(ctx, onCloseTerms(ctx, kind, fee, duration)), fillHeightOf(ctx) + duration, fillHeightOf(ctx))
    }
    val order = UTXO(script, collateralErg, defaultCollateral)
    val orderIn = inputAt(order, ctx, 0)
    val asset = orderIn.id
    val (value, tokens) = principalOf(kind)
    val l = lender(ctx)
    Fill(ctx, kind, style, order, orderIn, funding(ctx, l, 1, slack, tokens), asset,
      UTXO(lenderTree(ctx), minBox, Seq(Token(asset, 1L)), eip4()),
      bondUTXO(ctx, kind, asset = asset, fee = fee, maturity = maturity, origin = orderIn.id.getBytes),
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
