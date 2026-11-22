package org.lithosprotocol.sigmafi
package specs.bonds

import ksingh.sigmabonds.app.Protocol
import ksingh.sigmabonds.client.ScriptGenerator
import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import sigma.Colls
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

/**
 * FI_Standard_LendOffer and FI_Standard_ERG_SigUSD_CollateralPolicy. The lender owns the offer, the
 * executor is the batcher, `other` sells. Most cases run an always-true policy so they test the offer
 * alone; the policy has its own section. The default bounds (`tightBounds`) are exactly the default
 * world's terms, so one step tighter in any bound refuses.
 */
class FiStandardLendOfferSpec extends AnyPropSpec with FiStandardMarketSpecBase {
  import BondSpecBase._
  import ZcStandardSpecBase._

  private val kinds = Seq[Kind](TokenBond, ErgBond)

  /** The FI lend offer for `w`'s market compiled from its file with `edits` applied: a mutant, to show which clause refuses. */
  private def offerVariant(w: W, edits: (String, String)*): Contract = {
    val bond = if (w.erg) fi(w.ctx).ergBond else fi(w.ctx).tokenBond
    FiStandardMarketSpecBase.offerFor(w.ctx, repaymentBytes(w), fiMarket(w).order, bond, edits: _*)
  }

  private def fillsBy(f: FiFill, offer: UTXO): SignedTransaction = ok(executor(f.ctx), offerFillTx(f, offer))
  private def refusedBy(f: FiFill, offer: UTXO): Unit = no(executor(f.ctx), offerFillTx(f, offer))

  // ═══ COMPILATION AND SIZE ═════════════════════════════════════════════════

  property("compile: ERG and token offers share a template and pin their FI order and bond; the policy pins FI's liquidation") {
    withCtx { ctx =>
      val m = mk(ctx)
      Seq(m.erg.offer, m.token.offer, m.erg.policy, m.token.policy).foreach(_.ergoTree.version shouldBe 3)
      m.erg.offer.ergoTree.template shouldBe m.token.offer.ergoTree.template
      m.erg.offer.ergoTree.bytes should not be m.token.offer.ergoTree.bytes
      Seq(m.erg -> fi(ctx).ergBond, m.token -> fi(ctx).tokenBond).foreach { case (mm, bond) =>
        mm.offer.ergoTreeHex should include(mm.order.hashedPropBytesHex)
        mm.offer.ergoTreeHex should include(bond.hashedPropBytesHex)
        mm.policy.ergoTreeHex should include(org.bouncycastle.util.encoders.Hex.toHexString(fi(ctx).payout.hashedValueBytes))
      }
      val w = world(ctx)
      println(s"FIMKT|tree|FI LendOffer ERG=${m.erg.offer.propBytes.length}|FI LendOffer SigUSD=${m.token.offer.propBytes.length}|" +
        s"ZC LendOffer ERG=${marketOf(ctx, ErgBond).offer.propBytes.length}|ZC LendOffer SigUSD=${marketOf(ctx, TokenBond).offer.propBytes.length}|" +
        s"FI policy ERG=${m.erg.policy.valueBytes.length}|FI policy SigUSD=${m.token.policy.valueBytes.length}|" +
        s"ZC policy SigUSD=${marketOf(ctx, TokenBond).policy.valueBytes.length}")
      for (kind <- kinds) {
        val kw = world(ctx, kind)
        val f = fillOf(kw)
        val offer = fiOffer(kw)
        val signed = fillsBy(f, offer)
        println(s"FIMKT|box|$kind|offer=${inputAt(offer, ctx, 1).bytes.length}|continuation=${outBytes(signed, 3)}|" +
          s"offer fill tx=${signed.toBytes.length}|offer fill cost=${signed.getCost}")
      }
      w.terms.size shouldBe 15
    }
  }

  // ═══ NEW BOND ═════════════════════════════════════════════════════════════

  for (kind <- kinds) {
    property(s"$kind new bond: an offer fills an FI order; the unit reaches the owner; the bond then accepts a Pay") {
      withCtx { ctx =>
        val w = world(ctx, kind)
        val f = fillOf(w)
        val offer = fiOffer(w)
        val tx = fillsBy(f, offer)
        tx.getOutputsToSpend.get(0).getErgoTree shouldBe lenderTree(ctx).ergoTree
        tx.getOutputsToSpend.get(0).getTokens.get(0).getId shouldBe f.asset
        tx.getOutputsToSpend.get(3).getErgoTree shouldBe fiMarket(w).offer.ergoTree
        val bond = spendOutput(tx, 1)
        r9(bond) shouldBe bornState(w)
        // The bond the offer bought is a working FI bond: the borrower pays coupon 0, the owner collects it.
        val h = w.due(0) - 10
        val paid = ok(borrower(ctx), payTx(w, bond, h, coupon = couponOut(w, asset = f.asset)))
        ok(lender(ctx), collectTx(w, Seq(spendOutput(paid, 1)), Seq(spendOutput(tx, 0), wallet(ctx, lender(ctx), 9, h)), h + 1))
        // The continuation fills a second order (the token offer's 400 SigUSD left cannot lend 600).
        val second = fillOf(withTerm(w, 4, w.terms(4) + 1L))
        val next = spendOutput(tx, 3).toUTXO
        if (w.erg) ok(executor(ctx), offerFillTx(second, next))
        else next.tokens shouldBe Seq(Token(SigUSD, tokenCapital - priceOf(w)))
      }
    }

    property(s"$kind new bond: the yield test is exact on the repayment alone; coupons come on top") {
      withCtx { ctx =>
        val w = world(ctx, kind)
        val f = fillOf(w)
        val cost = if (w.erg) priceOf(w) + OTerms().batcherFee else priceOf(w)
        val y = maxYield(w.terms(0), cost, w.maturity - f.height)
        fillsBy(f, fiOffer(w, OTerms(yieldBps = y)))
        refusedBy(f, fiOffer(w, OTerms(yieldBps = y + 1L)))
        // A larger coupon does not lower the price the offer may pay.
        val rich = withTerm(w, 7, w.terms(7) * 10L)
        refusedBy(fillOf(rich), fiOffer(rich, OTerms(yieldBps = y + 1L)))
      }
    }
  }

  // ═══ SCHEDULE BOUNDS (R7) ═════════════════════════════════════════════════

  property("bounds: minimum coupon and minimum count; a minimum count below 1 never fills") {
    withCtx { ctx =>
      for (kind <- kinds) {
        val w = world(ctx, kind)
        val f = fillOf(w)
        val b = tightBounds(w)
        fillsBy(f, fiOffer(w, bounds = b))
        withClue("coupon")(refusedBy(f, fiOffer(w, bounds = b.copy(minCoupon = b.minCoupon + 1L))))
        withClue("count")(refusedBy(f, fiOffer(w, bounds = b.copy(minCount = b.minCount + 1L))))
        fillsBy(f, fiOffer(w, bounds = b.copy(minCount = 1L)))
        withClue("count 0")(refusedBy(f, fiOffer(w, bounds = b.copy(minCount = 0L))))
      }
      // A bond with no coupons is never bought: it has no checkpoint for the covenant.
      val none = withTerms(world(ctx), 8 -> 0L, 12 -> 0L)
      refusedBy(fillOf(none), fiOffer(none, bounds = tightBounds(world(ctx)).copy(minCount = 0L, maxGap = 2000L)))
      refusedBy(fillOf(none), fiOffer(none, bounds = tightBounds(world(ctx)).copy(minCount = 1L, maxGap = 2000L)))
    }
  }

