package org.lithosprotocol.sigmafi
package specs.bonds

import ksingh.sigmabonds.app.Protocol
import ksingh.sigmabonds.client.ScriptGenerator
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import sigma.Colls
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

/**
 * FI_Standard_Order. Default world (FiStandardSpecBase): fill and start at s, coupon k due at
 * s + 200 + 200k (k = 0, 1, 2), grace 20, ratio 150%, cure 100, oracle age 30, maturity s + 2000.
 * Each refusal is a minimal pair: the same order with that one term at its boundary fills, one step
 * past it does not. Every accepted transaction also passes the node's output rules (`ok`).
 */
class FiStandardOrderSpec extends AnyPropSpec with FiStandardMarketSpecBase {
  import BondSpecBase._
  import ZcStandardSpecBase._

  private val kinds = Seq[Kind](TokenBond, ErgBond)

  private def fills(w: W): Unit = ok(lender(w.ctx), fiFillTx(fillOf(w)))
  private def refuses(w: W): Unit = no(lender(w.ctx), fiFillTx(fillOf(w)))

  /** The FI order for `w`'s market compiled from its file with `edits` applied: a mutant, to show which clause refuses. */
  private val variants = scala.collection.mutable.Map.empty[(Boolean, Seq[(String, String)]), Contract]
  private def orderVariant(w: W, edits: (String, String)*): Contract = variants.getOrElseUpdate((w.erg, edits),
    FiStandardMarketSpecBase.orderFor(w.ctx, w.bond, repaymentBytes(w), edits: _*))
  /** Fills under the order mutant: the refusal of the real order is that clause's. */
  private def fillsWithout(w: W, edits: (String, String)*): Unit =
    ok(lender(w.ctx), fiFillTx(fillOf(w, orderScript = orderVariant(w, edits: _*))))

  private val noFirstDueRule = "start + firstOffset >= HEIGHT.toLong && grace <= period &&" -> "grace <= period &&"
  private val noGraceRule = "start + firstOffset >= HEIGHT.toLong && grace <= period &&" -> "start + firstOffset >= HEIGHT.toLong &&"
  private val noCovenantCount = "(ratio == 0L || (count >= 1L && start" -> "(ratio == 0L || (start"
  private val noCheckpointRule = "count >= 1L && start + firstOffset + grace + 720L < maturity" -> "count >= 1L"
  /** The covenant cutoff as it stood before it counted the grace: due + 720 < maturity. */
  private val noCheckpointGrace = "start + firstOffset + grace + 720L < maturity" -> "start + firstOffset + 720L < maturity"
  /** Mode 0's floor as it stood: maturity > HEIGHT + 1. */
  private val noFixedFloor = "maturity == borrower(3) && maturity >= HEIGHT.toLong + 30L" ->
    "maturity == borrower(3) && maturity > HEIGHT.toLong + 1L"
  private val noLifeCap = "maturity <= start + 788400L && (" -> "("
  private val noGraceFloor = "borrower(11) >= 30L &&\n" -> "\n"
  private val noGuardRule = "sigmaProp(core && borrower(12) == 0L)" -> "sigmaProp(core)"
  private val noLiquidationPin = "SELF.R7[Coll[Byte]].get == CONST_LIQUIDATION_HASH &&" -> "CONST_LIQUIDATION_HASH.size >= 0 &&"
  private val noUnitStamp = "lenderOut.creationInfo._1 > HEIGHT - 1000 &&" -> "true &&"
  private val noPrincipalStamp = "principal.creationInfo._1 > HEIGHT - 1000 &&" -> "true &&"
  private val noAuthAge = "&&\n            b.creationInfo._1 > HEIGHT - 1051200" -> ""

  // ═══ COMPILATION AND SIZE ═════════════════════════════════════════════════

  property("compile: ErgoTree v3; ERG and token orders share a template and each pins its own FI bond") {
    withCtx { ctx =>
      val m = mk(ctx)
      Seq(m.erg.order, m.token.order).foreach(_.ergoTree.version shouldBe 3)
      m.erg.order.ergoTree.bytes should not be m.token.order.ergoTree.bytes
      m.erg.order.ergoTree.template shouldBe m.token.order.ergoTree.template
      m.erg.order.ergoTreeHex should include(fi(ctx).ergBond.hashedPropBytesHex)
      m.token.order.ergoTreeHex should include(fi(ctx).tokenBond.hashedPropBytesHex)
      println(s"FIMKT|tree|FI Order ERG=${m.erg.order.propBytes.length}|FI Order SigUSD=${m.token.order.propBytes.length}|" +
        s"ZC Order ERG=${scripts(ctx).ergOrder.propBytes.length}|ZC Order SigUSD=${scripts(ctx).tokenOrder.propBytes.length}")
    }
  }

  property("size: order, bond and fill transaction, realistic and with R4 at 64 Longs") {
    withCtx { ctx =>
      for (kind <- kinds) {
        val w = world(ctx, kind)
        val f = fillOf(w)
        val signed = ok(lender(ctx), fiFillTx(f))
        val wide = world(ctx, kind, payload = Seq.fill(49)(Long.MaxValue))
        val wf = fillOf(wide)
        val wideSigned = ok(lender(ctx), fiFillTx(wf))
        println(s"FIMKT|box|$kind|order=${f.orderIn.bytes.length}|bond=${outBytes(signed, 1)}|fill tx=${signed.toBytes.length}|" +
          s"64-Long order=${wf.orderIn.bytes.length}|64-Long bond=${outBytes(wideSigned, 1)}|64-Long fill tx=${wideSigned.toBytes.length}|" +
          s"fill cost=${signed.getCost}")
      }
    }
  }

