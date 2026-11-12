package org.lithosprotocol.sigmafi
package specs.app

import io.circe.Json
import ksingh.sigmabonds.app.Protocol._
import ksingh.sigmabonds.app._
import ksingh.sigmabonds.standard._
import org.ergoplatform.appkit.{ErgoProver, Parameters}
import org.ergoplatform.appkit.impl.InputBoxImpl
import org.ergoplatform.sdk.ErgoId
import org.scalatest.LoneElement
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Contract, Token, UTXO}

import scala.collection.mutable

/** The zcs1 off-chain rules (docs/ZC_STANDARD_OFFCHAIN.md) as the CLI's app follows them, end to end. */
class StandardOffchainSpec extends AnyPropSpec with StandardSpecSupport with LoneElement {

  private val fee = erg("0.005")
  private def tree(p: ErgoProver): Contract = Contract.fromAddress(p.getAddress)

  private def offerSpec(w: World, capital: Long = erg("20")): OfferSpec =
    OfferSpec(Market.Erg, capital, 0L, yieldBps = 1000L, minBlocks = 1L, maxBlocks = 262800L, batcherFee = fee,
      feeReserve = reserve, floorBps = 13000L, maxAge = 30L, expiry = w.h + 500L, minPrice = 1L,
      auctionFloorBps = 0L, auctionLength = 0L, delivery = None)

  private def oracleLoan(w: World, tokens: Long, repayment: Long = erg("6")): StdLoan =
    loan(repayment = repayment, collateral = Seq(Token(collateralToken, tokens)), rule = OracleChoice(15000L, 60L))(w)

  /** An expired lend offer owned by `owner`, with the given batcher fee. */
  private def expiredOffer(w: World, owner: ErgoProver, value: Long = erg("2")): UTXO =
    UTXO(protocol.market(Market.Erg).offer, value, Seq.empty,
      Seq(bytesValue(hashOf(tree(owner).ergoTree)),
        longsValue(0L, 1L, 262800L, fee, reserve, 13000L, w.h.toLong, 1L, 30L, 0L, 0L),
        bytesValue(protocol.market(Market.Erg).policy.hashedValueBytes), longsValue(), textsValue(), bytesValue(Array.empty[Byte])))

  private def text(box: work.lithos.mutations.InputUTXO, r: Int): Option[String] = bytesReg(box, r).map(StandardMetadata.text)

  // ═══ SCRIPT DISCLOSURE ════════════════════════════════════════════════════

