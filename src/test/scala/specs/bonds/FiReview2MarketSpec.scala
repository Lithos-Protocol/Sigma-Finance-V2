package org.lithosprotocol.sigmafi
package specs.bonds

import ksingh.sigmabonds.app.Protocol
import ksingh.sigmabonds.client.ScriptGenerator
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import sigma.Colls
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

/**
 * Adversarial review 2 of the FI market contracts (order, lend offer, collateral policy, fill guard).
 * Each FINDING is closed: its property refuses the attack, a mutant compiled from the contract file without
 * the fix signs it (so the fix is what refuses it), and the honest path is kept. The FAILED ATTACK
 * properties pin the lines that stopped an attack.
 */
class FiReview2MarketSpec extends AnyPropSpec with FiStandardMarketSpecBase {
  import BondSpecBase._
  import ZcStandardSpecBase._

  private val kinds = Seq[Kind](TokenBond, ErgBond)

  /** The FI lend offer for `w`'s market compiled from its file with `edits` applied. */
  private def offerVariant(w: W, edits: (String, String)*): Contract = {
    val bond = if (w.erg) fi(w.ctx).ergBond else fi(w.ctx).tokenBond
    FiStandardMarketSpecBase.offerFor(w.ctx, repaymentBytes(w), fiMarket(w).order, bond, edits: _*)
  }

  /** A held FI bond: filled from its genuine order (so `origin` hashes to the asset). */
  private case class Held(f: FiFill, state: InputUTXO) {
    def w: W = f.w
    def origin: Array[Byte] = f.orderIn.bytes
  }

  private def held(ctx: BlockchainContext, kind: Kind): Held = {
    val f = fillOf(world(ctx, kind))
    Held(f, spendOutput(ok(lender(ctx), fiFillTx(f)), 1))
  }

  /** The bond state box with R9 replaced: the state a sequence of bond transactions reaches. */
  private def stateWith(l: Held, r9: Seq[Long]): InputUTXO =
    inputAt(l.state.toUTXO.withRegNum(9, longsValue(r9: _*)), l.w.ctx, 0, "cd" * 32)

  /** The FI order for `w`'s market compiled from its file with `edits` applied. */
  private def orderVariant(w: W, edits: (String, String)*): Contract =
    FiStandardMarketSpecBase.orderFor(w.ctx, w.bond, repaymentBytes(w), edits: _*)

  // ═══ FINDING 1 (closed): the offer bought a bond that was already liquidatable ════════

  // The fix: the existing-bond branch requires the bond's own `liquidatable` to be false. Mutants without
  // each half: the missed-coupon count, and the open breach at its deadline.
  private val noMissedCheck =
    "lender(3) >= (if (HEIGHT.toLong < lender(1) + borrower(9) + borrower(11)) 0L" -> "lender(3) >= (if (true) 0L"
  private val noDeadlineCheck = "(lender(5) == 0L || HEIGHT.toLong < lender(5))" -> "true"

  for (kind <- kinds) {
    property(s"FINDING 1 $kind (closed): an offer refuses a unit of a bond whose coupon is missed; without the missed-coupon rule it buys it") {
      withCtx { ctx =>
        val l = held(ctx, kind)
        val w = l.w
        val h = w.due(0) + grace // coupon 0 unpaid: missed from this block
        no(executor(ctx), buyTx(w, l.origin, l.state, fiOffer(w), unitPrice(w), h))
        val loose = fiOffer(w).setContract(offerVariant(w, noMissedCheck))
        val buy = ok(executor(ctx), buyTx(w, l.origin, l.state, loose, unitPrice(w), h))
        spendOutput(buy, 1).tokens shouldBe Seq(Token(l.f.asset, 1L))
        // ... while anyone may already liquidate the bond it names.
        ok(executor(ctx), fiLiquidateTx(w, l.state, h))
        // The same refusal with the real FI policy at a calm price (the policy judges collateral, not the schedule).
        val policy = fiMarket(w).policy
        no(executor(ctx), buyTx(w, l.origin, l.state, fiOffer(w, OTerms(floorBps = 15000L), policy = policy),
          unitPrice(w), h, policy = policy, data = Seq(oracle(ctx, w.calm, h))))
        // One block earlier the bond is still performing: the honest purchase this branch is for.
        ok(executor(ctx), buyTx(w, l.origin, l.state, fiOffer(w), unitPrice(w), h - 1))
        no(executor(ctx), fiLiquidateTx(w, l.state, h - 1))
      }
    }
  }

