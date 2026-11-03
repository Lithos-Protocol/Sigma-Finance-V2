package org.lithosprotocol.sigmafi
package specs.bonds

import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Token, UTXO}

import scala.collection.JavaConverters._

class BondNftGeneratorSpec extends AnyPropSpec with BondSpecBase {

  import BondSpecBase._

  // ═══ MINT ═════════════════════════════════════════════════════════════════

  property("mint: an executor with no inputs of its own mints both units and keeps the fee") {
    withCtx { ctx =>
      val m = mintScenario(ctx)
      val signed = accepts(m.prover, mintTx(m)())
      val outs = signed.getOutputsToSpend.asScala
      outs.flatMap(_.getTokens.asScala).filter(_.getId == m.asset).map(_.getValue.longValue).sum shouldBe 2L
      outs.filter(_.getErgoTree == contractOf(m.prover).ergoTree).map(_.getValue.longValue).sum shouldBe
        executorFee - Parameters.MinFee
    }
  }

  property("mint: the generator's own tokens ride into the order") {
    withCtx { ctx =>
      val m = mintScenario(ctx, genTokens = Seq(Token(stableToken, 500L)))
      accepts(m.prover, mintTx(m)())
    }
  }

  property("mint: rejects the executor keeping the generator's tokens") {
    withCtx { ctx =>
      val m = mintScenario(ctx, genTokens = Seq(Token(stableToken, 500L)))
      rejectsAtSigning(m.prover, mintTx(m)(orderOut = m.orderOut.setTokens(Token(m.asset, 1L))))
    }
  }

  property("mint: rejects the executor keeping one nanoERG more than its fee") {
    withCtx { ctx =>
      val m = mintScenario(ctx)
      rejectsAtSigning(m.prover, mintTx(m)(orderOut = m.orderOut.subValue(1L)))
    }
  }

  property("mint: rejects a third unit (verifiedMint)") {
    withCtx { ctx =>
      val m = mintScenario(ctx)
      val third = UTXO(contractOf(m.prover), minBox, Seq(Token(m.asset, 1L)))
      rejectsAtSigning(m.prover, mintTx(m)(outputs = Seq(m.lenderOut, m.orderOut.subValue(minBox), third)))
    }
  }

  property("mint: rejects two units in the order and none to the lender") {
    withCtx { ctx =>
      val m = mintScenario(ctx)
      rejectsAtSigning(m.prover, mintTx(m)(lenderOut = m.lenderOut.setTokens(),
        orderOut = m.orderOut.setTokens(Token(m.asset, 2L))))
    }
  }

  property("mint: rejects the circulating unit sent to someone other than the lender") {
    withCtx { ctx =>
      val m = mintScenario(ctx)
      rejectsAtSigning(m.prover, mintTx(m)(lenderOut = m.lenderOut.setContract(contractOf(m.prover))))
    }
  }

  property("mint: rejects the principal sent to a script other than the order") {
    withCtx { ctx =>
      val m = mintScenario(ctx)
      rejectsAtSigning(m.prover, mintTx(m)(orderOut = m.orderOut.setContract(contractOf(m.prover))))
    }
  }

  property("mint: rejects registers on the order output, so the executor cannot pick its terms") {
    withCtx { ctx =>
      val m = mintScenario(ctx)
      rejectsAtSigning(m.prover, mintTx(m)(orderOut = m.orderOut.setRegs(bytesValue(hashOf(contractOf(m.prover))))))
      rejectsAtSigning(m.prover, mintTx(m)(orderOut = m.orderOut.setRegs(ErgoValue.of(1L))))
    }
  }

  property("mint: a generator that is not INPUTS(0) cannot mint, and falls to the lender-only refund") {
    withCtx { ctx =>
      val m = mintScenario(ctx)
      val first = funding(ctx, m.prover, 0)
      val genSecond = inputAt(m.gen, ctx, 1)
      val wrongAsset = first.id
      rejectsAtSigning(m.prover, mintTx(m)(
        inputs = Seq(first, genSecond),
        outputs = Seq(m.lenderOut.setTokens(Token(wrongAsset, 1L)), m.orderOut.setTokens(Token(wrongAsset, 1L)))))
    }
  }

