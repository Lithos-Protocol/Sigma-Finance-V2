package org.lithosprotocol.sigmafi
package specs.bonds

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

/**
 * Round-3 audit items, driven through the real contracts: the Record bounty outside the policy's cover
 * (F-026 remainder, price and Refresh routes), the first coupon's fill-time floor (F-006 remainder), padding
 * on the Bond's settlement, return box and pool (C), and the CouponVault's top-up id test and old key homes (D).
 */
class FiRound3Spec extends AnyPropSpec with FiStandardMarketSpecBase with FiStandardSharesSpecBase {
  import BondSpecBase._
  import ZcStandardSpecBase._

  /** A SigUSD loan of repayment R < 800 raw lends R - 10 raw; otherwise the specs' 600. */
  override protected def loanTokens(w: W): Seq[Token] =
    if (w.erg) Seq.empty else Seq(Token(SigUSD, if (w.terms(0) < 800L) w.terms(0) - 10L else 600L))

  private val Bounty500: Long = FiStandardSpecBase.RecordBounty

  // ─── worlds ───────────────────────────────────────────────────────────────

  /**
   * ERG market: repayment 1 ERG, principal 0.9 ERG (so 0.9 + the 0.1 ERG fee clears a 0 bps offer), first
   * checkpoint at the start, grace 30, ratio `ratio`; `erg` nanoERG and `usd` raw SigUSD of collateral.
   */
  private def ergWorld(ctx: BlockchainContext, erg: Long, usd: Long, ratio: Long, life: Long = 2000L): W =
    withTerms(world(ctx, ErgBond, life = life, collateralErg = erg, collateral = if (usd > 0L) Seq(Token(SigUSD, usd)) else Seq.empty),
      0 -> 1000000000L, 1 -> 900000000L, 2 -> 1000000000L, 9 -> 0L, 11 -> 30L, 12 -> ratio)

  /** SigUSD market: repayment 200 raw ($2), principal 190 raw, `erg` nanoERG of collateral, no token collateral. */
  private def usdWorld(ctx: BlockchainContext, erg: Long, ratio: Long): W =
    withTerms(world(ctx, TokenBond, collateralErg = erg, collateral = Seq.empty), 0 -> 200L, 9 -> 0L, 11 -> 30L, 12 -> ratio)

  /** The lender's offer at a 130% floor under `policy`; a SigUSD offer's fee and ERG budgets fit a 190-unit fill. */
  private def offerOf(w: W, policy: Contract, maxBlocks: Long = 100000L): UTXO = {
    val t = OTerms(floorBps = 13000L, maxBlocks = maxBlocks, rewardBudget = 600000000L)
    val o = fiOffer(w, t, policy = policy)
    if (w.erg) o else o.withRegNum(5, longsValue(offerTerms(w.ctx, TokenBond, t).updated(12, 2000000000L): _*))
  }

  /** What the offer gave up in a fill, in nanoERG at `price` (raw SigUSD counted at that price). */
  private def outlay(w: W, offer: UTXO, fill: SignedTransaction, next: Int, price: Long): Long = {
    val cont = fill.getOutputsToSpend.get(next)
    val erg = offer.value - cont.getValue
    val usdIn = offer.tokens.filter(_.id == SigUSD).map(_.amount).sum
    val usdOut = (0 until cont.getTokens.size).map(cont.getTokens.get(_)).filter(_.getId == SigUSD).map(_.getValue.longValue).sum
    erg + (usdIn - usdOut) * price / 100L
  }

  private def worth(u: InputUTXO, price: Long): Long =
    u.value + u.tokens.filter(_.id == SigUSD).map(_.amount).sum * price / 100L

  // ─── mutants: each takes out one round-3 term ─────────────────────────────

  private val cache = scala.collection.mutable.Map.empty[String, Contract]
  private def once(key: String)(c: => Contract): Contract = cache.getOrElseUpdate(key, c)

  /** The policy without the Record allowance (`takeable`) in its cover test. */
  private def noTakeable(w: W): Contract = once(s"policy|${w.erg}")(FiStandardMarketSpecBase.policyFor(w.ctx, repaymentBytes(w),
    "(if (getVar[Int](2).isDefined) takeable else lender(0) + takeable)" -> "(if (getVar[Int](2).isDefined) 0L else lender(0))"))

