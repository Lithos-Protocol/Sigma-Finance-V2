package ksingh.sigmabonds
package livetest

import io.circe.Json
import ksingh.sigmabonds.app.SigmaFiCli.parseErg
import ksingh.sigmabonds.app._
import ksingh.sigmabonds.livetest.StepState._
import ksingh.sigmabonds.standard._
import org.ergoplatform.appkit.NetworkType
import scorex.util.encode.Base16
import work.lithos.mutations.InputUTXO

import java.io.{ByteArrayOutputStream, File, FileOutputStream, OutputStream, PrintStream}
import java.util.Scanner
import scala.collection.mutable
import scala.util.{Failure, Success, Try}

/**
 * The live test: a shell that walks the SigmaFi CLI through every ZC Standard command against a real node
 * and wallet (LivePlan), feeding each step's command line into the CLI's own session. It waits for
 * confirmations and heights, chains through the mempool where the CLI allows it, saves its progress after
 * every change, and resumes where it stopped.
 *
 *   sbt "livetest [node options] [--no-chain] [--maturity-blocks <n>] [--state <file>] [--log <file>]"
 */
object LiveTest {

  val Usage: String =
    """SigmaFi live test. Each step is a SigmaFi CLI command line, run in one CLI session against your node.
      |
      |  next             run the next step that is ready, or say what everything is waiting for
      |  run              run every step that is ready now, without waiting
      |  auto             keep running steps as they become ready, waiting for confirmations and heights;
      |                   press Enter to pause
      |  status           every step: done, in flight, waiting (and on what), failed or skipped
      |  plan             the steps, their tracks and what each builds on
      |  show <step>      what a step does and shows off, its command line, transactions, output boxes and notes
      |  vars             the box and token ids steps have found
      |  retry <step> ... run failed or skipped steps again; `retry failed` re-runs every failed step. A step
      |                   whose transaction left the mempool unconfirmed runs again by itself (twice at most)
      |  skip <step>      skip a step (B3, the recollateralize 60 blocks after a fill, saves about an hour)
      |  chain on|off     whether steps chain through the mempool or wait for confirmations
      |  cleanup [run]    this wallet's open orders, offers, asks, share requests, bonds and claims, with the
      |                   command that closes each; `cleanup run` submits them
      |  reset            forget the progress (the state file), after a confirmation
      |  exit
      |
      |Anything else is a SigmaFi CLI command, run in the same session: e.g. `bonds --mine` or `help`.
      |Progress is saved after every change, so `exit` (or Ctrl+C) and starting again resumes the run.
      |""".stripMargin

  /** `text` broken into lines of at most `width` characters at spaces (a longer word gets a line of its own). */
  def wrap(text: String, width: Int): Seq[String] =
    text.split(" ").filter(_.nonEmpty).foldLeft(Vector.empty[String]) { (lines, word) =>
      lines.lastOption match {
        case Some(last) if last.length + 1 + word.length <= width => lines.init :+ s"$last $word"
        case _ => lines :+ word
      }
    }

  def main(args: Array[String]): Unit = {
    var stateFile = "sigmafi-livetest.json"
    var logFile = "sigmafi-livetest.log"
    var chain = true
    var maturity = LivePlan.DefaultMaturityBlocks
    val rest = Vector.newBuilder[String]
    var it = args.toList
    while (it.nonEmpty) it match {
      case "--state" :: f :: tail => stateFile = f; it = tail
      case "--log" :: f :: tail => logFile = f; it = tail
      case "--no-chain" :: tail => chain = false; it = tail
      case "--maturity-blocks" :: n :: tail =>
        maturity = Try(n.toInt).filter(_ >= 20).getOrElse { System.err.println("--maturity-blocks takes 20 or more"); sys.exit(1) }
        it = tail
      case x :: tail => rest += x; it = tail
      case Nil =>
    }
    val launch = Try(Args.parse(rest.result())).fold(e => { SigmaFiCli.report(e); sys.exit(1) }, identity)
    if (launch.command.isDefined || launch.flag("send")) {
      System.err.println("The live test takes node options only (--node, --api-key, --wallet-pass, --address, --fee)")
      sys.exit(1)
    }
    // Everything printed also goes to the log, which is what to share when something goes wrong.
    val log = new FileOutputStream(logFile, true)
    val outTee = new Tee(System.out, log)
    val errTee = new Tee(System.err, log)
    val out = new PrintStream(outTee, true)
    val err = new PrintStream(errTee, true)
    System.setOut(out)
    System.setErr(err)
    Console.withOut(out) {
      Console.withErr(err) {
        println(s"\n===== SigmaFi live test, ${java.time.LocalDateTime.now().withNano(0)} =====")
        new LiveRunner(new SigmaFiCli.Session(launch), launch, new File(stateFile), chain, maturity, outTee, errTee,
          logFile).shell()
      }
    }
    log.close()
    sys.exit(0)
  }
}

/** An output stream that writes to every sink, and to a capture buffer while one is set. */
final class Tee(sinks: OutputStream*) extends OutputStream {
  @volatile var capture: Option[ByteArrayOutputStream] = None
  override def write(b: Int): Unit = { sinks.foreach(_.write(b)); capture.foreach(_.write(b)) }
  override def write(b: Array[Byte], off: Int, len: Int): Unit = {
    sinks.foreach(_.write(b, off, len))
    capture.foreach(_.write(b, off, len))
  }
  override def flush(): Unit = sinks.foreach(_.flush())
}

