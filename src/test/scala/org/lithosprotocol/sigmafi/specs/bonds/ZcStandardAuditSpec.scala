package org.lithosprotocol.sigmafi
package specs.bonds

import org.lithosprotocol.sigmafi.app.Protocol
import org.lithosprotocol.sigmafi.standard.{Market => StdMarket}
import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import org.lithosprotocol.sigmafi.mutations.{Contract, InputUTXO, Token, UTXO}

/** Regressions for FIRST_REPORT F1-F3, with separately funded honest transactions as controls. */
class ZcStandardAuditSpec extends AnyPropSpec with ZcStandardSpecBase {
  import BondSpecBase._
  import ZcStandardSpecBase._

  private val ergCapital = 20L * Parameters.OneErg

  private def offerBox(ctx: BlockchainContext, policy: Contract): UTXO =
    UTXO(marketOf(ctx, ErgBond).offer, ergCapital, Seq.empty[Token], Seq(
      bytesValue(hashOf(lenderTree(ctx))),
      longsValue(0L, 1L, 100000L, batchingFee, batchingFee, 13000L, ctx.getHeight + 5000L,
        1L, 30L, 0L, 1000000L), bytesValue(policy.hashedValueBytes),
      longsValue(), textsValue(), bytesValue(Array.emptyByteArray)))

  private def issuing(ctx: BlockchainContext, kind: Kind, priced: Boolean): Flow = {
    val f = flow(ctx, kind)
    if (!priced) f else {
      val m = if (kind == ErgBond) StdMarket.Erg else StdMarket.SigUsd
      val policies = ErgoValue.of(sigma.Colls.fromArray(Array(alwaysTrue(ctx).hashedValueBytes, Array.emptyByteArray)
        .map(sigma.Colls.fromArray(_)))(sigma.collRType(sigma.ByteType)),
        ErgoType.collType(org.ergoplatform.appkit.scalaapi.scalaByteType))
      val order = f.order.setContract(ZcStandardSpecBase.compiled(ctx).market(m).pricedOrder)
        .withRegNum(8, policies).withRegNum(9, longsValue())
      val in = inputAt(order, ctx, 0).withCtxVar(2.toByte, liqVar(alwaysTrue(ctx)))
      f.copy(order = order, orderIn = in, unit = f.unit.setTokens(Token(in.id, 1L)),
        bond = f.bond.setTokens(Token(in.id, 2L) +: f.collateral: _*)
          .withRegNum(4, bytesValue(in.id.getBytes)),
        principal = f.principal.withRegNum(4, bytesValue(in.id.getBytes)))
    }
  }

  property("F1: a new-bond policy cannot be redirected to a decoy data input") {
    withCtx { ctx =>
      val policy = marketOf(ctx, ErgBond).policy
      val f = flow(ctx, ErgBond, collateralValue = minBox, collateral = Seq.empty, liq = neverTrue(ctx))
      val offer = offerBox(ctx, policy)
      val offerId = inputAt(offer, ctx, 1).id
      val next = offerSuccessor(offer.subValue(batchingFee + minBox + f.fee + f.data(1)), offerId)
      val decoy = inputAt(UTXO(otherTree(ctx), 100L * Parameters.OneErg, Seq.empty[Token], Seq(
        bytesValue(originOrderId), bytesValue(originOrderId),
        longsValue(1L, 0L, 0L, 0L, 0L, 0L, 0L, 13000L, 30L),
        Protocol.tokensValue(Seq.empty[Token]), bytesValue(oracleRuleOf(ctx, ErgBond).hashedValueBytes),
        longsValue(0L, 0L, 0L))), ctx, 21)
      val base = inputAt(offer, ctx, 1).withCtxVar(0.toByte, ErgoValue.of(3)).withCtxVar(1.toByte, liqVar(policy))
      def tx(in: InputUTXO) = buildAt(ctx, f.height, Seq(f.orderIn, in),
        Seq(offerDelivery(f.unit, offerId), f.bond, f.principal, next), executor(ctx).getAddress, Seq(oracleBox(f), decoy))
      rejectsAtSigning(executor(ctx), tx(base))
      rejectsAtSigning(executor(ctx), tx(base.withCtxVar(3.toByte, ErgoValue.of(1))))

      val good = flow(ctx, ErgBond, liq = oracleRuleOf(ctx, ErgBond))
      accepts(executor(ctx), buildAt(ctx, good.height, Seq(good.orderIn, base),
        Seq(offerDelivery(good.unit, offerId), good.bond, good.principal, next), executor(ctx).getAddress, Seq(oracleBox(good))))
      // The host also rejects ambiguous selectors even with a policy that accepts everything.
      val open = offerBox(ctx, alwaysTrue(ctx))
      val openIn = inputAt(open, ctx, 1).withCtxVar(0.toByte, ErgoValue.of(3))
        .withCtxVar(1.toByte, liqVar(alwaysTrue(ctx))).withCtxVar(3.toByte, ErgoValue.of(1))
      rejectsAtSigning(executor(ctx), buildAt(ctx, f.height, Seq(f.orderIn, openIn),
        Seq(offerDelivery(f.unit, openIn.id), f.bond, f.principal, offerSuccessor(open.setValue(next.value), openIn.id)), executor(ctx).getAddress,
        Seq(oracleBox(f), decoy)))
    }
  }

