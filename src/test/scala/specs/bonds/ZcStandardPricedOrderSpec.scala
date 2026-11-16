package org.lithosprotocol.sigmafi
package specs.bonds

import ksingh.sigmabonds.app.Protocol
import ksingh.sigmabonds.standard.{Market => StdMarket}
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Contract, Token, UTXO}

/** Real interpreter checks for two-point yield pricing, immutable terms and the actual principal record. */
class ZcStandardPricedOrderSpec extends AnyPropSpec with ZcStandardSpecBase {
  import BondSpecBase._
  import ZcStandardSpecBase._

  private def c(ctx: BlockchainContext, k: Kind) = ZcStandardSpecBase.compiled(ctx).market(if (k == ErgBond) StdMarket.Erg else StdMarket.SigUsd)
  private def policies(pricing: Contract, guard: Option[Contract] = None): ErgoValue[_] =
    ErgoValue.of(sigma.Colls.fromArray(Array(pricing.hashedValueBytes, guard.fold(Array.emptyByteArray)(_.hashedValueBytes))
      .map(sigma.Colls.fromArray(_)))(sigma.collRType(sigma.ByteType)),
      ErgoType.collType(org.ergoplatform.appkit.scalaapi.scalaByteType))

  private def priced(base: Flow, expression: Contract, payload: Seq[Long], amount: Long,
                     guard: Option[Contract] = None): Flow = {
    val order = base.order.setContract(c(base.ctx, base.kind).pricedOrder)
      .withRegNum(8, policies(expression, guard)).withRegNum(9, longsValue(payload: _*))
    val raw = inputAt(order, base.ctx, 0)
    val in = guard.fold(raw)(g => raw.withCtxVar(1.toByte, liqVar(g))).withCtxVar(2.toByte, liqVar(expression))
    val data = if (base.kind == ErgBond) base.data.updated(1, amount) else base.data
    val tokens = if (base.kind == ErgBond) Seq.empty[Token] else Seq(Token(SigUSD, amount))
    base.copy(order = order, orderIn = in, data = data,
      unit = base.unit.setTokens(Token(in.id, 1L)),
      bond = base.bond.setTokens(Token(in.id, 2L) +: base.collateral: _*)
        .withRegNum(4, bytesValue(in.id.getBytes)).withRegNum(6, longsValue(data: _*))
        .withRegNum(7, Protocol.tokensValue(tokens)),
      principal = base.principal.setValue(data(1) + base.expiryFee).setTokens(tokens: _*)
        .withRegNum(4, bytesValue(in.id.getBytes)),
      fund = funding(base.ctx, base.holder, 1, data(1) + base.fee + slack, tokens))
  }

  /** One yield, flat across the fill height `h`: (start height, start yield, end height, end yield). */
  private def flat(yieldBps: Long, h: Long): Seq[Long] = Seq(h - 100L, yieldBps, h + 100L, yieldBps)

  /** A bond repaying 110 against a 100 floor, filled half a year (131400 blocks) before its maturity. */
  private def halfYear(ctx: BlockchainContext, kind: Kind): Flow = {
    val reference = ctx.getHeight.toLong
    val scale = if (kind == ErgBond) Parameters.OneErg else 100L
    moveFill(flow(ctx, kind, principalValue = if (kind == ErgBond) 100L * scale else minBox,
      principalAssets = if (kind == ErgBond) Seq.empty else Seq(Token(SigUSD, 100L * scale)),
      repayment = 110L * scale, fixedMaturity = reference + 262800L, expiry = reference + 262799L),
      (reference + 131400L).toInt)
  }

