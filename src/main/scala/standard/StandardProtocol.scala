package ksingh.sigmabonds
package standard

import ksingh.sigmabonds.app.Protocol._
import org.ergoplatform.sdk.ErgoId
import scorex.util.encode.Base16
import sigma.ast.ErgoTree
import work.lithos.mutations.{Contract, InputUTXO, Token}

/** How a bond may be liquidated: the expression its R8 names. */
sealed trait LiquidationRule {
  def name: String
  def expression: Contract
}

/** ZC_Standard_MaturityLiquidation: from maturity on, everything to the vault. */
case class AtMaturity(expression: Contract) extends LiquidationRule {
  def name = "maturity"
}

/**
 * ZC_Standard_ERG_SigUSD_Liquidation, compiled for `market`: before maturity, strictly below the
 * borrower's collateral ratio at a fresh USD oracle price; from maturity on, always.
 */
case class OracleRatio(market: Market, expression: Contract) extends LiquidationRule {
  def name = s"oracle ratio ($market repayment)"
}

/**
 * A standard order. `terms` is R4 exactly as the bond will copy it: 0 repayment, 1 principal nanoERG,
 * 2 repayment ERG floor, 3 maturity height or duration, 4 expiry height, 5 expiry fee, 6 mode, 7.. payload.
 */
case class StdOrder(box: InputUTXO, market: Market, terms: Seq[Long], principalTokens: Seq[Token],
                    borrowerHash: Array[Byte], liqHash: Array[Byte]) {
  def repayment: Long = terms(0)
  def principalErg: Long = terms(1)
  def ergFloor: Long = terms(2)
  def expiry: Long = terms(4)
  def expiryFee: Long = terms(5)
  def onClose: Boolean = terms(6) == 1L
  def payload: Seq[Long] = terms.drop(7)
  /** The principal in the market's unit: nanoERG, or the one principal token's amount. */
  def principal: Long = if (market.token.isEmpty) principalErg else principalTokens.headOption.fold(0L)(_.amount)
  def collateralErg: Long = box.value - expiryFee
  def collateral: Seq[Token] = box.tokens
}

/** A live standard bond. R9 is (lender fee reserve, start height, maturity height). */
case class StdBond(box: InputUTXO, market: Market, terms: Seq[Long], principalTokens: Seq[Token],
                   borrowerHash: Array[Byte], liqHash: Array[Byte], feeReserve: Long, start: Long, maturity: Long) {
  def asset: ErgoId = box.tokens.head.id
  def repayment: Long = terms(0)
  def ergFloor: Long = terms(2)
  def payload: Seq[Long] = terms.drop(7)
  def principal: Long = if (market.token.isEmpty) terms(1) else principalTokens.headOption.fold(0L)(_.amount)
  def collateral: Seq[Token] = box.tokens.drop(1)
  def collateralErg: Long = box.value - feeReserve
  /** The box's creation height: when the bond last moved, which starts its recollateralize wait. */
  def movedAt: Int = box.input.getCreationHeight
  def recollateralizableFrom: Long = movedAt.toLong + StandardProtocol.RecollateralizeWait
  /** How much of the repayment asset the collateral holds: nanoERG, or the repayment token. */
  def heldRepaymentAsset: Long = market.token.fold(collateralErg)(id => collateral.filter(_.id == id).map(_.amount).sum)
}

/** A settlement at the bond vault: the bond's 2-unit reserve and its proceeds. */
case class StdSettlement(box: InputUTXO, asset: ErgoId, bondId: Array[Byte], liquidated: Boolean) {
  def proceeds: Seq[Token] = box.tokens.drop(1)
}

/** One read of the USD oracle pool: nanoERG per USD, as the oracle rule reads it. */
case class OracleState(box: InputUTXO, price: Long, epoch: Int, created: Int)

/**
 * The ZC Standard as a client sees it, over the pinned trees: which boxes are orders, bonds and settlements,
 * and their terms. Recognizing a box's script and shape does not prove its asset genuine; see `originOf` in
 * the app for that.
 */
class StandardProtocol(val contracts: StandardContracts = StandardPins.load) {

  import StandardProtocol._

  def market(m: Market): MarketContracts = contracts.market(m)

  val bondTemplateHash: Array[Byte] = templateHash(market(Market.Erg).bond.ergoTree)
  val orderTemplateHash: Array[Byte] = templateHash(market(Market.Erg).order.ergoTree)

  private def marketWhere(tree: ErgoTree, of: MarketContracts => Contract): Option[Market] =
    Market.all.find(m => sameTree(tree, of(market(m)).ergoTree))

  def bondMarket(tree: ErgoTree): Option[Market] = marketWhere(tree, _.bond)
  def orderMarket(tree: ErgoTree): Option[Market] = marketWhere(tree, _.order)

