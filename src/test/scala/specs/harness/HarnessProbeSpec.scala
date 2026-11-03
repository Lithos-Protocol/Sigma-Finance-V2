package org.lithosprotocol.sigmafi
package specs.harness

import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Contract, UTXO}

import java.math.BigInteger

/**
 * Harness sanity, offline. Nothing here touches a SigmaFi contract: if one of these fails, every
 * contract spec built on [[ContractSpecBase]] is suspect, whatever it reports.
 */
class HarnessProbeSpec extends AnyPropSpec with ContractSpecBase {

  property("mocked client yields a usable BlockchainContext") {
    withCtx { ctx =>
      ctx.getHeight should be > 0
      ctx.getNetworkType shouldBe NetworkType.MAINNET
      println(s"[probe] height=${ctx.getHeight} network=${ctx.getNetworkType}")
    }
  }

  property("a dummy prover can sign a spend offline through the mutations builder") {
    withCtx { ctx =>
      val prover = proverWith(ctx, BigInteger.valueOf(12345L))
      val in = inputAt(UTXO(Contract.SIGMA_TRUE, Parameters.OneErg), ctx, 0)
      val out = UTXO(Contract.SIGMA_TRUE, Parameters.OneErg - Parameters.MinFee)
      accepts(prover, build(ctx, Seq(in), Seq(out), prover.getAddress))
        .getOutputsToSpend.size() should be > 0
    }
  }

  property("the mocked context compiles 6.0 scripts at ErgoTree v3") {
    withCtx { ctx =>
      // Global.serialize only exists from 6.0 on. Against the plain (non-_v6) fixtures this either
      // fails to compile or comes out as a v2 tree, and every 6.0 contract spec is then meaningless.
      val tree = ctx.compileContract(ConstantsBuilder.empty(),
        "{ sigmaProp(Global.serialize(SELF.value).size > 0) }").getErgoTree
      tree.version shouldBe 3
    }
  }

  property("a contract rejection is told apart from a transaction that never balanced") {
    withCtx { ctx =>
      val prover = proverWith(ctx, BigInteger.valueOf(12345L))
      val in = inputAt(UTXO(Contract.SIGMA_FALSE, Parameters.OneErg), ctx, 0)
      val out = UTXO(Contract.SIGMA_TRUE, Parameters.OneErg - Parameters.MinFee)
      rejectsAtSigning(prover, build(ctx, Seq(in), Seq(out), prover.getAddress))

      val overpaid = out.addValue(1L)
      an[org.scalatest.exceptions.TestFailedException] should be thrownBy
        rejectsAtSigning(prover, build(ctx, Seq(in), Seq(overpaid), prover.getAddress))
    }
  }
}
