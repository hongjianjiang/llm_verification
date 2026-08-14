package brasp

/** Boolean-summary automata obtained by reversing a forward PVWAA.
  *
  * The state is a Boolean summary `S : Q x B^G -> B`, where `G` is the
  * goto-support. This is the directional mirror of Section 9.2 of the paper.
  * For a forward PVWAA recognizing `reverse(L)`, the resulting automaton
  * reads a word in `L` from left to right.
  *
  * A state `q`'s summary function only actually varies with a goto-support
  * bit `g` if `q` directly `goto`-references `g`, or `leave`-references
  * (transitively, since `leave` re-reads the same abstraction) some other
  * state whose summary varies with `g`; `carry` never contributes a
  * dependency, since it only reads a plain diagonal scalar. In practice this
  * per-state "local support" (`ReverseBooleanAutomaton.support`) is far
  * smaller than the automaton-wide `gotoSupport`, so every table here is
  * indexed per-state by that state's own local support rather than by one
  * automaton-wide `2^|gotoSupport|` abstraction space — the latter is
  * astronomical even for modest automata (Prop. 9.4) despite most of it
  * being irrelevant to any single state.
  */

/** Per-state Boolean summary: `table(state)` is a total truth table over
  * `ReverseBooleanAutomaton.support(state)`, indexed by the binary encoding
  * of a local abstraction (MSB-first, matching `cartesianBooleans`'s
  * enumeration order — see `BooleanAutomaton.bitsToIndex`).
  */
final case class BooleanSummary(table: Map[String, Vector[Boolean]])

/** A deterministic Boolean automaton that reads the original language.
  *
  * `source` reads the reversed language left-to-right. This automaton reads
  * the source word in the opposite direction, hence left-to-right on the
  * original word. It materializes one Boolean-summary state at a time.
  *
  * `gotoSupport` is the automaton-wide set of every state ever reached by a
  * `goto` transition (rank order); `support(state)` is the subsequence of
  * `gotoSupport` that `state`'s own summary function actually depends on.
  * `supportIndex(state)` is `support(state).zipWithIndex.toMap`, precomputed
  * once so every `goto`/`leave` bit lookup is an O(1) map read instead of an
  * O(|support(state)|) `List.indexOf` scan repeated across `2^|support|`
  * abstractions and every BFS step of `reachable`/`accepts`.
  */
