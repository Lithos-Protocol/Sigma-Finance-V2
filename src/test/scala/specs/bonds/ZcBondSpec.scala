package org.lithosprotocol.sigmafi
package specs.bonds

import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Token, UTXO}

import scala.collection.JavaConverters._

/** ZC_ERG_Bond and ZC_Token_Bond. Every property runs against both, except where named. */
class ZcBondSpec extends AnyPropSpec with BondSpecBase {

  import BondSpecBase._

  Seq[Kind](ErgBond, TokenBond).foreach { kind =>

    // ═══ REPAY ══════════════════════════════════════════════════════════════

    property(s"$kind repay: the borrower repays before maturity, with no context variable") {
      withCtx { ctx =>
        val r = repayScenario(ctx, kind)
        accepts(r.prover, repayTx(r)())
      }
    }

    property(s"$kind repay: anyone may repay on the borrower's behalf") {
      withCtx { ctx =>
        val r = repayScenario(ctx, kind, payer = other(ctx))
        accepts(r.prover, repayTx(r)())
      }
    }

    property(s"$kind repay: closes at maturity (HEIGHT < maturityHeight)") {
      withCtx { ctx =>
        val r = repayScenario(ctx, kind)
        rejectsAtSigning(r.prover, repayTx(r)(height = r.maturity))
      }
    }

    property(s"$kind repay: rejects paying the vault one unit short") {
      withCtx { ctx =>
        val r = repayScenario(ctx, kind)
        rejectsAtSigning(r.prover, repayTx(r)(settleOut = repaymentBox(kind, r.vaultScript, r.bondIn.id, short = 1L)))
      }
    }

    property(s"$kind repay: rejects a repayment to a script other than the vault (toVault)") {
      withCtx { ctx =>
        val r = repayScenario(ctx, kind)
        rejectsAtSigning(r.prover, repayTx(r)(settleOut = r.settleOut.setContract(otherTree(ctx))))
      }
    }

    property(s"$kind repay: rejects a repayment tagged for another bond (settlementTagged)") {
      withCtx { ctx =>
        val r = repayScenario(ctx, kind)
        rejectsAtSigning(r.prover, repayTx(r)(settleOut = r.settleOut.setRegs(bytesValue(originOrderId),
          bytesValue(bondAsset.getBytes))))
      }
    }

    property(s"$kind repay: rejects collateral returned to someone other than the borrower") {
      withCtx { ctx =>
        val r = repayScenario(ctx, kind)
        rejectsAtSigning(r.prover, repayTx(r)(returnOut = r.returnOut.setContract(otherTree(ctx))))
      }
    }

    property(s"$kind repay: rejects a return box tagged for another bond") {
      withCtx { ctx =>
        val r = repayScenario(ctx, kind)
        rejectsAtSigning(r.prover, repayTx(r)(returnOut = r.returnOut.setRegs(bytesValue(originOrderId))))
      }
    }

    property(s"$kind repay: rejects returning one nanoERG less collateral") {
      withCtx { ctx =>
        val r = repayScenario(ctx, kind)
        rejectsAtSigning(r.prover, repayTx(r)(returnOut = r.returnOut.subValue(1L)))
      }
    }

    property(s"$kind repay: rejects holding back one collateral token") {
      withCtx { ctx =>
        val r = repayScenario(ctx, kind)
        rejectsAtSigning(r.prover, repayTx(r)(returnOut = r.returnOut.setTokens(Token(collateralToken, 99L))))
      }
    }

    property(s"$kind repay: every token the bond holds is collateral, the first one included") {
      withCtx { ctx =>
        // the bond holds no singleton any more, so nothing at tokens(0) may be skipped on the way back
        val two = Seq(Token(collateralToken, 100L), Token(secondCollateral, 5L))
        val r = repayScenario(ctx, kind, collateral = two)
        accepts(r.prover, repayTx(r)())
        rejectsAtSigning(r.prover, repayTx(r)(returnOut = r.returnOut.setTokens(Token(secondCollateral, 5L)),
          burn = Seq(Token(collateralToken, 100L))))
      }
    }

    // ═══ LIQUIDATE ══════════════════════════════════════════════════════════

    property(s"$kind liquidate: an executor with no inputs of its own keeps the batching fee") {
      withCtx { ctx =>
        val l = liquidateScenario(ctx, kind)
        val signed = accepts(l.prover, liquidateTx(l)())
        val executorTree = contractOf(l.prover).ergoTree
        val kept = signed.getOutputsToSpend.asScala.filter(_.getErgoTree == executorTree).map(_.getValue.longValue).sum
        kept shouldBe batchingFee - Parameters.MinFee
      }
    }

    property(s"$kind liquidate: the lender may liquidate and keep the fee too") {
      withCtx { ctx =>
        val l = liquidateScenario(ctx, kind)
        accepts(lender(ctx), liquidateTx(l)(settleOut = l.settleOut.setValue(l.bond.value - Parameters.MinFee),
          changeTo = lender(ctx).getAddress))
      }
    }

    property(s"$kind liquidate: rejected before the listed script allows it") {
      withCtx { ctx =>
        val l = liquidateScenario(ctx, kind)
        rejectsAtSigning(l.prover, liquidateTx(l)(height = l.maturity - 1))
      }
    }

    property(s"$kind liquidate: rejects a script R8 does not list, even one that passes") {
      withCtx { ctx =>
        val l = liquidateScenario(ctx, kind, ran = alwaysTrue(ctx))
        rejectsAtSigning(l.prover, liquidateTx(l)(height = l.maturity - 1))
      }
    }

    property(s"$kind liquidate: rejects when the listed script is false") {
      withCtx { ctx =>
        val l = liquidateScenario(ctx, kind, listed = neverTrue(ctx))
        rejectsAtSigning(l.prover, liquidateTx(l)())
      }
    }

    property(s"$kind liquidate: rejects keeping one nanoERG more than the batching fee") {
      withCtx { ctx =>
        val l = liquidateScenario(ctx, kind)
        rejectsAtSigning(l.prover, liquidateTx(l)(settleOut = l.settleOut.subValue(1L)))
      }
    }

    property(s"$kind liquidate: a zero fee leaves the executor nothing to keep") {
      withCtx { ctx =>
        val l = liquidateScenario(ctx, kind, fee = 0L)
        rejectsAtSigning(l.prover, liquidateTx(l)(settleOut = l.settleOut.subValue(Parameters.MinFee),
          inputs = Seq(l.bondIn, funding(ctx, l.prover, 1))))
      }
    }

    property(s"$kind liquidate: rejects collateral sent to a script other than the vault") {
      withCtx { ctx =>
        val l = liquidateScenario(ctx, kind)
        rejectsAtSigning(l.prover, liquidateTx(l)(settleOut = l.settleOut.setContract(otherTree(ctx))))
      }
    }

    property(s"$kind liquidate: rejects a settlement tagged for another bond") {
      withCtx { ctx =>
        val l = liquidateScenario(ctx, kind)
        rejectsAtSigning(l.prover, liquidateTx(l)(settleOut = l.settleOut.setRegs(bytesValue(originOrderId),
          bytesValue(bondAsset.getBytes))))
      }
    }

    property(s"$kind liquidate: rejects holding back one collateral token") {
      withCtx { ctx =>
        val l = liquidateScenario(ctx, kind)
        rejectsAtSigning(l.prover, liquidateTx(l)(settleOut = l.settleOut.setTokens(Token(collateralToken, 99L))))
      }
    }

    property(s"$kind liquidate: works when the collateral is ERG only") {
      withCtx { ctx =>
        // the draft token bond threw `Index 0 out of bounds` here: its repay check ran on this path too
        val l = liquidateScenario(ctx, kind, collateral = Seq.empty[Token])
        accepts(l.prover, liquidateTx(l)())
      }
    }

    property(s"$kind repay: rejects a repayment naming another asset in R5 (settlementTagged)") {
      withCtx { ctx =>
        // the vault opens for whatever R5 names, so this would hand the repayment to otherBondAsset's holder
        val r = repayScenario(ctx, kind, payer = other(ctx))
        rejectsAtSigning(r.prover, repayTx(r)(settleOut = repaymentBox(kind, r.vaultScript, r.bondIn.id, otherBondAsset)))
      }
    }

    property(s"$kind liquidate: rejects a settlement naming another asset in R5 (settlementTagged)") {
      withCtx { ctx =>
        val l = liquidateScenario(ctx, kind)
        rejectsAtSigning(l.prover, liquidateTx(l)(settleOut =
          settlement(l.vaultScript, l.settleOut.value, l.bondIn.id, otherBondAsset, defaultCollateral)))
      }
    }

    // ═══ BATCHING / DOUBLE SATISFACTION ═════════════════════════════════════

    property(s"$kind batch: one executor liquidates two bonds and keeps both fees") {
      withCtx { ctx =>
        val maturity = maturityOf(ctx)
        val ins = Seq(bondAsset, otherBondAsset).zipWithIndex.map { case (asset, i) =>
          inputAt(bondUTXO(ctx, kind, asset = asset, maturity = maturity), ctx, i)
            .withCtxVar(0.toByte, liqVar(matured(ctx)))
        }
        val outs = Seq(bondAsset, otherBondAsset).zip(ins).map { case (asset, in) =>
          settlement(vault(ctx), collateralErg - batchingFee, in.id, asset, defaultCollateral)
        }
        val ex = executor(ctx)
        val signed = accepts(ex, buildAt(ctx, maturity, ins, outs, ex.getAddress))
        signed.getOutputsToSpend.asScala.filter(_.getErgoTree == contractOf(ex).ergoTree)
          .map(_.getValue.longValue).sum shouldBe 2 * batchingFee - Parameters.MinFee
      }
    }

    property(s"$kind batch: two repayments in one transaction, with a filler input between them") {
      withCtx { ctx =>
        val payer = other(ctx)
        val a = inputAt(bondUTXO(ctx, kind, asset = bondAsset), ctx, 0)
        val filler = funding(ctx, payer, 1, slack, repaymentFunds(kind).map(t => Token(t.id, 2 * t.amount)))
        val b = inputAt(bondUTXO(ctx, kind, asset = otherBondAsset, collateral = Seq(Token(secondCollateral, 5L))), ctx, 2)
        val outs = Seq(
          repaymentBox(kind, vault(ctx), a.id),
          tagged(borrowerTree(ctx), collateralErg, a.id, defaultCollateral),
          repaymentBox(kind, vault(ctx), b.id, otherBondAsset),
          tagged(borrowerTree(ctx), collateralErg, b.id, Seq(Token(secondCollateral, 5L))))
        accepts(payer, build(ctx, Seq(a, filler, b), outs, payer.getAddress))
      }
    }

    property(s"$kind double satisfaction: one vault box cannot return bond A's collateral and settle bond B") {
      withCtx { ctx =>
        // Bond A names the vault as its borrower, so its collateral return and B's liquidation are both
        // vault boxes holding the same collateral at OUTPUTS(1). Only R4 = SELF.id on both tells them apart:
        // without it, one box would satisfy both bonds and the executor would keep B's whole collateral.
        val payer = executor(ctx)
        val maturity = maturityOf(ctx)
        val a = inputAt(bondUTXO(ctx, kind, asset = bondAsset, borrowerScript = vault(ctx), maturity = maturity), ctx, 0)
        // B matured a block before A, so at maturity - 1 A is still repayable and B already liquidatable
        val b = inputAt(bondUTXO(ctx, kind, asset = otherBondAsset, fee = 0L, maturity = maturity - 1), ctx, 1)
          .withCtxVar(0.toByte, liqVar(matured(ctx)))
        val fund = funding(ctx, payer, 2, slack, repaymentFunds(kind))
        val shared = settlement(vault(ctx), collateralErg, a.id, otherBondAsset, defaultCollateral)
        rejectsAtSigning(payer, buildAt(ctx, maturity - 1, Seq(a, b, fund),
          Seq(repaymentBox(kind, vault(ctx), a.id), shared), payer.getAddress))
      }
    }
  }

  // ═══ TOKEN BOND ONLY ══════════════════════════════════════════════════════

  property("TokenBond repay: rejects repayment in a token other than CONST_TOKEN_ID") {
    withCtx { ctx =>
      val r = repayScenario(ctx, TokenBond)
      val wrong = UTXO(r.vaultScript, minBox, Seq(Token(collateralToken, repaymentTokens)), r.settleOut.registers)
      rejectsAtSigning(r.prover, repayTx(r)(settleOut = wrong,
        inputs = Seq(r.bondIn, funding(ctx, r.prover, 1, slack, Seq(Token(collateralToken, 1000L))))))
    }
  }

  property("TokenBond repay: rejects an extra token beside the repayment") {
    withCtx { ctx =>
      val r = repayScenario(ctx, TokenBond)
      val extra = r.settleOut.addToken(Token(collateralToken, 1L))
      rejectsAtSigning(r.prover, repayTx(r)(settleOut = extra,
        inputs = Seq(r.bondIn, funding(ctx, r.prover, 1, slack, repaymentFunds(TokenBond) :+ Token(collateralToken, 1L)))))
    }
  }
}