  property("bounds: the maximum gap binds the first offset, the period and the stretch from the last due to maturity") {
    withCtx { ctx =>
      val base = world(ctx)
      // Each world has one stretch strictly longest: first offset 1000 (then 100, 800), period 1000
      // (then 100, 900), last gap 1400 (then 200, 200).
      val first = withTerms(base, 9 -> 1000L, 10 -> 100L)
      val period = withTerms(maturingAt(base, base.start + 3000L), 9 -> 100L, 10 -> 1000L)
      Seq("first" -> (first, 1000L), "period" -> (period, 1000L), "last" -> (base, 1400L)).foreach { case (name, (w, gap)) =>
        gapOf(w) shouldBe gap
        val f = fillOf(w)
        withClue(name)(fillsBy(f, fiOffer(w, bounds = tightBounds(w))))
        withClue(name)(refusedBy(f, fiOffer(w, bounds = tightBounds(w).copy(maxGap = gap - 1L))))
      }
    }
  }

  property("bounds: checkpoints in the last 720 blocks, where the bond shuts Record breach, do not shorten the gap") {
    withCtx { ctx =>
      val base = world(ctx)
      // Life 1000: checkpoints from s + 280 on cannot be recorded. Coupons at 100..800: only 100 and 200
      // can, so the last stretch is 800 blocks, though the last coupon falls 200 before maturity.
      val late = withTerms(maturingAt(base, base.start + 1000L), 8 -> 8L, 9 -> 100L, 10 -> 100L)
      // Coupons at 300..600: none can be recorded, so the gap is the whole life. With a covenant the FI order
      // refuses this schedule (its first checkpoint is not before maturity - 720), so it has none.
      val none = withTerms(maturingAt(base, base.start + 1000L), 8 -> 4L, 9 -> 300L, 10 -> 100L, 12 -> 0L)
      Seq("late" -> (late, 800L), "none" -> (none, 1000L)).foreach { case (name, (w, gap)) =>
        gapOf(w) shouldBe gap
        val f = fillOf(w)
        withClue(name)(fillsBy(f, fiOffer(w, bounds = tightBounds(w))))
        withClue(name)(refusedBy(f, fiOffer(w, bounds = tightBounds(w).copy(maxGap = gap - 1L))))
      }
      // The boundary: a checkpoint whose last block, due + grace, is maturity - 721 is recordable for its whole
      // window, so it ends the stretch.
      val edge = withTerms(maturingAt(base, base.start + 1051L), 8 -> 3L, 9 -> 100L, 10 -> 100L)
      gapOf(edge) shouldBe 751L
      // One block later the third checkpoint's last block falls at maturity - 720: the stretch runs from the second.
      val later = withTerm(edge, 9, 101L)
      gapOf(later) shouldBe 850L
      // A checkpoint due at maturity - 721 itself is recordable in its due block only: it no longer ends the stretch.
      val due721 = withTerms(maturingAt(base, base.start + 1021L), 8 -> 3L, 9 -> 100L, 10 -> 100L)
      gapOf(due721) shouldBe 821L
      Seq(edge -> 751L, later -> 850L, due721 -> 821L).foreach { case (w, gap) =>
        refusedBy(fillOf(w), fiOffer(w, bounds = tightBounds(w).copy(maxGap = gap - 1L)))
        fillsBy(fillOf(w), fiOffer(w, bounds = tightBounds(w)))
      }
      // The offer as it counted (the last checkpoint due at or before maturity - 721) takes the shortened one.
      val dueOnly = offerVariant(due721, "val recordable = lender(2) - 721L - firstDue - borrower(11)" -> "val recordable = lender(2) - 721L - firstDue")
      fillsBy(fillOf(due721), fiOffer(due721, bounds = tightBounds(due721).copy(maxGap = 721L)).setContract(dueOnly))
      refusedBy(fillOf(due721), fiOffer(due721, bounds = tightBounds(due721).copy(maxGap = 721L)))
      // The bond agrees: with every coupon paid, a breach is recorded at the third checkpoint's last allowed
      // block in the first world, and refused at it in the second.
      def third(w: W): Int = w.start + w.terms(9).toInt + 2 * w.terms(10).toInt
      ok(executor(ctx), recordTx(edge, fiIn(edge, fiBox(edge, paid = 3L)), third(edge), 2L))
      ok(executor(ctx), recordTx(edge, fiIn(edge, fiBox(edge, paid = 3L)), third(edge) + grace, 2L))
      no(executor(ctx), recordTx(later, fiIn(later, fiBox(later, paid = 3L)), third(later) + grace, 2L))
      (third(edge) + grace) shouldBe edge.maturity - 721L
      (third(later) + grace) shouldBe later.maturity - 720L
    }
  }

  /** The bond with its quiet zone taken out: a Record it signs and the real bond refuses was refused by the zone. */
  private val unzoned = scala.collection.mutable.Map.empty[Boolean, Contract]
  private def noZone(w: W): Contract = unzoned.getOrElseUpdate(w.erg,
    FiStandardSpecBase.bondVariant(w.ctx, repaymentBytes(w), fi(w.ctx).couponVault, "else !quiet)" -> "else true)"))

  private def quietRefuses(w: W, box: UTXO, h: Int, k: Long): Unit = {
    no(executor(w.ctx), recordTx(w, fiIn(w, box), h, k))
    ok(executor(w.ctx), recordTx(w, fiIn(w, box.setContract(noZone(w))), h, k))
  }

  property("bounds: no bond with the grace above the period exists to be offered (the FI order refuses it), or an earlier coupon's quiet zone could cover a checkpoint") {
    withCtx { ctx =>
      for (kind <- kinds) {
        val base = world(ctx, kind)
        val even = withTerm(base, 11, period.toLong)
        val longer = withTerm(base, 11, period + 1L)
        // Grace equal to the period fills; one block more does not, whatever R7 allows. The FI order itself
        // refuses the longer grace, so the offer, which buys only bonds that order made, keeps no check of its own.
        fillsBy(fillOf(even), fiOffer(even))
        ok(lender(ctx), fiFillTx(fillOf(even)))
        no(lender(ctx), fiFillTx(fillOf(longer)))
        refusedBy(fillOf(longer), fiOffer(longer))
        refusedBy(fillOf(longer), fiOffer(longer, bounds = tightBounds(longer).copy(maxGap = 1000000000L, maxGrace = 1000000000L)))
        // Why. Grace 201: unpaid coupon 0's quiet zone is [d0 + 101, d0 + 201) and covers d1 = d0 + 200.
        // Coupon 0 is not yet missed, so the bond is not liquidatable either, and checkpoint 1 cannot be
        // recorded at its due height.
        val d1 = longer.due(1)
        quietRefuses(longer, fiBox(longer), d1, 1L)
        no(executor(ctx), fiLiquidateTx(longer, fiIn(longer, fiBox(longer)), d1))
        // Grace 200: coupon 0 unpaid at d1 is missed and the bond is liquidatable; paid, checkpoint 1 records at d1.
        ok(executor(ctx), fiLiquidateTx(even, fiIn(even, fiBox(even)), even.due(1)))
        ok(executor(ctx), recordTx(even, fiIn(even, fiBox(even, paid = 1L)), even.due(1), 1L))
      }
      // One coupon: the period is otherwise unused, and must still be at least the grace (the order's rule).
      val one = withTerm(world(ctx), 8, 1L)
      fillsBy(fillOf(withTerm(one, 10, grace.toLong)), fiOffer(withTerm(one, 10, grace.toLong)))
      refusedBy(fillOf(withTerm(one, 10, grace - 1L)), fiOffer(withTerm(one, 10, grace - 1L)))
    }
  }

