package org.lithosprotocol.sigmafi
package specs.app

import ksingh.sigmabonds.app.Protocol._
import ksingh.sigmabonds.app.OnClose
import ksingh.sigmabonds.app._
import ksingh.sigmabonds.standard._
import org.ergoplatform.appkit.{ErgoProver, ErgoType, ErgoValue}
import org.scalatest.LoneElement
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.Token

/** Issuance, price resolution, bid matching and resale use the same pinned contracts end to end. */
class StandardPricedAppSpec extends AnyPropSpec with StandardSpecSupport with LoneElement {
  private val fee = erg("0.005")
  private def offer(w: World, m: Market): OfferSpec = OfferSpec(m,
    if (m == Market.Erg) erg("20") else 2000L, erg("2"), 0L, 1L, 262800L, fee, reserve,
    15000L, 30L, w.h + 500L, 1L, 0L, 0L, None)

  property("CLI pricing options build on-close NAV issuance and a yield ask, rejecting contradictory or invalid rates") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l) = (borrower(ctx), lender(ctx))
      w.fund(b, erg("30"))
      w.fund(l, erg("30"))
      def command(p: ErgoProver, line: String): Prepared = {
        var captured: Option[Prepared] = None
        val node = new FakeNode(w.chain, p)
        val app = new StandardApp(ctx, node, AppSettings(), _ => (), protocol) {
          override def submit(prepared: Prepared): Option[String] = { captured = Some(prepared); None }
        }
        StandardCommands.dispatch(app, node, Args.parse(Args.tokenize(line)), hasApiKey = true)
        captured.get
      }
      val create = "create-order --principal 5 --repayment 6 --collateral-erg 10 --duration 20000"
      w.run(b, command(b, create + " --nav --margin -1"))
      val order = w.app(l).orders().loneElement
      order.priced shouldBe true
      protocol.yieldOrder(order) shouldBe false
      w.run(l, w.app(l).fillOrder(order.box.id.toString, reserve))
      val bond = w.app(l).bonds().loneElement
      val sell = s"sell ${bond.asset} --amount 1"
      w.run(l, command(l, sell + " --yield 10 --margin 2 --price 5"))
      protocol.yieldAsk(w.app(l).asks().loneElement) shouldBe true
      w.app(l).asks().loneElement.terms(6) shouldBe 200L
      w.app(l).asks().loneElement.margin shouldBe 200L
      refused(command(b, create + " --nav --yield 10")) should include("choose --nav or --yield")
      refused(command(b, create + " --margin 1")) should include("--margin needs --nav")
      Seq("-1", "1.001", "invalid", "999999999999999999999").foreach { bad =>
        refused(command(b, create + s" --yield $bad")) should include("--yield")
        refused(command(l, sell + s" --yield $bad")) should include("--yield")
      }
    }
  }

  for (m <- Market.all; nav <- Seq(true, false); viaOffer <- Seq(true, false)) {
    property(s"$m ${if (nav) "NAV" else "yield"} PricedOrder fills ${if (viaOffer) "from an offer" else "from a wallet"}, then resells through a guarded yield ask") {
      withCtx { ctx =>
        val w = new World(ctx)
        val (b, l, e, o) = (borrower(ctx), lender(ctx), executor(ctx), other(ctx))
        w.fund(b, erg("30"), Token(collateralToken, 100L), Token(SigUsd, 5000L))
        w.fund(l, erg("30"), Token(SigUsd, 5000L))
        w.fund(o, erg("30"), Token(SigUsd, 5000L))
        w.fund(e, erg("2"))
        w.oracle(500000000L, w.h)
        val choice = if (nav) NavOrderPrice(0L) else YieldOrderPrice(1000L, -200L)
        val request = loan(m, if (m == Market.Erg) erg("5") else 500L,
          if (m == Market.Erg) erg("6") else 600L, rule = OracleChoice(15000L, 30L), maturityIn = 10000)(w)
          .copy(price = Some(choice), fillRatio = Some(16000L))
        w.run(b, w.app(b).createOrder(request))
        val order = w.app(e).orders().loneElement
        order.priced shouldBe true
        order.principal shouldBe request.principal
        val quote = w.app(l).orderPrincipal(order, w.h + 3L, w.h.toLong)
        quote should be > request.principal
        if (viaOffer) {
          w.run(l, w.app(l).createOffer(offer(w, m)))
          val bid = w.app(e).offers().loneElement
          w.app(e).batchScan().work.exists(_.command == s"match-offer ${bid.box.id} --order ${order.box.id}") shouldBe true
          w.run(e, w.app(e).matchOrder(bid.box.id.toString, order.box.id.toString))
        } else w.run(l, w.app(l).fillOrder(order.box.id.toString, reserve))
        val bond = w.app(e).bonds().loneElement
        bond.principal shouldBe quote
        bond.repayment shouldBe request.repayment
        bond.payload shouldBe order.payload
        w.app(e).originVerified(bond) shouldBe true
        w.app(e).assetMarket(bond.asset) shouldBe Some(m)
        w.app(e).bondMetadataIssues(bond.asset) shouldBe empty
        w.run(l, w.app(l).sell(bond.asset.toString, 1L, m, YieldPrice(1000L, marginBps = -100L), Some(1L),
          w.h + 100L, fee, Some(SaleGuard(16000L, 30L))))
        val ask = w.app(e).asks().loneElement
        protocol.yieldAsk(ask) shouldBe true
        protocol.saleGuard(ask) shouldBe Some(16000L -> 30L)
        w.run(o, w.app(o).createOffer(offer(w, m)))
        val bid = w.app(e).offers().find(x => w.app(o).isOwnHash(x.ownerHash)).get
        w.run(e, w.app(e).matchAsk(bid.box.id.toString, ask.box.id.toString, None))
        w.tokens(o).get(bond.asset) shouldBe Some(1L)
        // Settlement uses the original repayment, and the new holder claims normally.
        w.run(b, w.app(b).repay(bond.box.id.toString))
        w.run(o, w.app(o).claim(bond.asset.toString))
        w.app(e).settlements() shouldBe empty
      }
    }
  }

  property("yield-priced shares partially fill, retain their expression, and can be bought by a lend offer") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l, e, o) = (borrower(ctx), lender(ctx), executor(ctx), other(ctx))
      w.fund(b, erg("30"), Token(collateralToken, 100L))
      w.fund(l, erg("30"))
      w.fund(e, erg("30"))
      w.fund(o, erg("30"))
      w.oracle(500000000L, w.h)
      val bond = w.liveBond(loan(rule = OracleChoice(15000L, 30L), maturityIn = 10000)(w).copy(price = Some(YieldOrderPrice(1000L))), l)
      w.run(l, w.app(l).fractionalize(bond.asset.toString, ShareTerms(1000L, fee, fee)))
      val request = w.app(e).shareRequests().loneElement
      w.run(e, w.app(e).mintShares(request.box.id.toString))
      w.run(l, w.app(l).sell(request.shareId.toString, 1000L, Market.Erg, YieldPrice(1000L),
        Some(1L), w.h + 100L, fee))
      val ask = w.app(e).asks().loneElement
      w.run(e, w.app(e).buy(ask.box.id.toString, Some(400L)))
      val rest = w.app(e).asks().loneElement
      rest.token.amount shouldBe 600L
      rest.pricingHash.get.toSeq shouldBe ask.pricingHash.get.toSeq
      w.run(o, w.app(o).createOffer(offer(w, Market.Erg)))
      w.run(e, w.app(e).matchAsk(w.app(e).offers().loneElement.box.id.toString, rest.box.id.toString, Some(300L)))
      w.tokens(o).get(request.shareId) shouldBe Some(300L)
      w.run(b, w.app(b).repay(bond.box.id.toString))
      refused(w.app(e).buy(w.app(e).asks().loneElement.box.id.toString, None)) should include("not live")
    }
  }

  property("priced orders cancel and refund through the existing commands; on-close NAV uses the actual maturity") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l, e) = (borrower(ctx), lender(ctx), executor(ctx))
      w.fund(b, erg("40"), Token(collateralToken, 100L))
      w.fund(l, erg("30"))
      w.fund(e, erg("2"))
      w.run(b, w.app(b).createOrder(loan()(w).copy(schedule = OnClose(1000L), price = Some(NavOrderPrice()))))
      val navOrder = w.app(l).orders().loneElement
      w.run(l, w.app(l).fillOrder(navOrder.box.id.toString, reserve))
      w.app(e).bonds().loneElement.maturity shouldBe w.h + 1000L
      // Return collateral so the same wallet can post another request.
      w.run(b, w.app(b).repay(w.app(e).bonds().loneElement.box.id.toString))
      w.run(b, w.app(b).createOrder(loan()(w).copy(price = Some(YieldOrderPrice(0L)))))
      val order = w.app(e).orders().loneElement
      w.run(b, w.app(b).cancelOrder(order.box.id.toString))
      val expired = order.box.toUTXO.withRegNum(4, longsValue(order.terms.updated(4, w.h.toLong): _*))
      val seeded = w.seedFrom(b, expired)
      w.run(e, w.app(e).refundOrder(seeded.id.toString))
      w.app(e).orders() shouldBe empty
    }
  }

  property("unknown pricing is refused and skipped without losing owner cancellation") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l, e, o) = (borrower(ctx), lender(ctx), executor(ctx), other(ctx))
      w.fund(b, erg("40"), Token(collateralToken, 100L))
      w.fund(l, erg("30"))
      w.fund(e, erg("2"))
      w.fund(o, erg("30"))
      w.oracle(500000000L, w.h)
      val request = loan(rule = OracleChoice(15000L, 30L), maturityIn = 10000)(w)
        .copy(price = Some(YieldOrderPrice(1000L)))
      w.run(b, w.app(b).createOrder(request))
      val original = w.app(e).orders().loneElement
      w.run(b, w.app(b).cancelOrder(original.box.id.toString))
      val hashes = ErgoValue.of(sigma.Colls.fromArray(Array(sigma.Colls.fromArray(Array.fill[Byte](32)(66)),
        sigma.Colls.fromArray(Array.emptyByteArray)))(sigma.collRType(sigma.ByteType)),
        ErgoType.collType(org.ergoplatform.appkit.scalaapi.scalaByteType))
      val unknown = w.seedFrom(b, original.box.toUTXO.withRegNum(8, hashes))
      w.run(o, w.app(o).createOffer(offer(w, Market.Erg)))
      refused(w.app(l).fillOrder(unknown.id.toString, reserve)) should include("unknown pricing expression")
      w.app(e).batchScan().work.filter(_.command.startsWith("match-offer")) shouldBe empty
      w.run(b, w.app(b).cancelOrder(unknown.id.toString))

      val bond = w.liveBond(request, l)
      w.run(l, w.app(l).sell(bond.asset.toString, 1L, Market.Erg, YieldPrice(1000L), Some(1L), w.h + 100L, fee))
      val ask = w.app(e).asks().loneElement
      w.chain.unspent.remove(ask.box.id.toString)
      val unknownAsk = w.seedFrom(l, ask.box.toUTXO.withRegNum(6, bytesValue(Array.fill[Byte](32)(66))))
      refused(w.app(e).buy(unknownAsk.id.toString, None)) should include("unknown pricing expression")
      w.app(e).batchScan().work.exists(_.command.contains(unknownAsk.id.toString)) shouldBe false
      w.run(l, w.app(l).cancelAsk(unknownAsk.id.toString))
    }
  }
}