  // ═══ FILL ═════════════════════════════════════════════════════════════════

  for (kind <- kinds; close <- Seq(false, true)) {
    val label = s"$kind ${if (close) "on-close" else "fixed"}"

    property(s"$label: a fill mints 3 units and creates the FI bond: R6 = R4, R9 = (fee, S, M, 0, -1, 0, S)") {
      withCtx { ctx =>
        val base = world(ctx, kind)
        val w = if (close) onClose(base, 2000L) else base
        val f = fillOf(w)
        val signed = ok(lender(ctx), fiFillTx(f))
        val bond = spendOutput(signed, 1)
        bond.contract shouldBe w.bond
        bond.tokens shouldBe (Token(f.asset, 2L) +: w.collateral)
        bond.value shouldBe w.collateralErg + batchingFee
        bond.registers(0) shouldBe bytesValue(f.asset.getBytes)
        r6(bond) shouldBe w.terms
        r9(bond) shouldBe Seq(batchingFee, w.start.toLong, w.maturity, 0L, -1L, 0L, w.start.toLong)
        bond.input.getCreationHeight shouldBe w.start
        spendOutput(signed, 0).tokens shouldBe Seq(Token(f.asset, 1L))
        spendOutput(signed, 2).value shouldBe w.terms(1) + w.terms(5)
        spendOutput(signed, 2).tokens shouldBe loanTokens(w)
      }
    }

    property(s"$label: the bond the order creates is a working FI bond: Pay, a missed coupon, Repay") {
      withCtx { ctx =>
        val base = world(ctx, kind)
        val w = if (close) onClose(base, 2000L) else base
        val f = fillOf(w)
        val signed = ok(lender(ctx), fiFillTx(f))
        val b = spendOutput(signed, 1)
        val h = w.due(0) - 10
        val payTxn = ok(borrower(ctx), payTx(w, b, h, coupon = couponOut(w, asset = f.asset)))
        val next = spendOutput(payTxn, 0)
        r9(next)(3) shouldBe 1L
        // The lender holding the circulating unit collects the coupon.
        ok(lender(ctx), collectTx(w, Seq(spendOutput(payTxn, 1)), Seq(spendOutput(signed, 0), wallet(ctx, lender(ctx), 9, h)), h + 1))
        // Coupon 1 unpaid at its due height plus grace: liquidatable, by the bond's own rule.
        no(executor(ctx), fiLiquidateTx(w, next, w.due(1) + grace - 1))
        ok(executor(ctx), fiLiquidateTx(w, next, w.due(1) + grace))
        ok(borrower(ctx), fiRepayTx(w, next, w.due(1)))
      }
    }
  }

  property("fill: the mint is exactly 2 in the bond and 1 circulating; the reserve is funded above the collateral") {
    withCtx { ctx =>
      val w = world(ctx)
      val f = fillOf(w)
      def split(unit: Long, reserve: Long): Tx = fiFillTx(f, unit = f.unit.setTokens(Token(f.asset, unit)),
        bond = f.bond.setTokens(Token(f.asset, reserve) +: w.collateral: _*))
      ok(lender(ctx), split(1L, 2L))
      Seq((2L, 1L), (1L, 3L), (2L, 2L), (1L, 1L)).foreach { case (u, r) => no(lender(ctx), split(u, r)) }
      no(lender(ctx), fiFillTx(f, bond = bondR9(f, 0, batchingFee + 1L)))
      no(lender(ctx), fiFillTx(f, bond = f.bond.setTokens(Token(f.asset, 2L))))
      no(lender(ctx), fiFillTx(f, unit = f.unit.withRegNum(6, utf8("9"))))
      no(lender(ctx), fiFillTx(f, principal = f.principal.subValue(1L)))
      no(lender(ctx), fiFillTx(f, principal = f.principal.setContract(otherTree(ctx))))
      no(lender(ctx), fiFillTx(f, principal = f.principal.withRegNum(4, bytesValue(originOrderId))))
    }
  }

  // ═══ EVERY TERM REACHES THE BOND UNCHANGED ════════════════════════════════

