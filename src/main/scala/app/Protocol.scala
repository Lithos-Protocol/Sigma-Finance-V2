package ksingh.sigmabonds
package app

import ksingh.sigmabonds.client.ScriptGenerator
import ksingh.sigmabonds.client.ScriptGenerator.{ContractTemplate, LiquidationScript}
import org.ergoplatform.ErgoBox
import org.ergoplatform.appkit.impl.InputBoxImpl
import org.ergoplatform.appkit.scalaapi._
import org.ergoplatform.appkit.{BlockchainContext, ErgoType, ErgoValue, Parameters}
import org.ergoplatform.sdk.ErgoId
import scorex.crypto.hash.Blake2b256
import scorex.util.encode.Base16
import sigma.ast.SCollection.SByteArray
import sigma.ast.{Constant, ErgoTree, SCollection, SInt, SLong, STuple, SType}
import sigma.{Coll, Colls}
import work.lithos.mutations.{Contract, InputUTXO, Token}

import scala.collection.mutable

/** When a bond matures: at a fixed height, or a number of blocks after the order is filled. */
sealed trait Schedule
case class FixedMaturity(height: Long) extends Schedule
case class OnClose(duration: Long) extends Schedule

/** A borrower's order, of either style. The box's whole contents are the collateral. */
case class OrderInfo(box: InputUTXO,
                     schedule: Schedule,
                     bondHash: Array[Byte],
                     borrowerHash: Array[Byte],
                     principalErg: Long,
                     principalTokens: Seq[Token],
                     repayment: Long,
                     liqScriptHash: Array[Byte])

/** A live bond. `repayToken` is None for an ERG bond, or CONST_TOKEN_ID for a token bond. */
case class BondInfo(box: InputUTXO,
                    repayToken: Option[ErgoId],
                    origin: Array[Byte],
                    borrowerHash: Array[Byte],
                    repayment: Long,
                    batchingFee: Long,
                    principalErg: Long,
                    principalTokens: Seq[Token],
                    maturity: Long,
                    liqScriptHash: Array[Byte]) {
  def asset: ErgoId = box.tokens.head.id
  def collateral: Seq[Token] = box.tokens.drop(1)
  def collateralErg: Long = box.value - batchingFee
}

/**
 * SigmaFi V2 as a client sees it: each contract compiled once, through ScriptGenerator, and boxes read back
 * into their terms. Orders must pass `ContractTemplate.matches`, and bonds must have the exact bond tree
 * for their repayment token. Recognizing a bond's shape does not authenticate its mint or origin.
 */
class Protocol(ctx: BlockchainContext) {

  import Protocol._

  lazy val vault: Contract = Contract.fromErgoContract(ScriptGenerator.mkBondAssetVault(ctx))
  lazy val ergBond: Contract = Contract.fromErgoContract(ScriptGenerator.mkZcBondContract(ctx, None))
  lazy val orderTemplate: ContractTemplate = ScriptGenerator.mkOrderTemplate(ctx)
  lazy val onCloseTemplate: ContractTemplate = ScriptGenerator.mkOnCloseOrderTemplate(ctx)
  lazy val liquidations: Seq[LiquidationScript] =
    ScriptGenerator.LIQUIDATION_SCRIPTS.map(ScriptGenerator.mkLiquidationScript(ctx, _))

  def liquidation(name: String): LiquidationScript =
    liquidations.find(_.name == name).getOrElse(throw CliError(
      s"unknown liquidation script $name; known: ${liquidations.map(_.name).mkString(", ")}"))

  def liquidationOf(hash: Array[Byte]): Option[LiquidationScript] = liquidations.find(_.hash.sameElements(hash))

  // ─── bonds ───────────────────────────────────────────────────────────────

  private val tokenBonds = mutable.Map.empty[ErgoId, Contract]

  /** Compiled per repayment token, since CONST_TOKEN_ID is compiled in. */
  def tokenBond(tokenId: ErgoId): Contract =
    tokenBonds.getOrElseUpdate(tokenId,
      Contract.fromErgoContract(ScriptGenerator.mkZcBondContract(ctx, Some(tokenId.toString))))

  def bondContract(repayToken: Option[ErgoId]): Contract = repayToken.fold(ergBond)(tokenBond)

  /**
   * Every token bond shares one template; CONST_TOKEN_ID sits at the constant position the sentinel lands
   * on. The template only finds candidates: `asBond` recompiles the bond for the token it reads.
   */
  lazy val (tokenBondTemplateHash: Array[Byte], tokenIdPosition: Int) = {
    val sentinel = Colls.fromArray(Array.fill(32)(0x7e.toByte))
    val tree = ScriptGenerator.mkZcBondContract(ctx, Some(Base16.encode(sentinel.toArray))).getErgoTree
    val found = tree.constants.indices.filter(i => tree.constants(i).value == sentinel)
    require(found.size == 1, s"CONST_TOKEN_ID sentinel found at ${found.mkString(",")} in the token bond")
    (templateHash(tree), found.head)
  }