  // ═══ EIP-4 ════════════════════════════════════════════════════════════════

  /** What the node's ExtraIndexer records: R4-R6 of the first output carrying the new token. */
  private def indexedMetadata(tx: SignedTransaction, asset: org.ergoplatform.sdk.ErgoId): (String, String, Int) = {
    val issuing = tx.getOutputsToSpend.asScala.find(_.getTokens.asScala.exists(_.getId == asset)).get
    def text(i: Int) = new String(issuing.getRegisters.get(i).getValue.asInstanceOf[sigma.Coll[Byte]].toArray, "UTF-8")
    (text(0), text(1), text(2).toInt)
  }

  property("eip-4: the lender's unit is the issuing box, so wallets show the lender's name and 0 decimals") {
    withCtx { ctx =>
      val m = mintScenario(ctx)
      indexedMetadata(accepts(m.prover, mintTx(m)()), m.asset) shouldBe ((tokenName, tokenDescription, 0))
    }
  }

  property("eip-4: rejects an executor renaming the asset") {
    withCtx { ctx =>
      val m = mintScenario(ctx)
      rejectsAtSigning(m.prover, mintTx(m)(lenderOut = m.lenderOut.setRegs(eip4(name = "SigUSD"): _*)))
    }
  }

  property("eip-4: rejects a description other than the lender's") {
    withCtx { ctx =>
      val m = mintScenario(ctx)
      rejectsAtSigning(m.prover, mintTx(m)(lenderOut = m.lenderOut.setRegs(eip4(description = "Redeem at scam.io"): _*)))
    }
  }

  property("eip-4: rejects decimals other than 0") {
    withCtx { ctx =>
      val m = mintScenario(ctx)
      rejectsAtSigning(m.prover, mintTx(m)(lenderOut = m.lenderOut.setRegs(eip4(decimals = "2"): _*)))
    }
  }

  property("eip-4: rejects an asset type and image link added from R7 on") {
    withCtx { ctx =>
      val m = mintScenario(ctx)
      val nftPicture = eip4() ++ Seq(bytesValue(Array[Byte](1, 1)), bytesValue(Array.fill(32)(3.toByte)), utf8("ipfs://bait"))
      rejectsAtSigning(m.prover, mintTx(m)(lenderOut = m.lenderOut.setRegs(nftPicture: _*)))
    }
  }

  property("eip-4: rejects a lender unit with no metadata") {
    withCtx { ctx =>
      val m = mintScenario(ctx)
      rejectsAtSigning(m.prover, mintTx(m)(lenderOut = m.lenderOut.setRegs()))
    }
  }

  property("eip-4: a generator without metadata registers cannot mint, but its lender can still refund it") {
    withCtx { ctx =>
      val gen = generatorUTXO(ctx, withMetadata = false)
      val m = mintScenario(ctx).copy(gen = gen, genIn = inputAt(gen, ctx, 0))
      val asset = m.genIn.id
      rejectsAtSigning(m.prover, mintTx(m)(
        lenderOut = m.lenderOut.setTokens(Token(asset, 1L)), orderOut = m.orderOut.setTokens(Token(asset, 1L))))

      val l = lender(ctx)
      accepts(l, build(ctx, Seq(m.genIn, funding(ctx, l, 1)), Seq(UTXO(lenderTree(ctx), gen.value)), l.getAddress))
    }
  }

  // ═══ REFUND ═══════════════════════════════════════════════════════════════

  property("refund: the lender reclaims a generator nobody minted") {
    withCtx { ctx =>
      val gen = generatorUTXO(ctx)
      val l = lender(ctx)
      accepts(l, build(ctx, Seq(inputAt(gen, ctx, 0), funding(ctx, l, 1)),
        Seq(UTXO(lenderTree(ctx), gen.value)), l.getAddress))
    }
  }

  property("refund: rejected without the lender's co-spend (lenderAuth)") {
    withCtx { ctx =>
      val gen = generatorUTXO(ctx)
      val ex = executor(ctx)
      rejectsAtSigning(ex, build(ctx, Seq(inputAt(gen, ctx, 0), funding(ctx, ex, 1)),
        Seq(UTXO(contractOf(ex), gen.value)), ex.getAddress))
    }
  }

}
