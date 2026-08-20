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
    btor2: Boolean = false,
    btor2MaxStates: Int = 4096,
    runRic3: Boolean = false,
    runNative: Boolean = false,
    nativeMaxStates: Int = 4096,
    direct: Boolean = false,
    runDirect: Boolean = false,
    directAiger: Boolean = false,
    runDirectAbc: Boolean = false,
    ric3Bin: File,
    ric3Mode: String = "ic3",
    ric3Raw: Boolean = false,
    aiger: Boolean = false,
    aigerMaxStates: Int = 4096,
    runAbc: Boolean = false,
    abcBin: File,
    abcRaw: Boolean = false,
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

  private def defaultRic3Bin: File =
    val cwd = new File(System.getProperty("user.dir"))
    new File(cwd.getParentFile, "rIC3/target/release/ric3")

  private def defaultAbcBin: File =
    val cwd = new File(System.getProperty("user.dir"))
    new File(cwd.getParentFile, "abc/abc")

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
    var btor2Out = false
    var btor2MaxStates = 4096
    var runRic3 = false
    var runNative = false
    var nativeMaxStates = 4096
    var directOut = false
    var runDirect = false
    var directAigerOut = false
    var runDirectAbc = false
    var ric3Bin: File = defaultRic3Bin
    var ric3Mode = "ic3"
    var ric3Raw = false
    var aigerOut = false
    var aigerMaxStates = 4096
    var runAbc = false
    var abcBin: File = defaultAbcBin
    var abcRaw = false
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
        case "--btor2"              => btor2Out = true
        case "--btor2-max-states"   =>
          val raw = takeValue("--btor2-max-states", it)
          btor2MaxStates = raw.toIntOption.getOrElse(fail(s"argument --btor2-max-states: expected an integer, got '$raw'"))
        case "--run-ric3"           => runRic3 = true
        case "--run-native"         => runNative = true
        case "--native-max-states"  =>
          val raw = takeValue("--native-max-states", it)
          nativeMaxStates = raw.toIntOption.getOrElse(fail(s"argument --native-max-states: expected an integer, got '$raw'"))
        case "--direct"             => directOut = true
        case "--run-direct"         => runDirect = true
        case "--direct-aiger"       => directAigerOut = true
        case "--run-direct-abc"     => runDirectAbc = true
        case "--ric3-bin"           => ric3Bin = File(takeValue("--ric3-bin", it))
        case "--ric3-mode"          => ric3Mode = takeValue("--ric3-mode", it)
        case "--ric3-raw"           => ric3Raw = true
        case "--aiger"              => aigerOut = true
        case "--aiger-max-states"   =>
          val raw = takeValue("--aiger-max-states", it)
          aigerMaxStates = raw.toIntOption.getOrElse(fail(s"argument --aiger-max-states: expected an integer, got '$raw'"))
        case "--run-abc"            => runAbc = true
        case "--abc-bin"            => abcBin = File(takeValue("--abc-bin", it))
        case "--abc-raw"            => abcRaw = true
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
      btor2 = btor2Out,
      btor2MaxStates = btor2MaxStates,
      runRic3 = runRic3,
      runNative = runNative,
      nativeMaxStates = nativeMaxStates,
      direct = directOut,
      runDirect = runDirect,
      directAiger = directAigerOut,
      runDirectAbc = runDirectAbc,
      ric3Bin = ric3Bin,
      ric3Mode = ric3Mode,
      ric3Raw = ric3Raw,
      aiger = aigerOut,
      aigerMaxStates = aigerMaxStates,
      runAbc = runAbc,
      abcBin = abcBin,
      abcRaw = abcRaw,
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
    * shape `Ric3.summarize`/`Abc`'s summaries use, but computed entirely
    * in-process from `BooleanAutomaton.reachable` — no BTOR2/AIGER model,
    * no external solver. `dfa.truncated` (the exploration budget was too
    * small to reach a verdict either way) is this check's only source of
    * `UNKNOWN`; unlike an external solver there is no timeout/resource
    * notion beyond that budget.
    */
  def nativeSummary(dfa: ReachableDfa, alphabet: List[String], goal: String, emptyBad: Boolean): String =
    if dfa.truncated then
      goal match
        case "inclusion"   => s"brasp-native: UNKNOWN — inclusion not decided within ${dfa.stateCount} states (raise --native-max-states)."
        case "equivalence" => s"brasp-native: UNKNOWN — equivalence not decided within ${dfa.stateCount} states (raise --native-max-states)."
        case _             => s"brasp-native: UNKNOWN — safety not decided within ${dfa.stateCount} states (raise --native-max-states)."
    else
      val nonemptyWitness = BooleanAutomaton.witness(dfa, alphabet)
      val mainProved = nonemptyWitness.isEmpty
      val allProved = mainProved && !emptyBad
      val heading =
        if allProved then
          goal match
            case "inclusion"   => "brasp-native: PROVED — inclusion holds."
            case "equivalence" => "brasp-native: PROVED — the languages are equivalent."
            case _             => "brasp-native: PROVED — no bad prefix is reachable."
        else
          goal match
            case "inclusion"   => "brasp-native: NOT PROVED — a counterexample was found."
            case "equivalence" => "brasp-native: NOT PROVED — the languages differ."
            case _             => "brasp-native: NOT PROVED — a bad prefix is reachable."
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
      if emptyBad then emptyLine += " — witness: ε"
      List(heading, mainLine, emptyLine).mkString("\n")

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
            parsed.lustre || parsed.booleanAutomaton || parsed.btor2 || parsed.runRic3 ||
            parsed.runNative || parsed.aiger || parsed.runAbc
        if needsBooleanAutomaton then
          CompileResult.BooleanResult(BooleanAutomaton.fromForwardPvwaa(Pvwaa.fromFuture2ltl(toFuture(translated))))
        else if parsed.pvwaa || parsed.direct || parsed.runDirect || parsed.directAiger || parsed.runDirectAbc then
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
        else if parsed.runRic3 || parsed.btor2 then
          val goal =
            if parsed.kind2Subset.isDefined then "inclusion"
            else if parsed.kind2Equivalent.isDefined then "equivalence"
            else "safety"
          val monitorName =
            if parsed.kind2Subset.isDefined then "subset_monitor"
            else if parsed.kind2Equivalent.isDefined then "equivalence_monitor"
            else "brasp_monitor"
          val model =
            try Btor2.generateSafetyAuto(automaton, monitorName, parsed.btor2MaxStates)
            catch
              case Btor2Error(message) =>
                System.err.println(s"translator: $message")
                return 2
          if parsed.runRic3 then
            try
              val emptyBad = BooleanAutomaton.diagonal(automaton, automaton.initial)(automaton.source.initialState)
              return Ric3.run(model, parsed.ric3Bin, goal, automaton.source.alphabet, emptyBad, parsed.ric3Mode, parsed.ric3Raw)
            catch
              case Ric3Error(message) =>
                System.err.println(s"translator: $message")
                return 2
          println(model)
        else if parsed.runNative then
          val goal =
            if parsed.kind2Subset.isDefined then "inclusion"
            else if parsed.kind2Equivalent.isDefined then "equivalence"
            else "safety"
          val emptyBad = BooleanAutomaton.diagonal(automaton, automaton.initial)(automaton.source.initialState)
          val dfa = BooleanAutomaton.reachable(automaton, parsed.nativeMaxStates)
          println(nativeSummary(dfa, automaton.source.alphabet, goal, emptyBad))
        else if parsed.runAbc || parsed.aiger then
          val goal =
            if parsed.kind2Subset.isDefined then "inclusion"
            else if parsed.kind2Equivalent.isDefined then "equivalence"
            else "safety"
          val model =
            try Aiger.generateSafetyAuto(automaton, parsed.aigerMaxStates)
            catch
              case AigerError(message) =>
                System.err.println(s"translator: $message")
                return 2
          if parsed.runAbc then
            try
              val emptyBad = BooleanAutomaton.diagonal(automaton, automaton.initial)(automaton.source.initialState)
              return Abc.run(model, parsed.abcBin, goal, emptyBad, parsed.abcRaw)
            catch
              case AbcError(message) =>
                System.err.println(s"translator: $message")
                return 2
          System.out.write(model)
          System.out.flush()
        else if parsed.kind2Subset.isDefined || parsed.kind2Equivalent.isDefined || parsed.kind2Safety then
          val goal =
            if parsed.kind2Subset.isDefined then "inclusion"
            else if parsed.kind2Equivalent.isDefined then "equivalence"
            else "safety"
          val harness =
            try
              if parsed.kind2Subset.isDefined then
                Kind2.generateSafety(automaton, monitorName = "subset_monitor", mainName = "kind2_subset")
              else if parsed.kind2Equivalent.isDefined then
                Kind2.generateSafety(automaton, monitorName = "equivalence_monitor", mainName = "kind2_equivalence")
              else Kind2.generateSafety(automaton)
            catch
              // Kind2.generateSafety delegates to Lustre.generate internally
              // and doesn't rewrap its errors, so both types can surface here.
              case Kind2Error(message) =>
                System.err.println(s"translator: $message")
                return 2
              case LustreError(message) =>
                System.err.println(s"translator: $message")
                return 2
          if parsed.runKind2 then
            try return Kind2.run(harness, parsed.kind2Bin, goal, automaton.source.alphabet, parsed.kind2Json)
            catch
              case Kind2Error(message) =>
                System.err.println(s"translator: $message")
                return 2
          println(harness)
        else if parsed.lustre then
          try println(Lustre.generate(automaton))
          catch
            case LustreError(message) =>
              System.err.println(s"translator: $message")
              return 2
        else
          trySaveArtifact(graphOutputPath(parsed.input, suffix = "boolean_automaton"), BooleanAutomaton.toDot(automaton)) match
            case Some(code) => return code
            case None       => ()
          println(if parsed.json then Json.render(BooleanAutomaton.toJson(automaton)) else BooleanAutomaton.render(automaton))
      case CompileResult.PvwaaResult(automaton) =>
        if parsed.runDirect || parsed.direct then
          val model =
            try DirectPvwaa.generateSafety(automaton)
            catch
              case DirectPvwaaError(message) =>
                System.err.println(s"translator: $message")
                return 2
          if parsed.runDirect then
            try
              val emptyBad = DirectPvwaa.emptyWordAccepted(automaton)
              return Ric3.run(model, parsed.ric3Bin, "safety", automaton.alphabet, emptyBad, parsed.ric3Mode, parsed.ric3Raw, reverseWitness = true)
            catch
              case Ric3Error(message) =>
                System.err.println(s"translator: $message")
                return 2
          println(model)
        else if parsed.runDirectAbc || parsed.directAiger then
          val model =
            try DirectPvwaa.generateSafetyAiger(automaton)
            catch
              case DirectPvwaaError(message) =>
                System.err.println(s"translator: $message")
                return 2
          if parsed.runDirectAbc then
            try
              val emptyBad = DirectPvwaa.emptyWordAccepted(automaton)
              return Abc.run(model, parsed.abcBin, "safety", emptyBad, parsed.abcRaw)
            catch
              case AbcError(message) =>
                System.err.println(s"translator: $message")
                return 2
          System.out.write(model)
          System.out.flush()
        else if parsed.dot then
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
