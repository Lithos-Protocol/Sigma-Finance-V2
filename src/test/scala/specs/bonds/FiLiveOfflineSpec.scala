package org.lithosprotocol.sigmafi
package specs.bonds

import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.InputUTXO

/**
 * FiLive's four runs on the offline mock chain: the same flow code (FiLiveFlows), with the base trait's
 * test keys as the actors (Alice borrower, Bob lender, Charlie executor, David `other`, Erin `third`), a
 * mocked oracle pool box and stand-in wallet boxes. Every transaction of a run is signed in order, each
 * spending the outputs the previous ones signed, at pinned heights, through FiStandardSpecBase's `ok`, which
 * also applies the node's creation-height and 4096-byte rules; every control is refused through its `no`.
 * The ledger the runner prints is checked too: no SigUSD is created or lost across the actors, and the
 * nanoERG they lose together is exactly the miner fees.
 */
class FiLiveOfflineSpec extends AnyPropSpec with FiLiveFlows {
  import ZcStandardSpecBase._

  /** 0.5 USD per ERG. */
  private val price: Long = 2000000000L

  override protected def signAccepted(p: ErgoProver, t: Tx): SignedTransaction = ok(p, t)
  override protected def signRefused(built: => (Tx, ErgoProver)): Option[String] = {
    val (t, p) = built
    Some(first(no(p, t)))
  }

  private def setup(ctx: BlockchainContext): Int = {
    chain = ctx
    principalUnits = 100L
    val keys = Map("BORROWER" -> borrower(ctx), "LENDER" -> lender(ctx), "KEEPER" -> executor(ctx),
      "DAVID" -> other(ctx), "ERIN" -> third(ctx))
    actors = Roles.map { case (role, display) =>
      role -> Actor(role, display, role, 0, keys(role), keys(role).getAddress)
    }.toMap
    purse = actors.keys.map(_ -> Seq.empty[InputUTXO]).toMap
    standIns = Seq.empty
    failures = Seq.empty
    refusals = Seq.empty
    needs = Map.empty
    highest = 0
    ctx.getHeight
  }

  private def mockOracle(ctx: BlockchainContext, h: Int): () => InputUTXO = {
    val box = oracle(ctx, price, h)
    () => box
  }

  /** The run signed every step; the ledger balances; the controls named were all refused. */
  private def balanced(run: String, controls: Int): Unit = {
    failures shouldBe empty
    refusals.size shouldBe controls
    val n = needs(run)
    n.collect { case ((_, "SIGUSD"), (_, net)) => net }.sum shouldBe 0L
    n.collect { case ((_, "ERG"), (_, net)) => net }.sum shouldBe -signedInRun * txFee
    n.values.foreach { case (short, _) => short should be >= 0L }
  }

  private def net(run: String, tag: String, asset: String): Long = needs(run)((tag, asset))._2

  property("cycle: order, direct fill, coupon 0, coupon 1 into the same box, collect, breach, cure, early repayment, claim") {
    withCtx { ctx =>
      val h = setup(ctx)
      cycleRun(h, price, mockOracle(ctx, h))
      balanced("cycle", 3)
      signedInRun shouldBe 9
      net("cycle", "BOB", "SIGUSD") shouldBe 2L * couponOf(principalUnits)
      net("cycle", "ALICE", "SIGUSD") shouldBe -2L * couponOf(principalUnits)
      // Charlie's record transaction spends only the bond: he keeps the bounty less the miner fee.
      net("cycle", "CHARLIE", "ERG") shouldBe FiStandardSpecBase.RecordBounty - txFee
    }
  }

  property("liquidate: lend offer, order, fill from the offer, coupon 0 missed, liquidation, claim, cancel") {
    withCtx { ctx =>
      val h = setup(ctx)
      liquidateRun(h, price)
      balanced("liquidate", 2)
      signedInRun shouldBe 6
      net("liquidate", "ALICE", "SIGUSD") shouldBe principalUnits
      net("liquidate", "BOB", "SIGUSD") shouldBe -principalUnits
      // The fill fee and the lender's reserve, each less a miner fee; Bob ends with the collateral.
      net("liquidate", "CHARLIE", "ERG") shouldBe 2L * (OfferFee - txFee)
      net("liquidate", "BOB", "ERG") should be > 0L
    }
  }

  property("shares: fill, share mint, transfers, coupon 0 absorbed, repayment, pull after 720 blocks, three claims") {
    withCtx { ctx =>
      val h = setup(ctx)
      sharesRun(h, price)
      balanced("shares", 1)
      signedInRun shouldBe 12
      // 105 raw SigUSD pulled (coupon 5 + repayment 100; the cut of 5 is 0): 31 to David, 21 to Erin, 53 to Bob.
      net("shares", "DAVID", "SIGUSD") shouldBe part(105L, 300L, 1000L)
      net("shares", "ERIN", "SIGUSD") shouldBe part(105L - 31L, 200L, 700L)
      net("shares", "BOB", "SIGUSD") shouldBe 105L - 31L - 21L - principalUnits
      net("shares", "CHARLIE", "SIGUSD") shouldBe 0L
    }
  }

