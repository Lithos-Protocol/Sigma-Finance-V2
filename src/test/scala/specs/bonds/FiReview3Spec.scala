package org.lithosprotocol.sigmafi
package specs.bonds

import ksingh.sigmabonds.client.ScriptGenerator
import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import sigma.Colls
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

/**
 * Review 3 (pull wait, record bounty, keeper cut, FI share mint). Every property signs the
 * transaction it names, so each passes while the behaviour it pins exists.
 *
 * - R5T-*: the coupon pool trusting the home's R5(2) at a 3-unit burn, capped at the home's own value: which repo
 *   contract can be that home, the Long overflow, and what the cap changes (the uncapped pool as a mutant).
 * - CSE-*: the home's R5 is not read eagerly where it must not be.
 * - STAMP-*: the settlement stamp pin and the 720-block wait against the node's monotonic rule and refreshes.
 * - CUT-*: the cut summed over the pools, not per pool, on both sides.
 * - TQ-*: existing negatives checked against a mutant that drops the clause they claim refuses them.
 * - MINT-*: the FI mint is ZC's mint with one constant changed.
 */
class FiReview3Spec extends AnyPropSpec with FiStandardSharesSpecBase {
  import BondSpecBase._
  import ZcStandardSpecBase._

  private val cache = scala.collection.mutable.Map.empty[String, Contract]
  private def once(key: String)(c: => Contract): Contract = cache.getOrElseUpdate(key, c)

  private val Shortfall = "if (left > 0L) 0L else min(h.R5[Coll[Long]].get(2), h.value)"
  /** The pool before the cap: the burn shortfall the home's R5(2), unbounded. */
  private def uncapPool(ctx: BlockchainContext): Contract = once("uncap")(FiStandardSpecBase.poolVariant(ctx,
    Shortfall -> "if (left > 0L) 0L else h.R5[Coll[Long]].get(2)"))
  /** The rejected alternative: no R5 at all, the shortfall the home's whole value. */
  private def valuePool(ctx: BlockchainContext): Contract = once("value")(FiStandardSpecBase.poolVariant(ctx,
    Shortfall -> "if (left > 0L) 0L else h.value"))

  private val mark = Token(ErgoId.create("6d" * 32), 1L)
  private def usd(ts: Seq[Token]): Long = sumOf(ts, SigUSD)

  private def bv(ctx: BlockchainContext, created: Int, value: Long = 5L * minBox, tokens: Seq[Token] = Seq.empty): InputUTXO =
    inputAt(UTXO(scripts(ctx).vault, value, Token(bondAsset, 2L) +: tokens,
      Seq(bytesValue(ErgoId.create("77" * 32).getBytes), ErgoValue.of(0.toByte)), Some(created)), ctx, 31)

  /** A contract home (always-true script) holding `mark` then the unit, with `regs`. */
  private def trueHome(ctx: BlockchainContext, created: Int, regs: Seq[ErgoValue[_]], value: Long = Parameters.OneErg,
                       index: Int = 33): InputUTXO =
    inputAt(UTXO(Contract.SIGMA_TRUE, value, Seq(mark, Token(bondAsset, 1L)), regs, Some(created)), ctx, index)

  private def burnAt(ctx: BlockchainContext, h: Int, ins: Seq[InputUTXO], outs: Seq[UTXO], by: ErgoProver): Tx =
    txAt(ctx, h, ins :+ wallet(ctx, by, 7, h - 60), outs, by.getAddress, burn = Seq(Token(bondAsset, 3L)))

  // ═══ R5(2) TRUST ══════════════════════════════════════════════════════════

