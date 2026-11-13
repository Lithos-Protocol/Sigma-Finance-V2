package org.lithosprotocol.sigmafi
package specs.app

import ksingh.sigmabonds.app.Args
import ksingh.sigmabonds.livetest._
import ksingh.sigmabonds.standard.ExchangeCommands
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec

/**
 * The live test's plan, offline: it cannot be run here (it needs a node, a wallet and time), so these check
 * that it is well formed. Every command parses, every variable a step reads is found by a step it builds on,
 * nothing a transaction reads can be rebound by a step that may run before it, and every ZC Standard command
 * is exercised.
 */
class LivePlanSpec extends AnyPropSpec with Matchers {

  private val plan = LivePlan.steps
  private val byId = plan.map(s => s.id -> s).toMap

  private def deps(s: Step): Seq[String] = if (s.last) plan.filterNot(_.last).map(_.id) else (s.after ++ s.settled).distinct

  /** Every step `s` builds on, directly or not. */
  private def closure(s: Step): Set[String] = {
    var seen = Set.empty[String]
    var todo = deps(s).toList
    while (todo.nonEmpty) {
      val d = todo.head
      todo = todo.tail
      if (!seen(d)) {
        seen += d
        todo = deps(byId(d)).toList ++ todo
      }
    }
    seen
  }

  private def binders(name: String): Seq[Step] =
    plan.filter(s => s.binds.exists(_.name == name) || (s.kind == Kind.Setup && LivePlan.SetupVars.contains(name)))

  /** A stand-in value for each variable: box and token ids are 64 hex characters, heights numbers. */
  private val sample: Map[String, String] =
    plan.flatMap(_.reads).distinct.map(n => n -> (if (n == "T0" || n == "M") "1000000" else "ab" * 32)).toMap

  private val standardCommands = ExchangeCommands.Commands ++ Set("info", "orders", "bonds", "claims", "create-order",
    "cancel-order", "refund-order", "repay", "recollateralize", "fill-order", "claim", "liquidate")

  property("step ids are unique, and every step builds only on steps that exist") {
    plan.map(_.id).distinct.size shouldBe plan.size
    plan.foreach(s => deps(s).foreach(d => withClue(s"${s.id} builds on $d: ")(byId.contains(d) shouldBe true)))
  }

  property("the steps have no cycle, so every one can run") {
    plan.foreach(s => withClue(s"${s.id}: ")(closure(s).contains(s.id) shouldBe false))
  }

  property("every command parses as a known CLI command, and none submits by itself") {
    plan.filter(_.command.nonEmpty).foreach { s =>
      withClue(s"${s.id}: ") {
        val line = LivePlan.fill(s.command, sample)
        line.isRight shouldBe true
        val a = Args.parse(Args.tokenize(line.right.get))
        standardCommands should contain(a.command.get)
        a.flag("send") shouldBe false
      }
    }
  }

  property("every variable a step reads is found by the setup or a step it builds on") {
    plan.foreach { s =>
      // A step's own bindings count only for its check, which runs after it.
      val found = closure(s).flatMap(d => byId(d).binds.map(_.name) ++
        (if (byId(d).kind == Kind.Setup) LivePlan.SetupVars else Nil)) ++ s.binds.map(_.name)
      val reads = LivePlan.placeholders(s.command) ++ s.expect.flatMap(LivePlan.placeholders) ++ s.reads
      reads.foreach(n => withClue(s"${s.id} reads {$n}: ")(found should contain(n)))
    }
  }

  property("a transaction never reads a variable that a step which may run before it can rebind") {
    plan.filter(s => s.kind == Kind.Tx).foreach { s =>
      val before = closure(s)
      LivePlan.placeholders(s.command).foreach { n =>
        // Binders it does not build on must build on it, so they come after it.
        binders(n).filterNot(b => b.id == s.id || before.contains(b.id)).foreach { b =>
          withClue(s"${s.id} reads {$n}, which ${b.id} also binds: ")(closure(b).contains(s.id) shouldBe true)
        }
        // Among the binders it builds on, the latest is well defined: each pair is ordered.
        val inside = binders(n).filter(b => before.contains(b.id))
        for (a <- inside; b <- inside if a.id != b.id)
          withClue(s"${s.id} reads {$n}, bound by both ${a.id} and ${b.id}: ")(
            (closure(a).contains(b.id) || closure(b).contains(a.id)) shouldBe true)
      }
    }
  }

