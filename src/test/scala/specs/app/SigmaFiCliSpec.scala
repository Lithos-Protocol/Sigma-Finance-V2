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

  property("a standard fill's --fee-reserve has the same 0.05 ERG default and floor") {
    SigmaFiCli.reserve(fillArgs(), "fee-reserve") shouldBe 50000000L
    SigmaFiCli.reserve(fillArgs("--fee-reserve", "0.2"), "fee-reserve") shouldBe 200000000L
    intercept[CliError](SigmaFiCli.reserve(fillArgs("--fee-reserve", "0.002"), "fee-reserve")).message shouldBe
      "--fee-reserve must be at least 0.05 ERG"
  }

  property("the shell splits a line on whitespace and keeps quoted text together") {
    Args.tokenize("  repay  abc --from-collateral ") shouldBe Seq("repay", "abc", "--from-collateral")
    Args.tokenize("""create-order --wallet-pass "two words" --x 'a b' """) shouldBe
      Seq("create-order", "--wallet-pass", "two words", "--x", "a b")
    Args.tokenize("""claim "" x""") shouldBe Seq("claim", "", "x")
    intercept[CliError](Args.tokenize("""repay "abc""")).message shouldBe "unterminated quote"
  }

  property("launch options fill in a shell command's node options, but --send never carries over") {
    val launch = Args.parse(Seq("--node", "http://n:9053", "--api-key", "k", "--fee", "0.002", "--send"))
    val line = Args.parse(Seq("fill-order", "id", "--fee", "0.003")).withDefaults(launch)
    line.opt("node") shouldBe Some("http://n:9053")
    line.opt("api-key") shouldBe Some("k")
    line.opt("fee") shouldBe Some("0.003")
    line.flag("send") shouldBe false
    Args.parse(Seq("repay", "id", "--send")).withDefaults(launch).flag("send") shouldBe true
  }

  property("the share, ask, offer, lot and batch options parse, including a moving yield and a curve") {
    val offer = Args.parse(Args.tokenize("create-offer --capital 10 --yield 5 --curve 8:21600,10:131400 --collateral-floor 130 " +
      "--deliver-shares --shares 100 --vault dutch --auction-opening 150 --auction-floor 90 --auction-length 500 " +
      "--auction-expiry 600 --min-auction-floor 80 --max-auction-expiry 1000 --batcher-fee 0.005 --fees 0.5 --min-price 1 " +
      "--min-blocks 10 --max-blocks 20"))
    offer.opt("vault") shouldBe Some("dutch")
    offer.opt("auction-floor") shouldBe Some("90")
    offer.opt("curve") shouldBe Some("8:21600,10:131400")
    val ask = Args.parse(Args.tokenize("sell abc --amount 60 --yield 5 --end-yield 7.5 --end-in 100 --min-fill 5 --refund-fee 0.002"))
    ask.opt("end-yield") shouldBe Some("7.5")
    ask.opt("end-in") shouldBe Some("100")
    val lot = Args.parse(Args.tokenize("sell-lot --token a:1 --token b:2 --erg 1 --opening 5 --floor 2 --falls-for 10 --holds-for 20"))
    lot.opts("token") shouldBe Seq("a:1", "b:2")
    intercept[CliError](Args.parse(Args.tokenize("sell abc --amount 1 --nav"))).getMessage should include("unknown option --nav")
    intercept[CliError](Args.parse(Args.tokenize("sell abc --amount 1 --yield 5 --margin 1"))).getMessage should include("unknown option --margin")
    Args.parse(Args.tokenize("match-offer o --ask a --amount 40")).opt("ask") shouldBe Some("a")
    Args.parse(Args.tokenize("batch --run --send")).flags shouldBe Set("run", "send")
  }

  property("--old marks a command for the first-generation contracts") {
    Args.parse(Seq("orders", "--old")).flag("old") shouldBe true
    Args.parse(Seq("orders")).flag("old") shouldBe false
  }
}