  property("R5T-1 (no stranger): ZC_Standard_LotAuction is a repo home whose R5(2) is seller-chosen and unbounded (its start height); the pool keeps at most the lot's own value of it, and only a paying buyer can burn there") {
    withCtx { ctx =>
      val ew = world(ctx, ErgBond)
      val h = ctx.getHeight + 100
      val lot = exchange(ctx).erg.lotAuction
      val opening = Parameters.OneErg
      def lotBox(start: Long): InputUTXO = inputAt(UTXO(lot, minBox, Seq(mark, Token(bondAsset, 1L)),
        Seq(bytesValue(hashOf(lenderTree(ctx))), longsValue(opening, opening, start, start + 1L, math.max(start + 2L, h + 1000L), 0L)),
        Some(h - 60)), ctx, 34).withCtxVar(0.toByte, int(0))
      val settled = bv(ctx, h - 60, value = 4L * Parameters.OneErg)
      val p = poolIn(ew, h - 50, amount = 3L * Parameters.OneErg)
      def buyBurn(l: InputUTXO, homeOut: Long, paySeller: Boolean = true): Tx = {
        val payout = UTXO(lenderTree(ctx), l.value + opening, Seq.empty, Seq(bytesValue(l.id.getBytes)))
        val again = UTXO(lot, homeOut, Seq(mark), Seq(bytesValue(hashOf(otherTree(ctx)))))
        txAt(ctx, h, Seq(l, settled, p, wallet(ctx, other(ctx), 7, h - 60)),
          (if (paySeller) Seq(payout) else Seq.empty) :+ again, other(ctx).getAddress, burn = Seq(Token(bondAsset, 3L)))
      }
      // Everything the payout at the lot script carries, less the cut: the lot, the settlement and the pool.
      val all = minBox + settled.value + p.value - cut(p.value)
      // Start far in the future: the price holds at the opening until then, and the lot is valid. The shortfall
      // beyond the cut is the lot's own value, not its R5(2).
      val far = 1000000000000000L
      val limit = settled.value + p.value - cut(p.value)
      all - math.min(far, minBox) shouldBe limit
      val s = ok(other(ctx), buyBurn(lotBox(far), limit))
      s.getOutputsToSpend.get(1).getValue.longValue shouldBe limit
      println(s"FIR3|lot home R5(2)=$far, value=$minBox: payout at the lot script holds $limit of " +
        s"${p.value + settled.value} pool+settlement nanoERG")
      no(other(ctx), buyBurn(lotBox(far), limit - 1L))
      no(other(ctx), buyBurn(lotBox(far), minBox))
      // The same burn without paying the seller is the lot's refusal: whoever burns there is a buyer.
      no(other(ctx), buyBurn(lotBox(far), limit, paySeller = false))
      // A realistic start (a height): the shortfall beyond the cut is the smaller of that height and the lot's value.
      val start = (h - 10).toLong
      val atStart = all - math.min(start, minBox)
      ok(other(ctx), buyBurn(lotBox(start), atStart))
      no(other(ctx), buyBurn(lotBox(start), atStart - 1L))
      println(s"FIR3|lot home R5(2)=$start, value=$minBox: shortfall ${all - atStart}")
    }
  }

  property("R5T-2 (attack failed): a negative R5(2) only asks for more; Long.MinValue overflows, and sigma's exact Long arithmetic then fails the collect closed, on the capped pool and on the uncapped mutant") {
    withCtx { ctx =>
      val ew = world(ctx, ErgBond)
      val h = ctx.getHeight + 100
      val settled = bv(ctx, h - 60)
      val p = poolIn(ew, h - 50, amount = 3L * Parameters.OneErg)
      val all = Parameters.OneErg + settled.value + p.value - cut(p.value)
      val minus1 = trueHome(ctx, h - 60, Seq(bytesValue(bondAsset.getBytes), longsValue(1L, 1L, -1L)))
      ok(executor(ctx), burnAt(ctx, h, Seq(minus1, settled, p), Seq(UTXO(Contract.SIGMA_TRUE, all + 1L, Seq(mark))), executor(ctx)))
      no(executor(ctx), burnAt(ctx, h, Seq(minus1, settled, p), Seq(UTXO(Contract.SIGMA_TRUE, all, Seq(mark))), executor(ctx)))
      val wrap = trueHome(ctx, h - 60, Seq(bytesValue(bondAsset.getBytes), longsValue(1L, 1L, Long.MinValue)))
      val up = poolIn(ew, h - 50, amount = 3L * Parameters.OneErg, pool = uncapPool(ctx))
      Seq(p, up).foreach { q =>
        no(executor(ctx), burnAt(ctx, h, Seq(wrap, settled, q), Seq(UTXO(Contract.SIGMA_TRUE, minBox, Seq(mark))), executor(ctx)))
        no(executor(ctx), burnAt(ctx, h, Seq(wrap, settled, q), Seq(UTXO(Contract.SIGMA_TRUE, 2L * all, Seq(mark))), executor(ctx)))
      }
    }
  }