  property("only transactions bind variables, refusals say what they expect, and a skip-if names a binding") {
    plan.filter(_.binds.nonEmpty).foreach(s => withClue(s"${s.id}: ")(s.kind shouldBe Kind.Tx))
    plan.filter(_.kind == Kind.Refusal).foreach(s => withClue(s"${s.id}: ")(s.expect should not be empty))
    plan.flatMap(s => s.skipIf.map(s -> _)).foreach { case (s, n) =>
      withClue(s"${s.id} skips on {$n}: ")(s.binds.map(_.name) should contain(n))
    }
  }

  property("the plan runs every ZC Standard command, and the first generation's listings") {
    val run = plan.filter(_.command.nonEmpty).map(s => Args.parse(Args.tokenize(LivePlan.fill(s.command, sample).right.get)))
    val standard = run.filterNot(_.flag("old")).flatMap(_.command).toSet
    (standardCommands -- standard) shouldBe empty
    run.filter(_.flag("old")).flatMap(_.command).toSet shouldBe Set("info", "orders", "bonds", "claims")
    run.exists(a => a.command.contains("batch") && a.flag("run")) shouldBe true
  }

  property("every step explains itself in a sentence or two, and every explanation belongs to a step") {
    plan.foreach(s => withClue(s"${s.id}: ") {
      s.about should not be empty
      s.about.length should be <= 320
      s.about.forall(c => c >= ' ' && c <= '~') shouldBe true
    })
    (LivePlan.About.keySet -- plan.map(_.id)) shouldBe empty
    LiveTest.wrap("one two three four", 9) shouldBe Seq("one two", "three", "four")
    LiveTest.wrap("", 9) shouldBe empty
  }

  property("a variable is filled in, and a missing one is named") {
    LivePlan.fill("claim {orderA} --amount {n}", Map("orderA" -> "x", "n" -> "5")) shouldBe Right("claim x --amount 5")
    LivePlan.fill("claim {orderA}", Map.empty) shouldBe Left("orderA")
  }

  property("the progress file keeps everything across a save and a load") {
    val s = LiveState(started = true, chain = false, maturityBlocks = 33, address = Some("9addr"),
      vars = Map("orderA" -> "aa", "M" -> "123"),
      steps = Map("A1" -> StepState(StepState.InFlight, Vector("tx1"), Vector(Vector("o0", "o1")), Some("create-order"),
        Vector("note"), Vector("S1"), attempts = 2, misses = 1, firstMiss = 99L, ranAt = Some(7), notBefore = Some(8))))
      .bindAs("A1", Seq("orderA" -> "bb", "reqA" -> "cc")).unbind("A1").bindAs("A2", Seq("orderA" -> "dd"))
    LiveState.decode(LiveState.encode(s)) shouldBe s
  }

  property("a dropped step's bindings are undone, to the latest binding still alive, in whatever order steps drop") {
    val start = LiveState.fresh(chain = true, maturityBlocks = 40).bind("offer1" -> "o1")
    // E2 and E6 rebind offer1 to their continuations; E7 binds reqE for the first time.
    val bound = start.bindAs("E2", Seq("offer1" -> "e2")).bindAs("E6", Seq("offer1" -> "e6")).bindAs("E7", Seq("reqE" -> "r"))
    bound.unbind("E6").vars("offer1") shouldBe "e2"
    // The parent drops first while the child still stands; then the child: back to the value before both.
    val parentFirst = bound.unbind("E2")
    parentFirst.vars("offer1") shouldBe "e6"
    parentFirst.unbind("E6").vars("offer1") shouldBe "o1"
    bound.unbind("E6").unbind("E2").vars("offer1") shouldBe "o1"
    // A name a dropped step bound first goes away; a step that bound nothing changes nothing.
    bound.unbind("E7").vars.contains("reqE") shouldBe false
    bound.unbind("A1") shouldBe bound
    // A re-run after a drop binds afresh, and undoing it again restores the same earlier value.
    val rerun = bound.unbind("E6").bindAs("E6", Seq("offer1" -> "e6b"))
    rerun.vars("offer1") shouldBe "e6b"
    rerun.unbind("E6").vars("offer1") shouldBe "e2"
  }
}