  property("bounds: why the grace rule. A period of at most grace / 2 joins the quiet zones, shutting Record for a late payer") {
    withCtx { ctx =>
      // Grace 400, period 200, Q = 200. Checkpoint 0 records on [d0, d0 + 200); from d0 + 200 to d0 + 399
      // coupon 0's zone covers checkpoint 1. Paid in its last block, coupon 1's zone [d0 + 400, d0 + 600)
      // starts at once and covers checkpoints 1 and 2, and so on to the last coupon. The FI order refuses it
      // (the boxes below are made by hand).
      val w = withTerm(world(ctx), 11, 400L)
      no(lender(ctx), fiFillTx(fillOf(w)))
      val d0 = w.due(0)
      ok(executor(ctx), recordTx(w, fiIn(w, fiBox(w)), d0 + 199, 0L))
      quietRefuses(w, fiBox(w), d0 + 200, 0L)
      Seq(d0 + 200, d0 + 399).foreach(h => quietRefuses(w, fiBox(w), h, 1L))
      Seq((d0 + 400, 1L), (d0 + 400, 2L), (d0 + 599, 2L)).foreach { case (h, k) => quietRefuses(w, fiBox(w, paid = 1L), h, k) }
      Seq(d0 + 399, d0 + 599).foreach(h => no(executor(ctx), fiLiquidateTx(w, fiIn(w, fiBox(w, paid = if (h < d0 + 400) 0L else 1L)), h)))
      refusedBy(fillOf(w), fiOffer(w))
    }
  }

  property("bounds: maximum grace and maximum cure window; R7 holds exactly 5 values") {
    withCtx { ctx =>
      val w = world(ctx)
      val f = fillOf(w)
      val b = tightBounds(w)
      withClue("grace")(refusedBy(f, fiOffer(w, bounds = b.copy(maxGrace = b.maxGrace - 1L))))
      withClue("cure")(refusedBy(f, fiOffer(w, bounds = b.copy(maxCure = b.maxCure - 1L))))
      fillsBy(f, fiOffer(w, bounds = b.copy(maxGrace = b.maxGrace + 1L, maxCure = b.maxCure + 1L)))
      refusedBy(f, fiOffer(w, r7 = b.values.dropRight(1)))
      refusedBy(f, fiOffer(w, r7 = b.values :+ 0L))
    }
  }

  // ═══ ORDER, DELIVERY, CONTINUATION ════════════════════════════════════════

  property("new bond: maturity window, minimum fill, exact reserve") {
    withCtx { ctx =>
      val w = world(ctx, ErgBond)
      val f = fillOf(w)
      val blocks = w.maturity - f.height
      fillsBy(f, fiOffer(w, OTerms(minBlocks = blocks, maxBlocks = blocks)))
      Seq(OTerms(minBlocks = blocks + 1L), OTerms(maxBlocks = blocks - 1L), OTerms(minFill = priceOf(w) + 1L),
        OTerms(reserve = batchingFee + 1L), OTerms(yieldBps = -1L), OTerms(batcherFee = 99999999L)).foreach { t =>
        no(executor(ctx), offerFillTx(f, fiOffer(w, t), extra = Seq(wallet(ctx, executor(ctx), 9, created(w)))))
      }
    }
  }

  property("isolation: the FI offer fills only the FI order; the ZC offer never fills an FI order") {
    withCtx { ctx =>
      val w = world(ctx, ErgBond)
      // A ZC order whose payload is FI's coupon terms, so the offer reads every index it reads from an
      // FI bond and only the order script differs.
      val zc = flow(ctx, ErgBond, fixedMaturity = w.maturity, extra = w.terms.drop(7))
      val offer = fiOffer(w.copy(maturity = zc.maturity, start = zc.height))
      val in = offerIn(offer, ctx, null, 3)
      val ergOut = batchingFee + minBox + zc.fee + zc.data(1)
      no(executor(ctx), txAt(ctx, zc.height, Seq(zc.orderIn, in),
        Seq(offerDelivery(zc.unit, in.id), zc.bond, zc.principal,
          continuationOf(w, offer, in.id, ergOut, zc.data(1))), executor(ctx).getAddress))
      // The same terms through the FI order fill.
      fillsBy(fillOf(w), fiOffer(w))
      // A ZC offer (its R7..R9 empty: unit delivery) refuses the FI order.
      val zcOffer = UTXO(marketOf(ctx, ErgBond).offer, ergCapital, Seq.empty[Token],
        Seq(bytesValue(hashOf(lenderTree(ctx))), longsValue(offerTerms(ctx, ErgBond, OTerms()): _*),
          bytesValue(alwaysTrue(ctx).hashedValueBytes), longsValue(), textsValue(), bytesValue(Array.empty[Byte])))
      no(executor(ctx), offerFillTx(fillOf(w), zcOffer))
    }
  }

  property("new bond: only the fee, delivery, reserve and price leave, to a continuation that keeps R4 to R7") {
    withCtx { ctx =>
      for (kind <- kinds) {
        val w = world(ctx, kind)
        val f = fillOf(w)
        val offer = fiOffer(w)
        val id = inputAt(offer, ctx, 1).id
        val ergOut = batcherFeeOf(w, offer, priceOf(w)) + minBox + batchingFee + w.terms(1)
        val next = continuationOf(w, offer, id, ergOut, priceOf(w))
        ok(executor(ctx), offerFillTx(f, offer, next = next))
        val keptTokens = if (kind == TokenBond) Seq("tokens" -> next.setTokens(Token(SigUSD, tokenCapital - priceOf(w) - 1L))) else Nil
        (Seq("value" -> next.subValue(1L), "script" -> next.setContract(otherTree(ctx)),
          "R4" -> next.withRegNum(4, bytesValue(hashOf(otherTree(ctx)))),
          "R5" -> next.withRegNum(5, longsValue(offerTerms(ctx, kind, OTerms(minFill = 2L)): _*)),
          "R6" -> next.withRegNum(6, bytesValue(neverTrue(ctx).hashedValueBytes ++ id.getBytes)),
          "R6 tag" -> next.withRegNum(6, bytesValue(alwaysTrue(ctx).hashedValueBytes)),
          "R7" -> next.withRegNum(7, longsValue(tightBounds(w).copy(maxCure = 1000L).values: _*))) ++ keptTokens)
          .foreach { case (name, bad) => withClue(name)(no(executor(ctx), offerFillTx(f, offer, next = bad))) }
        // The unit, with its box minimum and the offer's tag, to the owner only.
        Seq(f.unit.setContract(otherTree(ctx)), f.unit.subValue(1L)).foreach { bad =>
          no(executor(ctx), offerFillTx(f, offer, delivery = bad))
        }
      }
    }
  }

  property("new bond: the policy expression is the one in R6, and it runs") {
    withCtx { ctx =>
      val w = world(ctx, ErgBond)
      val f = fillOf(w)
      no(executor(ctx), offerFillTx(f, fiOffer(w), policy = neverTrue(ctx)))
      no(executor(ctx), offerFillTx(f, fiOffer(w, policy = neverTrue(ctx)), policy = neverTrue(ctx)))
    }
  }

