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

/**
 * One read of the USD oracle pool: nanoERG per USD, as the oracle rule reads it. `refreshing`: a refresh in
 * the mempool already spends this box, so a transaction reading it fails once that refresh confirms.
 */
case class OracleState(box: InputUTXO, price: Long, epoch: Int, created: Int, refreshing: Boolean = false)

/**
 * A request to turn one bond unit into shares: ZC_Standard_FractionalMint, or a market's
 * ZC_Standard_DutchAuctionMint (`dutch`). R8 is (N, pull fee, mint fee), then for a Dutch request the
 * auction's (opening bps, floor bps, length).
 */
case class StdShareRequest(box: InputUTXO, dutch: Option[Market], ownerHash: Array[Byte], terms: Seq[Long],
                           metadata: Seq[Array[Byte]]) {
  def bondAsset: ErgoId = box.tokens.head.id
  def shares: Long = terms(0)
  def pullFee: Long = terms(1)
  def mintFee: Long = terms(2)
  def auction: Seq[Long] = terms.drop(3)
  def shareId: ErgoId = box.id
}

/**
 * A share vault: ZC_Standard_FractionalVault or ZC_Standard_DutchAuctionVault. It holds the share's
 * N+1 reserve, then the bond unit until the pull, then the settlement's proceeds. For a Dutch vault,
 * `auction` is (repayment, opening bps, floor bps, length, start height) and `state` is 0 repaid, 1 auction
 * open, 2 sold or nothing to sell, 3 paid in kind (meaningful only after the pull).
 */
case class StdShareVault(box: InputUTXO, dutch: Boolean, bondAsset: ErgoId, n: Long, outstanding: Long,
                         pullFee: Long, state: Option[Byte], auction: Seq[Long], currency: Option[Array[Byte]]) {
  def shareId: ErgoId = box.tokens.head.id
  def assets: Seq[Token] = box.tokens.drop(1)
  def holding: Boolean = assets.exists(_.id == bondAsset)
  def auctionOpen: Boolean = dutch && !holding && state.contains(1.toByte)
  def currencyToken: Option[ErgoId] = currency.filter(_.length == 32).map(new ErgoId(_))
}

/** An ask: a ZC_Standard_SaleOrder, or a ZC_Standard_NavAsk (`nav`) priced by its R6 expression. */
case class StdAsk(box: InputUTXO, market: Market, nav: Boolean, sellerHash: Array[Byte], terms: Seq[Long],
                  pricingHash: Option[Array[Byte]], navAsset: Option[ErgoId]) {
  def token: Token = box.tokens.head
  def numerator: Long = terms(0)
  def denominator: Long = terms(1)
  def minFill: Long = terms(2)
  def expiry: Long = terms(3)
  def margin: Long = if (nav) terms(4) else 0L
  def wholeUnits: Long = if (nav) terms(5) else 1L
  /** The fixed price (a sale order's price, a NAV ask's floor) of `units` raw units. */
  def fixedPrice(units: Long): BigInt = (BigInt(units) * numerator + denominator - 1) / denominator
}

/**
 * A lend offer. R5 terms: 0 yield bps, 1 min and 2 max blocks to maturity, 3 batcher fee, 4 new-bond fee
 * reserve, 5 collateral floor bps, 6 expiry, 7 minimum price, 8 oracle age, 9 min auction floor, 10 max
 * auction length. R7, R8, R9: a fractional delivery's terms, metadata and mint script hash, or empty.
 */
