package ksingh.sigmabonds
package app

import io.circe.{Json, parser}
import okhttp3.{HttpUrl, MediaType, OkHttpClient, Request, RequestBody}
import org.ergoplatform.appkit.UnsignedTransaction
import org.ergoplatform.appkit.impl.{InputBoxImpl, UnsignedTransactionImpl}
import org.ergoplatform.wallet.serialization.JsonCodecsWrapper
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast.ErgoTree
import sigma.serialization.ErgoTreeSerializer
import work.lithos.mutations.InputUTXO

import java.util.concurrent.TimeUnit

/** The node answered with an error status. `reason` is the node's ApiError reason and detail, when it sent one. */
case class NodeError(status: Int, path: String, reason: String)
  extends Exception(s"node returned $status for $path: $reason")

/**
 * The Ergo node REST API, and nothing else: there is no explorer. The `/blockchain` endpoints need the
 * node's extra index (`ergo.node.extraIndex = true`); the `/wallet` endpoints need the API key. Every
 * endpoint returns the node's JSON as is, so a spec can stand in for the node by overriding them.
 *
 * With `mempool`, lookups also see the mempool: unspent listings include boxes created by unconfirmed
 * transactions and leave out boxes they spend, a box id falls back to the node's UTXO set with its pool, and
 * the wallet lists unconfirmed boxes too. A batch run uses it to chain work through continuations it has just
 * submitted.
 */
class NodeApi(nodeUrl: String, apiKey: Option[String], val mempool: Boolean = false) {

  private val root = nodeUrl.stripSuffix("/")
  private val JsonType = MediaType.parse("application/json; charset=utf-8")
  private lazy val http = new OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .build()

  /** The indexed endpoints accept any limit, but a page this size keeps each response small. */
  private val PageSize = 100
  private val WalletPageSize = 500

  // ─── info ────────────────────────────────────────────────────────────────

  def info(): Json = required("/info", get("/info"))

  /** `indexedHeight` and `fullHeight`: the index lags the chain while it catches up. */
  def indexedHeight(): Json = required("/blockchain/indexedHeight", get("/blockchain/indexedHeight"))

  // ─── indexed blockchain ──────────────────────────────────────────────────

  def unspentByErgoTree(treeHex: String): Seq[Json] = pages(PageSize) { (offset, limit) =>
    post("/blockchain/box/unspent/byErgoTree", Json.fromString(treeHex), unspentQuery(offset, limit))
  }

  def unspentByTemplateHash(hashHex: String): Seq[Json] = pages(PageSize) { (offset, limit) =>
    get(s"/blockchain/box/unspent/byTemplateHash/$hashHex", unspentQuery(offset, limit))
  }

  def unspentByTokenId(tokenId: String): Seq[Json] = pages(PageSize) { (offset, limit) =>
    get(s"/blockchain/box/unspent/byTokenId/$tokenId", unspentQuery(offset, limit))
  }

  /**
   * Any box the index has seen, spent or not: `spentTransactionId` says which. With `mempool`, a box the index
   * has not seen yet is looked up in the UTXO set with the mempool's outputs.
   */
  def boxById(boxId: String): Option[Json] =
    get(s"/blockchain/box/byId/$boxId").orElse(
      if (mempool) get(s"/utxo/withPool/byId/$boxId").filterNot(_.isNull) else None)

  /**
   * Its `inputs` and `outputs` are whole boxes, ErgoTrees included, and each input carries the `spendingProof`
   * it was spent with, context extension included.
   */
  def transactionById(txId: String): Option[Json] = get(s"/blockchain/transaction/byId/$txId")

  /** A transaction still in the mempool: its inputs carry their `spendingProof`, and their box when known. */
  def unconfirmedTransactionById(txId: String): Option[Json] =
    get(s"/transactions/unconfirmed/byTransactionId/$txId").filterNot(_.isNull)

  // ─── wallet ──────────────────────────────────────────────────────────────

  /** `isInitialized`, `isUnlocked`, `changeAddress`, `walletHeight`, `error`. */
  def walletStatus(): Json = required("/wallet/status", get("/wallet/status"))

  def walletUnlock(pass: String): Unit = post("/wallet/unlock", Json.obj("pass" -> Json.fromString(pass)))

  def walletLock(): Unit = get("/wallet/lock")

