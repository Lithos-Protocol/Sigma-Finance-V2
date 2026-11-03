package org.lithosprotocol.sigmafi
package specs.bonds

import ksingh.sigmabonds.client.ScriptGenerator
import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import scorex.crypto.hash.Blake2b256
import sigma.serialization.ErgoTreeSerializer
import work.lithos.mutations.{InputUTXO, Token, UTXO}

import scala.collection.JavaConverters._
import scala.util.Try

/** ZC_Offer, and the whole lender-side chain it starts: offer, close (mint), bond, vault, claim. */
class ZcOfferSpec extends AnyPropSpec with BondSpecBase {

  import BondSpecBase._

  private def otherKind(kind: Kind): Kind = if (kind == ErgBond) TokenBond else ErgBond

  // ═══ TEMPLATE ═════════════════════════════════════════════════════════════

  property("template: every term sits at its own constant position") {
    withCtx { ctx =>
      val positions = offerTemplate(ctx).positions
      positions.keySet.size shouldBe 10
      positions.values.toSet.size shouldBe 10
    }
  }

  property("template: offers with different terms share one template hash") {
    withCtx { ctx =>
      val t = offerTemplate(ctx)
      val erg = t.withTerms(offerTerms(ctx, ErgBond))
      val token = t.withTerms(offerTerms(ctx, TokenBond, collateral = Seq.empty[Token], fee = 0L,
        lenderScript = otherTree(ctx)).copy(assetName = Array.emptyByteArray))
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
        .copy(repayment = 1000000L, assetDescription = tokenName.getBytes("UTF-8"))
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

    property(s"$kind close: a borrower posts the collateral, the lender gets the one unit, the borrower the principal") {
      withCtx { ctx =>
        val c = closeScenario(ctx, kind)
        val outs = accepts(c.prover, closeTx(c)()).getOutputsToSpend.asScala
        outs.flatMap(_.getTokens.asScala).filter(_.getId == c.asset).map(_.getValue.longValue).sum shouldBe 1L
        outs.head.getErgoTree shouldBe lenderTree(ctx).ergoTree
      }
    }

    property(s"$kind close: eip-4 metadata is the lender's, on the first output carrying the asset") {
      withCtx { ctx =>
        val c = closeScenario(ctx, kind)
        val outs = accepts(c.prover, closeTx(c)()).getOutputsToSpend.asScala
        outs.indexWhere(_.getTokens.asScala.exists(_.getId == c.asset)) shouldBe 0
        def reg(i: Int) = new String(outs.head.getRegisters.get(i).getValue.asInstanceOf[sigma.Coll[Byte]].toArray, "UTF-8")
        (reg(0), reg(1), reg(2)) shouldBe ((tokenName, tokenDescription, "0"))
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

    // ─── the lender's unit ──────────────────────────────────────────────────

    property(s"$kind close: rejects the unit going to anyone but the lender") {
      withCtx { ctx =>
        // the borrower builds the close, so this would let it keep the claim on its own bond
        val c = closeScenario(ctx, kind)
        rejectsAtSigning(c.prover, closeTx(c)(unitOut = c.unitOut.setContract(borrowerTree(ctx))))
      }
    }

    property(s"$kind close: rejects a unit box under 0.001 ERG") {
      withCtx { ctx =>
        val c = closeScenario(ctx, kind)
        rejectsAtSigning(c.prover, closeTx(c)(unitOut = c.unitOut.setValue(minBox - 1)))
      }
    }

    property(s"$kind close: rejects a second unit (verifiedMint)") {
      withCtx { ctx =>
        val c = closeScenario(ctx, kind)
        val second = UTXO(borrowerTree(ctx), minBox, Seq(Token(c.asset, 1L)))
        rejectsAtSigning(c.prover, closeTx(c)(outputs = Seq(c.unitOut, c.bondOut, c.principalOut, second)))
        rejectsAtSigning(c.prover, closeTx(c)(unitOut = c.unitOut.setTokens(Token(c.asset, 2L))))
      }
    }

    property(s"$kind close: rejects the unit anywhere but OUTPUTS(0)") {
      withCtx { ctx =>
        val c = closeScenario(ctx, kind)
        rejectsAtSigning(c.prover, closeTx(c)(outputs = Seq(c.bondOut, c.unitOut, c.principalOut)))
      }
    }

    property(s"$kind close: rejects metadata other than the lender's") {
      withCtx { ctx =>
        val c = closeScenario(ctx, kind)
        Seq(eip4(name = "Bond of Nothing"), eip4(description = "worthless"), eip4(decimals = "2"),
          eip4() :+ utf8("an image link"), eip4().take(2)).foreach { regs =>
          rejectsAtSigning(c.prover, closeTx(c)(unitOut = c.unitOut.setRegs(regs: _*)))
        }
      }
    }

    property(s"$kind close: an offer that is not INPUTS(0) cannot mint, and falls to the lender-only cancel") {
      withCtx { ctx =>
        val c = closeScenario(ctx, kind)
        val first = funding(ctx, c.prover, 0, slack, defaultCollateral)
        val offerSecond = inputAt(c.offer, ctx, 1)
        val wrongAsset = first.id
        rejectsAtSigning(c.prover, closeTx(c)(
          inputs = Seq(first, offerSecond),
          unitOut = c.unitOut.setTokens(Token(wrongAsset, 1L)),
          bondOut = bondUTXO(ctx, kind, asset = wrongAsset)))
      }
    }

    // ─── the bond ───────────────────────────────────────────────────────────

    property(s"$kind close: rejects a bond under another contract") {
      withCtx { ctx =>
        val c = closeScenario(ctx, kind)
        rejectsAtSigning(c.prover, closeTx(c)(bondOut = c.bondOut.setContract(bondContract(ctx, otherKind(kind)))))
      }
    }

    property(s"$kind close: rejects a bond that does not name this offer's asset in R4") {
      withCtx { ctx =>
        // the bond would settle into the vault under an asset the borrower holds, and the borrower would claim it
        val c = closeScenario(ctx, kind)
        rejectsAtSigning(c.prover, closeTx(c)(bondOut = c.bondOut.withRegNum(4, bytesValue(otherBondAsset.getBytes))))
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

    property(s"$kind close: rejects a liquidation script the lender did not offer") {
      withCtx { ctx =>
        val c = closeScenario(ctx, kind)
        rejectsAtSigning(c.prover, closeTx(c)(bondOut =
          c.bondOut.withRegNum(8, bytesValue(neverTrue(ctx).hashedValueBytes))))
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
        rejectsAtSigning(c.prover, closeTx(c)(bondOut = c.bondOut.setTokens(Token(collateralToken, 99L))))
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

    property(s"$kind close: an offer whose fee could eat the collateral cannot be closed (feeBounded)") {
      withCtx { ctx =>
        val c = closeScenario(ctx, kind, fee = collateralErg)
        rejectsAtSigning(c.prover, closeTx(c)())
        val negative = closeScenario(ctx, kind, fee = -1L)
        rejectsAtSigning(negative.prover, closeTx(negative)())
      }
    }

    // ═══ CANCEL ═════════════════════════════════════════════════════════════

    property(s"$kind cancel: the lender takes the principal back") {
      withCtx { ctx =>
        val c = closeScenario(ctx, kind)
        val l = lender(ctx)
        accepts(l, build(ctx, Seq(c.offerIn, funding(ctx, l, 1)),
          Seq(UTXO(lenderTree(ctx), c.offer.value, c.offer.tokens)), l.getAddress))
      }
    }

    property(s"$kind cancel: rejected without the lender (lenderAuth)") {
      withCtx { ctx =>
        val c = closeScenario(ctx, kind)
        val o = other(ctx)
        rejectsAtSigning(o, build(ctx, Seq(c.offerIn, funding(ctx, o, 1)),
          Seq(UTXO(otherTree(ctx), c.offer.value, c.offer.tokens)), o.getAddress))
      }
    }

    // ═══ THE WHOLE CHAIN ════════════════════════════════════════════════════

    /** Offer -> borrower closes. Returns the asset, the lender's unit and the live bond. */
    def close(ctx: BlockchainContext): (ErgoId, InputUTXO, InputUTXO) = {
      val c = closeScenario(ctx, kind)
      val closed = accepts(c.prover, closeTx(c)())
      (c.asset, spendOutput(closed, 0), spendOutput(closed, 1))
    }

    def claim(ctx: BlockchainContext, asset: ErgoId, lenderUnit: InputUTXO, settled: InputUTXO) = {
      val holder = lender(ctx)
      accepts(holder, build(ctx, Seq(settled, lenderUnit, funding(ctx, holder, 2)),
        Seq(UTXO(lenderTree(ctx), settled.value, settled.tokens)), holder.getAddress, burn = Seq(Token(asset, 1L))))
    }

    property(s"$kind chain: offer, close, repay, and the lender's unit claims the repayment") {
      withCtx { ctx =>
        val (asset, lenderUnit, bondIn) = close(ctx)
        val b = borrower(ctx)
        val repaid = accepts(b, build(ctx, Seq(bondIn, funding(ctx, b, 1, slack, repaymentFunds(kind))),
          Seq(repaymentBox(kind, vault(ctx), bondIn.id, asset),
            tagged(borrowerTree(ctx), bondIn.value, bondIn.id, defaultCollateral)),
          b.getAddress))
        claim(ctx, asset, lenderUnit, spendOutput(repaid, 0))
      }
    }

    property(s"$kind chain: offer, close, an executor liquidates, and the lender's unit claims the collateral") {
      withCtx { ctx =>
        val (asset, lenderUnit, bond) = close(ctx)
        val bondIn = bond.withCtxVar(0.toByte, liqVar(matured(ctx)))
        val ex = executor(ctx)
        val liquidated = accepts(ex, buildAt(ctx, maturityOf(ctx), Seq(bondIn),
          Seq(settlement(vault(ctx), bondIn.value - batchingFee, bondIn.id, asset, defaultCollateral)),
          ex.getAddress))
        claim(ctx, asset, lenderUnit, spendOutput(liquidated, 0))
      }
    }
  }
}
