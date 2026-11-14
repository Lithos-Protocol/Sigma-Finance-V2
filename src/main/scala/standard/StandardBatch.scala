package ksingh.sigmabonds
package standard

import ksingh.sigmabonds.app.Protocol._
import ksingh.sigmabonds.app._
import org.ergoplatform.appkit.ErgoValue
import work.lithos.mutations.{Contract, InputUTXO, UTXO}

import scala.collection.mutable
import scala.util.Try

/**
 * One piece of batchable work: what it does, the CLI command that does it alone, the nanoERG it pays whoever
 * runs it (before the miner fee), and the boxes it spends, so a batch run never spends a box twice. A refund
 * or a liquidation also names its `packBox`: such work shares a transaction with others through `pack`.
 */
case class Work(summary: String, command: String, reward: Long, spends: Set[String], packBox: Option[String] = None) {
  def packable: Boolean = packBox.isDefined
}

/** A scan's work, and what it passed over because no batcher can serve it, with why. */
case class BatchScan(work: Seq[Work], skipped: Seq[String])

/**
 * Part of a packed transaction: one protocol input, given the position of its first output, the outputs it
 * names, the fee it leaves the runner, and the oracle box it reads as data input 0, if any.
 */
case class Piece(boxId: String, input: Int => InputUTXO, outputs: Seq[UTXO], reward: Long, note: String,
                 oracle: Option[OracleState] = None)

/**
 * Everything in the standard set that waits on someone other than its owner: share mints and pulls, expiry
 * refunds, liquidations, and lend offers that some order or ask would satisfy, each paying its runner a fee
 * the poster committed. It follows the zcs1 batching rules: refunds and liquidations pack into shared
 * transactions, offers take the source that gives them the best yield, and boxes that do not disclose the
 * scripts they name are passed over.
 */
trait StandardBatch extends AppBase { self: StandardApp =>

  import StandardBatch._
  import StandardProtocol._

  /** The USD oracle pool's state, read once per run. */
  private[standard] lazy val oracleNow: Option[OracleState] = Try(oracle()).toOption.flatten

  // ═══ PIECES ═══════════════════════════════════════════════════════════════

  private[standard] def orderRefund(order: StdOrder): Piece = {
    if (height.toLong < order.expiry) fail(s"the order expires at height ${order.expiry}; it can be refunded from then on")
    if (order.expiryFee < MinBatchingFee || order.expiryFee > order.box.value - MinBox)
      fail("the order's expiry fee is outside its contract's bounds, so only its borrower can cancel it")
    val refund = UTXO(Contract(borrowerTree(order.borrowerHash, order.box)), order.box.value - order.expiryFee,
      order.collateral, Seq(bytesValue(order.box.id.getBytes)))
    Piece(order.box.id.toString, i => order.box.withCtxVar(0.toByte, intValue(i)), Seq(refund), order.expiryFee,
      s"returns ${Fmt.contents(refund.value, refund.tokens)} to the borrower of order ${order.box.id}, for its " +
        s"${Fmt.erg(order.expiryFee)} expiry fee")
  }

  private[standard] def offerRefund(o: StdOffer): Piece = {
    if (height.toLong < o.expiry) fail(s"the offer expires at height ${o.expiry}; it can be refunded from then on")
    if (o.batcherFee < 0L || o.batcherFee > o.box.value - MinBox) fail("the offer's fee leaves no box minimum: only its owner can cancel it")
    val refund = UTXO(Contract(ownerTree(o.ownerHash, o.box, "offer owner")), o.box.value - o.batcherFee, o.box.tokens,
      Seq(bytesValue(o.box.id.getBytes)))
    Piece(o.box.id.toString, i => o.box.withCtxVar(0.toByte, intValue(i)), Seq(refund), o.batcherFee,
      s"returns ${Fmt.contents(refund.value, refund.tokens)} to the owner of offer ${o.box.id}, for its " +
        s"${Fmt.erg(o.batcherFee)} batcher fee")
  }

  private[standard] def askRefund(ask: StdAsk): Piece = {
    if (height.toLong < ask.expiry) fail(s"the ask expires at height ${ask.expiry}; it can be refunded from then on")
    val refund = UTXO(Contract(ownerTree(ask.sellerHash, ask.box, "seller")), math.min(MinBox, ask.box.value),
      Seq(ask.token), Seq(bytesValue(ask.box.id.getBytes)))
    Piece(ask.box.id.toString, i => ask.box.withCtxVar(0.toByte, intValue(i)), Seq(refund), ask.box.value - refund.value,
      s"returns ${ask.token.amount} of ${ask.token.id} to the seller of ask ${ask.box.id}, for " +
        Fmt.erg(ask.box.value - refund.value))
  }

