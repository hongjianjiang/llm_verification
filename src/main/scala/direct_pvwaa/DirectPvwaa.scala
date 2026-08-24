package brasp

import scala.collection.mutable

/** A BTOR2 backend that skips `BooleanAutomaton`'s determinization
  * entirely: it encodes the forward PVWAA (the alternating automaton
  * `Pvwaa.fromFuture2ltl` produces) directly, one Boolean state register
  * per PVWAA state, instead of one register per Boolean-summary cell or
  * per minimized-DFA state (the determinized-path encodings elsewhere in
  * this project).
  *
  * The construction follows the string solver Sloth's own AFA-to-AIGER
  * translation (`strsolver.Emptiness.AFA2AAG`, uuverifiers/sloth): each
  * state's own `Carry`/`Leave` references become a *freshly guessed*
  * Boolean input, validated one step later by requiring "if this state was
  * active, its own transition formula (built from that step's guesses)
  * must hold" — exactly a hardware model checker's own job, no explicit
  * subset-construction needed. Sloth's automata have no second ("pebble")
  * position, though, so that part is this project's own addition: a
  * `Goto` reference resolves against the *referencing state's own pebble*
  * (`run(state, pebble, pebble)` in `Pvwaa.satisfy`) rather than becoming
  * another guess. Since `Goto` never moves the pebble, and every state's
  * own pebble is wherever its `active`/`guess` chain last (re)armed it,
  * this needs one small per-state register (`generateSafety`'s own
  * `rootOf`/`activePrevOf`, see the comment above `needsRoot` there for
  * the full reasoning) capturing the symbol seen exactly when that
  * happened — not just the *word's* first symbol, which only coincides
  * with every state's own pebble as long as no `Goto`-containing state is
  * ever itself `Carry`'d into from somewhere else (true for `same_letter_
  * before`/`since_same_letter`, false the moment one `rightmost(...)` is
  * nested inside another's witness operand, e.g. `at_least_two_a`'s
  * `Hist`-based construction, or a Boolean-combinator goto-source like
  * `dot_depth`'s `f_3 = f_1 & f_2` that itself later gets `Carry`'d into).
  *
  * This is validated against `Pvwaa.accepts` on every `two_var` formula
  * this project's own generators produce, including formulas with `Until`
  * nested inside another `Until`'s operand (`at_least_two_a`, the
  * `dot_depth`/`y_depth` families) — the case this backend used to reject
  * outright. Soundness there rests on one empirical fact, checked by
  * `tools.GotoOverlapCheck` rather than proven in general: no state in
  * this project's automata is ever needed relative to two different,
  * simultaneously-live pebbles at once (so one root register per state,
  * not one per still-open "episode", suffices) -- which lines up with
  * `Pvwaa`'s very-weak rank ordering forcing every `Goto`/`Carry`
  * reference to strictly decreasing rank, plus a `rightmost` witness
  * search committing to at most one candidate witness at a time along any
  * real accepting continuation. If some future formula shape violates
  * that, `GotoOverlapCheck` is how to find out before trusting this
  * backend's output on it.
  *
  * Where it applies, this sidesteps two independent blow-ups the other
  * backends hit on the same `two_var` families: the determinized path's
  * `2^|local support|` explicit table (`same_letter_before`/
  * `since_same_letter`, exponential in the alphabet), and
  * `BooleanAutomaton.reachable`'s own joint-state enumeration
  * (exponential for any of these families once summoned explicitly).
  * Measured on `same_letter_before`: sigma=16 already failed every other
  * backend outright; this one solves sigma=256 in a few seconds.
  */
final case class DirectPvwaaError(message: String) extends RuntimeException(message)

