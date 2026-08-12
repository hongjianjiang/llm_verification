package brasp

/** Lustre V6 backend for reverse Boolean-summary automata. */

final case class LustreError(message: String) extends RuntimeException(message)

object Lustre:

  private def identifier(value: String): String =
    val result = value.replaceAll("[^A-Za-z0-9_]", "_")
    if result.isEmpty || result.head.isDigit then "monitor_" + result else result

  /** Generate a left-to-right, restartable Lustre node for a
    * Boolean-summary automaton.
    *
    * The symbol is an integer index into `automaton.source.alphabet`
    * (`input_ok` reports whether a `valid` tick's `symbol` is in range).
    * `start` resets the summary to `automaton.initial`; `valid` gates
    * whether the current tick's `symbol` is consumed at all (a tick with
    * `valid = false` holds the summary steady, e.g. a stutter/pause between
    * words). `accept_prefix` reports whether the prefix consumed since the
    * last reset is accepted; `accept_word = last and accept_prefix` reports
    * acceptance of a finite word ending on this tick.
    */
  def generate(automaton: ReverseBooleanAutomaton, nodeName: String = "brasp_monitor"): String =
    if automaton.source.alphabet.isEmpty then throw LustreError("the Lustre backend requires a non-empty alphabet")
    val node = identifier(nodeName)
    val states = automaton.source.states
    val abstractions = automaton.abstractions
    val stateIndex = states.zipWithIndex.toMap
    val abstractionIndex = abstractions.zipWithIndex.toMap
    val gotoIndex = automaton.gotoSupport.zipWithIndex.toMap
    val alphabetSize = automaton.source.alphabet.length

    def summaryName(prefix: String, state: String, abstraction: List[Boolean]): String =
      s"${prefix}_s_${stateIndex(state)}_${abstractionIndex(abstraction)}"
    def diagonalName(prefix: String, state: String): String =
      s"${prefix}_v_${stateIndex(state)}"

    /** Select the summary column indexed by the Boolean expressions `bits`. */
    def mux(state: String, bits: List[String], prefix: String): String =
      def select(index: Int, depth: Int): String =
        if depth == bits.length then summaryName(prefix, state, abstractions(index))
        else
          val falseBranch = select(index, depth + 1)
          val trueBranch = select(index + (1 << (bits.length - depth - 1)), depth + 1)
          s"(if ${bits(depth)} then $trueBranch else $falseBranch)"
      select(0, 0)

    val ordered = states.sortBy(state => (automaton.source.rank(state), state))

    def diagonalEquations(prefix: String): List[String] =
      val equations = scala.collection.mutable.ArrayBuffer.empty[String]
      var known = Set.empty[String]
      for state <- ordered do
        val bits = automaton.gotoSupport.map(goto => if known.contains(goto) then diagonalName(prefix, goto) else "false")
        equations += s"  ${diagonalName(prefix, state)} = ${mux(state, bits, prefix)};"
        known += state
      equations.toList

    def transitionFormula(formula: PositiveFormula, abstraction: List[Boolean]): String = formula match
      case PositiveFormula.PositiveConstant(value) => if value then "true" else "false"
      case PositiveFormula.PositiveAnd(operands) =>
        "(" + operands.map(transitionFormula(_, abstraction)).mkString(" and ") + ")"
      case PositiveFormula.PositiveOr(operands) =>
        "(" + operands.map(transitionFormula(_, abstraction)).mkString(" or ") + ")"
      case PositiveFormula.TransitionAtom(state, action) =>
        action match
          case Action.Carry => diagonalName("old", state)
          case Action.Leave => summaryName("old", state, abstraction)
          case Action.Goto  => if abstraction(gotoIndex(state)) then "true" else "false"

    def symbolCase(state: String, abstraction: List[Boolean]): String =
      automaton.source.alphabet.zipWithIndex.foldRight(summaryName("old", state, abstraction)) {
        case ((symbol, index), expression) =>
          val transition = automaton.source.transitions((state, symbol))
          s"(if symbol = $index then ${transitionFormula(transition, abstraction)} else $expression)"
      }

    val declarations = scala.collection.mutable.ArrayBuffer.empty[String]
    val equations = scala.collection.mutable.ArrayBuffer.empty[String]
    for
      state <- states
      abstraction <- abstractions
    do
      val old = summaryName("old", state, abstraction)
      val cur = summaryName("cur", state, abstraction)
      declarations += s"  $old: bool;"
      declarations += s"  $cur: bool;"
      val initialLiteral = if automaton.initial.table(stateIndex(state))(abstractionIndex(abstraction)) then "true" else "false"
      equations += s"  $old = $initialLiteral -> (if start then $initialLiteral else pre($cur));"
    declarations ++= states.map(state => s"  ${diagonalName("old", state)}: bool;")
    declarations ++= states.map(state => s"  ${diagonalName("cur", state)}: bool;")
    equations ++= diagonalEquations("old")
    for
      state <- states
      abstraction <- abstractions
    do
      val old = summaryName("old", state, abstraction)
      equations += s"  ${summaryName("cur", state, abstraction)} = if not valid then $old else ${symbolCase(state, abstraction)};"
    equations ++= diagonalEquations("cur")

    val comments = automaton.source.alphabet.zipWithIndex.map { case (symbol, index) =>
      s"-- symbol = $index represents input symbol '$symbol'."
    }

    (comments ++ List(
      s"node $node(symbol: int; valid: bool; start: bool; last: bool) returns (accept_prefix: bool; accept_word: bool; input_ok: bool);",
      "var",
    ) ++ declarations.toList ++ List("let") ++ equations.toList ++ List(
      s"  input_ok = not valid or (0 <= symbol and symbol < $alphabetSize);",
      s"  accept_prefix = ${diagonalName("cur", automaton.source.initialState)};",
      "  accept_word = last and accept_prefix;",
      "tel",
      "",
    )).mkString("\n")