  private def bondWithout(w: W, edit: (String, String)): Contract = once(s"bond|${w.erg}|${edit._1}")(
    FiStandardSpecBase.bondVariant(w.ctx, repaymentBytes(w), fi(w.ctx).couponVault, edit))
  private val noSettlementR6 = " &&\n                    settlement.R6[Coll[Byte]].isDefined == false" -> ""
  private val noReturnSize = "returnBox.tokens.size <= collateral.size &&" -> "true &&"
  private val noPoolSize = "couponBox.tokens.size <= 1 &&" -> "true &&"

  private def poolWithout(ctx: BlockchainContext, edits: (String, String)*): Contract =
    once(s"pool|${edits.mkString}")(FiStandardSpecBase.poolVariant(ctx, edits: _*))
  private val noIdTest =
    "p.tokens.forall { (t: (Coll[Byte], Long)) => SELF.tokens.exists { (u: (Coll[Byte], Long)) => u._1 == t._1 } } &&" -> "true &&"
  /** The pool's home rule as round 2 left it: the age on a canonical key home only. */
  private val round2Homes =
    "h.creationInfo._1 > HEIGHT - 1051200 && (isKey ||" -> "((isKey && h.creationInfo._1 > HEIGHT - 1051200) ||"

  // ═══ A. F-026 REMAINDER: THE RECORD BOUNTY OUTSIDE THE COVER ══════════════

  /** A new-bond fill of `w` from the 130% offer under `policy`, the standard guard, one oracle box at `price`. */
  private def floorFill(w: W, policy: Contract, price: Long, maxBlocks: Long = 100000L): Tx = {
    val f = fillOf(w, guard = fiMarket(w).guard)
    offerFillTx(f, offerOf(w, policy, maxBlocks), policy = policy, data = Seq(oracle(w.ctx, price, f.height)))
  }

  /**
   * The price route: the borrower (also the batcher) fills the offer at zero margin with the standard guard,
   * a new oracle box moves the price against the bond, he records checkpoint 0 keeping 0.5 ERG, and
   * liquidates at coupon 0's miss, keeping the reserve. Returns (outlay, recovery) in nanoERG at the new price.
   */
  private def priceRoute(w: W, pFill: Long, pRecord: Long, policy: Contract): (Long, Long) = {
    val ctx = w.ctx
    val f = fillOf(w, guard = fiMarket(w).guard)
    val offer = offerOf(w, policy)
    val fill = ok(executor(ctx), offerFillTx(f, offer, policy = policy, data = Seq(oracle(ctx, pFill, f.height))))
    val bond = spendOutput(fill, 1)
    val h = f.height + 1
    val rec = ok(executor(ctx), recordTx(w, bond, h, 0L, next = recorded(w, bond, h, 0L).subValue(Bounty500),
      data = Seq(oracle(ctx, pRecord, h))))
    val liq = ok(executor(ctx), fiLiquidateTx(w, spendOutput(rec, 0), w.start + 30))
    val paid = outlay(w, offer, fill, 3, pRecord)
    val got = worth(spendOutput(liq, 0), pRecord)
    println(s"R3-A price|${w.kind}|ratio=${w.terms(12)}|fill price=$pFill|record price=$pRecord|lender outlay=$paid|" +
      s"lender recovers=$got|lender net=${got + minBox - paid}")
    (paid, got)
  }

  // Before the fix (recorded in the round-3 report) all three signed every step under the real policy:
  // ratio 13000: outlay 1.101 ERG, recovery 0.8 ERG; ratio 14000: 0.9 ERG; SigUSD mirror: outlay 1.166, recovery 0.9.
  property("F-026 price route: a zero-margin bond whose cover moves with the price is refused; without the allowance the attack signs") {
    withCtx { ctx =>
      // Ratio = floor 13000: the reserve leaves 0.1 ERG of margin, so the fall must wipe 0.1 ERG of cover
      // (12.5% of the 0.8 ERG of SigUSD here). Ratio 14000 = floor x R + reserve: one tick suffices.
      val routes = Seq((ergWorld(ctx, 600000000L, 200L, 13000L), 400000000L, 349999999L),
        (ergWorld(ctx, 600000000L, 200L, 14000L), 400000000L, 399999999L),
        (usdWorld(ctx, 1400000000L, 14000L), 500000000L, 500000001L))
      for ((w, pFill, pRecord) <- routes) {
        withClue(s"${w.kind} ratio ${w.terms(12)}: ")(no(executor(ctx), floorFill(w, fiMarket(w).policy, pFill)))
        val (paid, got) = priceRoute(w, pFill, pRecord, noTakeable(w))
        got should be < paid
      }
    }
  }

