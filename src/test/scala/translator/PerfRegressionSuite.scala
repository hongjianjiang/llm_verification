package brasp

import java.io.File
import java.nio.file.Files

/** Regression coverage for a specific performance pathology in
  * `BooleanAutomaton.reachable` and `Aiger.generateSafetyAuto`:
  * large-alphabet automata whose local support is also too large for
  * `checkSupportSize`.
  *
  * `reachable`'s cost is `states explored x alphabet size` — an automaton
  * with a wide alphabet makes even a handful of states expensive to
  * explore, and (before this suite existed) two bugs compounded that into
  * a genuine multi-minute hang on `ltl_examples/Nim/nim_heaps=1tokens=1`
  * (alphabet size 256, max local support 128): `generateSafetyAuto`'s
  * "quick DFA attempt" budget was sized purely by state count, ignoring
  * alphabet size, and `reachable` itself kept exploring (wastefully) for a
  * while after `truncated` was already set. Both are fixed now — this
  * measures wall-clock against a deliberately generous bound (large enough
  * to never flake on a slow CI machine, far too small for either bug to
  * pass) rather than asserting an exact call count, since the actual
  * `transition` cost is legitimately data- and machine-dependent.
  *
  * Skips entirely if the fixture file is missing, matching
  * `LtlExamplesCrossCheckSuite`'s convention for repo-relative test data.
  *
  * Opt-in, not part of the default `sbt test` run: even at a generous
  * bound this is the slowest thing in the suite by roughly an order of
  * magnitude (~15s alone vs. ~7s for everything else combined), which
  * isn't worth paying on every routine test run for a regression this
  * narrow. Run explicitly with:
  *
  *   RUN_SLOW_TESTS=1 sbt "testOnly brasp.PerfRegressionSuite"
  */
class PerfRegressionSuite extends munit.FunSuite:

  private val nimFixture = new File("ltl_examples/Nim/nim_heaps=1tokens=1.brasp")

  // The actual regression took ~18 minutes (~1,080,000 ms); this test does
  // two separate calls that each redundantly re-explore the automaton
  // (a direct `reachable`, then Aiger's own internal quick +
  // full-retry `reachable` calls), measured around 15s total in-suite. 90s
  // still leaves several-times headroom over that measured cost for a
  // loaded/slow machine, while remaining more than 10x too tight for the
  // actual bug (minutes, not seconds) to sneak back in unnoticed.
  private val boundMs = 90000

  private def buildAutomaton(file: File): ReverseBooleanAutomaton =
    val program = BraspText.parse(Files.readString(file.toPath))
    val ltl = BraspToLtl.translateProgram(program)
    BooleanAutomaton.fromForwardPvwaa(Pvwaa.fromFuture2ltl(Translator.mirrorToFuture(ltl)))

  private def timedMs[A](body: => A): (A, Double) =
    val start = System.nanoTime()
    val result = body
    (result, (System.nanoTime() - start) / 1e6)

  test("reachable + generateSafetyAuto stay bounded on a wide-alphabet, oversized-support automaton") {
    assume(sys.env.contains("RUN_SLOW_TESTS"), "slow test — set RUN_SLOW_TESTS=1 to run it")
    assume(nimFixture.isFile, s"fixture not found: $nimFixture")
    val automaton = buildAutomaton(nimFixture)

    // Guard the fixture's own shape, so this test stays meaningful (and
    // fails loudly with a clear message) if `ltl_examples/Nim` ever
    // changes instead of silently testing a no-longer-pathological case.
    assert(automaton.source.alphabet.length >= 100, s"expected a wide alphabet, got ${automaton.source.alphabet.length}")
    intercept[PVWAAError](BooleanAutomaton.checkSupportSize(automaton))

    val (dfa, reachableMs) = timedMs(BooleanAutomaton.reachable(automaton, maxStates = 4096))
    assert(reachableMs < boundMs, f"BooleanAutomaton.reachable took ${reachableMs}%.0f ms, expected < $boundMs ms")

    // The fixture's alphabet size (256) is a power of two, so this doesn't
    // hit Aiger's separate non-power-of-two restriction — it exercises the
    // quick-attempt-then-fallback path.
    val (_, aigerMs) = timedMs(intercept[AigerError](Aiger.generateSafetyAuto(automaton)))
    assert(aigerMs < boundMs, f"Aiger.generateSafetyAuto took ${aigerMs}%.0f ms, expected < $boundMs ms")
  }