object DirectPvwaa:

  private final class Builder:
    val lines = mutable.ArrayBuffer.empty[String]
    private var counter = 0
    private val gateCache = mutable.Map.empty[String, Int]
    def emit(rest: String): Int =
      counter += 1
      lines += s"$counter $rest"
      counter
    def gate(rest: String): Int = gateCache.getOrElseUpdate(rest, emit(rest))

  private def symbolWidth(alphabetSize: Int): Int =
    if alphabetSize <= 1 then 1 else 32 - Integer.numberOfLeadingZeros(alphabetSize - 1)

  /** Whether the empty word is accepted — a compile-time constant (mirrors
    * `Pvwaa.accepts`'s own `head == length` base case at `length == 0`),
    * computed statically rather than by asking the solver.
    */
  def emptyWordAccepted(automaton: ForwardPVWAA): Boolean =
    automaton.finalStates.contains(automaton.initialState)

  /** Emit a BTOR2 model proving the automaton's bad language is
    * unreachable: `bad` is reachable iff some nonempty word is accepted.
    * The `symbol` input is declared first (BTOR2 input index 0), matching
    * rIC3's own counterexample-witness convention (its `@k` frames carry
    * one value per input, in declaration order) — this backend's extra
    * per-state "guess" inputs come after it, so reading a witness back out
    * of rIC3's raw output still means "take the first input's value" with
    * no extra bookkeeping.
    */
  def generateSafety(automaton: ForwardPVWAA, monitorName: String = "brasp_monitor"): String =
    if automaton.alphabet.isEmpty then throw DirectPvwaaError("this backend requires a non-empty alphabet")

    val b = Builder()
    val states = automaton.states
    val alphabet = automaton.alphabet
    val alphabetSize = alphabet.length

    val sortBool = b.emit("sort bitvec 1")
    val zero = b.emit(s"zero $sortBool")
    val one = b.emit(s"one $sortBool")
    def boolConst(value: Boolean): Int = if value then one else zero
    def notGate(a: Int): Int = b.gate(s"not $sortBool $a")
    def andGate(a: Int, c: Int): Int = b.gate(s"and $sortBool $a $c")
    def orGate(a: Int, c: Int): Int = b.gate(s"or $sortBool $a $c")
    def andAll(xs: Iterable[Int]): Int = xs.reduceLeftOption(andGate).getOrElse(one)
    def orAll(xs: Iterable[Int]): Int = xs.reduceLeftOption(orGate).getOrElse(zero)

    val width = symbolWidth(alphabetSize)
    val sortSymbol = b.emit(s"sort bitvec $width")
    val symbolInput = b.emit(s"input $sortSymbol symbol") // must stay BTOR2 input index 0
    val constIndexCache = mutable.Map.empty[Int, Int]
    def constIndex(index: Int): Int = constIndexCache.getOrElseUpdate(index, b.emit(s"constd $sortSymbol $index"))

    // one "active" register per state (Carry/Leave obligation, guessed &
    // validated one step later) plus one guessed input feeding it
    val activeOf = states.map(state => state -> b.emit(s"state $sortBool active_$state")).toMap
    for state <- states do b.emit(s"init $sortBool ${activeOf(state)} ${boolConst(state == automaton.initialState)}")
    val guessOf = states.map(state => state -> b.emit(s"input $sortBool guess_$state")).toMap

    val errReg = b.emit(s"state $sortBool err")
    b.emit(s"init $sortBool $errReg $zero")

    // still needed below purely to gate `reachCheck` against t=0 (the
    // empty-word case, handled separately by `emptyWordAccepted`) --
    // unrelated to Goto resolution now (see `rootOf` below).
    val startedReg = b.emit(s"state $sortBool started")
    b.emit(s"init $sortBool $startedReg $zero")

    // A `SymbolTest` leaf is `Ltl.symbolMatches(kind, sym, concreteSymbol) !=
    // dual` for whichever concrete symbol `wire` currently holds — resolved
    // here as an OR of `eq`s against every alphabet symbol that satisfies
    // it, mirroring what used to be baked into which `(state, symbol)` map
    // entry got selected before `ForwardPVWAA.transitions` moved to one
    // formula per state (see that type's own doc-comment).
    val alphabetIndex = alphabet.zipWithIndex.toMap
    def resolveSymbolTest(wire: Int, kind: AtomKind, sym: Option[String], dual: Boolean): Int =
      val hits = alphabet.filter(candidate => Ltl.symbolMatches(kind, sym, candidate) != dual)
      orAll(hits.map(candidate => b.gate(s"eq $sortBool $wire ${constIndex(alphabetIndex(candidate))}")))

    // `Goto q` means `run(q, pebble, pebble)` (`Pvwaa.satisfy`): re-evaluate
    // q's own transition formula at the *referencing* state's own pebble,
    // not at the live head. That pebble is wherever the referencing
    // state's own active/guess chain last (re)armed it -- which is
    // position 0 only for a state that's never itself `Carry`'d into (the
    // old global "frozen first symbol" this replaces silently assumed that
    // of *every* state with a Goto in its formula, which stops holding the
    // moment one `rightmost(...)` is nested inside another's witness
    // operand, e.g. `at_least_two_a`'s `Hist`-based construction, or a
    // Boolean-combinator goto-source like `dot_depth`'s `f_3 = f_1 & f_2`
    // that itself later gets `Carry`'d into by `f_4`'s witness search).
    //
    // So every state whose own formula contains a `Goto` gets a one-shot
    // "root" register, capturing the symbol seen exactly when *that
    // state's own* `active` register last flipped 0->1, held until it
    // flips again -- the old global `started`/`firstSymbol` pair, just per
    // state instead of once globally. A nested `Goto` chain (a target
    // whose own formula also `Goto`s further) reuses the *same* root the
    // whole way down, since `Goto` never changes the pebble -- only
    // `Carry` does, and that's handled by the target's own root instead.
    //
    // A single root register per state (rather than one per still-open
    // "episode") is only sound if a state's value is never needed
    // relative to two different, simultaneously-live roots at once --
    // checked empirically (`tools.GotoOverlapCheck`) against every
    // `two_var`/`Hist`-based formula in this repo, including the deepest
    // (`dot_depth__k-24`, 12 alternating Goto/Carry levels): it never
    // happens, which lines up with `Pvwaa`'s very-weak rank ordering
    // forcing every `Goto`/`Carry` reference to strictly decreasing rank,
    // plus a `rightmost` witness search committing to at most one
    // candidate witness at a time along any real accepting continuation.
    def containsGoto(f: PositiveFormula): Boolean = f match
      case PositiveFormula.PositiveConstant(_)                            => false
      case _: PositiveFormula.SymbolTest                                  => false
      case PositiveFormula.PositiveAnd(operands)                          => operands.exists(containsGoto)
      case PositiveFormula.PositiveOr(operands)                           => operands.exists(containsGoto)
      case PositiveFormula.TransitionAtom(_, Action.Goto)                 => true
      case PositiveFormula.TransitionAtom(_, Action.Carry | Action.Leave) => false
    val needsRoot = states.filter(state => containsGoto(automaton.transitions(state)))

    val rootOf = needsRoot.map(state => state -> b.emit(s"state $sortSymbol root_$state")).toMap
    for state <- needsRoot do b.emit(s"init $sortSymbol ${rootOf(state)} ${constIndex(0)}")
    val activePrevOf = needsRoot.map(state => state -> b.emit(s"state $sortBool activePrev_$state")).toMap
    for state <- needsRoot do b.emit(s"init $sortBool ${activePrevOf(state)} $zero")

    def effectiveRootOf(state: String): Int =
      val justBecameActive = b.gate(s"and $sortBool ${activeOf(state)} ${notGate(activePrevOf(state))}")
      b.gate(s"ite $sortSymbol $justBecameActive $symbolInput ${rootOf(state)}")
    val rootSymbolOf = needsRoot.map(state => state -> effectiveRootOf(state)).toMap

    // `currentWire` is what a *direct* `SymbolTest` leaf of the formula
    // being walked resolves against; `gotoWire` is what it switches to (and
    // then stays at, through further nested `Goto`s) the moment a `Goto` is
    // crossed -- see `Pvwaa.satisfy`: a state's own formula dispatches on
    // `word(head)`, but `run(target, pebble, pebble)` dispatches on
    // `word(pebble)` from then on.
    def encodeFor(currentWire: Int, gotoWire: Int)(f: PositiveFormula): Int = f match
      case PositiveFormula.PositiveConstant(value) => boolConst(value)
      case PositiveFormula.SymbolTest(kind, sym, dual) => resolveSymbolTest(currentWire, kind, sym, dual)
      case PositiveFormula.PositiveAnd(operands)   => andAll(operands.map(encodeFor(currentWire, gotoWire)))
      case PositiveFormula.PositiveOr(operands)    => orAll(operands.map(encodeFor(currentWire, gotoWire)))
      case PositiveFormula.TransitionAtom(state, Action.Goto) =>
        encodeFor(gotoWire, gotoWire)(automaton.transitions(state))
      case PositiveFormula.TransitionAtom(state, Action.Carry | Action.Leave) =>
        guessOf(state)

    val obligationOf =
      states.map(state => state -> encodeFor(symbolInput, rootSymbolOf.getOrElse(state, symbolInput))(automaton.transitions(state))).toMap

    val allSatisfied = andAll(states.map(q => orGate(notGate(activeOf(q)), obligationOf(q))))
    val noNewErr = andGate(notGate(errReg), allSatisfied)

    // every currently-active state must itself be a final state — mirrors
    // `head == length: finalStates.contains(state)` applied to every
    // pending obligation, checked at every step (the solver picks which
    // step corresponds to "end of word").
    val allActiveAreFinal = andAll(states.map(q => orGate(notGate(activeOf(q)), boolConst(automaton.finalStates.contains(q)))))
    // gated on `started`: at t=0 (before any symbol is consumed) `active`
    // already holds the *initial* configuration, so this would otherwise
    // also fire for the empty word — which is already reported separately
    // (`emptyWordAccepted`): `bad` here is inherently post-symbol.
    val reachCheck = andGate(startedReg, andGate(notGate(errReg), allActiveAreFinal))

    for state <- states do b.emit(s"next $sortBool ${activeOf(state)} ${guessOf(state)}")
    b.emit(s"next $sortBool $errReg ${notGate(noNewErr)}")
    b.emit(s"next $sortBool $startedReg $one")
    for state <- needsRoot do
      b.emit(s"next $sortSymbol ${rootOf(state)} ${rootSymbolOf(state)}")
      b.emit(s"next $sortBool ${activePrevOf(state)} ${activeOf(state)}")

    if alphabetSize < (1 << width) then
      val inRange = b.emit(s"ult $sortBool $symbolInput ${constIndex(alphabetSize)}")
      b.emit(s"constraint $inRange")

    b.emit(s"bad $reachCheck")

    (List(s"; $monitorName: symbol is an index into the alphabet below") ++
      alphabet.zipWithIndex.map { case (symbol, index) => s"; symbol = $index represents input symbol '$symbol'." } ++
      b.lines.toList :+ "").mkString("\n")