  def walletAddresses(): Seq[String] =
    required("/wallet/addresses", get("/wallet/addresses")).asArray.getOrElse(Vector.empty).flatMap(_.asString)

  /** The `box` of every unspent box the wallet holds: confirmed ones, and with `mempool` unconfirmed ones too. */
  def walletUnspentBoxes(): Seq[Json] = pages(WalletPageSize) { (offset, limit) =>
    get("/wallet/boxes/unspent", Seq("offset" -> offset, "limit" -> limit) ++
      (if (mempool) Seq("minConfirmations" -> -1) else Seq.empty))
  }.flatMap(_.hcursor.downField("box").focus)

  /**
   * Sign with the wallet's keys. The input boxes go along in serialized form, so the node needs neither a
   * UTXO set nor the boxes in its wallet, and inputs no key guards are simply left with empty proofs.
   */
  def signTransaction(tx: Json, inputsRaw: Seq[String], dataInputsRaw: Seq[String]): Json =
    required("/wallet/transaction/sign", post("/wallet/transaction/sign", Json.obj(
      "tx" -> tx,
      "inputsRaw" -> Json.fromValues(inputsRaw.map(Json.fromString)),
      "dataInputsRaw" -> Json.fromValues(dataInputsRaw.map(Json.fromString)),
      "secrets" -> Json.obj("dlog" -> Json.arr(), "dht" -> Json.arr()))))

  /** Full validation against the node's UTXO set and mempool, without submitting. Returns the tx id. */
  def checkTransaction(signed: Json): String = txId("/transactions/check", post("/transactions/check", signed))

  def sendTransaction(signed: Json): String = txId("/transactions", post("/transactions", signed))

  /**
   * Have the node wallet build, sign and submit a transaction for `requests`: payments (`address`, `value`,
   * `assets`) and at most one asset issue (`address`, `ergValue`, `amount`, `name`, `description`,
   * `decimals`), whose token id is the transaction's first input. Returns the transaction id.
   */
  def walletTransactionSend(requests: Seq[Json], fee: Long): String =
    txId("/wallet/transaction/send", post("/wallet/transaction/send", Json.obj(
      "requests" -> Json.fromValues(requests),
      "fee" -> Json.fromLong(fee),
      "inputsRaw" -> Json.arr(),
      "dataInputsRaw" -> Json.arr())))

  // ─── http ────────────────────────────────────────────────────────────────

  private def unspentQuery(offset: Int, limit: Int): Seq[(String, Any)] =
    Seq("offset" -> offset, "limit" -> limit, "sortDirection" -> "asc") ++
      (if (mempool) Seq("includeUnconfirmed" -> true, "excludeMempoolSpent" -> true) else Seq.empty)

  private def get(path: String, query: Seq[(String, Any)] = Seq.empty): Option[Json] =
    request("GET", path, query, None)

  private def post(path: String, body: Json, query: Seq[(String, Any)] = Seq.empty): Option[Json] =
    request("POST", path, query, Some(body))

  private def required(path: String, json: Option[Json]): Json =
    json.getOrElse(throw NodeError(404, path, "not found; is this an Ergo node URL?"))

  private def txId(path: String, json: Option[Json]): String =
    required(path, json).asString.getOrElse(throw NodeError(200, path, s"expected a transaction id, got $json"))

  /** Some list endpoints return a bare array and others `{items, total}`. */
  private def items(json: Json): Seq[Json] =
    json.asArray.orElse(json.hcursor.downField("items").focus.flatMap(_.asArray)).getOrElse(Vector.empty)

  /** Every page, in index order. An index that has never seen the tree, hash or token answers 404: no boxes. */
  private def pages(size: Int)(fetch: (Int, Int) => Option[Json]): Seq[Json] = {
    val all = Vector.newBuilder[Json]
    var offset = 0
    var more = true
    while (more) {
      val page = fetch(offset, size).map(items).getOrElse(Seq.empty)
      all ++= page
      offset += size
      more = page.size == size
    }
    all.result()
  }