/** Runs LivePlan through one SigmaFi CLI session. */
class LiveRunner(session: SigmaFiCli.Session, launch: Args, file: File, chainOption: Boolean, maturityOption: Int,
                 outTee: Tee, errTee: Tee, logFile: String) {

  import LivePlan._

  private val plan = LivePlan.steps
  private val byId = plan.map(s => s.id.toLowerCase -> s).toMap
  private val protocol = new StandardProtocol()
  private val in = new Scanner(System.in)
  private var state: LiveState = LiveState.load(file).getOrElse(LiveState.fresh(chainOption, maturityOption))
  /** Polls a lookup should wait between, and how many attempts a step gets before it fails for good. */
  private val PollSeconds = 15
  private val MaxAttempts = 3

  private def save(): Unit = LiveState.save(state, file)
  private def st(id: String): StepState = state.step(id)
  private def set(id: String)(f: StepState => StepState): Unit = { state = state.update(id)(f); save() }
  private def say(s: String): Unit = println(s)

  /** A step's explanation, wrapped and indented under its title. */
  private def explain(s: Step): Unit = LiveTest.wrap(s.about, 96).foreach(line => say(s"    $line"))
  private def short(id: String): String = if (id.length > 12) id.take(8) + ".." else id

  // ═══ THE CHAIN ════════════════════════════════════════════════════════════

  /**
   * Heights: the node's chain, its index, and its wallet's scan. The node wallet signs at `height`, so a gate
   * on a path open from height X opens at height >= X (see AppBase.height).
   */
  private case class View(height: Int, indexed: Int, wallet: Int) {
    def reached(x: Long): Boolean = height.toLong >= x
  }

  private def view(): View = {
    val n = session.nodeApi()
    val full = n.info().hcursor.downField("fullHeight").as[Int].getOrElse(0)
    val indexed = Try(n.indexedHeight().hcursor.downField("indexedHeight").as[Int].getOrElse(full)).getOrElse(full)
    val wallet = Try(n.walletStatus().hcursor.downField("walletHeight").as[Int].getOrElse(full)).getOrElse(full)
    View(full, indexed, wallet)
  }

  /** Box reads for one pass over the plan, through the mempool when chaining. */
  private class Look {
    private val node = session.nodeApi(state.chain)
    private val boxes = mutable.Map.empty[String, Option[InputUTXO]]
    def box(id: String): Option[InputUTXO] =
      boxes.getOrElseUpdate(id, Try(node.boxById(id)).toOption.flatten.flatMap(j => Try(NodeJson.box(j)).toOption))
    def expiry(kind: BoxKind, id: String): Option[Long] = box(id).flatMap { b =>
      kind match {
        case BoxKind.Order => protocol.asOrder(b).map(_.expiry)
        case BoxKind.Offer => protocol.asOffer(b).map(_.expiry)
        case BoxKind.Ask => protocol.asAsk(b).map(_.expiry)
      }
    }
    def bond(id: String): Option[StdBond] = box(id).flatMap(protocol.asBond)
  }

  private sealed trait TxState
  private case object Confirmed extends TxState
  private case object Pooled extends TxState
  private case object Missing extends TxState

  /** Confirmed once the index has it and the wallet has scanned its block. */
  private def txState(n: NodeApi, tx: String, v: View): TxState = n.transactionById(tx) match {
    case Some(j) => if (v.wallet >= j.hcursor.downField("inclusionHeight").as[Int].getOrElse(v.indexed)) Confirmed else Pooled
    case None => if (Try(n.unconfirmedTransactionById(tx)).toOption.flatten.isDefined) Pooled else Missing
  }

  /**
   * Settle in-flight steps: done once every transaction confirms. One that leaves the mempool unconfirmed (and
   * with it whatever was chained on it) has its bindings undone and runs again, up to MaxAttempts.
   */
  private def settle(v: View): Unit = {
    val n = session.nodeApi()
    plan.filter(s => st(s.id).status == InFlight).foreach { s =>
      val states = st(s.id).txs.map(tx => txState(n, tx, v))
      if (states.forall(_ == Confirmed)) {
        if (s.kind == Kind.Setup && !state.vars.contains("token")) readSetup(st(s.id).txs.head)
        verify(s) match {
          case None => finish(s, Done, s"confirmed at height ${v.height} or before")
          case Some(problem) => finish(s, Failed, s"confirmed, but the check failed: $problem")
        }
      } else if (states.contains(Missing) && v.indexed >= v.height) {
        val now = System.currentTimeMillis()
        val ss = st(s.id)
        val first = if (ss.misses == 0) now else ss.firstMiss
        if (ss.misses + 1 >= 3 && now - first >= 90000L) dropped(s)
        else set(s.id)(_.copy(misses = ss.misses + 1, firstMiss = first))
      } else if (st(s.id).misses > 0) set(s.id)(_.copy(misses = 0))
    }
  }

  /** A step whose transaction left the mempool unconfirmed: its bindings are undone, and it runs again. */
  private def dropped(s: Step): Unit = {
    val why = "a transaction left the mempool without confirming (dropped, or replaced by a conflicting one)"
    state = state.unbind(s.id)
    val ss = st(s.id)
    if (ss.attempts < MaxAttempts) {
      set(s.id)(_.copy(status = Pending, txs = Vector.empty, outputs = Vector.empty, waitFor = Vector.empty,
        misses = 0, notes = ss.notes :+ s"attempt ${ss.attempts}: $why"))
      say(s"[${s.id}] $why; it runs again")
    } else {
      save()
      finish(s, Failed, s"$why; `retry ${s.id}` runs the step again")
    }
  }

  /** What must hold once a step confirms; None when it does. */
  private def verify(s: Step): Option[String] = Try {
    val n = session.nodeApi()
    def v(name: String) = state.vars(name)
    def box(id: String) = n.boxById(id).flatMap(j => Try(NodeJson.box(j)).toOption)
    s.check match {
      case Check.NoCheck => None
      case Check.TokenGone(t) =>
        val left = n.unspentByTokenId(v(t))
        if (left.isEmpty) None else Some(s"${left.size} unspent box(es) still hold token ${v(t)}")
      case Check.VaultState(b, want) => box(v(b)).flatMap(protocol.asShareVault) match {
        case None => Some(s"box ${v(b)} is not a share vault")
        case Some(vault) if vault.holding => Some("the vault still holds its bond unit")
        case Some(vault) if !vault.state.contains(want.toByte) =>
          Some(s"the vault's state is ${vault.state.fold("unset")(_.toString)}, not $want")
        case _ => None
      }
      case Check.BondHolds(b, t, amount) => box(v(b)).flatMap(protocol.asBond) match {
        case None => Some(s"box ${v(b)} is not a live bond")
        case Some(bond) =>
          val held = bond.collateral.filter(_.id.toString == v(t)).map(_.amount).sum
          if (held == amount) None else Some(s"the bond holds $held of token ${v(t)}, not $amount")
      }
    }
  }.fold(e => Some(s"the check could not read the node: ${e.getMessage}"), identity)

  // ═══ READINESS ════════════════════════════════════════════════════════════

  private sealed trait Readiness
  private case class Ready(chained: Boolean) extends Readiness
  /** `external`: waiting on the chain (a height, a box, the batch scan) rather than on another step. */
  private case class Waiting(why: String, until: Option[Long] = None, external: Boolean = false) extends Readiness
  private case class Blocked(why: String) extends Readiness
  private case class SkipNow(why: String) extends Readiness

  private def stepOf(id: String): Step = byId(id.toLowerCase)
  private def orderOnly(id: String): Boolean = stepOf(id).kind == Kind.Read || stepOf(id).kind == Kind.Refusal
  private def over(id: String): Boolean = Set(Done, Skipped, Failed).contains(st(id).status)
  private def finished(id: String): Boolean = Set(Done, Skipped).contains(st(id).status)

  private def deps(s: Step): Seq[String] = if (s.last) plan.filterNot(_.last).map(_.id) else (s.after ++ s.settled).distinct

  /** Whether dependency `id` is met: run (a listing or refusal), confirmed, or in the mempool when chaining. */
  private def met(id: String, confirmed: Boolean): Boolean =
    if (orderOnly(id)) over(id) else finished(id) || (!confirmed && state.chain && st(id).status == InFlight)

  private def waitWords(ids: Seq[String]): String = ids.map { d =>
    st(d).status match {
      case InFlight => s"$d to confirm"
      case Pending => s"$d to run"
      case other => s"$d ($other)"
    }
  }.mkString(", ")

  private def readiness(s: Step, v: View, look: Look): Readiness = {
    val blockers = deps(s).filter(d => st(d).status == Failed && !orderOnly(d))
    val needConfirmed = if (s.last) deps(s) else s.settled ++ (if (state.chain) Nil else s.after)
    lazy val unconfirmed = needConfirmed.filterNot(d => met(d, confirmed = true))
    lazy val unsent = s.after.filterNot(d => met(d, confirmed = false))
    lazy val held = st(s.id).waitFor.filter(d => st(d).status == InFlight)
    // The check's variables may be bound by the step itself, so only these must exist beforehand.
    lazy val missing = (placeholders(s.command) ++ s.expect.flatMap(placeholders) ++ gateVars(s.gate)).distinct
      .filterNot(state.vars.contains)
    lazy val chained = deps(s).exists(d => st(d).status == InFlight)
    if (blockers.nonEmpty) Blocked(s"${blockers.mkString(", ")} failed: retry or skip it")
    else if (unconfirmed.nonEmpty) Waiting(s"waiting for ${waitWords(unconfirmed)}")
    else if (unsent.nonEmpty) Waiting(s"waiting for ${waitWords(unsent)}")
    else if (held.nonEmpty) Waiting(s"retrying once ${held.mkString(", ")} confirm")
    else if (st(s.id).notBefore.exists(_ > v.height))
      Waiting(s"retrying at height ${st(s.id).notBefore.get}", st(s.id).notBefore.map(_.toLong), external = true)
    else if (s.skipIf.exists(state.vars.contains)) SkipNow(s"${s.skipIf.get} is already bound: an earlier step did this")
    else if (missing.nonEmpty) {
      if (chained) Waiting(s"waiting for ${missing.mkString(", ")}")
      else Blocked(s"needs ${missing.mkString(", ")}, which no finished step found")
    } else gate(s, v, look).map {
      case w: Waiting => w.copy(external = true)
      case other => other
    }.getOrElse(Ready(chained))
  }

  private def gateVars(g: Gate): Seq[String] = g match {
    case Gate.Expired(_, b) => Seq(b)
    case Gate.Unexpired(_, b) => Seq(b)
    case Gate.Recollateralizable(b) => Seq(b)
    case Gate.Reached(h) => Seq(h)
    case Gate.Before(h) => Seq(h)
    case Gate.QuietBatch(bs) => bs
    case Gate.Open => Seq.empty
  }

  private def kindName(k: BoxKind): String = k match {
    case BoxKind.Order => "order"
    case BoxKind.Offer => "offer"
    case BoxKind.Ask => "ask"
  }

  /** The gate's verdict, or None when it is open. A height counts once the chain is at it: the node wallet signs there. */
  private def gate(s: Step, v: View, look: Look): Option[Readiness] = {
    def vr(n: String) = state.vars(n)
    def unread(id: String) = Some(Waiting(s"cannot read box ${short(id)} from the node yet"))
    s.gate match {
      case Gate.Open => None
      case Gate.Expired(k, b) => look.expiry(k, vr(b)) match {
        case Some(e) if v.reached(e) => None
        case Some(e) => Some(Waiting(s"${kindName(k)} ${short(vr(b))} expires at height $e", Some(e)))
        case None => unread(vr(b))
      }
      case Gate.Unexpired(k, b) => look.expiry(k, vr(b)) match {
        case Some(e) if v.reached(e) => Some(SkipNow(s"the ${kindName(k)} expired at height $e before this could run"))
        case Some(_) => None
        case None => unread(vr(b))
      }
      case Gate.Recollateralizable(b) => look.bond(vr(b)).map(_.recollateralizableFrom) match {
        case Some(h) if v.reached(h) => None
        case Some(h) => Some(Waiting(s"bond ${short(vr(b))} can take collateral from height $h", Some(h)))
        case None => unread(vr(b))
      }
      case Gate.Reached(h) =>
        val at = vr(h).toLong
        if (v.reached(at)) None else Some(Waiting(s"height $at ($h)", Some(at)))
      case Gate.Before(h) =>
        val at = vr(h).toLong
        if (v.reached(at)) Some(SkipNow(s"height $at ($h) has passed")) else None
      case Gate.QuietBatch(boxes) => quiet(boxes.map(vr).toSet, v)
    }
  }

  private var quietMemo: Option[((Int, Int, Set[String]), Option[Readiness])] = None

  /** Open when a batch scan finds work and every piece spends only `ids` (so a batch run does just that). */
  private def quiet(ids: Set[String], v: View): Option[Readiness] = {
    val key = (v.height, session.submissions.size, ids)
    quietMemo.filter(_._1 == key).map(_._2).getOrElse {
      val verdict = Try(session.withStandard(launch, state.chain)((app, _) => app.batchScan())) match {
        case Failure(e) => Some(Waiting(s"the batch scan failed: ${e.getMessage}"))
        case Success(scan) =>
          val ours = state.vars.values.toSet
          val other = scan.work.filterNot(_.spends.subsetOf(ids))
          if (other.nonEmpty) Some(Waiting("the batch scan also finds other work, to be done first: " + other.map(w =>
            w.summary + (if (w.spends.subsetOf(ours)) "" else " (NOT a box of this test: do it yourself, or skip this step)"))
            .mkString("; ")))
          else if (!scan.work.exists(_.spends == ids)) Some(Waiting("the batch scan does not find the planned match yet"))
          else None
      }
      quietMemo = Some(key -> verdict)
      verdict
    }
  }

  // ═══ RUNNING STEPS ════════════════════════════════════════════════════════

  /** The first step that can run now, skipping any whose time has passed. */
  private def firstReady(v: View): Option[(Step, Boolean)] = {
    val look = new Look
    var found: Option[(Step, Boolean)] = None
    val pending = plan.filter(s => st(s.id).status == Pending).iterator
    while (found.isEmpty && pending.hasNext) {
      val s = pending.next()
      readiness(s, v, look) match {
        case Ready(chained) => found = Some(s -> chained)
        case SkipNow(why) => finish(s, Skipped, why)
        case _ =>
      }
    }
    found
  }

  private def finish(s: Step, status: String, note: String): Unit = {
    set(s.id)(ss => ss.copy(status = status, notes = ss.notes :+ note, waitFor = Vector.empty))
    val mark = status match {
      case Done => "ok"
      case Failed => "FAILED"
      case other => other
    }
    say(s"[${s.id}] $mark: $note")
  }

  private def capture[A](f: => A): (A, String, String) = {
    val o = new ByteArrayOutputStream
    val e = new ByteArrayOutputStream
    outTee.capture = Some(o)
    errTee.capture = Some(e)
    try {
      val r = f
      Console.out.flush()
      System.err.flush()
      (r, o.toString, e.toString)
    } finally {
      outTee.capture = None
      errTee.capture = None
    }
  }

  private def lastError(err: String): Option[String] =
    err.split("\r?\n").map(_.trim).filter(_.startsWith("error:")).lastOption.map(_.stripPrefix("error:").trim)
      .orElse(err.split("\r?\n").map(_.trim).filter(_.nonEmpty).lastOption)

  /** Run one step. `chained` says it builds on transactions still in the mempool. */
  private def run(s: Step, chained: Boolean, v: View): Unit = s.kind match {
    case Kind.Setup => setup(s, v)
    case kind => fill(s.command, state.vars) match {
      case Left(missing) => finish(s, Failed, s"no value for {$missing}")
      case Right(line) =>
        val expect = s.expect.map(e => fill(e, state.vars).getOrElse(e))
        val mempool = if (state.chain) " --mempool" else ""
        val full = if (kind == Kind.Tx) s"$line --send$mempool" else s"$line$mempool"
        // A moment for the node's mempool view to take in what the previous step just submitted.
        if (chained) Thread.sleep(1000)
        say(s"\n[${s.id}] ${s.title}" + (if (chained) " (chained through the mempool)" else ""))
        explain(s)
        say(s"> $full")
        val before = session.submissions.size
        val (ok, out, err) = capture(session.execute(Args.parse(Args.tokenize(full)).withDefaults(launch)))
        val subs = session.submissions.drop(before).toVector
        set(s.id)(ss => ss.copy(command = Some(full), attempts = ss.attempts + 1, ranAt = Some(v.height)))
        kind match {
          case Kind.Tx =>
            if (subs.nonEmpty) {
              bind(s, subs)
              set(s.id)(_.copy(status = InFlight, txs = subs.map(_.txId), outputs = subs.map(_.outputs.toVector),
                waitFor = Vector.empty, misses = 0))
              say(s"[${s.id}] submitted: ${subs.map(_.txId).mkString(", ")}" +
                (if (!ok) " (the command then failed; see above)" else ""))
            } else failure(s, if (ok) "the command submitted nothing" else lastError(err).getOrElse("it failed"))
          case Kind.Read =>
            val absent = expect.filterNot(out.contains)
            if (ok && absent.isEmpty) finish(s, Done, if (expect.isEmpty) "listed" else "listed as expected")
            else failure(s, if (!ok) lastError(err).getOrElse("it failed") else s"the listing does not show ${absent.mkString(", ")}")
          case Kind.Refusal =>
            if (ok) finish(s, Failed, "the CLI accepted it, where it should refuse. It was a dry run: nothing was submitted")
            else if (expect.forall(err.contains)) finish(s, Done, "refused as expected")
            else failure(s, s"refused, but not for the expected reason: ${lastError(err).getOrElse("")}")
          case Kind.Setup =>
        }
    }
  }

  /**
   * A failure while transactions are in flight may be the mempool's doing (a lookup that does not see it yet,
   * a wallet box still unconfirmed), so the step runs again once they confirm: its own dependencies when it
   * built on any of them, else everything in flight. A refusal that names the next block (an oracle refresh in
   * the mempool) waits for it without counting as an attempt. Otherwise the step fails.
   */
  private def failure(s: Step, message: String): Unit = {
    val inflightDeps = deps(s).filter(d => st(d).status == InFlight)
    val inflight = if (inflightDeps.nonEmpty) inflightDeps else plan.map(_.id).filter(d => st(d).status == InFlight)
    if (message.contains("Try again after the next block")) {
      val at = st(s.id).ranAt.getOrElse(0) + 1
      set(s.id)(ss => ss.copy(status = Pending, attempts = math.max(0, ss.attempts - 1), notBefore = Some(at),
        notes = ss.notes :+ s"waited a block: $message"))
      say(s"[${s.id}] $message\n[${s.id}] it runs again at height $at")
    } else if (!s.noDefer && inflight.nonEmpty && st(s.id).attempts < MaxAttempts) {
      val how = if (inflightDeps.nonEmpty) "chained through the mempool" else "with transactions in flight"
      set(s.id)(ss => ss.copy(status = Pending, waitFor = inflight.toVector,
        notes = ss.notes :+ s"attempt ${ss.attempts} ($how) failed: $message"))
      say(s"[${s.id}] failed $how: $message\n[${s.id}] it runs again once ${inflight.mkString(", ")} confirm")
    } else finish(s, Failed, message)
  }

  private def bind(s: Step, subs: Seq[Submission]): Unit = {
    val found = s.binds.flatMap { b =>
      val sub = if (b.title.isEmpty) subs.lastOption else subs.reverse.find(_.title.startsWith(b.title))
      sub.flatMap(_.outputs.lift(b.output)).filter(_.nonEmpty).map(b.name -> _)
    }
    if (found.nonEmpty) {
      state = state.bindAs(s.id, found)
      save()
      say(s"[${s.id}] found " + found.map { case (k, id) => s"$k = $id" }.mkString(", "))
    }
  }

  // ═══ SETUP ════════════════════════════════════════════════════════════════

  /** Checks before anything is submitted, the plan in words, and a yes. */
  private def start(): Boolean = {
    say("\nChecking the node, the wallet and the oracle before anything is submitted...")
    val pre = Try(session.withStandard(launch, mempool = false) { (app, _) =>
      (app.networkType, app.myAddress.toString, app.walletBoxes.map(_.value).sum, Try(app.oracle()).toOption.flatten,
        app.height)
    })
    pre match {
      case Failure(e) =>
        SigmaFiCli.report(e)
        false
      case Success((network, address, balance, oracle, height)) =>
        val problems = Seq(
          if (network != NetworkType.MAINNET) Some("the live test needs mainnet: the oracle rule reads the mainnet USD oracle pool")
          else None,
          if (balance < parseErg(MinimumBalance)) Some(s"the wallet's spendable boxes hold ${Fmt.erg(balance)}; " +
            s"the live test wants at least $MinimumBalance ERG") else None,
          if (oracle.isEmpty) Some("the node's index has no single USD oracle pool box") else None).flatten
        if (problems.nonEmpty) {
          problems.foreach(p => say(s"Cannot start: $p."))
          return false
        }
        val age = height + 1 - oracle.get.created
        if (state.chain) {
          val probe = Try {
            val n = session.nodeApi(mempool = true)
            n.unspentByTemplateHash(Base16.encode(protocol.orderTemplateHash))
            n.walletUnspentBoxes()
          }
          probe.failed.foreach { e =>
            say(s"The node refused a mempool lookup (${e.getMessage}), so mempool chaining is off: every step waits " +
              "for the confirmations it builds on.")
            state = state.copy(chain = false)
          }
        }
        val txs = plan.count(_.kind == Kind.Tx) + 1
        say(
          s"""
             |Wallet $address holds ${Fmt.erg(balance)}. Height $height; the oracle price is $age blocks old.
             |
             |The live test submits about $txs transactions on MAINNET from this wallet, which is borrower, lender,
             |seller, buyer and batcher throughout. Loans are 0.1 ERG. At most about 2.7 ERG sits in contracts at
             |once, and all of it comes back to this wallet; the cost is the miner fees, about ${txs + 10} x 0.001 ERG.
             |The setup mints $TokenAmount "$TokenName" tokens (worthless test collateral).
             |
             |Bonds D and E mature ${state.maturityBlocks} blocks after the setup. Bond B is recollateralized 60 blocks
             |after its fill (the contract's wait), so the whole run takes about 70 blocks, roughly 2.5 hours.
             |`skip B3` finishes about an hour sooner. Mempool chaining: ${if (state.chain) "on" else "off"}.
             |Progress is saved in ${file.getPath}; everything printed also goes to $logFile.
             |""".stripMargin)
        print("Type yes to start submitting transactions: ")
        System.out.flush()
        val answer = if (in.hasNextLine) in.nextLine().trim else ""
        if (answer != "yes") {
          say("Not started.")
          false
        } else {
          state = state.copy(started = true, address = Some(address))
          save()
          true
        }
    }
  }

  private var walletChecked = false

  /** A resumed run must act as the wallet address it started with. */
  private def sameWallet(): Boolean = walletChecked || {
    state.address match {
      case None => walletChecked = true
      case Some(started) =>
        Try(session.withStandard(launch, mempool = false)((app, _) => app.myAddress.toString)) match {
          case Success(now) if now == started => walletChecked = true
          case Success(now) => say(s"This run started as $started, but the wallet now acts as $now. Pass --address $started.")
          case Failure(e) => SigmaFiCli.report(e)
        }
    }
    walletChecked
  }

  /** The node wallet mints the test token to this address and splits ERG into boxes, in one transaction. */
  private def setup(s: Step, v: View): Unit = {
    if (!state.started && !start()) return
    say(s"\n[${s.id}] ${s.title}")
    explain(s)
    say(s"> (node wallet) POST /wallet/transaction/send: 1 asset issue and $SplitBoxes payments to this wallet")
    val sent = Try(session.withStandard(launch, mempool = false) { (app, node) =>
      val me = Json.fromString(app.myAddress.toString)
      val issue = Json.obj("address" -> me, "ergValue" -> Json.fromLong(parseErg(SplitValue)),
        "amount" -> Json.fromLong(TokenAmount), "name" -> Json.fromString(TokenName),
        "description" -> Json.fromString(TokenDescription), "decimals" -> Json.fromInt(0))
      val pay = Json.obj("address" -> me, "value" -> Json.fromLong(parseErg(SplitValue)), "assets" -> Json.arr())
      node.walletTransactionSend(issue +: Seq.fill(SplitBoxes)(pay), app.minerFee)
    })
    sent match {
      case Failure(e) =>
        SigmaFiCli.report(e)
        set(s.id)(ss => ss.copy(attempts = ss.attempts + 1))
        finish(s, Failed, s"the node wallet did not send it: ${e.getMessage}")
      case Success(txId) =>
        state = state.bind("T0" -> v.height.toString, "M" -> (v.height + state.maturityBlocks).toString)
        set(s.id)(ss => ss.copy(status = InFlight, txs = Vector(txId), attempts = ss.attempts + 1, ranAt = Some(v.height),
          command = Some("POST /wallet/transaction/send")))
        say(s"[${s.id}] submitted: $txId. T0 = ${v.height}; bonds D and E mature at M = ${v.height + state.maturityBlocks}")
        if (!readSetup(txId)) say(s"[${s.id}] the token id is read once the transaction confirms")
    }
  }

  /** The test token's id is the setup's first input; its inputs are spent for every later command. */
  private def readSetup(txId: String): Boolean = {
    val n = session.nodeApi()
    val tx = (1 to 10).iterator.map { i =>
      if (i > 1) Thread.sleep(1000)
      Try(n.unconfirmedTransactionById(txId).orElse(n.transactionById(txId))).toOption.flatten
    }.collectFirst { case Some(j) => j }
    val inputs = tx.toSeq.flatMap(_.hcursor.downField("inputs").focus.flatMap(_.asArray).getOrElse(Vector.empty))
      .flatMap(_.hcursor.downField("boxId").as[String].toOption)
    inputs.headOption.foreach { first =>
      session.markSpent(inputs)
      state = state.bind("token" -> first)
      save()
      say(s"[S1] found token = $first")
    }
    inputs.nonEmpty
  }

  // ═══ SHELL ════════════════════════════════════════════════════════════════

  def shell(): Unit = {
    say(LiveTest.Usage)
    if (state.started) status()
    else say("Not started. `next` or `auto` runs the read-only listings first, then checks the wallet and asks before " +
      "the first transaction.")
    var open = true
    while (open) {
      print("livetest> ")
      System.out.flush()
      if (!in.hasNextLine) open = false
      else in.nextLine().trim match {
        case "" =>
        case "exit" | "quit" => open = false
        case line => Try(command(line)).failed.foreach(SigmaFiCli.report)
      }
    }
  }

  private def command(line: String): Unit = line.split("\\s+").toList match {
    case "help" :: Nil => say(LiveTest.Usage)
    case "status" :: _ => status()
    case "plan" :: _ => showPlan()
    case "next" :: _ => next()
    case "run" :: _ => wave()
    case "auto" :: _ => auto()
    case "show" :: id :: _ => show(id)
    case "vars" :: _ => state.vars.toSeq.sortBy(_._1).foreach { case (k, v) => say(f"  $k%-10s $v") }
    case "retry" :: ids if ids.nonEmpty => retry(ids)
    case "skip" :: id :: _ => skip(id)
    case "chain" :: "on" :: _ => state = state.copy(chain = true); save(); say("Mempool chaining is on.")
    case "chain" :: "off" :: _ => state = state.copy(chain = false); save(); say("Mempool chaining is off.")
    case "cleanup" :: rest => cleanup(rest.contains("run"))
    case "reset" :: _ => reset()
    case _ =>
      // Anything else is a CLI command, in the same session.
      session.execute(Args.parse(Args.tokenize(line)).withDefaults(launch))
  }

  private def find(id: String): Option[Step] = {
    val s = byId.get(id.toLowerCase)
    if (s.isEmpty) say(s"No step $id; `plan` lists them.")
    s
  }

  private def ready(): Boolean = sameWallet()

  private def next(): Unit = if (ready()) {
    val v = view()
    settle(v)
    firstReady(v) match {
      case Some((s, chained)) => run(s, chained, v)
      case None => say(summary(v, detailed = true))
    }
  }

  /** Every step that is ready now, in plan order, each run before the next is looked for. Returns how many ran. */
  private def wave(): Int = if (!ready()) 0 else {
    var v = view()
    settle(v)
    var ran = 0
    var more = true
    while (more && ran < 200) {
      firstReady(v) match {
        case Some((s, chained)) =>
          val wasStarted = state.started
          run(s, chained, v)
          // A setup that was declined stops the wave.
          if (s.kind == Kind.Setup && !wasStarted && !state.started) more = false
          ran += 1
          v = Try(view()).getOrElse(v)
        case None => more = false
      }
    }
    if (ran == 0) say(summary(v, detailed = true))
    ran
  }

  private def auto(): Unit = if (ready()) {
    say(s"Running steps as they become ready; checking every $PollSeconds seconds. Press Enter to pause.")
    var last = ""
    var lastAt = 0L
    var going = true
    while (going) {
      Try(view()) match {
        case Failure(e) =>
          SigmaFiCli.report(e)
          if (pause(PollSeconds * 2)) going = false
        case Success(v) =>
          settle(v)
          var ran = 0
          var more = true
          while (more) firstReady(v) match {
            case Some((s, chained)) =>
              val wasStarted = state.started
              run(s, chained, v)
              ran += 1
              if (s.kind == Kind.Setup && !wasStarted && !state.started) { more = false; going = false }
            case None => more = false
          }
          if (going) {
            if (plan.forall(s => over(s.id))) {
              finalReport()
              going = false
            } else if (ran == 0 && stuck(v)) {
              say("\nNothing can run, and nothing is in flight or waiting for a height:")
              status()
              going = false
            } else {
              val line = summary(v, detailed = false)
              val now = System.currentTimeMillis()
              if (line != last || now - lastAt > 300000L) {
                say(line)
                last = line
                lastAt = now
              }
              if (pause(PollSeconds)) {
                say("Paused. `auto` continues; `status` shows where things stand.")
                going = false
              }
            }
          }
      }
    }
  }

  /** Sleep, unless the person presses Enter: then true. */
  private def pause(seconds: Int): Boolean = {
    val end = System.currentTimeMillis() + seconds * 1000L
    var pressed = false
    while (!pressed && System.currentTimeMillis() < end) {
      if (Try(System.in.available()).getOrElse(0) > 0) {
        if (in.hasNextLine) in.nextLine()
        pressed = true
      } else Thread.sleep(300)
    }
    pressed
  }

  /** Nothing in flight, nothing ready, and no pending step waiting on the chain: only on failed or blocked steps. */
  private def stuck(v: View): Boolean = {
    val look = new Look
    !plan.exists(s => st(s.id).status == InFlight) &&
      !plan.filter(s => st(s.id).status == Pending).exists(s => readiness(s, v, look) match {
        case Ready(_) => true
        case w: Waiting => w.external
        case _ => false
      })
  }

  private def counts: String = {
    val by = plan.groupBy(s => st(s.id).status).map { case (k, ss) => k -> ss.size }
    Seq(Done, InFlight, Pending, Failed, Skipped).flatMap(k => by.get(k).map(n => s"$n $k")).mkString(", ")
  }

  private def minutes(blocks: Long): String = {
    val m = math.max(blocks, 0L) * 2
    if (m >= 60) s"~${m / 60}h${m % 60}m" else s"~${m}m"
  }

  /** One line (or, `detailed`, a few) on where the run stands and what it waits for. */
  private def summary(v: View, detailed: Boolean): String = {
    val look = new Look
    val pending = plan.filter(s => st(s.id).status == Pending).map(s => s -> readiness(s, v, look))
    val inflight = plan.filter(s => st(s.id).status == InFlight).map(_.id)
    val heights = pending.collect { case (s, Waiting(_, Some(h), _)) => s -> h }.sortBy(_._2)
    val failed = plan.filter(s => st(s.id).status == Failed).map(_.id)
    val head = s"Height ${v.height}: $counts." +
      (if (inflight.nonEmpty) s" In flight: ${inflight.mkString(", ")}." else "") +
      heights.headOption.fold("") { case (s, h) => s" Next height: ${s.id} at $h (${h - v.height} blocks, ${minutes(h - v.height)})." } +
      (if (failed.nonEmpty) s" FAILED: ${failed.mkString(", ")}." else "")
    if (!detailed) head
    else (head +: pending.take(8).map {
      case (s, Waiting(why, _, _)) => s"  ${s.id}: $why"
      case (s, Blocked(why)) => s"  ${s.id}: blocked: $why"
      case (s, Ready(_)) => s"  ${s.id}: ready"
      case (s, SkipNow(why)) => s"  ${s.id}: to be skipped: $why"
    }).mkString("\n")
  }

  private def status(): Unit = {
    val v = Try(view()).toOption
    v.foreach(settle)
    val look = new Look
    say(v.fold("The node cannot be reached.")(x =>
      s"\nHeight ${x.height} (index ${x.indexed}, wallet ${x.wallet}). Mempool chaining: ${if (state.chain) "on" else "off"}." +
        state.vars.get("T0").fold("")(t => s" T0 $t, M ${state.vars.getOrElse("M", "?")}.")) + s" $counts.")
    plan.groupBy(_.track).toSeq.sortBy { case (_, ss) => plan.indexOf(ss.head) }.foreach { case (track, ss) =>
      say(s"\n$track")
      ss.foreach { s =>
        val ss2 = st(s.id)
        val detail = ss2.status match {
          case Pending => v.fold("")(x => readiness(s, x, look) match {
            case Ready(c) => "ready" + (if (c) " (chained)" else "")
            case Waiting(why, _, _) => why
            case Blocked(why) => s"blocked: $why"
            case SkipNow(why) => why
          })
          case Failed => ss2.notes.lastOption.getOrElse("")
          case _ => ""
        }
        say(f"  ${s.id}%-4s ${ss2.status}%-9s ${s.title}" + (if (detail.nonEmpty) s"\n                 $detail" else ""))
      }
    }
  }

  private def showPlan(): Unit = plan.groupBy(_.track).toSeq.sortBy { case (_, ss) => plan.indexOf(ss.head) }.foreach {
    case (track, ss) =>
      say(s"\n$track")
      ss.foreach { s =>
        val needs = (s.after.map(d => if (s.settled.contains(d)) s"$d (confirmed)" else d) ++
          s.settled.filterNot(s.after.contains).map(d => s"$d (confirmed)")) ++ (if (s.last) Seq("everything else") else Nil)
        say(f"  ${s.id}%-4s ${s.title}")
        if (s.command.nonEmpty) say(s"       > ${s.command}")
        if (needs.nonEmpty || s.gate != Gate.Open)
          say(s"       after ${(needs ++ (if (s.gate != Gate.Open) Seq(s.gate.toString) else Nil)).mkString(", ")}")
      }
  }

  private def show(id: String): Unit = find(id).foreach { s =>
    val ss = st(s.id)
    say(s"${s.id}  ${s.title}  (${s.track}, ${ss.status})")
    explain(s)
    if (s.command.nonEmpty) say(s"  command:  ${fill(s.command, state.vars).fold(m => s"${s.command}  (no {$m} yet)", identity)}")
    ss.command.foreach(c => say(s"  ran:      $c" + ss.ranAt.fold("")(h => s"  at height $h") + s", attempt ${ss.attempts}"))
    if (s.expect.nonEmpty) say(s"  expects:  ${s.expect.map(e => fill(e, state.vars).getOrElse(e)).mkString(", ")}")
    if (s.gate != Gate.Open) say(s"  gate:     ${s.gate}")
    if (s.check != Check.NoCheck) say(s"  check:    ${s.check}")
    ss.txs.zipWithIndex.foreach { case (tx, i) =>
      say(s"  tx:       $tx")
      ss.outputs.lift(i).foreach(_.zipWithIndex.foreach { case (b, j) => say(s"            output $j: $b") })
    }
    ss.notes.foreach(n => say(s"  note:     $n"))
  }

  /**
   * `retry failed` re-runs every failed step; otherwise each step named. A step whose transactions never
   * reached the chain has its bindings undone first, so it builds on what was there before it.
   */
  private def retry(ids: Seq[String]): Unit = {
    val chosen = if (ids == Seq("failed")) plan.filter(s => st(s.id).status == Failed) else ids.flatMap(find)
    if (chosen.isEmpty) say("Nothing to retry.")
    val n = session.nodeApi()
    chosen.foreach { s =>
      st(s.id).status match {
        case Failed | Skipped =>
          val onChain = st(s.id).txs.exists(tx => Try(n.transactionById(tx)).toOption.flatten.isDefined)
          if (!onChain) state = state.unbind(s.id)
          set(s.id)(ss => ss.copy(status = Pending, txs = Vector.empty, outputs = Vector.empty, waitFor = Vector.empty,
            attempts = 0, misses = 0, notBefore = None, notes = ss.notes :+ "retried"))
          say(s"${s.id} will run again.")
        case other => say(s"${s.id} is $other: only a failed or skipped step can be retried.")
      }
    }
  }

  private def skip(id: String): Unit = find(id).foreach { s =>
    st(s.id).status match {
      case Pending | Failed =>
        finish(s, Skipped, "skipped by you")
        val dependents = plan.filter(d => (d.after ++ d.settled).contains(s.id) && s.binds.exists(b => d.reads.contains(b.name)))
        if (dependents.nonEmpty) say(s"Steps that read what ${s.id} would have found use the earlier value, if any: " +
          dependents.map(_.id).mkString(", "))
      case other => say(s"${s.id} is $other: only a pending or failed step can be skipped.")
    }
  }

  private def reset(): Unit = {
    say("This forgets the test's progress. Boxes it created stay on chain: run `cleanup` first to close them.")
    print("Type reset to confirm: ")
    System.out.flush()
    if (in.hasNextLine && in.nextLine().trim == "reset") {
      file.delete()
      state = LiveState.fresh(chainOption, maturityOption)
      walletChecked = false
      say("Progress forgotten.")
    } else say("Kept.")
  }

  /** This wallet's open boxes in the standard set, and the command that closes or collects each. */
  private def cleanup(execute: Boolean): Unit = {
    val commands = session.withStandard(launch, state.chain) { (app, _) =>
      val next = app.height.toLong + 1L
      val mine: Array[Byte] => Boolean = app.isOwnHash
      val settled = app.settlements().map(_.asset).toSet
      app.orders().filter(o => mine(o.borrowerHash)).map(o => s"cancel-order ${o.box.id}") ++
        app.offers().filter(o => mine(o.ownerHash)).map(o => s"cancel-offer ${o.box.id}") ++
        app.asks().filter(a => mine(a.sellerHash)).map(a => s"cancel-ask ${a.box.id}") ++
        app.shareRequests().filter(r => mine(r.ownerHash)).map(r => s"cancel-share-request ${r.box.id}") ++
        app.shareVaults().filter(v => app.walletHolds(v.shareId)).map(v =>
          if (v.holding && settled.contains(v.bondAsset)) s"pull ${v.box.id}" else s"claim-shares ${v.shareId}") ++
        app.bonds().filter(b => mine(b.borrowerHash)).map(b =>
          if (next < b.maturity) s"repay ${b.box.id}" else s"liquidate ${b.box.id}") ++
        app.settlements().filter(s => app.walletHolds(s.asset)).map(s => s"claim ${s.asset}")
    }
    if (commands.isEmpty) say("Nothing of this wallet's is open in the ZC Standard contracts.")
    else {
      say("Open boxes of this wallet, and what closes each (a claim may need a pull or liquidation to confirm first):")
      commands.foreach(c => say(s"  $c"))
      if (execute) {
        print("Type yes to submit them: ")
        System.out.flush()
        if (in.hasNextLine && in.nextLine().trim == "yes")
          commands.foreach { c =>
            say(s"> $c --send")
            session.execute(Args.parse(Args.tokenize(c + " --send" + (if (state.chain) " --mempool" else "")))
              .withDefaults(launch))
          }
        else say("Nothing submitted.")
      } else say("`cleanup run` submits them.")
    }
  }

  private def finalReport(): Unit = {
    say(s"\nThe live test is over: $counts.")
    val failed = plan.filter(s => st(s.id).status == Failed)
    failed.foreach(s => say(s"  FAILED ${s.id} ${s.title}: ${st(s.id).notes.lastOption.getOrElse("")}"))
    val retried = plan.filter(s => st(s.id).notes.exists(_.contains("failed: ")) && st(s.id).status == Done)
    if (retried.nonEmpty) {
      say("Passed on a later attempt (the first failed while what it built on was unconfirmed):")
      retried.foreach(s => say(s"  ${s.id}: ${st(s.id).notes.filter(_.contains("failed: ")).mkString("; ")}"))
    }
    say(s"The full output is in $logFile.")
  }
}
