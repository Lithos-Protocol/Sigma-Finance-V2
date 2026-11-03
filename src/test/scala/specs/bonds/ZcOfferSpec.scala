package org.lithosprotocol.sigmafi
package specs.bonds

import ksingh.sigmabonds.client.ScriptGenerator
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import scorex.crypto.hash.Blake2b256
import sigma.serialization.ErgoTreeSerializer
import work.lithos.mutations.{Token, UTXO}

import scala.util.Try

/** ZC_Offer, and the whole lender-side chain it joins: mint, offer, bond, vault, claim. */
class ZcOfferSpec extends AnyPropSpec with BondSpecBase {

  import BondSpecBase._

  private def otherKind(kind: Kind): Kind = if (kind == ErgBond) TokenBond else ErgBond

  // ═══ TEMPLATE ═════════════════════════════════════════════════════════════

  property("template: every term sits at its own constant position") {
    withCtx { ctx =>
      val positions = offerTemplate(ctx).positions
      positions.keySet.size shouldBe 7
      positions.values.toSet.size shouldBe 7
    }
  }

  property("template: offers with different terms share one template hash") {
    withCtx { ctx =>
      val t = offerTemplate(ctx)
      val erg = t.withTerms(offerTerms(ctx, ErgBond))
      val token = t.withTerms(offerTerms(ctx, TokenBond, collateral = Seq.empty[Token], fee = 0L))
      erg should not equal token
      Seq(erg, token).foreach { tree =>
        Blake2b256.hash(tree.template).toSeq shouldBe t.templateHash.toSeq
        tree.version shouldBe 3
        (tree.header & 0x08).toByte should not be 0.toByte
        // the size bit is honest: the stamped bytes parse back to the same tree
        sigma.VersionContext.withVersions(3, 3) {
          ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(tree.bytes) shouldBe tree
        }
      }
    }
  }

  property("template: terms that collide with each other and with literals keep the template") {
    withCtx { ctx =>
      val t = offerTemplate(ctx)
      val colliding = offerTerms(ctx, ErgBond, fee = 1000000L, collateralValue = 1000000L)
        .copy(repayment = 1000000L)
      Blake2b256.hash(t.withTerms(colliding).template).toSeq shouldBe t.templateHash.toSeq

      // What a recompile does with the same values. Informational only: stamping does not depend on it.
      val recompiled = Try(ctx.compileContract(colliding.constants.foldLeft(ConstantsBuilder.create()) {
        case (b, (n, v)) => b.item(n, v)
      }.build(), ScriptGenerator.mkScript("bonds/P2P/ZC_Offer")).getErgoTree)
      println(s"[offer] recompiling with colliding terms: " + recompiled.map(tree =>
        s"${tree.constants.size} constants vs ${t.tree.constants.size}, same template: " +
          (Blake2b256.hash(tree.template).toSeq == t.templateHash.toSeq)).getOrElse(s"failed: ${recompiled.failed.get.getMessage}"))
    }
  }

