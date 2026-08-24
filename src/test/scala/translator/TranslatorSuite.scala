package brasp

import java.io.File
import java.nio.file.Files
import scala.collection.immutable.VectorMap
import JsonValue.*

class TranslatorSuite extends munit.FunSuite:

  private def wordsOfLength(n: Int, alphabet: List[String]): List[List[String]] =
    if n == 0 then List(Nil)
    else for first <- alphabet; rest <- wordsOfLength(n - 1, alphabet) yield first :: rest

  /** A tiny BTOR2 interpreter for `DirectPvwaa.generateSafety`'s models,
    * covering exactly the op vocabulary it emits
    * (sort/zero/one/constd/input/state/init/next/eq/ite/and/or/ult/bad),
    * used to check the encoder against `Pvwaa.accepts` without a real
    * BTOR2 solver. Unlike a plain BTOR2 circuit, these models have more
    * than one `input` (the shared `symbol`, plus one existentially-guessed
    * "next obligation" per PVWAA state) — a single shared `currentInput`
    * can't represent that. `symbol` is always the first declared input
    * (see `DirectPvwaa.generateSafety`'s own doc-comment); every other
    * input is a guess this search existentially quantifies over via a
    * small BFS across reachable register-value tuples, exactly mirroring
    * what the real solver does. Returns, for a run over `symbolIndices`,
    * whether `bad` is reachable after consuming each prefix — small
    * automata only (guess count is exponential in the search), which is
    * all this backend's own tests need.
    */
  private def runDirectPvwaa(model: String, symbolIndices: IndexedSeq[Int]): List[Boolean] =
    case class Op(keyword: String, args: List[Int])
    val ops = scala.collection.mutable.Map.empty[Int, Op]
    val consts = scala.collection.mutable.Map.empty[Int, Int]
    val stateIds = scala.collection.mutable.Set.empty[Int]
    val registerInit = scala.collection.mutable.Map.empty[Int, Int]
    val registerNext = scala.collection.mutable.Map.empty[Int, Int]
    var badId = -1
    var symbolInputId = -1
    val guessInputIds = scala.collection.mutable.ArrayBuffer.empty[Int]

    for line <- model.linesIterator if line.nonEmpty && !line.startsWith(";") do
      val parts = line.trim.split("\\s+").toList
      val id = parts.head.toInt
      parts(1) match
        case "sort"       => ()
        case "zero"       => consts(id) = 0
        case "one"        => consts(id) = 1
        case "constd"     => consts(id) = parts(3).toInt
        case "input"      => if symbolInputId < 0 then symbolInputId = id else guessInputIds += id
        case "state"      => stateIds += id
        case "init"       => registerInit(parts(3).toInt) = parts(4).toInt
        case "next"       => registerNext(parts(3).toInt) = parts(4).toInt
        case "bad"        => badId = parts(2).toInt
        case "constraint" => ()
        case "eq" | "ult" => ops(id) = Op(parts(1), List(parts(3).toInt, parts(4).toInt))
        case "and" | "or" => ops(id) = Op(parts(1), List(parts(3).toInt, parts(4).toInt))
        case "not"        => ops(id) = Op(parts(1), List(parts(3).toInt))
        case "ite"        => ops(id) = Op(parts(1), List(parts(3).toInt, parts(4).toInt, parts(5).toInt))
        case other        => throw new RuntimeException(s"runDirectPvwaa: unsupported op '$other'")

    def eval(id: Int, symbolValue: Int, guesses: Map[Int, Int], registers: Map[Int, Int], memo: scala.collection.mutable.Map[Int, Int]): Int =
      memo.getOrElseUpdate(
        id,
        if stateIds.contains(id) then registers(id)
        else if id == symbolInputId then symbolValue
        else if guesses.contains(id) then guesses(id)
        else
          consts.get(id) match
            case Some(v) => v
            case None =>
              val op = ops(id)
              op.keyword match
                case "eq"  => if eval(op.args(0), symbolValue, guesses, registers, memo) == eval(op.args(1), symbolValue, guesses, registers, memo) then 1 else 0
                case "ult" => if eval(op.args(0), symbolValue, guesses, registers, memo) < eval(op.args(1), symbolValue, guesses, registers, memo) then 1 else 0
                case "and" => if eval(op.args(0), symbolValue, guesses, registers, memo) == 1 && eval(op.args(1), symbolValue, guesses, registers, memo) == 1 then 1 else 0
                case "or"  => if eval(op.args(0), symbolValue, guesses, registers, memo) == 1 || eval(op.args(1), symbolValue, guesses, registers, memo) == 1 then 1 else 0
                case "not" => if eval(op.args(0), symbolValue, guesses, registers, memo) == 1 then 0 else 1
                case "ite" => if eval(op.args(0), symbolValue, guesses, registers, memo) == 1 then eval(op.args(1), symbolValue, guesses, registers, memo) else eval(op.args(2), symbolValue, guesses, registers, memo)
                case other => throw new RuntimeException(s"runDirectPvwaa: unsupported op '$other'"),
      )

    def allGuessCombinations: Iterator[Map[Int, Int]] =
      val ids = guessInputIds.toList
      (0 until (1 << ids.length)).iterator.map(mask => ids.zipWithIndex.map((id, i) => id -> ((mask >> i) & 1)).toMap)

    var frontier = Set(stateIds.map(id => id -> consts(registerInit(id))).toMap)
    symbolIndices.map { symbol =>
      val nextFrontier = scala.collection.mutable.Set.empty[Map[Int, Int]]
      for registers <- frontier; guesses <- allGuessCombinations do
        val memo = scala.collection.mutable.Map.empty[Int, Int]
        nextFrontier += stateIds.map(id => id -> eval(registerNext(id), symbol, guesses, registers, memo)).toMap
      frontier = nextFrontier.toSet
      // `bad` depends only on register values (no symbol/guesses of its
      // own), so any fixed dummy values suffice to evaluate it here.
      frontier.exists(registers => eval(badId, 0, Map.empty, registers, scala.collection.mutable.Map.empty) == 1)
    }.toList

  /** A tiny binary-AIGER interpreter covering exactly what `Aiger` emits
    * (text header/latch-next-literals/output, then delta-encoded AND gates,
    * then a `c` comment section it ignores), used to check the encoder —
    * including its exact byte-level format, not just its node graph —
    * against `BooleanAutomaton.accepts` without a real AIGER solver. Every
    * latch is reset-to-0 by construction (see `Aiger`'s doc-comment), so
    * there's no separate init value to read. Returns, for a run over
    * `symbolIndices`, whether the output holds after consuming each
    * prefix.
    */
  private def runAiger(model: Array[Byte], symbolIndices: IndexedSeq[Int]): List[Boolean] =
    var pos = 0
    def readLine(): String =
      val start = pos
      while model(pos) != '\n'.toByte do pos += 1
      val line = new String(model, start, pos - start, java.nio.charset.StandardCharsets.US_ASCII)
      pos += 1
      line

    val Array(_, i, l, o, a) = readLine().split("\\s+").tail.map(_.toInt)
    val latchNextLits = (0 until l).map(_ => readLine().trim.toInt)
    val outputLits = (0 until o).map(_ => readLine().trim.toInt)

    def decodeDelta(): Int =
      var value = 0
      var shift = 0
      var more = true
      while more do
        val byte = model(pos) & 0xff
        pos += 1
        value |= (byte & 0x7f) << shift
        shift += 7
        more = (byte & 0x80) != 0
      value

    val andOf = (0 until a).map { k =>
      val outLit = 2 * (i + l + k + 1)
      val rhsHigh = outLit - decodeDelta()
      val rhsLow = rhsHigh - decodeDelta()
      outLit -> (rhsHigh, rhsLow)
    }.toMap

    var latchState = (0 until l).map(k => 2 * (i + k + 1) -> 0).toMap // every latch resets to 0

    def valueOf(literal: Int, inputVals: Map[Int, Int], memo: scala.collection.mutable.Map[Int, Int]): Int =
      if literal == 0 then 0
      else if literal == 1 then 1
      else
        val v = literal & ~1
        val negated = literal & 1
        val raw = memo.getOrElseUpdate(
          v,
          if latchState.contains(v) then latchState(v)
          else if inputVals.contains(v) then inputVals(v)
          else
            andOf.get(v) match
              case Some((aLit, bLit)) => valueOf(aLit, inputVals, memo) & valueOf(bLit, inputVals, memo)
              case None                => throw new RuntimeException(s"runAiger: unknown variable $v"),
        )
        raw ^ negated

    symbolIndices.map { symbol =>
      val inputVals = (0 until i).map(bitPos => 2 * (bitPos + 1) -> ((symbol >> bitPos) & 1)).toMap
      val memo = scala.collection.mutable.Map.empty[Int, Int]
      val bad = valueOf(outputLits.head, inputVals, memo) == 1
      latchState = latchNextLits.zipWithIndex.map { case (nextLit, k) => 2 * (i + k + 1) -> valueOf(nextLit, inputVals, memo) }.toMap
      bad
    }.toList

  /** Run `dfa` on `word`, returning whether each prefix is accepted —
    * directly interprets `ReachableDfa.transitions`/`.accepting`, no
    * encoder involved, so it's usable against both `reachable`'s raw
    * output and `BooleanAutomaton.minimize`'s.
    */
  private def runDfa(dfa: ReachableDfa, word: List[String]): List[Boolean] =
    var state = dfa.initial
    word.map { symbol =>
      state = dfa.transitions((state, symbol))
      dfa.accepting.contains(state)
    }

  test("brasp past LTL and future mirror agree") {
    val specification = obj(
      "alphabet" -> arr(str("a"), str("b")),
      "program" -> arr(
        obj("name" -> str("bos"), "op" -> str("bos")),
        obj("name" -> str("a"), "op" -> str("symbol"), "symbol" -> str("a")),
        obj("name" -> str("b"), "op" -> str("symbol"), "symbol" -> str("b")),
        obj(
          "name" -> str("chosen"),
          "op" -> str("rightmost"),
          "score" -> obj(
            "op" -> str("or"),
            "args" -> arr(
              obj("op" -> str("ref"), "name" -> str("a"), "at" -> str("j")),
              obj(
                "op" -> str("and"),
                "args" -> arr(
                  obj("op" -> str("ref"), "name" -> str("b"), "at" -> str("i")),
                  obj("op" -> str("ref"), "name" -> str("b"), "at" -> str("j")),
                ),
              ),
            ),
          ),
          "value" -> obj("op" -> str("not"), "arg" -> obj("op" -> str("ref"), "name" -> str("b"), "at" -> str("j"))),
        ),
        obj(
          "name" -> str("after_bos"),
          "op" -> str("rightmost"),
          "score" -> obj("op" -> str("ref"), "name" -> str("bos"), "at" -> str("j")),
          "value" -> obj("op" -> str("ref"), "name" -> str("b"), "at" -> str("i")),
        ),
        obj(
          "name" -> str("output"),
          "op" -> str("or"),
          "args" -> arr(
            obj("op" -> str("ref"), "name" -> str("chosen")),
            obj("op" -> str("ref"), "name" -> str("after_bos")),
          ),
        ),
      ),
      "output" -> str("output"),
    )

    val program = Brasp.fromJson(specification)
    val past = BraspToLtl.translate(specification)
    val future = Translator.mirrorToFuture(past)
    val automaton = Pvwaa.fromFuture2ltl(future)
    val booleanAutomaton = BooleanAutomaton.fromForwardPvwaa(automaton)
    assert(booleanAutomaton.gotoSupport.nonEmpty)

    for
      length <- 0 until 5
      word <- wordsOfLength(length, List("a", "b"))
    do
      val pastValue = Ltl.evaluate(past, word.toIndexedSeq)
      val braspValue = Brasp.accepts(program, word.toIndexedSeq)
      assertEquals(braspValue, pastValue, s"word=$word")
      assertEquals(Brasp.evaluate(program, word.toIndexedSeq).at(program.output, length), braspValue, s"word=$word")
      assertEquals(pastValue, Ltl.evaluate(future, word.reverse.toIndexedSeq), s"word=$word")
      assertEquals(pastValue, Pvwaa.accepts(automaton, word.reverse.toIndexedSeq), s"word=$word")
      assertEquals(pastValue, BooleanAutomaton.accepts(booleanAutomaton, word.toIndexedSeq), s"word=$word")
  }

  test("brasp evaluator handles leftmost attention") {
    val spec = obj(
      "alphabet" -> arr(str("a"), str("b")),
      "program" -> arr(
        obj("name" -> str("a"), "op" -> str("symbol"), "symbol" -> str("a")),
        obj(
          "name" -> str("has_a"),
          "op" -> str("leftmost"),
          "score" -> obj("op" -> str("ref"), "name" -> str("a"), "at" -> str("j")),
          "value" -> obj("op" -> str("const"), "value" -> bool(true)),
        ),
      ),
      "output" -> str("has_a"),
    )
    val program = Brasp.fromJson(spec)
    assert(!Brasp.accepts(program, IndexedSeq.empty))
    assert(!Brasp.accepts(program, IndexedSeq("b", "b")))
    assert(Brasp.accepts(program, IndexedSeq("b", "a", "a")))
  }

  test("inclusion reduces to counterexample emptiness") {
    val endsInA = Brasp.fromJson(
      obj(
        "alphabet" -> arr(str("a"), str("b")),
        "program" -> arr(obj("name" -> str("a"), "op" -> str("symbol"), "symbol" -> str("a"))),
        "output" -> str("a"),
      )
    )
    val allWords = Brasp.fromJson(
      obj(
        "alphabet" -> arr(str("a"), str("b")),
        "program" -> arr(obj("name" -> str("yes"), "op" -> str("const"), "value" -> bool(true))),
        "output" -> str("yes"),
      )
    )
    val noCounterexample = Inclusion.counterexampleProgram(endsInA, allWords)
    val hasCounterexample = Inclusion.counterexampleProgram(allWords, endsInA)
    for
      length <- 0 until 4
      word <- wordsOfLength(length, List("a", "b"))
    do assert(!Brasp.accepts(noCounterexample, word.toIndexedSeq), s"word=$word")
    assert(Brasp.accepts(hasCounterexample, IndexedSeq("b")))
    assert(!Brasp.accepts(Inclusion.equivalenceCounterexampleProgram(endsInA, endsInA), IndexedSeq("a")))
    assert(Brasp.accepts(Inclusion.equivalenceCounterexampleProgram(endsInA, allWords), IndexedSeq("b")))
  }

  private def sampleAutomaton(alphabet: List[String] = List("a", "b")): ReverseBooleanAutomaton =
    val specification = obj(
      "alphabet" -> arr(alphabet.map(str)*),
      "program" -> arr(
        obj("name" -> str("bos"), "op" -> str("bos")),
        obj("name" -> str("a"), "op" -> str("symbol"), "symbol" -> str(alphabet(0))),
        obj("name" -> str("b"), "op" -> str("symbol"), "symbol" -> str(alphabet(1))),
        obj(
          "name" -> str("chosen"),
          "op" -> str("rightmost"),
          "score" -> obj(
            "op" -> str("or"),
            "args" -> arr(
              obj("op" -> str("ref"), "name" -> str("a"), "at" -> str("j")),
              obj(
                "op" -> str("and"),
                "args" -> arr(
                  obj("op" -> str("ref"), "name" -> str("b"), "at" -> str("i")),
                  obj("op" -> str("ref"), "name" -> str("b"), "at" -> str("j")),
                ),
              ),
            ),
          ),
          "value" -> obj("op" -> str("not"), "arg" -> obj("op" -> str("ref"), "name" -> str("b"), "at" -> str("j"))),
        ),
        obj(
          "name" -> str("after_bos"),
          "op" -> str("rightmost"),
          "score" -> obj("op" -> str("ref"), "name" -> str("bos"), "at" -> str("j")),
          "value" -> obj("op" -> str("ref"), "name" -> str("b"), "at" -> str("i")),
        ),
        obj(
          "name" -> str("output"),
          "op" -> str("or"),
          "args" -> arr(
            obj("op" -> str("ref"), "name" -> str("chosen")),
            obj("op" -> str("ref"), "name" -> str("after_bos")),
          ),
        ),
      ),
      "output" -> str("output"),
    )
    val past = BraspToLtl.translate(specification)
    val future = Translator.mirrorToFuture(past)
    val pvwaa = Pvwaa.fromFuture2ltl(future)
    BooleanAutomaton.fromForwardPvwaa(pvwaa)

  private val sameLetterBeforeLtl =
    """logic past-strict
      |alphabet a b
      |
      |f_0 := sym(a)@i
      |f_1 := sym(b)@i
      |f_2 := P(!((!((f_0@i & f_0@j)) & !((f_1@i & f_1@j)))))
      |
      |output := f_2@i
      |evaluate at i = |w| (the final input position)
      |""".stripMargin

  private def pvwaaFromLtl(text: String): ForwardPVWAA =
    val dag = LtlText.parse(text)
    val future = if dag.logic == Logic.FutureStrict then dag else Translator.mirrorToFuture(dag)
    Pvwaa.fromFuture2ltl(future)

  private def pvwaaFromBrasp(text: String): ForwardPVWAA =
    val dag = BraspToLtl.translateProgram(BraspText.parse(text))
    Pvwaa.fromFuture2ltl(Translator.mirrorToFuture(dag))

  test("DirectPvwaa.generateSafety matches Pvwaa.accepts prefix-by-prefix") {
    val automaton = pvwaaFromLtl(sameLetterBeforeLtl)
    val model = DirectPvwaa.generateSafety(automaton)
    assert(model.contains("bad "))
    assert(model.contains("input "))
    val alphabet = automaton.alphabet

    for
      length <- 1 until 5
      word <- wordsOfLength(length, alphabet)
    do
      val indices = word.map(alphabet.indexOf).toIndexedSeq
      // this circuit reads the future-mirrored word directly (see
      // DirectPvwaa's own doc-comment) -- compare against Pvwaa.accepts
      // un-reversed, the same direction the circuit itself consumes.
      val expected = (1 to word.length).map(k => Pvwaa.accepts(automaton, word.take(k).toIndexedSeq)).toList
      assertEquals(runDirectPvwaa(model, indices), expected, s"word=$word")
  }

  test("DirectPvwaa.emptyWordAccepted matches Pvwaa.accepts on the empty word") {
    val automaton = pvwaaFromLtl(sameLetterBeforeLtl)
    assertEquals(DirectPvwaa.emptyWordAccepted(automaton), Pvwaa.accepts(automaton, IndexedSeq.empty))
  }

  test("DirectPvwaa.generateSafety handles a goto-target with its own Carry/Leave structure") {
    // top --goto--> mid --carry--> leaf: `mid` is not symbol-constant (it
    // has its own Carry) -- the shape `Hist`-based formulas produce, which
    // this backend used to reject outright (see git history) and now
    // handles via a per-state "root" register (see the class doc-comment
    // on `DirectPvwaa` and the comment above `needsRoot` in
    // `generateSafety`).
    val transitions = Map(
      "top"  -> PositiveFormula.TransitionAtom("mid", Action.Goto),
      "mid"  -> PositiveFormula.TransitionAtom("leaf", Action.Carry),
      "leaf" -> PositiveFormula.SymbolTest(AtomKind.SymbolAtom, Some("a"), false),
    )
    val automaton = ForwardPVWAA(List("a", "b"), List("top", "mid", "leaf"), transitions, "top", Set("leaf"), Map("top" -> 2, "mid" -> 1, "leaf" -> 0))
    val model = DirectPvwaa.generateSafety(automaton)
    val alphabet = automaton.alphabet

    for
      length <- 1 until 6
      word <- wordsOfLength(length, alphabet)
    do
      val indices = word.map(alphabet.indexOf).toIndexedSeq
      val expected = (1 to word.length).map(k => Pvwaa.accepts(automaton, word.take(k).toIndexedSeq)).toList
      assertEquals(runDirectPvwaa(model, indices), expected, s"word=$word")
  }

  // `runDirectPvwaa` existentially enumerates *all* `2^(#states)` guess
  // combinations per step (see its own doc-comment) -- fine for the 3-state
  // automaton above, but `nestedGotoBrasp`/`atLeastTwoABrasp` have 8-10
  // states, so kept to a shorter word-length bound here than the other
  // tests in this file use, to keep this suite's own runtime bounded.
  private val nestedGotoBrasp =
    """alphabet a b
      |
      |is_a = symbol a
      |a_before = rightmost(is_a@j, true)
      |check = is_a & a_before
      |outer = rightmost(check@j, true)
      |
      |output outer
      |""".stripMargin

  test("DirectPvwaa.generateSafety matches Pvwaa.accepts on a real goto-target-with-Carry/Leave program") {
    // `check`'s own transitions goto `a_before`, whose own transitions
    // carry/leave (an `Until`) -- exactly the shape `checkGotoTargetsAreSimple`
    // used to reject, and `check` is itself later `Carry`'d into by
    // `outer`'s own witness search, so `check`'s own root is not always
    // position 0 either.
    val automaton = pvwaaFromBrasp(nestedGotoBrasp)
    val model = DirectPvwaa.generateSafety(automaton)
    val alphabet = automaton.alphabet

    for
      length <- 1 until 3
      word <- wordsOfLength(length, alphabet)
    do
      val indices = word.map(alphabet.indexOf).toIndexedSeq
      val expected = (1 to word.length).map(k => Pvwaa.accepts(automaton, word.take(k).toIndexedSeq)).toList
      assertEquals(runDirectPvwaa(model, indices), expected, s"word=$word")
  }

  private val atLeastTwoABrasp =
    """alphabet a b
      |
      |is_a = symbol a
      |a_before = rightmost(is_a@j & true | !!is_a@j, is_a@j & true)
      |two_a_before = rightmost(is_a@j & a_before@j | !!is_a@j, is_a@j & a_before@j)
      |last_is_second_a = is_a & a_before
      |at_least_two_a = two_a_before | last_is_second_a
      |
      |output at_least_two_a
      |""".stripMargin

  test("DirectPvwaa.generateSafety matches Pvwaa.accepts on at_least_two_a (Until nested in Until's operand)") {
    // the actual motivating example: `last_is_second_a` gotos `a_before`,
    // which has its own Carry/Leave (from its own `rightmost`); `a_before`
    // is also `Carry`'d into directly by `two_a_before`'s witness search,
    // and `two_a_before`/`last_is_second_a` are themselves goto-targets of
    // the top-level `at_least_two_a`. Kept to length 1 only -- at 10
    // states `runDirectPvwaa`'s exhaustive `2^states`-per-step guess
    // search already takes ~20s at length 2 -- the mechanism itself is
    // exhaustively covered above (the hand-built automaton and
    // `nestedGotoBrasp`); deeper coverage on this exact formula (and the
    // `dot_depth`/`y_depth` families, too large to brute-force here at
    // all) was cross-checked out-of-band instead: `--direct`'s BTOR2 model
    // run through rIC3 manually, against the independent `--run-native`
    // backend, and `tools.GotoOverlapCheck` against `Pvwaa.accepts`
    // directly.
    val automaton = pvwaaFromBrasp(atLeastTwoABrasp)
    val model = DirectPvwaa.generateSafety(automaton)
    val alphabet = automaton.alphabet

    for
      length <- 1 until 2
      word <- wordsOfLength(length, alphabet)
    do
      val indices = word.map(alphabet.indexOf).toIndexedSeq
      val expected = (1 to word.length).map(k => Pvwaa.accepts(automaton, word.take(k).toIndexedSeq)).toList
      assertEquals(runDirectPvwaa(model, indices), expected, s"word=$word")
  }

  // A single "hub" state that directly `goto`s 30 other (strictly
  // lower-ranked) states, each a trivial constant — support(hub) = 30,
  // over checkSupportSize's cap of 24, so generateSafetyAuto can only reach
  // this automaton via the DFA-encoding branch. Its reachable-state count
  // is tiny (the leaves are constants, so there are only a couple of
  // distinct summaries), so that branch succeeds quickly once tried.
  // `alphabet` defaults to a single symbol;
  // the Aiger backend needs a power-of-two alphabet (its `symbolWidth`
  // rejects size 1 the same as any other non-power-of-two size), so its
  // tests pass a 2-symbol alphabet instead — every symbol gets the same
  // trivial transition, so which alphabet is used doesn't otherwise matter.
  private def oversizedSupportAutomaton(alphabet: List[String] = List("a")): ReverseBooleanAutomaton =
    val n = 30
    val leaves = (0 until n).map(i => s"g$i").toList
    val hubFormula = PositiveFormula.PositiveAnd(leaves.map(name => PositiveFormula.TransitionAtom(name, Action.Goto)))
    val transitions = leaves.map(name => name -> PositiveFormula.PositiveConstant(true)).toMap + ("hub" -> hubFormula)
    val rank = leaves.zipWithIndex.toMap + ("hub" -> n)
    val pvwaa = ForwardPVWAA(alphabet, "hub" :: leaves, transitions, "hub", leaves.toSet, rank)
    BooleanAutomaton.fromForwardPvwaa(pvwaa)

  // Many independent "hub" states (not just one), each `goto`-ing the same
  // 14 leaf states — every individual hub's support is 14, comfortably
  // under checkSupportSize's per-state cap of 24, but summed across all
  // `hubCount` hubs (each contributing its own 2^14 cells) the *aggregate*
  // still exceeds the aggregate cap. This is the shape that actually
  // caused an OutOfMemoryError on a real `ltl_examples/OrderedSequence`
  // benchmark (130 states, none over local support 16, but N x
  // alphabetSize ~ 21 million) before the aggregate check existed.
  private def aggregateOversizedAutomaton(alphabet: List[String] = List("a", "b")): ReverseBooleanAutomaton =
    val leafCount = 14
    val hubCount = 100
    val leaves = (0 until leafCount).map(i => s"g$i").toList
    val hubs = (0 until hubCount).map(i => s"hub$i").toList
    val hubFormula = PositiveFormula.PositiveAnd(leaves.map(name => PositiveFormula.TransitionAtom(name, Action.Goto)))
    val transitions =
      leaves.map(name => name -> PositiveFormula.PositiveConstant(true)).toMap ++
        hubs.map(hub => hub -> hubFormula).toMap
    val rank = leaves.zipWithIndex.toMap ++ hubs.map(hub => hub -> leafCount).toMap
    val pvwaa = ForwardPVWAA(alphabet, hubs ++ leaves, transitions, hubs.head, leaves.toSet, rank)
    BooleanAutomaton.fromForwardPvwaa(pvwaa)

  test("checkSupportSize rejects an aggregate blow-up even when no single state exceeds the per-state cap") {
    val automaton = aggregateOversizedAutomaton()
    // Every individual state's local support (14) is well under the
    // per-state cap (24) — this must fail for the *aggregate* reason, not
    // the per-state one.
    assert(automaton.support.values.forall(_.length <= 14))
    val error = intercept[PVWAAError](BooleanAutomaton.checkSupportSize(automaton))
    assert(error.message.contains("aggregate"), error.message)
    assert(!error.message.contains("goto-support states, exceeding this backend's per-state limit"), error.message)

    // The rejection is shared plumbing (checkSupportSize), so every
    // explicit-table backend must refuse it the same way, cleanly, rather
    // than attempt to materialize it (which is exactly what OOM'd before).
    intercept[AigerError](Aiger.generateSafety(automaton))
  }

  test("aiger encoding matches BooleanAutomaton.accepts prefix-by-prefix") {
    val alphabet = List("a", "b")
    val automaton = sampleAutomaton(alphabet)
    val model = Aiger.generateSafety(automaton)
    val header = new String(model, 0, math.min(model.length, 64), java.nio.charset.StandardCharsets.US_ASCII)
    assert(header.startsWith("aig "))
    // AND gates are raw binary bytes with no newline separators, so the "c"
    // comment marker isn't necessarily preceded by one.
    assert(new String(model, java.nio.charset.StandardCharsets.US_ASCII).contains("c\n"))

    for
      length <- 1 until 6
      word <- wordsOfLength(length, alphabet)
    do
      val indices = word.map(alphabet.indexOf).toIndexedSeq
      val expected = (1 to word.length).map(k => BooleanAutomaton.accepts(automaton, word.take(k).toIndexedSeq)).toList
      assertEquals(runAiger(model, indices), expected, s"word=$word")
  }

  test("aiger encoding rejects non-power-of-two alphabets") {
    val alphabet = List("a", "b", "c")
    val automaton = sampleAutomaton(alphabet)
    intercept[AigerError] {
      Aiger.generateSafety(automaton)
    }
  }

  test("aiger encoding covers the --subset/--equivalent reduction too") {
    val alphabet = List("a", "b")
    val subsetProgram = Inclusion.counterexampleProgram(
      Brasp.fromJson(obj("alphabet" -> arr(str("a"), str("b")), "program" -> arr(obj("name" -> str("a"), "op" -> str("symbol"), "symbol" -> str("a"))), "output" -> str("a"))),
      Brasp.fromJson(obj("alphabet" -> arr(str("a"), str("b")), "program" -> arr(obj("name" -> str("yes"), "op" -> str("const"), "value" -> bool(true))), "output" -> str("yes"))),
    )
    val ltl = BraspToLtl.translateProgram(subsetProgram)
    val automaton = BooleanAutomaton.fromForwardPvwaa(Pvwaa.fromFuture2ltl(Translator.mirrorToFuture(ltl)))
    val model = Aiger.generateSafety(automaton)
    for
      length <- 0 until 4
      word <- wordsOfLength(length, alphabet)
    do
      val indices = word.map(alphabet.indexOf).toIndexedSeq
      val expected = (1 to word.length).map(k => BooleanAutomaton.accepts(automaton, word.take(k).toIndexedSeq)).toList
      assertEquals(runAiger(model, indices), expected, s"word=$word")
  }

  test("aiger DFA encoding (generateSafetyFromDfa) matches BooleanAutomaton.accepts prefix-by-prefix") {
    val alphabet = List("a", "b")
    val automaton = sampleAutomaton(alphabet)
    val dfa = BooleanAutomaton.reachable(automaton, maxStates = 512)
    assert(!dfa.truncated)
    val model = Aiger.generateSafetyFromDfa(dfa, alphabet)
    val header = new String(model, 0, math.min(model.length, 64), java.nio.charset.StandardCharsets.US_ASCII)
    assert(header.startsWith("aig "))

    for
      length <- 1 until 6
      word <- wordsOfLength(length, alphabet)
    do
      val indices = word.map(alphabet.indexOf).toIndexedSeq
      val expected = (1 to word.length).map(k => BooleanAutomaton.accepts(automaton, word.take(k).toIndexedSeq)).toList
      assertEquals(runAiger(model, indices), expected, s"word=$word")
  }

  test("aiger generateSafetyAuto prefers the explicit-table encoding when local support fits") {
    // `sampleAutomaton`'s support is well under the checkSupportSize cap,
    // so this must never touch
    // BooleanAutomaton.reachable at all — even with maxStates = 0.
    val alphabet = List("a", "b")
    val automaton = sampleAutomaton(alphabet)
    val autoModel = Aiger.generateSafetyAuto(automaton, maxStates = 0)
    assertEquals(autoModel.toVector, Aiger.generateSafety(automaton).toVector)

    for
      length <- 1 until 6
      word <- wordsOfLength(length, alphabet)
    do
      val indices = word.map(alphabet.indexOf).toIndexedSeq
      val expected = (1 to word.length).map(k => BooleanAutomaton.accepts(automaton, word.take(k).toIndexedSeq)).toList
      assertEquals(runAiger(autoModel, indices), expected, s"word=$word")
  }

  test("aiger generateSafetyFromDfa rejects a truncated DFA") {
    val automaton = sampleAutomaton(List("a", "b"))
    val truncated = BooleanAutomaton.reachable(automaton, maxStates = 1)
    assert(truncated.truncated)
    intercept[AigerError](Aiger.generateSafetyFromDfa(truncated, List("a", "b")))
  }

  test("aiger generateSafetyAuto falls back to the DFA encoding when local support is too large") {
    val automaton = oversizedSupportAutomaton(List("a", "b"))
    intercept[PVWAAError](BooleanAutomaton.checkSupportSize(automaton))
    val autoModel = Aiger.generateSafetyAuto(automaton, maxStates = 64)
    val dfa = BooleanAutomaton.reachable(automaton, maxStates = 64)
    assert(!dfa.truncated)
    assertEquals(autoModel.toVector, Aiger.generateSafetyFromDfa(dfa, automaton.source.alphabet).toVector)
  }

  test("aiger generateSafetyAuto fails clearly when neither encoding is viable") {
    // Local support is too large for generateSafety, and maxStates = 0
    // truncates the DFA search immediately too, so both encodings are
    // impractical and this must fail with generateSafety's own error.
    val automaton = oversizedSupportAutomaton(List("a", "b"))
    intercept[AigerError](Aiger.generateSafetyAuto(automaton, maxStates = 0))
  }

  test("minimize preserves acceptance behavior on a real automaton") {
    val alphabet = List("a", "b")
    val automaton = sampleAutomaton(alphabet)
    val dfa = BooleanAutomaton.reachable(automaton, maxStates = 512)
    assert(!dfa.truncated)
    val minimized = BooleanAutomaton.minimize(dfa, alphabet)
    assert(minimized.stateCount <= dfa.stateCount)

    for
      length <- 1 until 6
      word <- wordsOfLength(length, alphabet)
    do
      val expected = (1 to word.length).map(k => BooleanAutomaton.accepts(automaton, word.take(k).toIndexedSeq)).toList
      assertEquals(runDfa(minimized, word), expected, s"word=$word")

    // Minimizing an already-minimal DFA must be a no-op (Moore's algorithm
    // has already found the coarsest partition consistent with acceptance).
    assertEquals(BooleanAutomaton.minimize(minimized, alphabet).stateCount, minimized.stateCount)
  }

  test("minimize rejects a truncated DFA") {
    val automaton = sampleAutomaton(List("a", "b"))
    val truncated = BooleanAutomaton.reachable(automaton, maxStates = 1)
    assert(truncated.truncated)
    intercept[PVWAAError](BooleanAutomaton.minimize(truncated, List("a", "b")))
  }

  test("minimize actually merges behaviorally-equivalent states") {
    // 0 -a-> 1, 0 -b-> 2, and 1/2 are indistinguishable twins (both
    // non-accepting, both go straight to the accepting sink 3 on either
    // symbol) — exactly the classic redundant-pair textbook example, and
    // structurally the same shape a symmetric equivalence/inclusion
    // reduction (comparing a spec against a syntactically different but
    // language-identical copy of itself) would produce two independent,
    // never-merged-by-reachable's-exact-equality-dedup copies of.
    val alphabet = List("a", "b")
    val transitions = VectorMap(
      (0, "a") -> 1,
      (0, "b") -> 2,
      (1, "a") -> 3,
      (1, "b") -> 3,
      (2, "a") -> 3,
      (2, "b") -> 3,
      (3, "a") -> 3,
      (3, "b") -> 3,
    )
    val dfa = ReachableDfa(stateCount = 4, transitions = transitions, accepting = Set(3), initial = 0, truncated = false)
    val minimized = BooleanAutomaton.minimize(dfa, alphabet)
    assertEquals(minimized.stateCount, 3)

    for
      length <- 1 until 4
      word <- wordsOfLength(length, alphabet)
    do assertEquals(runDfa(minimized, word), runDfa(dfa, word), s"word=$word")
  }

  test("minimize always renumbers the initial state's block to 0") {
    // Same redundant-pair shape as above, but with `initial = 2` instead
    // of 0 — `ReachableDfa.initial` is documented as always 0, and
    // `Aiger.generateSafetyFromDfa` relies on that without ever reading
    // `.initial` (its latches just physically reset to all-zero), so a
    // minimizer that only *happened* to put block 0 first because state 0
    // came first in iteration order — rather than because it's the actual
    // initial state — would silently produce a wrong circuit whenever the
    // input DFA's initial state isn't literally state 0.
    val alphabet = List("a", "b")
    val transitions = VectorMap(
      (2, "a") -> 0,
      (2, "b") -> 1,
      (0, "a") -> 3,
      (0, "b") -> 3,
      (1, "a") -> 3,
      (1, "b") -> 3,
      (3, "a") -> 3,
      (3, "b") -> 3,
    )
    val dfa = ReachableDfa(stateCount = 4, transitions = transitions, accepting = Set(3), initial = 2, truncated = false)
    val minimized = BooleanAutomaton.minimize(dfa, alphabet)
    assertEquals(minimized.initial, 0)
    assertEquals(minimized.stateCount, 3)

    for
      length <- 1 until 4
      word <- wordsOfLength(length, alphabet)
    do assertEquals(runDfa(minimized, word), runDfa(dfa, word), s"word=$word")
  }

  test("BooleanAutomaton.witness finds the shortest word reaching an accepting state") {
    // 0 -a-> 1 -a-> 2(accepting); 0 -b-> 0 (self-loop, never reaches 2 via b alone).
    val dfa = ReachableDfa(
      stateCount = 3,
      transitions = VectorMap((0, "a") -> 1, (0, "b") -> 0, (1, "a") -> 2, (1, "b") -> 0),
      accepting = Set(2),
      initial = 0,
      truncated = false,
    )
    assertEquals(BooleanAutomaton.witness(dfa, List("a", "b")), Some(List("a", "a")))

    val unreachable = dfa.copy(accepting = Set.empty)
    assertEquals(BooleanAutomaton.witness(unreachable, List("a", "b")), None)
  }

  test("native summary reports PROVED/NOT PROVED/UNKNOWN, computed with no external solver") {
    val emptyDfa = ReachableDfa(1, VectorMap.empty, Set.empty, 0, truncated = false)
    val proved = Translator.nativeSummary(emptyDfa, List("a", "b"), goal = "safety", emptyBad = false)
    assert(proved.contains("PROVED"))
    assert(proved.contains("✓ no nonempty bad prefix (unsat)"))

    val emptyWordBad = Translator.nativeSummary(emptyDfa, List("a", "b"), goal = "inclusion", emptyBad = true)
    assert(emptyWordBad.contains("NOT PROVED"))
    assert(emptyWordBad.contains("✗ no empty-word counterexample (sat) — witness: ε"))

    val reachingDfa = ReachableDfa(2, VectorMap((0, "a") -> 1), Set(1), 0, truncated = false)
    val counterexample = Translator.nativeSummary(reachingDfa, List("a", "b"), goal = "equivalence", emptyBad = false)
    assert(counterexample.contains("NOT PROVED"))
    assert(counterexample.contains("witness: a"))

    val truncated = ReachableDfa(4096, VectorMap.empty, Set.empty, 0, truncated = true)
    val unknown = Translator.nativeSummary(truncated, List("a", "b"), goal = "safety", emptyBad = false)
    assert(unknown.contains("UNKNOWN"))
    assert(unknown.contains("--native-max-states"))
  }

  test("conflict summary mirrors nativeSummary's own PROVED/NOT PROVED/UNKNOWN shape") {
    val proved = Translator.conflictSummary(BooleanAutomaton.ConflictResult(None, 0, truncated = false), goal = "safety", emptyBad = false)
    assert(proved.contains("brasp-native-conflict: PROVED"))
    assert(proved.contains("✓ no nonempty bad prefix (unsat)"))

    val counterexample =
      Translator.conflictSummary(BooleanAutomaton.ConflictResult(Some(List("a")), 1, truncated = false), goal = "equivalence", emptyBad = false)
    assert(counterexample.contains("NOT PROVED"))
    assert(counterexample.contains("witness: a"))

    val unknown = Translator.conflictSummary(BooleanAutomaton.ConflictResult(None, 4096, truncated = true), goal = "safety", emptyBad = false)
    assert(unknown.contains("UNKNOWN"))
    assert(unknown.contains("--native-max-states"))
  }

  /** `BooleanAutomaton.conflictWitness` and `reachable` + `witness` decide
    * the exact same question (is there a reachable summary whose diagonal
    * is true at the source's initial state) by two different searches —
    * this checks they never disagree, across both hand-built automata
    * (`sampleAutomaton`, the "hub" automata whose whole point is an
    * explicit-table blow-up neither search should ever hit) and real
    * compiled formulas (`same_letter_before`, `at_least_two_a` — the
    * latter exercising `Goto`/`Carry` nesting, same as `DirectPvwaa`'s own
    * motivating example).
    */
  private def assertConflictAgreesWithReachable(automaton: ReverseBooleanAutomaton, maxStates: Int = 4096): Unit =
    val dfa = BooleanAutomaton.reachable(automaton, maxStates)
    assert(!dfa.truncated, "test automaton must fit within maxStates for a meaningful comparison")
    val reachableWitness = BooleanAutomaton.witness(dfa, automaton.source.alphabet)
    val conflictResult = BooleanAutomaton.conflictWitness(automaton, maxStates)
    assert(!conflictResult.truncated)
    assertEquals(conflictResult.witness.isDefined, reachableWitness.isDefined)
    conflictResult.witness.foreach { word =>
      assert(BooleanAutomaton.accepts(automaton, word.toIndexedSeq), s"conflictWitness's own witness $word must itself be accepted")
    }
    reachableWitness.foreach { word =>
      assert(BooleanAutomaton.accepts(automaton, word.toIndexedSeq), s"reachable/witness's own witness $word must itself be accepted")
    }

  test("BooleanAutomaton.conflictWitness agrees with reachable+witness on hand-built automata") {
    assertConflictAgreesWithReachable(sampleAutomaton())
    assertConflictAgreesWithReachable(oversizedSupportAutomaton(), maxStates = 64)
    assertConflictAgreesWithReachable(aggregateOversizedAutomaton())
  }

  test("BooleanAutomaton.conflictWitness agrees with reachable+witness on real compiled formulas") {
    assertConflictAgreesWithReachable(BooleanAutomaton.fromForwardPvwaa(pvwaaFromLtl(sameLetterBeforeLtl)))
    assertConflictAgreesWithReachable(BooleanAutomaton.fromForwardPvwaa(pvwaaFromBrasp(nestedGotoBrasp)))
    assertConflictAgreesWithReachable(BooleanAutomaton.fromForwardPvwaa(pvwaaFromBrasp(atLeastTwoABrasp)))
  }

  // A genuinely empty language (`s0` only ever `Carry`s a permanent
  // `false`), plus a "decoy" `Carry` chain over `c0..c(chainLen-1)` whose
  // own summaries keep growing with word length (each `c_i` bakes in
  // whichever symbol was seen `i` steps ago) purely to give
  // `conflictWitness` a reachable state space actually worth truncating —
  // unlike `oversizedSupportAutomaton`/`aggregateOversizedAutomaton` above,
  // whose all-`Goto`/all-constant formulas resolve to "final" after a
  // single step regardless of budget, this can never short-circuit that
  // way, so it's the one to use for testing truncation specifically.
  //
  // `s1` ANDs in a `Carry` reference to the decoy chain's own last state --
  // syntactically redundant (`x & false` always reduces to the same
  // `Bdd.False` node `false` alone would, by ROBDD canonicality, so it
  // changes no *value* anywhere) but it's exactly what makes the whole
  // decoy chain reachable from `s0` in the transition-formula graph, and
  // therefore part of `conflictWitness`'s own `relevantStates(initialState)`
  // closure -- without it, `relevantStates` would (correctly) prune the
  // entire decoy chain as dead weight never actually read by `s0`, and this
  // test would stop exercising real state-space growth at all.
  private def emptyLanguageAutomaton(chainLen: Int = 8): ReverseBooleanAutomaton =
    val decoy = (0 until chainLen).map(i => s"c$i").toList
    val decoyTransitions: Map[String, PositiveFormula] =
      decoy.zipWithIndex.map {
        case (name, 0)     => name -> PositiveFormula.SymbolTest(AtomKind.SymbolAtom, Some("a"), false)
        case (name, index) => name -> PositiveFormula.TransitionAtom(decoy(index - 1), Action.Carry)
      }.toMap
    val transitions = decoyTransitions ++ Map(
      "s0" -> PositiveFormula.TransitionAtom("s1", Action.Carry),
      "s1" -> PositiveFormula.PositiveAnd(
        List(PositiveFormula.TransitionAtom(decoy(chainLen - 1), Action.Carry), PositiveFormula.PositiveConstant(false))
      ),
    )
    val rank = decoy.zipWithIndex.toMap ++ Map("s1" -> chainLen, "s0" -> (chainLen + 1))
    val pvwaa = ForwardPVWAA(List("a", "b"), "s0" :: "s1" :: decoy, transitions, "s0", Set.empty, rank)
    BooleanAutomaton.fromForwardPvwaa(pvwaa)

  test("BooleanAutomaton.conflictWitness respects maxStates the same way reachable does") {
    val automaton = emptyLanguageAutomaton()

    val tooSmall = BooleanAutomaton.conflictWitness(automaton, maxStates = 64)
    assert(tooSmall.truncated)
    assertEquals(tooSmall.witness, None)
    assertEquals(BooleanAutomaton.reachable(automaton, maxStates = 64).truncated, true)

    // large enough to actually prove emptiness (256 reachable summaries,
    // 255 of them beyond the freely-explored initial state).
    val enough = BooleanAutomaton.conflictWitness(automaton, maxStates = 4096)
    assert(!enough.truncated)
    assertEquals(enough.witness, None)
    assertEquals(BooleanAutomaton.reachable(automaton, maxStates = 4096).stateCount, 256)
  }

  /** `conflictWitness`'s headline result is on `y_depth`, a family
    * `BooleanAutomaton.reachable` can't decide at all within a practical
    * budget (its wide-but-shallow reachable graph is exactly what BFS
    * pays for and DFS doesn't) — which means the usual "cross-check
    * against `reachable` + `witness`" test above can't actually validate
    * it there; `reachable` never finishes long enough to have an answer
    * to compare against. This instead validates the one thing that
    * matters directly: replay the word `conflictWitness` claims is a
    * witness through `Pvwaa.accepts` -- the original recursive
    * alternating-automaton evaluator, a completely different code path
    * from `BooleanAutomaton`'s `diagonal`/`transition` that
    * `conflictWitness` itself is built on, so this isn't just checking
    * `conflictWitness` against itself. `dot_depth` is included too, both
    * because it's cheap and because `reachable` *can* decide it (checked
    * directly here as an extra cross-check, unlike `y_depth`).
    *
    * Skips any fixture that isn't present, matching
    * `PerfRegressionSuite`/`LtlExamplesCrossCheckSuite`'s own convention
    * for repo-relative benchmark files that aren't guaranteed to exist in
    * every checkout.
    */
  test("BooleanAutomaton.conflictWitness's witnesses on y_depth/dot_depth are genuinely accepted (independent Pvwaa.accepts check)") {
    val yDepthFixtures = (17 to 30).map(k => new File(s"examples/brasp/y_depth__k-$k.brasp"))
    val dotDepthFixtures = List(9, 15, 24).map(k => new File(s"examples/brasp/dot_depth__k-${k}__sigma-2.brasp"))
    val fixtures = yDepthFixtures ++ dotDepthFixtures
    assume(fixtures.exists(_.isFile), "no examples/brasp fixtures found in this checkout")

    for file <- fixtures if file.isFile do
      val pvwaa = pvwaaFromBrasp(Files.readString(file.toPath))
      val automaton = BooleanAutomaton.fromForwardPvwaa(pvwaa)
      val result = BooleanAutomaton.conflictWitness(automaton, maxStates = 200_000)
      assert(!result.truncated, s"$file: conflictWitness truncated within 200,000 states")
      val word = result.witness.getOrElse(fail(s"$file: expected a nonempty witness, found none"))
      // the PVWAA accepts reverse(w) (see Pvwaa.scala's own directional
      // note); `--word` on the CLI applies this same reversal.
      assert(Pvwaa.accepts(pvwaa, word.reverse.toIndexedSeq), s"$file: witness $word is not actually accepted by Pvwaa.accepts")

    // dot_depth's reachable graph is small enough that `reachable` +
    // `witness` can decide it directly too -- an extra cross-check beyond
    // the independent-evaluator one above, same as the other
    // `assertConflictAgreesWithReachable` tests already do for other
    // automata.
    for file <- dotDepthFixtures if file.isFile do
      assertConflictAgreesWithReachable(BooleanAutomaton.fromForwardPvwaa(pvwaaFromBrasp(Files.readString(file.toPath))), maxStates = 200_000)
  }

  test("ltl evaluator supports unary past and future operators") {
    val pastPrevious = FormulaDag(
      Logic.PastStrict,
      VectorMap.empty,
      Formula.Previous(Position.I, Position.J, Formula.Atom(AtomKind.SymbolAtom, Position.J, Some("a"))),
      "i = |w|",
    )
    val pastOnce = FormulaDag(
      Logic.PastStrict,
      VectorMap.empty,
      Formula.Once(Position.I, Position.J, Formula.Atom(AtomKind.SymbolAtom, Position.J, Some("a"))),
      "i = |w|",
    )
    val futureNext = FormulaDag(
      Logic.FutureStrict,
      VectorMap.empty,
      Formula.Next(Position.I, Position.J, Formula.Atom(AtomKind.SymbolAtom, Position.J, Some("b"))),
      "i = 0",
    )
    val futureEventually = FormulaDag(
      Logic.FutureStrict,
      VectorMap.empty,
      Formula.Eventually(Position.I, Position.J, Formula.Atom(AtomKind.EosAtom, Position.J)),
      "i = 0",
    )
    assert(Ltl.evaluate(pastPrevious, IndexedSeq("a", "b")))
    assert(Ltl.evaluate(pastOnce, IndexedSeq("a", "b")))
    assert(Ltl.evaluate(futureNext, IndexedSeq("a", "b")))
    assert(Ltl.evaluate(futureEventually, IndexedSeq("a", "b")))
  }

  test("rejects forward reference and leftmost compilation") {
    intercept[TranslationError] {
      BraspToLtl.translate(
        obj(
          "alphabet" -> arr(str("a")),
          "program" -> arr(obj("name" -> str("p"), "op" -> str("not"), "arg" -> obj("op" -> str("ref"), "name" -> str("q")))),
        )
      )
    }
    intercept[TranslationError] {
      BraspToLtl.translate(
        obj(
          "alphabet" -> arr(str("a")),
          "program" -> arr(
            obj(
              "name" -> str("p"),
              "op" -> str("leftmost"),
              "score" -> obj("op" -> str("const"), "value" -> bool(true)),
              "value" -> obj("op" -> str("const"), "value" -> bool(true)),
            )
          ),
        )
      )
    }
  }

  test("Ltl.mirror is stack-safe on a formula nested far deeper than any JVM default thread stack") {
    // Built and checked *iteratively*, not recursively -- constructing the
    // formula, or verifying the result with a recursive walk (or even
    // `==`, whose case-class-generated `equals` recurses into nested
    // fields), would just reintroduce the same stack-depth problem
    // `mirror` itself used to have. Real benchmark formulas
    // (`two_var/monotone_past`) reach ~860 levels and already overflowed
    // the default ~512KB-1MB stack (see `Ltl.mirror`'s own doc-comment);
    // this goes three orders of magnitude deeper to prove the fix isn't
    // just "the JVM happened to have enough stack for the formulas we
    // tried."
    val depth = 500_000
    var deep: Formula = Formula.Constant(true)
    for _ <- 0 until depth do deep = Formula.Negation(deep)

    def negationDepth(root: Formula): (Int, Formula) =
      var node = root
      var count = 0
      while node.isInstanceOf[Formula.Negation] do
        node = node.asInstanceOf[Formula.Negation].operand
        count += 1
      (count, node)

    val (mirroredDepth, mirroredLeaf) = negationDepth(Ltl.mirror(deep))
    assertEquals(mirroredDepth, depth)
    assertEquals(mirroredLeaf, Formula.Constant(true))

    // `mirror` is its own inverse (see its own doc-comment) -- mirroring
    // back should exactly reproduce the original depth too.
    val (roundTrippedDepth, roundTrippedLeaf) = negationDepth(Ltl.mirror(Ltl.mirror(deep)))
    assertEquals(roundTrippedDepth, depth)
    assertEquals(roundTrippedLeaf, Formula.Constant(true))
  }
