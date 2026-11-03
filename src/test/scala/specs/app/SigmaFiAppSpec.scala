package org.lithosprotocol.sigmafi
package specs.app

import ksingh.sigmabonds.app._
import ksingh.sigmabonds.app.Protocol._
import org.ergoplatform.appkit._
import org.ergoplatform.appkit.impl.{InputBoxImpl, UnsignedTransactionImpl}
import org.ergoplatform.sdk.ErgoId
import org.ergoplatform.wallet.serialization.JsonCodecsWrapper
import org.scalatest.LoneElement
import org.scalatest.propspec.AnyPropSpec
import specs.harness.ContractSpecBase
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}

import java.math.BigInteger
import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

object SigmaFiAppSpec {
  private var cached: Option[Protocol] = None

  /** Compiled once per JVM, as BondSpecBase does: the sigma compiler mutates shared AST nodes. */
  def protocol(ctx: BlockchainContext): Protocol = synchronized {
    cached.getOrElse { val p = new Protocol(ctx); cached = Some(p); p }
  }
}

/**
 * The CLI's App, end to end on an in-memory chain. Each step finds its boxes through the node endpoints the
 * App uses, builds a transaction, and the spec signs it as the node wallet would: signing runs the
 * contracts, so every step that signs is a step the contracts accept. The outputs then join the chain, and
 * the next step has to find them there.
 */
class SigmaFiAppSpec extends AnyPropSpec with ContractSpecBase with LoneElement {

  private val collateralToken = ErgoId.create("aa" * 32)
  private val stable = ErgoId.create("cc" * 32)

  private def erg(s: String): Long = SigmaFiCli.parseErg(s)

  private def borrower(ctx: BlockchainContext) = proverWith(ctx, BigInteger.valueOf(1001L))
  private def lender(ctx: BlockchainContext) = proverWith(ctx, BigInteger.valueOf(2002L))
  private def executor(ctx: BlockchainContext) = proverWith(ctx, BigInteger.valueOf(3003L))

  /** One chain per property, seen by each party through a node holding only that party's wallet. */
  private class World(val ctx: BlockchainContext) {
    val chain = new FakeChain
    val protocol: Protocol = SigmaFiAppSpec.protocol(ctx)
    private var seeds = 0

    def seed(utxo: UTXO): InputUTXO = {
      seeds += 1
      val box = utxo.toInput(ctx, ErgoId.create(f"$seeds%064x"), 0.toShort)
      chain.add(box.input.asInstanceOf[InputBoxImpl].getErgoBox)
      box
    }

    def fund(p: ErgoProver, value: Long, tokens: Token*): InputUTXO = seed(UTXO(Contract.fromAddress(p.getAddress), value, tokens))

    def app(p: ErgoProver, fee: Long = Parameters.MinFee): SigmaFiApp =
      new SigmaFiApp(ctx, new FakeNode(chain, p), AppSettings(fee = fee), _ => (), Some(protocol))

    /** Sign as the node wallet would, which runs every contract, then put the transaction on the chain. */
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

    def balance(p: ErgoProver): (Long, Map[ErgoId, Long]) = {
      val boxes = app(p).walletBoxes
      (boxes.map(_.value).sum, boxes.flatMap(_.tokens).groupBy(_.id).map { case (id, ts) => id -> ts.map(_.amount).sum })
    }
  }

  private def ergLoan(collateral: Seq[Token] = Seq(Token(collateralToken, 100L))): Loan =
    Loan(erg("5"), None, erg("6"), erg("0.002"), erg("10"), collateral, "MaturityLiquidation")

  private def tokenLoan: Loan =
    Loan(MinBox, Some(Token(stable, 600L)), 700L, erg("0.002"), erg("10"), Seq(Token(collateralToken, 100L)),
      "MaturityLiquidation")

  private def refused(f: => Any): String = Try(f) match {
    case Failure(e: CliError) => e.message
    case Failure(e) => fail(s"expected a refusal, got ${e.getMessage}", e)
    case Success(_) => fail("expected a refusal, but it was accepted")
  }

  // ═══ BORROWER ORDERS ══════════════════════════════════════════════════════

