package org.lithosprotocol.sigmafi
package specs.bonds

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

/**
 * The four FI Standard runs (ERG collateral, SigUSD principal and repayment), as flow code that does
 * not know where it runs. `FiLive` runs it against a real node; `FiLiveOfflineSpec` runs the same code
 * on the offline mock chain with the base trait's test keys. Every transaction is built from the
 * builders of FiStandardSpecBase, FiStandardMarketSpecBase and FiStandardSharesSpecBase; only the
 * inputs are assembled here, from the boxes earlier transactions created and the actors' wallets.
 *
 * Actors: BORROWER Alice, LENDER Bob, KEEPER Charlie, DAVID, ERIN. Every bond: 3 coupons of 5% of the
 * principal, first due 10 blocks after the start, period 10, grace 10; repayment = principal.
 *
 * Each signed transaction prints FILEDGER lines (per actor and asset, outputs at the actor's address
 * less inputs from it, stand-in boxes included) and each run ends with FINEED lines (the largest
 * cumulative shortfall in the run, and the net at its end).
 */
trait FiLiveFlows extends FiStandardMarketSpecBase with FiStandardSharesSpecBase {
  import BondSpecBase._
  import ZcStandardSpecBase._

  /** `role` keys the actor; `key` is the env name whose mnemonic signs, at EIP-3 `index`. */
  protected case class Actor(role: String, display: String, key: String, index: Int, prover: ErgoProver,
                             address: Address, standIn: Boolean = false) {
    val tree: Contract = Contract.fromAddress(address)
    def tag: String = display.toUpperCase
  }

  protected val Roles: Seq[(String, String)] =
    Seq("BORROWER" -> "Alice", "LENDER" -> "Bob", "KEEPER" -> "Charlie", "DAVID" -> "David", "ERIN" -> "Erin")

  protected var chain: BlockchainContext = null
  protected var actors: Map[String, Actor] = Map.empty
  protected var purse: Map[String, Seq[InputUTXO]] = Map.empty
  protected var standIns: Seq[String] = Seq.empty
  protected var allowStandIns: Boolean = true
  protected var failures: Seq[String] = Seq.empty
  protected var refusals: Seq[String] = Seq.empty
  protected var principalUnits: Long = 100L
  /** Signed transactions of the current run, and the highest height any run has pinned. */
  protected var signedInRun: Int = 0
  protected var highest: Int = 0
  protected var step: Int = 0
  protected var runName: String = ""
  private var standInSeq: Int = 0
  private var cum: Map[(String, String), Long] = Map.empty
  private var low: Map[(String, String), Long] = Map.empty
  /** (actor tag, asset) -> (largest cumulative shortfall, net at the end), per run. */
  protected var needs: Map[String, Map[(String, String), (Long, Long)]] = Map.empty

  protected val oneErg: Long = Parameters.OneErg
  protected val txFee: Long = Parameters.MinFee
  protected val minPerByte: Long = 360L

  // The schedule every bond uses, and the covenant bond's terms.
  protected val FirstOffset: Int = 10
  // Period and grace at least 30: a covenant order refuses a shorter grace.
  protected val Period: Int = 30
  protected val Grace: Int = 30
  protected val Coupons: Long = 3L
  protected val CureWindow: Long = 30L
  protected val OracleAge: Long = 720L
  /** The offer's fill fee and its expiry refund fee, at the floor. */
  protected val OfferFee: Long = 100000000L

  protected def alice: Actor = actors("BORROWER")
  protected def bob: Actor = actors("LENDER")
  protected def charlie: Actor = actors("KEEPER")
  protected def david: Actor = actors("DAVID")
  protected def erin: Actor = actors("ERIN")

  /** The FI order and bond carry the runner's principal, not the specs' 600. */
  override protected def loanTokens(w: W): Seq[Token] =
    if (w.erg) Seq.empty else Seq(Token(SigUSD, principalUnits))

  // ─── where it runs: offline by default, FiLive overrides ────────────────────

  protected def live: Boolean = false
  /** Dry: the planned height. Submitting: waits, and returns the node height. */
  protected def arrive(planned: Int): Int = planned
  protected def chainHeight(): Int = sys.error("no node in this mode")
  protected def txCtx(): BlockchainContext = chain
  protected def signer(c: BlockchainContext, a: Actor): ErgoProver = a.prover
  protected def isSaved(n: Int): Boolean = false
  protected def saved(n: Int): Option[SignedTransaction] = None
  protected def keep(n: Int, signed: SignedTransaction): Unit = ()
  protected def land(signed: SignedTransaction, label: String): Unit = ()
  /** The node's id for a transaction, where it differs from the id computed here (see `land`). */
  protected var nodeId: Map[String, String] = Map.empty
  /** A transaction's outputs as the node holds them: re-identified under the node's id where that differs. */
  protected def outsOf(tx: SignedTransaction): Seq[InputBox] = {
    val made = tx.getOutputsToSpend.asScala.toSeq
    nodeId.get(tx.getId.replace("\"", "")) match {
      case None => made
      case Some(real) => made.zipWithIndex.map { case (b, i) =>
        InputUTXO(b).toUTXO.setCreationHeight(b.getCreationHeight).toInput(txCtx(), ErgoId.create(real), i.toShort).input
      }
    }
  }

  /** Signs and applies the node rules; FiLiveOfflineSpec signs through FiStandardSpecBase.ok instead. */
  protected def signAccepted(p: ErgoProver, t: Tx): SignedTransaction = {
    val s = p.sign(t.unsigned)
    nodeRules(t, s).foreach(r => failures :+= s"node rule: $r")
    s
  }

  /** None if it signed; the refusal otherwise. FiLiveOfflineSpec refuses through FiStandardSpecBase.no. */
  protected def signRefused(built: => (Tx, ErgoProver)): Option[String] = Try(built) match {
    case Failure(e) => Some(first(e))
    case Success((t, p)) => Try(p.sign(t.unsigned)) match {
      case Success(_) => None
      case Failure(e) => Some(first(e))
    }
  }

  protected def first(e: Throwable): String = {
    var t = e
    while (t.getCause != null && t.getCause != t) t = t.getCause
    Option(t.getMessage).getOrElse(t.toString).split('\n').head.take(160)
  }

  // ─── purses ──────────────────────────────────────────────────────────────

  protected def usdIn(ts: Seq[Token]): Long = ts.filter(_.id == SigUSD).map(_.amount).sum
  protected def plain(b: InputUTXO): Boolean = b.tokens.forall(_.id == SigUSD)

  protected def standIn(a: Actor, value: Long, tokens: Seq[Token], why: String): Unit = {
    if (!allowStandIns) sys.error(s"${a.display} would need a stand-in box: $why")
    standInSeq += 1
    val box = UTXO(a.tree, value, tokens).toInput(chain, ErgoId.create(dummyTxId), (100 + standInSeq).toShort)
    purse += a.role -> (purse.getOrElse(a.role, Seq.empty) :+ box)
    standIns :+= s"${a.tag}: $why"
    println(s"        STAND-IN box for ${a.tag}: $why")
  }

