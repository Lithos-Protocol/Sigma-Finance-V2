package org.lithosprotocol.sigmafi
package specs.bonds

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{InputUTXO, Token, UTXO}

/**
 * ZC_Standard_DutchAuctionMint and ZC_Standard_DutchAuctionVault, layered on a standard bond. The lender
 * owns the request and the shares; the executor batches mints, pulls and auction purchases.
 */
class ZcStandardDutchAuctionSpec extends AnyPropSpec with ZcStandardSpecBase {
  import BondSpecBase._
  import ZcStandardSpecBase._

  private val kinds: Seq[Kind] = Seq(ErgBond, TokenBond)
  private val pullFee: Long = 2000000L
  private val mintFee: Long = 2000000L
  private val shares: Long = 1000L
  private val metadata: Seq[String] = Seq("Auction shares", "Shares of one bond", "0")

  private case class Auction(opening: Long, floor: Long, length: Long) {
    def longs: Seq[Long] = Seq(opening, floor, length)
  }

  /**
   * A 7 ERG repayment against 10 ERG of collateral: the vault holds about 10.001 ERG of its currency
   * after the pull, so a 150% floor (10.5 ERG) leaves it short and opens an auction. A SigUSD bond's
   * default collateral holds no SigUSD, so any floor opens one.
   */
  private def defaultAuction(kind: Kind): Auction =
    if (kind == ErgBond) Auction(20000L, 15000L, 500L) else Auction(15000L, 11000L, 500L)

  private def currency(kind: Kind): Array[Byte] = if (kind == ErgBond) Array.empty[Byte] else SigUSD.getBytes

  private case class Wrapped(f: Flow, a: Auction, fill: SignedTransaction, request: InputUTXO) {
    def shareId: ErgoId = request.id
    def bond: InputUTXO = InputUTXO(fill.getOutputsToSpend.get(1))
    def kind: Kind = f.kind
    def ctx: BlockchainContext = f.ctx
  }

  private def requestBox(ctx: BlockchainContext, kind: Kind, asset: ErgoId, a: Auction): UTXO =
    UTXO(marketOf(ctx, kind).dutchMint, 2L * minBox + pullFee + mintFee, Seq(Token(asset, 1L)),
      eip4() ++ Seq(bytesValue(hashOf(lenderTree(ctx))),
        longsValue(Seq(shares, pullFee, mintFee) ++ a.longs: _*), textsValue(metadata: _*)))

  /** A fill whose circulating unit goes straight into a Dutch auction request. */
  private def wrap(ctx: BlockchainContext, kind: Kind = ErgBond, a: Auction = null,
                   collateral: Seq[Token] = defaultCollateral): Wrapped = {
    val auction = if (a == null) defaultAuction(kind) else a
    val f = flow(ctx, kind, collateral = collateral)
    val fill = accepts(f.holder, standardFillTx(f, unit = requestBox(ctx, kind, f.asset, auction)))
    Wrapped(f, auction, fill, spendOutput(fill, 0))
  }

  private def shareBox(w: Wrapped): UTXO =
    UTXO(lenderTree(w.ctx), minBox, Seq(Token(w.shareId, shares)), metadata.map(utf8))

  private def auctionLongs(w: Wrapped, start: Long): ErgoValue[_] =
    longsValue(Seq(w.f.data(0)) ++ w.a.longs ++ Seq(start): _*)

  /** R6 is only a placeholder at mint: registers are dense and R7, R8 follow it. */
  private def vaultBox(w: Wrapped): UTXO =
    UTXO(exchange(w.ctx).dutchVault, w.request.value - mintFee - minBox,
      Seq(Token(w.shareId, shares + 1L), Token(w.f.asset, 1L)),
      Seq(bytesValue(w.f.asset.getBytes), longsValue(shares, shares, pullFee), ErgoValue.of(0.toByte),
        auctionLongs(w, 0L), bytesValue(currency(w.kind))))

