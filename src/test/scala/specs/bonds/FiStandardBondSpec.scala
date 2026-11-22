package org.lithosprotocol.sigmafi
package specs.bonds

import ksingh.sigmabonds.app.Protocol
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{InputUTXO, Token, UTXO}

import scala.collection.JavaConverters._

/**
 * FI_Standard_Bond, FI_Standard_ERG_SigUSD_Covenant and FI_Standard_CouponVault. The FI order does not
 * exist yet, so every bond here is built directly (FiStandardSpecBase.fiBox) with the registers an order
 * would write, and every later state is the output of a signed transaction or a box built in that state.
 *
 * Heights in the default world: start s, coupon k due at s + 200 + 200k (k = 0, 1, 2), grace 30, so
 * coupon k is missed from s + 230 + 200k; cure window 100; maximum oracle age 30; maturity s + 2000.
 * Every accepted transaction also passes the node's output-height and box-size rules (`ok`).
 */
class FiStandardBondSpec extends AnyPropSpec with FiStandardSpecBase {
  import BondSpecBase._
  import ZcStandardSpecBase._

  private val kinds = Seq[Kind](TokenBond, ErgBond)

  private def at(w: W, paid: Long = 0L, last: Long = -1L, deadline: Long = 0L, clock: Long = -1L,
                 stamp: Int = -1): InputUTXO = fiIn(w, fiBox(w, paid, last, deadline, clock, stamp))

  private def withR9(u: UTXO, i: Int, f: Long => Long): UTXO = {
    val c = u.registers(5).getValue.asInstanceOf[sigma.Coll[Long]].toArray.toSeq
    u.withRegNum(9, longsValue(c.updated(i, f(c(i))): _*))
  }

  /** Each immutable part of a successor changed alone: script, R4..R8, and R9(0..2). */
  private def immutables(w: W, u: UTXO): Seq[(String, UTXO)] = {
    val terms = u.registers(2).getValue.asInstanceOf[sigma.Coll[Long]].toArray.toSeq
    Seq("script" -> u.setContract(otherTree(w.ctx)),
      "R4" -> u.withRegNum(4, bytesValue(otherBondAsset.getBytes)),
      "R5" -> u.withRegNum(5, bytesValue(hashOf(otherTree(w.ctx)))),
      "R7" -> u.withRegNum(7, Protocol.tokensValue(Seq(Token(SigUSD, 1L)))),
      "R8" -> u.withRegNum(8, bytesValue(alwaysTrue(w.ctx).hashedValueBytes)),
      "R9(0)" -> withR9(u, 0, _ + 1L), "R9(1)" -> withR9(u, 1, _ + 1L), "R9(2)" -> withR9(u, 2, _ + 1L)) ++
      terms.indices.map(i => s"R6($i)" -> u.withRegNum(6, longsValue(terms.updated(i, terms(i) + 1L): _*)))
  }

  private def lowerCollateral(u: UTXO): (UTXO, Seq[Token]) = {
    val t = u.tokens(1)
    (u.setTokens(u.tokens.updated(1, Token(t.id, t.amount - 1L)): _*), Seq(Token(t.id, 1L)))
  }

  // ═══ COMPILATION AND SIZE ═════════════════════════════════════════════════

  property("compile: ErgoTree v3; ERG and token bonds share a template and pin the vault, coupon vault and covenant") {
    withCtx { ctx =>
      val f = fi(ctx)
      Seq(f.couponVault, f.ergBond, f.tokenBond, f.ergCovenant, f.tokenCovenant).foreach(_.ergoTree.version shouldBe 3)
      f.ergBond.ergoTree.bytes should not be f.tokenBond.ergoTree.bytes
      f.ergBond.ergoTree.template shouldBe f.tokenBond.ergoTree.template
      Seq(f.ergBond -> f.ergCovenant, f.tokenBond -> f.tokenCovenant).foreach { case (bond, covenant) =>
        Seq(scripts(ctx).vault.hashedPropBytes, f.couponVault.hashedPropBytes, covenant.hashedValueBytes)
          .foreach(hash => bond.ergoTreeHex should include(Hex.toHexString(hash)))
      }
      // One covenant for both markets: it reads the repayment asset from the bond's R7, not from a constant.
      f.ergCovenant.hashedValueBytes.toSeq shouldBe f.tokenCovenant.hashedValueBytes.toSeq
      println(s"FISIZE|tree|FI ERG bond=${f.ergBond.propBytes.length}|FI SigUSD bond=${f.tokenBond.propBytes.length}|" +
        s"ZC ERG bond=${scripts(ctx).ergBond.propBytes.length}|ZC SigUSD bond=${scripts(ctx).tokenBond.propBytes.length}|" +
        s"covenant ERG=${f.ergCovenant.valueBytes.length}|covenant SigUSD=${f.tokenCovenant.valueBytes.length}|" +
        s"coupon vault=${f.couponVault.propBytes.length}|FI liquidation=${f.payout.valueBytes.length}")
      println(s"FIHASH|ERG bond=${f.ergBond.hashedPropBytesHex}|SigUSD bond=${f.tokenBond.hashedPropBytesHex}|" +
        s"covenant ERG=${Hex.toHexString(f.ergCovenant.hashedValueBytes)}|covenant SigUSD=${Hex.toHexString(f.tokenCovenant.hashedValueBytes)}|" +
        s"coupon vault=${f.couponVault.hashedPropBytesHex}")
      f.tokenBond.propBytes.length should be <= 2300
    }
  }

  property("size: realistic and worst-case bond boxes; the most collateral tokens that fit sign every path") {
    withCtx { ctx =>
      val w = world(ctx)
      val realistic = at(w).bytes.length
      val ergRealistic = at(world(ctx, ErgBond)).bytes.length
      // Worst case: R6 at 64 Longs, the payload all Long.MaxValue (10-byte VLQ), every R9 value wide,
      // every token amount Long.MaxValue.
      val wideR6 = w.terms ++ Seq.fill(64 - w.terms.size)(Long.MaxValue)
      def tokens(n: Int, amount: Long): Seq[Token] =
        Token(bondAsset, 2L) +: (1 to n).map(i => Token(ErgoId.create("%064x".format(1000 + i)), amount))
      def worst(n: Int): Int = fiIn(w, fiBox(w, tokens = tokens(n, Long.MaxValue), terms = wideR6,
        r9 = Seq(Long.MaxValue, Int.MaxValue.toLong, Long.MaxValue, 1000L, 999L, Long.MaxValue, Int.MaxValue.toLong))).bytes.length
      val fits = Iterator.from(0).takeWhile(n => worst(n) <= MaxBox).toSeq.last
      println(s"FISIZE|box|realistic SigUSD bond=$realistic|realistic ERG bond=$ergRealistic|" +
        s"worst, 0 collateral tokens=${worst(0)}|worst fits $fits collateral tokens (${worst(fits)} bytes)|${fits + 1} tokens=${worst(fits + 1)}")
      realistic should be < MaxBox
      // Every path at the heaviest shape that fits (amounts below Long.MaxValue so the top-up can add).
      val heavy = fiIn(w, fiBox(w, tokens = tokens(fits, 1000000L), terms = wideR6))
      val hr = w.due(0) + 5
      val b1 = spendOutput(ok(borrower(ctx), payTx(w, heavy, hr - 10)), 0)
      val b2 = spendOutput(ok(executor(ctx), recordTx(w, b1, hr, 0L)), 0)
      val b3 = spendOutput(ok(borrower(ctx), cureTx(w, b2, hr + 1)), 0)
      val b4 = spendOutput(ok(borrower(ctx), recollatTx(w, b3, hr + 2)), 0)
      ok(executor(ctx), fiLiquidateTx(w, b4, w.due(1) + grace))
      ok(borrower(ctx), fiRepayTx(w, b4, hr + 3))
      println(s"FISIZE|heavy|$fits collateral tokens, R6 64 Longs: bond=${heavy.bytes.length}, after four moves=${b4.bytes.length}")
    }
  }

  // ═══ PAY ══════════════════════════════════════════════════════════════════

  for (kind <- kinds) {
    property(s"pay ($kind): the borrower pays coupon 0 into a coupon vault; the successor differs only in R9(3)") {
      withCtx { ctx =>
        val w = world(ctx, kind)
        val b = at(w)
        val h = w.due(0) - 10
        val signed = ok(borrower(ctx), payTx(w, b, h))
        val next = spendOutput(signed, 0)
        r9(next) shouldBe r9(b).updated(3, 1L)
        next.registers.take(5) shouldBe b.registers.take(5)
        next.tokens shouldBe b.tokens
        next.value shouldBe b.value
        val vault = spendOutput(signed, 1)
        vault.contract shouldBe fi(ctx).couponVault
        if (w.erg) vault.value shouldBe w.coupon else vault.tokens shouldBe Seq(Token(SigUSD, w.coupon))
        // The coupon the bond created is the one its unit holder collects.
        ok(lender(ctx), collectTx(w, Seq(vault), Seq(unit(ctx, h)), h + 1))
        // The next coupon, from the successor.
        ok(borrower(ctx), payTx(w, next, w.due(1) - 10))
      }
    }
  }

  property("pay: open from one period before the due height until the block before due + grace") {
    withCtx { ctx =>
      val w = world(ctx)
      val b = at(w, paid = 1L)
      val open = w.due(1) - period
      val missed = w.due(1) + grace
      no(borrower(ctx), payTx(w, b, open - 1))
      Seq(open, open + 1, missed - 2, missed - 1).foreach(h => ok(borrower(ctx), payTx(w, b, h)))
      Seq(missed, missed + 1).foreach(h => no(borrower(ctx), payTx(w, b, h)))
      // At each of these heights exactly one of Pay and the missed-coupon liquidation is open.
      no(executor(ctx), fiLiquidateTx(w, b, missed - 1))
      ok(executor(ctx), fiLiquidateTx(w, b, missed))
    }
  }

  property("pay: one coupon per Pay, never beyond the count") {
    withCtx { ctx =>
      val w = world(ctx)
      val last = at(w, paid = 2L)
      val h = w.due(2) - 10
      ok(borrower(ctx), payTx(w, last, h))
      no(borrower(ctx), payTx(w, last, h, next = withR9(paid(w, last, h), 3, _ + 1L)))
      no(borrower(ctx), payTx(w, last, h, next = withR9(paid(w, last, h), 3, _ - 1L)))
      val done = at(w, paid = 3L)
      no(borrower(ctx), payTx(w, done, h))
      no(borrower(ctx), payTx(w, done, w.due(2) + 100))
    }
  }

  property("pay: needs a borrower-script co-spend, and the bond does not count as its own") {
    withCtx { ctx =>
      val w = world(ctx)
      val b = at(w)
      val h = w.due(0) - 10
      ok(borrower(ctx), payTx(w, b, h))
      no(other(ctx), payTx(w, b, h, by = other(ctx)))
      no(executor(ctx), payTx(w, b, h, by = executor(ctx), coSpend = false))
      // A bond naming its own script as the borrower's has no other input at that script.
      val selfNamed = fiIn(w, fiBox(w, borrowerHash = hashOf(w.bond)))
      no(executor(ctx), payTx(w, selfNamed, h, by = executor(ctx), coSpend = false))
    }
  }

  property("pay: the successor keeps every immutable field, every token and the value") {
    withCtx { ctx =>
      for (kind <- kinds) {
        val w = world(ctx, kind)
        val b = at(w)
        val h = w.due(0) - 10
        val next = paid(w, b, h)
        ok(borrower(ctx), payTx(w, b, h, next = next.addValue(1L)))
        immutables(w, next).foreach { case (name, bad) => withClue(name)(no(borrower(ctx), payTx(w, b, h, next = bad))) }
        Seq(4, 5, 6).foreach(i => withClue(s"R9($i)")(no(borrower(ctx), payTx(w, b, h, next = withR9(next, i, _ + 1L)))))
        no(borrower(ctx), payTx(w, b, h, next = next.subValue(1L)))
        val (lower, burn) = lowerCollateral(next)
        no(borrower(ctx), payTx(w, b, h, next = lower, burn = burn))
        no(borrower(ctx), payTx(w, b, h, next = next.setTokens(next.tokens :+ Token(secondCollateral, 1L): _*)))
      }
    }
  }

  private val regsFree = scala.collection.mutable.Map.empty[Boolean, work.lithos.mutations.Contract]

  /** The bond without "the coupon output has no R5". */
  private def anyRegs(w: W): work.lithos.mutations.Contract = regsFree.getOrElseUpdate(w.erg,
    FiStandardSpecBase.bondVariant(w.ctx, repaymentBytes(w), fi(w.ctx).couponVault,
      "couponBox.R5[Coll[Byte]].isDefined == false &&" -> "true &&"))

