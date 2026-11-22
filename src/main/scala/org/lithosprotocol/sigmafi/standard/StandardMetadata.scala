package org.lithosprotocol.sigmafi
package standard

import org.ergoplatform.sdk.ErgoId
import scorex.util.encode.Base16

/**
 * zcs1 token metadata: the EIP-4 name, description and decimals of bond units and shares, built only from
 * chain data so every client writes the same bytes and a reader can rebuild them to check (see
 * docs/ZC_STANDARD_OFFCHAIN.md, "Token metadata"). Descriptions are compact JSON with a fixed key order.
 * Display only: verification never trusts it.
 */
object StandardMetadata {

  val Profile = "zcs1"

  /** The `zcs` field of every description: the profile's version. */
  val Version = "1"

  /** zcs1's share count and decimals: 100.000000 shares is the whole bond, so 1.0 shares is 1% of it. */
  val ShareCount: Long = 100000000L
  val ShareDecimals = "6"

  /** A bond unit's decimals, which the standard order enforces. */
  val BondDecimals = "0"

  /** The `note` field of every description. */
  val Verify = "Verify on chain."

  /** A JSON field: its key, and its value already written as JSON. */
  type Field = (String, String)

  /** What a bond's metadata is built from: the fill's market, asset, amounts, heights and rule fields. */
  case class BondFacts(market: Market, asset: ErgoId, principal: Long, repayment: Long, start: Long, maturity: Long,
                       rule: Seq[Field])

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

  /** A JSON string. Every value written here is ASCII; quotes and backslashes are escaped all the same. */
  def str(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  /** A compact JSON object, keys in the order given. */
  def json(fields: Seq[Field]): String = fields.map { case (k, v) => s"${str(k)}:$v" }.mkString("{", ",", "}")

  /** The fields naming a bond's liquidation rule. */
  def ruleText(rule: Option[LiquidationRule], payload: Seq[Long], liqHash: Array[Byte]): Seq[Field] = rule match {
    case Some(AtMaturity(_)) => Seq("rule" -> str("maturity"))
    case Some(OracleRatio(_, _)) if payload.nonEmpty => Seq("rule" -> str("oracle"), "ratio" -> percent(payload.head))
    case _ => Seq("rule" -> str("other"), "ruleHash" -> str(Base16.encode(liqHash).take(8)))
  }

  /** The bond's terms: principal, repayment, start, maturity and rule. */
  private def terms(f: BondFacts): Seq[Field] = Seq(
    "principal" -> number(f.principal, f.market), "repayment" -> number(f.repayment, f.market),
    "start" -> f.start.toString, "maturity" -> f.maturity.toString) ++ f.rule

  /** A bond unit's (name, description, decimals). */
  def bond(f: BondFacts): Seq[String] = Seq(
    s"ZCS-Bond-Unit-${id6(f.asset)}",
    json(Seq("zcs" -> Version, "type" -> str("bond unit"), "market" -> str(f.market.name)) ++ terms(f) ++
      Seq("note" -> str(Verify))),
    BondDecimals)

  /**
   * Shares' (name, description, decimals): naming the bond when the writer knows it, as `fractionalize` does,
   * and without it when the writer does not, as a lend offer posted before it buys anything. Plain, Selective
   * and Dutch shares share a name; the description's `type` and `auction` tell them apart: "selective shares"
   * carry the auction's in-kind `threshold`, "auction shares" (Dutch) always auction.
   */
  def shares(m: Market, auction: Option[AuctionTerms], bond: Option[BondFacts]): Seq[String] = {
    val whole = plain(BigDecimal(ShareCount) / BigDecimal(10).pow(ShareDecimals.toInt))
    val kind = auction.fold("shares")(a => if (a.selective) "selective shares" else "auction shares")
    val head = Seq("zcs" -> Version, "type" -> str(kind), "shares" -> whole)
    val what = bond.fold(Seq("market" -> str(m.name), "via" -> str("lend offer")))(b =>
      Seq("bond" -> str(id6(b.asset)), "market" -> str(m.name)) ++ terms(b))
    val sale = auction.toSeq.map(a => "auction" ->
      json(Seq("from" -> percent(a.openingBps), "to" -> percent(a.floorBps), "blocks" -> a.length.toString,
        "expiry" -> a.expiry.toString) ++ (if (a.selective) Seq("threshold" -> a.threshold.toString) else Seq.empty)))
    Seq(
      "ZCS-Bond-Shares" + bond.fold("")(b => s"-${id6(b.asset)}"),
      json(head ++ what ++ sale ++ Seq("note" -> str(Verify))),
      ShareDecimals)
  }

  /**
   * A wrap receipt's (name, description, decimals): one indivisible unit standing for `amount` raw units of the
   * wrapped token, which the receipt's holder takes back by burning it.
   */
  def receipt(wrapped: ErgoId, amount: Long): Seq[String] = Seq(
    s"ZCS-Wrap-${id6(wrapped)}",
    json(Seq("zcs" -> Version, "type" -> str("wrap receipt"), "token" -> str(wrapped.toString),
      "amount" -> amount.toString, "note" -> str(Verify))),
    "0")

  /** The metadata as EIP-4 register bytes. */
  def bytes(metadata: Seq[String]): Seq[Array[Byte]] = metadata.map(_.getBytes("UTF-8"))

  def text(bytes: Array[Byte]): String = new String(bytes, "UTF-8")
}
