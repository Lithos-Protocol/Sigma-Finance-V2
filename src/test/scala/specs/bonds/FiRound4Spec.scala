package org.lithosprotocol.sigmafi
package specs.bonds

import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

/**
 * Round-4 audit item: a Refresh bounty taken before maturity can make a covenant breach on a bond held at exactly
 * its ratio, which the refresher then records for the Record bounty, at the borrower's cost.
 */
class FiRound4Spec extends AnyPropSpec with FiStandardMarketSpecBase {
  import BondSpecBase._
  import ZcStandardSpecBase._

  /**
   * ERG for ERG: repayment 1 ERG, principal 0.9 ERG, net collateral 1.3 ERG at a 130% ratio (exactly, as the
   * standard guard and the Cure accept), one coupon due 525700 blocks after the start, life 600000.
   */
  private def atRatio(ctx: BlockchainContext): W =
    withTerms(world(ctx, ErgBond, life = 600000L, collateralErg = 1300000000L, collateral = Seq.empty),
      0 -> 1000000000L, 1 -> 900000000L, 2 -> 1000000000L, 8 -> 1L, 9 -> 525700L, 11 -> 30L, 12 -> 13000L)

  /** The bond filled directly, with the standard guard on a fresh oracle box. */
  private def filled(w: W): InputUTXO = {
    val f = fillOf(w, guard = fiMarket(w).guard)
    spendOutput(ok(lender(w.ctx), fiFillTx(f, data = Seq(oracle(w.ctx, w.calm, f.height)))), 1)
  }

  private def due(w: W): Int = w.start + 525700

  /** The bond with the Refresh bounty line as it stood before round 4 (0.01 ERG at any height). */
  private def oldRefresh(w: W): Contract = FiStandardSpecBase.bondVariant(w.ctx, repaymentBytes(w), fi(w.ctx).couponVault,
    "(if (refresh) (if (height >= maturity) 10000000L else 0L) else CONST_RECORD_BOUNTY)" ->
      "(if (refresh) 10000000L else CONST_RECORD_BOUNTY)")