  /**
   * A bond's liquidation: its rule from context var 0, its vault output named in var 1. An oracle-rule bond
   * before maturity reads the oracle pool as data input 0. Its fee reserve goes to the runner.
   */
  private[standard] def liquidation(b: StdBond): Piece = {
    val rule = protocol.ruleOf(b.liqHash).getOrElse(fail(s"bond ${b.box.id} names a liquidation expression this client does not know"))
    val matured = height.toLong >= b.maturity
    val reads = rule match {
      case AtMaturity(_) if !matured => fail(s"bond ${b.box.id} matures at height ${b.maturity}; it can be liquidated from then on")
      case OracleRatio(_, _) if !matured =>
        if (b.payload.size < 2) fail(s"bond ${b.box.id} has no oracle terms; it can be liquidated from maturity, height ${b.maturity}")
        val price = readableOracle(oracleNow.getOrElse(fail("the node's index has no single USD oracle pool box")))
        val (ratio, age) = (b.payload(0), b.payload(1))
        if (height.toLong + 1L - price.created > age)
          fail(s"the oracle price is from height ${price.created}, older than bond ${b.box.id} accepts ($age blocks)")
        val below = protocol.cover(b, price.price).exists { case (c, d) => c * 10000 < d * ratio }
        if (!below) fail(s"bond ${b.box.id} is at ${protocol.ratioBps(b, price.price).fold("?")(Units.percent)}, " +
          s"not below its ${Units.percent(ratio)}; it can be liquidated at maturity, height ${b.maturity}")
        Some(price)
      case _ => None
    }
    val vault = UTXO(protocol.contracts.vault, b.box.value - b.feeReserve, b.box.tokens,
      Seq(bytesValue(b.box.id.getBytes), ErgoValue.of(1.toByte)))
    Piece(b.box.id.toString,
      i => b.box.withCtxVar(0.toByte, bytesValue(rule.expression.valueBytes)).withCtxVar(1.toByte, intValue(i)),
      Seq(vault), b.feeReserve, s"liquidates bond ${b.box.id} (${b.market}), for its ${Fmt.erg(b.feeReserve)} fee reserve", reads)
  }

  /**
   * Several pieces in one transaction: inputs in order, each naming its own outputs, one miner fee, and the
   * fees left over to this wallet. Each contract reads only its own context variables and outputs, so pieces
   * of different kinds mix.
   */
  private[standard] def packed(title: String, pieces: Seq[Piece]): Prepared = {
    if (pieces.isEmpty) fail("nothing to do")
    if (pieces.map(_.boxId).distinct.size != pieces.size) fail("a box is named twice")
    if (pieces.size > PackLimit) fail(s"pack at most $PackLimit boxes into one transaction")
    val starts = pieces.scanLeft(0)(_ + _.outputs.size)
    val inputs = pieces.zip(starts).map { case (p, at) => p.input(at) }
    val price = pieces.flatMap(_.oracle).headOption
    prepare(title, plan(inputs, pieces.flatMap(_.outputs), dataInputs = price.map(_.box).toSeq),
      pieces.map(_.note) ++
        price.map(o => s"oracle price: ${o.price} nanoERG per USD, from height ${o.created}") ++
        Seq(s"you keep ${Fmt.erg(pieces.map(_.reward).sum)} in fees, less the miner fee"))
  }

  /** Refund expired orders, offers and asks and liquidate bonds, any mix, in one transaction. */
  def pack(boxIds: Seq[String]): Prepared = {
    if (boxIds.isEmpty) fail("name at least one box id")
    val pieces = boxIds.distinct.map { id =>
      val box = unspent(id)
      protocol.asOrder(box).map(orderRefund)
        .orElse(protocol.asOffer(box).map(offerRefund))
        .orElse(protocol.asAsk(box).map(askRefund))
        .orElse(protocol.asBond(box).map(liquidation))
        .getOrElse(fail(s"$id is not a ZC Standard order, offer, ask or bond"))
    }
    packed(s"Pack ${pieces.size} refund(s) and liquidation(s)", pieces)
  }

  // ═══ SCAN ═════════════════════════════════════════════════════════════════

  def batchable(): Seq[Work] = batchScan().work