  /** Run by a batcher with the request alone. Var 0: the state data input; var 1: a settled bond's bytes. */
  private def mintTx(w: Wrapped, shareOut: UTXO = null, vault: UTXO = null, state: InputUTXO = null,
                     bondBytes: Array[Byte] = null): UnsignedTransaction = {
    val base = w.request.withCtxVar(0.toByte, ErgoValue.of(0))
    val in = if (bondBytes == null) base else base.withCtxVar(1.toByte, bytesValue(bondBytes))
    build(w.ctx, Seq(in), Seq(if (shareOut == null) shareBox(w) else shareOut, if (vault == null) vaultBox(w) else vault),
      executor(w.ctx).getAddress, dataInputs = Seq(if (state == null) w.bond else state))
  }

  private def repaid(w: Wrapped): InputUTXO =
    spendOutput(accepts(w.f.owner, standardRepayTx(w.f, repayInput(w.bond))), 0)

  private def liquidated(w: Wrapped): InputUTXO =
    spendOutput(accepts(executor(w.ctx), standardLiquidationTx(w.f, liquidationInput(w.f, w.bond))), 0)

  // ═══ MINT ═════════════════════════════════════════════════════════════════

  for (kind <- kinds) {
    property(s"$kind mint: 2N+1 shares, and the vault records the live bond's repayment and the auction terms") {
      withCtx { ctx =>
        val w = wrap(ctx, kind)
        val tx = accepts(executor(ctx), mintTx(w))
        val shareOut = tx.getOutputsToSpend.get(0)
        shareOut.getTokens.get(0).getId shouldBe w.shareId
        shareOut.getTokens.get(0).getValue.longValue shouldBe shares
        val vault = tx.getOutputsToSpend.get(1)
        vault.getTokens.get(0).getValue.longValue shouldBe shares + 1L
        vault.getRegisters.get(3) shouldBe auctionLongs(w, 0L)
        vault.getRegisters.get(4) shouldBe bytesValue(currency(kind))
      }
    }
  }

  property("mint: the auction terms are bounded") {
    withCtx { ctx =>
      Seq(Auction(1000000L, 0L, 1000000L), Auction(10000L, 10000L, 1L)).foreach { a =>
        accepts(executor(ctx), mintTx(wrap(ctx, a = a)))
      }
      Seq(Auction(10000L, 10001L, 500L), Auction(1000001L, 10000L, 500L), Auction(15000L, 11000L, 0L),
        Auction(15000L, 11000L, 1000001L), Auction(15000L, -1L, 500L)).foreach { a =>
        rejectsAtSigning(executor(ctx), mintTx(wrap(ctx, a = a)))
      }
    }
  }

  property("mint: the vault carries this bond's repayment and the market's currency, from this bond's state box") {
    withCtx { ctx =>
      val w = wrap(ctx)
      val vault = vaultBox(w)
      accepts(executor(ctx), mintTx(w, vault = vault))
      Seq(vault.withRegNum(7, longsValue(w.f.data(0) - 1L, 20000L, 15000L, 500L, 0L)),
        vault.withRegNum(7, longsValue(w.f.data(0), 20000L, 15000L, 500L, 1L)),
        vault.withRegNum(7, longsValue(w.f.data(0), 20000L, 14999L, 500L, 0L)),
        vault.withRegNum(8, bytesValue(SigUSD.getBytes)),
        vault.withRegNum(5, longsValue(shares, shares - 1L, pullFee)),
        vault.setContract(scripts(ctx).fractionalVault)).foreach { bad =>
        rejectsAtSigning(executor(ctx), mintTx(w, vault = bad))
      }
      val bond = w.bond.toUTXO
      val collateral = bond.tokens.drop(1)
      Seq(bond.setTokens(Token(otherBondAsset, 2L) +: collateral: _*), bond.setTokens(Token(w.f.asset, 1L) +: collateral: _*),
        bond.setContract(otherTree(ctx)), bond.setContract(bondOf(ctx, TokenBond))).foreach { fake =>
        rejectsAtSigning(executor(ctx), mintTx(w, state = inputAt(fake, ctx, 30)))
      }
    }
  }

