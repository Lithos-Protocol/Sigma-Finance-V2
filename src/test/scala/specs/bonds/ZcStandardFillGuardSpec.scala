package org.lithosprotocol.sigmafi
package specs.bonds

import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

/**
 * The guards: ZC_Standard_ERG_SigUSD_FillGuard, which ZC_Standard_Order runs on a fill when its R8 names
 * it, and ZC_Standard_ERG_SigUSD_GuardedNav_Pricing, which ZC_Standard_NavAsk runs on a sale. Each lets
 * its owner, the borrower or the seller, set a collateral ratio below which the box does not trade.
 *
 * Covers used throughout: an ERG bond holds 10 ERG against a 7 ERG repayment, 142.857% whatever the
 * price. A SigUSD bond holds 10 ERG against $7: 285.71% at 0.5 ERG per USD, 178.57% at 0.8.
 */
class ZcStandardFillGuardSpec extends AnyPropSpec with ZcStandardSpecBase {
  import BondSpecBase._
  import ZcStandardSpecBase._

  private def guardOf(ctx: BlockchainContext, kind: Kind): Contract = marketOf(ctx, kind).fillGuard

  /** An oracle-rule order at `ratio`, oracle age 30, whose R8 names `guard` with the minimum `fill`. */
  private def guarded(ctx: BlockchainContext, kind: Kind, ratio: Long, fill: Long, guard: Contract = null): Flow =
    flow(ctx, kind, extra = Seq(ratio, 30L, fill), liq = oracleRuleOf(ctx, kind),
      guard = if (guard == null) guardOf(ctx, kind) else guard)

  /** A wallet fill: the guard in the order's var 1, and by default one fresh oracle at 0.5 ERG per USD. */
  private def guardedFillTx(f: Flow, expression: Contract = null, data: Seq[InputUTXO] = null,
                            orderIn: InputUTXO = null): UnsignedTransaction = {
    val in = if (orderIn != null) orderIn
      else f.orderIn.withCtxVar(1.toByte, liqVar(if (expression == null) guardOf(f.ctx, f.kind) else expression))
    buildAt(f.ctx, f.height, Seq(in, f.fund), Seq(f.unit, f.bond, f.principal), f.holder.getAddress,
      dataInputs = if (data == null) Seq(oracleBox(f)) else data)
  }

  // ═══ FILL GUARD ═══════════════════════════════════════════════════════════

  property("fill guard: a guarded order fills only while the new bond's cover is at least its minimum, to the basis point") {
    withCtx { ctx =>
      val erg = guarded(ctx, ErgBond, 14000L, 14285L)
      accepts(erg.holder, guardedFillTx(erg))
      rejectsAtSigning(erg.holder, guardedFillTx(guarded(ctx, ErgBond, 14000L, 14286L)))

      // The same SigUSD order fills while ERG is worth $2, and not once it falls to $1.25.
      val f = guarded(ctx, TokenBond, 15000L, 20000L)
      accepts(f.holder, guardedFillTx(f))
      rejectsAtSigning(f.holder, guardedFillTx(f, data = Seq(oracleBox(f, price = 800000000L))))
      // At 285.71% the boundary is a price: 1e16 >= P * 700 * 28571 holds up to P = 500007500.
      val edge = guarded(ctx, TokenBond, 15000L, 28571L)
      accepts(edge.holder, guardedFillTx(edge, data = Seq(oracleBox(edge, price = 500007500L))))
      rejectsAtSigning(edge.holder, guardedFillTx(edge, data = Seq(oracleBox(edge, price = 500007501L))))
    }
  }

  property("fill guard: the R8 expression must be supplied and pass, with one fresh oracle and a minimum at least the rule's ratio") {
    withCtx { ctx =>
      val f = guarded(ctx, TokenBond, 15000L, 20000L)
      // No guard in var 1, or another expression there.
      rejectsAtSigning(f.holder, guardedFillTx(f, orderIn = f.orderIn))
      rejectsAtSigning(f.holder, guardedFillTx(f, expression = alwaysTrue(ctx)))
      // An order naming a guard that never passes never fills.
      val never = guarded(ctx, TokenBond, 15000L, 20000L, guard = neverTrue(ctx))
      rejectsAtSigning(never.holder, guardedFillTx(never, expression = neverTrue(ctx)))

      // The oracle: none, as old as the rule's age allows and a block older, and two pool boxes.
      rejectsAtSigning(f.holder, guardedFillTx(f, data = Seq.empty))
      accepts(f.holder, guardedFillTx(f, data = Seq(oracleBox(f, created = f.height - 30))))
      rejectsAtSigning(f.holder, guardedFillTx(f, data = Seq(oracleBox(f, created = f.height - 31))))
      rejectsAtSigning(f.holder, guardedFillTx(f, data = Seq(oracleBox(f), oracleBox(f, epoch = 2))))
      rejectsAtSigning(f.holder, guardedFillTx(f, data = Seq(oracleBox(f, amount = 2L))))

      // A minimum under the rule's own ratio, or none at all, never fills: the bond could start liquidatable.
      rejectsAtSigning(f.holder, guardedFillTx(guarded(ctx, TokenBond, 15000L, 14999L)))
      val short = flow(ctx, TokenBond, extra = Seq(15000L, 30L), liq = oracleRuleOf(ctx, TokenBond),
        guard = guardOf(ctx, TokenBond))
      rejectsAtSigning(short.holder, guardedFillTx(short))
    }
  }