  /** Wallet boxes covering `need` nanoERG and `usd` SigUSD units: SigUSD holders first, then plain ERG. */
  protected def pick(a: Actor, need: Long, usd: Long): Seq[InputUTXO] = {
    def usable = purse.getOrElse(a.role, Seq.empty).filter(plain)
    val haveUsd = usable.map(b => usdIn(b.tokens)).sum
    if (haveUsd < usd) standIn(a, 10L * minBox, Seq(Token(SigUSD, usd - haveUsd)), s"SigUSD short by ${usd - haveUsd} units")
    val haveErg = usable.map(_.value).sum
    if (haveErg < need) standIn(a, need - haveErg + oneErg / 10L, Seq.empty, f"ERG short by ${(need - haveErg) / 1e9}%.4f")
    val all = usable
    var chosen = Seq.empty[InputUTXO]
    val holders = all.filter(b => usdIn(b.tokens) > 0L).sortBy(b => -usdIn(b.tokens))
    holders.foreach(h => if (chosen.map(b => usdIn(b.tokens)).sum < usd) chosen :+= h)
    (all.filter(b => usdIn(b.tokens) == 0L).sortBy(-_.value) ++ holders.filterNot(chosen.contains))
      .foreach(b => if (chosen.map(_.value).sum < need) chosen :+= b)
    require(chosen.map(_.value).sum >= need && chosen.map(b => usdIn(b.tokens)).sum >= usd, s"${a.display} cannot cover the transaction")
    chosen
  }

  protected def settle(tx: SignedTransaction): Unit = {
    val spent = tx.getSignedInputs.asScala.map(_.getId.toString).toSet
    val outs = outsOf(tx).map(b => InputUTXO(b))
    purse = actors.keys.map { role =>
      val kept = purse.getOrElse(role, Seq.empty).filterNot(b => spent(b.id.toString))
      val have = kept.map(_.id.toString).toSet
      role -> (kept ++ outs.filter(o => o.contract == actors(role).tree && !have(o.id.toString)))
    }.toMap
  }

  // ─── one transaction ─────────────────────────────────────────────────────

  /**
   * One transaction. `fixed` are the protocol inputs, in order; the signer's wallet boxes follow when the
   * outputs, the fee or SigUSD need them (`cosign` forces one, for a borrower- or owner-script co-spend).
   * Outputs without a creation height are stamped at `height` (submitting: at the node height).
   */
  protected case class Step(card: String, label: String, by: Actor, height: Int, fixed: Seq[InputUTXO],
                            outs: Seq[UTXO], data: Seq[InputUTXO] = Seq.empty, burn: Seq[Token] = Seq.empty,
                            wallet: Boolean = true, cosign: Boolean = false)

  private def build(s: Step, pinned: Boolean): (Tx, Seq[InputUTXO], ErgoProver) = {
    val c = if (live) txCtx() else chain
    val at = if (pinned) s.height else chainHeight() + 1
    val stamp = if (pinned) s.height else at - 1
    val outs = s.outs.map(o => if (o.creationHeight.isDefined) o else o.setCreationHeight(stamp))
    val short = outs.map(_.value).sum + txFee - s.fixed.map(_.value).sum
    val usd = math.max(0L, outs.map(o => usdIn(o.tokens)).sum - s.fixed.map(b => usdIn(b.tokens)).sum)
    // Tokens no output or burn takes go to change, which then needs a box minimum.
    val leftover = s.fixed.flatMap(_.tokens).groupBy(_.id).exists { case (id, ts) =>
      ts.map(_.amount).sum > outs.flatMap(_.tokens).filter(_.id == id).map(_.amount).sum +
        s.burn.filter(_.id == id).map(_.amount).sum
    }
    val thinChange = short < 0L && -short < Parameters.MinChangeValue
    val needWallet = s.wallet && (short > 0L || usd > 0L || s.cosign || thinChange || (leftover && short >= 0L))
    val own = if (!needWallet) Seq.empty[InputUTXO]
      else pick(s.by, math.max(short, 0L) + 2L * Parameters.MinChangeValue, usd).filterNot(s.fixed.contains)
    // The node signs and identifies a transaction with each input's context variables in ascending id
    // order, so they are put in that order here; otherwise a wallet signature beside them is refused.
    val ins = (s.fixed ++ own).map(b => if (b.ctxVars.size < 2) b else orderedVars(b))
    val unsigned = TxBuilder(c)
      .setInputs(ins: _*)
      .setDataInputs(s.data: _*)
      .setOutputs(outs: _*)
      .setPreHeader(c.createPreHeader().height(at).build())
      .buildTx(txFee, s.by.address, s.burn)
    (Tx(unsigned, at, outs.size), ins, signer(c, s.by))
  }

  /**
   * The node rebuilds a transaction from its JSON and keeps each input's context variables in the order
   * the JSON lists them. A wallet signature covers them in the order held here, so the two must agree.
   * `varOrder` is that JSON order per input box, read from a first signing; `orderedVars` applies it.
   */
  private var varOrder: Map[String, Seq[Byte]] = Map.empty
  private def orderedVars(b: InputUTXO): InputUTXO = varOrder.get(b.id.toString) match {
    case None => b
    case Some(order) =>
      val box = b.input.withContextVars(b.ctxVars: _*)
      val ext = box.asInstanceOf[org.ergoplatform.appkit.impl.InputBoxImpl].getExtension
      val f = box.getClass.getDeclaredField("_extension")
      f.setAccessible(true)
      f.set(box, ext.copy(values = scala.collection.immutable.ListMap(order.map(k => k -> ext.values(k)): _*)))
      InputUTXO(box)
  }
  private def jsonVarOrder(json: String): Map[String, Seq[Byte]] = {
    val inputs = json.split("\"dataInputs\"")(0)
    """"boxId"\s*:\s*"([0-9a-f]{64})"[^}]*?"extension"\s*:\s*\{([^}]*)\}""".r.findAllMatchIn(inputs).map { m =>
      m.group(1) -> """"(\d+)"\s*:""".r.findAllMatchIn(m.group(2)).map(_.group(1).toByte).toSeq
    }.filter(_._2.size > 1).toMap
  }

  /** Must be accepted; signed (and, submitting, saved, sent and waited on), then entered in the ledger. */
  protected def ok(s: Step): SignedTransaction = {
    step += 1
    val before = saved(step)
    if (before.isDefined) {
      println(f"  saved ${s.by.display}%-8s [${s.card}] ${s.label}")
      land(before.get, s.label)
      settle(before.get)
      signedInRun += 1
      println(s"FILEDGER|$runName|$step|${s.card}|${s.label}|-|-|resumed, not counted")
      return before.get
    }
    if (live) arrive(s.height)
    Try {
      varOrder = Map.empty
      val (t0, ins0, p0) = build(s, pinned = !live)
      val sig0 = signAccepted(p0, t0)
      varOrder = if (live) jsonVarOrder(sig0.toJson(false)) else Map.empty
      if (varOrder.isEmpty) (sig0, t0, ins0) else {
        val (t, ins, p) = build(s, pinned = false)
        val again = signAccepted(p, t)
        varOrder = Map.empty
        (again, t, ins)
      }
    } match {
      case Success((signed, t, ins)) =>
        if (live) {
          keep(step, signed)
          println(f"  built ${s.by.display}%-8s [${s.card}] ${s.label}")
          land(signed, s.label)
        }
        settle(signed)
        signedInRun += 1
        highest = math.max(highest, t.height)
        val boxes = outsOf(signed)
        val thin = boxes.zipWithIndex.filter { case (b, _) => b.getValue < b.getBytes.length * minPerByte || b.getBytes.length > MaxBox }
        val note = if (thin.isEmpty) "" else {
          failures :+= s"${s.label}: output(s) ${thin.map(_._2).mkString(",")} below the minimum value for their size, or over $MaxBox bytes"
          "  BOX UNDER MINIMUM OR OVERSIZE: " + thin.map { case (b, i) => s"#$i ${b.getValue} for ${b.getBytes.length} bytes" }.mkString(" ")
        }
        println(f"  ok    h=${t.height}%-8d cost=${signed.getCost}%-7d bytes=${signed.toBytes.length}%-5d ${s.by.display}%-8s [${s.card}] ${s.label}$note")
        ledger(s, ins, signed)
        signed
      case Failure(e) =>
        failures :+= s"${s.label}: rejected, ${first(e)}"
        println(f"  FAIL  h=${s.height}%-8d ${s.by.display}%-8s [${s.card}] ${s.label}: ${first(e)}")
        throw new RuntimeException(s"stopped at: ${s.label}", e)
    }
  }

