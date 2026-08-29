package brasp

import scala.util.Random

/** Cross-checks `Ltlp.translate` against an independent, from-scratch
  * brute-force evaluator of the *original* (pre-separation) LTLp formula
  * — deliberately not reusing any of `Ltlp.scala`'s own logic, same
  * philosophy as `LtlExamplesCrossCheckSuite.bruteForce` for the LTLf
  * front end.
  *
  * The fixture is `example/phi.txt` from
  * https://github.com/xsk07/LTLpSeparator — `G(landing -> O
  * request_landing)` — and its `matrix.json` produced by that tool's
  * `-a` flag (captured once, hardcoded here rather than shelling out to
  * an external, non-repo Java tool on every test run).
  */
class LtlpSuite extends munit.FunSuite:

  // Captured verbatim from running:
  //   java -cp out:lib/commons-cli-1.4.jar main.Main -a -iF example/phi.txt
  // against a from-source build of https://github.com/xsk07/LTLpSeparator.
  private val phiMatrixJson =
    """[
      | ["true", "request_landing & true", "!(true U ((((!(request_landing)) & (!(true))) & true) & ((true & (!(request_landing))) U (!(!(landing))))))"],
      | ["true", "true", "(!(true U ((((!(request_landing)) & (!(true))) & true) & ((true & (!(request_landing))) U (!(!(landing))))))) & (!((true & (!(request_landing))) U (!(!(landing)))))"],
      | ["true", "request_landing & request_landing", "!(true U ((((!(request_landing)) & (!(true))) & true) & ((true & (!(request_landing))) U (!(!(landing))))))"],
      | ["true", "request_landing", "(!(true U ((((!(request_landing)) & (!(true))) & true) & ((true & (!(request_landing))) U (!(!(landing))))))) & (!((true & (!(request_landing))) U (!(!(landing)))))"],
      | ["true", "true", "((!(true U ((((!(request_landing)) & (!(true))) & true) & ((true & (!(request_landing))) U (!(!(landing))))))) & (!((true & (!(request_landing))) U (!(!(landing)))))) & (!((true & (!(request_landing))) U (!(!(landing)))))"],
      | ["true S request_landing", "true", "!(true U ((((!(request_landing)) & (!(true))) & true) & ((true & (!(request_landing))) U (!(!(landing))))))"],
      | ["true S request_landing", "true", "(!(true U ((((!(request_landing)) & (!(true))) & true) & ((true & (!(request_landing))) U (!(!(landing))))))) & (!((true & (!(request_landing))) U (!(!(landing)))))"],
      | ["true S request_landing", "request_landing", "!(true U ((((!(request_landing)) & (!(true))) & true) & ((true & (!(request_landing))) U (!(!(landing))))))"]
      |]""".stripMargin

  // --- Independent brute-force evaluator for the ORIGINAL LTLp formula ---
  // Deliberately hand-written for exactly `G(landing -> O request_landing)`,
  // not parsed, not going through `Ltlp.scala`. Semantics match
  // LTLpSeparator's own algebraic rewrite rules (`ConversionRules.java`):
  // `Oq ≡ q S true`, `Gq ≡ !((!q) U true)` — both STRICT (exclude "now"),
  // confirmed against the captured matrix's own `"true S request_landing"`
  // cell (see `Ltlp.scala`'s doc-comment for the derivation).
  private def originalHolds(trace: IndexedSeq[(Boolean, Boolean)], t: Int): Boolean =
    // trace(i) = (landing, request_landing) at position i
    def onceRequestLanding(at: Int): Boolean = (0 until at).exists(s => trace(s)._2)
    def landingImpliesOnce(at: Int): Boolean = !trace(at)._1 || onceRequestLanding(at)
    (t + 1 until trace.length).forall(landingImpliesOnce) // G is strict: constrains only positions AFTER t, not t itself

  private def randomTrace(rng: Random, length: Int): IndexedSeq[(Boolean, Boolean)] =
    IndexedSeq.fill(length)((rng.nextBoolean(), rng.nextBoolean()))

  /** Every trace of the given length, in order. */
  private def allTraces(length: Int): List[IndexedSeq[(Boolean, Boolean)]] =
    val steps = List((false, false), (true, false), (false, true), (true, true))
    (0 until length).foldLeft(List(IndexedSeq.empty[(Boolean, Boolean)])) { (traces, _) =>
      for trace <- traces; step <- steps yield trace :+ step
    }

  private def encodeSymbol(step: (Boolean, Boolean)): String =
    // propositionsUsed sorts alphabetically: "landing" < "request_landing",
    // matching Ltlf.Builder.alphabet's bitPos-0-is-first-character order.
    val (landing, requestLanding) = step
    s"${if landing then '1' else '0'}${if requestLanding then '1' else '0'}"

  test("Ltlp.translate matches an independent brute-force evaluator of the original formula") {
    val dag = Ltlp.translate(phiMatrixJson)
    assertEquals(dag.logic, Logic.PastStrict)

    // Exhaustive, not sampled. The bug this guards -- `Ltlp`'s strict `U`
    // handed to `Ltlf`'s non-strict compiler, so the evaluation point got
    // constrained when the original formula leaves it free (see
    // `Ltlp.toLtlf`) -- shows up only when position 0 carries the sole
    // `landing`. That is 1 trace in 4 at length 1 but under 9% by length 4,
    // so 20 random traces per length is not a reliable net for it.
    for
      length <- 0 to 4
      trace <- allTraces(length)
    do
      val expected = originalHolds(trace, 0)
      val word = trace.map(encodeSymbol).reverse // compileToPast mirrors: evaluate on reverse(w)
      val actual = Ltl.evaluate(dag, word)
      assertEquals(actual, expected, s"trace=$trace")

    // Longer traces stay sampled: 4^n exhaustion stops being cheap, but the
    // shape of the bug is already pinned above.
    val rng = new Random(1234)
    for
      length <- 5 until 8
      _ <- 0 until 40
    do
      val trace = randomTrace(rng, length)
      val expected = originalHolds(trace, 0)
      val word = trace.map(encodeSymbol).reverse
      assertEquals(Ltl.evaluate(dag, word), expected, s"trace=$trace")
  }

  test("Ltlp.evalPastOnEmptyHistory matches Ltl.evaluate on a zero-length word") {
    // Cross-check the "no automaton needed" shortcut itself: for a handful
    // of hand-built pure-past cells, the direct structural evaluator must
    // agree with actually running Ltl.evaluate(PastStrict dag, empty word)
    // through the real (memoized-definition) evaluator.
    def viaLtlEvaluate(formula: LtlpFormula): Boolean =
      def toFormula(f: LtlpFormula): Formula = f match
        case LtlpFormula.True             => Formula.Constant(true)
        case LtlpFormula.False            => Formula.Constant(false)
        case LtlpFormula.Prop(name)        => Formula.Atom(AtomKind.BitAtom, Position.I, Some("0"))
        case LtlpFormula.Not(operand)      => Formula.Negation(toFormula(operand))
        case LtlpFormula.And(left, right)  => Formula.Conjunction(List(toFormula(left), toFormula(right)))
        case LtlpFormula.Or(left, right)   => Formula.Disjunction(List(toFormula(left), toFormula(right)))
        case LtlpFormula.Since(left, right) =>
          Formula.Since(Position.I, Position.J, toFormula(left), toFormula(right))
        case LtlpFormula.Until(_, _) => throw new IllegalArgumentException("test-only helper: past cells only")
      val dag = FormulaDag(Logic.PastStrict, scala.collection.immutable.VectorMap.empty, toFormula(formula), "i = 0", alphabet = Some(List("0", "1")))
      Ltl.evaluate(dag, IndexedSeq.empty)

    val samples = List(
      LtlpFormula.True,
      LtlpFormula.False,
      LtlpFormula.Prop("p"),
      LtlpFormula.Since(LtlpFormula.True, LtlpFormula.Prop("p")),
      LtlpFormula.Not(LtlpFormula.Since(LtlpFormula.True, LtlpFormula.Prop("p"))),
      LtlpFormula.And(LtlpFormula.True, LtlpFormula.Not(LtlpFormula.Since(LtlpFormula.Prop("p"), LtlpFormula.False))),
      LtlpFormula.Or(LtlpFormula.False, LtlpFormula.Since(LtlpFormula.False, LtlpFormula.True)),
    )
    for formula <- samples do
      assertEquals(Ltlp.evalPastOnEmptyHistory(formula), viaLtlEvaluate(formula), s"formula=$formula")
  }

  test("Ltlp.parseCell matches LTLpSeparator's precedence (! tighter than S/U, unlike LtlText)") {
    // Their grammar.jjt: ltl_unary -> ltl_atom | UNARY ltl_unary; ltl_binary
    // -> ltl_unary (BINARYTEMP ltl_unary)* — so `!` binds INSIDE `S`/`U`,
    // e.g. "!a S b" is "(!a) S b", not "!(a S b)" (LtlText's own choice).
    val parsed = Ltlp.parseCell("!a S b")
    assertEquals(parsed, LtlpFormula.Since(LtlpFormula.Not(LtlpFormula.Prop("a")), LtlpFormula.Prop("b")))
  }

  test("Ltlp.propositionsUsed discovers and sorts every Prop across all three columns") {
    val rows = Ltlp.parseMatrix(phiMatrixJson)
    assertEquals(Ltlp.propositionsUsed(rows), List("landing", "request_landing"))
  }