  property("fill guard: it measures the bond as filled, net of the lender's fee reserve") {
    withCtx { ctx =>
      // The lender funds a 1 ERG reserve. Left out, the cover is 142.857%; counted, it would be 157%.
      def withReserve(f: Flow): Flow = f.copy(
        bond = f.bond.withRegNum(9, longsValue(Parameters.OneErg, f.start, f.maturity))
          .setValue(f.collateralValue + Parameters.OneErg),
        fund = funding(ctx, f.holder, 1, f.data(1) + Parameters.OneErg + slack))
      val f = withReserve(guarded(ctx, ErgBond, 14000L, 14285L))
      accepts(f.holder, guardedFillTx(f))
      val greedy = withReserve(guarded(ctx, ErgBond, 14000L, 15000L))
      rejectsAtSigning(greedy.holder, guardedFillTx(greedy))
    }
  }

  property("fill guard: without R8 a fill is unchanged; a guarded order still refunds after expiry and cancels") {
    withCtx { ctx =>
      // Unguarded, a fill needs neither var 1 nor an oracle, even with a payload the guard would refuse.
      val plain = flow(ctx, TokenBond, extra = Seq(15000L, 30L, 99999L), liq = oracleRuleOf(ctx, TokenBond))
      accepts(plain.holder, standardFillTx(plain))

      val f = guarded(ctx, TokenBond, 15000L, 20000L)
      rejectsAtSigning(f.holder, standardFillTx(f))
      val refundIn = inputAt(f.order, ctx, 0).withCtxVar(0.toByte, ErgoValue.of(0))
      accepts(executor(ctx), buildAt(ctx, f.expiry.toInt, Seq(refundIn),
        Seq(tagged(borrowerTree(ctx), f.collateralValue, f.asset, f.collateral)), executor(ctx).getAddress))
      accepts(f.owner, buildAt(ctx, f.height, Seq(inputAt(f.order, ctx, 0), funding(ctx, f.owner, 1)),
        Seq(UTXO(contractOf(f.owner), f.order.value, f.order.tokens)), f.owner.getAddress))
    }
  }

  property("fill guard: it binds a lend offer too, whose own policy would pass the same fill") {
    withCtx { ctx =>
      // The offer's floor is 130%; the order's rule is 140% and its guard asks for 142.85% or 142.86%.
      def offerFill(f: Flow): UnsignedTransaction = {
        val m = marketOf(ctx, ErgBond)
        val offer = UTXO(m.offer, 20L * Parameters.OneErg, Seq.empty[Token],
          Seq(bytesValue(hashOf(lenderTree(ctx))),
            longsValue(0L, 1L, 100000L, batchingFee, f.fee, 13000L, ctx.getHeight + 5000L, 1L, 30L, 0L, 1000000L),
            bytesValue(m.policy.hashedValueBytes), longsValue(), textsValue(), bytesValue(Array.empty[Byte])))
        val offerIn = inputAt(offer, ctx, 1).withCtxVar(0.toByte, ErgoValue.of(3)).withCtxVar(1.toByte, liqVar(m.policy))
        val next = offer.setValue(offer.value - (batchingFee + minBox + f.fee + f.data(1)))
        buildAt(ctx, f.height, Seq(f.orderIn.withCtxVar(1.toByte, liqVar(guardOf(ctx, ErgBond))), offerIn),
          Seq(f.unit, f.bond, f.principal, next), executor(ctx).getAddress, dataInputs = Seq(oracleBox(f)))
      }
      accepts(executor(ctx), offerFill(guarded(ctx, ErgBond, 14000L, 14285L)))
      rejectsAtSigning(executor(ctx), offerFill(guarded(ctx, ErgBond, 14000L, 14286L)))
    }
  }

  // ═══ GUARDED NAV PRICING ══════════════════════════════════════════════════

  /** A live bond, filled, and a height 300 blocks into its term. */
  private case class Live(f: Flow, state: InputUTXO) {
    val height: Int = f.height + 300
    /** What AccruedValue_Pricing asks for one whole bond unit at `height`, plus `margin`. */
    def owed(margin: Long): Long = {
      val principal = if (f.kind == ErgBond) f.data(1) else 600L
      val term = f.maturity - f.start
      val elapsed = height - f.start
      ceilDiv((BigInt(principal) * (term - elapsed) + BigInt(f.data(0)) * elapsed) * (10000L + margin), BigInt(term) * 10000L)
    }
  }

  private def live(ctx: BlockchainContext, kind: Kind): Live = {
    val f = flow(ctx, kind)
    Live(f, spendOutput(filled(f), 1))
  }