  /** ERG-only, life 600000, one coupon due at start + 525700. */
  private def refreshWorld(ctx: BlockchainContext, erg: Long, ratio: Long = 14000L): W =
    withTerms(ergWorld(ctx, erg, 0L, ratio, life = 600000L), 8 -> 1L, 9 -> 525700L)

  /** The bond with the Refresh bounty line as it stood before round 4 (0.01 ERG at any height). */
  private def oldRefresh(w: W): Contract = bondWithout(w,
    "(if (refresh) (if (height >= maturity) 10000000L else 0L) else CONST_RECORD_BOUNTY)" -> "(if (refresh) 10000000L else CONST_RECORD_BOUNTY)")

  // Before round 3 this signed every step under the real policy: outlay 1.101 ERG, recovery 0.89 ERG. Round 3 refused
  // the fill (an allowance for bonds living more than 526320 blocks); round 4 closes it in the Bond instead: no
  // Refresh takes nanoERG before maturity, so the policy dropped that arm and accepts the fill again.
  property("F-026 Refresh route: the zero-margin fill of a long ERG-only bond is accepted, and no pre-maturity Refresh can tip it") {
    withCtx { ctx =>
      val w = refreshWorld(ctx, 1400000000L)
      val policy = fiMarket(w).policy
      val f = fillOf(w, guard = fiMarket(w).guard)
      val offer = offerOf(w, policy, maxBlocks = 1000000L)
      val fill = ok(executor(ctx), offerFillTx(f, offer, policy = policy, data = Seq(oracle(ctx, w.calm, f.height))))
      val bond = spendOutput(fill, 1)
      val due = w.start + 525700
      // At zero margin it is not recordable. The 0.01 ERG refresh bounty that tipped it is refused before maturity;
      // the value-preserving Refresh signs and leaves it unrecordable.
      no(executor(ctx), recordTx(w, bond, due, 0L))
      val rh = w.start + 525601
      no(executor(ctx), refreshTx(w, bond, rh, next = bond.toUTXO.subValue(Bounty).setCreationHeight(rh - 60)))
      val kept = spendOutput(ok(executor(ctx), refreshTx(w, bond, rh)), 0)
      kept.value shouldBe bond.value
      no(executor(ctx), recordTx(w, kept, due, 0L))
      // Under the round-3 Bond line (the bounty at any height) the chain still signs, at the lender's cost.
      val old = inputAt(bond.toUTXO.setContract(oldRefresh(w)), ctx, 0, "cd" * 32)
      val refreshed = spendOutput(ok(executor(ctx), refreshTx(w, old, rh, next = old.toUTXO.subValue(Bounty).setCreationHeight(rh - 60))), 0)
      val rec = ok(executor(ctx), recordTx(w, refreshed, due, 0L, next = recorded(w, refreshed, due, 0L).subValue(Bounty500)))
      val liq = ok(executor(ctx), fiLiquidateTx(w, spendOutput(rec, 0), due + 30))
      val paid = outlay(w, offer, fill, 3, w.calm)
      val got = spendOutput(liq, 0).value
      println(s"R3-A refresh|lender outlay=$paid|lender recovers=$got")
      got should be < paid
    }
  }