  // Before the fix (recorded in the round-4 report) this sequence signed under the real bond: the refresher kept
  // 0.01 ERG, the recorder 0.5 ERG; Cure cost 0.51 ERG, repayment returned 0.79 ERG instead of 1.3, default 0.
  property("R4: a stranger's pre-maturity Refresh can no longer take the 0.01 ERG that makes the breach; the old line still signs the attack") {
    withCtx { ctx =>
      val w = atRatio(ctx)
      val bond = filled(w)
      val h = due(w)
      // Untouched for 525700 blocks and at exactly its ratio: not below it, so Record is refused.
      no(executor(ctx), recordTx(w, bond, h, 0L))
      // The bounty-taking Refresh is refused before maturity, by 0.01 ERG or by one nanoERG.
      no(executor(ctx), refreshTx(w, bond, h, next = bond.toUTXO.subValue(Bounty).setCreationHeight(h - 60)))
      no(executor(ctx), refreshTx(w, bond, h, next = bond.toUTXO.subValue(1L).setCreationHeight(h - 60)))
      // A value-preserving Refresh still signs, under the same stamp rules; after it the bond is still not below.
      val kept = bond.toUTXO.setCreationHeight(h - 60)
      val renewed = spendOutput(ok(executor(ctx), refreshTx(w, bond, h, next = kept)), 0)
      no(executor(ctx), refreshTx(w, bond, h, next = bond.toUTXO.setCreationHeight(h - 59)))
      no(executor(ctx), refreshTx(w, bond, h, next = bond.toUTXO.setCreationHeight(h - 1000)))
      no(executor(ctx), recordTx(w, renewed, h, 0L))
      // Under the old line the whole attack signs, at the borrower's cost.
      val old = inputAt(bond.toUTXO.setContract(oldRefresh(w)), ctx, 0, "cd" * 32)
      val refreshed = spendOutput(ok(executor(ctx), refreshTx(w, old, h, next = old.toUTXO.subValue(Bounty).setCreationHeight(h - 60))), 0)
      refreshed.value shouldBe old.value - Bounty
      val rec = ok(executor(ctx), recordTx(w, refreshed, h, 0L,
        next = recorded(w, refreshed, h, 0L).subValue(FiStandardSpecBase.RecordBounty)))
      val breached = spendOutput(rec, 0)
      val taken = old.value - breached.value
      taken shouldBe Bounty + FiStandardSpecBase.RecordBounty
      // The borrower's three outcomes there. 1: Cure must restore the ratio: 0.51 ERG, a nanoERG less refused.
      val cureAt = h + 1
      ok(borrower(ctx), cureTx(w, breached, cureAt, next = succ(breached, r9(breached).updated(5, 0L), cureAt).addValue(taken)))
      no(borrower(ctx), cureTx(w, breached, cureAt, next = succ(breached, r9(breached).updated(5, 0L), cureAt).addValue(taken - 1L)))
      // 2: repay the 1 ERG and take back what is left of the collateral.
      val back = spendOutput(ok(borrower(ctx), fiRepayTx(w, breached, cureAt)), 1).value
      val untouched = bond.value - batchingFee
      back shouldBe untouched - taken
      // 3: miss the cure deadline: liquidated, the lender's vault takes the rest and the borrower nothing.
      val vault = spendOutput(ok(executor(ctx), fiLiquidateTx(w, breached, r9(breached)(5).toInt)), 0).value
      println(s"R4|old line: refresher kept $Bounty, recorder ${FiStandardSpecBase.RecordBounty}|borrower: cure costs $taken|" +
        s"repay returns $back instead of $untouched|default: the vault takes $vault, the borrower 0")
    }
  }

  property("R4: from maturity on a Refresh keeps up to 0.01 ERG as before; the Record bounty is unchanged") {
    withCtx { ctx =>
      val w = atRatio(ctx)
      val bond = filled(w)
      val m = w.maturity.toInt
      ok(executor(ctx), refreshTx(w, bond, m))
      no(executor(ctx), refreshTx(w, bond, m, next = bond.toUTXO.subValue(Bounty + 1L).setCreationHeight(m - 60)))
      no(executor(ctx), refreshTx(w, bond, m - 1))
      // Record still keeps up to 0.5 ERG: on a bond below its ratio by hand (one nanoERG under), at its checkpoint.
      val below = fiIn(w, filled(w).toUTXO.subValue(1L))
      val h = due(w)
      ok(executor(ctx), recordTx(w, below, h, 0L, next = recorded(w, below, h, 0L).subValue(FiStandardSpecBase.RecordBounty)))
      no(executor(ctx), recordTx(w, below, h, 0L, next = recorded(w, below, h, 0L).subValue(FiStandardSpecBase.RecordBounty + 1L)))
    }
  }

  property("R4 reproduction: a borrower Pay or Recollateralize stamped before the checkpoint makes the Refresh refused (age)") {
    withCtx { ctx =>
      val w = atRatio(ctx)
      val bond = filled(w)
      val h = due(w)
      val afterPay = spendOutput(withClue("pay: ")(ok(borrower(ctx), payTx(w, bond, h - 10, coupon = couponOut(w, asset = bond.tokens.head.id)))), 0)
      withClue("refresh after pay: ")(no(executor(ctx), refreshTx(w, afterPay, h)))
      val regrownBox = spendOutput(withClue("recollateralize: ")(ok(borrower(ctx), recollatTx(w, bond, h - 10))), 0)
      withClue("refresh after recollateralize: ")(no(executor(ctx), refreshTx(w, regrownBox, h)))
      // The untouched box refreshes at the same height.
      ok(executor(ctx), refreshTx(w, bond, h))
    }
  }
}
