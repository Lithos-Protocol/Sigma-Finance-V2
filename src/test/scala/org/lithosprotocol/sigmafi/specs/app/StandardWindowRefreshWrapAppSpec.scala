package org.lithosprotocol.sigmafi
package specs.app

import org.lithosprotocol.sigmafi.app.Protocol._
import org.lithosprotocol.sigmafi.app._
import org.lithosprotocol.sigmafi.standard._
import org.ergoplatform.appkit.{ErgoProver, ErgoValue}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.LoneElement
import org.scalatest.propspec.AnyPropSpec
import org.lithosprotocol.sigmafi.mutations.{Contract, Token, UTXO}

import scala.collection.JavaConverters._

/**
 * The client for window orders, paid refreshes, custody wraps and Dutch excess auctions, end to end over the pinned
 * trees: the CLI's options, zcs1's choices (a window's maturity, a refresh's stamp and disclosure), and the batch scan.
 */
class StandardWindowRefreshWrapAppSpec extends AnyPropSpec with StandardSpecSupport with LoneElement {

  private def command(w: World, p: ErgoProver, line: String): Prepared = {
    var captured: Option[Prepared] = None
    val node = new FakeNode(w.chain, p)
    val app = new StandardApp(w.ctx, node, AppSettings(), _ => (), protocol) {
      override def submit(prepared: Prepared): Option[String] = { captured = Some(prepared); None }
    }
    StandardCommands.dispatch(app, node, Args.parse(Args.tokenize(line)), hasApiKey = true)
    captured.get
  }

  private val Grid = StandardProtocol.MaturityGrid

  // ═══ WINDOW ORDERS ════════════════════════════════════════════════════════

