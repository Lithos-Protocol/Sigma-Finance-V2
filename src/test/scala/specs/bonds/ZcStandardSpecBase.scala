package org.lithosprotocol.sigmafi
package specs.bonds

import ksingh.sigmabonds.app.Protocol
import ksingh.sigmabonds.client.ScriptGenerator
import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import sigma.Colls
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import scala.collection.JavaConverters._

/** Test-only compilation of the ZC_Standard_* contracts: no production off-chain code builds them yet. */
object ZcStandardSpecBase {
  val SigUSD: ErgoId = ErgoId.create("03faf2cb329f2e90d6d23b58d91bbb6c046aa143261cc21f52fbe2824bfcbf04")
  val OracleNFT: ErgoId = ErgoId.create("6a2b821b5727e85beb5e78b4efb9f0250d59cd48481d2ded2c23e91ba1d07c66")

  /** One bond and one order tree per repayment asset; each pair shares a template. */
  case class Scripts(vault: Contract, ergBond: Contract, tokenBond: Contract,
                     ergOrder: Contract, tokenOrder: Contract,
                     fractionalVault: Contract, fractionalMint: Contract,
                     maturity: Contract, ergOracle: Contract, tokenOracle: Contract)

  private var cache: Option[Scripts] = None

  private def compile(ctx: BlockchainContext, path: String, terms: Seq[(String, Any)]): Contract = {
    val constants = terms.foldLeft(ConstantsBuilder.create()) {
      case (builder, (key, value)) => builder.item(key, value)
    }.build()
    Contract.fromErgoContract(ctx.compileContract(constants, ScriptGenerator.mkScript(path)))
  }

  private def hash(c: Contract) = Colls.fromArray(c.hashedPropBytes)

  /**
   * The contracts compiled per repayment (or payment) asset beyond the bond and order: the lend offer,
   * both asks, the two expressions they run, and the Dutch auction mint.
   */
  case class Market(offer: Contract, saleOrder: Contract, navAsk: Contract, pricing: Contract,
                    policy: Contract, dutchMint: Contract)

  case class Exchange(erg: Market, token: Market, dutchVault: Contract)

  private var exchangeCache: Option[Exchange] = None

  /** The P2P exchange, the Dutch auction pair and the policies, compiled once per JVM. */
  def exchange(ctx: BlockchainContext): Exchange = synchronized {
    exchangeCache.getOrElse {
      val s = scripts(ctx)
      val dutchVault = compile(ctx, "bonds/lender-scripts/ZC_Standard_DutchAuctionVault", Seq.empty)
      def market(token: Array[Byte], order: Contract, bond: Contract, rule: Contract): Market = {
        val asset = Colls.fromArray(token)
        val dutchMint = compile(ctx, "bonds/lender-scripts/ZC_Standard_DutchAuctionMint",
          Seq("CONST_DUTCH_VAULT_HASH" -> hash(dutchVault), "CONST_BOND_HASH" -> hash(bond),
            "CONST_REPAYMENT_TOKEN" -> asset))
        Market(
          compile(ctx, "bonds/P2P/ZC_Standard_LendOffer",
            Seq("CONST_REPAYMENT_TOKEN" -> asset, "CONST_ORDER_HASH" -> hash(order),
              "CONST_BOND_HASH" -> hash(bond), "CONST_FRACTIONAL_MINT_HASH" -> hash(s.fractionalMint),
              "CONST_DUTCH_MINT_HASH" -> hash(dutchMint))),
          compile(ctx, "bonds/P2P/ZC_Standard_SaleOrder", Seq("CONST_PAYMENT_TOKEN" -> asset)),
          compile(ctx, "bonds/P2P/ZC_Standard_NavAsk", Seq("CONST_PAYMENT_TOKEN" -> asset)),
          compile(ctx, "bonds/policies/ZC_Standard_AccruedValue_Pricing",
            Seq("CONST_REPAYMENT_TOKEN" -> asset, "CONST_BOND_HASH" -> hash(bond))),
          compile(ctx, "bonds/policies/ZC_Standard_ERG_SigUSD_CollateralPolicy",
            Seq("CONST_REPAYMENT_TOKEN" -> asset, "CONST_LIQUIDATION_HASH" -> Colls.fromArray(rule.hashedValueBytes))),
          dutchMint)
      }
      val e = Exchange(market(Array.empty[Byte], s.ergOrder, s.ergBond, s.ergOracle),
        market(SigUSD.getBytes, s.tokenOrder, s.tokenBond, s.tokenOracle), dutchVault)
      exchangeCache = Some(e)
      e
    }
  }

