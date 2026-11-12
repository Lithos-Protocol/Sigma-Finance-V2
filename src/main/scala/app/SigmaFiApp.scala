package ksingh.sigmabonds
package app

import ksingh.sigmabonds.client.ScriptGenerator.{OnCloseOrderTerms, OrderTerms}
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue}
import org.ergoplatform.sdk.ErgoId
import scorex.util.encode.Base16
import sigma.ast.ErgoTree
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import scala.util.Try

/**
 * A borrower's loan terms. An ERG loan has no principal token; a token loan lends one
 * token, with `principalErg` only the box's ERG floor, and is repaid in that same token.
 */
case class Loan(principalErg: Long,
                principalToken: Option[Token],
                repayment: Long,
                collateralErg: Long,
                collateralTokens: Seq[Token],
                liquidation: String) {
  def repayToken: Option[ErgoId] = principalToken.map(_.id)
}

/**
 * The FIRST-GENERATION contracts (`contracts/old/`) over a single Ergo node: the CLI's `--old` commands.
 * The ZC Standard set has its own app, `standard.StandardApp`. Boxes come from the node's extra index,
 * keys and signatures from its wallet.
 *
 * Each action finds and verifies the boxes it spends, builds the transaction with the protocol inputs at the
 * positions their contracts read and wallet boxes after them, and returns it unsigned. `submit` has the node
 * wallet sign it, has the node check it, and sends it only when the settings say to.
 *
 * An order's borrower hash is resolved from this wallet, its optional R4 ErgoTree hint, or the transaction
 * that created the order. The hint is never read by a contract.
 */
