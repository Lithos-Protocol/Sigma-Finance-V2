package org.lithosprotocol.sigmafi
package specs.bonds

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import scala.collection.JavaConverters._
import scala.util.Try

/**
 * FI_Standard_FractionalVault: shares of an FI unit, coupons absorbed into the vault from the bond's coupon
 * pool, then the pull and the pro-rata claims.
 *
 * A refusal is paired with the one-field change that makes the same transaction sign, or with a mutant
 * vault (compiled from the file with one clause removed) that signs it, so the named clause refused it.
 * - `noAssetCheck`: Absorb and Pull without "every pool's R4 is the bond asset".
 * - `ergOnlyPool`: the coupon pool whose contract-home collect counts nanoERG but no token (`onErgOnly`
 *   is the vault pinning it): with it the vault absorbs a pool holding any token, the token left
 *   behind, so it is the pool's own sum, beside the vault's exact token list, that keeps such a pool out.
 * - `burnShut`: the coupon pool that collects at a contract home only while the unit survives, as before
 *   the pull could take pools (`onBurnShut` is the vault pinning it).
 */
class FiStandardSharesSpec extends AnyPropSpec with FiStandardSharesSpecBase {
  import BondSpecBase._
  import ZcStandardSpecBase._

  private val kinds = Seq[Kind](TokenBond, ErgBond)

  private val cache = scala.collection.mutable.Map.empty[String, Contract]
  private def once(key: String)(c: => Contract): Contract = cache.getOrElseUpdate(key, c)

  private val usdBytes: Array[Byte] = SigUSD.getBytes

  private def noAssetCheck(ctx: BlockchainContext): Contract = once("noAsset")(FiStandardSharesSpecBase.vaultFor(ctx,
    fi(ctx).couponVault, usdBytes, "pools.forall { (b: Box) => b.R4[Coll[Byte]].get == bondAsset } &&" -> "true &&"))

  private val HomeCarries =
    "carries((p, (INPUTS.filter { (b: Box) => b.propositionBytes == home || held(b) > 0L }.append(vaults),\n" +
    "                        if (left > 0L) 0L else min(h.R5[Coll[Long]].get(2), h.value))))"
  private def ergOnlyPool(ctx: BlockchainContext): Contract = once("ergOnlyPool")(FiStandardSpecBase.poolVariant(ctx,
    HomeCarries -> ("p.value >= INPUTS.filter { (b: Box) => b.propositionBytes == home || held(b) > 0L }" +
      ".append(vaults).fold(0L, { (a: Long, b: Box) => a + b.value }) - vaults.fold(0L, { (a: Long, b: Box) => a + b.value }) / 200L")))
  private def onErgOnly(ctx: BlockchainContext): Contract =
    once("onErgOnly")(FiStandardSharesSpecBase.vaultFor(ctx, ergOnlyPool(ctx), usdBytes))
  private def burnShut(ctx: BlockchainContext): Contract = once("burnShut")(FiStandardSpecBase.poolVariant(ctx,
    "held(p) == min(left, 1L)" -> "left > 0L && held(p) == 1L"))
  private def onBurnShut(ctx: BlockchainContext, erg: Boolean): Contract =
    once(s"onBurnShut|$erg")(FiStandardSharesSpecBase.vaultFor(ctx, burnShut(ctx), if (erg) Array.emptyByteArray else usdBytes))

  private def usd(ts: Seq[Token]): Long = sumOf(ts, SigUSD)

  /** The unit's vault holding (share, N+1), the unit and `coupons`, at `value`, created at `created`. */
  private def holdingVault(ctx: BlockchainContext, created: Int, value: Long = minBox + pullFee,
                           coupons: Seq[Token] = Seq.empty, c: Contract = null, asset: ErgoId = bondAsset,
                           share: ErgoId = handShare, index: Int = 30): InputUTXO =
    vaultBox(ctx, value, Seq(Token(share, N + 1L), Token(asset, 1L)) ++ coupons, created, c = c, asset = asset, index = index)

  // ═══ FULL LIFE ════════════════════════════════════════════════════════════

  /**
   * Shares minted on the unit at the start; coupon 0 paid and absorbed; coupon 1 paid into a fresh pool.
   * Repaid life: coupon 2's Pay tops up that pool, one absorb takes both, and the bond is repaid.
   * Liquidated life: coupon 1's pool is absorbed, coupon 2 is missed and the bond liquidated. Then the
   * pull, the shares split three ways, two partial claims and the final claim. Every holder's payout is
   * checked against the pro-rata formula, and their sum against the pulled vault, to the nanoERG and the
   * raw token unit.
   */
  private def life(ctx: BlockchainContext, kind: Kind, liquidate: Boolean, collateral: Seq[Token] = null): Unit = {
    val w = world(ctx, kind, collateral = collateral)
    val m = minted(w, w.start, w.start + 1)
    m.vault.tokens shouldBe Seq(Token(m.shareId, N + 1L), Token(bondAsset, 1L))

    val pay0 = ok(borrower(ctx), payTx(w, fiIn(w, fiBox(w)), w.due(0) - 10))
    val p0 = spendOutput(pay0, 1)
    val a1 = ok(executor(ctx), absorbTx(w, m.vault, Seq(p0), w.due(0) - 9))
    val v1 = spendOutput(a1, 0)
    v1.value shouldBe m.vault.value + p0.value - ergCut(Seq(p0))
    if (w.erg) v1.tokens shouldBe m.vault.tokens
    else v1.tokens shouldBe m.vault.tokens :+ Token(SigUSD, w.coupon)
    println(s"FICOST|absorb $kind, fresh vault + 1 coupon=${a1.getCost}|successor bytes=${outBytes(a1, 0)}")

    // Coupon 1 into a fresh pool; coupon 2 (repaid life) tops that pool up, so one absorb takes both.
    val pay1 = ok(borrower(ctx), payTx(w, spendOutput(pay0, 0), w.due(1) - 10))
    val p1 = spendOutput(pay1, 1)
    val (bond, pool, absorbAt) =
      if (liquidate) (spendOutput(pay1, 0), p1, w.due(1) - 9)
      else {
        val pay2 = ok(borrower(ctx), payTx(w, spendOutput(pay1, 0), w.due(2) - 10, extraIns = Seq(p1),
          coupon = toppedPool(w, Seq(p1))))
        (spendOutput(pay2, 0), spendOutput(pay2, 1), w.due(2) - 9)
      }
    val a2 = ok(executor(ctx), absorbTx(w, v1, Seq(pool), absorbAt))
    val v2 = spendOutput(a2, 0)
    val paidCoupons = if (liquidate) 2L else 3L
    v2.value shouldBe v1.value + pool.value - ergCut(Seq(pool))
    if (!w.erg) { usd(v2.tokens) shouldBe paidCoupons * w.coupon - cut(w.coupon) - cut(sumOf(pool.tokens, SigUSD)); v2.tokens.size shouldBe 3 }
    println(s"FICOST|absorb $kind, vault already holding coupons=${a2.getCost}|successor bytes=${outBytes(a2, 0)}")

    val (source, settledAt) =
      if (liquidate) {
        val h = w.due(2) + grace
        (spendOutput(ok(executor(ctx), fiLiquidateTx(w, bond, h)), 0), h)
      } else {
        val h = w.due(2) + 5
        (spendOutput(ok(borrower(ctx), fiRepayTx(w, bond, h)), 0), h)
      }
    // The pull waits 720 blocks from the settlement's creation height.
    no(executor(ctx), pullTx(w, v2, source, settledAt + PullWait - 1))
    val pull = ok(executor(ctx), pullTx(w, v2, source, settledAt + PullWait))
    val pv = spendOutput(pull, 0)
    pv.registers(2) shouldBe ErgoValue.of((if (liquidate) 1 else 0).toByte)
    pv.value shouldBe v2.value + source.value - pullFee
    pv.tokens shouldBe pulledTokens(v2, source)
    println(s"FICOST|pull $kind ${if (liquidate) "liquidated" else "repaid"}, with coupons=${pull.getCost}|successor bytes=${outBytes(pull, 0)}")

    // What the share holders are owed between them: everything in the pulled vault.
    val owedErg = pv.value
    val owedTokens = pv.tokens.drop(1)
    if (!w.erg && !liquidate) owedTokens shouldBe Seq(Token(SigUSD, usd(v2.tokens) + w.repayment))

    // The shares split: 300 to `other`, 200 to `third`, 500 kept by the lender.
    val split = ok(lender(ctx), splitTx(w, m.held, Seq(other(ctx) -> 300L, third(ctx) -> 200L, lender(ctx) -> 500L), settledAt + PullWait + 1))
    val (sb, sc, sa) = (spendOutput(split, 0), spendOutput(split, 1), spendOutput(split, 2))

    val h = settledAt + PullWait + 2
    val c1t = partialTx(w, pv, sb, 300L, other(ctx), h)
    val c1 = ok(other(ctx), c1t)
    val pv1 = spendOutput(c1, 1)
    val c2 = ok(third(ctx), partialTx(w, pv1, sc, 200L, third(ctx), h + 1))
    val pv2 = spendOutput(c2, 1)
    outstandingOf(pv2) shouldBe 500L
    val c3 = ok(lender(ctx), finalTx(w, pv2, sa, lender(ctx), h + 2))
    println(s"FICOST|claims $kind: partial=${c1.getCost}|final=${c3.getCost}")

    val got = Seq(paidTo(c1, 1, otherTree(ctx)), paidTo(c2, 1, contractOf(third(ctx))), paidTo(c3, 1, lenderTree(ctx)))
    // Each partial is the floor of its pro-rata part of the vault it spent.
    got(0)._1 shouldBe part(pv.value, 300L, 1000L)
    got(1)._1 shouldBe part(pv1.value, 200L, 700L)
    got(2)._1 shouldBe pv2.value
    owedTokens.foreach { t =>
      got(0)._2.getOrElse(t.id, 0L) shouldBe part(t.amount, 300L, 1000L)
      got(1)._2.getOrElse(t.id, 0L) shouldBe part(sumOf(pv1.tokens, t.id), 200L, 700L)
    }
    // Together they took exactly what the vault held: nothing left, nothing invented.
    got.map(_._1).sum shouldBe owedErg
    owedTokens.foreach(t => got.map(_._2.getOrElse(t.id, 0L)).sum shouldBe t.amount)
    got.flatMap(_._2.keys).toSet shouldBe owedTokens.map(_.id).toSet
  }

