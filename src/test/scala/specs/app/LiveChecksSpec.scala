package org.lithosprotocol.sigmafi
package specs.app

import ksingh.sigmabonds.app.Protocol._
import ksingh.sigmabonds.livetest._
import org.ergoplatform.appkit.ErgoValue
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Token, UTXO}

/** Confirmation checks must survive chained successors rebinding and spending the checked box. */
class LiveChecksSpec extends AnyPropSpec with StandardSpecSupport {
  private def step(id: String): Step = LivePlan.steps.find(_.id == id).get

  property("E12 and E14 verify their own outputs after the auction is bought, partly claimed and closed") {
    withCtx { ctx =>
      val w = new World(ctx)
      val e = executor(ctx)
      val share = ErgoId.create("bb" * 32)
      val bond = ErgoId.create("cc" * 32)
      def vault(state: Byte, shares: Long) = w.seed(UTXO(protocol.contracts.dutchVault, MinBox,
        Seq(Token(share, 100000001L)), Seq(bytesValue(bond.getBytes), longsValue(100000000L, shares, 5000000L),
          ErgoValue.of(state), longsValue(120000000L, 30000L, 25000L, 20L, w.h.toLong), bytesValue(Array.empty[Byte]))))
      val opened = vault(1, 100000000L)
      val sold = vault(2, 100000000L)
      val partial = vault(2, 70000000L)
      // These historical boxes are all already spent by the time confirmation is checked.
      Seq(opened, sold, partial).zipWithIndex.foreach { case (b, i) =>
        w.chain.spent(b.id.toString) = (w.chain.unspent.remove(b.id.toString).get, s"successor$i")
      }
      val state = LiveState.fresh(chain = true, maturityBlocks = 40)
        .bindAs("E12", Seq("vaultE" -> opened.id.toString))
        .bindAs("E14", Seq("vaultE" -> sold.id.toString))
        .bindAs("E15", Seq("vaultE" -> partial.id.toString))
      val node = new FakeNode(w.chain, e)
      LiveChecks.verify(step("E12"), state, node, protocol) shouldBe None
      LiveChecks.verify(step("E14"), state, node, protocol) shouldBe None
      // An incorrect state must still fail, rather than treating subsequent progress as a pass.
      LiveChecks.verify(step("E12").copy(check = Check.VaultState("vaultE", 0)), state, node, protocol).get should
        include("state is 1, not 0")
    }
  }
}
