package brasp

import scala.collection.mutable

/** BTOR2 backend for reverse Boolean-summary automata: an alternative to
  * `Kind2`/`Lustre` for backends that check word-level circuits (BTOR2)
  * rather than Lustre, e.g. rIC3.
  *
  * The encoding mirrors `Lustre.generate` node-for-node: one Boolean state
  * register per `(pvwaa state, local abstraction)` summary cell — `state`'s
  * abstractions range only over `automaton.support(state)`, the (usually
  * small) subset of the goto-support it actually depends on, not the
  * automaton-wide goto-support — initialized to `automaton.initial`; a
  * `symbol` input consumed on every step (there is no
  * `valid`/`start`/`last` here, since BTOR2's `init`/`next` already model
  * "reset once at step 0, consume one symbol every step" directly); and a
  * single `bad` line for "the prefix consumed so far is accepted", i.e. a
  * reachable bad state is exactly a nonempty counterexample word. The
  * empty-word case doesn't need a second `bad` line: it's already a
  * compile-time constant (`automaton.initial`'s own diagonal), the same fact
  * `Kind2.generateSafety` computes for `empty_bad` before ever calling the
  * solver.
  */

final case class Btor2Error(message: String) extends RuntimeException(message)

object Btor2:

  private final class Builder:
    val lines = mutable.ArrayBuffer.empty[String]
    private var counter = 0
    private val gateCache = mutable.Map.empty[String, Int]
    def emit(rest: String): Int =
      counter += 1
      lines += s"$counter $rest"
      counter

    /** Hash-consed variant of `emit` for pure combinational gates (`and`,
      * `or`, `ite`, `eq`): the output is a pure function of the operator and
      * operand ids, so an identical `rest` string always denotes the same
      * value and can reuse a prior line instead of re-emitting it. This
      * matters a lot in practice: e.g. `symbolCase`'s `eq $sortSymbol
      * $symbolInput <k>` line — "does the shared `symbol` input equal
      * alphabet index k" — is the exact same gate no matter which `(state,
      * abstraction)` summary cell is being built, but without this cache it
      * was being re-emitted once per cell, multiplying the model by
      * `states * abstractions-per-state`.
      */
    def gate(rest: String): Int = gateCache.getOrElseUpdate(rest, emit(rest))

  private def symbolWidth(alphabetSize: Int): Int =
    if alphabetSize <= 1 then 1 else 32 - Integer.numberOfLeadingZeros(alphabetSize - 1)

  /** Emit a BTOR2 model proving the monitor's bad language is unreachable:
    * `bad` is reachable iff some nonempty word is accepted.
    */
  def generateSafety(automaton: ReverseBooleanAutomaton, monitorName: String = "brasp_monitor"): String =
    if automaton.source.alphabet.isEmpty then throw Btor2Error("the BTOR2 backend requires a non-empty alphabet")
    // This backend still builds one explicit register per (state, local
    // abstraction) summary cell below — unlike `BooleanAutomaton`'s own
    // BDD-based `transition`/`diagonal`, which no longer need this check.
    try BooleanAutomaton.checkSupportSize(automaton)
    catch case PVWAAError(message) => throw Btor2Error(message)
    val b = Builder()
    val states = automaton.source.states
    val stateIndex = states.zipWithIndex.toMap
    // `Vector` at both levels, not `List`: `transitionFormula` indexes into
    // an abstraction once per `goto`/`leave` atom, and `mux`'s `select`
    // indexes into the abstraction *list itself* by position (up to
    // `2^n - 1`) — either as a `List`, that's an O(i) scan per lookup, the
    // same trap `supportIndex` (see `BooleanAutomaton.scala`) exists to
    // avoid one level up. Cached per state: `mux` (called twice, via the
    // `oldDiagonal`/`curDiagonal` `diagonalOf` passes) and the `oldSummary`/
    // `curSummary` loops below each ask for the same state's abstractions
    // again, and rebuilding a `2^n`-entry vector from scratch every time is
    // wasted work for states with a sizeable local support.
    val abstractionsCache = mutable.Map.empty[String, Vector[Vector[Boolean]]]
    def abstractionsOf(state: String): Vector[Vector[Boolean]] =
      abstractionsCache.getOrElseUpdate(
        state, {
          val n = automaton.support(state).length
          if n == 0 then Vector(Vector.empty) else (0 until (1 << n)).map(i => (n - 1 to 0 by -1).map(bit => ((i >> bit) & 1) == 1).toVector).toVector
        },
      )
    def abstractionIndexOf(state: String, abstraction: Vector[Boolean]): Int =
      abstraction.foldLeft(0)((acc, bit) => (acc << 1) | (if bit then 1 else 0))
    val alphabet = automaton.source.alphabet
    val alphabetSize = alphabet.length
    val ordered = states.sortBy(state => (automaton.source.rank(state), state))

    val sortBool = b.emit("sort bitvec 1")
    val zero = b.emit(s"zero $sortBool")
    val one = b.emit(s"one $sortBool")
    def boolConst(value: Boolean): Int = if value then one else zero

    val width = symbolWidth(alphabetSize)
    val sortSymbol = b.emit(s"sort bitvec $width")
    val symbolInput = b.emit(s"input $sortSymbol symbol")
    val constIndexCache = mutable.Map.empty[Int, Int]
    def constIndex(index: Int): Int = constIndexCache.getOrElseUpdate(index, b.emit(s"constd $sortSymbol $index"))

    // One Boolean state register per (pvwaa state, local abstraction)
    // summary cell — `state`'s local abstractions range only over
    // `automaton.support(state)`, not the automaton-wide goto-support.
    val oldSummary = mutable.Map.empty[(String, Vector[Boolean]), Int]
    for
      state <- states
      abstraction <- abstractionsOf(state)
    do
      val name = s"old_s_${stateIndex(state)}_${abstractionIndexOf(state, abstraction)}"
      val reg = b.emit(s"state $sortBool $name")
      // The initial summary doesn't depend on any goto-support bit yet
      // (nothing has been consumed), so every abstraction of a state
      // starts at the same constant: whether it's a final PVWAA state.
      val init = boolConst(automaton.source.finalStates.contains(state))
      b.emit(s"init $sortBool $reg $init")
      oldSummary((state, abstraction)) = reg

    /** Select the summary/diagonal cell for `state` indexed by `bits`
      * (`state`'s own local support, in order) — the BTOR2 mirror of
      * `Lustre.generate`'s `mux`. `bits` is a `Vector`: `select` indexes it
      * once per recursion level across up to `2^bits.length` calls, and
      * `List(i)` would turn that into an O(i) scan each time.
      */
    def mux(state: String, bits: Vector[Int], summaryOf: (String, Vector[Boolean]) => Int): Int =
      val abstractions = abstractionsOf(state)
      def select(index: Int, depth: Int): Int =
        if depth == bits.length then summaryOf(state, abstractions(index))
        else
          val trueBranch = select(index + (1 << (bits.length - depth - 1)), depth + 1)
          val falseBranch = select(index, depth + 1)
          b.gate(s"ite $sortBool ${bits(depth)} $trueBranch $falseBranch")
      select(0, 0)

    /** `Lustre.generate`'s `diagonalEquations`: solve the summary recurrence
      * bottom-up along the very-weak order, for either the register values
      * (`oldSummary`) or this step's freshly computed values (`curSummary`).
      */
    def diagonalOf(summaryOf: (String, Vector[Boolean]) => Int): Map[String, Int] =
      var known = Map.empty[String, Int]
      for state <- ordered do
        val bits = automaton.support(state).map(goto => known.getOrElse(goto, zero)).toVector
        known = known.updated(state, mux(state, bits, summaryOf))
      known

    val oldDiagonal = diagonalOf((state, abstraction) => oldSummary((state, abstraction)))

    // `ownerAbstraction` is indexed by `automaton.support(owner)`; a
    // `leave`/`goto` atom's target is always in `owner`'s support by
    // construction (see `BooleanAutomaton.supportOf`), so the projections
    // below always find a valid index.
    def transitionFormula(owner: String, ownerAbstraction: Vector[Boolean], formula: PositiveFormula): Int = formula match
      case PositiveFormula.PositiveConstant(value) => boolConst(value)
      case PositiveFormula.PositiveAnd(operands) =>
        operands.map(transitionFormula(owner, ownerAbstraction, _)).reduceLeftOption((a, c) => b.gate(s"and $sortBool $a $c")).getOrElse(one)
      case PositiveFormula.PositiveOr(operands) =>
        operands.map(transitionFormula(owner, ownerAbstraction, _)).reduceLeftOption((a, c) => b.gate(s"or $sortBool $a $c")).getOrElse(zero)
      case PositiveFormula.TransitionAtom(state, action) =>
        val ownerIndex = automaton.supportIndex(owner)
        action match
          case Action.Carry => oldDiagonal(state)
          case Action.Leave =>
            val bits = automaton.support(state).map(g => ownerAbstraction(ownerIndex(g))).toVector
            oldSummary((state, bits))
          case Action.Goto => boolConst(ownerAbstraction(ownerIndex(state)))

    def symbolCase(state: String, abstraction: Vector[Boolean]): Int =
      val base = oldSummary((state, abstraction))
      alphabet.zipWithIndex.foldRight(base) { case ((symbol, index), acc) =>
        val eq = b.gate(s"eq $sortBool $symbolInput ${constIndex(index)}")
        val transition = transitionFormula(state, abstraction, automaton.source.transitions((state, symbol)))
        b.gate(s"ite $sortBool $eq $transition $acc")
      }

    val curSummary = mutable.Map.empty[(String, Vector[Boolean]), Int]
    for
      state <- states
      abstraction <- abstractionsOf(state)
    do curSummary((state, abstraction)) = symbolCase(state, abstraction)

    val curDiagonal = diagonalOf((state, abstraction) => curSummary((state, abstraction)))

    for
      state <- states
      abstraction <- abstractionsOf(state)
    do b.emit(s"next $sortBool ${oldSummary((state, abstraction))} ${curSummary((state, abstraction))}")

    if alphabetSize < (1 << width) then
      val inRange = b.emit(s"ult $sortBool $symbolInput ${constIndex(alphabetSize)}")
      b.emit(s"constraint $inRange")

    b.emit(s"bad ${curDiagonal(automaton.source.initialState)}")

    (List(s"; $monitorName: symbol is an index into the alphabet below") ++
      alphabet.zipWithIndex.map { case (symbol, index) => s"; symbol = $index represents input symbol '$symbol'." } ++
      b.lines.toList :+ "").mkString("\n")

  /** Emit a BTOR2 model for `dfa` (`BooleanAutomaton.reachable`'s explicit
    * exploration of the automaton's *actually* reachable states) directly:
    * one `ceil(log2(stateCount))`-bit state register plus a next-state
    * lookup over `dfa.transitions`, instead of `generateSafety`'s one
    * register per `(pvwaa state, local abstraction)` summary cell.
    *
    * This is a completely standard "explicit FSM to hardware" encoding —
    * correct for any *complete* automaton — and its size tracks the
    * automaton's real reachable-state count, not the syntactic
    * `2^|local support|` bound `generateSafety` is stuck with. `dfa` must
    * not be `truncated`: an incomplete transition table would silently
    * produce an unsound circuit (a missing transition could hide a real
    * bad state as unreachable). Callers needing a truncated exploration to
    * still produce *something* should fall back to `generateSafety`
    * instead — see `generateSafetyAuto`.
    *
    * `dfa` is minimized (`BooleanAutomaton.minimize`, exact Moore-style
    * bisimulation merging — see its own doc-comment) before encoding: on
    * real specifications this routinely halves or better the reachable
    * state count `reachable` produces (its dedup is exact structural
    * equality only), which shrinks this function's output for free, with
    * no change to the language encoded.
    */
  def generateSafetyFromDfa(rawDfa: ReachableDfa, alphabet: List[String], monitorName: String = "brasp_monitor"): String =
    if alphabet.isEmpty then throw Btor2Error("the BTOR2 backend requires a non-empty alphabet")
    if rawDfa.truncated then
      throw Btor2Error(
        "cannot encode a truncated reachable-state exploration as hardware (it would be unsound) " +
          "— raise --btor2-max-states, or this falls back to the explicit-table encoding automatically via generateSafetyAuto"
      )
    val dfa = BooleanAutomaton.minimize(rawDfa, alphabet)
    val b = Builder()
    val alphabetSize = alphabet.length
    val stateCount = math.max(dfa.stateCount, 1)

    val sortBool = b.emit("sort bitvec 1")
    val zero = b.emit(s"zero $sortBool")
    val one = b.emit(s"one $sortBool")

    val symbolBits = symbolWidth(alphabetSize)
    val sortSymbol = b.emit(s"sort bitvec $symbolBits")
    val symbolInput = b.emit(s"input $sortSymbol symbol")
    val symbolConstCache = mutable.Map.empty[Int, Int]
    def symbolConst(index: Int): Int = symbolConstCache.getOrElseUpdate(index, b.emit(s"constd $sortSymbol $index"))

    val stateBits = symbolWidth(stateCount)
    val sortState = b.emit(s"sort bitvec $stateBits")
    val stateConstCache = mutable.Map.empty[Int, Int]
    def stateConst(value: Int): Int = stateConstCache.getOrElseUpdate(value, b.emit(s"constd $sortState $value"))

    val stateReg = b.emit(s"state $sortState state_reg")
    b.emit(s"init $sortState $stateReg ${stateConst(dfa.initial)}")

    // next_state = dfa.transitions(state_reg, symbol), as two nested
    // cascading equality chains (state, then symbol) — `Builder.gate`
    // collapses any repeated sub-chains, which is common for automata with
    // regular structure (e.g. many states agreeing on where a given
    // symbol leads).
    def nextStateFor(state: Int): Int =
      alphabet.zipWithIndex.foldRight(stateConst(state)) { case ((symbol, index), acc) =>
        val eq = b.gate(s"eq $sortBool $symbolInput ${symbolConst(index)}")
        val target = stateConst(dfa.transitions((state, symbol)))
        b.gate(s"ite $sortState $eq $target $acc")
      }

    val nextState = (0 until stateCount).foldRight(stateConst(dfa.initial)) { case (state, acc) =>
      val eq = b.gate(s"eq $sortBool $stateReg ${stateConst(state)}")
      b.gate(s"ite $sortState $eq ${nextStateFor(state)} $acc")
    }
    b.emit(s"next $sortState $stateReg $nextState")

    if alphabetSize < (1 << symbolBits) then
      val inRange = b.emit(s"ult $sortBool $symbolInput ${symbolConst(alphabetSize)}")
      b.emit(s"constraint $inRange")

    // bad = "the state reached by consuming this step's symbol accepts" —
    // matches `generateSafety`'s `curDiagonal`, the *post*-transition
    // diagonal, not the pre-transition register.
    val acceptingCheck = dfa.accepting.toList.sorted.foldLeft(zero) { case (acc, state) =>
      val eq = b.gate(s"eq $sortBool $nextState ${stateConst(state)}")
      b.gate(s"or $sortBool $eq $acc")
    }
    b.emit(s"bad $acceptingCheck")

    (List(s"; $monitorName: symbol is an index into the alphabet below") ++
      alphabet.zipWithIndex.map { case (symbol, index) => s"; symbol = $index represents input symbol '$symbol'." } ++
      b.lines.toList :+ "").mkString("\n")

  /** Total `transition` calls (not states) `generateSafetyAuto`'s quick DFA
    * attempt is willing to spend, regardless of whether `generateSafety`'s
    * explicit table would also fit — most real specifications (measured:
    * the example B-RASP programs in this repo, 30-95% smaller after
    * `BooleanAutomaton.minimize`) have a reachable state count well under
    * a few dozen, so the resulting per-state budget catches the common
    * case and gets the smaller, minimized encoding "for free."
    *
    * Budgeted by `transition` calls rather than a flat state count because
    * `reachable`'s cost is `states explored x alphabet size`: an
    * alphabet-size-256 automaton (e.g. `ltl_examples/Nim`'s bit-blasted
    * heap encoding, measured: 3.1s to explore just 8 states, 17.7s for 64
    * and still not converged) makes even a "small" fixed state count
    * expensive, which is exactly the large-alphabet pathology
    * `generateSafetyAuto`'s ordering exists to avoid paying for twice (see
    * its own doc-comment). Dividing by alphabet size keeps this quick
    * attempt's *wall-clock* cost roughly bounded regardless of alphabet
    * size, shrinking to a token 1-state look before giving up on
    * enormous-alphabet automata instead of silently reintroducing that
    * pathology as this function's own new fixed overhead.
    */
  private val quickDfaWork = 256

  /** Prefer a *small*, bounded `BooleanAutomaton.reachable` + `minimize`
    * attempt first (see `quickDfaWork`); if that doesn't finish, fall
    * back to the per-state Boolean-summary table encoding (`generateSafety`)
    * whenever `checkSupportSize` passes, and only then retry the
    * reachable-DFA encoding (`generateSafetyFromDfa`) at the full
    * `maxStates` budget as a last resort for automata too tangled for
    * `generateSafety` either.
    *
    * The explicit-table-first fallback ordering (rather than always
    * retrying the DFA search at the full budget) is deliberate:
    * `checkSupportSize` bounds each state's *local* dependency count,
    * which is unrelated to the automaton's *global* reachable-state count
    * — a formula can easily have small per-state support (cheap for
    * `generateSafety`, and instant to check — no search required) while
    * still having a huge, slow-to-explore reachable summary space
    * (expensive for `BooleanAutomaton.reachable`'s BFS, which pays
    * `O(reachable states x alphabet size)` `transition` calls just to find
    * out). A dense counter-style formula is exactly this shape: its
    * alphabet is large because every one of its bits is actually load-
    * bearing somewhere (so the alphabet itself can't be shrunk), and its
    * reachable-state count grows with the number of live sub-formulas, not
    * with any single state's support. Retrying the full-budget DFA search
    * in that case burns the time this function exists to avoid, only to
    * fall back to the encoding that was cheap to detect as viable all
    * along — the quick bounded attempt above already gave the DFA
    * encoding its cheap shot; past that, `generateSafety` gets priority
    * again for the same reason it always has.
    *
    * The final fallback still requires `checkSupportSize` to pass on its
    * own eventual retry inside `generateSafety`, so this can still fail if
    * *every* encoding is impractical for a given automaton.
    */
  def generateSafetyAuto(automaton: ReverseBooleanAutomaton, monitorName: String = "brasp_monitor", maxStates: Int = 4096): String =
    val quickBudget = math.min(maxStates, quickDfaWork / math.max(1, automaton.source.alphabet.length))
    val quickDfa = if quickBudget > 0 then Some(BooleanAutomaton.reachable(automaton, quickBudget)).filterNot(_.truncated) else None
    quickDfa match
      case Some(dfa) => generateSafetyFromDfa(dfa, automaton.source.alphabet, monitorName)
      case None =>
        val supportFits =
          try
            BooleanAutomaton.checkSupportSize(automaton)
            true
          catch case _: PVWAAError => false
        if supportFits then generateSafety(automaton, monitorName)
        else if maxStates > quickBudget then
          val dfa = BooleanAutomaton.reachable(automaton, maxStates)
          if !dfa.truncated then generateSafetyFromDfa(dfa, automaton.source.alphabet, monitorName)
          else generateSafety(automaton, monitorName)
        else generateSafety(automaton, monitorName)
