package brasp

import scala.collection.immutable.VectorMap
import JsonValue.*

class TranslatorSuite extends munit.FunSuite:

  private def wordsOfLength(n: Int, alphabet: List[String]): List[List[String]] =
    if n == 0 then List(Nil)
    else for first <- alphabet; rest <- wordsOfLength(n - 1, alphabet) yield first :: rest

  /** A tiny BTOR2 interpreter covering exactly the op vocabulary `Btor2`
    * emits (sort/zero/one/constd/input/state/init/next/eq/ite/and/or/ult/bad),
    * used to check the encoder against `BooleanAutomaton.accepts` without a
    * real BTOR2 solver. Returns, for a run over `symbolIndices`, whether
    * `bad` holds after consuming each prefix (index `k` = word of length
    * `k + 1`).
    */
  private def runBtor2(model: String, symbolIndices: IndexedSeq[Int]): List[Boolean] =
    case class Op(keyword: String, args: List[Int])
    val ops = scala.collection.mutable.Map.empty[Int, Op]
    val consts = scala.collection.mutable.Map.empty[Int, Int]
    val stateIds = scala.collection.mutable.Set.empty[Int]
    val inputIds = scala.collection.mutable.Set.empty[Int]
    val registerInit = scala.collection.mutable.Map.empty[Int, Int]
    val registerNext = scala.collection.mutable.Map.empty[Int, Int]
    val badIds = scala.collection.mutable.ArrayBuffer.empty[Int]

    for line <- model.linesIterator if line.nonEmpty && !line.startsWith(";") do
      val parts = line.trim.split("\\s+").toList
      val id = parts.head.toInt
      parts(1) match
        case "sort"                    => ()
        case "zero"                    => consts(id) = 0
        case "one"                     => consts(id) = 1
        case "constd"                  => consts(id) = parts(3).toInt
        case "input"                   => inputIds += id
        case "state"                   => stateIds += id
        case "init"                    => registerInit(parts(3).toInt) = parts(4).toInt
        case "next"                    => registerNext(parts(3).toInt) = parts(4).toInt
        case "bad"                     => badIds += parts(2).toInt
        case "constraint"              => ()
        case "eq" | "ult"              => ops(id) = Op(parts(1), List(parts(3).toInt, parts(4).toInt))
        case "and" | "or"              => ops(id) = Op(parts(1), List(parts(3).toInt, parts(4).toInt))
        case "ite"                     => ops(id) = Op(parts(1), List(parts(3).toInt, parts(4).toInt, parts(5).toInt))
        case other                     => throw new RuntimeException(s"runBtor2: unsupported op '$other'")

    var registers = stateIds.map(id => id -> consts(registerInit(id))).toMap
    var currentInput = 0
    def eval(id: Int, memo: scala.collection.mutable.Map[Int, Int]): Int =
      memo.getOrElseUpdate(
        id,
        if stateIds.contains(id) then registers(id)
        else if inputIds.contains(id) then currentInput
        else
          consts.get(id) match
            case Some(v) => v
            case None =>
              val op = ops(id)
              op.keyword match
                case "eq"  => if eval(op.args(0), memo) == eval(op.args(1), memo) then 1 else 0
                case "ult" => if eval(op.args(0), memo) < eval(op.args(1), memo) then 1 else 0
                case "and" => if eval(op.args(0), memo) == 1 && eval(op.args(1), memo) == 1 then 1 else 0
                case "or"  => if eval(op.args(0), memo) == 1 || eval(op.args(1), memo) == 1 then 1 else 0
                case "ite" => if eval(op.args(0), memo) == 1 then eval(op.args(1), memo) else eval(op.args(2), memo)
                case other => throw new RuntimeException(s"runBtor2: unsupported op '$other'"),
      )

    symbolIndices.map { symbol =>
      currentInput = symbol
      val memo = scala.collection.mutable.Map.empty[Int, Int]
      val bad = badIds.exists(eval(_, memo) == 1)
      registers = stateIds.map(id => id -> eval(registerNext(id), memo)).toMap
      bad
    }.toList

  /** `runBtor2`, extended for `DirectPvwaa`'s models: unlike every other
    * encoder, these have more than one `input` (the shared `symbol`, plus
    * one existentially-guessed "next obligation" per PVWAA state) — a
    * single shared `currentInput` can't represent that. `symbol` is always
    * the first declared input (see `DirectPvwaa.generateSafety`'s own
    * doc-comment); every other input is a guess this search existentially
    * quantifies over via a small BFS across reachable register-value
    * tuples, exactly mirroring what the real solver does. Returns, for a
    * run over `symbolIndices`, whether `bad` is reachable after consuming
    * each prefix — small automata only (guess count is exponential in the
    * search), which is all this backend's own tests need.
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
    * then a `c` comment section it ignores), used the same way `runBtor2`
    * is: to check the encoder — including its exact byte-level format,
    * not just its node graph — against `BooleanAutomaton.accepts` without
    * a real AIGER solver. Every latch is reset-to-0 by construction (see
    * `Aiger`'s doc-comment), so unlike `runBtor2` there's no separate init
    * value to read. Returns, for a run over `symbolIndices`, whether the
    * output holds after consuming each prefix.
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

  /** `runAiger`, extended the same way `runDirectPvwaa` extends `runBtor2`:
    * only the first `symbolWidth` inputs are the bit-blasted `symbol` —
    * every input after that is a per-state guess, existentially quantified
    * via a BFS across reachable latch-state tuples. Small automata only,
    * same caveat as `runDirectPvwaa`.
    */
  private def runDirectPvwaaAiger(model: Array[Byte], symbolIndices: IndexedSeq[Int], symbolWidth: Int): List[Boolean] =
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

    val guessCount = i - symbolWidth
    def valueOf(literal: Int, inputVals: Map[Int, Int], latchState: Map[Int, Int], memo: scala.collection.mutable.Map[Int, Int]): Int =
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
              case Some((aLit, bLit)) => valueOf(aLit, inputVals, latchState, memo) & valueOf(bLit, inputVals, latchState, memo)
              case None                => throw new RuntimeException(s"runDirectPvwaaAiger: unknown variable $v"),
        )
        raw ^ negated

    var frontier = Set((0 until l).map(k => 2 * (i + k + 1) -> 0).toMap) // every latch resets to 0
    symbolIndices.map { symbol =>
      val symbolBits = (0 until symbolWidth).map(bitPos => 2 * (bitPos + 1) -> ((symbol >> (symbolWidth - 1 - bitPos)) & 1)).toMap
      val nextFrontier = scala.collection.mutable.Set.empty[Map[Int, Int]]
      for
        latchState <- frontier
        guessMask <- 0 until (1 << guessCount)
      do
        val guessBits = (0 until guessCount).map(k => 2 * (symbolWidth + k + 1) -> ((guessMask >> k) & 1)).toMap
        val inputVals = symbolBits ++ guessBits
        val memo = scala.collection.mutable.Map.empty[Int, Int]
        nextFrontier += latchNextLits.zipWithIndex.map { case (nextLit, k) => 2 * (i + k + 1) -> valueOf(nextLit, inputVals, latchState, memo) }.toMap
      frontier = nextFrontier.toSet
      frontier.exists(latchState => valueOf(outputLits.head, Map.empty, latchState, scala.collection.mutable.Map.empty) == 1)
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
    val lustre = Lustre.generate(booleanAutomaton, "example_monitor")
    assert(booleanAutomaton.gotoSupport.nonEmpty)
    assert(lustre.contains("node example_monitor("))
    assert(lustre.contains("accept_prefix ="))
    assert(lustre.contains("accept_word = last and accept_prefix;"))
    assert(lustre.contains("pre("))
    assert(lustre.contains("if old_v_"))
    val kind2Safety = Kind2.generateSafety(booleanAutomaton)
    assert(kind2Safety.contains("guarantee not bad;"))
    assert(kind2Safety.contains("--%MAIN ;"))
    val kind2Equivalence = Kind2.generateEquivalence(booleanAutomaton, booleanAutomaton)
    assert(kind2Equivalence.contains("guarantee left_ok = right_ok;"))

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

  test("kind2 summary is compact and reports the two inclusion cases") {
    val report = Json.render(
      arr(
        obj("objectType" -> str("property"), "expr" -> str("(not bad)"), "answer" -> obj("value" -> str("valid"))),
        obj("objectType" -> str("property"), "expr" -> str("(not empty_bad)"), "answer" -> obj("value" -> str("valid"))),
      )
    )
    assertEquals(
      Kind2.summarize(report, goal = "inclusion"),
      "Kind2: PROVED — inclusion holds.\n  ✓ no nonempty counterexample (valid)\n  ✓ no empty-word counterexample (valid)",
    )
  }

  test("kind2 summary extracts a counterexample word") {
    val report = Json.render(
      arr(
        obj(
          "objectType" -> str("property"),
          "expr" -> str("(not bad)"),
          "answer" -> obj("value" -> str("falsifiable")),
          "counterExample" -> arr(
            obj(
              "streams" -> arr(
                obj(
                  "name" -> str("symbol"),
                  "class" -> str("input"),
                  "instantValues" -> arr(arr(JsonValue.int(0), JsonValue.int(1)), arr(JsonValue.int(1), JsonValue.int(0))),
                )
              )
            )
          ),
        ),
        obj("objectType" -> str("property"), "expr" -> str("(not empty_bad)"), "answer" -> obj("value" -> str("falsifiable"))),
      )
    )
    val summary = Kind2.summarize(report, goal = "inclusion", alphabet = List("a", "b"))
    assert(summary.contains("witness: b a"))
    assert(summary.contains("witness: ε"))
  }

  test("kind2 equivalence summary uses distinguishing word language") {
    val report = Json.render(
      arr(obj("objectType" -> str("property"), "expr" -> str("(not bad)"), "answer" -> obj("value" -> str("valid"))))
    )
    assert(Kind2.summarize(report, goal = "equivalence").contains("the languages are equivalent"))
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

  test("btor2 encoding matches BooleanAutomaton.accepts prefix-by-prefix") {
    val alphabet = List("a", "b")
    val automaton = sampleAutomaton(alphabet)
    val model = Btor2.generateSafety(automaton)
    assert(model.contains("bad "))
    assert(model.contains("state 1"))
    assert(model.contains("input "))

    for
      length <- 1 until 6
      word <- wordsOfLength(length, alphabet)
    do
      val indices = word.map(alphabet.indexOf).toIndexedSeq
      val expected = (1 to word.length).map(k => BooleanAutomaton.accepts(automaton, word.take(k).toIndexedSeq)).toList
      assertEquals(runBtor2(model, indices), expected, s"word=$word")
  }

  test("btor2 DFA encoding (generateSafetyFromDfa) matches BooleanAutomaton.accepts prefix-by-prefix") {
    val alphabet = List("a", "b")
    val automaton = sampleAutomaton(alphabet)
    val dfa = BooleanAutomaton.reachable(automaton, maxStates = 512)
    assert(!dfa.truncated)
    val model = Btor2.generateSafetyFromDfa(dfa, alphabet)
    assert(model.contains("bad "))
    assert(model.contains("input "))

    for
      length <- 1 until 6
      word <- wordsOfLength(length, alphabet)
    do
      val indices = word.map(alphabet.indexOf).toIndexedSeq
      val expected = (1 to word.length).map(k => BooleanAutomaton.accepts(automaton, word.take(k).toIndexedSeq)).toList
      assertEquals(runBtor2(model, indices), expected, s"word=$word")
  }

  test("btor2 generateSafetyAuto prefers the explicit-table encoding when local support fits") {
    // `sampleAutomaton`'s per-state support is well under the checkSupportSize
    // cap, so this must pick generateSafety directly — never touching
    // BooleanAutomaton.reachable at all — even with a tiny maxStates that
    // would truncate the DFA search if it were ever attempted.
    val alphabet = List("a", "b")
    val automaton = sampleAutomaton(alphabet)
    val autoModel = Btor2.generateSafetyAuto(automaton, maxStates = 0)
    assertEquals(autoModel, Btor2.generateSafety(automaton))

    for
      length <- 1 until 6
      word <- wordsOfLength(length, alphabet)
    do
      val indices = word.map(alphabet.indexOf).toIndexedSeq
      val expected = (1 to word.length).map(k => BooleanAutomaton.accepts(automaton, word.take(k).toIndexedSeq)).toList
      assertEquals(runBtor2(autoModel, indices), expected, s"word=$word")
  }

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

  test("DirectPvwaa.generateSafetyAiger matches Pvwaa.accepts prefix-by-prefix") {
    val automaton = pvwaaFromLtl(sameLetterBeforeLtl)
    val model = DirectPvwaa.generateSafetyAiger(automaton)
    val alphabet = automaton.alphabet
    val width = 1 // symbolWidth(2)

    for
      length <- 1 until 5
      word <- wordsOfLength(length, alphabet)
    do
      val indices = word.map(alphabet.indexOf).toIndexedSeq
      val expected = (1 to word.length).map(k => Pvwaa.accepts(automaton, word.take(k).toIndexedSeq)).toList
      assertEquals(runDirectPvwaaAiger(model, indices, width), expected, s"word=$word")
  }

  test("DirectPvwaa.emptyWordAccepted matches Pvwaa.accepts on the empty word") {
    val automaton = pvwaaFromLtl(sameLetterBeforeLtl)
    assertEquals(DirectPvwaa.emptyWordAccepted(automaton), Pvwaa.accepts(automaton, IndexedSeq.empty))
  }

  test("DirectPvwaa.checkGotoTargetsAreSimple rejects a goto-target with its own Carry/Leave structure") {
    // top --goto--> mid --carry--> leaf: `mid` is not symbol-constant (it
    // has its own Carry), the shape `Hist`-based formulas produce and this
    // backend deliberately doesn't support -- see its own doc-comment.
    val transitions = Map(
      ("top", "a")  -> PositiveFormula.TransitionAtom("mid", Action.Goto),
      ("top", "b")  -> PositiveFormula.TransitionAtom("mid", Action.Goto),
      ("mid", "a")  -> PositiveFormula.TransitionAtom("leaf", Action.Carry),
      ("mid", "b")  -> PositiveFormula.TransitionAtom("leaf", Action.Carry),
      ("leaf", "a") -> PositiveFormula.PositiveConstant(true),
      ("leaf", "b") -> PositiveFormula.PositiveConstant(false),
    )
    val automaton = ForwardPVWAA(List("a", "b"), List("top", "mid", "leaf"), transitions, "top", Set("leaf"), Map("top" -> 2, "mid" -> 1, "leaf" -> 0))
    val error = intercept[DirectPvwaaError](DirectPvwaa.generateSafety(automaton))
    assert(error.message.contains("mid"), error.message)
  }

  test("btor2 generateSafetyFromDfa rejects a truncated DFA") {
    val automaton = sampleAutomaton(List("a", "b"))
    val truncated = BooleanAutomaton.reachable(automaton, maxStates = 1)
    assert(truncated.truncated)
    intercept[Btor2Error](Btor2.generateSafetyFromDfa(truncated, List("a", "b")))
  }

  // A single "hub" state that directly `goto`s 30 other (strictly
  // lower-ranked) states, each a trivial constant — support(hub) = 30,
  // over checkSupportSize's cap of 24, so generateSafetyAuto can only reach
  // this automaton via the DFA-encoding branch. Its reachable-state count
  // is tiny (the leaves are constants, so there are only a couple of
  // distinct summaries), so that branch succeeds quickly once tried.
  // `alphabet` defaults to a single symbol for the Btor2/Lustre backends;
  // the Aiger backend needs a power-of-two alphabet (its `symbolWidth`
  // rejects size 1 the same as any other non-power-of-two size), so its
  // tests pass a 2-symbol alphabet instead — every symbol gets the same
  // trivial transition, so which alphabet is used doesn't otherwise matter.
  private def oversizedSupportAutomaton(alphabet: List[String] = List("a")): ReverseBooleanAutomaton =
    val n = 30
    val leaves = (0 until n).map(i => s"g$i").toList
    val hubFormula = PositiveFormula.PositiveAnd(leaves.map(name => PositiveFormula.TransitionAtom(name, Action.Goto)))
    val transitions =
      (for name <- leaves; symbol <- alphabet yield (name, symbol) -> PositiveFormula.PositiveConstant(true)).toMap ++
        alphabet.map(symbol => (("hub", symbol) -> hubFormula)).toMap
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
      (for name <- leaves; symbol <- alphabet yield (name, symbol) -> PositiveFormula.PositiveConstant(true)).toMap ++
        (for hub <- hubs; symbol <- alphabet yield (hub, symbol) -> hubFormula).toMap
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
    intercept[Btor2Error](Btor2.generateSafety(automaton))
    intercept[AigerError](Aiger.generateSafety(automaton))
    intercept[LustreError](Lustre.generate(automaton))
  }

  test("btor2 generateSafetyAuto falls back to the DFA encoding when local support is too large") {
    val automaton = oversizedSupportAutomaton()
    intercept[PVWAAError](BooleanAutomaton.checkSupportSize(automaton))
    val autoModel = Btor2.generateSafetyAuto(automaton, maxStates = 64)
    val dfa = BooleanAutomaton.reachable(automaton, maxStates = 64)
    assert(!dfa.truncated)
    assertEquals(autoModel, Btor2.generateSafetyFromDfa(dfa, automaton.source.alphabet))
  }

  test("btor2 generateSafetyAuto fails clearly when neither encoding is viable") {
    // Local support is too large for generateSafety, and maxStates = 0
    // truncates the DFA search immediately too, so both encodings are
    // impractical and this must fail with generateSafety's own error
    // (not hang, and not silently produce an unsound truncated model).
    val automaton = oversizedSupportAutomaton()
    intercept[Btor2Error](Btor2.generateSafetyAuto(automaton, maxStates = 0))
  }

  test("btor2 encoding adds a range constraint for non-power-of-two alphabets") {
    val alphabet = List("a", "b", "c")
    val automaton = sampleAutomaton(alphabet)
    val model = Btor2.generateSafety(automaton)
    assert(model.contains("ult "))
    assert(model.contains("constraint "))
  }

  test("btor2 encoding covers the --kind2-subset/--kind2-equivalent reduction too") {
    val alphabet = List("a", "b")
    val endsInA = sampleAutomaton(alphabet)
    val subsetProgram = Inclusion.counterexampleProgram(
      Brasp.fromJson(obj("alphabet" -> arr(str("a"), str("b")), "program" -> arr(obj("name" -> str("a"), "op" -> str("symbol"), "symbol" -> str("a"))), "output" -> str("a"))),
      Brasp.fromJson(obj("alphabet" -> arr(str("a"), str("b")), "program" -> arr(obj("name" -> str("yes"), "op" -> str("const"), "value" -> bool(true))), "output" -> str("yes"))),
    )
    val ltl = BraspToLtl.translateProgram(subsetProgram)
    val automaton = BooleanAutomaton.fromForwardPvwaa(Pvwaa.fromFuture2ltl(Translator.mirrorToFuture(ltl)))
    val model = Btor2.generateSafety(automaton, monitorName = "subset_monitor")
    for
      length <- 0 until 4
      word <- wordsOfLength(length, alphabet)
    do
      val indices = word.map(alphabet.indexOf).toIndexedSeq
      val expected = (1 to word.length).map(k => BooleanAutomaton.accepts(automaton, word.take(k).toIndexedSeq)).toList
      assertEquals(runBtor2(model, indices), expected, s"word=$word")
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

  test("aiger encoding covers the --kind2-subset/--kind2-equivalent reduction too") {
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
    // Same rationale as the Btor2 equivalent: `sampleAutomaton`'s support
    // is well under the checkSupportSize cap, so this must never touch
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

  test("ric3 summarize reports proved/not-proved and the empty-word case statically") {
    val proved = Ric3.summarize("UNSAT\n", goal = "safety", alphabet = List("a", "b"), emptyBad = false)
    assert(proved.contains("PROVED"))
    assert(proved.contains("✓ no nonempty bad prefix (unsat)"))

    val emptyWordBad = Ric3.summarize("UNSAT\n", goal = "inclusion", alphabet = List("a", "b"), emptyBad = true)
    assert(emptyWordBad.contains("NOT PROVED"))
    assert(emptyWordBad.contains("✗ no empty-word counterexample (sat) — witness: ε"))

    // rIC3's own log noise (from --ui false) surrounds the SAT verdict and witness on stdout.
    val satStdout = "[10:00:00 INFO] some log line\nSAT\nsat\nb0\n#0\n0 0\n@0\n0 1\n.\n"
    val counterexample = Ric3.summarize(satStdout, goal = "equivalence", alphabet = List("a", "b"), emptyBad = false)
    assert(counterexample.contains("NOT PROVED"))
    assert(counterexample.contains("witness: b"))

    val unknown = Ric3.summarize("UNKNOWN\n", goal = "safety", alphabet = List("a", "b"), emptyBad = false)
    assert(unknown.contains("UNKNOWN"))
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

  test("native summary mirrors ric3's PROVED/NOT PROVED/UNKNOWN shape, computed with no external solver") {
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
