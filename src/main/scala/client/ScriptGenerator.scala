package ksingh.sigmabonds
package client

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import scorex.crypto.hash.Blake2b256
import sigma.ast.{Constant, ErgoTree, SType}
import sigma.data.RType
import sigma.{Coll, Colls, VersionContext}

import scala.io.Source

object ScriptGenerator {
  private final val BASE_PATH = "contracts/"
  private final val EXT = ".ergo"

  private final val ERG = "ERG"
  private final val TOKEN = "Token"

  private final val ZC_BOND_PREFIX = "bonds/P2P/ZC_"
  private final val BOND_SUFFIX = "_Bond"
  private final val LENDER_SCRIPTS = "bonds/lender-scripts/"
  private final val LIQUIDATIONS = "bonds/liquidations/"

  /** Every liquidation script a bond may name in R9. */
  final val LIQUIDATION_SCRIPTS: Seq[String] = Seq("MaturityLiquidation")

  def mkScript(name: String): String = {
    val src = Source.fromFile(BASE_PATH + name + EXT)
    val script = src.mkString
    src.close()
    script
  }

  def idFromStr(idStr: String): Coll[Byte] = {
    Colls.fromArray(
      ErgoId.create(
        idStr
      ).getBytes
    )
  }

  // Zero-coupon bonds with liquidation and a bond asset
  def mkZcBondContract(ctx: BlockchainContext, optTokenId: Option[String]): ErgoContract = {
    optTokenId match {
      case Some(id) =>
        val script = mkScript(ZC_BOND_PREFIX + TOKEN + BOND_SUFFIX)
        val constants = ConstantsBuilder
          .create()
          .item("CONST_TOKEN_ID", idFromStr(id))
          .build()

        ctx.compileContract(constants, script)
      case None =>
        val script = mkScript(ZC_BOND_PREFIX + ERG + BOND_SUFFIX)
        val constants = ConstantsBuilder.empty()

        ctx.compileContract(constants, script)
    }
  }

  // One vault for every bond: the asset id is on the settlement box's R5, so it has no constants
  def mkBondAssetVault(ctx: BlockchainContext): ErgoContract = {
    val script = mkScript(LENDER_SCRIPTS + "BondAssetVault")
    ctx.compileContract(ConstantsBuilder.empty(), script)
  }

  def mkBondAssetGenerator(ctx: BlockchainContext): ErgoContract = {
    val script = mkScript(LENDER_SCRIPTS + "BondNFT_Generator")
    ctx.compileContract(ConstantsBuilder.empty(), script)
  }

  /**
   * A liquidation script. `bytes` is the serialized expression a bond runs with executeFromVar, which an
   * executor attaches as context var 0; `hash` is what the bond keeps in R9. Neither is an ErgoTree.
   */
  case class LiquidationScript(name: String, tree: ErgoTree) {
    def bytes: Array[Byte] = work.lithos.mutations.Contract.compileValue(tree)
    def hash: Array[Byte] = Blake2b256.hash(bytes)
  }

  def mkLiquidationScript(ctx: BlockchainContext, name: String): LiquidationScript = {
    require(LIQUIDATION_SCRIPTS.contains(name), s"unknown liquidation script $name")
    LiquidationScript(name, ctx.compileContract(ConstantsBuilder.empty(), mkScript(LIQUIDATIONS + name)).getErgoTree)
  }

  /** Terms compiled into an ErgoTree template as named constants. */
  trait ContractTerms {
    def constants: Seq[(String, Any)]
  }

  /**
   * A lender's terms, which become the constants of one zero-coupon offer. There is no lender term: the
   * bond always pays the vault, and the lender is whoever holds the bond asset.
   */
  case class OfferTerms(bondHash: Array[Byte],
                        repayment: Long,
                        batchingFee: Long,
                        maturity: Int,
                        liqScriptHash: Array[Byte],
                        collateralErg: Long,
                        collateralTokens: Seq[(Array[Byte], Long)]) extends ContractTerms {

    def constants: Seq[(String, Any)] = Seq(
      "CONST_BOND_HASH" -> Colls.fromArray(bondHash),
      "CONST_REPAYMENT" -> repayment,
      "CONST_BATCHING_FEE" -> batchingFee,
      "CONST_MATURITY" -> maturity,
      "CONST_LIQ_SCRIPT_HASH" -> Colls.fromArray(liqScriptHash),
      "CONST_COLLATERAL_ERG" -> collateralErg,
      "CONST_COLLATERAL_TOKENS" -> tokenColl(collateralTokens)
    )
  }

