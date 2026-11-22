package org.lithosprotocol.sigmafi
package specs.bonds

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import org.lithosprotocol.sigmafi.mutations.{InputUTXO, Token, UTXO}

/**
 * The paid refresh on ZC_Standard_Bond, ZC_Standard_BondVault, ZC_Standard_FractionalVault and
 * ZC_Standard_DutchAuctionVault (ZC_Standard_WrapVault's is in ZcStandardWrapSpec): anyone re-creates a box more than
 * 525600 blocks old, unchanged, keeping at most 0.01 ERG of it and never taking it under its floor. And what a
 * refreshed box still does: a bond repays, liquidates and recollateralizes at once; a vault is claimed or pulled.
 */
class ZcStandardRefreshSpec extends AnyPropSpec with ZcStandardSpecBase {
  import BondSpecBase._
  import ZcStandardSpecBase._

  private val Age = 525600
  private val Bounty = 10000000L

  /** Var 9 names the successor at output 0; the bounty, less the miner fee, is the executor's change. */
  private def refreshTx(ctx: BlockchainContext, box: InputUTXO, next: UTXO, height: Int,
                        extra: Seq[InputUTXO] = Seq.empty): UnsignedTransaction =
    buildAt(ctx, height, box.withCtxVar(9.toByte, ErgoValue.of(0)) +: extra, Seq(next), executor(ctx).getAddress)

  /** A bond maturing `blocks` after the mocked height, filled; its creation height is its start. */
  private def longBond(ctx: BlockchainContext, blocks: Long, kind: Kind = ErgBond): (Flow, InputUTXO, InputUTXO) = {
    val f = flow(ctx, kind, fixedMaturity = ctx.getHeight.toLong + blocks)
    val fill = accepts(f.holder, standardFillTx(f))
    (f, spendOutput(fill, 1), spendOutput(fill, 0))
  }

  // ═══ BOND ═════════════════════════════════════════════════════════════════

  property("bond: after 525600 blocks anyone re-creates it unchanged, for at most 0.01 ERG, never under the reserve plus 0.001") {
    withCtx { ctx =>
      val (f, bond, _) = longBond(ctx, 700000L)
      val at = f.height + Age + 1
      val ok = bond.toUTXO.subValue(Bounty).setCreationHeight(at - 60)
      accepts(executor(ctx), refreshTx(ctx, bond, ok, at))
      rejectsAtSigning(executor(ctx), refreshTx(ctx, bond, ok, at - 1))
      rejectsAtSigning(executor(ctx), refreshTx(ctx, bond, ok.subValue(1L), at))
      // Every register, token and the script stay.
      Seq(ok.withRegNum(6, longsValue(f.data.updated(0, f.data(0) + 1L): _*)),
        ok.withRegNum(9, longsValue(f.fee, f.start, f.maturity + 1L)),
        ok.withRegNum(5, bytesValue(hashOf(otherTree(ctx)))),
        ok.setTokens(ok.tokens.head +: ok.tokens.tail.map(t => Token(t.id, t.amount - 1L)): _*),
        ok.setContract(otherTree(ctx))).foreach { bad =>
        rejectsAtSigning(executor(ctx), refreshTx(ctx, bond, bad, at))
      }
      // The floor binds before the bounty: a bond holding its reserve plus 0.006 ERG keeps the reserve plus 0.001.
      val thin = bond.toUTXO.setValue(f.fee + 6000000L).setCreationHeight(f.height)
      val thinIn = inputAt(thin, ctx, 0)
      accepts(executor(ctx), refreshTx(ctx, thinIn, thin.setValue(f.fee + minBox).setCreationHeight(at - 60), at))
      rejectsAtSigning(executor(ctx), refreshTx(ctx, thinIn, thin.setValue(f.fee + minBox - 1L).setCreationHeight(at - 60), at))
    }
  }

