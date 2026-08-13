package brasp

import scala.util.Random

class LtlfSuite extends munit.FunSuite:
  import LtlfFormula as F

  /** Independent, textbook-definition brute-force evaluator for
    * (not-yet-desugared) LTLf formulas over finite traces — standard
    * non-strict semantics (`X`/`F`/`G`/`U` all include "now"). Deliberately
    * doesn't reuse `Ltlf.desugar`/`Ltlf.compile`: `Release`/`WeakUntil` are
    * computed via their standard defining identities inline, not by calling
    * into this project's own desugaring code.
    */
  private def bruteForce(formula: LtlfFormula, trace: IndexedSeq[List[Boolean]], props: List[String]): Boolean =
    def propValue(name: String, pos: Int): Boolean =
      pos < trace.length && trace(pos)(props.indexOf(name))
    def eval(f: LtlfFormula, pos: Int): Boolean = f match
      case F.True         => true
      case F.False        => false
      case F.Prop(name)   => propValue(name, pos)
      case F.Not(x)       => !eval(x, pos)
      case F.And(l, r)    => eval(l, pos) && eval(r, pos)
      case F.Or(l, r)     => eval(l, pos) || eval(r, pos)
      case F.Implies(l, r) => !eval(l, pos) || eval(r, pos)
      case F.Iff(l, r)    => eval(l, pos) == eval(r, pos)
      case F.Next(x)      => pos + 1 < trace.length && eval(x, pos + 1)
      case F.WeakNext(x)  => pos + 1 >= trace.length || eval(x, pos + 1)
      case F.Eventually(x) => (pos until trace.length).exists(j => eval(x, j))
      case F.Always(x)    => (pos until trace.length).forall(j => eval(x, j))
      case F.Until(l, r)  => (pos until trace.length).exists(j => eval(r, j) && (pos until j).forall(k => eval(l, k)))
      case F.Release(l, r) => !eval(F.Until(F.Not(l), F.Not(r)), pos)
      case F.WeakUntil(l, r) => eval(F.Until(l, r), pos) || eval(F.Always(l), pos)
    eval(formula, 0)

  private def randomTrace(rng: Random, length: Int, props: List[String]): IndexedSeq[List[Boolean]] =
    IndexedSeq.fill(length)(props.map(_ => rng.nextBoolean()))

  private def encodeSymbol(step: List[Boolean]): String =
    step.map(bit => if bit then '1' else '0').mkString

  /** Fixed_Formulas/formulas.txt from SynthesisLab/LTLf_Learning_Benchmarks,
    * inlined so this test is self-contained (doesn't depend on the clone's
    * filesystem location).
    */
  private val fixedFormulas: List[(String, String, List[String])] = List(
    ("absence1", "G(!(var0))", List("var0", "var1")),
    ("absence2", "F(var0) -> (!(var0) U var1)", List("var0", "var1")),
    ("absence3", "G(var1 -> G(!(var0)))", List("var0", "var1")),
    ("existence1", "F(var0)", List("var0", "var1")),
    ("existence2", "G(!(var0)) || F(var0 && F(var1))", List("var0", "var1")),
    ("existence3", "G(var0 && (!(var1) -> (!var1) U (var2 && !(var1))))", List("var0", "var1", "var2")),
    ("universality1", "G(var0)", List("var0", "var1")),
    ("universality2", "F(var1) -> (var0 U var1)", List("var0", "var1")),
    ("universality3", "G(var1 -> G(var0))", List("var0", "var1")),
  )

  /** Cross-checks one (name, LTLf text, atomic propositions) instance
    * against `bruteForce`, both directly (future dag) and through the full
    * mirror + `LtlToBrasp` pipeline on reversed traces, over many random
    * traces of varying length.
    */
  private def crossCheck(rng: Random, name: String, text: String, props: List[String]): Unit =
    val ast = Ltlf.parse(text)
    val futureDag = Ltlf.compileToFuture(ast, props)
    val pastDag = Ltl.mirrorToPast(futureDag)
    val program = LtlToBrasp.translate(pastDag)
    for
      length <- 0 to 6
      _ <- 0 until 20
    do
      val trace = randomTrace(rng, length, props)
      val expected = bruteForce(ast, trace, props)
      val symbolTrace = trace.map(encodeSymbol)
      assertEquals(Ltl.evaluate(futureDag, symbolTrace), expected, s"formula=$name trace=$trace (future dag)")
      val reversed = symbolTrace.reverse
      assertEquals(Ltl.evaluate(pastDag, reversed), expected, s"formula=$name trace=$trace (past dag)")
      assertEquals(Brasp.accepts(program, reversed), expected, s"formula=$name trace=$trace (Brasp.accepts)")

  test("Fixed_Formulas: compiled dags and the full LtlToBrasp pipeline match an independent brute-force evaluator") {
    val rng = Random(2026)
    for (name, text, props) <- fixedFormulas do crossCheck(rng, name, text, props)
  }

  /** `X[!]` (strong next) vs bare `X` (weak next) are distinct operators in
    * this benchmark suite's own generator scripts (e.g.
    * `Generating_Formulas/gen_singlecounter.py`'s `Next` vs `WeakNext`) —
    * none of `fixedFormulas` above exercise either one.
    */
  test("Strong (X[!]) vs weak (X) next are distinct and both correct") {
    val rng = Random(2028)
    val props = List("a", "b")
    val cases = List(
      "strong_next" -> "X[!] a",
      "weak_next" -> "X a",
      "strong_next_at_end" -> "G(a -> X[!] b)",
      "weak_next_at_end" -> "G(a -> X b)",
      "nested" -> "F(a && X[!] F(b))",
    )
    for (name, text) <- cases do crossCheck(rng, name, text, props)
  }

  test("Ltlf parser handles precedence, associativity, and the full operator set") {
    assertEquals(Ltlf.parse("a && b || c"), LtlfFormula.Or(LtlfFormula.And(F.Prop("a"), F.Prop("b")), F.Prop("c")))
    assertEquals(Ltlf.parse("!a && b"), LtlfFormula.And(F.Not(F.Prop("a")), F.Prop("b")))
    assertEquals(Ltlf.parse("a -> b -> c"), LtlfFormula.Implies(F.Prop("a"), F.Implies(F.Prop("b"), F.Prop("c"))))
    assertEquals(Ltlf.parse("X[!](a)"), LtlfFormula.Next(F.Prop("a")))
    assertEquals(Ltlf.parse("X[!] a"), LtlfFormula.Next(F.Prop("a")))
    assertEquals(Ltlf.parse("X(a)"), LtlfFormula.WeakNext(F.Prop("a")))
    assertEquals(Ltlf.parse("X a"), LtlfFormula.WeakNext(F.Prop("a")))
    assertEquals(Ltlf.parse("!a"), LtlfFormula.Not(F.Prop("a")))
    assertEquals(Ltlf.parse("true"), LtlfFormula.True)
    assertEquals(Ltlf.parse("false"), LtlfFormula.False)
    // a proposition literally named like a keyword prefix must not be swallowed
    assertEquals(Ltlf.parse("Ga"), LtlfFormula.Prop("Ga"))
    assertEquals(Ltlf.parse("a R b"), LtlfFormula.Release(F.Prop("a"), F.Prop("b")))
    assertEquals(Ltlf.parse("a W b"), LtlfFormula.WeakUntil(F.Prop("a"), F.Prop("b")))
    assertEquals(Ltlf.parse("a <-> b"), LtlfFormula.Iff(F.Prop("a"), F.Prop("b")))
  }