  property("tamper: the filler cannot change any R6 value, R6's length, R4, R5, R7, R8 or the bond script") {
    withCtx { ctx =>
      for (kind <- kinds) {
        val w = world(ctx, kind, payload = Seq(Long.MaxValue, Long.MinValue, 7L))
        val f = fillOf(w)
        ok(lender(ctx), fiFillTx(f))
        w.terms.indices.foreach { i =>
          val v = w.terms(i)
          val changed = w.terms.updated(i, if (v == Long.MaxValue) v - 1L else v + 1L)
          withClue(s"R6($i)")(no(lender(ctx), fiFillTx(f, bond = f.bond.withRegNum(6, longsValue(changed: _*)))))
        }
        no(lender(ctx), fiFillTx(f, bond = f.bond.withRegNum(6, longsValue(w.terms.dropRight(1): _*))))
        no(lender(ctx), fiFillTx(f, bond = f.bond.withRegNum(6, longsValue((w.terms :+ 1L): _*))))
        no(lender(ctx), fiFillTx(f, bond = f.bond.withRegNum(4, bytesValue(originOrderId))))
        no(lender(ctx), fiFillTx(f, bond = f.bond.withRegNum(5, bytesValue(hashOf(otherTree(ctx))))))
        no(lender(ctx), fiFillTx(f, bond = f.bond.withRegNum(7, Protocol.tokensValue(Seq(Token(SigUSD, 1000000L))))))
        // The liquidation expression: another one, or the maturity-only variant of FI's own.
        no(lender(ctx), fiFillTx(f, bond = f.bond.withRegNum(8, bytesValue(alwaysTrue(ctx).hashedValueBytes))))
        no(lender(ctx), fiFillTx(f, bond = f.bond.withRegNum(8, bytesValue(fi(ctx).maturityOnly.hashedValueBytes))))
        // The bond script: the other market's FI bond, the ZC bond, an FI bond with another covenant, a key.
        val otherFi = if (w.erg) fi(ctx).tokenBond else fi(ctx).ergBond
        val swaps = Seq("other FI market" -> otherFi, "ZC bond" -> bondOf(ctx, kind), "key" -> otherTree(ctx)) ++
          (if (w.erg) Seq("FI bond, other covenant" -> mk(ctx).swappedBond) else Nil)
        swaps.foreach { case (name, c) => withClue(name)(no(lender(ctx), fiFillTx(f, bond = f.bond.setContract(c)))) }
        // Collateral tokens and value as the order holds them.
        no(lender(ctx), fiFillTx(f, bond = f.bond.subValue(1L)))
      }
    }
  }

  property("tamper: R9 starts exactly at (fee, S, M, 0, -1, 0, S) and has 7 values") {
    withCtx { ctx =>
      for (kind <- kinds) {
        val w = world(ctx, kind)
        val f = fillOf(w)
        val s = w.start.toLong
        ok(lender(ctx), fiFillTx(f))
        Seq(1 -> (s - 1L), 1 -> (s + 1L), 2 -> (w.maturity - 1L), 2 -> (w.maturity + 1L),
          3 -> 1L, 3 -> -1L, 4 -> 0L, 4 -> -2L, 5 -> 1L, 5 -> -1L, 5 -> (s + 100L),
          6 -> (s - 1L), 6 -> (s + 1L), 6 -> 0L).foreach { case (i, v) =>
          withClue(s"R9($i) = $v")(no(lender(ctx), fiFillTx(f, bond = bondR9(f, i, v))))
        }
        no(lender(ctx), fiFillTx(f, bond = f.bond.withRegNum(9, longsValue(bornState(w).dropRight(1): _*))))
        no(lender(ctx), fiFillTx(f, bond = f.bond.withRegNum(9, longsValue((bornState(w) :+ 0L): _*))))
        no(lender(ctx), fiFillTx(f, bond = f.bond.withRegNum(9, longsValue(bornState(w).take(3): _*))))
      }
    }
  }

  property("start: within 8 blocks of the fill, the bond's creation height, and its recollateralize clock") {
    withCtx { ctx =>
      val h = fillHeight(world(ctx))
      Seq(h, h - 8).foreach(s => fills(startedAt(world(ctx), s)))
      Seq(h - 9, h + 1).foreach(s => refuses(startedAt(world(ctx), s)))
      // The bond's creation height is the start: Refresh reads the box's age from it.
      val f = fillOf(world(ctx))
      Seq(h - 1, h + 1, 0).foreach(stamp => no(lender(ctx), fiFillTx(f, bond = f.bond.setCreationHeight(stamp))))
      // A start in the past moves the clock with it; the clock cannot stay at the fill height.
      val back = fillOf(startedAt(world(ctx), h - 8), height = h)
      ok(lender(ctx), fiFillTx(back))
      no(lender(ctx), fiFillTx(back, bond = bondR9(back, 6, h.toLong)))
      // A bond started 8 blocks back counts its schedule from that start: coupon 0 is missed 8 blocks sooner.
      val signed = ok(lender(ctx), fiFillTx(back))
      val bw = startedAt(world(ctx), h - 8)
      no(executor(ctx), fiLiquidateTx(bw, spendOutput(signed, 1), bw.due(0) + grace - 1))
      ok(executor(ctx), fiLiquidateTx(bw, spendOutput(signed, 1), bw.due(0) + grace))
      bw.due(0) + grace shouldBe h + firstOffset + grace - 8
    }
  }

