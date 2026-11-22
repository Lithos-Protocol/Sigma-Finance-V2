package org.lithosprotocol.sigmafi
package specs.bonds

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

/**
 * FI_Standard_CouponVault as the coupon pool of one bond asset: Pay creates it or tops it up, any spend of
 * the 2-unit box may only carry it forward whole, and anyone may refresh it once it is old. Its one register
 * is R4, the bond asset; no path takes it for the borrower.
 *
 * A refusal is checked against a mutant that drops the one clause meant to refuse it: the same
 * transaction signs on the mutant, so that clause, and nothing unrelated, refused it.
 * - `owedCoupon`: the bond counts only the coupon, not what the spent pools hold (the bond's Pay sum).
 * - `manyOuts`: the bond without "the only output at the pool script tagged with this asset".
 * - `anyTokens`: the bond without "the coupon output holds no token but the repayment token" and "one entry at most".
 * - `anyRegs`: the bond without "the coupon output has no R5".
 * - `ergLeak` / `tokenLeak` / `leak`: the pool's top-up drops its nanoERG / token / both sums.
 * - `open2`: the pool's top-up without its "inputs hold exactly 2 units" condition.
 * - `sizeFree`: the pool's top-up without its "no more token entries than this box" bound.
 * - `r5Free`: the pool's top-up without its "the output has no R5".
 * - `soloFree`: the pool's refresh without its "only input at this script" condition.
 * - `burnShut`: the pool's contract-home collect only while the unit survives (no collect at a pull).
 * - `unitOnly`: the pool's contract-home collect counting the home's inputs but not the 2-unit box.
 * - `homeFree`: the pool's contract-home collect at a 3-unit burn without "the payout holds the home's tokens(0)".
 * - `FiStandardSpecBase.openPool`: the pool with no rule but refresh (`poolRefuses` moves a pool there).
 * A bond that must run beside a mutant pool is compiled to pin that pool (`FiStandardSpecBase.bondVariant`).
 */
class FiStandardPoolSpec extends AnyPropSpec with FiStandardSpecBase {
  import BondSpecBase._
  import ZcStandardSpecBase._

  private val kinds = Seq[Kind](TokenBond, ErgBond)

  private def at(w: W, paid: Long = 0L, last: Long = -1L, deadline: Long = 0L, stamp: Int = -1): InputUTXO =
    fiIn(w, fiBox(w, paid, last, deadline, stamp = stamp))

  // ─── mutants ──────────────────────────────────────────────────────────────

  private val cache = scala.collection.mutable.Map.empty[String, Contract]
  private def once(key: String)(c: => Contract): Contract = cache.getOrElseUpdate(key, c)

  private val TopUpSums = "carries((p, (pools, 0L)))"
  private val PoolErg = "p.value >= pools.fold(0L, { (a: Long, b: Box) => a + b.value })"
  private val PoolTokens = """SELF.tokens.forall { (t: (Coll[Byte], Long)) =>
      val need: Long = pools.fold(0L, { (a: Long, b: Box) =>
        val k: Long = b.tokens.fold(0L, { (z: Long, v: (Coll[Byte], Long)) => if (v._1 == t._1) z + v._2 else z })
        a + k
      })
      p.tokens.exists { (u: (Coll[Byte], Long)) => u._1 == t._1 && u._2 >= need }
    }"""

  private def ergLeak(ctx: BlockchainContext): Contract =
    once("ergLeak")(FiStandardSpecBase.poolVariant(ctx, TopUpSums -> PoolTokens))
  private def tokenLeak(ctx: BlockchainContext): Contract =
    once("tokenLeak")(FiStandardSpecBase.poolVariant(ctx, TopUpSums -> PoolErg))
  private def leak(ctx: BlockchainContext): Contract =
    once("leak")(FiStandardSpecBase.poolVariant(ctx, TopUpSums -> "true"))
  private def open2(ctx: BlockchainContext): Contract =
    once("open2")(FiStandardSpecBase.poolVariant(ctx, "shown == 2L && OUTPUTS.exists" -> "OUTPUTS.exists"))
  private def sizeFree(ctx: BlockchainContext): Contract =
    once("sizeFree")(FiStandardSpecBase.poolVariant(ctx, "p.tokens.size <= SELF.tokens.size &&" -> "true &&"))
  private def r5Free(ctx: BlockchainContext): Contract =
    once("r5Free")(FiStandardSpecBase.poolVariant(ctx, "p.R5[Coll[Byte]].isDefined == false &&" -> "true &&"))
  private def soloFree(ctx: BlockchainContext): Contract =
    once("soloFree")(FiStandardSpecBase.poolVariant(ctx, " || vaults.size != 1" -> ""))
  private def burnShut(ctx: BlockchainContext): Contract =
    once("burnShut")(FiStandardSpecBase.poolVariant(ctx, "held(p) == min(left, 1L)" -> "left > 0L && held(p) == 1L"))
  private def unitOnly(ctx: BlockchainContext): Contract =
    once("unitOnly")(FiStandardSpecBase.poolVariant(ctx, " || held(b) > 0L" -> ""))
  private def homeFree(ctx: BlockchainContext): Contract =
    once("homeFree")(FiStandardSpecBase.poolVariant(ctx,
      "\n                    (left > 0L || (p.tokens.size > 0 && p.tokens(0) == h.tokens(0))) &&" -> ""))

  /** The bond, for this world's asset, pinning `pool`; `owedCoupon` drops the pools from its Pay sum. */
  private def bondFor(w: W, pool: Contract, owedCoupon: Boolean = false, manyOuts: Boolean = false,
                      anyTokens: Boolean = false, anyRegs: Boolean = false): Contract =
    once(s"bond|${w.erg}|${pool.hashedPropBytesHex}|$owedCoupon|$manyOuts|$anyTokens|$anyRegs") {
      val edits = (if (owedCoupon) Seq("a + inRepayment(b)" -> "a") else Seq.empty) ++
        (if (manyOuts) Seq("OUTPUTS.filter(pool).size == 1" -> "true") else Seq.empty) ++
        (if (anyTokens) Seq("couponBox.tokens.forall { (t: (Coll[Byte], Long)) => t._1 == repaymentToken } &&" -> "true &&",
          "couponBox.tokens.size <= 1 &&" -> "true &&") else Seq.empty) ++
        (if (anyRegs) Seq("couponBox.R5[Coll[Byte]].isDefined == false &&" -> "true &&") else Seq.empty)
      FiStandardSpecBase.bondVariant(w.ctx, repaymentBytes(w), pool, edits: _*)
    }

  private def bondAt(w: W, c: Contract, paid: Long = 0L): InputUTXO = fiIn(w, fiBox(w, paid).setContract(c))

  // ─── boxes ────────────────────────────────────────────────────────────────

  /** A pool output carrying `ins` forward whole, plus `plus` of the repayment asset (and `ergPlus` on a token pool). */
  private def carried(w: W, ins: Seq[InputUTXO], plus: Long = 0L, ergPlus: Long = 0L, c: Contract = null): UTXO = {
    val erg = ins.map(_.value).sum
    val usd = ins.flatMap(_.tokens).filter(_.id == SigUSD).map(_.amount).sum
    val u = if (w.erg) couponOut(w, amount = erg + plus) else couponOut(w, amount = usd + plus, erg = erg + ergPlus)
    if (c == null) u else u.setContract(c)
  }

  private def usd(u: UTXO): Long = u.tokens.filter(_.id == SigUSD).map(_.amount).sum
  private def usd(u: InputUTXO): Long = u.tokens.filter(_.id == SigUSD).map(_.amount).sum

  /** A 2-unit box at an always-true script: only the pool's own rule can refuse a spend beside it. */
  private def bare2(ctx: BlockchainContext, created: Int): InputUTXO =
    inputAt(UTXO(Contract.SIGMA_TRUE, minBox, Seq(Token(bondAsset, 2L)), Seq.empty, Some(created)), ctx, 35)

  /** `u` with an R5 after its R4: the borrower's script hash, as a pool naming the borrower would carry. */
  private def withR5(ctx: BlockchainContext, u: UTXO): UTXO =
    u.setRegs(u.registers.take(1) :+ bytesValue(hashOf(borrowerTree(ctx))): _*)

