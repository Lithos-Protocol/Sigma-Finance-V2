package org.lithosprotocol.sigmafi
package specs.harness

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.matchers.should.Matchers
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}

import java.math.BigInteger
import java.util.logging.{Level, Logger => JLogger}
import java.util.{List => JList}
import scala.collection.JavaConverters._
import scala.io.Source
import scala.util.{Failure, Success, Try}

object ContractSpecBase {

  /**
   * appkit's FileMockedErgoClient spins up a MockWebServer per `execute`, and each one logs two
   * request lines plus a shutdown notice at INFO. Across a full run that buries the results.
   */
  private lazy val quieted: Unit = {
    Seq("okhttp3.mockwebserver.MockWebServer", "okhttp3.mockwebserver")
      .foreach(name => JLogger.getLogger(name).setLevel(Level.OFF))
  }

  def silenceMockWebServer(): Unit = quieted

  /**
   * Opt-in cost log. With the environment variable SIGMAFI_COST set to a file path, every accepted
   * transaction appends one tab-separated line: appkit's cost estimate from signing, the scripts of
   * its inputs in order, its data-input and output counts, and its signed size in bytes. Run it with
   * `SIGMAFI_COST=costs.tsv sbt test`. Signing also enforces the mocked node's maxBlockCost of
   * 1000000, which equals the reference node's default per-transaction limit (maxTransactionCost),
   * so every accepted transaction is already under it.
   */
  private val costLog: Option[java.io.File] = sys.env.get("SIGMAFI_COST").map(new java.io.File(_))
  private val scriptNames = scala.collection.concurrent.TrieMap.empty[String, String]

  /** Names a script in the cost log. Unnamed inputs show as `wallet` (a public key) or `other`. */
  def nameScript(contract: Contract, name: String): Unit = scriptNames.putIfAbsent(contract.ergoTreeHex, name)

  def logCost(tx: UnsignedTransaction, signed: SignedTransaction): Unit = costLog.foreach { file =>
    val inputs = tx.getInputs.asScala.map { box =>
      val tree = box.getErgoTree
      scriptNames.getOrElse(org.bouncycastle.util.encoders.Hex.toHexString(tree.bytes),
        if (tree.toSigmaBooleanOpt.isDefined) "wallet" else "other")
    }
    val line = s"${signed.getCost}\t${inputs.mkString(" + ")}\t${tx.getDataInputs.size} data, ${tx.getOutputs.size} out" +
      s"\t${signed.toBytes.length}\n"
    synchronized {
      val out = new java.io.FileWriter(file, true)
      try out.write(line) finally out.close()
    }
  }
}

/**
 * Offline harness shared by every contract spec. Ported from the Lithos client's
 * `contracts.specs.harness.ContractSpecBase` by way of c-bonds; the AVL-tree helpers are dropped
 * because no SigmaFi contract uses an authenticated dictionary yet.
 *
 * There is no mockchain and no interpreter to call directly. The whole method is: build boxes as
 * `UTXO`, turn the one under test into an input, attach context vars, assemble with `TxBuilder`,
 * and call `prover.sign`. Signing is what runs the interpreter, so a positive test is "sign
 * succeeds" and a negative is "sign throws".
 *
 * Contracts compile at ErgoTree v3, which needs the `_v6` node fixtures. The plain ones report
 * blockVersion 3 and reject every 6.0 feature.
 */
trait ContractSpecBase extends Matchers {

  ContractSpecBase.silenceMockWebServer()

  protected val dummyTxId: String =
    "f9e5ce5aa0d95f5d54a7bc89c46730d9662397067250aa18a0039631c0f5b809"

  protected val boxValue: Long = 60L * Parameters.OneErg
  protected val fundingValue: Long = Parameters.OneErg

  private def loadResponse(name: String): String = {
    val src = Source.fromResource(s"mockwebserver/node_responses/$name")
    try src.mkString finally src.close()
  }

  protected def withCtx[A](f: BlockchainContext => A): A = {
    val responses: JList[String] = Seq(
      loadResponse("response_NodeInfo_v6.json"),
      loadResponse("response_LastHeaders_v6.json")
    ).asJava
    new FileMockedErgoClient(responses, Seq.empty[String].asJava, true).execute(ctx => f(ctx))
  }