  property("maturity: fixed is the borrower's height, at least HEIGHT + 30; on-close is S + a duration of at least 30") {
    withCtx { ctx =>
      val h = fillHeight(world(ctx))
      // No coupons and no covenant, so only the maturity rule binds.
      val plain = withTerms(world(ctx), 8 -> 0L, 12 -> 0L)
      fills(maturingAt(plain, h + 30L))
      refuses(maturingAt(plain, h + 29L))
      refuses(maturingAt(plain, h + 2L))
      // The floor as it stood (maturity > HEIGHT + 1) fills h + 29: the 30-block floor, and nothing else, refuses it.
      fillsWithout(maturingAt(plain, h + 29L), noFixedFloor)
      fills(onClose(plain, 30L))
      refuses(onClose(plain, 29L))
      refuses(onClose(plain, Long.MaxValue))
      // On-close with the start 8 back: maturity moves with it, and may not stay put.
      val early = startedAt(onClose(plain, 30L), h - 8)
      fills(early)
      val f = fillOf(early)
      no(lender(ctx), fiFillTx(f, bond = bondR9(f, 2, h + 30L)))
    }
  }

  // ═══ SCHEDULE: NOT BORN IN DEFAULT ════════════════════════════════════════

  property("schedule: the first coupon is missed no earlier than HEIGHT + 30, wherever the start lies") {
    withCtx { ctx =>
      // No covenant: a covenant order needs a grace of at least 30, so only a plain coupon order has one this short.
      val w = withTerm(world(ctx), 12, 0L)
      val h = fillHeight(w)
      // Started at the fill: first offset + grace of 30 fills, 29 does not.
      fills(withTerms(w, 9 -> 0L, 11 -> 30L))
      refuses(withTerms(w, 9 -> 0L, 11 -> 29L))
      fills(withTerms(w, 9 -> 30L, 11 -> 0L))
      refuses(withTerms(w, 9 -> 29L, 11 -> 0L))
      // The floor as it stood (missed after HEIGHT + 1) filled 2: the 30-block floor, and nothing else, refuses it.
      refuses(withTerms(w, 9 -> 0L, 11 -> 2L))
      fillsWithout(withTerms(w, 9 -> 0L, 11 -> 2L), "firstMissed >= HEIGHT.toLong + 30L" -> "firstMissed > HEIGHT.toLong + 1L")
      // Started 8 blocks back, the same coupon needs 38: the test is at the fill height, not the start. The
      // first due height may not precede the fill, so the offset is at least 8 and the grace then 30.
      val back = startedAt(w, h - 8)
      fills(withTerms(back, 9 -> 8L, 11 -> 30L))
      refuses(withTerms(back, 9 -> 8L, 11 -> 29L))
      refuses(withTerms(back, 9 -> 8L, 11 -> 2L))
      fillsWithout(withTerms(back, 9 -> 8L, 11 -> 2L), "firstMissed >= HEIGHT.toLong + 30L" -> "firstMissed > HEIGHT.toLong + 1L")
      // What the refusal prevents: the bond that fill would make is liquidatable in its first block.
      val born = withTerms(back, 9 -> 0L, 11 -> 5L)
      refuses(born)
      ok(executor(ctx), fiLiquidateTx(born, fiIn(born, fiBox(born)), h))
    }
  }

  property("schedule: the last coupon is missed before maturity, so every coupon listed is one the bond enforces") {
    withCtx { ctx =>
      val w = world(ctx)
      // Period 700 (the grace may not exceed it): coupon 2 due at s + 1600, missed at s + 1600 + grace.
      // Grace 399 misses it at M - 1; 400 at M.
      val wide = withTerm(w, 10, 700L)
      fills(withTerm(wide, 11, 399L))
      refuses(withTerm(wide, 11, 400L))
      // The same rule by count: coupon 8 due at s + 1800 fits, coupon 9 due at M does not.
      fills(withTerm(w, 8, 9L))
      refuses(withTerm(w, 8, 10L))
      // Counts that would overflow the schedule arithmetic are refused, not wrapped.
      Seq(Long.MaxValue, Long.MaxValue / 200L).foreach(c => refuses(withTerm(w, 8, c)))
      // A coupon due at maturity is what the repayment already is: the bond cannot enforce it. No covenant,
      // so this rule alone binds (a covenant needs its first checkpoint before M - 720).
      val atMaturity = withTerms(w, 8 -> 1L, 9 -> 2000L, 11 -> 0L, 12 -> 0L)
      refuses(atMaturity)
      fills(withTerms(w, 8 -> 1L, 9 -> 1999L, 11 -> 0L, 12 -> 0L))
    }
  }

