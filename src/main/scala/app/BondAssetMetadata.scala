package ksingh.sigmabonds
package app

import io.circe.Json
import org.ergoplatform.sdk.ErgoId
import scorex.util.encode.Base16
import work.lithos.mutations.Token

/** EIP-4 display metadata, generated from the fill's terms. Protocol verification never trusts it. */
object BondAssetMetadata {
  private val NamePrefix = "SigmaFi-ZC-Bond-"

  /** Match LithosDex's LD-NFT convention: the first six hexadecimal characters of the token id. */
  def name(asset: ErgoId): String = NamePrefix + asset.toString.take(6)

  def description(order: OrderInfo, repayToken: Option[ErgoId], maturity: Int, buildHeight: Int,
                   liquidation: String): String = {
    def contents(value: Long, tokens: Seq[Token]): Json = Json.obj(
      "nanoErgs" -> Json.fromLong(value),
      "tokens" -> Json.fromValues(tokens.map(t => Json.obj(
        "id" -> Json.fromString(t.id.toString), "amount" -> Json.fromLong(t.amount)))))

    val fields = Seq(
      "type" -> Json.fromString("SigmaFi ZC-Bond"),
      "bondAsset" -> Json.fromString(order.box.id.toString),
      "borrowerHash" -> Json.fromString(Base16.encode(order.borrowerHash)),
      "principal" -> contents(math.max(order.principalErg, Protocol.MinBox), order.principalTokens),
      "repayment" -> Json.fromLong(order.repayment),
      "repayToken" -> Json.fromString(repayToken.fold("ERG")(_.toString)),
      "maturity" -> Json.fromInt(maturity),
      "collateral" -> contents(order.box.value, order.box.tokens),
      "batchingFee" -> Json.fromLong(order.batchingFee),
      "liquidation" -> Json.fromString(liquidation),
      "liquidationHash" -> Json.fromString(Base16.encode(order.liqScriptHash)),
      "height" -> Json.fromInt(buildHeight))
    val duration = order.schedule match {
      case OnClose(blocks) => Seq("durationBlocks" -> Json.fromInt(blocks))
      case FixedMaturity(_) => Seq.empty
    }
    Json.obj((fields ++ duration): _*).noSpaces
  }
}
