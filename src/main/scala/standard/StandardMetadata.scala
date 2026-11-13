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

  /** zcs1's share count and decimals: 100.000000 shares is the whole bond, so 1.0 shares is 1% of it. */
  val ShareCount: Long = 100000000L
  val ShareDecimals = "6"

  /** A bond unit's decimals, which the standard order enforces. */
  val BondDecimals = "0"

  /** The closing sentence of every description. */
  val Verify = "Verify on chain."

  /** What a bond's metadata is built from: the fill's market, asset, amounts, heights and rule sentence. */
  case class BondFacts(market: Market, asset: ErgoId, principal: Long, repayment: Long, start: Long, maturity: Long,
                       rule: String)

  object BondFacts {
    def of(b: StdBond, rule: Option[LiquidationRule]): BondFacts =
      BondFacts(b.market, b.asset, b.principal, b.repayment, b.start, b.maturity, ruleText(rule, b.payload, b.liqHash))
  }

  /** A raw amount in the market's unit: no trailing zeros, no trailing point, no separators. */
  def number(raw: Long, m: Market): String = plain(BigDecimal(raw) / BigDecimal(10).pow(Units.decimals(m)))

  /** Basis points as a percentage, formatted the same way: 9000 is "90", 9050 is "90.5". */
  def percent(bps: Long): String = plain(BigDecimal(bps) / 100)

  private def plain(d: BigDecimal): String = d.bigDecimal.stripTrailingZeros.toPlainString

  def id6(asset: ErgoId): String = asset.toString.take(6).toLowerCase

  /** "an" before a market name that starts with a vowel letter ("an ERG bond"), "a" otherwise ("a SigUSD bond"). */
  def article(m: Market): String = if (m.name.headOption.exists(c => "AEIOUaeiou".indexOf(c) >= 0)) "an" else "a"

  /** The sentence naming a bond's liquidation rule. */
  def ruleText(rule: Option[LiquidationRule], payload: Seq[Long], liqHash: Array[Byte]): String = rule match {
    case Some(AtMaturity(_)) => "It liquidates at maturity if unpaid."
    case Some(OracleRatio(_, _)) if payload.nonEmpty =>
      s"It liquidates below ${percent(payload.head)}% collateral, or at maturity if unpaid."
    case _ => s"It liquidates under liquidation rule ${Base16.encode(liqHash).take(8)}."
  }

  /** "lent {P} {M} at height {start} and repays {R} {M} at height {maturity}. {rule}" */
  private def terms(f: BondFacts): String =
    s"lent ${number(f.principal, f.market)} ${f.market.name} at height ${f.start} and repays " +
      s"${number(f.repayment, f.market)} ${f.market.name} at height ${f.maturity}. ${f.rule}"

  /** A bond unit's (name, description, decimals). */
  def bond(f: BondFacts): Seq[String] = Seq(
    s"ZCS-Bond-Unit-${id6(f.asset)}",
    s"ZC Standard bond. It ${terms(f)} $Verify",
    BondDecimals)

  /** "Shares" for standard shares, "Auction-Shares" for Dutch auction shares. */
  def shareKind(auction: Option[AuctionTerms]): String = if (auction.isDefined) "Auction-Shares" else "Shares"

  /**
   * Shares' (name, description, decimals): naming the bond when the writer knows it, as `fractionalize` does,
   * and without it when the writer does not, as a lend offer posted before it buys anything.
   */
  def shares(m: Market, auction: Option[AuctionTerms], bond: Option[BondFacts]): Seq[String] = {
    val what = bond.fold(s"1.0 shares equals 1% of ${article(m)} ${m.name} bond bought by a lend offer.")(b =>
      s"1.0 shares equals 1% of bond ${id6(b.asset)}. The bond ${terms(b)}")
    val sale = auction.fold("")(a => s" If liquidated, its collateral is auctioned from ${percent(a.openingBps)}% " +
      s"down to ${percent(a.floorBps)}% of the repayment over ${a.length} blocks.")
    Seq(
      s"ZCS-Bond-${shareKind(auction)}" + bond.fold("")(b => s"-${id6(b.asset)}"),
      s"ZC Standard shares. $what$sale $Verify",
      ShareDecimals)
  }

  /** The metadata as EIP-4 register bytes. */
  def bytes(metadata: Seq[String]): Seq[Array[Byte]] = metadata.map(_.getBytes("UTF-8"))

  def text(bytes: Array[Byte]): String = new String(bytes, "UTF-8")
}