  property("F-026 allowance: who needs it and how much, at one nanoERG either side, in both markets") {
    withCtx { ctx =>
      def fills(w: W, price: Long, maxBlocks: Long = 100000L): Unit = {
        val s = ok(executor(ctx), floorFill(w, fiMarket(w).policy, price, maxBlocks))
        println(s"R3-A cost|${w.kind}|offer fill under the real policy and guard=${s.getCost}")
      }
      def refuses(w: W, price: Long, maxBlocks: Long = 100000L): Unit = no(executor(ctx), floorFill(w, fiMarket(w).policy, price, maxBlocks))
      // Nothing to take: nanoERG at the 0.001 ERG minimum beside SigUSD (takeable 0) fills at floor x R + reserve.
      fills(ergWorld(ctx, 1000000L, 350L, 13000L), 400000000L)
      // An ERG-for-ERG bond of 2000 blocks can never be recorded: floor x R + reserve exactly fills, a nanoERG less does not.
      fills(ergWorld(ctx, 1400000000L, 0L, 13000L), 600000000L)
      refuses(ergWorld(ctx, 1399999999L, 0L, 13000L), 600000000L)
      // The same bond living 600000 blocks: no pre-maturity Refresh can tip it (round 4), so it needs no allowance
      // either (round 3 asked 0.51 ERG more): floor x R + reserve exactly fills, a nanoERG less does not.
      fills(refreshWorld(ctx, 1400000000L, 13000L), 600000000L, 1000000L)
      refuses(refreshWorld(ctx, 1399999999L, 13000L), 600000000L, 1000000L)
      // ERG bond holding SigUSD: 0.5 ERG more. 200 raw SigUSD at 0.4 ERG/USD and 1.1 ERG cover 130% + 0.1 + 0.5.
      fills(ergWorld(ctx, 1100000000L, 200L, 13000L), 400000000L)
      refuses(ergWorld(ctx, 1099999999L, 200L, 13000L), 400000000L)
      // SigUSD bond with ERG collateral: $2 at 0.5 ERG/USD is 1 ERG; 1.3 + 0.1 + 0.5 = 1.9 ERG.
      fills(usdWorld(ctx, 1900000000L, 13000L), 500000000L)
      refuses(usdWorld(ctx, 1899999999L, 13000L), 500000000L)
      ok(executor(ctx), floorFill(usdWorld(ctx, 1400000000L, 13000L), noTakeable(usdWorld(ctx, 1400000000L, 13000L)), 500000000L))
    }
  }

  property("F-026 allowance, existing bond: the offer buys a price-dependent unit only above floor x R + 0.5 ERG") {
    withCtx { ctx =>
      def buy(erg: Long, policy: W => Contract): Tx = {
        val w = ergWorld(ctx, erg, 200L, 13000L)
        val f = fillOf(w, guard = fiMarket(w).guard)
        val state = spendOutput(ok(lender(ctx), fiFillTx(f, data = Seq(oracle(ctx, 400000000L, f.height)))), 1)
        val h = f.height + 1
        val p = policy(w)
        buyTx(w, f.orderIn.bytes, state, offerOf(w, p), 850000000L, h, policy = p, data = Seq(oracle(ctx, 400000000L, h)))
      }
      // No reserve on this path: 1.3 ERG + 0.5 ERG of cover, 1 ERG of nanoERG beside the $2 of SigUSD.
      ok(executor(ctx), buy(1000000000L, w => fiMarket(w).policy))
      no(executor(ctx), buy(999999999L, w => fiMarket(w).policy))
      no(executor(ctx), buy(600000000L, w => fiMarket(w).policy))
      ok(executor(ctx), buy(600000000L, noTakeable))
    }
  }

  // ═══ B. F-006 REMAINDER: THE FIRST COUPON'S FILL-TIME FLOOR ══════════════