  property("R5T-3 (cap measured): the shortfall capped at the home value closes a huge-R5(2) custom home that the uncapped mutant lets drop the pool, and keeps every repo vault's pull fee and cut exactly") {
    withCtx { ctx =>
      val ew = world(ctx, ErgBond)
      val tw = world(ctx)
      val h = ctx.getHeight + 100 + PullWait
      val c = fi(ctx).couponVault
      val sizes = Seq("uncapped" -> uncapPool(ctx), "current min(r,value)" -> c, "value only" -> valuePool(ctx)).map { case (n, k) => n -> k.ergoTree.bytes.length }
      println(s"FIR3|pool tree bytes: ${sizes.map { case (n, b) => s"$n=$b" }.mkString(" ")}")
      // 1. A custom home with a huge R5(2): the uncapped mutant lets the payout drop the pool; the pool does not.
      val settled = bv(ctx, h - 60)
      val huge = trueHome(ctx, h - 60, Seq(bytesValue(bondAsset.getBytes), longsValue(1L, 1L, 1000000000000000L)))
      val p = poolIn(ew, h - 50, amount = 3L * Parameters.OneErg, pool = uncapPool(ctx))
      val cp = poolIn(ew, h - 50, amount = 3L * Parameters.OneErg)
      ok(executor(ctx), burnAt(ctx, h, Seq(huge, settled, p), Seq(UTXO(Contract.SIGMA_TRUE, minBox, Seq(mark))), executor(ctx)))
      no(executor(ctx), burnAt(ctx, h, Seq(huge, settled, cp), Seq(UTXO(Contract.SIGMA_TRUE, minBox, Seq(mark))), executor(ctx)))
      val floorOut = settled.value + cp.value - cut(cp.value)
      ok(executor(ctx), burnAt(ctx, h, Seq(huge, settled, cp), Seq(UTXO(Contract.SIGMA_TRUE, floorOut, Seq(mark))), executor(ctx)))
      no(executor(ctx), burnAt(ctx, h, Seq(huge, settled, cp), Seq(UTXO(Contract.SIGMA_TRUE, floorOut - 1L, Seq(mark))), executor(ctx)))
      // 2. The FI share vault: the pull with a token pool keeps the pull fee and the cut, no more.
      val v = vaultBox(ctx, minBox + pullFee, Seq(Token(handShare, N + 1L), Token(bondAsset, 1L), Token(SigUSD, 600L)),
        h - 800)
      val src = settledIn(ctx, h - PullWait, Seq(Token(SigUSD, 800L)), 6000000L, 0.toByte)
      val tp = poolIn(tw, h - 40, amount = 1000L, erg = Parameters.OneErg, pool = c)
      ok(executor(ctx), pullTx(tw, v, src, h, pools = Seq(tp)))
      no(executor(ctx), pullTx(tw, v, src, h, pools = Seq(tp), out = pulledBox(v, src, keep = pullFee + 1L, pools = Seq(tp))))
      // 3. ZC fractional vault and Dutch auction vault pulls beside an ERG pool: fee and cut, exactly.
      val share = ErgoId.create("cc" * 32)
      val zcFee = 2000000L
      val s2 = inputAt(UTXO(scripts(ctx).vault, 4000000000L, Seq(Token(bondAsset, 2L)),
        Seq(bytesValue(ErgoId.create("77" * 32).getBytes), ErgoValue.of(0.toByte)), Some(h - 60)), ctx, 31)
      val zc = inputAt(UTXO(scripts(ctx).fractionalVault, minBox + zcFee, Seq(Token(share, 1001L), Token(bondAsset, 1L)),
        Seq(bytesValue(bondAsset.getBytes), longsValue(1000L, 1000L, zcFee)), Some(h - 60)), ctx, 3).withCtxVar(0.toByte, int(0))
      val auction = longsValue(4000000000L, 20000L, 15000L, 500L, 500L, Long.MaxValue, 0L)
      val dutch = inputAt(UTXO(exchange(ctx).dutchVault, minBox + zcFee, Seq(Token(share, 1001L), Token(bondAsset, 1L)),
        Seq(bytesValue(bondAsset.getBytes), longsValue(1000L, 1000L, zcFee), ErgoValue.of(0.toByte), auction,
          bytesValue(Array.emptyByteArray)), Some(h - 60)), ctx, 4).withCtxVar(0.toByte, int(0))
      def pulled(home: InputUTXO, value: Long): UTXO =
        UTXO(home.contract, value, Seq(Token(share, 1001L)),
          if (home.contract == exchange(ctx).dutchVault) home.registers else home.registers.take(2) :+ ErgoValue.of(0.toByte))
      def pull(home: InputUTXO, pools: Seq[InputUTXO], value: Long): Tx =
        burnAt(ctx, h, Seq(home, s2) ++ pools, Seq(pulled(home, value)), executor(ctx))
      val ep = poolIn(ew, h - 40, amount = Parameters.OneErg, pool = c)
      Seq(zc, dutch).foreach { home =>
        val all = home.value + s2.value
        ok(executor(ctx), pull(home, Seq(ep), all + ep.value - zcFee - cut(ep.value)))
        no(executor(ctx), pull(home, Seq(ep), all + ep.value - zcFee - cut(ep.value) - 1L))
      }
      // 4. The rejected alternative (shortfall = the home's value, no R5): at a ZC vault, whose own rule does not count
      // pools, the puller takes a small ERG pool whole. So the R5(2) bound is needed; the cap only limits it.
      val small = poolIn(ew, h - 40, amount = minBox, pool = valuePool(ctx))
      ok(executor(ctx), pull(zc, Seq(small), zc.value + s2.value - zcFee))
      val smallCap = poolIn(ew, h - 40, amount = minBox, pool = c)
      no(executor(ctx), pull(zc, Seq(smallCap), zc.value + s2.value - zcFee))
    }
  }

