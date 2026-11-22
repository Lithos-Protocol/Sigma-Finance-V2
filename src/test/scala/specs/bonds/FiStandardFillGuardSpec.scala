package org.lithosprotocol.sigmafi
package specs.bonds

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Contract, InputUTXO, Token}

import scala.util.Try

/**
 * FI_Standard_ERG_SigUSD_FillGuard, which FI_Standard_Order runs on a fill when its R8 names it: the new
 * bond must start at or above its own covenant ratio R6(12) at a fresh oracle price no older than R6(14).
 *
 * Covers (FiStandardSpecBase's default world, ratio 150%). SigUSD bond: 7 ERG against $8, so 150% holds
 * up to 0.583333333 ERG per USD, and at 0.5 it needs 6 ERG. ERG bond: 1 ERG and 1000 raw SigUSD against
 * 4 ERG, so 150% holds down to 0.5 ERG per USD, where it needs all 1000 raw SigUSD.
 */
class FiStandardFillGuardSpec extends AnyPropSpec with FiStandardMarketSpecBase {
  import BondSpecBase._
  import ZcStandardSpecBase._

  private val kinds = Seq[Kind](TokenBond, ErgBond)

  private def guardOf(w: W): Contract = fiMarket(w).guard

  private var strictCache: Option[Contract] = None
  /** The guard with its cover test made strict: another expression, another hash. */
  private def strictGuard(ctx: BlockchainContext): Contract = synchronized {
    strictCache.getOrElse {
      val c = FiStandardSpecBase.synchronized {
        Contract.fromErgoContract(ctx.compileContract(ConstantsBuilder.empty(), FiStandardSpecBase.edited(
          ksingh.sigmabonds.client.ScriptGenerator.mkScript(FiStandardMarketSpecBase.GuardPath),
          Seq("collateralValue * 10000L.toBigInt >= debtValue" -> "collateralValue * 10000L.toBigInt > debtValue"))))
      }
      strictCache = Some(c)
      c
    }
  }

  /** A guarded fill of `w` with `data` (default: one oracle at `price`, created at the fill). */
  private def guardedFill(w: W, price: Long = -1L, created: Int = -1, data: Seq[InputUTXO] = null,
                          guard: Contract = null): Tx = {
    val f = fillOf(w, guard = if (guard == null) guardOf(w) else guard)
    fiFillTx(f, data = if (data != null) data
      else Seq(oracle(w.ctx, if (price < 0L) w.calm else price, if (created < 0) f.height else created)))
  }

  private def fills(w: W, price: Long = -1L, created: Int = -1, data: Seq[InputUTXO] = null): Unit =
    ok(lender(w.ctx), guardedFill(w, price, created, data))
  private def refuses(w: W, price: Long = -1L, created: Int = -1, data: Seq[InputUTXO] = null): Unit =
    no(lender(w.ctx), guardedFill(w, price, created, data))

  /** The boundary price of the default world at 150%: the last that fills and the first that does not. */
  private def edge(w: W): (Long, Long) =
    if (w.erg) (500000000L, 499999999L) else (583333333L, 583333334L)

  // ═══ COMPILATION ══════════════════════════════════════════════════════════

  property("compile: one guard for both repayment assets (it reads the asset from the bond's R7), not ZC's, pinning no bond") {
    withCtx { ctx =>
      val m = mk(ctx)
      Seq(m.erg.guard, m.token.guard).foreach(_.ergoTree.version shouldBe 3)
      m.erg.guard.hashedValueBytes.toSeq shouldBe m.token.guard.hashedValueBytes.toSeq
      m.token.guard.hashedValueBytes.toSeq should not be marketOf(ctx, TokenBond).fillGuard.hashedValueBytes.toSeq
      m.token.guard.ergoTreeHex should not include fi(ctx).tokenBond.hashedPropBytesHex
      val w = world(ctx)
      val signed = ok(lender(ctx), guardedFill(w))
      println(s"FIMKT|guard|FI guard ERG=${m.erg.guard.valueBytes.length}|FI guard SigUSD=${m.token.guard.valueBytes.length}|" +
        s"ZC guard SigUSD=${marketOf(ctx, TokenBond).fillGuard.valueBytes.length}|guarded fill tx=${signed.toBytes.length}|" +
        s"guarded fill cost=${signed.getCost}")
    }
  }

  // ═══ THE BOUNDARY ═════════════════════════════════════════════════════════