  // ═══ COLLATERAL POLICY ════════════════════════════════════════════════════

  /** A new-bond fill under the real FI policy, with one oracle box created at `priced`. */
  private def policyFill(w: W, floor: Long = 15000L, maxAge: Long = 30L, price: Long = -1L, priced: Int = -1,
                         oracle: Boolean = true, liq: Contract = null): Tx = {
    val f = fillOf(w, liq = liq)
    val policy = fiMarket(w).policy
    offerFillTx(f, fiOffer(w, OTerms(floorBps = floor, maxAge = maxAge), policy = policy), policy = policy,
      data = if (oracle) Seq(this.oracle(w.ctx, if (price < 0L) w.calm else price, if (priced < 0) f.height else priced))
             else Seq.empty)
  }

  property("policy: the covenant ratio is at least the lender's floor, and collateral covers it now") {
    withCtx { ctx =>
      val token = world(ctx, TokenBond)
      val erg = world(ctx, ErgBond)
      for (w <- Seq(token, erg)) {
        ok(executor(ctx), policyFill(w, floor = 15000L))
        // The covenant's 150% is below a 150.01% floor, though collateral covers it (175% at the calm price).
        no(executor(ctx), policyFill(w, floor = 15001L))
      }
      // Cover now at a 150% floor, plus the new bond's 0.1 ERG reserve (F-027) and the 0.5 ERG a Record can take
      // from a bond whose cover moves with the price (F-026). SigUSD bond: 7 ERG against $8 holds 150% and
      // 0.6 ERG up to 0.53333 ERG/USD (with the reserve alone up to 0.575, with neither up to 0.58333).
      ok(executor(ctx), policyFill(token, price = 533333333L))
      no(executor(ctx), policyFill(token, price = 533333334L))
      no(executor(ctx), policyFill(token, price = 575000000L))
      // ERG bond holding SigUSD: 1 ERG and $10 against 4 ERG holds 150% and 0.6 ERG down to 0.56 ERG/USD
      // (with the reserve alone down to 0.51, with neither 0.5).
      ok(executor(ctx), policyFill(erg, price = 560000000L))
      no(executor(ctx), policyFill(erg, price = 559999999L))
      no(executor(ctx), policyFill(erg, price = 510000000L))
    }
  }