  property("disclosure: a fill into a share request puts the owner's tree in var 64 of INPUTS(0); a wallet's own boxes need none") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, e, o) = (borrower(ctx), executor(ctx), other(ctx))
      w.fund(b, erg("40"), Token(collateralToken, 100L))
      w.fund(e, erg("1"))
      w.fund(o, erg("30"))
      w.oracle(500000000L, w.h)

      // The borrower posts from its own address: the order's creating transaction spends from it, so no var 64.
      val posted = w.run(b, w.app(b).createOrder(oracleLoan(w, 50L)))
      w.disclosed(posted.getId, e) shouldBe empty
      w.run(o, w.app(o).createOffer(offerSpec(w).copy(delivery = Some(OfferDelivery(ShareTerms(pullFee = fee, mintFee = fee))))))

      // The fill names the owner in the request's R7 and the continuation's R4, and pays it nothing.
      val order = w.app(e).orders().loneElement
      val prepared = w.app(e).matchOrder(w.app(e).offers().loneElement.box.id.toString, order.box.id.toString)
      prepared.notes.exists(_.contains("discloses 1 script")) shouldBe true
      val filled = w.run(e, prepared)
      w.disclosed(filled.getId, e) shouldBe Seq(tree(o).ergoTree.bytes.toSeq)

      // A batcher that has never seen the owner finds it there, in one lookup, and mints to it.
      val req = w.app(e).shareRequests().loneElement
      w.run(e, w.app(e).mintShares(req.box.id.toString))
      w.tokens(o).get(req.shareId) shouldBe Some(StandardMetadata.ShareCount)

      // The continuation was made by the fill too, so its next fill finds the owner the same way.
      w.run(b, w.app(b).createOrder(oracleLoan(w, 40L)))
      val second = w.app(e).orders().loneElement
      val again = w.run(e, w.app(e).matchOrder(w.app(e).offers().loneElement.box.id.toString, second.box.id.toString))
      w.disclosed(again.getId, e) shouldBe Seq(tree(o).ergoTree.bytes.toSeq)
    }
  }

  property("disclosure: a box whose creating transaction hides the script it names is passed over and refused; disclosed, it is served") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (l, e, o) = (lender(ctx), executor(ctx), other(ctx))
      w.fund(e, erg("1"))
      // Owned by `o` but funded by `l`: nothing in the creating transaction is o's script.
      val hidden = w.seedFrom(l, expiredOffer(w, o))
      val scan = w.app(e).batchScan()
      scan.work.map(_.command) should not contain s"refund-offer ${hidden.id}"
      scan.skipped.loneElement should (include(hidden.id.toString) and include("does not disclose its owner's script"))
      refused(w.app(e).refundOffer(hidden.id.toString)) should include("does not disclose")
      // The owner's own wallet knows its script, so the owner can still act on it.
      w.app(o).refundOffer(hidden.id.toString).tx.getOutputs.get(0).getErgoTree shouldBe tree(o).ergoTree

      // The same offer, disclosing the owner in var 64.
      val open = w.seedDisclosing(l, expiredOffer(w, o, erg("3")), tree(o))
      w.app(e).batchScan().work.map(_.command) should contain(s"refund-offer ${open.id}")
      val o0 = w.nano(o)
      w.run(e, w.app(e).refundOffer(open.id.toString))
      w.nano(o) shouldBe o0 + erg("3") - fee
    }
  }

  // ═══ TOKEN METADATA ═══════════════════════════════════════════════════════

  property("metadata: a fill's bond unit carries the zcs1 name, description and decimals, and the check passes") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l) = (borrower(ctx), lender(ctx))
      w.fund(b, erg("20"), Token(collateralToken, 100L))
      w.fund(l, erg("20"))
      val bond = w.liveBond(loan()(w), l)
      val maturity = w.h + 1000L
      val unit = w.app(l).walletBoxes.find(_.tokens.exists(_.id == bond.asset)).get
      text(unit, 4) shouldBe Some(s"ZCS-ERG-B-$maturity-${bond.asset.toString.take(6)}")
      text(unit, 5) shouldBe Some(s"zcs1 bond: repays 6 ERG at height $maturity; lent 5 at height ${w.h}; " +
        "liquidates at maturity. Verify on chain.")
      text(unit, 6) shouldBe Some("0")
      w.app(l).bondMetadataIssues(bond.asset) shouldBe empty
    }
  }

  property("metadata: the template formats SigUSD amounts, oracle ratios and Dutch auctions exactly") {
    val asset = ErgoId.create("3fa2c1" + "00" * 29)
    val bond = StandardMetadata.bond(StandardMetadata.BondFacts(Market.SigUsd, asset, 10000L, 10550L, 1712000L,
      1749600L, StandardMetadata.ruleText(Some(OracleRatio(Market.SigUsd, protocol.market(Market.SigUsd).oracleRule)),
        Seq(15000L, 60L), Array.fill(32)(0.toByte))))
    bond shouldBe Seq("ZCS-SigUSD-B-1749600-3fa2c1",
      "zcs1 bond: repays 105.5 SigUSD at height 1749600; lent 100 at height 1712000; liquidates below 150% or at maturity. " +
        "Verify on chain.", "0")
    val auction = Some(AuctionTerms(12000L, 9050L, 720L))
    StandardMetadata.shares(Market.SigUsd, auction, Some(StandardMetadata.ShareBond(asset, 10550L, 1749600L))) shouldBe
      Seq("ZCS-SigUSD-D90.5-1749600-3fa2c1",
        "zcs1 shares: 1.000000 = one bond 3fa2c1 repaying 105.5 SigUSD at height 1749600. If liquidated, auctioned " +
          "from 120% to 90.5% of the repayment over 720 blocks. Verify on chain.", "6")
    StandardMetadata.shares(Market.Erg, None, None) shouldBe
      Seq("ZCS-ERG-S", "zcs1 shares: 1.000000 = one ERG bond. Verify on chain.", "6")
    StandardMetadata.ruleText(None, Seq.empty, Array.fill(32)(0xab.toByte)) shouldBe "liquidation rule abababab"
  }

  property("metadata: a misleading name or an R7 set on a fill's unit is flagged") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l) = (borrower(ctx), lender(ctx))
      // A standard order, spent by a fill whose unit was named after a stablecoin and given an asset type.
      val order = w.seed(UTXO(protocol.market(Market.Erg).order, erg("3") + fee, Seq.empty,
        Seq(longsValue(erg("2"), erg("1"), erg("2"), w.h + 1000L, w.h + 500L, fee, 0L), tokensValue(Seq.empty),
          bytesValue(hashOf(tree(b).ergoTree)), bytesValue(protocol.contracts.maturity.hashedValueBytes))))
      val asset = order.id
      val first = w.bond(Market.Erg, asset, erg("3"), Seq.empty, erg("2"), protocol.contracts.maturity,
        start = w.h.toLong, maturity = w.h + 1000L, moved = w.h)
      val txId = "ee" * 32
      val unit = UTXO(tree(l), MinBox, Seq(Token(asset, 1L)),
        Seq(utf8Value("SigUSD"), utf8Value("a stablecoin"), utf8Value("0"), bytesValue(Array[Byte](1, 1))))
        .toInput(ctx, ErgoId.create(txId), 0.toShort).input.asInstanceOf[InputBoxImpl].getErgoBox
      val orderBox = order.input.asInstanceOf[InputBoxImpl].getErgoBox
      w.chain.unspent.remove(asset.toString)
      w.chain.spent(asset.toString) = (orderBox, txId)
      w.chain.txs(txId) = FakeTx(Seq(orderBox), Seq(unit, first.input.asInstanceOf[InputBoxImpl].getErgoBox))
      val issues = w.app(l).bondMetadataIssues(asset)
      issues.exists(_.contains("its name is 'SigUSD'")) shouldBe true
      issues.exists(_.contains("R7 to R9")) shouldBe true
    }
  }

  property("metadata: fractionalize names the bond, an offer's delivery cannot, and both check as zcs1; overrides are flagged") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l, e, o) = (borrower(ctx), lender(ctx), executor(ctx), other(ctx))
      w.fund(b, erg("40"), Token(collateralToken, 110L))
      w.fund(l, erg("20"))
      w.fund(e, erg("1"))
      w.fund(o, erg("5"), Token(SigUsd, 1000L))
      w.oracle(500000000L, w.h)

      // Dutch shares of a known bond: named after it, with the floor in the kind.
      val bond = w.liveBond(loan(collateral = Seq(Token(collateralToken, 100L)))(w), l)
      val auction = Some(AuctionTerms(15000L, 9000L, 500L))
      w.run(l, w.app(l).fractionalize(bond.asset.toString, ShareTerms(pullFee = fee, mintFee = fee, auction = auction)))
      val req = w.app(e).shareRequests().loneElement
      req.shares shouldBe 1000000L
      StandardMetadata.text(req.metadata.head) shouldBe s"ZCS-ERG-D90-${w.h + 1000L}-${bond.asset.toString.take(6)}"
      w.app(e).requestMetadataIssues(req) shouldBe empty
      w.run(e, w.app(e).mintShares(req.box.id.toString))
      val shares = w.app(l).walletBoxes.find(_.tokens.exists(_.id == req.shareId)).get
      text(shares, 6) shouldBe Some("6")
      w.app(e).shareMetadataIssues(req.shareId) shouldBe empty

      // A SigUSD offer delivering Dutch shares is posted before it knows its bond.
      w.run(o, w.app(o).createOffer(offerSpec(w).copy(market = Market.SigUsd, capital = 1000L, fees = erg("0.5"),
        delivery = Some(OfferDelivery(ShareTerms(pullFee = fee, mintFee = fee, auction = auction))))))
      val offer = w.app(e).offers().loneElement
      offer.metadata.map(StandardMetadata.text).head shouldBe "ZCS-SigUSD-D90"
      w.app(e).offerMetadataIssues(offer) shouldBe empty
      w.run(b, w.app(b).createOrder(loan(Market.SigUsd, principal = 600L, repayment = 700L,
        collateral = Seq(Token(collateralToken, 5L)), rule = OracleChoice(15000L, 60L))(w)))
      w.run(e, w.app(e).matchOrder(offer.box.id.toString, w.app(e).orders().loneElement.box.id.toString))
      val delivered = w.app(e).shareRequests().loneElement
      w.run(e, w.app(e).mintShares(delivered.box.id.toString))
      w.app(e).shareMetadataIssues(delivered.shareId) shouldBe empty

      // Overrides build, but say they are nonstandard, and the check says so too.
      val own = w.liveBond(loan(collateral = Seq(Token(collateralToken, 5L)))(w), l)
      val custom = w.app(l).fractionalize(own.asset.toString, ShareTerms(100L, fee, fee, name = Some("My shares")))
      custom.notes.exists(_.startsWith("NONSTANDARD share count")) shouldBe true
      custom.notes.exists(_.startsWith("NONSTANDARD metadata")) shouldBe true
      w.run(l, custom)
      val issues = w.app(e).requestMetadataIssues(w.app(e).shareRequests().find(_.bondAsset == own.asset).get)
      issues.exists(_.contains("share count is 100")) shouldBe true
      issues.exists(_.contains("'My shares'")) shouldBe true
    }
  }

  // ═══ SHARE COUNT, FACE, LOTS ══════════════════════════════════════════════

  property("shares: asks quote a percentage of face, share asks default to the least lot, and a dust claim warns") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l, e) = (borrower(ctx), lender(ctx), executor(ctx))
      w.fund(b, erg("20"), Token(collateralToken, 200L), Token(SigUsd, 200L))
      w.fund(l, erg("20"), Token(SigUsd, 600L))
      w.fund(e, erg("1"))

      // A whole unit at 5.5 ERG for a 6 ERG repayment is 91.66% of face.
      val ergBond = w.liveBond(loan(collateral = Seq(Token(collateralToken, 100L)))(w), l)
      w.app(l).faceBps(ergBond.asset, erg("5.5"), 1L) shouldBe Some(9166L)

      // 1,000,000 shares of a 7.00 SigUSD bond: 1429 shares claim at least one cent.
      val bond = w.liveBond(loan(Market.SigUsd, principal = 600L, repayment = 700L, collateralErg = erg("2"),
        collateral = Seq(Token(collateralToken, 100L)))(w), l)
      w.run(l, w.app(l).fractionalize(bond.asset.toString, ShareTerms(pullFee = fee, mintFee = fee)))
      w.run(e, w.app(e).mintShares(w.app(e).shareRequests().loneElement.box.id.toString))
      val shareId = w.app(e).shareVaults().loneElement.shareId
      w.app(l).shareLot(shareId) shouldBe Some(1429L)
      w.app(l).faceBps(shareId, 350L, 500000L) shouldBe Some(10000L)

      val listed = w.app(l).sell(shareId.toString, 500000L, Market.SigUsd, FixedPrice(340L), None, w.h + 100L, erg("0.002"))
      listed.notes.exists(_.startsWith("WARNING")) shouldBe false
      w.run(l, listed)
      w.app(e).asks().loneElement.minFill shouldBe 1429L
      w.app(l).sell(shareId.toString, 1000L, Market.SigUsd, FixedPrice(1L), None, w.h + 100L, erg("0.002"))
        .notes.exists(_.contains("fills under 1429 shares")) shouldBe true

      // Repaid and pulled: the vault holds 700 SigUSD, so a 1000-share claim takes none of it.
      w.run(b, w.app(b).repay(bond.box.id.toString))
      w.run(e, w.app(e).pull(w.app(e).shareVaults().loneElement.box.id.toString))
      val dust = w.app(l).claimShares(shareId.toString, Some(1000L))
      dust.notes.exists(n => n.startsWith("WARNING") && n.contains(SigUsd.toString) && n.contains("at least 1429 shares")) shouldBe true
      // It still builds and signs; the SigUSD stays in the vault for later claimants.
      w.run(l, dust)
      w.app(e).shareVaults().loneElement.assets shouldBe Seq(Token(SigUsd, 700L))
      w.app(l).claimShares(shareId.toString, Some(1429L)).notes.exists(_.startsWith("WARNING")) shouldBe false
    }
  }

  // ═══ MATURITY GRID ════════════════════════════════════════════════════════

  property("grid: a maturity snaps to the nearest multiple of 21600 blocks that can still be filled, a tie going later") {
    import StandardProtocol.snapMaturity
    snapMaturity(1743200L, 1700000L) shouldBe 1749600L
    snapMaturity(1749600L, 1700000L) shouldBe 1749600L
    snapMaturity(1738800L, 1700000L) shouldBe 1749600L
    snapMaturity(1730000L, 1700000L) shouldBe 1728000L
    // The nearest grid height is too close to the current height, so the next one is taken.
    snapMaturity(1728005L, 1727999L) shouldBe 1749600L
    Args.parse(Args.tokenize("create-order --term 43200 --exact-maturity")).flag("exact-maturity") shouldBe true
    Args.parse(Args.tokenize("create-offer --deliver-shares --shares 10")).flag("deliver-shares") shouldBe true
  }

  // ═══ BATCHING ═════════════════════════════════════════════════════════════

  property("pack: refunds of an order, an offer and an ask and a liquidation share one transaction and one miner fee") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l, e, o) = (borrower(ctx), lender(ctx), executor(ctx), other(ctx))
      w.fund(b, erg("1"))
      w.fund(l, erg("1"))
      w.fund(e, erg("1"))
      w.fund(o, erg("1"))
      val order = w.seedFrom(b, UTXO(protocol.market(Market.Erg).order, erg("3") + fee, Seq(Token(collateralToken, 7L)),
        Seq(longsValue(erg("2"), erg("1"), erg("2"), w.h + 1000L, w.h.toLong, fee, 0L), tokensValue(Seq.empty),
          bytesValue(hashOf(tree(b).ergoTree)), bytesValue(protocol.contracts.maturity.hashedValueBytes))))
      val offer = w.seedFrom(o, expiredOffer(w, o))
      // An ask whose refund fee, 0.0005 ERG, is under the miner fee: worth running only packed.
      val ask = w.seedFrom(l, UTXO(protocol.market(Market.Erg).saleOrder, MinBox + erg("0.0005"),
        Seq(Token(collateralToken, 3L)), Seq(bytesValue(hashOf(tree(l).ergoTree)), longsValue(1L, 1L, 1L, w.h.toLong))))
      val due = w.bond(Market.Erg, ErgoId.create("d1" * 32), erg("3"), Seq.empty, erg("2"),
        protocol.contracts.maturity, start = w.h - 100L, maturity = w.h.toLong, moved = w.h - 100)

      val work = w.app(e).batchScan().work
      work.filter(_.packable).flatMap(_.packBox).toSet shouldBe Set(order, offer, ask, due).map(_.id.toString)
      work.find(_.packBox.contains(ask.id.toString)).get.reward shouldBe erg("0.0005")

      val (b0, l0, o0, e0) = (w.nano(b), w.nano(l), w.nano(o), w.nano(e))
      w.run(e, w.app(e).pack(Seq(order, offer, ask, due).map(_.id.toString)))
      w.nano(b) shouldBe b0 + erg("3")
      w.tokens(b).get(collateralToken) shouldBe Some(7L)
      w.nano(o) shouldBe o0 + erg("2") - fee
      w.tokens(l).get(collateralToken) shouldBe Some(3L)
      w.nano(l) shouldBe l0 + MinBox
      w.app(e).settlements().loneElement.liquidated shouldBe true
      w.nano(e) shouldBe e0 + fee + fee + erg("0.0005") + reserve - Parameters.MinFee
      refused(w.app(e).pack(Seq(order.id.toString))) should include("spent")
    }
  }

  property("batch: the highest-fee offer takes the best yield, and a run chains a second fill through the continuation") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, e, o, l) = (borrower(ctx), executor(ctx), other(ctx), lender(ctx))
      w.fund(b, erg("60"), Token(collateralToken, 300L))
      w.fund(e, erg("1"))
      w.fund(o, erg("30"))
      w.fund(l, erg("30"))
      w.oracle(500000000L, w.h)
      w.run(b, w.app(b).createOrder(oracleLoan(w, 40L, repayment = erg("6"))))
      w.run(b, w.app(b).createOrder(oracleLoan(w, 50L, repayment = erg("6.5"))))
      val orders = w.app(e).orders()
      val better = orders.find(_.repayment == erg("6.5")).get.box.id.toString
      val worse = orders.find(_.repayment == erg("6")).get.box.id.toString

      // Two offers: the one paying the higher batcher fee is served first and gets the 6.5 ERG order.
      w.run(o, w.app(o).createOffer(offerSpec(w).copy(batcherFee = erg("0.01"))))
      w.run(l, w.app(l).createOffer(offerSpec(w)))
      val high = w.app(e).offers().find(_.batcherFee == erg("0.01")).get.box.id.toString
      val low = w.app(e).offers().find(_.batcherFee == fee).get.box.id.toString
      w.app(e).batchScan().work.map(_.command).toSet shouldBe
        Set(s"match-offer $high --order $better", s"match-offer $low --order $worse")

      // With one offer, a run fills the better order, scans again, and fills the other from the continuation.
      w.run(l, w.app(l).cancelOffer(low))
      w.run(o, w.app(o).cancelOffer(high))
      w.run(o, w.app(o).createOffer(offerSpec(w)))
      val first = w.app(e).offers().loneElement.box.id.toString
      val log = w.app(e).runBatch(w.runner(e))
      log.map(_._2) shouldBe Seq(true, true)
      log.head._1 shouldBe s"match-offer $first --order $better"
      log(1)._1 should (startWith("match-offer ") and endWith(s"--order $worse"))
      log(1)._1 should not include first
      w.app(e).orders() shouldBe empty
      w.app(e).offers().loneElement.capital shouldBe erg("20") - 2 * (erg("5") + fee + MinBox + reserve)
    }
  }

  property("batch: a run packs refunds that pay under the miner fee alone, and skips nothing it can serve") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (l, e) = (lender(ctx), executor(ctx))
      w.fund(l, erg("1"))
      w.fund(e, erg("1"))
      val asks = Seq(1L, 2L, 3L).map(n => w.seedFrom(l, UTXO(protocol.market(Market.Erg).saleOrder, MinBox + erg("0.0008"),
        Seq(Token(collateralToken, n)), Seq(bytesValue(hashOf(tree(l).ergoTree)), longsValue(1L, 1L, 1L, w.h.toLong)))))
      val log = w.app(e).runBatch(w.runner(e))
      log.loneElement._1 should startWith("pack ")
      log.loneElement._2 shouldBe true
      asks.foreach(a => log.head._1 should include(a.id.toString))
      w.tokens(l).get(collateralToken) shouldBe Some(6L)
    }
  }

  // ═══ SESSION AND MEMPOOL ══════════════════════════════════════════════════

  property("session: a box this session spent is never built on, and wallet boxes it spent are left out") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, e) = (borrower(ctx), executor(ctx))
      val funds = w.fund(e, erg("1"))
      val order = w.seedFrom(b, UTXO(protocol.market(Market.Erg).order, erg("3") + fee, Seq.empty,
        Seq(longsValue(erg("2"), erg("1"), erg("2"), w.h + 1000L, w.h.toLong, fee, 0L), tokensValue(Seq.empty),
          bytesValue(hashOf(tree(b).ergoTree)), bytesValue(protocol.contracts.maturity.hashedValueBytes))))
      val spent = mutable.Set(order.id.toString, funds.id.toString)
      val app = w.app(e, AppSettings(spent = spent))
      refused(app.refundOrder(order.id.toString)) should include("spent by a transaction this session submitted")
      app.walletBoxes shouldBe empty
    }
  }

  property("mempool: lookups ask the node to include unconfirmed boxes and leave out mempool spends only when asked") {
    class Recorder(mempool: Boolean) extends NodeApi("http://recorder.invalid", None, mempool) {
      val calls = mutable.ArrayBuffer.empty[(String, Map[String, String])]
      override protected def request(method: String, path: String, query: Seq[(String, Any)], body: Option[Json]): Option[Json] = {
        calls += path -> query.map { case (k, v) => k -> v.toString }.toMap
        None
      }
    }
    val live = new Recorder(true)
    live.unspentByTokenId("ab")
    live.boxById("cd")
    live.walletUnspentBoxes()
    live.calls.map(_._1) shouldBe Seq("/blockchain/box/unspent/byTokenId/ab", "/blockchain/box/byId/cd",
      "/utxo/withPool/byId/cd", "/wallet/boxes/unspent")
    live.calls.head._2 should contain allOf ("includeUnconfirmed" -> "true", "excludeMempoolSpent" -> "true")
    live.calls(3)._2 should contain("minConfirmations" -> "-1")

    val plain = new Recorder(false)
    plain.unspentByTokenId("ab")
    plain.boxById("cd")
    plain.calls.map(_._1) shouldBe Seq("/blockchain/box/unspent/byTokenId/ab", "/blockchain/box/byId/cd")
    plain.calls.head._2.keySet should not contain "includeUnconfirmed"
  }
}