  property("schedule: the first due height is at or after the fill, so checkpoint 0 is recordable at its due height") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = fillHeight(w)
      // Started at the fill: a first offset of 0 puts the first due height at the fill height itself.
      fills(withTerm(w, 9, 0L))
      // Started 8 blocks back: offset 8 is due at the fill height and fills; 7 is due one block before it.
      // A grace of 31 keeps the first miss 30 blocks after the fill, so only the first-due rule refuses it.
      val back = withTerm(startedAt(w, h - 8), 11, grace + 1L)
      fills(withTerm(back, 9, 8L))
      refuses(withTerm(back, 9, 7L))
      fillsWithout(withTerm(back, 9, 7L), noFirstDueRule)
      // Without coupons there is no checkpoint, and the rule does not bind.
      fills(withTerms(back, 8 -> 0L, 9 -> 0L, 12 -> 0L))
    }
  }

  property("schedule: with coupons the grace is at most the period; one block more is refused") {
    withCtx { ctx =>
      for (kind <- kinds) {
        val w = world(ctx, kind)
        fills(withTerm(w, 11, period.toLong))
        refuses(withTerm(w, 11, period + 1L))
        fillsWithout(withTerm(w, 11, period + 1L), noGraceRule)
        // With one coupon the period is otherwise unused, and still bounds the grace.
        val one = withTerm(w, 8, 1L)
        fills(withTerm(one, 10, grace.toLong))
        refuses(withTerm(one, 10, grace - 1L))
        // Without coupons the grace is not bounded by the period.
        fills(withTerms(w, 8 -> 0L, 11 -> (period + 1L), 12 -> 0L))
      }
    }
  }

  property("schedule: a covenant needs a coupon and a first checkpoint whose window, to due + grace, ends before maturity - 720") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = fillHeight(w)
      val g = grace.toLong
      // No coupon: with ratio 0 it fills; with a ratio, refused (the covenant would never run).
      fills(withTerms(w, 8 -> 0L, 12 -> 0L))
      refuses(withTerm(w, 8, 0L))
      fillsWithout(withTerm(w, 8, 0L), noCovenantCount)
      // Fixed maturity s + 2000, started at the fill: first due at s + 1279 - grace has its last block, due +
      // grace, at M - 721 and fills; one later, that last block is M - 720 and does not.
      fills(withTerm(w, 9, 1279L - g))
      refuses(withTerm(w, 9, 1280L - g))
      fillsWithout(withTerm(w, 9, 1280L - g), noCheckpointRule)
      // The cutoff as it stood (due + 720 < M) fills it: the grace term, and nothing else, refuses it.
      fillsWithout(withTerm(w, 9, 1280L - g), noCheckpointGrace)
      fills(withTerms(w, 9 -> (1280L - g), 12 -> 0L))
      // Fixed maturity, started 8 blocks back: the first due height moves with the start, the maturity does not.
      val back = startedAt(w, h - 8)
      fills(withTerm(back, 9, 1287L - g))
      refuses(withTerm(back, 9, 1288L - g))
      // On-close, maturity S + 2000 moves with the start: the bound is on the offset alone, wherever S lies.
      val close = onClose(w, 2000L)
      fills(withTerm(close, 9, 1279L - g))
      refuses(withTerm(close, 9, 1280L - g))
      val closeBack = startedAt(close, h - 8)
      fills(withTerm(closeBack, 9, 1279L - g))
      refuses(withTerm(closeBack, 9, 1280L - g))
      // The bond agrees: at the boundary checkpoint 0 records at its due height and at its last allowed block,
      // due + grace = M - 721 (coupon 0 paid, so not missed there). One later, that last block is M - 720.
      val edge = withTerm(w, 9, 1279L - g)
      val late = withTerm(w, 9, 1280L - g)
      ok(executor(ctx), recordTx(edge, fiIn(edge, fiBox(edge)), edge.start + 1279 - grace, 0L))
      ok(executor(ctx), recordTx(edge, fiIn(edge, fiBox(edge, paid = 1L)), edge.start + 1279, 0L))
      no(executor(ctx), recordTx(late, fiIn(late, fiBox(late, paid = 1L)), late.start + 1280, 0L))
      (edge.start + 1279L) shouldBe edge.maturity - 721L
      (late.start + 1280L) shouldBe late.maturity - 720L
    }
  }

  property("terms: count >= 0, a positive coupon when there are coupons, and a coupon of 0 or more without") {
    withCtx { ctx =>
      val w = world(ctx)
      // Without coupons, no covenant (a covenant needs a checkpoint).
      val bare = withTerm(w, 12, 0L)
      fills(withTerm(bare, 8, 0L))
      refuses(withTerm(bare, 8, -1L))
      fills(withTerm(w, 7, 1L))
      refuses(withTerm(w, 7, 0L))
      fills(withTerms(bare, 8 -> 0L, 7 -> 0L))
      refuses(withTerms(bare, 8 -> 0L, 7 -> -1L))
    }
  }

  property("terms: first offset, period and grace inside the bond's clamps, so the bond reads them as written") {
    withCtx { ctx =>
      // No coupons and no covenant, so only the range checks bind, not the schedule.
      val w = withTerms(world(ctx), 8 -> 0L, 12 -> 0L)
      Seq(9 -> 0L, 9 -> 1000000000L, 10 -> 1L, 10 -> 1000000000L, 11 -> 0L, 11 -> 1000000000L)
        .foreach { case (i, v) => withClue(s"R4($i) = $v")(fills(withTerm(w, i, v))) }
      Seq(9 -> -1L, 9 -> 1000000001L, 10 -> 0L, 10 -> -1L, 10 -> 1000000001L, 11 -> -1L, 11 -> 1000000001L)
        .foreach { case (i, v) => withClue(s"R4($i) = $v")(refuses(withTerm(w, i, v))) }
      // With coupons and no covenant, a period of 1 is a schedule like any other (with a grace of at most 1:
      // here 0). A covenant order's grace is at least 30, so its period is too.
      val coupons = withTerm(world(ctx), 12, 0L)
      fills(withTerms(coupons, 10 -> 1L, 11 -> 0L))
      refuses(withTerms(coupons, 10 -> 0L, 11 -> 0L))
      refuses(withTerms(world(ctx), 10 -> 1L, 11 -> 0L))
    }
  }

  property("terms: with a covenant ratio, a cure window of 30..10^9 and an oracle age of 1..100000; with none, anything") {
    withCtx { ctx =>
      val w = world(ctx)
      Seq(13 -> 30L, 13 -> 1000000000L, 14 -> 1L, 14 -> 100000L).foreach { case (i, v) => fills(withTerm(w, i, v)) }
      Seq(13 -> 29L, 13 -> 1L, 13 -> 0L, 13 -> -1L, 13 -> 1000000001L, 14 -> 0L, 14 -> 100001L)
        .foreach { case (i, v) => withClue(s"R4($i) = $v")(refuses(withTerm(w, i, v))) }
      refuses(withTerm(w, 12, -1L))
      // Ratio 0 is no covenant: the cure window and oracle age are not read.
      fills(withTerms(w, 12 -> 0L, 13 -> 0L, 14 -> 0L))
      fills(withTerms(w, 12 -> 0L, 13 -> -5L, 14 -> Long.MaxValue))
    }
  }

  property("terms: a covenant's cure window of 29 is refused by the 30-block minimum alone; 30 fills; without a covenant 29 fills") {
    withCtx { ctx =>
      for (kind <- kinds) {
        val w = world(ctx, kind)
        withClue(s"$kind 30")(fills(withTerm(w, 13, 30L)))
        withClue(s"$kind 29")(refuses(withTerm(w, 13, 29L)))
        // The order with a minimum of 1 fills the same 29-block order: the minimum, and nothing else, refused it.
        withClue(s"$kind 29, minimum 1")(fillsWithout(withTerm(w, 13, 29L), "borrower(13) >= 30L" -> "borrower(13) >= 1L"))
        // Ratio 0: the window is not read, whatever its value.
        Seq(29L, 1L, 0L).foreach(v => withClue(s"$kind ratio 0, window $v")(fills(withTerms(w, 12 -> 0L, 13 -> v))))
      }
    }
  }

  property("terms: R4 holds 15..64 Longs") {
    withCtx { ctx =>
      val w = world(ctx)
      fills(w)
      fills(world(ctx, payload = Seq.fill(49)(17L)))
      refuses(world(ctx, payload = Seq.fill(50)(17L)))
      refuses(w.copy(terms = w.terms.take(14)))
    }
  }

  // ═══ AS ZC ════════════════════════════════════════════════════════════════

  property("as ZC: expiry stops fills and opens a complete, tagged refund by anyone") {
    withCtx { ctx =>
      val w = world(ctx)
      val expiry = w.terms(4).toInt
      val last = startedAt(w, expiry - 1)
      ok(lender(ctx), fiFillTx(fillOf(last, height = expiry - 1)))
      val atExpiry = startedAt(w, expiry)
      no(lender(ctx), fiFillTx(fillOf(atExpiry, height = expiry)))

      val order = orderBox(w)
      val refundIn = inputAt(order, ctx, 0).withCtxVar(0.toByte, ErgoValue.of(0))
      val refund = tagged(borrowerTree(ctx), w.collateralErg, refundIn.id, w.collateral)
      def expire(out: UTXO = refund, in: InputUTXO = refundIn, at: Int = expiry): Tx =
        txAt(ctx, at, Seq(in), Seq(out), executor(ctx).getAddress)
      ok(executor(ctx), expire())
      no(executor(ctx), expire(at = expiry - 1))
      no(executor(ctx), expire(out = refund.subValue(1L)))
      no(executor(ctx), expire(out = refund.setContract(otherTree(ctx))))
      no(executor(ctx), expire(out = refund.withRegNum(4, bytesValue(originOrderId))))
      no(executor(ctx), expire(out = refund.setTokens()))
      no(executor(ctx), expire(in = inputAt(order, ctx, 0)))
    }
  }

  property("as ZC: only a borrower co-spend cancels, at any height, even with malformed or unfillable terms") {
    withCtx { ctx =>
      val w = world(ctx)
      def cancel(order: UTXO, by: ErgoProver, at: Int): Tx =
        txAt(ctx, at, Seq(inputAt(order, ctx, 0), wallet(ctx, by, 1, created(w))),
          Seq(UTXO(contractOf(by), order.value, order.tokens)), by.getAddress)
      Seq(fillHeight(w), w.terms(4).toInt + 10).foreach(at => ok(borrower(ctx), cancel(orderBox(w), borrower(ctx), at)))
      no(other(ctx), cancel(orderBox(w), other(ctx), fillHeight(w)))
      ok(borrower(ctx), cancel(orderBox(w).withRegNum(4, utf8("not Longs")), borrower(ctx), fillHeight(w)))
      // An order whose schedule can never fill is still the borrower's to take back.
      ok(borrower(ctx), cancel(orderBox(withTerm(w, 10, 0L)), borrower(ctx), fillHeight(w)))
    }
  }

  property("as ZC: expiry fee minimum, funded collateral, one asset") {
    withCtx { ctx =>
      refuses(withTerm(world(ctx), 5, 99999999L))
      fills(withTerm(world(ctx), 5, 100000000L))
      // The lender's liquidation reserve has the same floor: one nanoERG under 0.1 ERG is refused.
      val f = fillOf(world(ctx))
      ok(lender(ctx), fiFillTx(f))
      no(lender(ctx), fiFillTx(f, bond = bondR9(f, 0, 99999999L)))
      refuses(world(ctx).copy(collateralErg = minBox - 1L))
      // ERG: the floor never binds, and nothing but nanoERG is lent.
      val erg = world(ctx, ErgBond)
      fills(withTerm(erg, 2, erg.terms(0)))
      refuses(withTerm(erg, 2, erg.terms(0) + 1L))
      refuses(withTerm(erg, 1, 0L))
      // Token: at most a box minimum beside the repayment token.
      val token = world(ctx, TokenBond)
      refuses(withTerm(token, 1, minBox + 1L))
      refuses(withTerm(token, 2, minBox + 1L))
    }
  }

  // ═══ FILL GUARD ═══════════════════════════════════════════════════════════

  property("guard: R8 names an expression the fill must also pass, from var 1; ZC's ERG/SigUSD guard refuses every FI fill") {
    withCtx { ctx =>
      val w = world(ctx)
      ok(lender(ctx), fiFillTx(fillOf(w, guard = alwaysTrue(ctx))))
      no(lender(ctx), fiFillTx(fillOf(w, guard = alwaysTrue(ctx)), ran = neverTrue(ctx)))
      no(lender(ctx), fiFillTx(fillOf(w, guard = neverTrue(ctx))))
      // ZC_Standard_ERG_SigUSD_FillGuard reads R9 as 3 Longs: with a fresh oracle and ample cover it still refuses.
      val zcGuard = marketOf(ctx, TokenBond).fillGuard
      no(lender(ctx), fiFillTx(fillOf(w, guard = zcGuard), data = Seq(oracle(ctx, 500000000L, fillHeight(w)))))
    }
  }

  // ═══ AUDIT FIXES ══════════════════════════════════════════════════════════

  property("F-008 life cap: maturity at most S + 788400 in both modes; a far fixed maturity fills once the start reaches it") {
    withCtx { ctx =>
      val h = fillHeight(world(ctx))
      val plain = withTerms(world(ctx), 8 -> 0L, 12 -> 0L)
      fills(onClose(plain, 788400L))
      refuses(onClose(plain, 788401L))
      fillsWithout(onClose(plain, 788401L), noLifeCap)
      fills(maturingAt(plain, h + 788400L))
      refuses(maturingAt(plain, h + 788401L))
      fillsWithout(maturingAt(plain, h + 788401L), noLifeCap)
      // The same fixed maturity, started one block later (and filled then), is within the cap.
      val later = startedAt(maturingAt(plain, h + 788401L), h + 1)
      ok(lender(ctx), fiFillTx(fillOf(later, height = h + 1)))
    }
  }

  property("F-004 grace floor: a covenant's grace of 29 is refused by the 30-block minimum alone; 30 fills; without a covenant 29 fills") {
    withCtx { ctx =>
      for (kind <- kinds) {
        val w = world(ctx, kind)
        withClue(s"$kind 30")(fills(withTerm(w, 11, 30L)))
        withClue(s"$kind 29")(refuses(withTerm(w, 11, 29L)))
        withClue(s"$kind 0")(refuses(withTerm(w, 11, 0L)))
        withClue(s"$kind 29, no floor")(fillsWithout(withTerm(w, 11, 29L), noGraceFloor))
        // Ratio 0: no covenant, no floor.
        Seq(29L, 1L, 0L).foreach(v => withClue(s"$kind ratio 0, grace $v")(fills(withTerms(w, 12 -> 0L, 11 -> v))))
      }
    }
  }

  property("F-003 guard: a covenant order with no R8 never fills, yet cancels and refunds; with an R8 it fills; with no covenant none is needed") {
    withCtx { ctx =>
      for (kind <- kinds) {
        val w = world(ctx, kind)
        no(lender(ctx), fiFillTx(fillOf(w, unguarded = true)))
        ok(lender(ctx), fiFillTx(fillOf(w, unguarded = true, orderScript = orderVariant(w, noGuardRule))))
        // The explicit opt-out (an always-true guard) and the standard guard both fill.
        ok(lender(ctx), fiFillTx(fillOf(w, guard = alwaysTrue(ctx))))
        ok(lender(ctx), fiFillTx(fillOf(w, guard = fiMarket(w).guard), data = Seq(oracle(ctx, w.calm, fillHeight(w)))))
        // Ratio 0: no R8, and it fills.
        val plain = withTerms(w, 12 -> 0L, 13 -> 0L, 14 -> 0L)
        ok(lender(ctx), fiFillTx(fillOf(plain, unguarded = true)))
        // The unfillable covenant order is still the borrower's: cancelled at any height, refunded at expiry.
        val order = orderBox(w, unguarded = true)
        order.registers.size shouldBe 4
        ok(borrower(ctx), txAt(ctx, fillHeight(w), Seq(inputAt(order, ctx, 0), wallet(ctx, borrower(ctx), 1, created(w))),
          Seq(UTXO(contractOf(borrower(ctx)), order.value, order.tokens)), borrower(ctx).getAddress))
        val refundIn = inputAt(order, ctx, 0).withCtxVar(0.toByte, ErgoValue.of(0))
        ok(executor(ctx), txAt(ctx, w.terms(4).toInt, Seq(refundIn),
          Seq(tagged(borrowerTree(ctx), w.collateralErg, refundIn.id, w.collateral)), executor(ctx).getAddress))
      }
    }
  }

  property("F-002 liquidation: an order naming any expression but FI_Standard_Liquidation never fills") {
    withCtx { ctx =>
      for (kind <- kinds) {
        val w = world(ctx, kind)
        fills(w)
        Seq("always true" -> alwaysTrue(ctx), "maturity only" -> fi(ctx).maturityOnly, "never true" -> neverTrue(ctx))
          .foreach { case (name, liq) =>
            withClue(name)(no(lender(ctx), fiFillTx(fillOf(w, liq = liq))))
            withClue(s"$name, no pin")(ok(lender(ctx), fiFillTx(fillOf(w, liq = liq, orderScript = orderVariant(w, noLiquidationPin)))))
          }
      }
    }
  }

  property("F-008 fill stamps: the unit and the principal are stamped within 999 blocks of the fill") {
    withCtx { ctx =>
      for (kind <- kinds) {
        // Filled 1010 blocks after the order and the lender's box were created, so a stamp 1000 back is
        // still at or above every input's: only the order refuses it.
        val base = world(ctx, kind)
        val h = created(base) + 1010
        val w = withTerm(startedAt(base, h), 4, h + 500L)
        val f = fillOf(w, height = h)
        ok(lender(ctx), fiFillTx(f, unit = f.unit.setCreationHeight(h - 999), principal = f.principal.setCreationHeight(h - 999)))
        no(lender(ctx), fiFillTx(f, unit = f.unit.setCreationHeight(h - 1000)))
        no(lender(ctx), fiFillTx(f, principal = f.principal.setCreationHeight(h - 1000)))
        val u = fillOf(w, height = h, orderScript = orderVariant(w, noUnitStamp))
        ok(lender(ctx), fiFillTx(u, unit = u.unit.setCreationHeight(h - 1000)))
        val p = fillOf(w, height = h, orderScript = orderVariant(w, noPrincipalStamp))
        ok(lender(ctx), fiFillTx(p, principal = p.principal.setCreationHeight(h - 1000)))
      }
    }
  }

  property("F-023 cancel: a borrower box 1051200 or more blocks old (open to storage rent) authorizes nothing; one block younger cancels") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = 1200000
      def cancel(order: UTXO, boxAge: Int): Tx =
        txAt(ctx, h, Seq(inputAt(order, ctx, 0), wallet(ctx, borrower(ctx), 1, h - boxAge)),
          Seq(UTXO(contractOf(borrower(ctx)), order.value, order.tokens)), borrower(ctx).getAddress)
      ok(borrower(ctx), cancel(orderBox(w), 1051199))
      no(borrower(ctx), cancel(orderBox(w), 1051200))
      no(borrower(ctx), cancel(orderBox(w), 1100000))
      // Without the age term the old box cancels: that term, and nothing else, refused it.
      ok(borrower(ctx), cancel(orderBox(w, contract = orderVariant(w, noAuthAge)), 1051200))
    }
  }

  /** `u` with a 1000-byte R5 after its R4: the padding the payout rules refuse. */
  private def padR5(u: UTXO): UTXO = u.setRegs(u.registers.take(1) :+ bytesValue(Array.fill(1000)(7.toByte)): _*)

  property("D refund: the expiry refund is stamped within 999 blocks of HEIGHT and carries no R5") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = created(w) + 1010
      val order = orderBox(w)
      def expire(c: Contract, f: UTXO => UTXO): Tx = {
        val in = inputAt(if (c == null) order else order.setContract(c), ctx, 0).withCtxVar(0.toByte, ErgoValue.of(0))
        txAt(ctx, h, Seq(in), Seq(f(tagged(borrowerTree(ctx), w.collateralErg, in.id, w.collateral))), executor(ctx).getAddress)
      }
      ok(executor(ctx), expire(null, _.setCreationHeight(h - 999)))
      no(executor(ctx), expire(null, _.setCreationHeight(h - 1000)))
      no(executor(ctx), expire(null, padR5))
      ok(executor(ctx), expire(orderVariant(w, "refund.creationInfo._1 > HEIGHT - 1000 &&" -> "true &&"), _.setCreationHeight(h - 1000)))
      ok(executor(ctx), expire(orderVariant(w, "refund.R5[Coll[Byte]].isDefined == false &&" -> "true &&"), padR5))
    }
  }

  property("D principal: the principal box carries no R5") {
    withCtx { ctx =>
      for (kind <- kinds) {
        val w = world(ctx, kind)
        val f = fillOf(w)
        ok(lender(ctx), fiFillTx(f))
        no(lender(ctx), fiFillTx(f, principal = padR5(f.principal)))
        val loose = orderVariant(w, "principal.R4[Coll[Byte]].get == SELF.id &&\n                    principal.R5[Coll[Byte]].isDefined == false" ->
          "principal.R4[Coll[Byte]].get == SELF.id")
        val g = fillOf(w, orderScript = loose)
        ok(lender(ctx), fiFillTx(g, principal = padR5(g.principal)))
      }
    }
  }
}