  /** None for a 404; any other error status throws, with the node's own reason. */
  protected def request(method: String, path: String, query: Seq[(String, Any)], body: Option[Json]): Option[Json] = {
    val url = Option(HttpUrl.parse(root + path))
      .getOrElse(throw new IllegalArgumentException(s"not a valid node URL: ${root + path}"))
      .newBuilder()
    query.foreach { case (k, v) => url.addQueryParameter(k, v.toString) }
    val req = new Request.Builder().url(url.build()).header("Accept", "application/json")
    apiKey.foreach(req.header("api_key", _))
    req.method(method, body.map(b => RequestBody.create(JsonType, b.noSpaces)).orNull)

    val resp = http.newCall(req.build()).execute()
    try {
      val text = Option(resp.body()).map(_.string()).getOrElse("")
      if (resp.code() == 404) None
      else if (!resp.isSuccessful) throw NodeError(resp.code(), path, reasonOf(text))
      else if (text.trim.isEmpty) Some(Json.Null)
      else Some(parser.parse(text).fold(
        e => throw new IllegalStateException(s"node sent invalid JSON for $path: ${e.message}"), identity))
    } finally resp.close()
  }

  private def reasonOf(text: String): String =
    parser.parse(text).toOption.flatMap { j =>
      val c = j.hcursor
      c.downField("reason").as[String].toOption.map { reason =>
        reason + c.downField("detail").as[String].toOption.filter(_.nonEmpty).fold("")(": " + _)
      }
    }.getOrElse(text.take(500))
}

/** Converting between the node's JSON and appkit's types. */
object NodeJson {

  /** The SigmaFi contracts are ErgoTree v3, which only parse with 6.0 activated. */
  def v6[A](f: => A): A =
    VersionContext.withVersions(VersionContext.V6SoftForkVersion, VersionContext.V6SoftForkVersion)(f)

  /**
   * A box as the node renders one: `/blockchain`, `/utxo` and the wallet's `box` all share the format. The
   * id is recomputed from the contents, and must match the one the node sent.
   */
  def box(json: Json): InputUTXO = v6 {
    val ergoBox = JsonCodecsWrapper.ergoBoxDecoder.decodeJson(json)
      .fold(e => throw new IllegalStateException(s"undecodable box: ${e.getMessage}"), identity)
    json.hcursor.downField("boxId").as[String].foreach { id =>
      require(id == Base16.encode(ergoBox.id), s"box $id decodes to different contents")
    }
    InputUTXO(new InputBoxImpl(ergoBox))
  }

  /** The transaction that spent an indexed box, if any. */
  def spentBy(json: Json): Option[String] =
    json.hcursor.downField("spentTransactionId").as[Option[String]].toOption.flatten

  def tree(hex: String): ErgoTree = v6 {
    ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(Base16.decode(hex).get)
  }

  /** Every ErgoTree an indexed transaction's inputs and outputs carry. */
  def transactionTrees(json: Json): Seq[ErgoTree] =
    Seq("inputs", "outputs").flatMap { field =>
      json.hcursor.downField(field).focus.flatMap(_.asArray).getOrElse(Vector.empty)
        .flatMap(_.hcursor.downField("ergoTree").as[String].toOption)
    }.flatMap(hex => scala.util.Try(tree(hex)).toOption)

  /**
   * The ErgoTrees a transaction discloses in context variable 64 (zcs1 script disclosure): a
   * `Coll[Coll[Byte]]` on any input's extension, read from each input's `spendingProof`. Entries that do not
   * parse are dropped.
   */
  def disclosedTrees(json: Json): Seq[ErgoTree] = v6 {
    json.hcursor.downField("inputs").focus.flatMap(_.asArray).getOrElse(Vector.empty).flatMap { input =>
      input.hcursor.downField("spendingProof").downField("extension").focus.toSeq
        .flatMap(ext => JsonCodecsWrapper.contextExtensionDecoder.decodeJson(ext).toOption)
        .flatMap(_.values.get(Protocol.DisclosureVar))
        .flatMap {
          case c: sigma.ast.Constant[_] if c.tpe == sigma.ast.SCollection(sigma.ast.SCollection.SByteArray) =>
            c.value.asInstanceOf[sigma.Coll[sigma.Coll[Byte]]].toArray.toSeq.map(_.toArray)
          case _ => Seq.empty
        }
    }.flatMap(bytes => scala.util.Try(tree(Base16.encode(bytes))).toOption)
  }

  /** The unsigned transaction as `/wallet/transaction/sign` reads it, context extensions included. */
  def unsignedTx(uTx: UnsignedTransaction): Json = v6 {
    JsonCodecsWrapper.unsignedErgoLikeTransactionEncoder(uTx.asInstanceOf[UnsignedTransactionImpl].getTx)
  }
}
