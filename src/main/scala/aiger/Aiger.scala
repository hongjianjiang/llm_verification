package brasp

import java.io.ByteArrayOutputStream
import scala.collection.mutable

/** Binary AIGER (.aig) backend for reverse Boolean-summary automata: an
  * alternative to `Btor2` for backends that check bit-level circuits
  * (AIGER) rather than word-level ones (BTOR2), e.g. ABC.
  *
  * The encoding is the same one `Btor2.generateSafety` uses — one Boolean
  * state register (here, an AIGER latch) per `(pvwaa state, abstraction)`
  * summary cell, a `symbol` input bit-blasted into individual AIGER inputs,
  * and a single output literal for "the prefix consumed so far is
  * accepted" — just built from 2-input AND gates (with literal negation for
  * free, per the AIGER convention) instead of BTOR2's typed op lines.
  *
  * Two format quirks this needs to work around, found by hex-diffing
  * ABC's own `write_aiger` output against a hand-derivation from its
  * reader source (`ioReadAiger.c`):
  *   - AIGER numbers variables *positionally*: `[1, I]` are inputs, `[I+1,
  *     I+L]` latches, and everything after that an AND gate — there's no
  *     per-line keyword tagging a variable's role, so (unlike `Btor2`)
  *     every input and latch variable must be allocated before any AND
  *     gate is created.
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
    * the sole output is reachable iff some nonempty word is accepted. See
    * `Btor2.generateSafety`'s doc-comment for why the empty-word case
    * doesn't need a second output here either.
    */
  def generateSafety(automaton: ReverseBooleanAutomaton): Array[Byte] =
    if automaton.source.alphabet.isEmpty then throw AigerError("the AIGER backend requires a non-empty alphabet")
    val b = Builder()
    val states = automaton.source.states
    val abstractions = automaton.abstractions
    val stateIndex = states.zipWithIndex.toMap
    val abstractionIndex = abstractions.zipWithIndex.toMap
    val gotoIndex = automaton.gotoSupport.zipWithIndex.toMap
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
    val physicalLatch = mutable.Map.empty[(String, List[Boolean]), Int] // -> latch var
    val resetsHigh = mutable.Map.empty[(String, List[Boolean]), Boolean]
    for
      state <- states
      abstraction <- abstractions
    do
      physicalLatch((state, abstraction)) = b.freshVar()
      resetsHigh((state, abstraction)) = automaton.initial.table(stateIndex(state))(abstractionIndex(abstraction))

    def trueOldLit(state: String, abstraction: List[Boolean]): Int =
      val key = (state, abstraction)
      b.flip(b.lit(physicalLatch(key)), resetsHigh(key))

    def mux(state: String, bits: List[Int], summaryOf: (String, List[Boolean]) => Int): Int =
      def select(index: Int, depth: Int): Int =
        if depth == bits.length then summaryOf(state, abstractions(index))
        else
          val trueBranch = select(index + (1 << (bits.length - depth - 1)), depth + 1)
          val falseBranch = select(index, depth + 1)
          b.ite(bits(depth), trueBranch, falseBranch)
      select(0, 0)

    def diagonalOf(summaryOf: (String, List[Boolean]) => Int): Map[String, Int] =
      var known = Map.empty[String, Int]
      for state <- ordered do
        val bits = automaton.gotoSupport.map(goto => known.getOrElse(goto, b.False))
        known = known.updated(state, mux(state, bits, summaryOf))
      known

    val oldDiagonal = diagonalOf((state, abstraction) => trueOldLit(state, abstraction))

    def transitionFormula(formula: PositiveFormula, abstraction: List[Boolean]): Int = formula match
      case PositiveFormula.PositiveConstant(value) => if value then b.True else b.False
      case PositiveFormula.PositiveAnd(operands) =>
        operands.map(transitionFormula(_, abstraction)).reduceLeftOption(b.and).getOrElse(b.True)
      case PositiveFormula.PositiveOr(operands) =>
        operands.map(transitionFormula(_, abstraction)).reduceLeftOption(b.or).getOrElse(b.False)
      case PositiveFormula.TransitionAtom(state, action) =>
        action match
          case Action.Carry => oldDiagonal(state)
          case Action.Leave => trueOldLit(state, abstraction)
          case Action.Goto  => if abstraction(gotoIndex(state)) then b.True else b.False

    /** Bitwise equality of the `symbol` input against the constant `index`,
      * i.e. the AIGER bit-blast of `Btor2`'s `eq $symbolInput ${constIndex(index)}`.
      */
    def symbolEquals(index: Int): Int =
      (0 until width).foldLeft(b.True) { (acc, bitPos) =>
        val bit = if ((index >> bitPos) & 1) == 1 then b.True else b.False
        b.and(acc, b.xnor(inputLits(bitPos), bit))
      }

    def symbolCase(state: String, abstraction: List[Boolean]): Int =
      val base = trueOldLit(state, abstraction)
      alphabet.zipWithIndex.foldRight(base) { case ((symbol, index), acc) =>
        val transition = transitionFormula(automaton.source.transitions((state, symbol)), abstraction)
        b.ite(symbolEquals(index), transition, acc)
      }

    val trueNext = mutable.Map.empty[(String, List[Boolean]), Int]
    for
      state <- states
      abstraction <- abstractions
    do trueNext((state, abstraction)) = symbolCase(state, abstraction)

    val curDiagonal = diagonalOf((state, abstraction) => trueNext((state, abstraction)))

    val badLit = curDiagonal(automaton.source.initialState)

    // Flip the computed *true* next-value back into what the physically
    // reset-to-0 latch actually stores.
    val latches = for
      state <- states
      abstraction <- abstractions
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
