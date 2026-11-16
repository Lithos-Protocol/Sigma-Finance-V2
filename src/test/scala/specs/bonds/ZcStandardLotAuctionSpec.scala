package org.lithosprotocol.sigmafi
package specs.bonds

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

/**
 * ZC_Standard_LotAuction: a basket sold whole at a price falling in a straight line from an opening to a floor, held
 * there until expiry; refunds from expiry on; cancels by the seller. Both currencies, every price to one raw unit.
 */
class ZcStandardLotAuctionSpec extends AnyPropSpec with ZcStandardSpecBase {
  import BondSpecBase._
  import ZcStandardSpecBase._

  private val kinds: Seq[Kind] = Seq(ErgBond, TokenBond)
  private val refundFee: Long = 2000000L
  private val lotToken: ErgoId = collateralToken

  /** Lot terms: prices in the currency's raw units, heights relative to the mocked height. */
  private case class Terms(opening: Long, floor: Long, start: Long, end: Long, expiry: Long) {
    def longs: Seq[Long] = Seq(opening, floor, start, end, expiry, refundFee)
  }

  private def terms(ctx: BlockchainContext, kind: Kind): Terms = {
    val h = ctx.getHeight.toLong
    if (kind == ErgBond) Terms(10000000L, 2000000L, h + 10L, h + 110L, h + 200L) else Terms(1000L, 200L, h + 10L, h + 110L, h + 200L)
  }

  /** An ERG lot keeps its nanoERG with the seller; a token lot sells its nanoERG too. */
  private def lotBox(ctx: BlockchainContext, kind: Kind, t: Terms, tokens: Seq[Token] = Seq(Token(lotToken, 100L)),
                     value: Long = 5000000L, longs: Seq[Long] = null): UTXO =
    UTXO(marketOf(ctx, kind).lotAuction, value, tokens,
      Seq(bytesValue(hashOf(lenderTree(ctx))), longsValue((if (longs == null) t.longs else longs): _*)))

  /** The least payment at `height`: opening, then the straight line, then the floor, rounded up. */
  private def priceAt(t: Terms, height: Long): Long = {
    val at = height.max(t.start).min(t.end)
    val span = BigInt(t.end - t.start)
    ceilDiv(BigInt(t.opening) * span - BigInt(t.opening - t.floor) * (at - t.start), span)
  }

  /** Output 0 pays the seller, tagged with the lot; the buyer takes the lot as change. */
  private def payout(ctx: BlockchainContext, kind: Kind, lot: InputUTXO, paid: Long, to: Contract = null,
                     tag: Array[Byte] = null): UTXO = {
    val regs = Seq(bytesValue(if (tag == null) lot.id.getBytes else tag))
    val seller = if (to == null) lenderTree(ctx) else to
    if (kind == ErgBond) UTXO(seller, lot.value + paid, Seq.empty[Token], regs)
    else UTXO(seller, minBox, Seq(Token(SigUSD, paid)), regs)
  }

  private def buyTx(ctx: BlockchainContext, kind: Kind, lot: UTXO, paid: Long, height: Int, out: UTXO = null): UnsignedTransaction = {
    val in = inputAt(lot, ctx, 0)
    buildAt(ctx, height, Seq(in.withCtxVar(0.toByte, ErgoValue.of(0)),
      funding(ctx, executor(ctx), 9, slack, if (kind == ErgBond) Seq.empty else Seq(Token(SigUSD, 100000L)))),
      Seq(if (out == null) payout(ctx, kind, in, paid) else out), executor(ctx).getAddress)
  }

  for (kind <- kinds) {
    property(s"$kind buy: the price falls in a straight line from the opening to the floor, to one raw unit, then holds") {
      withCtx { ctx =>
        val t = terms(ctx, kind)
        val lot = lotBox(ctx, kind, t)
        // Before the start, at it, a quarter in, halfway, at the end, and held at the floor after it.
        Seq(t.start - 5L, t.start, t.start + 25L, t.start + 50L, t.end, t.end + 50L).foreach { h =>
          val p = priceAt(t, h)
          accepts(executor(ctx), buyTx(ctx, kind, lot, p, h.toInt))
          rejectsAtSigning(executor(ctx), buyTx(ctx, kind, lot, p - 1L, h.toInt))
        }
        priceAt(t, t.end + 50L) shouldBe t.floor
        priceAt(t, t.start - 5L) shouldBe t.opening
      }
    }
  }

