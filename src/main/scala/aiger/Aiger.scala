package brasp

import java.io.ByteArrayOutputStream
import scala.collection.mutable

/** Binary AIGER (.aig) backend for reverse Boolean-summary automata: bit-level
  * circuits (AIGER) checked by ABC. The non-determinized `--direct` backend
  * (`DirectPvwaa.scala`) emits a separate BTOR2 encoding for word-level
  * checkers such as rIC3, but this project has no BTOR2 encoder of its own
  * for the determinized automaton.
  *
  * The encoding: one Boolean state register (here, an AIGER latch) per
  * `(pvwaa state, abstraction)` summary cell, a `symbol` input bit-blasted
  * into individual AIGER inputs, and a single output literal for "the
  * prefix consumed so far is accepted" — built from 2-input AND gates (with
  * literal negation for free, per the AIGER convention) instead of BTOR2's
  * typed op lines.
  *
  * Two format quirks this needs to work around, found by hex-diffing
  * ABC's own `write_aiger` output against a hand-derivation from its
  * reader source (`ioReadAiger.c`):
  *   - AIGER numbers variables *positionally*: `[1, I]` are inputs, `[I+1,
  *     I+L]` latches, and everything after that an AND gate — there's no
  *     per-line keyword tagging a variable's role, so every input and
  *     latch variable must be allocated before any AND gate is created.
  *   - This build's binary reader only supports the plain (pre-1.9)
  *     latch format: one line per latch, just its `next` literal — no
  *     explicit reset field, always reset-to-0. So every latch here is
  *     canonicalized to physically reset to 0 via the standard
  *     XOR-with-init trick: a latch that should start at `r` stores
  *     `state XOR r` instead of `state` directly, unflipped (same XOR,
  *     since it's its own inverse) everywhere `state` is read or written.
  */

final case class AigerError(message: String) extends RuntimeException(message)