  /** Must be refused at signing: a control that the acceptance beside it means something. Never sent. */
  protected def no(s: Step): Unit =
    signRefused { val (t, _, p) = build(s, pinned = true); (t, p) } match {
      case None =>
        failures :+= s"${s.label}: expected a refusal, was accepted"
        println(f"  FAIL  h=${s.height}%-8d ${s.by.display}%-8s [${s.card}] ${s.label}: accepted, expected a refusal")
      case Some(why) =>
        refusals :+= s.label
        println(f"  no    h=${s.height}%-8d ${s.by.display}%-8s [${s.card}] ${s.label} (${why.take(60)})")
    }

  /** Must sign, and is never sent or kept: the accepted twin of a control, so the refusal is the height's. */
  protected def twin(s: Step): Unit =
    Try { val (t, _, p) = build(s, pinned = true); p.sign(t.unsigned) } match {
      case Success(_) => println(f"  twin  h=${s.height}%-8d ${s.by.display}%-8s [${s.card}] ${s.label} (signs; not sent)")
      case Failure(e) =>
        failures :+= s"${s.label}: expected to sign, ${first(e)}"
        println(f"  FAIL  h=${s.height}%-8d ${s.by.display}%-8s [${s.card}] ${s.label}: ${first(e)}")
    }

  // ─── the ledger ──────────────────────────────────────────────────────────

  private def ledger(s: Step, ins: Seq[InputUTXO], tx: SignedTransaction): Unit = {
    val outs = outsOf(tx)
    Roles.map(_._1).filter(actors.contains).map(actors).foreach { a =>
      val tree = a.tree.ergoTree
      val in = ins.filter(_.input.getErgoTree == tree)
      val out = outs.filter(_.getErgoTree == tree)
      val dErg = out.map(_.getValue.longValue).sum - in.map(_.value).sum
      val dUsd = out.flatMap(_.getTokens.asScala).filter(_.getId == SigUSD).map(_.getValue.longValue).sum -
        in.map(b => usdIn(b.tokens)).sum
      Seq("ERG" -> dErg, "SIGUSD" -> dUsd).foreach { case (asset, d) =>
        val key = (a.tag, asset)
        val c = cum.getOrElse(key, 0L) + d
        cum += key -> c
        low += key -> math.min(low.getOrElse(key, 0L), c)
        if (d != 0L) println(s"FILEDGER|$runName|$step|${s.card}|${s.label}|${a.tag}|$asset|$d")
      }
    }
  }

  protected def beginRun(name: String, title: String): Unit = {
    runName = name
    step = 0
    signedInRun = 0
    cum = Map.empty
    low = Map.empty
    println(s"\nRUN $name  $title")
  }

  protected def endRun(): Unit = {
    val rows = for {
      (role, _) <- Roles if actors.contains(role)
      asset <- Seq("ERG", "SIGUSD")
    } yield {
      val key = (actors(role).tag, asset)
      key -> (-low.getOrElse(key, 0L), cum.getOrElse(key, 0L))
    }
    rows.foreach { case ((tag, asset), (short, net)) => println(s"FINEED|$runName|$tag|$asset|$short|$net") }
    needs += runName -> rows.toMap
  }

  // ─── bonds ───────────────────────────────────────────────────────────────

  protected case class Bond(w: W, asset: ErgoId, origin: InputUTXO, bond: InputUTXO, held: InputUTXO)

  protected def out(tx: SignedTransaction, i: Int): InputUTXO = InputUTXO(outsOf(tx)(i))
  protected def startOf(b: InputUTXO): Int = r9(b)(1).toInt

  protected def couponOf(principal: Long): Long = math.max(principal * 5L / 100L, 1L)

  /** Collateral worth 150% of the repayment at `price` nanoERG per USD. */
  protected def cover150(price: Long): Long = (BigInt(price) * principalUnits * 15000L / 1000000L).toLong

  /**
   * Order R4: repayment = principal (raw SigUSD), 1000000 nanoERG beside the principal and as the floor,
   * fixed maturity, the order's expiry fee at the floor, then the coupon schedule; with a covenant ratio a
   * 30-block cure window and a 720-block oracle age.
   */
  protected def termsOf(maturity: Long, expiry: Long, ratio: Long): Seq[Long] =
    Seq(principalUnits, minBox, minBox, maturity, expiry, batchingFee, 0L, couponOf(principalUnits), Coupons,
      FirstOffset.toLong, Period.toLong, Grace.toLong, ratio, if (ratio > 0L) CureWindow else 0L,
      if (ratio > 0L) OracleAge else 0L)

  protected def worldOf(h: Int, maturity: Long, ratio: Long, collateral: Long): W =
    W(chain, TokenBond, h + 2, maturity, termsOf(maturity, h + 200L, ratio), collateral, Seq.empty)

  protected def postOrder(planned: Int, w: W): InputUTXO = {
    val now = arrive(planned)
    out(ok(Step("1", "Alice posts the order", alice, now, Seq.empty, Seq(orderBox(w).setCreationHeight(now)))), 0)
  }

  /** The fill's unit, bond and principal for `order`, started at `start`: fillOf, re-targeted at the real asset. */
  protected def fillOuts(w: W, order: InputUTXO, start: Int, holder: Actor): (W, UTXO, UTXO, UTXO) = {
    val ws = startedAt(w, start)
    val f = fillOf(ws)
    val asset = order.id
    (ws, f.unit.setTokens(Token(asset, 1L)).setContract(holder.tree),
      f.bond.setTokens(Token(asset, 2L) +: ws.collateral: _*).withRegNum(4, bytesValue(asset.getBytes)),
      f.principal.setRegs(bytesValue(asset.getBytes)))
  }

  /** The order spent by a fill: a covenant order names the always-true guard in R8 (orderBox), run from var 1. */
  protected def orderSpend(w: W, order: InputUTXO): InputUTXO =
    if (w.terms(12) > 0L) order.withCtxVar(1.toByte, liqVar(alwaysTrue(chain))) else order

  protected def fillDirect(planned: Int, w: W, order: InputUTXO, by: Actor): Bond = {
    val now = arrive(planned)
    val (_, held, bond, principal) = fillOuts(w, order, now, by)
    val t = ok(Step("2a", s"${by.display} fills the order directly", by, now, Seq(orderSpend(w, order)), Seq(held, bond, principal)))
    val b = out(t, 1)
    Bond(startedAt(w, startOf(b)), order.id, order, b, out(t, 0))
  }

  /**
   * Bob's FI lend offer for exactly the principal: fill fee and refund fee at the floor, the new bond's
   * reserve, and an ERG budget of exactly what one fill may take (the new-bond fill, or a purchase).
   */
  protected def offerBox(w: W, bounds: Bounds, policy: Contract, primary: Boolean, expiry: Long): UTXO = {
    val ergOut = OfferFee + minBox + (if (primary) batchingFee + minBox else minBox)
    val terms = offerTerms(chain, TokenBond, OTerms(batcherFee = OfferFee, reserve = batchingFee, expiry = expiry,
      minFillBps = 10000L, rewardBudget = OfferFee)).updated(11, principalUnits).updated(12, ergOut)
    fiOffer(w, OTerms(), bounds, policy).setValue(ergOut + minBox + OfferFee)
      .setTokens(Token(SigUSD, principalUnits)).withRegNum(5, longsValue(terms: _*))
  }