  for (priced <- Seq(false, true)) {
    property(s"F2a priced=$priced: principal cannot share another order's expiry refund") {
      withCtx { ctx =>
        val x = issuing(ctx, ErgBond, priced)
        val old = flow(ctx, TokenBond, collateral = Seq.empty, expiry = ctx.getHeight + 5L)
        val oldIn = inputAt(old.order, ctx, 4).withCtxVar(0.toByte, ErgoValue.of(2))
        val shared = tagged(borrowerTree(ctx), math.max(x.principal.value, oldIn.value - old.expiryFee), oldIn.id)
        def tx(out: Seq[UTXO], in: InputUTXO) = buildAt(ctx, x.height, Seq(x.orderIn, in, x.fund),
          Seq(x.unit, x.bond) ++ out, x.holder.getAddress)
        rejectsAtSigning(x.holder, tx(Seq(shared), oldIn))
        val refund = tagged(borrowerTree(ctx), oldIn.value - old.expiryFee, oldIn.id)
        accepts(x.holder, tx(Seq(x.principal, refund), oldIn.setCtxVars(ContextVar.of(0.toByte, ErgoValue.of(3)))))
      }
    }

    property(s"F2b priced=$priced: principal cannot share another bond's collateral return") {
      withCtx { ctx =>
        val x = issuing(ctx, ErgBond, priced)
        val y = flow(ctx, TokenBond, collateral = Seq.empty)
        val bond = spendOutput(filled(y), 1).withCtxVar(1.toByte, ErgoValue.of(3))
          .withCtxVar(2.toByte, ErgoValue.of(2))
        val shared = tagged(borrowerTree(ctx), math.max(x.principal.value, bond.value - y.fee), bond.id)
        val settle = vaultBox(y, bond.id, minBox + y.fee, Seq(Token(SigUSD, y.data(0))), 0.toByte)
        def tx(in: InputUTXO, out: Seq[UTXO]) = buildAt(ctx, x.height + 1,
          Seq(x.orderIn, in, x.fund, funding(ctx, x.holder, 7, slack, Seq(Token(SigUSD, y.data(0))))),
          Seq(x.unit, moveFill(x, x.height + 1).bond) ++ out, x.holder.getAddress)
        rejectsAtSigning(x.holder, tx(bond, Seq(shared, settle)))
        accepts(x.holder, tx(bond.setCtxVars(ContextVar.of(1.toByte, ErgoValue.of(3)), ContextVar.of(2.toByte, ErgoValue.of(4))),
          Seq(x.principal, settle, tagged(borrowerTree(ctx), bond.value - y.fee, bond.id))))
      }
    }

    property(s"F2c priced=$priced: principal cannot share the borrower's ask payout") {
      withCtx { ctx =>
        val x = issuing(ctx, ErgBond, priced)
        val price = 5L * Parameters.OneErg
        val ask = UTXO(marketOf(ctx, ErgBond).saleOrder, minBox, Seq(Token(ErgoId.create("5a" * 32), 1L)),
          Seq(bytesValue(hashOf(borrowerTree(ctx))), longsValue(price, 1L, 1L, ctx.getHeight + 100L)))
        val in = inputAt(ask, ctx, 4).withCtxVar(0.toByte, ErgoValue.of(2))
        val pay = UTXO(borrowerTree(ctx), price, Seq.empty[Token],
          Seq(bytesValue(in.id.getBytes), ErgoValue.of(1L)))
        def tx(askIn: InputUTXO, out: Seq[UTXO]) = buildAt(ctx, x.height, Seq(x.orderIn, askIn, x.fund),
          Seq(x.unit, x.bond) ++ out, x.holder.getAddress)
        rejectsAtSigning(x.holder, tx(in, Seq(pay.setValue(math.max(x.principal.value, price)))))
        accepts(x.holder, tx(in.setCtxVars(ContextVar.of(0.toByte, ErgoValue.of(3))), Seq(x.principal, pay)))
      }
    }

    property(s"F2d priced=$priced: principal cannot share the borrower's lend-offer refund") {
      withCtx { ctx =>
        val x = issuing(ctx, ErgBond, priced)
        val offer = offerBox(ctx, alwaysTrue(ctx)).withRegNum(4, bytesValue(hashOf(borrowerTree(ctx))))
          .withRegNum(5, longsValue(0L, 1L, 100000L, batchingFee, batchingFee, 13000L,
            ctx.getHeight + 5L, 1L, 30L, 0L, 1000000L))
        val in = inputAt(offer, ctx, 4).withCtxVar(0.toByte, ErgoValue.of(2))
        val refund = tagged(borrowerTree(ctx), offer.value - batchingFee, in.id)
        def tx(offerIn: InputUTXO, out: Seq[UTXO]) = buildAt(ctx, x.height, Seq(x.orderIn, offerIn, x.fund),
          Seq(x.unit, x.bond) ++ out, x.holder.getAddress)
        rejectsAtSigning(x.holder, tx(in, Seq(refund.setValue(math.max(x.principal.value, refund.value)))))
        accepts(x.holder, tx(in.setCtxVars(ContextVar.of(0.toByte, ErgoValue.of(3))), Seq(x.principal, refund)))
      }
    }

    for (kind <- Seq[Kind](ErgBond, TokenBond)) {
      property(s"F2 $kind priced=$priced: principal requires its own order id, including for token loans") {
        withCtx { ctx =>
          val f = issuing(ctx, kind, priced)
          filled(f)
          rejectsAtSigning(f.holder, standardFillTx(f, principal = f.principal.withRegNum(4, bytesValue(originOrderId))))
          rejectsAtSigning(f.holder, standardFillTx(f, principal = f.principal.setRegs()))
        }
      }
    }
  }

