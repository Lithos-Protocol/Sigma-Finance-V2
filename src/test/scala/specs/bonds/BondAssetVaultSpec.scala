package org.lithosprotocol.sigmafi
package specs.bonds

import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Token, UTXO}

class BondAssetVaultSpec extends AnyPropSpec with BondSpecBase {

  import BondSpecBase._

  property("claim: the holder of the bond asset claims the vault by burning it") {
    withCtx { ctx =>
      val c = claimScenario(ctx)
      accepts(c.prover, claimTx(c)())
    }
  }

  property("claim: the asset is a bearer claim, so a buyer of it can claim too") {
    withCtx { ctx =>
      val c = claimScenario(ctx, holder = other(ctx))
      accepts(c.prover, claimTx(c)())
    }
  }

  property("claim: rejected when the asset is kept rather than burned (assetBurned)") {
    withCtx { ctx =>
      val c = claimScenario(ctx)
      rejectsAtSigning(c.prover, claimTx(c)(burn = Seq.empty[Token]))
    }
  }

  property("claim: rejected without the asset (assetSpent)") {
    withCtx { ctx =>
      val c = claimScenario(ctx, holderHasAsset = false, holder = other(ctx))
      rejectsAtSigning(c.prover, claimTx(c)(burn = Seq.empty[Token]))
    }
  }

  property("claim: another bond's asset does not open this vault") {
    withCtx { ctx =>
      val c = claimScenario(ctx, holderHasAsset = false)
      val wrong = funding(ctx, c.prover, 1, Parameters.OneErg, Seq(Token(otherBondAsset, 1L)))
      rejectsAtSigning(c.prover, claimTx(c)(inputs = Seq(c.vaultIn, wrong), burn = Seq(Token(otherBondAsset, 1L))))
    }
  }

  Seq[Kind](ErgBond, TokenBond).foreach { kind =>
    property(s"end to end ($kind): a repayment lands in the vault and only the lender's unit claims it") {
      withCtx { ctx =>
        val r = repayScenario(ctx, kind, payer = other(ctx))
        val settled = spendOutput(accepts(r.prover, repayTx(r)()), 0)

        val holder = lender(ctx)
        val unit = funding(ctx, holder, 1, Parameters.OneErg, Seq(Token(bondAsset, 1L)))
        val payout = UTXO(contractOf(holder), settled.value, settled.tokens)
        accepts(holder, build(ctx, Seq(settled, unit), Seq(payout), holder.getAddress,
          burn = Seq(Token(bondAsset, 1L))))

        // the bond holds no unit of the asset, so neither the repayer nor anyone else has one to open it with
        val stranger = other(ctx)
        val noUnit = funding(ctx, stranger, 1, Parameters.OneErg)
        rejectsAtSigning(stranger, build(ctx, Seq(settled, noUnit),
          Seq(UTXO(contractOf(stranger), settled.value, settled.tokens)), stranger.getAddress))
      }
    }

    property(s"end to end ($kind): liquidated collateral lands in the vault and the lender's unit claims it") {
      withCtx { ctx =>
        val l = liquidateScenario(ctx, kind)
        val settled = spendOutput(accepts(l.prover, liquidateTx(l)()), 0)
        val holder = lender(ctx)
        val unit = funding(ctx, holder, 1, Parameters.OneErg, Seq(Token(bondAsset, 1L)))
        accepts(holder, build(ctx, Seq(settled, unit),
          Seq(UTXO(contractOf(holder), settled.value, settled.tokens)), holder.getAddress,
          burn = Seq(Token(bondAsset, 1L))))
      }
    }
  }
}
