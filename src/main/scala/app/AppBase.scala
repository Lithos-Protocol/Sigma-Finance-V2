package ksingh.sigmabonds
package app

import io.circe.Json
import org.ergoplatform.appkit.{Address, BlockchainContext, NetworkType, Parameters}
import org.ergoplatform.sdk.ErgoId
import scorex.util.encode.Base16
import sigma.ast.ErgoTree
import work.lithos.mutations.{Contract, InputUTXO, MainnetEip27Constants, Token, TxBuilder, UTXO}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.Try

/**
 * What a run may do beyond reading: which wallet address to act as, the fee, and whether to submit. `spent`
 * collects the boxes transactions submitted in this session spent, so a later command never builds on them
 * while the node still lists them; a shell session shares one set across its commands. `submitted` records
 * every transaction a run submits, for a caller driving the CLI (the live test).
 */
case class AppSettings(address: Option[String] = None,
                       walletPass: Option[String] = None,
                       fee: Long = Parameters.MinFee,
                       send: Boolean = false,
                       spent: mutable.Set[String] = mutable.Set.empty[String],
                       submitted: mutable.Buffer[Submission] = mutable.ArrayBuffer.empty[Submission])

/** A transaction a run submitted: its title, its id, and the ids of its output boxes in order. */
case class Submission(title: String, txId: String, outputs: Seq[String])

/** A transaction built and ready for the node wallet, with what it does in words. */
case class Prepared(title: String, tx: org.ergoplatform.appkit.UnsignedTransaction, notes: Seq[String])

/**
 * ErgoTrees by the blake2b256 hash of their bytes, as contracts name scripts. A hash never changes meaning,
 * so entries never expire; a shell session shares one book across its commands.
 */
class ScriptBook {
  private val byHash = mutable.Map.empty[String, ErgoTree]
  def add(tree: ErgoTree): Unit = byHash(Base16.encode(Protocol.hashOf(tree))) = tree
  def get(hash: Array[Byte]): Option[ErgoTree] = byHash.get(Base16.encode(hash))
}

/**
 * What every SigmaFi app shares, whichever contract generation it builds: the node wallet, transaction
 * planning around protocol inputs, and signing and submitting through the node. Nothing is signed locally,
 * and no explorer is used. A subclass names the scripts it knows, for transaction summaries.
 */
