package org.lithosprotocol.sigmafi
package specs.app

import ksingh.sigmabonds.app.Protocol._
import ksingh.sigmabonds.app._
import ksingh.sigmabonds.standard._
import org.ergoplatform.appkit._
import org.ergoplatform.appkit.impl.InputBoxImpl
import org.ergoplatform.sdk.ErgoId
import org.scalatest.LoneElement
import org.scalatest.propspec.AnyPropSpec
import specs.harness.ContractSpecBase
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import java.math.BigInteger
import scala.util.{Failure, Success, Try}

/** The ZC Standard core lifecycle, end to end on an in-memory chain (see StandardSpecSupport). */
class StandardAppSpec extends AnyPropSpec with StandardSpecSupport with LoneElement {

  // ═══ LIFECYCLE ════════════════════════════════════════════════════════════

  property("ERG loan: created, listed, filled, repaid, and claimed by burning all 3 bond units") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l) = (borrower(ctx), lender(ctx))
      w.fund(b, erg("20"), Token(collateralToken, 100L))
      w.fund(l, erg("20"))

      w.run(b, w.app(b).createOrder(loan()(w)))
      val order = w.app(l).orders().loneElement
      order.market shouldBe Market.Erg
      order.principal shouldBe erg("5")
      order.repayment shouldBe erg("6")
      order.collateralErg shouldBe erg("10")
      order.expiry shouldBe w.h + 500L

      val filled = w.run(l, w.app(l).fillOrder(order.box.id.toString, reserve))
      w.app(l).orders() shouldBe empty
      val bond = w.app(b).bonds().loneElement
      bond.asset shouldBe order.box.id
      bond.box.tokens.head shouldBe Token(order.box.id, 2L)
      bond.start shouldBe w.h
      bond.movedAt shouldBe w.h
      bond.maturity shouldBe w.h + 1000L
      bond.feeReserve shouldBe reserve
      bond.collateralErg shouldBe erg("10")
      w.app(l).originVerified(bond) shouldBe true
      filled.getOutputsToSpend.get(0).getTokens.get(0).getValue.longValue shouldBe 1L
      // The borrower got the principal and the unused expiry fee back.
      w.nano(b) shouldBe erg("20") - erg("10") - erg("0.005") - Parameters.MinFee + erg("5") + erg("0.005")

      w.run(b, w.app(b).repay(bond.box.id.toString))
      w.app(b).bonds() shouldBe empty
      w.tokens(b).get(collateralToken) shouldBe Some(100L)
      val settled = w.app(l).settlements().loneElement
      settled.liquidated shouldBe false
      settled.box.value shouldBe erg("6") + reserve

      val before = w.nano(l)
      w.run(l, w.app(l).claim(bond.asset.toString))
      w.app(l).settlements() shouldBe empty
      w.tokens(l).get(bond.asset) shouldBe None
      w.nano(l) shouldBe before + erg("6") + reserve - Parameters.MinFee
    }
  }

  property("SigUSD loan: the borrower repays from SigUSD held as collateral, bringing none") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l) = (borrower(ctx), lender(ctx))
      w.fund(b, erg("20"), Token(SigUsd, 1000L))
      w.fund(l, erg("20"), Token(SigUsd, 600L))

      w.run(b, w.app(b).createOrder(loan(Market.SigUsd, principal = 600L, repayment = 700L,
        collateralErg = erg("2"), collateral = Seq(Token(SigUsd, 1000L)))(w)))
      val order = w.app(l).orders().loneElement
      order.market shouldBe Market.SigUsd
      w.run(l, w.app(l).fillOrder(order.box.id.toString, reserve))
      w.tokens(b).get(SigUsd) shouldBe Some(600L)

      val bond = w.app(b).bonds().loneElement
      bond.heldRepaymentAsset shouldBe 1000L
      // A third party could only repay in full; the borrower keeps back 700 of the 1000 instead.
      refused(w.app(l).repay(bond.box.id.toString, fromCollateral = true)) should include("only the borrower")
      w.run(b, w.app(b).repay(bond.box.id.toString, fromCollateral = true))
      w.tokens(b).get(SigUsd) shouldBe Some(600L + 300L)
      val settled = w.app(l).settlements().loneElement
      settled.proceeds shouldBe Seq(Token(SigUsd, 700L))
      w.run(l, w.app(l).claim(bond.asset.toString))
      w.tokens(l).get(SigUsd) shouldBe Some(700L)
    }
  }

  // ═══ LIQUIDATION ══════════════════════════════════════════════════════════

  property("oracle rule: an executor liquidates below the ratio, with the oracle as data input, and not above it") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l, e) = (borrower(ctx), lender(ctx), executor(ctx))
      w.fund(b, erg("20"), Token(SigUsd, 1000L))
      w.fund(l, erg("20"))
      w.fund(e, erg("1"))
      // 1 ERG and $10 of SigUSD against a 6 ERG repayment, liquidated below 150%.
      w.run(b, w.app(b).createOrder(loan(collateralErg = erg("1"), collateral = Seq(Token(SigUsd, 1000L)),
        rule = OracleChoice(15000L, 30L))(w)))
      w.run(l, w.app(l).fillOrder(w.app(l).orders().loneElement.box.id.toString, reserve))
      val bond = w.app(e).bonds().loneElement

      // At 0.9 ERG per USD the collateral is worth 10 ERG, 166%: healthy.
      val healthy = w.oracle(900000000L, w.h)
      refused(w.app(e).liquidate(Seq(bond.box.id.toString))) should include("not below")
      w.chain.unspent.remove(healthy.id.toString)
      // At 0.5 ERG per USD it is worth 6 ERG, 100%.
      w.oracle(500000000L, w.h)
      w.app(e).protocol.ratioBps(bond, 500000000L) shouldBe Some(10000L)
      val prepared = w.app(e).liquidate(Seq(bond.box.id.toString))
      prepared.tx.getDataInputs.size shouldBe 1
      w.run(e, prepared)
      val settled = w.app(l).settlements().loneElement
      settled.liquidated shouldBe true
      settled.proceeds shouldBe Seq(Token(SigUsd, 1000L))
      w.nano(e) shouldBe erg("1") + reserve - Parameters.MinFee
    }
  }

  property("maturity: repayment closes, an executor liquidates, and the holder claims the collateral") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, e) = (borrower(ctx), executor(ctx))
      w.fund(b, erg("20"))
      w.fund(e, erg("1"))
      val asset = ErgoId.create("a1" * 32)
      val due = w.bond(Market.Erg, asset, erg("3"), Seq(Token(collateralToken, 5L)), erg("2"),
        protocol.contracts.maturity, start = w.h - 100L, maturity = w.h.toLong, moved = w.h - 100)
      val live = w.bond(Market.Erg, ErgoId.create("a2" * 32), erg("3"), Seq.empty, erg("2"),
        protocol.contracts.maturity, start = w.h - 100L, maturity = w.h + 100L, moved = w.h - 100)
      refused(w.app(b).repay(due.id.toString)) should include("repayment is closed")
      refused(w.app(e).liquidate(Seq(live.id.toString))) should include("can be liquidated from then on")

      w.run(e, w.app(e).liquidate(Seq(due.id.toString)))
      val settled = w.app(e).settlements().loneElement
      settled.liquidated shouldBe true
      settled.box.value shouldBe erg("3")
      // The holder of the circulating unit claims it.
      w.fund(e, MinBox, Token(asset, 1L))
      w.run(e, w.app(e).claim(asset.toString))
      w.tokens(e).get(collateralToken) shouldBe Some(5L)
    }
  }

  // ═══ RECOLLATERALIZE ══════════════════════════════════════════════════════

  property("recollateralize: after the wait the borrower adds ERG, a token bond takes on SigUSD, and the wait restarts") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l) = (borrower(ctx), lender(ctx))
      w.fund(b, erg("20"), Token(SigUsd, 500L))
      w.fund(l, erg("1"))
      // Offline signing runs the scripts at the context's own height h, where a node would include the
      // transaction at h + 1 at the earliest. The app plans for h + 1, so these bonds rest one block more.
      val ergBond = w.bond(Market.Erg, ErgoId.create("b1" * 32), erg("3"), Seq.empty, erg("2"),
        protocol.contracts.maturity, start = w.h - 100L, maturity = w.h + 1000L, moved = w.h - 60)
      val rested = w.bond(Market.SigUsd, ErgoId.create("b2" * 32), erg("3"), Seq(Token(collateralToken, 5L)), 700L,
        protocol.contracts.maturity, start = w.h - 100L, maturity = w.h + 1000L, moved = w.h - 70, principal = 600L)
      val tooSoon = w.bond(Market.Erg, ErgoId.create("b3" * 32), erg("3"), Seq.empty, erg("2"),
        protocol.contracts.maturity, start = w.h - 100L, maturity = w.h + 1000L, moved = w.h - 58)

      refused(w.app(b).recollateralize(tooSoon.id.toString, erg("1"), Seq.empty)) should include(s"from height ${w.h + 2}")
      refused(w.app(l).recollateralize(ergBond.id.toString, erg("1"), Seq.empty)) should include("only the borrower")
      w.run(b, w.app(b).recollateralize(ergBond.id.toString, erg("1"), Seq.empty))
      val grown = w.app(b).bonds().find(_.asset == ErgoId.create("b1" * 32)).get
      grown.collateralErg shouldBe erg("4")
      grown.movedAt shouldBe w.h
      refused(w.app(b).recollateralize(grown.box.id.toString, erg("1"), Seq.empty)) should include(s"from height ${w.h + 60}")

      refused(w.app(b).recollateralize(rested.id.toString, 0L, Seq(Token(ErgoId.create("cc" * 32), 1L)))) should
        include("only takes more of its own tokens")
      w.run(b, w.app(b).recollateralize(rested.id.toString, 0L, Seq(Token(SigUsd, 500L))))
      w.app(b).bonds().find(_.asset == ErgoId.create("b2" * 32)).get.collateral shouldBe
        Seq(Token(collateralToken, 5L), Token(SigUsd, 500L))
    }
  }

  // ═══ ORDERS ═══════════════════════════════════════════════════════════════

  property("orders: only the borrower cancels; from expiry anyone refunds one for its expiry fee") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, e) = (borrower(ctx), executor(ctx))
      w.fund(b, erg("20"), Token(collateralToken, 100L))
      w.fund(e, erg("1"))
      w.run(b, w.app(b).createOrder(loan()(w)))
      val open = w.app(e).orders().loneElement
      refused(w.app(e).cancelOrder(open.box.id.toString)) should include("only the borrower")
      refused(w.app(e).refundOrder(open.box.id.toString)) should include("can be refunded from then on")
      w.run(b, w.app(b).cancelOrder(open.box.id.toString))
      w.app(e).orders() shouldBe empty
      w.tokens(b).get(collateralToken) shouldBe Some(100L)

      // An order the borrower made earlier, now past its expiry.
      val borrowerTree = Contract.fromAddress(b.getAddress).ergoTree
      val expired = w.seedFrom(b, UTXO(protocol.market(Market.Erg).order, erg("3") + erg("0.005"), Seq(Token(collateralToken, 7L)),
        Seq(longsValue(erg("2"), erg("1"), erg("2"), w.h + 1000L, w.h.toLong, erg("0.005"), 0L),
          tokensValue(Seq.empty), bytesValue(hashOf(borrowerTree)),
          bytesValue(protocol.contracts.maturity.hashedValueBytes))))
      refused(w.app(e).fillOrder(expired.id.toString, reserve)) should include("expired")
      val before = w.nano(b)
      w.run(e, w.app(e).refundOrder(expired.id.toString))
      w.nano(b) shouldBe before + erg("3")
      w.tokens(b).get(collateralToken) shouldBe Some(107L)
      w.nano(e) shouldBe erg("1") + erg("0.005") - Parameters.MinFee
    }
  }

  property("fill-order refuses an order that breaks the one-asset rule or names an unknown rule") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l) = (borrower(ctx), lender(ctx))
      w.fund(l, erg("20"))
      val borrowerTree = Contract.fromAddress(b.getAddress).ergoTree
      def order(principalTokens: Seq[Token], rule: Array[Byte]): InputUTXO =
        w.seed(UTXO(protocol.market(Market.Erg).order, erg("3"), Seq.empty,
          Seq(longsValue(erg("2"), erg("1"), erg("2"), w.h + 1000L, w.h + 500L, erg("0.005"), 0L),
            tokensValue(principalTokens), bytesValue(hashOf(borrowerTree)), bytesValue(rule))))
      refused(w.app(l).fillOrder(order(Seq(Token(SigUsd, 1L)), protocol.contracts.maturity.hashedValueBytes).id.toString,
        reserve)) should include("does not lend and repay in ERG alone")
      refused(w.app(l).fillOrder(order(Seq.empty, Array.fill(32)(7.toByte)).id.toString, reserve)) should
        include("does not know")
      refused(w.app(l).fillOrder(order(Seq.empty,
        protocol.market(Market.SigUsd).oracleRule.hashedValueBytes).id.toString, reserve)) should include("measures a SigUSD")
    }
  }
}
