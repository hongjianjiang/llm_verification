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

/** Per-state Boolean summary: `table(state)` is `state`'s current summary
  * function, represented as a node in `ReverseBooleanAutomaton.bddManager`'s
  * shared ROBDD (see `Bdd.scala`) rather than an explicit truth table —
  * `state`'s function is defined over (a subset of) the *global*
  * `gotoSupport` variables, not a per-state-local index space, which is
  * exactly what lets `transition`'s `Leave` case below reuse another
  * state's node as-is.
  */
final case class BooleanSummary(table: Map[String, Bdd.Node])

/** A deterministic Boolean automaton that reads the original language.
  *
  * `source` reads the reversed language left-to-right. This automaton reads
  * the source word in the opposite direction, hence left-to-right on the
  * original word. It materializes one Boolean-summary state at a time.
  *
  * `gotoSupport` is the automaton-wide set of every state ever reached by a
  * `goto` transition (rank order) — also `bddManager`'s fixed variable
  * order. `support(state)` is the subsequence of `gotoSupport` that
  * `state`'s own summary function *syntactically* depends on (via its
  * `goto`/`leave` references); `supportIndex(state)` is
  * `support(state).zipWithIndex.toMap`. Both are kept only for the
  * legacy per-backend (`Aiger`) explicit-table
  * encoder, which still enumerates `2^|support(state)|` abstractions
  * themselves — `BooleanAutomaton`'s own `diagonal`/`transition`/
  * `reachable`/`accepts` no longer need either field, since a BDD node
  * already only ever tests the variables it actually (semantically)
  * depends on.
  */