  property("window order: --window and --curve build it; a wallet fills at the earliest grid height or a chosen maturity") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l) = (borrower(ctx), lender(ctx))
      w.fund(b, erg("60"), Token(collateralToken, 300L))
      w.fund(l, erg("60"))
      val create = "create-order --principal 4 --repayment 6 --collateral-erg 10 --window 30:40000"
      w.run(b, command(w, b, create + s" --collateral-token $collateralToken:100 --yield 50 --curve 20:10000,50:40000"))
      val order = w.app(l).orders().loneElement
      order.window shouldBe true
      protocol.curveOrder(order) shouldBe true
      protocol.orderWindow(order) shouldBe Some((30L, 40000L))
      protocol.orderBorrowCurve(order).map(c => (c.maxBps, c.points)) shouldBe Some((5000L, Seq(2000L -> 10000L, 5000L -> 40000L)))
      // zcs1's default: the earliest grid height in the window.
      val grid = Iterator.iterate(w.h.toLong + 30L)(_ + 1L).find(_ % Grid == 0L).get
      val quote = (0L to 3L).map(d => w.app(l).orderPrincipal(order, w.h + d, w.h.toLong, Some(grid))).max
      w.run(l, w.app(l).fillOrder(order.box.id.toString, reserve))
      val bond = w.app(l).bonds().loneElement
      bond.maturity shouldBe grid
      bond.principal shouldBe quote
      bond.terms(6) shouldBe 2L
      quote should be > erg("4")

      // A chosen maturity inside the window; outside it, refused before anything is built.
      w.run(b, command(w, b, create + s" --collateral-token $collateralToken:101 --yield 30"))
      val second = w.app(l).orders().loneElement
      refused(w.app(l).fillOrder(second.box.id.toString, reserve, chosenMaturity = Some(w.h + 40001L))) should include("outside the order's window")
      w.run(l, w.app(l).fillOrder(second.box.id.toString, reserve, chosenMaturity = Some(w.h + 5000L)))
      w.app(l).bonds().find(_.asset == second.box.id).get.maturity shouldBe w.h + 5000L
      // A curve needs a window, and a window a curve rather than a moving yield; a fixed order's maturity is not the lender's.
      refused(command(w, b, "create-order --principal 4 --repayment 6 --collateral-erg 10 --duration 100 --curve 5:10")) should include("needs --window")
      refused(command(w, b, create + " --yield 5 --end-yield 10")) should include("does not move with time")
      refused(command(w, b, "create-order --principal 4 --repayment 6 --collateral-erg 10 --window 29:100 --yield 5")) should include("at least 30")
      refused(command(w, b, "create-order --principal 4 --repayment 6 --collateral-erg 10 --window 30")) should include("--window")
    }
  }

  property("window order: an offer's batch match names zcs1's maturity, a grid height both accept, and fills there") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l, e) = (borrower(ctx), lender(ctx), executor(ctx))
      w.fund(b, erg("60"), Token(collateralToken, 100L))
      w.fund(l, erg("60"))
      w.fund(e, erg("2"))
      w.oracle(500000000L, w.h)
      w.run(b, w.app(b).createOrder(loan(rule = OracleChoice(15000L, 30L))(w).copy(principal = erg("4"),
        schedule = MaturityWindow(30L, 50000L), price = Some(CurveOrderPrice(5000L, Seq(2000L -> 10000L, 5000L -> 50000L))))))
      w.run(l, w.app(l).createOffer(OfferSpec(Market.Erg, erg("20"), 0L, 0L, 1L, 262800L, erg("0.005"), reserve, 15000L, 30L,
        w.h + 500L, 1L, 0L, 0L, None, curve = Seq(1000L -> 10000L, 1500L -> 50000L))))
      val matches = w.app(e).batchScan().work.filter(_.command.startsWith("match-offer"))
      val chosen = matches.loneElement.command.split(" ").last.toLong
      chosen % Grid shouldBe 0L
      chosen should (be >= w.h + 30L and be <= w.h + 50000L)
      w.app(e).runBatch(w.runner(e)).count(_._2) should be >= 1
      w.app(e).bonds().loneElement.maturity shouldBe chosen
    }
  }

  // ═══ REFRESH ══════════════════════════════════════════════════════════════

  /** Two years and more pass: an app at `w.h + Later`, whose transactions run their scripts at that height. */
  private val Later = 600000
  private def later(w: World, p: ErgoProver): StandardApp =
    new StandardApp(w.ctx, new FakeNode(w.chain, p), AppSettings(), _ => (), protocol) {
      override def height: Int = w.h + Later
    }

  property("refresh: an old bond refreshes for its bounty, stamped 60 back, disclosing its borrower; a third party then repays it") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l, e) = (borrower(ctx), lender(ctx), executor(ctx))
      w.fund(b, erg("60"), Token(collateralToken, 100L))
      w.fund(l, erg("60"))
      w.fund(e, erg("30"))
      val live = w.liveBond(loan(maturityIn = 700000)(w), l)
      refused(w.app(e).refresh(Seq(live.box.id.toString))) should include("can be refreshed from height")
      // A copy of the bond whose creating transaction is unknown, as a refresher that disclosed nothing would leave
      // it: the client still finds the borrower in the fill that minted the asset.
      val copy = w.seed(UTXO(Contract(live.box.input.getErgoTree), live.box.value, live.box.tokens, live.box.registers,
        Some(w.h)))
      later(w, e).batchScan().work.find(_.command == s"refresh ${copy.id}").map(_.reward) shouldBe
        Some(StandardProtocol.RefreshBounty)
      val tx = w.run(e, later(w, e).refresh(Seq(copy.id.toString)))
      val next = tx.getOutputsToSpend.asScala.find(_.getErgoTree == live.box.input.getErgoTree).get
      next.getCreationHeight shouldBe w.h + Later - 60
      next.getValue.longValue shouldBe copy.value - StandardProtocol.RefreshBounty
      w.disclosed(tx.getId, e) should contain(Contract.fromAddress(b.getAddress).ergoTree.bytes.toSeq)
      // Anyone repays the refreshed bond: its borrower is found through the refresh's disclosure.
      w.fund(e, erg("10"))
      w.run(e, later(w, e).repay(next.getId.toString))
    }
  }

  property("refresh: none in the 720 blocks before maturity; vaults pack together; a wrap vault goes alone") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l, e) = (borrower(ctx), lender(ctx), executor(ctx))
      w.fund(b, erg("60"), Token(collateralToken, 100L))
      w.fund(l, erg("60"))
      w.fund(e, erg("30"))
      val near = w.liveBond(loan(maturityIn = Later + 500)(w), l)
      refused(later(w, e).refresh(Seq(near.box.id.toString))) should include("before maturity")
      later(w, e).batchScan().work.exists(_.command == s"refresh ${near.box.id}") shouldBe false
      // A settled bond vault and a share vault, both two years old, refresh in one transaction.
      val asset = ErgoId.create("ab" * 32)
      val settlement = w.seed(UTXO(protocol.contracts.vault, erg("1"), Seq(Token(asset, 2L), Token(collateralToken, 5L)),
        Seq(bytesValue(asset.getBytes), ErgoValue.of(1.toByte)), Some(w.h)))
      val share = ErgoId.create("ac" * 32)
      val vault = w.seed(UTXO(protocol.contracts.fractionalVault, erg("0.5"), Seq(Token(share, 101L), Token(collateralToken, 3L)),
        Seq(bytesValue(asset.getBytes), longsValue(100L, 100L, erg("0.002")), ErgoValue.of(0.toByte)), Some(w.h)))
      val scan = later(w, e).batchScan().work.map(_.command)
      scan should contain allOf (s"refresh ${settlement.id}", s"refresh ${vault.id}")
      // A wrap vault refreshes alone, and never beside anything else.
      val wrapped = w.seed(UTXO(protocol.contracts.wrapVault, erg("0.03"), Seq(Token(ErgoId.create("ad" * 32), 2L),
        Token(ErgoId.create("ae" * 32), 1L)), Seq.empty, Some(w.h)))
      refused(later(w, e).refresh(Seq(wrapped.id.toString, settlement.id.toString))) should include("only input")
      val packed = w.run(e, later(w, e).refresh(Seq(settlement.id.toString, vault.id.toString)))
      val trees = Seq(protocol.contracts.vault.ergoTree, protocol.contracts.fractionalVault.ergoTree)
      packed.getOutputsToSpend.asScala.filter(o => trees.contains(o.getErgoTree)).map(_.getCreationHeight).toSet shouldBe Set(w.h + Later)
      val alone = w.run(e, later(w, e).refresh(Seq(wrapped.id.toString)))
      alone.getSignedInputs.size shouldBe 1
    }
  }

  // ═══ WRAPS ════════════════════════════════════════════════════════════════

  property("wrap: a request, a batcher's mint with the request alone, a verified receipt, and an unwrap that returns the token") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (l, e) = (lender(ctx), executor(ctx))
      val nft = ErgoId.create("dd" * 32)
      w.fund(l, erg("5"), Token(nft, 1L))
      w.fund(e, erg("1"))
      w.run(l, command(w, l, s"wrap $nft --refresh-reserve 0.02"))
      val request = w.app(e).wrapRequests().loneElement
      request.wrapped shouldBe Token(nft, 1L)
      StandardMetadata.text(request.metadata.head) shouldBe s"ZCS-Wrap-${StandardMetadata.id6(nft)}"
      w.app(e).batchScan().work.map(_.command) should contain(s"mint-wrap ${request.box.id}")
      val minted = w.run(e, w.app(e).mintWrap(request.box.id.toString))
      minted.getSignedInputs.size shouldBe 1
      val vault = w.app(l).wrapVaults().loneElement
      vault.wrapped shouldBe Token(nft, 1L)
      vault.box.value shouldBe MinBox + erg("0.02")
      w.app(l).wrapVerified(request.receiptId) shouldBe true
      w.tokens(l).get(request.receiptId) shouldBe Some(1L)
      refused(w.app(e).unwrap(request.receiptId.toString)) should include("does not hold")
      refused(w.app(e).refresh(Seq(vault.box.id.toString))) should include("can be refreshed from height")
      w.run(l, w.app(l).unwrap(request.receiptId.toString))
      w.tokens(l).get(nft) shouldBe Some(1L)
      w.tokens(l).get(request.receiptId) shouldBe None
      w.app(l).wrapVaults() shouldBe empty
    }
  }

  property("wrap: the owner cancels a request; another's receipt is not verified; a request too poor for its mint waits") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (l, e) = (lender(ctx), executor(ctx))
      val nft = ErgoId.create("dd" * 32)
      w.fund(l, erg("5"), Token(nft, 2L))
      w.run(l, w.app(l).wrap(nft.toString, Some(1L), erg("0.002"), 0L))
      val request = w.app(l).wrapRequests().loneElement
      request.wrapped shouldBe Token(nft, 1L)
      w.run(l, w.app(l).cancelWrap(request.box.id.toString))
      w.tokens(l).get(nft) shouldBe Some(2L)
      refused(w.app(l).wrap(nft.toString, Some(3L), erg("0.002"), 0L)) should include("wrap 1 to 2")
      refused(w.app(l).wrap(nft.toString, None, erg("0.001"), 0L)) should include("at least")
      w.app(l).wrapVerified(nft) shouldBe false
    }
  }

  // ═══ DUTCH EXCESS AUCTIONS ════════════════════════════════════════════════

  private val dutchAsset: ErgoId = ErgoId.create("ef" * 32)

  /**
   * A Dutch vault for `share` in `state`, its auction begun at `start`: a 7 ERG repayment, 200% falling to 150% over
   * 500 blocks, expiry 2160, Dutch threshold. `held` is its nanoERG; `assets` what it holds besides the reserve.
   */
  private def dutchVault(w: World, share: ErgoId, state: Byte, start: Long, held: Long,
                         assets: Seq[Token] = Seq(Token(collateralToken, 100L))): UTXO =
    UTXO(protocol.contracts.dutchVault, held, Token(share, 1001L) +: assets,
      Seq(bytesValue(dutchAsset.getBytes), longsValue(1000L, 1000L, erg("0.002")), ErgoValue.of(state),
        longsValue(erg("7"), 20000L, 15000L, 500L, 2160L, Long.MaxValue, start), bytesValue(Array.emptyByteArray)), Some(w.h))

  property("Dutch excess auction: a pull holding the floor opens it, buy-lot pays its falling price, claims wait for its end") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (l, e) = (lender(ctx), executor(ctx))
      w.fund(e, erg("40"))
      val Seq(s1, s2, s3) = Seq("f1", "f2", "f3").map(c => ErgoId.create(c * 32))
      w.fund(l, erg("1"), Token(s2, 250L), Token(s3, 250L))

      // The pull: a liquidated settlement of 11 ERG and a thin token, against a 10.5 ERG floor target.
      val holding = w.seed(dutchVault(w, s1, 0.toByte, 0L, erg("0.003"), Seq(Token(dutchAsset, 1L))))
      w.seed(UTXO(protocol.contracts.vault, erg("11"), Seq(Token(dutchAsset, 2L), Token(collateralToken, 100L)),
        Seq(bytesValue(dutchAsset.getBytes), ErgoValue.of(1.toByte))))
      w.run(e, w.app(e).pull(holding.id.toString))
      val pulled = w.app(e).shareVaults().find(_.shareId == s1).get
      pulled.state shouldBe Some(4.toByte)
      pulled.excess shouldBe true
      pulled.auctionStart shouldBe w.h.toLong

      // Open: 7 ERG at 200% at the start, falling to nothing over 500 blocks; claims wait for the end.
      val open = w.seed(dutchVault(w, s2, 4.toByte, w.h.toLong, erg("10.5")))
      val v = w.app(e).shareVaults().find(_.shareId == s2).get
      v.claimableFrom shouldBe w.h + 500L
      w.app(e).excessPrice(v, w.h.toLong) shouldBe Some(erg("14"))
      refused(w.app(l).claimShares(s2.toString, Some(100L))) should include("excess auction runs until")
      w.run(e, w.app(e).buyLot(open.id.toString))
      w.app(e).shareVaults().find(_.shareId == s2).get.state shouldBe Some(2.toByte)

      // Over: its price reached zero; the lot cannot be bought, and a claim takes its part in kind.
      val over = w.seed(dutchVault(w, s3, 4.toByte, w.h.toLong - 500L, erg("10.5")))
      val ended = w.app(e).shareVaults().find(_.shareId == s3).get
      w.app(e).excessPrice(ended, w.h.toLong) shouldBe None
      refused(w.app(e).buyLot(over.id.toString)) should include("reached zero")
      w.run(l, w.app(l).claimShares(s3.toString, Some(250L)))
      w.tokens(l).get(collateralToken) shouldBe Some(25L)
      w.app(l).shareVaults().find(_.shareId == s3).get.state shouldBe Some(3.toByte)
    }
  }

  // ═══ AUDIT HARDENING (FS-1, FS-3, FS-4, FS-5) ═════════════════════════════

  property("FS-1: sell warns on a large refund fee or an ERG fixed ask priced at or under its box ERG") {
    withCtx { ctx =>
      val w = new World(ctx)
      val l = lender(ctx)
      w.fund(l, erg("5"), Token(collateralToken, 10L))
      val bigFee = w.app(l).sell(collateralToken.toString, 1L, Market.Erg, FixedPrice(erg("1")), None, w.h + 500L, erg("0.05"))
      bigFee.notes.exists(_.contains("WARNING: refund fee")) shouldBe true
      bigFee.notes.exists(_.contains("not returned separately")) shouldBe true
      val tinyPrice = w.app(l).sell(collateralToken.toString, 1L, Market.Erg, FixedPrice(erg("0.002")), None, w.h + 500L, erg("0.002"))
      tinyPrice.notes.exists(_.contains("WARNING: total price")) shouldBe true
    }
  }

  property("FS-3: vault refreshes, buyLot and partial claims strip unknown trailing spam registers") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (l, e) = (lender(ctx), executor(ctx))
      w.fund(e, erg("40"))
      val spam = bytesValue(Array.fill(200)(0x55.toByte))
      val asset = ErgoId.create("bb" * 32)
      val share1 = ErgoId.create("bc" * 32)
      val share2 = ErgoId.create("bd" * 32)
      val share3 = ErgoId.create("be" * 32)
      w.fund(l, erg("2"), Token(share3, 250L))

      // BondVault with spam R6, FractionalVault with spam R7, DutchAuctionVault with spam R9: all refresh cleanly.
      val bv = w.seed(UTXO(protocol.contracts.vault, erg("0.1"), Seq(Token(asset, 2L)),
        Seq(bytesValue(asset.getBytes), ErgoValue.of(1.toByte), spam), Some(w.h)))
      val fv = w.seed(UTXO(protocol.contracts.fractionalVault, erg("0.1"), Seq(Token(share1, 101L), Token(collateralToken, 5L)),
        Seq(bytesValue(asset.getBytes), longsValue(100L, 100L, erg("0.002")), ErgoValue.of(0.toByte), spam), Some(w.h)))
      val dv = w.seed(dutchVault(w, share2, 2.toByte, 0L, erg("0.1")).withRegNum(9, spam))
      val refreshed = w.run(e, later(w, e).refresh(Seq(bv.id.toString, fv.id.toString, dv.id.toString)))
      refreshed.getOutputsToSpend.asScala.find(_.getErgoTree == protocol.contracts.vault.ergoTree).get.getRegisters.size shouldBe 2
      refreshed.getOutputsToSpend.asScala.find(_.getErgoTree == protocol.contracts.fractionalVault.ergoTree).get.getRegisters.size shouldBe 3
      refreshed.getOutputsToSpend.asScala.find(_.getErgoTree == protocol.contracts.dutchVault.ergoTree).get.getRegisters.size shouldBe 5

      // DutchAuctionVault with spam R9 strips R9 on buyLot and on partial claimShares.
      val openSpam = w.seed(dutchVault(w, share3, 4.toByte, w.h.toLong, erg("10.5")).withRegNum(9, spam))
      val bought = w.run(e, w.app(e).buyLot(openSpam.id.toString))
      bought.getOutputsToSpend.get(0).getRegisters.size shouldBe 5
      val claimed = w.run(l, w.app(l).claimShares(share3.toString, Some(100L)))
      claimed.getOutputsToSpend.get(1).getRegisters.size shouldBe 5
    }
  }

  property("FS-4 & FS-5: dynamic minValuePerByte on large DutchAuctionVault, partial claim floor, 4 KB offer cap, and 30-token cap") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l, e) = (borrower(ctx), lender(ctx), executor(ctx))
      val manyTokens = (1 to 31).map(i => Token(ErgoId.create(f"c1$i%062x"), 500L))
      w.fund(b, erg("40"), manyTokens: _*)
      w.fund(l, erg("40"), Token(SigUsd, 20000L))
      w.fund(e, erg("40"), Token(SigUsd, 20000L))
      w.oracle(500000000L, w.h)

      // createOrder refuses > 30 distinct collateral tokens.
      refused(w.app(b).createOrder(loan(collateral = manyTokens)(w))) should include("at most 30")

      // createOffer refuses custom share metadata whose post-fill continuation (+32 B R6) exceeds 4,096 bytes.
      val bigDesc = "x" * 1800
      val hugeShareTerms = ShareTerms(pullFee = erg("0.005"), mintFee = erg("0.005"), description = Some(bigDesc),
        auction = Some(AuctionTerms(12000L, 9000L, 720L, 2160L, StandardProtocol.SelectiveThreshold)))
      refused(w.app(l).createOffer(OfferSpec(Market.SigUsd, 1000L, erg("0.5"), 1000L, 1L, 262800L, erg("0.005"),
        reserve, 13000L, 30L, w.h + 500L, 1L, 0L, 0L, Some(OfferDelivery(hugeShareTerms))))) should include("4096-byte")

      // A seeded 31-token bond is refused by Dutch fractionalize and Dutch-delivering offers.
      val bigAsset = ErgoId.create("c2" * 32)
      val bigOrder = w.seed(UTXO(protocol.market(Market.Erg).order, erg("10") + erg("0.005"), manyTokens,
        Seq(longsValue(erg("6"), erg("5"), erg("6"), w.h + 1000L, w.h + 500L, erg("0.005"), 0L), tokensValue(Seq.empty),
          bytesValue(hashOf(Contract.fromAddress(b.getAddress).ergoTree)), bytesValue(protocol.market(Market.Erg).oracleRule.hashedValueBytes))))
      val bigOrderBox = bigOrder.input.asInstanceOf[org.ergoplatform.appkit.impl.InputBoxImpl].getErgoBox
      w.chain.unspent.remove(bigOrder.id.toString)
      w.chain.spent(bigOrder.id.toString) = (bigOrderBox, "d1" * 32)
      val bigBond = w.bond(Market.Erg, bigOrder.id, erg("10"), manyTokens, erg("6"),
        protocol.market(Market.Erg).oracleRule, w.h.toLong, w.h + 1000L, w.h, payload = Seq(15000L, 60L))
      w.fund(l, MinBox, Token(bigOrder.id, 1L))
      refused(w.app(l).fractionalize(bigOrder.id.toString, ShareTerms(pullFee = erg("0.005"), mintFee = erg("0.005"),
        auction = Some(AuctionTerms(12000L, 9000L, 720L, 2160L, StandardProtocol.SelectiveThreshold))))) should include("30-token limit")
      val dutchOffer = OfferSpec(Market.Erg, erg("20"), 0L, 500L, 1L, 262800L, erg("0.005"), reserve, 13000L, 30L,
        w.h + 500L, 1L, 0L, 0L, Some(OfferDelivery(ShareTerms(pullFee = erg("0.005"), mintFee = erg("0.005"),
          auction = Some(AuctionTerms(12000L, 9000L, 720L, 2160L, StandardProtocol.SelectiveThreshold))))))
      w.run(l, w.app(l).createOffer(dutchOffer))
      val offer = w.app(e).offers().loneElement
      val liveBig = protocol.asBond(bigBond).get
      w.app(e).offerRefusal(offer, liveBig, erg("5"), 1L, 1L, Some(w.app(e).oracle().get), primary = true)
        .getOrElse(fail("expected refusal")) should include("30-token limit")

      // A 15-token SigUSD DutchAuctionVault (> 2,777 B, minValuePerByte > MinBox):
      // State-4 buyLot (!saleKeepsErg), partial claim (FS-5), and refresh all satisfy FakeChain's 360 nanoERG/byte check.
      val fatTokens = (1 to 15).map(i => Token(ErgoId.create(f"c1$i%062x"), 5000000L))
      val thinNft = Token(ErgoId.create("c3" * 32), 1L)
      val s4 = ErgoId.create("c4" * 32)
      w.fund(l, erg("2"), Token(s4, 600L))
      val sigVault = w.seed(UTXO(protocol.contracts.dutchVault, erg("0.002"),
        Token(s4, 1001L) +: Token(SigUsd, 1000L) +: thinNft +: fatTokens,
        Seq(bytesValue(bigAsset.getBytes), longsValue(1000L, 1000L, erg("0.002")), ErgoValue.of(4.toByte),
          longsValue(1000L, 10000L, 8000L, 500L, 2160L, 2000000L, w.h.toLong), bytesValue(SigUsd.getBytes)), Some(w.h)))
      // State-4 buyLot sells thinNft + lotERG, keeps 15 fat tokens + SigUSD (> 2,777 B), and funds >= 360 nanoERG/byte.
      val afterSale = w.run(e, w.app(e).buyLot(sigVault.id.toString))
      afterSale.getOutputsToSpend.get(0).getValue.longValue should be > MinBox
      afterSale.getOutputsToSpend.get(0).getValue.longValue should be < erg("0.002")
      // 60% partial claim (FS-5): remaining ERG drops below MinBox and tops up to >= minValuePerByte.
      val afterClaim = w.run(l, w.app(l).claimShares(s4.toString, Some(600L)))
      afterClaim.getOutputsToSpend.get(1).getValue.longValue should be > MinBox
      // Refresh of a 15-token DutchAuctionVault holding 0.005 ERG never drops below its 360 nanoERG/byte floor.
      val oldBig = w.seed(UTXO(protocol.contracts.dutchVault, erg("0.005"),
        Token(ErgoId.create("c5" * 32), 1001L) +: Token(SigUsd, 1000L) +: fatTokens,
        Seq(bytesValue(bigAsset.getBytes), longsValue(1000L, 1000L, erg("0.002")), ErgoValue.of(2.toByte),
          longsValue(1000L, 10000L, 8000L, 500L, 2160L, 100L, 0L), bytesValue(SigUsd.getBytes)), Some(w.h)))
      val afterRef = w.run(e, later(w, e).refresh(Seq(oldBig.id.toString)))
      afterRef.getOutputsToSpend.get(0).getValue.longValue should be > MinBox
    }
  }
}