  // ═══ PAY: CREATE AND TOP UP ═══════════════════════════════════════════════

  for (kind <- kinds) {
    property(s"pool ($kind): Pay with no pool spent creates one: R4 the asset and no other register, the coupon") {
      withCtx { ctx =>
        val w = world(ctx, kind)
        val h = w.due(0) - 10
        val signed = ok(borrower(ctx), payTx(w, at(w), h))
        val pool = spendOutput(signed, 1)
        pool.contract shouldBe fi(ctx).couponVault
        pool.registers shouldBe poolRegs()
        if (w.erg) { pool.value shouldBe w.coupon; pool.tokens shouldBe empty }
        else { pool.value shouldBe minBox; pool.tokens shouldBe Seq(Token(SigUSD, w.coupon)) }
      }
    }

    property(s"pool ($kind): Pay tops up an existing pool; the holder collects both coupons from one box") {
      withCtx { ctx =>
        val w = world(ctx, kind)
        val first = ok(borrower(ctx), payTx(w, at(w), w.due(0) - 10))
        val pool = spendOutput(first, 1)
        val h = w.due(1) - 10
        val second = ok(borrower(ctx), payTx(w, spendOutput(first, 0), h, extraIns = Seq(pool),
          coupon = toppedPool(w, Seq(pool))))
        val topped = spendOutput(second, 1)
        if (w.erg) topped.value shouldBe 2L * w.coupon
        else { usd(topped) shouldBe 2L * w.coupon; topped.value shouldBe minBox }
        topped.registers shouldBe poolRegs()
        // More than owed is fine.
        ok(borrower(ctx), payTx(w, spendOutput(first, 0), h, extraIns = Seq(pool), coupon = toppedPool(w, Seq(pool), extra = 1L)))
        // Spending the pool is optional: the same Pay into a fresh box signs, leaving two pools.
        ok(borrower(ctx), payTx(w, spendOutput(first, 0), h))
        // The holder collects the topped-up pool.
        ok(lender(ctx), collectTx(w, Seq(topped), Seq(unit(ctx, h)), h + 1))
      }
    }

    property(s"pool ($kind): a Pay spending pools must carry all of them under the new coupon") {
      withCtx { ctx =>
        val w = world(ctx, kind)
        val h = w.due(1) - 10
        val p1 = poolIn(w, h - 100, index = 1)
        val p2 = poolIn(w, h - 90, index = 2, amount = 3L * w.coupon, erg = 3L * minBox)
        val b = at(w, paid = 1L)
        ok(borrower(ctx), payTx(w, b, h, extraIns = Seq(p1, p2), coupon = toppedPool(w, Seq(p1, p2))))
        // One short in the repayment asset: the bond's Pay sum refuses (the pool's own rule is met).
        no(borrower(ctx), payTx(w, b, h, extraIns = Seq(p1, p2), coupon = toppedPool(w, Seq(p1, p2), extra = -1L)))
        val coupon = bondFor(w, fi(ctx).couponVault, owedCoupon = true)
        ok(borrower(ctx), payTx(w, bondAt(w, coupon, 1L), h, extraIns = Seq(p1, p2), coupon = toppedPool(w, Seq(p1, p2), extra = -1L)))
        // The pools' own value paid as the coupon: the output holds only what they held.
        no(borrower(ctx), payTx(w, b, h, extraIns = Seq(p1, p2), coupon = carried(w, Seq(p1, p2))))
        ok(borrower(ctx), payTx(w, bondAt(w, coupon, 1L), h, extraIns = Seq(p1, p2), coupon = carried(w, Seq(p1, p2))))
        // Only one of the two pools counted in the repayment asset (a token pool's nanoERG kept whole).
        val oneCounted = toppedPool(w, Seq(p2), ergExtra = if (w.erg) 0L else p1.value)
        no(borrower(ctx), payTx(w, b, h, extraIns = Seq(p1, p2), coupon = oneCounted))
        ok(borrower(ctx), payTx(w, bondAt(w, coupon, 1L), h, extraIns = Seq(p1, p2), coupon = oneCounted))
        // The coupon in a fresh box and the spent pools re-created whole in a second box: the pools are
        // content, but two clauses of the bond refuse it: the box var 5 names must carry them under the
        // coupon (the Pay sum), and it must be the only output at the pool script tagged with this asset.
        // The bond without the Pay sum still refuses it; without both clauses it signs.
        no(borrower(ctx), payTx(w, b, h, extraIns = Seq(p1, p2), extraOuts = Seq(carried(w, Seq(p1, p2)))))
        no(borrower(ctx), payTx(w, bondAt(w, coupon, 1L), h, extraIns = Seq(p1, p2), extraOuts = Seq(carried(w, Seq(p1, p2)))))
        ok(borrower(ctx), payTx(w, bondAt(w, bondFor(w, fi(ctx).couponVault, owedCoupon = true, manyOuts = true), 1L), h,
          extraIns = Seq(p1, p2), extraOuts = Seq(carried(w, Seq(p1, p2)))))
        // The topped-up pool beside a second box tagged with this asset: the bond refuses the second box
        // (a bond without its "only output" rule signs).
        no(borrower(ctx), payTx(w, b, h, extraIns = Seq(p1, p2), coupon = toppedPool(w, Seq(p1, p2)),
          extraOuts = Seq(couponOut(w))))
        ok(borrower(ctx), payTx(w, bondAt(w, bondFor(w, fi(ctx).couponVault, manyOuts = true), 1L), h,
          extraIns = Seq(p1, p2), coupon = toppedPool(w, Seq(p1, p2)), extraOuts = Seq(couponOut(w))))
      }
    }
  }

