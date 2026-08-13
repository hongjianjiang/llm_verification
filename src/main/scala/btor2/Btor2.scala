package brasp

import scala.collection.mutable

/** BTOR2 backend for reverse Boolean-summary automata: an alternative to
  * `Kind2`/`Lustre` for backends that check word-level circuits (BTOR2)
  * rather than Lustre, e.g. rIC3.
  *
  * The encoding mirrors `Lustre.generate` node-for-node: one Boolean state
  * register per `(pvwaa state, abstraction)` summary cell, initialized to
  * `automaton.initial`; a `symbol` input consumed on every step (there is no
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
    val abstractions = automaton.abstractions
    val stateIndex = states.zipWithIndex.toMap
    val abstractionIndex = abstractions.zipWithIndex.toMap
    val gotoIndex = automaton.gotoSupport.zipWithIndex.toMap
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

    // One Boolean state register per (pvwaa state, abstraction) summary cell.
    val oldSummary = mutable.Map.empty[(String, List[Boolean]), Int]
    for
      state <- states
      abstraction <- abstractions
    do
      val name = s"old_s_${stateIndex(state)}_${abstractionIndex(abstraction)}"
      val reg = b.emit(s"state $sortBool $name")
      val init = boolConst(automaton.initial.table(stateIndex(state))(abstractionIndex(abstraction)))
      b.emit(s"init $sortBool $reg $init")
      oldSummary((state, abstraction)) = reg

    /** Select the summary/diagonal cell for `state` indexed by `bits`
      * (goto-support condition ids, in support order) — the BTOR2 mirror of
      * `Lustre.generate`'s `mux`.
      */
    def mux(state: String, bits: List[Int], summaryOf: (String, List[Boolean]) => Int): Int =
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
    def diagonalOf(summaryOf: (String, List[Boolean]) => Int): Map[String, Int] =
      var known = Map.empty[String, Int]
      for state <- ordered do
        val bits = automaton.gotoSupport.map(goto => known.getOrElse(goto, zero))
        known = known.updated(state, mux(state, bits, summaryOf))
      known

    val oldDiagonal = diagonalOf((state, abstraction) => oldSummary((state, abstraction)))

    def transitionFormula(formula: PositiveFormula, abstraction: List[Boolean]): Int = formula match
      case PositiveFormula.PositiveConstant(value) => boolConst(value)
      case PositiveFormula.PositiveAnd(operands) =>
        operands.map(transitionFormula(_, abstraction)).reduceLeftOption((a, c) => b.emit(s"and $sortBool $a $c")).getOrElse(one)
      case PositiveFormula.PositiveOr(operands) =>
        operands.map(transitionFormula(_, abstraction)).reduceLeftOption((a, c) => b.emit(s"or $sortBool $a $c")).getOrElse(zero)
      case PositiveFormula.TransitionAtom(state, action) =>
        action match
          case Action.Carry => oldDiagonal(state)
          case Action.Leave => oldSummary((state, abstraction))
          case Action.Goto  => boolConst(abstraction(gotoIndex(state)))

    def symbolCase(state: String, abstraction: List[Boolean]): Int =
      val base = oldSummary((state, abstraction))
      alphabet.zipWithIndex.foldRight(base) { case ((symbol, index), acc) =>
        val eq = b.emit(s"eq $sortBool $symbolInput ${constIndex(index)}")
        val transition = transitionFormula(automaton.source.transitions((state, symbol)), abstraction)
        b.emit(s"ite $sortBool $eq $transition $acc")
      }

    val curSummary = mutable.Map.empty[(String, List[Boolean]), Int]
    for
      state <- states
      abstraction <- abstractions
    do curSummary((state, abstraction)) = symbolCase(state, abstraction)

    val curDiagonal = diagonalOf((state, abstraction) => curSummary((state, abstraction)))

    for
      state <- states
      abstraction <- abstractions
    do b.emit(s"next $sortBool ${oldSummary((state, abstraction))} ${curSummary((state, abstraction))}")

    if alphabetSize < (1 << width) then
      val inRange = b.emit(s"ult $sortBool $symbolInput ${constIndex(alphabetSize)}")
      b.emit(s"constraint $inRange")

    b.emit(s"bad ${curDiagonal(automaton.source.initialState)}")

    (List(s"; $monitorName: symbol is an index into the alphabet below") ++
      alphabet.zipWithIndex.map { case (symbol, index) => s"; symbol = $index represents input symbol '$symbol'." } ++
      b.lines.toList :+ "").mkString("\n")
