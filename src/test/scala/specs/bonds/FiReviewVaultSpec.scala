package org.lithosprotocol.sigmafi
package specs.bonds

import ksingh.sigmabonds.client.ScriptGenerator
import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import scala.collection.JavaConverters._

/**
 * Adversarial review of FI_Standard_CouponVault and FI_Standard_ERG_SigUSD_Covenant (review-only file).
 * "LEAK" properties were thefts the old collect rule accepted; each now asserts that the real contract
 * REFUSES it, beside the nearest legitimate collect. "COLLECT" properties (formerly PROPOSED, run on an
 * inline candidate) run the pooled rule now in the contract. "HOLDS" properties are attacks that failed.
 */
class FiReviewVaultSpec extends AnyPropSpec with FiStandardSpecBase {
  import BondSpecBase._
  import ZcStandardSpecBase._

  private val shareId = ErgoId.create("5a" * 32)
  private val N = 1000L
  private val pullFee = 2000000L

  private def sigUsdIn(s: SignedTransaction, tree: Contract): Long =
    s.getOutputsToSpend.asScala.filter(_.getErgoTree == tree.ergoTree)
      .flatMap(_.getTokens.asScala).filter(_.getId == SigUSD).map(_.getValue.longValue).sum

  private def ergIn(s: SignedTransaction, tree: Contract): Long =
    s.getOutputsToSpend.asScala.filter(_.getErgoTree == tree.ergoTree).map(_.getValue.longValue).sum

  private def exec(ctx: BlockchainContext): Contract = contractOf(executor(ctx))

  /** Coupon vault input at an arbitrary contract (current or candidate). */
  private def cv(w: W, created: Int, index: Int, c: Contract = null): InputUTXO = {
    val u = couponOut(w).setCreationHeight(created)
    inputAt(if (c == null) u else u.setContract(c), w.ctx, index, "ab" * 32)
  }

  private def fracVault(ctx: BlockchainContext, created: Int, value: Long = minBox + pullFee): InputUTXO =
    inputAt(UTXO(scripts(ctx).fractionalVault, value, Seq(Token(shareId, N + 1L), Token(bondAsset, 1L)),
      Seq(bytesValue(bondAsset.getBytes), longsValue(N, N, pullFee)), Some(created)), ctx, 30)

  private def bondVaultBox(ctx: BlockchainContext, created: Int): InputUTXO =
    inputAt(UTXO(scripts(ctx).vault, 5L * minBox, Seq(Token(bondAsset, 2L), Token(SigUSD, 800L)),
      Seq(bytesValue(ErgoId.create("77" * 32).getBytes), ErgoValue.of(0.toByte)),
      Some(created)), ctx, 31)

  private def expiredAsk(ctx: BlockchainContext, h: Int): InputUTXO =
    inputAt(UTXO(exchange(ctx).token.saleOrder, 3000000L, Seq(Token(bondAsset, 1L)),
      Seq(bytesValue(hashOf(lenderTree(ctx))), longsValue(1L, 1L, 1L, (h - 1).toLong)), Some(h - 100)), ctx, 9)

  // ═══ LEAKS, NOW REFUSED BY THE REAL CONTRACT ═════════════════════════════════
  // Each was accepted under the old collect rule (any spend holding 1 or 3 units took every coupon).
  // Each now asserts the refusal, beside the nearest legitimate collect, which still signs.

