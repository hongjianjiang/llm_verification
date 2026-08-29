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
    subset: Option[File] = None,
    equivalent: Option[File] = None,
    runNative: Boolean = false,
    runNativeConflict: Boolean = false,
    runAuto: Boolean = false,
    nativeMaxStates: Int = 4096,
    oneVariable: Boolean = false,
    aiger: Boolean = false,
    aigerMaxStates: Int = 4096,
    runAbc: Boolean = false,
    abcBin: File,
    abcRaw: Boolean = false,
    timing: Boolean = false,
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

  private def defaultAbcBin: File =
    val cwd = new File(System.getProperty("user.dir"))
    new File(cwd.getParentFile, "abc/abc")

  private def parseArgs(args: Array[String]): CliArgs =
    var input: Option[File] = None
    var future = false
    var pvwaa = false
    var booleanAutomaton = false
    var subset: Option[File] = None
    var equivalent: Option[File] = None
    var runNative = false
    var runNativeConflict = false
    var runAuto = false
    var nativeMaxStates = 4096
    var oneVariable = false
    var aigerOut = false
    var aigerMaxStates = 4096
    var runAbc = false
    var abcBin: File = defaultAbcBin
    var abcRaw = false
    var timing = false
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
        case "--subset"             => subset = Some(File(takeValue("--subset", it)))
        case "--equivalent"         => equivalent = Some(File(takeValue("--equivalent", it)))
        case "--run-native"         => runNative = true
        case "--run-native-conflict" => runNativeConflict = true
        case "--run-auto"           => runAuto = true
        case "--native-max-states"  =>
          val raw = takeValue("--native-max-states", it)
          nativeMaxStates = raw.toIntOption.getOrElse(fail(s"argument --native-max-states: expected an integer, got '$raw'"))
        case "--one-variable"       => oneVariable = true
        case "--aiger"              => aigerOut = true
        case "--aiger-max-states"   =>
          val raw = takeValue("--aiger-max-states", it)
          aigerMaxStates = raw.toIntOption.getOrElse(fail(s"argument --aiger-max-states: expected an integer, got '$raw'"))
        case "--run-abc"            => runAbc = true
        case "--abc-bin"            => abcBin = File(takeValue("--abc-bin", it))
        case "--abc-raw"            => abcRaw = true
        case "--timing"             => timing = true
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
      subset = subset,
      equivalent = equivalent,
      runNative = runNative,
      runNativeConflict = runNativeConflict,
      runAuto = runAuto,
      nativeMaxStates = nativeMaxStates,
      oneVariable = oneVariable,
      aiger = aigerOut,
      aigerMaxStates = aigerMaxStates,
      runAbc = runAbc,
      abcBin = abcBin,
      abcRaw = abcRaw,
      timing = timing,
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

  /** `--run-native`'s verdict: the same three-way PROVED/NOT PROVED/UNKNOWN
    * shape `Abc`'s summaries use, but computed entirely in-process from
    * `BooleanAutomaton.reachable` — no BTOR2/AIGER model,
    * no external solver. `dfa.truncated` (the exploration budget was too
    * small to reach a verdict either way) is this check's only source of
    * `UNKNOWN`; unlike an external solver there is no timeout/resource
    * notion beyond that budget.
    */
  /** Shared rendering for both native backends' PROVED/NOT PROVED/UNKNOWN
    * verdict: `backend` is the label line prefix (`nativeSummary` uses
    * `brasp-native`, `conflictSummary` uses `brasp-native-conflict`),
    * `nonemptyWitness` is `None` when no nonempty bad prefix was found —
    * either because none exists, or (when `truncated`) because the search
    * gave up before finding out either way.
    */
  private def renderVerdict(
      backend: String,
      truncated: Boolean,
      exploredCount: Int,
      nonemptyWitness: Option[List[String]],
      goal: String,
      emptyBad: Boolean,
  ): String =
    if truncated then
      goal match
        case "inclusion"   => s"$backend: UNKNOWN — inclusion not decided within $exploredCount states (raise --native-max-states)."
        case "equivalence" => s"$backend: UNKNOWN — equivalence not decided within $exploredCount states (raise --native-max-states)."
        case _             => s"$backend: UNKNOWN — safety not decided within $exploredCount states (raise --native-max-states)."
    else
      val mainProved = nonemptyWitness.isEmpty
      // The empty word is reported but excluded from the verdict: these
      // languages are subsets of Sigma^+ (see `Abc.summarize`).
      val allProved = mainProved
      val heading =
        if allProved then
          goal match
            case "inclusion"   => s"$backend: PROVED — inclusion holds."
            case "equivalence" => s"$backend: PROVED — the languages are equivalent."
            case _             => s"$backend: PROVED — no bad prefix is reachable."
        else
          goal match
            case "inclusion"   => s"$backend: NOT PROVED — a counterexample was found."
            case "equivalence" => s"$backend: NOT PROVED — the languages differ."
            case _             => s"$backend: NOT PROVED — a bad prefix is reachable."
      val mainLabel = goal match
        case "inclusion"   => "no nonempty counterexample"
        case "equivalence" => "no nonempty distinguishing word"
        case _             => "no nonempty bad prefix"
      val mainMark = if mainProved then "✓" else "✗"
      var mainLine = s"  $mainMark $mainLabel (${if mainProved then "unsat" else "sat"})"
      nonemptyWitness.foreach(word => mainLine += s" — witness: ${if word.isEmpty then "ε" else word.mkString(" ")}")
      val emptyLabel = goal match
        case "inclusion"   => "no empty-word counterexample"
        case "equivalence" => "no empty-word distinction"
        case _             => "no empty-word bad prefix"
      val emptyMark = if !emptyBad then "✓" else "✗"
      var emptyLine = s"  $emptyMark $emptyLabel (${if emptyBad then "sat" else "unsat"})"
      if emptyBad then emptyLine += " — witness: ε (excluded: languages are subsets of Σ⁺)"
      List(heading, mainLine, emptyLine).mkString("\n")

  def nativeSummary(dfa: ReachableDfa, alphabet: List[String], goal: String, emptyBad: Boolean): String =
    val nonemptyWitness = if dfa.truncated then None else BooleanAutomaton.witness(dfa, alphabet)
    renderVerdict("brasp-native", dfa.truncated, dfa.stateCount, nonemptyWitness, goal, emptyBad)

  /** `--run-native-conflict`'s verdict — same shape as `nativeSummary`, but
    * from `BooleanAutomaton.conflictWitness`'s on-the-fly search instead of
    * a fully materialized `reachable` DFA. See that function's doc-comment
    * for how the two searches differ.
    */
  def conflictSummary(result: BooleanAutomaton.ConflictResult, goal: String, emptyBad: Boolean): String =
    renderVerdict("brasp-native-conflict", result.truncated, result.statesVisited, result.witness, goal, emptyBad)

  /** Encode `automaton` to AIGER (`Aiger.generateSafetyAuto`, itself already
    * tiered: quick DFA attempt, then explicit table, then full-budget DFA)
    * and run ABC's `pdr` on it, printing `Abc.summarize`'s verdict. Shared
    * by `--run-abc` and `--run-auto`'s escalation path — both end up
    * needing exactly this, the only difference being what runs first.
    */
  private def runAbcBackend(automaton: ReverseBooleanAutomaton, parsed: CliArgs, goal: String, emptyBad: Boolean): Int =
    val encodeStart = System.nanoTime()
    val model =
      try Aiger.generateSafetyAuto(automaton, parsed.aigerMaxStates)
      catch
        case AigerError(message) =>
          System.err.println(s"translator: $message")
          return 2
    Phases.encodeNs = System.nanoTime() - encodeStart
    val solveStart = System.nanoTime()
    try Abc.run(model, parsed.abcBin, goal, emptyBad, parsed.abcRaw)
    catch
      case AbcError(message) =>
        System.err.println(s"translator: $message")
        2
    finally
      Phases.searchNs = System.nanoTime() - solveStart
      Phases.searchLabel = "abc"

  /** Split `--word` text into symbols: whitespace-separated if it contains
    * whitespace (for multi-character symbols), otherwise one symbol per
    * character (for the common single-character-alphabet case) — unless
    * the whole trimmed text is itself exactly one of `alphabet`'s symbols,
    * in which case it's a length-1 word of that symbol. Without that
    * check, a length-1 word could never be written for a multi-character
    * alphabet: e.g. alphabet `{111, 000}` and `--word 111` would
    * char-split into `1`, `1`, `1` — none of them valid symbols — since
    * there's no whitespace to signal "don't split per character", and
    * trimming means padding with a stray space doesn't help either.
    */
  private def splitWord(text: String, alphabet: List[String]): IndexedSeq[String] =
    val trimmed = text.trim
    if trimmed.isEmpty then IndexedSeq.empty
    else if alphabet.contains(trimmed) then IndexedSeq(trimmed)
    else if trimmed.exists(_.isWhitespace) then trimmed.split("\\s+").toIndexedSeq
    else trimmed.map(_.toString).toIndexedSeq

  private def alphabetOf(compiled: CompileResult): List[String] = compiled match
    case CompileResult.BooleanResult(automaton) => automaton.source.alphabet
    case CompileResult.PvwaaResult(automaton)   => automaton.alphabet
    case CompileResult.Dag(dag)                 => dag.alphabet.getOrElse(Nil)

  /** Wall-clock phase breakdown for `--timing`, in nanoseconds.
    *
    * Phases are accumulated as the run proceeds and reported once, by
    * `run`, so every exit path gets the same line. The total is
    * *in-process*: it excludes JVM startup and class loading, so it reads a
    * few hundred milliseconds below a `time` measured around the whole
    * command. `compile` covers everything from reading the input through
    * the Boolean-summary automaton (the one-variable translation included,
    * when `--one-variable` asked for it); `encode` is the AIGER model
    * build; `solve` is the backend's own search --- the ABC subprocess, or
    * the in-process reachability exploration.
    */
  private object Phases:
    var enabled = false
    var compileNs = 0L
    var encodeNs = 0L
    var searchNs = 0L
    var searchLabel = "solve"

    // Locale.ROOT: a decimal point regardless of the host locale, so the
    // line stays machine-readable when a benchmark script parses it.
    private def seconds(ns: Long): String = String.format(java.util.Locale.ROOT, "%.2f", ns / 1e9)

    /** One line to stderr, so it never mixes into a redirected verdict. */
    def report(enabled: Boolean): Unit =
      if enabled then
        val parts = scala.collection.mutable.ArrayBuffer(s"compile ${seconds(compileNs)}s")
        if encodeNs > 0 then parts += s"encode ${seconds(encodeNs)}s"
        if searchNs > 0 then parts += s"$searchLabel ${seconds(searchNs)}s"
        parts += s"total ${seconds(compileNs + encodeNs + searchNs)}s"
        System.err.println(s"timing: ${parts.mkString("  ")}")

  def run(args: Array[String]): Int =
    try runStages(args)
    finally Phases.report(Phases.enabled)

  private def runStages(args: Array[String]): Int =
    val parsed =
      try parseArgs(args)
      catch
        case CliParseError(message) =>
          System.err.println(s"translator: error: $message")
          return 2
    Phases.enabled = parsed.timing

    if parsed.subset.isDefined && parsed.equivalent.isDefined then
      System.err.println("translator: error: choose only one of --subset or --equivalent")
      return 2
    val compileStart = System.nanoTime()
    val compiled: CompileResult =
      try
        val translated: FormulaDag =
          if isLtlFile(parsed.input) then
            if parsed.subset.isDefined || parsed.equivalent.isDefined then
              throw TranslationError(
                "'.ltl' input is not supported with --subset/--equivalent; use a B-RASP program (.brasp or JSON) there"
              )
            LtlText.parse(readFile(parsed.input))
          else
            val inputProgram = loadProgram(parsed.input)
            if parsed.subset.isDefined then
              BraspToLtl.translateProgram(Inclusion.counterexampleProgram(inputProgram, loadProgram(parsed.subset.get)))
            else if parsed.equivalent.isDefined then
              BraspToLtl.translateProgram(Inclusion.equivalenceCounterexampleProgram(inputProgram, loadProgram(parsed.equivalent.get)))
            else BraspToLtl.translateProgram(inputProgram)

        // The classical baseline: compile the second variable away first,
        // paying the exponential up front, and hand the backends an ordinary
        // one-variable formula (hence a VWAA, with no goto atoms).
        val compiled0 =
          if parsed.oneVariable then
            try TwoLtlToOneVariable.translate(translated)
            catch
              case TwoLtlToOneVariable.TranslationTooLarge(message) =>
                System.err.println(s"translator: $message")
                return 2
          else translated

        val needsBooleanAutomaton =
          parsed.booleanAutomaton ||
            parsed.runNative || parsed.runNativeConflict || parsed.runAuto || parsed.aiger ||
            parsed.subset.isDefined || parsed.equivalent.isDefined || parsed.runAbc
        if needsBooleanAutomaton then
          CompileResult.BooleanResult(BooleanAutomaton.fromForwardPvwaa(Pvwaa.fromFuture2ltl(toFuture(compiled0))))
        else if parsed.pvwaa then
          CompileResult.PvwaaResult(Pvwaa.fromFuture2ltl(toFuture(compiled0)))
        else if parsed.future then
          CompileResult.Dag(mirrorToFuture(compiled0))
        else
          CompileResult.Dag(compiled0)
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
        case LtlError(message) =>
          System.err.println(s"translator: $message")
          return 2
    Phases.compileNs = System.nanoTime() - compileStart

    if parsed.word.isDefined then
      try
        val word = splitWord(parsed.word.get, alphabetOf(compiled))
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
        else if parsed.runNative then
          val goal =
            if parsed.subset.isDefined then "inclusion"
            else if parsed.equivalent.isDefined then "equivalence"
            else "safety"
          val emptyBad = BooleanAutomaton.diagonal(automaton, automaton.initial)(automaton.source.initialState)
          val searchStart = System.nanoTime()
          val dfa = BooleanAutomaton.reachable(automaton, parsed.nativeMaxStates)
          Phases.searchNs = System.nanoTime() - searchStart
          Phases.searchLabel = "explore"
          println(nativeSummary(dfa, automaton.source.alphabet, goal, emptyBad))
        else if parsed.runNativeConflict then
          val goal =
            if parsed.subset.isDefined then "inclusion"
            else if parsed.equivalent.isDefined then "equivalence"
            else "safety"
          val emptyBad = BooleanAutomaton.diagonal(automaton, automaton.initial)(automaton.source.initialState)
          val searchStart = System.nanoTime()
          val result = BooleanAutomaton.conflictWitness(automaton, parsed.nativeMaxStates)
          Phases.searchNs = System.nanoTime() - searchStart
          Phases.searchLabel = "explore"
          println(conflictSummary(result, goal, emptyBad))
        else if parsed.runAuto then
          val goal =
            if parsed.subset.isDefined then "inclusion"
            else if parsed.equivalent.isDefined then "equivalence"
            else "safety"
          val emptyBad = BooleanAutomaton.diagonal(automaton, automaton.initial)(automaton.source.initialState)
          // Native's cost tracks reachable-state count x alphabet size, not
          // Aiger's local/aggregate Boolean-summary support size
          // (`checkSupportSize`'s `maxLocalSupport`/`maxTotalWork`) — a
          // genuinely different failure mode, so trying it first is never
          // redundant with the ABC fallback below, only ever cheaper when
          // it happens to finish.
          //
          // The budget for that first try is deliberately *not*
          // `parsed.nativeMaxStates`: a reachable-state space can sit
          // entirely under that cap (so `reachable` never truncates) while
          // still being expensive per state, if the alphabet is large — the
          // very case `Aiger.generateSafetyAuto`'s own `quickDfaWork`
          // budget exists to avoid on the Aiger side (see its doc-comment).
          // `dot_depth k=1600` is the measured example: `reachable` finds a
          // witness within 4096 states (never truncates) but takes ~73s
          // doing it, against ABC's ~8s on the same formula via the
          // minimized+`scleanup`/`dc2`'d encoding. Capping the first
          // attempt the same way keeps this flag's whole point — try the
          // cheap thing first — actually cheap, and lets a slow-but-would
          // -eventually-finish native run get out of its own way instead of
          // silently costing more wall-clock time than escalating would
          // have.
          val quickBudget = math.min(parsed.nativeMaxStates, Aiger.quickDfaWork / math.max(1, automaton.source.alphabet.length))
          val quickDfa = if quickBudget > 0 then Some(BooleanAutomaton.reachable(automaton, quickBudget)).filterNot(_.truncated) else None
          quickDfa match
            case Some(dfa) => println(nativeSummary(dfa, automaton.source.alphabet, goal, emptyBad))
            case None =>
              System.err.println(
                s"translator: --run-auto: native reachability search did not finish within a quick $quickBudget-state budget; escalating to ABC pdr"
              )
              return runAbcBackend(automaton, parsed, goal, emptyBad)
        else if parsed.runAbc || parsed.aiger then
          val goal =
            if parsed.subset.isDefined then "inclusion"
            else if parsed.equivalent.isDefined then "equivalence"
            else "safety"
          if parsed.runAbc then
            val emptyBad = BooleanAutomaton.diagonal(automaton, automaton.initial)(automaton.source.initialState)
            return runAbcBackend(automaton, parsed, goal, emptyBad)
          val model =
            try Aiger.generateSafetyAuto(automaton, parsed.aigerMaxStates)
            catch
              case AigerError(message) =>
                System.err.println(s"translator: $message")
                return 2
          System.out.write(model)
          System.out.flush()
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

  /** `run`'s work happens on a dedicated thread with a much larger stack
    * than the JVM default (~512KB-1MB): some benchmark formulas are
    * legitimately deep, not just wide — the `two_var/monotone_past` family
    * builds one `Formula` subtree nesting `!(... & pairwise-combination
    * ...)` to `O(sigma^2)` levels (~860 for `sigma=42`), deep enough to
    * overflow the default stack in `Ltl.mirror`'s ordinary recursive
    * descent well before any backend's own size/complexity limit would
    * ever reject it. `-Xss` on the `java` command line would also fix
    * this, but only if the caller remembers to pass it; doing it here
    * means the packaged jar just works without one.
    */
  def main(args: Array[String]): Unit =
    var exitCode = 1
    val worker = new Thread(
      null,
      () =>
        exitCode =
          try run(args)
          catch
            case _: StackOverflowError =>
              System.err.println(
                "translator: error: formula is too deeply nested for this process's stack (even after " +
                  "raising it well past the JVM default) — this is a recursion-depth limit in the " +
                  "compiler's own AST walk, not a rejection from any backend's own size check"
              )
              1
      ,
      "brasp-worker",
      256L * 1024 * 1024,
    )
    worker.start()
    worker.join()
    System.exit(exitCode)