  // ═══ EAGER READS ══════════════════════════════════════════════════════════

  property("CSE-1: the home's R5 is not read while the unit survives (contract home with no R5, or a mistyped one) nor at a key home's burn with no registers") {
    withCtx { ctx =>
      val tw = world(ctx)
      val ew = world(ctx, ErgBond)
      val h = ctx.getHeight + 100
      val p = poolIn(tw, h - 50)
      Seq("no registers" -> Seq.empty[ErgoValue[_]], "R5 bytes" -> Seq(bytesValue(bondAsset.getBytes), bytesValue(bondAsset.getBytes)))
        .foreach { case (name, regs) =>
          withClue(name) {
            val home = trueHome(ctx, h - 60, regs)
            val out = UTXO(Contract.SIGMA_TRUE, home.value + p.value - cut(p.value), Seq(mark, Token(bondAsset, 1L), Token(SigUSD, tw.coupon)))
            ok(executor(ctx), txAt(ctx, h, Seq(home, p, wallet(ctx, executor(ctx), 7, h - 60)), Seq(out), executor(ctx).getAddress))
          }
        }
      // A key home with no registers burns all 3 beside a token pool and an ERG pool: outputs free.
      val keyed = inputAt(UTXO(lenderTree(ctx), minBox * 5L, Seq(Token(bondAsset, 1L)), Seq.empty, Some(h - 60)), ctx, 8)
      val ep = poolIn(ew, h - 50, index = 2, amount = Parameters.OneErg)
      ok(lender(ctx), txAt(ctx, h, Seq(keyed, bv(ctx, h - 60), p, ep), Seq(UTXO(lenderTree(ctx), minBox)),
        lender(ctx).getAddress, burn = Seq(Token(bondAsset, 3L))))
    }
  }

  // ═══ STAMP AND WAIT ═══════════════════════════════════════════════════════