  property("FINDING 1 (closed): an offer refuses a unit of a bond whose open breach has reached its deadline; without the deadline rule it buys it") {
    withCtx { ctx =>
      val l = held(ctx, TokenBond)
      val w = l.w
      val h = w.due(0) + 5 // before coupon 0's miss height: only the breach makes it liquidatable
      val breached = stateWith(l, bornState(w).updated(4, 0L).updated(5, h.toLong))
      no(executor(ctx), buyTx(w, l.origin, breached, fiOffer(w), unitPrice(w), h))
      ok(executor(ctx), buyTx(w, l.origin, breached, fiOffer(w).setContract(offerVariant(w, noDeadlineCheck)), unitPrice(w), h))
      ok(executor(ctx), fiLiquidateTx(w, breached, h))
    }
  }

  property("FINDING 1 cost (closed): under the real policy at a 50% floor the offer no longer pays 650 SigUSD for a defaulted unit whose collateral is worth $5") {
    withCtx { ctx =>
      val l = held(ctx, TokenBond)
      val w = l.w
      val h = w.due(0) + grace
      // 1.4 ERG per USD: 7 ERG of collateral is $5 against $8 owed (62.5%), above a 50% floor. The real policy
      // now also refuses it as below the bond's own 150% ratio (F-026); that term is taken out here so the
      // offer's missed-coupon rule is shown alone.
      val policy = FiStandardMarketSpecBase.policyFor(ctx, repaymentBytes(w),
        " &&\n            collateralValue * 10000L.toBigInt >= debtValue * borrower(12).toBigInt" -> "")
      def buy(c: Contract): Tx = buyTx(w, l.origin, l.state, fiOffer(w, OTerms(floorBps = 5000L), policy = policy).setContract(c),
        unitPrice(w), h, policy = policy, data = Seq(oracle(ctx, 1400000000L, h)))
      no(executor(ctx), buy(fiMarket(w).offer))
      ok(executor(ctx), buy(offerVariant(w, noMissedCheck)))
      // What it would have bought: the unit redeems the liquidation, not the repayment: 7 ERG and 100 unpriced tokens.
      val vault = spendOutput(ok(executor(ctx), fiLiquidateTx(w, l.state, h)), 0)
      vault.value shouldBe 7000000000L
      vault.tokens.filter(_.id == SigUSD) shouldBe empty
      unitPrice(w) shouldBe 650L
      // Under the real policy the same purchase is refused, missed-coupon rule or not.
      val real = fiMarket(w).policy
      no(executor(ctx), buyTx(w, l.origin, l.state, fiOffer(w, OTerms(floorBps = 5000L), policy = real)
        .setContract(offerVariant(w, noMissedCheck)), unitPrice(w), h, policy = real, data = Seq(oracle(ctx, 1400000000L, h))))
    }
  }

  property("FINDING 1 fix (applied): the offer refuses both purchases at their edges and keeps the performing ones") {
    withCtx { ctx =>
      for (kind <- kinds) {
        val l = held(ctx, kind)
        val w = l.w
        val offer = fiOffer(w)
        val h = w.due(0) + grace
        no(executor(ctx), buyTx(w, l.origin, l.state, offer, unitPrice(w), h))
        ok(executor(ctx), buyTx(w, l.origin, l.state, offer, unitPrice(w), h - 1))
        val breachAt = w.due(0) + 5
        no(executor(ctx), buyTx(w, l.origin, stateWith(l, bornState(w).updated(4, 0L).updated(5, breachAt.toLong)),
          offer, unitPrice(w), breachAt))
        ok(executor(ctx), buyTx(w, l.origin, stateWith(l, bornState(w).updated(4, 0L).updated(5, breachAt + 1L)),
          offer, unitPrice(w), breachAt))
        // After coupon 0 is paid, the same height is a performing bond again.
        ok(executor(ctx), buyTx(w, l.origin, stateWith(l, bornState(w).updated(3, 1L)), offer, unitPrice(w), h))
        // New-bond fills are unaffected.
        ok(executor(ctx), offerFillTx(fillOf(w), fiOffer(w)))
      }
    }
  }

  // ═══ FINDING 2 (closed): the "settled bond" test passed for the wrong reason ═══════
  // FiStandardLendOfferSpec's "the origin must be the FI order ..." now also refuses the forged vault below and
  // signs it against the offer without the state-script check, so that check is pinned there.

