package org.lithosprotocol.sigmafi
package specs.bonds

import org.lithosprotocol.sigmafi.client.ScriptGenerator
import org.lithosprotocol.sigmafi.standard.{Market => StdMarket}
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import sigma.Colls
import org.lithosprotocol.sigmafi.mutations.{Contract, InputUTXO, Token, UTXO}

object ZcStandardOfferIsolationSpec {
  private var variant: Option[Contract] = None

  /** Same offer rules, a different priced-order binding: both deployments accept the same new bond and shares. */
  def otherDeployment(ctx: BlockchainContext): Contract = synchronized {
    variant.getOrElse {
      val set = ZcStandardSpecBase.compiled(ctx)
      val m = set.market(StdMarket.Erg)
      val constants = ConstantsBuilder.create()
        .item("CONST_REPAYMENT_TOKEN", Colls.fromArray(Array.emptyByteArray))
        .item("CONST_ORDER_HASH", Colls.fromArray(m.order.hashedPropBytes))
        .item("CONST_PRICED_ORDER_HASH", Colls.fromArray(Array.fill[Byte](32)(42.toByte)))
        .item("CONST_BOND_HASH", Colls.fromArray(m.bond.hashedPropBytes))
        .item("CONST_DUTCH_MINT_HASH", Colls.fromArray(m.dutchMint.hashedPropBytes)).build()
      val c = Contract.fromErgoContract(ctx.compileContract(constants,
        ScriptGenerator.mkScript("bonds/P2P/ZC_Standard_LendOffer")))
      variant = Some(c)
      c
    }
  }
}

class ZcStandardOfferIsolationSpec extends AnyPropSpec with ZcStandardSpecBase {
  import BondSpecBase._
  import ZcStandardSpecBase._

  property("different offer deployments cannot charge one new bond to both continuations") {
    withCtx { ctx =>
      val f = flow(ctx, ErgBond)
      val aTree = marketOf(ctx, ErgBond).offer
      val bTree = ZcStandardOfferIsolationSpec.otherDeployment(ctx)
      aTree.ergoTreeHex should not be bTree.ergoTreeHex
      val capital = 20L * Parameters.OneErg
      val regs = Seq(bytesValue(hashOf(lenderTree(ctx))),
        longsValue(0L, 1L, 100000L, batchingFee, f.fee, 13000L, ctx.getHeight + 5000L,
          1L, 30L, 0L, 1000000L), bytesValue(alwaysTrue(ctx).hashedValueBytes),
        longsValue(), textsValue(), bytesValue(Array.emptyByteArray))
      val offers = Seq(aTree, bTree).map(t => UTXO(t, capital, Seq.empty[Token], regs))
      val spent = batchingFee + minBox + f.fee + f.data(1)
      val next = offers.zipWithIndex.map { case (o, i) => offerSuccessor(o.subValue(spent), inputAt(o, ctx, i + 1).id) }
      val ins = offers.zipWithIndex.map { case (o, i) =>
        inputAt(o, ctx, i + 1).withCtxVar(0.toByte, ErgoValue.of(3 + i))
          .withCtxVar(1.toByte, liqVar(alwaysTrue(ctx)))
      }
      val attack = buildAt(ctx, f.height, Seq(f.orderIn) ++ ins,
        Seq(offerDelivery(f.unit, ins.head.id), f.bond, f.principal) ++ next, executor(ctx).getAddress)
      rejectsAtSigning(executor(ctx), attack)
      // Each deployment is usable on its own, with identical economics and delivery.
      ins.zip(next).foreach { case (in, rest) =>
        accepts(executor(ctx), buildAt(ctx, f.height,
          Seq(f.orderIn, in.setCtxVars(ContextVar.of(0.toByte, ErgoValue.of(3)),
            ContextVar.of(1.toByte, liqVar(alwaysTrue(ctx))))),
          Seq(offerDelivery(f.unit, in.id), f.bond, f.principal, rest), executor(ctx).getAddress))
      }
    }
  }

