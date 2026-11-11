package org.lithosprotocol.sigmafi
package specs.bonds

import org.ergoplatform.appkit._
import org.ergoplatform.appkit.scalaapi._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import sigma.Colls
import work.lithos.mutations.{InputUTXO, Token, UTXO}

/** ZC_Standard_FractionalMint and ZC_Standard_FractionalVault, layered on a standard bond. */
class ZcStandardFractionalSpec extends AnyPropSpec with ZcStandardSpecBase {
  import BondSpecBase._
  import ZcStandardSpecBase._

  private val pullFee: Long = 2000000L
  private val mintFee: Long = 2000000L
  private val shareName = "Bond shares"
  private val shareDescription = "Shares of one bond"

  /** A fill whose circulating unit goes straight into a fractionalization request owned by the lender. */
  private case class Wrapped(f: Flow, shares: Long, fee: Long, mintFee: Long, decimals: String,
                             fill: SignedTransaction, request: InputUTXO) {
    def shareId: ErgoId = request.id
  }

  private def metadataValue(parts: String*): ErgoValue[_] =
    ErgoValue.of(Colls.fromArray(parts.map(p => Colls.fromArray(p.getBytes("UTF-8"))).toArray)(
      sigma.collRType(sigma.ByteType)), ErgoType.collType(scalaByteType))

  /** Funds the fractional vault (a minimum box plus the pull fee), the share box and the mint fee. */
  private def requestBox(f: Flow, shares: Long, fee: Long, mint: Long, decimals: String): UTXO =
    UTXO(scripts(f.ctx).fractionalMint, minBox + fee + minBox + mint, Seq(Token(f.asset, 1L)),
      eip4() ++ Seq(bytesValue(hashOf(lenderTree(f.ctx))), longsValue(shares, fee, mint),
        metadataValue(shareName, shareDescription, decimals)))

  private def wrap(ctx: BlockchainContext, kind: Kind = TokenBond, shares: Long = 1000L,
                   fee: Long = pullFee, mint: Long = mintFee, decimals: String = "2"): Wrapped = {
    val f = flow(ctx, kind)
    val fill = accepts(f.holder, standardFillTx(f, unit = requestBox(f, shares, fee, mint, decimals)))
    Wrapped(f, shares, fee, mint, decimals, fill, spendOutput(fill, 0))
  }

  private def shareBox(w: Wrapped, decimals: String = null): UTXO =
    UTXO(lenderTree(w.f.ctx), minBox, Seq(Token(w.shareId, w.shares)),
      eip4(shareName, shareDescription, if (decimals == null) w.decimals else decimals))

  private def freshVault(w: Wrapped): UTXO =
    UTXO(scripts(w.f.ctx).fractionalVault, w.request.value - w.mintFee - minBox,
      Seq(Token(w.shareId, w.shares + 1L), Token(w.f.asset, 1L)),
      Seq(bytesValue(w.f.asset.getBytes), longsValue(w.shares, w.shares, w.fee)))

  /**
   * Run by a batcher with the request as its only input, so the share id is the request's box id and
   * the mint fee is the batcher's miner fee and reward. Outputs: 0 the shares, 1 the fractional vault.
   */
  private def mintTx(w: Wrapped, shares: UTXO = null, vault: UTXO = null,
                     funded: Boolean = false): UnsignedTransaction =
    // `funded` adds an executor box, so a negative that shifts value is refused by the script and
    // not by a dust change box.
    build(w.f.ctx, if (funded) Seq(w.request, funding(w.f.ctx, executor(w.f.ctx), 5)) else Seq(w.request),
      Seq(if (shares == null) shareBox(w) else shares, if (vault == null) freshVault(w) else vault),
      executor(w.f.ctx).getAddress)

  private def minted(w: Wrapped): SignedTransaction = accepts(executor(w.f.ctx), mintTx(w))

  /** Repay the bond, so its vault is ready to be pulled. */
  private def bondVault(w: Wrapped): InputUTXO =
    spendOutput(accepts(w.f.owner, standardRepayTx(w.f, repayInput(spendOutput(w.fill, 1)))), 0)