  // ─── liquidation rules ───────────────────────────────────────────────────

  lazy val rules: Seq[LiquidationRule] =
    AtMaturity(contracts.maturity) +: Market.all.map(m => OracleRatio(m, market(m).oracleRule))

  def ruleOf(hash: Array[Byte]): Option[LiquidationRule] = rules.find(_.expression.hashedValueBytes.sameElements(hash))

  def ruleName(hash: Array[Byte]): String = ruleOf(hash).fold(s"UNKNOWN (${Base16.encode(hash)})")(_.name)

  // ─── boxes ───────────────────────────────────────────────────────────────

  def asOrder(box: InputUTXO): Option[StdOrder] = for {
    m <- orderMarket(box.input.getErgoTree)
    terms <- longsReg(box, 4) if terms.length >= 7 && terms.length <= 64
    principal <- tokensReg(box, 5)
    borrower <- bytesReg(box, 6) if borrower.length == 32
    liq <- bytesReg(box, 7) if liq.length == 32
  } yield StdOrder(box, m, terms.toSeq, principal, borrower, liq)

  def asBond(box: InputUTXO): Option[StdBond] = for {
    m <- bondMarket(box.input.getErgoTree)
    if box.tokens.nonEmpty && box.tokens.head.amount == 2L
    origin <- bytesReg(box, 4) if origin.sameElements(box.tokens.head.id.getBytes)
    borrower <- bytesReg(box, 5) if borrower.length == 32
    terms <- longsReg(box, 6) if terms.length >= 7 && terms.length <= 64
    principal <- tokensReg(box, 7)
    liq <- bytesReg(box, 8) if liq.length == 32
    lender <- longsReg(box, 9) if lender.length == 3 && lender(0) >= 0L && lender(0) <= box.value - MinBox
  } yield StdBond(box, m, terms.toSeq, principal, borrower, liq, lender(0), lender(1), lender(2))

  def asSettlement(box: InputUTXO): Option[StdSettlement] =
    if (!sameTree(box.input.getErgoTree, contracts.vault.ergoTree) || box.tokens.isEmpty || box.tokens.head.amount != 2L) None
    else for {
      bondId <- bytesReg(box, 4) if bondId.length == 32
      path <- byteReg(box, 5) if path == 0.toByte || path == 1.toByte
    } yield StdSettlement(box, box.tokens.head.id, bondId, path == 1.toByte)

  /** A short name for a script this client knows, for transaction summaries. */
  def nameOf(tree: ErgoTree): Option[String] =
    contracts.entries.find(e => !e.expression && sameTree(tree, e.contract.ergoTree)).map(e => s"ZC_Standard_${e.label}")

  // ─── oracle ──────────────────────────────────────────────────────────────

  /** The oracle pool's state, from boxes holding its NFT: exactly one, holding exactly one unit. */
  def asOracle(boxes: Seq[InputUTXO]): Option[OracleState] =
    boxes.filter(_.tokens.exists(t => t.id == OracleNft && t.amount == 1L)) match {
      case Seq(b) => for {
        price <- longReg(b, 4)
        epoch <- intReg(b, 5)
      } yield OracleState(b, price, epoch, b.input.getCreationHeight)
      case _ => None
    }

  /** Collateral value and debt in the oracle rule's common unit; None when the price is unusable. */
  def cover(bond: StdBond, price: Long): Option[(BigInt, BigInt)] =
    if (price <= 0L) None
    else {
      val sigUsd = bond.collateral.filter(_.id == Market.SigUsdId).map(t => BigInt(t.amount)).sum
      val collateral = BigInt(bond.collateralErg) * 100 + BigInt(price) * sigUsd
      val debt = if (bond.market.token.isEmpty) BigInt(bond.repayment) * 100 else BigInt(price) * bond.repayment
      if (debt <= 0) None else Some(collateral -> debt)
    }

  /** The collateral ratio in basis points of the repayment, as the oracle rule measures it. */
  def ratioBps(bond: StdBond, price: Long): Option[Long] =
    cover(bond, price).map { case (c, d) => (c * 10000 / d).min(BigInt(Long.MaxValue)).toLong }
}

object StandardProtocol {
  /** The USD oracle pool v2 singleton NFT the oracle rule reads, on mainnet. */
  val OracleNft: ErgoId = ErgoId.create("6a2b821b5727e85beb5e78b4efb9f0250d59cd48481d2ded2c23e91ba1d07c66")

  /** Blocks a bond must rest between recollateralizations, and how old a successor's stamp may be. */
  val RecollateralizeWait: Long = 60L
  val StampSlack: Long = 15L

  /** The start-height window a fill may use. */
  val StartSlack: Long = 8L

  /** The floor every standard batching fee shares on-chain: 0.002 ERG. */
  val MinBatchingFee: Long = 2000000L
}
