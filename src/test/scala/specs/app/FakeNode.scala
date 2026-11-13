package org.lithosprotocol.sigmafi
package specs.app

import io.circe.Json
import ksingh.sigmabonds.app.{NodeApi, NodeJson}
import org.ergoplatform.ErgoBox
import org.ergoplatform.appkit.impl.{InputBoxImpl, SignedTransactionImpl}
import org.ergoplatform.appkit.{ErgoProver, SignedTransaction, UnsignedTransaction}
import org.ergoplatform.wallet.serialization.JsonCodecsWrapper
import scorex.crypto.hash.Blake2b256
import scorex.util.encode.Base16

import scala.collection.JavaConverters._
import scala.collection.mutable

/**
 * A transaction the chain recorded: its input boxes, each input's `spendingProof` as the node renders it
 * (context extension included; empty for a seeded transaction), and its outputs.
 */
case class FakeTx(inputs: Seq[ErgoBox], outputs: Seq[ErgoBox], proofs: Seq[Json] = Seq.empty)

/**
 * An in-memory chain with an extra index: a UTXO set, the boxes it has spent and the transactions that
 * did, fed by the transactions a spec signs.
 */
class FakeChain {
  val unspent: mutable.LinkedHashMap[String, ErgoBox] = mutable.LinkedHashMap.empty
  val spent: mutable.Map[String, (ErgoBox, String)] = mutable.Map.empty
  val txs: mutable.Map[String, FakeTx] = mutable.Map.empty

  def add(box: ErgoBox): Unit = unspent(Base16.encode(box.id)) = box

  def apply(uTx: UnsignedTransaction, signed: SignedTransaction): Unit = {
    val ins = uTx.getInputs.asScala.map(_.asInstanceOf[InputBoxImpl].getErgoBox)
    val outs = signed.getOutputsToSpend.asScala.map(_.asInstanceOf[InputBoxImpl].getErgoBox)
    val proofs = NodeJson.v6(signed.asInstanceOf[SignedTransactionImpl].getTx.inputs.map(i =>
      JsonCodecsWrapper.inputEncoder(i).hcursor.downField("spendingProof").focus.getOrElse(Json.Null)))
    ins.foreach { b =>
      val id = Base16.encode(b.id)
      require(unspent.remove(id).isDefined, s"double spend of $id")
      spent(id) = (b, signed.getId)
    }
    outs.foreach(add)
    txs(signed.getId) = FakeTx(ins, outs, proofs)
  }

  /** As `/blockchain` renders a box. */
  def json(b: ErgoBox, spentBy: Option[String] = None): Json =
    NodeJson.v6(JsonCodecsWrapper.ergoBoxEncoder(b))
      .deepMerge(Json.obj("spentTransactionId" -> spentBy.fold(Json.Null)(Json.fromString)))
}

/**
 * The node's index and one wallet over a FakeChain. Signing is the spec's job, so the wallet endpoints that
 * sign or submit are not here: they would throw on the network.
 */
class FakeNode(chain: FakeChain, wallet: ErgoProver) extends NodeApi("http://fake.invalid", None) {

  private val walletTree = wallet.getAddress.toErgoContract.getErgoTree.bytes

  private def unspentWhere(p: ErgoBox => Boolean): Seq[Json] = chain.unspent.values.filter(p).map(chain.json(_)).toSeq

  override def info(): Json = Json.obj("network" -> Json.fromString("mainnet"))

  /** Its mempool is always empty, so reading it or not changes nothing. */
  override def withMempool(on: Boolean): NodeApi = this

  override def indexedHeight(): Json = Json.obj("indexedHeight" -> Json.fromInt(0), "fullHeight" -> Json.fromInt(0))

  override def unspentByErgoTree(treeHex: String): Seq[Json] =
    unspentWhere(b => Base16.encode(b.ergoTree.bytes) == treeHex)

  override def unspentByTemplateHash(hashHex: String): Seq[Json] =
    unspentWhere(b => Base16.encode(Blake2b256.hash(b.ergoTree.template)) == hashHex)

  override def unspentByTokenId(tokenId: String): Seq[Json] =
    unspentWhere(_.additionalTokens.toArray.exists { case (id, _) => Base16.encode(id.toArray) == tokenId })

  override def boxById(boxId: String): Option[Json] =
    chain.unspent.get(boxId).map(chain.json(_)).orElse(chain.spent.get(boxId).map { case (b, tx) => chain.json(b, Some(tx)) })

  /** As the indexed node renders it: inputs are boxes with the `spendingProof` they were spent with. */
  override def transactionById(txId: String): Option[Json] =
    chain.txs.get(txId).map { tx =>
      Json.obj("id" -> Json.fromString(txId),
        "inputs" -> Json.fromValues(tx.inputs.zipWithIndex.map { case (b, i) =>
          chain.json(b, Some(txId)).deepMerge(Json.obj("spendingProof" -> tx.proofs.lift(i).getOrElse(Json.Null)))
        }),
        "outputs" -> Json.fromValues(tx.outputs.map(chain.json(_))))
    }

  /** Everything here is confirmed at once: the mempool is always empty. */
  override def unconfirmedTransactionById(txId: String): Option[Json] = None

  override def walletStatus(): Json = Json.obj(
    "isInitialized" -> Json.True, "isUnlocked" -> Json.True,
    "changeAddress" -> Json.fromString(wallet.getAddress.toString))

  override def walletAddresses(): Seq[String] = Seq(wallet.getAddress.toString)

  override def walletUnspentBoxes(): Seq[Json] = unspentWhere(b => java.util.Arrays.equals(b.ergoTree.bytes, walletTree))
}
