package org.lithosprotocol.sigmafi
package specs.bonds

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import sigma.Colls
import org.lithosprotocol.sigmafi.mutations.{Contract, InputUTXO, Token, UTXO}

/**
 * ZC_Standard_WrapMint and ZC_Standard_WrapVault: a capability token goes into a vault for a receipt, and leaves only
 * when the receipt is burned. The capability is stood in for by a box any transaction holding the token may spend,
 * which is how LithosDex's provision treats its owner NFT.
 */
class ZcStandardWrapSpec extends AnyPropSpec with ZcStandardSpecBase {
  import BondSpecBase._
  import ZcStandardSpecBase._

  private val nft: ErgoId = ErgoId.create("dd" * 32)
  private val mintFee: Long = 2000000L
  private val meta: Seq[String] = Seq("ZCS-Wrap-dddddd", "{\"zcs\":1}", "0")

  private def s(ctx: BlockchainContext) = ZcStandardSpecBase.compiled(ctx)

  /** A box anyone may spend in a transaction that has the token among its inputs: LD_Provision's ownership rule. */
  private def capability(ctx: BlockchainContext): Contract =
    Contract.fromErgoContract(ctx.compileContract(
      ConstantsBuilder.create().item("CONST_NFT", Colls.fromArray(nft.getBytes)).build(),
      "{ sigmaProp(INPUTS.exists { (b: Box) => b.tokens.exists { (t: (Coll[Byte], Long)) => t._1 == CONST_NFT } }) }"))

  private def capabilityIn(ctx: BlockchainContext): InputUTXO = inputAt(UTXO(capability(ctx), 5L * minBox), ctx, 7)

  private case class Wrap(request: UTXO, in: InputUTXO, receiptOut: UTXO, vault: UTXO) {
    def receipt: ErgoId = in.id
  }

  private def wrap(ctx: BlockchainContext, fee: Long = mintFee, owner: Contract = null, reserve: Long = 3000000L): Wrap = {
    val o = if (owner == null) lenderTree(ctx) else owner
    val request = UTXO(s(ctx).wrapMint, 2L * minBox + reserve + fee, Seq(Token(nft, 1L)),
      Seq(bytesValue(hashOf(o)), ErgoValue.of(fee), textsValue(meta: _*)))
    val in = inputAt(request, ctx, 0)
    Wrap(request, in, UTXO(o, minBox, Seq(Token(in.id, 1L)), meta.map(utf8)),
      UTXO(s(ctx).wrapVault, minBox + reserve, Seq(Token(in.id, 2L), Token(nft, 1L))))
  }

  /** The request alone pays everything: the receipt, the vault and, as the miner fee, the mint fee. */
  private def mintTx(ctx: BlockchainContext, w: Wrap, receipt: UTXO = null, vault: UTXO = null,
                     extra: Seq[InputUTXO] = Seq.empty): UnsignedTransaction =
    build(ctx, w.in +: extra, Seq(if (receipt == null) w.receiptOut else receipt, if (vault == null) w.vault else vault),
      executor(ctx).getAddress, fee = mintFee)

  property("mint: three receipts, one to the owner with the committed metadata, two and the token into a bare vault") {
    withCtx { ctx =>
      val w = wrap(ctx)
      val tx = accepts(executor(ctx), mintTx(ctx, w))
      tx.getOutputsToSpend.get(0).getTokens.get(0).getId shouldBe w.receipt
      tx.getOutputsToSpend.get(1).getTokens.get(0).getValue.longValue shouldBe 2L
      // The receipt goes to the owner, with the committed metadata, one unit: two would mint four in all.
      Seq(w.receiptOut.setContract(otherTree(ctx)), w.receiptOut.withRegNum(5, bytesValue("other".getBytes("UTF-8"))),
        w.receiptOut.setTokens(Token(w.receipt, 2L))).foreach { bad =>
        rejectsAtSigning(executor(ctx), mintTx(ctx, w, receipt = bad))
      }
      Seq(w.vault.setContract(otherTree(ctx)), w.vault.withRegNum(4, bytesValue(Array[Byte](1))),
        w.vault.setTokens(Token(nft, 1L), Token(w.receipt, 2L))).foreach { bad =>
        rejectsAtSigning(executor(ctx), mintTx(ctx, w, vault = bad))
      }
      // The vault must hold the wrapped token: kept back, it would not be wrapped at all.
      rejectsAtSigning(executor(ctx), build(ctx, Seq(w.in), Seq(w.receiptOut, w.vault.setTokens(Token(w.receipt, 2L)),
        UTXO(contractOf(executor(ctx)), minBox, Seq(Token(nft, 1L)))), executor(ctx).getAddress, fee = mintFee - minBox))
      // The vault must receive the entire refresh reserve: diverting it into receiptOut would leave
      // the WrapVault unrefreshable under its INPUTS.size == 1 rule.
      rejectsAtSigning(executor(ctx), mintTx(ctx, w,
        receipt = w.receiptOut.addValue(3000000L), vault = w.vault.subValue(3000000L)))
      rejectsAtSigning(executor(ctx), mintTx(ctx, w,
        receipt = w.receiptOut.addValue(1L), vault = w.vault.subValue(1L)))
      // A mint fee under 0.002 ERG is refused.
      val cheap = wrap(ctx, fee = 1999999L)
      rejectsAtSigning(executor(ctx), build(ctx, Seq(cheap.in), Seq(cheap.receiptOut, cheap.vault), executor(ctx).getAddress,
        fee = 1999999L))
    }
  }