  property("FINDING 2 (closed): a settled bond's vault box can carry FI-shaped R6/R9; only CONST_BOND_HASH refuses it") {
    withCtx { ctx =>
      val l = held(ctx, ErgBond)
      val w = l.w
      val h = l.f.height + 50
      // The repayer wrote the vault box: the bond pinned only its script, tokens(0), R4 and R5. It now also refuses
      // an R6 on it (round 3, C), so the forged box is written through the bond without that term.
      def forgedRegs(b: InputUTXO) = repaid(w, b).registers ++ Seq(longsValue(w.terms: _*), Protocol.tokensValue(Seq.empty),
        bytesValue(fi(ctx).payout.hashedValueBytes), longsValue(bornState(w): _*))
      no(borrower(ctx), fiRepayTx(w, l.state, h, settle = repaid(w, l.state).setRegs(forgedRegs(l.state): _*)))
      val loose = inputAt(l.state.toUTXO.setContract(FiStandardSpecBase.bondVariant(ctx, repaymentBytes(w), fi(ctx).couponVault,
        " &&\n                    settlement.R6[Coll[Byte]].isDefined == false" -> "")), ctx, 0, "cd" * 32)
      val forged = spendOutput(ok(borrower(ctx), fiRepayTx(w, loose, h, settle = repaid(w, loose).setRegs(forgedRegs(loose): _*))), 0)
      val plain = spendOutput(ok(borrower(ctx), fiRepayTx(w, l.state, h)), 0)
      // The real offer refuses both.
      no(executor(ctx), buyTx(w, l.origin, plain, fiOffer(w), unitPrice(w), h + 1))
      no(executor(ctx), buyTx(w, l.origin, forged, fiOffer(w), unitPrice(w), h + 1))
      // Without the state-script check the plain vault is still refused (its R6 is absent: None.get) ...
      val unchecked = offerVariant(w, "blake2b256(bond.propositionBytes) == CONST_BOND_HASH &&" -> "CONST_BOND_HASH.size >= 0 &&")
      no(executor(ctx), buyTx(w, l.origin, plain, fiOffer(w).setContract(unchecked), unitPrice(w), h + 1))
      // ... and the mutant buys the unit of a repaid bond against forged terms.
      ok(executor(ctx), buyTx(w, l.origin, forged, fiOffer(w).setContract(unchecked), unitPrice(w), h + 1))
    }
  }

  // ═══ FINDING 3 (closed): anyone's expiry refund of an offer read R6 and R7 first ══

  // The offer as it was: R6 and R7 read before the expiry branch.
  private val hoisted = Seq(
    "            // R6 and R7 are read only here, so a box with either malformed still refunds.\n" +
      "            val bounds = SELF.R7[Coll[Long]].get\n            val policyRecord = SELF.R6[Coll[Byte]].get\n" +
      "            val policyHash = policyRecord.slice(0, 32)\n" -> "",
    "        val nextIndex = getVar[Int](0).get\n" ->
      ("        val bounds = SELF.R7[Coll[Long]].get\n        val policyRecord = SELF.R6[Coll[Byte]].get\n" +
        "        val policyHash = policyRecord.slice(0, 32)\n        val nextIndex = getVar[Int](0).get\n"))

  /** The offer with R7 absent, R7 mistyped, and R6 mistyped. */
  private def malformed(offer: UTXO): Seq[UTXO] =
    Seq(offer.setRegs(offer.registers.take(3): _*), offer.withRegNum(7, bytesValue(Array[Byte](1))),
      offer.withRegNum(6, longsValue(1L)))

  property("FINDING 3 (closed): an offer with R7 absent or mistyped (or R6 mistyped) is expiry-refunded by anyone; with the reads before the expiry branch it is not") {
    withCtx { ctx =>
      val w = world(ctx)
      val exp = ctx.getHeight + 50
      val offer = fiOffer(w, OTerms(expiry = exp))
      def refund(b: UTXO): Tx = {
        val in = inputAt(b, ctx, 1).withCtxVar(0.toByte, ErgoValue.of(0))
        txAt(ctx, exp, Seq(in, wallet(ctx, executor(ctx), 9, created(w))),
          Seq(tagged(lenderTree(ctx), b.value - batchingFee, in.id, b.tokens)), executor(ctx).getAddress)
      }
      def cancel(b: UTXO): Tx = txAt(ctx, exp, Seq(inputAt(b, ctx, 1), wallet(ctx, lender(ctx), 9, created(w))),
        Seq(UTXO(lenderTree(ctx), b.value, b.tokens)), lender(ctx).getAddress)
      ok(executor(ctx), refund(offer))
      val before = offerVariant(w, hoisted: _*)
      malformed(offer).foreach { bad =>
        ok(executor(ctx), refund(bad))
        no(executor(ctx), refund(bad.setContract(before)))
        ok(lender(ctx), cancel(bad)) // the owner's cancel works either way
      }
      // Wrong length but right type refunds (the size checks sit after the expiry branch).
      ok(executor(ctx), refund(offer.withRegNum(7, longsValue(1L))))
    }
  }