  property("LEAK-A (closed): a stranger refreshing an old fractional vault that holds the unit takes no coupon") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = ctx.getHeight + 600000
      val fv = fracVault(ctx, ctx.getHeight).withCtxVar(9.toByte, int(0))
      val v0 = cv(w, h - 50, 1)
      val v1 = cv(w, h - 40, 2)
      val next = fv.toUTXO.setCreationHeight(h)
      no(executor(ctx), txAt(ctx, h, Seq(fv, v0, v1, wallet(ctx, executor(ctx), 7, h - 60)), Seq(next),
        executor(ctx).getAddress))
      // The refresh alone still signs; the same two coupons collected by a key-held unit sign.
      ok(executor(ctx), txAt(ctx, h, Seq(fv, wallet(ctx, executor(ctx), 7, h - 60)), Seq(next), executor(ctx).getAddress))
      ok(lender(ctx), txAt(ctx, h, Seq(v0, v1, unit(ctx, h - 60)),
        Seq(UTXO(lenderTree(ctx), minBox, Seq(Token(bondAsset, 1L)))), lender(ctx).getAddress))
    }
  }

  property("LEAK-B (closed): whoever runs a fractional vault's pull takes no coupon; 3 in, 0 out at a contract home") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = ctx.getHeight + 100
      val fv = fracVault(ctx, ctx.getHeight).withCtxVar(0.toByte, int(0))
      val bv = bondVaultBox(ctx, ctx.getHeight)
      val cont = UTXO(scripts(ctx).fractionalVault, fv.value + bv.value - pullFee,
        Seq(Token(shareId, N + 1L), Token(SigUSD, 800L)),
        Seq(bytesValue(bondAsset.getBytes), longsValue(N, N, pullFee), ErgoValue.of(0.toByte)))
      val v0 = cv(w, h - 50, 1)
      no(executor(ctx), txAt(ctx, h, Seq(fv, bv, v0, wallet(ctx, executor(ctx), 7, h - 60)),
        Seq(cont), executor(ctx).getAddress, burn = Seq(Token(bondAsset, 3L))))
      // The pull alone signs; a key-held unit claiming the settlement vault takes the coupon with it.
      ok(executor(ctx), txAt(ctx, h, Seq(fv, bv, wallet(ctx, executor(ctx), 7, h - 60)),
        Seq(cont), executor(ctx).getAddress, burn = Seq(Token(bondAsset, 3L))))
      ok(lender(ctx), txAt(ctx, h, Seq(bv, v0, unit(ctx, h - 60)), Seq(UTXO(lenderTree(ctx), minBox)),
        lender(ctx).getAddress, burn = Seq(Token(bondAsset, 3L))))
    }
  }

  property("LEAK-C (closed): anyone running a fractional mint request (unit in FractionalMint) takes no coupon") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = ctx.getHeight + 100
      val meta = Seq("S", "shares", "0")
      val req = inputAt(UTXO(scripts(ctx).fractionalMint, minBox + pullFee + minBox + 2000000L,
        Seq(Token(bondAsset, 1L)),
        eip4() ++ Seq(bytesValue(hashOf(lenderTree(ctx))), longsValue(N, pullFee, 2000000L), textsValue(meta: _*)),
        Some(h - 100)), ctx, 40)
      val sid = req.id
      val shares = UTXO(lenderTree(ctx), minBox, Seq(Token(sid, N)), meta.map(utf8))
      val vault = UTXO(scripts(ctx).fractionalVault, minBox + pullFee, Seq(Token(sid, N + 1L), Token(bondAsset, 1L)),
        Seq(bytesValue(bondAsset.getBytes), longsValue(N, N, pullFee)))
      val v0 = cv(w, h - 50, 1)
      no(executor(ctx), txAt(ctx, h, Seq(req, v0, wallet(ctx, executor(ctx), 7, h - 60)),
        Seq(shares, vault), executor(ctx).getAddress))
      // The mint alone signs; the holder collects with the unit in a key box, before the deposit.
      ok(executor(ctx), txAt(ctx, h, Seq(req, wallet(ctx, executor(ctx), 7, h - 60)),
        Seq(shares, vault), executor(ctx).getAddress))
      ok(lender(ctx), txAt(ctx, h, Seq(v0, unit(ctx, h - 60)),
        Seq(UTXO(lenderTree(ctx), minBox, Seq(Token(bondAsset, 1L)))), lender(ctx).getAddress))
    }
  }

  property("LEAK-D (closed): 3 in / 3 out from two anyone-paths (settlement-vault refresh + expired sale refund) takes no coupon") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = ctx.getHeight + 600000
      val bv = bondVaultBox(ctx, ctx.getHeight).withCtxVar(9.toByte, int(0))
      val ask = expiredAsk(ctx, h).withCtxVar(0.toByte, int(1))
      val v0 = cv(w, h - 50, 1)
      val bvNext = bv.toUTXO.setCreationHeight(h)
      val refund = UTXO(lenderTree(ctx), minBox, Seq(Token(bondAsset, 1L)), Seq(bytesValue(ask.id.getBytes)))
      no(executor(ctx), txAt(ctx, h, Seq(bv, ask, v0, wallet(ctx, executor(ctx), 7, h - 60)), Seq(bvNext, refund),
        executor(ctx).getAddress))
      ok(executor(ctx), txAt(ctx, h, Seq(bv, ask, wallet(ctx, executor(ctx), 7, h - 60)), Seq(bvNext, refund),
        executor(ctx).getAddress))
      // Controls: the settlement vault alone (refresh, 2 in / 2 out) beside v0 is refused BY THE COUPON
      // VAULT (the vault's own refresh path accepts); with the lender's own key-held unit added, accepted.
      no(executor(ctx), txAt(ctx, h, Seq(bv, v0, wallet(ctx, executor(ctx), 7, h - 60)), Seq(bvNext), executor(ctx).getAddress))
      ok(executor(ctx), txAt(ctx, h, Seq(bv, wallet(ctx, executor(ctx), 7, h - 60)), Seq(bvNext), executor(ctx).getAddress))
      ok(lender(ctx), txAt(ctx, h, Seq(bv, v0, unit(ctx, h - 60)),
        Seq(bvNext, UTXO(lenderTree(ctx), minBox, Seq(Token(bondAsset, 1L)))), lender(ctx).getAddress))
    }
  }

  property("LEAK-E (closed): anyone liquidating an FI bond beside an expired sale refund takes no coupon (bond 2 + ask 1)") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = w.due(0) + grace
      val b = vars(fiIn(w, fiBox(w)), Seq(0 -> liqVar(fi(ctx).payout), 1 -> int(0)))
      val ask = expiredAsk(ctx, h).withCtxVar(0.toByte, int(1))
      val refund = UTXO(lenderTree(ctx), minBox, Seq(Token(bondAsset, 1L)), Seq(bytesValue(ask.id.getBytes)))
      val v0 = cv(w, h - 50, 1)
      no(executor(ctx), txAt(ctx, h, Seq(b, ask, v0, wallet(ctx, executor(ctx), 7, h - 60)),
        Seq(proceeds(w, b), refund), executor(ctx).getAddress))
      ok(executor(ctx), txAt(ctx, h, Seq(b, ask, wallet(ctx, executor(ctx), 7, h - 60)),
        Seq(proceeds(w, b), refund), executor(ctx).getAddress))
      ok(lender(ctx), txAt(ctx, h, Seq(v0, unit(ctx, h - 60)),
        Seq(UTXO(lenderTree(ctx), minBox, Seq(Token(bondAsset, 1L)))), lender(ctx).getAddress))
    }
  }

  property("TQ-1: suite's 'settlement vault alone' rejection is the BondVault's own refusal, not the coupon vault's") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = w.due(1) - 10
      val vault = spendOutput(ok(executor(ctx), fiLiquidateTx(w, fiIn(w, fiBox(w)), w.due(0) + grace)), 0)
      // Same shape as FiStandardBondSpec's old line 927 with NO coupon vault at all: still refused.
      // (That line is now replaced by the settlement vault's refresh, which the vault itself accepts.)
      no(lender(ctx), txAt(ctx, h, Seq(vault, wallet(ctx, lender(ctx), 8, h)),
        Seq(UTXO(lenderTree(ctx), minBox, vault.tokens)), lender(ctx).getAddress))
    }
  }

  // ═══ THE POOLED COLLECT RULE, ON THE REAL CONTRACT ══════════════════════════
  // Formerly PROPOSED-1..4, run against an inline candidate; the candidate is now FI_Standard_CouponVault.

  property("COLLECT-1: key holder collects several coupons, kept unit or burned at the vault claim; 2 in total refused") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = ctx.getHeight + 100
      val unitIn = unit(ctx, h - 60)
      ok(lender(ctx), txAt(ctx, h, Seq(cv(w, h - 50, 1), cv(w, h - 50, 2), unitIn),
        Seq(UTXO(lenderTree(ctx), minBox, Seq(Token(bondAsset, 1L)))), lender(ctx).getAddress))
      val three = ok(lender(ctx), txAt(ctx, h, Seq(cv(w, h - 50, 1), cv(w, h - 50, 2), cv(w, h - 50, 3), unitIn),
        Seq(UTXO(lenderTree(ctx), minBox, Seq(Token(bondAsset, 1L)))), lender(ctx).getAddress))
      println(s"FICOST|collect TokenBond, 3 coupons, key-held unit=${three.getCost}")
      val bv = bondVaultBox(ctx, ctx.getHeight)
      ok(lender(ctx), txAt(ctx, h, Seq(bv, cv(w, h - 50, 1), unitIn), Seq(UTXO(lenderTree(ctx), minBox)),
        lender(ctx).getAddress, burn = Seq(Token(bondAsset, 3L))))
      no(lender(ctx), txAt(ctx, h, Seq(cv(w, h - 50, 1), unit(ctx, h - 60, amount = 2L)),
        Seq(UTXO(lenderTree(ctx), minBox, Seq(Token(bondAsset, 2L)))), lender(ctx).getAddress))
    }
  }

  property("COLLECT-2: LEAK-A (token coupon), LEAK-B and a stranger's sale refund are refused; the refund alone signs") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = ctx.getHeight + 600000
      val fvR = fracVault(ctx, ctx.getHeight).withCtxVar(9.toByte, int(0))
      no(executor(ctx), txAt(ctx, h, Seq(fvR, cv(w, h - 50, 1), wallet(ctx, executor(ctx), 7, h - 60)),
        Seq(fvR.toUTXO.setCreationHeight(h)), executor(ctx).getAddress))
      // pull: 3 -> 0 at a contract home is refused (the coupon then has no collector)
      val h2 = ctx.getHeight + 100
      val fvP = fracVault(ctx, ctx.getHeight).withCtxVar(0.toByte, int(0))
      val bv = bondVaultBox(ctx, ctx.getHeight)
      val cont = UTXO(scripts(ctx).fractionalVault, fvP.value + bv.value - pullFee,
        Seq(Token(shareId, N + 1L), Token(SigUSD, 800L)),
        Seq(bytesValue(bondAsset.getBytes), longsValue(N, N, pullFee), ErgoValue.of(0.toByte)))
      no(executor(ctx), txAt(ctx, h2, Seq(fvP, bv, cv(w, h2 - 50, 1), wallet(ctx, executor(ctx), 7, h2 - 60)),
        Seq(cont), executor(ctx).getAddress, burn = Seq(Token(bondAsset, 3L))))
      // sale refund by a stranger
      val ask = expiredAsk(ctx, h2).withCtxVar(0.toByte, int(0))
      val refund = UTXO(lenderTree(ctx), minBox, Seq(Token(bondAsset, 1L)), Seq(bytesValue(ask.id.getBytes)))
      no(executor(ctx), txAt(ctx, h2, Seq(ask, cv(w, h2 - 50, 1), wallet(ctx, executor(ctx), 7, h2 - 60)),
        Seq(refund), executor(ctx).getAddress))
      ok(executor(ctx), txAt(ctx, h2, Seq(ask, wallet(ctx, executor(ctx), 7, h2 - 60)), Seq(refund), executor(ctx).getAddress))
    }
  }

  property("COLLECT-3: ERG coupon on a fractional refresh is accepted only when it lands in the vault's successor") {
    withCtx { ctx =>
      val w = world(ctx, ErgBond)
      val h = ctx.getHeight + 600000
      val fv = fracVault(ctx, ctx.getHeight).withCtxVar(9.toByte, int(0))
      val v0 = cv(w, h - 50, 1)
      val next = fv.toUTXO.setCreationHeight(h)
      no(executor(ctx), txAt(ctx, h, Seq(fv, v0, wallet(ctx, executor(ctx), 7, h - 60)), Seq(next), executor(ctx).getAddress))
      // The refresher may keep the pool's cut, 1/200 of its nanoERG, and not one nanoERG more.
      no(executor(ctx), txAt(ctx, h, Seq(fv, v0, wallet(ctx, executor(ctx), 7, h - 60)), Seq(next.addValue(v0.value - cut(v0.value) - 1L)),
        executor(ctx).getAddress))
      ok(executor(ctx), txAt(ctx, h, Seq(fv, v0, wallet(ctx, executor(ctx), 7, h - 60)), Seq(next.addValue(v0.value - cut(v0.value))),
        executor(ctx).getAddress))
      ok(executor(ctx), txAt(ctx, h, Seq(fv, v0, wallet(ctx, executor(ctx), 7, h - 60)), Seq(next.addValue(v0.value)),
        executor(ctx).getAddress))
    }
  }

  property("COLLECT-4: one payout cannot satisfy two coupon vaults at a contract home; a payout holding both signs") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = ctx.getHeight + 100
      // Worst-case contract home: an anyone-can-spend box holding the unit.
      val home = inputAt(UTXO(Contract.SIGMA_TRUE, Parameters.OneErg, Seq(Token(bondAsset, 1L)), Seq.empty, Some(h - 60)), ctx, 33)
      def payout(usd: Long, erg: Long) = UTXO(Contract.SIGMA_TRUE, erg, Seq(Token(bondAsset, 1L), Token(SigUSD, usd)))
      val ins = Seq(home, cv(w, h - 50, 1), cv(w, h - 50, 2), wallet(ctx, executor(ctx), 7, h - 60))
      // The payout carries one coupon, the builder's change the other: refused.
      no(executor(ctx), txAt(ctx, h, ins, Seq(payout(w.coupon, Parameters.OneErg + 2L * minBox)), executor(ctx).getAddress))
      // Both coupons, but one nanoERG past the cut (1/200 of the two pools' nanoERG) short: refused; at the cut, signs.
      no(executor(ctx), txAt(ctx, h, ins, Seq(payout(2L * w.coupon, Parameters.OneErg + 2L * minBox - cut(2L * minBox) - 1L)),
        executor(ctx).getAddress))
      ok(executor(ctx), txAt(ctx, h, ins, Seq(payout(2L * w.coupon, Parameters.OneErg + 2L * minBox - cut(2L * minBox))),
        executor(ctx).getAddress))
      val two = ok(executor(ctx), txAt(ctx, h, ins, Seq(payout(2L * w.coupon, Parameters.OneErg + 2L * minBox)), executor(ctx).getAddress))
      sigUsdIn(two, exec(ctx)) shouldBe 0L
      // Every coupon of the bond (3) at one contract home: the largest collect built here.
      val ins3 = ins.take(3) ++ Seq(cv(w, h - 50, 3), ins(3))
      val three = ok(executor(ctx), txAt(ctx, h, ins3, Seq(payout(3L * w.coupon, Parameters.OneErg + 3L * minBox)),
        executor(ctx).getAddress))
      println(s"FICOST|collect TokenBond, contract home, 2 coupons=${two.getCost}|3 coupons=${three.getCost}")
    }
  }

  // ═══ ATTACKS THAT FAILED ═══════════════════════════════════════════════════

  property("HOLDS: a pool box carrying a borrower hash and a release height in R5 and R6 opens to no borrower at any height") {
    withCtx { ctx =>
      val w = world(ctx)
      val c = ctx.getHeight + 100
      // A box at the pool script made by hand with the borrower's script hash and a past height after R4: no
      // rule reads them, so the borrower is refused by the pool alone, early, late and far into the Int range.
      val named = inputAt(couponOut(w).setRegs(poolRegs() ++ Seq(bytesValue(hashOf(borrowerTree(ctx))), ErgoValue.of(0L)): _*)
        .setCreationHeight(c), ctx, 1, "ab" * 32)
      (Seq(c) ++ LongAfter.map(w.maturity.toInt + _) :+ Int.MaxValue / 2).foreach { h =>
        withClue(s"height $h: ")(poolRefuses(borrower(ctx), w, named)(q => takeTx(w, q, h)))
      }
      // The holder of the unit collects it.
      ok(lender(ctx), collectTx(w, Seq(named), Seq(unit(ctx, c)), c))
    }
  }

  private def cureIn(w: W, h: Int): InputUTXO = fiIn(w, fiBox(w, last = 0L, deadline = h + 50L))

  property("HOLDS: covenant bounds -- ratio 0, maxAge 0 / 100001, repayment 0 fail both questions; edges pass") {
    withCtx { ctx =>
      val base = world(ctx)
      val h = base.due(0) + 5
      def t(i: Int, v: Long) = base.copy(terms = base.terms.updated(i, v))
      // ratio 0: no Record even far below
      no(executor(ctx), recordTx(t(12, 0L), fiIn(t(12, 0L), fiBox(t(12, 0L))), h, 0L))
      no(borrower(ctx), cureTx(t(12, 0L), cureIn(t(12, 0L), h), h))
      Seq(0L, 100001L).foreach { a =>
        no(executor(ctx), recordTx(t(14, a), fiIn(t(14, a), fiBox(t(14, a))), h, 0L, priced = h))
        no(borrower(ctx), cureTx(t(14, a), cureIn(t(14, a), h), h, priced = h))
      }
      Seq(1L, 100000L).foreach { a =>
        ok(executor(ctx), recordTx(t(14, a), fiIn(t(14, a), fiBox(t(14, a))), h, 0L, priced = h - 1))
        ok(borrower(ctx), cureTx(t(14, a), cureIn(t(14, a), h), h, priced = h - 1))
      }
      // repayment 0 would make Cure trivially "not below"; the guard refuses it. Repayment 1 cures.
      no(borrower(ctx), cureTx(t(0, 0L), cureIn(t(0, 0L), h), h))
      ok(borrower(ctx), cureTx(t(0, 1L), cureIn(t(0, 1L), h), h))
      // Cure freshness edge the suite tests only on the refused side
      ok(borrower(ctx), cureTx(base, cureIn(base, h), h, priced = h - maxAge))
      no(borrower(ctx), cureTx(base, cureIn(base, h), h, priced = h + 1))
      no(borrower(ctx), cureTx(base, cureIn(base, h), h,
        data = Seq(oracle(ctx, base.slump, h - 5), oracle(ctx, base.slump, h - 5, index = 22))))
      no(borrower(ctx), cureTx(base, cureIn(base, h), h, data = Seq(oracle(ctx, base.slump, h - 5, amount = 2L))))
    }
  }

  property("HOLDS: extreme price Long.MaxValue does not overflow (BigInt); ERG bond reads covered, SigUSD bond below") {
    withCtx { ctx =>
      val tw = world(ctx)
      val ew = world(ctx, ErgBond)
      val h = tw.due(0) + 5
      ok(executor(ctx), recordTx(tw, fiIn(tw, fiBox(tw)), h, 0L, price = Long.MaxValue))
      no(executor(ctx), recordTx(ew, fiIn(ew, fiBox(ew)), h, 0L, price = Long.MaxValue))
      ok(borrower(ctx), cureTx(ew, cureIn(ew, h), h, price = Long.MaxValue))
      no(borrower(ctx), cureTx(tw, cureIn(tw, h), h, price = Long.MaxValue))
      // ratio Long.MaxValue
      val big = tw.copy(terms = tw.terms.updated(12, Long.MaxValue))
      ok(executor(ctx), recordTx(big, fiIn(big, fiBox(big)), h, 0L, price = tw.calm))
    }
  }

  property("HOLDS: steering -- Record with var 7 also set, or Cure with var 6 set, is refused by the bond") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = w.due(0) + 5
      no(executor(ctx), recordTx(w, fiIn(w, fiBox(w)), h, 0L, vs = Seq(6 -> int(0), 7 -> int(0), 8 -> covVar(w.covenant))))
      no(borrower(ctx), cureTx(w, cureIn(w, h), h, vs = Seq(6 -> int(0), 7 -> int(0), 8 -> covVar(w.covenant))))
      // Cure with var 6 of another type (Long): does the covenant see Record? (bond reads getVar[Int](6))
      val r = scala.util.Try(ok(borrower(ctx), cureTx(w, cureIn(w, h), h,
        vs = Seq(6 -> ErgoValue.of(0L), 7 -> int(0), 8 -> covVar(w.covenant)))))
      println(s"FIREVIEW|cure with var6:Long accepted=${r.isSuccess}")
    }
  }

  property("TQ-2: a covenant mutant that measures the Record SUCCESSOR instead of SELF passes the suite's Record shapes") {
    withCtx { ctx =>
      val src = ScriptGenerator.mkScript(FiStandardSpecBase.CovenantPath)
        .replace("below(SELF)", "below(OUTPUTS(getVar[Int](6).get))")
      val asset = sigma.Colls.fromArray(SigUSD.getBytes)
      val mutCov = Contract.fromErgoContract(ctx.compileContract(
        ConstantsBuilder.create().item("CONST_REPAYMENT_TOKEN", asset).build(), src))
      val mutBond = Contract.fromErgoContract(ctx.compileContract(ConstantsBuilder.create()
        .item("CONST_VAULT_HASH", sigma.Colls.fromArray(scripts(ctx).vault.hashedPropBytes))
        .item("CONST_REPAYMENT_TOKEN", asset)
        .item("CONST_COUPON_VAULT_HASH", sigma.Colls.fromArray(fi(ctx).couponVault.hashedPropBytes))
        .item("CONST_COVENANT_HASH", sigma.Colls.fromArray(mutCov.hashedValueBytes))
        .item("CONST_RECORD_BOUNTY", FiStandardSpecBase.RecordBounty).build(),
        ScriptGenerator.mkScript(FiStandardSpecBase.BondPath)))
      val w = world(ctx)
      val h = w.due(0) + 5
      val b = fiIn(w, fiBox(w).setContract(mutBond))
      val vs = Seq(6 -> int(0), 8 -> covVar(mutCov))
      ok(executor(ctx), recordTx(w, b, h, 0L, vs = vs))
      ok(executor(ctx), recordTx(w, b, h, 0L, vs = vs, next = recorded(w, b, h, 0L).addValue(1L)))
      no(executor(ctx), recordTx(w, b, h, 0L, vs = vs, price = w.calm))
      // The distinguishing case the suite lacks: the real covenant records a breach on a below SELF
      // even when the recorder tops up the successor; the mutant refuses.
      val real = fiIn(w, fiBox(w))
      ok(executor(ctx), recordTx(w, real, h, 0L, next = recorded(w, real, h, 0L).addValue(5L * Parameters.OneErg)))
      no(executor(ctx), recordTx(w, b, h, 0L, vs = vs, next = recorded(w, b, h, 0L).addValue(5L * Parameters.OneErg)))
    }
  }
}
