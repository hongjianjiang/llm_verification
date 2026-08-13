package brasp

import java.io.{ByteArrayOutputStream, File}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.sys.process.ProcessIO
import scala.sys.process.stringSeqToProcess

/** ABC (https://github.com/berkeley-abc/abc) PDR runner for `Aiger`-encoded
  * reverse Boolean-summary automata — the AIGER-backed sibling of `Ric3`.
  *
  * Uses the classic (non-`&`) command family: `read_aiger` (binary AIGER
  * only) + `pdr`. The GIA package's `&read` also reads AIGER and accepts
  * ASCII too, but was found to silently ignore latch reset values — every
  * latch came back "DC-valued" regardless of what the file declared,
  * giving wrong verdicts rather than an error. `Aiger` sidesteps needing
  * `read_aiger`'s reset support at all: every latch it emits is
  * canonicalized to physically reset to 0.
  */

/** ABC could not be started. */
final case class AbcError(message: String) extends RuntimeException(message)

object Abc:

  private def renderWord(word: Option[List[String]]): String = word match
    case None       => "unavailable"
    case Some(Nil)  => "ε"
    case Some(word) => word.mkString(" ")

  /** Turn ABC's stdout (from the script in `run`) into a compact
    * user-facing verdict. `emptyBad` is the empty-word answer, already
    * known at compile time (see `Btor2`'s doc-comment) — it does not come
    * from ABC.
    */
  def summarize(stdout: String, goal: String, emptyBad: Boolean): String =
    val outcome =
      if stdout.contains("Property proved") then "unsat"
      else if stdout.contains("was asserted") || stdout.contains("counter-example") then "sat"
      else "unrecognized"

    val mainProved = outcome == "unsat"
    val allProved = mainProved && !emptyBad
    val heading =
      if allProved then
        goal match
          case "inclusion"   => "ABC: PROVED — inclusion holds."
          case "equivalence" => "ABC: PROVED — the languages are equivalent."
          case _             => "ABC: PROVED — no bad prefix is reachable."
      else if outcome == "unrecognized" then "ABC: finished, but its result was not recognized. Use --abc-raw to inspect it."
      else
        goal match
          case "inclusion"   => "ABC: NOT PROVED — a counterexample was found or the result is incomplete."
          case "equivalence" => "ABC: NOT PROVED — the languages differ or the result is incomplete."
          case _             => "ABC: NOT PROVED — a bad prefix is reachable."

    if outcome == "unrecognized" then heading
    else
      val lines = scala.collection.mutable.ArrayBuffer(heading)
      val mainLabel = goal match
        case "inclusion"   => "no nonempty counterexample"
        case "equivalence" => "no nonempty distinguishing word"
        case _             => "no nonempty bad prefix"
      val mainMark = if mainProved then "✓" else "✗"
      lines += s"  $mainMark $mainLabel ($outcome)"

      val emptyLabel = goal match
        case "inclusion"   => "no empty-word counterexample"
        case "equivalence" => "no empty-word distinction"
        case _             => "no empty-word bad prefix"
      val emptyMark = if !emptyBad then "✓" else "✗"
      var emptyLine = s"  $emptyMark $emptyLabel (${if emptyBad then "sat" else "unsat"})"
      if emptyBad then emptyLine += " — witness: ε"
      lines += emptyLine

      if !allProved then lines += "  Run again with --abc-raw to inspect ABC's raw output (no witness word extraction yet)."
      lines.mkString("\n")

  /** Run ABC's PDR on a generated AIGER model, returning its exit status.
    *
    * The model is stored in a temporary directory, so checking a property
    * does not leave a generated `.aag` file in the working tree.
    */
  def run(model: Array[Byte], executable: File, goal: String, emptyBad: Boolean, rawOutput: Boolean = false): Int =
    if !executable.isFile then throw AbcError(s"ABC executable not found: $executable")
    if !executable.canExecute then throw AbcError(s"ABC executable is not executable: $executable")
    val directory = Files.createTempDirectory("brasp-abc-")
    val modelPath = directory.resolve("model.aig")
    Files.write(modelPath, model)
    val stdoutBuffer = new ByteArrayOutputStream()
    val stderrBuffer = new ByteArrayOutputStream()
    val processIO = new ProcessIO(
      _.close(),
      stdout => try stdout.transferTo(stdoutBuffer) finally stdout.close(),
      stderr => try stderr.transferTo(stderrBuffer) finally stderr.close(),
    )
    // The classic (non-`&`) command family is used deliberately: `&read`'s
    // ASCII/GIA path was found to silently ignore latch reset values, but
    // `read_aiger` handles binary AIGER (with all-reset-to-0 latches, per
    // `Aiger`'s doc-comment) correctly.
    val script = s"read_aiger ${modelPath.toString}; pdr; print_status"
    val command = Seq(executable.getAbsolutePath, "-c", script)
    val exitCode =
      try command.run(processIO).exitValue()
      catch case error: Exception => throw AbcError(s"could not run ABC: ${error.getMessage}")
    val stdoutText = stdoutBuffer.toString(StandardCharsets.UTF_8)
    val stderrText = stderrBuffer.toString(StandardCharsets.UTF_8)
    if rawOutput then print(stdoutText) else println(summarize(stdoutText, goal, emptyBad))
    if stderrText.nonEmpty then Console.err.print(stderrText)
    exitCode