  private def pulledBox(w: Wrapped, vault: InputUTXO, source: InputUTXO, keep: Long): UTXO =
    UTXO(scripts(w.f.ctx).fractionalVault, vault.value + source.value - keep,
      Token(w.shareId, w.shares + 1L) +: source.tokens.drop(1),
      Seq(bytesValue(w.f.asset.getBytes), longsValue(w.shares, w.shares, w.fee), source.registers(1)))

  /** The executor spends only the two vaults: the pull fee is its miner fee and its reward. */
  private def pullTx(w: Wrapped, vault: InputUTXO, source: InputUTXO, out: UTXO = null): UnsignedTransaction =
    build(w.f.ctx, Seq(vault.withCtxVar(0.toByte, ErgoValue.of(0)), source),
      Seq(if (out == null) pulledBox(w, vault, source, w.fee) else out), executor(w.f.ctx).getAddress,
      burn = Seq(Token(w.f.asset, 3L)))

  private case class Pulled(w: Wrapped, mint: SignedTransaction, vault: InputUTXO)

  private def pulled(ctx: BlockchainContext, kind: Kind = TokenBond, shares: Long = 1000L): Pulled = {
    val w = wrap(ctx, kind, shares)
    val mint = minted(w)
    val tx = accepts(executor(ctx), pullTx(w, spendOutput(mint, 1), bondVault(w)))
    Pulled(w, mint, spendOutput(tx, 0))
  }

  private def share(amount: Long, burned: Long, outstanding: Long): Long =
    (BigInt(amount) * burned / outstanding).toLong

  /** Outputs: 0 the payout, 1 the continuing vault. Leftover shares return to the holder as change. */
  private def partialTx(p: Pulled, vault: InputUTXO, holderShares: InputUTXO, burned: Long, outstanding: Long,
                        payout: UTXO = null, next: UTXO = null): UnsignedTransaction = {
    val w = p.w
    val assets = vault.tokens.drop(1)
    val paidERG = share(vault.value, burned, outstanding)
    val paid = assets.map(t => t.id -> share(t.amount, burned, outstanding)).filter(_._2 > 0L)
      .map { case (id, amount) => Token(id, amount) }
    val kept = assets.map(t => t.id -> (t.amount - share(t.amount, burned, outstanding))).filter(_._2 > 0L)
      .map { case (id, amount) => Token(id, amount) }
    val pay = tagged(lenderTree(w.f.ctx), math.max(paidERG, minBox), vault.id, paid)
    val cont = UTXO(scripts(w.f.ctx).fractionalVault, vault.value - paidERG,
      Token(w.shareId, w.shares + 1L) +: kept,
      Seq(vault.registers.head, longsValue(w.shares, outstanding - burned, w.fee), vault.registers(2)))
    build(w.f.ctx,
      Seq(vault.withCtxVar(0.toByte, ErgoValue.of(1)).withCtxVar(1.toByte, ErgoValue.of(0)), holderShares,
        funding(w.f.ctx, w.f.holder, 9)),
      Seq(if (payout == null) pay else payout, if (next == null) cont else next), w.f.holder.getAddress,
      burn = Seq(Token(w.shareId, burned)))
  }

  /** Every outstanding share and the reserve burn; the payout takes the whole vault. */
  private def finalTx(w: Wrapped, vault: InputUTXO, holderShares: InputUTXO, outstanding: Long,
                      burn: Long = -1L, payout: UTXO = null): UnsignedTransaction =
    build(w.f.ctx, Seq(vault.withCtxVar(1.toByte, ErgoValue.of(0)), holderShares, funding(w.f.ctx, w.f.holder, 9)),
      Seq(if (payout == null) tagged(lenderTree(w.f.ctx), vault.value, vault.id, vault.tokens.drop(1)) else payout),
      w.f.holder.getAddress,
      burn = Seq(Token(w.shareId, if (burn < 0L) outstanding + w.shares + 1L else burn)))

  // ═══ MINT ═════════════════════════════════════════════════════════════════

  property("mint: a fill can hand its unit to a request, which mints 2N+1 shares with N+1 locked") {
    withCtx { ctx =>
      val w = wrap(ctx)
      val tx = minted(w)
      val shares = tx.getOutputsToSpend.get(0)
      shares.getTokens.get(0).getId shouldBe w.shareId
      shares.getTokens.get(0).getValue.longValue shouldBe w.shares
      val vault = tx.getOutputsToSpend.get(1)
      vault.getTokens.get(0).getValue.longValue shouldBe w.shares + 1L
      vault.getTokens.get(1).getId shouldBe w.f.asset
      vault.getRegisters.get(1) shouldBe longsValue(w.shares, w.shares, w.fee)
    }
  }