  /** Compiled once per JVM: the sigma compiler mutates shared AST nodes. */
  def scripts(ctx: BlockchainContext): Scripts = synchronized {
    cache.getOrElse {
      def compile(path: String, terms: Seq[(String, Any)] = Seq.empty): Contract =
        ZcStandardSpecBase.compile(ctx, path, terms)
      val vault = compile("bonds/core/ZC_Standard_BondVault")
      def bond(token: Array[Byte]) = compile("bonds/core/ZC_Standard_Bond",
        Seq("CONST_VAULT_HASH" -> hash(vault), "CONST_REPAYMENT_TOKEN" -> Colls.fromArray(token)))
      // The order compiles in the same repayment token as its bond, for the one-asset rule.
      def order(b: Contract, token: Array[Byte]) = compile("bonds/P2P/ZC_Standard_Order",
        Seq("CONST_BOND_HASH" -> hash(b), "CONST_REPAYMENT_TOKEN" -> Colls.fromArray(token)))
      val ergBond = bond(Array.empty[Byte])
      val tokenBond = bond(SigUSD.getBytes)
      val fractionalVault = compile("bonds/lender-scripts/ZC_Standard_FractionalVault")
      val fractionalMint = compile("bonds/lender-scripts/ZC_Standard_FractionalMint",
        Seq("CONST_FRACTIONAL_VAULT_HASH" -> hash(fractionalVault)))
      val c = Scripts(vault, ergBond, tokenBond, order(ergBond, Array.empty[Byte]), order(tokenBond, SigUSD.getBytes),
        fractionalVault, fractionalMint,
        compile("bonds/liquidations/ZC_Standard_MaturityLiquidation"),
        // The oracle rule measures the repayment, so it is compiled per repayment asset like the bond.
        compile("bonds/liquidations/ZC_Standard_ERG_SigUSD_Liquidation",
          Seq("CONST_REPAYMENT_TOKEN" -> Colls.fromArray(Array.empty[Byte]))),
        compile("bonds/liquidations/ZC_Standard_ERG_SigUSD_Liquidation",
          Seq("CONST_REPAYMENT_TOKEN" -> Colls.fromArray(SigUSD.getBytes))))
      cache = Some(c)
      c
    }
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
   * index 7: the oracle rule reads its ratio and maximum age there.
   */
  protected def flow(ctx: BlockchainContext, kind: Kind = TokenBond, style: OrderStyle = FixedHeight,
                     collateralValue: Long = collateralErg, collateral: Seq[Token] = defaultCollateral,
                     expiryFee: Long = 2000000L, expiry: Long = -1L, fee: Long = batchingFee,
                     duration: Long = 1000L, fixedMaturity: Long = -1L,
                     principalValue: Long = -1L, principalAssets: Seq[Token] = null,
                     repayment: Long = -1L, extra: Seq[Long] = Seq(15000L, 30L, 777L),
                     liq: Contract = null, ergFloor: Long = -1L): Flow = {
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
      Seq(longsValue(data: _*), Protocol.tokensValue(principalTokens), borrowerHash, liqHash))
    val input = inputAt(order, ctx, 0)
    val bond = UTXO(bondOf(ctx, kind), collateralValue + fee, Token(input.id, 2L) +: collateral,
      Seq(bytesValue(input.id.getBytes), borrowerHash, longsValue(data: _*),
        Protocol.tokensValue(principalTokens), liqHash, longsValue(fee, height.toLong, maturity)))
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
      bond = f.bond.withRegNum(9, longsValue(f.fee, height.toLong, maturity)))
  }

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

  /** `bond` must already carry the repayment context vars. Outputs: 0 the vault, 1 the collateral return. */
  protected def standardRepayTx(f: Flow, bond: InputUTXO, settle: UTXO = null, returned: UTXO = null,
                                height: Int = -1): UnsignedTransaction =
    buildAt(f.ctx, if (height < 0) (f.maturity - 1L).toInt else height,
      // One unit of an unrelated token rides along, for negatives that try to slip it into the vault.
      Seq(bond, funding(f.ctx, f.owner, 7, slack, repaymentAssets(f) :+ Token(secondCollateral, 1L))),
      Seq(if (settle == null) repaidBox(f, bond) else settle,
        if (returned == null) returnedBox(f, bond) else returned), f.owner.getAddress)

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
