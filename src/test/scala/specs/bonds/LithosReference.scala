package org.lithosprotocol.sigmafi
package specs.bonds

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import ksingh.sigmabonds.client.ScriptGenerator
import ksingh.sigmabonds.client.ScriptGenerator.{ContractTemplate, ContractTerms}
import org.ergoplatform.appkit.{Address, BlockchainContext, NetworkType}
import scorex.crypto.hash.Blake2b256
import scorex.util.encode.Base16
import sigma.{Colls, VersionContext}
import sigma.ast.{ErgoTree, SType}
import sigma.serialization.ValueSerializer
import specs.harness.ContractSpecBase

/** Offline export of the exact client compiler output; the surrounding integration notes are authored. */
object LithosReference extends ContractSpecBase {
  val Document = "docs/LITHOS_SIGMAFI_V2_REFERENCE.md"
  private val Begin = "<!-- BEGIN GENERATED CONTRACT REFERENCE -->"
  private val End = "<!-- END GENERATED CONTRACT REFERENCE -->"
  val TokenSentinel: String = "7e" * 32
  val SigUsd: String = "03faf2cb329f2e90d6d23b58d91bbb6c046aa143261cc21f52fbe2824bfcbf04"

  private case class Entry(name: String, tree: ErgoTree, terms: Map[String, Int] = Map.empty,
                           address: Boolean = false)
  private def hex(bytes: Array[Byte]): String = Base16.encode(bytes)
  private def hash(bytes: Array[Byte]): String = hex(Blake2b256.hash(bytes))
  private def read(path: String): String =
    new String(Files.readAllBytes(Paths.get(path)), UTF_8).replace("\r\n", "\n")

  def tokenPrototype(ctx: BlockchainContext): ContractTemplate = {
    val tree = ScriptGenerator.mkZcBondContract(ctx, Some(TokenSentinel)).getErgoTree
    val sentinel = Colls.fromArray(Base16.decode(TokenSentinel).get)
    val positions = tree.constants.indices.filter(i => tree.constants(i).value == sentinel)
    require(positions.size == 1, "repayment token must occupy exactly one constant position")
    ContractTemplate(tree, Map("CONST_TOKEN_ID" -> positions.head))
  }

  def tokenTerms(id: String): ContractTerms = new ContractTerms {
    def constants: Seq[(String, Any)] = Seq("CONST_TOKEN_ID" -> ScriptGenerator.idFromStr(id))
  }

