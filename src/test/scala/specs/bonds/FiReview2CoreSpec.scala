package org.lithosprotocol.sigmafi
package specs.bonds

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import scala.collection.JavaConverters._

/**
 * Second adversarial review of FI_Standard_Bond, FI_Standard_CouponVault and FI_Standard_FractionalVault
 * (review-only file). Each finding is closed: its property refuses the attack, signs it against a mutant
 * without the fix (so the fix is what refuses it), and keeps the honest path. HOLDS properties pin attacks that failed.
 */
class FiReview2CoreSpec extends AnyPropSpec with FiStandardSharesSpecBase {
  import BondSpecBase._
  import ZcStandardSpecBase._

  private val kinds = Seq[Kind](TokenBond, ErgBond)

  /** The attacker's own share token: anyone can mint a supply of 2N+1. */
  private val attShare: ErgoId = ErgoId.create("a7" * 32)

  private def usdOf(ts: Seq[Token]): Long = ts.filter(_.id == SigUSD).map(_.amount).sum

  private def usdIn(s: SignedTransaction, i: Int): Long =
    s.getOutputsToSpend.get(i).getTokens.asScala.filter(_.getId == SigUSD).map(_.getValue.longValue).sum

  /** A ZC_Standard_FractionalVault holding the unit of `bondAsset`, as R5 of FiReviewBondSpec builds it. */
  private def zcHolding(ctx: BlockchainContext, created: Int): InputUTXO =
    inputAt(UTXO(scripts(ctx).fractionalVault, minBox + pullFee,
      Seq(Token(handShare, N + 1L), Token(bondAsset, 1L)),
      Seq(bytesValue(bondAsset.getBytes), longsValue(N, N, pullFee)), Some(created)), ctx, 30)

  /**
   * The attacker's box at a share-vault script `c`: a vault of the attacker's own share (N+1 reserve), holding
   * `value` and `tokens`. The attacker holds the other N shares, so a final claim releases it to them.
   */
  private def mirror(c: Contract, value: Long, tokens: Seq[Token]): UTXO =
    UTXO(c, value, Token(attShare, N + 1L) +: tokens, Seq(bytesValue(bondAsset.getBytes), longsValue(N, N, pullFee)))

  /** The attacker's final claim on its own mirror box: burns all 2N+1 of its shares, takes everything. */
  private def releaseMirror(ctx: BlockchainContext, box: InputUTXO, h: Int): SignedTransaction = {
    val shares = wallet(ctx, other(ctx), 13, h, Seq(Token(attShare, N)))
    ok(other(ctx), txAt(ctx, h, Seq(box.withCtxVar(1.toByte, int(0)), shares),
      Seq(tagged(otherTree(ctx), box.value, box.id, box.tokens.drop(1))), other(ctx).getAddress,
      burn = Seq(Token(attShare, 2L * N + 1L))))
  }

  /** The pool without its burn rule "the payout holds the home's tokens(0)": the pool these attacks signed against. */
  private var homeFreeC: Contract = null
  private def homeFree(ctx: BlockchainContext): Contract = FiStandardSpecBase.synchronized {
    if (homeFreeC == null) homeFreeC = FiStandardSpecBase.poolVariant(ctx,
      "\n                    (left > 0L || (p.tokens.size > 0 && p.tokens(0) == h.tokens(0))) &&" -> "")
    homeFreeC
  }

  // ═══ F1 (closed): AT A 3-UNIT BURN ANY OUTPUT AT THE HOME'S SCRIPT WAS "THE PAYOUT" ══════════
  // CouponVault: with left == 0, held(p) == min(left, 1L) is held(p) == 0, which every box at the home's
  // script satisfies; propositionBytes == home names a template, not the home's successor. A stranger who
  // ran an anyone-path burning the 3 units (a share vault's pull) added a second box at that template which
  // they owned, funded it from their own wallet, and the pools' coupons rode on top. Closed: at a burn the
  // payout must also hold the home's tokens(0) as its own tokens(0), a share vault's reserve, which the
  // share's supply lets no second box hold. Each attack below is refused, and signs against `homeFree`.

