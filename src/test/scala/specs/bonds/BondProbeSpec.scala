package org.lithosprotocol.sigmafi
package specs.bonds

import org.scalatest.propspec.AnyPropSpec

/** The compile gate for the bond contract set, offline. */
class BondProbeSpec extends AnyPropSpec with BondSpecBase {

  import BondSpecBase._

  property("every bond contract compiles at ErgoTree v3 with the size bit set") {
    withCtx { ctx =>
      val set = Seq(
        "ZC_ERG_Bond" -> bondContract(ctx, ErgBond),
        "ZC_Token_Bond" -> bondContract(ctx, TokenBond),
        "BondAssetVault" -> vault(ctx))
      set.foreach { case (n, c) =>
        withClue(s"$n: ") {
          c.ergoTree.version shouldBe 3
          (c.ergoTree.header & 0x08).toByte should not be 0.toByte
        }
      }
      println("\n[bonds] SigmaFi V2 zero-coupon bond set")
      println("-" * 78)
      set.foreach { case (n, c) => println(f"  $n%-18s ${c.propBytes.length}%5d bytes") }
      println("-" * 78)
    }
  }

  property("one vault serves every bond: it compiles with no constant naming an asset") {
    withCtx { ctx =>
      vault(ctx).ergoTree.constants.map(_.tpe) should not contain sigma.ast.SCollection.SByteArray
    }
  }

  property("ContractClient's V2 report compiles everything and lists every order term") {
    withCtx { ctx =>
      val report = ksingh.sigmabonds.client.BondsV2Report.lines(ctx, Seq(stableToken.toString))
      report.foreach(l => println(s"[report] $l"))
      report.count(_.startsWith("  constant ")) shouldBe 16
      val liq = scorex.util.encode.Base16.encode(matured(ctx).hashedValueBytes)
      report should contain(s"    hash:  $liq")
    }
  }

  property("the token bond pins its repayment token id") {
    withCtx { ctx =>
      bondContract(ctx, TokenBond).ergoTreeHex should include(stableToken.toString)
    }
  }

  property("the Lithos integration reference matches current compiler output") {
    withCtx(LithosReference.check)
  }

  property("the exported token prototype substitutes to the directly compiled SigUSD bond") {
    withCtx { ctx =>
      val template = LithosReference.tokenPrototype(ctx)
      val substituted = template.withTerms(LithosReference.tokenTerms(LithosReference.SigUsd))
      val direct = ksingh.sigmabonds.client.ScriptGenerator.mkZcBondContract(ctx, Some(LithosReference.SigUsd)).getErgoTree
      substituted.bytes.toSeq shouldBe direct.bytes.toSeq
      template.matches(direct) shouldBe true
    }
  }
}