  property("pay: the coupon vault output: script, amount, tag, index, R4 alone, one per asset") {
    withCtx { ctx =>
      for (kind <- kinds) {
        val w = world(ctx, kind)
        val b = at(w)
        val h = w.due(0) - 10
        val coupon = couponOut(w)
        ok(borrower(ctx), payTx(w, b, h, coupon = coupon))
        ok(borrower(ctx), payTx(w, b, h, coupon = couponOut(w, amount = w.coupon + 1L)))
        Seq("script" -> coupon.setContract(lenderTree(ctx)),
          "amount" -> couponOut(w, amount = w.coupon - 1L),
          "R4 asset" -> couponOut(w, asset = otherBondAsset)
        ).foreach { case (name, bad) => withClue(name)(no(borrower(ctx), payTx(w, b, h, coupon = bad))) }
        // The pool names no borrower and no height: any register after R4 is refused, and the bond without
        // its "no R5" rule signs the same Pay, so that rule refused it.
        val borrowerNamed = coupon.setRegs(poolRegs() :+ bytesValue(hashOf(borrowerTree(ctx))): _*)
        Seq("R5 borrower hash" -> borrowerNamed,
          "R5 borrower hash, R6 maturity" -> borrowerNamed.setRegs(borrowerNamed.registers :+ ErgoValue.of(w.maturity): _*),
          "R5 Long" -> coupon.setRegs(poolRegs() :+ ErgoValue.of(w.maturity): _*)
        ).foreach { case (name, bad) =>
          withClue(name) {
            no(borrower(ctx), payTx(w, b, h, coupon = bad))
            ok(borrower(ctx), payTx(w, fiIn(w, fiBox(w).setContract(anyRegs(w))), h, coupon = bad))
          }
        }
        if (!w.erg) no(borrower(ctx), payTx(w, b, h, coupon = coupon.setTokens(coupon.tokens :+ Token(secondCollateral, 1L): _*)))
        // Var 5 must name it: the successor, an index past the outputs, or no var 5 at all fail.
        Seq(Seq(4 -> int(0), 5 -> int(0)), Seq(4 -> int(0), 5 -> int(7)), Seq(4 -> int(0)))
          .foreach(vs => no(borrower(ctx), payTx(w, b, h, vs = vs)))
        // The only output at the coupon vault script tagged with this asset; another bond's is no matter.
        no(borrower(ctx), payTx(w, b, h, extraOuts = Seq(couponOut(w))))
        ok(borrower(ctx), payTx(w, b, h, extraOuts = Seq(couponOut(w, asset = otherBondAsset))))
        // Var 5 must name the tagged box itself: a coupon tagged for another bond at var 5, beside an
        // empty box tagged for this one, pays this bond's holder nothing.
        no(borrower(ctx), payTx(w, b, h, coupon = couponOut(w, asset = otherBondAsset),
          extraOuts = Seq(couponOut(w, amount = if (w.erg) minBox else 1L))))
      }
    }
  }

  property("pay: allowed while a breach is open; the breach stays open") {
    withCtx { ctx =>
      val w = world(ctx)
      val b = at(w, last = 0L, deadline = w.due(0) + 100L)
      val next = spendOutput(ok(borrower(ctx), payTx(w, b, w.due(0) + 5)), 0)
      r9(next) shouldBe r9(b).updated(3, 1L)
    }
  }

  // ═══ RECORD BREACH ════════════════════════════════════════════════════════

  for (kind <- kinds) {
    property(s"record ($kind): anyone records a breach at a coupon date when the bond is below its ratio") {
      withCtx { ctx =>
        val w = world(ctx, kind)
        val b = at(w)
        val h = w.due(0) + 5
        val next = spendOutput(ok(executor(ctx), recordTx(w, b, h, 0L)), 0)
        r9(next) shouldBe r9(b).updated(4, 0L).updated(5, h.toLong + cureBlocks)
        next.tokens shouldBe b.tokens
        ok(other(ctx), recordTx(w, b, h, 0L, by = other(ctx)))
        // A recorded breach does not liquidate by itself.
        no(executor(ctx), fiLiquidateTx(w, next, h + 1))
        no(executor(ctx), recordTx(w, b, h, 0L, price = w.calm))
      }
    }
  }

  property("record: the window is the checkpoint's due height to due + grace, both included") {
    withCtx { ctx =>
      val w = world(ctx)
      val b = at(w, paid = 1L)
      val d0 = w.due(0)
      no(executor(ctx), recordTx(w, b, d0 - 1, 0L))
      Seq(d0, d0 + 1, d0 + grace - 1, d0 + grace).foreach(h => ok(executor(ctx), recordTx(w, b, h, 0L)))
      no(executor(ctx), recordTx(w, b, d0 + grace + 1, 0L))
      // Checkpoint 1 in checkpoint 0's window, or the other way round, does not record.
      no(executor(ctx), recordTx(w, b, d0 + 5, 1L))
      val d1 = w.due(1)
      no(executor(ctx), recordTx(w, b, d1 - 1, 1L))
      ok(executor(ctx), recordTx(w, b, d1, 1L))
      no(executor(ctx), recordTx(w, b, d1, 0L))
      // Coupon 1 is unpaid: its last grace / 2 blocks are the quiet zone, and at d1 + grace it is missed,
      // the bond is liquidatable and nothing re-creates it.
      ok(executor(ctx), recordTx(w, b, d1 + grace / 2 - 1, 1L))
      no(executor(ctx), recordTx(w, b, d1 + grace / 2, 1L))
      no(executor(ctx), recordTx(w, b, d1 + grace - 1, 1L))
      no(executor(ctx), recordTx(w, b, d1 + grace, 1L))
      // Paid ahead, coupon 1 has no quiet zone: its checkpoint records to the last block of grace.
      ok(executor(ctx), recordTx(w, at(w, paid = 2L), d1 + grace, 1L))
    }
  }

  property("record: only strictly below the ratio, on the repayment") {
    withCtx { ctx =>
      // SigUSD bond: 7.2 ERG against $8 at 0.6 ERG/USD is exactly 150%.
      val exact = world(ctx, collateralErg = 7200000000L)
      val under = world(ctx, collateralErg = 7200000000L - 1L)
      no(executor(ctx), recordTx(exact, at(exact), exact.due(0) + 5, 0L))
      ok(executor(ctx), recordTx(under, at(under), under.due(0) + 5, 0L))
      // ERG bond: 1 ERG and 1250 raw SigUSD against 4 ERG at 0.4 ERG/USD is exactly 150%.
      val ergExact = world(ctx, ErgBond, collateral = Seq(Token(SigUSD, 1250L)))
      val ergUnder = world(ctx, ErgBond, collateral = Seq(Token(SigUSD, 1249L)))
      no(executor(ctx), recordTx(ergExact, at(ergExact), ergExact.due(0) + 5, 0L))
      ok(executor(ctx), recordTx(ergUnder, at(ergUnder), ergUnder.due(0) + 5, 0L))
    }
  }

  property("record: needs exactly one fresh, well-formed oracle box, at any data-input position") {
    withCtx { ctx =>
      val w = world(ctx)
      val b = at(w)
      val h = w.due(0) + 5
      def with_(data: Seq[InputUTXO]): Tx = recordTx(w, b, h, 0L, data = data)
      val decoy = inputAt(UTXO(work.lithos.mutations.Contract.SIGMA_TRUE, minBox), ctx, 21)
      ok(executor(ctx), recordTx(w, b, h, 0L, priced = h - maxAge))
      ok(executor(ctx), recordTx(w, b, h, 0L, priced = h))
      ok(executor(ctx), with_(Seq(decoy, oracle(ctx, w.slump, h - 5))))
      no(executor(ctx), recordTx(w, b, h, 0L, priced = h - maxAge - 1))
      no(executor(ctx), recordTx(w, b, h, 0L, priced = h + 1))
      no(executor(ctx), with_(Seq.empty))
      no(executor(ctx), with_(Seq(decoy)))
      no(executor(ctx), with_(Seq(oracle(ctx, w.slump, h - 5), oracle(ctx, w.slump, h - 5, index = 22))))
      no(executor(ctx), with_(Seq(oracle(ctx, w.slump, h - 5, nft = otherBondAsset))))
      no(executor(ctx), with_(Seq(oracle(ctx, w.slump, h - 5, amount = 2L))))
      Seq(0L, -1L).foreach(p => no(executor(ctx), with_(Seq(oracle(ctx, p, h - 5)))))
      // On an ERG bond a zero price would value the SigUSD collateral at nothing, which reads as below.
      val e = world(ctx, ErgBond)
      ok(executor(ctx), recordTx(e, at(e), h, 0L))
      Seq(0L, -1L).foreach(p => no(executor(ctx), recordTx(e, at(e), h, 0L, data = Seq(oracle(ctx, p, h - 5)))))
      no(executor(ctx), with_(Seq(oracle(ctx, w.slump, h - 5, epoch = -1))))
    }
  }

  property("record: the cure deadline lies in [HEIGHT + cure, HEIGHT + cure + 15]") {
    withCtx { ctx =>
      val w = world(ctx)
      val b = at(w)
      val h = w.due(0) + 5
      def deadline(d: Long): Tx = recordTx(w, b, h, 0L, next = recorded(w, b, h, 0L, d))
      Seq(0L, 15L).foreach(x => ok(executor(ctx), deadline(h + cureBlocks + x)))
      Seq(-1L, 16L).foreach(x => no(executor(ctx), deadline(h + cureBlocks + x)))
      no(executor(ctx), deadline(0L))
    }
  }

  property("record: once per checkpoint; not while a breach is open; a cured checkpoint never again") {
    withCtx { ctx =>
      val w = world(ctx)
      val b = at(w, paid = 1L)
      val h = w.due(0) + 5
      val breached = spendOutput(ok(executor(ctx), recordTx(w, b, h, 0L)), 0)
      no(executor(ctx), recordTx(w, breached, h + 1, 0L))
      // A later checkpoint while a breach is open would push the deadline out.
      val d1 = w.due(1)
      val openAtD1 = at(w, paid = 1L, last = 0L, deadline = d1 + 50L)
      no(executor(ctx), recordTx(w, openAtD1, d1 + 5, 1L))
      ok(executor(ctx), recordTx(w, at(w, paid = 1L, last = 0L), d1 + 5, 1L))
      // After a cure the checkpoint stays recorded: it cannot be recorded again; the next one can.
      val cleared = spendOutput(ok(borrower(ctx), cureTx(w, breached, h + 1)), 0)
      r9(cleared)(4) shouldBe 0L
      // At a price that breaches it again, checkpoint 0 is still refused; a fresh box records it.
      no(executor(ctx), recordTx(w, cleared, h + 2, 0L, price = w.slump + 1L))
      ok(executor(ctx), recordTx(w, at(w, paid = 1L), h + 2, 0L, price = w.slump + 1L))
      // The cure topped it up to exactly 150% at 0.6; a price above that breaches it again.
      ok(executor(ctx), recordTx(w, cleared, d1 + 5, 1L, price = w.slump + 1L))
      // Checkpoints run 0 .. count - 1.
      val allPaid = at(w, paid = 3L, last = 1L)
      ok(executor(ctx), recordTx(w, allPaid, w.due(2) + 5, 2L))
      no(executor(ctx), recordTx(w, at(w, paid = 3L, last = 2L), w.due(3) + 5, 3L))
    }
  }

  property("record: refused in the 720 blocks before maturity") {
    withCtx { ctx =>
      val w = world(ctx, life = firstOffset + 730L)
      val b = at(w)
      val m = w.maturity.toInt
      ok(executor(ctx), recordTx(w, b, m - 721, 0L))
      no(executor(ctx), recordTx(w, b, m - 720, 0L))
    }
  }

  property("record: the successor keeps every other field and every token; the value falls by at most the bounty") {
    withCtx { ctx =>
      val w = world(ctx)
      val b = at(w)
      val h = w.due(0) + 5
      val next = recorded(w, b, h, 0L)
      ok(executor(ctx), recordTx(w, b, h, 0L, next = next.addValue(1L)))
      // The covenant measures this box, not the successor: a successor topped up 5 ERG, far above the
      // ratio, still records the breach of the box that was below it.
      ok(executor(ctx), recordTx(w, b, h, 0L, next = next.addValue(5L * Parameters.OneErg)))
      immutables(w, next).foreach { case (name, bad) => withClue(name)(no(executor(ctx), recordTx(w, b, h, 0L, next = bad))) }
      Seq(3, 6).foreach(i => withClue(s"R9($i)")(no(executor(ctx), recordTx(w, b, h, 0L, next = withR9(next, i, _ + 1L)))))
      no(executor(ctx), recordTx(w, b, h, 0L, next = next.subValue(RecordBounty + 1L)))
      // No token leaves, with the bounty taken or not.
      val (lower, burn) = lowerCollateral(next)
      no(executor(ctx), recordTx(w, b, h, 0L, next = lower, burn = burn))
      no(executor(ctx), recordTx(w, b, h, 0L, next = lower.subValue(RecordBounty), burn = burn))
    }
  }

  // ═══ RECORD BOUNTY ════════════════════════════════════════════════════════

  private val RecordBounty: Long = FiStandardSpecBase.RecordBounty
  /** The least value any path accepts: R9(0) + 1000000. */
  private val Floor: Long = batchingFee + 1000000L