abstract class AppBase(ctx: BlockchainContext, node: NodeApi, settings: AppSettings, out: String => Unit,
                       scripts: ScriptBook = new ScriptBook) {

  import Protocol._

  /**
   * The last block's height. The node wallet signs at it, running every script there, and the transaction
   * lands at a later height. So a path open only before height X needs height + 1 < X, and a path open from
   * X on (an expiry refund, a maturity liquidation, the end of a wait) needs height >= X.
   */
  def height: Int = ctx.getHeight

  /** The node, for code mixed into a subclass. */
  protected def nodeApi: NodeApi = node

  /** The miner fee every transaction this run builds pays. */
  def minerFee: Long = settings.fee

  def networkType: NetworkType = ctx.getNetworkType

  /** A short name for a script this app knows, for transaction summaries. */
  protected def nameOf(tree: ErgoTree): Option[String]

  // ═══ WALLET ═══════════════════════════════════════════════════════════════

  private var unlockedHere = false

  private lazy val walletReady: Unit = {
    val status = node.walletStatus().hcursor
    if (!status.downField("isInitialized").as[Boolean].getOrElse(false))
      fail("the node's wallet is not initialized")
    if (!status.downField("isUnlocked").as[Boolean].getOrElse(false)) {
      val pass = settings.walletPass.getOrElse(fail("the node's wallet is locked: pass --wallet-pass"))
      node.walletUnlock(pass)
      unlockedHere = true
    }
  }

  /** Locks the wallet again if this run unlocked it. */
  def close(): Unit = if (unlockedHere) {
    unlockedHere = false
    Try(node.walletLock())
  }

  lazy val walletAddresses: Seq[Address] = {
    walletReady
    node.walletAddresses().map(Address.create)
  }

  private lazy val walletTrees: Map[String, ErgoTree] = walletAddresses.map { a =>
    val tree = a.toErgoContract.getErgoTree
    Base16.encode(hashOf(tree)) -> tree
  }.toMap

  def isWalletHash(hash: Array[Byte]): Boolean = walletTrees.contains(Base16.encode(hash))

  /** Whether this run can read the wallet at all: listings mark your own boxes only when it can. */
  lazy val walletAvailable: Boolean = Try(walletAddresses).isSuccess

  /** As isWalletHash, but false rather than an error when the wallet cannot be read. */
  def isOwnHash(hash: Array[Byte]): Boolean = walletAvailable && isWalletHash(hash)

  /** The address this wallet lends, borrows and executes as: --address, else the wallet's change address. */
  lazy val myAddress: Address = {
    walletReady
    val chosen = settings.address
      .orElse(node.walletStatus().hcursor.downField("changeAddress").as[String].toOption.filter(_.nonEmpty))
      .map(a => Try(Address.create(a)).getOrElse(fail(s"$a is not a valid address")))
      .orElse(walletAddresses.headOption)
      .getOrElse(fail("the node's wallet has no addresses"))
    if (!walletAddresses.exists(_.toString == chosen.toString))
      fail(s"$chosen is not an address of the node's wallet")
    chosen
  }

  lazy val myTree: ErgoTree = myAddress.toErgoContract.getErgoTree

  /**
   * Boxes the wallet can spend: P2PK boxes at its own addresses. Boxes carrying re-emission tokens are left
   * out, so a spend never owes the EIP-27 payment, and so are boxes this session has already spent.
   */
  lazy val walletBoxes: Seq[InputUTXO] = {
    walletReady
    node.walletUnspentBoxes().flatMap(j => Try(NodeJson.box(j)).toOption)
      .filter(b => walletTrees.contains(Base16.encode(hashOf(b.input.getErgoTree))))
      .filterNot(_.tokens.exists(_.id.toString == MainnetEip27Constants.TokenId))
      .filterNot(b => settings.spent.contains(b.id.toString))
  }

  def walletHolds(tokenId: ErgoId): Boolean = walletBoxes.exists(_.tokens.exists(_.id == tokenId))

  /**
   * Wallet boxes still in the mempool (listed only with --mempool). Coin selection spends them last: a
   * transaction paying with unconfirmed change shares the fate of whatever made it, so one dropped
   * transaction would take every later one down with it.
   */
  private lazy val unconfirmedWallet: Set[String] = {
    walletReady
    Try(node.walletUnconfirmedBoxIds()).getOrElse(Set.empty)
  }

  private def unconfirmed(b: InputUTXO): Boolean = unconfirmedWallet.contains(b.id.toString)

  /** A wallet box under the script `hash` names: the co-spend that proves a borrower or owner is present. */
  protected def walletBoxAt(hash: Array[Byte], who: String): InputUTXO =
    walletBoxes.filter(b => hashOf(b.input.getErgoTree).sameElements(hash)).sortBy(unconfirmed).headOption
      .getOrElse(fail(s"this wallet holds no box at the $who's address; only the $who can do this"))

  // ═══ SIGN AND SUBMIT ══════════════════════════════════════════════════════

  /**
   * Print what the transaction does, have the node wallet sign it and the node check it, and submit it only
   * when the settings say so. Returns the id of a submitted transaction.
   */
  def submit(p: Prepared): Option[String] = {
    summarize(p)
    walletReady
    val signed = node.signTransaction(NodeJson.unsignedTx(p.tx),
      p.tx.getInputs.asScala.map(b => Base16.encode(b.getBytes)),
      p.tx.getDataInputs.asScala.map(b => Base16.encode(b.getBytes)))
    val id = node.checkTransaction(signed)
    out(s"The node wallet signed it, and the node accepts it: $id")
    val outputIds = signed.hcursor.downField("outputs").focus.flatMap(_.asArray).getOrElse(Vector.empty)
      .map(_.hcursor.downField("boxId").as[String].getOrElse(""))
    outputIds.zipWithIndex.foreach { case (boxId, i) => if (boxId.nonEmpty) out(s"  output $i box id: $boxId") }
    if (settings.send) {
      val sent = node.sendTransaction(signed)
      settings.spent ++= p.tx.getInputs.asScala.map(_.getId.toString)
      settings.submitted += Submission(p.title, sent, outputIds)
      out(s"Submitted: $sent")
      Some(sent)
    } else {
      out("Dry run: nothing was submitted. Run the same command with --send to submit it.")
      None
    }
  }

  private def summarize(p: Prepared): Unit = {
    out(s"== ${p.title} ==")
    p.notes.foreach(n => out(s"  $n"))
    out("Inputs:")
    p.tx.getInputs.asScala.zipWithIndex.foreach { case (b, i) =>
      out(f"  $i%2d  ${b.getId}  ${Fmt.erg(b.getValue)}%-16s  ${describe(b.getErgoTree)}")
      b.getTokens.asScala.foreach(t => out(s"        ${t.getValue} of ${t.getId}"))
    }
    if (!p.tx.getDataInputs.isEmpty) {
      out("Data inputs:")
      p.tx.getDataInputs.asScala.zipWithIndex.foreach { case (b, i) => out(f"  $i%2d  ${b.getId}") }
    }
    out("Outputs:")
    p.tx.getOutputs.asScala.zipWithIndex.foreach { case (b, i) =>
      out(f"  $i%2d  ${Fmt.erg(b.getValue)}%-16s  ${describe(b.getErgoTree)}")
      b.getTokens.asScala.foreach(t => out(s"        ${t.getValue} of ${t.getId}"))
    }
    p.tx.getTokensToBurn.asScala.foreach(t => out(s"Burns: ${t.getValue} of ${t.getId}"))
  }

  private def describe(tree: ErgoTree): String =
    nameOf(tree).orElse(if (sameTree(tree, Contract.FEE.ergoTree)) Some("miner fee") else None).getOrElse {
      val address = addressOf(tree)
      if (isWalletHash(hashOf(tree))) s"$address (this wallet)" else address
    }

  protected def addressOf(tree: ErgoTree): String = {
    val a = Address.fromErgoTree(tree, ctx.getNetworkType).toString
    if (a.length > 60) a.take(28) + "..." + a.takeRight(28) else a
  }

  // ═══ BUILDING ═════════════════════════════════════════════════════════════

  protected case class Plan(inputs: Seq[InputUTXO], outputs: Seq[UTXO], burn: Seq[Token], fee: Long,
                            dataInputs: Seq[InputUTXO] = Seq.empty)

  protected def prepare(title: String, p: Plan, notes: Seq[String] = Seq.empty): Prepared = {
    val (inputs, disclosed) = disclose(p)
    val tx = TxBuilder(ctx).setInputs(inputs: _*).setDataInputs(p.dataInputs: _*).setOutputs(p.outputs: _*)
      .buildTx(p.fee, myAddress, p.burn)
    Prepared(title, tx, notes ++ (if (disclosed == 0) Seq.empty
      else Seq(s"discloses $disclosed script(s) the outputs name by hash, in context variable ${DisclosureVar.toInt} (zcs1)")))
  }

  /**
   * The hashes of scripts `outputs` name in their registers (a borrower, an owner, a seller): what script
   * disclosure covers. A subclass that builds such boxes says which registers those are.
   */
  protected def namedScripts(outputs: Seq[UTXO]): Seq[Array[Byte]] = Seq.empty

  /**
   * zcs1 script disclosure: every script the outputs name by hash, and that no input or output of the
   * transaction already has as its script, goes into context variable 64 of INPUTS(0) as ErgoTree bytes. A
   * batcher then finds it in one lookup of the box's creating transaction. A script this app cannot name
   * stops the build: the transaction would not be standard.
   */
  private def disclose(p: Plan): (Seq[InputUTXO], Int) = {
    val present = (p.inputs.map(_.input.getErgoTree) ++ p.outputs.map(_.contract.ergoTree))
      .map(t => Base16.encode(hashOf(t))).toSet
    val missing = namedScripts(p.outputs).map(Base16.encode).distinct.filterNot(present.contains)
    if (missing.isEmpty || p.inputs.isEmpty) (p.inputs, 0)
    else {
      val trees = missing.map { hex =>
        knownTree(Base16.decode(hex).get).getOrElse(fail(s"cannot disclose the script with hash $hex, which an " +
          "output names: zcs1 needs it in the transaction, and this client has not seen it"))
      }
      (p.inputs.head.withCtxVar(DisclosureVar, treesValue(trees: _*)) +: p.inputs.tail, trees.size)
    }
  }

  /** A tree already known for `hash`: this run's book, or one of the wallet's own addresses. */
  protected def knownTree(hash: Array[Byte]): Option[ErgoTree] =
    scripts.get(hash).orElse(if (walletAvailable) walletTrees.get(Base16.encode(hash)) else None)

  /**
   * Protocol inputs keep the positions their contracts read, and wallet boxes follow them to cover the ERG
   * and tokens the outputs, burns and fee need beyond what those inputs carry. Change returns to this
   * wallet; a surplus too small for a change box, with no tokens to carry, goes to the miner. Wallet boxes
   * holding a token being burned are never picked: the leftover would reach the change box, and every
   * burn here requires that no output carries the token.
   */
  protected def plan(spends: Seq[InputUTXO], outputs: Seq[UTXO], burn: Seq[Token] = Seq.empty,
                     minted: Option[ErgoId] = None, dataInputs: Seq[InputUTXO] = Seq.empty): Plan = {
    def left(inputs: Seq[InputUTXO]): (Long, Map[ErgoId, Long]) = {
      val erg = inputs.map(_.value).sum - outputs.map(_.value).sum - settings.fee
      val held = tally(inputs.flatMap(_.tokens))
      val used = tally(outputs.flatMap(_.tokens) ++ burn)
      val net = (held.keySet ++ used.keySet).map(id => id -> (held.getOrElse(id, 0L) - used.getOrElse(id, 0L))).toMap
      (erg, net -- minted.toSeq)
    }
    val (erg, tokens) = left(spends)
    val short = tokens.collect { case (id, n) if n < 0 => id -> -n }
    val carries = tokens.exists(_._2 > 0)
    val selfFunded = short.isEmpty && (erg >= MinBox || (erg >= 0 && !carries))
    val wallet =
      if (selfFunded) Seq.empty
      else selectWallet(math.max(0L, MinBox - erg), short, spends.map(_.id).toSet, burn.map(_.id).toSet)

    val (finalErg, finalTokens) = left(spends ++ wallet)
    val dust = if (finalErg > 0 && finalErg < MinBox && !finalTokens.exists(_._2 > 0)) finalErg else 0L
    Plan(spends ++ wallet, outputs, burn, settings.fee + dust, dataInputs)
  }

  /** Everything the inputs hold, less burns and the fee, to this wallet in one box. */
  protected def sweep(spends: Seq[InputUTXO], burn: Seq[Token] = Seq.empty): Plan = {
    val burned = tally(burn)
    val kept = tally(spends.flatMap(_.tokens)).toSeq
      .map { case (id, n) => id -> (n - burned.getOrElse(id, 0L)) }
      .collect { case (id, n) if n > 0 => Token(id, n) }
    val total = spends.map(_.value).sum
    plan(spends, Seq(UTXO(Contract(myTree), math.max(MinBox, total - settings.fee), kept)), burn)
  }

  /**
   * Token boxes first, largest holdings first; then the largest ERG boxes until `erg` is covered. Confirmed
   * boxes always come before unconfirmed ones.
   */
  private def selectWallet(erg: Long, tokens: Map[ErgoId, Long], exclude: Set[ErgoId],
                           avoidTokens: Set[ErgoId]): Seq[InputUTXO] = {
    val pool = walletBoxes.filterNot(b => exclude.contains(b.id) || b.tokens.exists(t => avoidTokens.contains(t.id)))
    val chosen = mutable.LinkedHashSet.empty[InputUTXO]
    def held(id: ErgoId): Long = chosen.toSeq.flatMap(_.tokens).filter(_.id == id).map(_.amount).sum
    def amountIn(b: InputUTXO, id: ErgoId): Long = b.tokens.filter(_.id == id).map(_.amount).sum

    tokens.foreach { case (id, need) =>
      pool.filter(amountIn(_, id) > 0).sortBy(b => (unconfirmed(b), -amountIn(b, id)))
        .foreach(b => if (held(id) < need) chosen += b)
      if (held(id) < need) fail(s"the wallet holds ${held(id)} of token $id; this needs $need")
    }
    pool.filterNot(chosen.contains).sortBy(b => (unconfirmed(b), -b.value)).foreach { b =>
      if (chosen.toSeq.map(_.value).sum < erg) chosen += b
    }
    val value = chosen.toSeq.map(_.value).sum
    if (value < erg)
      fail(s"the wallet's spendable boxes hold ${Fmt.erg(pool.map(_.value).sum)}; this needs ${Fmt.erg(erg)} " +
        "from them, the fee and a change box included")
    chosen.toSeq
  }

  protected def tally(tokens: Seq[Token]): Map[ErgoId, Long] =
    tokens.groupBy(_.id).map { case (id, ts) => id -> ts.map(_.amount).sum }

  // ═══ LOOKUPS ══════════════════════════════════════════════════════════════

  protected def decoded(boxes: Seq[Json]): Seq[InputUTXO] = boxes.flatMap(j => Try(NodeJson.box(j)).toOption)

  /** An unspent box by id, from the node's index. */
  protected def unspent(boxId: String): InputUTXO = {
    if (settings.spent.contains(boxId)) fail(s"box $boxId was spent by a transaction this session submitted")
    val json = node.boxById(boxId).getOrElse(fail(s"the node's index has no box $boxId"))
    NodeJson.spentBy(json).foreach(tx => fail(s"box $boxId was already spent, by transaction $tx"))
    NodeJson.box(json)
  }

  /**
   * The ErgoTree a hash commits to: a hint tree, a tree this run has already seen, one of this wallet's
   * addresses, or, in one lookup of the transaction that created `box`, a tree it disclosed in context
   * variable 64 or the script of one of its inputs or outputs (zcs1 script disclosure). Nothing walks further
   * back. What is found is kept for the rest of the run.
   */
  protected def treeFor(hash: Array[Byte], box: InputUTXO, hints: Seq[ErgoTree]): Option[ErgoTree] = {
    def matching(trees: Seq[ErgoTree]) = trees.find(t => hashOf(t).sameElements(hash))
    val txId = box.input.getTransactionId
    val found = matching(hints)
      .orElse(knownTree(hash))
      .orElse(node.transactionById(txId).orElse(node.unconfirmedTransactionById(txId))
        .flatMap(tx => matching(NodeJson.disclosedTrees(tx) ++ NodeJson.transactionTrees(tx))))
    found.foreach(scripts.add)
    found
  }

  protected def addAmount(a: Long, b: Long): Long =
    Try(Math.addExact(a, b)).getOrElse(fail("the requested amount or height overflows a Long"))

  protected def fail(message: String): Nothing = throw CliError(message)
}
