package brasp

import java.io.{ByteArrayOutputStream, File}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.sys.process.ProcessIO
import scala.sys.process.stringSeqToProcess

/** rIC3 harness runner for `Btor2`-encoded reverse Boolean-summary automata.
  *
  * rIC3 (https://github.com/gipsyh/rIC3) is a bit-level IC3/PDR hardware
  * model checker that takes AIGER/BTOR2 circuits, not Lustre — this is the
  * BTOR2-backed sibling of `Kind2`, checking the same "prefix consumed since
  * reset is accepted" bad state via `ric3 check <model.btor2> [ic3|portfolio]`.
  */

/** rIC3 could not be started. */
final case class Ric3Error(message: String) extends RuntimeException(message)

object Ric3:

  /** Extract the input word from rIC3's stdout: passing `--cex` makes rIC3
    * print its BTOR2 witness (`sat` / `b<id>` / `#<state frame>` /
    * `@<input frame>` / `.`, see https://github.com/Boolector/btor2tools)
    * right after the `SAT` verdict, mixed in with `--ui false`'s INFO logs.
    * The model declares exactly one input (`symbol`, positional index `0`),
    * so the witness's `@k` frames carry one alphabet index per step — the
    * same "one symbol per tick" shape `Kind2`'s counterexamples have.
    */
  private def counterexampleWord(stdout: String, alphabet: List[String]): Option[List[String]] =
    val lines = stdout.linesIterator.toList
    val witnessStart = lines.indexOf("sat")
    if witnessStart < 0 then None
    else
      val body = lines.drop(witnessStart + 2).takeWhile(_ != ".") // drop "sat" and the "b<id>" line
      val symbolByFrame = scala.collection.mutable.HashMap.empty[Int, Int]
      var frame = -1
      var inInputFrame = false
      for line <- body do
        if line.startsWith("#") then
          frame = line.drop(1).trim.toInt
          inInputFrame = false
        else if line.startsWith("@") then
          frame = line.drop(1).trim.toInt
          inInputFrame = true
        else if inInputFrame && line.nonEmpty then
          line.trim.split("\\s+") match
            case Array("0", bits) => symbolByFrame(frame) = Integer.parseInt(bits.replace('x', '0'), 2)
            case _                => ()
      if symbolByFrame.isEmpty then None
      else
        val mapped = (0 to symbolByFrame.keys.max).map { step =>
          symbolByFrame.get(step).flatMap(index => if index >= 0 && index < alphabet.length then Some(alphabet(index)) else None)
        }
        if mapped.forall(_.isDefined) then Some(mapped.toList.map(_.get)) else None

  private def renderWord(word: Option[List[String]]): String = word match
    case None       => "unavailable"
    case Some(Nil)  => "ε"
    case Some(word) => word.mkString(" ")

  /** Turn rIC3's stdout (verdict, plus its BTOR2 witness for the `sat` case)
    * into a compact user-facing verdict. `emptyBad` is the empty-word
    * answer, already known at compile time (see `Btor2`'s doc-comment) — it
    * does not come from rIC3.
    */
  def summarize(stdout: String, goal: String, alphabet: List[String], emptyBad: Boolean): String =
    val outcome =
      if stdout.contains("UNSAT") then "unsat"
      else if stdout.contains("UNKNOWN") then "unknown"
      else if stdout.contains("SAT") then "sat"
      else "unrecognized"

    val mainProved = outcome == "unsat"
    val allProved = mainProved && !emptyBad
    val heading =
      if allProved then
        goal match
          case "inclusion"   => "rIC3: PROVED — inclusion holds."
          case "equivalence" => "rIC3: PROVED — the languages are equivalent."
          case _             => "rIC3: PROVED — no bad prefix is reachable."
      else if outcome == "unknown" then
        goal match
          case "inclusion"   => "rIC3: UNKNOWN — could not decide inclusion within resource limits."
          case "equivalence" => "rIC3: UNKNOWN — could not decide equivalence within resource limits."
          case _             => "rIC3: UNKNOWN — could not decide safety within resource limits."
      else if outcome == "unrecognized" then "rIC3: finished, but its result was not recognized. Use --ric3-raw to inspect it."
      else
        goal match
          case "inclusion"   => "rIC3: NOT PROVED — a counterexample was found or the result is incomplete."
          case "equivalence" => "rIC3: NOT PROVED — the languages differ or the result is incomplete."
          case _             => "rIC3: NOT PROVED — a bad prefix is reachable."

    if outcome == "unrecognized" then heading
    else
      val lines = scala.collection.mutable.ArrayBuffer(heading)
      val mainLabel = goal match
        case "inclusion"   => "no nonempty counterexample"
        case "equivalence" => "no nonempty distinguishing word"
        case _             => "no nonempty bad prefix"
      val mainMark = if mainProved then "✓" else if outcome == "unknown" then "?" else "✗"
      var mainLine = s"  $mainMark $mainLabel ($outcome)"
      if outcome == "sat" then mainLine += s" — witness: ${renderWord(counterexampleWord(stdout, alphabet))}"
      lines += mainLine

      val emptyLabel = goal match
        case "inclusion"   => "no empty-word counterexample"
        case "equivalence" => "no empty-word distinction"
        case _             => "no empty-word bad prefix"
      val emptyMark = if !emptyBad then "✓" else "✗"
      var emptyLine = s"  $emptyMark $emptyLabel (${if emptyBad then "sat" else "unsat"})"
      if emptyBad then emptyLine += " — witness: ε"
      lines += emptyLine

      if !allProved && outcome != "unknown" then lines += "  Run again with --ric3-raw to inspect rIC3's counterexample witness."
      lines.mkString("\n")

  /** Run rIC3 on a generated BTOR2 model, returning its exit status.
    *
    * The model is stored in a temporary directory, so checking a property
    * does not leave a generated `.btor2` file in the working tree.
    */
  def run(
      model: String,
      executable: File,
      goal: String,
      alphabet: List[String],
      emptyBad: Boolean,
      mode: String = "ic3",
      rawOutput: Boolean = false,
  ): Int =
    if !executable.isFile then throw Ric3Error(s"rIC3 executable not found: $executable")
    if !executable.canExecute then throw Ric3Error(s"rIC3 executable is not executable: $executable")
    val directory = Files.createTempDirectory("brasp-ric3-")
    val modelPath = directory.resolve("model.btor2")
    Files.writeString(modelPath, model)
    val stdoutBuffer = new ByteArrayOutputStream()
    val stderrBuffer = new ByteArrayOutputStream()
    val processIO = new ProcessIO(
      _.close(),
      stdout => try stdout.transferTo(stdoutBuffer) finally stdout.close(),
      stderr => try stderr.transferTo(stderrBuffer) finally stderr.close(),
    )
    // `--cex` alone is enough: rIC3 prints its BTOR2 witness on stdout right
    // after `SAT` (auto-managing its own certificate tempfile internally).
    val command = Seq(executable.getAbsolutePath, "check", modelPath.toString, "--cex", "--ui", "false", mode)
    val exitCode =
      try command.run(processIO).exitValue()
      catch case error: Exception => throw Ric3Error(s"could not run rIC3: ${error.getMessage}")
    val stdoutText = stdoutBuffer.toString(StandardCharsets.UTF_8)
    val stderrText = stderrBuffer.toString(StandardCharsets.UTF_8)
    if rawOutput then print(stdoutText) else println(summarize(stdoutText, goal, alphabet, emptyBad))
    if stderrText.nonEmpty then Console.err.print(stderrText)
    exitCode