  property("mint: after the bond settles, the bond is read from its bytes, proved by the settlement's R4") {
    withCtx { ctx =>
      Seq[Wrapped => InputUTXO](repaid, liquidated).foreach { settle =>
        val w = wrap(ctx)
        val settlement = settle(w)
        accepts(executor(ctx), mintTx(w, state = settlement, bondBytes = w.bond.bytes))
        // Without the bytes the settlement is not a bond; other bytes do not hash to its R4.
        rejectsAtSigning(executor(ctx), mintTx(w, state = settlement))
        rejectsAtSigning(executor(ctx), mintTx(w, state = settlement, bondBytes = w.request.bytes))
      }
      // Against the live bond, the bytes that hash to its R4 are the order box, which is not a bond.
      val w = wrap(ctx)
      rejectsAtSigning(executor(ctx), mintTx(w, bondBytes = w.f.orderIn.bytes))
    }
  }

  property("mint: the owner cancels a request and nobody else can") {
    withCtx { ctx =>
      val w = wrap(ctx)
      def cancel(by: ErgoProver): UnsignedTransaction =
        build(ctx, Seq(w.request, funding(ctx, by, 5)), Seq(UTXO(contractOf(by), w.request.value, Seq(Token(w.f.asset, 1L)))),
          by.getAddress)
      accepts(lender(ctx), cancel(lender(ctx)))
      rejectsAtSigning(other(ctx), cancel(other(ctx)))
    }
  }

  // ═══ PULL ═════════════════════════════════════════════════════════════════

  private case class Minted(w: Wrapped, mint: SignedTransaction) {
    def vault: InputUTXO = InputUTXO(mint.getOutputsToSpend.get(1))
    def holder: InputUTXO = InputUTXO(mint.getOutputsToSpend.get(0))
    /** Pulls, sales and claims run after maturity, once a liquidation is possible. */
    def height: Int = w.f.maturity.toInt + 5
  }

  private def minted(ctx: BlockchainContext, kind: Kind = ErgBond, a: Auction = null,
                     collateral: Seq[Token] = defaultCollateral): Minted = {
    val w = wrap(ctx, kind, a, collateral)
    Minted(w, accepts(executor(ctx), mintTx(w)))
  }

  private def pulledBox(m: Minted, source: InputUTXO, state: Byte, start: Long, value: Long = -1L): UTXO =
    UTXO(exchange(m.w.ctx).dutchVault, if (value < 0L) m.vault.value + source.value - pullFee else value,
      Token(m.w.shareId, shares + 1L) +: source.tokens.drop(1),
      Seq(bytesValue(m.w.f.asset.getBytes), longsValue(shares, shares, pullFee), ErgoValue.of(state),
        auctionLongs(m.w, start), bytesValue(currency(m.w.kind))))

  /** The executor spends the two vaults, burns all 3 bond units and keeps the pull fee. */
  private def pullTx(m: Minted, source: InputUTXO, out: UTXO, height: Int = -1, funded: Boolean = false): UnsignedTransaction =
    buildAt(m.w.ctx, if (height < 0) m.height else height,
      Seq(m.vault.withCtxVar(0.toByte, ErgoValue.of(0)), source) ++
        (if (funded) Seq(funding(m.w.ctx, executor(m.w.ctx), 9)) else Seq.empty),
      Seq(out), executor(m.w.ctx).getAddress, burn = Seq(Token(m.w.f.asset, 3L)))