  /** The bond pinning the real pool, with `edits` to its source: one clause changed, to show which one refuses. */
  private val variants = scala.collection.mutable.Map.empty[String, work.lithos.mutations.Contract]
  private def bondEdit(w: W, edits: (String, String)*): work.lithos.mutations.Contract =
    variants.getOrElseUpdate(s"${w.erg}|$edits",
      FiStandardSpecBase.bondVariant(w.ctx, if (w.erg) Array.emptyByteArray else SigUSD.getBytes, fi(w.ctx).couponVault, edits: _*))
  private val BountyCap = "else CONST_RECORD_BOUNTY)"
  private val FloorClause = "next.tokens == SELF.tokens && next.value >= fee + 1000000L &&"

  for (kind <- kinds) {
    property(s"record bounty ($kind): with ample nanoERG the caller keeps exactly CONST_RECORD_BOUNTY (0.5 ERG), not a nanoERG more") {
      withCtx { ctx =>
        val w = world(ctx, kind)
        val b = at(w)
        val h = w.due(0) + 5
        val next = recorded(w, b, h, 0L)
        RecordBounty shouldBe 500000000L
        val s = ok(executor(ctx), recordTx(w, b, h, 0L, next = next.subValue(RecordBounty)))
        spendOutput(s, 0).value shouldBe b.value - RecordBounty
        spendOutput(s, 0).tokens shouldBe b.tokens
        no(executor(ctx), recordTx(w, b, h, 0L, next = next.subValue(RecordBounty + 1L)))
        // The cap clause is what refuses: with the cap one nanoERG higher the same Record signs.
        val wider = bondEdit(w, BountyCap -> "else CONST_RECORD_BOUNTY + 1L)")
        val bw = fiIn(w, fiBox(w).setContract(wider))
        ok(executor(ctx), recordTx(w, bw, h, 0L, next = recorded(w, bw, h, 0L).subValue(RecordBounty + 1L)))
        // Recorded for nothing also signs.
        ok(executor(ctx), recordTx(w, b, h, 0L, next = next))
      }
    }

    property(s"record bounty ($kind): never below R9(0) + 1000000; 0.2 ERG above it, 0.2 ERG leaves; at it, the Record signs for nothing") {
      withCtx { ctx =>
        val w = world(ctx, kind)
        val h = w.due(0) + 5
        val fewTokens = if (w.erg) Seq(Token(bondAsset, 2L), Token(SigUSD, 1L)) else w.tokens
        Seq(200000000L, 0L).foreach { above =>
          withClue(s"$above above the floor: ") {
            val b = fiIn(w, fiBox(w, value = Floor + above, tokens = fewTokens))
            val next = recorded(w, b, h, 0L)
            ok(executor(ctx), recordTx(w, b, h, 0L, next = next.setValue(Floor)))
            no(executor(ctx), recordTx(w, b, h, 0L, next = next.setValue(Floor - 1L)))
            // The floor clause is what refuses: without it the same Record signs.
            val bare = bondEdit(w, FloorClause -> "next.tokens == SELF.tokens &&")
            val bb = fiIn(w, fiBox(w, value = Floor + above, tokens = fewTokens).setContract(bare))
            ok(executor(ctx), recordTx(w, bb, h, 0L, next = recorded(w, bb, h, 0L).setValue(Floor - 1L)))
          }
        }
      }
    }
  }

  property("record bounty: the borrower recording its own breach moves only its own nanoERG to itself; the date cannot be recorded twice") {
    withCtx { ctx =>
      val w = world(ctx)
      val b = at(w, paid = 1L)
      val h = w.due(0) + 5
      val payer = wallet(ctx, borrower(ctx), 7, h)
      val s = ok(borrower(ctx), recordTx(w, b, h, 0L, by = borrower(ctx), next = recorded(w, b, h, 0L).subValue(RecordBounty)))
      val outs = (0 until s.getOutputsToSpend.size).map(s.getOutputsToSpend.get)
      val toBorrower = outs.filter(_.getErgoTree == borrowerTree(ctx).ergoTree).map(_.getValue.longValue).sum
      toBorrower shouldBe payer.value + RecordBounty - Parameters.MinFee
      outs.filter(_.getErgoTree == borrowerTree(ctx).ergoTree).flatMap(_.getTokens.asScala) shouldBe empty
      // Once recorded, that coupon date is spent: not again while the breach is open, nor after a cure.
      val breached = spendOutput(s, 0)
      no(executor(ctx), recordTx(w, breached, h + 1, 0L))
      no(executor(ctx), recordTx(w, breached, h + 1, 0L, next = recorded(w, breached, h + 1, 0L)))
      val cleared = spendOutput(ok(borrower(ctx), cureTx(w, breached, h + 1,
        next = cured(w, breached, h + 1).addValue(RecordBounty))), 0)
      no(executor(ctx), recordTx(w, cleared, h + 2, 0L, price = w.slump + 1L))
      no(executor(ctx), recordTx(w, cleared, h + 2, 0L, price = w.slump + 1L, next = recorded(w, cleared, h + 2, 0L).subValue(RecordBounty)))
    }
  }

  for (kind <- kinds) {
    property(s"record bounty ($kind): Cure measures its successor, so after a bounty the cure must make it up as well") {
      withCtx { ctx =>
        val w = world(ctx, kind)
        val h = w.due(0) + 5
        val breached = spendOutput(ok(executor(ctx), recordTx(w, at(w, paid = 1L), h, 0L,
          next = recorded(w, at(w, paid = 1L), h, 0L).subValue(RecordBounty))), 0)
        val clear = succ(breached, r9(breached).updated(5, 0L), h + 1)
        // The top-up that restores exactly 150% on a full box is short after the bounty.
        no(borrower(ctx), cureTx(w, breached, h + 1, next = toppedUp(w, clear)))
        // Token bond: 0.2 ERG plus the 0.5 ERG bounty. ERG bond: 250 raw SigUSD plus 0.5 ERG's worth at 0.4 ERG/USD (125).
        val exact = if (w.erg) clear.setTokens(clear.tokens.map(t => if (t.id == SigUSD) Token(SigUSD, t.amount + 375L) else t): _*)
          else clear.addValue(200000000L + RecordBounty)
        val short = if (w.erg) clear.setTokens(clear.tokens.map(t => if (t.id == SigUSD) Token(SigUSD, t.amount + 374L) else t): _*)
          else exact.subValue(1L)
        ok(borrower(ctx), cureTx(w, breached, h + 1, next = exact))
        no(borrower(ctx), cureTx(w, breached, h + 1, next = short))
      }
    }
  }

  property("record and cure: the covenant expression must be the one the bond pins") {
    withCtx { ctx =>
      val w = world(ctx)
      val b = at(w)
      val h = w.due(0) + 5
      ok(executor(ctx), recordTx(w, b, h, 0L))
      Seq(Seq(6 -> int(0)), Seq(6 -> int(0), 8 -> covVar(alwaysTrue(ctx))), Seq(6 -> int(0), 8 -> covVar(w.otherCovenant)),
        Seq(6 -> int(0), 8 -> covVar(fi(ctx).payout))).foreach(vs => no(executor(ctx), recordTx(w, b, h, 0L, vs = vs)))
      val breached = at(w, last = 0L, deadline = h + 100L)
      ok(borrower(ctx), cureTx(w, breached, h))
      Seq(Seq(7 -> int(0)), Seq(7 -> int(0), 8 -> covVar(alwaysTrue(ctx))), Seq(7 -> int(0), 8 -> covVar(w.otherCovenant)))
        .foreach(vs => no(borrower(ctx), cureTx(w, breached, h, vs = vs)))
    }
  }

  // ═══ CURE ═════════════════════════════════════════════════════════════════

  for (kind <- kinds) {
    property(s"cure ($kind): the block after Record, the borrower restores the ratio and clears the breach") {
      withCtx { ctx =>
        val w = world(ctx, kind)
        val h = w.due(0) + 5
        val breached = spendOutput(ok(executor(ctx), recordTx(w, at(w, paid = 1L), h, 0L)), 0)
        val next = spendOutput(ok(borrower(ctx), cureTx(w, breached, h + 1)), 0)
        r9(next) shouldBe r9(breached).updated(5, 0L)
        r9(next)(4) shouldBe 0L
        // Exactly the ratio passes; one unit under does not.
        no(borrower(ctx), cureTx(w, breached, h + 1, next = cured(w, breached, h + 1, short = true)))
        // Cleared, the bond is not liquidatable past the old deadline, only from the next missed coupon.
        no(executor(ctx), fiLiquidateTx(w, next, h + cureBlocks + 1))
        ok(executor(ctx), fiLiquidateTx(w, next, w.due(1) + grace))
      }
    }
  }