  /**
   * The bond a bond hash names, if it is a genuine bond and fits the principal: an ERG loan (no principal
   * tokens) is an ERG bond, and a loan of one token is that token's bond. Anything else is refused, since
   * orders do not check the pairing themselves.
   */
  def pairedBond(bondHash: Array[Byte], principalTokens: Seq[Token]): Option[Option[ErgoId]] =
    principalTokens match {
      case Seq() if ergBond.hashedPropBytes.sameElements(bondHash) => Some(None)
      case Seq(t) if tokenBond(t.id).hashedPropBytes.sameElements(bondHash) => Some(Some(t.id))
      case _ => None
    }

  def asBond(box: InputUTXO): Option[BondInfo] = {
    val tree = box.input.getErgoTree
    val repayToken: Option[Option[ErgoId]] =
      if (sameTree(tree, ergBond.ergoTree)) Some(None)
      else if (templateHash(tree).sameElements(tokenBondTemplateHash)) {
        tree.constants(tokenIdPosition).value match {
          case id: Coll[Byte @unchecked] if id.length == 32 =>
            val token = new ErgoId(id.toArray)
            if (sameTree(tree, tokenBond(token).ergoTree)) Some(Some(token)) else None
          case _ => None
        }
      } else None

    for {
      token <- repayToken
      if box.tokens.nonEmpty && box.tokens.head.amount == 1L
      origin <- bytesReg(box, 4)
      borrower <- bytesReg(box, 5)
      amounts <- longsReg(box, 6) if amounts.length == 4
      if amounts(0) > 0L && amounts(1) >= 0L && amounts(1) <= box.value - MinBox && amounts(2) >= MinBox && amounts(3) > 0L
      principal <- tokensReg(box, 7)
      liq <- bytesReg(box, 8)
    } yield BondInfo(box, token, origin, borrower, amounts(0), amounts(1), amounts(2), principal, amounts(3), liq)
  }

  // ─── orders ──────────────────────────────────────────────────────────────

  def asOrder(box: InputUTXO): Option[OrderInfo] = {
    val tree = box.input.getErgoTree
    val styled = Seq(orderTemplate, onCloseTemplate).find(_.matches(tree))
    styled.map { t =>
      val schedule =
        if (t eq orderTemplate) FixedMaturity(longTerm(t, tree, "CONST_MATURITY"))
        else OnClose(longTerm(t, tree, "CONST_DURATION"))
      OrderInfo(box, schedule,
        bondHash = bytesTerm(t, tree, "CONST_BOND_HASH"),
        borrowerHash = bytesTerm(t, tree, "CONST_BORROWER_HASH"),
        principalErg = longTerm(t, tree, "CONST_PRINCIPAL_ERG"),
        principalTokens = tokensTerm(t, tree, "CONST_PRINCIPAL_TOKENS").map { case (id, n) => Token(new ErgoId(id), n) },
        repayment = longTerm(t, tree, "CONST_REPAYMENT"),
        liqScriptHash = bytesTerm(t, tree, "CONST_LIQ_SCRIPT_HASH"))
    }
  }

  // ─── lender scripts ──────────────────────────────────────────────────────

  /** The bond asset a vault box opens for, from its R5. */
  def vaultAsset(box: InputUTXO): Option[ErgoId] =
    if (!sameTree(box.input.getErgoTree, vault.ergoTree)) None
    else bytesReg(box, 5).filter(_.length == 32).map(new ErgoId(_))

  /** A short name for a contract this client knows, for transaction summaries. */
  def nameOf(tree: ErgoTree): Option[String] =
    if (sameTree(tree, vault.ergoTree)) Some("BondAssetVault")
    else if (sameTree(tree, ergBond.ergoTree)) Some("ZC_ERG_Bond")
    else if (templateHash(tree).sameElements(tokenBondTemplateHash)) Some("ZC_Token_Bond")
    else if (orderTemplate.matches(tree)) Some("ZC_Order")
    else if (onCloseTemplate.matches(tree)) Some("ZC_OrderOnClose")
    else if (sameTree(tree, Contract.FEE.ergoTree)) Some("miner fee")
    else None

  // ─── template terms ──────────────────────────────────────────────────────

  private def term(t: ContractTemplate, tree: ErgoTree, name: String): Any = tree.constants(t.positions(name)).value

  private def bytesTerm(t: ContractTemplate, tree: ErgoTree, name: String): Array[Byte] =
    term(t, tree, name).asInstanceOf[Coll[Byte]].toArray

  private def longTerm(t: ContractTemplate, tree: ErgoTree, name: String): Long =
    term(t, tree, name).asInstanceOf[Long]

  private def intTerm(t: ContractTemplate, tree: ErgoTree, name: String): Int =
    term(t, tree, name).asInstanceOf[Int]

  private def tokensTerm(t: ContractTemplate, tree: ErgoTree, name: String): Seq[(Array[Byte], Long)] =
    term(t, tree, name).asInstanceOf[Coll[(Coll[Byte], Long)]].toArray.toSeq.map { case (id, n) => id.toArray -> n }
}

object Protocol {

  /** Every protocol output that holds a token or registers carries at least this: 0.001 ERG. */
  final val MinBox: Long = Parameters.MinChangeValue

