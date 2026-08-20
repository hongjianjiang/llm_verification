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
    // This backend still builds one explicit variable per (state, local
    // abstraction) summary cell below — unlike `BooleanAutomaton`'s own
    // BDD-based `transition`/`diagonal`, which no longer need this check.
    try BooleanAutomaton.checkSupportSize(automaton)
    catch case PVWAAError(message) => throw LustreError(message)
    val node = identifier(nodeName)
    val states = automaton.source.states
    val stateIndex = states.zipWithIndex.toMap
    // `Vector` at both levels, not `List`: both `mux`'s `select` and
    // `transitionFormula` index into an abstraction/`bits`, and `mux` also
    // indexes into the abstraction *list itself* by position (up to
    // `2^n - 1`) — either as a `List`, that's an O(i) scan per lookup, the
    // same trap `supportIndex` (see `BooleanAutomaton.scala`) exists to
    // avoid one level up. Cached per state: `mux` (called twice, via the
    // `old`/`cur` `diagonalEquations` passes) and the declaration/equation
    // loops below each ask for the same state's abstractions again, and
    // rebuilding a `2^n`-entry vector from scratch every time is wasted
    // work for states with a sizeable local support.
    val abstractionsCache = scala.collection.mutable.HashMap.empty[String, Vector[Vector[Boolean]]]
    def abstractionsOf(state: String): Vector[Vector[Boolean]] =
      abstractionsCache.getOrElseUpdate(
        state, {
          val n = automaton.support(state).length
          if n == 0 then Vector(Vector.empty) else (0 until (1 << n)).map(i => (n - 1 to 0 by -1).map(bit => ((i >> bit) & 1) == 1).toVector).toVector
        },
      )
    def abstractionIndexOf(state: String, abstraction: Vector[Boolean]): Int =
      abstraction.foldLeft(0)((acc, bit) => (acc << 1) | (if bit then 1 else 0))
    val alphabetSize = automaton.source.alphabet.length

    def summaryName(prefix: String, state: String, abstraction: Vector[Boolean]): String =
      s"${prefix}_s_${stateIndex(state)}_${abstractionIndexOf(state, abstraction)}"
    def diagonalName(prefix: String, state: String): String =
      s"${prefix}_v_${stateIndex(state)}"

    /** Select the summary column indexed by the Boolean expressions `bits`
      * (`state`'s own local support, in order).
      */
    def mux(state: String, bits: Vector[String], prefix: String): String =
      val abstractions = abstractionsOf(state)
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
        val bits = automaton.support(state).map(goto => if known.contains(goto) then diagonalName(prefix, goto) else "false").toVector
        equations += s"  ${diagonalName(prefix, state)} = ${mux(state, bits, prefix)};"
        known += state
      equations.toList

    // `ownerAbstraction` is indexed by `automaton.support(owner)`; a
    // `leave`/`goto` atom's target is always in `owner`'s support by
    // construction (see `BooleanAutomaton.supportOf`), so the
    // `automaton.supportIndex(owner)` lookups below always find a valid
    // index.
    def transitionFormula(owner: String, ownerAbstraction: Vector[Boolean], formula: PositiveFormula): String = formula match
      case PositiveFormula.PositiveConstant(value) => if value then "true" else "false"
      case PositiveFormula.PositiveAnd(operands) =>
        "(" + operands.map(transitionFormula(owner, ownerAbstraction, _)).mkString(" and ") + ")"
      case PositiveFormula.PositiveOr(operands) =>
        "(" + operands.map(transitionFormula(owner, ownerAbstraction, _)).mkString(" or ") + ")"
      case PositiveFormula.TransitionAtom(state, action) =>
        val ownerIndex = automaton.supportIndex(owner)
        action match
          case Action.Carry => diagonalName("old", state)
          case Action.Leave =>
            val bits = automaton.support(state).map(g => ownerAbstraction(ownerIndex(g))).toVector
            summaryName("old", state, bits)
          case Action.Goto => if ownerAbstraction(ownerIndex(state)) then "true" else "false"

    def symbolCase(state: String, abstraction: Vector[Boolean]): String =
      automaton.source.alphabet.zipWithIndex.foldRight(summaryName("old", state, abstraction)) {
        case ((symbol, index), expression) =>
          val transition = automaton.source.transitions((state, symbol))
          s"(if symbol = $index then ${transitionFormula(state, abstraction, transition)} else $expression)"
      }

    val declarations = scala.collection.mutable.ArrayBuffer.empty[String]
    val equations = scala.collection.mutable.ArrayBuffer.empty[String]
    for
      state <- states
      abstraction <- abstractionsOf(state)
    do
      val old = summaryName("old", state, abstraction)
      val cur = summaryName("cur", state, abstraction)
      declarations += s"  $old: bool;"
      declarations += s"  $cur: bool;"
      // The initial summary doesn't depend on any goto-support bit yet
      // (nothing has been consumed), so every abstraction of a state
      // starts at the same constant: whether it's a final PVWAA state.
      val initialLiteral = if automaton.source.finalStates.contains(state) then "true" else "false"
      equations += s"  $old = $initialLiteral -> (if start then $initialLiteral else pre($cur));"
    declarations ++= states.map(state => s"  ${diagonalName("old", state)}: bool;")
    declarations ++= states.map(state => s"  ${diagonalName("cur", state)}: bool;")
    equations ++= diagonalEquations("old")
    for
      state <- states
      abstraction <- abstractionsOf(state)
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