  for (kind <- Seq[Kind](ErgBond, TokenBond)) {
    property(s"priced order $kind: the yield moves in a straight line between its two points, exact to one raw unit") {
      withCtx { ctx =>
        val scale = if (kind == ErgBond) Parameters.OneErg else 100L
        val base = halfYear(ctx, kind)
        val h = base.height.toLong
        // 10% over half a year: 110 / 1.05, rounded up.
        val amount = if (kind == ErgBond) 104761904762L else 10477L
        // Every line below is at 10% at the fill height: flat, rising and falling through it, before its start and
        // after its end. So one exact price checks the interpolation and both clamps.
        Seq(flat(1000L, h), Seq(h - 100L, 0L, h + 100L, 2000L), Seq(h - 100L, 2000L, h + 100L, 0L),
          Seq(h + 10L, 1000L, h + 110L, 5000L), Seq(h - 110L, 5000L, h - 10L, 1000L)).foreach { payload =>
          filled(priced(base, c(ctx, kind).yieldOrderPricing, payload, amount))
          val under = priced(base, c(ctx, kind).yieldOrderPricing, payload, amount - 1L)
          rejectsAtSigning(under.holder, standardFillTx(under))
        }
        // A huge yield prices under the floor, which then binds.
        filled(priced(base, c(ctx, kind).yieldOrderPricing, flat(1000000L, h), 100L * scale))
        val floor = priced(base, c(ctx, kind).yieldOrderPricing, flat(1000000L, h), 100L * scale - 1L)
        rejectsAtSigning(floor.holder, standardFillTx(floor))
        // Malformed: a short payload, negative yields, and an end not after the start.
        Seq(Seq(1000L), Seq(1000L, 0L), Seq(h - 100L, -1L, h + 100L, 1000L), Seq(h - 100L, 1000L, h + 100L, -1L),
          Seq(h, 1000L, h, 1000L), Seq(h + 100L, 1000L, h - 100L, 1000L)).foreach { payload =>
          val bad = priced(base, c(ctx, kind).yieldOrderPricing, payload, 110L * scale)
          rejectsAtSigning(bad.holder, standardFillTx(bad))
        }
      }
    }

    property(s"priced order $kind: an exact yield price, overpayment recorded, repayment and other terms fixed") {
      withCtx { ctx =>
        val base = halfYear(ctx, kind)
        val payload = flat(1000L, base.height.toLong)
        val yieldAmount = if (kind == ErgBond) 104761904762L else 10477L
        val byYield = priced(base, c(ctx, kind).yieldOrderPricing, payload, yieldAmount)
        filled(byYield)
        rejectsAtSigning(byYield.holder, standardFillTx(byYield, principal =
          if (kind == ErgBond) byYield.principal.subValue(1L) else byYield.principal.removeToken(SigUSD, 1L)))
        // More principal is allowed, and must be recorded; an unpaid inflated record is rejected.
        val better = priced(base, c(ctx, kind).yieldOrderPricing, payload, yieldAmount + 1L)
        filled(better)
        rejectsAtSigning(byYield.holder, standardFillTx(byYield, bond = better.bond))
        // No expression may change a non-price borrower term, even if its own logic would allow it.
        base.data.indices.filterNot(i => kind == ErgBond && i == 1).foreach { i =>
          val changed = byYield.data.updated(i, byYield.data(i) + 1L)
          rejectsAtSigning(byYield.holder, standardFillTx(byYield,
            bond = byYield.bond.withRegNum(6, longsValue(changed: _*))))
        }
        rejectsAtSigning(byYield.holder, standardFillTx(byYield,
          inputs = Seq(inputAt(byYield.order, ctx, 0).withCtxVar(2.toByte, liqVar(alwaysTrue(ctx))), byYield.fund)))
      }
    }

    property(s"priced order $kind: yield pricing supports on-close maturity, with a constant duration") {
      withCtx { ctx =>
        val base = flow(ctx, kind, OnClose, duration = 262800L)
        val h = base.height.toLong
        val amount = if (kind == ErgBond) ceilDiv(BigInt(base.data(0)) * 10L, 11L) else 637L
        filled(priced(base, c(ctx, kind).yieldOrderPricing, flat(1000L, h), amount))
        val short = priced(base, c(ctx, kind).yieldOrderPricing, flat(1000L, h), amount - 1L)
        rejectsAtSigning(short.holder, standardFillTx(short))
        val negative = priced(base, c(ctx, kind).yieldOrderPricing, flat(-1L, h), amount)
        rejectsAtSigning(negative.holder, standardFillTx(negative))

        // Filled half a year after posting, an on-close bond still runs its full duration: 10% over a year.
        val reference = ctx.getHeight.toLong
        val scale = if (kind == ErgBond) Parameters.OneErg else 100L
        val late = moveFill(flow(ctx, kind, OnClose, duration = 262800L,
          principalValue = if (kind == ErgBond) 100L * scale else minBox,
          principalAssets = if (kind == ErgBond) Seq.empty else Seq(Token(SigUSD, 100L * scale)),
          repayment = 110L * scale, expiry = reference + 500000L), (reference + 131400L).toInt)
        filled(priced(late, c(ctx, kind).yieldOrderPricing, flat(1000L, late.height.toLong), 100L * scale))
        late.maturity - late.start shouldBe 262800L
      }
    }
  }

  /** The same flow as a window order (mode 2): R4(3) becomes the window's minimum duration. */
  private def window(f: Flow, min: Long, mode: Long = 2L): Flow = {
    val data = f.data.updated(3, min).updated(6, mode)
    f.copy(data = data, order = f.order.withRegNum(4, longsValue(data: _*)), bond = f.bond.withRegNum(6, longsValue(data: _*)))
  }