  for (kind <- kinds) {
    property(s"F1 ($kind) (closed): a stranger pulling a ZC fractional vault cannot take the uncollected pool via a mirror box at the vault script") {
      withCtx { ctx =>
        val w = world(ctx, kind)
        // A genuine coupon pool: the borrower pays coupon 0, then repays before maturity.
        val pay = ok(borrower(ctx), payTx(w, fiIn(w, fiBox(w)), w.due(0) - 10))
        val pool = spendOutput(pay, 1)
        val h = w.due(0) - 5
        val bv = spendOutput(ok(borrower(ctx), fiRepayTx(w, spendOutput(pay, 0), h)), 0)
        val frac = zcHolding(ctx, w.start).withCtxVar(0.toByte, int(0))
        // The ZC vault's own pull continuation, exactly as its rule asks.
        val cont = UTXO(scripts(ctx).fractionalVault, frac.value + bv.value - pullFee,
          Token(handShare, N + 1L) +: bv.tokens.drop(1),
          Seq(bytesValue(bondAsset.getBytes), longsValue(N, N, pullFee), ErgoValue.of(0.toByte)))
        val t = h + 1
        val feesOnly = wallet(ctx, other(ctx), 7, t)
        // Control: the pool beside the plain pull is refused (FiReviewBondSpec R5's shape); the plain pull signs.
        no(other(ctx), txAt(ctx, t, Seq(frac, bv, pool, feesOnly), Seq(cont), other(ctx).getAddress,
          burn = Seq(Token(bondAsset, 3L))))
        ok(other(ctx), txAt(ctx, t, Seq(frac, bv, feesOnly), Seq(cont), other(ctx).getAddress,
          burn = Seq(Token(bondAsset, 3L))))
        // The attack: the stranger fronts the settlement's SigUSD and the two boxes' nanoERG into a mirror at the
        // ZC vault script, holding the stranger's own share reserve first, to "carry" the home, settlement and pool.
        val usdFront = usdOf(bv.tokens)
        def attack(p: InputUTXO): Tx = {
          val mirrorOut = mirror(scripts(ctx).fractionalVault, frac.value + bv.value + p.value,
            if (w.erg) Seq.empty else Seq(Token(SigUSD, usdFront + usdOf(p.tokens))))
          val funds = wallet(ctx, other(ctx), 7, t,
            Token(attShare, N + 1L) +: (if (w.erg) Seq.empty else Seq(Token(SigUSD, usdFront))), value = slack)
          txAt(ctx, t, Seq(frac, bv, p, funds), Seq(cont, mirrorOut), other(ctx).getAddress,
            burn = Seq(Token(bondAsset, 3L)))
        }
        no(other(ctx), attack(pool))
        // Against the pool without the home-token rule the same attack signs, and the mirror releases the coupon.
        val s = ok(other(ctx), attack(poolIn(w, h - 10, pool = homeFree(ctx))))
        usdIn(s, 0) shouldBe usdOf(bv.tokens)
        val released = releaseMirror(ctx, spendOutput(s, 1), t + 1)
        if (!w.erg) usdIn(released, 0) shouldBe usdFront + w.coupon
        // Honest: an ERG pool pulled into the ZC vault's own continuation, with no pull fee kept, signs (as it did
        // against the pool without the rule).
        if (w.erg) {
          val whole = cont.addValue(pool.value + pullFee)
          ok(other(ctx), txAt(ctx, t, Seq(frac, bv, pool, feesOnly), Seq(whole), other(ctx).getAddress,
            burn = Seq(Token(bondAsset, 3L))))
          ok(other(ctx), txAt(ctx, t, Seq(frac, bv, poolIn(w, h - 10, pool = homeFree(ctx)), feesOnly), Seq(whole),
            other(ctx).getAddress, burn = Seq(Token(bondAsset, 3L))))
        }
      }
    }
  }

  property("F1b (closed): an FI ERG share vault holding a SigUSD bond's unit: a stranger's pull with the pool and a mirror cannot take the SigUSD coupon") {
    withCtx { ctx =>
      val w = world(ctx) // SigUSD bond
      val pay = ok(borrower(ctx), payTx(w, fiIn(w, fiBox(w)), w.due(0) - 10))
      val pool = spendOutput(pay, 1)
      val h = w.due(0) - 5
      val bv = spendOutput(ok(borrower(ctx), fiRepayTx(w, spendOutput(pay, 0), h)), 0)
      val t = h + PullWait
      val usdFront = usdOf(bv.tokens)
      /** The pull of the ERG vault script `c` holding the unit, beside the pool `p` and the stranger's mirror. */
      def attack(c: Contract, p: InputUTXO): Tx = {
        val v = vaultBox(ctx, minBox + pullFee, Seq(Token(handShare, N + 1L), Token(bondAsset, 1L)), w.start, c = c)
          .withCtxVar(0.toByte, int(0))
        // The FI vault's own rule with a pool in: value of all parts less the pull fee; tokens: reserve, then the
        // proceeds (the ERG vault has no coupon entry), so the pool's SigUSD cannot enter it.
        val cont = UTXO(c, v.value - pullFee + bv.value + p.value, Token(handShare, N + 1L) +: bv.tokens.drop(1),
          Seq(bytesValue(bondAsset.getBytes), longsValue(N, N, pullFee), ErgoValue.of(0.toByte)))
        val mirrorOut = mirror(c, v.value + bv.value + p.value, Seq(Token(SigUSD, usdFront + w.coupon)))
        val funds = wallet(ctx, other(ctx), 7, t, Seq(Token(attShare, N + 1L), Token(SigUSD, usdFront)), value = slack)
        txAt(ctx, t, Seq(v, bv, p, funds), Seq(cont, mirrorOut), other(ctx).getAddress, burn = Seq(Token(bondAsset, 3L)))
      }
      no(other(ctx), attack(shares(ctx).ergVault, pool))
      // The pool without the home-token rule (and the ERG vault pinning it) signs the same attack.
      val free = homeFree(ctx)
      val s = ok(other(ctx), attack(FiStandardSharesSpecBase.vaultFor(ctx, free, Array.emptyByteArray), poolIn(w, h - 10, pool = free)))
      usdIn(releaseMirror(ctx, spendOutput(s, 1), t + 1), 0) shouldBe usdFront + w.coupon
    }
  }

