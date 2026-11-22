package org.lithosprotocol.sigmafi
package specs.bonds

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{InputUTXO, Token, UTXO}

import scala.util.{Failure, Success, Try}

/** Adversarial review probes for FI_Standard_Bond (throwaway; owned by the reviewer). */
class FiReviewBondSpec extends AnyPropSpec with FiStandardSpecBase {
  import BondSpecBase._
  import ZcStandardSpecBase._

  private def at(w: W, paid: Long = 0L, last: Long = -1L, deadline: Long = 0L, clock: Long = -1L,
                 stamp: Int = -1): InputUTXO = fiIn(w, fiBox(w, paid, last, deadline, clock, stamp))

  /** Sign and report, without asserting. */
  private def observe(tag: String, p: ErgoProver, t: => Tx): Boolean = {
    val r = Try(prover(p, t))
    r match {
      case Success(_) => println(s"FIREVIEW|$tag|ACCEPTED")
      case Failure(e) => println(s"FIREVIEW|$tag|REJECTED|${String.valueOf(e.getMessage).take(160).replace('\n', ' ')}")
    }
    r.isSuccess
  }
  private def prover(p: ErgoProver, t: Tx): SignedTransaction = p.sign(t.unsigned)

  // ─── F-overflow (fixed): the schedule terms are clamped, so no Long in them can throw ────────────

  property("R1 overflow (fixed): with Long.MaxValue in a schedule term, repayment before maturity signs; so do recollateralize and maturity liquidation") {
    withCtx { ctx =>
      for (kind <- Seq[Kind](TokenBond, ErgBond)) {
        val base = world(ctx, kind)
        val h = base.due(0) - 10
        // Sanity: the same tx on the default terms signs.
        ok(borrower(ctx), fiRepayTx(base, at(base), h))
        // 8 count, 9 first-due offset, 10 period, 11 grace, 13 cure window: each Long.MaxValue alone,
        // and 9 with count 0 (no coupons at all; the schedule is still evaluated).
        val cases = Seq(8, 9, 10, 11, 13).map(i => s"R6($i)=MaxValue" -> base.copy(terms = base.terms.updated(i, Long.MaxValue))) :+
          ("count=0, R6(9)=MaxValue" -> base.copy(terms = base.terms.updated(8, 0L).updated(9, Long.MaxValue)))
        cases.foreach { case (name, w) =>
          withClue(s"$kind $name: ") {
            ok(borrower(ctx), fiRepayTx(w, at(w), h))
            ok(borrower(ctx), fiRepayTx(w, at(w, paid = 1000000L), w.maturity.toInt - 1))
            ok(borrower(ctx), recollatTx(w, at(w), w.start + 60))
            ok(executor(ctx), fiLiquidateTx(w, at(w), w.maturity.toInt))
          }
        }
        // A grace of Long.MaxValue reads as 1000000000 blocks: no coupon liquidates before maturity.
        val g = base.copy(terms = base.terms.updated(11, Long.MaxValue))
        no(executor(ctx), fiLiquidateTx(g, at(g), g.maturity.toInt - 1))
        // Negative terms clamp to their lower bound: a first-due offset of Long.MinValue reads as 0, so
        // coupon 0 is due at the start height and missed from start + grace.
        val n = base.copy(terms = base.terms.updated(9, Long.MinValue))
        no(executor(ctx), fiLiquidateTx(n, at(n), n.start + grace - 1))
        ok(executor(ctx), fiLiquidateTx(n, at(n), n.start + grace))
        ok(borrower(ctx), fiRepayTx(n, at(n), n.start + grace - 1))
        no(borrower(ctx), fiRepayTx(n, at(n), n.start + grace))
      }
    }
  }

  // ─── F-outage (DOCUMENTED LIMITATION, bond header F3): an oracle outage during a cure window turns
  // into liquidation. Pins current behaviour; the design question is the standard author's. ───────