  /**
   * Every piece of work worth running now. Refunds and liquidations pack together, so each is listed when it
   * pays anything; everything else must pay more than the miner fee alone. A box whose creating transaction
   * does not disclose the script it names is passed over and reported.
   */
  def batchScan(): BatchScan = {
    val work = mutable.ArrayBuffer.empty[Work]
    val skipped = mutable.LinkedHashMap.empty[String, String]
    // Paths open from a height count at the signing height; ones open before it, at the next block.
    val now = height.toLong
    val next = now + 1L
    // While a refresh in the mempool spends the oracle box, nothing that reads it can be built.
    val usableOracle = oracleNow.filterNot(_.refreshing)
    if (oracleNow.exists(_.refreshing))
      skipped("oracle") = "offer matches and early oracle liquidations: the oracle pool is being refreshed; next block"
    def alone(fee: Long) = fee > minerFee
    def served(hash: Array[Byte], box: InputUTXO, what: String, who: String): Boolean = {
      val ok = discloses(hash, box)
      if (!ok) skipped(box.id.toString) = s"$what ${box.id}: does not disclose its $who's script (zcs1); only the $who can act on it"
      ok
    }
    def packWork(summary: String, command: String, reward: Long, box: String) =
      Work(summary, command, reward, Set(box), Some(box))

    shareRequests().filter(r => alone(r.mintFee) && served(r.ownerHash, r.box, "share request", "owner")).foreach { r =>
      work += Work(s"mint ${r.shares} shares of bond ${r.bondAsset}" + r.dutch.fold("")(_ => " (Dutch auction)"),
        s"mint-shares ${r.box.id}", r.mintFee, Set(r.box.id.toString))
    }
    val settled = settlements().map(s => s.asset -> s).toMap
    shareVaults().filter(v => v.holding && settled.contains(v.bondAsset) && alone(v.pullFee)).foreach { v =>
      work += Work(s"pull the settled bond ${v.bondAsset} into its share vault", s"pull ${v.box.id}", v.pullFee,
        Set(v.box.id.toString, settled(v.bondAsset).box.id.toString))
    }

    val liveBonds = self.bonds()
    liveBonds.foreach { b =>
      val rule = protocol.ruleOf(b.liqHash)
      val due = rule.isDefined && (now >= b.maturity || ((rule, usableOracle) match {
        case (Some(OracleRatio(_, _)), Some(o)) if b.payload.size >= 2 =>
          next - o.created <= b.payload(1) && protocol.cover(b, o.price).exists { case (c, d) => c * 10000 < d * b.payload(0) }
        case _ => false
      }))
      if (due && b.feeReserve > 0L)
        work += packWork(s"liquidate bond ${b.box.id} (${b.market})", s"liquidate ${b.box.id}", b.feeReserve, b.box.id.toString)
    }

    val openOrders = orders()
    openOrders.filter(o => now >= o.expiry && o.expiryFee >= MinBatchingFee && o.expiryFee <= o.box.value - MinBox &&
      served(o.borrowerHash, o.box, "order", "borrower")).foreach { o =>
      work += packWork(s"return expired order ${o.box.id} to its borrower", s"refund-order ${o.box.id}", o.expiryFee, o.box.id.toString)
    }
    val allOffers = offers()
    allOffers.filter(o => now >= o.expiry && o.batcherFee > 0L && o.batcherFee <= o.box.value - MinBox &&
      served(o.ownerHash, o.box, "lend offer", "owner")).foreach { o =>
      work += packWork(s"return expired offer ${o.box.id} to its owner", s"refund-offer ${o.box.id}", o.batcherFee, o.box.id.toString)
    }
    val openAsks = asks()
    openAsks.filter(a => now >= a.expiry && a.box.value > MinBox && served(a.sellerHash, a.box, "ask", "seller")).foreach { a =>
      work += packWork(s"return expired ask ${a.box.id} to its seller", s"refund-ask ${a.box.id}", a.box.value - MinBox,
        a.box.id.toString)
    }

    // Matches. Offers go by batcher fee, highest first, then box id; each takes the order or ask that gives it
    // the highest yield, then the lower box id. A source goes to one offer per scan.
    val orderSources = openOrders.filter(order => next < order.expiry && Try(checkOrder(order)).isSuccess &&
      served(order.borrowerHash, order.box, "order", "borrower"))
    val askSources = openAsks.filter(a => next < a.expiry && served(a.sellerHash, a.box, "ask", "seller"))
    val taken = mutable.Set.empty[String]
    allOffers.filter(o => next < o.expiry && alone(o.batcherFee) && served(o.ownerHash, o.box, "lend offer", "owner"))
      .sortBy(o => (-o.batcherFee, o.box.id.toString)).foreach { o =>
        val fromOrders = orderSources.filter(x => x.market == o.market && !taken(x.box.id.toString)).flatMap { original =>
          val order = resolveOrder(original, height.toLong)
          val maturity = if (order.onClose) height.toLong + order.terms(3) else order.terms(3)
          val bond = prospective(order, height.toLong, maturity, o.feeReserve)
          if (offerRefusal(o, bond, order.principal, 1L, 1L, usableOracle).nonEmpty ||
            guardRefusal(order, bond, usableOracle).nonEmpty) None
          else Some((impliedYield(o, order.repayment, order.principal, 1L, 1L, maturity), order.box.id.toString,
            Work(s"fill order ${order.box.id} from offer ${o.box.id}", s"match-offer ${o.box.id} --order ${order.box.id}",
              o.batcherFee, Set(o.box.id.toString, order.box.id.toString))))
        }
        val fromAsks = askSources.filter(a => a.market == o.market && !taken(a.box.id.toString)).flatMap { a =>
          Try[Option[(BigInt, String, Work)]] {
            val (asset, whole) = bondOf(a.token.id).get
            val units = if (whole == 1L) 1L else a.token.amount
            val bond = liveBonds.find(_.asset == asset).get
            val price = if (o.market.token.isEmpty) math.max(askPrice(a, units)._1, MinBox) else askPrice(a, units)._1
            if (units < a.minFill && units != a.token.amount) None
            else if (offerRefusal(o, bond, price, units, whole, usableOracle).nonEmpty ||
              saleGuardRefusal(a, bond, usableOracle).nonEmpty) None
            else Some((impliedYield(o, bond.repayment, price, units, whole, bond.maturity), a.box.id.toString,
              Work(s"buy $units of ${a.token.id} from ask ${a.box.id} with offer ${o.box.id}",
                s"match-offer ${o.box.id} --ask ${a.box.id}" + (if (units != a.token.amount) s" --amount $units" else ""),
                o.batcherFee, Set(o.box.id.toString, a.box.id.toString))))
          }.toOption.flatten.toSeq
        }
        (fromOrders ++ fromAsks).sortBy { case (y, id, _) => (-y, id) }.headOption.foreach { case (_, id, w) =>
          work += w
          taken += id
        }
      }
    BatchScan(work.result(), skipped.values.toSeq)
  }