  for (kind <- kinds) {
    property(s"life ($kind, repaid): mint, pay, absorb, pay twice, absorb, repay, pull, claims; every unit accounted for") {
      withCtx(ctx => life(ctx, kind, liquidate = false))
    }
    property(s"life ($kind, liquidated): two coupons absorbed, the third missed; the pull merges coupons and collateral") {
      withCtx(ctx => life(ctx, kind, liquidate = true))
    }
  }

  property("life (TokenBond, liquidated, collateral holding SigUSD): the coupons' SigUSD and the collateral's merge into one entry") {
    withCtx { ctx =>
      life(ctx, TokenBond, liquidate = true, collateral = Seq(Token(collateralToken, 100L), Token(SigUSD, 50L)))
    }
  }

  property("running example: token bond, 3 coupons of 500 raw SigUSD; two absorbed, the third taken in by the pull; who keeps what") {
    withCtx { ctx =>
      val base = world(ctx)
      val w = base.copy(terms = base.terms.updated(7, 500L))
      w.coupon shouldBe 500L
      val m = minted(w, w.start, w.start + 1)
      val pay0 = ok(borrower(ctx), payTx(w, fiIn(w, fiBox(w)), w.due(0) - 10))
      val a0 = ok(executor(ctx), absorbTx(w, m.vault, Seq(spendOutput(pay0, 1)), w.due(0) - 9))
      val pay1 = ok(borrower(ctx), payTx(w, spendOutput(pay0, 0), w.due(1) - 10))
      val a1 = ok(executor(ctx), absorbTx(w, spendOutput(a0, 0), Seq(spendOutput(pay1, 1)), w.due(1) - 9))
      val pay2 = ok(borrower(ctx), payTx(w, spendOutput(pay1, 0), w.due(2) - 10))
      val p2 = spendOutput(pay2, 1)
      val r = w.due(2) - 5
      val src = spendOutput(ok(borrower(ctx), fiRepayTx(w, spendOutput(pay2, 0), r)), 0)
      val v = spendOutput(a1, 0)
      // Each pool: 500 SigUSD beside the 1000000 nanoERG box minimum the borrower funds at Pay.
      Seq(spendOutput(pay0, 1), spendOutput(pay1, 1), p2).foreach { q => usd(q.tokens) shouldBe 500L; q.value shouldBe minBox }
      cut(500L) shouldBe 2L
      cut(minBox) shouldBe 5000L
      usd(v.tokens) shouldBe 2L * (500L - 2L)
      val pull = ok(executor(ctx), pullTx(w, v, src, r + PullWait, pools = Seq(p2)))
      val pv = spendOutput(pull, 0)
      usd(pv.tokens) shouldBe 3L * (500L - 2L) + w.repayment
      pv.value shouldBe v.value + src.value + p2.value - pullFee - cut(p2.value)
      println(s"FIRUN|borrower funds per coupon: 500 SigUSD + $minBox nanoERG pool box (no extra)")
      println(s"FIRUN|absorber per coupon: ${cut(500L)} raw SigUSD + ${cut(minBox)} nanoERG, less its own miner fee")
      println(s"FIRUN|puller (taking coupon 2 in): pull fee $pullFee nanoERG (lender-funded at mint) + ${cut(500L)} raw SigUSD + ${cut(minBox)} nanoERG")
      println(s"FIRUN|share holders receive ${usd(pv.tokens)} raw SigUSD of ${3L * 500L + w.repayment} paid in; ${3L * cut(500L)} raw SigUSD and ${3L * cut(minBox)} nanoERG went to keepers")
    }
  }

  // ═══ ABSORB ═══════════════════════════════════════════════════════════════

