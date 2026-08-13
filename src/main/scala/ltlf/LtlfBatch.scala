package brasp

import java.io.File
import java.nio.file.Files
import scala.jdk.CollectionConverters.*

/** Batch-converts an `LTLf_Learning_Benchmarks`-style `formulas.txt`
  * (lines of `"formula";["ap1","ap2",...];"name";"source"`, e.g.
  * `Fixed_Formulas/formulas.txt` from
  * https://github.com/SynthesisLab/LTLf_Learning_Benchmarks) into this
  * project's format: one `.brasp` and one `.ltl` file per line, named
  * after the benchmark's own `name` field.
  *
  * Per `Ltlf.compileToPast`'s doc-comment, each emitted program describes
  * the REVERSAL of that line's LTLf language, not the language itself —
  * intentional (see `Ltl.mirrorToPast`'s doc-comment for why), not a bug.
  * A benchmark's own reference traces would need reversing too before
  * comparing against these programs.
  */
object LtlfBatch:

  /** Split a `formulas.txt` line on top-level `;` — i.e. not inside a
    * `"..."` string or a `[...]` array, since the AP list field is itself
    * comma/semicolon-adjacent JSON.
    */
  private def splitTopLevel(line: String): List[String] =
    val fields = scala.collection.mutable.ArrayBuffer.empty[String]
    val current = new StringBuilder
    var depth = 0
    var inString = false
    var i = 0
    while i < line.length do
      val c = line(i)
      if inString then
        current += c
        if c == '\\' && i + 1 < line.length then
          current += line(i + 1)
          i += 1
        else if c == '"' then inString = false
      else
        c match
          case '"'                   => inString = true; current += c
          case '['                   => depth += 1; current += c
          case ']'                   => depth -= 1; current += c
          case ';' if depth == 0     => fields += current.toString; current.clear()
          case _                     => current += c
      i += 1
    fields += current.toString
    fields.toList

  private def unquote(field: String): String =
    val trimmed = field.trim
    if trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"") then
      trimmed.substring(1, trimmed.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
    else trimmed

  def parseLine(line: String): Option[(String, List[String], String)] =
    val fields = splitTopLevel(line)
    if fields.length < 3 then None
    else
      val formulaText = unquote(fields(0))
      // The AP list is JSON in Fixed_Formulas/formulas.txt (double-quoted),
      // but Python `repr()` of a list of str — single-quoted — everywhere
      // it's produced by the other generator scripts (e.g.
      // `str(inputs + outputs)` in gen_singlecounter.py). Neither the
      // formula text nor proposition names ever contain a quote character,
      // so a blind `'` -> `"` swap is safe and handles both.
      val aps = Json.parse(fields(1).trim.replace('\'', '"')).asArray.getOrElse(Vector.empty).flatMap(_.asString).toList
      val name = unquote(fields(2))
      if formulaText.isEmpty || aps.isEmpty || name.isEmpty then None else Some((formulaText, aps, name))

  /** Extracts `(generating_formula, atomic_propositions)` from one of the
    * full JSON instance files `Generating_Instances/gen_*.py` produce
    * (e.g. `OrderedSequence/trace_length=10....json`) — unlike
    * `formulas.txt`, these carry the formula alongside sampled traces this
    * converter doesn't need. Returns `None` for a family with no known
    * formula at all (`Hamming`'s `generating_formula` is always `""` — it's
    * a pure trace-classification benchmark, nothing to convert) as well as
    * for any structurally-unexpected file, so callers can't tell the two
    * apart from this alone; `run`/`runJsonDir` just skip either way.
    */
  def parseJsonInstance(jsonText: String): Option[(String, List[String])] =
    val value = Json.parse(jsonText)
    for
      formulaText <- value.field("generating_formula").flatMap(_.asString).filter(_.nonEmpty)
      apsValue <- value.field("atomic_propositions")
      aps = apsValue.asArray.getOrElse(Vector.empty).flatMap(_.asString).toList
      if aps.nonEmpty
    yield (formulaText, aps)

  /** Works around a real upstream bug in
    * `Generating_Instances/gen_orderedsequence.py`'s
    * `write_orderedsequence_formula`: for a sequence of length `k` it emits
    * `k - 1` opening parens but always `k` closing ones (the closing count
    * is `len(orderedsequence)` where it should be `len(orderedsequence) -
    * 1|), leaving exactly one unmatched trailing `)` in every OrderedSequence
    * formula, regardless of `k` — confirmed by inspecting generated
    * instances directly (e.g. `k=4` gives 3 opens, 4 closes). Trimming
    * trailing `)` while closes outnumber opens is safe for any
    * already-balanced formula (a no-op there) and fixes this specific
    * known-malformed shape without weakening the parser itself.
    */
  private def dropUnbalancedTrailingCloseParens(formulaText: String): String =
    var text = formulaText
    while text.nonEmpty && text.endsWith(")") && text.count(_ == ')') > text.count(_ == '(') do text = text.dropRight(1)
    text

  private def convertOne(formulaTextRaw: String, atomicPropositions: List[String], name: String, outputDir: File): Boolean =
    val formulaText = dropUnbalancedTrailingCloseParens(formulaTextRaw)
    try
      val pastDag = Ltlf.compileToPast(formulaText, atomicPropositions)
      val program = LtlToBrasp.translate(pastDag)
      Files.writeString(new File(outputDir, s"$name.brasp").toPath, BraspText.render(program))
      Files.writeString(new File(outputDir, s"$name.ltl").toPath, LtlText.render(pastDag))
      println(s"ltlf-batch: wrote $name.brasp / $name.ltl")
      true
    catch
      case error: RuntimeException =>
        System.err.println(s"ltlf-batch: $name: ${error.getMessage}")
        false

  /** Converts every line of a `formulas.txt`-style file (semicolon-quadruple
    * lines: `Fixed_Formulas/formulas.txt`, or the same shape the
    * `Generating_Formulas/gen_*.py` scripts write, e.g.
    * `SingleCounter/4.txt`).
    */
  def run(inputFile: File, outputDir: File): Int =
    if !inputFile.isFile then
      System.err.println(s"ltlf-batch: not a file: $inputFile")
      return 2
    outputDir.mkdirs()
    val lines = Files.readAllLines(inputFile.toPath).asScala.map(_.trim).filter(_.nonEmpty).toList
    var failures = 0
    for line <- lines do
      parseLine(line) match
        case None =>
          System.err.println(s"ltlf-batch: could not parse line: $line")
          failures += 1
        case Some((formulaText, atomicPropositions, name)) =>
          if !convertOne(formulaText, atomicPropositions, name, outputDir) then failures += 1
    if failures > 0 then System.err.println(s"ltlf-batch: $failures of ${lines.length} line(s) failed")
    if failures > 0 then 1 else 0

  /** Converts every `*.json` instance file directly in `inputDir` (not
    * recursive) — the `Generating_Instances/gen_*.py` output shape. Each
    * file is named after its own basename (already unique: it's built from
    * that instance's own generation parameters).
    */
  def runJsonDir(inputDir: File, outputDir: File): Int =
    if !inputDir.isDirectory then
      System.err.println(s"ltlf-batch: not a directory: $inputDir")
      return 2
    outputDir.mkdirs()
    val files = Option(inputDir.listFiles((_, name) => name.endsWith(".json"))).getOrElse(Array.empty[File]).sortBy(_.getName).toList
    var failures = 0
    var skipped = 0
    for file <- files do
      val name = file.getName.stripSuffix(".json")
      try
        parseJsonInstance(Files.readString(file.toPath)) match
          case None =>
            skipped += 1
          case Some((formulaText, atomicPropositions)) =>
            if !convertOne(formulaText, atomicPropositions, name, outputDir) then failures += 1
      catch
        case error: RuntimeException =>
          System.err.println(s"ltlf-batch: $name: ${error.getMessage}")
          failures += 1
    if skipped > 0 then println(s"ltlf-batch: skipped $skipped file(s) with no generating_formula")
    if failures > 0 then System.err.println(s"ltlf-batch: $failures of ${files.length} file(s) failed")
    if failures > 0 then 1 else 0

  def main(args: Array[String]): Unit =
    args.toList match
      case List(inputFile, outputDir) =>
        System.exit(run(new File(inputFile), new File(outputDir)))
      case List("--json-dir", inputDir, outputDir) =>
        System.exit(runJsonDir(new File(inputDir), new File(outputDir)))
      case _ =>
        System.err.println("usage: LtlfBatch INPUT_FORMULAS_TXT OUTPUT_DIR")
        System.err.println("       LtlfBatch --json-dir INPUT_JSON_DIR OUTPUT_DIR")
        System.exit(2)
