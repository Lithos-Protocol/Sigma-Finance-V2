package ksingh.sigmabonds
package livetest

import io.circe.{ACursor, Json, parser}

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardCopyOption}
import scala.util.Try

/**
 * Where one step stands. `txs` and `outputs` are its submitted transactions and their output box ids;
 * `waitFor` holds back a retry until those steps' transactions confirm; `misses` counts polls that found a
 * transaction neither confirmed nor in the mempool, from `firstMiss` (epoch millis).
 */
case class StepState(status: String = StepState.Pending, txs: Vector[String] = Vector.empty,
                     outputs: Vector[Vector[String]] = Vector.empty, command: Option[String] = None,
                     notes: Vector[String] = Vector.empty, waitFor: Vector[String] = Vector.empty,
                     attempts: Int = 0, misses: Int = 0, firstMiss: Long = 0L, ranAt: Option[Int] = None)

object StepState {
  val Pending = "pending"
  val InFlight = "in flight"
  val Done = "done"
  val Failed = "failed"
  val Skipped = "skipped"
}

/**
 * The live test's progress, saved after every change so a run can stop and resume: whether it has started
 * (and as which wallet address), whether it chains through the mempool, the variables steps have bound, and
 * each step's state.
 */
case class LiveState(started: Boolean, chain: Boolean, maturityBlocks: Int, address: Option[String],
                     vars: Map[String, String], steps: Map[String, StepState]) {
  def step(id: String): StepState = steps.getOrElse(id, StepState())
  def update(id: String)(f: StepState => StepState): LiveState = copy(steps = steps.updated(id, f(step(id))))
  def bind(kv: (String, String)*): LiveState = copy(vars = vars ++ kv)
}

object LiveState {

  def fresh(chain: Boolean, maturityBlocks: Int): LiveState =
    LiveState(started = false, chain, maturityBlocks, None, Map.empty, Map.empty)

  def load(file: File): Option[LiveState] =
    if (!file.exists()) None
    else {
      val text = new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)
      Some(parser.parse(text).toTry.flatMap(j => Try(decode(j))).getOrElse(
        throw new IllegalStateException(s"cannot read ${file.getPath}; move it aside to start over")))
    }

  /** Written to a temporary file first, so a crash mid-write never leaves half a state behind. */
  def save(state: LiveState, file: File): Unit = {
    val tmp = new File(file.getPath + ".tmp")
    Files.write(tmp.toPath, encode(state).spaces2.getBytes(StandardCharsets.UTF_8))
    Try(Files.move(tmp.toPath, file.toPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE))
      .getOrElse(Files.move(tmp.toPath, file.toPath, StandardCopyOption.REPLACE_EXISTING))
  }

  private def strings(xs: Seq[String]): Json = Json.fromValues(xs.map(Json.fromString))

  def encode(s: LiveState): Json = Json.obj(
    "version" -> Json.fromInt(1),
    "started" -> Json.fromBoolean(s.started),
    "chain" -> Json.fromBoolean(s.chain),
    "maturityBlocks" -> Json.fromInt(s.maturityBlocks),
    "address" -> s.address.fold(Json.Null)(Json.fromString),
    "vars" -> Json.obj(s.vars.toSeq.sortBy(_._1).map { case (k, v) => k -> Json.fromString(v) }: _*),
    "steps" -> Json.obj(s.steps.toSeq.sortBy(_._1).map { case (k, t) =>
      k -> Json.obj(
        "status" -> Json.fromString(t.status),
        "txs" -> strings(t.txs),
        "outputs" -> Json.fromValues(t.outputs.map(strings)),
        "command" -> t.command.fold(Json.Null)(Json.fromString),
        "notes" -> strings(t.notes),
        "waitFor" -> strings(t.waitFor),
        "attempts" -> Json.fromInt(t.attempts),
        "misses" -> Json.fromInt(t.misses),
        "firstMiss" -> Json.fromLong(t.firstMiss),
        "ranAt" -> t.ranAt.fold(Json.Null)(Json.fromInt))
    }: _*))

  private def strs(c: ACursor): Vector[String] = c.as[Vector[String]].getOrElse(Vector.empty)

  def decode(j: Json): LiveState = {
    val c = j.hcursor
    val steps = c.downField("steps").focus.flatMap(_.asObject).map(_.toMap).getOrElse(Map.empty).map { case (k, v) =>
      val s = v.hcursor
      k -> StepState(
        status = s.downField("status").as[String].getOrElse(StepState.Pending),
        txs = strs(s.downField("txs")),
        outputs = s.downField("outputs").as[Vector[Vector[String]]].getOrElse(Vector.empty),
        command = s.downField("command").as[Option[String]].getOrElse(None),
        notes = strs(s.downField("notes")),
        waitFor = strs(s.downField("waitFor")),
        attempts = s.downField("attempts").as[Int].getOrElse(0),
        misses = s.downField("misses").as[Int].getOrElse(0),
        firstMiss = s.downField("firstMiss").as[Long].getOrElse(0L),
        ranAt = s.downField("ranAt").as[Option[Int]].getOrElse(None))
    }
    LiveState(
      started = c.downField("started").as[Boolean].getOrElse(false),
      chain = c.downField("chain").as[Boolean].getOrElse(true),
      maturityBlocks = c.downField("maturityBlocks").as[Int].getOrElse(LivePlan.DefaultMaturityBlocks),
      address = c.downField("address").as[Option[String]].getOrElse(None),
      vars = c.downField("vars").as[Map[String, String]].getOrElse(Map.empty),
      steps = steps)
  }
}