  property("FINDING 3 fix (applied): the fill is unchanged; an offer with R7 absent, mistyped or of the wrong length still never fills") {
    withCtx { ctx =>
      val w = world(ctx)
      val f = fillOf(w)
      ok(executor(ctx), offerFillTx(f, fiOffer(w)))
      // (A mistyped R6 is left out: the harness's continuation builder reads R6 as bytes.)
      (malformed(fiOffer(w)).take(2) :+ fiOffer(w, r7 = Seq(1L))).foreach(bad => no(executor(ctx), offerFillTx(f, bad)))
    }
  }

  // ═══ FINDING 4 (closed): a backdated start could put checkpoint 0 before the fill ════
  // Fixed in the FI order (every FI bond has it): with coupons, the first due height is at or after the fill.

  private val noFirstDueRule = "start + firstOffset >= HEIGHT.toLong && grace <= period &&" -> "grace <= period &&"

  property("FINDING 4 (closed): a schedule whose first due height precedes the fill is refused by the order, so no offer buys it") {
    withCtx { ctx =>
      val h = fillHeight(world(ctx))
      // Start 8 back, first due at the start, one coupon; period and grace 38, so the first miss is 30 blocks
      // after the fill (the order's least).
      val filled = withTerms(startedAt(world(ctx), h - 8), 8 -> 1L, 9 -> 0L, 10 -> 38L, 11 -> 38L)
      no(lender(ctx), fiFillTx(fillOf(filled)))
      no(executor(ctx), offerFillTx(fillOf(filled), fiOffer(filled)))
      // The order without the first-due rule fills it: that rule refuses it.
      ok(lender(ctx), fiFillTx(fillOf(filled, orderScript = orderVariant(filled, noFirstDueRule))))
      // Why, on hand-made boxes with grace 10 (missed at h + 2, the order's minimum; the FI order now also
      // refuses a covenant grace under 30): Record at the fill height and the next block is refused by the
      // quiet zone [h - 3, h + 2) ...
      val w = withTerms(startedAt(world(ctx), h - 8), 8 -> 1L, 9 -> 0L, 10 -> 10L, 11 -> 10L)
      val d0 = w.due(0) - firstOffset // due(0) uses the default offset; this world's is 0
      d0 shouldBe h - 8
      no(lender(ctx), fiFillTx(fillOf(w)))
      val noZone = FiStandardSpecBase.bondVariant(ctx, repaymentBytes(w), fi(ctx).couponVault,
        "else !quiet)" -> "else true)")
      Seq(h, h + 1).foreach { at =>
        no(executor(ctx), recordTx(w, fiIn(w, fiBox(w)), at, 0L))
        ok(executor(ctx), recordTx(w, fiIn(w, fiBox(w).setContract(noZone)), at, 0L))
      }
      // ... at h + 2 unpaid the bond is liquidatable, so the one height left is h + 2 after a Pay at h + 1.
      no(executor(ctx), recordTx(w, fiIn(w, fiBox(w)), h + 2, 0L))
      ok(executor(ctx), recordTx(w, fiIn(w, fiBox(w, paid = 1L)), h + 2, 0L))
      no(executor(ctx), recordTx(w, fiIn(w, fiBox(w, paid = 1L)), h + 3, 0L))
      // Honest: the same schedule due at the fill height fills through the offer and records at its due height.
      val edge = withTerm(filled, 9, 8L)
      ok(executor(ctx), offerFillTx(fillOf(edge), fiOffer(edge)))
      ok(executor(ctx), recordTx(edge, fiIn(edge, fiBox(edge)), h, 0L))
    }
  }

