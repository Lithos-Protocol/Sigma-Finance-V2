package org.lithosprotocol.sigmafi
package specs.bonds

import ksingh.sigmabonds.standard.{Market, StandardPins}
import org.scalatest.propspec.AnyPropSpec
import sigma.Coll
import sigma.ast.SCollection.SByteArray
import specs.harness.ContractSpecBase
import work.lithos.mutations.Contract

/** The compile gate for the ZC Standard set: tree version, the pins the client loads, and cross-hashes. */
class ZcStandardProbeSpec extends AnyPropSpec with ContractSpecBase {

  property("every ZC Standard script compiles at ErgoTree v3 with the size bit set") {
    withCtx { ctx =>
      ZcStandardSpecBase.compiled(ctx).entries.foreach { e =>
        withClue(s"${e.label}: ") {
          e.contract.ergoTree.version shouldBe 3
          (e.contract.ergoTree.header & 0x08).toByte should not be 0.toByte
        }
      }
    }
  }

  property("the pinned trees and the reference document match the compiler") {
    withCtx(ZcStandardReference.check)
  }

  property("the pins the client loads are the compiled trees, byte for byte, with the same expression hashes") {
    withCtx { ctx =>
      val compiled = ZcStandardSpecBase.compiled(ctx).entries
      val loaded = StandardPins.load.entries
      loaded.map(_.label) shouldBe compiled.map(_.label)
      loaded.zip(compiled).foreach { case (l, c) =>
        withClue(s"${c.label}: ") {
          l.contract.propBytes.toSeq shouldBe c.contract.propBytes.toSeq
          if (c.expression) l.contract.hashedValueBytes.toSeq shouldBe c.contract.hashedValueBytes.toSeq
        }
      }
    }
  }

  property("every cross-contract hash appears in the tree that pins it") {
    withCtx { ctx =>
      val s = ZcStandardSpecBase.compiled(ctx)
      def pins(holder: Contract, hash: Array[Byte]): Boolean = holder.ergoTree.constants.exists { c =>
        c.tpe == SByteArray && c.value.asInstanceOf[Coll[Byte]].toArray.sameElements(hash)
      }
      pins(s.fractionalMint, s.fractionalVault.hashedPropBytes) shouldBe true
      Market.all.foreach { m =>
        withClue(s"$m: ") {
          val c = s.market(m)
          pins(c.bond, s.vault.hashedPropBytes) shouldBe true
          pins(c.order, c.bond.hashedPropBytes) shouldBe true
          Seq(c.order, c.bond, s.fractionalMint, c.dutchMint).foreach(t => pins(c.offer, t.hashedPropBytes) shouldBe true)
          pins(c.dutchMint, s.dutchVault.hashedPropBytes) shouldBe true
          pins(c.dutchMint, c.bond.hashedPropBytes) shouldBe true
          pins(c.policy, c.oracleRule.hashedValueBytes) shouldBe true
          pins(c.pricing, c.bond.hashedPropBytes) shouldBe true
          // The repayment token is compiled into every per-market script of a token market.
          m.token.foreach { id =>
            Seq(c.bond, c.order, c.offer, c.saleOrder, c.navAsk, c.dutchMint, c.oracleRule, c.policy, c.pricing)
              .foreach(t => pins(t, id.getBytes) shouldBe true)
          }
        }
      }
    }
  }
}
