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

    property(s"$kind repay: rejects paying the lender one unit short") {
      withCtx { ctx =>
        val r = repayScenario(ctx, kind)
        rejectsAtSigning(r.prover, repayTx(r)(settleOut = repaymentBox(kind, r.lenderScript, r.bondIn.id, short = 1L)))
      }
    }

    property(s"$kind repay: rejects a repayment to a script other than the lender (toVault)") {
      withCtx { ctx =>
        val r = repayScenario(ctx, kind)
        rejectsAtSigning(r.prover, repayTx(r)(settleOut = r.settleOut.setContract(otherTree(ctx))))
      }
    }

    property(s"$kind repay: rejects a repayment tagged for another bond (settlementTagged)") {
      withCtx { ctx =>
        val r = repayScenario(ctx, kind)
        rejectsAtSigning(r.prover, repayTx(r)(settleOut = r.settleOut.setRegs(bytesValue(originOrderId))))
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

    property(s"$kind repay: rejects a bond asset that survives the bond (assetBurned)") {
      withCtx { ctx =>
        // the draft ERG bond accepted this: the payer kept the singleton and could then claim the vault
        val r = repayScenario(ctx, kind, payer = other(ctx))
        rejectsAtSigning(r.prover, repayTx(r)(burn = Seq.empty[Token]))
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

    property(s"$kind liquidate: the agreed script can set a payout below the maturity rule's ERG floor") {
      withCtx { ctx =>
        val l = liquidateScenario(ctx, kind, listed = alwaysTrue(ctx))
        // The listed expression deliberately permits any payout contents. The bond must delegate this.
        accepts(l.prover, liquidateTx(l)(settleOut = l.settleOut.setValue(minBox)))
      }
    }

    property(s"$kind liquidate: even an unrestricted agreed script cannot bypass vault, tags, or burn") {
      withCtx { ctx =>
        val l = liquidateScenario(ctx, kind, listed = alwaysTrue(ctx))
        accepts(l.prover, liquidateTx(l)())
        rejectsAtSigning(l.prover, liquidateTx(l)(settleOut = l.settleOut.setContract(otherTree(ctx))))
        rejectsAtSigning(l.prover, liquidateTx(l)(settleOut =
          settlement(vault(ctx), l.settleOut.value, l.bondIn.id, otherBondAsset, defaultCollateral)))
        rejectsAtSigning(l.prover, liquidateTx(l)(settleOut =
          settlement(vault(ctx), l.settleOut.value, otherBondAsset, bondAsset, defaultCollateral)))
        rejectsAtSigning(l.prover, liquidateTx(l)(burn = Seq.empty[Token]))
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

    property(s"$kind liquidate: rejects collateral sent to a script other than the lender") {
      withCtx { ctx =>
        val l = liquidateScenario(ctx, kind)
        rejectsAtSigning(l.prover, liquidateTx(l)(settleOut = l.settleOut.setContract(otherTree(ctx))))
      }
    }

    property(s"$kind liquidate: rejects a settlement tagged for another bond") {
      withCtx { ctx =>
        val l = liquidateScenario(ctx, kind)
        rejectsAtSigning(l.prover, liquidateTx(l)(settleOut = l.settleOut.setRegs(bytesValue(originOrderId))))
      }
    }

    property(s"$kind liquidate: rejects holding back one collateral token") {
      withCtx { ctx =>
        val l = liquidateScenario(ctx, kind)
        rejectsAtSigning(l.prover, liquidateTx(l)(settleOut = l.settleOut.setTokens(Token(collateralToken, 99L))))
      }
    }

    property(s"$kind liquidate: rejects a bond asset that survives the bond (assetBurned)") {
      withCtx { ctx =>
        val l = liquidateScenario(ctx, kind)
        rejectsAtSigning(l.prover, liquidateTx(l)(burn = Seq.empty[Token]))
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
        rejectsAtSigning(r.prover, repayTx(r)(settleOut = repaymentBox(kind, r.lenderScript, r.bondIn.id, otherBondAsset)))
      }
    }

    property(s"$kind liquidate: rejects a settlement naming another asset in R5 (settlementTagged)") {
      withCtx { ctx =>
        val l = liquidateScenario(ctx, kind)
        rejectsAtSigning(l.prover, liquidateTx(l)(settleOut =
          settlement(l.lenderScript, l.settleOut.value, l.bondIn.id, otherBondAsset, defaultCollateral)))
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
        val signed = accepts(ex, buildAt(ctx, maturity, ins, outs, ex.getAddress,
          burn = Seq(Token(bondAsset, 1L), Token(otherBondAsset, 1L))))
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
        accepts(payer, build(ctx, Seq(a, filler, b), outs, payer.getAddress,
          burn = Seq(Token(bondAsset, 1L), Token(otherBondAsset, 1L))))
      }
    }

    property(s"$kind double satisfaction: one output cannot repay bond B and return bond A's collateral") {
      withCtx { ctx =>
        // A's borrower is the vault, so OUTPUTS(1) has the right script and contents for both A's
        // collateral return and B's repayment. Their distinct R4 tags must still forbid sharing it.
        val bob = otherTree(ctx)
        val payer = executor(ctx)
        val aCollateral = kind match {
          case ErgBond => defaultCollateral
          case TokenBond => Seq(Token(stableToken, repaymentTokens)) // so one box can look like both
        }
        val a = inputAt(bondUTXO(ctx, kind, asset = bondAsset, borrowerScript = vault(ctx), collateral = aCollateral), ctx, 0)
        val b = inputAt(bondUTXO(ctx, kind, asset = otherBondAsset, borrowerScript = bob,
          collateral = Seq(Token(secondCollateral, 5L))), ctx, 1)
        val fund = funding(ctx, payer, 2, slack, repaymentFunds(kind))
        val outs = Seq(
          repaymentBox(kind, vault(ctx), a.id),
          settlement(vault(ctx), collateralErg, b.id, otherBondAsset, aCollateral),
          tagged(bob, collateralErg, b.id, Seq(Token(secondCollateral, 5L))))
        rejectsAtSigning(payer, build(ctx, Seq(a, b, fund), outs, payer.getAddress,
          burn = Seq(Token(bondAsset, 1L), Token(otherBondAsset, 1L))))
      }
    }
  }

  // ═══ TOKEN BOND ONLY ══════════════════════════════════════════════════════

  property("TokenBond repay: rejects repayment in a token other than CONST_TOKEN_ID") {
    withCtx { ctx =>
      val r = repayScenario(ctx, TokenBond)
      val wrong = UTXO(r.lenderScript, minBox, Seq(Token(collateralToken, repaymentTokens)), r.settleOut.registers)
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
