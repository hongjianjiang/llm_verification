package brasp

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Compile Section 5 Boolean B-RASP JSON programs into shared 2LTL DAGs. */

/** The requested B-RASP construct has no direct compiler implementation. */
final case class TranslationError(message: String) extends RuntimeException(message)

private final case class CliParseError(message: String) extends RuntimeException(message)

final case class CliArgs(
    input: File,
    future: Boolean = false,
    pvwaa: Boolean = false,
    booleanAutomaton: Boolean = false,
    lustre: Boolean = false,
    kind2Safety: Boolean = false,
    kind2Subset: Option[File] = None,
    kind2Equivalent: Option[File] = None,
    runKind2: Boolean = false,
    kind2Bin: File,
    kind2Json: Boolean = false,
    json: Boolean = false,
    ltl: Boolean = false,
    dot: Boolean = false,
    brasp: Boolean = false,
    word: Option[String] = None,
)

enum CompileResult:
  case Dag(dag: FormulaDag)
  case PvwaaResult(automaton: ForwardPVWAA)
  case BooleanResult(automaton: ReverseBooleanAutomaton)

object Translator:

  /** Return the strict-future 2LTL formula for the reverse language. */
  def mirrorToFuture(past: FormulaDag): FormulaDag =
    try Ltl.mirrorDag(past)
    catch case LtlError(message) => throw TranslationError(message)

  def renderProgram(dag: FormulaDag): String =
    val lines = dag.definitions.toVector.map { case (name, formula) => s"$name(i) := ${Ltl.render(formula)}" }
    (lines :+ "" :+ s"output := ${Ltl.render(dag.output)}" :+ s"evaluate at ${dag.evaluationPoint}").mkString("\n")

  private def defaultKind2Bin: File =
    val cwd = new File(System.getProperty("user.dir"))
    new File(cwd.getParentFile, "kind2")

  private def parseArgs(args: Array[String]): CliArgs =
    var input: Option[File] = None
    var future = false
    var pvwaa = false
    var booleanAutomaton = false
    var lustre = false
    var kind2Safety = false
    var kind2Subset: Option[File] = None
    var kind2Equivalent: Option[File] = None
    var runKind2 = false
    var kind2Bin: File = defaultKind2Bin
    var kind2Json = false
    var jsonOut = false
    var ltlOut = false
    var dotOut = false
    var braspOut = false
    var word: Option[String] = None

    def fail(message: String): Nothing = throw CliParseError(message)
    def takeValue(flag: String, it: Iterator[String]): String =
      if !it.hasNext then fail(s"argument $flag: expected one argument") else it.next()

    val it = args.iterator
    while it.hasNext do
      it.next() match
        case "--future"            => future = true
        case "--pvwaa"              => pvwaa = true
        case "--boolean-automaton"  => booleanAutomaton = true
        case "--lustre"             => lustre = true
        case "--kind2-safety"       => kind2Safety = true
        case "--kind2-subset"       => kind2Subset = Some(File(takeValue("--kind2-subset", it)))
        case "--kind2-equivalent"   => kind2Equivalent = Some(File(takeValue("--kind2-equivalent", it)))
        case "--run-kind2"          => runKind2 = true
        case "--kind2-bin"          => kind2Bin = File(takeValue("--kind2-bin", it))
        case "--kind2-json"         => kind2Json = true
        case "--json"               => jsonOut = true
        case "--ltl"                 => ltlOut = true
        case "--dot"                 => dotOut = true
        case "--brasp"                => braspOut = true
        case "--word"                => word = Some(takeValue("--word", it))
        case other if other.startsWith("-") && other != "-" => fail(s"unrecognized arguments: $other")
        case other =>
          if input.isDefined then fail(s"unrecognized arguments: $other")
          input = Some(File(other))

    val resolvedInput = input.getOrElse(fail("the following arguments are required: input"))
    CliArgs(
      input = resolvedInput,
      future = future,
      pvwaa = pvwaa,
      booleanAutomaton = booleanAutomaton,
      lustre = lustre,
      kind2Safety = kind2Safety,
      kind2Subset = kind2Subset,
      kind2Equivalent = kind2Equivalent,
      runKind2 = runKind2,
      kind2Bin = kind2Bin,
      kind2Json = kind2Json,
      json = jsonOut,
      ltl = ltlOut,
      dot = dotOut,
      brasp = braspOut,
      word = word,
    )

  private def readFile(file: File): String =
    Files.readString(file.toPath, StandardCharsets.UTF_8)

  private val graphSourceExtensions = List(".brasp", ".json", ".ltl")

  private def inputStem(input: File): String =
    val name = input.getName
    graphSourceExtensions.find(name.endsWith).fold(name)(ext => name.dropRight(ext.length))

  /** Where `--dot` auto-saves a copy: `graphs/<input stem>[_suffix].dot`,
    * relative to the current working directory. `suffix` disambiguates
    * multiple `--dot`-producing stages for the same input (e.g. `--pvwaa
    * --dot` vs `--boolean-automaton --dot`) so they don't overwrite each
    * other.
    */
  private def graphOutputPath(input: File, suffix: String = ""): File =
    val tag = if suffix.isEmpty then "" else s"_$suffix"
    new File("graphs", s"${inputStem(input)}$tag.dot")

  /** Where the compiled 2LTL formula auto-saves: `ltl/<input stem>[_suffix].ltl`.
    * `suffix` disambiguates the strict-future mirror from the strict-past
    * original for the same input stem.
    */
  private def ltlOutputPath(input: File, suffix: String = ""): File =
    val tag = if suffix.isEmpty then "" else s"_$suffix"
    new File("ltl", s"${inputStem(input)}$tag.ltl")

  /** Where `--brasp` auto-saves a copy: `examples/brasp/<input stem>.brasp`.
    * Note this can share a path with a hand-written example source of the
    * same input stem (e.g. `at_least_two_a`), in which case the round-trip
    * output overwrites it.
    */
  private def braspOutputPath(input: File): File =
    new File("examples/brasp", s"${inputStem(input)}.brasp")

  private def saveArtifact(outFile: File, content: String): Unit =
    Option(outFile.getParentFile).foreach(_.mkdirs())
    Files.writeString(outFile.toPath, content)
    System.err.println(s"Saved $outFile")

  /** `saveArtifact`, but reporting an `IOException` as a CLI exit code
    * instead of raising: `Some(2)` on failure (already printed to stderr),
    * `None` on success.
    */
  private def trySaveArtifact(outFile: File, content: String): Option[Int] =
    try
      saveArtifact(outFile, content)
      None
    catch
      case error: java.io.IOException =>
        System.err.println(s"translator: could not save ${outFile.getName}: ${error.getMessage}")
        Some(2)

  /** Load a B-RASP program, dispatching on file extension: `.brasp` files
    * use the textual syntax (`BraspText`), everything else is parsed as
    * JSON (`Brasp.fromJson`).
    */
  private def isLtlFile(file: File): Boolean = file.getName.endsWith(".ltl")

  private def loadProgram(file: File): Program =
    if isLtlFile(file) then
      throw TranslationError(s"$file: '.ltl' is a compiled-formula file, not a B-RASP program; use .brasp or JSON here")
    val text = readFile(file)
    if file.getName.endsWith(".brasp") then BraspText.parse(text)
    else Brasp.fromJson(Json.parse(text))

  /** Mirror to strict-future 2LTL only if not already there: a `.ltl` file
    * may be loaded directly at either logic, unlike a freshly compiled
    * B-RASP program (always strict-past).
    */
  private def toFuture(dag: FormulaDag): FormulaDag =
    if dag.logic == Logic.FutureStrict then dag else mirrorToFuture(dag)

  /** Split `--word` text into symbols: whitespace-separated if it contains
    * whitespace (for multi-character symbols), otherwise one symbol per
    * character (for the common single-character-alphabet case).
    */
  private def splitWord(text: String): IndexedSeq[String] =
    val trimmed = text.trim
    if trimmed.isEmpty then IndexedSeq.empty
    else if trimmed.exists(_.isWhitespace) then trimmed.split("\\s+").toIndexedSeq
    else trimmed.map(_.toString).toIndexedSeq

  def run(args: Array[String]): Int =
    val parsed =
      try parseArgs(args)
      catch
        case CliParseError(message) =>
          System.err.println(s"translator: error: $message")
          return 2

    val kind2Modes = List(parsed.kind2Safety, parsed.kind2Subset.isDefined, parsed.kind2Equivalent.isDefined).count(identity)
    if kind2Modes > 1 then
      System.err.println("translator: error: choose only one of --kind2-safety, --kind2-subset, or --kind2-equivalent")
      return 2
    if parsed.runKind2 && kind2Modes == 0 then
      System.err.println("translator: error: --run-kind2 requires --kind2-safety, --kind2-subset, or --kind2-equivalent")
      return 2
    val compiled: CompileResult =
      try
        val translated: FormulaDag =
          if isLtlFile(parsed.input) then
            if parsed.kind2Subset.isDefined || parsed.kind2Equivalent.isDefined then
              throw TranslationError(
                "'.ltl' input is not supported with --kind2-subset/--kind2-equivalent; use a B-RASP program (.brasp or JSON) there"
              )
            LtlText.parse(readFile(parsed.input))
          else
            val inputProgram = loadProgram(parsed.input)
            if parsed.kind2Subset.isDefined then
              BraspToLtl.translateProgram(Inclusion.counterexampleProgram(inputProgram, loadProgram(parsed.kind2Subset.get)))
            else if parsed.kind2Equivalent.isDefined then
              BraspToLtl.translateProgram(Inclusion.equivalenceCounterexampleProgram(inputProgram, loadProgram(parsed.kind2Equivalent.get)))
            else BraspToLtl.translateProgram(inputProgram)

        val needsBooleanAutomaton =
          parsed.kind2Subset.isDefined || parsed.kind2Equivalent.isDefined || parsed.kind2Safety ||
            parsed.lustre || parsed.booleanAutomaton
        if needsBooleanAutomaton then
          CompileResult.BooleanResult(BooleanAutomaton.fromForwardPvwaa(Pvwaa.fromFuture2ltl(toFuture(translated))))
        else if parsed.pvwaa then
          CompileResult.PvwaaResult(Pvwaa.fromFuture2ltl(toFuture(translated)))
        else if parsed.future then
          CompileResult.Dag(mirrorToFuture(translated))
        else
          CompileResult.Dag(translated)
      catch
        case error: java.io.IOException =>
          System.err.println(s"translator: ${error.getMessage}")
          return 2
        case JsonError(message) =>
          System.err.println(s"translator: $message")
          return 2
        case BraspError(message) =>
          System.err.println(s"translator: $message")
          return 2
        case TranslationError(message) =>
          System.err.println(s"translator: $message")
          return 2
        case PVWAAError(message) =>
          System.err.println(s"translator: $message")
          return 2
        case LustreError(message) =>
          System.err.println(s"translator: $message")
          return 2
        case LtlError(message) =>
          System.err.println(s"translator: $message")
          return 2

    if parsed.word.isDefined then
      try
        val word = splitWord(parsed.word.get)
        val accepted = compiled match
          case CompileResult.BooleanResult(automaton) => BooleanAutomaton.accepts(automaton, word)
          // the PVWAA accepts reverse(w); --word always answers about w itself.
          case CompileResult.PvwaaResult(automaton) => Pvwaa.accepts(automaton, word.reverse)
          case CompileResult.Dag(dag) =>
            Ltl.evaluate(dag, if dag.logic == Logic.FutureStrict then word.reverse else word)
        println(accepted)
        return 0
      catch
        case PVWAAError(message) =>
          System.err.println(s"translator: $message")
          return 2

    compiled match
      case CompileResult.BooleanResult(automaton) =>
        if parsed.dot then
          val dot = BooleanAutomaton.toDot(automaton)
          trySaveArtifact(graphOutputPath(parsed.input, suffix = "boolean_automaton"), dot) match
            case Some(code) => return code
            case None       => ()
          println(dot)
        else if parsed.kind2Subset.isDefined || parsed.kind2Equivalent.isDefined || parsed.kind2Safety then
          val harness =
            if parsed.kind2Subset.isDefined then
              Kind2.generateSafety(automaton, monitorName = "subset_monitor", mainName = "kind2_subset")
            else if parsed.kind2Equivalent.isDefined then
              Kind2.generateSafety(automaton, monitorName = "equivalence_monitor", mainName = "kind2_equivalence")
            else Kind2.generateSafety(automaton)
          if parsed.runKind2 then
            try
              val goal =
                if parsed.kind2Subset.isDefined then "inclusion"
                else if parsed.kind2Equivalent.isDefined then "equivalence"
                else "safety"
              return Kind2.run(harness, parsed.kind2Bin, goal, automaton.source.alphabet, parsed.kind2Json)
            catch
              case Kind2Error(message) =>
                System.err.println(s"translator: $message")
                return 2
          println(harness)
        else if parsed.lustre then
          println(Lustre.generate(automaton))
        else
          trySaveArtifact(graphOutputPath(parsed.input, suffix = "boolean_automaton"), BooleanAutomaton.toDot(automaton)) match
            case Some(code) => return code
            case None       => ()
          println(if parsed.json then Json.render(BooleanAutomaton.toJson(automaton)) else BooleanAutomaton.render(automaton))
      case CompileResult.PvwaaResult(automaton) =>
        if parsed.dot then
          val dot = Pvwaa.toDot(automaton)
          trySaveArtifact(graphOutputPath(parsed.input, suffix = "pvwaa"), dot) match
            case Some(code) => return code
            case None       => ()
          println(dot)
        else
          trySaveArtifact(graphOutputPath(parsed.input, suffix = "pvwaa"), Pvwaa.toDot(automaton)) match
            case Some(code) => return code
            case None       => ()
          println(if parsed.json then Json.render(Pvwaa.toJson(automaton)) else Pvwaa.render(automaton))
      case CompileResult.Dag(dag) =>
        if parsed.brasp then
          try
            val rendered = BraspText.render(LtlToBrasp.translate(dag))
            trySaveArtifact(braspOutputPath(parsed.input), rendered) match
              case Some(code) => return code
              case None       => ()
            println(rendered)
          catch
            case LtlToBrasp.ReverseTranslationError(message) =>
              System.err.println(s"translator: $message")
              return 2
            case BraspError(message) =>
              System.err.println(s"translator: $message")
              return 2
        else
          val ltlSuffix = if dag.logic == Logic.FutureStrict then "future" else ""
          trySaveArtifact(ltlOutputPath(parsed.input, suffix = ltlSuffix), LtlText.render(dag)) match
            case Some(code) => return code
            case None       => ()
          println(
            if parsed.ltl then LtlText.render(dag)
            else if parsed.json then Json.render(Ltl.dagToJson(dag))
            else renderProgram(dag)
          )
    0

  def main(args: Array[String]): Unit = System.exit(run(args))