  // Before the fix this signed: a coupon order with first offset 8 and grace 2, started 8 back, filled and
  // liquidated 2 blocks later.
  property("F-006 first coupon: an order whose first coupon is missed under 30 blocks from the fill is refused; the floor as it stood fills it") {
    withCtx { ctx =>
      val h = fillHeight(world(ctx))
      val w = withTerms(startedAt(world(ctx), h - 8), 12 -> 0L, 9 -> 8L, 11 -> 2L)
      no(lender(ctx), fiFillTx(fillOf(w)))
      val loose = FiStandardMarketSpecBase.orderFor(ctx, w.bond, repaymentBytes(w),
        "firstMissed >= HEIGHT.toLong + 30L" -> "firstMissed > HEIGHT.toLong + 1L")
      val bond = spendOutput(ok(lender(ctx), fiFillTx(fillOf(w, orderScript = loose))), 1)
      // What the floor prevents: that bond is liquidatable 2 blocks after its fill.
      no(executor(ctx), fiLiquidateTx(w, bond, h + 1))
      ok(executor(ctx), fiLiquidateTx(w, bond, h + 2))
      // The honest boundary: missed 30 blocks after the fill.
      ok(lender(ctx), fiFillTx(fillOf(withTerm(w, 11, 30L))))
      no(lender(ctx), fiFillTx(fillOf(withTerm(w, 11, 29L))))
    }
  }

  // ═══ C. PADDING ON THE BOND'S SETTLEMENT, RETURN BOX AND POOL ═════════════

  /** `u` with a 1000-byte register appended. */
  private def padded(u: UTXO): UTXO = u.setRegs(u.registers :+ bytesValue(Array.fill(1000)(7.toByte)): _*)

  // Before the fix all four signed.
  property("C padding: a settlement with R6, a token return box repeating the repayment token, a pool repeating it are refused") {
    withCtx { ctx =>
      val w = world(ctx, TokenBond)
      def at(c: Contract): InputUTXO = fiIn(w, if (c == null) fiBox(w) else fiBox(w).setContract(c))
      val h = w.start + 100
      // Settlement R6: liquidation and repayment.
      no(executor(ctx), fiLiquidateTx(w, at(null), w.maturity.toInt, out = padded(proceeds(w, at(null)))))
      ok(executor(ctx), fiLiquidateTx(w, at(null), w.maturity.toInt))
      val loose = bondWithout(w, noSettlementR6)
      ok(executor(ctx), fiLiquidateTx(w, at(loose), w.maturity.toInt, out = padded(proceeds(w, at(loose)))))
      no(borrower(ctx), fiRepayTx(w, at(null), h, settle = padded(repaid(w, at(null)))))
      ok(borrower(ctx), fiRepayTx(w, at(loose), h, settle = padded(repaid(w, at(loose)))))
      // The return box with the repayment token in two more entries (appkit counts a repeated id once; the burn
      // line only balances its own change).
      def repay(c: Contract): Tx = {
        val b = at(c)
        txAt(ctx, h, Seq(vars(b, Seq(1 -> int(0), 2 -> int(1))), wallet(ctx, borrower(ctx), 6, h, borrowerFunds)),
          Seq(repaid(w, b), returned(w, b).setTokens(b.tokens.drop(1) ++ Seq(Token(SigUSD, 1L), Token(SigUSD, 1L)): _*)),
          borrower(ctx).getAddress, burn = Seq(Token(SigUSD, 1L)))
      }
      no(borrower(ctx), repay(null))
      ok(borrower(ctx), repay(bondWithout(w, noReturnSize)))
      ok(borrower(ctx), fiRepayTx(w, at(null), h))
      // Pay's pool with the repayment token in two entries.
      def pay(c: Contract): Tx = payTx(w, at(c), w.due(0) - 10,
        coupon = couponOut(w).setTokens(Token(SigUSD, w.coupon - 1L), Token(SigUSD, 1L)), burn = Seq(Token(SigUSD, w.coupon - 1L)))
      no(borrower(ctx), pay(null))
      ok(borrower(ctx), pay(bondWithout(w, noPoolSize)))
    }
  }

  // ═══ D1. THE TOP-UP'S TOKEN ID TEST ═══════════════════════════════════════

  private val junkZ: ErgoId = ErgoId.create("c3" * 32)

  /** A 2-unit box at an always-true script: only the pool's own rule can refuse a spend beside it. */
  private def bare2(ctx: BlockchainContext, created: Int): InputUTXO =
    inputAt(UTXO(Contract.SIGMA_TRUE, minBox, Seq(Token(bondAsset, 2L)), Seq.empty, Some(created)), ctx, 35)

