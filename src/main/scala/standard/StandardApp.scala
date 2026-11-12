package ksingh.sigmabonds
package standard

import ksingh.sigmabonds.app.Protocol._
import ksingh.sigmabonds.app._
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue}
import org.ergoplatform.sdk.ErgoId
import scorex.util.encode.Base16
import sigma.ast.ErgoTree
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import scala.util.Try

/** The liquidation rule a borrower asks for, with the oracle rule's payload. */
sealed trait RuleChoice
case object MaturityChoice extends RuleChoice
/** `ratioBps`: liquidate strictly below this collateral ratio (15000 = 150%); `maxAge`: oldest oracle, blocks. */
case class OracleChoice(ratioBps: Long, maxAge: Long) extends RuleChoice

/**
 * A borrower's terms for a standard order. A standard loan is lent and repaid in one asset, the market's:
 * `principal` and `repayment` are nanoERG for ERG, raw token units for a token market.
 */
case class StdLoan(market: Market, principal: Long, repayment: Long, collateralErg: Long,
                   collateralTokens: Seq[Token], schedule: Schedule, rule: RuleChoice,
                   expiry: Long, expiryFee: Long, requestedMaturity: Option[Long] = None)

/**
 * The ZC Standard set over a single Ergo node: the CLI's default commands. It builds with the pinned trees
 * (`StandardPins`), never with a fresh compile. Boxes come from the node's extra index, keys and signatures
 * from its wallet; protocol inputs sit at the positions their contracts read, wallet boxes after them. Every
 * transaction follows the zcs1 off-chain rules (docs/ZC_STANDARD_OFFCHAIN.md).
 */