  property("mint: any batcher mints for the mint fee, with the metadata the owner committed") {
    withCtx { ctx =>
      Seq("0", "2", "9").foreach { d =>
        val w = wrap(ctx, decimals = d)
        val tx = accepts(executor(ctx), mintTx(w))
        tx.getOutputsToSpend.get(0).getRegisters.get(2) shouldBe utf8(d)
        tx.getOutputsToSpend.get(0).getErgoTree shouldBe lenderTree(ctx).ergoTree
      }
      val w = wrap(ctx)
      rejectsAtSigning(executor(ctx), mintTx(w, shares = shareBox(w, "9")))
      rejectsAtSigning(executor(ctx), mintTx(w, shares = shareBox(w).withRegNum(4, utf8("Renamed"))))
      rejectsAtSigning(executor(ctx), mintTx(w, shares = shareBox(w).withRegNum(5, utf8("Other text"))))
      rejectsAtSigning(executor(ctx), mintTx(w, shares = shareBox(w).setRegs(utf8(shareName), utf8(shareDescription))))
      rejectsAtSigning(executor(ctx), mintTx(w, shares = shareBox(w).setContract(otherTree(ctx))))
    }
  }

  property("mint: supply, reserve and vault registers are exact, and the batcher keeps only the mint fee") {
    withCtx { ctx =>
      val w = wrap(ctx)
      val vault = freshVault(w)
      def supply(circulating: Long, reserve: Long): UnsignedTransaction = mintTx(w,
        shares = shareBox(w).setTokens(Token(w.shareId, circulating)),
        vault = vault.setTokens(Token(w.shareId, reserve), Token(w.f.asset, 1L)))
      accepts(executor(ctx), supply(w.shares, w.shares + 1L))
      Seq((w.shares, w.shares), (w.shares, w.shares + 2L), (w.shares + 1L, w.shares + 1L),
        (w.shares - 1L, w.shares + 1L)).foreach { case (c, r) => rejectsAtSigning(executor(ctx), supply(c, r)) }
      val bad = Seq(vault.subValue(1L), vault.setContract(otherTree(ctx)),
        vault.withRegNum(4, bytesValue(otherBondAsset.getBytes)),
        vault.withRegNum(5, longsValue(w.shares, w.shares - 1L, w.fee)),
        vault.withRegNum(5, longsValue(w.shares, w.shares, w.fee - 1L)),
        vault.withRegNum(5, longsValue(w.shares, w.shares)))
      bad.foreach(v => rejectsAtSigning(executor(ctx), mintTx(w, vault = v)))
      // Value may move between the two owner-side boxes, but neither may drop below its floor.
      accepts(executor(ctx), mintTx(w, shares = shareBox(w).addValue(1L), vault = vault, funded = true))
      rejectsAtSigning(executor(ctx), mintTx(w, shares = shareBox(w).subValue(1L), vault = vault.addValue(1L), funded = true))
      rejectsAtSigning(executor(ctx), mintTx(w, shares = shareBox(w).addValue(1L), vault = vault.subValue(1L), funded = true))
    }
  }

  property("mint: both fees have a 0.002 ERG minimum and the share count is bounded") {
    withCtx { ctx =>
      accepts(executor(ctx), mintTx(wrap(ctx, fee = 2000000L, mint = 2000000L)))
      Seq(wrap(ctx, fee = 1999999L), wrap(ctx, mint = 1999999L)).foreach { low =>
        rejectsAtSigning(executor(ctx), mintTx(low, funded = true))
      }
      accepts(executor(ctx), mintTx(wrap(ctx, shares = 1L)))
      accepts(executor(ctx), mintTx(wrap(ctx, shares = 1000000000L)))
      Seq(0L, 1000000001L).foreach { n =>
        val w = wrap(ctx, shares = n)
        rejects(executor(ctx), mintTx(w))
      }
    }
  }