  /**
   * A top-up of `pool` beside the 2 units, re-creating it at `c` holding (SigUSD, coupon) and `extra`. appkit
   * reads a repeated entry once: the executor's box carries 19 more SigUSD so its change sums balance (the real
   * transaction leaves those 19 out of the outputs).
   */
  private def topUp(w: W, pool: InputUTXO, c: Contract, extra: Seq[Token], h: Int): Tx = {
    val ctx = w.ctx
    val z = wallet(ctx, executor(ctx), 7, h, extra ++ Seq(Token(SigUSD, w.coupon - 1L)))
    txAt(ctx, h, Seq(bare2(ctx, h), pool, z),
      Seq(couponOut(w).setContract(c).setTokens(Token(SigUSD, w.coupon) +: extra: _*), UTXO(Contract.SIGMA_TRUE, minBox, Seq(Token(bondAsset, 2L)))),
      executor(ctx).getAddress)
  }

  // Before the fix: the Pay made the two-entry pool, the top-up swapped in the foreign token, and no share
  // vault could absorb the result.
  property("D1: a two-entry pool cannot be made, and a top-up cannot swap a foreign token into a pool") {
    withCtx { ctx =>
      val w = world(ctx, TokenBond)
      val h = w.due(0) - 10
      val twoEntries = couponOut(w).setTokens(Token(SigUSD, w.coupon - 1L), Token(SigUSD, 1L))
      no(borrower(ctx), payTx(w, fiIn(w, fiBox(w)), h, coupon = twoEntries, burn = Seq(Token(SigUSD, w.coupon - 1L))))
      // Under the bond without its size term the pool exists; the pool's id test alone then refuses the swap.
      val pay = ok(borrower(ctx), payTx(w, fiIn(w, fiBox(w).setContract(bondWithout(w, noPoolSize))), h, coupon = twoEntries,
        burn = Seq(Token(SigUSD, w.coupon - 1L))))
      val pool = spendOutput(pay, 1)
      no(executor(ctx), topUp(w, pool, fi(ctx).couponVault, Seq(Token(junkZ, 1L)), h + 1))
      // The honest top-up of the same pool, into one entry, signs.
      val honest = ok(executor(ctx), topUp(w, pool, fi(ctx).couponVault, Seq.empty, h + 1))
      println(s"R3-D1|honest top-up cost=${honest.getCost}")
      // The pool without the id test takes the swap.
      val free = poolWithout(ctx, noIdTest)
      val freePool = inputAt(twoEntries.setContract(free).setCreationHeight(h), ctx, 1, "ab" * 32)
      ok(executor(ctx), topUp(w, freePool, free, Seq(Token(junkZ, 1L)), h + 1))
      // What the swap does: a pool holding the foreign token is absorbed by no share vault.
      val poisoned = inputAt(couponOut(w).setTokens(Token(SigUSD, w.coupon), Token(junkZ, 1L)).setCreationHeight(h), ctx, 1, "ab" * 32)
      val vault = vaultBox(ctx, minBox + pullFee, Seq(Token(handShare, N + 1L), Token(bondAsset, 1L)), h)
      no(executor(ctx), absorbTx(w, vault, Seq(poisoned), h + 2))
      no(executor(ctx), absorbTx(w, vault, Seq(poisoned), h + 2,
        next = absorbedAll(vault, Seq(poisoned)).setTokens(absorbedAll(vault, Seq(poisoned)).tokens :+ Token(junkZ, 1L): _*)))
      val clean = inputAt(couponOut(w).setCreationHeight(h), ctx, 1, "ab" * 32)
      ok(executor(ctx), absorbTx(w, vault, Seq(clean), h + 2))
    }
  }

  // ═══ D2. THE AGE ON EVERY HOME ════════════════════════════════════════════

  /** A key home that is not the canonical 36-byte P2PK tree: `{ pk }` compiled for the lender's key (37 bytes). */
  private def compiledKey(ctx: BlockchainContext): Contract = once("compiledKey")(FiStandardSpecBase.synchronized {
    Contract.fromErgoContract(ctx.compileContract(ConstantsBuilder.create().item("pk", lender(ctx).getAddress.getPublicKey).build(), "{ pk }"))
  })

