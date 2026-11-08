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

    property(s"$kind repay: the borrower repays before maturity, without liquidation context variable 0") {
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

    property(s"$kind repay: reserve is refunded to the vault and cannot be diverted to borrower") {
      withCtx { ctx =>
        val r = repayScenario(ctx, kind)
        val signed = accepts(r.prover, repayTx(r)())
        signed.getOutputsToSpend.get(1).getValue shouldBe collateralErg
        rejectsAtSigning(r.prover, repayTx(r)(settleOut = r.settleOut.subValue(1L),
          returnOut = r.returnOut.addValue(1L)))
        rejectsAtSigning(r.prover, repayTx(r)(settleOut = r.settleOut.subValue(batchingFee),
          returnOut = r.returnOut.addValue(batchingFee)))
      }
    }

    property(s"$kind settlement: path markers must be present, typed Byte and match the executed branch") {
      withCtx { ctx =>
        val r = repayScenario(ctx, kind)
        val l = liquidateScenario(ctx, kind)
        for (bad <- Seq(ErgoValue.of(1.toByte), ErgoValue.of(2.toByte), ErgoValue.of(0))) {
          rejectsAtSigning(r.prover, repayTx(r)(settleOut = r.settleOut.withRegNum(6, bad)))
        }
        for (bad <- Seq(ErgoValue.of(0.toByte), ErgoValue.of(2.toByte), ErgoValue.of(1))) {
          rejectsAtSigning(l.prover, liquidateTx(l)(settleOut = l.settleOut.withRegNum(6, bad)))
        }
        rejectsAtSigning(r.prover, repayTx(r)(settleOut = r.settleOut.setRegs(r.settleOut.registers.take(2): _*)))
        rejectsAtSigning(l.prover, liquidateTx(l)(settleOut = l.settleOut.setRegs(l.settleOut.registers.take(2): _*)))
        accepts(r.prover, repayTx(r)()).getOutputsToSpend.get(0).getRegisters.get(2).getValue shouldBe 0.toByte
        accepts(l.prover, liquidateTx(l)()).getOutputsToSpend.get(0).getRegisters.get(2).getValue shouldBe 1.toByte
      }
    }

    property(s"$kind maturity: Long heights above Int.MaxValue do not wrap or narrow") {
      withCtx { ctx =>
        val r = repayScenario(ctx, kind)
        val high = r.bond.withRegNum(6, longsValue(repaymentOf(kind), batchingFee, principalOf(kind)._1, Long.MaxValue))
        val input = inputAt(high, ctx, 0).withCtxVar(1.toByte, ErgoValue.of(0)).withCtxVar(2.toByte, ErgoValue.of(1))
        val settle = repaymentBox(kind, vault(ctx), input.id)
        val ret = tagged(borrowerTree(ctx), collateralErg, input.id, defaultCollateral)
        accepts(r.prover, repayTx(r)(inputs = Seq(input, r.payerIn), outputs = Seq(settle, ret)))
        val liquidation = input.setCtxVars(ContextVar.of(0.toByte, liqVar(matured(ctx))), ContextVar.of(1.toByte, ErgoValue.of(0)))
        val payout = settlement(vault(ctx), collateralErg, input.id, bondAsset, defaultCollateral)
        rejectsAtSigning(r.prover, liquidateTx(liquidateScenario(ctx, kind))(
          inputs = Seq(liquidation, r.payerIn), outputs = Seq(payout)))
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

    property(s"$kind mapping: repayment outputs can be reversed independently of input position") {
      withCtx { ctx =>
        val r = repayScenario(ctx, kind)
        val mapped = r.bondIn.setCtxVars(ContextVar.of(1.toByte, ErgoValue.of(1)), ContextVar.of(2.toByte, ErgoValue.of(0)))
        accepts(r.prover, repayTx(r)(inputs = Seq(r.payerIn, mapped), outputs = Seq(r.returnOut, r.settleOut)))
      }
    }

    property(s"$kind mapping: liquidation checks the selected vault economics, not the input position") {
      withCtx { ctx =>
        val l = liquidateScenario(ctx, kind)
        val mapped = l.bondIn.setCtxVars(ContextVar.of(0.toByte, liqVar(matured(ctx))), ContextVar.of(1.toByte, ErgoValue.of(1)))
        val filler = UTXO(contractOf(l.prover), minBox)
        val fund = funding(ctx, l.prover, 1, slack, defaultCollateral)
        accepts(l.prover, liquidateTx(l)(inputs = Seq(mapped, fund), outputs = Seq(filler, l.settleOut)))
        // A correctly funded decoy at selfBoxIndex must not cover an underpaid selected vault.
        val decoy = l.settleOut.setContract(contractOf(l.prover))
        rejectsAtSigning(l.prover, liquidateTx(l)(inputs = Seq(mapped, fund),
          outputs = Seq(decoy, l.settleOut.subValue(1L))))
      }
    }

    property(s"$kind mapping: missing, mistyped and out-of-range required indices reject") {
      withCtx { ctx =>
        val r = repayScenario(ctx, kind)
        val valid = Seq(ContextVar.of(1.toByte, ErgoValue.of(0)), ContextVar.of(2.toByte, ErgoValue.of(1)))
        for (tag <- Seq(1.toByte, 2.toByte)) {
          val others = valid.filterNot(_.getId == tag)
          val invalid = Seq(None, Some(ErgoValue.of(-1)), Some(ErgoValue.of(1000)), Some(ErgoValue.of(0L)))
          invalid.foreach { value =>
            val vars = others ++ value.map(v => ContextVar.of(tag, v))
            rejectsAtSigning(r.prover, repayTx(r)(inputs = Seq(r.bondIn.setCtxVars(vars: _*), r.payerIn)))
          }
        }
      }
    }

    property(s"$kind mapping: liquidation rejects missing, mistyped and out-of-range settlement indices") {
      withCtx { ctx =>
        val l = liquidateScenario(ctx, kind)
        val expr = ContextVar.of(0.toByte, liqVar(matured(ctx)))
        for (value <- Seq(None, Some(ErgoValue.of(-1)), Some(ErgoValue.of(1000)), Some(ErgoValue.of(0L)))) {
          val vars = Seq(expr) ++ value.map(v => ContextVar.of(1.toByte, v))
          rejectsAtSigning(l.prover, liquidateTx(l)(inputs = Seq(l.bondIn.setCtxVars(vars: _*))))
        }
      }
    }

    property(s"$kind mapping: one bond cannot share its settlement and collateral-return output") {
      withCtx { ctx =>
        val p = executor(ctx)
        val collateral = if (kind == ErgBond) Seq.empty[Token] else Seq(Token(stableToken, repaymentTokens))
        val raw = inputAt(bondUTXO(ctx, kind, borrowerScript = vault(ctx), collateral = collateral), ctx, 0)
        val mapped = raw.withCtxVar(1.toByte, ErgoValue.of(0)).withCtxVar(2.toByte, ErgoValue.of(1))
        val shared = settlement(vault(ctx), collateralErg, raw.id, bondAsset, collateral, path = 0.toByte)
        val fund = funding(ctx, p, 1, slack, repaymentFunds(kind))
        accepts(p, build(ctx, Seq(mapped, fund), Seq(shared, shared), p.getAddress, burn = Seq(Token(bondAsset, 1L))))
        rejectsAtSigning(p, build(ctx, Seq(raw.withCtxVar(1.toByte, ErgoValue.of(0)).withCtxVar(2.toByte, ErgoValue.of(0)), fund),
          Seq(shared), p.getAddress, burn = Seq(Token(bondAsset, 1L))))
      }
    }

    // ═══ BATCHING / DOUBLE SATISFACTION ═════════════════════════════════════

    property(s"$kind batch: one executor liquidates two bonds and keeps both fees") {
      withCtx { ctx =>
        val maturity = maturityOf(ctx)
        val ins = Seq(bondAsset, otherBondAsset).zipWithIndex.map { case (asset, i) =>
          inputAt(bondUTXO(ctx, kind, asset = asset, maturity = maturity), ctx, i)
            .withCtxVar(0.toByte, liqVar(matured(ctx))).withCtxVar(1.toByte, ErgoValue.of(i))
        }
        val outs = Seq(bondAsset, otherBondAsset).zip(ins).map { case (asset, in) =>
          settlement(vault(ctx), collateralErg, in.id, asset, defaultCollateral)
        }
        val ex = executor(ctx)
        rejectsAtSigning(ex, buildAt(ctx, maturity,
          ins.map(_.setCtxVars(ContextVar.of(0.toByte, liqVar(matured(ctx))), ContextVar.of(1.toByte, ErgoValue.of(0)))), outs, ex.getAddress,
          burn = Seq(Token(bondAsset, 1L), Token(otherBondAsset, 1L))))
        val signed = accepts(ex, buildAt(ctx, maturity, ins, outs, ex.getAddress,
          burn = Seq(Token(bondAsset, 1L), Token(otherBondAsset, 1L))))
        signed.getOutputsToSpend.asScala.filter(_.getErgoTree == contractOf(ex).ergoTree)
          .map(_.getValue.longValue).sum shouldBe 2 * batchingFee - Parameters.MinFee
      }
    }

    property(s"$kind batch: two adjacent bond inputs repay without filler inputs") {
      withCtx { ctx =>
        val payer = other(ctx)
        val a = inputAt(bondUTXO(ctx, kind, asset = bondAsset), ctx, 0).withCtxVar(1.toByte, ErgoValue.of(0)).withCtxVar(2.toByte, ErgoValue.of(1))
        val filler = funding(ctx, payer, 1, slack, repaymentFunds(kind).map(t => Token(t.id, 2 * t.amount)))
        val b = inputAt(bondUTXO(ctx, kind, asset = otherBondAsset, collateral = Seq(Token(secondCollateral, 5L))), ctx, 2).withCtxVar(1.toByte, ErgoValue.of(2)).withCtxVar(2.toByte, ErgoValue.of(3))
        val outs = Seq(
          repaymentBox(kind, vault(ctx), a.id),
          tagged(borrowerTree(ctx), collateralErg, a.id, defaultCollateral),
          repaymentBox(kind, vault(ctx), b.id, otherBondAsset),
          tagged(borrowerTree(ctx), collateralErg, b.id, Seq(Token(secondCollateral, 5L))))
        accepts(payer, build(ctx, Seq(a, b, filler), outs, payer.getAddress,
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
        val a = inputAt(bondUTXO(ctx, kind, asset = bondAsset, borrowerScript = vault(ctx), collateral = aCollateral), ctx, 0).withCtxVar(1.toByte, ErgoValue.of(0)).withCtxVar(2.toByte, ErgoValue.of(1))
        val b = inputAt(bondUTXO(ctx, kind, asset = otherBondAsset, borrowerScript = bob,
          collateral = Seq(Token(secondCollateral, 5L))), ctx, 1).withCtxVar(1.toByte, ErgoValue.of(1)).withCtxVar(2.toByte, ErgoValue.of(2))
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