object Aiger:

  private final class Builder:
    private var nextVar = 1
    val andGates = mutable.ArrayBuffer.empty[(Int, Int, Int)] // (outputLit, aLit, bLit)
    def freshVar(): Int =
      val v = nextVar
      nextVar += 1
      v
    def maxVar: Int = nextVar - 1

    def lit(v: Int, negated: Boolean = false): Int = 2 * v + (if negated then 1 else 0)
    val True = 1
    val False = 0
    def not(a: Int): Int = a ^ 1
    def and(a: Int, b: Int): Int =
      if a == False || b == False then False
      else if a == True then b
      else if b == True then a
      else if a == b then a
      else
        val v = freshVar()
        val out = lit(v)
        andGates += ((out, a, b))
        out
    def or(a: Int, b: Int): Int = not(and(not(a), not(b)))
    def ite(c: Int, t: Int, f: Int): Int = or(and(c, t), and(not(c), f))
    def xnor(a: Int, b: Int): Int = or(and(a, b), and(not(a), not(b)))
    def flip(literal: Int, negate: Boolean): Int = if negate then not(literal) else literal

  private def symbolWidth(alphabetSize: Int): Int =
    if alphabetSize <= 1 then 1 else 32 - Integer.numberOfLeadingZeros(alphabetSize - 1)

  /** AIGER's variable-length delta encoding for AND-gate literals (see
    * `Io_ReadAigerDecode` in ABC's `ioReadAiger.c`): little-endian, 7 bits
    * per byte, high bit set while more bytes follow.
    */
  private def encodeDelta(out: ByteArrayOutputStream, valueIn: Int): Unit =
    var value = valueIn
    while (value & ~0x7f) != 0 do
      out.write((value & 0x7f) | 0x80)
      value = value >>> 7
    out.write(value & 0x7f)

  /** Emit an AIGER model proving the monitor's bad language is unreachable:
    * the sole output is reachable iff some nonempty word is accepted. The
    * empty-word case doesn't need a second output here either: it's
    * already a compile-time constant (`automaton.initial`'s own diagonal),
    * checked before ever calling the solver.
    */
  def generateSafety(automaton: ReverseBooleanAutomaton): Array[Byte] =
    if automaton.source.alphabet.isEmpty then throw AigerError("the AIGER backend requires a non-empty alphabet")
    // This backend still builds one explicit register per (state, local
    // abstraction) summary cell below — unlike `BooleanAutomaton`'s own
    // BDD-based `transition`/`diagonal`, which no longer need this check.
    try BooleanAutomaton.checkSupportSize(automaton)
    catch case PVWAAError(message) => throw AigerError(message)
    val b = Builder()
    val states = automaton.source.states
    val stateIndex = states.zipWithIndex.toMap
    // `Vector` at both levels, not `List`: both `mux`'s `select` and
    // `transitionFormula` index into an abstraction/`bits`, and `mux` also
    // indexes into the abstraction *list itself* by position (up to
    // `2^n - 1`) — either as a `List`, that's an O(i) scan per lookup, the
    // same trap `supportIndex` (see `BooleanAutomaton.scala`) exists to
    // avoid one level up. Cached per state: `mux` (called twice, via the
    // `oldDiagonal`/`curDiagonal` `diagonalOf` passes) and the latch/
    // `trueNext` loops below each ask for the same state's abstractions
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

    val width = symbolWidth(alphabetSize)
    if alphabetSize < (1 << width) then
      throw AigerError(
        s"the AIGER backend doesn't support non-power-of-two alphabets yet (alphabet size $alphabetSize needs a $width-bit symbol range constraint, which AIGER has no native way to express the way BTOR2's `constraint` line does)"
      )
    val inputVars = Vector.fill(width)(b.freshVar())
    val inputLits = inputVars.map(v => b.lit(v))

    // Every latch must exist before any AND gate does (AIGER numbers
    // variables positionally by role), so allocate all of them up front —
    // and canonicalize every one to physically reset to 0 (see class doc).
    val physicalLatch = mutable.Map.empty[(String, Vector[Boolean]), Int] // -> latch var
    val resetsHigh = mutable.Map.empty[(String, Vector[Boolean]), Boolean]
    for
      state <- states
      abstraction <- abstractionsOf(state)
    do
      physicalLatch((state, abstraction)) = b.freshVar()
      // The initial summary doesn't depend on any goto-support bit yet
      // (nothing has been consumed), so every abstraction of a state
      // starts at the same constant: whether it's a final PVWAA state.
      resetsHigh((state, abstraction)) = automaton.source.finalStates.contains(state)

    def trueOldLit(state: String, abstraction: Vector[Boolean]): Int =
      val key = (state, abstraction)
      b.flip(b.lit(physicalLatch(key)), resetsHigh(key))

    def mux(state: String, bits: Vector[Int], summaryOf: (String, Vector[Boolean]) => Int): Int =
      val abstractions = abstractionsOf(state)
      def select(index: Int, depth: Int): Int =
        if depth == bits.length then summaryOf(state, abstractions(index))
        else
          val trueBranch = select(index + (1 << (bits.length - depth - 1)), depth + 1)
          val falseBranch = select(index, depth + 1)
          b.ite(bits(depth), trueBranch, falseBranch)
      select(0, 0)

    def diagonalOf(summaryOf: (String, Vector[Boolean]) => Int): Map[String, Int] =
      var known = Map.empty[String, Int]
      for state <- ordered do
        val bits = automaton.support(state).map(goto => known.getOrElse(goto, b.False)).toVector
        known = known.updated(state, mux(state, bits, summaryOf))
      known

    val oldDiagonal = diagonalOf((state, abstraction) => trueOldLit(state, abstraction))

    // `ownerAbstraction` is indexed by `automaton.support(owner)`; a
    // `leave`/`goto` atom's target is always in `owner`'s support by
    // construction (see `BooleanAutomaton.supportOf`), so the projections
    // below always find a valid index.
    def transitionFormula(owner: String, ownerAbstraction: Vector[Boolean], symbol: String, formula: PositiveFormula): Int = formula match
      case PositiveFormula.PositiveConstant(value) => if value then b.True else b.False
      case PositiveFormula.SymbolTest(kind, sym, dual) =>
        if Ltl.symbolMatches(kind, sym, symbol) != dual then b.True else b.False
      case PositiveFormula.PositiveAnd(operands) =>
        operands.map(transitionFormula(owner, ownerAbstraction, symbol, _)).reduceLeftOption(b.and).getOrElse(b.True)
      case PositiveFormula.PositiveOr(operands) =>
        operands.map(transitionFormula(owner, ownerAbstraction, symbol, _)).reduceLeftOption(b.or).getOrElse(b.False)
      case PositiveFormula.TransitionAtom(state, action) =>
        val ownerIndex = automaton.supportIndex(owner)
        action match
          case Action.Carry => oldDiagonal(state)
          case Action.Leave =>
            val bits = automaton.support(state).map(g => ownerAbstraction(ownerIndex(g))).toVector
            trueOldLit(state, bits)
          case Action.Goto => if ownerAbstraction(ownerIndex(state)) then b.True else b.False

    /** Bitwise equality of the `symbol` input against the constant `index`,
      * i.e. the AIGER bit-blast of a BTOR2-style `eq $symbolInput ${constIndex(index)}`.
      */
    def symbolEquals(index: Int): Int =
      (0 until width).foldLeft(b.True) { (acc, bitPos) =>
        val bit = if ((index >> bitPos) & 1) == 1 then b.True else b.False
        b.and(acc, b.xnor(inputLits(bitPos), bit))
      }

    def symbolCase(state: String, abstraction: Vector[Boolean]): Int =
      val base = trueOldLit(state, abstraction)
      alphabet.zipWithIndex.foldRight(base) { case ((symbol, index), acc) =>
        val transition = transitionFormula(state, abstraction, symbol, automaton.source.transitions(state))
        b.ite(symbolEquals(index), transition, acc)
      }

    val trueNext = mutable.Map.empty[(String, Vector[Boolean]), Int]
    for
      state <- states
      abstraction <- abstractionsOf(state)
    do trueNext((state, abstraction)) = symbolCase(state, abstraction)

    val curDiagonal = diagonalOf((state, abstraction) => trueNext((state, abstraction)))

    val badLit = curDiagonal(automaton.source.initialState)

    // Flip the computed *true* next-value back into what the physically
    // reset-to-0 latch actually stores.
    val latches = for
      state <- states
      abstraction <- abstractionsOf(state)
    yield
      val key = (state, abstraction)
      (physicalLatch(key), b.flip(trueNext(key), resetsHigh(key)))

    val out = new ByteArrayOutputStream()
    def writeLine(text: String): Unit = out.write((text + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII))

    writeLine(s"aig ${b.maxVar} $width ${latches.length} 1 ${b.andGates.length}")
    for (_, nextLit) <- latches do writeLine(nextLit.toString)
    writeLine(badLit.toString)
    for (outLit, aLit, cLit) <- b.andGates do
      val rhsHigh = math.max(aLit, cLit)
      val rhsLow = math.min(aLit, cLit)
      encodeDelta(out, outLit - rhsHigh)
      encodeDelta(out, rhsHigh - rhsLow)
    writeLine("c")
    for (symbol, index) <- alphabet.zipWithIndex do
      writeLine(s"symbol = $index represents input symbol '$symbol' (bit-blasted, $width-bit).")

    out.toByteArray

  /** Emit an AIGER model for `dfa` (`BooleanAutomaton.reachable`'s explicit
    * exploration of the automaton's *actually* reachable states) directly:
    * `ceil(log2(stateCount))` latches encoding the current state in binary,
    * plus a next-state lookup over `dfa.transitions`. `dfa` must not be
    * `truncated` (an incomplete transition table would silently misencode
    * an unexplored, possibly-bad state as unreachable) — this encoding's
    * size tracks the automaton's real reachable-state count rather than
    * the syntactic `2^|local support|` bound `generateSafety` is stuck
    * with.
    *
    * `dfa.initial` is always `0` (see `ReachableDfa`'s doc-comment), so
    * unlike `generateSafety`'s summary latches, no reset-polarity XOR trick
    * is needed here: every state latch already means what it says, and
    * physically resets to the all-zero (= initial) state for free.
    *
    * `dfa` is minimized (`BooleanAutomaton.minimize`) before encoding: on
    * real specs, `reachable`'s exact-equality dedup routinely leaves states
    * behind that `minimize` merges for free. `minimize` guarantees its
    * output's initial state is still block `0`, so the no-XOR-trick
    * property above continues to hold after minimizing too.
    */
  def generateSafetyFromDfa(rawDfa: ReachableDfa, alphabet: List[String]): Array[Byte] =
    if alphabet.isEmpty then throw AigerError("the AIGER backend requires a non-empty alphabet")
    if rawDfa.truncated then
      throw AigerError(
        "cannot encode a truncated reachable-state exploration as hardware (it would be unsound) " +
          "— raise the state cap, or this falls back to the explicit-table encoding automatically via generateSafetyAuto"
      )
    val dfa = BooleanAutomaton.minimize(rawDfa, alphabet)
    val b = Builder()
    val alphabetSize = alphabet.length
    val stateCount = math.max(dfa.stateCount, 1)

    val width = symbolWidth(alphabetSize)
    if alphabetSize < (1 << width) then
      throw AigerError(
        s"the AIGER backend doesn't support non-power-of-two alphabets yet (alphabet size $alphabetSize needs a $width-bit symbol range constraint, which AIGER has no native way to express the way BTOR2's `constraint` line does)"
      )
    val inputVars = Vector.fill(width)(b.freshVar())
    val inputLits = inputVars.map(v => b.lit(v))

    // Every latch must exist before any AND gate does (AIGER numbers
    // variables positionally by role), so allocate all `stateBits` of them
    // up front, same as `generateSafety`'s summary latches.
    val stateBits = symbolWidth(stateCount)
    val stateLatchVars = Vector.fill(stateBits)(b.freshVar())
    val curStateLits = stateLatchVars.map(v => b.lit(v))

    def symbolEquals(index: Int): Int =
      (0 until width).foldLeft(b.True) { (acc, bitPos) =>
        val bit = if ((index >> bitPos) & 1) == 1 then b.True else b.False
        b.and(acc, b.xnor(inputLits(bitPos), bit))
      }
    val symbolEqLits = alphabet.indices.map(symbolEquals).toVector

    def stateEquals(value: Int): Int =
      (0 until stateBits).foldLeft(b.True) { (acc, bitPos) =>
        val bit = if ((value >> bitPos) & 1) == 1 then b.True else b.False
        b.and(acc, b.xnor(curStateLits(bitPos), bit))
      }
    val stateEqLits = (0 until stateCount).map(stateEquals).toVector

    def targetBit(target: Int, bitPos: Int): Int = if ((target >> bitPos) & 1) == 1 then b.True else b.False

    // next-state bit `bitPos`, as a function of the current-state latches
    // and the symbol input: select `state`'s row via `stateEqLits`, then
    // within that row select the symbol's target bit via `symbolEqLits` —
    // two nested cascading equality chains, bit-blasted.
    def nextBit(bitPos: Int): Int =
      (0 until stateCount).foldRight(b.False) { case (state, outer) =>
        val perSymbol = alphabet.indices.foldRight(b.False) { case (index, inner) =>
          val target = targetBit(dfa.transitions((state, alphabet(index))), bitPos)
          b.ite(symbolEqLits(index), target, inner)
        }
        b.ite(stateEqLits(state), perSymbol, outer)
      }
    val nextBits = (0 until stateBits).map(nextBit).toVector

    // bad = "the state reached by consuming this step's symbol accepts" —
    // matches `generateSafety`'s `curDiagonal`, the *post*-transition
    // diagonal, not the pre-transition latches.
    def nextStateEquals(state: Int): Int =
      (0 until stateBits).foldLeft(b.True) { (acc, bitPos) => b.and(acc, b.xnor(nextBits(bitPos), targetBit(state, bitPos))) }
    val badLit = dfa.accepting.toList.sorted.foldLeft(b.False) { (acc, state) => b.or(acc, nextStateEquals(state)) }

    val out = new ByteArrayOutputStream()
    def writeLine(text: String): Unit = out.write((text + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII))

    writeLine(s"aig ${b.maxVar} $width $stateBits 1 ${b.andGates.length}")
    for nextLit <- nextBits do writeLine(nextLit.toString)
    writeLine(badLit.toString)
    for (outLit, aLit, cLit) <- b.andGates do
      val rhsHigh = math.max(aLit, cLit)
      val rhsLow = math.min(aLit, cLit)
      encodeDelta(out, outLit - rhsHigh)
      encodeDelta(out, rhsHigh - rhsLow)
    writeLine("c")
    for (symbol, index) <- alphabet.zipWithIndex do
      writeLine(s"symbol = $index represents input symbol '$symbol' (bit-blasted, $width-bit).")

    out.toByteArray

  /** Total `transition` calls (not states) `generateSafetyAuto`'s quick DFA
    * attempt is willing to spend, regardless of whether `generateSafety`'s
    * explicit table would also fit — most real specifications have a
    * reachable state count well under a few dozen, so the resulting
    * per-state budget catches the common case and gets the smaller,
    * minimized encoding "for free."
    *
    * Budgeted by `transition` calls rather than a flat state count because
    * `reachable`'s cost is `states explored x alphabet size`: a
    * large-alphabet automaton makes even a "small" fixed state count
    * expensive. Dividing by alphabet size keeps this quick attempt's
    * *wall-clock* cost roughly bounded regardless of alphabet size,
    * shrinking to a token 1-state look before giving up on
    * enormous-alphabet automata instead of silently reintroducing that
    * pathology as this function's own new fixed overhead.
    */
  private val quickDfaWork = 256

  /** Prefer a small, bounded `BooleanAutomaton.reachable` + `minimize`
    * attempt first (`quickDfaWork`); if that doesn't finish, fall back to
    * the per-state Boolean-summary table encoding (`generateSafety`)
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
    * out).
    */
  def generateSafetyAuto(automaton: ReverseBooleanAutomaton, maxStates: Int = 4096): Array[Byte] =
    val quickBudget = math.min(maxStates, quickDfaWork / math.max(1, automaton.source.alphabet.length))
    val quickDfa = if quickBudget > 0 then Some(BooleanAutomaton.reachable(automaton, quickBudget)).filterNot(_.truncated) else None
    quickDfa match
      case Some(dfa) => generateSafetyFromDfa(dfa, automaton.source.alphabet)
      case None =>
        val supportFits =
          try
            BooleanAutomaton.checkSupportSize(automaton)
            true
          catch case _: PVWAAError => false
        if supportFits then generateSafety(automaton)
        else if maxStates > quickBudget then
          val dfa = BooleanAutomaton.reachable(automaton, maxStates)
          if !dfa.truncated then generateSafetyFromDfa(dfa, automaton.source.alphabet)
          else generateSafety(automaton)
        else generateSafety(automaton)
