package brasp

import java.io.ByteArrayOutputStream
import scala.collection.mutable

/** A BTOR2 backend that skips `BooleanAutomaton`'s determinization
  * entirely: it encodes the forward PVWAA (the alternating automaton
  * `Pvwaa.fromFuture2ltl` produces) directly, one Boolean state register
  * per PVWAA state, instead of one register per Boolean-summary cell
  * (`Btor2.generateSafety`) or per minimized-DFA state
  * (`Btor2.generateSafetyFromDfa`).
  *
  * The construction follows the string solver Sloth's own AFA-to-AIGER
  * translation (`strsolver.Emptiness.AFA2AAG`, uuverifiers/sloth): each
  * state's own `Carry`/`Leave` references become a *freshly guessed*
  * Boolean input, validated one step later by requiring "if this state was
  * active, its own transition formula (built from that step's guesses)
  * must hold" — exactly a hardware model checker's own job, no explicit
  * subset-construction needed. Sloth's automata have no second ("pebble")
  * position, though, so that part is this project's own addition: a
  * `Goto` reference resolves against the *pebble's original anchor
  * symbol* (frozen the moment the referencing state was first activated,
  * not recomputed per step) rather than becoming another guess.
  *
  * That resolution is only implemented for the case validated against
  * `Pvwaa.accepts` on every `two_var` family this project's own generator
  * produces: a goto-target whose own formula is symbol-constant, with no
  * further `Carry`/`Leave` of its own (`Once`/`Hist`/`Yst`/`Since` over
  * plain symbol atoms, i.e. no `Until` nested inside another `Until`'s
  * operand). `checkGotoTargetsAreSimple` rejects anything else up front —
  * e.g. `at_least_two_a`'s `Hist`-based construction, whose goto-targets
  * carry their own multi-step `Carry`/`Leave` chains, is deliberately out
  * of scope here; use `Btor2`/`Aiger` for those.
  *
  * Where it applies, this sidesteps two independent blow-ups the other
  * backends hit on the same `two_var` families: `Btor2.generateSafety`'s
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

  /** Every `Goto`-referenced state's own transition formula must be built
    * only from `PositiveConstant`/`PositiveAnd`/`PositiveOr`/`Goto` — no
    * `Carry`/`Leave` of its own, transitively — see the class doc-comment
    * for why. Throws `DirectPvwaaError` naming the first offending state
    * instead of silently building an unsound circuit.
    */
  def checkGotoTargetsAreSimple(automaton: ForwardPVWAA): Unit =
    val gotoTargets = mutable.LinkedHashSet.empty[String]
    def collectGotoTargets(f: PositiveFormula): Unit = f match
      case PositiveFormula.PositiveConstant(_)                  => ()
      case PositiveFormula.PositiveAnd(operands)                => operands.foreach(collectGotoTargets)
      case PositiveFormula.PositiveOr(operands)                 => operands.foreach(collectGotoTargets)
      case PositiveFormula.TransitionAtom(target, Action.Goto)  => gotoTargets += target
      case PositiveFormula.TransitionAtom(_, Action.Carry | Action.Leave) => ()
    for
      state <- automaton.states
      symbol <- automaton.alphabet
    do collectGotoTargets(automaton.transitions((state, symbol)))

    def isSimple(state: String, seen: Set[String]): Boolean =
      if seen.contains(state) then true
      else
        def check(f: PositiveFormula): Boolean = f match
          case PositiveFormula.PositiveConstant(_)                             => true
          case PositiveFormula.PositiveAnd(operands)                           => operands.forall(check)
          case PositiveFormula.PositiveOr(operands)                            => operands.forall(check)
          case PositiveFormula.TransitionAtom(_, Action.Carry | Action.Leave)  => false
          case PositiveFormula.TransitionAtom(target, Action.Goto)             => isSimple(target, seen + state)
        automaton.alphabet.forall(symbol => check(automaton.transitions((state, symbol))))

    for target <- gotoTargets do
      if !isSimple(target, Set.empty) then
        throw DirectPvwaaError(
          s"goto-target '$target' has its own Carry/Leave structure (e.g. a Until nested inside another " +
            "Until's operand, as Hist-based formulas produce) — this backend only supports goto-targets " +
            "that are themselves symbol-constant formulas; use --btor2/--aiger instead"
        )

  /** Whether the empty word is accepted — a compile-time constant (mirrors
    * `Pvwaa.accepts`'s own `head == length` base case at `length == 0`),
    * computed statically rather than by asking the solver.
    */
  def emptyWordAccepted(automaton: ForwardPVWAA): Boolean =
    automaton.finalStates.contains(automaton.initialState)

  /** Emit a BTOR2 model proving the automaton's bad language is
    * unreachable: `bad` is reachable iff some nonempty word is accepted.
    * The `symbol` input is declared first (BTOR2 input index 0), matching
    * what `Ric3.summarize`'s witness decoder already expects — this
    * backend's extra per-state "guess" inputs come after it and are
    * simply ignored by that decoder, so counterexample words still decode
    * correctly with no changes there.
    */
  def generateSafety(automaton: ForwardPVWAA, monitorName: String = "brasp_monitor"): String =
    if automaton.alphabet.isEmpty then throw DirectPvwaaError("this backend requires a non-empty alphabet")
    checkGotoTargetsAreSimple(automaton)

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

    // frozen-first-symbol machinery for resolving Goto (see class doc)
    val startedReg = b.emit(s"state $sortBool started")
    b.emit(s"init $sortBool $startedReg $zero")
    val firstSymbolReg = b.emit(s"state $sortSymbol firstSymbol")
    b.emit(s"init $sortSymbol $firstSymbolReg ${constIndex(0)}")
    val effectiveFirstSymbol = b.gate(s"ite $sortSymbol $startedReg $firstSymbolReg $symbolInput")

    def dispatchOnSymbol(state: String, symbolBits: Int, encode: PositiveFormula => Int): Int =
      alphabet.zipWithIndex.foldRight(zero) { case ((symbol, index), acc) =>
        val eq = b.gate(s"eq $sortBool $symbolBits ${constIndex(index)}")
        val transition = encode(automaton.transitions((state, symbol)))
        b.gate(s"ite $sortBool $eq $transition $acc")
      }

    def encode(f: PositiveFormula): Int = f match
      case PositiveFormula.PositiveConstant(value) => boolConst(value)
      case PositiveFormula.PositiveAnd(operands)   => andAll(operands.map(encode))
      case PositiveFormula.PositiveOr(operands)    => orAll(operands.map(encode))
      case PositiveFormula.TransitionAtom(state, Action.Goto) =>
        dispatchOnSymbol(state, effectiveFirstSymbol, encode)
      case PositiveFormula.TransitionAtom(state, Action.Carry | Action.Leave) =>
        guessOf(state)

    val obligationOf = states.map(state => state -> dispatchOnSymbol(state, symbolInput, encode)).toMap

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
    // (`emptyWordAccepted`), the same convention `Btor2.generateSafety`
    // uses (its `bad` is built from `curDiagonal`, inherently post-symbol).
    val reachCheck = andGate(startedReg, andGate(notGate(errReg), allActiveAreFinal))

    for state <- states do b.emit(s"next $sortBool ${activeOf(state)} ${guessOf(state)}")
    b.emit(s"next $sortBool $errReg ${notGate(noNewErr)}")
    b.emit(s"next $sortBool $startedReg $one")
    b.emit(s"next $sortSymbol $firstSymbolReg $effectiveFirstSymbol")

    if alphabetSize < (1 << width) then
      val inRange = b.emit(s"ult $sortBool $symbolInput ${constIndex(alphabetSize)}")
      b.emit(s"constraint $inRange")

    b.emit(s"bad $reachCheck")

    (List(s"; $monitorName: symbol is an index into the alphabet below") ++
      alphabet.zipWithIndex.map { case (symbol, index) => s"; symbol = $index represents input symbol '$symbol'." } ++
      b.lines.toList :+ "").mkString("\n")

  private final class AigerBuilder:
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
    // Hash-consed, unlike `Aiger.scala`'s own `Builder`: that one's calls
    // are naturally close to unique already (one `symbolCase`/`ite` chain
    // per distinct `(state, abstraction)` cell), but this construction
    // repeats the *exact* same symbol-dispatch structure once per PVWAA
    // state (`dispatchOnSymbol`) and once more per `Goto` reference —
    // without caching, that's `O(states)` duplicate copies of the same
    // gates, which is exactly the blow-up this whole backend exists to
    // avoid elsewhere.
    private val andCache = mutable.HashMap.empty[(Int, Int), Int]
    def and(a: Int, b: Int): Int =
      if a == False || b == False then False
      else if a == True then b
      else if b == True then a
      else if a == b then a
      else if a == not(b) then False
      else
        val key = if a <= b then (a, b) else (b, a)
        andCache.getOrElseUpdate(
          key, {
            val v = freshVar()
            val out = lit(v)
            andGates += ((out, a, b))
            out
          },
        )
    def or(a: Int, c: Int): Int = not(and(not(a), not(c)))
    def ite(c: Int, t: Int, e: Int): Int = or(and(c, t), and(not(c), e))
    def xnor(a: Int, c: Int): Int = or(and(a, c), and(not(a), not(c)))
    def flip(literal: Int, negate: Boolean): Int = if negate then not(literal) else literal

  private def encodeDelta(out: ByteArrayOutputStream, valueIn: Int): Unit =
    var value = valueIn
    while (value & ~0x7f) != 0 do
      out.write((value & 0x7f) | 0x80)
      value = value >>> 7
    out.write(value & 0x7f)

  /** The AIGER (binary `.aig`) mirror of `generateSafety`, for the ABC
    * backend (`Abc.run`) instead of rIC3 — same construction (one latch
    * per PVWAA state, `Goto` resolved against a frozen first symbol,
    * `Carry`/`Leave` a freshly guessed input validated one step later),
    * built from 2-input AND gates instead of BTOR2's typed op lines, with
    * the `symbol` and frozen-first-symbol registers bit-blasted the same
    * way `Aiger.generateSafety` bit-blasts its own `symbol` input.
    *
    * Two of `Aiger.generateSafety`'s own format constraints apply here
    * unchanged (see its doc-comment for the full reasoning): every input
    * and latch variable must be allocated before any AND gate is built
    * (AIGER numbers variables positionally by role, no per-line role tag),
    * and every latch must physically reset to 0 — only `active_<initial
    * state>` needs a logical reset of 1, handled with the same
    * XOR-with-init trick (`resetsHigh`/`flip`) `Aiger.generateSafety` uses
    * for its own summary latches. Only power-of-two alphabets are
    * supported, for the same reason `Aiger.generateSafety` is limited to
    * them: AIGER has no `constraint`-line equivalent to rule out
    * out-of-range symbol codes.
    */
  def generateSafetyAiger(automaton: ForwardPVWAA): Array[Byte] =
    if automaton.alphabet.isEmpty then throw DirectPvwaaError("this backend requires a non-empty alphabet")
    checkGotoTargetsAreSimple(automaton)

    val states = automaton.states
    val alphabet = automaton.alphabet
    val alphabetSize = alphabet.length
    val width = symbolWidth(alphabetSize)
    if alphabetSize < (1 << width) then
      throw DirectPvwaaError(
        s"the AIGER backend doesn't support non-power-of-two alphabets yet (alphabet size $alphabetSize needs a " +
          s"$width-bit symbol range constraint, which AIGER has no native way to express the way BTOR2's `constraint` line does)"
      )

    val b = AigerBuilder()

    // inputs first (AIGER's positional numbering requires it): symbol bits,
    // then one guess bit per state
    val symbolVars = Vector.fill(width)(b.freshVar())
    val symbolLits = symbolVars.map(v => b.lit(v))
    val guessVars = states.map(_ => b.freshVar())
    val guessOf = states.zip(guessVars.map(v => b.lit(v))).toMap

    // latches next: active/err/started/frozen-first-symbol, all still
    // before any AND gate
    val activeLatchVars = states.map(_ => b.freshVar())
    val activeOf = states.zip(activeLatchVars).toMap
    val errVar = b.freshVar()
    val startedVar = b.freshVar()
    val firstSymbolVars = Vector.fill(width)(b.freshVar())

    def resetsHigh(state: String): Boolean = state == automaton.initialState
    def trueOldActive(state: String): Int = b.flip(b.lit(activeOf(state)), resetsHigh(state))
    val errLogical = b.lit(errVar) // resets low
    val startedLogical = b.lit(startedVar) // resets low
    val firstSymbolLogical = firstSymbolVars.map(v => b.lit(v)) // each resets low (index 0)

    val effectiveFirstSymbol = firstSymbolLogical.zip(symbolLits).map { case (frozen, live) => b.ite(startedLogical, frozen, live) }

    def bitsEqual(bits: Vector[Int], index: Int): Int =
      bits.indices.foldLeft(b.True) { (acc, pos) =>
        val want = if ((index >> (width - 1 - pos)) & 1) == 1 then b.True else b.False
        b.and(acc, b.xnor(bits(pos), want))
      }

    def dispatchOnSymbol(state: String, bits: Vector[Int], encode: PositiveFormula => Int): Int =
      alphabet.zipWithIndex.foldRight(b.False) { case ((symbol, index), acc) =>
        b.ite(bitsEqual(bits, index), encode(automaton.transitions((state, symbol))), acc)
      }

    def encode(f: PositiveFormula): Int = f match
      case PositiveFormula.PositiveConstant(value) => if value then b.True else b.False
      case PositiveFormula.PositiveAnd(operands)   => operands.map(encode).reduceLeftOption(b.and).getOrElse(b.True)
      case PositiveFormula.PositiveOr(operands)    => operands.map(encode).reduceLeftOption(b.or).getOrElse(b.False)
      case PositiveFormula.TransitionAtom(state, Action.Goto) =>
        dispatchOnSymbol(state, effectiveFirstSymbol, encode)
      case PositiveFormula.TransitionAtom(state, Action.Carry | Action.Leave) =>
        guessOf(state)

    val obligationOf = states.map(state => state -> dispatchOnSymbol(state, symbolLits, encode)).toMap

    val allSatisfied = states.map(q => b.or(b.not(trueOldActive(q)), obligationOf(q))).reduceLeftOption(b.and).getOrElse(b.True)
    val noNewErr = b.and(b.not(errLogical), allSatisfied)

    val allActiveAreFinal = states
      .map(q => b.or(b.not(trueOldActive(q)), if automaton.finalStates.contains(q) then b.True else b.False))
      .reduceLeftOption(b.and)
      .getOrElse(b.True)
    val reachCheck = b.and(startedLogical, b.and(b.not(errLogical), allActiveAreFinal))

    // physical next-values: flip back into what each physically-reset-to-0
    // latch actually stores
    val activeNext = states.map(state => b.flip(guessOf(state), resetsHigh(state)))
    val errNext = b.not(noNewErr)
    val startedNext = b.True
    val firstSymbolNext = effectiveFirstSymbol

    val out = new ByteArrayOutputStream()
    def writeLine(text: String): Unit = out.write((text + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII))

    val numLatches = states.length + 2 + width
    writeLine(s"aig ${b.maxVar} ${width + states.length} $numLatches 1 ${b.andGates.length}")
    for next <- activeNext do writeLine(next.toString)
    writeLine(errNext.toString)
    writeLine(startedNext.toString)
    for next <- firstSymbolNext do writeLine(next.toString)
    writeLine(reachCheck.toString)
    for (outLit, aLit, cLit) <- b.andGates do
      val rhsHigh = math.max(aLit, cLit)
      val rhsLow = math.min(aLit, cLit)
      encodeDelta(out, outLit - rhsHigh)
      encodeDelta(out, rhsHigh - rhsLow)
    writeLine("c")
    for (symbol, index) <- alphabet.zipWithIndex do
      writeLine(s"symbol = $index represents input symbol '$symbol' (bit-blasted, $width-bit).")

    out.toByteArray