  for (priced <- Seq(false, true)) {
    property(s"F3 priced=$priced: two identical asks cannot share a continuation; full fills can be batched") {
      withCtx { ctx =>
        val asset = ErgoId.create("5a" * 32)
        val m = marketOf(ctx, ErgBond)
        val regs = Seq(bytesValue(hashOf(otherTree(ctx))), longsValue(1000003L, 2L, 1L, ctx.getHeight + 100L)) ++
          (if (priced) Seq(bytesValue(alwaysTrue(ctx).hashedValueBytes), bytesValue(Array.emptyByteArray)) else Seq.empty)
        val ask = UTXO(if (priced) m.pricedAsk else m.saleOrder, minBox, Seq(Token(asset, 10L)), regs)
        def in(index: Int, payout: Int, partial: Boolean): InputUTXO = {
          val base = inputAt(ask, ctx, index).withCtxVar(0.toByte, ErgoValue.of(payout))
          val continued = if (partial) base.withCtxVar(1.toByte, ErgoValue.of(2)) else base
          if (priced) continued.withCtxVar(2.toByte, liqVar(alwaysTrue(ctx))) else continued
        }
        def pay(id: ErgoId, units: Long) = UTXO(otherTree(ctx), ceilDiv(BigInt(units) * 1000003L, 2L),
          Seq.empty[Token], Seq(bytesValue(id.getBytes), ErgoValue.of(units)))
        val (a, b) = (in(0, 0, partial = true), in(3, 1, partial = true))
        def tx(inputs: Seq[InputUTXO], outputs: Seq[UTXO]) = buildAt(ctx, ctx.getHeight + 10,
          inputs :+ funding(ctx, executor(ctx), 9), outputs, executor(ctx).getAddress)
        rejectsAtSigning(executor(ctx), tx(Seq(a, b), Seq(pay(a.id, 1L), pay(b.id, 1L), ask.setTokens(Token(asset, 9L)))))
        accepts(executor(ctx), tx(Seq(in(0, 0, partial = false), in(3, 1, partial = false)),
          Seq(pay(a.id, 10L), pay(b.id, 10L))))
        // A single partial fill still works and pays for exactly the one unit taken.
        accepts(executor(ctx), tx(Seq(a), Seq(pay(a.id, 1L),
          UTXO(contractOf(executor(ctx)), minBox), ask.setTokens(Token(asset, 9L))
            .withRegNum(if (priced) 8 else 6, bytesValue(a.id.getBytes)))))
      }
    }
  }
}