  property("R2 outage (documented limitation): with the oracle older than R6(14), Cure is impossible however much is added; Recollateralize does not clear; liquidation at the deadline") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = w.due(0) + 5
      val breached = spendOutput(ok(executor(ctx), recordTx(w, at(w, paid = 1L), h, 0L)), 0)
      val d = h + cureBlocks
      val stale = Seq(oracle(ctx, w.calm, d - 1 - maxAge - 1))
      // 10 ERG more than needed, at the calm price: still refused, the oracle is one block too old.
      val rich = succ(breached, r9(breached).updated(5, 0L), d - 1).addValue(10L * Parameters.OneErg)
      no(borrower(ctx), cureTx(w, breached, d - 1, next = rich, data = stale))
      // The same top-up through Recollateralize signs but leaves the deadline in place...
      val topped = spendOutput(ok(borrower(ctx), recollatTx(w, breached, d - 1,
        next = regrown(w, breached, d - 1).addValue(10L * Parameters.OneErg))), 0)
      r9(topped)(5) shouldBe d.toLong
      // ...so at the deadline anyone liquidates the over-collateralized bond.
      ok(executor(ctx), fiLiquidateTx(w, topped, d))
    }
  }

  // ─── F-void (closed by the quiet zone): a keeper's Record breach can no longer void the borrower's
  // last-block Pay; one before the zone still re-creates the box, with blocks left to rebuild. ─────

  property("R3 void (quiet zone): Record in the last grace / 2 blocks is refused; one just before the zone voids a Pay that is rebuilt in time") {
    withCtx { ctx =>
      val w = world(ctx)
      val b = at(w)
      val missed = w.due(0) + grace
      val zone = missed - grace / 2
      // The borrower's Pay at missed-1 against b is valid, and the lender's Record against the same b
      // at missed-1 (the bond is below ratio) is now refused, so it cannot void the Pay.
      ok(borrower(ctx), payTx(w, b, missed - 1))
      no(lender(ctx), recordTx(w, b, missed - 1, 0L, by = lender(ctx)))
      no(lender(ctx), recordTx(w, b, zone, 0L, by = lender(ctx)))
      // A Record the block before the zone re-creates the box under a Pay built on b...
      val s1 = spendOutput(ok(lender(ctx), recordTx(w, b, zone - 1, 0L, by = lender(ctx))), 0)
      // ...and the Pay rebuilt against s1 anywhere in the zone signs; nothing but the borrower can move s1 there.
      Seq(zone, missed - 1).foreach(h => ok(borrower(ctx), payTx(w, s1, h)))
      no(executor(ctx), fiLiquidateTx(w, s1, missed - 1))
      ok(executor(ctx), fiLiquidateTx(w, s1, missed))
    }
  }

  // ─── two bonds in one transaction ───────────────────────────────────────────────────────────

  private def bondB(w: W, index: Int = 1): InputUTXO =
    inputAt(fiBox(w, tokens = Token(otherBondAsset, 2L) +: w.collateral)
      .withRegNum(4, bytesValue(otherBondAsset.getBytes)), w.ctx, index)

  property("R4 two bonds: one coupon vault cannot serve two Pays; one each signs") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = w.due(0) - 10
      val a = at(w)
      val b = bondB(w)
      val payer = wallet(ctx, borrower(ctx), 6, h, borrowerFunds)
      def tx(bVars: Seq[(Int, ErgoValue[_])], outs: Seq[UTXO]): Tx =
        txAt(ctx, h, Seq(vars(a, Seq(4 -> int(0), 5 -> int(2))), vars(b, bVars), payer), outs, borrower(ctx).getAddress)
      val sa = paid(w, a, h)
      val sb = paid(w, b, h)
      // Shared coupon vault (tagged A) for both: B must refuse.
      observe("R4 shared coupon pool", borrower(ctx), tx(Seq(4 -> int(1), 5 -> int(2)), Seq(sa, sb, couponOut(w)))) shouldBe false
      // Shared successor: B names A's successor.
      observe("R4 shared successor", borrower(ctx), tx(Seq(4 -> int(0), 5 -> int(3)),
        Seq(sa, couponOut(w), sb, couponOut(w, asset = otherBondAsset)))) shouldBe false
      // One each: accepted.
      observe("R4 one each", borrower(ctx), tx(Seq(4 -> int(1), 5 -> int(3)),
        Seq(sa, sb, couponOut(w), couponOut(w, asset = otherBondAsset)))) shouldBe true
      // A Records, B Cures, one oracle, one tx: each answers its own question.
      val hb = w.due(0) + 5
      val bOpen = inputAt(fiBox(w, last = 0L, deadline = hb + 50L, tokens = Token(otherBondAsset, 2L) +: w.collateral)
        .withRegNum(4, bytesValue(otherBondAsset.getBytes)), ctx, 1)
      val cov = 8 -> covVar(w.covenant)
      val mixed = txAt(ctx, hb, Seq(vars(at(w), Seq(6 -> int(0), cov)), vars(bOpen, Seq(7 -> int(1), cov)),
        wallet(ctx, borrower(ctx), 6, hb, borrowerFunds)),
        Seq(recorded(w, at(w), hb, 0L), cured(w, bOpen, hb)), borrower(ctx).getAddress,
        Seq(oracle(ctx, w.slump, hb - 5)))
      observe("R4 record A + cure B", borrower(ctx), mixed) shouldBe true
      // Cure B pointing its var 7 at A's (uncured, unchanged-collateral) Record successor: refused.
      val cross = txAt(ctx, hb, Seq(vars(at(w), Seq(6 -> int(0), cov)), vars(bOpen, Seq(7 -> int(0), cov)),
        wallet(ctx, borrower(ctx), 6, hb, borrowerFunds)),
        Seq(recorded(w, at(w), hb, 0L), cured(w, bOpen, hb)), borrower(ctx).getAddress,
        Seq(oracle(ctx, w.slump, hb - 5)))
      observe("R4 cure B at A's successor", borrower(ctx), cross) shouldBe false
    }
  }

  // ─── coupon theft by a keeper pulling a fractionalized FI bond (closed by the pooled collect rule and, at
  // the 3-unit burn, by the payout holding the home's tokens(0)) ─

  property("R5 fractional (closed): a pool spent beside a pull into ZC_Standard_FractionalVault is refused, so the puller takes no uncollected coupon (the mirror-box variant: FiReview2CoreSpec F1)") {
    withCtx { ctx =>
      val w = world(ctx)
      val n = 1000L
      val pullFee = 2000000L
      val share = ErgoId.create("cc" * 32)
      val frac = inputAt(UTXO(scripts(ctx).fractionalVault, minBox + pullFee,
        Seq(Token(share, n + 1L), Token(bondAsset, 1L)),
        Seq(bytesValue(bondAsset.getBytes), longsValue(n, n, pullFee))), ctx, 3)
      val missed = w.due(1) + grace
      // The bond paid coupon 0, then missed coupon 1 and was liquidated.
      val paidTx = ok(borrower(ctx), payTx(w, at(w), w.due(0) - 10))
      val coupon0 = spendOutput(paidTx, 1)
      val vault = spendOutput(ok(executor(ctx), fiLiquidateTx(w, spendOutput(paidTx, 0), missed)), 0)
      val pulled = UTXO(scripts(ctx).fractionalVault, frac.value + vault.value - pullFee,
        Token(share, n + 1L) +: vault.tokens.drop(1),
        Seq(bytesValue(bondAsset.getBytes), longsValue(n, n, pullFee), vault.registers(1)))
      // Sanity: the plain pull.
      ok(executor(ctx), txAt(ctx, missed + 1, Seq(frac.withCtxVar(0.toByte, ErgoValue.of(0)), vault), Seq(pulled),
        executor(ctx).getAddress, burn = Seq(Token(bondAsset, 3L))))
      // The keeper adds the coupon vault as a third input: now refused by the coupon vault.
      no(executor(ctx), txAt(ctx, missed + 1, Seq(frac.withCtxVar(0.toByte, ErgoValue.of(0)), vault, coupon0),
        Seq(pulled), executor(ctx).getAddress, burn = Seq(Token(bondAsset, 3L))))
      // The nearest legitimate collect: the holder, with the unit still in a key box, takes it.
      ok(lender(ctx), collectTx(w, Seq(coupon0), Seq(unit(ctx, missed)), missed + 1))
    }
  }

  // ─── edge terms ──────────────────────────────────────────────────────────────────────────────

  property("R6 edges: period 0 reads as 1; count 0 never liquidates on coupons nor records; a cure window below 1 shuts Record") {
    withCtx { ctx =>
      val base = world(ctx)
      val p0 = base.copy(terms = base.terms.updated(10, 0L))
      // step 1: coupon k due at s+200+k; Pay of coupon 0 opens at due-1.
      ok(borrower(ctx), payTx(p0, at(p0), p0.due(0) - 1))
      no(borrower(ctx), payTx(p0, at(p0), p0.due(0) - 2))
      ok(executor(ctx), fiLiquidateTx(p0, at(p0, paid = 2L), p0.due(0) + grace + 2))
      val c0 = base.copy(terms = base.terms.updated(8, 0L))
      no(executor(ctx), fiLiquidateTx(c0, at(c0), c0.due(2) + grace))
      no(executor(ctx), recordTx(c0, at(c0), c0.due(0) + 5, 0L))
      no(borrower(ctx), payTx(c0, at(c0), c0.due(0) - 10))
      // Cure window 0 (formerly born-liquidatable: the deadline could equal the Record height): Record refused.
      val h = base.due(0) + 5
      Seq(0L, -1L).foreach { c =>
        val z = base.copy(terms = base.terms.updated(13, c))
        no(executor(ctx), recordTx(z, at(z, paid = 1L), h, 0L, next = recorded(z, at(z, paid = 1L), h, 0L, h.toLong + c)))
      }
      // Cure window 1 (no order fills a covenant with a window under 30; the bond itself takes 1): Record
      // signs; the breach can be cured in its own block and is final from the next.
      val one = base.copy(terms = base.terms.updated(13, 1L))
      val s = spendOutput(ok(executor(ctx), recordTx(one, at(one, paid = 1L), h, 0L,
        next = recorded(one, at(one, paid = 1L), h, 0L, h + 1L))), 0)
      no(executor(ctx), fiLiquidateTx(one, s, h))
      ok(borrower(ctx), cureTx(one, s, h))
      ok(executor(ctx), fiLiquidateTx(one, s, h + 1))
    }
  }

  // ─── var-type confusion: var 6 as Coll[Byte] beside a Cure ─────────────────────────────────────

  property("R7 types: a path var of the wrong type does not reach another path") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = w.due(0) + 5
      val open = at(w, last = 0L, deadline = h + 50L)
      val cov = 8 -> covVar(w.covenant)
      observe("R7 cure + var6 Coll[Byte]", borrower(ctx), cureTx(w, open, h, vs = Seq(6 -> bytesValue(Array[Byte](1)), 7 -> int(0), cov))) shouldBe false
      observe("R7 pay var4 Long", borrower(ctx), payTx(w, at(w), h - 15, vs = Seq(4 -> ErgoValue.of(0L), 5 -> int(1)))) shouldBe false
      // A Cure whose var 7 is a Long: neither the bond nor the covenant may read it as a path.
      observe("R7 cure var7 Long", borrower(ctx), cureTx(w, open, h, vs = Seq(7 -> ErgoValue.of(0L), cov))) shouldBe false
    }
  }
}