  for (kind <- Seq[Kind](ErgBond, TokenBond)) {
    property(s"window order $kind: the lender's maturity, inside the window, priced on the borrower's curve to one raw unit") {
      withCtx { ctx =>
        val curve = c(ctx, kind).curveOrderPricing
        // Filled half a year before its maturity: 131400 blocks, inside a 100000 to 200000 window.
        val base = window(halfYear(ctx, kind), 100000L)
        val h = base.height.toLong
        val amount = if (kind == ErgBond) 104761904762L else 10477L
        // Flat 10% (no points), and 5% at 100000 blocks rising to 15% at 162800, which is 10% at 131400.
        Seq(Seq(200000L, 1000L), Seq(200000L, 5000L, 500L, 100000L, 1500L, 162800L)).foreach { payload =>
          filled(priced(base, curve, payload, amount))
          val under = priced(base, curve, payload, amount - 1L)
          rejectsAtSigning(under.holder, standardFillTx(under))
        }
        // The cap binds: at most 8%, so 10% is too little principal.
        val capped = priced(base, curve, Seq(200000L, 800L, 500L, 100000L, 1500L, 162800L), amount)
        rejectsAtSigning(capped.holder, standardFillTx(capped))
        // The window's ends are inclusive, and a duration outside them is refused.
        filled(priced(window(halfYear(ctx, kind), 131400L), curve, Seq(131400L, 1000L), amount))
        Seq(window(halfYear(ctx, kind), 131401L) -> Seq(200000L, 1000L), base -> Seq(131399L, 1000L),
          window(halfYear(ctx, kind), 29L) -> Seq(200000L, 1000L), base -> Seq(99999L, 1000L)).foreach { case (b, payload) =>
          val out = priced(b, curve, payload, amount)
          rejectsAtSigning(out.holder, standardFillTx(out))
        }
        // Curve points out of order, negative yields, or an odd payload never fill.
        Seq(Seq(200000L, 1000L, 1000L, 162800L, 1000L, 100000L), Seq(200000L, 1000L, -1L, 100000L), Seq(200000L, 1000L, 5L),
          Seq(200000L)).foreach { payload =>
          val bad = priced(base, curve, payload, 110L * (if (kind == ErgBond) Parameters.OneErg else 100L))
          rejectsAtSigning(bad.holder, standardFillTx(bad))
        }
        // The two-point expression refuses a window order, since the shell reads its first value as the maximum.
        val twoPoint = priced(base, c(ctx, kind).yieldOrderPricing, flat(1000L, h), amount)
        rejectsAtSigning(twoPoint.holder, standardFillTx(twoPoint))
        // There is no mode 3; the curve still prices a fixed-maturity order, its R9(0) unused.
        val mode3 = priced(window(halfYear(ctx, kind), 100000L, 3L), curve, Seq(200000L, 1000L), amount)
        rejectsAtSigning(mode3.holder, standardFillTx(mode3))
        filled(priced(halfYear(ctx, kind), curve, Seq(1L, 1000L), amount))
      }
    }
  }

  property("priced order: principal floor survives a permissive pricing expression; price and guard are independent") {
    withCtx { ctx =>
      val base = flow(ctx, ErgBond)
      val f = priced(base, alwaysTrue(ctx), Seq.empty, base.data(1))
      filled(f)
      val under = priced(base, alwaysTrue(ctx), Seq.empty, base.data(1) - 1L)
      rejectsAtSigning(under.holder, standardFillTx(under))
      val guarded = priced(base, alwaysTrue(ctx), Seq.empty, base.data(1), Some(neverTrue(ctx)))
      rejectsAtSigning(guarded.holder, standardFillTx(guarded))
      // A yield order with a malformed line never fills, whatever it pays.
      val malformed = priced(base, c(ctx, ErgBond).yieldOrderPricing, Seq(base.height + 1L, 0L, base.height - 1L, 0L), base.data(0))
      rejectsAtSigning(malformed.holder, standardFillTx(malformed))
    }
  }

  property("priced order: expiry and cancellation do not need pricing or guard witnesses, even for malformed policy data") {
    withCtx { ctx =>
      val f = priced(flow(ctx), alwaysTrue(ctx), Seq.empty, 600L)
      val malformed = f.order.withRegNum(8, utf8("invalid policies")).withRegNum(9, utf8("invalid payload"))
      val in = inputAt(malformed, ctx, 0)
      val refund = tagged(borrowerTree(ctx), f.collateralValue, in.id, f.collateral)
      accepts(executor(ctx), buildAt(ctx, f.expiry.toInt, Seq(in.withCtxVar(0.toByte, ErgoValue.of(0))),
        Seq(refund), executor(ctx).getAddress))
      accepts(f.owner, buildAt(ctx, f.height, Seq(in, funding(ctx, f.owner, 1)),
        Seq(UTXO(borrowerTree(ctx), malformed.value, malformed.tokens)), f.owner.getAddress))
    }
  }
}