  Seq[Kind](ErgBond, TokenBond).foreach { kind =>

    // ═══ CLOSE ══════════════════════════════════════════════════════════════

    property(s"$kind close: a borrower posts the collateral, creates the bond and keeps the principal") {
      withCtx { ctx =>
        val c = closeScenario(ctx, kind)
        accepts(c.prover, closeTx(c)())
      }
    }

    property(s"$kind close: the borrower may post more ERG than the offer asks") {
      withCtx { ctx =>
        val c = closeScenario(ctx, kind)
        accepts(c.prover, closeTx(c)(bondOut = c.bondOut.addValue(Parameters.OneErg)))
      }
    }

    property(s"$kind close: the principal may go anywhere the borrower sends it") {
      withCtx { ctx =>
        val c = closeScenario(ctx, kind)
        accepts(c.prover, closeTx(c)(principalOut = c.principalOut.setContract(otherTree(ctx))))
      }
    }

    property(s"$kind close: rejects a bond under another contract") {
      withCtx { ctx =>
        val c = closeScenario(ctx, kind)
        rejectsAtSigning(c.prover, closeTx(c)(bondOut = c.bondOut.setContract(bondContract(ctx, otherKind(kind)))))
      }
    }

    property(s"$kind close: rejects a bond that does not name this offer in R4") {
      withCtx { ctx =>
        val c = closeScenario(ctx, kind)
        rejectsAtSigning(c.prover, closeTx(c)(bondOut = c.bondOut.withRegNum(4, bytesValue(originOrderId))))
      }
    }

    property(s"$kind close: rejects a borrower hash the bond could not read") {
      withCtx { ctx =>
        val c = closeScenario(ctx, kind)
        rejectsAtSigning(c.prover, closeTx(c)(bondOut = c.bondOut.withRegNum(5, ErgoValue.of(5L))))
      }
    }

    property(s"$kind close: rejects a repayment one unit below the offer") {
      withCtx { ctx =>
        val c = closeScenario(ctx, kind)
        rejectsAtSigning(c.prover, closeTx(c)(bondOut =
          c.bondOut.withRegNum(6, longsValue(c.terms.repayment - 1, c.terms.batchingFee))))
      }
    }

    property(s"$kind close: rejects a batching fee other than the offer's") {
      withCtx { ctx =>
        val c = closeScenario(ctx, kind)
        rejectsAtSigning(c.prover, closeTx(c)(bondOut =
          c.bondOut.withRegNum(6, longsValue(c.terms.repayment, c.terms.batchingFee + 1))))
      }
    }

    property(s"$kind close: rejects a later maturity") {
      withCtx { ctx =>
        val c = closeScenario(ctx, kind)
        rejectsAtSigning(c.prover, closeTx(c)(bondOut = c.bondOut.withRegNum(7, ErgoValue.of(c.terms.maturity + 1))))
      }
    }

    property(s"$kind close: rejects repayment directed away from the vault") {
      withCtx { ctx =>
        val c = closeScenario(ctx, kind)
        rejectsAtSigning(c.prover, closeTx(c)(bondOut = c.bondOut.withRegNum(8, bytesValue(hashOf(borrowerTree(ctx))))))
      }
    }

    property(s"$kind close: rejects a liquidation script the lender did not offer") {
      withCtx { ctx =>
        val c = closeScenario(ctx, kind)
        rejectsAtSigning(c.prover, closeTx(c)(bondOut =
          c.bondOut.withRegNum(9, bytesValue(neverTrue(ctx).hashedValueBytes))))
      }
    }

    property(s"$kind close: rejects one nanoERG less collateral") {
      withCtx { ctx =>
        val c = closeScenario(ctx, kind)
        rejectsAtSigning(c.prover, closeTx(c)(bondOut = c.bondOut.subValue(1L)))
      }
    }

    property(s"$kind close: rejects one collateral token short") {
      withCtx { ctx =>
        val c = closeScenario(ctx, kind)
        rejectsAtSigning(c.prover, closeTx(c)(bondOut = c.bondOut.setTokens(Token(bondAsset, 1L), Token(collateralToken, 99L))))
      }
    }

    property(s"$kind close: rejects collateral the offer did not ask for") {
      withCtx { ctx =>
        val c = closeScenario(ctx, kind)
        val richer = funding(ctx, c.prover, 1, slack, defaultCollateral :+ Token(secondCollateral, 1L))
        rejectsAtSigning(c.prover, closeTx(c)(bondOut = c.bondOut.addToken(Token(secondCollateral, 1L)),
          inputs = Seq(c.offerIn, richer)))
      }
    }

    property(s"$kind close: rejects keeping the offer's unit out of the bond") {
      withCtx { ctx =>
        val c = closeScenario(ctx, kind)
        rejectsAtSigning(c.prover, closeTx(c)(bondOut = c.bondOut.setTokens(defaultCollateral: _*)))
      }
    }

    property(s"$kind close: rejects a bond carrying another token in place of the offer's unit") {
      withCtx { ctx =>
        // the bond would settle into the vault under the borrower's own token, and the borrower would claim it
        val c = closeScenario(ctx, kind)
        val withFake = funding(ctx, c.prover, 1, slack, defaultCollateral :+ Token(otherBondAsset, 1L))
        rejectsAtSigning(c.prover, closeTx(c)(
          bondOut = c.bondOut.setTokens(Token(otherBondAsset, 1L) +: defaultCollateral: _*),
          inputs = Seq(c.offerIn, withFake)))
      }
    }

    property(s"$kind close: rejects burning the offer's unit without the lender's") {
      withCtx { ctx =>
        val c = closeScenario(ctx, kind)
        rejectsAtSigning(c.prover, closeTx(c)(bondOut = c.bondOut.setTokens(defaultCollateral: _*),
          burn = Seq(Token(bondAsset, 1L))))
      }
    }

    property(s"$kind close: an offer whose fee could eat the collateral cannot be closed (feeBounded)") {
      withCtx { ctx =>
        val c = closeScenario(ctx, kind, fee = collateralErg)
        rejectsAtSigning(c.prover, closeTx(c)())
        val negative = closeScenario(ctx, kind, fee = -1L)
        rejectsAtSigning(negative.prover, closeTx(negative)())
      }
    }

    // ═══ CANCEL ═════════════════════════════════════════════════════════════

    property(s"$kind cancel: the holder of the lender's unit burns both units and takes the principal") {
      withCtx { ctx =>
        val c = closeScenario(ctx, kind)
        val l = lender(ctx)
        val unit = funding(ctx, l, 1, Parameters.OneErg, Seq(Token(bondAsset, 1L)))
        accepts(l, build(ctx, Seq(c.offerIn, unit), Seq(c.principalOut.setContract(lenderTree(ctx))), l.getAddress,
          burn = Seq(Token(bondAsset, 2L))))
      }
    }

    property(s"$kind cancel: rejected without the lender's unit") {
      withCtx { ctx =>
        val c = closeScenario(ctx, kind)
        val o = other(ctx)
        rejectsAtSigning(o, build(ctx, Seq(c.offerIn, funding(ctx, o, 1)), Seq(c.principalOut.setContract(otherTree(ctx))),
          o.getAddress, burn = Seq(Token(bondAsset, 1L))))
      }
    }

    // ═══ THE WHOLE CHAIN ════════════════════════════════════════════════════

    /** Generator -> executor mint -> offer -> borrower closes. Returns the lender's unit and the live bond. */
    def mintAndClose(ctx: BlockchainContext) = {
      val terms = offerTerms(ctx, kind)
      val offerScript = offerContract(ctx, terms)
      val (offerValue, principal) = principalOf(kind)
      val m = mintScenario(ctx, genTokens = principal, orderScript = offerScript, principal = offerValue)
      val minted = accepts(m.prover, mintTx(m)())
      val lenderUnit = minted.getOutputsToSpend.get(0)
      val offerIn = spendOutput(minted, 1)

      val b = borrower(ctx)
      val bondOut = bondUTXO(ctx, kind, asset = m.asset, origin = offerIn.id.getBytes)
      val closed = accepts(b, build(ctx, Seq(offerIn, funding(ctx, b, 1, slack, defaultCollateral)),
        Seq(bondOut, UTXO(borrowerTree(ctx), offerIn.value, offerIn.tokens.drop(1))), b.getAddress))
      (m.asset, work.lithos.mutations.InputUTXO(lenderUnit), spendOutput(closed, 0))
    }

    def claim(ctx: BlockchainContext, asset: org.ergoplatform.sdk.ErgoId,
              lenderUnit: work.lithos.mutations.InputUTXO, settled: work.lithos.mutations.InputUTXO) = {
      val holder = lender(ctx)
      accepts(holder, build(ctx, Seq(settled, lenderUnit, funding(ctx, holder, 2)),
        Seq(UTXO(lenderTree(ctx), settled.value, settled.tokens)), holder.getAddress, burn = Seq(Token(asset, 1L))))
    }

    property(s"$kind chain: mint, offer, close, repay, and the lender's unit claims the repayment") {
      withCtx { ctx =>
        val (asset, lenderUnit, bondIn) = mintAndClose(ctx)
        val b = borrower(ctx)
        val repaid = accepts(b, build(ctx, Seq(bondIn, funding(ctx, b, 1, slack, repaymentFunds(kind))),
          Seq(repaymentBox(kind, vault(ctx), bondIn.id, asset),
            tagged(borrowerTree(ctx), bondIn.value, bondIn.id, defaultCollateral)),
          b.getAddress, burn = Seq(Token(asset, 1L))))
        claim(ctx, asset, lenderUnit, spendOutput(repaid, 0))
      }
    }

    property(s"$kind chain: mint, offer, close, an executor liquidates, and the lender's unit claims the collateral") {
      withCtx { ctx =>
        val (asset, lenderUnit, bond) = mintAndClose(ctx)
        val bondIn = bond.withCtxVar(0.toByte, liqVar(matured(ctx)))
        val ex = executor(ctx)
        val liquidated = accepts(ex, buildAt(ctx, maturityOf(ctx), Seq(bondIn),
          Seq(settlement(vault(ctx), bondIn.value - batchingFee, bondIn.id, asset, defaultCollateral)),
          ex.getAddress, burn = Seq(Token(asset, 1L))))
        claim(ctx, asset, lenderUnit, spendOutput(liquidated, 0))
      }
    }
  }
}