  property("STAMP-1: after a bond Refresh (successor stamped HEIGHT - 60) repay and liquidation still settle with a fresh fee box; the node's monotonic rule and HEIGHT - 15 never conflict") {
    withCtx { ctx =>
      // Repay: a long bond, every coupon paid, refreshed after 525600 blocks, repaid one block later.
      val w = world(ctx, life = 600000L)
      val b = fiIn(w, fiBox(w, paid = couponCount))
      val h = w.start + RefreshAge + 1
      val rb = spendOutput(ok(executor(ctx), refreshTx(w, b, h)), 0)
      rb.input.getCreationHeight shouldBe h - 60
      val rep = ok(borrower(ctx), fiRepayTx(w, rb, h + 1))
      val src = spendOutput(rep, 0)
      src.input.getCreationHeight shouldBe h + 1
      // The share vault waits 720 from that stamp.
      val v = vaultBox(ctx, minBox + pullFee, Seq(Token(handShare, N + 1L), Token(bondAsset, 1L)), h - 50, c = fvOf(w))
      no(executor(ctx), pullTx(w, v, src, h + 1 + PullWait - 1))
      ok(executor(ctx), pullTx(w, v, src, h + 1 + PullWait))
      // Liquidation: a short bond refreshed after maturity, liquidated by a keeper whose fee box is from this block.
      val w2 = world(ctx)
      val b2 = fiIn(w2, fiBox(w2))
      val h2 = w2.start + RefreshAge + 1
      val rb2 = spendOutput(ok(executor(ctx), refreshTx(w2, b2, h2)), 0)
      ok(executor(ctx), txAt(ctx, h2 + 1, Seq(vars(rb2, Seq(0 -> liqVar(fi(ctx).payout), 1 -> int(0))),
        wallet(ctx, executor(ctx), 7, h2 + 1)), Seq(proceeds(w2, rb2)), executor(ctx).getAddress))
    }
  }

  property("STAMP-2: the bond vault's refresh may stamp HEIGHT - 999, after which the pull needs no further wait; a refresh needs the box 525600 old, so it never shortens a live wait") {
    withCtx { ctx =>
      val w = world(ctx)
      val c = ctx.getHeight
      val h = c + RefreshAge + 1
      val old = bv(ctx, c, value = 50000000L, tokens = Seq(Token(SigUSD, 800L)))
      val renewed = UTXO(scripts(ctx).vault, old.value - Bounty, old.tokens, old.registers, Some(h - 999))
      val r = ok(executor(ctx), txAt(ctx, h, Seq(old.withCtxVar(9.toByte, int(0))), Seq(renewed), executor(ctx).getAddress))
      val src = spendOutput(r, 0)
      val v = vaultBox(ctx, minBox + pullFee, Seq(Token(handShare, N + 1L), Token(bondAsset, 1L)), c, c = fvOf(w))
      ok(executor(ctx), pullTx(w, v, src, h))
      // Too young to refresh: refused (so no one re-stamps a fresh settlement).
      val young = bv(ctx, h - 100, value = 50000000L)
      no(executor(ctx), txAt(ctx, h, Seq(young.withCtxVar(9.toByte, int(0))),
        Seq(UTXO(scripts(ctx).vault, young.value - Bounty, young.tokens, young.registers, Some(h))), executor(ctx).getAddress))
    }
  }

  property("STAMP-3: a ZC fractional vault holding an FI unit pulls one block after the settlement; the 720-block wait is the FI vault's alone") {
    withCtx { ctx =>
      val h = ctx.getHeight + 100
      val share = ErgoId.create("cc" * 32)
      val zcFee = 2000000L
      val s = bv(ctx, h - 1)
      val zc = inputAt(UTXO(scripts(ctx).fractionalVault, minBox + zcFee, Seq(Token(share, 1001L), Token(bondAsset, 1L)),
        Seq(bytesValue(bondAsset.getBytes), longsValue(1000L, 1000L, zcFee)), Some(h - 60)), ctx, 3).withCtxVar(0.toByte, int(0))
      ok(executor(ctx), burnAt(ctx, h, Seq(zc, s), Seq(UTXO(zc.contract, zc.value + s.value - zcFee, Seq(Token(share, 1001L)),
        zc.registers.take(2) :+ ErgoValue.of(0.toByte))), executor(ctx)))
    }
  }

  // ═══ CUT ══════════════════════════════════════════════════════════════════

