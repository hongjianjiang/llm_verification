package brasp

/** Boolean-summary automata obtained by reversing a forward PVWAA.
  *
  * The state is a Boolean summary `S : Q x B^G -> B`, where `G` is the
  * goto-support. This is the directional mirror of Section 9.2 of the paper.
  * For a forward PVWAA recognizing `reverse(L)`, the resulting automaton
  * reads a word in `L` from left to right.
  */

/** A total truth table for `Q x B^G`, encoded in automaton order. */
final case class BooleanSummary(table: Vector[Vector[Boolean]])

/** A deterministic Boolean automaton that reads the original language.
  *
  * `source` reads the reversed language left-to-right. This automaton reads
  * the source word in the opposite direction, hence left-to-right on the
  * original word. It materializes one Boolean-summary state at a time.
  */
final case class ReverseBooleanAutomaton(
    source: ForwardPVWAA,
    gotoSupport: List[String],
    abstractions: List[List[Boolean]],
    initial: BooleanSummary,
)

/** The concrete, finite deterministic automaton reachable from `initial` by
  * actually following transitions — as opposed to the full Boolean-summary
  * state space (astronomical by construction, `Prop. 9.4`, and never meant
  * to be materialized). States are `0 until stateCount`; `initial` is
  * always `0`. `truncated` is set if exploration hit `maxStates` before
  * converging, in which case `transitions` is incomplete and unsafe to use
  * as-is (some reachable `(state, symbol)` pairs are simply missing).
  */
final case class ReachableDfa(
    stateCount: Int,
    transitions: scala.collection.immutable.VectorMap[(Int, String), Int],
    accepting: Set[Int],
    initial: Int,
    truncated: Boolean,
)