  // ─── provers and identities ───────────────────────────────────────────────

  protected def proverWith(ctx: BlockchainContext, secret: BigInteger): ErgoProver =
    ctx.newProverBuilder().withDLogSecret(secret).build()

  protected def contractOf(prover: ErgoProver): Contract = Contract.fromAddress(prover.getAddress)

  // ─── boxes and transactions ───────────────────────────────────────────────

  protected def inputAt(box: UTXO, ctx: BlockchainContext, index: Int, txId: String = dummyTxId): InputUTXO =
    box.toInput(ctx, ErgoId.create(txId), index.toShort)

  protected def fundingInput(ctx: BlockchainContext, prover: ErgoProver, value: Long = fundingValue): InputUTXO =
    UTXO(contractOf(prover), value).toInput(ctx, ErgoId.create(dummyTxId), 1.toShort)

  protected def build(ctx: BlockchainContext,
                      inputs: Seq[InputUTXO],
                      outputs: Seq[UTXO],
                      changeTo: Address,
                      dataInputs: Seq[InputUTXO] = Seq.empty[InputUTXO],
                      burn: Seq[Token] = Seq.empty[Token],
                      fee: Long = Parameters.MinFee): UnsignedTransaction =
    TxBuilder(ctx)
      .setInputs(inputs: _*)
      .setDataInputs(dataInputs: _*)
      .setOutputs(outputs: _*)
      .buildTx(fee, changeTo, burn)

  /**
   * The same build with HEIGHT pinned. A preHeader is the only way to move HEIGHT off the mocked
   * context's own height, so any condition comparing against an exact height needs one. Every
   * bond maturity and option expiry is such a condition.
   */
  protected def buildAt(ctx: BlockchainContext,
                        height: Int,
                        inputs: Seq[InputUTXO],
                        outputs: Seq[UTXO],
                        changeTo: Address,
                        dataInputs: Seq[InputUTXO] = Seq.empty[InputUTXO],
                        burn: Seq[Token] = Seq.empty[Token],
                        fee: Long = Parameters.MinFee): UnsignedTransaction =
    TxBuilder(ctx)
      .setInputs(inputs: _*)
      .setDataInputs(dataInputs: _*)
      .setOutputs(outputs: _*)
      .setPreHeader(ctx.createPreHeader().height(height).build())
      .buildTx(fee, changeTo, burn)

  // ─── assertions ───────────────────────────────────────────────────────────

  /** The spend must be accepted by the contract. */
  protected def accepts(prover: ErgoProver, tx: UnsignedTransaction): SignedTransaction =
    Try(prover.sign(tx)) match {
      case Success(signed) =>
        ContractSpecBase.logCost(tx, signed)
        signed
      case Failure(ex) =>
        fail(s"expected the contract to accept this spend, but it was rejected: ${ex.getMessage}", ex)
    }

  /** The spend must be rejected. Takes the tx by name — some negatives fail during build. */
  protected def rejects(prover: ErgoProver, tx: => UnsignedTransaction): Throwable =
    Try(prover.sign(tx)) match {
      case Success(_) =>
        fail("expected the contract to reject this spend, but it was accepted")
      case Failure(ex) => ex
    }

  /**
   * The spend must be rejected by the *contract*, not by the transaction builder.
   *
   * A negative that perturbs token amounts or box values can easily produce a transaction that
   * never balances, and [[rejects]] would pass on the build failure alone, proving nothing. This
   * splits the two steps: building must succeed, signing must not. Prefer it everywhere except
   * where a negative genuinely cannot produce a buildable transaction, and say so when it cannot.
   */
  protected def rejectsAtSigning(prover: ErgoProver, tx: => UnsignedTransaction): Throwable = {
    val built = Try(tx) match {
      case Success(t) => t
      case Failure(ex) =>
        fail(s"expected a well-formed transaction for the contract to reject, but building it failed: ${ex.getMessage}", ex)
    }
    Try(prover.sign(built)) match {
      case Success(_) =>
        fail("expected the contract to reject this spend, but it was accepted")
      case Failure(ex) => ex
    }
  }
}
