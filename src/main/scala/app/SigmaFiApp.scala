package ksingh.sigmabonds
package app

import io.circe.Json
import ksingh.sigmabonds.client.ScriptGenerator.{OnCloseOrderTerms, OrderTerms}
import org.ergoplatform.appkit.{Address, BlockchainContext, NetworkType, Parameters, UnsignedTransaction}
import org.ergoplatform.sdk.ErgoId
import scorex.util.encode.Base16
import sigma.ast.ErgoTree
import work.lithos.mutations.{Contract, InputUTXO, MainnetEip27Constants, Token, TxBuilder, UTXO}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.Try

/** What a run may do beyond reading: which wallet address to act as, the fee, and whether to submit. */
case class AppSettings(address: Option[String] = None,
                       walletPass: Option[String] = None,
                       fee: Long = Parameters.MinFee,
                       send: Boolean = false)

/**
 * A borrower's loan terms. An ERG loan has no principal token; a token loan lends one
 * token, with `principalErg` only the box's ERG floor, and is repaid in that same token.
 */
case class Loan(principalErg: Long,
                principalToken: Option[Token],
                repayment: Long,
                batchingFee: Long,
                collateralErg: Long,
                collateralTokens: Seq[Token],
                liquidation: String) {
  def repayToken: Option[ErgoId] = principalToken.map(_.id)
}

/** A transaction built and ready for the node wallet, with what it does in words. */
case class Prepared(title: String, tx: UnsignedTransaction, notes: Seq[String])

/**
 * SigmaFi V2 over a single Ergo node. Everything comes from the node: boxes from its extra index, keys and
 * signatures from its wallet. Nothing is signed locally, and no explorer is used.
 *
 * Each action finds and verifies the boxes it spends, builds the transaction with the protocol inputs at the
 * positions their contracts read and wallet boxes after them, and returns it unsigned. `submit` has the node
 * wallet sign it, has the node check it, and sends it only when the settings say to.
 *
 * An order's borrower hash is resolved from this wallet, its optional R4 ErgoTree hint, or the transaction
 * that created the order. The hint is never read by a contract.
 */
