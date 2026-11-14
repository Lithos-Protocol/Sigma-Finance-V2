package org.lithosprotocol.sigmafi
package specs.bonds

import ksingh.sigmabonds.app.Protocol
import ksingh.sigmabonds.standard.{Market => StdMarket}
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Contract, Token, UTXO}

/** Real interpreter checks for pricing, immutable terms and the actual principal record. */
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
      principal = base.principal.setValue(data(1) + base.expiryFee).setTokens(tokens: _*),
      fund = funding(base.ctx, base.holder, 1, data(1) + base.fee + slack, tokens))
  }

  for (kind <- Seq[Kind](ErgBond, TokenBond)) {
    property(s"priced order $kind: signed yield price margins apply before rounding and cannot defeat the principal floor") {
      withCtx { ctx =>
        val reference = ctx.getHeight.toLong
        val scale = if (kind == ErgBond) Parameters.OneErg else 100L
        val base = moveFill(flow(ctx, kind, principalValue = if (kind == ErgBond) 100L * scale else minBox,
          principalAssets = if (kind == ErgBond) Seq.empty else Seq(Token(SigUSD, 100L * scale)),
          repayment = 110L * scale, fixedMaturity = reference + 262800L, expiry = reference + 262799L),
          (reference + 131400L).toInt)
        val cases = if (kind == ErgBond) Seq(200L -> 106857142858L, -200L -> 102666666667L)
          else Seq(200L -> 10686L, -200L -> 10267L)
        cases.foreach { case (margin, amount) =>
          filled(priced(base, c(ctx, kind).yieldOrderPricing, Seq(1000L, margin), amount))
          val under = priced(base, c(ctx, kind).yieldOrderPricing, Seq(1000L, margin), amount - 1L)
          rejectsAtSigning(under.holder, standardFillTx(under))
        }
        filled(priced(base, c(ctx, kind).yieldOrderPricing, Seq(1000L, -9999L), 100L * scale))
        filled(priced(base, c(ctx, kind).yieldOrderPricing, Seq(1000L, 1000000L), 110L * scale * 101L))
        Seq(Seq(1000L), Seq(1000L, -10000L), Seq(1000L, 1000001L)).foreach { payload =>
          val bad = priced(base, c(ctx, kind).yieldOrderPricing, payload, 110L * scale)
          rejectsAtSigning(bad.holder, standardFillTx(bad))
        }
      }
    }

    property(s"priced order $kind: NAV and yield give distinct exact halfway prices, repayment stays fixed") {
      withCtx { ctx =>
        val scale = if (kind == ErgBond) Parameters.OneErg else 100L
        val reference = ctx.getHeight.toLong
        val base = moveFill(flow(ctx, kind, principalValue = if (kind == ErgBond) 100L * scale else minBox,
          principalAssets = if (kind == ErgBond) Seq.empty else Seq(Token(SigUSD, 100L * scale)),
          repayment = 110L * scale, fixedMaturity = reference + 262800L, expiry = reference + 262799L),
          (reference + 131400L).toInt)
        val nav = priced(base, c(ctx, kind).navOrderPricing, Seq(reference, 0L), 105L * scale)
        val yieldAmount = if (kind == ErgBond) 104761904762L else 10477L
        val byYield = priced(base, c(ctx, kind).yieldOrderPricing, Seq(1000L, 0L), yieldAmount)
        Seq(nav, byYield).foreach { f =>
          filled(f)
          rejectsAtSigning(f.holder, standardFillTx(f, principal =
            if (kind == ErgBond) f.principal.subValue(1L) else f.principal.removeToken(SigUSD, 1L)))
          val short = priced(base, if (f == nav) c(ctx, kind).navOrderPricing else c(ctx, kind).yieldOrderPricing,
            if (f == nav) Seq(reference, 0L) else Seq(1000L, 0L),
            (if (f == nav) 105L * scale else yieldAmount) - 1L)
          rejectsAtSigning(short.holder, standardFillTx(short))
        }
        // More principal is allowed, and must be recorded; an unpaid inflated record is rejected.
        val better = priced(base, c(ctx, kind).yieldOrderPricing, Seq(1000L, 0L), yieldAmount + 1L)
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

    property(s"priced order $kind: NAV and yield both support on-close maturity") {
      withCtx { ctx =>
        val base = flow(ctx, kind, OnClose, duration = 262800L)
        val amount = if (kind == ErgBond) ceilDiv(BigInt(base.data(0)) * 10L, 11L) else 637L
        val y = priced(base, c(ctx, kind).yieldOrderPricing, Seq(1000L, 0L), amount)
        filled(y)
        val nav = priced(base, c(ctx, kind).navOrderPricing, Seq(ctx.getHeight.toLong, 0L), base.data(0))
        filled(nav)
        val negative = priced(base, c(ctx, kind).yieldOrderPricing, Seq(-1L, 0L), amount)
        rejectsAtSigning(negative.holder, standardFillTx(negative))
      }
    }

    property(s"priced order $kind: on-close NAV follows the moving endpoint while yield retains a constant duration") {
      withCtx { ctx =>
        val reference = ctx.getHeight.toLong
        val scale = if (kind == ErgBond) Parameters.OneErg else 100L
        val base = moveFill(flow(ctx, kind, OnClose, duration = 262800L,
          principalValue = if (kind == ErgBond) 100L * scale else minBox,
          principalAssets = if (kind == ErgBond) Seq.empty else Seq(Token(SigUSD, 100L * scale)),
          repayment = 110L * scale, expiry = reference + 500000L), (reference + 131400L).toInt)
        val navAmount = if (kind == ErgBond) 103333333334L else 10334L
        val nav = priced(base, c(ctx, kind).navOrderPricing, Seq(reference, 0L), navAmount)
        filled(nav)
        val short = priced(base, c(ctx, kind).navOrderPricing, Seq(reference, 0L), navAmount - 1L)
        rejectsAtSigning(short.holder, standardFillTx(short))
        filled(priced(base, c(ctx, kind).yieldOrderPricing, Seq(1000L, 0L), 100L * scale))
        base.maturity - base.start shouldBe 262800L
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
      val original = priced(base, c(ctx, ErgBond).navOrderPricing, Seq(ctx.getHeight.toLong + 1L, 0L), base.data(0))
      rejectsAtSigning(original.holder, standardFillTx(original))
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