  property("bond: the stamp is 60 to 999 blocks old, so a refresh never starts the recollateralize wait") {
    withCtx { ctx =>
      val (f, bond, _) = longBond(ctx, 700000L)
      val at = f.height + Age + 1
      def stamped(s: Int): UTXO = bond.toUTXO.subValue(Bounty).setCreationHeight(s)
      Seq(at - 60, at - 999).foreach(s => accepts(executor(ctx), refreshTx(ctx, bond, stamped(s), at)))
      Seq(at - 59, at, at - 1000).foreach(s => rejectsAtSigning(executor(ctx), refreshTx(ctx, bond, stamped(s), at)))
      // The borrower recollateralizes the refreshed bond in the very block it was refreshed in.
      val refreshed = spendOutput(accepts(executor(ctx), refreshTx(ctx, bond, stamped(at - 60), at)), 0)
      accepts(f.owner, recollateralizeTx(f, refreshed, refreshed.toUTXO.addValue(minBox), at))
    }
  }

  property("bond: no refresh in the 720 blocks before maturity, where it could void a repayment; from maturity on, again") {
    withCtx { ctx =>
      val (f, bond, _) = longBond(ctx, 600000L)
      val m = f.maturity.toInt
      def at(h: Int): UnsignedTransaction = refreshTx(ctx, bond, bond.toUTXO.subValue(Bounty).setCreationHeight(h - 60), h)
      accepts(executor(ctx), at(m - 721))
      Seq(m - 720, m - 1).foreach(h => rejectsAtSigning(executor(ctx), at(h)))
      Seq(m, m + 100).foreach(h => accepts(executor(ctx), at(h)))
    }
  }

  property("bond: a refreshed bond repays, liquidates, and settles into a vault naming the refreshed box") {
    withCtx { ctx =>
      for (kind <- Seq[Kind](ErgBond, TokenBond)) {
        val (f, bond, unit) = longBond(ctx, 700000L, kind)
        val at = f.height + Age + 1
        val refreshed = spendOutput(accepts(executor(ctx),
          refreshTx(ctx, bond, bond.toUTXO.subValue(Bounty).setCreationHeight(at - 60), at)), 0)
        accepts(f.owner, standardRepayTx(f, repayInput(refreshed), height = at + 1))
        val settled = spendOutput(accepts(executor(ctx), standardLiquidationTx(f, liquidationInput(f, refreshed))), 0)
        settled.registers.head shouldBe bytesValue(refreshed.id.getBytes)
        accepts(f.holder, standardClaimTx(f, settled, unit))
        val borrowerUnit = inputAt(UTXO(borrowerTree(ctx), minBox, Seq(Token(f.asset, 1L))), ctx, 12)
        accepts(f.owner, buildAt(ctx, at + 1, Seq(refreshed, borrowerUnit),
          Seq(UTXO(borrowerTree(ctx), refreshed.value + borrowerUnit.value - Parameters.MinFee, f.collateral)),
          f.owner.getAddress, burn = Seq(Token(f.asset, 3L))))
      }
    }
  }

  // ═══ VAULTS ═══════════════════════════════════════════════════════════════

  property("bond vault: refreshed unchanged with no extra register, then claimed by the unit as before") {
    withCtx { ctx =>
      val (f, bond, unit) = longBond(ctx, 1000L)
      val vault = spendOutput(accepts(executor(ctx), standardLiquidationTx(f, liquidationInput(f, bond))), 0)
      val at = vault.input.getCreationHeight + Age + 1
      val ok = vault.toUTXO.subValue(Bounty).setCreationHeight(at)
      val refreshed = spendOutput(accepts(executor(ctx), refreshTx(ctx, vault, ok, at)), 0)
      rejectsAtSigning(executor(ctx), refreshTx(ctx, vault, ok, at - 1))
      Seq(ok.withRegNum(6, bytesValue(Array[Byte](1))), ok.withRegNum(5, ErgoValue.of(0.toByte)),
        ok.subValue(1L), ok.setCreationHeight(at - 1000)).foreach { bad =>
        rejectsAtSigning(executor(ctx), refreshTx(ctx, vault, bad, at))
      }
      accepts(f.holder, standardClaimTx(f, refreshed, unit))
    }
  }

  private val share: ErgoId = ErgoId.create("cc" * 32)
  private val pullFee: Long = 2000000L