final case class ReverseBooleanAutomaton(
    source: ForwardPVWAA,
    gotoSupport: List[String],
    support: Map[String, List[String]],
    supportIndex: Map[String, Map[String, Int]],
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

  // `transition` calls `cartesianBooleans(support(state).length)` fresh for
  // every state on every call — and `transition` itself runs once per
  // step of `accepts`/`reachable`'s BFS (up to `maxStates x alphabet`
  // times). Without caching, a state with a support of even ~20 turns
  // into rebuilding a multi-hundred-thousand-element list from scratch on
  // every single one of those calls; `n` is the only input, so the result
  // is safe to memoize globally, once, across every automaton. Each
  // abstraction is a `Vector`, not a `List`: `evaluate` (below) indexes
  // into it once per `goto`/`leave` atom per abstraction, and `List(i)` is
  // itself an O(i) scan — the exact same trap `supportIndex` exists to
  // avoid one level up.
  private val cartesianCache = scala.collection.mutable.HashMap.empty[Int, List[Vector[Boolean]]]

  private def cartesianBooleans(n: Int): List[Vector[Boolean]] =
    cartesianCache.getOrElseUpdate(
      n,
      if n == 0 then List(Vector.empty) else for first <- List(false, true); rest <- cartesianBooleans(n - 1) yield first +: rest,
    )

  /** Binary-encode a local abstraction into a table index, MSB-first — the
    * same weighting `cartesianBooleans`'s enumeration order and every
    * backend's `mux`/`ite` selector tree already assume.
    */
  private def bitsToIndex(bits: List[Boolean]): Int =
    bits.foldLeft(0)((acc, bit) => (acc << 1) | (if bit then 1 else 0))

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

  /** The `goto`/`leave` states each state's own transition formulas mention
    * directly (union across every input symbol).
    */
  private def directTargets(automaton: ForwardPVWAA): Map[String, (Set[String], Set[String])] =
    def atoms(formula: PositiveFormula, goto: scala.collection.mutable.Set[String], leave: scala.collection.mutable.Set[String]): Unit =
      formula match
        case PositiveFormula.TransitionAtom(state, Action.Goto)  => goto += state
        case PositiveFormula.TransitionAtom(state, Action.Leave) => leave += state
        case _: PositiveFormula.TransitionAtom                   => ()
        case PositiveFormula.PositiveAnd(operands)                => operands.foreach(atoms(_, goto, leave))
        case PositiveFormula.PositiveOr(operands)                 => operands.foreach(atoms(_, goto, leave))
        case PositiveFormula.PositiveConstant(_)                  => ()
    automaton.states.map { state =>
      val goto = scala.collection.mutable.Set.empty[String]
      val leave = scala.collection.mutable.Set.empty[String]
      for symbol <- automaton.alphabet do atoms(automaton.transitions((state, symbol)), goto, leave)
      state -> (goto.toSet, leave.toSet)
    }.toMap

  /** `support(state)`: the goto-support states whose bit actually affects
    * `state`'s summary — direct `goto` targets, plus (transitively) the
    * support of every `leave` target. Computed as a least fixpoint over the
    * (possibly cyclic, e.g. self-loop `leave`) target graph, then ordered
    * as a subsequence of `gotoSupport` so a `leave`-referenced state's
    * (smaller) local abstraction can be recovered from its referrer's by
    * simple index lookup — see `BooleanAutomaton.transition`.
    */
  private def supportOf(automaton: ForwardPVWAA, gotoSupport: List[String]): Map[String, List[String]] =
    val targets = directTargets(automaton)
    var supportSets = automaton.states.map(state => state -> targets(state)._1).toMap
    var changed = true
    while changed do
      changed = false
      for state <- automaton.states do
        val leaveTargets = targets(state)._2
        val merged = supportSets(state) ++ leaveTargets.flatMap(supportSets)
        if merged.size != supportSets(state).size then
          supportSets = supportSets.updated(state, merged)
          changed = true
    automaton.states.map(state => state -> gotoSupport.filter(supportSets(state))).toMap

  /** Every state's local Boolean-summary table has `2^support(state).length`
    * entries (`BooleanAutomaton.fromForwardPvwaa`'s `Vector.fill`,
    * mirrored by every backend's own per-state table/mux construction).
    * Beyond a couple dozen dependencies that's already tens of millions of
    * entries — impractical to materialize — and beyond 31 it silently
    * overflows `Int`'s `1 << n` (wrapping, or going negative), corrupting
    * the table size instead of just being slow. Reject early with a clear
    * message rather than let either failure mode surface as a confusing
    * crash or hang deep in `diagonal`/`transition`.
    */
  private val maxLocalSupport = 24

  private def checkSupportSize(support: Map[String, List[String]]): Unit =
    for (state, deps) <- support if deps.length > maxLocalSupport do
      throw PVWAAError(
        s"state '$state' depends on ${deps.length} other goto-support states, exceeding this backend's per-state limit of $maxLocalSupport " +
          s"(its Boolean-summary table would need 2^${deps.length} entries) — the automaton's goto/leave dependency structure is too tangled for this encoding"
      )

  /** Construct the initial Boolean summary for the reversed automaton. */
  def fromForwardPvwaa(automaton: ForwardPVWAA): ReverseBooleanAutomaton =
    val gotoSupport = gotoSupportOf(automaton)
    val support = supportOf(automaton, gotoSupport)
    checkSupportSize(support)
    val supportIndex = support.view.mapValues(_.zipWithIndex.toMap).toMap
    val initial = BooleanSummary(
      automaton.states.map { state =>
        state -> Vector.fill(1 << support(state).length)(automaton.finalStates.contains(state))
      }.toMap
    )
    ReverseBooleanAutomaton(automaton, gotoSupport, support, supportIndex, initial)

  /** Solve `V(q) = S(q, V|G)` bottom-up along the very-weak order. */
  def diagonal(automaton: ReverseBooleanAutomaton, summary: BooleanSummary): Map[String, Boolean] =
    val ordered = automaton.source.states.sortBy(state => (automaton.source.rank(state), state))
    var result = Map.empty[String, Boolean]
    for state <- ordered do
      // By very weakness, this row can inspect only strictly lower-ranked
      // goto states. Values for the remaining support are don't-cares.
      val bits = automaton.support(state).map(goto => result.getOrElse(goto, false))
      result = result.updated(state, summary.table(state)(bitsToIndex(bits)))
    result

  /** Consume one original-language symbol and return the next summary. */
  def transition(automaton: ReverseBooleanAutomaton, summary: BooleanSummary, symbol: String): BooleanSummary =
    if !automaton.source.alphabet.contains(symbol) then
      throw PVWAAError(s"symbol outside automaton alphabet: '$symbol'")
    val priorDiagonal = diagonal(automaton, summary)

    // `ownerAbstraction` is indexed by `automaton.support(owner)` — the
    // formula being evaluated always belongs to `owner`. A `leave`/`goto`
    // atom's target is always in `owner`'s support by construction of
    // `supportOf`, so the `automaton.supportIndex(owner)` lookups below
    // never miss.
    def evaluate(owner: String, ownerIndex: Map[String, Int], ownerAbstraction: Vector[Boolean], formula: PositiveFormula): Boolean = formula match
      case PositiveFormula.PositiveConstant(value) => value
      case PositiveFormula.PositiveAnd(operands)    => operands.forall(o => evaluate(owner, ownerIndex, ownerAbstraction, o))
      case PositiveFormula.PositiveOr(operands)     => operands.exists(o => evaluate(owner, ownerIndex, ownerAbstraction, o))
      case PositiveFormula.TransitionAtom(state, action) =>
        action match
          case Action.Carry => priorDiagonal(state)
          case Action.Leave =>
            val bits = automaton.support(state).map(g => ownerAbstraction(ownerIndex(g)))
            summary.table(state)(bitsToIndex(bits))
          case Action.Goto => ownerAbstraction(ownerIndex(state))

    val table = automaton.source.states.map { state =>
      val formula = automaton.source.transitions((state, symbol))
      val index = automaton.supportIndex(state)
      state -> cartesianBooleans(automaton.support(state).length).map(abstraction => evaluate(state, index, abstraction, formula)).toVector
    }.toMap
    BooleanSummary(table)

  /** Run the Boolean automaton left-to-right on the original word. */
  def accepts(automaton: ReverseBooleanAutomaton, word: IndexedSeq[String]): Boolean =
    val finalSummary = word.foldLeft(automaton.initial)((summary, symbol) => transition(automaton, summary, symbol))
    diagonal(automaton, finalSummary)(automaton.source.initialState)

  /** Total number of independent Boolean cells across every state's local
    * summary table — the size of the (never-materialized) full
    * Boolean-summary state space is `2` to this power.
    */
  private def totalCells(automaton: ReverseBooleanAutomaton): BigInt =
    automaton.source.states.map(state => BigInt(2).pow(automaton.support(state).length)).sum

  private def maximumStateCount(automaton: ReverseBooleanAutomaton): BigInt =
    val cells = totalCells(automaton).min(BigInt(Int.MaxValue))
    BigInt(2).pow(cells.toInt)

  def render(automaton: ReverseBooleanAutomaton): String =
    List(
      "Reverse Boolean automaton (reads the original language left-to-right)",
      s"PVWAA states: ${automaton.source.states.length}",
      s"goto support: {${automaton.gotoSupport.mkString(", ")}}",
      s"max per-state local support: ${automaton.support.values.map(_.length).maxOption.getOrElse(0)}",
      s"Boolean-summary cells: ${totalCells(automaton)}",
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
        "support" -> JObj(automaton.source.states.map(state => state -> JArr(automaton.support(state).map(str).toVector)).toVector),
        "cell_count" -> JsonValue.bigInt(totalCells(automaton)),
        "maximum_state_count" -> JsonValue.bigInt(maximumStateCount(automaton)),
        "initial_summary" -> JObj(automaton.source.states.map(state => state -> JArr(automaton.initial.table(state).map(bool).toVector)).toVector),
      )
    )
