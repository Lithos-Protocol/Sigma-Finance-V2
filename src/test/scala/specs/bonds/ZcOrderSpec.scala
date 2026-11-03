package org.lithosprotocol.sigmafi
package specs.bonds

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import scorex.crypto.hash.Blake2b256
import work.lithos.mutations.{InputUTXO, Token, UTXO}

import scala.collection.JavaConverters._

/**
 * ZC_Order and ZC_OrderOnClose, and the borrower-side chain they start: order, fill (mint), bond, vault,
 * claim. The two contracts differ only in how the bond's maturity is set, so every other property runs
 * against both.
 */
class ZcOrderSpec extends AnyPropSpec with BondSpecBase {

  import BondSpecBase._

  private def otherKind(kind: Kind): Kind = if (kind == ErgBond) TokenBond else ErgBond

  /** The principal box short by one unit of whatever the loan is paid in. */
  private def shortPrincipal(f: Fill): UTXO = f.kind match {
    case ErgBond => f.principalOut.subValue(1L)
    case TokenBond => f.principalOut.setTokens(Token(stableToken, principalTokens - 1))
  }

  // ═══ TEMPLATES ════════════════════════════════════════════════════════════

  property("templates: every term sits at its own constant position, and orders of a style share one hash") {
    withCtx { ctx =>
      val fixed = orderTemplate(ctx)
      val onClose = onCloseTemplate(ctx)
      Seq(
        fixed -> Seq(orderTerms(ctx, ErgBond), orderTerms(ctx, TokenBond, fee = 0L)),
        onClose -> Seq(onCloseTerms(ctx, ErgBond), onCloseTerms(ctx, TokenBond, fee = 0L, duration = 30))
      ).foreach { case (t, terms) =>
        t.positions.keySet.size shouldBe 8
        t.positions.values.toSet.size shouldBe 8
        val trees = terms.map(t.withTerms)
        trees.distinct.size shouldBe 2
        trees.foreach { tree =>
          Blake2b256.hash(tree.template).toSeq shouldBe t.templateHash.toSeq
          (tree.header & 0x08).toByte should not be 0.toByte
        }
      }
      Seq(fixed.templateHash, onClose.templateHash, offerTemplate(ctx).templateHash).map(_.toSeq).distinct.size shouldBe 3
    }
  }

  // ═══ ON-CLOSE MATURITY ════════════════════════════════════════════════════

  Seq[Kind](ErgBond, TokenBond).foreach { kind =>

    property(s"OnClose $kind fill: a fill built at height h stays valid through h + 8, as in V1") {
      withCtx { ctx =>
        val f = fillScenario(ctx, kind, style = OnClose)
        accepts(f.prover, fillTx(f)(height = f.height))
        accepts(f.prover, fillTx(f)(height = f.height + 8))
        rejectsAtSigning(f.prover, fillTx(f)(height = f.height + 9))
        rejectsAtSigning(f.prover, fillTx(f)(height = f.height - 1))
      }
    }

    property(s"OnClose $kind fill: the lender may stamp a maturity up to 8 blocks short, and no shorter") {
      withCtx { ctx =>
        val f = fillScenario(ctx, kind, style = OnClose)
        accepts(f.prover, fillTx(f)(bondOut = f.bondOut.withRegNum(7, ErgoValue.of(f.maturity - 8))))
        rejectsAtSigning(f.prover, fillTx(f)(bondOut = f.bondOut.withRegNum(7, ErgoValue.of(f.maturity - 9))))
        rejectsAtSigning(f.prover, fillTx(f)(bondOut = f.bondOut.withRegNum(7, ErgoValue.of(f.maturity + 1))))
      }
    }

    property(s"OnClose $kind fill: the 30-block minimum survives stamping the template") {
      withCtx { ctx =>
        // `CONST_DURATION >= 30` alone would be folded away when the template compiles at its sentinel,
        // and a 29-block order stamped from it would fill
        accepts(fillScenario(ctx, kind, style = OnClose, duration = 30).prover,
          fillTx(fillScenario(ctx, kind, style = OnClose, duration = 30))())
        val short = fillScenario(ctx, kind, style = OnClose, duration = 29)
        rejectsAtSigning(short.prover, fillTx(short)())
      }
    }

    property(s"FixedHeight $kind fill: needs a full block below maturity for the borrower to repay") {
      withCtx { ctx =>
        val f = fillScenario(ctx, kind)
        accepts(f.prover, fillTx(f)(height = f.maturity - 2))
        rejectsAtSigning(f.prover, fillTx(f)(height = f.maturity - 1))
      }
    }

    property(s"FixedHeight $kind fill: rejects a maturity other than the order's") {
      withCtx { ctx =>
        val f = fillScenario(ctx, kind)
        rejectsAtSigning(f.prover, fillTx(f)(bondOut = f.bondOut.withRegNum(7, ErgoValue.of(f.maturity - 1))))
      }
    }
  }