  /**
   * A borrower's terms, which become the constants of one zero-coupon order. The principal is a value
   * floor plus an exact token list, so one template serves ERG loans (no tokens) and token loans (one).
   */
  case class OrderTerms(bondHash: Array[Byte],
                        borrowerHash: Array[Byte],
                        principalErg: Long,
                        principalTokens: Seq[(Array[Byte], Long)],
                        repayment: Long,
                        batchingFee: Long,
                        maturity: Int,
                        liqScriptHash: Array[Byte]) extends ContractTerms {

    def constants: Seq[(String, Any)] = Seq(
      "CONST_BOND_HASH" -> Colls.fromArray(bondHash),
      "CONST_BORROWER_HASH" -> Colls.fromArray(borrowerHash),
      "CONST_PRINCIPAL_ERG" -> principalErg,
      "CONST_PRINCIPAL_TOKENS" -> tokenColl(principalTokens),
      "CONST_REPAYMENT" -> repayment,
      "CONST_BATCHING_FEE" -> batchingFee,
      "CONST_MATURITY" -> maturity,
      "CONST_LIQ_SCRIPT_HASH" -> Colls.fromArray(liqScriptHash)
    )
  }

  /** An on-close order's terms: as OrderTerms, but a duration counted from the fill replaces the maturity. */
  case class OnCloseOrderTerms(bondHash: Array[Byte],
                               borrowerHash: Array[Byte],
                               principalErg: Long,
                               principalTokens: Seq[(Array[Byte], Long)],
                               repayment: Long,
                               batchingFee: Long,
                               duration: Int,
                               liqScriptHash: Array[Byte]) extends ContractTerms {

    def constants: Seq[(String, Any)] = Seq(
      "CONST_BOND_HASH" -> Colls.fromArray(bondHash),
      "CONST_BORROWER_HASH" -> Colls.fromArray(borrowerHash),
      "CONST_PRINCIPAL_ERG" -> principalErg,
      "CONST_PRINCIPAL_TOKENS" -> tokenColl(principalTokens),
      "CONST_REPAYMENT" -> repayment,
      "CONST_BATCHING_FEE" -> batchingFee,
      "CONST_DURATION" -> duration,
      "CONST_LIQ_SCRIPT_HASH" -> Colls.fromArray(liqScriptHash)
    )
  }

  private def tokenColl(tokens: Seq[(Array[Byte], Long)]): Coll[(Coll[Byte], Long)] =
    Colls.fromArray(tokens.map { case (id, amount) => (Colls.fromArray(id), amount) }.toArray)(
      RType.pairRType(sigma.collRType(sigma.ByteType), sigma.LongType))

  /**
   * Each template is compiled once, with every term set to a value that appears nowhere else in its
   * script, so each term is found by value at exactly one constant position.
   */
  private val OrderSentinels = OrderTerms(
    bondHash = Array.fill(32)(0x61.toByte),
    borrowerHash = Array.fill(32)(0x62.toByte),
    principalErg = 8888888881L,
    principalTokens = Seq(Array.fill(32)(0x64.toByte) -> 8888888885L),
    repayment = 8888888882L,
    batchingFee = 8888888883L,
    maturity = 888884,
    liqScriptHash = Array.fill(32)(0x63.toByte))

  private val OnCloseOrderSentinels = OnCloseOrderTerms(
    bondHash = Array.fill(32)(0x71.toByte),
    borrowerHash = Array.fill(32)(0x72.toByte),
    principalErg = 9999999991L,
    principalTokens = Seq(Array.fill(32)(0x74.toByte) -> 9999999995L),
    repayment = 9999999992L,
    batchingFee = 9999999993L,
    duration = 999994,
    liqScriptHash = Array.fill(32)(0x73.toByte))

  private val OfferSentinels = OfferTerms(
    bondHash = Array.fill(32)(0x51.toByte),
    repayment = 7777777771L,
    batchingFee = 7777777772L,
    maturity = 777773,
    liqScriptHash = Array.fill(32)(0x53.toByte),
    collateralErg = 7777777774L,
    collateralTokens = Seq(Array.fill(32)(0x54.toByte) -> 7777777775L))

