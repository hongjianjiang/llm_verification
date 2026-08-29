package brasp

import scala.util.Random
import LtlpFormula as F

/** Randomized differential tests across the compilation stages.
  *
  * The hand-written suites pin specific shapes; these pin the *agreement*
  * between stages on programs nobody chose. That distinction is not
  * academic: `Ltlp` had a single cross-check fixture whose 20-random-traces
  * -per-length sampling nearly missed a real strict-vs-non-strict `U`
  * confusion, and `BraspNormalize`'s case split has far more shapes than a
  * fixture list can enumerate.
  *
  * Seeded, so a failure is reproducible and the suite is deterministic.
  */
class PipelineFuzzSuite extends munit.FunSuite:

  private def words(n: Int, alphabet: List[String]): List[List[String]] =
    if n == 0 then List(Nil) else for f <- alphabet; r <- words(n - 1, alphabet) yield f :: r

  // --- random B-RASP programs -------------------------------------------

  private def randomExpression(rng: Random, names: Vector[String], predicate: Boolean, depth: Int): BooleanExpression =
    import BooleanExpression.*
    if depth <= 0 || names.isEmpty || rng.nextInt(100) < 25 then
      if names.isEmpty || rng.nextInt(100) < 15 then Const(rng.nextBoolean())
      else Ref(names(rng.nextInt(names.length)), if predicate && rng.nextBoolean() then Position.J else Position.I)
    else
      rng.nextInt(3) match
        case 0 => Not(randomExpression(rng, names, predicate, depth - 1))
        case 1 => And(List.fill(1 + rng.nextInt(2))(randomExpression(rng, names, predicate, depth - 1)))
        case _ => Or(List.fill(1 + rng.nextInt(2))(randomExpression(rng, names, predicate, depth - 1)))

  /** Deliberately weighted toward `leftmost`, the stage with the most moving
    * parts (`BraspNormalize`'s query-side case split). */
  private def randomProgram(rng: Random, alphabet: List[String]): Program =
    import Subprogram.*
    val subprograms = scala.collection.mutable.ArrayBuffer.empty[Subprogram]
    subprograms += Bos("is_bos")
    for (symbol, index) <- alphabet.zipWithIndex do subprograms += SymbolNode(s"is_$index", symbol)
    for i <- 0 until (1 + rng.nextInt(4)) do
      val names = subprograms.map(_.name).toVector
      val name = s"n$i"
      subprograms += (rng.nextInt(4) match
        case 0 => BooleanNode(name, randomExpression(rng, names, predicate = false, depth = 2))
        case 1 => RightmostAttention(name, randomExpression(rng, names, true, 2), randomExpression(rng, names, true, 2))
        case 2 => LeftmostAttention(name, randomExpression(rng, names, true, 2), randomExpression(rng, names, true, 2))
        case _ => LeftmostAttention(name, randomExpression(rng, names, true, 3), randomExpression(rng, names, true, 2)))
    val program = Program(subprograms.toList, subprograms.last.name, Some(alphabet))
    program.validate()
    program

  test("every compilation stage agrees with Brasp.accepts on random programs") {
    val rng = new Random(20260829)
    for trial <- 0 until 60 do
      val alphabet = if rng.nextBoolean() then List("a", "b") else List("a", "b", "c")
      val maxLength = if alphabet.length == 2 then 6 else 4
      val program = randomProgram(rng, alphabet)
      val label = BraspText.render(program)

      val past = BraspToLtl.translateProgram(program)
      val future = Ltl.mirrorDag(past)
      val pvwaa = Pvwaa.fromFuture2ltl(future)
      val booleanAutomaton = BooleanAutomaton.fromForwardPvwaa(pvwaa)
      val other = randomProgram(rng, alphabet)
      val subsetProgram = Inclusion.counterexampleProgram(program, other)
      val xorProgram = Inclusion.equivalenceCounterexampleProgram(program, other)

      for
        length <- 0 to maxLength
        word <- words(length, alphabet)
      do
        val expected = Brasp.accepts(program, word.toIndexedSeq)
        val clue = s"trial $trial word=${word.mkString}\n$label"
        assertEquals(Ltl.evaluate(past, word.toIndexedSeq), expected, s"past: $clue")
        assertEquals(Ltl.evaluate(future, word.reverse.toIndexedSeq), expected, s"future: $clue")
        assertEquals(Pvwaa.accepts(pvwaa, word.reverse.toIndexedSeq), expected, s"pvwaa: $clue")
        assertEquals(BooleanAutomaton.accepts(booleanAutomaton, word.toIndexedSeq), expected, s"automaton: $clue")
        // The inclusion reduction against plain set semantics.
        val otherAccepts = Brasp.accepts(other, word.toIndexedSeq)
        assertEquals(Brasp.accepts(subsetProgram, word.toIndexedSeq), expected && !otherAccepts, s"subset: $clue")
        assertEquals(Brasp.accepts(xorProgram, word.toIndexedSeq), expected != otherAccepts, s"xor: $clue")

      // A reported witness must really be accepted, and an "empty" verdict
      // must not coexist with a short accepted word.
      val dfa = BooleanAutomaton.reachable(booleanAutomaton, maxStates = 200_000)
      if !dfa.truncated then
        val witness = BooleanAutomaton.witness(dfa, alphabet)
        witness.foreach: found =>
          assert(Brasp.accepts(program, found.toIndexedSeq), s"witness ${found.mkString} not accepted\n$label")
        if witness.isEmpty then
          val short = (1 to maxLength).flatMap(words(_, alphabet)).find(w => Brasp.accepts(program, w.toIndexedSeq))
          assert(short.isEmpty, s"witness=None but ${short.map(_.mkString)} is accepted\n$label")
  }

  // --- random LTLp matrices ---------------------------------------------

  private val propositions = Vector("p", "q")

  /** Strict `Since`/`Until`, evaluated straight off the trace — deliberately
    * sharing nothing with `Ltlp.scala`. */
  private def holds(formula: F, trace: IndexedSeq[Set[String]], at: Int): Boolean = formula match
    case F.True        => true
    case F.False       => false
    case F.Prop(name)  => trace(at).contains(name)
    case F.Not(o)      => !holds(o, trace, at)
    case F.And(l, r)   => holds(l, trace, at) && holds(r, trace, at)
    case F.Or(l, r)    => holds(l, trace, at) || holds(r, trace, at)
    case F.Since(l, r) => (0 until at).exists(j => holds(r, trace, j) && ((j + 1) until at).forall(k => holds(l, trace, k)))
    case F.Until(l, r) =>
      (at + 1 until trace.length).exists(j => holds(r, trace, j) && ((at + 1) until j).forall(k => holds(l, trace, k)))

  private def randomCell(rng: Random, depth: Int, past: Boolean): F =
    if depth <= 0 || rng.nextInt(100) < 30 then
      rng.nextInt(6) match
        case 0 => F.True
        case 1 => F.False
        case _ => F.Prop(propositions(rng.nextInt(propositions.length)))
    else
      rng.nextInt(5) match
        case 0 => F.Not(randomCell(rng, depth - 1, past))
        case 1 => F.And(randomCell(rng, depth - 1, past), randomCell(rng, depth - 1, past))
        case 2 => F.Or(randomCell(rng, depth - 1, past), randomCell(rng, depth - 1, past))
        case _ =>
          if past then F.Since(randomCell(rng, depth - 1, past), randomCell(rng, depth - 1, past))
          else F.Until(randomCell(rng, depth - 1, past), randomCell(rng, depth - 1, past))

  /** A *pure-past* cell in the separation sense: propositions occur only under
    * a `Since`, never bare at the current point — bare current-point atoms are
    * what the `present` column is for, and the captured LTLpSeparator matrix
    * in `LtlpSuite` matches this (its past cells are `true` and
    * `true S request_landing`). Generating bare props here instead would be
    * testing `Ltlp` outside the domain it documents.
    */
  private def randomPastCell(rng: Random, depth: Int): F =
    if depth <= 0 || rng.nextInt(100) < 25 then (if rng.nextBoolean() then F.True else F.False)
    else
      rng.nextInt(4) match
        case 0 => F.Not(randomPastCell(rng, depth - 1))
        case 1 => F.And(randomPastCell(rng, depth - 1), randomPastCell(rng, depth - 1))
        case 2 => F.Or(randomPastCell(rng, depth - 1), randomPastCell(rng, depth - 1))
        case _ => F.Since(randomCell(rng, depth - 1, past = true), randomCell(rng, depth - 1, past = true))

  test("Ltlp.translateMatrix agrees with an independent strict-semantics evaluator on random matrices") {
    val rng = new Random(7)
    // Length 0 is excluded on purpose: position 0 does not exist in an empty
    // trace, and that degenerate case has its own test in `LtlpSuite`
    // (`evalPastOnEmptyHistory matches Ltl.evaluate on a zero-length word`).
    val traces = (1 to 4).flatMap { n =>
      List
        .fill(n)(List(Set.empty[String], Set("p"), Set("q"), Set("p", "q")))
        .foldLeft(List(List.empty[Set[String]]))((acc, options) => for a <- acc; o <- options yield a :+ o)
    }.toList

    for _ <- 0 until 150 do
      val rows = List.fill(1 + rng.nextInt(3))(
        (randomPastCell(rng, 2), randomCell(rng, 2, past = false), randomCell(rng, 2, past = false))
      )
      val dag = Ltlp.translateMatrix(rows, propositions.toList)
      for trace <- traces do
        val expected = rows.exists { case (past, present, future) =>
          holds(past, trace.toIndexedSeq, 0) && holds(present, trace.toIndexedSeq, 0) && holds(future, trace.toIndexedSeq, 0)
        }
        val word = trace.map(step => propositions.map(p => if step.contains(p) then '1' else '0').mkString).reverse
        assertEquals(Ltl.evaluate(dag, word.toIndexedSeq), expected, s"rows=$rows trace=$trace")
  }