  property("buy: the payout goes to the seller, tagged with this lot; a token lot may not hold its own currency") {
    withCtx { ctx =>
      val t = terms(ctx, ErgBond)
      val lot = lotBox(ctx, ErgBond, t)
      val in = inputAt(lot, ctx, 0)
      val h = t.end.toInt
      accepts(executor(ctx), buyTx(ctx, ErgBond, lot, t.floor, h))
      rejectsAtSigning(executor(ctx), buyTx(ctx, ErgBond, lot, t.floor, h, out = payout(ctx, ErgBond, in, t.floor, to = otherTree(ctx))))
      rejectsAtSigning(executor(ctx), buyTx(ctx, ErgBond, lot, t.floor, h, out = payout(ctx, ErgBond, in, t.floor, tag = otherBondAsset.getBytes)))
      // An ERG lot's own nanoERG goes back to the seller beside the price: only the excess pays.
      rejectsAtSigning(executor(ctx), buyTx(ctx, ErgBond, lot, t.floor, h, out = payout(ctx, ErgBond, in, t.floor).subValue(1L)))
      val tt = terms(ctx, TokenBond)
      val holding = lotBox(ctx, TokenBond, tt, tokens = Seq(Token(lotToken, 100L), Token(SigUSD, 5L)))
      rejectsAtSigning(executor(ctx), buyTx(ctx, TokenBond, holding, tt.opening * 10L, tt.end.toInt))
    }
  }

  property("terms: floor at least 1 and at most the opening, a fall that ends after it starts and by the expiry, six longs") {
    withCtx { ctx =>
      val t = terms(ctx, ErgBond)
      // At the start, before any expiry below, so only the terms can refuse the purchase.
      val h = t.start.toInt
      Seq(t.copy(floor = 0L), t.copy(floor = t.opening + 1L), t.copy(end = t.start), t.copy(expiry = t.end - 1L)).foreach { bad =>
        rejectsAtSigning(executor(ctx), buyTx(ctx, ErgBond, lotBox(ctx, ErgBond, bad), t.opening, h))
      }
      accepts(executor(ctx), buyTx(ctx, ErgBond, lotBox(ctx, ErgBond, t.copy(floor = t.opening)), t.opening, h))
      rejectsAtSigning(executor(ctx), buyTx(ctx, ErgBond, lotBox(ctx, ErgBond, t, longs = t.longs.dropRight(1)), t.opening, h))
    }
  }

  property("expiry: buys stop at the expiry, from which anyone refunds the whole lot to the seller, tagged, less the fee") {
    withCtx { ctx =>
      for (kind <- kinds) {
        val t = terms(ctx, kind)
        val lot = lotBox(ctx, kind, t)
        val in = inputAt(lot, ctx, 0)
        accepts(executor(ctx), buyTx(ctx, kind, lot, t.floor, t.expiry.toInt - 1))
        rejectsAtSigning(executor(ctx), buyTx(ctx, kind, lot, t.opening * 10L, t.expiry.toInt))
        def refundTx(out: UTXO, height: Int): UnsignedTransaction =
          buildAt(ctx, height, Seq(in.withCtxVar(0.toByte, ErgoValue.of(0)), funding(ctx, executor(ctx), 9)), Seq(out),
            executor(ctx).getAddress)
        val back = UTXO(lenderTree(ctx), lot.value - refundFee, lot.tokens, Seq(bytesValue(in.id.getBytes)))
        accepts(executor(ctx), refundTx(back, t.expiry.toInt))
        rejectsAtSigning(executor(ctx), refundTx(back, t.expiry.toInt - 1))
        rejectsAtSigning(executor(ctx), refundTx(back.subValue(1L), t.expiry.toInt))
        rejectsAtSigning(executor(ctx), refundTx(back.setTokens(Token(lotToken, 99L)), t.expiry.toInt))
        rejectsAtSigning(executor(ctx), refundTx(back.setContract(otherTree(ctx)), t.expiry.toInt))
      }
    }
  }

  property("cancel: the seller takes the lot back at any height and nobody else can") {
    withCtx { ctx =>
      val t = terms(ctx, ErgBond)
      val lot = lotBox(ctx, ErgBond, t)
      def cancel(by: ErgoProver): UnsignedTransaction =
        build(ctx, Seq(inputAt(lot, ctx, 0), funding(ctx, by, 5)), Seq(UTXO(contractOf(by), lot.value, lot.tokens)), by.getAddress)
      accepts(lender(ctx), cancel(lender(ctx)))
      rejectsAtSigning(other(ctx), cancel(other(ctx)))
    }
  }
}
