package org.lithosprotocol.sigmafi
package specs.app

import ksingh.sigmabonds.app.Protocol._
import ksingh.sigmabonds.app._
import ksingh.sigmabonds.standard._
import org.ergoplatform.appkit.ErgoProver
import org.scalatest.LoneElement
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Token, UTXO}

/** Shares, asks, lend offers and the batch scan, end to end on an in-memory chain (see StandardSpecSupport). */
class StandardExchangeAppSpec extends AnyPropSpec with StandardSpecSupport with LoneElement {

  private val fee = erg("0.005")
  private def terms(n: Long, auction: Option[AuctionTerms] = None): ShareTerms =
    ShareTerms(n, fee, fee, auction = auction)

  private def offerSpec(w: World, capital: Long = erg("20")): OfferSpec =
    OfferSpec(Market.Erg, capital, 0L, yieldBps = 1000L, minBlocks = 1L, maxBlocks = 262800L, batcherFee = fee,
      feeReserve = reserve, floorBps = 13000L, maxAge = 30L, expiry = w.h + 500L, minPrice = 1L,
      auctionFloorBps = 0L, auctionLength = 0L, delivery = None)

  private val oracleLoan = (w: World, tokens: Long) =>
    loan(collateral = Seq(Token(collateralToken, tokens)), rule = OracleChoice(15000L, 60L))(w)

  // ═══ SHARES ═══════════════════════════════════════════════════════════════