  property("fractional vault: holding, it keeps its pull fee; pulled, it keeps its path byte") {
    withCtx { ctx =>
      val (f, _, _) = longBond(ctx, 1000L)
      val h0 = f.height
      val at = h0 + Age + 1
      val floor = minBox + pullFee
      val holding = UTXO(scripts(ctx).fractionalVault, floor + 5000000L, Seq(Token(share, 101L), Token(f.asset, 1L)),
        Seq(bytesValue(f.asset.getBytes), longsValue(100L, 100L, pullFee)), Some(h0))
      val hIn = inputAt(holding, ctx, 0)
      accepts(executor(ctx), refreshTx(ctx, hIn, holding.setValue(floor).setCreationHeight(at), at))
      rejectsAtSigning(executor(ctx), refreshTx(ctx, hIn, holding.setValue(floor - 1L).setCreationHeight(at), at))
      rejectsAtSigning(executor(ctx), refreshTx(ctx, hIn, holding.setValue(floor).setCreationHeight(at)
        .withRegNum(6, ErgoValue.of(0.toByte)), at))
      val pulled = UTXO(scripts(ctx).fractionalVault, 20000000L, Seq(Token(share, 101L), Token(SigUSD, 700L)),
        Seq(bytesValue(f.asset.getBytes), longsValue(100L, 100L, pullFee), ErgoValue.of(1.toByte)), Some(h0))
      val pIn = inputAt(pulled, ctx, 0)
      accepts(executor(ctx), refreshTx(ctx, pIn, pulled.subValue(Bounty).setCreationHeight(at), at))
      rejectsAtSigning(executor(ctx), refreshTx(ctx, pIn, pulled.subValue(Bounty).setCreationHeight(at)
        .withRegNum(6, ErgoValue.of(0.toByte)), at))
    }
  }

  property("Dutch vault: every register kept; refreshed mid-way through an excess auction, its price is unchanged") {
    withCtx { ctx =>
      val (f, _, _) = longBond(ctx, 1000L)
      val h0 = f.height
      val at = h0 + Age + 1
      val repayment = 7000000000L
      // An ERG-currency vault in an excess auction (state 4) that began 10 blocks ago and falls for 500.
      val auction = longsValue(repayment, 20000L, 15000L, 500L, 2160L, Long.MaxValue, at - 10L)
      val vault = UTXO(exchange(ctx).dutchVault, 10500000000L, Seq(Token(share, 101L), Token(collateralToken, 100L)),
        Seq(bytesValue(f.asset.getBytes), longsValue(100L, 100L, pullFee), ErgoValue.of(4.toByte), auction,
          bytesValue(Array.emptyByteArray)), Some(h0))
      val in = inputAt(vault, ctx, 0)
      val ok = vault.subValue(Bounty).setCreationHeight(at)
      Seq(ok.withRegNum(6, ErgoValue.of(1.toByte)), ok.withRegNum(7, longsValue(repayment, 20000L, 15000L, 500L, 2160L,
        Long.MaxValue, at)), ok.withRegNum(9, bytesValue(Array[Byte](1)))).foreach { bad =>
        rejectsAtSigning(executor(ctx), refreshTx(ctx, in, bad, at))
      }
      val refreshed = spendOutput(accepts(executor(ctx), refreshTx(ctx, in, ok, at)), 0)
      // The excess price 20 blocks in, whatever the vault now holds: 7 ERG * 200% * 480 / 500.
      val price = ceilDiv(BigInt(repayment) * 20000L * 480L, BigInt(10000L) * 500L)
      def sale(paid: Long): UnsignedTransaction = {
        val v = refreshed.toUTXO
        val sold = v.copy(value = refreshed.value + paid, tokens = Seq(v.tokens.head), registers = v.registers.updated(2, ErgoValue.of(2.toByte)))
        buildAt(ctx, (at - 10 + 20), Seq(refreshed.withCtxVar(0.toByte, ErgoValue.of(0)), funding(ctx, executor(ctx), 9)),
          Seq(sold), executor(ctx).getAddress)
      }
      accepts(executor(ctx), sale(price))
      rejectsAtSigning(executor(ctx), sale(price - 1L))
    }
  }
}