  for (priced <- Seq(false, true); mixed <- Seq(false, true)) {
    property(s"batch priced=$priced mixed=$mixed: same-owner offers and partial asks use distinct deliveries and continuations") {
      withCtx { ctx =>
        val f = flow(ctx, ErgBond)
        val fill = filled(f)
        val state = spendOutput(fill, 1)
        val request = inputAt(UTXO(marketOf(ctx, ErgBond).dutchMint, 6000000L, Seq(Token(f.asset, 1L)),
          eip4() ++ Seq(bytesValue(hashOf(lenderTree(ctx))), longsValue(1000L, 2000000L, 2000000L, 15000L, 10000L, 300L, 500L, 100L),
            textsValue("Shares", "Bond shares", "0"))), ctx, 10)
        val market = marketOf(ctx, ErgBond)
        val ask = UTXO(if (priced) market.pricedAsk else market.saleOrder, minBox,
          Seq(Token(request.id, 10L)),
          Seq(bytesValue(hashOf(otherTree(ctx))), longsValue(1000000L, 1L, 1L, ctx.getHeight + 5000L)) ++
            (if (priced) Seq(bytesValue(alwaysTrue(ctx).hashedValueBytes), bytesValue(Array.emptyByteArray)) else Seq.empty))
        val offer = UTXO(market.offer, 20L * Parameters.OneErg, Seq.empty[Token],
          Seq(bytesValue(hashOf(lenderTree(ctx))),
            longsValue(0L, 1L, 100000L, batchingFee, f.fee, 13000L, ctx.getHeight + 5000L, 1L, 30L, 0L, 1000000L),
            bytesValue(alwaysTrue(ctx).hashedValueBytes), longsValue(), textsValue(), bytesValue(Array.emptyByteArray)))
        val offers = Seq(offer, if (mixed) offer.setContract(ZcStandardOfferIsolationSpec.otherDeployment(ctx)) else offer)
        val rawAsks = (0 to 1).map(i => inputAt(ask, ctx, i))
        val rawOffers = offers.zipWithIndex.map { case (o, i) => inputAt(o, ctx, i + 2) }
        val deliveries = rawOffers.map(in => offerDelivery(UTXO(lenderTree(ctx), minBox, Seq(Token(request.id, 1L))), in.id))
        val payouts = rawAsks.map(in => UTXO(otherTree(ctx), minBox, Seq.empty[Token],
          Seq(bytesValue(in.id.getBytes), ErgoValue.of(1L))))
        val continuations = rawOffers.map(in => offerSuccessor(in.toUTXO.subValue(batchingFee + 2L * minBox), in.id))
        val askNext = rawAsks.map(in => ask.setTokens(Token(request.id, 9L))
          .withRegNum(if (priced) 8 else 6, bytesValue(in.id.getBytes)))
        val outputs = deliveries ++ payouts ++ continuations ++ askNext

        def tx(sharedDelivery: Boolean = false, sharedOffer: Boolean = false, sharedAsk: Boolean = false,
               outs: Seq[UTXO] = outputs): UnsignedTransaction = {
          val asks = rawAsks.zipWithIndex.map { case (in, i) =>
            val base = in.withCtxVar(0.toByte, ErgoValue.of(2 + i))
              .withCtxVar(1.toByte, ErgoValue.of(if (sharedAsk) 6 else 6 + i))
            if (priced) base.withCtxVar(2.toByte, liqVar(alwaysTrue(ctx))) else base
          }
          val bids = rawOffers.zipWithIndex.map { case (in, i) =>
            in.withCtxVar(0.toByte, ErgoValue.of(if (sharedOffer) 4 else 4 + i))
              .withCtxVar(1.toByte, liqVar(alwaysTrue(ctx))).withCtxVar(2.toByte, ErgoValue.of(i))
              .withCtxVar(3.toByte, ErgoValue.of(0)).withCtxVar(4.toByte, bytesValue(f.orderIn.bytes))
              .withCtxVar(5.toByte, ErgoValue.of(if (sharedDelivery) 0 else i))
              .withCtxVar(6.toByte, bytesValue(request.bytes)).withCtxVar(7.toByte, ErgoValue.of(2 + i))
          }
          buildAt(ctx, f.height + 20, asks ++ bids :+ funding(ctx, executor(ctx), 9), outs,
            executor(ctx).getAddress, Seq(state))
        }

        accepts(executor(ctx), tx())
        rejectsAtSigning(executor(ctx), tx(sharedDelivery = true))
        rejectsAtSigning(executor(ctx), tx(sharedOffer = true))
        rejectsAtSigning(executor(ctx), tx(sharedAsk = true))
        rejectsAtSigning(executor(ctx), tx(outs = outputs.updated(1,
          deliveries(1).withRegNum(7, bytesValue(rawOffers.head.id.getBytes)))))
        rejectsAtSigning(executor(ctx), tx(outs = outputs.updated(5,
          continuations(1).withRegNum(6, bytesValue(alwaysTrue(ctx).hashedValueBytes)))))
      }
    }
  }
}