  property("fractional shares: deposited, minted by a batcher, pulled after repayment, then claimed in part and in full") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l, e) = (borrower(ctx), lender(ctx), executor(ctx))
      w.fund(b, erg("20"), Token(collateralToken, 100L))
      w.fund(l, erg("20"))
      w.fund(e, erg("1"))
      val bond = w.liveBond(loan()(w), l)

      w.run(l, w.app(l).fractionalize(bond.asset.toString, terms(1000L)))
      val req = w.app(e).shareRequests().loneElement
      req.bondAsset shouldBe bond.asset
      req.dutch shouldBe None
      w.run(e, w.app(e).mintShares(req.box.id.toString))
      val shareId = req.shareId
      w.tokens(l).get(shareId) shouldBe Some(1000L)
      val vault = w.app(e).shareVault(shareId).get
      vault.holding shouldBe true
      refused(w.app(e).pull(vault.box.id.toString)) should include("not been repaid")
      refused(w.app(l).claimShares(shareId.toString, Some(400L))) should include("pull it first")

      w.run(b, w.app(b).repay(bond.box.id.toString))
      val before = w.nano(e)
      w.run(e, w.app(e).pull(vault.box.id.toString))
      w.nano(e) shouldBe before + fee - org.ergoplatform.appkit.Parameters.MinFee
      val pulled = w.app(e).shareVault(shareId).get
      pulled.holding shouldBe false
      pulled.state shouldBe Some(0.toByte)
      pulled.box.value shouldBe erg("6") + reserve + MinBox

      val l0 = w.nano(l)
      w.run(l, w.app(l).claimShares(shareId.toString, Some(400L)))
      w.tokens(l).get(shareId) shouldBe Some(600L)
      w.app(e).shareVault(shareId).get.outstanding shouldBe 600L
      w.nano(l) shouldBe l0 + pulled.box.value * 400 / 1000 - org.ergoplatform.appkit.Parameters.MinFee
      w.run(l, w.app(l).claimShares(shareId.toString, None))
      w.app(e).shareVaults() shouldBe empty
      w.tokens(l).get(shareId) shouldBe None
    }
  }

  property("Dutch shares: a liquidated bond's lot opens an auction anyone buys, and holders then claim the currency") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l, e) = (borrower(ctx), lender(ctx), executor(ctx))
      w.fund(b, erg("20"), Token(SigUsd, 1000L))
      w.fund(l, erg("20"))
      w.fund(e, erg("20"))
      val bond = w.liveBond(loan(collateralErg = erg("1"), collateral = Seq(Token(SigUsd, 1000L)),
        rule = OracleChoice(15000L, 30L))(w), l)
      w.run(l, w.app(l).fractionalize(bond.asset.toString, terms(100L, Some(AuctionTerms(15000L, 9000L, 500L)))))
      val req = w.app(e).shareRequests().loneElement
      req.dutch shouldBe Some(Market.Erg)
      w.run(e, w.app(e).mintShares(req.box.id.toString))

      w.oracle(500000000L, w.h)
      w.run(e, w.app(e).liquidate(Seq(bond.box.id.toString)))
      w.run(e, w.app(e).pull(w.app(e).shareVault(req.shareId).get.box.id.toString))
      val open = w.app(e).shareVault(req.shareId).get
      open.auctionOpen shouldBe true
      open.auction(4) shouldBe w.h.toLong
      refused(w.app(l).claimShares(req.shareId.toString, Some(10L))) should include("auction runs until")

      // 150% of the 6 ERG repayment at the start of the auction.
      w.app(e).lotTarget(open, w.h.toLong) shouldBe erg("9")
      w.run(e, w.app(e).buyLot(open.box.id.toString))
      w.tokens(e).get(SigUsd) shouldBe Some(1000L)
      val sold = w.app(e).shareVault(req.shareId).get
      sold.state shouldBe Some(2.toByte)
      sold.box.value shouldBe erg("9")
      refused(w.app(e).buyLot(sold.box.id.toString)) should include("no open auction")

      w.run(l, w.app(l).claimShares(req.shareId.toString, None))
      w.app(e).shareVaults() shouldBe empty
    }
  }

  property("Dutch shares in SigUSD: the lot is bought with SigUSD, the buyer takes the collateral and ERG, and holders claim SigUSD") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l, e) = (borrower(ctx), lender(ctx), executor(ctx))
      w.fund(b, erg("20"), Token(collateralToken, 50L))
      w.fund(l, erg("20"), Token(SigUsd, 600L))
      w.fund(e, erg("1"), Token(SigUsd, 2000L))
      // 2 ERG and 50 unpriced tokens against a 7.00 SigUSD repayment, liquidated below 150%.
      val bond = w.liveBond(loan(Market.SigUsd, principal = 600L, repayment = 700L, collateralErg = erg("2"),
        collateral = Seq(Token(collateralToken, 50L)), rule = OracleChoice(15000L, 30L))(w), l)
      w.run(l, w.app(l).fractionalize(bond.asset.toString, terms(100L, Some(AuctionTerms(15000L, 9000L, 500L)))))
      val req = w.app(e).shareRequests().loneElement
      req.dutch shouldBe Some(Market.SigUsd)
      w.run(e, w.app(e).mintShares(req.box.id.toString))

      // At 0.5 ERG per USD the 2 ERG are worth $4, under 150% of $7.
      w.oracle(500000000L, w.h)
      w.run(e, w.app(e).liquidate(Seq(bond.box.id.toString)))
      w.run(e, w.app(e).pull(w.app(e).shareVault(req.shareId).get.box.id.toString))
      val open = w.app(e).shareVault(req.shareId).get
      // The vault holds no SigUSD, so it is short of the floor and the auction opens.
      open.auctionOpen shouldBe true
      open.currencyToken shouldBe Some(SigUsd)
      w.app(e).currencyHeld(open) shouldBe 0L
      open.box.value shouldBe erg("2") + MinBox

      // 150% of the 700 raw SigUSD repayment at the start of the auction.
      w.app(e).lotTarget(open, w.h.toLong) shouldBe 1050L
      val e0 = w.nano(e)
      w.run(e, w.app(e).buyLot(open.box.id.toString))
      w.tokens(e).get(SigUsd) shouldBe Some(2000L - 1050L)
      w.tokens(e).get(collateralToken) shouldBe Some(50L)
      // Everything above the box minimum was part of the lot: the buyer takes the 2 ERG too.
      w.nano(e) shouldBe e0 + erg("2") - org.ergoplatform.appkit.Parameters.MinFee
      val sold = w.app(e).shareVault(req.shareId).get
      sold.state shouldBe Some(2.toByte)
      sold.box.value shouldBe MinBox
      w.app(e).currencyHeld(sold) shouldBe 1050L
      sold.assets shouldBe Seq(Token(SigUsd, 1050L))

      w.run(l, w.app(l).claimShares(req.shareId.toString, None))
      w.tokens(l).get(SigUsd) shouldBe Some(1050L)
      w.app(e).shareVaults() shouldBe empty
    }
  }

  // ═══ ASKS ═════════════════════════════════════════════════════════════════

  property("asks: a bond unit at a fixed price and shares at NAV, bought by wallets; the seller cancels; an expired ask is refunded") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l, e, o) = (borrower(ctx), lender(ctx), executor(ctx), other(ctx))
      w.fund(b, erg("20"), Token(collateralToken, 100L))
      w.fund(l, erg("20"))
      w.fund(e, erg("20"))
      val bond = w.liveBond(loan()(w), l)

      w.run(l, w.app(l).sell(bond.asset.toString, 1L, Market.Erg, FixedPrice(erg("5.5")), Some(1L), w.h + 100L, erg("0.002")))
      val ask = w.app(e).asks().loneElement
      ask.nav shouldBe false
      w.app(e).askPrice(ask, 1L)._1 shouldBe erg("5.5")
      val l0 = w.nano(l)
      w.run(e, w.app(e).buy(ask.box.id.toString, None))
      w.tokens(e).get(bond.asset) shouldBe Some(1L)
      w.nano(l) shouldBe l0 + erg("5.5")

      // The new holder splits the unit and sells 60 of 100 shares at accrued value.
      w.run(e, w.app(e).fractionalize(bond.asset.toString, terms(100L)))
      w.run(e, w.app(e).mintShares(w.app(e).shareRequests().loneElement.box.id.toString))
      val shareId = w.app(e).shareVaults().loneElement.shareId
      refused(w.app(e).sell(shareId.toString, 60L, Market.SigUsd, NavPrice(0L, None), Some(1L), w.h + 100L, 0L)) should
        include("use --market ERG")
      w.run(e, w.app(e).sell(shareId.toString, 60L, Market.Erg, NavPrice(0L, None), Some(1L), w.h + 100L, erg("0.002")))
      val nav = w.app(l).asks().loneElement
      nav.nav shouldBe true
      nav.wholeUnits shouldBe 100L
      nav.navAsset shouldBe Some(bond.asset)
      w.run(l, w.app(l).buy(nav.box.id.toString, Some(40L)))
      w.tokens(l).get(shareId) shouldBe Some(40L)
      val rest = w.app(l).asks().loneElement
      rest.token.amount shouldBe 20L
      refused(w.app(l).cancelAsk(rest.box.id.toString)) should include("only the seller")
      w.run(e, w.app(e).cancelAsk(rest.box.id.toString))
      w.app(l).asks() shouldBe empty
      // 40 never offered, and the 20 the cancel returned.
      w.tokens(e).get(shareId) shouldBe Some(60L)

      // An ask that expired: anyone returns its tokens to the seller.
      val expired = w.seedFrom(e, UTXO(protocol.market(Market.Erg).saleOrder, MinBox + erg("0.002"),
        Seq(Token(collateralToken, 3L)),
        Seq(bytesValue(hashOf(w.app(e).myTree)), longsValue(1L, 1L, 1L, w.h.toLong))))
      refused(w.app(o).buy(expired.id.toString, None)) should include("expired")
      w.run(o, w.app(o).refundAsk(expired.id.toString))
      w.tokens(e).get(collateralToken) shouldBe Some(3L)
    }
  }

  // ═══ LEND OFFERS ══════════════════════════════════════════════════════════

  property("lend offer: a batcher fills an oracle-rule order and buys a bond unit from an ask with it, never a maturity-rule bond") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l, e, o) = (borrower(ctx), lender(ctx), executor(ctx), other(ctx))
      w.fund(b, erg("60"), Token(collateralToken, 300L))
      w.fund(l, erg("20"))
      w.fund(e, erg("1"))
      w.fund(o, erg("30"))
      w.oracle(500000000L, w.h)
      w.run(o, w.app(o).createOffer(offerSpec(w)))
      val offer = w.app(e).offers().loneElement
      offer.capital shouldBe erg("20")

      w.run(b, w.app(b).createOrder(loan(collateral = Seq(Token(collateralToken, 100L)))(w)))
      val plain = w.app(e).orders().loneElement
      refused(w.app(e).matchOrder(offer.box.id.toString, plain.box.id.toString)) should include("oracle rule")
      w.run(b, w.app(b).createOrder(oracleLoan(w, 50L)))
      val order = w.app(e).orders().find(_.collateral == Seq(Token(collateralToken, 50L))).get
      val e0 = w.nano(e)
      w.run(e, w.app(e).matchOrder(offer.box.id.toString, order.box.id.toString))
      w.nano(e) shouldBe e0 + fee - org.ergoplatform.appkit.Parameters.MinFee
      w.tokens(o).get(order.box.id) shouldBe Some(1L)
      val filled = w.app(e).bonds().find(_.asset == order.box.id).get
      filled.feeReserve shouldBe reserve
      // An ERG offer's capital is its whole value: the batcher fee, the delivery box and the reserve leave it too.
      val afterFill = erg("20") - erg("5") - fee - MinBox - reserve
      w.app(e).offers().loneElement.capital shouldBe afterFill

      // A holder sells an oracle-rule unit; the offer buys it.
      val held = w.liveBond(oracleLoan(w, 30L), l)
      w.run(l, w.app(l).sell(held.asset.toString, 1L, Market.Erg, FixedPrice(erg("5.2")), Some(1L), w.h + 100L, erg("0.002")))
      val ask = w.app(e).asks().loneElement
      val next = w.app(e).offers().loneElement
      val l0 = w.nano(l)
      w.run(e, w.app(e).matchAsk(next.box.id.toString, ask.box.id.toString, None))
      w.tokens(o).get(held.asset) shouldBe Some(1L)
      w.nano(l) shouldBe l0 + erg("5.2")
      w.app(e).offers().loneElement.capital shouldBe afterFill - erg("5.2") - fee - MinBox

      // 10% a year over about 1000 blocks caps the price near 5.9977 ERG for 6; above that it refuses and says why.
      w.run(l, w.app(l).sell(w.liveBond(oracleLoan(w, 20L), l).asset.toString, 1L, Market.Erg,
        FixedPrice(erg("5.999")), Some(1L), w.h + 100L, erg("0.002")))
      refused(w.app(e).matchAsk(w.app(e).offers().loneElement.box.id.toString, w.app(e).asks().loneElement.box.id.toString, None)) should
        include("under the offer's 10% yield")
    }
  }

  property("lend offer: buys shares from a NAV ask into its owner's wallet, and an expired offer is refunded") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l, e, o) = (borrower(ctx), lender(ctx), executor(ctx), other(ctx))
      w.fund(b, erg("20"), Token(collateralToken, 100L))
      w.fund(l, erg("20"))
      w.fund(e, erg("1"))
      w.fund(o, erg("30"))
      w.oracle(500000000L, w.h)
      val bond = w.liveBond(oracleLoan(w, 100L), l)
      w.run(l, w.app(l).fractionalize(bond.asset.toString, terms(100L)))
      w.run(e, w.app(e).mintShares(w.app(e).shareRequests().loneElement.box.id.toString))
      val shareId = w.app(e).shareVaults().loneElement.shareId
      w.run(l, w.app(l).sell(shareId.toString, 60L, Market.Erg, NavPrice(0L, None), Some(1L), w.h + 100L, erg("0.002")))
      w.run(o, w.app(o).createOffer(offerSpec(w)))

      val ask = w.app(e).asks().loneElement
      w.run(e, w.app(e).matchAsk(w.app(e).offers().loneElement.box.id.toString, ask.box.id.toString, Some(40L)))
      w.tokens(o).get(shareId) shouldBe Some(40L)
      w.app(e).asks().loneElement.token.amount shouldBe 20L

      // An offer that expired: anyone returns it to its owner for the batcher fee.
      val stale = w.seedFrom(o, UTXO(protocol.market(Market.Erg).offer, erg("2"), Seq.empty,
        Seq(bytesValue(hashOf(w.app(o).myTree)),
          longsValue(0L, 1L, 262800L, fee, reserve, 13000L, w.h.toLong, 1L, 30L, 0L, 0L),
          bytesValue(protocol.market(Market.Erg).policy.hashedValueBytes), longsValue(), textsValue(), bytesValue(Array.empty[Byte]))))
      val o0 = w.nano(o)
      w.run(e, w.app(e).refundOffer(stale.id.toString))
      w.nano(o) shouldBe o0 + erg("2") - fee
    }
  }

  property("lend offer: buys Dutch shares only when their auction clears its floor and length bounds") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l, e, o) = (borrower(ctx), lender(ctx), executor(ctx), other(ctx))
      w.fund(b, erg("20"), Token(collateralToken, 100L))
      w.fund(l, erg("20"))
      w.fund(e, erg("1"))
      w.fund(o, erg("90"))
      w.oracle(500000000L, w.h)
      // Shares whose auction falls from 150% to a 90% floor over 500 blocks, 60 of them for sale at NAV.
      val bond = w.liveBond(oracleLoan(w, 100L), l)
      w.run(l, w.app(l).fractionalize(bond.asset.toString, terms(100L, Some(AuctionTerms(15000L, 9000L, 500L)))))
      w.run(e, w.app(e).mintShares(w.app(e).shareRequests().loneElement.box.id.toString))
      val shareId = w.app(e).shareVaults().loneElement.shareId
      w.run(l, w.app(l).sell(shareId.toString, 60L, Market.Erg, NavPrice(0L, None), Some(1L), w.h + 100L, erg("0.002")))
      val ask = w.app(e).asks().loneElement

      // Three offers: one buys no Dutch shares, one wants a 95% floor, one accepts 80% over at most 1000 blocks.
      Seq((0L, 0L), (9500L, 1000L), (8000L, 1000L)).foreach { case (floor, length) =>
        w.run(o, w.app(o).createOffer(offerSpec(w).copy(auctionFloorBps = floor, auctionLength = length)))
      }
      def offerWith(floor: Long, length: Long): String =
        w.app(e).offers().find(x => x.terms(9) == floor && x.terms(10) == length).get.box.id.toString
      refused(w.app(e).matchAsk(offerWith(0L, 0L), ask.box.id.toString, Some(40L))) should include("buys no Dutch auction shares")
      refused(w.app(e).matchAsk(offerWith(9500L, 1000L), ask.box.id.toString, Some(40L))) should include("outside the offer's bounds")
      // A shorter length bound than the auction's is refused too.
      w.run(o, w.app(o).createOffer(offerSpec(w).copy(auctionFloorBps = 8000L, auctionLength = 499L)))
      refused(w.app(e).matchAsk(offerWith(8000L, 499L), ask.box.id.toString, Some(40L))) should include("outside the offer's bounds")

      w.run(e, w.app(e).matchAsk(offerWith(8000L, 1000L), ask.box.id.toString, Some(40L)))
      w.tokens(o).get(shareId) shouldBe Some(40L)
      w.app(e).asks().loneElement.token.amount shouldBe 20L
    }
  }

  property("lend offer in SigUSD: a fill delivers into the Dutch share request it names, and a batcher mints to its owner") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, e, o) = (borrower(ctx), executor(ctx), other(ctx))
      w.fund(b, erg("20"), Token(collateralToken, 5L))
      w.fund(e, erg("1"))
      w.fund(o, erg("5"), Token(SigUsd, 1000L))
      w.oracle(500000000L, w.h)
      val auction = Some(AuctionTerms(15000L, 9000L, 500L))
      w.run(o, w.app(o).createOffer(offerSpec(w).copy(market = Market.SigUsd, capital = 1000L, fees = erg("0.5"),
        delivery = Some(OfferDelivery(terms(100L, auction))))))
      w.run(b, w.app(b).createOrder(loan(Market.SigUsd, principal = 600L, repayment = 700L,
        collateral = Seq(Token(collateralToken, 5L)), rule = OracleChoice(15000L, 60L))(w)))
      val order = w.app(e).orders().loneElement

      w.run(e, w.app(e).matchOrder(w.app(e).offers().loneElement.box.id.toString, order.box.id.toString))
      w.tokens(b).get(SigUsd) shouldBe Some(600L)
      w.app(e).offers().loneElement.capital shouldBe 400L
      val req = w.app(e).shareRequests().loneElement
      req.bondAsset shouldBe order.box.id
      req.dutch shouldBe Some(Market.SigUsd)
      // The request names the offer's owner; the fill disclosed its script in context variable 64 (zcs1).
      w.run(e, w.app(e).mintShares(req.box.id.toString))
      w.tokens(o).get(req.shareId) shouldBe Some(100L)
      w.app(e).shareVault(req.shareId).get.currencyToken shouldBe Some(SigUsd)
    }
  }

  // ═══ BATCH ════════════════════════════════════════════════════════════════

  property("batch: lists every mint, pull, refund, liquidation and offer match, and each one builds and signs") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l, e, o) = (borrower(ctx), lender(ctx), executor(ctx), other(ctx))
      w.fund(b, erg("60"), Token(collateralToken, 300L))
      w.fund(l, erg("30"))
      w.fund(e, erg("1"))
      w.fund(o, erg("30"))
      w.oracle(500000000L, w.h)

      // A request waiting for its mint, and a minted vault whose bond has been repaid.
      w.run(l, w.app(l).fractionalize(w.liveBond(loan(collateral = Seq(Token(collateralToken, 10L)))(w), l).asset.toString, terms(10L)))
      val repaid = w.liveBond(loan(collateral = Seq(Token(collateralToken, 20L)))(w), l)
      w.run(l, w.app(l).fractionalize(repaid.asset.toString, terms(10L)))
      w.run(l, w.app(l).mintShares(w.app(l).shareRequests().find(_.bondAsset == repaid.asset).get.box.id.toString))
      w.run(b, w.app(b).repay(repaid.box.id.toString))
      // An order past its expiry, a matured bond, and an offer with an order it would fill.
      w.seedFrom(b, UTXO(protocol.market(Market.Erg).order, erg("3") + fee, Seq(Token(collateralToken, 7L)),
        Seq(longsValue(erg("2"), erg("1"), erg("2"), w.h + 1000L, w.h.toLong, fee, 0L), tokensValue(Seq.empty),
          bytesValue(hashOf(w.app(b).myTree)), bytesValue(protocol.contracts.maturity.hashedValueBytes))))
      w.bond(Market.Erg, org.ergoplatform.sdk.ErgoId.create("c1" * 32), erg("3"), Seq.empty, erg("2"),
        protocol.contracts.maturity, start = w.h - 100L, maturity = w.h.toLong, moved = w.h - 100)
      w.run(o, w.app(o).createOffer(offerSpec(w)))
      w.run(b, w.app(b).createOrder(oracleLoan(w, 40L)))

      val work = w.app(e).batchable()
      work.map(_.command.split(" ").head).sorted shouldBe
        Seq("liquidate", "match-offer", "mint-shares", "pull", "refund-order")
      work.foreach { job =>
        val app = w.app(e)
        val words = job.command.split(" ").toSeq
        w.run(e, words match {
          case Seq("mint-shares", id) => app.mintShares(id)
          case Seq("pull", id) => app.pull(id)
          case Seq("refund-order", id) => app.refundOrder(id)
          case Seq("liquidate", id) => app.liquidate(Seq(id))
          case Seq("match-offer", offer, "--order", order) => app.matchOrder(offer, order)
        })
      }
      w.app(e).batchable() shouldBe empty
    }
  }
}