  property("sale: order, Erin fills, Erin's sale order, Bob's offer, the match, early repayment, claim, cancel") {
    withCtx { ctx =>
      val h = setup(ctx)
      saleRun(h, price)
      balanced("sale", 0)
      signedInRun shouldBe 8
      net("sale", "ERIN", "SIGUSD") shouldBe 0L
      net("sale", "BOB", "SIGUSD") shouldBe 0L
      net("sale", "CHARLIE", "ERG") shouldBe OfferFee
    }
  }

  property("submit mode's checkpoint search: the next open window, past the quiet zone, and none once a coupon is missed") {
    withCtx { ctx =>
      setup(ctx)
      val w = world(ctx)
      val unpaid = fiIn(w, fiBox(w))
      val one = fiIn(w, fiBox(w, paid = 1L))
      recordable(unpaid, w.start, 0) shouldBe Some((0L, w.due(0)))
      recordable(unpaid, w.due(0) + 3, 0) shouldBe Some((0L, w.due(0) + 3))
      // Coupon 0 unpaid: its quiet zone [due + grace / 2, due + grace) shuts checkpoint 0, and from due + grace it is missed.
      recordable(unpaid, w.due(0) + grace / 2, 0) shouldBe None
      // Coupon 0 paid: checkpoint 0's window runs to due + grace, then checkpoint 1 is next.
      recordable(one, w.due(0) + grace / 2, 0) shouldBe Some((0L, w.due(0) + grace / 2))
      recordable(one, w.due(0) + grace + 1, 0) shouldBe Some((1L, w.due(1)))
      recordable(one, w.due(0) + grace - 2, 3) shouldBe Some((1L, w.due(1)))
      // A breach already recorded at checkpoint 1 leaves only later ones; with coupon 1 unpaid, none.
      recordable(fiIn(w, fiBox(w, paid = 2L, last = 1L)), w.due(0), 0) shouldBe Some((2L, w.due(2)))
      recordable(fiIn(w, fiBox(w, paid = 1L, last = 1L)), w.due(0), 0) shouldBe None
      // The lead keeps the record far enough before the next miss for a cure and a repayment.
      recordable(one, w.due(1) + grace / 2 - 5, 0, lead = grace / 2 + 6) shouldBe None
    }
  }

  property("all four runs in sequence on one purse, each planned from where the last ended, as FiLive signs them") {
    withCtx { ctx =>
      val h = setup(ctx)
      runs(h, price, mockOracle(ctx, h)).foreach(_._2())
      failures shouldBe empty
      refusals.size shouldBe 6
      needs.keySet shouldBe Set("cycle", "liquidate", "shares", "sale")
      highest should be > h + PullWait
    }
  }

  property("hashes: every FI contract compiles, and its tree (or expression) hash is printed for the deployer") {
    withCtx { ctx =>
      import org.bouncycastle.util.encoders.Hex
      val f = fi(ctx)
      val m = mk(ctx)
      val s = shares(ctx)
      def tree(c: work.lithos.mutations.Contract): String = c.hashedPropBytesHex
      def expr(c: work.lithos.mutations.Contract): String = Hex.toHexString(c.hashedValueBytes)
      val all = Seq(
        "Bond ERG" -> tree(f.ergBond), "Bond SigUSD" -> tree(f.tokenBond),
        "CouponVault" -> tree(f.couponVault),
        "Covenant ERG" -> expr(f.ergCovenant), "Covenant SigUSD" -> expr(f.tokenCovenant),
        "FillGuard ERG" -> expr(m.erg.guard), "FillGuard SigUSD" -> expr(m.token.guard),
        "CollateralPolicy ERG" -> expr(m.erg.policy), "CollateralPolicy SigUSD" -> expr(m.token.policy),
        "FractionalMint ERG" -> tree(s.ergMint), "FractionalMint SigUSD" -> tree(s.mint),
        "FractionalVault ERG" -> tree(s.ergVault), "FractionalVault SigUSD" -> tree(s.vault),
        "LendOffer ERG" -> tree(m.erg.offer), "LendOffer SigUSD" -> tree(m.token.offer),
        "Liquidation" -> expr(f.payout),
        "Order ERG" -> tree(m.erg.order), "Order SigUSD" -> tree(m.token.order))
      all.foreach { case (name, hash) =>
        hash.length shouldBe 64
        println(s"FIHASHALL|$name=$hash")
      }
    }
  }
}
