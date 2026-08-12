package brasp

import java.io.{ByteArrayOutputStream, File}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.sys.process.ProcessIO
import scala.sys.process.stringSeqToProcess

/** Kind2 harness generation for Lustre Boolean-summary monitors. */

/** Kind2 could not be started. */
final case class Kind2Error(message: String) extends RuntimeException(message)

object Kind2:
  import Lustre.generate as generateLustre

  /** Every safety/equivalence run is a single continuous, non-restarting
    * word: the monitor's general `valid`/`start`/`last` interface is pinned
    * to "always consume `symbol`, reset only before the first tick, every
    * tick is a candidate word end" so a Kind2 counterexample trace is
    * exactly one input word, symbol per tick — no stutters or mid-trace
    * resets to reason about.
    */
  private def pinnedActuals(symbolExpr: String): String = s"$symbolExpr, true, (true -> false), true"

  /** Emit a Kind2 model proving the monitor's bad language is unreachable.
    *
    * `accept_prefix` is intentionally named `bad` in the wrapper: invoke
    * this only when the compiled B-RASP program recognizes violation
    * prefixes. A Kind2 counterexample is then a concrete violating input
    * word, one alphabet-index symbol per tick.
    */
  def generateSafety(
      automaton: ReverseBooleanAutomaton,
      monitorName: String = "brasp_monitor",
      mainName: String = "kind2_safety",
  ): String =
    val monitor = generateLustre(automaton, monitorName)
    val alphabetSize = automaton.source.alphabet.length
    val emptyBad = BooleanAutomaton.diagonal(automaton, automaton.initial)(automaton.source.initialState)
    List(
      monitor.stripTrailing(),
      "",
      s"node $mainName(symbol: int) returns (bad: bool; empty_bad: bool);",
      "con",
      s"  assume 0 <= symbol and symbol < $alphabetSize;",
      "  guarantee not bad;",
      "  guarantee not empty_bad;",
      "noc",
      "var",
      "  accept_prefix, accept_word, input_ok: bool;",
      "let",
      s"  (accept_prefix, accept_word, input_ok) = $monitorName(${pinnedActuals("symbol")});",
      "  bad = accept_prefix;",
      s"  empty_bad = ${if emptyBad then "true" else "false"};",
      "  --%MAIN ;",
      "tel",
      "",
    ).mkString("\n")

  /** Emit a Kind2 contract proving two monitors accept the same prefixes. */
  def generateEquivalence(
      left: ReverseBooleanAutomaton,
      right: ReverseBooleanAutomaton,
      leftName: String = "left_monitor",
      rightName: String = "right_monitor",
      mainName: String = "kind2_equivalence",
  ): String =
    if left.source.alphabet != right.source.alphabet then
      throw IllegalArgumentException("Kind2 equivalence requires equal ordered alphabets")
    val leftLustre = generateLustre(left, leftName)
    val rightLustre = generateLustre(right, rightName)
    val alphabetSize = left.source.alphabet.length
    val leftEmpty = BooleanAutomaton.diagonal(left, left.initial)(left.source.initialState)
    val rightEmpty = BooleanAutomaton.diagonal(right, right.initial)(right.source.initialState)
    List(
      leftLustre.stripTrailing(),
      "",
      rightLustre.stripTrailing(),
      "",
      s"node $mainName(symbol: int) returns (left_ok: bool; right_ok: bool; left_empty: bool; right_empty: bool);",
      "con",
      s"  assume 0 <= symbol and symbol < $alphabetSize;",
      "  guarantee left_ok = right_ok;",
      "  guarantee left_empty = right_empty;",
      "noc",
      "var",
      "  left_accept_prefix, left_accept_word, left_input_ok: bool;",
      "  right_accept_prefix, right_accept_word, right_input_ok: bool;",
      "let",
      s"  (left_accept_prefix, left_accept_word, left_input_ok) = $leftName(${pinnedActuals("symbol")});",
      s"  (right_accept_prefix, right_accept_word, right_input_ok) = $rightName(${pinnedActuals("symbol")});",
      "  left_ok = left_accept_prefix;",
      "  right_ok = right_accept_prefix;",
      s"  left_empty = ${if leftEmpty then "true" else "false"};",
      s"  right_empty = ${if rightEmpty then "true" else "false"};",
      "  --%MAIN ;",
      "tel",
      "",
    ).mkString("\n")

  private def propertyLabel(expression: String, goal: String): String = expression match
    case "(not bad)" =>
      goal match
        case "inclusion"   => "no nonempty counterexample"
        case "equivalence" => "no nonempty distinguishing word"
        case _             => "no nonempty bad prefix"
    case "(not empty_bad)" =>
      goal match
        case "inclusion"   => "no empty-word counterexample"
        case "equivalence" => "no empty-word distinction"
        case _             => "no empty-word bad prefix"
    case other => other

  /** Extract the input word from a Kind2 JSON counterexample: the `symbol`
    * stream carries one alphabet index per tick (every tick consumes a
    * symbol, per `pinnedActuals`).
    */
  private def counterexampleWord(property: JsonValue, alphabet: List[String]): Option[List[String]] =
    if property.field("expr").flatMap(_.asString).contains("(not empty_bad)") then Some(Nil)
    else
      val streamsOpt =
        for
          counterexamples <- property.field("counterExample").flatMap(_.asArray).filter(_.nonEmpty)
          root <- counterexamples.headOption
          streams <- root.field("streams").flatMap(_.asArray)
        yield streams
      streamsOpt.flatMap { streams =>
        val symbolStream = streams.find(stream =>
          stream.field("class").flatMap(_.asString).contains("input") &&
            stream.field("name").flatMap(_.asString).contains("symbol")
        )
        symbolStream.flatMap { stream =>
          val values = scala.collection.mutable.HashMap.empty[Int, Int]
          val instantValues = stream.field("instantValues").flatMap(_.asArray).getOrElse(Vector.empty)
          for entry <- instantValues do
            entry.asArray.foreach { pair =>
              if pair.length == 2 then
                for instant <- pair(0).asInt; symbolIndex <- pair(1).asInt do values(instant) = symbolIndex
            }
          val mapped = values.keys.toList.sorted.map { instant =>
            val index = values(instant)
            if index >= 0 && index < alphabet.length then Some(alphabet(index)) else None
          }
          if mapped.forall(_.isDefined) then Some(mapped.map(_.get)) else None
        }
      }

  private def renderWord(word: Option[List[String]]): String = word match
    case None       => "unavailable"
    case Some(Nil)  => "ε"
    case Some(word) => word.mkString(" ")

  /** Turn Kind2's JSON property report into a compact user-facing verdict. */
  def summarize(output: String, goal: String, alphabet: List[String] = Nil): String =
    val eventsOpt =
      try Some(Json.parse(output))
      catch case _: JsonError => None
    eventsOpt.flatMap(_.asArray) match
      case None =>
        "Kind2 finished, but its result was not valid JSON. Use --kind2-json to inspect it."
      case Some(events) =>
        val order = Map("(not bad)" -> 0, "(not empty_bad)" -> 1)
        val properties = events
          .filter(_.field("objectType").flatMap(_.asString).contains("property"))
          .sortBy(p => order.getOrElse(p.field("expr").flatMap(_.asString).getOrElse(""), 2))
        if properties.isEmpty then
          "Kind2 finished without reporting a property result. Use --kind2-json to inspect it."
        else
          val valid = properties.filter(p =>
            p.field("answer").flatMap(_.field("value")).flatMap(_.asString).contains("valid")
          )
          val heading =
            if valid.length == properties.length then
              goal match
                case "inclusion"   => "Kind2: PROVED — inclusion holds."
                case "equivalence" => "Kind2: PROVED — the languages are equivalent."
                case _             => "Kind2: PROVED — no bad prefix is reachable."
            else
              goal match
                case "inclusion"   => "Kind2: NOT PROVED — a counterexample or incomplete result was reported."
                case "equivalence" => "Kind2: NOT PROVED — the languages differ or the result is incomplete."
                case _             => "Kind2: NOT PROVED — a bad prefix may be reachable."
          val lines = scala.collection.mutable.ArrayBuffer(heading)
          for property <- properties do
            val answer = property.field("answer").flatMap(_.field("value")).flatMap(_.asString).getOrElse("unknown")
            val mark = if answer == "valid" then "✓" else "✗"
            val label = property
              .field("expr")
              .flatMap(_.asString)
              .orElse(property.field("name").flatMap(_.asString))
              .getOrElse("property")
            var line = s"  $mark ${propertyLabel(label, goal)} ($answer)"
            if answer == "falsifiable" then
              line += s" — witness: ${renderWord(counterexampleWord(property, alphabet))}"
            lines += line
          if valid.length != properties.length then
            lines += "  Run again with --kind2-json to inspect Kind2's counterexample trace."
          lines.mkString("\n")

  /** Run Kind2 on a generated Lustre harness, returning its exit status.
    *
    * The harness is stored in a temporary directory, so checking a property
    * does not leave a generated `.lus` file in the working tree.
    */
  def run(harness: String, executable: File, goal: String, alphabet: List[String], rawJson: Boolean = false): Int =
    if !executable.isFile then throw Kind2Error(s"Kind2 executable not found: $executable")
    if !executable.canExecute then throw Kind2Error(s"Kind2 executable is not executable: $executable")
    val directory = Files.createTempDirectory("brasp-kind2-")
    val harnessPath = directory.resolve("harness.lus")
    Files.writeString(harnessPath, harness)
    val stdoutBuffer = new ByteArrayOutputStream()
    val stderrBuffer = new ByteArrayOutputStream()
    val processIO = new ProcessIO(
      _.close(),
      stdout => try stdout.transferTo(stdoutBuffer) finally stdout.close(),
      stderr => try stderr.transferTo(stderrBuffer) finally stderr.close(),
    )
    val exitCode =
      try Seq(executable.getAbsolutePath, "-json", harnessPath.toString).run(processIO).exitValue()
      catch case error: Exception => throw Kind2Error(s"could not run Kind2: ${error.getMessage}")
    val stdoutText = stdoutBuffer.toString(StandardCharsets.UTF_8)
    val stderrText = stderrBuffer.toString(StandardCharsets.UTF_8)
    if stdoutText.nonEmpty then
      if rawJson then print(stdoutText) else println(summarize(stdoutText, goal, alphabet))
    if stderrText.nonEmpty then Console.err.print(stderrText)
    exitCode
