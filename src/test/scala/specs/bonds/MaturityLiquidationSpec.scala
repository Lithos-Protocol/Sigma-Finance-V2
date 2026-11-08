package org.lithosprotocol.sigmafi
package specs.bonds

import ksingh.sigmabonds.client.ScriptGenerator
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import scorex.crypto.hash.Blake2b256
import sigma.serialization.ValueSerializer
import work.lithos.mutations.Token

import scala.collection.JavaConverters._

/**
 * MaturityLiquidation against SigmaFi V1. V1's bond opened liquidation at `HEIGHT >= maturityHeight`,
 * to anyone, sending the collateral to the lender; repayment was open strictly below that height.
 */
class MaturityLiquidationSpec extends AnyPropSpec with BondSpecBase {

  import BondSpecBase._

  property("the client's bytes are what a bond runs, and its hash is what R8 holds") {
    withCtx { ctx =>
      val script = ScriptGenerator.mkLiquidationScript(ctx, "MaturityLiquidation")
      script.hash.toSeq shouldBe Blake2b256.hash(script.bytes).toSeq
      script.hash.toSeq shouldBe matured(ctx).hashedValueBytes.toSeq
      sigma.VersionContext.withVersions(3, 3) {
        ValueSerializer.deserialize(script.bytes).tpe shouldBe sigma.ast.SSigmaProp
      }
    }
  }

  Seq[Kind](ErgBond, TokenBond).foreach { kind =>

    property(s"$kind V1 boundary: repay below maturity, liquidate from maturity on, never both or neither") {
      withCtx { ctx =>
        val r = repayScenario(ctx, kind)
        val l = liquidateScenario(ctx, kind)
        accepts(r.prover, repayTx(r)(height = r.maturity - 1))
        rejectsAtSigning(l.prover, liquidateTx(l)(height = l.maturity - 1))
        rejectsAtSigning(r.prover, repayTx(r)(height = r.maturity))
        accepts(l.prover, liquidateTx(l)(height = l.maturity))
      }
    }

    property(s"$kind V1 parity: anyone may liquidate after maturity, without a signature, long after too") {
      withCtx { ctx =>
        val l = liquidateScenario(ctx, kind)
        accepts(other(ctx), liquidateTx(l)(height = l.maturity + 100000, changeTo = other(ctx).getAddress))
      }
    }

    property(s"$kind V1 parity: with a zero batching fee the lender receives the collateral in full") {
      withCtx { ctx =>
        // V1 required exactly the bond's value; here the executor pays the miner fee from its own box
        val l = liquidateScenario(ctx, kind, fee = 0L)
        l.settleOut.value shouldBe l.bond.value
        accepts(l.prover, liquidateTx(l)(inputs = Seq(l.bondIn, funding(ctx, l.prover, 1))))
      }
    }

    property(s"$kind batcher: three bonds in one liquidation pay three fees for one miner fee") {
      withCtx { ctx =>
        val maturity = maturityOf(ctx)
        val assets = Seq(bondAsset, otherBondAsset, org.ergoplatform.sdk.ErgoId.create("f22d" * 16))
        val ins = assets.zipWithIndex.map { case (asset, i) =>
          inputAt(bondUTXO(ctx, kind, asset = asset, maturity = maturity), ctx, i)
            .withCtxVar(0.toByte, liqVar(matured(ctx))).withCtxVar(1.toByte, ErgoValue.of(i))
        }
        val outs = assets.zip(ins).map { case (asset, in) =>
          settlement(vault(ctx), collateralErg, in.id, asset, defaultCollateral)
        }
        val batcher = executor(ctx)
        val signed = accepts(batcher, buildAt(ctx, maturity, ins, outs, batcher.getAddress, burn = assets.map(Token(_, 1L))))
        signed.getOutputsToSpend.asScala.filter(_.getErgoTree == contractOf(batcher).ergoTree)
          .map(_.getValue.longValue).sum shouldBe 3 * batchingFee - Parameters.MinFee
      }
    }
  }
}
