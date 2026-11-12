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

/**
 * The ZC Standard app, end to end on an in-memory chain. Each step finds its boxes through the node
 * endpoints the app uses and builds a transaction from the pinned trees; the spec signs it as the node
 * wallet would, which runs every contract. The outputs join the chain for the next step to find.
 *
 * The mocked context's height is fixed, so steps that need time to pass (the recollateralize wait,
 * maturity, an order's expiry) start from boxes seeded with earlier heights.
 */
class StandardAppSpec extends AnyPropSpec with ContractSpecBase with LoneElement {

  private val collateralToken = ErgoId.create("aa" * 32)
  private val SigUsd = Market.SigUsdId
  private val protocol = new StandardProtocol()

  private def erg(s: String): Long = SigmaFiCli.parseErg(s)
  private val reserve = erg("0.05")

  private def borrower(ctx: BlockchainContext) = proverWith(ctx, BigInteger.valueOf(1001L))
  private def lender(ctx: BlockchainContext) = proverWith(ctx, BigInteger.valueOf(2002L))
  private def executor(ctx: BlockchainContext) = proverWith(ctx, BigInteger.valueOf(3003L))

  private class World(val ctx: BlockchainContext) {
    val chain = new FakeChain
    private var seeds = 0
    val h: Int = ctx.getHeight

    def seed(utxo: UTXO): InputUTXO = {
      seeds += 1
      val box = utxo.toInput(ctx, ErgoId.create(f"$seeds%064x"), 0.toShort)
      chain.add(box.input.asInstanceOf[InputBoxImpl].getErgoBox)
      box
    }

    def fund(p: ErgoProver, value: Long, tokens: Token*): InputUTXO = seed(UTXO(Contract.fromAddress(p.getAddress), value, tokens))

    def app(p: ErgoProver): StandardApp = new StandardApp(ctx, new FakeNode(chain, p), AppSettings(), _ => (), protocol)

    def run(p: ErgoProver, prepared: => Prepared): SignedTransaction = {
      val built = Try(prepared) match {
        case Success(b) => b
        case Failure(e) => fail(s"building failed: ${e.getMessage}", e)
      }
      val signed = Try(p.sign(built.tx)) match {
        case Success(s) => s
        case Failure(e) => fail(s"the contracts rejected '${built.title}': ${e.getMessage}", e)
      }
      chain.apply(built.tx, signed)
      signed
    }

    def tokens(p: ErgoProver): Map[ErgoId, Long] =
      app(p).walletBoxes.flatMap(_.tokens).groupBy(_.id).map { case (id, ts) => id -> ts.map(_.amount).sum }

    def nano(p: ErgoProver): Long = app(p).walletBoxes.map(_.value).sum

    /** A bond under the market's script, as if a fill long ago had made it: it moved at `moved`. */
    def bond(m: Market, asset: ErgoId, collateralErg: Long, collateral: Seq[Token], repayment: Long,
             rule: Contract, start: Long, maturity: Long, moved: Int, payload: Seq[Long] = Seq.empty,
             principal: Long = erg("5")): InputUTXO = {
      val (principalErg, floor, principalTokens) = m.token match {
        case None => (principal, repayment, Seq.empty[Token])
        case Some(id) => (0L, MinBox, Seq(Token(id, principal)))
      }
      val terms = Seq(repayment, principalErg, floor, maturity, start + 10L, erg("0.005"), 0L) ++ payload
      seed(UTXO(protocol.market(m).bond, collateralErg + reserve, Token(asset, 2L) +: collateral,
        Seq(bytesValue(asset.getBytes), bytesValue(hashOf(Contract.fromAddress(borrower(ctx).getAddress).ergoTree)),
          longsValue(terms: _*), tokensValue(principalTokens), bytesValue(rule.hashedValueBytes),
          longsValue(reserve, start, maturity)), Some(moved)))
    }

    def oracle(price: Long, created: Int): InputUTXO =
      seed(UTXO(Contract.SIGMA_TRUE, MinBox, Seq(Token(StandardProtocol.OracleNft, 1L)),
        Seq(ErgoValue.of(price), ErgoValue.of(1)), Some(created)))
  }

  private def loan(m: Market = Market.Erg, principal: Long = erg("5"), repayment: Long = erg("6"),
                   collateralErg: Long = erg("10"), collateral: Seq[Token] = Seq(Token(collateralToken, 100L)),
                   rule: RuleChoice = MaturityChoice, maturityIn: Int = 1000)(w: World): StdLoan =
    StdLoan(m, principal, repayment, collateralErg, collateral, FixedMaturity(w.h + maturityIn), rule,
      expiry = w.h + 500L, expiryFee = erg("0.005"))

  private def refused(f: => Any): String = Try(f) match {
    case Failure(e: CliError) => e.message
    case Failure(e) => fail(s"expected a refusal, got ${e.getMessage}", e)
    case Success(_) => fail("expected a refusal, but it was accepted")
  }

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

      // An order made earlier, now past its expiry.
      val borrowerTree = Contract.fromAddress(b.getAddress).ergoTree
      val expired = w.seed(UTXO(protocol.market(Market.Erg).order, erg("3") + erg("0.005"), Seq(Token(collateralToken, 7L)),
        Seq(longsValue(erg("2"), erg("1"), erg("2"), w.h + 1000L, w.h.toLong, erg("0.005"), 0L),
          tokensValue(Seq.empty), bytesValue(hashOf(borrowerTree)),
          bytesValue(protocol.contracts.maturity.hashedValueBytes), treesValue(borrowerTree))))
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
            tokensValue(principalTokens), bytesValue(hashOf(borrowerTree)), bytesValue(rule), treesValue(borrowerTree))))
      refused(w.app(l).fillOrder(order(Seq(Token(SigUsd, 1L)), protocol.contracts.maturity.hashedValueBytes).id.toString,
        reserve)) should include("does not lend and repay in ERG alone")
      refused(w.app(l).fillOrder(order(Seq.empty, Array.fill(32)(7.toByte)).id.toString, reserve)) should
        include("does not know")
      refused(w.app(l).fillOrder(order(Seq.empty,
        protocol.market(Market.SigUsd).oracleRule.hashedValueBytes).id.toString, reserve)) should include("measures a SigUSD")
    }
  }
}