  /** A 2-of-2 home: the lender's and `other`'s keys. */
  private def twoOfTwo(ctx: BlockchainContext): Contract = once("twoOfTwo")(FiStandardSpecBase.synchronized {
    Contract.fromErgoContract(ctx.compileContract(ConstantsBuilder.create()
      .item("pkA", lender(ctx).getAddress.getPublicKey).item("pkB", other(ctx).getAddress.getPublicKey).build(), "{ pkA && pkB }"))
  })

  private def both(ctx: BlockchainContext): ErgoProver = ctx.newProverBuilder()
    .withDLogSecret(java.math.BigInteger.valueOf(2002L)).withDLogSecret(java.math.BigInteger.valueOf(4004L)).build()

  /**
   * The unit at `home`, `age` blocks old, collects an ERG pool into its re-created box, the cut paid to a third
   * party (what a rent spender of an old box would do; here the home's keys sign in its place).
   */
  private def sweep(w: W, home: Contract, age: Int, pool: Contract): Tx = {
    val ctx = w.ctx
    val h = 1200000
    val p = poolIn(w, w.start, index = 1, pool = pool)
    val u = inputAt(UTXO(home, minBox * 5L, Seq(Token(bondAsset, 1L)), Seq.empty, Some(h - age)), ctx, 8)
    val fees = inputAt(UTXO(Contract.SIGMA_TRUE, 10000000L, Seq.empty, Seq.empty, Some(h - age)), ctx, 37)
    txAt(ctx, h, Seq(p, u, fees),
      Seq(UTXO(home, u.value + p.value - cut(p.value), u.tokens), UTXO(otherTree(ctx), cut(p.value))), other(ctx).getAddress)
  }

  // Before the fix the old non-canonical home's sweep signed.
  property("D2: a home of any kind 1051200 or more blocks old collects nothing; young key, compiled-key and multisig homes collect") {
    withCtx { ctx =>
      val w = world(ctx, ErgBond)
      val cv = fi(ctx).couponVault
      val key = compiledKey(ctx)
      key.propBytes.length shouldBe 37
      no(lender(ctx), sweep(w, key, 1051200, cv))
      ok(lender(ctx), sweep(w, key, 1051200, poolWithout(ctx, round2Homes)))
      ok(lender(ctx), sweep(w, key, 1051199, cv))
      no(both(ctx), sweep(w, twoOfTwo(ctx), 1051200, cv))
      ok(both(ctx), sweep(w, twoOfTwo(ctx), 1051199, cv))
      ok(both(ctx), sweep(w, twoOfTwo(ctx), 1051200, poolWithout(ctx, round2Homes)))
      // The canonical key home: its own collect while young.
      ok(lender(ctx), collectTx(w, Seq(poolIn(w, w.start, index = 1)), Seq(unit(ctx, 1200000 - 1051199)), 1200000))
    }
  }

  property("D2: an FI share vault 1051200 or more blocks old absorbs no pool until refreshed; its pull without pools still passes") {
    withCtx { ctx =>
      val w = world(ctx, TokenBond)
      val h = 1200000
      val vault = vaultBox(ctx, 200000000L, Seq(Token(handShare, N + 1L), Token(bondAsset, 1L)), h - 1051200)
      val pool = poolIn(w, h - 100)
      no(executor(ctx), absorbTx(w, vault, Seq(pool), h))
      val refreshed = spendOutput(ok(executor(ctx), txAt(ctx, h, Seq(vault.withCtxVar(9.toByte, int(0)), wallet(ctx, executor(ctx), 7, h - 2000)),
        Seq(vault.toUTXO.subValue(Bounty).setCreationHeight(h)), executor(ctx).getAddress)), 0)
      ok(executor(ctx), absorbTx(w, refreshed, Seq(pool), h + 1))
      // One block younger, no refresh needed.
      ok(executor(ctx), absorbTx(w, vaultBox(ctx, 200000000L, Seq(Token(handShare, N + 1L), Token(bondAsset, 1L)), h - 1051199),
        Seq(pool), h))
      // The pull of the old vault with no pool: the pool script never runs.
      val src = settledIn(ctx, h - 800, Seq(Token(SigUSD, 800L)), 6000000L, 0.toByte)
      ok(executor(ctx), pullTx(w, vault, src, h))
    }
  }
}