  protected def postOffer(planned: Int, w: W, bounds: Bounds, policy: Contract, primary: Boolean, expiry: Long): InputUTXO = {
    val now = arrive(planned)
    out(ok(Step("-", "Bob posts an FI lend offer for the principal", bob, now, Seq.empty,
      Seq(offerBox(w, bounds, policy, primary, expiry)))), 0)
  }

  /** Charlie fills the order from the offer: offerFillTx's outputs. Returns the bond and the offer's continuation. */
  protected def fillFromOffer(planned: Int, w: W, order: InputUTXO, offer: InputUTXO, policy: Contract): (Bond, InputUTXO) = {
    val now = arrive(planned)
    val (ws, held, bond, principal) = fillOuts(w, order, now, bob)
    val in = offer.withCtxVar(0.toByte, ErgoValue.of(3)).withCtxVar(1.toByte, liqVar(policy))
    val o = offer.toUTXO
    val price = priceOf(ws)
    val ergOut = batcherFeeOf(ws, o, price) + minBox + batchingFee + ws.terms(1)
    val t = ok(Step("2b", "Charlie fills the order from Bob's lend offer, keeping the fill fee", charlie, now,
      Seq(orderSpend(w, order), in), Seq(offerDelivery(held, in.id), bond, principal, continuationOf(ws, o, in.id, ergOut, price)),
      wallet = false))
    val b = out(t, 1)
    (Bond(startedAt(w, startOf(b)), order.id, order, b, out(t, 0)), out(t, 3))
  }

  /** Pay the next coupon into a fresh pool, or into `pools` topped up. Returns the bond and the pool. */
  protected def pay(card: String, planned: Int, b: Bond, pools: Seq[InputUTXO]): (Bond, InputUTXO) = {
    val now = arrive(planned)
    val k = r9(b.bond)(3)
    val pool = (if (pools.isEmpty) couponOut(b.w, asset = b.asset) else toppedPool(b.w, pools)).setRegs(poolRegs(b.asset): _*)
    val t = ok(Step(card, s"Alice pays coupon $k" + (if (pools.nonEmpty) ", topping up the same coupon box" else ""),
      alice, now, vars(b.bond, Seq(4 -> int(0), 5 -> int(1))) +: pools, Seq(paid(b.w, b.bond, now), pool), cosign = true))
    (b.copy(bond = out(t, 0)), out(t, 1))
  }

  /** The holder's unit box after a transaction: its output at `who` holding the asset. */
  protected def heldBy(t: SignedTransaction, asset: ErgoId, who: Actor): InputUTXO =
    outsOf(t).map(x => InputUTXO(x)).find(x => x.contract == who.tree && x.tokens.exists(_.id == asset)).get

  protected def collect(planned: Int, b: Bond, pools: Seq[InputUTXO], who: Actor): Bond = {
    val now = arrive(planned)
    val coupons = pools.flatMap(_.tokens).filter(_.id == SigUSD).map(_.amount).sum
    val t = ok(Step("4", s"${who.display} collects the coupon box with the bond unit ($coupons raw SigUSD)", who, now,
      pools :+ b.held, Seq(UTXO(who.tree, b.held.value, b.held.tokens), UTXO(who.tree, pools.map(_.value).sum,
        Seq(Token(SigUSD, coupons))))))
    b.copy(held = heldBy(t, b.asset, who))
  }

  protected def repayIn(b: InputUTXO): InputUTXO = vars(b, Seq(1 -> int(0), 2 -> int(1)))

  protected def repay(planned: Int, b: Bond): SignedTransaction = {
    val now = arrive(planned)
    ok(Step("8", "Alice repays early", alice, now, Seq(repayIn(b.bond)), Seq(repaid(b.w, b.bond), returned(b.w, b.bond)),
      cosign = true))
  }

  /** The holder burns the 3 units for the settlement, collecting any coupon box still open. */
  protected def claim(planned: Int, b: Bond, settlement: InputUTXO, who: Actor, pools: Seq[InputUTXO] = Seq.empty): SignedTransaction = {
    val now = arrive(planned)
    val take = settlement.tokens.drop(1) ++ pools.flatMap(_.tokens)
    val merged = take.groupBy(_.id).map { case (id, ts) => Token(id, ts.map(_.amount).sum) }.toSeq
    ok(Step("9", s"${who.display} burns the 3 units for the settlement" + (if (pools.nonEmpty) " and the open coupon box" else ""),
      who, now, Seq(settlement, b.held) ++ pools, Seq(UTXO(who.tree, settlement.value + pools.map(_.value).sum, merged)),
      burn = Seq(Token(b.asset, 3L))))
  }

  protected def liquidationIn(b: Bond): InputUTXO = vars(b.bond, Seq(0 -> liqVar(fi(chain).payout), 1 -> int(0)))

  protected def cancelOffer(planned: Int, left: InputUTXO): SignedTransaction = {
    val now = arrive(planned)
    ok(Step("-", "Bob cancels what is left of his offer", bob, now, Seq(left), Seq(UTXO(bob.tree, left.value, left.tokens)),
      cosign = true))
  }

  /** The schedule's miss height for the next unpaid coupon of `b`. */
  protected def missOf(b: InputUTXO): Long = {
    val st = r9(b)
    val t = r6(b)
    st(1) + t(9) + st(3) * t(10) + t(11)
  }

  /**
   * The first checkpoint at or after `from` where Record breach can land: in [due(k), due(k) + grace],
   * below maturity - 720, before the quiet zone [m - min(720, grace / 2), m) of the next unpaid coupon and
   * so before that coupon's miss height m. `margin` blocks are kept back at the window's end, and `lead`
   * blocks before m (for the cure and the repayment, which must land before m).
   */
  protected def recordable(b: InputUTXO, from: Int, margin: Int, lead: Int = 0): Option[(Long, Int)] = {
    val st = r9(b)
    val t = r6(b)
    val firstDue = st(1) + t(9)
    val count = t(8)
    val miss = missOf(b)
    val quietFrom = if (st(3) < count) miss - math.min(720L, t(11) / 2L) else Long.MaxValue
    (st(4) + 1L until count).iterator.map { k =>
      val cp = firstDue + k * t(10)
      val lo = math.max(cp, from.toLong)
      val hi = math.min(Seq(cp + t(11), st(2) - 721L, quietFrom - 1L).min - margin, miss - lead)
      (k, lo, hi)
    }.collectFirst { case (k, lo, hi) if lo <= hi => (k, lo.toInt) }
  }

  /** The covenant's test, here: is `value - fee` nanoERG below `ratio` of the repayment at `price`? */
  protected def below(collateral: Long, price: Long, ratio: Long): Boolean =
    BigInt(collateral) * 100L * 10000L < BigInt(price) * principalUnits * ratio

  protected def priceIn(pool: InputUTXO): Long = pool.parseReg[Long](0)

  // ─── the four runs ───────────────────────────────────────────────────────