  property("CUT-1: two pools of 199 and 1 raw SigUSD (and 1000199 + 1000001 nanoERG) are cut once on their sum: 1 unit and 10001 nanoERG, not the per-pool 0 and 10000") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = ctx.getHeight + 100
      val v = vaultBox(ctx, minBox + pullFee, Seq(Token(handShare, N + 1L), Token(bondAsset, 1L), Token(SigUSD, 600L)), h - 50)
      val p1 = poolIn(w, h - 40, index = 1, amount = 199L, erg = 1000199L)
      val p2 = poolIn(w, h - 40, index = 2, amount = 1L, erg = 1000001L)
      val e = absorbedAll(v, Seq(p1, p2))
      e.value shouldBe v.value + p1.value + p2.value - 10001L
      usd(e.tokens) shouldBe 600L + 200L - 1L
      ok(executor(ctx), absorbTx(w, v, Seq(p1, p2), h))
      no(executor(ctx), absorbTx(w, v, Seq(p1, p2), h, next = e.subValue(1L)))
      no(executor(ctx), absorbTx(w, v, Seq(p1, p2), h, next = e.setTokens(e.tokens.init :+ Token(SigUSD, 798L): _*)))
      // The per-pool reading (no token cut) is refused: the vault's list is exact given the summed cut.
      no(executor(ctx), absorbTx(w, v, Seq(p1, p2), h, next = e.setTokens(e.tokens.init :+ Token(SigUSD, 800L): _*).addValue(1L),
        callerTokens = Seq(Token(SigUSD, 1L))))
    }
  }

  // ═══ TEST QUALITY ═════════════════════════════════════════════════════════

  property("TQ-1: 'the date cannot be recorded twice' is refused by k > lastBreach: the bond without it signs the same re-record") {
    withCtx { ctx =>
      val w = world(ctx)
      val mut = once("noRise")(FiStandardSpecBase.bondVariant(ctx, SigUSD.getBytes, fi(ctx).couponVault,
        "k > lastBreach && " -> ""))
      val b = fiIn(w, fiBox(w, paid = 1L).setContract(mut))
      val h = w.due(0) + 5
      val breached = spendOutput(ok(borrower(ctx), recordTx(w, b, h, 0L, by = borrower(ctx),
        next = recorded(w, b, h, 0L).subValue(FiStandardSpecBase.RecordBounty))), 0)
      val cleared = spendOutput(ok(borrower(ctx), cureTx(w, breached, h + 1,
        next = cured(w, breached, h + 1).addValue(FiStandardSpecBase.RecordBounty))), 0)
      ok(executor(ctx), recordTx(w, cleared, h + 2, 0L, price = w.slump + 1L))
      ok(executor(ctx), recordTx(w, cleared, h + 2, 0L, price = w.slump + 1L,
        next = recorded(w, cleared, h + 2, 0L).subValue(FiStandardSpecBase.RecordBounty)))
    }
  }

  // ═══ MINT ═════════════════════════════════════════════════════════════════

  property("MINT-1: FI_Standard_FractionalMint with its minimum set back to 2000000 and the audit terms (F-017, F-023, F-025) taken out is byte-identical to ZC_Standard_FractionalMint bound to the FI vault") {
    withCtx { ctx =>
      val back = FiStandardSpecBase.synchronized {
        Contract.fromErgoContract(ctx.compileContract(ConstantsBuilder.create()
          .item("CONST_FRACTIONAL_VAULT_HASH", Colls.fromArray(fv(ctx).hashedPropBytes)).build(),
          FiStandardSpecBase.edited(ScriptGenerator.mkScript(FiStandardSharesSpecBase.MintPath),
            Seq("pullFee >= 100000000L" -> "pullFee >= 2000000L",
              " &&\n                shareOut.creationInfo._1 >= HEIGHT - 1000L" -> "",
              "vault.value - pullFee <= 1000000L &&\n                " -> "",
              " &&\n                vault.creationInfo._1 >= HEIGHT - 1000L" -> "",
              " &&\n            b.creationInfo._1 > HEIGHT - 1051200" -> ""))))
      }
      back.ergoTree.bytes.toSeq shouldBe FiStandardSharesSpecBase.zcMintFor(ctx, fv(ctx)).ergoTree.bytes.toSeq
      shares(ctx).mint.ergoTree.bytes.toSeq should not be back.ergoTree.bytes.toSeq
    }
  }
}
