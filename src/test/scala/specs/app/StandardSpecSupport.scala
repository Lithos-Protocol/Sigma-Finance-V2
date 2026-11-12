package org.lithosprotocol.sigmafi
package specs.app

import ksingh.sigmabonds.app.Protocol._
import ksingh.sigmabonds.app._
import ksingh.sigmabonds.standard._
import org.ergoplatform.appkit._
import org.ergoplatform.appkit.impl.InputBoxImpl
import org.ergoplatform.sdk.ErgoId
import org.scalatest.Assertions
import specs.harness.ContractSpecBase
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import java.math.BigInteger
import scala.util.{Failure, Success, Try}

/**
 * The in-memory world the ZC Standard app specs run in. Each step finds its boxes through the node
 * endpoints the app uses and builds a transaction from the pinned trees; `run` signs it as the node wallet
 * would, which runs every contract, and adds the outputs to the chain for the next step to find.
 *
 * The mocked context's height is fixed, so steps that need time to pass start from boxes seeded with
 * earlier heights. Offline signing runs scripts at the context's own height h, where a node includes a
 * transaction at h + 1 at the earliest; the app plans for h + 1.
 */
trait StandardSpecSupport extends ContractSpecBase with Assertions {

  protected val collateralToken: ErgoId = ErgoId.create("aa" * 32)
  protected val SigUsd: ErgoId = Market.SigUsdId
  protected val protocol = new StandardProtocol()

  protected def erg(s: String): Long = SigmaFiCli.parseErg(s)
  protected val reserve: Long = erg("0.05")

  protected def borrower(ctx: BlockchainContext): ErgoProver = proverWith(ctx, BigInteger.valueOf(1001L))
  protected def lender(ctx: BlockchainContext): ErgoProver = proverWith(ctx, BigInteger.valueOf(2002L))
  protected def executor(ctx: BlockchainContext): ErgoProver = proverWith(ctx, BigInteger.valueOf(3003L))
  protected def other(ctx: BlockchainContext): ErgoProver = proverWith(ctx, BigInteger.valueOf(4004L))

  protected class World(val ctx: BlockchainContext) {
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

    /**
     * Seed `utxo` as if `funder` had created it, with a recorded transaction spending one of the funder's
     * boxes: the app finds an owner's or seller's script through the transaction that created a box.
     */
    def seedFrom(funder: ErgoProver, utxo: UTXO): InputUTXO = {
      val paid = fund(funder, MinBox).input.asInstanceOf[InputBoxImpl].getErgoBox
      seeds += 1
      val txId = f"$seeds%064x"
      val box = utxo.toInput(ctx, ErgoId.create(txId), 0.toShort)
      val made = box.input.asInstanceOf[InputBoxImpl].getErgoBox
      val paidId = scorex.util.encode.Base16.encode(paid.id)
      chain.unspent.remove(paidId)
      chain.spent(paidId) = (paid, txId)
      chain.add(made)
      chain.txs(txId) = (Seq(paid), Seq(made))
      box
    }

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

    /** An order from the borrower, filled directly by `by`: returns the live bond. */
    def liveBond(l: StdLoan, by: ErgoProver): StdBond = {
      val b = borrower(ctx)
      run(b, app(b).createOrder(l))
      val order = app(by).orders().find(_.box.tokens == l.collateralTokens).get
      run(by, app(by).fillOrder(order.box.id.toString, reserve))
      app(by).bonds().find(_.asset == order.box.id).get
    }
  }

  protected def loan(m: Market = Market.Erg, principal: Long = erg("5"), repayment: Long = erg("6"),
                     collateralErg: Long = erg("10"), collateral: Seq[Token] = Seq(Token(collateralToken, 100L)),
                     rule: RuleChoice = MaturityChoice, maturityIn: Int = 1000)(w: World): StdLoan =
    StdLoan(m, principal, repayment, collateralErg, collateral, FixedMaturity(w.h + maturityIn), rule,
      expiry = w.h + 500L, expiryFee = erg("0.005"))

  protected def refused(f: => Any): String = Try(f) match {
    case Failure(e: CliError) => e.message
    case Failure(e) => fail(s"expected a refusal, got ${e.getMessage}", e)
    case Success(_) => fail("expected a refusal, but it was accepted")
  }
}