  /** A NAV ask for the bond unit under the guarded expression: R5 floor, min fill, expiry, margin, N, minimum, age. */
  private def guardedAsk(l: Live, minBps: Long, margin: Long = 0L, floor: (Long, Long) = (1L, 1000000000L),
                         maxAge: Long = 30L): UTXO = {
    val ctx = l.f.ctx
    UTXO(marketOf(ctx, l.f.kind).navAsk, minBox, Seq(Token(l.f.asset, 1L)),
      Seq(bytesValue(hashOf(otherTree(ctx))),
        longsValue(floor._1, floor._2, 1L, l.f.maturity + 100L, margin, 1L, minBps, maxAge),
        bytesValue(marketOf(ctx, l.f.kind).guardedPricing.hashedValueBytes), bytesValue(l.f.asset.getBytes)))
  }

  /** A buyer takes the unit, paying `paid`; data inputs: 0 the bond's state box, then `data` (an oracle by default). */
  private def saleTx(l: Live, ask: UTXO, paid: Long, data: Seq[InputUTXO] = null, price: Long = 500000000L,
                     expression: Contract = null): UnsignedTransaction = {
    val ctx = l.f.ctx
    val kind = l.f.kind
    val askIn = inputAt(ask, ctx, 0)
    val in = askIn.withCtxVar(0.toByte, ErgoValue.of(0)).withCtxVar(3.toByte, ErgoValue.of(0))
      .withCtxVar(2.toByte, liqVar(if (expression == null) marketOf(ctx, kind).guardedPricing else expression))
    val regs = Seq(bytesValue(askIn.id.getBytes), ErgoValue.of(1L))
    val pay = if (kind == ErgBond) UTXO(otherTree(ctx), paid, Seq.empty[Token], regs)
      else UTXO(otherTree(ctx), minBox, Seq(Token(SigUSD, paid)), regs)
    val buyer = funding(ctx, executor(ctx), 9, slack, if (kind == ErgBond) Seq.empty[Token] else Seq(Token(SigUSD, 1000000L)))
    buildAt(ctx, l.height, Seq(in, buyer), Seq(pay), executor(ctx).getAddress,
      dataInputs = l.state +: (if (data == null) Seq(oracleBox(l.f, price = price, created = l.height)) else data))
  }

  property("guarded nav: a sale passes only while the bond's cover is at least the seller's minimum, priced as accrued value") {
    withCtx { ctx =>
      val erg = live(ctx, ErgBond)
      accepts(executor(ctx), saleTx(erg, guardedAsk(erg, 14285L), erg.owed(0L)))
      rejectsAtSigning(executor(ctx), saleTx(erg, guardedAsk(erg, 14285L), erg.owed(0L) - 1L))
      rejectsAtSigning(executor(ctx), saleTx(erg, guardedAsk(erg, 14286L), erg.owed(0L)))

      val usd = live(ctx, TokenBond)
      val ask = guardedAsk(usd, 20000L, margin = 250L)
      accepts(executor(ctx), saleTx(usd, ask, usd.owed(250L)))
      rejectsAtSigning(executor(ctx), saleTx(usd, ask, usd.owed(250L) - 1L))
      // ERG falls to $1.25: 178%, under the seller's 200%, whatever the buyer pays.
      rejectsAtSigning(executor(ctx), saleTx(usd, ask, 3L * usd.owed(250L), price = 800000000L))
    }
  }

  property("guarded nav: margin -9999 leaves the fixed floor as the price, still guarded") {
    withCtx { ctx =>
      val l = live(ctx, TokenBond)
      val ask = guardedAsk(l, 20000L, margin = -9999L, floor = (500L, 1L))
      accepts(executor(ctx), saleTx(l, ask, 500L))
      rejectsAtSigning(executor(ctx), saleTx(l, ask, 499L))
      rejectsAtSigning(executor(ctx), saleTx(l, ask, 500L, price = 800000000L))
    }
  }

  property("guarded nav: it needs one fresh oracle, positive bounds, and its own expression") {
    withCtx { ctx =>
      val l = live(ctx, TokenBond)
      val ask = guardedAsk(l, 20000L)
      val paid = l.owed(0L)
      rejectsAtSigning(executor(ctx), saleTx(l, ask, paid, data = Seq.empty))
      accepts(executor(ctx), saleTx(l, ask, paid, data = Seq(oracleBox(l.f, created = l.height - 30))))
      rejectsAtSigning(executor(ctx), saleTx(l, ask, paid, data = Seq(oracleBox(l.f, created = l.height - 31))))
      rejectsAtSigning(executor(ctx), saleTx(l, guardedAsk(l, 0L), paid))
      rejectsAtSigning(executor(ctx), saleTx(l, guardedAsk(l, 20000L, maxAge = 0L), paid))
      // The plain accrued-value expression is not the one this ask's R6 names.
      rejectsAtSigning(executor(ctx), saleTx(l, ask, paid, expression = marketOf(ctx, TokenBond).pricing))
    }
  }
}