final case class ReverseBooleanAutomaton(
    source: ForwardPVWAA,
    gotoSupport: List[String],
    support: Map[String, List[String]],
    supportIndex: Map[String, Map[String, Int]],
    bddManager: Bdd.Manager,
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

  private def gotoSupportOf(automaton: ForwardPVWAA): List[String] =
    var found = Set.empty[String]
    def visit(formula: PositiveFormula): Unit = formula match
      case PositiveFormula.TransitionAtom(state, Action.Goto) => found += state
      case _: PositiveFormula.TransitionAtom                  => ()
      case PositiveFormula.PositiveAnd(operands)               => operands.foreach(visit)
      case PositiveFormula.PositiveOr(operands)                => operands.foreach(visit)
      case PositiveFormula.PositiveConstant(_)                 => ()
      case _: PositiveFormula.SymbolTest                       => ()
    automaton.transitions.values.foreach(visit)
    found.toList.sortBy(state => (automaton.rank(state), state))

  /** The `goto`/`leave` states each state's own transition formula mentions
    * directly. `PositiveFormula.SymbolTest` leaves don't hold `goto`/`leave`
    * references, so — unlike when this scanned once per alphabet symbol —
    * one pass over each state's single formula already finds every
    * reference regardless of which symbol it fires on.
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
        case _: PositiveFormula.SymbolTest                        => ()
    automaton.states.map { state =>
      val goto = scala.collection.mutable.Set.empty[String]
      val leave = scala.collection.mutable.Set.empty[String]
      atoms(automaton.transitions(state), goto, leave)
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

  /** Every state's local Boolean-summary table would have
    * `2^support(state).length` entries under the *explicit* per-state
    * table/mux construction `Aiger` still builds for hardware output.
    * Beyond a couple dozen dependencies that's already tens of millions of
    * entries — impractical to materialize — and beyond 31 it silently
    * overflows `Int`'s `1 << n` (wrapping, or going negative), corrupting
    * the table size instead of just being slow. `Aiger` calls this
    * explicitly, before doing any of that expensive work, to reject early
    * with a clear message rather than let either failure mode surface as a
    * confusing crash or hang.
    *
    * `BooleanAutomaton`'s own `diagonal`/`transition`/`reachable`/`accepts`
    * do *not* call this — they build each state's summary as a
    * (hash-consed, reduction-collapsed) `Bdd.Node` instead of an explicit
    * table, so their cost tracks the function's actual complexity, not the
    * syntactic dependency count this checks. `Bdd.Manager`'s own
    * `maxNodes` cap is their equivalent safety valve.
    */
  private val maxLocalSupport = 24

  /** Even when no single state's local support exceeds `maxLocalSupport`,
    * the *sum* across states (`totalCells`, i.e. `N` in
    * `docs/boolean-automaton-construction.tex`) can still be large enough
    * that materializing every cell's `alphabet.length`-way symbol case
    * (one `transitionFormula` evaluation per `(cell, symbol)` pair, in
    * `Aiger`'s `symbolCase`) is impractical — measured on
    * an `ltl_examples/OrderedSequence` benchmark with 130 states none of
    * which individually exceeded a local support of 16, `N x
    * alphabetSize` reached ~21 million cell-symbol evaluations, enough to
    * exhaust a 6 GB heap outright (not a slow run — an actual
    * `OutOfMemoryError`), well before any single state came close to
    * `maxLocalSupport`. This cap catches that aggregate case with a
    * clean, immediate error instead of a crash that can arrive minutes
    * into construction.
    *
    * The bound has to protect the *consumer*, not just this process's own
    * heap: `two_var/monotone_past/sigma=16` (34 states, local support up
    * to 15 — nowhere near `maxLocalSupport`) reaches `totalWork` ≈
    * 1,049,088, comfortably under the old 2,000,000 cap, so `checkSupportSize`
    * let it through. Construction itself doesn't outright OOM (it finishes
    * given an 8 GB heap, emitting a ~1.07M-line AIGER model), but with the
    * JVM's *default* heap it takes ~116s before an `OutOfMemoryError`, and
    * the equivalent BTOR2 model is bad enough that rIC3 — a separate
    * process — crashes reading it with a native stack overflow after
    * ~150s. Neither failure is this check's job to let happen: a
    * borderline-permitted encoding that only "works" with a heap most
    * environments don't have, and that breaks a downstream solver outright,
    * is exactly what this cap exists to reject up front instead. The very
    * next alphabet size (`sigma=17`, `totalWork` ≈ 2,228,802) already falls
    * back cleanly to the bounded `reachable`-DFA search and finishes in
    * under a second — proof this formula family was never actually hard,
    * just wrongly routed at `sigma=16`. Lowered accordingly, with headroom
    * below the largest `sigma` that measured cheap (`sigma=15`, `totalWork`
    * ≈ 491,970).
    */
  private val maxTotalWork = BigInt(500_000)

  def checkSupportSize(automaton: ReverseBooleanAutomaton): Unit =
    for (state, deps) <- automaton.support if deps.length > maxLocalSupport do
      throw PVWAAError(
        s"state '$state' depends on ${deps.length} other goto-support states, exceeding this backend's per-state limit of $maxLocalSupport " +
          s"(its Boolean-summary table would need 2^${deps.length} entries) — the automaton's goto/leave dependency structure is too tangled for this encoding"
      )
    val cells = totalCells(automaton)
    val totalWork = cells * automaton.source.alphabet.length
    if totalWork > maxTotalWork then
      throw PVWAAError(
        s"this automaton's explicit encoding would need $cells Boolean-summary cells x ${automaton.source.alphabet.length} alphabet symbols = $totalWork cell-symbol evaluations, " +
          s"exceeding this backend's aggregate limit of $maxTotalWork — even though no single state's local support exceeds $maxLocalSupport, too many individually-tangled states combine into an impractical total for this encoding"
      )

  /** Construct the initial Boolean summary for the reversed automaton. */
  def fromForwardPvwaa(automaton: ForwardPVWAA): ReverseBooleanAutomaton =
    val gotoSupport = gotoSupportOf(automaton)
    val support = supportOf(automaton, gotoSupport)
    val supportIndex = support.view.mapValues(_.zipWithIndex.toMap).toMap
    val bddManager = Bdd.Manager(gotoSupport)
    // Every entry of the old explicit table was the same constant
    // (`Vector.fill(1 << n)(finalStates.contains(state))`) — the initial
    // summary doesn't actually depend on any goto-support bit yet, so it's
    // exactly the corresponding BDD terminal, not a variable-testing node.
    val initial = BooleanSummary(
      automaton.states.map(state => state -> (if automaton.finalStates.contains(state) then Bdd.True else Bdd.False)).toMap
    )
    ReverseBooleanAutomaton(automaton, gotoSupport, support, supportIndex, bddManager, initial)

  /** Solve `V(q) = S(q, V|G)` bottom-up along the very-weak order. */
  def diagonal(automaton: ReverseBooleanAutomaton, summary: BooleanSummary): Map[String, Boolean] =
    val ordered = automaton.source.states.sortBy(state => (automaton.source.rank(state), state))
    var result = Map.empty[String, Boolean]
    for state <- ordered do
      // By very weakness, this evaluation can inspect only strictly
      // lower-ranked goto states. Values for the remaining support are
      // don't-cares (`eval` defaults to `false`, same as the old lookup).
      val value = automaton.bddManager.eval(summary.table(state), goto => result.getOrElse(goto, false))
      result = result.updated(state, value)
    result

  /** Consume one original-language symbol and return the next summary. */
  def transition(automaton: ReverseBooleanAutomaton, summary: BooleanSummary, symbol: String): BooleanSummary =
    if !automaton.source.alphabet.contains(symbol) then
      throw PVWAAError(s"symbol outside automaton alphabet: '$symbol'")
    val priorDiagonal = diagonal(automaton, summary)
    val mgr = automaton.bddManager

    // Builds `formula`'s BDD directly over the *global* goto-support
    // variables — no per-owner local abstraction/index bookkeeping needed
    // at all, unlike the old explicit-table `evaluate`: `Goto` is simply
    // that state's shared BDD variable, and `Leave` simply reuses that
    // state's already-built `Node` from `summary` unchanged (same manager,
    // same variable identities, so no projection/remapping is needed).
    def build(formula: PositiveFormula): Bdd.Node = formula match
      case PositiveFormula.PositiveConstant(value) => if value then Bdd.True else Bdd.False
      case PositiveFormula.SymbolTest(kind, sym, dual) =>
        if Ltl.symbolMatches(kind, sym, symbol) != dual then Bdd.True else Bdd.False
      case PositiveFormula.PositiveAnd(operands)    => mgr.and(operands.map(build))
      case PositiveFormula.PositiveOr(operands)     => mgr.or(operands.map(build))
      case PositiveFormula.TransitionAtom(state, action) =>
        action match
          case Action.Carry => if priorDiagonal(state) then Bdd.True else Bdd.False
          case Action.Leave => summary.table(state)
          case Action.Goto  => mgr.variable(state)

    val table = automaton.source.states.map { state =>
      val formula = automaton.source.transitions(state)
      state -> build(formula)
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

  /** `2^cells` as a *string*, never as a materialized `BigInt` — `cells`
    * itself can legitimately reach into the millions for the exact formulas
    * this file's `Bdd`-based rewrite targets, and `BigInt(2).pow(millions)`
    * would need to actually allocate (and `render`/`toJson` would then have
    * to decimal-format) a number with proportionally many digits. Printing
    * the exponent instead is exact, human-readable, and can never be slow.
    */
  private def maximumStateCountLabel(automaton: ReverseBooleanAutomaton): String =
    s"2^${totalCells(automaton)}"

  def render(automaton: ReverseBooleanAutomaton): String =
    List(
      "Reverse Boolean automaton (reads the original language left-to-right)",
      s"PVWAA states: ${automaton.source.states.length}",
      s"goto support: {${automaton.gotoSupport.mkString(", ")}}",
      s"max per-state local support: ${automaton.support.values.map(_.length).maxOption.getOrElse(0)}",
      s"Boolean-summary cells (worst case, pre-BDD-reduction): ${totalCells(automaton)}",
      s"maximum Boolean-summary states: ${maximumStateCountLabel(automaton)}",
      "transition: Section 9 summary recurrence, evaluated on demand via a shared ROBDD (Bdd.scala)",
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
    // Stop as soon as `truncated` is set, both across states and within a
    // single state's own symbols: a truncated `transitions` table is
    // already documented as unusable as-is (`generateSafetyFromDfa`/
    // `minimize` both reject it outright), so continuing to explore more
    // of it past that point is pure wasted `transition` calls — e.g. once
    // a large-alphabet automaton has clearly blown past `maxStates` on its
    // very first state, there's no reason to keep computing that state's
    // remaining `alphabet.length` transitions one by one.
    while queue.nonEmpty && !truncated do
      val current = queue.dequeue()
      val fromId = idFor(current)
      val symbols = automaton.source.alphabet.iterator
      while symbols.hasNext && !truncated do
        val symbol = symbols.next()
        val next = transition(automaton, current, symbol)
        val isNewState = !ids.contains(next)
        if isNewState && ids.size >= maxStates then truncated = true
        else
          transitions((fromId, symbol)) = idFor(next)
          if isNewState then queue += next

    val accepting =
      if truncated then Set.empty[Int]
      else ids.collect { case (summary, id) if diagonal(automaton, summary)(automaton.source.initialState) => id }.toSet
    ReachableDfa(ids.size, VectorMap.from(transitions), accepting, initial, truncated)

  /** Shortest nonempty word (if any) that reaches an accepting state of
    * `dfa` from its initial state — a BFS over the already-materialized
    * `transitions` table, entirely separate from `reachable`'s own BFS.
    * This is what lets `--run-native` report a concrete counterexample
    * without needing an external solver's witness extraction: `dfa` is
    * small and explicit by the time this runs, so a second linear BFS over
    * it is negligible next to the cost of building it in the first place.
    * Only meaningful when `dfa.initial` itself isn't accepting (that case —
    * the empty word — is instead reported separately, the same
    * "compile-time constant" way every other backend already does).
    */
  def witness(dfa: ReachableDfa, alphabet: List[String]): Option[List[String]] =
    val parent = scala.collection.mutable.HashMap.empty[Int, (Int, String)]
    val visited = scala.collection.mutable.Set(dfa.initial)
    val queue = scala.collection.mutable.Queue(dfa.initial)
    var target: Option[Int] = None // the empty word is reported separately by the caller, not here
    while queue.nonEmpty && target.isEmpty do
      val state = queue.dequeue()
      val symbols = alphabet.iterator
      while symbols.hasNext && target.isEmpty do
        val symbol = symbols.next()
        dfa.transitions.get((state, symbol)).foreach { next =>
          if !visited.contains(next) then
            visited += next
            parent(next) = (state, symbol)
            if dfa.accepting.contains(next) then target = Some(next)
            else queue += next
        }
    target.map { goal =>
      val path = scala.collection.mutable.ArrayBuffer.empty[String]
      var current = goal
      while current != dfa.initial do
        val (previous, symbol) = parent(current)
        path += symbol
        current = previous
      path.reverse.toList
    }

  /** Result of `conflictWitness`: `witness` is the shortest-found (not
    * necessarily shortest-possible, unlike `witness` over an already-built
    * `ReachableDfa`) accepting word, `statesVisited` the number of distinct
    * summaries actually explored, `truncated` whether `maxStates` cut the
    * search off before it could prove emptiness.
    */
  final case class ConflictResult(witness: Option[List[String]], statesVisited: Int, truncated: Boolean)

  /** The static closure of `initialState` under every `TransitionAtom`
    * edge (`Goto`/`Carry`/`Leave` alike) in the *original* PVWAA
    * transition-formula graph — i.e. every state `initialState`'s own
    * acceptance could ever depend on, now or at any future step, since
    * that formula graph never changes shape across steps (only which
    * concrete `Bdd.Node` each state's entry holds changes).
    *
    * In practice this is close to exactly half of `source.states`:
    * `Pvwaa` allocates *both* polarities (`dual = true/false`) of every
    * named definition, but a real formula typically only ever needs one
    * polarity of each name transitively from its own top-level state —
    * measured on the `dot_depth`/`y_depth` benchmark families, this
    * closure is exactly `states.length / 2` at every k tried, from k=9
    * (46 states, 23 relevant) up to k=800 (3210 states, 1605 relevant).
    * `conflictWitness` uses this to project away exactly that dead weight
    * when deciding whether two summaries are equivalent.
    */
  private def relevantStates(automaton: ForwardPVWAA): Set[String] =
    def targetsOf(formula: PositiveFormula): List[String] = formula match
      case PositiveFormula.TransitionAtom(state, _) => List(state)
      case PositiveFormula.PositiveAnd(operands)     => operands.flatMap(targetsOf)
      case PositiveFormula.PositiveOr(operands)      => operands.flatMap(targetsOf)
      case _                                          => Nil
    val seen = scala.collection.mutable.LinkedHashSet(automaton.initialState)
    val queue = scala.collection.mutable.Queue(automaton.initialState)
    while queue.nonEmpty do
      for target <- targetsOf(automaton.transitions(queue.dequeue())) if seen.add(target) do queue += target
    seen.toSet

  /** On-the-fly emptiness/witness search over `ReverseBooleanAutomaton`: a
    * single depth-first walk from `automaton.initial`, in the spirit of
    * Li/Rozier/Pu/Zhang/Vardi's CDLSC ("SAT-based Explicit LTLf
    * Satisfiability Checking", AAAI'19) — build only the part of the state
    * space actually needed to answer the question, and remember every
    * summary already proven to have no reachable final configuration
    * (`dead`) so a shared successor reached by more than one path is never
    * re-explored.
    *
    * `dead`/`onStack` are keyed not by the raw `BooleanSummary` but by its
    * *projection* onto `relevantStates(automaton.source)` — this is this
    * function's own analogue of CDLSC's unsat-core generalization, just
    * computed statically from the formula graph rather than extracted
    * per-query from a SAT solver: two summaries that agree on every
    * relevant state are provably interchangeable for every purpose this
    * search cares about, by induction on `transition`:
    *
    *   - `isFinal` only ever reads `diagonal(...)(initialState)`, and
    *     `diagonal`'s value for any relevant state only actually depends
    *     (through `Bdd.eval`'s variable substitution) on other relevant
    *     states' entries — irrelevant entries are computed too (`diagonal`
    *     sweeps every state) but never *read* by anything relevant.
    *   - `transition`'s `build` produces a relevant state's *next* entry
    *     from: `priorDiagonal` of a `Carry` target (relevant, by the same
    *     argument, and a function of only relevant `diagonal` values),
    *     `summary.table` of a `Leave` target directly (relevant, and
    *     already assumed equal), or a `Goto` target's `Bdd` variable (a
    *     fixed name, trivially equal across any two summaries).
    *
    * So relevant-projection equality is preserved by `transition` for
    * every symbol, forever — a genuine bisimulation with respect to
    * `isFinal`, not just a same-step heuristic. That also justifies using
    * it for `onStack` (cycle detection), not only `dead`: re-entering a
    * projection-equal state while its first occurrence is still being
    * explored is exactly as redundant as re-entering the identical state
    * would be, since every future step behaves identically either way.
    *
    * Without this, two summaries differing only on some irrelevant
    * state's entry are spuriously distinct `dead`/`onStack` keys even
    * though they behave identically — and they routinely do differ there,
    * since `transition` recomputes *every* state's entry each step
    * (`diagonal`/`transition` don't skip irrelevant states), including
    * whichever fresh `Bdd.Node` an irrelevant state's own `SymbolTest`
    * happens to produce for the symbol just consumed.
    *
    * Soundness of treating a cycle back to an in-progress ancestor
    * (`onStack`) as "no new information" — rather than marking it dead —
    * is the standard explicit-state reachability argument: the ancestor's
    * own call already tries every one of its own successors directly, so
    * nothing reachable through the cycle is missed by not re-descending
    * into it.
    *
    * `automaton.initial` itself is explored for free, matching
    * `reachable`'s own convention that the initial state doesn't count
    * against `maxStates` — only states discovered *from* it do.
    */
  def conflictWitness(automaton: ReverseBooleanAutomaton, maxStates: Int = 4096): ConflictResult =
    val relevant = relevantStates(automaton.source)
    def key(summary: BooleanSummary): Map[String, Bdd.Node] = summary.table.view.filterKeys(relevant).toMap

    val dead = scala.collection.mutable.HashSet.empty[Map[String, Bdd.Node]]
    val onStack = scala.collection.mutable.HashSet.empty[Map[String, Bdd.Node]]
    var visited = 0
    var truncated = false
    def isFinal(summary: BooleanSummary): Boolean = diagonal(automaton, summary)(automaton.source.initialState)

    def explore(summary: BooleanSummary): Option[List[String]] =
      val summaryKey = key(summary)
      onStack += summaryKey
      var found: Option[List[String]] = None
      val symbols = automaton.source.alphabet.iterator
      while found.isEmpty && symbols.hasNext && !truncated do
        val symbol = symbols.next()
        val next = transition(automaton, summary, symbol)
        val nextKey = key(next)
        found =
          if isFinal(next) then Some(List(symbol))
          else if dead.contains(nextKey) || onStack.contains(nextKey) then None
          else if visited >= maxStates then
            truncated = true
            None
          else
            visited += 1
            explore(next).map(symbol :: _)
      onStack -= summaryKey
      if found.isEmpty && !truncated then dead += summaryKey
      found

    val witness = if isFinal(automaton.initial) then Some(Nil) else explore(automaton.initial)
    ConflictResult(witness, visited, truncated)

  /** Merge every distinct label in `labels` to a small int, assigned in
    * first-occurrence order — the "give each partition block a stable id"
    * step `minimize` needs twice (once for the initial accepting/rejecting
    * split, once per refinement round).
    */
  private def renumber[A](labels: IndexedSeq[A]): (IndexedSeq[Int], Int) =
    val ids = scala.collection.mutable.LinkedHashMap.empty[A, Int]
    val assigned = labels.map(label => ids.getOrElseUpdate(label, ids.size))
    (assigned, ids.size)

  /** Minimize `dfa` via Moore's partition-refinement algorithm: merge every
    * pair of states that accept exactly the same set of future suffixes
    * into one. `dfa` must not be `truncated` — a partial transition table
    * can't be soundly minimized, since a missing transition could hide a
    * real distinction between two states that only manifests past the cap.
    *
    * This targets the same goal as an antichain/simulation-based state
    * reduction during exploration (Abdulla et al., "When Simulation Meets
    * Antichains", TACAS'10) — shrink a reachable state space down to only
    * the states that matter — but takes the exact route: standard
    * bisimulation-equivalence minimization on an already-materialized DFA,
    * rather than an on-the-fly simulation preorder while exploring it. It's
    * strictly weaker (it can't prune what `reachable` never finishes
    * exploring), but its soundness is the textbook DFA-minimization
    * argument rather than a new preorder that would need its own proof.
    */
  def minimize(dfa: ReachableDfa, alphabet: List[String]): ReachableDfa =
    if dfa.truncated then
      throw PVWAAError(
        "cannot minimize a truncated reachable-state exploration — a missing transition could hide a real distinction between two states"
      )

    // Every round below assigns block ids in the order this sequence is
    // walked (first-occurrence order, via `renumber`) — putting `initial`
    // first therefore guarantees its block is *always* id 0, in every
    // round, preserving `ReachableDfa`'s own documented invariant
    // ("initial is always 0") in the output. That invariant isn't optional
    // bookkeeping: `Aiger.generateSafetyFromDfa` never reads `.initial` at
    // all — it relies on the physical all-zero latch state already being
    // the initial one — so a minimizer that renumbered some other block to
    // 0 would silently reset the emitted circuit to the wrong state.
    val order: Vector[Int] = dfa.initial +: (0 until dfa.stateCount).filterNot(_ == dfa.initial).toVector
    def renumberByState(labelOf: Int => Any): (Map[Int, Int], Int) =
      val (assigned, count) = renumber(order.map(labelOf))
      (order.zip(assigned).toMap, count)

    var (blockOf, blockCount) = renumberByState(state => dfa.accepting.contains(state))
    var changed = true
    while changed do
      val (nextBlockOf, nextBlockCount) = renumberByState(state => (blockOf(state), alphabet.map(symbol => blockOf(dfa.transitions((state, symbol))))))
      changed = nextBlockCount != blockCount
      blockOf = nextBlockOf
      blockCount = nextBlockCount

    // Any one original state per block stands in for the whole block: by
    // construction every state in a block agrees on both acceptance and
    // where each symbol leads (as a block), so it doesn't matter which.
    val representative = (0 until dfa.stateCount).foldLeft(Map.empty[Int, Int])((chosen, state) => chosen.updatedWith(blockOf(state))(_.orElse(Some(state))))
    val newTransitions = for
      block <- 0 until blockCount
      symbol <- alphabet
    yield (block, symbol) -> blockOf(dfa.transitions((representative(block), symbol)))
    val newAccepting = (0 until blockCount).filter(block => dfa.accepting.contains(representative(block))).toSet
    ReachableDfa(blockCount, VectorMap.from(newTransitions), newAccepting, blockOf(dfa.initial), truncated = false)

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
        "maximum_state_count" -> str(maximumStateCountLabel(automaton)),
        // The initial summary is always a constant per state (independent
        // of goto-support, since nothing has been consumed yet) — this is
        // that constant, not a full (and, pre-rewrite, needlessly `2^n`-long)
        // per-abstraction table.
        "initial_summary" -> JObj(automaton.source.states.map(state => state -> bool(automaton.source.finalStates.contains(state))).toVector),
      )
    )