  property("cure: open until the block before the deadline; from the deadline the breach is final") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = w.due(0) + 5
      val breached = spendOutput(ok(executor(ctx), recordTx(w, at(w, paid = 1L), h, 0L)), 0)
      val d = h + cureBlocks
      ok(borrower(ctx), cureTx(w, breached, d - 1))
      no(executor(ctx), fiLiquidateTx(w, breached, d - 1))
      no(borrower(ctx), cureTx(w, breached, d))
      ok(executor(ctx), fiLiquidateTx(w, breached, d))
      no(borrower(ctx), cureTx(w, breached, d + 1))
    }
  }

  property("cure: needs the borrower, an open breach and a fresh oracle") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = w.due(0) + 5
      val breached = at(w, last = 0L, deadline = h + 50L)
      ok(borrower(ctx), cureTx(w, breached, h))
      no(other(ctx), cureTx(w, breached, h, by = other(ctx)))
      no(borrower(ctx), cureTx(w, at(w, last = 0L), h))
      no(borrower(ctx), cureTx(w, breached, h, priced = h - maxAge - 1))
      no(borrower(ctx), cureTx(w, breached, h, data = Seq.empty))
      // On a SigUSD bond a zero or negative price would make the debt nothing, which reads as covered.
      Seq(0L, -1L).foreach(p => no(borrower(ctx), cureTx(w, breached, h, data = Seq(oracle(ctx, p, h - 5)))))
      // A bond naming its own script as the borrower's has no other input at that script.
      val selfNamed = fiIn(w, fiBox(w, last = 0L, deadline = h + 50L, borrowerHash = hashOf(w.bond)))
      no(executor(ctx), cureTx(w, selfNamed, h, by = executor(ctx)))
    }
  }

  property("cure: collateral grows by recollateralize's rules, measured on the successor") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = w.due(0) + 5
      val breached = at(w, last = 0L, deadline = h + 50L)
      val clear = succ(breached, r9(breached).updated(5, 0L), h)
      // The price has recovered: still something must be added, as recollateralize asks.
      no(borrower(ctx), cureTx(w, breached, h, next = clear, price = w.calm))
      ok(borrower(ctx), cureTx(w, breached, h, next = clear.addValue(1L), price = w.calm))
      // A token bond holding no SigUSD may append it; it counts towards the ratio (34 raw is enough at 0.6).
      ok(borrower(ctx), cureTx(w, breached, h, next = clear.setTokens(clear.tokens :+ Token(SigUSD, 34L): _*)))
      no(borrower(ctx), cureTx(w, breached, h, next = clear.setTokens(clear.tokens :+ Token(SigUSD, 33L): _*)))
      no(borrower(ctx), cureTx(w, breached, h, next = toppedUp(w, clear).setTokens(clear.tokens :+ Token(secondCollateral, 1L): _*)))
      // More ERG cannot pay for taking a collateral token out.
      val (lower, burn) = lowerCollateral(toppedUp(w, clear).addValue(Parameters.OneErg))
      no(borrower(ctx), cureTx(w, breached, h, next = lower, burn = burn))
      no(borrower(ctx), cureTx(w, breached, h, next = toppedUp(w, clear).subValue(Parameters.OneErg)))
    }
  }

  property("cure: the successor keeps every other field") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = w.due(0) + 5
      val breached = at(w, last = 0L, deadline = h + 50L)
      val next = cured(w, breached, h)
      ok(borrower(ctx), cureTx(w, breached, h, next = next))
      immutables(w, next).foreach { case (name, bad) => withClue(name)(no(borrower(ctx), cureTx(w, breached, h, next = bad))) }
      Seq(3, 4, 6).foreach(i => withClue(s"R9($i)")(no(borrower(ctx), cureTx(w, breached, h, next = withR9(next, i, _ + 1L)))))
      no(borrower(ctx), cureTx(w, breached, h, next = withR9(next, 5, _ => h + 50L)))
    }
  }

  // ═══ LIQUIDATE ════════════════════════════════════════════════════════════

  for (kind <- kinds) {
    property(s"liquidate ($kind): a coupon unpaid at its due height plus grace; the unit claims the vault") {
      withCtx { ctx =>
        val w = world(ctx, kind)
        val b = at(w)
        val missed = w.due(0) + grace
        no(executor(ctx), fiLiquidateTx(w, b, missed - 1))
        ok(executor(ctx), fiLiquidateTx(w, b, missed + 1))
        val settled = ok(executor(ctx), fiLiquidateTx(w, b, missed))
        val vault = spendOutput(settled, 0)
        vault.tokens shouldBe b.tokens
        vault.registers.head shouldBe bytesValue(b.id.getBytes)
        vault.registers(1) shouldBe ErgoValue.of(1.toByte)
        ok(lender(ctx), collectTx(w, Seq(vault), Seq(unit(ctx, missed)), missed + 1,
          outs = Seq(UTXO(lenderTree(ctx), minBox)), burn = Seq(Token(bondAsset, 3L))))
      }
    }
  }

  property("liquidate: paying coupon k moves the clock to coupon k + 1; with every coupon paid, none is missed") {
    withCtx { ctx =>
      val w = world(ctx)
      val one = at(w, paid = 1L)
      no(executor(ctx), fiLiquidateTx(w, one, w.due(0) + grace))
      no(executor(ctx), fiLiquidateTx(w, one, w.due(1) + grace - 1))
      ok(executor(ctx), fiLiquidateTx(w, one, w.due(1) + grace))
      val all = at(w, paid = 3L)
      no(executor(ctx), fiLiquidateTx(w, all, w.due(2) + grace + 500))
      no(executor(ctx), fiLiquidateTx(w, all, w.maturity.toInt - 1))
      ok(executor(ctx), fiLiquidateTx(w, all, w.maturity.toInt))
    }
  }

  property("liquidate: an open breach liquidates from its deadline, not a block before") {
    withCtx { ctx =>
      val w = world(ctx)
      val d = w.due(0) + 100
      val b = at(w, paid = 1L, last = 0L, deadline = d.toLong)
      no(executor(ctx), fiLiquidateTx(w, b, d - 1))
      ok(executor(ctx), fiLiquidateTx(w, b, d))
    }
  }

  property("liquidate: a low ratio alone never liquidates before maturity") {
    withCtx { ctx =>
      val w = world(ctx)
      val b = at(w, paid = 1L)
      val h = w.due(0) + 5
      val price = Seq(oracle(ctx, w.slump, h - 5))
      no(executor(ctx), fiLiquidateTx(w, b, h, data = price))
      no(executor(ctx), fiLiquidateTx(w, b, w.due(1) + grace - 1, data = price))
      // ZC's oracle rule cannot stand in: it reads R9 as 3 Longs, so it never passes on an FI bond.
      val zcOracle = scripts(ctx).tokenOracle
      no(executor(ctx), fiLiquidateTx(w, fiIn(w, fiBox(w, paid = 1L, liq = zcOracle)), h, ran = zcOracle, data = price))
    }
  }

  property("liquidate: the agreed expression decides the payout and can only narrow eligibility") {
    withCtx { ctx =>
      val w = world(ctx)
      val missed = w.due(0) + grace
      val b = at(w)
      ok(executor(ctx), fiLiquidateTx(w, b, missed))
      // An expression the borrower never agreed to is not run, however permissive.
      no(executor(ctx), fiLiquidateTx(w, b, missed, ran = alwaysTrue(ctx)))
      // A maturity-only expression narrows: the missed coupon alone does not liquidate under it.
      val narrow = fiIn(w, fiBox(w, liq = fi(ctx).maturityOnly))
      no(executor(ctx), fiLiquidateTx(w, narrow, missed, ran = fi(ctx).maturityOnly))
      ok(executor(ctx), fiLiquidateTx(w, narrow, w.maturity.toInt, ran = fi(ctx).maturityOnly))
      // And an expression cannot widen: a bond that is not liquidatable stays put under alwaysTrue.
      val loose = fiIn(w, fiBox(w, liq = alwaysTrue(ctx)))
      no(executor(ctx), fiLiquidateTx(w, loose, missed - 1, ran = alwaysTrue(ctx)))
      ok(executor(ctx), fiLiquidateTx(w, loose, missed, ran = alwaysTrue(ctx)))
      // The settlement box, as ZC.
      val out = proceeds(w, b)
      val (fewer, burn) = (out.setTokens(out.tokens.take(1): _*), Seq(Token(collateralToken, 100L)))
      no(executor(ctx), fiLiquidateTx(w, b, missed, out = fewer, burn = burn))
      Seq(out.subValue(1L), out.setContract(otherTree(ctx)), out.withRegNum(4, bytesValue(originOrderId)),
        out.withRegNum(5, ErgoValue.of(0.toByte))).foreach(o => no(executor(ctx), fiLiquidateTx(w, b, missed, out = o)))
    }
  }

  property("liquidate: at and after maturity, whatever the coupon and breach state") {
    withCtx { ctx =>
      val w = world(ctx)
      val m = w.maturity.toInt
      // (state, already liquidatable the block before maturity)
      Seq(
        "all paid, never breached" -> (at(w, paid = 3L), false),
        "all paid, breach cured" -> (at(w, paid = 3L, last = 2L), false),
        "all paid, breach open past maturity" -> (at(w, paid = 3L, last = 2L, deadline = m + 500L), false),
        "none paid" -> (at(w, paid = 0L), true),
        "one paid, breach open past maturity" -> (at(w, paid = 1L, last = 0L, deadline = m + 500L), true),
        "two paid, breach final before maturity" -> (at(w, paid = 2L, last = 1L, deadline = m - 100L), true),
        "all paid, breach final the block before maturity" -> (at(w, paid = 3L, last = 2L, deadline = m - 1L), true)
      ).foreach { case (name, (b, early)) =>
        withClue(name) {
          if (early) ok(executor(ctx), fiLiquidateTx(w, b, m - 1)) else no(executor(ctx), fiLiquidateTx(w, b, m - 1))
          ok(executor(ctx), fiLiquidateTx(w, b, m))
          ok(executor(ctx), fiLiquidateTx(w, b, m + 1000))
        }
      }
    }
  }

  // ═══ ANTI-DODGE ═══════════════════════════════════════════════════════════

  property("anti-dodge: at a missed coupon's first block liquidation passes and Pay, Record, Cure, Recollateralize fail; a block earlier, the reverse, Record aside (quiet zone)") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = w.due(0) + grace
      val plain = at(w)
      val breached = at(w, last = 0L, deadline = h + 60L)
      ok(executor(ctx), fiLiquidateTx(w, plain, h))
      ok(executor(ctx), fiLiquidateTx(w, breached, h))
      no(borrower(ctx), payTx(w, plain, h))
      no(executor(ctx), recordTx(w, plain, h, 0L))
      no(borrower(ctx), cureTx(w, breached, h))
      no(borrower(ctx), recollatTx(w, plain, h))
      val e = h - 1
      no(executor(ctx), fiLiquidateTx(w, plain, e))
      no(executor(ctx), fiLiquidateTx(w, breached, e))
      ok(borrower(ctx), payTx(w, plain, e))
      // The block before the miss is in the quiet zone: Record waits it out.
      no(executor(ctx), recordTx(w, plain, e, 0L))
      ok(executor(ctx), recordTx(w, plain, h - grace / 2 - 1, 0L))
      ok(borrower(ctx), cureTx(w, breached, e))
      ok(borrower(ctx), recollatTx(w, plain, e))
    }
  }

  property("anti-dodge: at a breach's deadline the same; Record is shut while the breach is open") {
    withCtx { ctx =>
      val w = world(ctx)
      val d = w.due(0) + 100
      val b = at(w, paid = 1L, last = 0L, deadline = d.toLong)
      ok(executor(ctx), fiLiquidateTx(w, b, d))
      no(borrower(ctx), payTx(w, b, d))
      no(borrower(ctx), cureTx(w, b, d))
      no(borrower(ctx), recollatTx(w, b, d))
      no(executor(ctx), fiLiquidateTx(w, b, d - 1))
      ok(borrower(ctx), payTx(w, b, d - 1))
      ok(borrower(ctx), cureTx(w, b, d - 1))
      ok(borrower(ctx), recollatTx(w, b, d - 1))
    }
  }

  // ═══ QUIET ZONE ═══════════════════════════════════════════════════════════
  // Q = min(720, grace / 2) blocks before the next unpaid coupon's miss height, Record breach and Refresh
  // are refused; C = min(720, cure / 2) blocks before an open breach's deadline, Refresh is refused. Every
  // refusal here is checked against the bond with the zone taken out (`noZone`, `noDeadlineZone`): the
  // same transaction signs there, so the zone, and nothing else, refused it.

  private val unzoned = scala.collection.mutable.Map.empty[Boolean, work.lithos.mutations.Contract]

  private def noZone(w: W): work.lithos.mutations.Contract = unzoned.getOrElseUpdate(w.erg,
    FiStandardSpecBase.bondVariant(w.ctx, repaymentBytes(w), fi(w.ctx).couponVault, "(if (liquidatable) refresh else !quiet)" -> "(refresh || !liquidatable)"))

  /** Refused by the bond, signed by the same bond without the zone. */
  private def quietRefuses(p: ErgoProver, w: W, box: UTXO)(tx: InputUTXO => Tx): Unit = {
    no(p, tx(fiIn(w, box)))
    ok(p, tx(fiIn(w, box.setContract(noZone(w)))))
  }

  /** Refused with and without the zone: the existing rules refuse it. */
  private def refusedEither(p: ErgoProver, w: W, box: UTXO)(tx: InputUTXO => Tx): Unit = {
    no(p, tx(fiIn(w, box)))
    no(p, tx(fiIn(w, box.setContract(noZone(w)))))
  }

  /** A world whose first coupon falls due `offset` blocks after the start, with grace `g`. */
  private def scheduled(ctx: BlockchainContext, kind: Kind = TokenBond, offset: Long = firstOffset.toLong,
                        g: Long = grace.toLong, life: Long = 2000L): W = {
    val w = world(ctx, kind, life = life)
    w.copy(terms = w.terms.updated(9, offset).updated(11, g))
  }

  for (kind <- kinds) {
    property(s"quiet zone ($kind): Record breach is refused from miss - grace / 2 to the block before the miss; at the miss as before") {
      withCtx { ctx =>
        val w = world(ctx, kind)
        val miss = w.due(0) + grace
        val zone = miss - grace / 2
        ok(executor(ctx), recordTx(w, at(w), zone - 1, 0L))
        Seq(zone, zone + 1, miss - 1).foreach(h => quietRefuses(executor(ctx), w, fiBox(w))(b => recordTx(w, b, h, 0L)))
        // At the miss height the coupon is missed: liquidatable, so Record is refused with or without the
        // zone, and the liquidation signs.
        refusedEither(executor(ctx), w, fiBox(w))(b => recordTx(w, b, miss, 0L))
        ok(executor(ctx), fiLiquidateTx(w, at(w), miss))
      }
    }
  }

  property("quiet zone: Refresh is refused from miss - grace / 2 to the block before the miss; at the miss it opens again, while liquidatable") {
    withCtx { ctx =>
      for (kind <- kinds) {
        // The first coupon falls due after the refresh age, so the box is old enough to refresh at its zone.
        val w = scheduled(ctx, kind, offset = RefreshAge + 100L, life = 700000L)
        val miss = w.start + RefreshAge + 100 + grace
        val zone = miss - grace / 2
        ok(executor(ctx), refreshTx(w, at(w), zone - 1))
        Seq(zone, miss - 1).foreach(h => quietRefuses(executor(ctx), w, fiBox(w))(b => refreshTx(w, b, h)))
        ok(executor(ctx), refreshTx(w, at(w), miss))
        ok(executor(ctx), fiLiquidateTx(w, at(w), miss))
        // With every coupon paid there is no next coupon and no zone.
        ok(executor(ctx), refreshTx(w, at(w, paid = 3L), miss - 1))
        // A coupon paid ahead moves the zone on: with coupon 0 paid, its last blocks are open to Refresh.
        ok(executor(ctx), refreshTx(w, at(w, paid = 1L), miss - 1))
        quietRefuses(executor(ctx), w, fiBox(w, paid = 1L))(b => refreshTx(w, b, miss + period - 1))
      }
    }
  }

  property("quiet zone: while liquidatable Refresh ignores the zone; a miss height past maturity shuts no Refresh after maturity") {
    withCtx { ctx =>
      for (kind <- kinds) {
        // Coupon 0 falls due 50 blocks before maturity with a grace of 200: its miss is maturity + 150 and its
        // zone [maturity + 50, maturity + 150), all of it after maturity.
        val life = RefreshAge + 1000L
        val w = scheduled(ctx, kind, offset = life - 50L, g = 200L, life = life)
        val m = w.maturity.toInt
        Seq(m + 50, m + 149).foreach(h => ok(executor(ctx), refreshTx(w, at(w), h)))
        ok(executor(ctx), fiLiquidateTx(w, at(w), m + 50))
        // The bond as it was, the zone applied while liquidatable, refuses the same Refresh.
        val old = FiStandardSpecBase.bondVariant(ctx, repaymentBytes(w), fi(ctx).couponVault,
          "(if (liquidatable) refresh else !quiet)" -> "(refresh || !liquidatable) && !quiet")
        no(executor(ctx), refreshTx(w, fiIn(w, fiBox(w).setContract(old)), m + 50))
        ok(executor(ctx), refreshTx(w, fiIn(w, fiBox(w).setContract(old)), m + 150))
        // After maturity no Pay is open, so the zone had nothing to protect.
        no(borrower(ctx), payTx(w, at(w), m + 50))
      }
    }
  }

  property("quiet zone: a Pay moves the zone to the next coupon; Pay, Cure, Recollateralize and repayment sign inside it") {
    withCtx { ctx =>
      val w = world(ctx)
      val miss = w.due(0) + grace
      val h = miss - 2
      quietRefuses(executor(ctx), w, fiBox(w))(b => recordTx(w, b, h, 0L))
      // The borrower pays coupon 0 inside the zone; on the successor checkpoint 0 records at the same height.
      val next = spendOutput(ok(borrower(ctx), payTx(w, at(w), h)), 0)
      ok(executor(ctx), recordTx(w, next, h, 0L))
      // The zone is now coupon 1's.
      val miss1 = w.due(1) + grace
      ok(executor(ctx), recordTx(w, next, miss1 - grace / 2 - 1, 1L))
      quietRefuses(executor(ctx), w, fiBox(w, paid = 1L))(b => recordTx(w, b, miss1 - grace / 2, 1L))
      // The borrower's own moves and a repayment are not held back by the zone.
      val open = at(w, last = 0L, deadline = miss + 50L)
      ok(borrower(ctx), cureTx(w, open, h))
      ok(borrower(ctx), recollatTx(w, at(w), h))
      ok(borrower(ctx), fiRepayTx(w, at(w), h))
      ok(other(ctx), fiRepayTx(w, at(w), h, payer = other(ctx)))
    }
  }

  property("quiet zone: short grace 4 has a 2-block zone, and so do grace 1 and 0 (the 2-block minimum); a long grace caps it at 720") {
    withCtx { ctx =>
      // Grace 4: Q = 2. Checkpoint 0 records at due and due + 1, not at due + 2 or + 3; due + 4 is the miss.
      val g4 = scheduled(ctx, g = 4L)
      val d = g4.due(0)
      Seq(d, d + 1).foreach(h => ok(executor(ctx), recordTx(g4, at(g4), h, 0L)))
      Seq(d + 2, d + 3).foreach(h => quietRefuses(executor(ctx), g4, fiBox(g4))(b => recordTx(g4, b, h, 0L)))
      refusedEither(executor(ctx), g4, fiBox(g4))(b => recordTx(g4, b, d + 4, 0L))
      ok(borrower(ctx), payTx(g4, at(g4), d + 3))
      // Grace 1: the zone is the 2-block minimum, [due - 1, due + 1): Record at due, its one unpaid block, is
      // refused by it; paid, it records. due + 1 is the miss.
      val g1 = scheduled(ctx, g = 1L)
      quietRefuses(executor(ctx), g1, fiBox(g1))(b => recordTx(g1, b, d, 0L))
      ok(executor(ctx), recordTx(g1, at(g1, paid = 1L), d, 0L))
      refusedEither(executor(ctx), g1, fiBox(g1))(b => recordTx(g1, b, d + 1, 0L))
      ok(executor(ctx), fiLiquidateTx(g1, at(g1), d + 1))
      // Grace 0: the due height is the miss, so an unpaid coupon's checkpoint never records; a coupon paid
      // ahead leaves its checkpoint open for its one block.
      val g0 = scheduled(ctx, g = 0L)
      refusedEither(executor(ctx), g0, fiBox(g0))(b => recordTx(g0, b, d, 0L))
      ok(executor(ctx), fiLiquidateTx(g0, at(g0), d))
      ok(executor(ctx), recordTx(g0, at(g0, paid = 1L), d, 0L))
      ok(borrower(ctx), payTx(g0, at(g0), d - 1))
      // Grace 2000: Q = 720, not 1000. The block before the zone records; the zone's first block does not.
      val long = scheduled(ctx, g = 2000L, life = 10000L)
      val miss = long.due(0) + 2000
      Seq(miss - 1000, miss - 721).foreach(h => ok(executor(ctx), recordTx(long, at(long), h, 0L)))
      Seq(miss - 720, miss - 1).foreach(h => quietRefuses(executor(ctx), long, fiBox(long))(b => recordTx(long, b, h, 0L)))
    }
  }

  // The cure-deadline zone alone taken out: the coupon zone stays.
  private val deadlineZone = " ||\n                (refresh && deadline > 0L && height < deadline &&\n" +
    "                height >= deadline - min(720L, borrower(13) / 2L))"
  private val undeadlined = scala.collection.mutable.Map.empty[Boolean, work.lithos.mutations.Contract]

  private def noDeadlineZone(w: W): work.lithos.mutations.Contract = undeadlined.getOrElseUpdate(w.erg,
    FiStandardSpecBase.bondVariant(w.ctx, repaymentBytes(w), fi(w.ctx).couponVault, deadlineZone -> ""))

  for (kind <- kinds) {
    property(s"quiet zone ($kind): with a breach open, Refresh is refused in the last min(720, cure / 2) blocks before the deadline; Cure and repayment sign there") {
      withCtx { ctx =>
        // Every coupon paid, so no coupon zone; the box is old enough to refresh. Cure windows 30, 100
        // and 2000 give zones of 15, 50 and 720 (capped) blocks.
        for ((cure, c) <- Seq(30L -> 15, cureBlocks.toLong -> 50, 2000L -> 720)) withClue(s"cure window $cure: ") {
          val base = world(ctx, kind, life = 700000L)
          val w = base.copy(terms = base.terms.updated(13, cure))
          val d = w.start + RefreshAge + 3000
          val open = fiBox(w, paid = 3L, last = 0L, deadline = d.toLong)
          val zone = d - c
          // One block before the zone, Refresh signs.
          ok(executor(ctx), refreshTx(w, fiIn(w, open), zone - 1))
          Seq(zone, zone + 1, d - 1).foreach { h =>
            withClue(s"at deadline - ${d - h}: ") {
              no(executor(ctx), refreshTx(w, fiIn(w, open), h))
              ok(executor(ctx), refreshTx(w, fiIn(w, open.setContract(noDeadlineZone(w))), h))
              ok(borrower(ctx), cureTx(w, fiIn(w, open), h))
              ok(borrower(ctx), fiRepayTx(w, fiIn(w, open), h))
              ok(other(ctx), fiRepayTx(w, fiIn(w, open), h, payer = other(ctx)))
            }
          }
          // From the deadline the bond is liquidatable: Refresh opens again, beside the liquidation.
          ok(executor(ctx), refreshTx(w, fiIn(w, open), d))
          ok(executor(ctx), fiLiquidateTx(w, fiIn(w, open), d))
          // With the breach cured no deadline zone remains.
          ok(executor(ctx), refreshTx(w, at(w, paid = 3L, last = 0L), d - 1))
        }
      }
    }
  }

  property("quiet zone: Record breach never meets the deadline zone, which needs a breach open; Pay signs inside it") {
    withCtx { ctx =>
      // Coupon 2 falls due at s + RefreshAge + 1400, its coupon zone is [due + 10, due + 20). A breach of
      // checkpoint 1 is open until d = due + 10, so the deadline zone (cure 100: 50 blocks) is [d - 50, d).
      val w = scheduled(ctx, offset = RefreshAge + 1000L, life = 700000L)
      val due2 = w.start + RefreshAge + 1000 + 2 * period
      val d = due2 + 10
      val h = due2 + 5
      val open = fiBox(w, paid = 2L, last = 1L, deadline = d.toLong)
      // h is inside the deadline zone, outside the coupon zone, and inside checkpoint 2's window.
      no(executor(ctx), refreshTx(w, fiIn(w, open), h))
      ok(executor(ctx), refreshTx(w, fiIn(w, open.setContract(noDeadlineZone(w))), h))
      // Record of checkpoint 2 there: refused by the open breach, with or without the zone ...
      no(executor(ctx), recordTx(w, fiIn(w, open), h, 2L))
      no(executor(ctx), recordTx(w, fiIn(w, open.setContract(noDeadlineZone(w))), h, 2L))
      // ... and signed at the same height once the breach is cured.
      ok(executor(ctx), recordTx(w, at(w, paid = 2L, last = 1L), h, 2L))
      // The borrower's moves inside the zone.
      ok(borrower(ctx), payTx(w, fiIn(w, open), h))
      ok(borrower(ctx), cureTx(w, fiIn(w, open), h))
      ok(borrower(ctx), recollatTx(w, fiIn(w, open), h))
      ok(borrower(ctx), fiRepayTx(w, fiIn(w, open), h))
    }
  }

  // ═══ REPAY ════════════════════════════════════════════════════════════════

  for (kind <- kinds) {
    property(s"repay ($kind): as ZC, before maturity; coupons not yet paid are not owed; paid ones stay collectable") {
      withCtx { ctx =>
        val w = world(ctx, kind)
        val h = w.due(0) - 10
        val paidTx = ok(borrower(ctx), payTx(w, at(w), h))
        val b = spendOutput(paidTx, 0)
        val settledTx = ok(borrower(ctx), fiRepayTx(w, b, h + 1))
        val vault = spendOutput(settledTx, 0)
        vault.registers(1) shouldBe ErgoValue.of(0.toByte)
        spendOutput(settledTx, 1).value shouldBe w.collateralErg
        // Coupon 0 was paid before the repayment: its holder still collects it, then claims the vault.
        ok(lender(ctx), collectTx(w, Seq(spendOutput(paidTx, 1)), Seq(unit(ctx, h)), h + 2))
        ok(lender(ctx), collectTx(w, Seq(vault, spendOutput(paidTx, 1)), Seq(unit(ctx, h)), h + 2,
          outs = Seq(UTXO(lenderTree(ctx), minBox)), burn = Seq(Token(bondAsset, 3L))))
        // A default is final: repayment is shut from the block the bond becomes liquidatable.
        // A coupon missed: open through the last block of its grace, shut from the miss height.
        ok(borrower(ctx), fiRepayTx(w, at(w), w.due(0) + grace - 1))
        no(borrower(ctx), fiRepayTx(w, at(w), w.due(0) + grace))
        no(borrower(ctx), fiRepayTx(w, at(w, paid = 1L), w.due(1) + grace))
        // A breach open: open until its cure deadline, shut from it, though every coupon is paid.
        val open = at(w, paid = 3L, last = 0L, deadline = w.due(0) + 50L)
        ok(borrower(ctx), fiRepayTx(w, open, w.due(0) + 49))
        no(borrower(ctx), fiRepayTx(w, open, w.due(0) + 50))
        // Maturity, with every coupon paid.
        ok(borrower(ctx), fiRepayTx(w, at(w, paid = 3L), w.maturity.toInt - 1))
        no(borrower(ctx), fiRepayTx(w, at(w, paid = 3L), w.maturity.toInt))
        // The clause is what refuses: the bond with ZC's maturity test in its place repays after a miss.
        val zcRule = FiStandardSpecBase.bondVariant(ctx, if (w.erg) Array.emptyByteArray else SigUSD.getBytes,
          fi(ctx).couponVault, "!liquidatable &&\n                            toVault" ->
            "height < maturity &&\n                            toVault")
        val zb = fiIn(w, fiBox(w).setContract(zcRule))
        ok(borrower(ctx), fiRepayTx(w, zb, w.due(0) + grace))
      }
    }
  }

  property("repay: the settlement and the return as ZC; a third party repays but keeps nothing back") {
    withCtx { ctx =>
      val w = world(ctx)
      val b = at(w)
      val h = w.due(0)
      val settle = repaid(w, b)
      ok(other(ctx), fiRepayTx(w, b, h, payer = other(ctx)))
      Seq(settle.setTokens(Token(bondAsset, 2L), Token(SigUSD, w.repayment - 1L)), settle.setContract(otherTree(ctx)),
        settle.withRegNum(4, bytesValue(originOrderId)), settle.withRegNum(5, ErgoValue.of(1.toByte)))
        .foreach(s => no(borrower(ctx), fiRepayTx(w, b, h, settle = s)))
      val back = returned(w, b)
      Seq(back.subValue(1L), back.setContract(otherTree(ctx)), back.withRegNum(4, bytesValue(originOrderId)))
        .foreach(r => no(other(ctx), fiRepayTx(w, b, h, back = r, payer = other(ctx))))
    }
  }

  // ═══ SETTLEMENT STAMP ═════════════════════════════════════════════════════

  for (kind <- kinds) {
    property(s"settlement stamp ($kind): repay and liquidate need the settlement created at HEIGHT - 15 or later, so the share vault's 720-block wait cannot be shortened") {
      withCtx { ctx =>
        val w = world(ctx, kind)
        val h = w.due(0) + 100
        // Repayment, the borrower's box created 20 blocks back so an earlier stamp passes the node's monotonic rule.
        val b = at(w, paid = 1L)
        def repay(stamp: Int): Tx = txAt(ctx, h, Seq(vars(b, Seq(1 -> int(0), 2 -> int(1))),
          wallet(ctx, borrower(ctx), 6, h - 20, borrowerFunds)),
          Seq(repaid(w, b).setCreationHeight(stamp), returned(w, b)), borrower(ctx).getAddress)
        Seq(h, h - 15).foreach(t => withClue(s"repay stamped $t: ")(ok(borrower(ctx), repay(t))))
        Seq(h - 16, h - 720, w.start).foreach(t => withClue(s"repay stamped $t: ")(no(borrower(ctx), repay(t))))
        // Liquidation (a coupon missed), with the agreed expression and with an always-true one the bond pins: the
        // bond checks the stamp itself, so no expression can drop it.
        val missed = w.due(0) + grace + 100
        Seq(fi(ctx).payout, alwaysTrue(ctx)).foreach { liq =>
          val lb = fiIn(w, fiBox(w, liq = liq))
          def liquidate(stamp: Int): Tx = fiLiquidateTx(w, lb, missed, out = proceeds(w, lb).setCreationHeight(stamp), ran = liq)
          Seq(missed, missed - 15).foreach(t => withClue(s"liquidate stamped $t: ")(ok(executor(ctx), liquidate(t))))
          Seq(missed - 16, missed - 720).foreach(t => withClue(s"liquidate stamped $t: ")(no(executor(ctx), liquidate(t))))
        }
        // The clause is what refuses: the bond without it signs the backdated repayment.
        val unpinned = FiStandardSpecBase.bondVariant(ctx, if (w.erg) Array.emptyByteArray else SigUSD.getBytes,
          fi(ctx).couponVault, " &&\n                    settlement.creationInfo._1 >= HEIGHT - 15" -> "")
        val ub = fiIn(w, fiBox(w, paid = 1L).setContract(unpinned))
        ok(borrower(ctx), txAt(ctx, h, Seq(vars(ub, Seq(1 -> int(0), 2 -> int(1))),
          wallet(ctx, borrower(ctx), 6, h - 20, borrowerFunds)),
          Seq(repaid(w, ub).setCreationHeight(h - 16), returned(w, ub)), borrower(ctx).getAddress))
      }
    }
  }

  // ═══ RECOLLATERALIZE ══════════════════════════════════════════════════════

  property("recollateralize: the 60-block wait reads R9(6) and the successor restarts it within 15 blocks") {
    withCtx { ctx =>
      val w = world(ctx)
      val b = at(w)
      val open = w.start + recollateralizeWait
      no(borrower(ctx), recollatTx(w, b, open - 1))
      ok(borrower(ctx), recollatTx(w, b, open))
      ok(borrower(ctx), recollatTx(w, b, open, next = regrown(w, b, open, clock = open - 15L)))
      no(borrower(ctx), recollatTx(w, b, open, next = regrown(w, b, open, clock = open - 16L)))
      no(borrower(ctx), recollatTx(w, b, open, next = regrown(w, b, open, clock = open + 1L)))
      no(borrower(ctx), recollatTx(w, b, open, next = regrown(w, b, open, clock = w.start.toLong)))
      no(other(ctx), recollatTx(w, b, open, by = other(ctx)))
      // The next move waits 60 blocks from the new clock.
      val moved = spendOutput(ok(borrower(ctx), recollatTx(w, b, open)), 0)
      no(borrower(ctx), recollatTx(w, moved, open + 59))
      ok(borrower(ctx), recollatTx(w, moved, open + 60))
    }
  }

  property("recollateralize: the box's creation height is not read; Pay, Record and Cure leave the clock alone") {
    withCtx { ctx =>
      val w = world(ctx)
      val open = w.start + recollateralizeWait
      // A box stamped a block ago, with an old clock, recollateralizes; a box stamped long ago, with a
      // new clock, waits.
      ok(borrower(ctx), recollatTx(w, at(w, stamp = open - 1), open))
      no(borrower(ctx), recollatTx(w, at(w, clock = open - 30L, stamp = w.start), open))
      val h = w.due(0) - 10
      val payed = spendOutput(ok(borrower(ctx), payTx(w, at(w), h)), 0)
      ok(borrower(ctx), recollatTx(w, payed, h + 1))
      val breached = spendOutput(ok(executor(ctx), recordTx(w, payed, w.due(0) + 5, 0L)), 0)
      ok(borrower(ctx), recollatTx(w, breached, w.due(0) + 6))
      val clear = spendOutput(ok(borrower(ctx), cureTx(w, breached, w.due(0) + 6)), 0)
      r9(clear)(6) shouldBe w.start.toLong
      ok(borrower(ctx), recollatTx(w, clear, w.due(0) + 7))
    }
  }

  property("recollateralize: keeps the coupon state, so it cannot close a breach; ZC's rules for what may be added") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = w.due(0) + 5
      val b = at(w, last = 0L, deadline = h + 50L)
      val next = regrown(w, b, h)
      ok(borrower(ctx), recollatTx(w, b, h, next = toppedUp(w, next)))
      // Paid count up, the breach record back to none, the deadline cleared: each refused.
      Seq(3 -> 1L, 4 -> -1L, 5 -> 0L).foreach { case (i, v) =>
        withClue(s"R9($i)")(no(borrower(ctx), recollatTx(w, b, h, next = withR9(next, i, _ => v))))
      }
      immutables(w, next).foreach { case (name, bad) => withClue(name)(no(borrower(ctx), recollatTx(w, b, h, next = bad))) }
      no(borrower(ctx), recollatTx(w, b, h, next = next.subValue(1L)))
      ok(borrower(ctx), recollatTx(w, b, h, next = next.subValue(1L).setTokens(next.tokens :+ Token(SigUSD, 1L): _*)))
      no(borrower(ctx), recollatTx(w, b, h, next = next.setTokens(next.tokens :+ Token(secondCollateral, 1L): _*)))
      val (lower, burn) = lowerCollateral(next.addValue(Parameters.OneErg))
      no(borrower(ctx), recollatTx(w, b, h, next = lower, burn = burn))
    }
  }

  // ═══ REFRESH ══════════════════════════════════════════════════════════════

  property("refresh: as ZC, but before maturity it takes nothing (no 0.01 ERG bounty until maturity); R9 is kept whole, an open breach included; open while liquidatable") {
    withCtx { ctx =>
      val w = world(ctx, life = 700000L)
      val b = at(w)
      val h = w.start + RefreshAge + 1
      // This bond has missed every coupon by now: liquidatable, and refreshed all the same.
      ok(executor(ctx), fiLiquidateTx(w, b, h))
      ok(executor(ctx), refreshTx(w, b, h))
      no(executor(ctx), refreshTx(w, b, h - 1))
      no(executor(ctx), refreshTx(w, b, h, next = refreshed(b, h).subValue(1L)))
      // Before maturity the 0.01 ERG bounty is refused (round 4: it could tip a bond at its ratio into a breach).
      no(executor(ctx), refreshTx(w, b, h, next = refreshed(b, h).subValue(Bounty)))
      (0 until 7).foreach(i => withClue(s"R9($i)")(no(executor(ctx), refreshTx(w, b, h, next = withR9(refreshed(b, h), i, _ + 1L)))))
      immutables(w, refreshed(b, h)).foreach { case (name, bad) => withClue(name)(no(executor(ctx), refreshTx(w, b, h, next = bad))) }
      val (lower, burn) = lowerCollateral(refreshed(b, h))
      no(executor(ctx), refreshTx(w, b, h, next = lower, burn = burn))
      Seq(h - 60, h - 999).foreach(s => ok(executor(ctx), refreshTx(w, b, h, next = refreshed(b, h).setCreationHeight(s))))
      Seq(h - 59, h, h - 1000).foreach(s => no(executor(ctx), refreshTx(w, b, h, next = refreshed(b, h).setCreationHeight(s))))
      // An open breach whose deadline lies beyond its quiet zone (the last cure / 2 = 50 blocks).
      val open = at(w, paid = 3L, last = 0L, deadline = h + 100L)
      r9(spendOutput(ok(executor(ctx), refreshTx(w, open, h)), 0)) shouldBe r9(open)
      // From maturity on the bounty is the keeper's pay: up to 0.01 ERG, a nanoERG more refused.
      val m = w.maturity.toInt
      ok(executor(ctx), refreshTx(w, b, m, next = b.toUTXO.subValue(Bounty).setCreationHeight(m - 60)))
      no(executor(ctx), refreshTx(w, b, m, next = b.toUTXO.subValue(Bounty + 1L).setCreationHeight(m - 60)))
      // There the floor binds before the bounty; before maturity a thin box keeps all of its value.
      val thin = fiIn(w, fiBox(w, value = batchingFee + 6000000L))
      ok(executor(ctx), refreshTx(w, thin, m, next = thin.toUTXO.setValue(batchingFee + minBox).setCreationHeight(m - 60)))
      no(executor(ctx), refreshTx(w, thin, m, next = thin.toUTXO.setValue(batchingFee + minBox - 1L).setCreationHeight(m - 60)))
      no(executor(ctx), refreshTx(w, thin, h, next = thin.toUTXO.setValue(batchingFee + minBox).setCreationHeight(h - 60)))
      ok(executor(ctx), refreshTx(w, thin, h))
    }
  }

  property("refresh: not in the 720 blocks before maturity; from maturity on, again") {
    withCtx { ctx =>
      val w = world(ctx, life = 600000L)
      val b = at(w, paid = 3L)
      val m = w.maturity.toInt
      ok(executor(ctx), refreshTx(w, b, m - 721))
      Seq(m - 720, m - 1).foreach(h => no(executor(ctx), refreshTx(w, b, h)))
      Seq(m, m + 100).foreach(h => ok(executor(ctx), refreshTx(w, b, h)))
      // A refreshed bond recollateralizes at once: its clock is old.
      val r = spendOutput(ok(executor(ctx), refreshTx(w, b, m - 721)), 0)
      ok(borrower(ctx), recollatTx(w, r, m - 721))
    }
  }

  // ═══ CROSS-PATH ═══════════════════════════════════════════════════════════

  property("cross-path: two path variables at once fail; settlement variables beside a path select nothing") {
    withCtx { ctx =>
      val w = world(ctx)
      val b = at(w)
      val h = w.due(0) + 5
      val breached = at(w, last = 0L, deadline = h + 50L)
      val cov = 8 -> covVar(w.covenant)
      val liq = 0 -> liqVar(fi(ctx).payout)
      ok(borrower(ctx), payTx(w, b, h))
      Seq(6 -> int(0), 7 -> int(0), 3 -> int(0), 9 -> int(0), liq).foreach { extra =>
        withClue(s"pay + var ${extra._1}")(no(borrower(ctx), payTx(w, b, h, vs = Seq(4 -> int(0), 5 -> int(1), extra))))
      }
      ok(executor(ctx), recordTx(w, b, h, 0L))
      Seq(7 -> int(0), 4 -> int(0), 3 -> int(0), liq).foreach { extra =>
        withClue(s"record + var ${extra._1}")(no(executor(ctx), recordTx(w, b, h, 0L, vs = Seq(6 -> int(0), cov, extra))))
      }
      ok(borrower(ctx), cureTx(w, breached, h))
      Seq(3 -> int(0), 6 -> int(0), 9 -> int(0), liq).foreach { extra =>
        withClue(s"cure + var ${extra._1}")(no(borrower(ctx), cureTx(w, breached, h, vs = Seq(7 -> int(0), cov, extra))))
      }
      ok(borrower(ctx), recollatTx(w, b, h))
      Seq(4 -> int(0), 9 -> int(0), liq).foreach { extra =>
        withClue(s"recollateralize + var ${extra._1}")(no(borrower(ctx), recollatTx(w, b, h, vs = Seq(3 -> int(0), extra))))
      }
      val missed = w.due(0) + grace
      ok(executor(ctx), fiLiquidateTx(w, b, missed))
      Seq(3 -> int(0), 4 -> int(0), 9 -> int(0)).foreach { extra =>
        withClue(s"liquidation + var ${extra._1}")(no(executor(ctx), fiLiquidateTx(w, b, missed, vs = Seq(liq, 1 -> int(0), extra))))
      }
      val lw = world(ctx, life = 700000L)
      val lb = at(lw, paid = 3L)
      val rh = lw.start + RefreshAge + 1
      ok(executor(ctx), refreshTx(lw, lb, rh))
      Seq(0 -> liqVar(fi(ctx).payout), 4 -> int(0), 3 -> int(0)).foreach { extra =>
        withClue(s"refresh + var ${extra._1}")(no(executor(ctx), refreshTx(lw, lb, rh, vs = Seq(9 -> int(0), extra))))
      }
      // Vars 1 and 2 beside Pay are not read: the transaction is a Pay and nothing else.
      ok(borrower(ctx), payTx(w, b, h, vs = Seq(1 -> int(1), 2 -> int(0), 4 -> int(0), 5 -> int(1))))
    }
  }

  property("cross-path: with no path variable the bond can only settle; no path is reached by omission") {
    withCtx { ctx =>
      val w = world(ctx)
      val b = at(w)
      val h = w.due(0) + 5
      no(borrower(ctx), payTx(w, b, h, vs = Seq.empty))
      no(borrower(ctx), payTx(w, b, h, vs = Seq(5 -> int(1))))
      no(executor(ctx), recordTx(w, b, h, 0L, vs = Seq(8 -> covVar(w.covenant))))
      no(borrower(ctx), recollatTx(w, b, h, vs = Seq(1 -> int(0), 2 -> int(0))))
    }
  }

  // ═══ ABSENT CASES ═════════════════════════════════════════════════════════

  property("absent-case: each path signs with only its own variables, outputs and data inputs") {
    withCtx { ctx =>
      // Each spend carries no variable, output or data input of any other path. A shared expression
      // hoisted above its guard (getVar(5).get, getVar(7).get, the coupon vault output, the oracle) would
      // throw here even though the path is valid.
      for (kind <- kinds) {
        val w = world(ctx, kind)
        val h = w.due(0) + 5
        ok(borrower(ctx), payTx(w, at(w), h))                                        // 4, 5; no data input
        ok(executor(ctx), recordTx(w, at(w), h, 0L))                                 // 6, 8; no var 7, one output
        ok(borrower(ctx), cureTx(w, at(w, last = 0L, deadline = h + 50L), h))        // 7, 8; no var 6
        ok(borrower(ctx), recollatTx(w, at(w), h))                                   // 3 only
        ok(executor(ctx), fiLiquidateTx(w, at(w), w.due(0) + grace))                 // 0, 1 only
        ok(borrower(ctx), fiRepayTx(w, at(w), h))                                    // 1, 2 only
        val lw = world(ctx, kind, life = 700000L)
        ok(executor(ctx), refreshTx(lw, at(lw), lw.start + RefreshAge + 1))          // 9 only
        // Another path's variable left over and pointing nowhere is not read either.
        ok(executor(ctx), recordTx(w, at(w), h, 0L, vs = Seq(1 -> int(9), 5 -> int(9), 6 -> int(0), 8 -> covVar(w.covenant))))
        ok(borrower(ctx), cureTx(w, at(w, last = 0L, deadline = h + 50L), h, vs = Seq(2 -> int(9), 5 -> int(9), 7 -> int(0), 8 -> covVar(w.covenant))))
        ok(borrower(ctx), payTx(w, at(w), h, vs = Seq(4 -> int(0), 5 -> int(1), 8 -> covVar(w.covenant))))
        ok(executor(ctx), fiLiquidateTx(w, at(w), w.due(0) + grace, vs = Seq(0 -> liqVar(fi(ctx).payout), 1 -> int(0), 5 -> int(9), 8 -> covVar(w.covenant))))
      }
    }
  }

  property("successor index: past the outputs, or negative, fails every path") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = w.due(0) + 5
      val breached = at(w, last = 0L, deadline = h + 50L)
      Seq(-1, 5).foreach { i =>
        no(borrower(ctx), payTx(w, at(w), h, vs = Seq(4 -> int(i), 5 -> int(1))))
        no(executor(ctx), recordTx(w, at(w), h, 0L, vs = Seq(6 -> int(i), 8 -> covVar(w.covenant))))
        no(borrower(ctx), cureTx(w, breached, h, vs = Seq(7 -> int(i), 8 -> covVar(w.covenant))))
        no(borrower(ctx), recollatTx(w, at(w), h, vs = Seq(3 -> int(i))))
      }
    }
  }

  property("shape: R6 outside 15..64 Longs or R9 other than 7 Longs leaves the box unspendable") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = w.due(0) - 10
      ok(borrower(ctx), payTx(w, at(w), h))
      val longest = fiIn(w, fiBox(w, terms = w.terms ++ Seq.fill(64 - w.terms.size)(0L)))
      ok(borrower(ctx), payTx(w, longest, h))
      ok(borrower(ctx), fiRepayTx(w, longest, h))
      Seq("R9 of 6" -> fiBox(w, r9 = fiState(w).take(6)), "R9 of 8" -> fiBox(w, r9 = fiState(w) :+ 0L),
        "R6 of 14" -> fiBox(w, terms = w.terms.take(14)),
        "R6 of 65" -> fiBox(w, terms = w.terms ++ Seq.fill(65 - w.terms.size)(0L))).foreach { case (name, box) =>
        val b = fiIn(w, box)
        withClue(name) {
          no(borrower(ctx), payTx(w, b, h, next = b.toUTXO.setCreationHeight(h)))
          no(borrower(ctx), fiRepayTx(w, b, h))
          no(executor(ctx), fiLiquidateTx(w, b, w.maturity.toInt))
        }
      }
    }
  }

  property("schedule: counted from the bond's own start R9(1), so a later start moves every due height") {
    withCtx { ctx =>
      val w = world(ctx)
      val late = 50
      val start = w.start + late
      val b = fiIn(w, fiBox(w, r9 = fiState(w).updated(1, start.toLong).updated(6, start.toLong), stamp = start))
      no(executor(ctx), fiLiquidateTx(w, b, w.due(0) + grace + late - 1))
      ok(executor(ctx), fiLiquidateTx(w, b, w.due(0) + grace + late))
      ok(borrower(ctx), payTx(w, b, w.due(0) + grace + late - 1))
      no(executor(ctx), recordTx(w, b, w.due(0) + late - 1, 0L))
      ok(executor(ctx), recordTx(w, b, w.due(0) + late, 0L))
    }
  }

  property("schedule: extreme terms: a period of 0 reads as 1; a count of 0 means no coupon liquidation, no Record, no Pay") {
    withCtx { ctx =>
      val base = world(ctx)
      // Period 0, read as 1: coupon k is due at s + 200 + k, and Pay of coupon 0 opens one block early.
      val p0 = base.copy(terms = base.terms.updated(10, 0L))
      ok(borrower(ctx), payTx(p0, at(p0), p0.due(0) - 1))
      no(borrower(ctx), payTx(p0, at(p0), p0.due(0) - 2))
      // With two paid, the third is missed from s + 200 + 2 + grace.
      no(executor(ctx), fiLiquidateTx(p0, at(p0, paid = 2L), p0.due(0) + grace + 1))
      ok(executor(ctx), fiLiquidateTx(p0, at(p0, paid = 2L), p0.due(0) + grace + 2))
      // Count 0: nothing to pay, record or miss; only maturity liquidates.
      val c0 = base.copy(terms = base.terms.updated(8, 0L))
      no(executor(ctx), fiLiquidateTx(c0, at(c0), c0.due(2) + grace))
      ok(executor(ctx), fiLiquidateTx(c0, at(c0), c0.maturity.toInt))
      no(executor(ctx), recordTx(c0, at(c0), c0.due(0) + 5, 0L))
      no(borrower(ctx), payTx(c0, at(c0), c0.due(0) - 10))
      ok(borrower(ctx), fiRepayTx(c0, at(c0), c0.due(0) - 10))
    }
  }

  property("covenant bounds: ratio <= 0, maximum age outside 1..100000 and repayment <= 0 fail both questions; Cure's oracle edges") {
    withCtx { ctx =>
      val base = world(ctx)
      val h = base.due(0) + 5
      def t(i: Int, v: Long) = base.copy(terms = base.terms.updated(i, v))
      def open(w: W): InputUTXO = at(w, last = 0L, deadline = h + 50L)
      // Ratio 0 or below: no Record even far below, no Cure.
      Seq(0L, -1L).foreach { r =>
        no(executor(ctx), recordTx(t(12, r), at(t(12, r)), h, 0L))
        no(borrower(ctx), cureTx(t(12, r), open(t(12, r)), h))
      }
      Seq(0L, 100001L).foreach { a =>
        no(executor(ctx), recordTx(t(14, a), at(t(14, a)), h, 0L, priced = h))
        no(borrower(ctx), cureTx(t(14, a), open(t(14, a)), h, priced = h))
      }
      Seq(1L, 100000L).foreach { a =>
        ok(executor(ctx), recordTx(t(14, a), at(t(14, a)), h, 0L, priced = h - 1))
        ok(borrower(ctx), cureTx(t(14, a), open(t(14, a)), h, priced = h - 1))
      }
      // A repayment of 0 would make Cure trivially "not below"; the guard refuses it. Repayment 1 cures.
      Seq(0L, -1L).foreach(r => no(borrower(ctx), cureTx(t(0, r), open(t(0, r)), h)))
      ok(borrower(ctx), cureTx(t(0, 1L), open(t(0, 1L)), h))
      // Cure's oracle: the freshness edge h - maxAge passes; a future oracle, two pools, an NFT of 2 fail.
      ok(borrower(ctx), cureTx(base, open(base), h, priced = h - maxAge))
      no(borrower(ctx), cureTx(base, open(base), h, priced = h + 1))
      no(borrower(ctx), cureTx(base, open(base), h,
        data = Seq(oracle(ctx, base.slump, h - 5), oracle(ctx, base.slump, h - 5, index = 22))))
      no(borrower(ctx), cureTx(base, open(base), h, data = Seq(oracle(ctx, base.slump, h - 5, amount = 2L))))
    }
  }

  private def secondBond(w: W, index: Int = 1): InputUTXO =
    inputAt(fiBox(w, tokens = Token(otherBondAsset, 2L) +: w.collateral)
      .withRegNum(4, bytesValue(otherBondAsset.getBytes)), w.ctx, index)

  property("two bonds in one transaction: a shared coupon vault or a shared successor is refused; one each signs") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = w.due(0) - 10
      val a = at(w)
      val b = secondBond(w)
      val payer = wallet(ctx, borrower(ctx), 6, h, borrowerFunds)
      def tx(bVars: Seq[(Int, ErgoValue[_])], outs: Seq[UTXO], aVars: Seq[(Int, ErgoValue[_])] = Seq(4 -> int(0), 5 -> int(2))): Tx =
        txAt(ctx, h, Seq(vars(a, aVars), vars(b, bVars), payer), outs, borrower(ctx).getAddress)
      val sa = paid(w, a, h)
      val sb = paid(w, b, h)
      val ca = couponOut(w)
      val cb = couponOut(w, asset = otherBondAsset)
      // One each.
      ok(borrower(ctx), tx(Seq(4 -> int(1), 5 -> int(3)), Seq(sa, sb, ca, cb)))
      // One coupon vault (tagged A) named by both.
      no(borrower(ctx), tx(Seq(4 -> int(1), 5 -> int(2)), Seq(sa, sb, ca)))
      // One successor (A's) named by both, each with its own coupon vault.
      no(borrower(ctx), tx(Seq(4 -> int(0), 5 -> int(2)), Seq(sa, ca, cb), aVars = Seq(4 -> int(0), 5 -> int(1))))
    }
  }

  property("refresh: a successor index past the outputs, or negative, fails") {
    withCtx { ctx =>
      val w = world(ctx, life = 700000L)
      val b = at(w, paid = 3L)
      val h = w.start + RefreshAge + 1
      ok(executor(ctx), refreshTx(w, b, h))
      Seq(-1, 5).foreach(i => no(executor(ctx), refreshTx(w, b, h, vs = Seq(9 -> int(i)))))
    }
  }

  // ═══ COUPON VAULT ═════════════════════════════════════════════════════════

  property("coupon vault: the unit holder collects and keeps the unit; one transaction takes several coupons of one bond") {
    withCtx { ctx =>
      for (kind <- kinds) {
        val w = world(ctx, kind)
        val h = w.due(1)
        val v0 = vaultIn(w, h - 10)
        val v1 = vaultIn(w, h - 5, index = 2)
        ok(lender(ctx), collectTx(w, Seq(v0), Seq(unit(ctx, h)), h))
        val both = ok(lender(ctx), collectTx(w, Seq(v0, v1), Seq(unit(ctx, h)), h))
        both.getOutputsToSpend.get(0).getTokens.get(0).getId shouldBe bondAsset
        println(s"FICOST|collect $kind, 2 coupons, key-held unit=${both.getCost}")
        // A coupon of another bond does not ride along on this unit.
        no(lender(ctx), collectTx(w, Seq(v0, vaultIn(w, h - 5, asset = otherBondAsset, index = 3)), Seq(unit(ctx, h)), h))
      }
    }
  }

  property("coupon vault: a 2-unit spend never collects, so a borrower spending the bond cannot take coupons back") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = w.due(1) - 10
      val b = at(w, paid = 1L)
      val v0 = vaultIn(w, h - 100)
      ok(borrower(ctx), payTx(w, b, h))
      // The same Pay, with the pool spent beside the bond: it must carry the pool forward, coupon on top.
      no(borrower(ctx), payTx(w, b, h, extraIns = Seq(v0)))
      ok(borrower(ctx), payTx(w, b, h, extraIns = Seq(v0), coupon = toppedPool(w, Seq(v0))))
      // Isolated from the bond: a key box holding 2 units takes nothing; holding the 1 unit, it collects.
      no(lender(ctx), collectTx(w, Seq(v0), Seq(unit(ctx, h, amount = 2L)), h))
      ok(lender(ctx), collectTx(w, Seq(v0), Seq(unit(ctx, h)), h))
      // A 2-unit spend the settlement vault itself accepts, its refresh (var 9, a box older than
      // 525600 blocks), takes nothing from a pool: refused beside one that it does not re-create,
      // accepted alone, beside one it re-creates whole, and beside one when the lender's key-held
      // unit makes 3.
      val rh = ctx.getHeight + 600000
      val bv = inputAt(UTXO(scripts(ctx).vault, 5L * minBox, Seq(Token(bondAsset, 2L), Token(SigUSD, 800L)),
        Seq(bytesValue(ErgoId.create("77" * 32).getBytes), ErgoValue.of(0.toByte)), Some(ctx.getHeight)), ctx, 31)
        .withCtxVar(9.toByte, int(0))
      val bvNext = bv.toUTXO.setCreationHeight(rh)
      val old = vaultIn(w, rh - 50)
      val fees = wallet(ctx, executor(ctx), 7, rh - 60)
      no(executor(ctx), txAt(ctx, rh, Seq(bv, old, fees), Seq(bvNext), executor(ctx).getAddress))
      ok(executor(ctx), txAt(ctx, rh, Seq(bv, fees), Seq(bvNext), executor(ctx).getAddress))
      ok(executor(ctx), txAt(ctx, rh, Seq(bv, old, fees), Seq(bvNext, old.toUTXO.setCreationHeight(rh)), executor(ctx).getAddress))
      ok(lender(ctx), txAt(ctx, rh, Seq(bv, old, unit(ctx, rh - 60)),
        Seq(bvNext, UTXO(lenderTree(ctx), minBox, Seq(Token(bondAsset, 1L)))), lender(ctx).getAddress))
    }
  }

  property("coupon vault: the unit may not burn alone; at the vault's claim all 3 burn and the coupon goes too") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = w.due(0) + grace
      val v0 = vaultIn(w, h - 100)
      no(lender(ctx), collectTx(w, Seq(v0), Seq(unit(ctx, h)), h, outs = Seq(UTXO(lenderTree(ctx), minBox)),
        burn = Seq(Token(bondAsset, 1L))))
      val vault = spendOutput(ok(executor(ctx), fiLiquidateTx(w, at(w), h)), 0)
      ok(lender(ctx), collectTx(w, Seq(vault, v0), Seq(unit(ctx, h)), h + 1, outs = Seq(UTXO(lenderTree(ctx), minBox)),
        burn = Seq(Token(bondAsset, 3L))))
    }
  }

  property("coupon vault: 3 units in and out (the borrower holds the unit and spends the bond) collects; burning the unit does not") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = w.due(1) - 10
      val b = at(w, paid = 1L)
      val v0 = vaultIn(w, h - 100)
      val own = unit(ctx, h, holder = borrower(ctx))
      // The borrower holding the unit collects the pool outright...
      ok(borrower(ctx), collectTx(w, Seq(v0), Seq(own), h, by = borrower(ctx)))
      // ...but a Pay that spends the pool must still carry it forward under the new coupon: the bond counts
      // every pool input, whatever path the pool itself takes.
      ok(borrower(ctx), payTx(w, b, h, extraIns = Seq(v0, own), coupon = toppedPool(w, Seq(v0))))
      no(borrower(ctx), payTx(w, b, h, extraIns = Seq(v0, own)))
      no(borrower(ctx), payTx(w, b, h, extraIns = Seq(v0, own), coupon = toppedPool(w, Seq(v0)),
        burn = Seq(Token(bondAsset, 1L))))
    }
  }

  property("coupon vault: another bond's unit, or another token, collects nothing") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = w.due(1)
      val v0 = vaultIn(w, h - 10)
      ok(lender(ctx), collectTx(w, Seq(v0), Seq(unit(ctx, h)), h))
      no(lender(ctx), collectTx(w, Seq(v0), Seq(unit(ctx, h, asset = otherBondAsset)), h))
      no(lender(ctx), collectTx(w, Seq(v0), Seq(unit(ctx, h, asset = collateralToken)), h))
      no(lender(ctx), collectTx(w, Seq(v0), Seq(wallet(ctx, lender(ctx), 8, h)), h, outs = Seq(UTXO(lenderTree(ctx), minBox))))
      // A unit shown as a data input is not spent, so it counts for nothing.
      no(lender(ctx), txAt(ctx, h, Seq(v0, wallet(ctx, lender(ctx), 8, h)), Seq(UTXO(lenderTree(ctx), minBox)),
        lender(ctx).getAddress, data = Seq(unit(ctx, h))))
    }
  }

  for (kind <- kinds) {
    property(s"coupon vault ($kind): a paid coupon is never the borrower's; long after maturity only the unit holder collects it") {
      withCtx { ctx =>
        val w = world(ctx, kind)
        val signed = ok(borrower(ctx), payTx(w, at(w), w.due(0) - 10))
        val v0 = spendOutput(signed, 1)
        v0.registers shouldBe poolRegs()
        (0 +: LongAfter).map(w.maturity.toInt + _).foreach { h =>
          withClue(s"height maturity + ${h - w.maturity}: ") {
            // The borrower, spending an input at the borrower's script, cannot take it; nor can anyone else.
            poolRefuses(borrower(ctx), w, v0)(p => takeTx(w, p, h))
            poolRefuses(other(ctx), w, v0)(p => takeTx(w, p, h, by = other(ctx)))
            // The holder of the unit still collects it.
            ok(lender(ctx), collectTx(w, Seq(v0), Seq(unit(ctx, h)), h))
          }
        }
      }
    }
  }

  property("coupon vault: a contract holding the unit is no key: anyone refunding an expired sale order takes no coupon") {
    withCtx { ctx =>
      val w = world(ctx)
      val h = w.due(1)
      val v0 = vaultIn(w, h - 10)
      // The lender listed the unit on ZC_Standard_SaleOrder; it expired. Anyone may refund it.
      val ask = inputAt(UTXO(exchange(ctx).token.saleOrder, 3000000L, Seq(Token(bondAsset, 1L)),
        Seq(bytesValue(hashOf(lenderTree(ctx))), longsValue(1L, 1L, 1L, (h - 1).toLong)), Some(h - 100)), ctx, 9)
        .withCtxVar(0.toByte, int(0))
      val refund = UTXO(lenderTree(ctx), minBox, Seq(Token(bondAsset, 1L)), Seq(bytesValue(ask.id.getBytes)))
      // The refund alone signs; with the coupon vault beside it, the coupon vault refuses: the unit's
      // home is the sale order, and no output at that script carries the unit and the coupon.
      ok(executor(ctx), txAt(ctx, h, Seq(ask, wallet(ctx, executor(ctx), 7, h)), Seq(refund), executor(ctx).getAddress))
      no(executor(ctx), txAt(ctx, h, Seq(ask, v0, wallet(ctx, executor(ctx), 7, h)), Seq(refund), executor(ctx).getAddress))
      // The cut opens nothing here: the refund pays the seller's script, and no output at the home's script holds
      // the unit. The same refund signs beside the pool moved to the open pool (its contents to change): the pool
      // refused it.
      poolRefuses(executor(ctx), w, v0)(q => txAt(ctx, h, Seq(ask, q, wallet(ctx, executor(ctx), 7, h)), Seq(refund),
        executor(ctx).getAddress))
      // Back in the lender's key box, the unit collects the coupon.
      ok(lender(ctx), collectTx(w, Seq(v0), Seq(unit(ctx, h)), h))
    }
  }

  property("coupon vault: a unit held by a ZC fractional vault collects no token coupon; after the pull the coupon is stuck in its pool") {
    withCtx { ctx =>
      val w = world(ctx)
      val n = 1000L
      val pullFee = 2000000L
      val share = ErgoId.create("cc" * 32)
      val frac = inputAt(UTXO(scripts(ctx).fractionalVault, minBox + pullFee,
        Seq(Token(share, n + 1L), Token(bondAsset, 1L)),
        Seq(bytesValue(bondAsset.getBytes), longsValue(n, n, pullFee)), Some(ctx.getHeight)), ctx, 3)
        .withCtxVar(0.toByte, int(0))
      val missed = w.due(1) + grace
      // The bond paid coupon 0, then missed coupon 1 and was liquidated.
      val paidTx = ok(borrower(ctx), payTx(w, at(w), w.due(0) - 10))
      val coupon0 = spendOutput(paidTx, 1)
      val vault = spendOutput(ok(executor(ctx), fiLiquidateTx(w, spendOutput(paidTx, 0), missed)), 0)
      val pulled = UTXO(scripts(ctx).fractionalVault, frac.value + vault.value - pullFee,
        Token(share, n + 1L) +: vault.tokens.drop(1),
        Seq(bytesValue(bondAsset.getBytes), longsValue(n, n, pullFee), vault.registers(1)))
      // The pull with the coupon vault beside it is refused; without it, it signs.
      no(executor(ctx), txAt(ctx, missed + 1, Seq(frac, vault, coupon0), Seq(pulled), executor(ctx).getAddress,
        burn = Seq(Token(bondAsset, 3L))))
      ok(executor(ctx), txAt(ctx, missed + 1, Seq(frac, vault), Seq(pulled), executor(ctx).getAddress,
        burn = Seq(Token(bondAsset, 3L))))
      // After the pull no unit exists: the pool has no collector. The borrower, the puller (the executor here)
      // and the lender cannot take it, then or long after maturity.
      (Seq(missed + 2) ++ LongAfter.map(w.maturity.toInt + _)).foreach { h =>
        withClue(s"height $h: ") {
          poolRefuses(borrower(ctx), w, coupon0)(p => takeTx(w, p, h))
          poolRefuses(executor(ctx), w, coupon0)(p => takeTx(w, p, h, by = executor(ctx)))
          poolRefuses(lender(ctx), w, coupon0)(p => takeTx(w, p, h, by = lender(ctx)))
        }
      }
    }
  }

  // ═══ AUDIT FIXES ══════════════════════════════════════════════════════════

  property("F-023: a borrower box 1051200 or more blocks old (open to storage rent) authorizes no Pay; one block younger pays") {
    withCtx { ctx =>
      for (kind <- kinds) {
        val base = world(ctx, kind)
        val s = 1200000
        val w = base.copy(start = s, maturity = s + 2000L, terms = base.terms.updated(3, s + 2000L).updated(4, s + 490L))
        val h = w.due(0) - 10
        ok(borrower(ctx), payTx(w, at(w), h, payerCreated = h - 1051199))
        no(borrower(ctx), payTx(w, at(w), h, payerCreated = h - 1051200))
        // The bond without the age term pays from the old box: that term, and nothing else, refused it.
        val ageFree = FiStandardSpecBase.bondVariant(ctx, repaymentBytes(w), fi(ctx).couponVault,
          "&&\n            b.creationInfo._1 > HEIGHT - 1051200" -> "")
        ok(borrower(ctx), payTx(w, fiIn(w, fiBox(w).setContract(ageFree)), h, payerCreated = h - 1051200))
      }
    }
  }

  property("F-012: the covenant reads the repayment asset from the bond's R7: empty is ERG, SigUSD alone is SigUSD, anything else fails") {
    withCtx { ctx =>
      val w = world(ctx, TokenBond)
      val h = w.due(0) + 5
      def withR7(ts: Seq[Token]): InputUTXO = fiIn(w, fiBox(w).withRegNum(7, Protocol.tokensValue(ts)))
      // The SigUSD bond (R7 = SigUSD) at the slump price is below its ratio, and records.
      ok(executor(ctx), recordTx(w, withR7(Seq(Token(SigUSD, 600L))), h, 0L))
      // R7 empty reads as an ERG debt of 800 nanoERG: covered, so no breach.
      no(executor(ctx), recordTx(w, withR7(Seq.empty), h, 0L))
      // R7 naming another asset: neither market, so the covenant fails on both paths.
      no(executor(ctx), recordTx(w, withR7(Seq(Token(collateralToken, 600L))), h, 0L))
      val breached = fiIn(w, fiBox(w, last = 0L, deadline = h + 100L).withRegNum(7, Protocol.tokensValue(Seq(Token(collateralToken, 600L)))))
      no(borrower(ctx), cureTx(w, breached, h))
      ok(borrower(ctx), cureTx(w, at(w, last = 0L, deadline = h + 100L), h))
    }
  }

  /** `u` with a 1000-byte R5 after its R4: the padding the payout rules refuse. */
  private def padR5(u: UTXO): UTXO = u.setRegs(u.registers.take(1) :+ bytesValue(Array.fill(1000)(7.toByte)): _*)

  property("D return box: the repayment's collateral return is stamped within 999 blocks of HEIGHT and carries no R5") {
    withCtx { ctx =>
      for (kind <- kinds) {
        val w = world(ctx, kind)
        val h = w.start + 1100
        val old = h - 1000
        def repay(c: work.lithos.mutations.Contract, f: UTXO => UTXO): Tx = {
          val box = fiBox(w, paid = 3L, stamp = old)
          val b = fiIn(w, if (c == null) box else box.setContract(c))
          fiRepayTx(w, b, h, back = f(returned(w, b)), payerCreated = old)
        }
        ok(borrower(ctx), repay(null, _.setCreationHeight(h - 999)))
        no(borrower(ctx), repay(null, _.setCreationHeight(h - 1000)))
        no(borrower(ctx), repay(null, padR5))
        ok(borrower(ctx), repay(FiStandardSpecBase.bondVariant(ctx, repaymentBytes(w), fi(ctx).couponVault,
          "returnBox.creationInfo._1 > HEIGHT - 1000 &&" -> "true &&"), _.setCreationHeight(h - 1000)))
        ok(borrower(ctx), repay(FiStandardSpecBase.bondVariant(ctx, repaymentBytes(w), fi(ctx).couponVault,
          "returnBox.R5[Coll[Byte]].isDefined == false &&" -> "true &&"), padR5))
      }
    }
  }
}