class SigmaFiApp(ctx: BlockchainContext, node: NodeApi, settings: AppSettings, out: String => Unit = println,
                 compiled: Option[Protocol] = None) {

  import Protocol._

  /** `compiled` shares one compilation across instances; the sigma compiler is slow, and not reentrant. */
  val protocol: Protocol = compiled.getOrElse(new Protocol(ctx))

  /** The last block's height. A transaction built now lands at a later one. */
  def height: Int = ctx.getHeight

  def networkType: NetworkType = ctx.getNetworkType

  // ═══ WALLET ═══════════════════════════════════════════════════════════════

  private var unlockedHere = false

  private lazy val walletReady: Unit = {
    val status = node.walletStatus().hcursor
    if (!status.downField("isInitialized").as[Boolean].getOrElse(false))
      fail("the node's wallet is not initialized")
    if (!status.downField("isUnlocked").as[Boolean].getOrElse(false)) {
      val pass = settings.walletPass.getOrElse(fail("the node's wallet is locked: pass --wallet-pass"))
      node.walletUnlock(pass)
      unlockedHere = true
    }
  }

  /** Locks the wallet again if this run unlocked it. */
  def close(): Unit = if (unlockedHere) {
    unlockedHere = false
    Try(node.walletLock())
  }

  lazy val walletAddresses: Seq[Address] = {
    walletReady
    node.walletAddresses().map(Address.create)
  }

  private lazy val walletTrees: Map[String, ErgoTree] = walletAddresses.map { a =>
    val tree = a.toErgoContract.getErgoTree
    Base16.encode(hashOf(tree)) -> tree
  }.toMap

  def isWalletHash(hash: Array[Byte]): Boolean = walletTrees.contains(Base16.encode(hash))

  /** Whether this run can read the wallet at all: listings mark your own boxes only when it can. */
  lazy val walletAvailable: Boolean = Try(walletAddresses).isSuccess

  /** As isWalletHash, but false rather than an error when the wallet cannot be read. */
  def isOwnHash(hash: Array[Byte]): Boolean = walletAvailable && isWalletHash(hash)

  /** The address this wallet lends, borrows and executes as: --address, else the wallet's change address. */
  lazy val myAddress: Address = {
    walletReady
    val chosen = settings.address
      .orElse(node.walletStatus().hcursor.downField("changeAddress").as[String].toOption.filter(_.nonEmpty))
      .map(a => Try(Address.create(a)).getOrElse(fail(s"$a is not a valid address")))
      .orElse(walletAddresses.headOption)
      .getOrElse(fail("the node's wallet has no addresses"))
    if (!walletAddresses.exists(_.toString == chosen.toString))
      fail(s"$chosen is not an address of the node's wallet")
    chosen
  }

  lazy val myTree: ErgoTree = myAddress.toErgoContract.getErgoTree

  /**
   * Boxes the wallet can spend: P2PK boxes at its own addresses. Boxes carrying re-emission tokens are left
   * out, so a spend never owes the EIP-27 payment.
   */
  lazy val walletBoxes: Seq[InputUTXO] = {
    walletReady
    node.walletUnspentBoxes().flatMap(j => Try(NodeJson.box(j)).toOption)
      .filter(b => walletTrees.contains(Base16.encode(hashOf(b.input.getErgoTree))))
      .filterNot(_.tokens.exists(_.id.toString == MainnetEip27Constants.TokenId))
  }

  def walletHolds(tokenId: ErgoId): Boolean = walletBoxes.exists(_.tokens.exists(_.id == tokenId))

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

  /**
   * Tx 2B: pay the principal and create the bond. The order is INPUTS(0), so the fill mints the bond asset;
   * this wallet's unit, at OUTPUTS(0), names it.
   */
  def fillOrder(orderId: String): Prepared = {
    val order = protocol.asOrder(unspent(orderId)).getOrElse(fail(s"$orderId is not a SigmaFi order"))
    val repayToken = protocol.pairedBond(order.bondHash, order.principalTokens)
      .getOrElse(fail("the order's bond contract does not match its principal, so its bond could not be repaid as asked"))
    val liq = protocol.liquidationOf(order.liqScriptHash)
      .getOrElse(fail("the order names a liquidation script this client does not know"))
    if (order.batchingFee < 0 || order.box.value - order.batchingFee < MinBox)
      fail("the order's batching fee leaves its bond under 0.001 ERG, so it cannot be filled")
    val maturity = order.schedule match {
      case FixedMaturity(h) =>
        if (h <= height + 2) fail(s"the order matures at $h, too close to the current height $height to fill")
        h
      case OnClose(d) =>
        if (d < 30) fail(s"the order's duration of $d blocks is under the 30-block minimum, so it cannot be filled")
        height + d
    }
    val borrower = treeFor(order.borrowerHash, order.box, treesReg(order.box, 4)).getOrElse(fail(
      "cannot find the borrower's ErgoTree: the order carries no hint, and no box in the transaction that " +
        "created it hashes to CONST_BORROWER_HASH"))
    val asset = order.box.id

    val unit = UTXO(Contract(myTree), MinBox, Seq(Token(asset, 1L)),
      Seq(utf8Value(BondAssetMetadata.name(asset)),
        utf8Value(BondAssetMetadata.description(order, repayToken, maturity, height, liq.name)), utf8Value("0")))
    val bond = UTXO(protocol.bondContract(repayToken), order.box.value, Token(asset, 1L) +: order.box.tokens,
      Seq(bytesValue(asset.getBytes), bytesValue(order.borrowerHash),
        longsValue(order.repayment, order.batchingFee), intValue(maturity),
        bytesValue(order.liqScriptHash)))
    val principal = UTXO(Contract(borrower), math.max(order.principalErg, MinBox), order.principalTokens)

    val notes = Seq(
      s"lend: ${Fmt.principal(order.principalErg, order.principalTokens)}",
      s"repaid: ${Fmt.amount(order.repayment, repayToken)} by height $maturity (${maturity - height} blocks)",
      s"collateral: ${Fmt.contents(order.box.value, order.box.tokens)}, less at most ${Fmt.erg(order.batchingFee)} " +
        s"to a liquidator; liquidation: ${liq.name}",
      s"Your bond asset will be $asset. Claim the bond's payout with `claim $asset`.")
    prepare(s"Fill order $orderId", plan(Seq(order.box), Seq(unit, bond, principal), minted = Some(asset)), notes)
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

  /** Tx 1B: the collateral, under an order stamped from its template. R4 hints this wallet's tree. */
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
          loan.repayment, loan.batchingFee, h, liq.hash))
      case OnClose(d) =>
        if (d < 30) fail("an on-close order's duration must be at least 30 blocks")
        protocol.onCloseTemplate.withTerms(OnCloseOrderTerms(bondHash, borrowerHash, loan.principalErg,
          principalTokens, loan.repayment, loan.batchingFee, d, liq.hash))
    }
    val order = UTXO(Contract(tree), loan.collateralErg, loan.collateralTokens, Seq(treesValue(myTree)))

    val notes = describeLoan(loan) ++ Seq(schedule match {
      case FixedMaturity(h) => s"maturity: height $h (${h - height} blocks from now). Cancel the order if it " +
        "is still open close to then."
      case OnClose(d) => s"maturity: $d blocks after a lender fills it"
    }, "Output 0 is the order. Cancel it any time before it is filled with `cancel-order <its box id>`.")
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
    if (height + 1 >= bond.maturity) fail(s"the bond matured at height ${bond.maturity}: repayment is closed")
    val originHints = node.boxById(Base16.encode(bond.origin)).flatMap(j => Try(NodeJson.box(j)).toOption)
      .toSeq.flatMap(treesReg(_, 4))
    val borrower = treeFor(bond.borrowerHash, bond.box, originHints)
      .getOrElse(fail("cannot find the borrower's ErgoTree, which the collateral must return to"))

    val tag = Seq(bytesValue(bond.box.id.getBytes), bytesValue(bond.asset.getBytes))
    val settlement = bond.repayToken match {
      case None => UTXO(protocol.vault, math.max(bond.repayment, MinBox), Seq.empty, tag)
      case Some(token) => UTXO(protocol.vault, MinBox, Seq(Token(token, bond.repayment)), tag)
    }
    val collateral = UTXO(Contract(borrower), bond.box.value, bond.collateral, Seq(bytesValue(bond.box.id.getBytes)))

    val notes = Seq(
      s"repay: ${Fmt.amount(bond.repayment, bond.repayToken)} into the vault, for the holder of ${bond.asset}",
      s"collateral back: ${Fmt.contents(bond.box.value, bond.collateral)}" +
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
      if (liq.name == "MaturityLiquidation" && height + 1 < b.maturity)
        fail(s"bond ${b.box.id} matures at height ${b.maturity}; it can be liquidated from then on")
      liq
    }
    val inputs = bonds.zip(scripts).zipWithIndex.map { case ((b, liq), i) =>
      b.box.withCtxVar(0.toByte, bytesValue(liq.bytes)).withCtxVar(1.toByte, intValue(i))
    }
    val outputs = bonds.map(b => UTXO(protocol.vault, b.box.value - b.batchingFee, b.collateral,
      Seq(bytesValue(b.box.id.getBytes), bytesValue(b.asset.getBytes))))

    val notes = Seq(s"${bonds.size} bond(s); batching fees total ${Fmt.erg(bonds.map(_.batchingFee).sum)}, " +
      "less the miner fee, to this wallet")
    prepare(s"Liquidate ${bonds.size} bond(s)", plan(inputs, outputs, burn = bonds.map(b => Token(b.asset, 1L))), notes)
  }

  // ═══ SIGN AND SUBMIT ══════════════════════════════════════════════════════

  /**
   * Print what the transaction does, have the node wallet sign it and the node check it, and submit it only
   * when the settings say so. Returns the id of a submitted transaction.
   */
  def submit(p: Prepared): Option[String] = {
    summarize(p)
    walletReady
    val signed = node.signTransaction(NodeJson.unsignedTx(p.tx),
      p.tx.getInputs.asScala.map(b => Base16.encode(b.getBytes)),
      p.tx.getDataInputs.asScala.map(b => Base16.encode(b.getBytes)))
    val id = node.checkTransaction(signed)
    out(s"The node wallet signed it, and the node accepts it: $id")
    signed.hcursor.downField("outputs").focus.flatMap(_.asArray).getOrElse(Vector.empty).zipWithIndex.foreach {
      case (o, i) => o.hcursor.downField("boxId").as[String].foreach(boxId => out(s"  output $i box id: $boxId"))
    }
    if (settings.send) {
      val sent = node.sendTransaction(signed)
      out(s"Submitted: $sent")
      Some(sent)
    } else {
      out("Dry run: nothing was submitted. Run the same command with --send to submit it.")
      None
    }
  }

  private def summarize(p: Prepared): Unit = {
    out(s"== ${p.title} ==")
    p.notes.foreach(n => out(s"  $n"))
    out("Inputs:")
    p.tx.getInputs.asScala.zipWithIndex.foreach { case (b, i) =>
      out(f"  $i%2d  ${b.getId}  ${Fmt.erg(b.getValue)}%-16s  ${describe(b.getErgoTree)}")
      b.getTokens.asScala.foreach(t => out(s"        ${t.getValue} of ${t.getId}"))
    }
    out("Outputs:")
    p.tx.getOutputs.asScala.zipWithIndex.foreach { case (b, i) =>
      out(f"  $i%2d  ${Fmt.erg(b.getValue)}%-16s  ${describe(b.getErgoTree)}")
      b.getTokens.asScala.foreach(t => out(s"        ${t.getValue} of ${t.getId}"))
    }
    p.tx.getTokensToBurn.asScala.foreach(t => out(s"Burns: ${t.getValue} of ${t.getId}"))
  }

  private def describe(tree: ErgoTree): String =
    protocol.nameOf(tree).getOrElse {
      val address = addressOf(tree)
      if (isWalletHash(hashOf(tree))) s"$address (this wallet)" else address
    }

  private def addressOf(tree: ErgoTree): String = {
    val a = Address.fromErgoTree(tree, ctx.getNetworkType).toString
    if (a.length > 60) a.take(28) + "..." + a.takeRight(28) else a
  }

  // ═══ BUILDING ═════════════════════════════════════════════════════════════

  private case class Plan(inputs: Seq[InputUTXO], outputs: Seq[UTXO], burn: Seq[Token], fee: Long)

  private def prepare(title: String, p: Plan, notes: Seq[String] = Seq.empty): Prepared = {
    val tx = TxBuilder(ctx).setInputs(p.inputs: _*).setOutputs(p.outputs: _*).buildTx(p.fee, myAddress, p.burn)
    Prepared(title, tx, notes)
  }

  /**
   * Protocol inputs keep the positions their contracts read, and wallet boxes follow them to cover the ERG
   * and tokens the outputs, burns and fee need beyond what those inputs carry. Change returns to this
   * wallet; a surplus too small for a change box, with no tokens to carry, goes to the miner. Wallet boxes
   * holding a token being burned are never picked: the leftover would reach the change box, and every
   * burn here requires that no output carries the token.
   */
  private def plan(spends: Seq[InputUTXO], outputs: Seq[UTXO], burn: Seq[Token] = Seq.empty,
                   minted: Option[ErgoId] = None): Plan = {
    def left(inputs: Seq[InputUTXO]): (Long, Map[ErgoId, Long]) = {
      val erg = inputs.map(_.value).sum - outputs.map(_.value).sum - settings.fee
      val held = tally(inputs.flatMap(_.tokens))
      val used = tally(outputs.flatMap(_.tokens) ++ burn)
      val net = (held.keySet ++ used.keySet).map(id => id -> (held.getOrElse(id, 0L) - used.getOrElse(id, 0L))).toMap
      (erg, net -- minted.toSeq)
    }
    val (erg, tokens) = left(spends)
    val short = tokens.collect { case (id, n) if n < 0 => id -> -n }
    val carries = tokens.exists(_._2 > 0)
    val selfFunded = short.isEmpty && (erg >= MinBox || (erg >= 0 && !carries))
    val wallet =
      if (selfFunded) Seq.empty
      else selectWallet(math.max(0L, MinBox - erg), short, spends.map(_.id).toSet, burn.map(_.id).toSet)

    val (finalErg, finalTokens) = left(spends ++ wallet)
    val dust = if (finalErg > 0 && finalErg < MinBox && !finalTokens.exists(_._2 > 0)) finalErg else 0L
    Plan(spends ++ wallet, outputs, burn, settings.fee + dust)
  }

  /** Everything the inputs hold, less burns and the fee, to this wallet in one box. */
  private def sweep(spends: Seq[InputUTXO], burn: Seq[Token] = Seq.empty): Plan = {
    val burned = tally(burn)
    val kept = tally(spends.flatMap(_.tokens)).toSeq
      .map { case (id, n) => id -> (n - burned.getOrElse(id, 0L)) }
      .collect { case (id, n) if n > 0 => Token(id, n) }
    val total = spends.map(_.value).sum
    plan(spends, Seq(UTXO(Contract(myTree), math.max(MinBox, total - settings.fee), kept)), burn)
  }

  /** Token boxes first, largest holdings first; then the largest ERG boxes until `erg` is covered. */
  private def selectWallet(erg: Long, tokens: Map[ErgoId, Long], exclude: Set[ErgoId],
                           avoidTokens: Set[ErgoId]): Seq[InputUTXO] = {
    val pool = walletBoxes.filterNot(b => exclude.contains(b.id) || b.tokens.exists(t => avoidTokens.contains(t.id)))
    val chosen = mutable.LinkedHashSet.empty[InputUTXO]
    def held(id: ErgoId): Long = chosen.toSeq.flatMap(_.tokens).filter(_.id == id).map(_.amount).sum
    def amountIn(b: InputUTXO, id: ErgoId): Long = b.tokens.filter(_.id == id).map(_.amount).sum

    tokens.foreach { case (id, need) =>
      pool.filter(amountIn(_, id) > 0).sortBy(-amountIn(_, id)).foreach(b => if (held(id) < need) chosen += b)
      if (held(id) < need) fail(s"the wallet holds ${held(id)} of token $id; this needs $need")
    }
    pool.filterNot(chosen.contains).sortBy(-_.value).foreach { b =>
      if (chosen.toSeq.map(_.value).sum < erg) chosen += b
    }
    val value = chosen.toSeq.map(_.value).sum
    if (value < erg)
      fail(s"the wallet's spendable boxes hold ${Fmt.erg(pool.map(_.value).sum)}; this needs ${Fmt.erg(erg)} " +
        "from them, the fee and a change box included")
    chosen.toSeq
  }

  private def tally(tokens: Seq[Token]): Map[ErgoId, Long] =
    tokens.groupBy(_.id).map { case (id, ts) => id -> ts.map(_.amount).sum }

  // ═══ LOOKUPS ══════════════════════════════════════════════════════════════

  private def decoded(boxes: Seq[Json]): Seq[InputUTXO] = boxes.flatMap(j => Try(NodeJson.box(j)).toOption)

  /** An unspent box by id, from the node's index. */
  private def unspent(boxId: String): InputUTXO = {
    val json = node.boxById(boxId).getOrElse(fail(s"the node's index has no box $boxId"))
    NodeJson.spentBy(json).foreach(tx => fail(s"box $boxId was already spent, by transaction $tx"))
    NodeJson.box(json)
  }

  /**
   * The ErgoTree a hash commits to, from candidates: hint trees, this wallet's addresses, and every box in
   * the transaction that created `box`. The last is how a box made by another client is resolved: the
   * borrower or lender usually funded it from the very address the hash names.
   */
  private def treeFor(hash: Array[Byte], box: InputUTXO, hints: Seq[ErgoTree]): Option[ErgoTree] = {
    def matching(trees: Seq[ErgoTree]) = trees.find(t => hashOf(t).sameElements(hash))
    matching(hints)
      .orElse(walletTrees.get(Base16.encode(hash)))
      .orElse(node.transactionById(box.input.getTransactionId).flatMap(tx => matching(NodeJson.transactionTrees(tx))))
  }

  // ═══ CHECKS ═══════════════════════════════════════════════════════════════

  private def checkLoan(loan: Loan): Unit = {
    if (loan.principalErg < MinBox) fail("the principal box must hold at least 0.001 ERG")
    if (loan.repayment <= 0) fail("the repayment must be positive")
    if (loan.batchingFee < 0) fail("the batching fee cannot be negative")
    if (loan.collateralErg - loan.batchingFee < MinBox)
      fail("the collateral ERG must exceed the batching fee by at least 0.001 ERG, or no bond could be made")
    if (loan.collateralTokens.map(_.id).distinct.size != loan.collateralTokens.size)
      fail("list each collateral token once")
    if (loan.principalToken.exists(p => loan.collateralTokens.exists(_.id == p.id)))
      fail("the principal token cannot also be collateral")
  }

  private def checkMaturity(maturity: Int): Unit =
    if (maturity <= height + 2) fail(s"maturity $maturity is not far enough above the current height $height")

  private def describeLoan(loan: Loan): Seq[String] = Seq(
    s"loan: ${Fmt.principal(loan.principalErg, loan.principalToken.toSeq)}; repayment: " +
      Fmt.amount(loan.repayment, loan.repayToken),
    s"collateral: ${Fmt.contents(loan.collateralErg, loan.collateralTokens)}; batching fee: " +
      s"${Fmt.erg(loan.batchingFee)}; liquidation: ${loan.liquidation}")

  private def fail(message: String): Nothing = throw CliError(message)
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
