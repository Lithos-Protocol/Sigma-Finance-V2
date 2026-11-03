package ksingh.sigmabonds
package client

import org.ergoplatform.appkit.{BlockchainContext, ErgoContract, NetworkType, RestApiErgoClient}
import scorex.crypto.hash.Blake2b256
import scorex.util.encode.Base16

object ContractClient extends App {

  val networkType: NetworkType = NetworkType.MAINNET
  val nodePort: String = if (networkType == NetworkType.MAINNET) ":9053/" else ":9052/"

  val client = RestApiErgoClient.create(
    "http://213.239.193.208" + nodePort,
    networkType,
    "",
    RestApiErgoClient.getDefaultExplorerUrl(networkType))

  val tokenIds: Seq[String] = Seq("03faf2cb329f2e90d6d23b58d91bbb6c046aa143261cc21f52fbe2824bfcbf04") // Add token id strings here

  client.execute {
    ctx =>
      println(s"Using network type: ${networkType}")

      BondsV2Report.lines(ctx, tokenIds).foreach(println)
  }
}

/**
 * What a UI needs to find SigmaFi V2 boxes: fixed addresses for the single-instance contracts, script
 * hashes for the ones offers commit to, and each offer and order template's hash with the constant index
 * of each term. A template hash only finds candidates: verify one with `ContractTemplate.matches`.
 */
object BondsV2Report {

  private def hashHex(contract: ErgoContract): String =
    Base16.encode(Blake2b256.hash(contract.getErgoTree.bytes))

  def lines(ctx: BlockchainContext, tokenIds: Seq[String]): Seq[String] = {
    val vault = ScriptGenerator.mkBondAssetVault(ctx)
    val generator = ScriptGenerator.mkBondAssetGenerator(ctx)
    val offer = ScriptGenerator.mkOfferTemplate(ctx)
    val order = ScriptGenerator.mkOrderTemplate(ctx)
    val onCloseOrder = ScriptGenerator.mkOnCloseOrderTemplate(ctx)
    val bonds = (None +: tokenIds.map(Some(_))).map { optTokenId =>
      optTokenId.getOrElse("ERG") -> ScriptGenerator.mkZcBondContract(ctx, optTokenId)
    }
    val liquidations = ScriptGenerator.LIQUIDATION_SCRIPTS.map(ScriptGenerator.mkLiquidationScript(ctx, _))

    Seq("SigmaFi V2 zero-coupon bonds",
      s"Bond Asset Generator: ${generator.toAddress}",
      s"Bond Asset Vault:     ${vault.toAddress}",
      s"  hash (CONST_VAULT_HASH, every bond's R8): ${hashHex(vault)}") ++
      bonds.flatMap { case (name, bond) =>
        Seq(s"ZC Bond ($name): ${bond.toAddress}", s"  hash (offer CONST_BOND_HASH): ${hashHex(bond)}")
      } ++
      Seq("Liquidation scripts (hash: bond R9 and offer CONST_LIQ_SCRIPT_HASH; bytes: executor's context var 0)") ++
      liquidations.flatMap { l =>
        Seq(s"  ${l.name}", s"    hash:  ${Base16.encode(l.hash)}", s"    bytes: ${Base16.encode(l.bytes)}")
      } ++
      Seq("ZC Offer" -> offer, "ZC Order (fixed height)" -> order, "ZC Order (on-close)" -> onCloseOrder).flatMap {
        case (name, template) =>
        Seq(s"$name template hash: ${Base16.encode(template.templateHash)}") ++
          template.positions.toSeq.sortBy(_._2).map { case (term, pos) => s"  constant $pos: $term" }
      }
  }
}
