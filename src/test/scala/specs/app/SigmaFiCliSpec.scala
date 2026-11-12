package org.lithosprotocol.sigmafi
package specs.app

import ksingh.sigmabonds.app.{Args, CliError, SigmaFiCli}
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec

class SigmaFiCliSpec extends AnyPropSpec with Matchers {

  private def fillArgs(options: String*): Args =
    Args.parse(Seq("fill-order", "order-box-id") ++ options)

  property("fill-order defaults to a 0.05 ERG batching fee") {
    SigmaFiCli.batchingFee(fillArgs()) shouldBe 50000000L
  }

  property("fill-order accepts the minimum batching fee and larger overrides") {
    SigmaFiCli.batchingFee(fillArgs("--batching-fee", "0.05")) shouldBe 50000000L
    SigmaFiCli.batchingFee(fillArgs("--batching-fee=0.050000001")) shouldBe 50000001L
    SigmaFiCli.batchingFee(fillArgs("--batching-fee", "0.1")) shouldBe 100000000L
  }

  property("fill-order rejects batching fees below 0.05 ERG") {
    Seq("0", "0.002", "0.049999999").foreach { amount =>
      intercept[CliError](SigmaFiCli.batchingFee(fillArgs("--batching-fee", amount))).message shouldBe
        "--batching-fee must be at least 0.05 ERG"
    }
  }

  property("fill-order rejects malformed batching fee amounts") {
    Seq("-0.05", "invalid", "0.0500000001").foreach { amount =>
      intercept[CliError](SigmaFiCli.batchingFee(fillArgs("--batching-fee", amount)))
    }
  }
}