  /** Bond A: covenant, direct fill, coupons 0 and 1 into one box, collect, breach, cure, early repayment, claim. */
  protected def cycleRun(h: Int, price: Long, pricing: () => InputUTXO): Unit = {
    beginRun("cycle", "bond A: covenant below its ratio, direct fill, two coupons, breach, cure, early repayment")
    val coll0 = math.max((BigInt(price) * principalUnits / 100L).toLong, oneErg)
    val cover = (BigInt(coll0) * 1000000L / (BigInt(price) * principalUnits)).toLong
    // 150%, or half as much again as the cover when 1 ERG covers more than 100%: the bond starts a third short.
    val ratio = if (cover * 3L / 2L < 15000L) 15000L else cover * 3L / 2L + 1L
    println(f"  collateral ${coll0 / 1e9}%.4f ERG = $cover bps of the repayment at the oracle price; covenant ratio $ratio bps")
    val w = worldOf(h, h + 800L, ratio, coll0)
    val order = postOrder(h + 1, w)
    var b = fillDirect(h + 2, w, order, bob)
    val s = startOf(b.bond)
    val (b1, p0) = pay("3a", s + 1, b, Seq.empty)
    val (b2, p1) = pay("3b", s + FirstOffset, b1, Seq(p0))
    b = b2
    val usd = usdIn(p1.tokens)
    no(Step("3b", "control: Alice takes the coupon box", alice, s + FirstOffset, Seq(p1),
      Seq(UTXO(alice.tree, p1.value, p1.tokens)), cosign = true))
    no(Step("3b", "control: Alice takes 1 raw SigUSD out of the coupon box, re-creating the rest", alice, s + FirstOffset,
      Seq(p1), Seq(p1.toUTXO.setTokens(Token(SigUSD, usd - 1L)), UTXO(alice.tree, minBox, Seq(Token(SigUSD, 1L)))),
      cosign = true))
    b = collect(s + FirstOffset + 1, b, Seq(p1), bob)
    println(s"  SKIPPED [10] Alice's voluntary top-up: Recollateralize opens at start + 60 = ${s + 60}, but coupon 2 is" +
      s" missed at ${s + FirstOffset + 2 * Period + Grace}; from then the bond is liquidatable and refuses it (and the repayment).")

    // [5] Record breach, at the first checkpoint whose window is still open.
    val planned = s + FirstOffset + 2
    val resumed = isSaved(step + 1)
    val (k, at) = if (resumed) (0L, planned) else {
      val from = if (live) chainHeight() + 1 else planned
      recordable(b.bond, from, if (live) 3 else 0, if (live) 10 else 0).getOrElse(
        sys.error(s"no checkpoint left to record a breach at: the windows close and coupon 2 is missed at ${missOf(b.bond)}"))
    }
    if (!resumed && at > planned) println(s"  the checkpoint $k window: recording at $at")
    val now = arrive(at)
    val pool = pricing()
    val fee = r9(b.bond)(0)
    if (!resumed && !below(b.bond.value - fee, priceIn(pool), ratio))
      sys.error(s"the bond is not below its ratio at the oracle price ${priceIn(pool)}: ERG has risen too far since the order")
    val bounty = math.min(FiStandardSpecBase.RecordBounty, b.bond.value - fee - minBox)
    def recordIn(x: InputUTXO): InputUTXO = vars(x, Seq(6 -> int(0), 8 -> covVar(b.w.covenant)))
    val rec = ok(Step("5", s"Charlie records a breach at checkpoint $k, keeping the bounty", charlie, now,
      Seq(recordIn(b.bond)), Seq(recorded(b.w, b.bond, now, k, now + CureWindow + 8L).subValue(bounty)), data = Seq(pool)))
    b = b.copy(bond = out(rec, 0))
    val kept = r9(b.bond)(4)
    val deadline = r9(b.bond)(5)
    no(Step("5", s"control: a second Record at checkpoint $kept", charlie, now, Seq(recordIn(b.bond)),
      Seq(recorded(b.w, b.bond, now, kept, now + CureWindow + 8L).subValue(bounty)), data = Seq(pool)))

    // [6] Cure: back above the ratio at the oracle price then, with 25% to spare.
    val now2 = arrive(now + 1)
    val pool2 = pricing()
    if (!isSaved(step + 1) && now2 >= deadline) sys.error(s"the cure deadline $deadline has passed")
    val held = b.bond.value - fee
    val target = ((BigInt(priceIn(pool2)) * principalUnits * ratio * 5L + 4L * 1000000L - 1L) / (4L * 1000000L)).toLong
    val add = math.max(target - held, 1L)
    val cure = ok(Step("6", f"Alice cures, adding ${add / 1e9}%.4f ERG", alice, now2,
      Seq(vars(b.bond, Seq(7 -> int(0), 8 -> covVar(b.w.covenant)))),
      Seq(succ(b.bond, r9(b.bond).updated(5, 0L), now2).addValue(add)), data = Seq(pool2), cosign = true))
    b = b.copy(bond = out(cure, 0))
    val rep = repay(now2 + 1, b)
    claim(now2 + 2, b, out(rep, 0), bob)
    endRun()
  }

  /** Bond B: no covenant; an offer fill, no coupon paid, liquidation at coupon 0's miss height, claim, cancel. */
  protected def liquidateRun(h: Int, price: Long): Unit = {
    beginRun("liquidate", "bond B: no covenant, filled from Bob's lend offer, coupon 0 missed, liquidated")
    val w = worldOf(h, h + 200L, 0L, cover150(price))
    val policy = alwaysTrue(chain)
    val bounds = Bounds(couponOf(principalUnits), Coupons, 1000L, Grace.toLong, 1000L)
    val offer = postOffer(h + 1, w, bounds, policy, primary = true, h + 400L)
    val order = postOrder(h + 1, w)
    val (b, left) = fillFromOffer(h + 2, w, order, offer, policy)
    val miss = missOf(b.bond).toInt
    no(Step("7", "control: liquidation one block before coupon 0's miss height", charlie, miss - 1, Seq(liquidationIn(b)),
      Seq(proceeds(b.w, b.bond)), wallet = false))
    def repayAt(at: Int, label: String): Step = Step("8", label, alice, at, Seq(repayIn(b.bond)),
      Seq(repaid(b.w, b.bond), returned(b.w, b.bond)), cosign = true)
    twin(repayAt(miss - 1, "twin: the same repayment one block before the miss height"))
    no(repayAt(miss, "control: Alice repays at the miss height (refused while liquidatable)"))
    val now = arrive(miss)
    val liq = ok(Step("7", "Charlie liquidates from coupon 0's miss height, keeping the reserve", charlie, now,
      Seq(liquidationIn(b)), Seq(proceeds(b.w, b.bond)), wallet = false))
    claim(now + 1, b, out(liq, 0), bob)
    cancelOffer(now + 1, left)
    endRun()
  }