  property("FINDING 4 fix probe: the offer-side variant (first due at or after the fill) adds nothing once the order has the rule") {
    withCtx { ctx =>
      val h = fillHeight(world(ctx))
      val back = withTerms(startedAt(world(ctx), h - 8), 8 -> 1L, 9 -> 0L, 10 -> 30L, 11 -> 30L)
      def variant(w: W): UTXO = fiOffer(w).setContract(offerVariant(w,
        "val scheduleValid =\n                    bounds(1) >= 1L" ->
          "val scheduleValid =\n                    (!primary || firstDue >= HEIGHT.toLong) && bounds(1) >= 1L"))
      // First due 8 blocks before the fill: refused by the real offer (the order refuses it) and by the variant.
      no(executor(ctx), offerFillTx(fillOf(back), fiOffer(back)))
      no(executor(ctx), offerFillTx(fillOf(back), variant(back)))
      // First due exactly at the fill height, start 8 back, and the default world: both fill.
      val edge = withTerm(back, 9, 8L)
      Seq(edge, world(ctx)).foreach { w =>
        ok(executor(ctx), offerFillTx(fillOf(w), fiOffer(w)))
        ok(executor(ctx), offerFillTx(fillOf(w), variant(w)))
      }
    }
  }

  // ═══ FAILED ATTACKS (pinned) ══════════════════════════════════════════════

  property("FAILED ATTACK: grace = period below 720 keeps every later checkpoint recordable at its due height") {
    withCtx { ctx =>
      // Period 100, grace 100 (Q = 50), 5 coupons. Coupon 0 paid in its last block; checkpoint 1 records at d1.
      val w = withTerms(world(ctx), 8 -> 5L, 9 -> 100L, 10 -> 100L, 11 -> 100L)
      ok(executor(ctx), offerFillTx(fillOf(w), fiOffer(w)))
      val d1 = w.start + 100 + 100
      ok(executor(ctx), recordTx(w, fiIn(w, fiBox(w, paid = 1L)), d1, 1L))
      // Paid two ahead: still recordable at d1.
      ok(executor(ctx), recordTx(w, fiIn(w, fiBox(w, paid = 3L)), d1, 1L))
      // Unpaid at d1: missed and liquidatable, so nothing is lost by refusing Record.
      ok(executor(ctx), fiLiquidateTx(w, fiIn(w, fiBox(w)), d1))
      // One coupon, first due at the fill (not backdated): recordable in the fill's own block.
      val one = withTerms(world(ctx), 8 -> 1L, 9 -> 0L, 10 -> 30L, 11 -> 30L)
      ok(executor(ctx), offerFillTx(fillOf(one), fiOffer(one)))
      ok(executor(ctx), recordTx(one, fiIn(one, fiBox(one)), one.start, 0L))
    }
  }

  property("FAILED ATTACK: the fill guard and the covenant agree to the unit at the boundary price") {
    withCtx { ctx =>
      // Token bond: 7 ERG against 800 raw SigUSD at 150%: boundary 583333333 nanoERG per USD passes the guard.
      val w = world(ctx, TokenBond)
      val g = fiMarket(w).guard
      val h = fillHeight(w)
      ok(lender(ctx), fiFillTx(fillOf(w, guard = g), data = Seq(oracle(ctx, 583333333L, h))))
      no(lender(ctx), fiFillTx(fillOf(w, guard = g), data = Seq(oracle(ctx, 583333334L, h))))
      // At the price the guard passed, Record is refused; one tick worse it records.
      val at = w.due(0)
      no(executor(ctx), recordTx(w, fiIn(w, fiBox(w)), at, 0L, price = 583333333L))
      ok(executor(ctx), recordTx(w, fiIn(w, fiBox(w)), at, 0L, price = 583333334L))
      // Two oracle-NFT data inputs: refused by the guard.
      no(lender(ctx), fiFillTx(fillOf(w, guard = g), data = Seq(oracle(ctx, 500000000L, h), oracle(ctx, 500000000L, h, index = 21))))
    }
  }

  property("FAILED ATTACK: one order cannot be filled against two offers (the delivery tag names one)") {
    withCtx { ctx =>
      val w = world(ctx, ErgBond)
      val f = fillOf(w)
      val a = fiOffer(w)
      val b = fiOffer(w, OTerms(minFill = 2L))
      val ina = offerIn(a, ctx, null, 3)
      val inb = inputAt(b, ctx, 2).withCtxVar(0.toByte, ErgoValue.of(4)).withCtxVar(1.toByte, liqVar(alwaysTrue(ctx)))
      val price = priceOf(w)
      val ergOut = batcherFeeOf(w, a, price) + minBox + batchingFee + w.terms(1)
      no(executor(ctx), txAt(ctx, f.height, Seq(orderInput(f), ina, inb),
        Seq(offerDelivery(f.unit, ina.id), f.bond, f.principal, continuationOf(w, a, ina.id, ergOut, price),
          continuationOf(w, b, inb.id, ergOut, price)), executor(ctx).getAddress))
    }
  }
}