  property("mint: the owner cancels a request and takes the bond unit back") {
    withCtx { ctx =>
      val w = wrap(ctx)
      def cancel(by: ErgoProver): UnsignedTransaction =
        build(ctx, Seq(w.request, funding(ctx, by, 5)),
          Seq(UTXO(contractOf(by), w.request.value, Seq(Token(w.f.asset, 1L)))), by.getAddress)
      accepts(w.f.holder, cancel(w.f.holder))
      rejectsAtSigning(other(ctx), cancel(other(ctx)))
    }
  }

  // ═══ PULL ═════════════════════════════════════════════════════════════════

  for (kind <- Seq[Kind](ErgBond, TokenBond)) {
    property(s"$kind: anyone pulls the settled bond vault into the fractional vault for the pull fee") {
      withCtx { ctx =>
        val w = wrap(ctx, kind)
        val vault = spendOutput(minted(w), 1)
        val source = bondVault(w)
        val tx = accepts(executor(ctx), pullTx(w, vault, source))
        val next = tx.getOutputsToSpend.get(0)
        next.getValue shouldBe vault.value + source.value - w.fee
        next.getTokens.get(0).getValue.longValue shouldBe w.shares + 1L
        next.getRegisters.get(2) shouldBe ErgoValue.of(0.toByte)
        if (kind == TokenBond) next.getTokens.get(1).getId shouldBe SigUSD

        val out = pulledBox(w, vault, source, w.fee)
        val keptProceeds = if (kind == TokenBond) Seq(out.setTokens(Token(w.shareId, w.shares + 1L))) else Seq.empty[UTXO]
        val bad = Seq(out.subValue(1L), out.setContract(otherTree(ctx)),
          out.withRegNum(4, bytesValue(otherBondAsset.getBytes)),
          out.withRegNum(5, longsValue(w.shares, w.shares - 1L, w.fee)),
          out.withRegNum(6, ErgoValue.of(1.toByte))) ++ keptProceeds
        bad.foreach(o => rejectsAtSigning(executor(ctx), pullTx(w, vault, source, out = o)))
      }
    }
  }

  property("pull: a liquidated bond records its path, and nothing can be pulled twice") {
    withCtx { ctx =>
      val w = wrap(ctx)
      val vault = spendOutput(minted(w), 1)
      val bond = liquidationInput(w.f, spendOutput(w.fill, 1))
      val source = spendOutput(accepts(executor(ctx), standardLiquidationTx(w.f, bond)), 0)
      val tx = accepts(executor(ctx), pullTx(w, vault, source))
      tx.getOutputsToSpend.get(0).getRegisters.get(2) shouldBe ErgoValue.of(1.toByte)
      val again = spendOutput(tx, 0).withCtxVar(0.toByte, ErgoValue.of(0))
      rejects(executor(ctx), build(ctx, Seq(again, funding(ctx, executor(ctx), 9)),
        Seq(again.toUTXO), executor(ctx).getAddress))
    }
  }

  // ═══ CLAIMS ═══════════════════════════════════════════════════════════════

  for (kind <- Seq[Kind](ErgBond, TokenBond)) {
    property(s"$kind: partial claims pay pro rata, and the final claim burns the reserve and takes the rest") {
      withCtx { ctx =>
        val p = pulled(ctx, kind)
        val w = p.w
        val first = accepts(w.f.holder, partialTx(p, p.vault, spendOutput(p.mint, 0), 250L, 1000L))
        first.getOutputsToSpend.get(0).getValue shouldBe math.max(p.vault.value / 4L, minBox)
        val next = first.getOutputsToSpend.get(1)
        next.getValue shouldBe p.vault.value - p.vault.value / 4L
        next.getRegisters.get(1) shouldBe longsValue(1000L, 750L, w.fee)
        next.getTokens.get(0).getValue.longValue shouldBe w.shares + 1L
        if (kind == TokenBond) {
          first.getOutputsToSpend.get(0).getTokens.get(0).getValue.longValue shouldBe 175L
          next.getTokens.get(1).getValue.longValue shouldBe 525L
        }

        val vault = spendOutput(first, 1)
        val rest = outputWith(first, w.shareId, lenderTree(ctx))
        rest.tokens.find(_.id == w.shareId).get.amount shouldBe 750L
        accepts(w.f.holder, finalTx(w, vault, rest, 750L))
        // Burning the shares but keeping the reserve is neither a final nor a valid partial claim.
        rejects(w.f.holder, finalTx(w, vault, rest, 750L, burn = 750L))
        rejectsAtSigning(w.f.holder, finalTx(w, vault, rest, 750L,
          payout = tagged(lenderTree(ctx), vault.value - 1L, vault.id, vault.tokens.drop(1))))
        rejectsAtSigning(w.f.holder, finalTx(w, vault, rest, 750L,
          payout = tagged(lenderTree(ctx), vault.value, p.vault.id, vault.tokens.drop(1))))
      }
    }
  }