  property("pool (TokenBond): a Pay that keeps the coupon but takes the pools' nanoERG is refused by the pool") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = w.due(1) - 10
      val p1 = poolIn(w, h - 100, erg = 5L * minBox)
      ok(borrower(ctx), payTx(w, at(w, paid = 1L), h, extraIns = Seq(p1), coupon = toppedPool(w, Seq(p1))))
      no(borrower(ctx), payTx(w, at(w, paid = 1L), h, extraIns = Seq(p1), coupon = toppedPool(w, Seq(p1), ergExtra = -1L)))
      // The bond reads only the repayment token: beside the pool without its nanoERG sum the same Pay signs.
      val m = ergLeak(ctx)
      val mp = poolIn(w, h - 100, erg = 5L * minBox, pool = m)
      ok(borrower(ctx), payTx(w, bondAt(w, bondFor(w, m), 1L), h, extraIns = Seq(mp),
        coupon = toppedPool(w, Seq(mp), ergExtra = -1L).setContract(m)))
    }
  }

  // ═══ STRANGERS ════════════════════════════════════════════════════════════

  property("pool: a stranger cannot spend a pool alone, even to re-create it whole; with the 2 units beside it they could") {
    withCtx { ctx =>
      for (kind <- kinds) {
        val w = world(ctx, kind)
        val h = w.due(1)
        val p = poolIn(w, h - 100)
        val fees = wallet(ctx, executor(ctx), 7, h - 100)
        no(executor(ctx), txAt(ctx, h, Seq(p, fees), Seq(carried(w, Seq(p))), executor(ctx).getAddress))
        no(executor(ctx), txAt(ctx, h, Seq(p, fees), Seq(carried(w, Seq(p), plus = -1L)), executor(ctx).getAddress))
        // The same re-creation on a pool without the 2-unit condition signs: that condition refused it.
        val m = open2(ctx)
        val mp = poolIn(w, h - 100, pool = m)
        ok(executor(ctx), txAt(ctx, h, Seq(mp, fees), Seq(carried(w, Seq(mp), c = m)), executor(ctx).getAddress))
      }
    }
  }

  property("pool: beside a stranger's Record breach the pool can only be carried forward whole") {
    withCtx { ctx =>
      for (kind <- kinds) {
        val w = world(ctx, kind)
        val h = w.due(0) + 5
        val p = poolIn(w, h - 100, erg = 4L * minBox)
        def rec(pool: InputUTXO, out: Seq[UTXO]): Tx = recordTx(w, at(w), h, 0L, extraIns = Seq(pool), extraOuts = out)
        ok(executor(ctx), rec(p, Seq(carried(w, Seq(p)))))
        // Not re-created; re-created one short in the repayment asset; one short in nanoERG on a token pool.
        no(executor(ctx), rec(p, Seq.empty))
        no(executor(ctx), rec(p, Seq(carried(w, Seq(p), plus = -1L))))
        if (!w.erg) no(executor(ctx), rec(p, Seq(carried(w, Seq(p), ergPlus = -1L))))
        // Each refusal is the pool's sums: without them the same spends sign.
        val m = leak(ctx)
        val mp = poolIn(w, h - 100, erg = 4L * minBox, pool = m)
        ok(executor(ctx), rec(mp, Seq(carried(w, Seq(mp), plus = -1L, c = m))))
        if (!w.erg) ok(executor(ctx), rec(mp, Seq(carried(w, Seq(mp), ergPlus = -1L, c = m))))
        // Another asset in R4, or an R5 added: the output is not this pool's. Without its "no R5" the pool
        // signs the second, so that rule refused it.
        no(executor(ctx), rec(p, Seq(carried(w, Seq(p)).withRegNum(4, bytesValue(otherBondAsset.getBytes)))))
        no(executor(ctx), rec(p, Seq(withR5(ctx, carried(w, Seq(p))))))
        val r = r5Free(ctx)
        val rp = poolIn(w, h - 100, erg = 4L * minBox, pool = r)
        ok(executor(ctx), rec(rp, Seq(withR5(ctx, carried(w, Seq(rp), c = r)))))
      }
    }
  }

  property("pool: beside a stranger's Refresh of the bond, a liquidation, or any 2-unit box, the pool keeps its value") {
    withCtx { ctx =>
      val w = world(ctx, life = 700000L)
      val rh = w.start + RefreshAge + 1
      val p = poolIn(w, rh - 100)
      ok(executor(ctx), refreshTx(w, at(w), rh, extraIns = Seq(p), extraOuts = Seq(carried(w, Seq(p)))))
      no(executor(ctx), refreshTx(w, at(w), rh, extraIns = Seq(p), extraOuts = Seq(carried(w, Seq(p), plus = -1L))))
      no(executor(ctx), refreshTx(w, at(w), rh, extraIns = Seq(p)))
      val m = tokenLeak(ctx)
      val mp = poolIn(w, rh - 100, pool = m)
      ok(executor(ctx), refreshTx(w, at(w), rh, extraIns = Seq(mp), extraOuts = Seq(carried(w, Seq(mp), plus = -1L, c = m))))
      // A liquidation (anyone, once liquidatable).
      val d = world(ctx)
      val miss = d.due(0) + grace
      val dp = poolIn(d, miss - 100)
      val liq = vars(at(d), Seq(0 -> liqVar(fi(ctx).payout), 1 -> int(0)))
      val fees = wallet(ctx, executor(ctx), 7, miss - 100)
      def liquidate(out: Seq[UTXO]): Tx = txAt(ctx, miss, Seq(liq, dp, fees), proceeds(d, liq) +: out, executor(ctx).getAddress)
      ok(executor(ctx), liquidate(Seq(carried(d, Seq(dp)))))
      no(executor(ctx), liquidate(Seq(carried(d, Seq(dp), plus = -1L))))
      no(executor(ctx), liquidate(Seq.empty))
      // Any box holding the 2 units, whatever its script: the pool's own rule alone holds the value.
      for (kind <- kinds) {
        val k = world(ctx, kind)
        val h = ctx.getHeight + 100
        val kp = poolIn(k, h - 100, erg = 3L * minBox)
        def beside(out: Seq[UTXO]): Tx = txAt(ctx, h, Seq(bare2(ctx, h - 100), kp, wallet(ctx, executor(ctx), 7, h - 100)),
          UTXO(Contract.SIGMA_TRUE, minBox, Seq(Token(bondAsset, 2L))) +: out, executor(ctx).getAddress)
        ok(executor(ctx), beside(Seq(carried(k, Seq(kp)))))
        no(executor(ctx), beside(Seq(carried(k, Seq(kp), plus = -1L))))
        if (!k.erg) no(executor(ctx), beside(Seq(carried(k, Seq(kp), ergPlus = -1L))))
        no(executor(ctx), beside(Seq(UTXO(contractOf(executor(ctx)), kp.value, kp.tokens))))
      }
    }
  }

  // ═══ MERGE ════════════════════════════════════════════════════════════════

  for (kind <- kinds) {
    property(s"pool ($kind): two pools merge into one output, in a Pay or beside a Record; never counted once for both") {
      withCtx { ctx =>
        val w = world(ctx, kind)
        val h = w.due(0) + 5
        val p1 = poolIn(w, h - 100, index = 1)
        val p2 = poolIn(w, h - 90, index = 2, amount = 2L * w.coupon, erg = 2L * minBox)
        // In a Pay: both pools and the new coupon in one box; the holder then collects three coupons at once.
        val paid = ok(borrower(ctx), payTx(w, at(w), h, extraIns = Seq(p1, p2), coupon = toppedPool(w, Seq(p1, p2))))
        val merged = spendOutput(paid, 1)
        println(s"FICOST|pay $kind, 2 pools merged=${paid.getCost}")
        if (w.erg) merged.value shouldBe 4L * w.coupon else usd(merged) shouldBe 4L * w.coupon
        ok(lender(ctx), collectTx(w, Seq(merged), Seq(unit(ctx, h)), h + 1))
        // Beside a Record breach: the two merged, exactly.
        def rec(ins: Seq[InputUTXO], out: Seq[UTXO]): Tx = recordTx(w, at(w), h, 0L, extraIns = ins, extraOuts = out)
        ok(executor(ctx), rec(Seq(p1, p2), Seq(carried(w, Seq(p1, p2)))))
        // One output holding only the larger pool, named by both: refused.
        no(executor(ctx), rec(Seq(p1, p2), Seq(carried(w, Seq(p2)))))
        // Two outputs, each re-creating one pool: each pool asks for the sum of both in one output, refused.
        no(executor(ctx), rec(Seq(p1, p2), Seq(carried(w, Seq(p1)), carried(w, Seq(p2)))))
        // Without the sums the double count signs: the sums refused it.
        val m = leak(ctx)
        val m1 = poolIn(w, h - 100, index = 1, pool = m)
        val m2 = poolIn(w, h - 90, index = 2, amount = 2L * w.coupon, erg = 2L * minBox, pool = m)
        ok(executor(ctx), rec(Seq(m1, m2), Seq(carried(w, Seq(m2), c = m))))
        ok(executor(ctx), rec(Seq(m1, m2), Seq(carried(w, Seq(m1), c = m), carried(w, Seq(m2), c = m))))
        // A pool of another asset cannot ride along: without its own asset's 2 units it has no path.
        val other = poolIn(w, h - 80, index = 3, asset = otherBondAsset)
        no(executor(ctx), rec(Seq(p1, other), Seq(carried(w, Seq(p1, other)))))
      }
    }
  }

  property("pool: two bonds pay in one transaction, each topping up its own pool; one bond's pool fed to the other's is refused") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = w.due(0) - 10
      val a = at(w)
      val b = inputAt(fiBox(w, tokens = Token(otherBondAsset, 2L) +: w.collateral)
        .withRegNum(4, bytesValue(otherBondAsset.getBytes)), ctx, 1)
      val pa = poolIn(w, h - 100, index = 2)
      val pb = poolIn(w, h - 100, index = 3, asset = otherBondAsset)
      val payer = wallet(ctx, borrower(ctx), 6, h, borrowerFunds)
      def tx(outs: Seq[UTXO]): Tx = txAt(ctx, h, Seq(vars(a, Seq(4 -> int(0), 5 -> int(2))),
        vars(b, Seq(4 -> int(1), 5 -> int(3))), pa, pb, payer), outs, borrower(ctx).getAddress)
      val sa = paid(w, a, h)
      val sb = paid(w, b, h)
      val ta = toppedPool(w, Seq(pa))
      val tb = toppedPool(w, Seq(pb)).withRegNum(4, bytesValue(otherBondAsset.getBytes))
      ok(borrower(ctx), tx(Seq(sa, sb, ta, tb)))
      // B's pool carried in A's output, B's own output only its coupon: B's Pay sum refuses (both pools
      // and A are content), and a B that counts only the coupon signs the same transaction.
      val cross = Seq(toppedPool(w, Seq(pa), extra = w.coupon), couponOut(w, asset = otherBondAsset))
      no(borrower(ctx), tx(Seq(sa, sb) ++ cross))
      val bm = inputAt(fiBox(w, tokens = Token(otherBondAsset, 2L) +: w.collateral)
        .withRegNum(4, bytesValue(otherBondAsset.getBytes)).setContract(bondFor(w, fi(ctx).couponVault, owedCoupon = true)), ctx, 1)
      ok(borrower(ctx), txAt(ctx, h, Seq(vars(a, Seq(4 -> int(0), 5 -> int(2))), vars(bm, Seq(4 -> int(1), 5 -> int(3))), pa, pb, payer),
        Seq(sa, paid(w, bm, h)) ++ cross, borrower(ctx).getAddress))
    }
  }

  // ═══ REFRESH ══════════════════════════════════════════════════════════════

  private def refreshPoolTx(w: W, pool: InputUTXO, h: Int, next: UTXO, index: Int = 0,
                            extraIns: Seq[InputUTXO] = Seq.empty, created: Int = -1, burn: Seq[Token] = Seq.empty): Tx = {
    val c = if (created < 0) w.ctx.getHeight else created
    txAt(w.ctx, h, (pool.withCtxVar(9.toByte, int(index)) +: extraIns) :+ wallet(w.ctx, executor(w.ctx), 7, c),
      Seq(next), executor(w.ctx).getAddress, burn = burn)
  }

  private def renewed(pool: InputUTXO, value: Long, stamp: Int): UTXO = pool.toUTXO.setValue(value).setCreationHeight(stamp)

  for (kind <- kinds) {
    property(s"pool refresh ($kind): anyone, once older than 525600 blocks; the box unchanged but for a bounty") {
      withCtx { ctx =>
        val w = world(ctx, kind)
        val c = ctx.getHeight
        val p = poolIn(w, c, amount = if (w.erg) 100000000L else 20L, erg = 100000000L)
        val h = c + RefreshAge + 1
        val keep = p.value - Bounty
        ok(executor(ctx), refreshPoolTx(w, p, h, renewed(p, keep, h - 1)))
        // Too young: at exactly 525600 blocks old.
        no(executor(ctx), refreshPoolTx(w, p, h - 1, renewed(p, keep, h - 2)))
        // The bounty is at most 10000000 nanoERG.
        no(executor(ctx), refreshPoolTx(w, p, h, renewed(p, keep - 1L, h - 1)))
        // The stamp lies within 1000 blocks of HEIGHT, and no later.
        Seq(h, h - 999).foreach(s => ok(executor(ctx), refreshPoolTx(w, p, h, renewed(p, keep, s))))
        no(executor(ctx), refreshPoolTx(w, p, h, renewed(p, keep, h - 1000)))
        // Script, register and tokens unchanged; no register added.
        val next = renewed(p, keep, h - 1)
        Seq("script" -> next.setContract(lenderTree(ctx)),
          "R4" -> next.withRegNum(4, bytesValue(otherBondAsset.getBytes)),
          "R5 added" -> withR5(ctx, next),
          "R5 Long added" -> next.withRegNum(5, ErgoValue.of(w.maturity)),
          "token added" -> next.setTokens(next.tokens :+ Token(secondCollateral, 1L): _*)
        ).foreach { case (name, bad) =>
          // The refresher's own box supplies an added token, so every case builds.
          withClue(name)(no(executor(ctx), refreshPoolTx(w, p, h, bad,
            extraIns = Seq(wallet(ctx, executor(ctx), 8, c, Seq(Token(secondCollateral, 10L)))))))
        }
        if (!w.erg) {
          val t = next.tokens.head
          no(executor(ctx), refreshPoolTx(w, p, h, next.setTokens(Token(t.id, t.amount - 1L)), burn = Seq(Token(t.id, 1L))))
        }
        // The index must name an output.
        Seq(-1, 5).foreach(i => no(executor(ctx), refreshPoolTx(w, p, h, next, index = i)))
      }
    }
  }

  property("pool refresh: the value never goes below 1000000; a box at or under it renews for nothing") {
    withCtx { ctx =>
      val w = world(ctx, ErgBond)
      val c = ctx.getHeight
      val h = c + RefreshAge + 1
      val thin = poolIn(w, c, amount = 6000000L)
      ok(executor(ctx), refreshPoolTx(w, thin, h, renewed(thin, 1000000L, h)))
      no(executor(ctx), refreshPoolTx(w, thin, h, renewed(thin, 999999L, h)))
      val floor = poolIn(w, c, amount = 1000000L)
      ok(executor(ctx), refreshPoolTx(w, floor, h, renewed(floor, 1000000L, h)))
      no(executor(ctx), refreshPoolTx(w, floor, h, renewed(floor, 999999L, h)))
      // A token pool at the box minimum keeps all of it.
      val t = world(ctx)
      val tp = poolIn(t, c)
      ok(executor(ctx), refreshPoolTx(t, tp, h, renewed(tp, minBox, h)))
      no(executor(ctx), refreshPoolTx(t, tp, h, renewed(tp, minBox - 1L, h)))
    }
  }

  property("pool refresh: it must be the only input at the pool script, so two equal pools cannot share one successor") {
    withCtx { ctx =>
      val w = world(ctx, ErgBond)
      val c = ctx.getHeight
      val h = c + RefreshAge + 1
      val p1 = poolIn(w, c, index = 1)
      val p2 = poolIn(w, c, index = 2)
      val next = renewed(p1, p1.value - Bounty, h)
      ok(executor(ctx), refreshPoolTx(w, p1, h, next))
      // Both refreshed into one successor would hand the refresher the second pool whole.
      no(executor(ctx), refreshPoolTx(w, p1, h, next, extraIns = Seq(p2.withCtxVar(9.toByte, int(0)))))
      val m = soloFree(ctx)
      val m1 = poolIn(w, c, index = 1, pool = m)
      val m2 = poolIn(w, c, index = 2, pool = m)
      ok(executor(ctx), refreshPoolTx(w, m1, h, renewed(m1, m1.value - Bounty, h), extraIns = Seq(m2.withCtxVar(9.toByte, int(0)))))
    }
  }

  property("pool: a refreshed pool still collects, and neither it nor the old one opens to the borrower long after maturity") {
    withCtx { ctx =>
      for (kind <- kinds) {
        val w = world(ctx, kind)
        val c = ctx.getHeight
        val h = c + RefreshAge + 1
        val p = poolIn(w, c, erg = 20000000L, amount = if (w.erg) 20000000L else 20L)
        val r = spendOutput(ok(executor(ctx), refreshPoolTx(w, p, h, renewed(p, p.value - Bounty, h))), 0)
        r.registers shouldBe poolRegs()
        ok(lender(ctx), collectTx(w, Seq(r), Seq(unit(ctx, h)), h + 1))
        LongAfter.map(w.maturity.toInt + _).foreach { t =>
          withClue(s"height maturity + ${t - w.maturity}: ") {
            poolRefuses(borrower(ctx), w, poolIn(w, c))(q => takeTx(w, q, t))
            poolRefuses(borrower(ctx), w, r)(q => takeTx(w, q, math.max(t, h + 1)))
          }
        }
      }
    }
  }

  for (kind <- kinds) {
    property(s"pool ($kind): Pay and top-up write R4 alone; an R5 on the pool output is refused by the bond and by the pool") {
      withCtx { ctx =>
        val w = world(ctx, kind)
        val h = w.due(1) - 10
        val p1 = poolIn(w, h - 100)
        val b = at(w, paid = 1L)
        val topped = toppedPool(w, Seq(p1))
        val signed = ok(borrower(ctx), payTx(w, b, h, extraIns = Seq(p1), coupon = topped))
        spendOutput(signed, 1).registers shouldBe poolRegs()
        // The topped-up pool with an R5 naming the borrower: the bond refuses it; the bond without its "no R5"
        // still refuses, now by the spent pool's top-up rule; beside the pool without that rule too, it signs.
        no(borrower(ctx), payTx(w, b, h, extraIns = Seq(p1), coupon = withR5(ctx, topped)))
        no(borrower(ctx), payTx(w, bondAt(w, bondFor(w, fi(ctx).couponVault, anyRegs = true), 1L), h, extraIns = Seq(p1),
          coupon = withR5(ctx, topped)))
        val r = r5Free(ctx)
        val rp = poolIn(w, h - 100, pool = r)
        ok(borrower(ctx), payTx(w, bondAt(w, bondFor(w, r, anyRegs = true), 1L), h, extraIns = Seq(rp),
          coupon = withR5(ctx, toppedPool(w, Seq(rp)).setContract(r))))
      }
    }
  }

  property("pool: a pool left out of the 3-unit claim has no collector; only a refresh spends it") {
    withCtx { ctx =>
      for (kind <- kinds) {
        val w = world(ctx, kind)
        val missed = w.due(1) + grace
        // Coupon 0 paid, coupon 1 missed, the bond liquidated; the lender's key-held unit claims the settlement
        // and burns all 3 units without the pool.
        val paidTx = ok(borrower(ctx), payTx(w, at(w), w.due(0) - 10))
        val pool = spendOutput(paidTx, 1)
        val vault = spendOutput(ok(executor(ctx), fiLiquidateTx(w, spendOutput(paidTx, 0), missed)), 0)
        ok(lender(ctx), collectTx(w, Seq(vault), Seq(unit(ctx, missed)), missed + 1,
          outs = Seq(UTXO(lenderTree(ctx), minBox)), burn = Seq(Token(bondAsset, 3L))))
        // Afterwards the borrower, the former unit holder and a stranger are each refused by the pool alone.
        (Seq(missed + 2) ++ LongAfter.map(w.maturity.toInt + _)).foreach { t =>
          withClue(s"height $t: ") {
            poolRefuses(borrower(ctx), w, pool)(q => takeTx(w, q, t))
            poolRefuses(lender(ctx), w, pool)(q => takeTx(w, q, t, by = lender(ctx)))
            poolRefuses(executor(ctx), w, pool)(q => takeTx(w, q, t, by = executor(ctx)))
          }
        }
        // Anyone may still renew it, unchanged, once it is old.
        val renewedPool = spendOutput(ok(executor(ctx), renewTx(w, pool, w.due(0) - 10 + RefreshAge + 1)), 0)
        renewedPool.value shouldBe pool.value
        renewedPool.tokens shouldBe pool.tokens
        renewedPool.registers shouldBe poolRegs()
      }
    }
  }

  // ═══ JUNK TOKENS ══════════════════════════════════════════════════════════

  /** `k` distinct token ids nobody else uses. */
  private def junkIds(k: Int): Seq[Token] = (0 until k).map(i => Token(ErgoId.create("e1" + f"$i%062x"), 1L))

  for (kind <- kinds) {
    property(s"pool junk ($kind): Pay refuses a coupon output holding any token but the repayment token") {
      withCtx { ctx =>
        val w = world(ctx, kind)
        val h = w.due(0) - 10
        val j = junkIds(3)
        val funds = Seq(wallet(ctx, borrower(ctx), 12, h, j))
        val plain = couponOut(w)
        val loaded = plain.setTokens(plain.tokens ++ j: _*)
        ok(borrower(ctx), payTx(w, at(w), h, extraIns = funds))
        no(borrower(ctx), payTx(w, at(w), h, coupon = loaded, extraIns = funds))
        // The bond without its token rules (the id rule and, from round 3, one entry at most) signs the same Pay.
        val m = bondFor(w, fi(ctx).couponVault, anyTokens = true)
        ok(borrower(ctx), payTx(w, bondAt(w, m), h, coupon = loaded, extraIns = funds))
        // An ERG bond's coupon output may not hold SigUSD either; a token bond's may hold only it, in any amount above the coupon.
        if (w.erg) no(borrower(ctx), payTx(w, at(w), h, coupon = plain.setTokens(Token(SigUSD, 1L))))
        else ok(borrower(ctx), payTx(w, at(w), h, coupon = couponOut(w, amount = w.coupon + 5L)))
      }
    }

    property(s"pool junk ($kind): a stranger's 2-unit spend cannot add a token id to a pool; two same-token pools still merge") {
      withCtx { ctx =>
        val w = world(ctx, kind)
        val h = w.due(0) + 5
        val p1 = poolIn(w, h - 100, index = 1)
        val p2 = poolIn(w, h - 90, index = 2, amount = 2L * w.coupon, erg = 2L * minBox)
        val j = junkIds(2)
        val stuff = wallet(ctx, executor(ctx), 12, h, j)
        def rec(pools: Seq[InputUTXO], out: UTXO): Tx = recordTx(w, at(w), h, 0L, extraIns = pools :+ stuff, extraOuts = Seq(out))
        val whole = carried(w, Seq(p1))
        ok(executor(ctx), rec(Seq(p1), whole))
        // Re-created whole but with junk ids added: refused; the pool without its size bound still refuses it
        // (round 3's id test), and without both it signs.
        no(executor(ctx), rec(Seq(p1), whole.setTokens(whole.tokens ++ j: _*)))
        val sp = poolIn(w, h - 100, index = 1, pool = sizeFree(ctx))
        no(executor(ctx), rec(Seq(sp), carried(w, Seq(sp), c = sizeFree(ctx)).setTokens(carried(w, Seq(sp), c = sizeFree(ctx)).tokens ++ j: _*)))
        val m = once("sizeIdFree")(FiStandardSpecBase.poolVariant(ctx, "p.tokens.size <= SELF.tokens.size &&" -> "true &&",
          "p.tokens.forall { (t: (Coll[Byte], Long)) => SELF.tokens.exists { (u: (Coll[Byte], Long)) => u._1 == t._1 } } &&" -> "true &&"))
        val mp = poolIn(w, h - 100, index = 1, pool = m)
        val mw = carried(w, Seq(mp), c = m)
        ok(executor(ctx), rec(Seq(mp), mw.setTokens(mw.tokens ++ j: _*)))
        // Two pools of this bond merge into one output, exactly; with a junk id added, refused.
        val both = carried(w, Seq(p1, p2))
        ok(executor(ctx), rec(Seq(p1, p2), both))
        no(executor(ctx), rec(Seq(p1, p2), both.setTokens(both.tokens :+ j.head: _*)))
        // A token pool's repayment token cannot be swapped for another id of the same count.
        if (!w.erg) no(executor(ctx), rec(Seq(p1), whole.setTokens(j.head)))
      }
    }
  }

  // ═══ THE CUT AT EVERY OTHER HOME OR PATH ══════════════════════════════════

  /**
   * At a contract home the payout may leave out 1/200 of the pools' own amounts (and, at a 3-unit burn, the home's
   * R5(2)). These are the other homes and paths that can spend a pool, each with the exact most a stranger keeps.
   */
  property("cut: at a ZC fractional vault's refresh beside an ERG pool the refresher keeps exactly the cut, never the home's value; a token pool cannot ride along") {
    withCtx { ctx =>
      val ew = world(ctx, ErgBond)
      val tw = world(ctx)
      val c = ctx.getHeight
      val h = c + RefreshAge + 1
      val share = ErgoId.create("cc" * 32)
      val zc = inputAt(UTXO(scripts(ctx).fractionalVault, 200000000L, Seq(Token(share, 1001L), Token(bondAsset, 1L)),
        Seq(bytesValue(bondAsset.getBytes), longsValue(1000L, 1000L, 2000000L)), Some(c)), ctx, 3).withCtxVar(9.toByte, int(0))
      val fees = wallet(ctx, executor(ctx), 7, h - 100)
      def refresh(pools: Seq[InputUTXO], value: Long): Tx =
        txAt(ctx, h, (zc +: pools) :+ fees, Seq(zc.toUTXO.setValue(value).setCreationHeight(h)), executor(ctx).getAddress)
      // Alone, the ZC refresh pays its own bounty of up to 10000000 nanoERG out of the home.
      ok(executor(ctx), refresh(Seq.empty, zc.value - Bounty))
      val p = poolIn(ew, h - 40, amount = Parameters.OneErg)
      val q = poolIn(ew, h - 40, index = 2, amount = 3L * Parameters.OneErg)
      // Beside one pool, or two: the refresher keeps exactly the cut of what the pools hold together.
      ok(executor(ctx), refresh(Seq(p), zc.value + p.value - cut(p.value)))
      no(executor(ctx), refresh(Seq(p), zc.value + p.value - cut(p.value) - 1L))
      ok(executor(ctx), refresh(Seq(p, q), zc.value + p.value + q.value - cut(p.value + q.value)))
      no(executor(ctx), refresh(Seq(p, q), zc.value + p.value + q.value - cut(p.value + q.value) - 1L))
      cut(p.value) shouldBe 5000000L
      // The home's own bounty is not on offer with a pool beside it: the pool counts the home's value whole.
      no(executor(ctx), refresh(Seq(p), zc.value - Bounty + p.value))
      // A token pool: the refresh keeps the token list exactly, so the pool's SigUSD has nowhere to go; the same
      // spend signs with the pool moved to the open pool (its SigUSD to change): the pool refused it.
      poolRefuses(executor(ctx), tw, poolIn(tw, h - 40, amount = 1000L))(t => refresh(Seq(t), zc.value - Bounty))
    }
  }

  property("cut: at a ZC fractional vault's pull, or a Dutch auction vault's, beside an ERG pool the puller keeps exactly the pull fee R5(2) and the cut") {
    withCtx { ctx =>
      val ew = world(ctx, ErgBond)
      val tw = world(ctx)
      val h = ctx.getHeight + 100
      val share = ErgoId.create("cc" * 32)
      val zcFee = 2000000L
      val settled = inputAt(UTXO(scripts(ctx).vault, 4000000000L, Seq(Token(bondAsset, 2L)),
        Seq(bytesValue(ErgoId.create("77" * 32).getBytes), ErgoValue.of(0.toByte)), Some(h - 60)), ctx, 31)
      val zc = inputAt(UTXO(scripts(ctx).fractionalVault, minBox + zcFee, Seq(Token(share, 1001L), Token(bondAsset, 1L)),
        Seq(bytesValue(bondAsset.getBytes), longsValue(1000L, 1000L, zcFee)), Some(h - 60)), ctx, 3).withCtxVar(0.toByte, int(0))
      val auction = longsValue(4000000000L, 20000L, 15000L, 500L, 500L, Long.MaxValue, 0L)
      val dutch = inputAt(UTXO(exchange(ctx).dutchVault, minBox + zcFee, Seq(Token(share, 1001L), Token(bondAsset, 1L)),
        Seq(bytesValue(bondAsset.getBytes), longsValue(1000L, 1000L, zcFee), ErgoValue.of(0.toByte), auction, bytesValue(Array.emptyByteArray)),
        Some(h - 60)), ctx, 4).withCtxVar(0.toByte, int(0))
      val fees = wallet(ctx, executor(ctx), 7, h - 100)
      // The successor: the reserve alone, R6 the repaid path (the Dutch vault keeps its R6..R8: state 0, no auction).
      def pulled(home: InputUTXO, value: Long): UTXO =
        UTXO(home.contract, value, Seq(Token(share, 1001L)),
          if (home.contract == exchange(ctx).dutchVault) home.registers else home.registers.take(2) :+ ErgoValue.of(0.toByte))
      def pull(home: InputUTXO, pools: Seq[InputUTXO], value: Long): Tx =
        txAt(ctx, h, Seq(home, settled) ++ pools :+ fees, Seq(pulled(home, value)), executor(ctx).getAddress,
          burn = Seq(Token(bondAsset, 3L)))
      val p = poolIn(ew, h - 40, amount = Parameters.OneErg)
      Seq("ZC fractional vault" -> zc, "Dutch auction vault" -> dutch).foreach { case (name, home) =>
        withClue(name) {
          val all = home.value + settled.value
          // Without a pool the puller keeps the pull fee; with the pool, the pull fee and the cut, to the nanoERG.
          ok(executor(ctx), pull(home, Seq.empty, all - zcFee))
          no(executor(ctx), pull(home, Seq.empty, all - zcFee - 1L))
          ok(executor(ctx), pull(home, Seq(p), all + p.value - zcFee - cut(p.value)))
          no(executor(ctx), pull(home, Seq(p), all + p.value - zcFee - cut(p.value) - 1L))
          // A token pool cannot ride along: the home keeps the proceeds' tokens exactly. With the pool moved to the
          // open pool, its SigUSD to change, the same pull signs: the pool refused it.
          poolRefuses(executor(ctx), tw, poolIn(tw, h - 40, amount = 1000L))(t => pull(home, Seq(t), all - zcFee))
        }
      }
    }
  }

  property("cut: a 2-unit spend beside a pool (a top-up beside a bond move) keeps the pool whole: not even the cut may stay out") {
    withCtx { ctx =>
      for (kind <- kinds) {
        val w = world(ctx, kind)
        val h = w.due(0) + 5
        val p = poolIn(w, h - 100, amount = if (w.erg) Parameters.OneErg else 1000L, erg = Parameters.OneErg)
        def rec(out: UTXO, pool: InputUTXO = p): Tx = recordTx(w, at(w), h, 0L, extraIns = Seq(pool), extraOuts = Seq(out))
        ok(executor(ctx), rec(carried(w, Seq(p))))
        // Short by exactly the cut, in the repayment asset or (token pool) in nanoERG: refused; the pool without
        // its top-up sums signs both, so the sums refused them.
        no(executor(ctx), rec(carried(w, Seq(p), plus = -cut(if (w.erg) p.value else 1000L))))
        if (!w.erg) no(executor(ctx), rec(carried(w, Seq(p), ergPlus = -cut(p.value))))
        val m = leak(ctx)
        val mp = poolIn(w, h - 100, amount = if (w.erg) Parameters.OneErg else 1000L, erg = Parameters.OneErg, pool = m)
        ok(executor(ctx), rec(carried(w, Seq(mp), plus = -cut(if (w.erg) mp.value else 1000L), c = m), mp))
        if (!w.erg) ok(executor(ctx), rec(carried(w, Seq(mp), ergPlus = -cut(mp.value), c = m), mp))
        // Beside the 2 units at an always-true script too.
        def beside(out: UTXO): Tx = txAt(ctx, h, Seq(bare2(ctx, h - 100), p, wallet(ctx, executor(ctx), 7, h - 100)),
          Seq(UTXO(Contract.SIGMA_TRUE, minBox, Seq(Token(bondAsset, 2L))), out), executor(ctx).getAddress)
        ok(executor(ctx), beside(carried(w, Seq(p))))
        no(executor(ctx), beside(carried(w, Seq(p), plus = -1L)))
      }
    }
  }

  property("cut: at a 3-unit burn at a key home the outputs are free (the key signs); the home's R5 is never read there") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = w.due(0) + grace
      val p = poolIn(w, h - 100)
      val vault = spendOutput(ok(executor(ctx), fiLiquidateTx(w, at(w), h)), 0)
      // The lender's key box holding the unit, with an R5 of another type than Coll[Long]: an eager read of it would
      // fail the collect. The burn, with the pool, everything to the lender, signs.
      val keyed = inputAt(UTXO(lenderTree(ctx), minBox * 5L, Seq(Token(bondAsset, 1L)),
        Seq(bytesValue(bondAsset.getBytes), bytesValue(bondAsset.getBytes)), Some(h)), ctx, 8)
      ok(lender(ctx), collectTx(w, Seq(vault, p), Seq(keyed), h + 1, outs = Seq(UTXO(lenderTree(ctx), minBox)),
        burn = Seq(Token(bondAsset, 3L))))
      // A stranger cannot spend the key box at all.
      no(executor(ctx), collectTx(w, Seq(vault, p), Seq(keyed), h + 1, by = executor(ctx),
        outs = Seq(UTXO(contractOf(executor(ctx)), minBox)), burn = Seq(Token(bondAsset, 3L))))
    }
  }

  // ═══ COLLECT AT A CONTRACT HOME WHEN ALL 3 BURN ═══════════════════════════

  property("pool: at a contract home the coupon may go into the home's successor at the settlement vault's claim; the settlement counts too") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = ctx.getHeight + 100
      // Worst case: an anyone-can-spend contract home holding the unit after its own first token (as a share
      // vault holds its reserve first), and the settled bond vault. At the burn the payout is the output at the
      // home's script that holds that first token first.
      val mark = Token(ErgoId.create("6d" * 32), 1L)
      // R5 a Coll[Long] whose (2) is the nanoERG the home lets its caller keep at a burn: 0 here.
      def homeWith(regs: Seq[ErgoValue[_]], index: Int = 33): InputUTXO =
        inputAt(UTXO(Contract.SIGMA_TRUE, Parameters.OneErg, Seq(mark, Token(bondAsset, 1L)), regs, Some(h - 60)), ctx, index)
      val home = homeWith(Seq(bytesValue(bondAsset.getBytes), longsValue(1L, 1L, 0L)))
      val bv = inputAt(UTXO(scripts(ctx).vault, 5L * minBox, Seq(Token(bondAsset, 2L), Token(SigUSD, 800L)),
        Seq(bytesValue(ErgoId.create("77" * 32).getBytes), ErgoValue.of(0.toByte)), Some(h - 60)), ctx, 31)
      val fees = wallet(ctx, executor(ctx), 7, h - 60)
      def claimAt(from: InputUTXO, pool: InputUTXO, tokens: Seq[Token], erg: Long): Tx =
        txAt(ctx, h, Seq(from, bv, pool, fees), Seq(UTXO(Contract.SIGMA_TRUE, erg, tokens)),
          executor(ctx).getAddress, burn = Seq(Token(bondAsset, 3L)))
      def claim(pool: InputUTXO, usd: Long, erg: Long): Tx = claimAt(home, pool, Seq(mark, Token(SigUSD, usd)), erg)
      val all = home.value + bv.value
      val p = poolIn(w, h - 50)
      // The home's successor carries the home, the settlement and the pool: signs.
      ok(executor(ctx), claim(p, 800L + w.coupon, all + p.value))
      // The pool's coupon taken as change, the successor holding only the settlement's SigUSD: refused, and
      // signed by the pool that does not count the 2-unit box (its SigUSD would stand in for the coupon).
      no(executor(ctx), claim(p, 800L, all + p.value))
      val u = unitOnly(ctx)
      ok(executor(ctx), claim(poolIn(w, h - 50, pool = u), 800L, all + p.value))
      // Short by the cut of the pool, 1/200 of its nanoERG (its 20 SigUSD cut to 0), signs; one nanoERG more is refused.
      ok(executor(ctx), claim(p, 800L + w.coupon, all + p.value - cut(p.value)))
      no(executor(ctx), claim(p, 800L + w.coupon, all + p.value - cut(p.value) - 1L))
      // With 1000 SigUSD in the pool the token cut is 5: exactly that may stay out, not 6.
      val big = poolIn(w, h - 50, amount = 1000L)
      ok(executor(ctx), claim(big, 800L + 1000L - 5L, all + big.value - cut(big.value)))
      no(executor(ctx), claim(big, 800L + 1000L - 6L, all + big.value - cut(big.value)))
      // A home whose R5(2) is 3000000 may also keep that much at the burn, and no more.
      val feeHome = homeWith(Seq(bytesValue(bondAsset.getBytes), longsValue(1L, 1L, 3000000L)))
      ok(executor(ctx), claimAt(feeHome, p, Seq(mark, Token(SigUSD, 800L + w.coupon)), all + p.value - cut(p.value) - 3000000L))
      no(executor(ctx), claimAt(feeHome, p, Seq(mark, Token(SigUSD, 800L + w.coupon)), all + p.value - cut(p.value) - 3000001L))
      // A home with no R5, or an R5 of another type, cannot collect at a burn; with the Coll[Long] it signs above.
      Seq("no R5" -> Seq.empty[ErgoValue[_]], "R5 bytes" -> Seq(bytesValue(bondAsset.getBytes), bytesValue(bondAsset.getBytes)))
        .foreach { case (name, regs) =>
          withClue(name)(no(executor(ctx), claimAt(homeWith(regs), p, Seq(mark, Token(SigUSD, 800L + w.coupon)), all + p.value)))
        }
      // The pool that collects at a contract home only while the unit survives refuses the full claim.
      no(executor(ctx), claim(poolIn(w, h - 50, pool = burnShut(ctx)), 800L + w.coupon, all + p.value))
      // The payout without the home's first token first: refused, and signed by the pool without that rule.
      no(executor(ctx), claimAt(home, p, Seq(Token(SigUSD, 800L + w.coupon), mark), all + p.value))
      ok(executor(ctx), claimAt(home, poolIn(w, h - 50, pool = homeFree(ctx)), Seq(Token(SigUSD, 800L + w.coupon), mark),
        all + p.value))
      // A contract home holding only the unit has nothing to name its payout by once the unit is burned: it cannot
      // collect at the claim (the pool then has no collector), though the pool without that rule signs.
      val bare = inputAt(UTXO(Contract.SIGMA_TRUE, Parameters.OneErg, Seq(Token(bondAsset, 1L)),
        Seq(bytesValue(bondAsset.getBytes), longsValue(1L, 1L, 0L)), Some(h - 60)), ctx, 34)
      no(executor(ctx), claimAt(bare, p, Seq(Token(SigUSD, 800L + w.coupon)), all + p.value))
      ok(executor(ctx), claimAt(bare, poolIn(w, h - 50, pool = homeFree(ctx)), Seq(Token(SigUSD, 800L + w.coupon)), all + p.value))
      // The unit still may not burn alone, at a contract home or a key.
      no(executor(ctx), txAt(ctx, h, Seq(home, p, fees), Seq(UTXO(Contract.SIGMA_TRUE, home.value + p.value,
        Seq(mark, Token(SigUSD, w.coupon)))), executor(ctx).getAddress, burn = Seq(Token(bondAsset, 1L))))
    }
  }

  // ═══ AUDIT FIXES ══════════════════════════════════════════════════════════

  /** The bond with one Pay term taken out (the clause a refusal below is meant to show). */
  private def payMutant(w: W, edit: (String, String)): Contract =
    once(s"payMutant|${w.erg}|${edit._1}")(FiStandardSpecBase.bondVariant(w.ctx, repaymentBytes(w), fi(w.ctx).couponVault, edit))
  private val noMergeZone = "(poolIns.size == 0 || height < miss - quietLen) &&" -> "true &&"
  private val noPoolStamp = "pool(couponBox) && couponBox.creationInfo._1 > HEIGHT - 1000 &&" -> "pool(couponBox) &&"

  for (kind <- kinds) {
    property(s"F-005 ($kind): a Pay merging pools is refused in the coupon quiet zone [miss - grace / 2, miss); a fresh-pool Pay signs there") {
      withCtx { ctx =>
        val w = world(ctx, kind)
        val miss = w.due(0) + grace
        val zone = miss - grace / 2
        val b = at(w)
        val p1 = poolIn(w, w.start, index = 1)
        def merging(h: Int, bond: InputUTXO = b): Tx = payTx(w, bond, h, extraIns = Seq(p1), coupon = toppedPool(w, Seq(p1)))
        ok(borrower(ctx), payTx(w, b, zone - 1, extraIns = Seq(p1), coupon = toppedPool(w, Seq(p1))))
        Seq(zone, miss - 1).foreach { h =>
          withClue(s"merging at $h")(no(borrower(ctx), merging(h)))
          withClue(s"fresh pool at $h")(ok(borrower(ctx), payTx(w, b, h)))
          // The bond without the term signs the merge: that term, and nothing else, refused it.
          withClue(s"no zone at $h")(ok(borrower(ctx), merging(h, bondAt(w, payMutant(w, noMergeZone)))))
        }
      }
    }

    property(s"F-010 ($kind): Pay's pool output is stamped within 999 blocks of HEIGHT, by the bond and, merging, by the pool") {
      withCtx { ctx =>
        val w = world(ctx, kind)
        val h = w.due(0) - 10
        // Every input old enough that a stamp 1000 back still meets the node's rule (no output below an input).
        val old = h - 1000
        val b = fiIn(w, fiBox(w, stamp = old))
        ok(borrower(ctx), payTx(w, b, h, coupon = couponOut(w).setCreationHeight(h - 999), payerCreated = old))
        no(borrower(ctx), payTx(w, b, h, coupon = couponOut(w).setCreationHeight(old), payerCreated = old))
        val loose = fiIn(w, fiBox(w, stamp = old).setContract(payMutant(w, noPoolStamp)))
        ok(borrower(ctx), payTx(w, loose, h, coupon = couponOut(w).setCreationHeight(old), payerCreated = old))
        // Merging a pool, with the bond's own term taken out: the pool's top-up refuses the old stamp alone.
        val p1 = poolIn(w, old, index = 1)
        ok(borrower(ctx), payTx(w, loose, h, extraIns = Seq(p1), coupon = toppedPool(w, Seq(p1)).setCreationHeight(h - 999),
          payerCreated = old))
        no(borrower(ctx), payTx(w, loose, h, extraIns = Seq(p1), coupon = toppedPool(w, Seq(p1)).setCreationHeight(old),
          payerCreated = old))
        val stampFree = once("stampFree")(FiStandardSpecBase.poolVariant(ctx, " && p.creationInfo._1 > HEIGHT - 1000 &&" -> " &&"))
        val looseFree = fiIn(w, fiBox(w, stamp = old).setContract(once(s"looseFree|${w.erg}")(
          FiStandardSpecBase.bondVariant(ctx, repaymentBytes(w), stampFree, noPoolStamp))))
        val pFree = poolIn(w, old, index = 1, pool = stampFree)
        ok(borrower(ctx), payTx(w, looseFree, h, extraIns = Seq(pFree),
          coupon = toppedPool(w, Seq(pFree)).setContract(stampFree).setCreationHeight(old), payerCreated = old))
      }
    }
  }

  private def termed(w: W, cs: (Int, Long)*): W = w.copy(terms = cs.foldLeft(w.terms) { case (t, (i, v)) => t.updated(i, v) })

  /** The bond with the coupon quiet zone taken out of Refresh and Record (Pay's own merge rule kept). */
  private def zoneFree(w: W): Contract = payMutant(w, "(if (liquidatable) refresh else !quiet)" -> "(refresh || !liquidatable)")

  for (kind <- kinds; g <- Seq(0L, 1L)) {
    property(s"F-005 short grace ($kind, grace $g, no covenant): the zone is at least 2 blocks; a merge is refused there, an unmerged Pay signs; Refresh refused there") {
      withCtx { ctx =>
        val w = termed(world(ctx, kind), 11 -> g, 12 -> 0L)
        val miss = w.due(0) + g.toInt
        val b = at(w)
        val p1 = poolIn(w, w.start, index = 1)
        def merging(h: Int, bond: InputUTXO = b): Tx = payTx(w, bond, h, extraIns = Seq(p1), coupon = toppedPool(w, Seq(p1)))
        ok(borrower(ctx), merging(miss - 3))
        Seq(miss - 2, miss - 1).foreach { h =>
          withClue(s"merging at $h")(no(borrower(ctx), merging(h)))
          withClue(s"unmerged at $h")(ok(borrower(ctx), payTx(w, b, h)))
          withClue(s"no zone at $h")(ok(borrower(ctx), merging(h, bondAt(w, payMutant(w, noMergeZone)))))
        }
        // Refresh, on a bond old enough at its first coupon: refused in the same two blocks, open the block before.
        val old = termed(world(ctx, kind, life = 700000L), 9 -> (RefreshAge + 100L), 11 -> g, 12 -> 0L)
        val oldMiss = old.start + RefreshAge + 100 + g.toInt
        ok(executor(ctx), refreshTx(old, at(old), oldMiss - 3))
        Seq(oldMiss - 2, oldMiss - 1).foreach { h =>
          withClue(s"refresh at $h")(no(executor(ctx), refreshTx(old, at(old), h)))
          withClue(s"refresh, no zone, at $h")(ok(executor(ctx), refreshTx(old, fiIn(old, fiBox(old).setContract(zoneFree(old))), h)))
        }
        // Record needs a covenant (with ratio 0 it never passes, zone or not); see the covenant case below.
        no(executor(ctx), recordTx(w, at(w), miss - 1, 0L))
      }
    }
  }

  property("F-005 short grace: on a covenant bond with grace 1 (made by hand; the FI order refuses it) Record is refused in the 2-block zone") {
    withCtx { ctx =>
      val w = termed(world(ctx), 11 -> 1L)
      val d = w.due(0)
      // Zone [d - 1, d + 1): the checkpoint's only unpaid block d is in it.
      no(executor(ctx), recordTx(w, at(w), d, 0L))
      ok(executor(ctx), recordTx(w, fiIn(w, fiBox(w).setContract(zoneFree(w))), d, 0L))
    }
  }

  /**
   * F-009 remainder. Storage rent lets anyone spend a unit box 1051200 or more blocks old (no signature), provided
   * it re-creates the box at its own script, tokens and registers kept. The harness cannot run the node's rent
   * rule, so the lender's signature stands in for that rent spend; the transactions keep the rent rule's shape:
   * the unit box re-created at the lender's script holding only the unit, plus the pools less the cut, the cut
   * paid to a third party, the fee from an always-true box.
   */
  property("F-009: an old P2PK unit box collects through neither branch; the sweep into the holder's box, keeping the cut, is refused") {
    withCtx { ctx =>
      val h = 1200000
      // The pool as it stood after round 1: the age on the P2PK test alone, and no split (a key home also
      // passes the contract-home branch).
      val split = once("splitFree")(FiStandardSpecBase.poolVariant(ctx,
        "h.creationInfo._1 > HEIGHT - 1051200 && (isKey ||" -> "((isKey && h.creationInfo._1 > HEIGHT - 1051200) ||",
        "(!isKey && OUTPUTS.exists" -> "(OUTPUTS.exists"))
      val ageFree = once("ageFree")(FiStandardSpecBase.poolVariant(ctx,
        "h.creationInfo._1 > HEIGHT - 1051200 && (isKey ||" -> "(isKey ||"))
      for (kind <- kinds) {
        val w = world(ctx, kind)
        // The key holder's ordinary collect: the young box collects; the old box does not, and does with the age term out.
        val pool = poolIn(w, w.start, index = 1)
        ok(lender(ctx), collectTx(w, Seq(pool), Seq(unit(ctx, h - 1051199)), h))
        no(lender(ctx), collectTx(w, Seq(pool), Seq(unit(ctx, h - 1051200)), h))
        ok(lender(ctx), collectTx(w, Seq(poolIn(w, w.start, index = 1, pool = ageFree)), Seq(unit(ctx, h - 1051200)), h))
        // The sweep: the old unit box re-created with the pools' nanoERG less the cut, which the sender keeps.
        def sweep(c: Contract, short: Long = 0L): Tx = {
          val p = poolIn(w, w.start, index = 1, pool = c)
          val u = unit(ctx, h - 1051200)
          val keep = cut(p.value)
          val fees = inputAt(UTXO(Contract.SIGMA_TRUE, 10000000L, Seq.empty, Seq.empty, Some(h - 1051200)), ctx, 37)
          txAt(ctx, h, Seq(p, u, fees),
            Seq(UTXO(lenderTree(ctx), u.value + p.value - keep - short, u.tokens), UTXO(otherTree(ctx), keep)),
            other(ctx).getAddress)
        }
        if (w.erg) {
          // Without the split the old key box passes the contract-home branch; with it, it passes neither.
          ok(lender(ctx), sweep(split))
          no(lender(ctx), sweep(split, short = 1L))
          no(lender(ctx), sweep(fi(ctx).couponVault))
        } else {
          // A token pool's SigUSD cannot follow the re-created box (the rent rule keeps its tokens): refused either way.
          no(lender(ctx), sweep(split))
          no(lender(ctx), sweep(fi(ctx).couponVault))
        }
      }
    }
  }
}