  for (kind <- kinds) {
    property(s"$kind pull: a repaid bond opens no auction, and a liquidated lot short of the floor opens one") {
      withCtx { ctx =>
        val m = minted(ctx, kind)
        val paid = repaid(m.w)
        accepts(executor(ctx), pullTx(m, paid, pulledBox(m, paid, 0.toByte, 0L)))
        rejectsAtSigning(executor(ctx), pullTx(m, paid, pulledBox(m, paid, 1.toByte, m.height)))
        rejectsAtSigning(executor(ctx), pullTx(m, paid, pulledBox(m, paid, 0.toByte, m.height)))

        val l = minted(ctx, kind)
        val proceeds = liquidated(l.w)
        Seq(l.height, l.height - 8).foreach { start =>
          accepts(executor(ctx), pullTx(l, proceeds, pulledBox(l, proceeds, 1.toByte, start.toLong)))
        }
        Seq(l.height - 9L, l.height + 1L, 0L).foreach { start =>
          rejectsAtSigning(executor(ctx), pullTx(l, proceeds, pulledBox(l, proceeds, 1.toByte, start)))
        }
        rejectsAtSigning(executor(ctx), pullTx(l, proceeds, pulledBox(l, proceeds, 2.toByte, 0L)))
        // The auction terms ride along unchanged.
        rejectsAtSigning(executor(ctx), pullTx(l, proceeds,
          pulledBox(l, proceeds, 1.toByte, l.height).withRegNum(7, longsValue(l.w.f.data(0), 20000L, 1L, 500L, l.height))))
      }
    }
  }

  property("pull: currency held at the floor target means no auction, one unit below it means an auction") {
    withCtx { ctx =>
      // ERG: the floor target is 7 ERG * 150% = 10.5 ERG. The puller may add nanoERG to the vault.
      val m = minted(ctx)
      val proceeds = liquidated(m.w)
      val target = 10500000000L
      accepts(executor(ctx), pullTx(m, proceeds, pulledBox(m, proceeds, 2.toByte, 0L, target), funded = true))
      rejectsAtSigning(executor(ctx), pullTx(m, proceeds, pulledBox(m, proceeds, 1.toByte, m.height, target), funded = true))
      accepts(executor(ctx), pullTx(m, proceeds, pulledBox(m, proceeds, 1.toByte, m.height, target - 1L), funded = true))
      rejectsAtSigning(executor(ctx), pullTx(m, proceeds, pulledBox(m, proceeds, 2.toByte, 0L, target - 1L), funded = true))

      // SigUSD: the floor target is 700 * 110% = 770 raw SigUSD, counted among the proceeds.
      Seq(770L -> 2.toByte, 769L -> 1.toByte).foreach { case (held, state) =>
        val t = minted(ctx, TokenBond, collateral = Seq(Token(collateralToken, 100L), Token(SigUSD, held)))
        val src = liquidated(t.w)
        val start = if (state == 1.toByte) t.height.toLong else 0L
        accepts(executor(ctx), pullTx(t, src, pulledBox(t, src, state, start)))
        val other = if (state == 1.toByte) 2.toByte else 1.toByte
        rejectsAtSigning(executor(ctx), pullTx(t, src, pulledBox(t, src, other, if (other == 1.toByte) t.height.toLong else 0L)))
      }
    }
  }

  property("pull: nothing to sell, or a floor of 0, opens no auction") {
    withCtx { ctx =>
      val bare = minted(ctx, collateral = Seq.empty)
      val src = liquidated(bare.w)
      accepts(executor(ctx), pullTx(bare, src, pulledBox(bare, src, 2.toByte, 0L)))
      rejectsAtSigning(executor(ctx), pullTx(bare, src, pulledBox(bare, src, 1.toByte, bare.height)))
      val zero = minted(ctx, a = Auction(20000L, 0L, 500L))
      val src0 = liquidated(zero.w)
      accepts(executor(ctx), pullTx(zero, src0, pulledBox(zero, src0, 2.toByte, 0L)))
      rejectsAtSigning(executor(ctx), pullTx(zero, src0, pulledBox(zero, src0, 1.toByte, zero.height)))
    }
  }

  // ═══ SALE ═════════════════════════════════════════════════════════════════

  private case class Open(m: Minted, vault: InputUTXO, start: Int) {
    def ctx: BlockchainContext = m.w.ctx
    def kind: Kind = m.w.kind
  }

  private def open(ctx: BlockchainContext, kind: Kind): Open = {
    val m = minted(ctx, kind)
    val src = liquidated(m.w)
    val tx = accepts(executor(ctx), pullTx(m, src, pulledBox(m, src, 1.toByte, m.height.toLong)))
    Open(m, spendOutput(tx, 0), m.height)
  }

