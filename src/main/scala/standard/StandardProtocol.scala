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
 * `guardHash` is R8, the fill guard expression a fill must also pass, when the borrower set one; the
 * standard guard reads its minimum fill ratio at payload(2).
 */
case class StdOrder(box: InputUTXO, market: Market, terms: Seq[Long], principalTokens: Seq[Token],
                    borrowerHash: Array[Byte], liqHash: Array[Byte], guardHash: Option[Array[Byte]] = None) {
  /** The guard's minimum collateral ratio at the fill, in basis points of the repayment. */
  def fillRatio: Option[Long] = guardHash.flatMap(_ => payload.lift(2))
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

/**
 * A live standard bond. R9 is (lender fee reserve, start height, maturity height). `pending`: not yet made, the
 * bond a fill of the order `box` would make; the order holds the collateral alone, with its expiry fee.
 */
case class StdBond(box: InputUTXO, market: Market, terms: Seq[Long], principalTokens: Seq[Token],
                   borrowerHash: Array[Byte], liqHash: Array[Byte], feeReserve: Long, start: Long, maturity: Long,
                   pending: Boolean = false) {
  def asset: ErgoId = if (pending) box.id else box.tokens.head.id
  def repayment: Long = terms(0)
  def ergFloor: Long = terms(2)
  def payload: Seq[Long] = terms.drop(7)
  def principal: Long = if (market.token.isEmpty) terms(1) else principalTokens.headOption.fold(0L)(_.amount)
  def collateral: Seq[Token] = if (pending) box.tokens else box.tokens.drop(1)
  def collateralErg: Long = box.value - (if (pending) terms(5) else feeReserve)
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
  } yield StdOrder(box, m, terms.toSeq, principal, borrower, liq, bytesReg(box, 8))

  /** Whether `hash` is the standard fill guard compiled for `m`. */
  def isFillGuard(hash: Array[Byte], m: Market): Boolean = hash.sameElements(market(m).fillGuard.hashedValueBytes)

  /** Whether a NAV ask's R6 names the guarded pricing expression, which also holds a sale to a minimum cover. */
  def isGuarded(ask: StdAsk): Boolean = ask.pricingHash.exists(_.sameElements(market(ask.market).guardedPricing.hashedValueBytes))

  /** A guarded ask's (minimum cover in basis points, maximum oracle age): R5(6) and R5(7). */
  def saleGuard(ask: StdAsk): Option[(Long, Long)] =
    if (isGuarded(ask) && ask.terms.size >= 8) Some(ask.terms(6) -> ask.terms(7)) else None

  /** The pricing expression a NAV ask's R6 names, as the bytes its var 2 carries. */
  def pricingOf(ask: StdAsk): Contract = if (isGuarded(ask)) market(ask.market).guardedPricing else market(ask.market).pricing

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
    coverOf(bond.market, bond.collateralErg, bond.collateral, bond.repayment, price)

  /** The collateral ratio in basis points of the repayment, as the oracle rule measures it. */
  def ratioBps(bond: StdBond, price: Long): Option[Long] =
    cover(bond, price).map { case (c, d) => (c * 10000 / d).min(BigInt(Long.MaxValue)).toLong }

  /** The bond's own early-liquidation ratio, when its rule is the oracle rule and it carries one. */
  def ownRatio(bond: StdBond): Option[Long] = ruleOf(bond.liqHash) match {
    case Some(OracleRatio(_, _)) if bond.payload.nonEmpty => Some(bond.payload.head)
    case _ => None
  }

  /**
   * Whether the bond's collateral is strictly below its own oracle ratio at `price`: its rule then lets anyone
   * liquidate it, sending all the collateral to the lender, as soon as an oracle that fresh is read.
   */
  def belowOwnRatio(bond: StdBond, price: Long): Boolean =
    ownRatio(bond).exists(r => cover(bond, price).exists { case (c, d) => c * 10000 < d * r })
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

  /**
   * The margin of a guarded fixed-price ask: a NAV ask priced at a hundredth of a percent of accrued value, so its
   * floor, the fixed price, is what a buyer pays, and the guarded expression still holds the sale to its cover.
   */
  val GuardedFixedMargin: Long = -9999L

  /** zcs1's maturity grid: fixed maturities are multiples of 21,600 blocks, about 30 days of 2-minute blocks. */
  val MaturityGrid: Long = 21600L

  /**
   * zcs1's snap: a maturity at least one grid step away moves to the nearest grid height, a tie going to the
   * later one, so it moves by at most half a step and the term stays at least 10,800 blocks. A nearer maturity
   * keeps its height: rounding it could shorten a 10-day loan to minutes.
   */
  def snapMaturity(target: Long, height: Long): Long =
    if (target - height < MaturityGrid) target
    else {
      val below = Math.floorDiv(target, MaturityGrid) * MaturityGrid
      Seq(below, below + MaturityGrid).minBy(g => (math.abs(g - target), -g))
    }

  /**
   * zcs1's default order expiry: a week, but never more than half of a fixed maturity's remaining term, so a
   * fill always leaves the borrower at least half the term to repay.
   */
  def defaultOrderExpiry(height: Long, maturity: Option[Long], week: Long): Long =
    maturity.fold(height + week)(h => math.max(height + 2L, math.min(height + week, height + (h - height) / 2L)))

  /**
   * Collateral value and debt in the oracle rule's common unit: C = 100 * nanoERG + price * SigUSD raw, and
   * L = 100 * repayment nanoERG or price * repayment raw. Other tokens count as zero. None for an unusable price.
   */
  def coverOf(market: Market, collateralErg: Long, collateral: Seq[Token], repayment: Long,
              price: Long): Option[(BigInt, BigInt)] =
    if (price <= 0L) None
    else {
      val sigUsd = collateral.filter(_.id == Market.SigUsdId).map(t => BigInt(t.amount)).sum
      val value = BigInt(collateralErg) * 100 + BigInt(price) * sigUsd
      val debt = if (market.token.isEmpty) BigInt(repayment) * 100 else BigInt(price) * repayment
      if (debt <= 0) None else Some(value -> debt)
    }

  /**
   * Where collateral crosses `ratioBps` of the repayment, as an oracle price in nanoERG per USD, and which way:
   * `belowWhenHigher` is true when prices above it (ERG cheaper) put the collateral below the ratio. None when
   * no price moves it across: all-ERG collateral against an ERG repayment, or SigUSD that covers it alone.
   */
  def crossingPrice(market: Market, collateralErg: Long, collateral: Seq[Token], repayment: Long,
                    ratioBps: Long): Option[(BigInt, Boolean)] = {
    val sigUsd = collateral.filter(_.id == Market.SigUsdId).map(t => BigInt(t.amount)).sum
    if (market.token.isEmpty) {
      // (100 E + P U) 10000 < 100 R ratio  <=>  P < 100 (R ratio - 10000 E) / (10000 U)
      val gap = BigInt(100) * (BigInt(repayment) * ratioBps - BigInt(10000) * collateralErg)
      if (sigUsd <= 0 || gap <= 0) None else Some((gap / (sigUsd * 10000)) -> false)
    } else {
      // (100 E + P U) 10000 < P R ratio  <=>  P > 1000000 E / (R ratio - 10000 U)
      val gap = BigInt(repayment) * ratioBps - sigUsd * 10000
      if (gap <= 0) None else Some((BigInt(1000000) * collateralErg / gap) -> true)
    }
  }

  /** An oracle price, nanoERG per USD, as dollars per ERG. */
  def usdPerErg(price: BigInt): String =
    if (price <= 0) "an unbounded price"
    else "$" + (BigDecimal(1000000000) / BigDecimal(price)).setScale(4, BigDecimal.RoundingMode.HALF_UP).bigDecimal.toPlainString

  /** The bond a fill of `order` at `start` would make, as the cover and offer checks read it. */
  def prospective(order: StdOrder, start: Long, maturity: Long, feeReserve: Long = 0L): StdBond =
    StdBond(order.box, order.market, order.terms, order.principalTokens, order.borrowerHash, order.liqHash,
      feeReserve, start, maturity, pending = true)
}