  for (kind <- kinds) {
    property(s"$kind: a guarded fill passes at exactly the covenant ratio and fails one step below it") {
      withCtx { ctx =>
        val w = world(ctx, kind)
        val (pass, fail) = edge(w)
        fills(w, price = pass)
        refuses(w, price = fail)
        // The same boundary in collateral at 0.5 ERG per USD: one nanoERG, or one raw SigUSD, short refuses.
        if (w.erg) {
          fills(world(ctx, kind, collateral = Seq(Token(SigUSD, 1000L))), price = 500000000L)
          refuses(world(ctx, kind, collateral = Seq(Token(SigUSD, 999L))), price = 500000000L)
        } else {
          fills(world(ctx, kind, collateralErg = 6000000000L), price = 500000000L)
          refuses(world(ctx, kind, collateralErg = 5999999999L), price = 500000000L)
        }
        // The order with the always-true opt-out guard fills below the ratio: the guard, and nothing else, refuses.
        ok(lender(ctx), fiFillTx(fillOf(w), data = Seq(oracle(ctx, fail, fillHeight(w)))))
      }
    }
  }

  property("ratio: the guard reads the bond's own R6(12); a bond with no covenant passes with no oracle at all") {
    withCtx { ctx =>
      // SigUSD bond at 170%: 7 ERG against $8 holds up to 0.514705882 ERG per USD.
      val w = withTerm(world(ctx), 12, 17000L)
      fills(w, price = 514705882L)
      refuses(w, price = 514705883L)
      // Ratio 0: no covenant. It passes without an oracle, with a stale one, and at any price.
      for (kind <- kinds) {
        val none = withTerms(world(ctx, kind), 12 -> 0L, 13 -> 0L, 14 -> 0L)
        fills(none, data = Seq.empty)
        fills(none, price = 10L * edge(none)._2, created = fillHeight(none) - 1000)
        fills(none, price = if (none.erg) 1L else 100L * edge(none)._2)
      }
    }
  }

  // ═══ THE ORACLE ═══════════════════════════════════════════════════════════

  property("oracle: no older than the bond's R6(14), not from the future") {
    withCtx { ctx =>
      for (kind <- kinds) {
        val w = world(ctx, kind)
        val h = fillHeight(w)
        fills(w, created = h - maxAge)
        refuses(w, created = h - maxAge - 1)
        refuses(w, created = h + 1)
        // A longer bond age admits the older box.
        fills(withTerm(w, 14, maxAge + 1L), created = h - maxAge - 1)
      }
    }
  }

  property("oracle: exactly one pool box, found by its singleton NFT, with a positive price and epoch >= 0") {
    withCtx { ctx =>
      for (kind <- kinds) {
        val w = world(ctx, kind)
        val h = fillHeight(w)
        fills(w)
        refuses(w, data = Seq.empty)
        refuses(w, data = Seq(oracle(ctx, w.calm, h, nft = ErgoId.create("5a" * 32))))
        refuses(w, data = Seq(oracle(ctx, w.calm, h, amount = 2L)))
        refuses(w, data = Seq(oracle(ctx, w.calm, h), oracle(ctx, w.calm, h, epoch = 2, index = 21)))
        refuses(w, data = Seq(oracle(ctx, 0L, h)))
        refuses(w, data = Seq(oracle(ctx, w.calm, h, epoch = -1)))
        // Another box beside the one pool box does not matter.
        fills(w, data = Seq(oracle(ctx, w.calm, h, nft = ErgoId.create("5a" * 32), index = 21), oracle(ctx, w.calm, h)))
      }
    }
  }

  // ═══ TIED TO THE COVENANT ═════════════════════════════════════════════════

  /**
   * Around each world's boundary, on ONE oracle box: the guarded fill passes exactly when the bond it would
   * create cannot be recorded as breached at its first checkpoint on that same box. Coupon 0 falls due 10
   * blocks after the start, so the box (created 5 blocks before the fill) is fresh at both.
   */
  property("tie: a fill the guard passes is not recordable as breached on the same oracle box; one it refuses is") {
    withCtx { ctx =>
      val worlds = for (kind <- kinds; ratio <- Seq(15000L, 17000L)) yield withTerms(world(ctx, kind), 9 -> 10L, 12 -> ratio)
      for (w <- worlds) {
        // At 170%: the ERG bond holds down to 0.58 ERG per USD, the SigUSD bond up to 0.514705882.
        val (pass, fail) = if (w.terms(12) == 15000L) edge(w) else if (w.erg) (580000000L, 579999999L) else (514705882L, 514705883L)
        // The direction of a safer price: up for the ERG bond (its SigUSD collateral), down for the SigUSD bond (its debt).
        val safer = if (w.erg) 1L else -1L
        val prices = Seq(pass + 50L * safer, pass + safer, pass, fail, fail - safer, fail - 50L * safer)
        val outcomes = prices.map { price =>
          val box = oracle(ctx, price, fillHeight(w) - 5)
          val guarded = guardedFill(w, data = Seq(box))
          val passed = Try(lender(ctx).sign(guarded.unsigned)).isSuccess
          // The bond the order makes: the guarded fill's when it passed, else the same order with the opt-out guard.
          val fill = if (passed) ok(lender(ctx), guarded) else ok(lender(ctx), fiFillTx(fillOf(w), data = Seq(box)))
          val bond = spendOutput(fill, 1)
          val d0 = w.start + w.terms(9).toInt
          val record = recordTx(w, bond, d0, 0L, data = Seq(box))
          withClue(s"${w.kind} ratio ${w.terms(12)} price $price: ") {
            if (passed) {
              no(executor(ctx), record)
              // The same Record signs on a box at a price well past the boundary: only the price refused it.
              ok(executor(ctx), recordTx(w, bond, d0, 0L, data = Seq(oracle(ctx, fail - 50L * safer, fillHeight(w) - 5))))
            } else ok(executor(ctx), record)
          }
          passed
        }
        withClue(s"${w.kind} ratio ${w.terms(12)}: ")(outcomes shouldBe Seq(true, true, true, false, false, false))
      }
    }
  }

