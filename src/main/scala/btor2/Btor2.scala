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
    def emit(rest: String): Int =
      counter += 1
      lines += s"$counter $rest"
      counter

  private def symbolWidth(alphabetSize: Int): Int =
    if alphabetSize <= 1 then 1 else 32 - Integer.numberOfLeadingZeros(alphabetSize - 1)

  /** Emit a BTOR2 model proving the monitor's bad language is unreachable:
    * `bad` is reachable iff some nonempty word is accepted.
    */
  def generateSafety(automaton: ReverseBooleanAutomaton, monitorName: String = "brasp_monitor"): String =
    if automaton.source.alphabet.isEmpty then throw Btor2Error("the BTOR2 backend requires a non-empty alphabet")
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
      val init = boolConst(automaton.initial.table(state)(abstractionIndexOf(state, abstraction)))
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
          b.emit(s"ite $sortBool ${bits(depth)} $trueBranch $falseBranch")
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
        operands.map(transitionFormula(owner, ownerAbstraction, _)).reduceLeftOption((a, c) => b.emit(s"and $sortBool $a $c")).getOrElse(one)
      case PositiveFormula.PositiveOr(operands) =>
        operands.map(transitionFormula(owner, ownerAbstraction, _)).reduceLeftOption((a, c) => b.emit(s"or $sortBool $a $c")).getOrElse(zero)
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
        val eq = b.emit(s"eq $sortBool $symbolInput ${constIndex(index)}")
        val transition = transitionFormula(state, abstraction, automaton.source.transitions((state, symbol)))
        b.emit(s"ite $sortBool $eq $transition $acc")
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