class StandardApp(ctx: BlockchainContext, node: NodeApi, settings: AppSettings, out: String => Unit = println,
                  val protocol: StandardProtocol = new StandardProtocol(), scripts: ScriptBook = new ScriptBook)
  extends AppBase(ctx, node, settings, out, scripts)
    with StandardShares with StandardAsks with StandardOffers with StandardBatch {

  import StandardProtocol._

  protected def nameOf(tree: ErgoTree): Option[String] = protocol.nameOf(tree)

  /** The borrower, owner or seller hash in each output's named-script register. */
  override protected def namedScripts(outputs: Seq[UTXO]): Seq[Array[Byte]] =
    outputs.flatMap { o =>
      protocol.namedScriptRegister(o.contract.ergoTree).flatMap(r => o.registers.lift(r - 4))
        .flatMap(v => Try(v.getValue.asInstanceOf[sigma.Coll[Byte]].toArray).toOption).filter(_.length == 32)
    }

  // ═══ LOOKING AROUND ═══════════════════════════════════════════════════════

  /** Every market's orders: they share one template, so one indexed lookup finds them all. */
  def orders(): Seq[StdOrder] =
    decoded(node.unspentByTemplateHash(Base16.encode(protocol.orderTemplateHash))).flatMap(protocol.asOrder)

  def bonds(): Seq[StdBond] =
    decoded(node.unspentByTemplateHash(Base16.encode(protocol.bondTemplateHash))).flatMap(protocol.asBond)

  def settlements(): Seq[StdSettlement] =
    decoded(node.unspentByErgoTree(protocol.contracts.vault.ergoTreeHex)).flatMap(protocol.asSettlement)

  def oracle(): Option[OracleState] = protocol.asOracle(decoded(node.unspentByTokenId(OracleNft.toString)))

  /**
   * Whether a bond asset is genuine: the box whose id it is was spent under this market's standard order,
   * whose script mints exactly 3 units and locks 2 in the bond. Without this, a box under the bond script
   * proves nothing.
   */
  def originVerified(bond: StdBond): Boolean =
    node.boxById(bond.asset.toString).exists { json =>
      NodeJson.spentBy(json).isDefined &&
        Try(NodeJson.box(json)).toOption.exists(o => sameTree(o.input.getErgoTree, protocol.market(bond.market).order.ergoTree))
    }

  /**
   * A bond asset's bond as its terms stand: the live bond, or once settled, the bond box the settlement
   * names (spent, read from the index). None when neither is found.
   */
  def bondRecord(asset: ErgoId): Option[StdBond] = {
    val holders = decoded(node.unspentByTokenId(asset.toString)).filter(_.tokens.headOption.contains(Token(asset, 2L)))
    holders.flatMap(protocol.asBond).headOption.orElse {
      holders.flatMap(protocol.asSettlement).headOption.flatMap { s =>
        node.boxById(Base16.encode(s.bondId)).flatMap(j => Try(NodeJson.box(j)).toOption).flatMap(protocol.asBond)
      }
    }
  }

  /**
   * How a bond unit's metadata departs from zcs1: the fill's OUTPUTS(0) is the issuing box and OUTPUTS(1) the
   * first bond, so one lookup of the fill rebuilds the expected bytes. Also flags R7 to R9 set on an issuing
   * box that is not a share mint request. Empty when it matches.
   */
  def bondMetadataIssues(asset: ErgoId): Seq[String] = {
    val fill = node.boxById(asset.toString).flatMap(NodeJson.spentBy).flatMap(node.transactionById)
    val outputs = fill.toSeq.flatMap(_.hcursor.downField("outputs").focus.flatMap(_.asArray).getOrElse(Vector.empty))
      .flatMap(j => Try(NodeJson.box(j)).toOption)
    (outputs.lift(0), outputs.lift(1).flatMap(protocol.asBond)) match {
      case (Some(issuing), Some(first)) =>
        val expected = StandardMetadata.bond(StandardMetadata.BondFacts.of(first, protocol.ruleOf(first.liqHash)))
        metadataIssues(issuing, expected, Seq(expected)) ++
          (if (!protocol.isShareMint(issuing.input.getErgoTree) && issuing.registers.size > 3)
            Seq("its issuing box sets R7 to R9, which zcs1 leaves empty") else Seq.empty)
      case _ => Seq("cannot read the fill that minted it")
    }
  }

  /** Where `box`'s R4 to R6 differ from every one of `allowed`, described against `expected`. */
  private[standard] def metadataIssues(box: InputUTXO, expected: Seq[String], allowed: Seq[Seq[String]]): Seq[String] = {
    val found = (4 to 6).map(r => bytesReg(box, r).map(StandardMetadata.text))
    if (allowed.exists(a => found == a.map(Some(_)))) Seq.empty
    else Seq("name", "description", "decimals").zip(found).zip(expected).collect {
      case ((field, actual), want) if !actual.contains(want) =>
        val is = actual.fold("missing")(a => "'" + a.take(60) + "'")
        s"its $field is $is, not zcs1's '${want.take(60)}'"
    }
  }

  // ═══ BORROWER ═════════════════════════════════════════════════════════════

  /**
   * The collateral, under the market's order. R4 to R7 are the terms the bond will copy. The wallet funds it,
   * so the transaction discloses the borrower's script (zcs1): a lender pays the principal without asking.
   */
  def createOrder(loan: StdLoan): Prepared = {
    val m = loan.market
    if (loan.principal <= 0L) fail("the principal must be positive")
    if (loan.repayment <= 0L) fail("the repayment must be positive")
    if (loan.collateralErg < MinBox) fail("the collateral must hold at least 0.001 ERG")
    if (loan.expiryFee < MinBatchingFee) fail(s"the expiry fee must be at least ${Fmt.erg(MinBatchingFee)}")
    if (loan.collateralTokens.map(_.id).distinct.size != loan.collateralTokens.size) fail("list each collateral token once")
    if (loan.expiry <= height.toLong + 1L) fail(s"the order's expiry ${loan.expiry} is not above the current height $height")
    val (maturityTerm, mode) = loan.schedule match {
      case FixedMaturity(h) =>
        if (h <= height.toLong + 2L) fail(s"maturity $h is not far enough above the current height $height")
        (h, 0L)
      case OnClose(d) =>
        if (d < 30L) fail("an on-close order's duration must be at least 30 blocks")
        (d, 1L)
    }
    val (rule, payload) = loan.rule match {
      case MaturityChoice => (protocol.contracts.maturity, Seq.empty[Long])
      case OracleChoice(ratio, age) =>
        if (ratio <= 0L) fail("the liquidation ratio must be positive")
        if (age < 1L || age > 100000L) fail("the oracle age must be 1 to 100000 blocks")
        (protocol.market(m).oracleRule, Seq(ratio, age))
    }
    // One asset: an ERG loan's floor never binds; a token loan carries only box minimums beside its token.
    val (principalErg, floor, principalTokens) = m.token match {
      case None => (loan.principal, loan.repayment, Seq.empty[Token])
      case Some(id) => (0L, MinBox, Seq(Token(id, loan.principal)))
    }
    val terms = Seq(loan.repayment, principalErg, floor, maturityTerm, loan.expiry, loan.expiryFee, mode) ++ payload
    val order = UTXO(protocol.market(m).order, addAmount(loan.collateralErg, loan.expiryFee), loan.collateralTokens,
      Seq(longsValue(terms: _*), tokensValue(principalTokens), bytesValue(hashOf(myTree)),
        bytesValue(rule.hashedValueBytes)))

    val notes = Seq(
      s"borrow: ${Units.amount(loan.principal, m)}; repay ${Units.amount(loan.repayment, m)}",
      s"collateral: ${Fmt.contents(loan.collateralErg, loan.collateralTokens)}",
      loan.schedule match {
        case FixedMaturity(h) => s"maturity: height $h (${h - height} blocks from now)" +
          loan.requestedMaturity.filter(_ != h).fold("")(r => s", the zcs1 grid height nearest the $r asked for " +
            "(--exact-maturity keeps an exact height)")
        case OnClose(d) => s"maturity: $d blocks after a lender fills it"
      },
      "liquidation: " + (loan.rule match {
        case MaturityChoice => "from maturity on, all collateral to the lender"
        case OracleChoice(r, a) => s"below ${Units.percent(r)} of the repayment at a USD oracle price at most $a blocks old, " +
          "or from maturity on"
      }),
      s"expiry: height ${loan.expiry}; from then anyone may return it to you and keep the ${Fmt.erg(loan.expiryFee)} " +
        "expiry fee, which a fill refunds to you instead",
      "Output 0 is the order. Cancel it any time before it is filled with `cancel-order <its box id>`.")
    prepare("Create order", plan(Seq.empty, Seq(order)), notes)
  }

  /** Take the collateral back, authorized by a box at the borrower address. */
  def cancelOrder(orderId: String): Prepared = {
    val order = protocol.asOrder(unspent(orderId)).getOrElse(fail(s"$orderId is not a ZC Standard order"))
    prepare(s"Cancel order $orderId", sweep(Seq(order.box, walletBoxAt(order.borrowerHash, "borrower"))))
  }

  /**
   * From the order's expiry on, anyone may return it to its borrower, tagged with its id, and keep the
   * expiry fee less the miner fee.
   */
  def refundOrder(orderId: String): Prepared = {
    val order = protocol.asOrder(unspent(orderId)).getOrElse(fail(s"$orderId is not a ZC Standard order"))
    packed(s"Refund order $orderId", Seq(orderRefund(order)))
  }

  /**
   * The order is INPUTS(0), so the fill mints the bond asset: 1 unit to this wallet at OUTPUTS(0), with EIP-4
   * metadata, and 2 locked in the bond at OUTPUTS(1). The principal and the unused expiry fee go to the
   * borrower at OUTPUTS(2). This wallet funds the principal and the lender's liquidation fee reserve.
   */
  def fillOrder(orderId: String, feeReserve: Long): Prepared = {
    if (feeReserve < 0L) fail("the fee reserve cannot be negative")
    val order = protocol.asOrder(unspent(orderId)).getOrElse(fail(s"$orderId is not a ZC Standard order"))
    val m = order.market
    checkOrder(order)
    val rule = protocol.ruleOf(order.liqHash)
      .getOrElse(fail("the order names a liquidation expression this client does not know"))
    rule match {
      case OracleRatio(rm, _) if rm != m => fail(s"the order's oracle rule measures a $rm repayment, but it repays in $m")
      case OracleRatio(_, _) if order.payload.size < 2 =>
        fail("the order's oracle rule has no ratio or oracle age, so it could never liquidate before maturity")
      case _ =>
    }
    val start = height.toLong
    val maturity = if (order.onClose) addAmount(start, order.terms(3)) else order.terms(3)
    val borrower = borrowerTree(order.borrowerHash, order.box)
    val asset = order.box.id

    val unit = UTXO(Contract(myTree), MinBox, Seq(Token(asset, 1L)), bondMetadata(order, rule, start, maturity))
    // The order pins the bond's creation height to its start: the recollateralize wait counts from it.
    val bond = UTXO(protocol.market(m).bond, addAmount(order.collateralErg, feeReserve),
      Token(asset, 2L) +: order.collateral,
      Seq(bytesValue(asset.getBytes), bytesValue(order.borrowerHash), longsValue(order.terms: _*),
        tokensValue(order.principalTokens), bytesValue(order.liqHash), longsValue(feeReserve, start, maturity)),
      Some(start.toInt))
    val principal = UTXO(Contract(borrower), addAmount(order.expiryFee, order.principalErg), order.principalTokens)

    val notes = Seq(
      s"lend: ${Units.amount(order.principal, m)}; repaid ${Units.amount(order.repayment, m)} by height $maturity " +
        s"(${maturity - height} blocks)",
      s"collateral: ${Fmt.contents(order.collateralErg, order.collateral)}; liquidation: ${describeRule(rule, order.payload)}",
      s"You also fund a ${Fmt.erg(feeReserve)} liquidation fee reserve: it pays whoever liquidates, and goes to " +
        "the vault on repayment.",
      s"Your bond asset is $asset. Claim the bond's payout with `claim $asset`.")
    prepare(s"Fill order $orderId", plan(Seq(order.box), Seq(unit, bond, principal), minted = Some(asset)), notes)
  }

  /** A new bond unit's zcs1 metadata, as EIP-4 registers, from the order and the fill's heights. */
  private[standard] def bondMetadata(order: StdOrder, rule: LiquidationRule, start: Long, maturity: Long): Seq[ErgoValue[_]] =
    StandardMetadata.bond(StandardMetadata.BondFacts(order.market, order.box.id, order.principal, order.repayment,
      start, maturity, StandardMetadata.ruleText(Some(rule), order.payload, order.liqHash))).map(utf8Value)

  /** The fill's own checks, so a lender learns why before the node refuses. */
  private[standard] def checkOrder(o: StdOrder): Unit = {
    val oneAsset = o.market.token match {
      case None => o.principalTokens.isEmpty && o.principalErg > 0L && o.ergFloor <= o.repayment
      case Some(id) => o.principalTokens.map(_.id) == Seq(id) && o.principalErg <= MinBox && o.ergFloor <= MinBox
    }
    if (!oneAsset) fail(s"the order does not lend and repay in ${o.market} alone, so it cannot be filled")
    if (o.repayment <= 0L || o.ergFloor <= 0L || o.principalErg < 0L) fail("the order has invalid amounts")
    if (o.terms(6) != 0L && o.terms(6) != 1L) fail("the order has an unknown maturity mode")
    if (o.expiryFee < MinBatchingFee || o.expiryFee > o.box.value - MinBox) fail("the order's expiry fee is out of bounds")
    if (height.toLong + 1L >= o.expiry) fail(s"the order expired at height ${o.expiry}")
    if (o.onClose && o.terms(3) < 30L) fail("the order's duration is under the 30-block minimum")
    if (!o.onClose && o.terms(3) <= height.toLong + 2L) fail(s"the order matures at ${o.terms(3)}, too close to fill")
  }

  /** Every vault box holding the asset's reserve, claimed at once with the unit; all of the asset is burned. */
  def claim(assetId: String): Prepared = {
    val asset = Try(ErgoId.create(assetId)).getOrElse(fail(s"$assetId is not a token id"))
    val settled = settlements().filter(_.asset == asset)
    if (settled.isEmpty) fail(s"no vault box holds $assetId: its bond has not been repaid or liquidated yet")
    val unit = walletBoxes.find(_.tokens.exists(_.id == asset)).getOrElse(fail(s"this wallet does not hold bond asset $assetId"))
    val burned = settled.map(_.box.tokens.head.amount).sum + unit.tokens.filter(_.id == asset).map(_.amount).sum
    prepare(s"Claim bond asset $assetId", sweep(settled.map(_.box) :+ unit, burn = Seq(Token(asset, burned))),
      Seq(s"Claims ${settled.size} vault box(es) holding " +
        s"${Fmt.contents(settled.map(_.box.value).sum, settled.flatMap(_.proceeds))}" +
        (if (settled.exists(_.liquidated)) " (liquidated)" else " (repaid)") + s", and burns all $burned units."))
  }

  // ═══ BORROWER: REPAY AND RECOLLATERALIZE ══════════════════════════════════

  /**
   * The repayment to the vault with the bond's reserve, tagged R4 = bond id and R5 = 0, and the collateral
   * back to the borrower, tagged too. Anyone may repay. With `fromCollateral`, the borrower's co-spend lets
   * the return keep back up to the repayment of the repayment asset, so the collateral pays it.
   */
  def repay(bondId: String, fromCollateral: Boolean = false): Prepared = {
    val bond = liveBond(bondId)
    val m = bond.market
    if (height.toLong + 1L >= bond.maturity) fail(s"the bond matures at height ${bond.maturity}: repayment is closed")
    val borrower = borrowerTree(bond.borrowerHash, bond.box)

    val tag = Seq(bytesValue(bond.box.id.getBytes), ErgoValue.of(0.toByte))
    val settlement = m.token match {
      case None => UTXO(protocol.contracts.vault, addAmount(bond.feeReserve, bond.repayment), Seq(Token(bond.asset, 2L)), tag)
      case Some(id) => UTXO(protocol.contracts.vault, math.max(MinBox, addAmount(bond.feeReserve, bond.ergFloor)),
        Seq(Token(bond.asset, 2L), Token(id, bond.repayment)), tag)
    }
    val (returned, kept, auth) =
      if (!fromCollateral) ((bond.collateralErg, bond.collateral), 0L, Seq.empty[InputUTXO])
      else {
        val held = bond.heldRepaymentAsset
        val k = m.token match {
          case None => math.min(bond.repayment, bond.collateralErg - MinBox)
          case Some(_) => math.min(bond.repayment, held)
        }
        if (k <= 0L) fail(s"the bond's collateral holds no ${m} it can repay from")
        val back = m.token match {
          case None => (bond.collateralErg - k, bond.collateral)
          case Some(id) => (bond.collateralErg, bond.collateral.flatMap(t =>
            if (t.id != id) Some(t) else if (t.amount > k) Some(Token(id, t.amount - k)) else None))
        }
        (back, k, Seq(walletBoxAt(bond.borrowerHash, "borrower")))
      }
    val collateral = UTXO(Contract(borrower), returned._1, returned._2, Seq(bytesValue(bond.box.id.getBytes)))

    val notes = Seq(
      s"repay: ${Units.amount(bond.repayment, m)} into the vault, for the holder of ${bond.asset}",
      if (kept > 0L) s"from collateral: ${Units.amount(kept, m)}; you bring the other ${Units.amount(bond.repayment - kept, m)}"
      else "from collateral: nothing (add --from-collateral to repay from the bond's own " + m + ")",
      s"lender fee reserve to the vault: ${Fmt.erg(bond.feeReserve)}",
      s"collateral back: ${Fmt.contents(returned._1, returned._2)}" +
        (if (isWalletHash(hashOf(borrower))) " to this wallet" else s" to ${addressOf(borrower)}"),
      s"maturity: height ${bond.maturity} (${bond.maturity - height} blocks left)")
    val input = bond.box.withCtxVar(1.toByte, intValue(0)).withCtxVar(2.toByte, intValue(1))
    prepare(s"Repay bond $bondId", plan(input +: auth, Seq(settlement, collateral)), notes)
  }

  /**
   * Add collateral before maturity, at most once per 60 blocks: more ERG, more of a held token, or, for a
   * token bond holding none of it, its repayment token appended last. Needs the borrower's co-spend. The
   * successor keeps every register and is stamped with the current height, which restarts the wait.
   */
  def recollateralize(bondId: String, addErg: Long, addTokens: Seq[Token]): Prepared = {
    val bond = liveBond(bondId)
    if (addErg < 0L) fail("cannot add a negative amount")
    if (addErg == 0L && addTokens.isEmpty) fail("add something: --add-erg <ERG> or --add-token <token id>:<amount>")
    if (addTokens.map(_.id).distinct.size != addTokens.size) fail("list each added token once")
    if (height.toLong + 1L >= bond.maturity) fail(s"the bond matures at height ${bond.maturity}: it can no longer take collateral")
    if (height.toLong + 1L < bond.recollateralizableFrom)
      fail(s"the bond last moved at height ${bond.movedAt}; it can take collateral again from height ${bond.recollateralizableFrom}")

    val held = bond.collateral.map(_.id).toSet
    val appended = addTokens.filterNot(t => held.contains(t.id))
    appended.foreach { t =>
      if (!bond.market.token.contains(t.id))
        fail(s"the bond does not hold ${t.id}: it only takes more of its own tokens, or its repayment token")
    }
    val grown = bond.box.tokens.map(t => addTokens.find(_.id == t.id).fold(t)(a => Token(t.id, addAmount(t.amount, a.amount))))
    val successor = UTXO(bond.box.contract, addAmount(bond.box.value, addErg), grown ++ appended,
      bond.box.registers, Some(height))

    val notes = Seq(
      s"adds: ${Fmt.contents(addErg, addTokens)}",
      s"collateral after: ${Fmt.contents(successor.value - bond.feeReserve, successor.tokens.drop(1))}",
      s"the bond can take collateral again from height ${height + RecollateralizeWait}")
    val input = bond.box.withCtxVar(3.toByte, intValue(0))
    prepare(s"Recollateralize bond $bondId",
      plan(Seq(input, walletBoxAt(bond.borrowerHash, "borrower")), Seq(successor)), notes)
  }

  // ═══ EXECUTOR ═════════════════════════════════════════════════════════════

  /**
   * Liquidate bonds in one batch. Each runs its rule from context var 0 and names its vault output in var 1.
   * An oracle-rule bond before maturity reads the oracle pool as a data input. This wallet keeps the fee
   * reserves, less the miner fee.
   */
  def liquidate(bondIds: Seq[String]): Prepared = {
    if (bondIds.isEmpty) fail("name at least one bond box id")
    val pieces = bondIds.distinct.map(id => liquidation(liveBond(id)))
    packed(s"Liquidate ${pieces.size} bond(s)", pieces)
  }

  // ═══ HELPERS ══════════════════════════════════════════════════════════════

  private[standard] def liveBond(bondId: String): StdBond =
    protocol.asBond(unspent(bondId)).getOrElse(fail(s"$bondId is not a live ZC Standard bond"))

  private[standard] def borrowerTree(hash: Array[Byte], box: InputUTXO): ErgoTree = scriptOf(hash, box, "borrower")

  private[standard] def ownerTree(hash: Array[Byte], box: InputUTXO, who: String): ErgoTree = scriptOf(hash, box, who)

  /**
   * The script a box names by hash, found as zcs1 script disclosure promises: in one lookup of the
   * transaction that created the box (see `treeFor`). A box that does not disclose it cannot be served by
   * anyone but `who`, whose own wallet knows it.
   */
  private[standard] def scriptOf(hash: Array[Byte], box: InputUTXO, who: String): ErgoTree =
    treeFor(hash, box, Seq.empty).getOrElse(fail(s"box ${box.id} does not disclose its $who's script (zcs1 script " +
      s"disclosure): its creating transaction neither carries it in context variable ${DisclosureVar.toInt} nor " +
      s"spends from or pays to it, so only the $who can act on it"))

  /** Whether `box` discloses the script `hash` names, so a batcher can serve it. */
  private[standard] def discloses(hash: Array[Byte], box: InputUTXO): Boolean =
    Try(treeFor(hash, box, Seq.empty)).toOption.flatten.isDefined

  def describeRule(rule: LiquidationRule, payload: Seq[Long]): String = rule match {
    case AtMaturity(_) => "at maturity"
    case OracleRatio(_, _) if payload.size >= 2 =>
      s"below ${Units.percent(payload(0))} at an oracle price at most ${payload(1)} blocks old, or at maturity"
    case OracleRatio(_, _) => "oracle rule without terms: at maturity only"
  }
}

/** Amounts in a market's own unit: ERG with 9 decimals, SigUSD with 2. */
object Units {
  def decimals(m: Market): Int = if (m.token.isEmpty) 9 else 2

  def amount(raw: Long, m: Market): String =
    (BigDecimal(raw) / BigDecimal(10).pow(decimals(m))).bigDecimal.stripTrailingZeros.toPlainString + " " + m.name

  /** Parse "12.5" as raw units of the market, refusing more decimals than it has. */
  def parse(s: String, m: Market, what: String): Long = {
    val raw = Try(BigDecimal(s) * BigDecimal(10).pow(decimals(m))).getOrElse(throw CliError(s"$what: $s is not an amount"))
    if (!raw.isWhole || raw <= 0) throw CliError(s"$what: $s is not a positive ${m.name} amount with at most ${decimals(m)} decimals")
    Try(raw.toLongExact).getOrElse(throw CliError(s"$what: $s is too large"))
  }

  def percent(bps: Long): String = (BigDecimal(bps) / 100).bigDecimal.stripTrailingZeros.toPlainString + "%"
}