  // ═══ ISOLATION ════════════════════════════════════════════════════════════

  property("isolation: the order runs only the guard its R8 names; the guard refuses a ZC bond") {
    withCtx { ctx =>
      val w = world(ctx)
      // Named in R8 but var 1 holds another expression, or is absent.
      no(lender(ctx), fiFillTx(fillOf(w, guard = guardOf(w)), ran = alwaysTrue(ctx), data = Seq(oracle(ctx, w.calm, fillHeight(w)))))
      // Another expression is another hash: the order refuses it in var 1 (the covenant, or a guard one
      // comparison apart).
      no(lender(ctx), fiFillTx(fillOf(w, guard = guardOf(w)), ran = fi(ctx).tokenCovenant,
        data = Seq(oracle(ctx, w.calm, fillHeight(w)))))
      no(lender(ctx), fiFillTx(fillOf(w, guard = guardOf(w)), ran = strictGuard(ctx), data = Seq(oracle(ctx, w.calm, fillHeight(w)))))
      // A ZC order naming the FI guard, with FI-shaped terms: the ZC bond's R9 has 3 values, so it never fills.
      val guard = guardOf(w)
      def zcFill(g: Contract): (ErgoProver, UnsignedTransaction) = {
        val zc = flow(ctx, TokenBond, extra = w.terms.drop(7), guard = g)
        (zc.holder, buildAt(ctx, zc.height, Seq(zc.orderIn.withCtxVar(1.toByte, liqVar(g)), zc.fund),
          Seq(zc.unit, zc.bond, zc.principal), zc.holder.getAddress, dataInputs = Seq(oracleBox(zc))))
      }
      val (holder, control) = zcFill(alwaysTrue(ctx))
      accepts(holder, control)
      rejectsAtSigning(holder, zcFill(guard)._2)
    }
  }

  // ═══ AUDIT FIXES ══════════════════════════════════════════════════════════

  property("F-012: the guard reads the repayment asset from the bond's R7: empty is ERG, SigUSD alone is SigUSD, anything else refuses") {
    withCtx { ctx =>
      for (kind <- kinds) {
        val w = world(ctx, kind)
        val f = fillOf(w)
        val g = guardOf(w)
        val (pass, fail) = edge(w)
        def run(r7: Seq[Token], price: Long): Tx =
          runAsScript(w, g, f.bond.withRegNum(7, ksingh.sigmabonds.app.Protocol.tokensValue(r7)),
            data = Seq(oracle(ctx, price, fillHeight(w))))
        // The bond's own R7: the same boundary as a guarded fill.
        ok(executor(ctx), run(loanTokens(w), pass))
        no(executor(ctx), run(loanTokens(w), fail))
        // R7 naming another asset, or SigUSD beside it: neither market, refused at any price.
        Seq(Seq(Token(collateralToken, 600L)), Seq(Token(SigUSD, 600L), Token(collateralToken, 1L)))
          .foreach(r7 => withClue(s"$kind R7 $r7")(no(executor(ctx), run(r7, pass))))
      }
      // The SigUSD bond read as ERG (R7 empty): a debt of 800 nanoERG, covered at a price the SigUSD reading refuses.
      val token = world(ctx, TokenBond)
      ok(executor(ctx), runAsScript(token, guardOf(token), fillOf(token).bond.withRegNum(7, ksingh.sigmabonds.app.Protocol.tokensValue(Seq.empty)),
        data = Seq(oracle(ctx, edge(token)._2, fillHeight(token)))))
    }
  }
}
