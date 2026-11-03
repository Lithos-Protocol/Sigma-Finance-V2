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
      println(s"  ERG bond address: ${bondContract(ctx, ErgBond).mainnetAddress}")
      println("-" * 78)
    }
  }

  property("one vault serves every bond: it compiles with no constant naming an asset") {
    withCtx { ctx =>
      vault(ctx).ergoTree.constants.map(_.tpe) should not contain sigma.ast.SCollection.SByteArray
    }
  }

  property("every bond pins the vault's hash, so the vault is part of its address") {
    withCtx { ctx =>
      val vaultHash = scorex.util.encode.Base16.encode(hashOf(vault(ctx)))
      Seq(ErgBond, TokenBond).foreach(k => bondContract(ctx, k).ergoTreeHex should include(vaultHash))
    }
  }

  property("ContractClient's V2 report compiles everything and lists every offer term") {
    withCtx { ctx =>
      val report = ksingh.sigmabonds.client.BondsV2Report.lines(ctx, Seq(stableToken.toString))
      report.foreach(l => println(s"[report] $l"))
      report.count(_.startsWith("  constant ")) shouldBe 26
      val liq = scorex.util.encode.Base16.encode(matured(ctx).hashedValueBytes)
      report should contain(s"    hash:  $liq")
    }
  }

  property("the token bond pins its repayment token id") {
    withCtx { ctx =>
      bondContract(ctx, TokenBond).ergoTreeHex should include(stableToken.toString)
    }
  }
}