  def hashOf(tree: ErgoTree): Array[Byte] = Blake2b256.hash(tree.bytes)
  def templateHash(tree: ErgoTree): Array[Byte] = Blake2b256.hash(tree.template)
  def sameTree(a: ErgoTree, b: ErgoTree): Boolean = java.util.Arrays.equals(a.bytes, b.bytes)

  // ─── registers: read only at the exact type the protocol writes ──────────

  private def register(box: InputUTXO, r: Int): Option[Constant[SType]] =
    box.input.asInstanceOf[InputBoxImpl].getErgoBox.get(ErgoBox.registerByIndex(r)).collect {
      case c: Constant[SType @unchecked] => c
    }

  def bytesReg(box: InputUTXO, r: Int): Option[Array[Byte]] =
    register(box, r).filter(_.tpe == SByteArray).map(_.value.asInstanceOf[Coll[Byte]].toArray)

  def longsReg(box: InputUTXO, r: Int): Option[Array[Long]] =
    register(box, r).filter(_.tpe == SCollection(SLong)).map(_.value.asInstanceOf[Coll[Long]].toArray)

  def tokensReg(box: InputUTXO, r: Int): Option[Seq[Token]] =
    register(box, r).filter(_.tpe == SCollection(STuple(SByteArray, SLong)))
      .map(_.value.asInstanceOf[Coll[(Coll[Byte], Long)]].toArray.toSeq)
      .filter(_.forall { case (id, n) => id.length == 32 && n > 0L })
      .filter(ts => ts.map(_._1).distinct.size == ts.size)
      .map(_.map { case (id, n) => Token(new ErgoId(id.toArray), n) })

  def intReg(box: InputUTXO, r: Int): Option[Int] =
    register(box, r).filter(_.tpe == SInt).map(_.value.asInstanceOf[Int])

  def byteReg(box: InputUTXO, r: Int): Option[Byte] =
    register(box, r).filter(_.tpe == sigma.ast.SByte).map(_.value.asInstanceOf[Byte])

  def longReg(box: InputUTXO, r: Int): Option[Long] =
    register(box, r).filter(_.tpe == SLong).map(_.value.asInstanceOf[Long])

  /** A `Coll[Coll[Byte]]` register, such as share EIP-4 metadata. */
  def bytesListReg(box: InputUTXO, r: Int): Option[Seq[Array[Byte]]] =
    register(box, r).filter(_.tpe == SCollection(SByteArray))
      .map(_.value.asInstanceOf[Coll[Coll[Byte]]].toArray.toSeq.map(_.toArray))

  /** A hint register: ErgoTrees the box's hashes commit to. Entries that do not parse are dropped. */
  def treesReg(box: InputUTXO, r: Int): Seq[ErgoTree] =
    register(box, r).filter(_.tpe == SCollection(SByteArray)).toSeq
      .flatMap(_.value.asInstanceOf[Coll[Coll[Byte]]].toArray.toSeq)
      .flatMap(bytes => scala.util.Try(NodeJson.tree(Base16.encode(bytes.toArray))).toOption)

  // ─── register values for outputs ─────────────────────────────────────────

  def bytesValue(bytes: Array[Byte]): ErgoValue[_] = ErgoValue.of(Colls.fromArray(bytes), scalaByteType)
  def utf8Value(s: String): ErgoValue[_] = bytesValue(s.getBytes("UTF-8"))
  def longsValue(v: Long*): ErgoValue[_] = ErgoValue.of(Colls.fromArray(v.toArray), scalaLongType)
  def intValue(v: Int): ErgoValue[_] = ErgoValue.of(v)
  def longValue(v: Long): ErgoValue[_] = ErgoValue.of(v)

  def tokensValue(tokens: Seq[Token]): ErgoValue[_] =
    ErgoValue.of(
      Colls.fromArray(tokens.map(t => (Colls.fromArray(t.id.getBytes), t.amount)).toArray)(
        sigma.data.RType.pairRType(sigma.collRType(sigma.ByteType), sigma.LongType)),
      ErgoType.pairType(ErgoType.collType(scalaByteType), scalaLongType))

  /** A `Coll[Coll[Byte]]` register of UTF-8 strings, such as share EIP-4 metadata. */
  def textsValue(parts: String*): ErgoValue[_] =
    ErgoValue.of(Colls.fromArray(parts.map(p => Colls.fromArray(p.getBytes("UTF-8"))).toArray)(sigma.collRType(sigma.ByteType)),
      ErgoType.collType(scalaByteType))

  /** A hint register: `Coll[Coll[Byte]]`, each entry an ErgoTree's bytes. */
  def treesValue(trees: ErgoTree*): ErgoValue[_] =
    ErgoValue.of(
      Colls.fromArray(trees.map(t => Colls.fromArray(t.bytes)).toArray)(sigma.collRType(sigma.ByteType)),
      ErgoType.collType(scalaByteType))
}

/** A refusal meant for the person at the CLI: printed without a stack trace. */
case class CliError(message: String) extends Exception(message)