class SigmaFiApp(ctx: BlockchainContext, node: NodeApi, settings: AppSettings, out: String => Unit = println,
                 compiled: Option[Protocol] = None) extends AppBase(ctx, node, settings, out) {

  import Protocol._

  /** `compiled` shares one compilation across instances; the sigma compiler is slow, and not reentrant. */
  val protocol: Protocol = compiled.getOrElse(new Protocol(ctx))

  protected def nameOf(tree: ErgoTree): Option[String] = protocol.nameOf(tree)

  // ═══ LOOKING AROUND ═══════════════════════════════════════════════════════

  def orders(): Seq[OrderInfo] =
    Seq(protocol.orderTemplate, protocol.onCloseTemplate)
      .flatMap(t => decoded(node.unspentByTemplateHash(Base16.encode(t.templateHash))))
      .flatMap(protocol.asOrder)

  def bonds(): Seq[BondInfo] =
    (decoded(node.unspentByErgoTree(protocol.ergBond.ergoTreeHex)) ++
      decoded(node.unspentByTemplateHash(Base16.encode(protocol.tokenBondTemplateHash)))).flatMap(protocol.asBond)

  /** Every vault box, keyed by the bond asset that claims it. */
  def vaultBoxes(): Map[ErgoId, Seq[InputUTXO]] =
    decoded(node.unspentByErgoTree(protocol.vault.ergoTreeHex))
      .flatMap(b => protocol.vaultAsset(b).map(_ -> b))
      .groupBy(_._1).map { case (asset, boxes) => asset -> boxes.map(_._2) }

  // ═══ LENDER ═══════════════════════════════════════════════════════════════

  /**
   * Tx 2B: pay the principal and create the bond. The order is INPUTS(0), so the fill mints the bond asset;
   * this wallet's unit, at OUTPUTS(0), names it.
   */
  def fillOrder(orderId: String, batchingFee: Long = 2000000L): Prepared = {
    if (batchingFee < 0L) fail("the batching fee cannot be negative")
    val order = protocol.asOrder(unspent(orderId)).getOrElse(fail(s"$orderId is not a SigmaFi order"))
    if (order.principalErg < MinBox || order.repayment <= 0L) fail("the order has invalid principal or repayment terms")
    val repayToken = protocol.pairedBond(order.bondHash, order.principalTokens)
      .getOrElse(fail("the order's bond contract does not match its principal, so its bond could not be repaid as asked"))
    val liq = protocol.liquidationOf(order.liqScriptHash)
      .getOrElse(fail("the order names a liquidation script this client does not know"))
    val bondValue = addAmount(order.box.value, batchingFee)
    val maturity = order.schedule match {
      case FixedMaturity(h) =>
        if (h <= height.toLong + 2L) fail(s"the order matures at $h, too close to the current height $height to fill")
        h
      case OnClose(d) =>
        if (d < 30) fail(s"the order's duration of $d blocks is under the 30-block minimum, so it cannot be filled")
        addAmount(height.toLong, d)
    }
    val borrower = treeFor(order.borrowerHash, order.box, treesReg(order.box, 4)).getOrElse(fail(
      "cannot find the borrower's ErgoTree: the order carries no hint, and no box in the transaction that " +
        "created it hashes to CONST_BORROWER_HASH"))
    val asset = order.box.id

    val unit = UTXO(Contract(myTree), MinBox, Seq(Token(asset, 1L)),
      Seq(utf8Value(BondAssetMetadata.name(asset)),
        utf8Value(BondAssetMetadata.description(order, repayToken, maturity, height, liq.name, batchingFee)), utf8Value("0")))
    val bond = UTXO(protocol.bondContract(repayToken), bondValue, Token(asset, 1L) +: order.box.tokens,
      Seq(bytesValue(asset.getBytes), bytesValue(order.borrowerHash),
        longsValue(order.repayment, batchingFee, order.principalErg, maturity), tokensValue(order.principalTokens),
        bytesValue(order.liqScriptHash)))
    val principal = UTXO(Contract(borrower), math.max(order.principalErg, MinBox), order.principalTokens)

    val notes = Seq(
      s"lend: ${Fmt.principal(order.principalErg, order.principalTokens)}",
      s"repaid: ${Fmt.amount(order.repayment, repayToken)} by height $maturity (${maturity - height} blocks)",
      s"collateral: ${Fmt.contents(order.box.value, order.box.tokens)}; liquidation: ${liq.name}",
      s"You fund an additional ${Fmt.erg(batchingFee)} liquidation reserve; refunded to the vault on repayment.",
      s"Your bond asset will be $asset. Claim the bond's payout with `claim --old $asset`.")
    prepare(s"Fill order $orderId", plan(Seq(order.box.withCtxVar(0.toByte, longValue(batchingFee))), Seq(unit, bond, principal), minted = Some(asset)), notes)
  }

  /** Tx 7: every vault box naming the asset, claimed at once with the unit, which is burned. */
  def claim(assetId: String): Prepared = {
    val asset = Try(ErgoId.create(assetId)).getOrElse(fail(s"$assetId is not a token id"))
    val settled = vaultBoxes().getOrElse(asset, Seq.empty)
    if (settled.isEmpty) fail(s"no vault box names $assetId: its bond has not been repaid or liquidated yet")
    val unit = walletBoxes.find(_.tokens.exists(_.id == asset))
      .getOrElse(fail(s"this wallet does not hold bond asset $assetId"))
    prepare(s"Claim bond asset $assetId", sweep(settled :+ unit, burn = Seq(Token(asset, 1L))),
      Seq(s"Claims ${settled.size} vault box(es) holding " +
        s"${Fmt.contents(settled.map(_.value).sum, settled.flatMap(_.tokens))}, and burns the unit."))
  }

  // ═══ BORROWER ═════════════════════════════════════════════════════════════

  /**
   * Tx 1B: the collateral, under an order stamped from its template. R4 hints this wallet's tree. The CLI no
   * longer offers this: new orders use the ZC Standard. It stays for the specs and for code that needs it.
   */
  def createOrder(loan: Loan, schedule: Schedule): Prepared = {
    checkLoan(loan)
    val liq = protocol.liquidation(loan.liquidation)
    val bondHash = protocol.bondContract(loan.repayToken).hashedPropBytes
    val borrowerHash = hashOf(myTree)
    val principalTokens = loan.principalToken.toSeq.map(t => t.id.getBytes -> t.amount)
    val tree = schedule match {
      case FixedMaturity(h) =>
        checkMaturity(h)
        protocol.orderTemplate.withTerms(OrderTerms(bondHash, borrowerHash, loan.principalErg, principalTokens,
          loan.repayment, h, liq.hash))
      case OnClose(d) =>
        if (d < 30) fail("an on-close order's duration must be at least 30 blocks")
        protocol.onCloseTemplate.withTerms(OnCloseOrderTerms(bondHash, borrowerHash, loan.principalErg,
          principalTokens, loan.repayment, d, liq.hash))
    }
    val order = UTXO(Contract(tree), loan.collateralErg, loan.collateralTokens, Seq(treesValue(myTree)))

    val notes = describeLoan(loan) ++ Seq(schedule match {
      case FixedMaturity(h) => s"maturity: height $h (${h - height} blocks from now). Cancel the order if it " +
        "is still open close to then."
      case OnClose(d) => s"maturity: $d blocks after a lender fills it"
    }, "Output 0 is the order. Cancel it any time before it is filled with `cancel-order --old <its box id>`.")
    prepare("Create order", plan(Seq.empty, Seq(order)), notes)
  }

  /** Tx 3B: take the collateral back, authorized by a box at the borrower address. */
  def cancelOrder(orderId: String): Prepared = {
    val order = protocol.asOrder(unspent(orderId)).getOrElse(fail(s"$orderId is not a SigmaFi order"))
    val auth = walletBoxes.find(b => hashOf(b.input.getErgoTree).sameElements(order.borrowerHash))
      .getOrElse(fail("this wallet holds no box at the order's borrower address; only its borrower can cancel"))
    prepare(s"Cancel order $orderId", sweep(Seq(order.box, auth)))
  }

  /**
   * Tx 5: the repayment to the vault, tagged with the bond and its asset, and the collateral back to the
   * borrower. Anyone may repay; the borrower's tree comes from this wallet or the bond's history.
   */
  def repay(bondId: String): Prepared = {
    val bond = protocol.asBond(unspent(bondId)).getOrElse(fail(s"$bondId is not a SigmaFi bond"))
    if (height.toLong + 1L >= bond.maturity) fail(s"the bond matured at height ${bond.maturity}: repayment is closed")
    val originHints = node.boxById(Base16.encode(bond.origin)).flatMap(j => Try(NodeJson.box(j)).toOption)
      .toSeq.flatMap(treesReg(_, 4))
    val borrower = treeFor(bond.borrowerHash, bond.box, originHints)
      .getOrElse(fail("cannot find the borrower's ErgoTree, which the collateral must return to"))

    val tag = Seq(bytesValue(bond.box.id.getBytes), bytesValue(bond.asset.getBytes), ErgoValue.of(0.toByte))
    val settlement = bond.repayToken match {
      case None => UTXO(protocol.vault, math.max(addAmount(bond.repayment, bond.batchingFee), MinBox), Seq.empty, tag)
      case Some(token) => UTXO(protocol.vault, addAmount(MinBox, bond.batchingFee), Seq(Token(token, bond.repayment)), tag)
    }
    val collateral = UTXO(Contract(borrower), bond.collateralErg, bond.collateral, Seq(bytesValue(bond.box.id.getBytes)))

    val notes = Seq(
      s"repay: ${Fmt.amount(bond.repayment, bond.repayToken)} into the vault, for the holder of ${bond.asset}",
      s"lender fee reserve refunded to vault: ${Fmt.erg(bond.batchingFee)}",
      s"collateral back: ${Fmt.contents(bond.collateralErg, bond.collateral)}" +
        (if (sameTree(borrower, myTree) || isWalletHash(hashOf(borrower))) " to this wallet" else s" to ${addressOf(borrower)}"),
      s"maturity: height ${bond.maturity} (${bond.maturity - height} blocks left)")
    prepare(s"Repay bond $bondId", plan(Seq(bond.box.withCtxVar(1.toByte, intValue(0)).withCtxVar(2.toByte, intValue(1))), Seq(settlement, collateral), burn = Seq(Token(bond.asset, 1L))), notes)
  }

  // ═══ EXECUTOR ═════════════════════════════════════════════════════════════

  /**
   * Tx 6: liquidate bonds in one batch. Each bond runs its liquidation script from context var 0,
   * and selects its vault output using context var 1. This wallet keeps the batching fees, less the miner fee.
   */
  def liquidate(bondIds: Seq[String]): Prepared = {
    if (bondIds.isEmpty) fail("name at least one bond box id")
    val bonds = bondIds.distinct.map(id => protocol.asBond(unspent(id)).getOrElse(fail(s"$id is not a SigmaFi bond")))
    val scripts = bonds.map { b =>
      val liq = protocol.liquidationOf(b.liqScriptHash)
        .getOrElse(fail(s"bond ${b.box.id} names a liquidation script this client does not know"))
      if (liq.name == "MaturityLiquidation" && height.toLong + 1L < b.maturity)
        fail(s"bond ${b.box.id} matures at height ${b.maturity}; it can be liquidated from then on")
      liq
    }
    val inputs = bonds.zip(scripts).zipWithIndex.map { case ((b, liq), i) =>
      b.box.withCtxVar(0.toByte, bytesValue(liq.bytes)).withCtxVar(1.toByte, intValue(i))
    }
    val outputs = bonds.map(b => UTXO(protocol.vault, b.box.value - b.batchingFee, b.collateral,
      Seq(bytesValue(b.box.id.getBytes), bytesValue(b.asset.getBytes), ErgoValue.of(1.toByte))))

    val notes = Seq(s"${bonds.size} bond(s); batching fees total ${Fmt.erg(bonds.map(_.batchingFee).sum)}, " +
      "less the miner fee, to this wallet")
    prepare(s"Liquidate ${bonds.size} bond(s)", plan(inputs, outputs, burn = bonds.map(b => Token(b.asset, 1L))), notes)
  }

  // ═══ CHECKS ═══════════════════════════════════════════════════════════════

  private def checkLoan(loan: Loan): Unit = {
    if (loan.principalErg < MinBox) fail("the principal box must hold at least 0.001 ERG")
    if (loan.repayment <= 0) fail("the repayment must be positive")
    if (loan.collateralErg < MinBox) fail("the collateral must hold at least 0.001 ERG")
    if (loan.collateralTokens.map(_.id).distinct.size != loan.collateralTokens.size)
      fail("list each collateral token once")
    if (loan.principalToken.exists(p => loan.collateralTokens.exists(_.id == p.id)))
      fail("the principal token cannot also be collateral")
  }

  private def checkMaturity(maturity: Long): Unit =
    if (maturity <= height.toLong + 2L) fail(s"maturity $maturity is not far enough above the current height $height")

  private def describeLoan(loan: Loan): Seq[String] = Seq(
    s"loan: ${Fmt.principal(loan.principalErg, loan.principalToken.toSeq)}; repayment: " +
      Fmt.amount(loan.repayment, loan.repayToken),
    s"collateral: ${Fmt.contents(loan.collateralErg, loan.collateralTokens)}; liquidation: ${loan.liquidation}")
}

/** Amounts as people read them. */
object Fmt {
  def erg(nano: Long): String =
    (BigDecimal(nano) / BigDecimal(1000000000L)).bigDecimal.stripTrailingZeros.toPlainString + " ERG"

  def token(t: Token): String = s"${t.amount} of ${t.id}"

  /** A repayment: nanoERG for an ERG bond, token units for a token bond. */
  def amount(n: Long, token: Option[ErgoId]): String = token.fold(erg(n))(id => s"$n of $id")

  /** A loan's principal: a token loan's ERG is only the box's floor, so it is left out. */
  def principal(nano: Long, tokens: Seq[Token]): String =
    if (tokens.isEmpty) erg(nano) else tokens.map(token).mkString(", ")

  def contents(nano: Long, tokens: Seq[Token]): String = (erg(nano) +: tokens.map(token)).mkString(", ")
}