  /**
   * Every offer (or order) is this tree with different constants. `positions` maps each term to its
   * constant index, which is what a UI needs to read the terms off any box with this template.
   */
  case class ContractTemplate(tree: ErgoTree, positions: Map[String, Int]) {

    /** What indexers group boxes by. Finds candidates; it does not verify them: see `matches`. */
    def templateHash: Array[Byte] = Blake2b256.hash(tree.template)

    /**
     * Whether `candidate` is this template stamped with some terms. A template hash leaves out EVERY
     * constant, so a tree can share it while changing a literal such as an order's mint amount, or the
     * vault hash. Only the terms may differ, and only in value, never in type.
     */
    def matches(candidate: ErgoTree): Boolean = {
      val terms = positions.values.toSet
      candidate.header == tree.header &&
        candidate.template.sameElements(tree.template) &&
        candidate.constants.size == tree.constants.size &&
        tree.constants.indices.forall { i =>
          if (terms.contains(i)) candidate.constants(i).tpe == tree.constants(i).tpe
          else candidate.constants(i) == tree.constants(i)
        }
    }

    /** Stamp out one instance by replacing constants, so its template is this one byte for byte. */
    def withTerms(terms: ContractTerms): ErgoTree = {
      val replaced = terms.constants.foldLeft(tree.constants) { case (consts, (name, value)) =>
        val pos = positions(name)
        consts.updated(pos, Constant[SType](value.asInstanceOf[SType#WrappedType], consts(pos).tpe))
      }
      VersionContext.withVersions(tree.version, tree.version) {
        ErgoTree(tree.header, replaced, tree.root.right.get)
      }
    }
  }

  /** blake2b256 of BondAssetVault, which every offer and order pins as its bonds' R8. */
  def vaultHash(ctx: BlockchainContext): Array[Byte] = Blake2b256.hash(mkBondAssetVault(ctx).getErgoTree.bytes)

  /** Compile-time values every template shares; they are not terms, and `matches` holds them fixed. */
  private def fixedConstants(ctx: BlockchainContext): Seq[(String, Any)] =
    Seq("CONST_VAULT_HASH" -> Colls.fromArray(vaultHash(ctx)))

  private def mkTemplate(ctx: BlockchainContext, name: String, sentinels: ContractTerms): ContractTemplate = {
    val constants = (fixedConstants(ctx) ++ sentinels.constants).foldLeft(ConstantsBuilder.create()) {
      case (b, (term, value)) => b.item(term, value)
    }.build()
    val tree = ctx.compileContract(constants, mkScript(name)).getErgoTree

    val positions = sentinels.constants.map { case (term, value) =>
      val found = tree.constants.indices.filter(i => tree.constants(i).value == value)
      require(found.size == 1, s"$term sentinel found at ${found.mkString(",")} in the $name template")
      term -> found.head
    }.toMap
    ContractTemplate(tree, positions)
  }

  def mkOfferTemplate(ctx: BlockchainContext): ContractTemplate =
    mkTemplate(ctx, ZC_BOND_PREFIX + "Offer", OfferSentinels)

  def mkOrderTemplate(ctx: BlockchainContext): ContractTemplate =
    mkTemplate(ctx, ZC_BOND_PREFIX + "Order", OrderSentinels)

  def mkOnCloseOrderTemplate(ctx: BlockchainContext): ContractTemplate =
    mkTemplate(ctx, ZC_BOND_PREFIX + "OrderOnClose", OnCloseOrderSentinels)

  def mkZcOnCloseOrderContract(ctx: BlockchainContext, template: ContractTemplate,
                               terms: OnCloseOrderTerms): ErgoContract =
    ctx.newContract(template.withTerms(terms))

  def mkZcOfferContract(ctx: BlockchainContext, template: ContractTemplate, terms: OfferTerms): ErgoContract =
    ctx.newContract(template.withTerms(terms))

  def mkZcOrderContract(ctx: BlockchainContext, template: ContractTemplate, terms: OrderTerms): ErgoContract =
    ctx.newContract(template.withTerms(terms))

}