object BooleanAutomaton:
  import JsonValue.*
  import scala.collection.immutable.VectorMap

  private def cartesianBooleans(n: Int): List[List[Boolean]] =
    if n == 0 then List(Nil)
    else for first <- List(false, true); rest <- cartesianBooleans(n - 1) yield first :: rest

  private def gotoSupportOf(automaton: ForwardPVWAA): List[String] =
    var found = Set.empty[String]
    def visit(formula: PositiveFormula): Unit = formula match
      case PositiveFormula.TransitionAtom(state, Action.Goto) => found += state
      case _: PositiveFormula.TransitionAtom                  => ()
      case PositiveFormula.PositiveAnd(operands)               => operands.foreach(visit)
      case PositiveFormula.PositiveOr(operands)                => operands.foreach(visit)
      case PositiveFormula.PositiveConstant(_)                 => ()
    automaton.transitions.values.foreach(visit)
    found.toList.sortBy(state => (automaton.rank(state), state))

  /** Construct the initial Boolean summary for the reversed automaton. */
  def fromForwardPvwaa(automaton: ForwardPVWAA): ReverseBooleanAutomaton =
    val gotoSupport = gotoSupportOf(automaton)
    val abstractions = cartesianBooleans(gotoSupport.length)
    val initial = BooleanSummary(
      automaton.states.map(state => abstractions.map(_ => automaton.finalStates.contains(state)).toVector).toVector
    )
    ReverseBooleanAutomaton(automaton, gotoSupport, abstractions, initial)

  /** Solve `V(q) = S(q, V|G)` bottom-up along the very-weak order. */
  def diagonal(automaton: ReverseBooleanAutomaton, summary: BooleanSummary): Map[String, Boolean] =
    val ordered = automaton.source.states.sortBy(state => (automaton.source.rank(state), state))
    var result = Map.empty[String, Boolean]
    for state <- ordered do
      // By very weakness, this row can inspect only strictly lower-ranked
      // goto states. Values for the remaining support are don't-cares.
      val abstraction = automaton.gotoSupport.map(goto => result.getOrElse(goto, false))
      val stateIndex = automaton.source.states.indexOf(state)
      val abstractionIndex = automaton.abstractions.indexOf(abstraction)
      result = result.updated(state, summary.table(stateIndex)(abstractionIndex))
    result

  /** Consume one original-language symbol and return the next summary. */
  def transition(automaton: ReverseBooleanAutomaton, summary: BooleanSummary, symbol: String): BooleanSummary =
    if !automaton.source.alphabet.contains(symbol) then
      throw PVWAAError(s"symbol outside automaton alphabet: '$symbol'")
    val priorDiagonal = diagonal(automaton, summary)

    def evaluate(formula: PositiveFormula, abstraction: List[Boolean]): Boolean = formula match
      case PositiveFormula.PositiveConstant(value) => value
      case PositiveFormula.PositiveAnd(operands)    => operands.forall(o => evaluate(o, abstraction))
      case PositiveFormula.PositiveOr(operands)     => operands.exists(o => evaluate(o, abstraction))
      case PositiveFormula.TransitionAtom(state, action) =>
        action match
          case Action.Carry => priorDiagonal(state)
          case Action.Leave =>
            summary.table(automaton.source.states.indexOf(state))(automaton.abstractions.indexOf(abstraction))
          case Action.Goto => abstraction(automaton.gotoSupport.indexOf(state))

    val table = automaton.source.states.map { state =>
      val formula = automaton.source.transitions((state, symbol))
      automaton.abstractions.map(abstraction => evaluate(formula, abstraction)).toVector
    }.toVector
    BooleanSummary(table)

  /** Run the Boolean automaton left-to-right on the original word. */
  def accepts(automaton: ReverseBooleanAutomaton, word: IndexedSeq[String]): Boolean =
    val finalSummary = word.foldLeft(automaton.initial)((summary, symbol) => transition(automaton, summary, symbol))
    diagonal(automaton, finalSummary)(automaton.source.initialState)

  private def maximumStateCount(automaton: ReverseBooleanAutomaton): BigInt =
    BigInt(2).pow(automaton.source.states.length * automaton.abstractions.length)

  def render(automaton: ReverseBooleanAutomaton): String =
    List(
      "Reverse Boolean automaton (reads the original language left-to-right)",
      s"PVWAA states: ${automaton.source.states.length}",
      s"goto support: {${automaton.gotoSupport.mkString(", ")}}",
      s"Boolean abstractions: ${automaton.abstractions.length}",
      s"maximum Boolean-summary states: ${maximumStateCount(automaton)}",
      "transition: Section 9 summary recurrence, evaluated on demand",
    ).mkString("\n")

  /** Explore the automaton's *reachable* states breadth-first from
    * `initial`, materializing a concrete finite DFA.
    *
    * The full Boolean-summary state space (`maximum_state_count`) is
    * astronomical by construction (`Prop. 9.4`) and is never supposed to be
    * materialized — Algorithm 2 only ever holds one summary at a time. This
    * instead does what the paper itself suggests for emptiness checking:
    * "one explores the reachable summaries of A→ lazily... never
    * materializing the state space." For real specifications the reachable
    * set is usually small even though the worst-case bound is not.
    * `maxStates` caps the exploration so a pathological automaton can't
    * hang the caller; `truncated` is set (and `transitions` left
    * incomplete) if the cap is hit.
    */
  def reachable(automaton: ReverseBooleanAutomaton, maxStates: Int = 512): ReachableDfa =
    val ids = scala.collection.mutable.LinkedHashMap.empty[BooleanSummary, Int]
    def idFor(summary: BooleanSummary): Int = ids.getOrElseUpdate(summary, ids.size)

    val queue = scala.collection.mutable.Queue.empty[BooleanSummary]
    val transitions = scala.collection.mutable.LinkedHashMap.empty[(Int, String), Int]
    var truncated = false

    val initial = idFor(automaton.initial)
    queue += automaton.initial
    while queue.nonEmpty do
      val current = queue.dequeue()
      val fromId = idFor(current)
      for symbol <- automaton.source.alphabet do
        val next = transition(automaton, current, symbol)
        val isNewState = !ids.contains(next)
        if isNewState && ids.size >= maxStates then truncated = true
        else
          transitions((fromId, symbol)) = idFor(next)
          if isNewState then queue += next

    val accepting = ids.collect { case (summary, id) if diagonal(automaton, summary)(automaton.source.initialState) => id }.toSet
    ReachableDfa(ids.size, VectorMap.from(transitions), accepting, initial, truncated)

  /** Graphviz DOT rendering of `reachable(automaton, maxStates)`. */
  def toDot(automaton: ReverseBooleanAutomaton, name: String = "boolean_automaton", maxStates: Int = 512): String =
    def quote(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    def nodeName(id: Int): String = s"s$id"

    val dfa = reachable(automaton, maxStates)

    val edges = scala.collection.mutable.LinkedHashMap.empty[(Int, Int), scala.collection.mutable.ArrayBuffer[String]]
    for ((from, symbol), to) <- dfa.transitions do
      edges.getOrElseUpdate((from, to), scala.collection.mutable.ArrayBuffer.empty) += symbol

    val lines = scala.collection.mutable.ArrayBuffer.empty[String]
    lines += s"digraph $name {"
    lines += "  rankdir=LR;"
    lines += "  node [shape=circle, fontname=\"monospace\"];"
    lines += "  edge [fontname=\"monospace\", fontsize=10];"
    lines += "  __start__ [shape=point];"
    lines += s"  __start__ -> ${quote(nodeName(dfa.initial))};"
    for id <- dfa.accepting.toList.sorted do lines += s"  ${quote(nodeName(id))} [shape=doublecircle];"
    for ((from, to), symbols) <- edges do
      lines += s"  ${quote(nodeName(from))} -> ${quote(nodeName(to))} [label=${quote(symbols.mkString(","))}];"
    if dfa.truncated then
      lines += s"""  __truncated__ [shape=note, label=${quote(s"reachable states exceed $maxStates; truncated")}];"""
    lines += "}"
    lines.mkString("\n")

  def toJson(automaton: ReverseBooleanAutomaton): JsonValue =
    JObj(
      Vector(
        "kind" -> str("reverse-boolean-automaton"),
        "alphabet" -> JArr(automaton.source.alphabet.map(str).toVector),
        "pvwaa_state_count" -> JsonValue.int(automaton.source.states.length),
        "goto_support" -> JArr(automaton.gotoSupport.map(str).toVector),
        "abstraction_count" -> JsonValue.int(automaton.abstractions.length),
        "maximum_state_count" -> JsonValue.bigInt(maximumStateCount(automaton)),
        "initial_summary" -> JArr(automaton.initial.table.map(row => JArr(row.map(bool).toVector)).toVector),
      )
    )