  for (style <- Seq[OrderStyle](FixedHeight, OnClose); kind <- Seq[Kind](ErgBond, TokenBond)) {
    val name = s"$style $kind"

    // ═══ FILL ═══════════════════════════════════════════════════════════════

    property(s"$name fill: a lender pays the principal, creates the bond and mints the asset") {
      withCtx { ctx =>
        val f = fillScenario(ctx, kind, style = style)
        val signed = accepts(f.prover, fillTx(f)())
        signed.getOutputsToSpend.asScala.flatMap(_.getTokens.asScala).filter(_.getId == f.asset)
          .map(_.getValue.longValue).sum shouldBe 2L
      }
    }

    property(s"$name fill: eip-4 metadata comes from the lender's unit, the first output carrying the asset") {
      withCtx { ctx =>
        val f = fillScenario(ctx, kind, style = style)
        val outs = accepts(f.prover, fillTx(f)()).getOutputsToSpend.asScala
        val issuing = outs.indexWhere(_.getTokens.asScala.exists(_.getId == f.asset))
        issuing shouldBe 0
        new String(outs(issuing).getRegisters.get(0).getValue.asInstanceOf[sigma.Coll[Byte]].toArray, "UTF-8") shouldBe tokenName
      }
    }

    property(s"$name fill: the lender chooses where its unit goes") {
      withCtx { ctx =>
        val f = fillScenario(ctx, kind, style = style)
        accepts(f.prover, fillTx(f)(unitOut = f.unitOut.setContract(otherTree(ctx))))
      }
    }

    property(s"$name fill: rejects repayment directed away from the vault, even to the lender's own wallet") {
      withCtx { ctx =>
        val f = fillScenario(ctx, kind, style = style)
        rejectsAtSigning(f.prover, fillTx(f)(bondOut = f.bondOut.withRegNum(8, bytesValue(hashOf(lenderTree(ctx))))))
      }
    }

    property(s"$name fill: rejects a bond under another contract") {
      withCtx { ctx =>
        val f = fillScenario(ctx, kind, style = style)
        rejectsAtSigning(f.prover, fillTx(f)(bondOut = f.bondOut.setContract(bondContract(ctx, otherKind(kind)))))
      }
    }

    property(s"$name fill: rejects a bond that does not name this order in R4") {
      withCtx { ctx =>
        val f = fillScenario(ctx, kind, style = style)
        rejectsAtSigning(f.prover, fillTx(f)(bondOut = f.bondOut.withRegNum(4, bytesValue(originOrderId))))
      }
    }

    property(s"$name fill: rejects a bond that returns the collateral to someone other than the borrower") {
      withCtx { ctx =>
        val f = fillScenario(ctx, kind, style = style)
        rejectsAtSigning(f.prover, fillTx(f)(bondOut = f.bondOut.withRegNum(5, bytesValue(hashOf(lenderTree(ctx))))))
      }
    }

    property(s"$name fill: rejects a repayment one unit above the order") {
      withCtx { ctx =>
        val f = fillScenario(ctx, kind, style = style)
        rejectsAtSigning(f.prover, fillTx(f)(bondOut = f.bondOut.withRegNum(6, longsValue(f.repayment + 1, f.fee))))
      }
    }

    property(s"$name fill: rejects a batching fee other than the order's") {
      withCtx { ctx =>
        val f = fillScenario(ctx, kind, style = style)
        rejectsAtSigning(f.prover, fillTx(f)(bondOut = f.bondOut.withRegNum(6, longsValue(f.repayment, f.fee + 1))))
      }
    }

    property(s"$name fill: rejects a lender address the bond could not read") {
      withCtx { ctx =>
        val f = fillScenario(ctx, kind, style = style)
        rejectsAtSigning(f.prover, fillTx(f)(bondOut = f.bondOut.withRegNum(8, ErgoValue.of(5L))))
      }
    }

    property(s"$name fill: rejects a liquidation script the borrower did not ask for") {
      withCtx { ctx =>
        val f = fillScenario(ctx, kind, style = style)
        rejectsAtSigning(f.prover, fillTx(f)(bondOut = f.bondOut.withRegNum(9, bytesValue(alwaysTrue(ctx).hashedValueBytes))))
      }
    }

    property(s"$name fill: rejects keeping one nanoERG of the collateral") {
      withCtx { ctx =>
        val f = fillScenario(ctx, kind, style = style)
        rejectsAtSigning(f.prover, fillTx(f)(bondOut = f.bondOut.subValue(1L)))
      }
    }

    property(s"$name fill: rejects keeping one collateral token") {
      withCtx { ctx =>
        val f = fillScenario(ctx, kind, style = style)
        rejectsAtSigning(f.prover, fillTx(f)(bondOut = f.bondOut.setTokens(Token(f.asset, 1L), Token(collateralToken, 99L))))
      }
    }

    property(s"$name fill: rejects paying the borrower one unit short") {
      withCtx { ctx =>
        val f = fillScenario(ctx, kind, style = style)
        rejectsAtSigning(f.prover, fillTx(f)(principalOut = shortPrincipal(f)))
      }
    }

    property(s"$name fill: rejects the principal paid to someone other than the borrower") {
      withCtx { ctx =>
        val f = fillScenario(ctx, kind, style = style)
        rejectsAtSigning(f.prover, fillTx(f)(principalOut = f.principalOut.setContract(lenderTree(ctx))))
      }
    }

    property(s"$name fill: rejects a third unit (verifiedMint)") {
      withCtx { ctx =>
        val f = fillScenario(ctx, kind, style = style)
        val third = UTXO(lenderTree(ctx), minBox, Seq(Token(f.asset, 1L)))
        rejectsAtSigning(f.prover, fillTx(f)(outputs = Seq(f.unitOut, f.bondOut, f.principalOut, third)))
      }
    }

    property(s"$name fill: rejects both units in the bond and none for the lender") {
      withCtx { ctx =>
        val f = fillScenario(ctx, kind, style = style)
        rejectsAtSigning(f.prover, fillTx(f)(unitOut = f.unitOut.setTokens(),
          bondOut = f.bondOut.setTokens(Token(f.asset, 2L) +: defaultCollateral: _*)))
      }
    }

    property(s"$name fill: an order whose fee could eat the collateral cannot be filled (feeBounded)") {
      withCtx { ctx =>
        val f = fillScenario(ctx, kind, fee = collateralErg, style = style)
        rejectsAtSigning(f.prover, fillTx(f)())
      }
    }

    property(s"$name fill: an order that is not INPUTS(0) cannot mint, and falls to the borrower-only cancel") {
      withCtx { ctx =>
        val f = fillScenario(ctx, kind, style = style)
        val first = funding(ctx, f.prover, 0, slack, principalOf(kind)._2)
        val orderSecond = inputAt(f.order, ctx, 1)
        val wrongAsset = first.id
        rejectsAtSigning(f.prover, fillTx(f)(
          inputs = Seq(first, orderSecond),
          unitOut = f.unitOut.setTokens(Token(wrongAsset, 1L)),
          bondOut = f.bondOut.setTokens(Token(wrongAsset, 1L) +: defaultCollateral: _*)))
      }
    }

    // ═══ CANCEL ═════════════════════════════════════════════════════════════

    property(s"$name cancel: the borrower takes the collateral back") {
      withCtx { ctx =>
        val f = fillScenario(ctx, kind, style = style)
        val b = borrower(ctx)
        accepts(b, build(ctx, Seq(f.orderIn, funding(ctx, b, 1)),
          Seq(UTXO(borrowerTree(ctx), f.order.value, defaultCollateral)), b.getAddress))
      }
    }

    property(s"$name cancel: rejected without the borrower (borrowerAuth)") {
      withCtx { ctx =>
        val f = fillScenario(ctx, kind, style = style)
        rejectsAtSigning(f.prover, build(ctx, Seq(f.orderIn, f.lenderIn),
          Seq(UTXO(lenderTree(ctx), f.order.value, defaultCollateral)), f.prover.getAddress))
      }
    }

    // ═══ THE WHOLE CHAIN ════════════════════════════════════════════════════

    def fill(ctx: BlockchainContext): (Fill, InputUTXO, InputUTXO) = {
      val f = fillScenario(ctx, kind, style = style)
      val filled = accepts(f.prover, fillTx(f)())
      (f, spendOutput(filled, 0), spendOutput(filled, 1))
    }

    def claim(ctx: BlockchainContext, asset: ErgoId, lenderUnit: InputUTXO, settled: InputUTXO) = {
      val holder = lender(ctx)
      accepts(holder, build(ctx, Seq(settled, lenderUnit, funding(ctx, holder, 2)),
        Seq(UTXO(lenderTree(ctx), settled.value, settled.tokens)), holder.getAddress, burn = Seq(Token(asset, 1L))))
    }

    property(s"$name chain: order, fill, repay, and the lender's unit claims the repayment") {
      withCtx { ctx =>
        val (f, lenderUnit, bondIn) = fill(ctx)
        val b = borrower(ctx)
        val repaid = accepts(b, buildAt(ctx, f.maturity - 1, Seq(bondIn, funding(ctx, b, 1, slack, repaymentFunds(kind))),
          Seq(repaymentBox(kind, vault(ctx), bondIn.id, f.asset),
            tagged(borrowerTree(ctx), bondIn.value, bondIn.id, defaultCollateral)),
          b.getAddress, burn = Seq(Token(f.asset, 1L))))
        claim(ctx, f.asset, lenderUnit, spendOutput(repaid, 0))
      }
    }

    property(s"$name chain: order, fill, a batcher liquidates at maturity for its fee, and the lender's unit claims") {
      withCtx { ctx =>
        val (f, lenderUnit, bond) = fill(ctx)
        val bondIn = bond.withCtxVar(0.toByte, liqVar(matured(ctx)))
        val batcher = executor(ctx)
        val settle = settlement(vault(ctx), bondIn.value - batchingFee, bondIn.id, f.asset, defaultCollateral)
        rejectsAtSigning(batcher, buildAt(ctx, f.maturity - 1, Seq(bondIn), Seq(settle), batcher.getAddress,
          burn = Seq(Token(f.asset, 1L))))
        val liquidated = accepts(batcher, buildAt(ctx, f.maturity, Seq(bondIn), Seq(settle), batcher.getAddress,
          burn = Seq(Token(f.asset, 1L))))
        liquidated.getOutputsToSpend.asScala.filter(_.getErgoTree == contractOf(batcher).ergoTree)
          .map(_.getValue.longValue).sum shouldBe batchingFee - Parameters.MinFee
        claim(ctx, f.asset, lenderUnit, spendOutput(liquidated, 0))
      }
    }
  }
}
