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
    Loan(erg("5"), None, erg("6"), erg("10"), collateral, "MaturityLiquidation")

  private def tokenLoan: Loan =
    Loan(MinBox, Some(Token(stable, 600L)), 700L, erg("10"), Seq(Token(collateralToken, 100L)),
      "MaturityLiquidation")

  private def refused(f: => Any): String = Try(f) match {
    case Failure(e: CliError) => e.message
    case Failure(e) => fail(s"expected a refusal, got ${e.getMessage}", e)
    case Success(_) => fail("expected a refusal, but it was accepted")
  }

  /** Read the actual issuing output, including metadata that has gone through transaction signing. */
  private def mintedMetadata(tx: SignedTransaction, asset: ErgoId): io.circe.Json = {
    val unit = tx.getOutputsToSpend.get(0)
    def text(r: Int): String =
      new String(unit.getRegisters.get(r).getValue.asInstanceOf[sigma.Coll[Byte]].toArray, "UTF-8")
    unit.getTokens.get(0).getId shouldBe asset
    text(0) shouldBe s"SigmaFi-ZC-Bond-${asset.toString.take(6)}"
    text(2) shouldBe "0"
    io.circe.parser.parse(text(1)).fold(e => fail(s"invalid EIP-4 JSON: $e"), identity)
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

      val filled = w.run(l, w.app(l).fillOrder(order.box.id.toString, erg("0.007")))
      w.app(l).orders() shouldBe empty
      val bond = w.app(b).bonds().loneElement
      bond.asset shouldBe order.box.id
      bond.maturity shouldBe ctx.getHeight + 1000
      bond.collateral shouldBe Seq(Token(collateralToken, 100L))
      bond.principalErg shouldBe order.principalErg
      bond.principalTokens shouldBe order.principalTokens
      bond.batchingFee shouldBe erg("0.007")
      bond.box.value shouldBe order.box.value + bond.batchingFee
      bond.collateralErg shouldBe order.box.value
      w.balance(l)._1 shouldBe erg("20") - erg("5") - bond.batchingFee - Parameters.MinFee
      val metadata = mintedMetadata(filled, bond.asset).hcursor
      metadata.get[String]("type").toOption shouldBe Some("SigmaFi ZC-Bond")
      metadata.get[String]("bondAsset").toOption shouldBe Some(bond.asset.toString)
      metadata.get[String]("borrowerHash").toOption shouldBe Some(scorex.util.encode.Base16.encode(order.borrowerHash))
      metadata.downField("principal").get[Long]("nanoErgs").toOption shouldBe Some(erg("5"))
      metadata.downField("principal").get[Vector[io.circe.Json]]("tokens").toOption shouldBe Some(Vector.empty)
      metadata.get[Long]("repayment").toOption shouldBe Some(erg("6"))
      metadata.get[String]("repayToken").toOption shouldBe Some("ERG")
      metadata.get[Long]("maturity").toOption shouldBe Some(bond.maturity)
      metadata.downField("collateral").get[Long]("nanoErgs").toOption shouldBe Some(erg("10"))
      metadata.downField("collateral").downField("tokens").downArray.get[String]("id").toOption shouldBe
        Some(collateralToken.toString)
      metadata.downField("collateral").downField("tokens").downArray.get[Long]("amount").toOption shouldBe Some(100L)
      metadata.get[Long]("batchingFee").toOption shouldBe Some(bond.batchingFee)
      metadata.get[String]("liquidation").toOption shouldBe Some("MaturityLiquidation")
      metadata.get[String]("liquidationHash").toOption shouldBe Some(scorex.util.encode.Base16.encode(bond.liqScriptHash))
      metadata.get[Int]("height").toOption shouldBe Some(ctx.getHeight)
      metadata.downField("durationBlocks").succeeded shouldBe false
      w.app(l).walletHolds(bond.asset) shouldBe true
      w.balance(b)._1 shouldBe erg("20") - erg("10") - Parameters.MinFee + erg("5")

      val beforeRepayment = w.balance(b)._1
      val repaid = w.run(b, w.app(b).repay(bond.box.id.toString))
      repaid.getOutputsToSpend.get(0).getValue shouldBe bond.repayment + bond.batchingFee
      repaid.getOutputsToSpend.get(0).getRegisters.get(2).getValue shouldBe 0.toByte
      repaid.getOutputsToSpend.get(1).getValue shouldBe order.box.value
      w.balance(b)._1 shouldBe beforeRepayment + order.box.value - bond.repayment - Parameters.MinFee
      w.app(b).bonds() shouldBe empty
      w.balance(b)._2.get(collateralToken) shouldBe Some(100L)
      w.app(l).vaultBoxes().keySet shouldBe Set(bond.asset)

      val before = w.balance(l)._1
      w.run(l, w.app(l).claim(bond.asset.toString))
      w.app(l).vaultBoxes() shouldBe empty
      w.app(l).walletHolds(bond.asset) shouldBe false
      w.balance(l)._1 shouldBe before + erg("6") + bond.batchingFee - Parameters.MinFee
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

      val filled = w.run(l, w.app(l).fillOrder(order.box.id.toString))
      val bond = w.app(b).bonds().loneElement
      bond.repayToken shouldBe Some(stable)
      bond.principalErg shouldBe MinBox
      bond.principalTokens shouldBe Seq(Token(stable, 600L))
      bond.maturity shouldBe ctx.getHeight + 720
      val metadata = mintedMetadata(filled, bond.asset).hcursor
      metadata.downField("principal").get[Long]("nanoErgs").toOption shouldBe Some(MinBox)
      metadata.downField("principal").downField("tokens").downArray.get[String]("id").toOption shouldBe Some(stable.toString)
      metadata.downField("principal").downField("tokens").downArray.get[Long]("amount").toOption shouldBe Some(600L)
      metadata.get[Long]("repayment").toOption shouldBe Some(700L)
      metadata.get[String]("repayToken").toOption shouldBe Some(stable.toString)
      metadata.get[Long]("maturity").toOption shouldBe Some(bond.maturity)
      metadata.get[Int]("durationBlocks").toOption shouldBe Some(720)
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
        Seq.empty, loan.repayment, ctx.getHeight + 1000,
        w.protocol.liquidation("MaturityLiquidation").hash))
      val uTx = TxBuilder(ctx).setInputs(funds)
        .setOutputs(UTXO(Contract(tree), loan.collateralErg, loan.collateralTokens))
        .buildTx(Parameters.MinFee, b.getAddress)
      w.chain.apply(uTx, b.sign(uTx))

      val order = w.app(l).orders().loneElement
      treesReg(order.box, 4) shouldBe empty
      w.run(l, w.app(l).fillOrder(order.box.id.toString))
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

  property("order: metadata preserves token amounts above double precision") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l) = (borrower(ctx), lender(ctx))
      val principal = 9007199254740993L
      val repayment = principal + 100L
      val loan = tokenLoan.copy(principalToken = Some(Token(stable, principal)), repayment = repayment)
      w.fund(b, erg("20"), Token(collateralToken, 100L))
      w.fund(l, erg("5"), Token(stable, principal))
      w.run(b, w.app(b).createOrder(loan, FixedMaturity(ctx.getHeight + 1000)))
      val order = w.app(l).orders().loneElement
      val metadata = mintedMetadata(w.run(l, w.app(l).fillOrder(order.box.id.toString)), order.box.id).hcursor
      metadata.downField("principal").downField("tokens").downArray.get[Long]("amount").toOption shouldBe Some(principal)
      metadata.get[Long]("repayment").toOption shouldBe Some(repayment)
    }
  }

  property("fill: rejects negative reserves and overflow; Long maturity survives reading and metadata") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l) = (borrower(ctx), lender(ctx))
      w.fund(b, erg("20"), Token(collateralToken, 100L))
      w.fund(l, erg("20"))
      w.run(b, w.app(b).createOrder(ergLoan(), FixedMaturity(Long.MaxValue)))
      val order = w.app(l).orders().loneElement
      refused(w.app(l).fillOrder(order.box.id.toString, -1L)) should include("cannot be negative")
      refused(w.app(l).fillOrder(order.box.id.toString, Long.MaxValue)) should include("overflows")
      val filled = w.run(l, w.app(l).fillOrder(order.box.id.toString, 0L))
      val bond = w.app(b).bonds().loneElement
      bond.maturity shouldBe Long.MaxValue
      bond.batchingFee shouldBe 0L
      mintedMetadata(filled, bond.asset).hcursor.get[Long]("maturity").toOption shouldBe Some(Long.MaxValue)
      w.run(b, w.app(b).repay(bond.box.id.toString))
    }
  }

  property("fill: on-close durations above Int.MaxValue retain their full Long maturity") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l) = (borrower(ctx), lender(ctx))
      val duration = Int.MaxValue.toLong + 1000L
      w.fund(b, erg("20"), Token(collateralToken, 100L))
      w.fund(l, erg("20"))
      w.run(b, w.app(b).createOrder(ergLoan(), OnClose(duration)))
      val order = w.app(l).orders().loneElement
      order.schedule shouldBe OnClose(duration)
      w.run(l, w.app(l).fillOrder(order.box.id.toString))
      w.app(b).bonds().loneElement.maturity shouldBe ctx.getHeight.toLong + duration
    }
  }

  property("fill: on-close maturity addition rejects Long overflow") {
    withCtx { ctx =>
      val w = new World(ctx)
      val (b, l) = (borrower(ctx), lender(ctx))
      w.fund(b, erg("20"), Token(collateralToken, 100L))
      w.fund(l, erg("20"))
      w.run(b, w.app(b).createOrder(ergLoan(), OnClose(Long.MaxValue)))
      val order = w.app(l).orders().loneElement
      refused(w.app(l).fillOrder(order.box.id.toString)) should include("overflows")
    }
  }

  // ═══ BONDS ════════════════════════════════════════════════════════════════

  /** A bond made by some earlier fill, with the lender's unit in the lender's wallet. */
  private def liveBond(w: World, asset: ErgoId, maturity: Int, fee: Long, b: ErgoProver, l: ErgoProver): BondInfo = {
    val p = w.protocol
    w.fund(l, erg("0.01"), Token(asset, 1L))
    val box = w.seed(UTXO(p.ergBond, erg("10") + fee, Seq(Token(asset, 1L), Token(collateralToken, 50L)), Seq(
      bytesValue(Array.fill(32)(9.toByte)), bytesValue(hashOf(b.getAddress.toErgoContract.getErgoTree)),
      longsValue(erg("6"), fee, erg("5"), maturity.toLong), tokensValue(Seq.empty), bytesValue(p.liquidation("MaturityLiquidation").hash))))
    p.asBond(box).get
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
      val liquidated = w.run(e, prepared)
      liquidated.getOutputsToSpend.get(0).getRegisters.get(2).getValue shouldBe 1.toByte
      liquidated.getOutputsToSpend.get(1).getRegisters.get(2).getValue shouldBe 1.toByte
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
      decoded.inputs.head.extension.values.keySet shouldBe Set(0.toByte, 1.toByte)
      decoded.outputCandidates.map(_.bytesWithNoRef.toSeq) shouldBe
        tx.asInstanceOf[UnsignedTransactionImpl].getTx.outputCandidates.map(_.bytesWithNoRef.toSeq)
    }
  }
}