  property("absorb: the successor is exactly the vault plus the pool; nothing dropped, shrunk, added, reordered or moved") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = ctx.getHeight + 100
      val v = holdingVault(ctx, h - 50)
      val p = poolIn(w, h - 40, amount = 40L, erg = 3L * minBox)
      val e = absorbed(v, p)
      e.tokens shouldBe Seq(Token(handShare, N + 1L), Token(bondAsset, 1L), Token(SigUSD, 40L))
      val s = ok(executor(ctx), absorbTx(w, v, Seq(p), h))
      s.getOutputsToSpend.get(0).getValue shouldBe v.value + p.value - cut(p.value)
      // More ERG is a gift and signs.
      ok(executor(ctx), absorbTx(w, v, Seq(p), h, next = e.addValue(1L)))
      val withJunk = Seq(Token(secondCollateral, 10L))
      Seq(
        "value - 1" -> e.subValue(1L),
        "SigUSD - 1" -> e.setTokens(e.tokens.init :+ Token(SigUSD, 39L): _*),
        "SigUSD dropped" -> e.setTokens(e.tokens.init: _*),
        "caller's token added" -> e.setTokens(e.tokens :+ Token(secondCollateral, 1L): _*),
        "coupon before the unit" -> e.setTokens(e.tokens(0), e.tokens(2), e.tokens(1)),
        "R4 changed" -> e.withRegNum(4, bytesValue(otherBondAsset.getBytes)),
        "R5 changed" -> e.withRegNum(5, longsValue(N, N - 1L, pullFee)),
        "R6 added" -> e.setRegs(e.registers :+ ErgoValue.of(0.toByte): _*),
        "another script" -> e.setContract(lenderTree(ctx))
      ).foreach { case (name, bad) =>
        withClue(name)(no(executor(ctx), absorbTx(w, v, Seq(p), h, next = bad, callerTokens = withJunk)))
      }
      // The control for the caller's-token case: the same caller box, its token left in change, signs.
      ok(executor(ctx), absorbTx(w, v, Seq(p), h, callerTokens = withJunk))
      // The successor index must name an output.
      Seq(-1, 3).foreach(i => no(executor(ctx), absorbTx(w, v, Seq(p), h, index = i)))
    }
  }

  for (kind <- kinds) {
    property(s"absorb ($kind): the caller keeps 1/200 of the pools' own nanoERG and coupons, rounded down, and no more") {
      withCtx { ctx =>
        val w = world(ctx, kind)
        val h = ctx.getHeight + 100
        val v = holdingVault(ctx, h - 50, c = fvOf(w), coupons = if (w.erg) Seq.empty else Seq(Token(SigUSD, 600L)))
        // 1 ERG and 1000 raw SigUSD (token pool), or 1 ERG (ERG pool): a cut of 5000000 nanoERG and 5 SigUSD.
        val p = poolIn(w, h - 40, amount = if (w.erg) Parameters.OneErg else 1000L, erg = Parameters.OneErg)
        val e = absorbed(v, p)
        e.value shouldBe v.value + p.value - 5000000L
        if (!w.erg) usd(e.tokens) shouldBe 600L + 1000L - 5L
        val s = ok(executor(ctx), absorbTx(w, v, Seq(p), h))
        // Everything in but the successor is the caller's: the cut, less the miner fee it pays.
        (v.value + p.value) - s.getOutputsToSpend.get(0).getValue shouldBe 5000000L
        println(s"FIREWARD|absorb $kind: pool of ${p.value} nanoERG${if (w.erg) "" else " and 1000 SigUSD"}; caller keeps 5000000 nanoERG${if (w.erg) "" else " and 5 SigUSD"}")
        // One nanoERG, or one SigUSD unit, past the cut: refused.
        no(executor(ctx), absorbTx(w, v, Seq(p), h, next = e.subValue(1L)))
        if (!w.erg) no(executor(ctx), absorbTx(w, v, Seq(p), h,
          next = e.setTokens(e.tokens.init :+ Token(SigUSD, usd(e.tokens) - 1L): _*)))
        // A successor holding the whole pool (no cut taken) is refused for tokens (they are exact given the cut),
        // and a gift of nanoERG on top of the cut signs.
        if (!w.erg) no(executor(ctx), absorbTx(w, v, Seq(p), h,
          next = e.setTokens(e.tokens.init :+ Token(SigUSD, 1600L): _*), callerTokens = Seq(Token(SigUSD, 5L))))
        ok(executor(ctx), absorbTx(w, v, Seq(p), h, next = e.addValue(5000000L)))
        // Rounded down: 199 units cut to 0, 200 to 1.
        if (!w.erg) {
          Seq(199L -> 0L, 200L -> 1L, 399L -> 1L).foreach { case (amount, c) =>
            val q = poolIn(w, h - 40, amount = amount, erg = minBox)
            cut(amount) shouldBe c
            val eq = absorbed(v, q)
            usd(eq.tokens) shouldBe 600L + amount - c
            ok(executor(ctx), absorbTx(w, v, Seq(q), h))
            no(executor(ctx), absorbTx(w, v, Seq(q), h, next = eq.setTokens(eq.tokens.init :+ Token(SigUSD, usd(eq.tokens) - 1L): _*)))
          }
        }
      }
    }

    property(s"absorb ($kind): repeated hand-made gift pools never take one nanoERG or token unit of what the vault held; the giver gets back at most 1/200 of the gift") {
      withCtx { ctx =>
        val w = world(ctx, kind)
        val h = ctx.getHeight + 100
        var v = holdingVault(ctx, h - 50, c = fvOf(w), coupons = if (w.erg) Seq.empty else Seq(Token(SigUSD, 600L)))
        val priorErg = v.value
        val priorTokens = v.tokens
        // Gifts of every size, the attacker taking the most each rule allows every time.
        val gifts = Seq(1L, 199L, 200L, minBox, 7777777L, Parameters.OneErg)
        gifts.zipWithIndex.foreach { case (g, i) =>
          val t = if (w.erg) 0L else g
          val gift = poolIn(w, h - 40 + i, index = 50 + i, amount = if (w.erg) g else t, erg = if (w.erg) g else math.max(g, minBox))
          val before = v
          val s = ok(executor(ctx), absorbTx(w, before, Seq(gift), h + i))
          v = spendOutput(s, 0)
          // The vault never falls below what it held before the gift, in nanoERG or in any token.
          v.value should be >= before.value
          before.tokens.foreach(tk => sumOf(v.tokens, tk.id) should be >= tk.amount)
          // What the giver gets back is the cut of its own gift, never more.
          (before.value + gift.value) - v.value shouldBe cut(gift.value)
          if (!w.erg) sumOf(gift.tokens, SigUSD) + usd(before.tokens) - usd(v.tokens) shouldBe cut(t)
          // One more nanoERG out of the vault: refused.
          no(executor(ctx), absorbTx(w, before, Seq(gift), h + i, next = absorbed(before, gift).subValue(1L)))
        }
        v.value should be >= priorErg
        priorTokens.foreach(tk => sumOf(v.tokens, tk.id) should be >= tk.amount)
        v.tokens.take(2) shouldBe priorTokens.take(2)
      }
    }
  }

  property("absorb: the pool is carried whole into one output; splitting the coupon off to a second vault box is refused") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = ctx.getHeight + 100
      val v = holdingVault(ctx, h - 50)
      val p = poolIn(w, h - 40, amount = 40L)
      val e = absorbed(v, p)
      val side = UTXO(fv(ctx), minBox, Seq(Token(SigUSD, 40L)), e.registers)
      no(executor(ctx), absorbTx(w, v, Seq(p), h, next = e.setTokens(e.tokens.init: _*).subValue(minBox), extraOuts = Seq(side)))
      ok(executor(ctx), absorbTx(w, v, Seq(p), h))
    }
  }

  property("absorb: several pools of this bond in one transaction merge into one coupon entry; a pool of another bond is refused") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = ctx.getHeight + 100
      val v = holdingVault(ctx, h - 50, coupons = Seq(Token(SigUSD, 7L)))
      val p1 = poolIn(w, h - 40, index = 1)
      val p2 = poolIn(w, h - 40, index = 2, amount = 2L * w.coupon, erg = 2L * minBox)
      val both = absorbedAll(v, Seq(p1, p2))
      both.tokens shouldBe Seq(Token(handShare, N + 1L), Token(bondAsset, 1L), Token(SigUSD, 7L + 3L * w.coupon))
      both.value shouldBe v.value + p1.value + p2.value - cut(p1.value + p2.value)
      ok(executor(ctx), absorbTx(w, v, Seq(p1, p2), h))
      // Only one of the two counted: refused (by this box's exact list and by the other pool's rule).
      no(executor(ctx), absorbTx(w, v, Seq(p1, p2), h, next = absorbed(v, p2).addValue(p1.value)))
      ok(executor(ctx), absorbTx(w, v, Seq(p1), h))
      // A pool of another bond beside this bond's: refused, even with its coupon in the successor.
      val py = poolIn(w, h - 40, index = 3, asset = otherBondAsset)
      no(executor(ctx), absorbTx(w, v, Seq(p1, py), h, next = absorbedAll(v, Seq(p1, py))))
    }
  }

  property("absorb: a pool of another bond is refused by the vault's R4 check, even where that bond's unit holder opens it") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = w.due(0)
      // The other bond's unit, in the lender's key box, is spent beside its pool: the pool itself is open (its
      // holder collects with the outputs free), so only the vault can refuse it.
      val py = poolIn(w, h - 40, asset = otherBondAsset)
      val yUnit = unit(ctx, h - 40, asset = otherBondAsset)
      def tx(v: InputUTXO): Tx = txAt(ctx, h, Seq(v.withCtxVar(0.toByte, int(0)), py, yUnit, wallet(ctx, lender(ctx), 6, h)),
        Seq(absorbed(v, py)), lender(ctx).getAddress)
      no(lender(ctx), tx(holdingVault(ctx, h - 50)))
      ok(lender(ctx), tx(holdingVault(ctx, h - 50, c = noAssetCheck(ctx))))
      // A pool of this bond in the same spot signs on the real vault.
      val px = poolIn(w, h - 40)
      ok(lender(ctx), txAt(ctx, h, Seq(holdingVault(ctx, h - 50).withCtxVar(0.toByte, int(0)), px, wallet(ctx, lender(ctx), 6, h)),
        Seq(absorbed(holdingVault(ctx, h - 50), px)), lender(ctx).getAddress))
    }
  }

  property("absorb: a box at another script naming the bond is no pool, so there is nothing to absorb") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = ctx.getHeight + 100
      val v = holdingVault(ctx, h - 50)
      val fake = poolIn(w, h - 40, pool = Contract.SIGMA_TRUE)
      no(executor(ctx), absorbTx(w, v, Seq(fake), h))
      ok(executor(ctx), absorbTx(w, v, Seq(poolIn(w, h - 40)), h))
      // Without any pool at all.
      no(executor(ctx), txAt(ctx, h, Seq(v.withCtxVar(0.toByte, int(0)), wallet(ctx, executor(ctx), 7, h)), Seq(v.toUTXO),
        executor(ctx).getAddress))
    }
  }

  property("absorb: a pool holding any token but the repayment token is refused: a share, junk, or SigUSD in the ERG vault") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = ctx.getHeight + 100
      val v = holdingVault(ctx, h - 50, coupons = Seq(Token(SigUSD, 20L)))
      val p = poolIn(w, h - 40, amount = 40L)
      def salted(extra: Token, c: Contract = null): InputUTXO = {
        val u = p.toUTXO.setTokens(extra, Token(SigUSD, 40L))
        inputAt((if (c == null) u else u.setContract(c)).setCreationHeight(h - 40), ctx, 9, "ab" * 32)
      }
      ok(executor(ctx), absorbTx(w, v, Seq(p), h))
      Seq("share" -> Token(handShare, 1L), "junk" -> junk(1).head).foreach { case (name, extra) =>
        val bad = salted(extra)
        // The successor without the extra token (this box takes only SigUSD), or with it appended: refused.
        withClue(name)(no(executor(ctx), absorbTx(w, v, Seq(bad), h)))
        val e = absorbed(v, bad)
        withClue(name)(no(executor(ctx), absorbTx(w, v, Seq(bad), h, next = if (extra.id == handShare) e.setTokens(Token(handShare, N + 2L) +: e.tokens.tail: _*) else e.setTokens(e.tokens :+ extra: _*))))
        // Beside a pool that does not ask for its tokens at a contract home, the same absorb signs, the extra
        // token left to the caller: the pool's own sum refused it. The reserve is intact either way.
        val mv = holdingVault(ctx, h - 50, coupons = Seq(Token(SigUSD, 20L)), c = onErgOnly(ctx))
        val s = withClue(name)(ok(executor(ctx), absorbTx(w, mv, Seq(salted(extra, ergOnlyPool(ctx))), h)))
        spendOutput(s, 0).tokens shouldBe Seq(Token(handShare, N + 1L), Token(bondAsset, 1L), Token(SigUSD, 60L))
      }
      // The ERG vault takes no token at all: a SigUSD pool is refused there.
      val ew = world(ctx, ErgBond)
      val ev = holdingVault(ctx, h - 50, c = fvOf(ew))
      no(executor(ctx), absorbTx(ew, ev, Seq(p), h))
      no(executor(ctx), absorbTx(ew, ev, Seq(p), h, next = UTXO(fvOf(ew), ev.value + p.value, ev.tokens ++ p.tokens, ev.registers)))
      ok(executor(ctx), absorbTx(ew, ev, Seq(poolIn(ew, h - 40)), h))
    }
  }

  property("absorb: a hand-made pool adds only nanoERG and SigUSD; no number of them adds a token id") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = ctx.getHeight + 100
      // Front-run: a hand-made pool holding junk, sent before the first genuine pool, cannot be absorbed.
      val fresh = holdingVault(ctx, h - 50)
      val handJunk = inputAt(couponOut(w, amount = 1L).setTokens(junk(1): _*).setCreationHeight(h - 40), ctx, 2, "ab" * 32)
      no(executor(ctx), absorbTx(w, fresh, Seq(handJunk), h))
      // Hand-made pools of nanoERG alone are gifts; ten in one absorb leave the token list as it was.
      val gifts = (0 until 10).map(i => inputAt(couponOut(w, amount = 1L).setTokens().setCreationHeight(h - 40), ctx, 10 + i, "ab" * 32))
      val s = ok(executor(ctx), absorbTx(w, fresh, gifts, h))
      val v1 = spendOutput(s, 0)
      v1.tokens shouldBe fresh.tokens
      println(s"FICOST|absorb TokenBond, 10 hand-made nanoERG pools=${s.getCost}|successor bytes=${outBytes(s, 0)}")
      // Then the genuine pool's SigUSD becomes the one coupon entry.
      val s2 = ok(executor(ctx), absorbTx(w, v1, Seq(poolIn(w, h - 40, amount = 30L)), h + 1))
      spendOutput(s2, 0).tokens shouldBe fresh.tokens :+ Token(SigUSD, 30L)
    }
  }

  property("absorb: shares burned beside it select the claims, which refuse while the unit is held") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = w.start + 300
      val m = minted(w, w.start, w.start + 1)
      val p = poolIn(w, h - 40)
      no(lender(ctx), absorbTx(w, m.vault, Seq(p), h, by = lender(ctx), extraIns = Seq(m.held), burn = Seq(Token(m.shareId, 1L))))
      ok(lender(ctx), absorbTx(w, m.vault, Seq(p), h, by = lender(ctx), extraIns = Seq(m.held)))
    }
  }

  property("absorb: beside another vault's claim, the pool counts that vault too, so the caller would have to pay it in") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = ctx.getHeight + 100
      val vx = holdingVault(ctx, h - 50)
      val p = poolIn(w, h - 40, amount = 40L)
      // Vault Y: another bond's shares, its holder taking the unit back with every share.
      val yShare = ErgoId.create("5b" * 32)
      val vy = holdingVault(ctx, h - 50, asset = otherBondAsset, share = yShare, index = 32).withCtxVar(1.toByte, int(1))
      val sy = wallet(ctx, lender(ctx), 11, h, Seq(Token(yShare, N)))
      val yOut = tagged(lenderTree(ctx), vy.value, vy.id, Seq(Token(otherBondAsset, 1L)))
      def tx(next: UTXO): Tx = txAt(ctx, h, Seq(vx.withCtxVar(0.toByte, int(0)), p, vy, sy, wallet(ctx, lender(ctx), 8, h)),
        Seq(next, yOut), lender(ctx).getAddress, burn = Seq(Token(yShare, 2L * N + 1L)))
      no(lender(ctx), tx(absorbed(vx, p)))
      // Paying vault Y's value into X's successor too meets the pool's rule: it was the reason.
      ok(lender(ctx), tx(absorbed(vx, p).addValue(vy.value)))
    }
  }

  property("absorb: two vaults absorbing their own pools in one transaction are refused; each alone signs") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = ctx.getHeight + 100
      val vx = holdingVault(ctx, h - 50)
      val vy = holdingVault(ctx, h - 50, asset = otherBondAsset, share = ErgoId.create("5b" * 32), index = 32)
      val px = poolIn(w, h - 40, index = 1)
      val py = poolIn(w, h - 40, index = 2, asset = otherBondAsset)
      no(executor(ctx), txAt(ctx, h, Seq(vx.withCtxVar(0.toByte, int(0)), vy.withCtxVar(0.toByte, int(1)), px, py,
        wallet(ctx, executor(ctx), 7, h)), Seq(absorbed(vx, px), absorbed(vy, py)), executor(ctx).getAddress))
      ok(executor(ctx), absorbTx(w, vx, Seq(px), h))
      ok(executor(ctx), absorbTx(w, vy, Seq(py), h))
    }
  }

  property("absorb: once pulled the vault holds no unit, so neither it nor the pool allows an absorb") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = ctx.getHeight + 100
      val pulledV = vaultBox(ctx, 5L * minBox, Seq(Token(handShare, N + 1L), Token(SigUSD, 860L)), h - 50, path = Some(0.toByte))
      val p = poolIn(w, h - 40)
      no(executor(ctx), absorbTx(w, pulledV, Seq(p), h,
        next = UTXO(fv(ctx), pulledV.value + p.value, absorbedTokens(pulledV.tokens, p.tokens), pulledV.registers)))
      ok(executor(ctx), absorbTx(w, holdingVault(ctx, h - 50), Seq(p), h))
    }
  }

  // ═══ PULL ═════════════════════════════════════════════════════════════════

  property("pull: with coupons held, the repayment token merges into the coupon entry; any other layout is refused") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = ctx.getHeight + 100
      val v = holdingVault(ctx, h - 50, value = 4L * minBox + pullFee, coupons = Seq(Token(SigUSD, 60L)))
      val src = settledIn(ctx, h - PullWait, Seq(Token(SigUSD, 800L)), 6000000L, 0.toByte)
      val e = pulledBox(v, src)
      e.tokens shouldBe Seq(Token(handShare, N + 1L), Token(SigUSD, 860L))
      ok(executor(ctx), pullTx(w, v, src, h))
      Seq(
        "SigUSD - 1" -> e.setTokens(Token(handShare, N + 1L), Token(SigUSD, 859L)),
        "value - 1" -> e.subValue(1L),
        "R6 wrong" -> e.withRegNum(6, ErgoValue.of(1.toByte)),
        "R5 changed" -> e.withRegNum(5, longsValue(N, N - 1L, pullFee))
      ).foreach { case (name, bad) => withClue(name)(no(executor(ctx), pullTx(w, v, src, h, out = bad))) }
      // Pull fee kept by the puller, no more.
      ok(executor(ctx), pullTx(w, v, src, h, out = pulledBox(v, src, keep = pullFee)))
      no(executor(ctx), pullTx(w, v, src, h, out = pulledBox(v, src, keep = pullFee + 1L)))
    }
  }

  property("pull: the coupon entry first, then the proceeds' other tokens in order; the ERG vault keeps the proceeds' order") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = ctx.getHeight + 100
      val r = Token(handShare, N + 1L)
      val coll = Token(collateralToken, 100L)
      val v = holdingVault(ctx, h - 50, value = 4L * minBox + pullFee, coupons = Seq(Token(SigUSD, 40L)))
      val src = settledIn(ctx, h - PullWait, Seq(coll, Token(SigUSD, 50L)), 6000000L, 1.toByte)
      val e = pulledBox(v, src)
      e.tokens shouldBe Seq(r, Token(SigUSD, 90L), coll)
      ok(executor(ctx), pullTx(w, v, src, h))
      Seq(
        "proceeds first" -> e.setTokens(r, coll, Token(SigUSD, 90L)),
        "two SigUSD entries" -> e.setTokens(r, Token(SigUSD, 40L), coll, Token(SigUSD, 50L)),
        "SigUSD coupon dropped" -> e.setTokens(r, Token(SigUSD, 50L), coll),
        "collateral dropped" -> e.setTokens(r, Token(SigUSD, 90L))
      ).foreach { case (name, bad) => withClue(name)(no(executor(ctx), pullTx(w, v, src, h, out = bad))) }
      // No coupon absorbed: the proceeds' SigUSD still comes first.
      val v0 = holdingVault(ctx, h - 50, value = 4L * minBox + pullFee)
      pulledBox(v0, src).tokens shouldBe Seq(r, Token(SigUSD, 50L), coll)
      ok(executor(ctx), pullTx(w, v0, src, h))
      no(executor(ctx), pullTx(w, v0, src, h, out = pulledBox(v0, src).setTokens(r, coll, Token(SigUSD, 50L))))
      // The ERG vault merges nothing: the proceeds keep their order, SigUSD collateral included.
      val ew = world(ctx, ErgBond)
      val ev = holdingVault(ctx, h - 50, value = 4L * minBox + pullFee, c = fvOf(ew))
      pulledBox(ev, src).tokens shouldBe Seq(r, coll, Token(SigUSD, 50L))
      ok(executor(ctx), pullTx(ew, ev, src, h))
      no(executor(ctx), pullTx(ew, ev, src, h, out = pulledBox(ev, src).setTokens(r, Token(SigUSD, 50L), coll)))
    }
  }

  for (kind <- kinds) {
    property(s"pull ($kind): takes one pool or two beside the settlement, merged as an absorb would; the puller keeps the pull fee and the cut") {
      withCtx { ctx =>
        val w = world(ctx, kind)
        val m = minted(w, w.start, w.start + 1)
        val pay0 = ok(borrower(ctx), payTx(w, fiIn(w, fiBox(w)), w.due(0) - 10))
        val pay1 = ok(borrower(ctx), payTx(w, spendOutput(pay0, 0), w.due(1) - 10))
        val (p0, p1) = (spendOutput(pay0, 1), spendOutput(pay1, 1))
        val r = w.due(1) - 5
        val src = spendOutput(ok(borrower(ctx), fiRepayTx(w, spendOutput(pay1, 0), r)), 0)
        val h = r + PullWait
        // One pool.
        val one = ok(executor(ctx), pullTx(w, m.vault, src, h, pools = Seq(p0)))
        val pv = spendOutput(one, 0)
        pv.value shouldBe m.vault.value + src.value + p0.value - pullFee - ergCut(Seq(p0))
        pv.tokens shouldBe pulledTokens(m.vault, src, Seq(p0))
        pv.registers(2) shouldBe ErgoValue.of(0.toByte)
        if (w.erg) pv.tokens shouldBe Seq(Token(m.shareId, N + 1L))
        else pv.tokens shouldBe Seq(Token(m.shareId, N + 1L), Token(SigUSD, w.coupon - cut(w.coupon) + w.repayment))
        println(s"FICOST|pull $kind repaid, with 1 pool=${one.getCost}|successor bytes=${outBytes(one, 0)}")
        // Two pools.
        val two = ok(executor(ctx), pullTx(w, m.vault, src, h, pools = Seq(p0, p1)))
        val pv2 = spendOutput(two, 0)
        pv2.value shouldBe m.vault.value + src.value + p0.value + p1.value - pullFee - ergCut(Seq(p0, p1))
        if (!w.erg) pv2.tokens shouldBe Seq(Token(m.shareId, N + 1L), Token(SigUSD, 2L * w.coupon - cut(2L * w.coupon) + w.repayment))
        println(s"FICOST|pull $kind repaid, with 2 pools=${two.getCost}")
        // Only one of the two counted: refused (the other pool's rule and the vault's sum).
        no(executor(ctx), pullTx(w, m.vault, src, h, pools = Seq(p0, p1), out = pulledBox(m.vault, src, pools = Seq(p0))))
        // A nanoERG or a token unit past the pull fee and the cut: refused.
        val full = pulledBox(m.vault, src, pools = Seq(p0, p1))
        no(executor(ctx), pullTx(w, m.vault, src, h, pools = Seq(p0, p1), out = full.subValue(1L)))
        if (!w.erg) no(executor(ctx), pullTx(w, m.vault, src, h, pools = Seq(p0, p1),
          out = full.setTokens(full.tokens.head, Token(SigUSD, full.tokens(1).amount - 1L))))
        // The claims run on the pulled box as on any other.
        ok(lender(ctx), finalTx(w, pv2, m.held, lender(ctx), h + 1))
      }
    }

    property(s"pull ($kind): taking a pool in never pays the puller less than leaving it out; the cut comes from the pool alone") {
      withCtx { ctx =>
        val w = world(ctx, kind)
        val h = ctx.getHeight + 100 + PullWait
        val v = holdingVault(ctx, h - 50, c = fvOf(w), coupons = if (w.erg) Seq.empty else Seq(Token(SigUSD, 600L)))
        val src = settledIn(ctx, h - PullWait, if (w.erg) Seq.empty else Seq(Token(SigUSD, 800L)), 4000000000L, 0.toByte)
        // A pool large enough that the cut is not 0 in either asset: 3 ERG, or 1 ERG and 1000 raw SigUSD.
        val p = poolIn(w, h - 40, amount = if (w.erg) 3000000000L else 1000L, erg = Parameters.OneErg)
        val bare = ok(executor(ctx), pullTx(w, v, src, h))
        val withPool = ok(executor(ctx), pullTx(w, v, src, h, pools = Seq(p)))
        // What the puller takes: everything in, less the successor (the miner fee is the puller's either way).
        def taken(s: SignedTransaction, ins: Seq[InputUTXO]): Long = ins.map(_.value).sum - s.getOutputsToSpend.get(0).getValue
        val noPool = taken(bare, Seq(v, src))
        val pooled = taken(withPool, Seq(v, src, p))
        noPool shouldBe pullFee
        pooled shouldBe pullFee + cut(p.value)
        pooled should be > noPool
        if (!w.erg) {
          // The token cut: the successor holds all of the vault's and settlement's SigUSD and the pool's less 1/200.
          usd(spendOutput(withPool, 0).tokens) shouldBe 600L + 800L + 1000L - cut(1000L)
          cut(1000L) shouldBe 5L
        }
        println(s"FIREWARD|pull $kind: without pool keeps $noPool|with a pool of ${p.value} nanoERG keeps $pooled")
        // Neither the vault's own nor the settlement's value is cut: one nanoERG past the pull fee and the pool's cut is refused.
        no(executor(ctx), pullTx(w, v, src, h, pools = Seq(p), out = pulledBox(v, src, pools = Seq(p)).subValue(1L)))
        no(executor(ctx), pullTx(w, v, src, h, out = pulledBox(v, src).subValue(1L)))
      }
    }
  }

  property("pull: a pool of another bond beside the settlement is refused by the vault's R4 check, even where that bond's unit holder opens it") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = w.due(0)
      val src = settledIn(ctx, h - PullWait, Seq(Token(SigUSD, 800L)), 6000000L, 0.toByte)
      val px = poolIn(w, h - 40, index = 1)
      val py = poolIn(w, h - 40, index = 2, asset = otherBondAsset)
      // The other bond's unit, in the lender's key box, opens its pool; it leaves in the lender's change.
      def tx(v: InputUTXO, pools: Seq[InputUTXO]): Tx = pullTx(w, v, src, h, pools = pools, by = lender(ctx),
        extraIns = Seq(unit(ctx, h - 40, asset = otherBondAsset)))
      no(lender(ctx), tx(holdingVault(ctx, h - 50), Seq(px, py)))
      ok(lender(ctx), tx(holdingVault(ctx, h - 50, c = noAssetCheck(ctx)), Seq(px, py)))
      ok(lender(ctx), tx(holdingVault(ctx, h - 50), Seq(px)))
    }
  }

  for (kind <- kinds) {
    property(s"pull ($kind): the stranding race; a coupon paid into a fresh pool, the bond repaid at once; the pull waits 720 blocks") {
      withCtx { ctx =>
        val w = world(ctx, kind)
        val m = minted(w, w.start, w.start + 1)
        val pay0 = ok(borrower(ctx), payTx(w, fiIn(w, fiBox(w)), w.due(0) - 10))
        val v1 = spendOutput(ok(executor(ctx), absorbTx(w, m.vault, Seq(spendOutput(pay0, 1)), w.due(0) - 9)), 0)
        // The borrower pays coupon 1 into a fresh pool and repays, at one height.
        val h = w.due(1) - 10
        val pay1 = ok(borrower(ctx), payTx(w, spendOutput(pay0, 0), h))
        val p1 = spendOutput(pay1, 1)
        val src = spendOutput(ok(borrower(ctx), fiRepayTx(w, spendOutput(pay1, 0), h)), 0)
        // No pull, with or without the pool, until 720 blocks after the settlement's creation height.
        Seq(h, h + PullWait - 1).foreach { t =>
          withClue(s"height $t: ") {
            no(borrower(ctx), pullTx(w, v1, src, t, by = borrower(ctx)))
            no(other(ctx), pullTx(w, v1, src, t, pools = Seq(p1), by = other(ctx)))
          }
        }
        // Meanwhile anyone may absorb the pool, and is paid the cut for it.
        val v2 = spendOutput(ok(executor(ctx), absorbTx(w, v1, Seq(p1), h)), 0)
        v2.value shouldBe v1.value + p1.value - ergCut(Seq(p1))
        val at = h + PullWait
        // RESIDUAL: once the wait is over, the pull without the pool still signs; nothing in it shows the pool
        // exists. The pool then has no collector: the borrower (the puller here), a stranger and the lender (who
        // deposited the unit and holds the shares) are each refused by the pool alone, then and long after
        // maturity; only a refresh spends it.
        val bare = spendOutput(ok(borrower(ctx), pullTx(w, v1, src, at, by = borrower(ctx))), 0)
        (Seq(at + 1) ++ LongAfter.map(w.maturity.toInt + _)).foreach { t =>
          withClue(s"height $t: ") {
            poolRefuses(borrower(ctx), w, p1)(q => takeTx(w, q, t))
            poolRefuses(other(ctx), w, p1)(q => takeTx(w, q, t, by = other(ctx)))
            poolRefuses(lender(ctx), w, p1)(q => takeTx(w, q, t, by = lender(ctx)))
          }
        }
        ok(executor(ctx), renewTx(w, p1, h + RefreshAge + 1))
        if (!w.erg) usd(bare.tokens) shouldBe w.coupon + w.repayment
        // Anyone pulling may take the pool in: the coupon reaches the share holders, less the cut.
        val taken = spendOutput(ok(other(ctx), pullTx(w, v1, src, at, pools = Seq(p1), by = other(ctx))), 0)
        taken.value shouldBe v1.value + src.value + p1.value - pullFee - ergCut(Seq(p1))
        if (!w.erg) usd(taken.tokens) shouldBe 2L * w.coupon - cut(w.coupon) + w.repayment
        // The pool that collects at a contract home only while the unit survives, beside the vault pinning it,
        // refuses the same pull and signs it without the pool.
        val vb = inputAt(v1.toUTXO.setContract(onBurnShut(ctx, w.erg)).setCreationHeight(w.due(0) - 9), ctx, 30)
        val pb = inputAt(p1.toUTXO.setContract(burnShut(ctx)).setCreationHeight(h), ctx, 3, "ab" * 32)
        no(other(ctx), pullTx(w, vb, src, at, pools = Seq(pb), by = other(ctx)))
        ok(other(ctx), pullTx(w, vb, src, at, by = other(ctx)))
        // Absorbed before the pull, the coupon reaches the holders on either rule.
        val pv = spendOutput(ok(executor(ctx), pullTx(w, v2, src, at)), 0)
        pv.value shouldBe v2.value + src.value - pullFee
        if (!w.erg) usd(pv.tokens) shouldBe 2L * w.coupon - cut(w.coupon) + w.repayment
      }
    }
  }

  // ═══ CLAIMS BEFORE THE PULL  // ═══ CLAIMS BEFORE THE PULL ═══════════════════════════════════════════════

  for (kind <- kinds) {
    property(s"final claim before the pull ($kind): every share takes back the unit and every absorbed coupon") {
      withCtx { ctx =>
        val w = world(ctx, kind)
        val m = minted(w, w.start, w.start + 1)
        val pay0 = ok(borrower(ctx), payTx(w, fiIn(w, fiBox(w)), w.due(0) - 10))
        val v1 = spendOutput(ok(executor(ctx), absorbTx(w, m.vault, Seq(spendOutput(pay0, 1)), w.due(0) - 9)), 0)
        val h = w.due(0) - 5
        val s = ok(lender(ctx), finalTx(w, v1, m.held, lender(ctx), h))
        val out = s.getOutputsToSpend.get(0)
        out.getValue shouldBe v1.value
        out.getTokens.asScala.map(t => Token(t.getId, t.getValue)) shouldBe v1.tokens.drop(1)
        if (!w.erg) usd(v1.tokens) shouldBe w.coupon
        // The coupon left out of the payout, or a nanoERG short: refused.
        if (!w.erg) no(lender(ctx), finalTx(w, v1, m.held, lender(ctx), h,
          payout = tagged(lenderTree(ctx), v1.value, v1.id, Seq(Token(bondAsset, 1L)))))
        no(lender(ctx), finalTx(w, v1, m.held, lender(ctx), h,
          payout = tagged(lenderTree(ctx), v1.value - 1L, v1.id, v1.tokens.drop(1))))
        // No partial claim while the unit is held, coupons or not.
        no(lender(ctx), partialTx(w, v1, m.held, 250L, lender(ctx), h))
        // The unit is key-held again: its holder collects the next coupon and claims the settlement.
        val unitBox = spendOutput(s, 0)
        val pay1 = ok(borrower(ctx), payTx(w, spendOutput(pay0, 0), w.due(1) - 10))
        ok(lender(ctx), collectTx(w, Seq(spendOutput(pay1, 1)), Seq(unitBox), w.due(1) - 9))
      }
    }
  }

  // ═══ REFRESH ══════════════════════════════════════════════════════════════

  private def refreshTxOf(w: W, v: InputUTXO, h: Int, next: UTXO, extraIns: Seq[InputUTXO] = Seq.empty): Tx =
    txAt(w.ctx, h, (v.withCtxVar(9.toByte, int(0)) +: extraIns) :+ wallet(w.ctx, executor(w.ctx), 7, h - 2000),
      Seq(next), executor(w.ctx).getAddress)

  property("refresh: a vault holding the unit and coupons, or pulled with merged proceeds, renews as before") {
    withCtx { ctx =>
      val w = world(ctx)
      val c = ctx.getHeight
      val h = c + RefreshAge + 1
      val v = holdingVault(ctx, c, value = 200000000L, coupons = Seq(Token(SigUSD, 40L)))
      val next = v.toUTXO.subValue(Bounty).setCreationHeight(h - 1)
      ok(executor(ctx), refreshTxOf(w, v, h, next))
      no(executor(ctx), refreshTxOf(w, v, h, next.subValue(1L)))
      no(executor(ctx), refreshTxOf(w, v, h, next.setTokens(next.tokens.init :+ Token(SigUSD, 39L): _*)))
      no(executor(ctx), refreshTxOf(w, v, h - 1, next.setCreationHeight(h - 2)))
      // The floor is the box minimum plus the pull fee while the unit is held.
      val thin = holdingVault(ctx, c, value = minBox + pullFee + 4000000L, coupons = Seq(Token(SigUSD, 40L)))
      ok(executor(ctx), refreshTxOf(w, thin, h, thin.toUTXO.setValue(minBox + pullFee).setCreationHeight(h)))
      no(executor(ctx), refreshTxOf(w, thin, h, thin.toUTXO.setValue(minBox + pullFee - 1L).setCreationHeight(h)))
      val pulledV = vaultBox(ctx, 50000000L, Seq(Token(handShare, N + 1L), Token(SigUSD, 860L)), c, outstanding = 700L,
        path = Some(0.toByte))
      ok(executor(ctx), refreshTxOf(w, pulledV, h, pulledV.toUTXO.subValue(Bounty).setCreationHeight(h)))
      no(executor(ctx), refreshTxOf(w, pulledV, h, pulledV.toUTXO.subValue(Bounty).withRegNum(6, ErgoValue.of(1.toByte)).setCreationHeight(h)))
    }
  }

  property("refresh: a token pool beside it is refused either way; an ERG pool rides along only into the successor, the refresher keeping at most its cut") {
    withCtx { ctx =>
      val tw = world(ctx)
      val c = ctx.getHeight
      val h = c + RefreshAge + 1
      val v = holdingVault(ctx, c, value = 200000000L, coupons = Seq(Token(SigUSD, 40L)))
      val tp = poolIn(tw, h - 40)
      val next = v.toUTXO.subValue(Bounty).setCreationHeight(h)
      // The pool needs its SigUSD in the successor; the refresh needs the token list unchanged.
      no(executor(ctx), refreshTxOf(tw, v, h, next, extraIns = Seq(tp)))
      no(executor(ctx), refreshTxOf(tw, v, h, next.addValue(Bounty + tp.value).setTokens(absorbedTokens(v.tokens, tp.tokens): _*),
        extraIns = Seq(tp)))
      ok(executor(ctx), refreshTxOf(tw, v, h, next))
      val ew = world(ctx, ErgBond)
      val ev = holdingVault(ctx, c, value = 200000000L)
      val ep = poolIn(ew, h - 40)
      no(executor(ctx), refreshTxOf(ew, ev, h, ev.toUTXO.subValue(Bounty).setCreationHeight(h), extraIns = Seq(ep)))
      // The refresher keeps at most the cut of the pool: exactly that signs, one nanoERG more is refused.
      ok(executor(ctx), refreshTxOf(ew, ev, h, ev.toUTXO.addValue(ep.value - cut(ep.value)).setCreationHeight(h), extraIns = Seq(ep)))
      no(executor(ctx), refreshTxOf(ew, ev, h, ev.toUTXO.addValue(ep.value - cut(ep.value) - 1L).setCreationHeight(h), extraIns = Seq(ep)))
    }
  }

  // ═══ MINT AND SHARE VERIFICATION ══════════════════════════════════════════

  property("mint: the FI mint bound to the FI vault creates only an FI vault; the ZC-bound mint only a ZC vault") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = ctx.getHeight + 20
      val req = requestIn(w, h - 10)
      val s = ok(executor(ctx), mintTx(w, req, h))
      // The share's state box: the one box holding N+1 of the share id; its R4 names the bond asset.
      val state = spendOutput(s, 1)
      state.contract shouldBe fv(ctx)
      state.tokens.head shouldBe Token(req.id, N + 1L)
      state.registers.head shouldBe bytesValue(bondAsset.getBytes)
      no(executor(ctx), mintTx(w, req, h, vault = freshVault(w, req, vault = scripts(ctx).fractionalVault)))
      val zcReq = requestIn(w, h - 10, mint = scripts(ctx).fractionalMint)
      no(executor(ctx), mintTx(w, zcReq, h, vault = freshVault(w, zcReq)))
      ok(executor(ctx), mintTx(w, zcReq, h, vault = freshVault(w, zcReq, vault = scripts(ctx).fractionalVault)))
      shares(ctx).mint.ergoTree should not be scripts(ctx).fractionalMint.ergoTree
    }
  }

  property("mint: FI_Standard_FractionalMint asks a pull fee of at least 0.1 ERG; ZC's mint bound to the FI vault, a different script, still mints one the FI vault runs") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = ctx.getHeight + 20
      val min = 100000000L
      pullFee shouldBe min
      ok(executor(ctx), mintTx(w, requestIn(w, h - 10, fee = min), h))
      // One nanoERG under the minimum: refused by the FI mint, and accepted by the ZC mint compiled from its own
      // source with the FI vault's hash (ZC's minimum is 0.002 ERG). The two mints are different scripts.
      val low = requestIn(w, h - 10, fee = min - 1L)
      no(executor(ctx), mintTx(w, low, h, vault = freshVault(w, low, fee = min - 1L)))
      val zcOnFi = FiStandardSharesSpecBase.zcMintFor(ctx, fv(ctx))
      zcOnFi.ergoTree should not be shares(ctx).mint.ergoTree
      zcOnFi.ergoTree should not be scripts(ctx).fractionalMint.ergoTree
      val zlow = requestIn(w, h - 10, mint = zcOnFi, fee = 2000000L)
      val made = ok(executor(ctx), mintTx(w, zlow, h, vault = freshVault(w, zlow, fee = 2000000L)))
      // The FI vault minted with the smaller fee is not locked: a pool is absorbed into it, and the pull keeps
      // that fee and the cut.
      val v = spendOutput(made, 1)
      val p = poolIn(w, h - 5)
      val a = spendOutput(ok(executor(ctx), absorbTx(w, v, Seq(p), h + 1)), 0)
      val at = h + 2 + PullWait
      val src = settledIn(ctx, h + 2, Seq(Token(SigUSD, 800L)), 6000000L, 0.toByte)
      ok(executor(ctx), pullTx(w, a, src, at, out = pulledBox(a, src, keep = 2000000L)))
      no(executor(ctx), pullTx(w, a, src, at, out = pulledBox(a, src, keep = 2000001L)))
      println(s"FISIZE|FI mint tree=${shares(ctx).mint.ergoTree.bytes.length}|ZC mint on FI vault tree=${zcOnFi.ergoTree.bytes.length}")
    }
  }

  // ═══ JUNK TOKENS IN GENUINE POOLS (CLOSED) ════════════════════════════════

  private val tooMany = 75

  property("closed: an ERG bond's Pay cannot load its pool with junk ids; a junk-loaded pool cannot be absorbed, nor taken by the borrower") {
    withCtx { ctx =>
      val w = world(ctx, ErgBond)
      val m = minted(w, w.start, w.start + 1)
      val h = w.due(0) - 10
      val j = junk(tooMany)
      val loaded = couponOut(w).setTokens(j: _*)
      no(borrower(ctx), payTx(w, fiIn(w, fiBox(w)), h, coupon = loaded, extraIns = Seq(wallet(ctx, borrower(ctx), 12, h, j))))
      // A pool loaded so anyway (made by hand): refused by the vault whole or without the junk.
      val p = inputAt(loaded.setCreationHeight(h), ctx, 1, "ab" * 32)
      no(executor(ctx), absorbTx(w, m.vault, Seq(p), h + 1))
      no(executor(ctx), absorbTx(w, m.vault, Seq(p), h + 1, next = absorbed(m.vault, p).setTokens(m.vault.tokens ++ j: _*)))
      LongAfter.foreach(d => poolRefuses(borrower(ctx), w, p)(q => takeTx(w, q, w.maturity.toInt + d)))
      // The genuine Pay's pool is absorbed.
      val pay = ok(borrower(ctx), payTx(w, fiIn(w, fiBox(w)), h))
      ok(executor(ctx), absorbTx(w, m.vault, Seq(spendOutput(pay, 1)), h + 1))
    }
  }

  property("closed: a stranger's Record breach cannot re-create an unabsorbed token pool with junk ids; carried whole, it is absorbed") {
    withCtx { ctx =>
      val w = world(ctx)
      val m = minted(w, w.start, w.start + 1)
      val pay0 = ok(borrower(ctx), payTx(w, fiIn(w, fiBox(w)), w.due(0) - 10))
      val p0 = spendOutput(pay0, 1)
      val h = w.due(0) + 5
      val j = junk(tooMany)
      def rec(out: UTXO): Tx = recordTx(w, spendOutput(pay0, 0), h, 0L, extraIns = Seq(p0, wallet(ctx, executor(ctx), 12, h, j)),
        extraOuts = Seq(out))
      no(executor(ctx), rec(UTXO(fi(ctx).couponVault, p0.value, p0.tokens ++ j, p0.registers)))
      val carried = spendOutput(ok(executor(ctx), rec(UTXO(fi(ctx).couponVault, p0.value, p0.tokens, p0.registers))), 1)
      ok(executor(ctx), absorbTx(w, m.vault, Seq(carried), h + 1))
    }
  }

  // ═══ SIZES AND CAPACITY ═══════════════════════════════════════════════════

  property("size: trees and boxes; the ERG and SigUSD vaults share a template; the largest settlement a pull still fits") {
    withCtx { ctx =>
      val w = world(ctx)
      val sh = shares(ctx)
      println(s"FISIZE|FI FractionalVault SigUSD tree=${sh.vault.ergoTree.bytes.length}|ERG tree=${sh.ergVault.ergoTree.bytes.length}" +
        s"|ZC FractionalVault tree=${scripts(ctx).fractionalVault.ergoTree.bytes.length}" +
        s"|FI mint tree=${sh.mint.ergoTree.bytes.length}|ZC mint tree=${scripts(ctx).fractionalMint.ergoTree.bytes.length}" +
        s"|pool tree=${fi(ctx).couponVault.ergoTree.bytes.length}")
      sh.vault.ergoTree.template shouldBe sh.ergVault.ergoTree.template
      sh.vault.ergoTree.bytes should not be sh.ergVault.ergoTree.bytes
      val h = ctx.getHeight + 100
      val m = minted(w, w.start, w.start + 1)
      // The largest box before the pull: the reserve, the unit and the coupon entry, amounts at their widest.
      val widest = holdingVault(ctx, h - 50, value = Long.MaxValue / 4, coupons = Seq(Token(SigUSD, Long.MaxValue)))
      println(s"FISIZE|fresh vault box=${outBytes(m.tx, 1)}|widest holding vault box=${widest.bytes.length}")
      // A settlement holding k collateral ids at the widest amounts (and SigUSD), into a vault holding coupons:
      // the most k a pull fits, against the most k a bond box with realistic terms holds.
      def ids(k: Int): Seq[Token] = junk(k, amount = Long.MaxValue / 2, salt = 1)
      def pulls(k: Int): Boolean = Try {
        val v = holdingVault(ctx, h - 50, value = 4L * minBox + pullFee, coupons = Seq(Token(SigUSD, 60L)))
        ok(executor(ctx), pullTx(w, v, settledIn(ctx, h - PullWait, ids(k) :+ Token(SigUSD, 800L), 6000000L, 1.toByte), h))
      }.isSuccess
      def bondFits(k: Int): Boolean = fiIn(w, fiBox(w, tokens = Token(bondAsset, 2L) +: (ids(k) :+ Token(SigUSD, 800L)))).bytes.length <= MaxBox
      val maxPull = (0 to 120).takeWhile(pulls).lastOption.getOrElse(-1)
      val maxBond = (0 to 120).takeWhile(bondFits).lastOption.getOrElse(-1)
      println(s"FISIZE|pull fits a settlement of at most $maxPull collateral ids beside SigUSD|a bond box holds at most $maxBond")
      maxPull should be >= maxBond
      val v = holdingVault(ctx, h - 50, value = 4L * minBox + pullFee, coupons = Seq(Token(SigUSD, 60L)))
      val pull = ok(executor(ctx), pullTx(w, v, settledIn(ctx, h - PullWait, Seq(Token(SigUSD, 800L)), 6000000L, 0.toByte), h))
      println(s"FISIZE|pulled box (repaid token bond, coupons merged)=${outBytes(pull, 0)}")
    }
  }


  // ═══ AUDIT FIXES ══════════════════════════════════════════════════════════

  /** The FI vault (SigUSD) compiled with `edits`: a mutant, to show which clause refuses. */
  private def vaultMutant(ctx: BlockchainContext, edits: (String, String)*): Contract =
    once(s"vault|${edits.mkString}")(FiStandardSharesSpecBase.vaultFor(ctx, fi(ctx).couponVault, usdBytes, edits: _*))
  private val noSuccessorStamp = "next.creationInfo._1 > HEIGHT - 1000 &&" -> "true &&"
  private val noSuccessorR7 = "next.R7[Coll[Byte]].isDefined == false &&" -> "true &&"

  /** The FI mint (SigUSD) compiled with `edits`, bound to the real FI vault. */
  private def mintMutant(ctx: BlockchainContext, edits: (String, String)*): Contract = once(s"mint|${edits.mkString}") {
    FiStandardSpecBase.synchronized {
      Contract.fromErgoContract(ctx.compileContract(ConstantsBuilder.create()
        .item("CONST_FRACTIONAL_VAULT_HASH", sigma.Colls.fromArray(fv(ctx).hashedPropBytes)).build(),
        FiStandardSpecBase.edited(ksingh.sigmabonds.client.ScriptGenerator.mkScript(FiStandardSharesSpecBase.MintPath), edits)))
    }
  }

  property("F-008 absorb: the successor is stamped within 999 blocks of HEIGHT and has no R7") {
    withCtx { ctx =>
      val w = world(ctx)
      val c = ctx.getHeight
      val h = c + 1010
      def run(next: UTXO => UTXO, c0: Contract = null): Tx = {
        val v = holdingVault(ctx, c, c = c0)
        val p = poolIn(w, c)
        absorbTx(w, v, Seq(p), h, next = next(absorbedAll(v, Seq(p))), walletCreated = c)
      }
      ok(executor(ctx), run(_.setCreationHeight(h - 999)))
      no(executor(ctx), run(_.setCreationHeight(h - 1000)))
      ok(executor(ctx), run(_.setCreationHeight(h - 1000), vaultMutant(ctx, noSuccessorStamp)))
    }
  }

  property("F-008 pull: the successor is stamped within 999 blocks of HEIGHT and carries no R7 padding") {
    withCtx { ctx =>
      val w = world(ctx)
      val c = ctx.getHeight
      val h = c + 1010
      def run(next: UTXO => UTXO, c0: Contract = null): Tx = {
        val v = holdingVault(ctx, c, c = c0)
        val src = settledIn(ctx, c, Seq(Token(SigUSD, 800L)), 6000000L, 0.toByte)
        pullTx(w, v, src, h, out = next(pulledBox(v, src)))
      }
      ok(executor(ctx), run(_.setCreationHeight(h - 999)))
      no(executor(ctx), run(_.setCreationHeight(h - 1000)))
      ok(executor(ctx), run(_.setCreationHeight(h - 1000), vaultMutant(ctx, noSuccessorStamp)))
      val padded: UTXO => UTXO = u => u.setRegs(u.registers :+ bytesValue(Array.fill(1000)(7.toByte)): _*)
      no(executor(ctx), run(padded))
      ok(executor(ctx), run(padded, vaultMutant(ctx, noSuccessorR7)))
    }
  }

  property("F-008 partial claim: the continuation is stamped within 999 blocks of HEIGHT and carries no R7 padding") {
    withCtx { ctx =>
      val w = world(ctx)
      val c = ctx.getHeight
      val h = c + 1010
      def run(next: UTXO => UTXO, c0: Contract = null): Tx = {
        val pv = vaultBox(ctx, 50000000L, Seq(Token(handShare, N + 1L), Token(SigUSD, 860L)), c, path = Some(0.toByte), c = c0)
        val held = wallet(ctx, other(ctx), 8, c, Seq(Token(handShare, 300L)))
        // The continuation partialTx builds: the vault less the pro-rata part of 300 of N shares.
        val kept = pv.tokens.drop(1).map(t => Token(t.id, t.amount - part(t.amount, 300L, N))).filter(_.amount > 0L)
        val cont = UTXO(pv.contract, pv.value - part(pv.value, 300L, N), pv.tokens.head +: kept,
          Seq(pv.registers.head, longsValue(N, N - 300L, pullFee)) ++ pv.registers.drop(2))
        partialTx(w, pv, held, 300L, other(ctx), h, next = next(cont), walletCreated = c)
      }
      ok(other(ctx), run(_.setCreationHeight(h - 999)))
      no(other(ctx), run(_.setCreationHeight(h - 1000)))
      ok(other(ctx), run(_.setCreationHeight(h - 1000), vaultMutant(ctx, noSuccessorStamp)))
      val padded: UTXO => UTXO = u => u.setRegs(u.registers :+ bytesValue(Array.fill(1000)(7.toByte)): _*)
      no(other(ctx), run(padded))
      ok(other(ctx), run(padded, vaultMutant(ctx, noSuccessorR7)))
    }
  }

  property("F-016 refresh: a refresh that burns shares is refused; without the burn it renews") {
    withCtx { ctx =>
      val w = world(ctx)
      val c = ctx.getHeight
      val h = c + RefreshAge + 1
      def run(c0: Contract = null, burn: Long = 1L): Tx = {
        val pv = vaultBox(ctx, 50000000L, Seq(Token(handShare, N + 1L), Token(SigUSD, 860L)), c, path = Some(0.toByte), c = c0)
        val held = wallet(ctx, executor(ctx), 8, c, Seq(Token(handShare, 300L)))
        txAt(ctx, h, Seq(pv.withCtxVar(9.toByte, int(0)), held, wallet(ctx, executor(ctx), 7, h - 2000)),
          Seq(pv.toUTXO.subValue(Bounty).setCreationHeight(h)), executor(ctx).getAddress,
          burn = if (burn > 0L) Seq(Token(handShare, burn)) else Seq.empty)
      }
      ok(executor(ctx), run(burn = 0L))
      no(executor(ctx), run())
      ok(executor(ctx), run(vaultMutant(ctx, "burned == 0L &&\n                    SELF.creationInfo._1 < HEIGHT - 525600" ->
        "SELF.creationInfo._1 < HEIGHT - 525600")))
    }
  }

  property("F-017 mint: the vault and the share box are stamped within 1000 blocks of HEIGHT") {
    withCtx { ctx =>
      val w = world(ctx)
      val c = ctx.getHeight
      val h = c + 1010
      def run(share: Int, vault: Int, mint: Contract = null): Tx = {
        val req = requestIn(w, c, mint = mint)
        txAt(ctx, h, Seq(req), Seq(shareBox(w, req.id).setCreationHeight(share), freshVault(w, req).setCreationHeight(vault)),
          executor(ctx).getAddress)
      }
      ok(executor(ctx), run(h - 1000, h - 1000))
      no(executor(ctx), run(h - 1001, h))
      no(executor(ctx), run(h, h - 1001))
      ok(executor(ctx), run(h - 1001, h - 1001, mintMutant(ctx,
        " &&\n                shareOut.creationInfo._1 >= HEIGHT - 1000L" -> "",
        " &&\n                vault.creationInfo._1 >= HEIGHT - 1000L" -> "")))
    }
  }

  property("F-025 mint: the vault holds exactly the pull fee plus 1000000; every spare nanoERG goes to the share box") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = ctx.getHeight + 20
      def run(toVault: Long, toShares: Long, mint: Contract = null): Tx = {
        val req = requestIn(w, h - 10, mint = mint)
        txAt(ctx, h, Seq(req, wallet(ctx, executor(ctx), 7, h - 10)),
          Seq(shareBox(w, req.id).addValue(toShares), freshVault(w, req).addValue(toVault)), executor(ctx).getAddress)
      }
      freshVault(w, requestIn(w, h - 10)).value shouldBe pullFee + 1000000L
      ok(executor(ctx), run(0L, 0L))
      ok(executor(ctx), run(0L, 5000000L))
      no(executor(ctx), run(1L, 0L))
      no(executor(ctx), run(5000000L, 0L))
      ok(executor(ctx), run(5000000L, 0L, mintMutant(ctx, "vault.value - pullFee <= 1000000L &&\n                " -> "")))
    }
  }

  property("F-023 mint cancel: an owner box 1051200 or more blocks old (open to storage rent) releases nothing; one block younger cancels") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = 1200000
      def cancel(boxAge: Int, mint: Contract = null): Tx = {
        val req = requestIn(w, ctx.getHeight, mint = mint)
        txAt(ctx, h, Seq(req, wallet(ctx, lender(ctx), 8, h - boxAge)), Seq(UTXO(lenderTree(ctx), req.value, req.tokens)),
          lender(ctx).getAddress)
      }
      ok(lender(ctx), cancel(1051199))
      no(lender(ctx), cancel(1051200))
      ok(lender(ctx), cancel(1051200, mintMutant(ctx, " &&\n            b.creationInfo._1 > HEIGHT - 1051200" -> "")))
    }
  }
}