  property("mint: the request is the transaction's only input, so the token never sits beside anything else") {
    withCtx { ctx =>
      val w = wrap(ctx)
      rejectsAtSigning(executor(ctx), mintTx(ctx, w, extra = Seq(funding(ctx, executor(ctx), 9))))
      // The stand-in capability box is exactly what an extra input could have been.
      rejectsAtSigning(executor(ctx), mintTx(ctx, w, extra = Seq(capabilityIn(ctx))))
    }
  }

  property("capability: a box keyed to the token is spent beside the raw token, but never beside its receipt") {
    withCtx { ctx =>
      val raw = inputAt(UTXO(lenderTree(ctx), minBox, Seq(Token(nft, 1L))), ctx, 3)
      accepts(lender(ctx), build(ctx, Seq(raw, capabilityIn(ctx)), Seq(UTXO(lenderTree(ctx), minBox, Seq(Token(nft, 1L)))),
        lender(ctx).getAddress))
      // A bond holding the receipt as collateral: its liquidation cannot take the capability box along.
      val w = wrap(ctx)
      val receiptBox = Token(w.receipt, 1L)
      val f = flow(ctx, ErgBond, collateral = Seq(receiptBox))
      val bond = spendOutput(accepts(f.holder, standardFillTx(f)), 1)
      accepts(executor(ctx), standardLiquidationTx(f, liquidationInput(f, bond)))
      rejectsAtSigning(executor(ctx), standardLiquidationTx(f, liquidationInput(f, bond),
        inputs = Seq(liquidationInput(f, bond), capabilityIn(ctx))))
    }
  }

  property("unwrap: burning all three receipts releases the token to whoever burns them; the reserve alone cannot") {
    withCtx { ctx =>
      val w = wrap(ctx)
      val vaultIn = inputAt(w.vault, ctx, 0)
      val holder = inputAt(w.receiptOut, ctx, 1)
      val takeOut = UTXO(lenderTree(ctx), minBox, Seq(Token(nft, 1L)))
      accepts(lender(ctx), build(ctx, Seq(vaultIn, holder, funding(ctx, lender(ctx), 2)), Seq(takeOut), lender(ctx).getAddress,
        burn = Seq(Token(w.receipt, 3L))))
      // The unwrapper holds the token at that moment, so the capability is theirs to use.
      accepts(lender(ctx), build(ctx, Seq(vaultIn, holder, funding(ctx, lender(ctx), 2), capabilityIn(ctx)), Seq(takeOut),
        lender(ctx).getAddress, burn = Seq(Token(w.receipt, 3L))))
      rejectsAtSigning(executor(ctx), build(ctx, Seq(vaultIn, funding(ctx, executor(ctx), 2)), Seq(takeOut), executor(ctx).getAddress,
        burn = Seq(Token(w.receipt, 2L))))
      // Every unit must burn: keeping the circulating one back is refused.
      rejectsAtSigning(lender(ctx), build(ctx, Seq(vaultIn, holder, funding(ctx, lender(ctx), 2)),
        Seq(takeOut, UTXO(lenderTree(ctx), minBox, Seq(Token(w.receipt, 1L)))), lender(ctx).getAddress, burn = Seq(Token(w.receipt, 2L))))
    }
  }

  property("cancel: the owner takes the request back before it is minted, and nobody else can") {
    withCtx { ctx =>
      val w = wrap(ctx)
      def cancel(by: ErgoProver): UnsignedTransaction =
        build(ctx, Seq(w.in, funding(ctx, by, 5)), Seq(UTXO(contractOf(by), w.request.value, w.request.tokens)), by.getAddress)
      accepts(lender(ctx), cancel(lender(ctx)))
      rejectsAtSigning(other(ctx), cancel(other(ctx)))
    }
  }

  property("refresh: after two years the vault alone is re-created bare, for at most a 0.01 ERG bounty") {
    withCtx { ctx =>
      val w = wrap(ctx, reserve = 30000000L)
      val h0 = ctx.getHeight
      val at = h0 + 525601
      val old = w.vault.setCreationHeight(h0)
      def refreshTx(next: UTXO, height: Int, extra: Seq[InputUTXO] = Seq.empty): UnsignedTransaction =
        buildAt(ctx, height, inputAt(old, ctx, 0).withCtxVar(9.toByte, ErgoValue.of(0)) +: extra, Seq(next), executor(ctx).getAddress)
      val ok = old.subValue(10000000L).setCreationHeight(at)
      accepts(executor(ctx), refreshTx(ok, at))
      rejectsAtSigning(executor(ctx), refreshTx(ok.setCreationHeight(at - 1), at - 1))
      rejectsAtSigning(executor(ctx), refreshTx(old.subValue(10000001L).setCreationHeight(at), at))
      rejectsAtSigning(executor(ctx), refreshTx(ok.withRegNum(4, bytesValue(Array[Byte](1))), at))
      rejectsAtSigning(executor(ctx), refreshTx(ok.setCreationHeight(at - 1000), at))
      rejectsAtSigning(executor(ctx), refreshTx(ok, at, extra = Seq(funding(ctx, executor(ctx), 9))))
      rejectsAtSigning(executor(ctx), refreshTx(ok, at, extra = Seq(capabilityIn(ctx))))
      // The 0.001 ERG floor binds before the bounty does: 0.006 ERG may drop to 0.001, not below it.
      val small = w.vault.setValue(6000000L).setCreationHeight(h0)
      def smallTx(left: Long): UnsignedTransaction = buildAt(ctx, at, Seq(inputAt(small, ctx, 0).withCtxVar(9.toByte, ErgoValue.of(0))),
        Seq(small.setValue(left).setCreationHeight(at)), executor(ctx).getAddress)
      accepts(executor(ctx), smallTx(minBox))
      rejectsAtSigning(executor(ctx), smallTx(minBox - 1L))
    }
  }
}