  // ═══ F2 (closed): WITH POOLS IN THE PULL, THE PULLER KEPT THE PULL FEE VIA A MIRROR ═══════════════
  // The puller of a share vault keeps the pull fee with pools in as without them (plus the cut), so the mirror
  // that once smuggled the fee out gains nothing: the successor may be short of the fee and the cut, no more,
  // mirror or not, and that is the vault's own sum.

  property("F2 (closed): an FI share-vault pull with a pool keeps the pull fee and the cut; a mirror box takes nothing more") {
    withCtx { ctx =>
      val w = world(ctx)
      val m = minted(w, w.start, w.start + 1)
      val pay = ok(borrower(ctx), payTx(w, fiIn(w, fiBox(w)), w.due(0) - 10))
      val pool = spendOutput(pay, 1)
      val h = w.due(0) - 5
      val src = spendOutput(ok(borrower(ctx), fiRepayTx(w, spendOutput(pay, 0), h)), 0)
      val t = h + PullWait
      // Honest: the pull with the pool keeps the fee and the cut; one nanoERG more is refused.
      ok(executor(ctx), pullTx(w, m.vault, src, t, pools = Seq(pool)))
      no(executor(ctx), pullTx(w, m.vault, src, t, pools = Seq(pool), out = pulledBox(m.vault, src, keep = pullFee + 1L, pools = Seq(pool))))
      val usdFront = usdOf(m.vault.tokens) + usdOf(src.tokens)
      def attack(vault: InputUTXO, p: InputUTXO, extra: Long): Tx = {
        val kept = pulledBox(vault, src, keep = pullFee + extra, pools = Seq(p))
        val mirrorOut = mirror(vault.contract, vault.value + src.value + p.value, Seq(Token(SigUSD, usdFront + w.coupon)))
        val funds = wallet(ctx, executor(ctx), 7, t, Seq(Token(attShare, N + 1L), Token(SigUSD, usdFront + w.coupon)), value = slack)
        txAt(ctx, t, Seq(vault.withCtxVar(0.toByte, int(0)), src, p, funds),
          Seq(kept, mirrorOut), executor(ctx).getAddress, burn = Seq(Token(bondAsset, 3L)))
      }
      // With a mirror beside it the successor may still keep the fee and the cut, and not a nanoERG more: the
      // vault's own sum refuses that, even beside the pool without the home-token rule (and the vault pinning it).
      ok(executor(ctx), attack(m.vault, pool, 0L))
      no(executor(ctx), attack(m.vault, pool, 1L))
      val free = homeFree(ctx)
      val freeVault = vaultBox(ctx, m.vault.value, m.vault.tokens, w.start + 1,
        c = FiStandardSharesSpecBase.vaultFor(ctx, free, SigUSD.getBytes))
      no(executor(ctx), attack(freeVault, poolIn(w, h - 10, pool = free), 1L))
      val s = ok(executor(ctx), attack(freeVault, poolIn(w, h - 10, pool = free), 0L))
      s.getOutputsToSpend.get(0).getValue.longValue shouldBe freeVault.value + src.value + pool.value - pullFee - cut(pool.value)
    }
  }

  // ═══ F3 (closed): TEST QUALITY: A PAY NEGATIVE REFUSED BY TWO CLAUSES, ATTRIBUTED TO ONE ═══════════
  // FiStandardPoolSpec line 155: "the coupon in a fresh box and the spent pools re-created whole in a
  // second box: ... the box var 5 names must carry them under the coupon" - unpaired. The bond without
  // the Pay sum still refuses it (the "only output" rule does), so the line does not pin the clause it names.