  /** The least currency the vault must hold after a sale `since` blocks into the auction. */
  private def target(o: Open, since: Long): Long = {
    val a = o.m.w.a
    val s = math.min(since, a.length)
    ceilDiv(BigInt(o.m.w.f.data(0)) * (BigInt(a.opening) * a.length - BigInt(a.opening - a.floor) * s),
      BigInt(10000L) * a.length)
  }

  /** The buyer brings the vault's currency up to `after` and takes the lot as change. */
  private def soldBox(o: Open, after: Long): UTXO = {
    val v = o.vault.toUTXO
    val regs = v.registers.updated(2, ErgoValue.of(2.toByte))
    if (o.kind == ErgBond) v.copy(value = after, tokens = Seq(v.tokens.head), registers = regs)
    else v.copy(value = minBox, tokens = Seq(v.tokens.head, Token(SigUSD, after)), registers = regs)
  }

  private def saleTx(o: Open, out: UTXO, since: Long, vault: InputUTXO = null): UnsignedTransaction =
    buildAt(o.ctx, o.start + since.toInt,
      Seq((if (vault == null) o.vault else vault).withCtxVar(0.toByte, ErgoValue.of(0)),
        funding(o.ctx, executor(o.ctx), 9, slack, if (o.kind == ErgBond) Seq.empty[Token] else Seq(Token(SigUSD, 100000L)))),
      Seq(out), executor(o.ctx).getAddress)

  for (kind <- kinds) {
    property(s"$kind sale: the vault's currency must reach the falling target, to one raw unit, and then stays at the floor") {
      withCtx { ctx =>
        val o = open(ctx, kind)
        Seq(0L, 250L, 500L, 800L).foreach { since =>
          accepts(executor(ctx), saleTx(o, soldBox(o, target(o, since)), since))
          rejectsAtSigning(executor(ctx), saleTx(o, soldBox(o, target(o, since) - 1L), since))
        }
        target(o, 800L) shouldBe target(o, 500L)
      }
    }
  }

  property("sale: the lot leaves whole, the auction closes, and its terms stay") {
    withCtx { ctx =>
      val o = open(ctx, ErgBond)
      val sold = soldBox(o, target(o, 100L))
      accepts(executor(ctx), saleTx(o, sold, 100L))
      Seq(sold.setTokens(sold.tokens.head, Token(collateralToken, 1L)),
        sold.withRegNum(6, ErgoValue.of(1.toByte)),
        sold.withRegNum(7, auctionLongs(o.m.w, o.start + 1L)),
        sold.setContract(otherTree(ctx))).foreach { bad =>
        rejectsAtSigning(executor(ctx), saleTx(o, bad, 100L))
      }
      // A vault that already held the floor in currency has no auction to buy into.
      val held = minted(ctx)
      val src = liquidated(held.w)
      val closed = spendOutput(accepts(executor(ctx), pullTx(held, src, pulledBox(held, src, 2.toByte, 0L, 10500000000L),
        funded = true)), 0)
      rejectsAtSigning(executor(ctx), saleTx(o, soldBox(o.copy(vault = closed), 20000000000L), 100L, vault = closed))
    }
  }

  // ═══ CLAIMS ═══════════════════════════════════════════════════════════════

  private def share(amount: Long, burned: Long, outstanding: Long): Long = (BigInt(amount) * burned / outstanding).toLong

