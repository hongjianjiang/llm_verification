package brasp

import java.io.File
import java.nio.file.Files
import scala.util.Random

/** Ad-hoc correctness check (not wired into the normal build) cross-checking
  * every `.ltl` file actually sitting in `ltl_examples/<Family>/` against
  * the ORIGINAL LTLf source formula it was generated from (via
  * `benchmarks/_dedup/<Family>/`), using the same independent brute-force
  * evaluator `LtlfSuite` uses for `Fixed_Formulas` — pointed at every
  * instance actually present, not just a curated subset.
  *
  * Skips entirely (no failures) if `benchmarks/_dedup/` doesn't exist,
  * since that's built from data outside the repo.
  */
class LtlExamplesCrossCheckSuite extends munit.FunSuite:
  import LtlfFormula as F

  private val examplesRoot = new File("ltl_examples")

  private def bruteForce(formula: LtlfFormula, trace: IndexedSeq[List[Boolean]], props: List[String]): Boolean =
    def propValue(name: String, pos: Int): Boolean =
      pos < trace.length && trace(pos)(props.indexOf(name))
    def eval(f: LtlfFormula, pos: Int): Boolean = f match
      case F.True          => true
      case F.False         => false
      case F.Prop(name)    => propValue(name, pos)
      case F.Not(x)        => !eval(x, pos)
      case F.And(l, r)     => eval(l, pos) && eval(r, pos)
      case F.Or(l, r)      => eval(l, pos) || eval(r, pos)
      case F.Implies(l, r) => !eval(l, pos) || eval(r, pos)
      case F.Iff(l, r)     => eval(l, pos) == eval(r, pos)
      case F.Next(x)       => pos + 1 < trace.length && eval(x, pos + 1)
      case F.WeakNext(x)   => pos + 1 >= trace.length || eval(x, pos + 1)
      case F.Eventually(x) => (pos until trace.length).exists(j => eval(x, j))
      case F.Always(x)     => (pos until trace.length).forall(j => eval(x, j))
      case F.Until(l, r)   => (pos until trace.length).exists(j => eval(r, j) && (pos until j).forall(k => eval(l, k)))
      case F.Release(l, r) => !eval(F.Until(F.Not(l), F.Not(r)), pos)
      case F.WeakUntil(l, r) => eval(F.Until(l, r), pos) || eval(F.Always(l), pos)
    eval(formula, 0)

  private def randomTrace(rng: Random, length: Int, props: List[String]): IndexedSeq[List[Boolean]] =
    IndexedSeq.fill(length)(props.map(_ => rng.nextBoolean()))

  private def encodeSymbol(step: List[Boolean]): String =
    step.map(bit => if bit then '1' else '0').mkString

  // Mirrors LtlfBatch.convertOne's own fix-up so we compare against the
  // exact text that was actually compiled, not the raw (known-malformed
  // for OrderedSequence) upstream text.
  private def dropUnbalancedTrailingCloseParens(formulaText: String): String =
    var text = formulaText
    while text.nonEmpty && text.endsWith(")") && text.count(_ == ')') > text.count(_ == '(') do text = text.dropRight(1)
    text

  /** name -> (formulaText, atomicPropositions), gathered from every
    * `*.json` instance file directly in `dir`.
    */
  private def collectJsonDir(dir: File): Map[String, (String, List[String])] =
    if !dir.isDirectory then Map.empty
    else
      val entries = for
        file <- dir.listFiles((_, n) => n.endsWith(".json")).toList
        parsed <- LtlfBatch.parseJsonInstance(Files.readString(file.toPath))
      yield file.getName.stripSuffix(".json") -> parsed
      entries.toMap

  // benchmarks/_dedup/<Family>/ holds one deduped, cleanly-named JSON
  // instance per distinct (generating_formula, atomic_propositions) pair —
  // built once (see the ltl_examples/ regeneration this cross-check
  // verifies) directly from benchmarks/<Family>/*.json, and named to match
  // ltl_examples/<Family>/*.ltl exactly. Supersedes the old per-family
  // lookups against ~/work/LTLf_Learning_Benchmarks and Nim/_representative,
  // which only covered the original ~26-instance curated subset.
  private val dedupRoot = new File("benchmarks/_dedup")
  private val families: List[String] = List(
    "Fixed_Formulas", "DoubleCounter", "SingleCounter", "Nim",
    "Random_Conjuncts_from_Basis", "OrderedSequence", "Subset", "Subword",
    "RandomBooleanCombinationsofFactors",
  )

  if dedupRoot.isDirectory then
    for family <- families do
      test(s"$family: every ltl_examples/*.ltl file matches its original LTLf source") {
        val familyDir = new File(examplesRoot, family)
        assume(familyDir.isDirectory, s"no ltl_examples/$family directory")
        val sourceByName = collectJsonDir(new File(dedupRoot, family))
        val ltlFiles = Option(familyDir.listFiles((_, n) => n.endsWith(".ltl"))).getOrElse(Array.empty[File]).sortBy(_.getName)
        assume(ltlFiles.nonEmpty, s"no .ltl files under $familyDir")
        val rng = Random(42)
        for compiledFile <- ltlFiles do
          val name = compiledFile.getName.stripSuffix(".ltl")
          sourceByName.get(name) match
            case None =>
              fail(s"$name: no matching source formula found under $dedupRoot/$family")
            case Some((rawFormulaText, props)) =>
              val formulaText = dropUnbalancedTrailingCloseParens(rawFormulaText)
              val ast = Ltlf.parse(formulaText)
              val pastDag = LtlText.parse(Files.readString(compiledFile.toPath))
              for
                length <- 0 to 8
                _ <- 0 until 10
              do
                val trace = randomTrace(rng, length, props)
                val expected = bruteForce(ast, trace, props)
                val reversed = trace.map(encodeSymbol).reverse
                assertEquals(Ltl.evaluate(pastDag, reversed), expected, s"$family/$name trace=$trace")
      }
  else
    test("skipped: benchmarks/_dedup not found".ignore) {}