  /** Bond C: direct fill, 1000 shares, 300 to David and 200 to Erin, coupon 0 absorbed, repaid, pulled, claimed. */
  protected def sharesRun(h: Int, price: Long): Unit = {
    beginRun("shares", "bond C: no covenant, Bob's unit fractionalized into 1000 shares, pull after 720 blocks")
    val w = worldOf(h, h + 200L, 0L, cover150(price))
    val order = postOrder(h + 1, w)
    val b = fillDirect(h + 2, w, order, bob)
    val s = startOf(b.bond)
    var now = arrive(s + 1)
    val req = out(ok(Step("12", s"Bob requests the FI share mint ($N shares)", bob, now, Seq(b.held), Seq(requestBox(b.w, b.asset)))), 0)
    now = arrive(s + 2)
    val mint = ok(Step("12", "Charlie runs the share mint", charlie, now, Seq(req),
      Seq(shareBox(b.w, req.id), freshVault(b.w, req, asset = b.asset)), wallet = false))
    val share = req.id
    var vault = out(mint, 1)
    now = arrive(s + 3)
    val split = ok(Step("-", "Bob sends 300 shares to David and 200 to Erin", bob, now, Seq(out(mint, 0)),
      Seq(UTXO(david.tree, minBox, Seq(Token(share, 300L))), UTXO(erin.tree, minBox, Seq(Token(share, 200L))),
        UTXO(bob.tree, minBox, Seq(Token(share, N - 500L))))))
    val (b1, pool) = pay("3a", s + 4, b, Seq.empty)
    now = arrive(s + 5)
    val absorb = ok(Step("13", s"Charlie absorbs the coupon box into the share vault, keeping 1/200 (${cut(usdIn(pool.tokens))} raw SigUSD, ${ergCut(Seq(pool))} nanoERG)",
      charlie, now, Seq(vault.withCtxVar(0.toByte, int(0)), pool), Seq(absorbedAll(vault, Seq(pool)))))
    vault = out(absorb, 0)
    val source = out(repay(s + 6, b1), 0)
    val opens = source.input.getCreationHeight + PullWait
    println(s"  the pull opens at height $opens: $PullWait blocks after the settlement box's creation height ${source.input.getCreationHeight}")
    def pullStep(at: Int, label: String): Step = Step("14", label, charlie, at, Seq(vault.withCtxVar(0.toByte, int(0)), source),
      Seq(pulledBox(vault, source)), burn = Seq(Token(b.asset, 3L)), wallet = false)
    no(pullStep(opens - 1, "control: the pull one block before it opens"))
    now = arrive(opens)
    var pv = out(ok(pullStep(now, "Charlie pulls the settlement into the share vault, keeping the pull fee")), 0)
    def partialStep(at: Int, v: InputUTXO, shares: InputUTXO, burned: Long, who: Actor): Step = {
      val outstanding = outstandingOf(v)
      val assets = v.tokens.drop(1)
      val paidErg = part(v.value, burned, outstanding)
      val paidTokens = assets.map(t => t.id -> part(t.amount, burned, outstanding)).filter(_._2 > 0L).map { case (i, a) => Token(i, a) }
      val kept = assets.map(t => t.id -> (t.amount - part(t.amount, burned, outstanding))).filter(_._2 > 0L).map { case (i, a) => Token(i, a) }
      Step("15", s"${who.display} claims with $burned shares", who, at,
        Seq(v.withCtxVar(0.toByte, int(1)).withCtxVar(1.toByte, int(0)), shares),
        Seq(tagged(who.tree, paidErg, v.id, paidTokens),
          UTXO(v.contract, v.value - paidErg, v.tokens.head +: kept,
            Seq(v.registers.head, longsValue(N, outstanding - burned, pullFee)) ++ v.registers.drop(2))),
        burn = Seq(Token(share, burned)))
    }
    now = arrive(opens + 1)
    pv = out(ok(partialStep(now, pv, out(split, 0), 300L, david)), 1)
    now = arrive(opens + 2)
    pv = out(ok(partialStep(now, pv, out(split, 1), 200L, erin)), 1)
    now = arrive(opens + 3)
    ok(Step("15", s"Bob claims the rest with his ${outstandingOf(pv)} shares, burning the reserve", bob, now,
      Seq(pv.withCtxVar(1.toByte, int(0)), out(split, 2)), Seq(tagged(bob.tree, pv.value, pv.id, pv.tokens.drop(1))),
      burn = Seq(Token(share, outstandingOf(pv) + N + 1L))))
    endRun()
  }

  /** Bond Y: Erin fills and sells the unit through a ZC sale order; Charlie matches Bob's offer to it; repaid. */
  protected def saleRun(h: Int, price: Long): Unit = {
    beginRun("sale", "bond Y: no covenant, Erin fills, sells the unit to Bob's lend offer, repaid early")
    val w = worldOf(h, h + 200L, 0L, cover150(price))
    val order = postOrder(h + 1, w)
    var b = fillDirect(h + 2, w, order, erin)
    val s = startOf(b.bond)
    var now = arrive(s + 1)
    val ask = out(ok(Step("-", "Erin posts a ZC sale order for her unit at the principal", erin, now, Seq(b.held),
      Seq(saleBox(b.w, b.asset, principalUnits).withRegNum(4, bytesValue(hashOf(erin.tree)))))), 0)
    val policy = alwaysTrue(chain)
    val offer = postOffer(s + 1, b.w, tightBounds(b.w), policy, primary = false, s + 400L)
    now = arrive(s + 2)
    // buyTx's inputs and outputs: 0 the ask, 1 the offer; 0 payout, 1 delivery, 2 continuation; the bond as data.
    val askIn = ask.withCtxVar(0.toByte, ErgoValue.of(0))
    val in = offer.withCtxVar(0.toByte, ErgoValue.of(2)).withCtxVar(1.toByte, liqVar(policy))
      .withCtxVar(2.toByte, ErgoValue.of(0)).withCtxVar(3.toByte, ErgoValue.of(0))
      .withCtxVar(4.toByte, bytesValue(b.origin.bytes))
      .withCtxVar(5.toByte, ErgoValue.of(1)).withCtxVar(7.toByte, ErgoValue.of(0))
    val o = offer.toUTXO
    val paidUnits = principalUnits
    val ergOut = batcherFeeOf(b.w, o, paidUnits) + minBox + minBox
    val matched = ok(Step("16", "Charlie matches Bob's offer against Erin's sale order", charlie, now, Seq(askIn, in),
      Seq(payoutBox(b.w, askIn.id, paidUnits).setContract(erin.tree),
        offerDelivery(UTXO(bob.tree, minBox, Seq(Token(b.asset, 1L))), in.id),
        continuationOf(b.w, o, in.id, ergOut, paidUnits)), data = Seq(b.bond), wallet = false))
    b = b.copy(held = out(matched, 1))
    val rep = repay(s + 3, b)
    claim(s + 4, b, out(rep, 0), bob)
    cancelOffer(s + 4, out(matched, 2))
    endRun()
  }

  /** Which optional actors a run needs. */
  protected val Needs: Map[String, Seq[String]] = Map("cycle" -> Seq.empty, "liquidate" -> Seq.empty,
    "shares" -> Seq("DAVID", "ERIN"), "sale" -> Seq("ERIN"))

  /** The runs in order; signing only, each is planned from where the last one ended. */
  protected def runs(h: Int, price: Long, pricing: () => InputUTXO): Seq[(String, () => Unit)] = {
    def base: Int = if (live) h else math.max(h, highest)
    Seq(
      "cycle" -> (() => cycleRun(base, price, pricing)),
      "liquidate" -> (() => liquidateRun(base, price)),
      "shares" -> (() => sharesRun(base, price)),
      "sale" -> (() => saleRun(base, price)))
  }
}

/**
 * The FI Standard runs against a real node: every transaction of four bond lifecycles, built from the
 * real wallets and the real oracle box and signed, WITHOUT submitting. Later transactions spend the
 * outputs of earlier signed ones; heights still in the future are pinned.
 *
 *   sbt "Test/runMain org.lithosprotocol.sigmafi.specs.bonds.FiLive <path to .env> [principal raw units, default 100]"
 *
 * The .env supplies NODE_URL, the BORROWER/LENDER/KEEPER mnemonics and expected addresses, and
 * optionally DAVID_MNEMONIC and ERIN_MNEMONIC (with optional *_EXPECTED_ADDRESS). Signing only, a missing
 * David or Erin is derived (EIP-3 index 1 of LENDER, of KEEPER) and reported; where a wallet lacks ERG or
 * SigUSD a stand-in box is used and reported.
 *
 * SUBMITTING, which spends real funds, is a separate mode for one run at a time:
 *
 *   ... FiLive <path to .env> <principal> submit <cycle|liquidate|shares|sale> <state directory>
 *
 * It refuses to start if the run needs a missing actor or, signed first without sending, any stand-in
 * box. Each transaction is signed, saved to the state directory, sent, and waited on until its first input
 * is spent by it in a block (spent by another transaction: STOP). Steps wait for the heights they need.
 * Started again with the same state directory it picks up where it stopped, re-sending a saved
 * transaction that never landed. The controls stay sign-only.
 */