case class StdOffer(box: InputUTXO, market: Market, ownerHash: Array[Byte], terms: Seq[Long],
                    policyHash: Array[Byte], fractional: Seq[Long], metadata: Seq[Array[Byte]], mintHash: Array[Byte]) {
  def yieldBps: Long = terms(0)
  def batcherFee: Long = terms(3)
  def feeReserve: Long = terms(4)
  def floorBps: Long = terms(5)
  def expiry: Long = terms(6)
  def minPrice: Long = terms(7)
  def maxAge: Long = terms(8)
  /** Capital in the market's unit: the box's nanoERG, or its repayment token. */
  def capital: Long = market.token.fold(box.value)(id => box.tokens.filter(_.id == id).map(_.amount).sum)
  def delivers: Boolean = fractional.nonEmpty
}

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

  val askTemplateHash: Array[Byte] = templateHash(market(Market.Erg).saleOrder.ergoTree)
  val navAskTemplateHash: Array[Byte] = templateHash(market(Market.Erg).navAsk.ergoTree)
  val offerTemplateHash: Array[Byte] = templateHash(market(Market.Erg).offer.ergoTree)
  val dutchMintTemplateHash: Array[Byte] = templateHash(market(Market.Erg).dutchMint.ergoTree)

  def asShareRequest(box: InputUTXO): Option[StdShareRequest] = {
    val tree = box.input.getErgoTree
    val dutch = marketWhere(tree, _.dutchMint)
    for {
      _ <- if (dutch.isDefined || sameTree(tree, contracts.fractionalMint.ergoTree)) Some(()) else None
      if box.tokens.size == 1 && box.tokens.head.amount == 1L
      owner <- bytesReg(box, 7) if owner.length == 32
      terms <- longsReg(box, 8) if terms.length == (if (dutch.isDefined) 6 else 3)
      metadata <- bytesListReg(box, 9) if metadata.size == 3
    } yield StdShareRequest(box, dutch, owner, terms.toSeq, metadata)
  }

  def asShareVault(box: InputUTXO): Option[StdShareVault] = {
    val tree = box.input.getErgoTree
    val dutch = sameTree(tree, contracts.dutchVault.ergoTree)
    for {
      _ <- if (dutch || sameTree(tree, contracts.fractionalVault.ergoTree)) Some(()) else None
      if box.tokens.nonEmpty
      asset <- bytesReg(box, 4) if asset.length == 32
      supply <- longsReg(box, 5) if supply.length == 3 && box.tokens.head.amount == supply(0) + 1L
      auction <- if (dutch) longsReg(box, 7).filter(_.length == 5).map(_.toSeq) else Some(Seq.empty[Long])
      currency <- if (dutch) bytesReg(box, 8).map(Some(_)) else Some(None)
    } yield StdShareVault(box, dutch, new ErgoId(asset), supply(0), supply(1), supply(2), byteReg(box, 6), auction, currency)
  }

  def asAsk(box: InputUTXO): Option[StdAsk] = {
    val tree = box.input.getErgoTree
    val sale = marketWhere(tree, _.saleOrder)
    val nav = marketWhere(tree, _.navAsk)
    for {
      m <- sale.orElse(nav)
      if box.tokens.size == 1
      seller <- bytesReg(box, 4) if seller.length == 32
      terms <- longsReg(box, 5) if (if (nav.isDefined) terms.length >= 6 && terms.length <= 64 else terms.length == 4)
      if terms(0) > 0L && terms(1) > 0L
      pricing <- if (nav.isDefined) bytesReg(box, 6).map(Some(_)) else Some(None)
      asset <- if (nav.isDefined) bytesReg(box, 7).filter(_.length == 32).map(a => Some(new ErgoId(a))) else Some(None)
    } yield StdAsk(box, m, nav.isDefined, seller, terms.toSeq, pricing, asset)
  }

  def asOffer(box: InputUTXO): Option[StdOffer] = for {
    m <- marketWhere(box.input.getErgoTree, _.offer)
    owner <- bytesReg(box, 4) if owner.length == 32
    terms <- longsReg(box, 5) if terms.length == 11
    policy <- bytesReg(box, 6)
    fractional <- longsReg(box, 7)
    metadata <- bytesListReg(box, 8)
    mint <- bytesReg(box, 9)
  } yield StdOffer(box, m, owner, terms.toSeq, policy, fractional.toSeq, metadata, mint)

  /** A short name for a script this client knows, for transaction summaries. */
  def nameOf(tree: ErgoTree): Option[String] =
    contracts.entries.find(e => !e.expression && sameTree(tree, e.contract.ergoTree)).map(e => s"ZC_Standard_${e.label}")

  /** Whether a script is one of the share mint requests: ZC_Standard_FractionalMint or a Dutch mint. */
  def isShareMint(tree: ErgoTree): Boolean =
    sameTree(tree, contracts.fractionalMint.ergoTree) || marketWhere(tree, _.dutchMint).isDefined

  /**
   * The register in which a box under `tree` names a script by its hash, which zcs1 script disclosure
   * covers: an order's borrower (R6), a bond's borrower (R5), a lend offer's owner and an ask's seller (R4),
   * and a share mint request's owner (R7).
   */
  def namedScriptRegister(tree: ErgoTree): Option[Int] =
    if (orderMarket(tree).isDefined) Some(6)
    else if (bondMarket(tree).isDefined) Some(5)
    else if (Seq[MarketContracts => Contract](_.offer, _.saleOrder, _.navAsk).exists(marketWhere(tree, _).isDefined)) Some(4)
    else if (isShareMint(tree)) Some(7)
    else None

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

  /** zcs1's maturity grid: fixed maturities are multiples of 21,600 blocks, about 30 days of 2-minute blocks. */
  val MaturityGrid: Long = 21600L

  /**
   * The grid height nearest `target` that still lies more than 2 blocks above `height`, so an order made now
   * can be filled; a tie goes to the later height.
   */
  def snapMaturity(target: Long, height: Long): Long = {
    val below = Math.floorDiv(target, MaturityGrid) * MaturityGrid
    Seq(below, below + MaturityGrid, below + 2 * MaturityGrid)
      .filter(_ > height + 2L)
      .minBy(g => (math.abs(g - target), -g))
  }
}