  property("policy: the bond's oracle age accepts the lender's; the oracle is present, unique and fresh") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = fillHeight(w)
      ok(executor(ctx), policyFill(w, maxAge = 30L))
      no(executor(ctx), policyFill(w, maxAge = 31L))
      ok(executor(ctx), policyFill(withTerm(w, 14, 31L), maxAge = 31L))
      ok(executor(ctx), policyFill(w, priced = h - 30))
      no(executor(ctx), policyFill(w, priced = h - 31))
      no(executor(ctx), policyFill(w, priced = h + 1))
      no(executor(ctx), policyFill(w, oracle = false))
      Seq(0L, 100001L).foreach(age => no(executor(ctx), policyFill(withTerm(w, 14, 100000L), maxAge = age)))
    }
  }

  property("policy: the bond's liquidation expression must be FI_Standard_Liquidation") {
    withCtx { ctx =>
      val w = world(ctx)
      ok(executor(ctx), policyFill(w))
      no(executor(ctx), policyFill(w, liq = fi(ctx).maturityOnly))
      no(executor(ctx), policyFill(w, liq = alwaysTrue(ctx)))
      // The FI order itself refuses any other expression now, so the policy is shown alone through an order
      // without that pin and an offer pinning that order: there it still refuses both, and takes FI's own.
      val unpinned = FiStandardMarketSpecBase.orderFor(ctx, w.bond, repaymentBytes(w),
        "SELF.R7[Coll[Byte]].get == CONST_LIQUIDATION_HASH &&" -> "CONST_LIQUIDATION_HASH.size >= 0 &&")
      val loose = FiStandardMarketSpecBase.offerFor(ctx, repaymentBytes(w), unpinned, w.bond)
      def looseFill(liq: Contract): Tx = {
        val f = fillOf(w, liq = liq, orderScript = unpinned)
        val policy = fiMarket(w).policy
        offerFillTx(f, fiOffer(w, OTerms(floorBps = 15000L), policy = policy).setContract(loose), policy = policy,
          data = Seq(this.oracle(ctx, w.calm, f.height)))
      }
      ok(executor(ctx), looseFill(fi(ctx).payout))
      no(executor(ctx), looseFill(fi(ctx).maturityOnly))
      no(executor(ctx), looseFill(alwaysTrue(ctx)))
      // ZC's policy reads R9 as 3 Longs: it refuses an FI bond whatever the cover.
      val f = fillOf(w)
      val zcPolicy = marketOf(ctx, TokenBond).policy
      no(executor(ctx), offerFillTx(f, fiOffer(w, OTerms(floorBps = 15000L), policy = zcPolicy), policy = zcPolicy,
        data = Seq(oracle(ctx, w.calm, f.height))))
    }
  }

  // ═══ EXISTING BOND, FROM A SALE ORDER ═════════════════════════════════════

  /** A held FI bond: filled from its order, then `age` blocks later. */
  private case class Held(f: FiFill, fill: SignedTransaction, state: InputUTXO, height: Int) {
    def w: W = f.w
    def origin: Array[Byte] = f.orderIn.bytes
  }

  private def held(ctx: BlockchainContext, kind: Kind, age: Int = 50): Held = {
    val w = world(ctx, kind)
    val f = fillOf(w)
    val fill = ok(lender(ctx), fiFillTx(f))
    Held(f, fill, spendOutput(fill, 1), f.height + age)
  }

  for (kind <- kinds) {
    property(s"$kind existing bond: an offer buys a verified FI bond unit from a sale order, with an exact yield") {
      withCtx { ctx =>
        val l = held(ctx, kind)
        val price = unitPrice(l.w)
        ok(executor(ctx), buyTx(l.w, l.origin, l.state, fiOffer(l.w), price, l.height))
        val cost = if (l.w.erg) price + OTerms().batcherFee else price
        val y = maxYield(l.w.terms(0), cost, l.w.maturity - l.height)
        ok(executor(ctx), buyTx(l.w, l.origin, l.state, fiOffer(l.w, OTerms(yieldBps = y)), price, l.height))
        no(executor(ctx), buyTx(l.w, l.origin, l.state, fiOffer(l.w, OTerms(yieldBps = y + 1L)), price, l.height))
      }
    }
  }

  property("existing bond: after a Pay the state box is the successor and still bought; the bounds read its terms") {
    withCtx { ctx =>
      val l = held(ctx, TokenBond)
      val w = l.w
      val h = w.due(0) - 10
      val paid = spendOutput(ok(borrower(ctx), payTx(w, l.state, h, coupon = couponOut(w, asset = l.f.asset))), 0)
      ok(executor(ctx), buyTx(w, l.origin, paid, fiOffer(w), unitPrice(w), h + 1))
      no(executor(ctx), buyTx(w, l.origin, paid, fiOffer(w, bounds = tightBounds(w).copy(minCount = 4L)), unitPrice(w), h + 1))
      no(executor(ctx), buyTx(w, l.origin, paid, fiOffer(w, bounds = tightBounds(w).copy(maxGrace = grace - 1L)), unitPrice(w), h + 1))
    }
  }

  property("existing bond: the origin must be the FI order that minted the asset, and the state box a live FI bond") {
    withCtx { ctx =>
      val l = held(ctx, ErgBond)
      val w = l.w
      val price = unitPrice(w)
      val offer = fiOffer(w)
      ok(executor(ctx), buyTx(w, l.origin, l.state, offer, price, l.height))
      // Bytes of another box: they do not hash to the asset id.
      no(executor(ctx), buyTx(w, l.state.bytes, l.state, offer, price, l.height))
      // A settled bond: its 2 units sit in the vault.
      val vault = spendOutput(ok(borrower(ctx), fiRepayTx(w, l.state, l.height)), 0)
      no(executor(ctx), buyTx(w, l.origin, vault, offer, price, l.height + 1))
      // That vault box has no R6, so it is refused before the state-script check matters. A repay may write
      // FI-shaped R6..R9 on the vault box (the bond pins only its script, tokens(0), R4 and R5): then only
      // the check that the state box is under CONST_BOND_HASH refuses it, as the offer without that check shows.
      def forgedRegs(b: InputUTXO) = repaid(w, b).registers ++ Seq(longsValue(w.terms: _*), Protocol.tokensValue(Seq.empty),
        bytesValue(fi(ctx).payout.hashedValueBytes), longsValue(bornState(w): _*))
      // The bond now refuses a settlement carrying R6 (round 3, C); the bond without that term writes the box.
      no(borrower(ctx), fiRepayTx(w, l.state, l.height, settle = repaid(w, l.state).setRegs(forgedRegs(l.state): _*)))
      val loose = inputAt(l.state.toUTXO.setContract(FiStandardSpecBase.bondVariant(ctx, repaymentBytes(w), fi(ctx).couponVault,
        " &&\n                    settlement.R6[Coll[Byte]].isDefined == false" -> "")), ctx, 0, "cd" * 32)
      val forged = spendOutput(ok(borrower(ctx), fiRepayTx(w, loose, l.height,
        settle = repaid(w, loose).setRegs(forgedRegs(loose): _*))), 0)
      no(executor(ctx), buyTx(w, l.origin, forged, offer, price, l.height + 1))
      val unchecked = offerVariant(w, "blake2b256(bond.propositionBytes) == CONST_BOND_HASH &&" -> "CONST_BOND_HASH.size >= 0 &&")
      ok(executor(ctx), buyTx(w, l.origin, forged, fiOffer(w).setContract(unchecked), price, l.height + 1))
      // A ZC bond's unit: minted by the ZC order, held in a ZC bond. Its R6 carries FI-shaped terms so
      // only the origin and state scripts differ.
      val zc = flow(ctx, ErgBond, fixedMaturity = w.maturity, extra = w.terms.drop(7))
      val zcFill = accepts(zc.holder, standardFillTx(zc))
      no(executor(ctx), buyTx(w, zc.orderIn.bytes, spendOutput(zcFill, 1), offer, price, l.height))
      // A hand-made asset: minted by a box that is not an order, with a hand-made FI bond holding 2 units.
      val fakeOrigin = inputAt(UTXO(otherTree(ctx), minBox), ctx, 11)
      val fakeState = inputAt(l.state.toUTXO.setTokens(Token(fakeOrigin.id, 2L) +: l.state.tokens.drop(1): _*), ctx, 12)
      no(executor(ctx), buyTx(w, fakeOrigin.bytes, fakeState, offer, price, l.height))
    }
  }

  property("existing bond: the ask convention; whole units only, delivered to the owner") {
    withCtx { ctx =>
      val l = held(ctx, ErgBond)
      val w = l.w
      val price = unitPrice(w)
      val offer = fiOffer(w)
      val ask = saleBox(w, l.f.asset, price)
      val askId = inputAt(ask, ctx, 2).id
      val pay = payoutBox(w, askId, price)
      Seq(pay.setContract(lenderTree(ctx)), pay.withRegNum(4, bytesValue(originOrderId)), pay.withRegNum(5, ErgoValue.of(2L)))
        .foreach(bad => no(executor(ctx), buyTx(w, l.origin, l.state, offer, price, l.height, payout = bad)))
      no(executor(ctx), buyTx(w, l.origin, l.state, offer, price, l.height,
        delivery = UTXO(otherTree(ctx), minBox, Seq(Token(l.f.asset, 1L)))))
      // Another token for sale beside this bond's state box: not the bond's unit, so not bought (no shares).
      val shareId = ErgoId.create("5a" * 32)
      no(executor(ctx), buyTx(w, l.origin, l.state, offer, price, l.height, ask = saleBox(w, shareId, price),
        delivery = UTXO(lenderTree(ctx), minBox, Seq(Token(shareId, 1L)))))
    }
  }

  property("existing bond: the policy judges the state box") {
    withCtx { ctx =>
      val l = held(ctx, TokenBond)
      val w = l.w
      val policy = fiMarket(w).policy
      def buy(price: Long): Tx = buyTx(w, l.origin, l.state, fiOffer(w, OTerms(floorBps = 15000L), policy = policy),
        unitPrice(w), l.height, policy = policy, data = Seq(oracle(ctx, price, l.height)))
      // No reserve on this path, but the 0.5 ERG a Record can take (F-026): 150% and 0.5 ERG hold up to 0.541666
      // ERG/USD (150% alone: 0.58333).
      ok(executor(ctx), buy(541666666L))
      no(executor(ctx), buy(541666667L))
      no(executor(ctx), buy(583333333L))
    }
  }

  // ═══ EXPIRY REFUND AND CANCEL ═════════════════════════════════════════════

  property("refund and cancel: as ZC") {
    withCtx { ctx =>
      val expiry = ctx.getHeight + 50
      for (kind <- kinds) {
        val w = world(ctx, kind)
        val offer = fiOffer(w, OTerms(expiry = expiry))
        val in = inputAt(offer, ctx, 1).withCtxVar(0.toByte, ErgoValue.of(0))
        val refund = tagged(lenderTree(ctx), offer.value - OTerms().batcherFee, in.id, offer.tokens)
        def refundTx(out: UTXO, at: Int): Tx =
          txAt(ctx, at, Seq(in, wallet(ctx, executor(ctx), 9, created(w))), Seq(out), executor(ctx).getAddress)
        ok(executor(ctx), refundTx(refund, expiry))
        no(executor(ctx), refundTx(refund, expiry - 1))
        Seq(refund.subValue(1L), refund.setContract(otherTree(ctx)), refund.withRegNum(4, bytesValue(originOrderId)))
          .foreach(bad => no(executor(ctx), refundTx(bad, expiry)))
        def cancel(by: ErgoProver): Tx = txAt(ctx, fillHeight(w), Seq(inputAt(offer, ctx, 1), wallet(ctx, by, 9, created(w))),
          Seq(UTXO(contractOf(by), offer.value, offer.tokens)), by.getAddress)
        ok(lender(ctx), cancel(lender(ctx)))
        no(executor(ctx), cancel(executor(ctx)))
      }
      // A fill at expiry is refused: the box can only be refunded.
      val w = world(ctx, ErgBond)
      refusedBy(fillOf(w), fiOffer(w, OTerms(expiry = fillHeight(w))))
    }
  }

  // ═══ AUDIT FIXES ══════════════════════════════════════════════════════════

  property("F-001 new bond: the order at INPUTS(0) must be younger than 1051200 blocks (not open to storage rent)") {
    withCtx { ctx =>
      for (kind <- kinds) {
        val base = world(ctx, kind)
        // The order and the lender's box are created at created(base); the fill comes `age` blocks later.
        def aged(age: Int): (W, FiFill) = {
          val h = created(base) + age
          val w = withTerm(maturingAt(startedAt(base, h), h + 2000L), 4, h + 500L)
          (w, fillOf(w, height = h))
        }
        def offer(w: W): UTXO = fiOffer(w, OTerms(expiry = w.start + 5000L))
        val (young, fy) = aged(1051199)
        fillsBy(fy, offer(young))
        val (old, fo) = aged(1051200)
        refusedBy(fo, offer(old))
        // The order fills itself at that age (no rent rule is simulated here): the offer's term refused it.
        ok(lender(ctx), fiFillTx(fo))
        val ageFree = offerVariant(old, "HEIGHT.toLong - order.creationInfo._1.toLong < 1051200L &&" -> "true &&")
        fillsBy(fo, offer(old).setContract(ageFree))
      }
    }
  }

  property("F-001 existing bond: the origin order must be younger than 1051200 blocks at the purchase") {
    withCtx { ctx =>
      val l = held(ctx, ErgBond)
      val w = l.w
      val born = l.f.orderIn.input.getCreationHeight
      // A state box made by hand, live far past the cap (every coupon paid, maturity well ahead), so that only
      // the origin's age differs between the two purchases.
      def stateAt(h: Int): InputUTXO = inputAt(l.state.toUTXO.withRegNum(9,
        longsValue(bornState(w).updated(2, h + 10000L).updated(3, w.terms(8)): _*)), ctx, 0, "cd" * 32)
      def offer(h: Int, c: Contract = null): UTXO = {
        val o = fiOffer(w, OTerms(expiry = h + 5000L), bounds = tightBounds(w).copy(maxGap = 1000000000L))
        if (c == null) o else o.setContract(c)
      }
      // The ask, open until well after the purchase.
      def ask(h: Int): UTXO = saleBox(w, l.f.asset, unitPrice(w)).withRegNum(5, longsValue(unitPrice(w), 1L, 1L, h + 5000L))
      val young = born + 1051199
      val old = born + 1051200
      ok(executor(ctx), buyTx(w, l.origin, stateAt(young), offer(young), unitPrice(w), young, ask = ask(young)))
      no(executor(ctx), buyTx(w, l.origin, stateAt(old), offer(old), unitPrice(w), old, ask = ask(old)))
      val ageFree = offerVariant(w, "HEIGHT.toLong - origin.creationInfo._1.toLong < 1051200L &&" -> "true &&")
      ok(executor(ctx), buyTx(w, l.origin, stateAt(old), offer(old, ageFree), unitPrice(w), old, ask = ask(old)))
    }
  }

  property("F-023 cancel: an owner box 1051200 or more blocks old (open to storage rent) releases nothing; one block younger cancels") {
    withCtx { ctx =>
      val w = world(ctx)
      val offer = fiOffer(w)
      val h = 1200000
      def cancel(o: UTXO, boxAge: Int): Tx = txAt(ctx, h, Seq(inputAt(o, ctx, 1), wallet(ctx, lender(ctx), 9, h - boxAge)),
        Seq(UTXO(lenderTree(ctx), o.value, o.tokens)), lender(ctx).getAddress)
      ok(lender(ctx), cancel(offer, 1051199))
      no(lender(ctx), cancel(offer, 1051200))
      val ageFree = offerVariant(w, "&&\n        b.creationInfo._1 > HEIGHT - 1051200" -> "")
      ok(lender(ctx), cancel(offer.setContract(ageFree), 1051200))
    }
  }

  property("F-014: an offer whose owner hash is its market's FI share mint never fills; the same offer pinning no mint does") {
    withCtx { ctx =>
      for (kind <- kinds) {
        val w = world(ctx, kind)
        val mint = FiStandardMarketSpecBase.mintOf(ctx, repaymentBytes(w))
        val f = fillOf(w)
        val toMint = fiOffer(w).withRegNum(4, bytesValue(mint.hashedPropBytes))
        val delivery = f.unit.setContract(mint)
        no(executor(ctx), offerFillTx(f, toMint, delivery = delivery))
        val mintFree = offerVariant(w, " && ownerHash != CONST_MINT_HASH &&" -> " &&")
        ok(executor(ctx), offerFillTx(f, toMint.setContract(mintFree), delivery = delivery))
        // The other market's mint is not this offer's constant: that owner still fills (ZC's own mints likewise).
        val otherMint = FiStandardMarketSpecBase.mintOf(ctx, if (w.erg) SigUSD.getBytes else Array.emptyByteArray)
        ok(executor(ctx), offerFillTx(f, fiOffer(w).withRegNum(4, bytesValue(otherMint.hashedPropBytes)),
          delivery = f.unit.setContract(otherMint)))
      }
    }
  }

  property("F-008: the delivered unit is stamped within 999 blocks of the fill, new bond or existing") {
    withCtx { ctx =>
      for (kind <- kinds) {
        val base = world(ctx, kind)
        val h = created(base) + 1010
        val w = withTerm(startedAt(base, h), 4, h + 500L)
        val f = fillOf(w, height = h)
        val offer = fiOffer(w)
        fillsBy(f, offer)
        ok(executor(ctx), offerFillTx(f, offer, delivery = f.unit.setCreationHeight(h - 999)))
        // The order's own stamp term also refuses the old unit; the order and offer without theirs sign it.
        no(executor(ctx), offerFillTx(f, offer, delivery = f.unit.setCreationHeight(h - 1000)))
        val noOrderStamp = FiStandardMarketSpecBase.orderFor(ctx, w.bond, repaymentBytes(w), "lenderOut.creationInfo._1 > HEIGHT - 1000 &&" -> "true &&")
        val g = fillOf(w, height = h, orderScript = noOrderStamp)
        val pinsLoose = FiStandardMarketSpecBase.offerFor(ctx, repaymentBytes(w), noOrderStamp, w.bond)
        no(executor(ctx), offerFillTx(g, offer.setContract(pinsLoose), delivery = g.unit.setCreationHeight(h - 1000)))
        val bothLoose = FiStandardMarketSpecBase.offerFor(ctx, repaymentBytes(w), noOrderStamp, w.bond,
          "unit.creationInfo._1 > HEIGHT - 1000 &&" -> "true &&")
        ok(executor(ctx), offerFillTx(g, offer.setContract(bothLoose), delivery = g.unit.setCreationHeight(h - 1000)))
      }
      // Existing bond: the delivery output of a purchase, 1010 blocks after the fill (every coupon paid, so live).
      val l = held(ctx, ErgBond, age = 1010)
      val live = inputAt(l.state.toUTXO.withRegNum(9, longsValue(bornState(l.w).updated(3, l.w.terms(8)): _*)), ctx, 0, "cd" * 32)
      val unitOut = UTXO(lenderTree(ctx), minBox, Seq(Token(l.f.asset, 1L)))
      ok(executor(ctx), buyTx(l.w, l.origin, live, fiOffer(l.w), unitPrice(l.w), l.height,
        delivery = unitOut.setCreationHeight(l.height - 999)))
      no(executor(ctx), buyTx(l.w, l.origin, live, fiOffer(l.w), unitPrice(l.w), l.height,
        delivery = unitOut.setCreationHeight(l.height - 1000)))
    }
  }

  property("F-012: an offer buys only a bond of its own repayment asset; one compiled against the other market's hashes refuses") {
    withCtx { ctx =>
      // A SigUSD offer compiled with the ERG market's order and bond hashes, buying a genuine ERG bond's unit for
      // SigUSD: the yield test compares nanoERG with raw SigUSD, so without the asset term it would buy.
      val l = held(ctx, ErgBond)
      val token = world(ctx, TokenBond)
      val m = mk(ctx).erg
      val mixed = FiStandardMarketSpecBase.offerFor(ctx, SigUSD.getBytes, m.order, fi(ctx).ergBond)
      val mixedFree = FiStandardMarketSpecBase.offerFor(ctx, SigUSD.getBytes, m.order, fi(ctx).ergBond,
        "(if (lendsERG) principalTokens.size == 0" -> "(if (true) true")
      def offer(c: Contract): UTXO = fiOffer(token, bounds = tightBounds(l.w)).setContract(c)
      no(executor(ctx), buyTx(token, l.origin, l.state, offer(mixed), unitPrice(token), l.height))
      ok(executor(ctx), buyTx(token, l.origin, l.state, offer(mixedFree), unitPrice(token), l.height))
      // Each market's own offer still buys its own bond (the honest path).
      ok(executor(ctx), buyTx(l.w, l.origin, l.state, fiOffer(l.w), unitPrice(l.w), l.height))
    }
  }

  property("F-012: the policy refuses an offer whose own tokens are not its repayment asset") {
    withCtx { ctx =>
      for (kind <- kinds) {
        val w = world(ctx, kind)
        val policy = fiMarket(w).policy
        val f = fillOf(w)
        val regs = Seq(bytesValue(hashOf(lenderTree(ctx))), longsValue(offerTerms(ctx, kind, OTerms()): _*))
        def run(tokens: Seq[Token]): Tx = runAsScript(w, policy, f.bond, selfTokens = tokens, selfRegs = regs,
          data = Seq(oracle(ctx, w.calm, fillHeight(w))))
        val own = if (w.erg) Seq.empty[Token] else Seq(Token(SigUSD, tokenCapital))
        ok(executor(ctx), run(own))
        Seq(Seq(Token(collateralToken, 5L)), if (w.erg) Seq(Token(SigUSD, 5L)) else Seq.empty[Token],
          own :+ Token(collateralToken, 5L)).foreach(ts => withClue(s"$kind $ts")(no(executor(ctx), run(ts))))
      }
    }
  }

  // ═══ ROUND 2: BORN IN BREACH (F-026) AND THE RESERVE OUTSIDE COVER (F-027) ════════

  /**
   * F-026 world, ERG market: repayment 1 ERG, principal 0.9 ERG (so 0.9 + the 0.1 ERG fee clears a 0 bps
   * offer), no collateral token, first checkpoint at the start, grace 30, covenant ratio `ratio`, net
   * collateral `collateral` nanoERG. The order names the always-true guard by default (the borrower's choice).
   */
  private def bornInBreach(ctx: BlockchainContext, collateral: Long, ratio: Long = 30000L): W =
    withTerms(world(ctx, ErgBond, collateralErg = collateral, collateral = Seq.empty),
      0 -> 1000000000L, 1 -> 900000000L, 2 -> 1000000000L, 9 -> 0L, 11 -> 30L, 12 -> ratio)

  /** A new-bond fill of `w` from an offer with a 130% floor under `policy`, one fresh oracle box at the fill. */
  private def floorFill(w: W, policy: Contract, guard: Contract = null): (Tx, FiFill, UTXO) = {
    val f = fillOf(w, guard = guard)
    val offer = fiOffer(w, OTerms(floorBps = 13000L), policy = policy)
    (offerFillTx(f, offer, policy = policy, data = Seq(oracle(w.ctx, w.calm, f.height))), f, offer)
  }

  /**
   * The whole F-026 attack on a new bond: the borrower (also the batcher) fills the offer, records a breach
   * at checkpoint 0 in the fill block keeping 0.5 ERG, and liquidates at coupon 0's miss. Returns what the
   * offer paid out and what the lender recovers (the settlement the unit claims).
   */
  private def bornInBreachAttack(w: W, policy: Contract): (Long, Long) = {
    val ctx = w.ctx
    val (fillT, f, offer) = floorFill(w, policy)
    val fill = ok(executor(ctx), fillT)
    val bond = spendOutput(fill, 1)
    val h = f.height
    val rec = ok(executor(ctx), recordTx(w, bond, h, 0L,
      next = recorded(w, bond, h, 0L).subValue(FiStandardSpecBase.RecordBounty)))
    val liq = ok(executor(ctx), fiLiquidateTx(w, spendOutput(rec, 0), w.start + 30))
    val paidOut = offer.value - fill.getOutputsToSpend.get(3).getValue
    val recovered = liq.getOutputsToSpend.get(0).getValue.longValue
    println(s"F-026|new bond|collateral=${w.collateralErg}|ratio=${w.terms(12)}|offer paid out=$paidOut|" +
      s"bounty=${FiStandardSpecBase.RecordBounty}|lender recovers=$recovered|lender net=${recovered + minBox - paidOut}")
    (paidOut, recovered)
  }

  /** The policy with one of the round-2 cover terms taken out: `noOwnRatio` (F-026), `noReserve` (F-027). */
  private val noOwnRatio = " &&\n            collateralValue * 10000L.toBigInt >= debtValue * borrower(12).toBigInt" -> ""
  private val noReserve = "(if (getVar[Int](2).isDefined) takeable else lender(0) + takeable).toBigInt" -> "takeable.toBigInt"
  private val policies = scala.collection.mutable.Map.empty[String, Contract]
  private def policyWithout(w: W, edits: (String, String)*): Contract = policies.getOrElseUpdate(s"${w.erg}|${edits.mkString}",
    FiStandardMarketSpecBase.policyFor(w.ctx, repaymentBytes(w), edits: _*))

  // Before the fix (recorded in the round-2 report) both reproductions signed every step under the real policy:
  // new bond, 1.3 ERG: offer paid out 1.101 ERG, lender recovered 0.8 ERG; 1.4 ERG: 0.9 ERG.
  property("F-026: a bond below its own covenant ratio is refused by the policy; without that term the whole attack signs") {
    withCtx { ctx =>
      // 1.4 ERG covers the 130% floor and the 0.1 ERG reserve, so only the own-ratio term refuses it.
      val w = bornInBreach(ctx, 1400000000L)
      no(executor(ctx), floorFill(w, fiMarket(w).policy)._1)
      // 1.3 ERG (at the floor alone) is refused by both round-2 terms.
      val atFloor = bornInBreach(ctx, 1300000000L)
      no(executor(ctx), floorFill(atFloor, fiMarket(atFloor).policy)._1)
      no(executor(ctx), floorFill(atFloor, policyWithout(atFloor, noOwnRatio))._1)
      no(executor(ctx), floorFill(atFloor, policyWithout(atFloor, noReserve))._1)
      // The policy without the own-ratio term: fill, Record in the fill block, liquidation, and the lender loses.
      val (paidOut, recovered) = bornInBreachAttack(w, policyWithout(w, noOwnRatio))
      recovered should be < paidOut
      // Honest: the ratio at the floor, the standard guard, collateral covering floor x R + reserve: it fills.
      val honest = bornInBreach(ctx, 1400000000L, ratio = 13000L)
      ok(executor(ctx), floorFill(honest, fiMarket(honest).policy, guard = fiMarket(honest).guard)._1)
      // The same honest bond one nanoERG short of the reserve is refused.
      val short = bornInBreach(ctx, 1399999999L, ratio = 13000L)
      no(executor(ctx), floorFill(short, fiMarket(short).policy, guard = fiMarket(short).guard)._1)
    }
  }

  property("F-026, existing bond: the offer refuses a unit whose bond is below its own ratio; without the term it buys") {
    withCtx { ctx =>
      val w = bornInBreach(ctx, 1400000000L)
      val f = fillOf(w)
      val state = spendOutput(ok(lender(ctx), fiFillTx(f)), 1)
      val h = f.height + 1
      def buy(policy: Contract): Tx = buyTx(w, f.orderIn.bytes, state, fiOffer(w, OTerms(floorBps = 13000L), policy = policy),
        850000000L, h, policy = policy, data = Seq(oracle(ctx, w.calm, h)))
      no(executor(ctx), buy(fiMarket(w).policy))
      ok(executor(ctx), buy(policyWithout(w, noOwnRatio)))
      // The reserve is not counted on this path: a bond at its own 130% ratio and the 130% floor, no reserve
      // above it, is bought.
      val atFloor = bornInBreach(ctx, 1300000000L, ratio = 13000L)
      val g = fillOf(atFloor)
      val st = spendOutput(ok(lender(ctx), fiFillTx(g)), 1)
      val policy = fiMarket(atFloor).policy
      ok(executor(ctx), buyTx(atFloor, g.orderIn.bytes, st, fiOffer(atFloor, OTerms(floorBps = 13000L), policy = policy),
        850000000L, g.height + 1, policy = policy, data = Seq(oracle(ctx, atFloor.calm, g.height + 1))))
    }
  }

  /** F-027 world, ERG market: price 0.001 ERG, repayment 0.101 ERG (price + the 0.1 ERG fee), ratio 130%. */
  private def smallBond(ctx: BlockchainContext, collateral: Long): W =
    withTerms(world(ctx, ErgBond, collateralErg = collateral, collateral = Seq.empty),
      0 -> 101000000L, 1 -> 1000000L, 2 -> 101000000L, 12 -> 13000L)

  /** The F-027 attack: one actor fills (batcher), defaults at coupon 0's miss and liquidates, keeping the reserve. */
  private def reserveAttack(w: W, policy: Contract): Long = {
    val ctx = w.ctx
    val (fillT, _, offer) = floorFill(w, policy)
    val fill = ok(executor(ctx), fillT)
    val liq = ok(executor(ctx), fiLiquidateTx(w, spendOutput(fill, 1), w.due(0) + grace))
    val paidOut = offer.value - fill.getOutputsToSpend.get(3).getValue
    val recovered = liq.getOutputsToSpend.get(0).getValue.longValue
    val net = recovered + minBox - paidOut
    println(s"F-027|collateral=${w.collateralErg}|offer paid out=$paidOut|reserve kept by the liquidator=${r9(spendOutput(fill, 1))(0)}|" +
      s"lender recovers=$recovered (+ the delivery box $minBox)|lender net=$net")
    net
  }

  // Before the fix (recorded in the round-2 report) this signed under the real policy: offer paid out 0.202 ERG,
  // the liquidator kept the 0.1 ERG reserve, lender net -0.0697 ERG.
  property("F-027: a new bond whose net collateral covers the floor but not the reserve is refused; without the term the loss signs") {
    withCtx { ctx =>
      val w = smallBond(ctx, 131300000L)
      no(executor(ctx), floorFill(w, fiMarket(w).policy)._1)
      reserveAttack(w, policyWithout(w, noReserve)) should be < 0L
      // Honest: collateral covering floor x R + reserve, ratio = floor, the standard guard: it fills; one
      // nanoERG less is refused.
      val honest = smallBond(ctx, 231300000L)
      ok(executor(ctx), floorFill(honest, fiMarket(honest).policy, guard = fiMarket(honest).guard)._1)
      no(executor(ctx), floorFill(smallBond(ctx, 231299999L), fiMarket(w).policy, guard = fiMarket(w).guard)._1)
    }
  }

  /** `u` with a 1000-byte R5 after its R4: the padding the payout rules refuse. */
  private def padR5(u: UTXO): UTXO = u.setRegs(u.registers.take(1) :+ bytesValue(Array.fill(1000)(7.toByte)): _*)

  property("D offer refund: the expiry refund is stamped within 999 blocks of HEIGHT and carries no R5") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = ctx.getHeight + 1010
      val offer = fiOffer(w, OTerms(expiry = ctx.getHeight + 50L))
      def expire(c: Contract, f: UTXO => UTXO): Tx = {
        val in = inputAt(if (c == null) offer else offer.setContract(c), ctx, 1).withCtxVar(0.toByte, ErgoValue.of(0))
        txAt(ctx, h, Seq(in, wallet(ctx, executor(ctx), 9, created(w))),
          Seq(f(tagged(lenderTree(ctx), offer.value - OTerms().batcherFee, in.id, offer.tokens))), executor(ctx).getAddress)
      }
      ok(executor(ctx), expire(null, _.setCreationHeight(h - 999)))
      no(executor(ctx), expire(null, _.setCreationHeight(h - 1000)))
      no(executor(ctx), expire(null, padR5))
      ok(executor(ctx), expire(offerVariant(w, "refund.creationInfo._1 > HEIGHT - 1000 &&" -> "true &&"), _.setCreationHeight(h - 1000)))
      ok(executor(ctx), expire(offerVariant(w, "refund.R5[Coll[Byte]].isDefined == false &&" -> "true &&"), padR5))
    }
  }

  property("D continuation: a fill's continuation is stamped within 999 blocks of HEIGHT and carries no R8") {
    withCtx { ctx =>
      for (kind <- kinds) {
        val base = world(ctx, kind)
        val h = created(base) + 1010
        val w = withTerm(startedAt(base, h), 4, h + 500L)
        val f = fillOf(w, height = h)
        def fill(c: Contract, g: UTXO => UTXO): Tx = {
          val offer = if (c == null) fiOffer(w) else fiOffer(w).setContract(c)
          val id = inputAt(offer, ctx, 1).id
          val ergOut = batcherFeeOf(w, offer, priceOf(w)) + minBox + batchingFee + w.terms(1)
          offerFillTx(f, offer, next = g(continuationOf(w, offer, id, ergOut, priceOf(w))))
        }
        val padR8: UTXO => UTXO = u => u.setRegs(u.registers :+ bytesValue(Array.fill(1000)(7.toByte)): _*)
        ok(executor(ctx), fill(null, _.setCreationHeight(h - 999)))
        no(executor(ctx), fill(null, _.setCreationHeight(h - 1000)))
        no(executor(ctx), fill(null, padR8))
        ok(executor(ctx), fill(offerVariant(w, "next.creationInfo._1 > HEIGHT - 1000 &&" -> "true &&"), _.setCreationHeight(h - 1000)))
        ok(executor(ctx), fill(offerVariant(w, "next.R8[Coll[Byte]].isDefined == false &&" -> "true &&"), padR8))
      }
    }
  }
}