  private def generated(ctx: BlockchainContext): String = {
    val fixed = ScriptGenerator.mkOrderTemplate(ctx)
    val onClose = ScriptGenerator.mkOnCloseOrderTemplate(ctx)
    val token = tokenPrototype(ctx)
    val maturity = ScriptGenerator.mkLiquidationScript(ctx, "MaturityLiquidation")
    val entries = Seq(
      Entry("BondAssetVault", ScriptGenerator.mkBondAssetVault(ctx).getErgoTree, address = true),
      Entry("ZC_ERG_Bond", ScriptGenerator.mkZcBondContract(ctx, None).getErgoTree, address = true),
      Entry("ZC_Token_Bond prototype", token.tree, token.positions),
      Entry("ZC_Token_Bond SigUSD example", ScriptGenerator.mkZcBondContract(ctx, Some(SigUsd)).getErgoTree,
        token.positions, address = true),
      Entry("ZC_Order prototype", fixed.tree, fixed.positions),
      Entry("ZC_OrderOnClose prototype", onClose.tree, onClose.positions),
      Entry("MaturityLiquidation compilation tree", maturity.tree))
    val b = new StringBuilder
    def line(s: String = ""): Unit = { b.append(s); b.append('\n') }
    def code(s: String): Unit = { line("```text"); line(s); line("```"); line() }

    line("## Generated compiler output")
    line()
    line("Generated offline through `ScriptGenerator` using the repository's V6 mock context (height 123413).")
    line("All entries have ErgoTree version 3. Full-tree hashes and template hashes are different inputs to blake2b256.")
    line("Order and token prototypes contain sentinels; replace only their listed terms before creating boxes.")
    line()
    line("| Entry | Tree bytes | Full ErgoTree blake2b256 | Template blake2b256 |")
    line("|---|---:|---|---|")
    entries.foreach(e => line(s"| ${e.name} | ${e.tree.bytes.length} | `${hash(e.tree.bytes)}` | `${hash(e.tree.template)}` |"))
    line()
    entries.foreach { e =>
      line(s"### ${e.name}")
      line()
      line(f"Header: `0x${e.tree.header & 0xff}%02x`; constant count: `${e.tree.constants.size}`.")
      line()
      line("Full serialized ErgoTree hex:")
      code(hex(e.tree.bytes))
      line("Raw template hex (`ErgoTree.template`, excludes all constants):")
      code(hex(e.tree.template))
      if (e.address) {
        line("Mainnet address:")
        code(Address.fromErgoTree(e.tree, NetworkType.MAINNET).toString)
        line("Testnet address:")
        code(Address.fromErgoTree(e.tree, NetworkType.TESTNET).toString)
      }
      if (e.terms.nonEmpty) {
        val names = e.terms.map { case (name, index) => index -> name }
        line("Constant registry. Only named terms may change; every `fixed` constant must match this prototype exactly.")
        line("Values below are serialized Sigma constants, including their type prefix; they are not raw token IDs.")
        line()
        line("| Zero-based constant index | Role | Sigma type | Serialized constant hex |")
        line("|---:|---|---|---|")
        e.tree.constants.zipWithIndex.foreach { case (c, i) =>
          val encoded = VersionContext.withVersions(e.tree.version, e.tree.version) {
            hex(ValueSerializer.serialize(c))
          }
          line(s"| $i | `${names.getOrElse(i, "fixed")}` | `${c.tpe}` | `$encoded` |")
        }
        line()
      }
    }
    line("### MaturityLiquidation runtime expression")
    line()
    line("Attach these raw expression bytes to the bond input as context variable 0 of type `Coll[Byte]`.")
    line("Do not attach the compilation ErgoTree or a serialized Sigma `Coll[Byte]` constant in their place.")
    line()
    code(hex(maturity.bytes))
    line(s"Expression blake2b256 (bond R8 / order `CONST_LIQ_SCRIPT_HASH`): `${hex(maturity.hash)}`.")
    line()
    line("### Source fingerprints")
    line()
    line("blake2b256 over UTF-8 source text after normalizing CRLF to LF; these fingerprints are not script hashes.")
    line()
    line("| Compiler input | Normalized source blake2b256 |")
    line("|---|---|")
    Seq("contracts/bonds/P2P/ZC_ERG_Bond.ergo", "contracts/bonds/P2P/ZC_Token_Bond.ergo",
      "contracts/bonds/P2P/ZC_Order.ergo", "contracts/bonds/P2P/ZC_OrderOnClose.ergo",
      "contracts/bonds/lender-scripts/BondAssetVault.ergo", "contracts/bonds/liquidations/MaturityLiquidation.ergo",
      "src/main/scala/client/ScriptGenerator.scala", "src/main/scala/work/lithos/mutations/Contract.scala",
      "build.sbt", "project/build.properties").foreach { p => line(s"| `$p` | `${hash(read(p).getBytes(UTF_8))}` |") }
    b.toString
  }

  private def updated(ctx: BlockchainContext, document: String): String = {
    val begin = document.indexOf(Begin)
    val end = document.indexOf(End)
    require(begin >= 0 && end > begin && document.lastIndexOf(Begin) == begin && document.lastIndexOf(End) == end,
      "reference must contain exactly one ordered pair of generated-block markers")
    document.take(begin + Begin.length) + "\n\n" + generated(ctx) + "\n" + document.drop(end)
  }

  def check(ctx: BlockchainContext): Unit = {
    val current = read(Document)
    require(current == updated(ctx, current),
      "Lithos reference bytes are stale; run Test / runMain org.lithosprotocol.sigmafi.specs.bonds.LithosReference --write")
  }

  def main(args: Array[String]): Unit = {
    require(args.toSeq == Seq("--write") || args.toSeq == Seq("--check"), "use --write or --check")
    withCtx { ctx =>
      if (args.head == "--write") {
        Files.write(Paths.get(Document), updated(ctx, read(Document)).getBytes(UTF_8))
        println(s"Generated $Document")
      } else {
        check(ctx)
        println(s"Verified $Document")
      }
    }
  }
}