  property("claims: a partial claim cannot overpay, underfund the vault or misstate what is outstanding") {
    withCtx { ctx =>
      val p = pulled(ctx)
      val w = p.w
      val shares = spendOutput(p.mint, 0)
      val paidERG = p.vault.value / 4L
      val pay = tagged(lenderTree(ctx), paidERG, p.vault.id, Seq(Token(SigUSD, 175L)))
      val cont = UTXO(scripts(ctx).fractionalVault, p.vault.value - paidERG,
        Seq(Token(w.shareId, w.shares + 1L), Token(SigUSD, 525L)),
        Seq(p.vault.registers.head, longsValue(1000L, 750L, w.fee), p.vault.registers(2)))
      def tx(payout: UTXO = pay, next: UTXO = cont): UnsignedTransaction =
        partialTx(p, p.vault, shares, 250L, 1000L, payout, next)
      accepts(w.f.holder, tx())
      val badNext = Seq(cont.subValue(1L), cont.setContract(otherTree(ctx)),
        cont.setTokens(Token(w.shareId, w.shares + 1L), Token(SigUSD, 524L)),
        cont.withRegNum(5, longsValue(1000L, 751L, w.fee)),
        cont.withRegNum(5, longsValue(999L, 750L, w.fee)),
        cont.withRegNum(4, bytesValue(otherBondAsset.getBytes)),
        cont.withRegNum(6, ErgoValue.of(1.toByte)))
      badNext.foreach(n => rejectsAtSigning(w.f.holder, tx(next = n)))
      rejectsAtSigning(w.f.holder, tx(payout = pay.withRegNum(4, bytesValue(originOrderId))))
      rejectsAtSigning(w.f.holder, tx(payout = pay.setTokens(Token(SigUSD, 176L)),
        next = cont.setTokens(Token(w.shareId, w.shares + 1L), Token(SigUSD, 524L))))
    }
  }

  property("claims: a claim too small for an asset rounds that asset to zero and leaves it in the vault") {
    withCtx { ctx =>
      val p = pulled(ctx)
      // 700 raw SigUSD across 1000 shares: one share is worth less than one raw unit.
      val tx = accepts(p.w.f.holder, partialTx(p, p.vault, spendOutput(p.mint, 0), 1L, 1000L))
      tx.getOutputsToSpend.get(0).getTokens.size shouldBe 0
      tx.getOutputsToSpend.get(1).getTokens.get(1).getValue.longValue shouldBe 700L
      tx.getOutputsToSpend.get(1).getRegisters.get(1) shouldBe longsValue(1000L, 999L, p.w.fee)
    }
  }

  property("claims: before the pull there are no partial claims, but all shares take the bond unit back") {
    withCtx { ctx =>
      val w = wrap(ctx)
      val mint = minted(w)
      val vault = spendOutput(mint, 1)
      val shares = spendOutput(mint, 0)
      rejectsAtSigning(w.f.holder, build(ctx,
        Seq(vault.withCtxVar(0.toByte, ErgoValue.of(1)).withCtxVar(1.toByte, ErgoValue.of(0)), shares,
          funding(ctx, w.f.holder, 9)),
        Seq(tagged(lenderTree(ctx), minBox, vault.id),
          vault.toUTXO.withRegNum(5, longsValue(1000L, 750L, w.fee))),
        w.f.holder.getAddress, burn = Seq(Token(w.shareId, 250L))))
      val unwrapped = accepts(w.f.holder, finalTx(w, vault, shares, 1000L))
      unwrapped.getOutputsToSpend.get(0).getTokens.get(0).getId shouldBe w.f.asset
    }
  }
}
