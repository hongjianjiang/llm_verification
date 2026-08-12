package brasp

import scala.collection.immutable.VectorMap
import JsonValue.*

class TranslatorSuite extends munit.FunSuite:

  private def wordsOfLength(n: Int, alphabet: List[String]): List[List[String]] =
    if n == 0 then List(Nil)
    else for first <- alphabet; rest <- wordsOfLength(n - 1, alphabet) yield first :: rest

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
