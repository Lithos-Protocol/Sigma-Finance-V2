package ksingh.sigmabonds
package standard

import org.ergoplatform.sdk.ErgoId
import scorex.util.encode.Base16

/**
 * zcs1 token metadata: the EIP-4 name, description and decimals of bond units and shares, built only from
 * chain data so every client writes the same bytes and a reader can rebuild them to check (see
 * docs/ZC_STANDARD_OFFCHAIN.md, "Token metadata"). Display only: verification never trusts it.
 */
object StandardMetadata {

  val Profile = "zcs1"

  /** zcs1's share count and decimals: 1.000000 shares is one whole bond. */
  val ShareCount: Long = 1000000L
  val ShareDecimals = "6"

  /** A bond unit's decimals, which the standard order enforces. */
  val BondDecimals = "0"

  /** What a bond unit's metadata is built from: the fill's market, asset, amounts, heights and rule. */
  case class BondFacts(market: Market, asset: ErgoId, principal: Long, repayment: Long, start: Long, maturity: Long,
                       rule: String)

  object BondFacts {
    def of(b: StdBond, rule: Option[LiquidationRule]): BondFacts =
      BondFacts(b.market, b.asset, b.principal, b.repayment, b.start, b.maturity, ruleText(rule, b.payload, b.liqHash))
  }

  /** The bond a share's metadata names when the writer knows it. */
  case class ShareBond(asset: ErgoId, repayment: Long, maturity: Long)

  /** A raw amount in the market's unit: no trailing zeros, no trailing point, no separators. */
  def number(raw: Long, m: Market): String = plain(BigDecimal(raw) / BigDecimal(10).pow(Units.decimals(m)))

  /** Basis points as a percentage, formatted the same way: 9000 is "90", 9050 is "90.5". */
  def percent(bps: Long): String = plain(BigDecimal(bps) / 100)

  private def plain(d: BigDecimal): String = d.bigDecimal.stripTrailingZeros.toPlainString

  def id6(asset: ErgoId): String = asset.toString.take(6).toLowerCase

  /** The rule clause of a bond's description. */
  def ruleText(rule: Option[LiquidationRule], payload: Seq[Long], liqHash: Array[Byte]): String = rule match {
    case Some(AtMaturity(_)) => "liquidates at maturity"
    case Some(OracleRatio(_, _)) if payload.nonEmpty => s"liquidates below ${percent(payload.head)}% or at maturity"
    case _ => s"liquidation rule ${Base16.encode(liqHash).take(8)}"
  }

  /** A bond unit's (name, description, decimals). */
  def bond(f: BondFacts): Seq[String] = Seq(
    s"ZCS-${f.market.name}-B-${f.maturity}-${id6(f.asset)}",
    s"$Profile bond: repays ${number(f.repayment, f.market)} ${f.market.name} at height ${f.maturity}; " +
      s"lent ${number(f.principal, f.market)} at height ${f.start}; ${f.rule}. Verify on chain.",
    BondDecimals)

  /** S for standard shares, D and the floor percentage for Dutch auction shares. */
  def shareKind(auction: Option[AuctionTerms]): String = auction.fold("S")(a => "D" + percent(a.floorBps))

  /**
   * Shares' (name, description, decimals): naming the bond when the writer knows it, as `fractionalize` does,
   * and without it when the writer does not, as a lend offer posted before it buys anything.
   */
  def shares(m: Market, auction: Option[AuctionTerms], bond: Option[ShareBond]): Seq[String] = {
    val what = bond.fold(s"one ${m.name} bond")(b =>
      s"one bond ${id6(b.asset)} repaying ${number(b.repayment, m)} ${m.name} at height ${b.maturity}")
    val sale = auction.fold("")(a => s" If liquidated, auctioned from ${percent(a.openingBps)}% to " +
      s"${percent(a.floorBps)}% of the repayment over ${a.length} blocks.")
    Seq(
      s"ZCS-${m.name}-${shareKind(auction)}" + bond.fold("")(b => s"-${b.maturity}-${id6(b.asset)}"),
      s"$Profile shares: 1.000000 = $what.$sale Verify on chain.",
      ShareDecimals)
  }

  /** The metadata as EIP-4 register bytes. */
  def bytes(metadata: Seq[String]): Seq[Array[Byte]] = metadata.map(_.getBytes("UTF-8"))

  def text(bytes: Array[Byte]): String = new String(bytes, "UTF-8")
}