  property("order: created, filled, repaid, and the lender's unit claims the repayment") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l) = (borrower(ctx), lender(ctx))
      w.fund(b, erg("20"), Token(collateralToken, 100L))
      w.fund(l, erg("20"))

      w.run(b, w.app(b).createOrder(ergLoan(), FixedMaturity(ctx.getHeight + 1000)))
      val order = w.app(l).orders().loneElement
      order.schedule shouldBe FixedMaturity(ctx.getHeight + 1000)
      order.box.tokens shouldBe Seq(Token(collateralToken, 100L))
      treesReg(order.box, 4).map(hashOf).map(_.toSeq) shouldBe Seq(order.borrowerHash.toSeq)

      w.run(l, w.app(l).fillOrder(order.box.id.toString, "SigmaFi Bond", "spec"))
      w.app(l).orders() shouldBe empty
      val bond = w.app(b).bonds().loneElement
      bond.asset shouldBe order.box.id
      w.app(b).verifyBond(bond) shouldBe Right(())
      bond.maturity shouldBe ctx.getHeight + 1000
      bond.collateral shouldBe Seq(Token(collateralToken, 100L))
      w.app(l).walletHolds(bond.asset) shouldBe true
      w.balance(b)._1 shouldBe erg("20") - erg("10") - Parameters.MinFee + erg("5")

      w.run(b, w.app(b).repay(bond.box.id.toString))
      w.app(b).bonds() shouldBe empty
      w.balance(b)._2.get(collateralToken) shouldBe Some(100L)
      w.app(l).vaultBoxes().keySet shouldBe Set(bond.asset)

      val before = w.balance(l)._1
      w.run(l, w.app(l).claim(bond.asset.toString))
      w.app(l).vaultBoxes() shouldBe empty
      w.app(l).walletHolds(bond.asset) shouldBe false
      w.balance(l)._1 shouldBe before + erg("6") - Parameters.MinFee
    }
  }

  property("order: an on-close token loan matures its duration after the fill, and is repaid in the token") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l) = (borrower(ctx), lender(ctx))
      w.fund(b, erg("20"), Token(collateralToken, 100L), Token(stable, 100L))
      w.fund(l, erg("5"), Token(stable, 600L))

      w.run(b, w.app(b).createOrder(tokenLoan, OnClose(720)))
      val order = w.app(l).orders().loneElement
      order.schedule shouldBe OnClose(720)
      order.principalTokens shouldBe Seq(Token(stable, 600L))

      w.run(l, w.app(l).fillOrder(order.box.id.toString, "SigmaFi Bond", "spec"))
      val bond = w.app(b).bonds().loneElement
      bond.repayToken shouldBe Some(stable)
      bond.maturity shouldBe ctx.getHeight + 720
      w.balance(b)._2.get(stable) shouldBe Some(700L)

      w.run(b, w.app(b).repay(bond.box.id.toString))
      w.balance(b)._2.get(stable) shouldBe None
      w.run(l, w.app(l).claim(bond.asset.toString))
      w.balance(l)._2.get(stable) shouldBe Some(700L)
    }
  }

  property("order: a borrower is found from the creating transaction when the order carries no hint") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l) = (borrower(ctx), lender(ctx))
      val funds = w.fund(b, erg("20"), Token(collateralToken, 100L))
      w.fund(l, erg("20"))

      // Another client's order: the same template and terms, without the R4 hint
      val loan = ergLoan()
      val tree = w.protocol.orderTemplate.withTerms(ksingh.sigmabonds.client.ScriptGenerator.OrderTerms(
        w.protocol.ergBond.hashedPropBytes, hashOf(b.getAddress.toErgoContract.getErgoTree), loan.principalErg,
        Seq.empty, loan.repayment, loan.batchingFee, ctx.getHeight + 1000,
        w.protocol.liquidation("MaturityLiquidation").hash))
      val uTx = TxBuilder(ctx).setInputs(funds)
        .setOutputs(UTXO(Contract(tree), loan.collateralErg, loan.collateralTokens))
        .buildTx(Parameters.MinFee, b.getAddress)
      w.chain.apply(uTx, b.sign(uTx))

      val order = w.app(l).orders().loneElement
      treesReg(order.box, 4) shouldBe empty
      w.run(l, w.app(l).fillOrder(order.box.id.toString, "SigmaFi Bond", "spec"))
      w.balance(b)._1 shouldBe erg("20") - erg("10") - Parameters.MinFee + erg("5")
    }
  }

  property("order: the borrower cancels it and takes the collateral back") {
    withCtx { ctx =>
      val w = new World(ctx)
      val b = borrower(ctx)
      w.fund(b, erg("20"), Token(collateralToken, 100L))
      w.run(b, w.app(b).createOrder(ergLoan(), FixedMaturity(ctx.getHeight + 1000)))
      val order = w.app(b).orders().loneElement
      w.run(b, w.app(b).cancelOrder(order.box.id.toString))
      w.app(b).orders() shouldBe empty
      w.balance(b) shouldBe ((erg("20") - 2 * Parameters.MinFee, Map(collateralToken -> 100L)))
    }
  }

  // ═══ LENDER OFFERS ════════════════════════════════════════════════════════

  property("offer: created, closed by a borrower, which mints the lender's asset, repaid, and claimed") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l) = (borrower(ctx), lender(ctx))
      w.fund(l, erg("20"))
      w.fund(b, erg("20"), Token(collateralToken, 100L))

      val maturity = ctx.getHeight + 1000
      w.run(l, w.app(l).createOffer(ergLoan(), maturity, "SigmaFi Bond", "spec"))
      val offer = w.app(b).offers().loneElement
      offer.terms.maturity shouldBe maturity
      offer.principalErg shouldBe erg("5")
      offer.name shouldBe "SigmaFi Bond"
      offer.box.tokens shouldBe empty
      treesReg(offer.box, 4).map(hashOf).map(_.toSeq) shouldBe Seq(offer.terms.lenderHash.toSeq)

      w.run(b, w.app(b).closeOffer(offer.box.id.toString))
      w.app(b).offers() shouldBe empty
      w.app(l).walletHolds(offer.asset) shouldBe true
      val bond = w.app(b).bonds().loneElement
      bond.asset shouldBe offer.asset
      bond.collateral shouldBe Seq(Token(collateralToken, 100L))
      w.app(b).verifyBond(bond) shouldBe Right(())
      w.balance(b)._1 shouldBe erg("20") - erg("10") - Parameters.MinFee + erg("5")

      w.run(b, w.app(b).repay(bond.box.id.toString))
      w.balance(b)._2.get(collateralToken) shouldBe Some(100L)
      w.run(l, w.app(l).claim(offer.asset.toString))
      w.app(l).walletHolds(offer.asset) shouldBe false
      w.balance(l)._1 shouldBe erg("20") - erg("5") - erg("0.001") - Parameters.MinFee +
        erg("0.001") + erg("6") - Parameters.MinFee
    }
  }

  property("offer: a token loan's lender is found from the creating transaction when the offer carries no hint") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l) = (borrower(ctx), lender(ctx))
      val funds = w.fund(l, erg("5"), Token(stable, 600L))
      w.fund(b, erg("20"), Token(collateralToken, 100L), Token(stable, 100L))

      // Another client's offer: the same template and terms, without the R4 hint
      val loan = tokenLoan
      val tree = w.protocol.offerTemplate.withTerms(ksingh.sigmabonds.client.ScriptGenerator.OfferTerms(
        hashOf(l.getAddress.toErgoContract.getErgoTree), "n".getBytes("UTF-8"), "d".getBytes("UTF-8"),
        w.protocol.tokenBond(stable).hashedPropBytes, loan.repayment, loan.batchingFee, ctx.getHeight + 1000,
        w.protocol.liquidation("MaturityLiquidation").hash,
        loan.collateralErg, loan.collateralTokens.map(t => t.id.getBytes -> t.amount)))
      val uTx = TxBuilder(ctx).setInputs(funds)
        .setOutputs(UTXO(Contract(tree), 2 * MinBox, Seq(Token(stable, 600L))))
        .buildTx(Parameters.MinFee, l.getAddress)
      w.chain.apply(uTx, l.sign(uTx))

      val offer = w.app(b).offers().loneElement
      treesReg(offer.box, 4) shouldBe empty
      w.run(b, w.app(b).closeOffer(offer.box.id.toString))
      w.app(l).walletHolds(offer.asset) shouldBe true
      val bond = w.app(b).bonds().loneElement
      bond.repayToken shouldBe Some(stable)
      w.balance(b)._2.get(stable) shouldBe Some(700L)
    }
  }

  property("offer: the lender cancels it and takes the principal back") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l) = (borrower(ctx), lender(ctx))
      w.fund(l, erg("20"))
      w.fund(b, erg("1"))

      w.run(l, w.app(l).createOffer(ergLoan(), ctx.getHeight + 1000, "n", "d"))
      val offer = w.app(l).offers().loneElement
      refused(w.app(b).cancelOffer(offer.box.id.toString)) should include("only its lender can cancel")
      w.run(l, w.app(l).cancelOffer(offer.box.id.toString))
      w.app(l).offers() shouldBe empty
      w.balance(l)._1 shouldBe erg("20") - 2 * Parameters.MinFee
    }
  }

  // ═══ BONDS ════════════════════════════════════════════════════════════════

  /**
   * A bond seeded straight onto the chain, with a unit of its asset in the lender's wallet. No order or offer
   * minted the asset, so it never verifies, but the contracts settle it like any other.
   */
  private def liveBond(w: World, asset: ErgoId, maturity: Int, fee: Long, b: ErgoProver, l: ErgoProver): BondInfo = {
    val p = w.protocol
    w.fund(l, erg("0.01"), Token(asset, 1L))
    val box = w.seed(UTXO(p.ergBond, erg("10"), Seq(Token(collateralToken, 50L)), Seq(
      bytesValue(asset.getBytes), bytesValue(hashOf(b.getAddress.toErgoContract.getErgoTree)),
      longsValue(erg("6"), fee), intValue(maturity), bytesValue(p.liquidation("MaturityLiquidation").hash))))
    p.asBond(box).get
  }

  property("bonds: a bond whose asset no order or offer minted is listed, but not verified") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l) = (borrower(ctx), lender(ctx))
      val bond = liveBond(w, ErgoId.create("a7" * 32), ctx.getHeight + 100, erg("0.003"), b, l)
      w.app(b).bonds().loneElement.box.id shouldBe bond.box.id
      w.app(b).verifyBond(bond).left.toOption.get should include("genesis box")
    }
  }

  property("bonds: an executor liquidates matured bonds in one batch and keeps the batching fees") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l, e) = (borrower(ctx), lender(ctx), executor(ctx))
      val (a1, a2) = (ErgoId.create("a1" * 32), ErgoId.create("a2" * 32))
      val bonds = Seq(liveBond(w, a1, ctx.getHeight - 10, erg("0.003"), b, l),
        liveBond(w, a2, ctx.getHeight - 10, erg("0.003"), b, l))

      w.app(e).bonds().map(_.box.id).toSet shouldBe bonds.map(_.box.id).toSet
      val prepared = w.app(e).liquidate(bonds.map(_.box.id.toString))
      prepared.tx.getInputs.asScala.map(_.getId) shouldBe bonds.map(_.box.id)
      w.run(e, prepared)
      w.balance(e)._1 shouldBe erg("0.006") - Parameters.MinFee

      w.app(l).vaultBoxes().keySet shouldBe Set(a1, a2)
      w.run(l, w.app(l).claim(a1.toString))
      w.balance(l)._2.get(collateralToken) shouldBe Some(50L)
    }
  }

  property("bonds: a batching fee below the miner fee is topped up from the executor's own wallet") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l, e) = (borrower(ctx), lender(ctx), executor(ctx))
      w.fund(e, erg("1"))
      val bond = liveBond(w, ErgoId.create("a3" * 32), ctx.getHeight - 10, 0L, b, l)
      val prepared = w.app(e).liquidate(Seq(bond.box.id.toString))
      prepared.tx.getInputs.asScala.map(_.getId).head shouldBe bond.box.id
      prepared.tx.getInputs.size shouldBe 2
      w.run(e, prepared)
      w.balance(e)._1 shouldBe erg("1") - Parameters.MinFee
    }
  }

  property("bonds: repayment is refused at maturity, and liquidation before it") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l, e) = (borrower(ctx), lender(ctx), executor(ctx))
      w.fund(b, erg("20"))
      val matured = liveBond(w, ErgoId.create("a4" * 32), ctx.getHeight, erg("0.003"), b, l)
      val live = liveBond(w, ErgoId.create("a5" * 32), ctx.getHeight + 100, erg("0.003"), b, l)
      refused(w.app(b).repay(matured.box.id.toString)) should include("repayment is closed")
      refused(w.app(e).liquidate(Seq(live.box.id.toString))) should include("can be liquidated from then on")
    }
  }

  // ═══ THE NODE'S SIGNING FORMAT ════════════════════════════════════════════

  property("node format: the unsigned transaction survives the node's decoder, context variables included") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l, e) = (borrower(ctx), lender(ctx), executor(ctx))
      val bond = liveBond(w, ErgoId.create("a6" * 32), ctx.getHeight - 10, erg("0.003"), b, l)
      val tx = w.app(e).liquidate(Seq(bond.box.id.toString)).tx
      val json = NodeJson.unsignedTx(tx)
      val decoded = NodeJson.v6(JsonCodecsWrapper.unsignedErgoLikeTransactionDecoder.decodeJson(json)).toOption.get
      decoded.id shouldBe tx.getId
      decoded.inputs.head.extension.values.keySet shouldBe Set(0.toByte)
      decoded.outputCandidates.map(_.bytesWithNoRef.toSeq) shouldBe
        tx.asInstanceOf[UnsignedTransactionImpl].getTx.outputCandidates.map(_.bytesWithNoRef.toSeq)
    }
  }
}