object FiLive extends FiLiveFlows {
  import ZcStandardSpecBase._

  private var env: Map[String, String] = Map.empty
  private var client: ErgoClient = _
  private var submitting: Boolean = false
  private var stateDir: java.io.File = _

  override protected def live: Boolean = submitting

  private def fresh(): BlockchainContext =
    client.execute(new java.util.function.Function[BlockchainContext, BlockchainContext] {
      def apply(c: BlockchainContext): BlockchainContext = c
    })

  private def proverOf(ctx: BlockchainContext, key: String, index: Int): ErgoProver = ctx.newProverBuilder()
    .withMnemonic(Mnemonic.create(env(key + "_MNEMONIC").toCharArray, Array.emptyCharArray), false)
    .withEip3Secret(index).build()

  // The base trait's fixed test keys, replaced by the real wallets so the builders make real boxes.
  override protected def borrower(ctx: BlockchainContext): ErgoProver = alice.prover
  override protected def lender(ctx: BlockchainContext): ErgoProver = bob.prover
  override protected def executor(ctx: BlockchainContext): ErgoProver = charlie.prover
  override protected def borrowerTree(ctx: BlockchainContext): Contract = alice.tree
  override protected def lenderTree(ctx: BlockchainContext): Contract = bob.tree

  override protected def signer(c: BlockchainContext, a: Actor): ErgoProver = proverOf(c, a.key, a.index)
  override protected def txCtx(): BlockchainContext = fresh()
  override protected def chainHeight(): Int = nodeHeight()

  // ─── node ────────────────────────────────────────────────────────────────

  private def http(path: String, body: String = null): String = {
    val c = new java.net.URL(env("NODE_URL") + path).openConnection().asInstanceOf[java.net.HttpURLConnection]
    c.setConnectTimeout(10000); c.setReadTimeout(30000)
    if (body != null) {
      c.setRequestMethod("POST"); c.setRequestProperty("Content-Type", "application/json"); c.setDoOutput(true)
      val os = c.getOutputStream
      try os.write(body.getBytes("UTF-8")) finally os.close()
    }
    val is = if (c.getResponseCode >= 400) c.getErrorStream else c.getInputStream
    val s = scala.io.Source.fromInputStream(is)
    try s.mkString finally s.close()
  }

  private def boxIds(json: String): Seq[String] =
    """"boxId"\s*:\s*"([0-9a-f]{64})"""".r.findAllMatchIn(json).map(_.group(1)).toSeq.distinct

  private def walletBoxes(ctx: BlockchainContext, a: Actor): Seq[InputUTXO] = {
    val ids = boxIds(http("/blockchain/box/unspent/byAddress?offset=0&limit=100", "\"" + a.address + "\""))
    if (ids.isEmpty) Seq.empty else ctx.getBoxesById(ids: _*).toSeq.map(b => InputUTXO(b))
  }

  private def realOracle(ctx: BlockchainContext): InputUTXO = {
    val ids = boxIds(http(s"/blockchain/box/unspent/byTokenId/$OracleNFT?offset=0&limit=5"))
    require(ids.size == 1, s"expected one oracle pool box, found ${ids.size}")
    InputUTXO(ctx.getBoxesById(ids.head).head)
  }

  private def nodeHeight(): Int =
    """"fullHeight"\s*:\s*(\d+)""".r.findFirstMatchIn(http("/info")).map(_.group(1).toInt).getOrElse(sys.error("no height from the node"))

  private def pending(id: String): Boolean =
    Try(http(s"/transactions/unconfirmed/byTransactionId/$id").contains(id)).getOrElse(false)

  /** The id of the transaction that spent `boxId` in a block, if any (the node's indexer). */
  private def spentBy(boxId: String): Option[String] =
    Try(""""spentTransactionId"\s*:\s*"([0-9a-f]{64})"""".r.findFirstMatchIn(http(s"/blockchain/box/byId/$boxId"))
      .map(_.group(1))).toOption.flatten

  /** Submitting: waits until a transaction built now lands at or after `planned`; returns the node height. */
  override protected def arrive(planned: Int): Int = if (!live) planned else {
    var h = nodeHeight()
    var waited = 0
    while (h < planned - 1) {
      val left = planned - 1 - h
      if (left <= 30 || waited % 15 == 0) println(s"        height $h, waiting for ${planned - 1} (about ${left * 2} minutes)")
      Thread.sleep(60000L); waited += 1; h = nodeHeight()
    }
    h
  }

  private def stateFile(n: Int): java.io.File = new java.io.File(stateDir, f"$n%02d.json")

  override protected def isSaved(n: Int): Boolean = live && stateFile(n).exists

  override protected def saved(n: Int): Option[SignedTransaction] =
    if (!isSaved(n)) None else {
      val src = scala.io.Source.fromFile(stateFile(n))
      try Some(fresh().signedTxFromJson(src.mkString)) finally src.close()
    }

  override protected def keep(n: Int, signed: SignedTransaction): Unit = {
    val w = new java.io.PrintWriter(stateFile(n))
    try w.write(signed.toJson(false)) finally w.close()
  }

  /**
   * Settled when the transaction's first input is spent by it in a block. Sent unless that is so or the
   * node already holds it; STOP if that input was spent by another transaction.
   */
  override protected def land(signed: SignedTransaction, label: String): Unit = {
    val id = signed.getId.replace("\"", "")
    val witness = signed.getSignedInputs.get(0).getId.toString
    def settled(): Boolean = spentBy(witness) match {
      case Some(t) if t == id => true
      case Some(t) if sameOutputs(signed, t) =>
        if (!nodeId.contains(id)) println(s"        the node's id for this transaction is $t (computed here as $id; outputs identical)")
        nodeId += id -> t
        true
      case Some(t) => sys.error(s"$label: STOP. Its first input $witness was spent by another transaction, $t, not by $id")
      case None => false
    }
    if (!settled() && !pending(id)) {
      Try(fresh().sendTransaction(signed)) match {
        case Success(_) => println(s"        sent $id")
        case Failure(e) => println(s"        not sent again (${first(e).take(80)}); waiting on its first input")
      }
    }
    var tries = 0
    while (!settled()) {
      tries += 1
      if (tries > 120) sys.error(s"$label: $id not in a block after an hour; run again with the same state directory")
      Thread.sleep(30000L)
    }
    println(s"        in a block: ${nodeId.getOrElse(id, id)} (its first input $witness is spent by it)")
  }

  /**
   * The node orders context variables its own way, so a transaction carrying two of them can get an id
   * there that differs from the one computed here. It is the same transaction if every output, rebuilt
   * under the node's id, has exactly the box id the node reports for that output.
   */
  private def sameOutputs(signed: SignedTransaction, other: String): Boolean = Try {
    val theirs = boxIds(http(s"/blockchain/transaction/byId/$other").split("\"outputs\"")(1))
    val made = signed.getOutputsToSpend.asScala.toSeq
    val ours = made.zipWithIndex.map { case (b, i) =>
      InputUTXO(b).toUTXO.setCreationHeight(b.getCreationHeight).toInput(fresh(), ErgoId.create(other), i.toShort).id.toString
    }
    ours.nonEmpty && ours == theirs.take(ours.size) && theirs.size == ours.size
  }.getOrElse(false)