  /**
   * The simple yield a purchase gives the offer, in basis points a year times 1,000,000, for ranking: R for
   * `units` of `whole` against the cost, the price plus the batcher fee on an ERG offer, as its own test reads it.
   */
  private[standard] def impliedYield(o: StdOffer, repayment: Long, price: Long, units: Long, whole: Long,
                                     maturity: Long): BigInt = {
    val cost = (if (o.market.token.isEmpty) BigInt(price) + o.batcherFee else BigInt(price)) * whole
    val left = math.max(maturity - (height.toLong + 1L), 1L)
    (BigInt(repayment) * units - cost) * BigInt(2628000000L) * 1000000 / (cost * left)
  }

  // ═══ RUN ══════════════════════════════════════════════════════════════════

  /**
   * Run the work, in rounds. Each round scans again, packs the refunds and liquidations into transactions of
   * at most PackLimit pieces that together pay more than the miner fee, and runs everything else one by one.
   * A later round finds what an earlier one made, such as an offer's continuation, so several fills of one
   * offer chain in a single run; with a node that reads the mempool they can land in one block. A box is
   * spent at most once, and a command that failed is not tried again. `run` executes a command line and
   * says whether it worked. Returns each command with its outcome.
   */
  def runBatch(run: String => Boolean, maxRounds: Int = DefaultRounds): Seq[(String, Boolean)] = {
    val spent = mutable.Set.empty[String]
    val failed = mutable.Set.empty[String]
    val log = mutable.ArrayBuffer.empty[(String, Boolean)]
    var round = 0
    var progress = true
    while (progress && round < maxRounds) {
      round += 1
      progress = false
      val fresh = batchScan().work.filterNot(_.spends.exists(spent.contains))
      val (packable, single) = fresh.partition(_.packable)
      val packs = packable.grouped(PackLimit).filter(_.map(_.reward).sum > minerFee)
        .map(chunk => (s"pack ${chunk.flatMap(_.packBox).mkString(" ")}", chunk.flatMap(_.spends).toSet, chunk)).toSeq
      def attempt(command: String, spends: Set[String]): Boolean =
        !spends.exists(spent.contains) && !failed.contains(command) && {
          val ok = run(command)
          log += command -> ok
          if (ok) {
            spent ++= spends
            progress = true
          } else failed += command
          ok
        }
      // A pack that fails is retried piece by piece, for the pieces that pay their own miner fee.
      packs.foreach { case (command, spends, chunk) =>
        if (!attempt(command, spends) && chunk.size > 1) chunk.filter(w => w.reward > minerFee).foreach(w => attempt(w.command, w.spends))
      }
      single.foreach(w => attempt(w.command, w.spends))
    }
    log.result()
  }
}

object StandardBatch {
  /** The most refunds and liquidations one packed transaction takes. */
  val PackLimit = 20

  /** How many times `batch --run` scans again for work an earlier round made possible. */
  val DefaultRounds = 5
}
