package org.lithosprotocol.sigmafi
package specs.app

import ksingh.sigmabonds.app._
import ksingh.sigmabonds.app.Protocol._
import ksingh.sigmabonds.livetest._
import ksingh.sigmabonds.standard._
import org.ergoplatform.appkit.ErgoProver
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.Token

/** Execute the actual live-plan CLI commands over the offline chain, including their confirmation checks. */
class LivePricingSpec extends AnyPropSpec with StandardSpecSupport {
  private def step(id: String) = LivePlan.steps.find(_.id == id).get

  private class Run(val w: World, p: ErgoProver) {
    var state = LiveState.fresh(chain = true, maturityBlocks = 40).bind("token" -> collateralToken.toString,
      "T0" -> w.h.toString, "M" -> (w.h + 40).toString)
    val node = new FakeNode(w.chain, p)
    def exec(id: String): Unit = {
      var captured: Option[Prepared] = None
      val app = new StandardApp(w.ctx, node, AppSettings(), _ => (), protocol) {
        override def submit(prepared: Prepared): Option[String] = { captured = Some(prepared); None }
      }
      val line = LivePlan.fill(step(id).command, state.vars).right.get
      StandardCommands.dispatch(app, node, Args.parse(Args.tokenize(line)), hasApiKey = true)
      val signed = w.run(p, captured.get)
      state = state.bindAs(id, step(id).binds.map(b => b.name -> signed.getOutputsToSpend.get(b.output).getId.toString))
      withClue(s"$id: ")(LiveChecks.verify(step(id), state, node, protocol) shouldBe None)
    }
  }

  property("the A and B live commands issue NAV-priced bonds with margins; A sells yield-priced shares before repaying") {
    withCtx { ctx =>
      val w = new World(ctx)
      val p = borrower(ctx)
      w.fund(p, erg("10"), Token(collateralToken, 10000L))
      val r = new Run(w, p)
      Seq("A1", "A2", "A3", "A5", "A6", "A8", "A9", "A10", "A11", "A12", "A13", "B1", "B2").foreach(r.exec)
      val original = r.state.checkVars("B2")("bondB")
      val bond = protocol.asBond(NodeJson.box(r.node.boxById(original).get)).get
      bond.principal should be > erg("0.1")
      bond.repayment shouldBe erg("0.11")
      bond.maturity - bond.start shouldBe 90L
      // The fill check must keep reading B2's historical output after B3 rebinds the live bond.
      r.state = r.state.bindAs("B3", Seq("bondB" -> "ff" * 32))
      LiveChecks.verify(step("B2"), r.state, r.node, protocol) shouldBe None
      val wrongPayout = w.fund(p, erg("1"))
      val wrong = r.state.bindAs("B2", Seq("principalB" -> wrongPayout.id.toString))
      LiveChecks.verify(step("B2"), wrong, r.node, protocol).get should include("actual borrower payment")
    }
  }

  property("the E live commands exercise a priced-order offer fill, NAV resale and guarded yield-priced Dutch shares") {
    withCtx { ctx =>
      val w = new World(ctx)
      val p = borrower(ctx)
      w.fund(p, erg("10"), Token(collateralToken, 10000L))
      w.oracle(500000000L, w.h)
      val r = new Run(w, p)
      Seq("O1", "E1", "E2", "E4", "E6", "E7", "E8", "E9", "E10", "E11").foreach(r.exec)
      LiveChecks.verify(step("E2"), r.state, r.node, protocol) shouldBe None
      LiveChecks.verify(step("E4"), r.state, r.node, protocol) shouldBe None
      LiveChecks.verify(step("E9"), r.state, r.node, protocol) shouldBe None
      LiveChecks.verify(step("E9").copy(check = Check.AskPricing("askE", true, 0L)), r.state, r.node, protocol).get should
        include("price margin")
    }
  }

  property("G's on-close yield order builds and signs the match that the live batch runner will execute") {
    withCtx { ctx =>
      val w = new World(ctx)
      val p = borrower(ctx)
      w.fund(p, erg("10"), Token(collateralToken, 10000L))
      w.oracle(500000000L, w.h)
      val r = new Run(w, p)
      Seq("G1", "O3").foreach(r.exec)
      val order = r.state.vars("orderG")
      val offer = r.state.vars("offer3")
      w.app(p).batchScan().work.exists(_.command == s"match-offer $offer --order $order") shouldBe true
      val signed = w.run(p, w.app(p).matchOrder(offer, order))
      r.state = r.state.bindAs("G3", Seq("bondG" -> signed.getOutputsToSpend.get(1).getId.toString,
        "principalG" -> signed.getOutputsToSpend.get(2).getId.toString))
      LiveChecks.verify(step("G3"), r.state, r.node, protocol) shouldBe None
    }
  }
}