  property("F3 (closed; FiStandardPoolSpec now pairs that line with both mutants): the line is refused with the Pay sum removed; only removing both clauses signs it") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = w.due(1) - 10
      val p1 = poolIn(w, h - 100, index = 1)
      val p2 = poolIn(w, h - 90, index = 2, amount = 3L * w.coupon, erg = 3L * minBox)
      val pool = fi(ctx).couponVault
      val sumOff = FiStandardSpecBase.bondVariant(ctx, SigUSD.getBytes, pool, "a + inRepayment(b)" -> "a")
      val bothOff = FiStandardSpecBase.bondVariant(ctx, SigUSD.getBytes, pool, "a + inRepayment(b)" -> "a",
        "OUTPUTS.filter(pool).size == 1" -> "true")
      val whole = couponOut(w, amount = usdOf(p1.tokens) + usdOf(p2.tokens), erg = p1.value + p2.value)
      def tx(c: Contract): Tx = payTx(w, fiIn(w, fiBox(w, paid = 1L).setContract(c)), h, extraIns = Seq(p1, p2),
        extraOuts = Seq(whole))
      no(borrower(ctx), tx(w.bond))
      no(borrower(ctx), tx(sumOff))
      ok(borrower(ctx), tx(bothOff))
    }
  }

  // ═══ HOLDS ════════════════════════════════════════════════════════════════

  /** The bond's own schedule formulas (lines 169-177, 364-366), for a model check. */
  private def model(start: Long, off: Long, step: Long, g: Long, count: Long) = new {
    val firstDue: Long = start + off
    def missed(h: Long): Long = if (h < firstDue + g) 0L else math.min(count, (h - firstDue - g) / step + 1L)
    def liquidatable(h: Long, paid: Long): Boolean = paid < missed(h)
    def quiet(h: Long, paid: Long): Boolean = {
      val miss = firstDue + paid * step + g
      paid < count && h < miss && h >= miss - math.min(720L, g / 2L)
    }
    def cp(k: Long): Long = firstDue + k * step
  }

  property("HOLDS: with grace <= period, every checkpoint's own due height is open to Record whatever the paid count, unless liquidatable") {
    // Model check over the contract's formulas, then the contract at the edges.
    var counter = 0
    for (g <- 0L to 30L; step <- 1L to 30L; off <- 0L to 3L; count <- 1L to 4L; j <- 0L until count; paid <- 0L to count) {
      val q = model(1000L, off, step, g, count)
      val h = q.cp(j)
      val open = q.liquidatable(h, paid) || !q.quiet(h, paid)
      if (g <= step) withClue(s"g=$g step=$step off=$off count=$count j=$j paid=$paid")(open shouldBe true)
      else if (!open) counter += 1
    }
    counter should be > 0 // grace > period does shut a checkpoint's due height for some paid count
    withCtx { ctx =>
      // grace == period == 6 (Q = 3), first-due offset 0, count 3, period far below 720.
      val base = world(ctx)
      val w = base.copy(terms = base.terms.updated(9, 0L).updated(10, 6L).updated(11, 6L))
      def cp(j: Int): Int = w.start + 6 * j
      for (j <- 0 until 3; p <- j to 3)
        withClue(s"j=$j paid=$p")(ok(executor(ctx), recordTx(w, fiIn(w, fiBox(w, paid = p.toLong)), cp(j), j.toLong)))
      // One coupon behind at a later checkpoint's due height: liquidatable instead (grace == period).
      for (j <- 1 until 3) ok(executor(ctx), fiLiquidateTx(w, fiIn(w, fiBox(w, paid = (j - 1).toLong)), cp(j)))
      // count 1, offset 0: the start height itself records.
      val one = w.copy(terms = w.terms.updated(8, 1L))
      ok(executor(ctx), recordTx(one, fiIn(one, fiBox(one)), one.start, 0L))
    }
  }

  property("HOLDS: while the unit survives (left 1) the mirror is no payout: a ZC vault refresh beside a token pool and a mirror is refused") {
    withCtx { ctx =>
      val w = world(ctx)
      val c = ctx.getHeight
      val h = c + RefreshAge + 1
      val frac = zcHolding(ctx, c).withCtxVar(9.toByte, int(0))
      val next = frac.toUTXO.setCreationHeight(h)
      val pool = poolIn(w, h - 50)
      val mirrorOut = mirror(scripts(ctx).fractionalVault, frac.value + pool.value, Seq(Token(SigUSD, w.coupon)))
      no(other(ctx), txAt(ctx, h, Seq(frac, pool, wallet(ctx, other(ctx), 7, h, Seq(Token(attShare, N + 1L)), value = slack)),
        Seq(next, mirrorOut), other(ctx).getAddress))
      ok(other(ctx), txAt(ctx, h, Seq(frac, wallet(ctx, other(ctx), 7, h)), Seq(next), other(ctx).getAddress))
    }
  }
}