  /** Outputs: 0 the payout, 1 the continuing vault, in state `nextState`. */
  private def partialTx(w: Wrapped, vault: InputUTXO, holder: InputUTXO, burned: Long, outstanding: Long,
                        nextState: Byte, height: Int): UnsignedTransaction = {
    val ctx = w.ctx
    val assets = vault.tokens.drop(1)
    val paidERG = share(vault.value, burned, outstanding)
    val paid = assets.map(t => Token(t.id, share(t.amount, burned, outstanding))).filter(_.amount > 0L)
    val kept = assets.map(t => Token(t.id, t.amount - share(t.amount, burned, outstanding))).filter(_.amount > 0L)
    val next = UTXO(exchange(ctx).dutchVault, vault.value - paidERG, Token(w.shareId, shares + 1L) +: kept,
      Seq(vault.registers.head, longsValue(shares, outstanding - burned, pullFee), ErgoValue.of(nextState),
        vault.registers(3), vault.registers(4)))
    buildAt(ctx, height,
      Seq(vault.withCtxVar(0.toByte, ErgoValue.of(1)).withCtxVar(1.toByte, ErgoValue.of(0)), holder,
        funding(ctx, w.f.holder, 9)),
      Seq(tagged(lenderTree(ctx), math.max(paidERG, minBox), vault.id, paid), next), w.f.holder.getAddress,
      burn = Seq(Token(w.shareId, burned)))
  }

  /** Every outstanding share and the reserve burn; the payout takes the whole vault. */
  private def finalTx(w: Wrapped, vault: InputUTXO, holder: InputUTXO, height: Int): UnsignedTransaction =
    buildAt(w.ctx, height, Seq(vault.withCtxVar(1.toByte, ErgoValue.of(0)), holder, funding(w.ctx, w.f.holder, 9)),
      Seq(tagged(lenderTree(w.ctx), vault.value, vault.id, vault.tokens.drop(1))), w.f.holder.getAddress,
      burn = Seq(Token(w.shareId, 2L * shares + 1L)))

  property("claims: an open auction blocks partial claims for its length, then a claim takes the lot in kind and closes it") {
    withCtx { ctx =>
      val o = open(ctx, ErgBond)
      val w = o.m.w
      val end = o.start + w.a.length.toInt
      rejectsAtSigning(w.f.holder, partialTx(w, o.vault, o.m.holder, 250L, shares, 3.toByte, end - 1))
      rejectsAtSigning(w.f.holder, partialTx(w, o.vault, o.m.holder, 250L, shares, 1.toByte, end))
      val tx = accepts(w.f.holder, partialTx(w, o.vault, o.m.holder, 250L, shares, 3.toByte, end))
      tx.getOutputsToSpend.get(0).getTokens.get(0).getValue.longValue shouldBe 25L
      // In kind for good: the rest of the lot can no longer be bought.
      val after = spendOutput(tx, 1)
      rejectsAtSigning(executor(ctx), saleTx(o.copy(vault = after), soldBox(o.copy(vault = after), 20000000000L),
        w.a.length + 1L, vault = after))
    }
  }

  property("claims: after a sale they pay pro rata in the currency, and the final claim takes everything in every state") {
    withCtx { ctx =>
      val o = open(ctx, ErgBond)
      val w = o.m.w
      // Open auction: the final claim needs no wait.
      accepts(w.f.holder, finalTx(w, o.vault, o.m.holder, o.start + 1))
      // Sold: partial claims run at once, in the currency only.
      val sold = spendOutput(accepts(executor(ctx), saleTx(o, soldBox(o, target(o, 0L)), 0L)), 0)
      val claim = accepts(w.f.holder, partialTx(w, sold, o.m.holder, 250L, shares, 2.toByte, o.start + 1))
      claim.getOutputsToSpend.get(0).getValue shouldBe sold.value / 4L
      claim.getOutputsToSpend.get(0).getTokens.size shouldBe 0
      accepts(w.f.holder, finalTx(w, sold, o.m.holder, o.start + 1))
      // Before the pull, all shares un-fractionalize; after a repayment, they take the repayment.
      val m = minted(ctx, TokenBond)
      accepts(m.w.f.holder, finalTx(m.w, m.vault, m.holder, m.height))
      val paid = repaid(m.w)
      val pulled = spendOutput(accepts(executor(ctx), pullTx(m, paid, pulledBox(m, paid, 0.toByte, 0L))), 0)
      accepts(m.w.f.holder, partialTx(m.w, pulled, m.holder, 500L, shares, 0.toByte, m.height))
      accepts(m.w.f.holder, finalTx(m.w, pulled, m.holder, m.height))
    }
  }
}