  // ─── main ────────────────────────────────────────────────────────────────

  private val usage = "submit mode: <path to .env> <principal raw units> submit <cycle|liquidate|shares|sale> <state directory>"

  def main(args: Array[String]): Unit = {
    require(args.nonEmpty, "usage: FiLive <path to .env> [principal in raw SigUSD units, default 100]")
    val src = scala.io.Source.fromFile(args(0))
    env = try src.getLines().map(_.trim).filter(l => l.nonEmpty && !l.startsWith("#") && l.indexOf('=') > 0)
      .map(l => l.take(l.indexOf('=')).trim -> l.drop(l.indexOf('=') + 1).trim.stripPrefix("\"").stripSuffix("\"")).toMap
    finally src.close()
    val principal = if (args.length > 1) args(1).toLong else 100L
    val which = if (args.length > 2) {
      require(args.length == 5 && args(2) == "submit" && Needs.contains(args(3)), usage)
      submitting = true
      stateDir = new java.io.File(args(4))
      stateDir.mkdirs()
      Some(args(3))
    } else None
    client = RestApiErgoClient.create(env("NODE_URL"), NetworkType.MAINNET, "",
      RestApiErgoClient.getDefaultExplorerUrl(NetworkType.MAINNET))
    sys.exit(run(fresh(), principal, which))
  }

  private def actorOf(ctx: BlockchainContext, role: String, display: String): Actor = {
    val fallback = Map("DAVID" -> "LENDER", "ERIN" -> "KEEPER")
    if (env.contains(role + "_MNEMONIC")) {
      val prover = proverOf(ctx, role, 0)
      val address = prover.getEip3Addresses.get(0)
      val expected = env.get(role + "_EXPECTED_ADDRESS")
      if (fallback.contains(role)) require(expected.forall(_ == address.toString), s"$role mnemonic does not give its expected address")
      else require(expected.contains(address.toString), s"$role mnemonic does not give its expected address")
      Actor(role, display, role, 0, prover, address)
    } else {
      require(fallback.contains(role), s"${role}_MNEMONIC is missing")
      val prover = proverOf(ctx, fallback(role), 1)
      Actor(role, display, fallback(role), 1, prover, prover.getEip3Addresses.get(0), standIn = true)
    }
  }

  private def run(ctx: BlockchainContext, principal: Long, which: Option[String]): Int = {
    chain = ctx
    principalUnits = principal
    actors = Roles.map { case (role, display) => role -> actorOf(ctx, role, display) }.toMap
    actors.values.filter(_.standIn).toSeq.sortBy(_.role).foreach { a =>
      println(s"STAND-IN ACTOR ${a.tag}: no ${a.role}_MNEMONIC; derived from ${a.key} at EIP-3 index 1")
    }
    val missing = which.toSeq.flatMap(Needs).filter(r => actors(r).standIn)
    if (live && missing.nonEmpty) {
      println(s"Not submitting the ${which.get} run: it needs ${missing.mkString(" and ")}, missing from the .env (${missing.map(_ + "_MNEMONIC").mkString(", ")}).")
      return 1
    }
    // A submitted run keeps the height and price it was planned at, so a second start rebuilds the same terms.
    val params = if (live) new java.io.File(stateDir, "params") else null
    val keptParams = if (live && params.exists) {
      val src = scala.io.Source.fromFile(params)
      try Some(src.mkString.trim.split(' ').map(_.toLong)) finally src.close()
    } else None
    val oracle = realOracle(ctx)
    val h = keptParams.map(_(0).toInt).getOrElse(nodeHeight())
    val price = keptParams.map(_(1)).getOrElse(oracle.parseReg[Long](0))
    require(keptParams.forall(_(2) == principal), "this state directory was started with another principal")
    if (live && keptParams.isEmpty) {
      val w = new java.io.PrintWriter(params)
      try w.write(s"$h $price $principal") finally w.close()
    }
    val age = nodeHeight() - oracle.input.getCreationHeight
    println(if (live) s"SUBMITTING the ${which.get} run to mainnet. Planned at height $h, state in $stateDir."
            else s"SIGN-ONLY, nothing is submitted. Node height $h, mainnet.")
    println(f"Oracle pool box ${oracle.id.toString.take(12)}…: $price nanoERG per USD (1 ERG = ${1e9 / price}%.4f USD), $age blocks old")
    println(s"Principal $principal raw SigUSD units (${principal / 100.0} SigUSD); repayment the same; coupons ${couponOf(principal)} each, 3 of them")
    println("Actors: ALICE = BORROWER, BOB = LENDER, CHARLIE = KEEPER, DAVID, ERIN")
    println("\nWallets (real boxes on chain; boxes holding any token but SigUSD are left alone):")
    Roles.foreach { case (role, _) =>
      val a = actors(role)
      val boxes = walletBoxes(ctx, a)
      purse += role -> boxes
      val usable = boxes.filter(plain)
      println(f"  ${a.tag}%-8s ${boxes.size}%3d boxes, ${usable.map(_.value).sum / 1e9}%.4f ERG and ${usable.map(b => usdIn(b.tokens)).sum}%d SigUSD units usable" +
        f" (${boxes.size - usable.size} hold other tokens)")
      println(s"FIHAVE|${a.tag}|ERG|${usable.map(_.value).sum}")
      println(s"FIHAVE|${a.tag}|SIGUSD|${usable.map(b => usdIn(b.tokens)).sum}")
    }
    val pricing: () => InputUTXO = () => if (live) realOracle(fresh()) else oracle
    val chosen = runs(h, price, pricing).filter(r => which.forall(_ == r._1))

    // Submitting a fresh run: sign it first without sending; any stand-in box means a wallet is short.
    val resumed = live && stateFile(1).exists
    if (resumed) println("\nResuming a started run: the preflight is skipped.")
    if (live && !resumed) {
      println("\nPREFLIGHT: the run signed without sending, from the wallets as they are")
      val wallets = purse
      submitting = false
      val dry = Try(chosen.head._2())
      submitting = true
      val short = standIns
      purse = wallets
      standIns = Seq.empty
      highest = 0
      if (dry.isFailure || short.nonEmpty || failures.nonEmpty) {
        dry.failed.foreach(e => println(s"  preflight stopped: ${first(e)}"))
        short.foreach(s => println(s"  short: $s"))
        failures.foreach(f => println(s"  problem: $f"))
        println("  Not submitting: the wallets above must hold these amounts themselves, and every step must sign.")
        return 1
      }
      println("\nPREFLIGHT clean. SUBMITTING.")
    }
    allowStandIns = !live

    val done = chosen.map { case (name, body) =>
      Try(body()) match {
        case Success(_) => name -> true
        case Failure(e) =>
          if (!failures.exists(_.contains(first(e)))) failures :+= s"$name: ${first(e)}"
          println(s"  run stopped: ${e.getMessage}")
          name -> false
      }
    }
    println("\nSUMMARY")
    done.foreach { case (name, good) => println(s"  ${if (good) "completed" else "STOPPED  "}  $name") }
    failures.distinct.foreach(f => println(s"  problem: $f"))
    if (standIns.nonEmpty) println(s"  stand-in boxes: ${standIns.mkString("; ")}")
    actors.values.filter(_.standIn).foreach(a => println(s"  stand-in actor: ${a.tag} (EIP-3 index 1 of ${a.key})"))
    println(if (live) s"  Transactions are saved in $stateDir." else "  Nothing was submitted.")
    if (failures.isEmpty && done.forall(_._2)) 0 else 1
  }
}
