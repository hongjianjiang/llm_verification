package brasp

/** Forward pebble very weak alternating automata for future 2LTL.
  *
  * The paper defines a right-to-left PVWAA for past 2LTL. This module is its
  * directional mirror: it starts at position 0 of a future formula's input
  * and therefore recognizes the reverse language produced by `Ltl.mirrorDag`.
  */

final case class PVWAAError(message: String) extends RuntimeException(message)

enum Action:
  case Carry, Leave, Goto

  def label: String = this match
    case Action.Carry => "carry"
    case Action.Leave => "leave"
    case Action.Goto  => "goto"

enum PositiveFormula:
  case PositiveConstant(value: Boolean)
  case TransitionAtom(state: String, action: Action)
  case PositiveAnd(operands: List[PositiveFormula])
  case PositiveOr(operands: List[PositiveFormula])

/** A one-pebble alternating automaton whose head moves left to right.
  *
  * A configuration is `(state, pebble, head)`, with `head >= pebble`.
  * `carry` moves both positions one step right, `leave` advances only the
  * head, and `goto` returns the head to the pebble. At `head == |word|`, the
  * head is on the implicit EOS boundary and acceptance is determined by
  * `finalStates`.
  */
final case class ForwardPVWAA(
    alphabet: List[String],
    states: List[String],
    transitions: Map[(String, String), PositiveFormula],
    initialState: String,
    finalStates: Set[String],
    rank: Map[String, Int],
)

object Pvwaa:
  import Formula.*
  import PositiveFormula.*
  import JsonValue.*

  /** Does `top(formula, dual, symbol, current)` actually depend on `symbol`?
    * Only a direct `Atom(SymbolAtom | BitAtom, ...)` leaf does —
    * `Reference` becomes a `Goto`/`Carry` atom regardless of `symbol`, and
    * `Until` delegates to `predicate`, which never inspects `symbol` at
    * all. Named definitions built purely from references to other names
    * are the common case for large alphabets, and are therefore fully
    * symbol-independent: `top` would otherwise rebuild an identical,
    * possibly large `PositiveFormula` tree once per alphabet symbol for
    * nothing. Anything not recognized here conservatively answers `true`
    * (forces per-symbol evaluation, which is always correct, just not
    * optimized) rather than risk caching a formula that does vary.
    */
  private def usesSymbol(formula: Formula): Boolean = formula match
    case Constant(_) | Reference(_, _)                                => false
    case Atom(AtomKind.SymbolAtom | AtomKind.BitAtom, Position.I, _)   => true
    case _: Atom                                                       => false
    case Negation(operand)                                             => usesSymbol(operand)
    case Conjunction(operands)                                         => operands.exists(usesSymbol)
    case Disjunction(operands)                                         => operands.exists(usesSymbol)
    case _: Until                                                      => false
    case _                                                             => true

  /** Rewrite `Next`/`Eventually`/`Always` into the `Until`/`Negation` terms
    * `top`, `predicate`, `finalOf`, and `symbolsOf` already handle, via the
    * standard equivalences `X φ = false U φ`, `F φ = true U φ`, and
    * `G φ = ¬F(¬φ)` — the future mirror of `LtlToBrasp.normalize`'s
    * `Y φ = false S φ`, `O φ = true S φ` for the past side (`Ltl.mirror`
    * pairs `Previous↔Next`, `Once↔Eventually`, `Historically↔Always`,
    * `Since↔Until` the same way). Without this, any strict-future DAG
    * containing a raw `Next`/`Eventually`/`Always` (e.g. from `LtlText`'s
    * `X(...)`/`F(...)`/`G(...)` parser, or `Ltlf`-compiled formulas, which
    * emit `Next`/`Eventually` directly) fails PVWAA translation even though
    * it's semantically just `Until` in disguise.
    */
  private def desugarFuture(formula: Formula): Formula = formula match
    case Constant(_) | Atom(_, _, _) | Reference(_, _) => formula
    case Negation(operand)                              => Negation(desugarFuture(operand))
    case Conjunction(operands)                           => Conjunction(operands.map(desugarFuture))
    case Disjunction(operands)                            => Disjunction(operands.map(desugarFuture))
    case Until(anchor, witness, left, right)               => Until(anchor, witness, desugarFuture(left), desugarFuture(right))
    case Next(anchor, witness, operand)                     => Until(anchor, witness, Constant(false), desugarFuture(operand))
    case Eventually(anchor, witness, operand)                => Until(anchor, witness, Constant(true), desugarFuture(operand))
    case Always(anchor, witness, operand)                     => Negation(Until(anchor, witness, Constant(true), Negation(desugarFuture(operand))))
    case other                                                 => other

  private def symbolsOf(formula: Formula): Set[String] = formula match
    case Atom(AtomKind.SymbolAtom, _, Some(symbol)) => Set(symbol)
    case _: Atom | Constant(_) | Reference(_, _)     => Set.empty
    case Negation(operand)                           => symbolsOf(operand)
    case Conjunction(operands)                        => operands.flatMap(symbolsOf).toSet
    case Disjunction(operands)                        => operands.flatMap(symbolsOf).toSet
    case Since(_, _, left, right)                      => symbolsOf(left) ++ symbolsOf(right)
    case Until(_, _, left, right)                      => symbolsOf(left) ++ symbolsOf(right)
    case other => throw PVWAAError(s"normalise unsupported temporal operator before PVWAA translation: $other")

  private def positiveRender(formula: PositiveFormula): String = formula match
    case PositiveConstant(value) => if value then "⊤" else "⊥"
    case TransitionAtom(state, action) => s"($state, ${action.label})"
    case PositiveAnd(operands) => "(" + operands.map(positiveRender).mkString(" ∧ ") + ")"
    case PositiveOr(operands)  => "(" + operands.map(positiveRender).mkString(" ∨ ") + ")"

  def render(automaton: ForwardPVWAA): String =
    val header = List(
      "Forward PVWAA (accepts the supplied word left-to-right)",
      s"alphabet: {${automaton.alphabet.mkString(", ")}} plus EOS",
      s"initial: ${automaton.initialState}",
      s"final at EOS: {${automaton.finalStates.toList.sorted.mkString(", ")}}",
      "transitions:",
    )
    val body = for
      state <- automaton.states
      symbol <- automaton.alphabet
    yield s"  δ($state, $symbol) = ${positiveRender(automaton.transitions((state, symbol)))}"
    (header ++ body).mkString("\n")

  private def positiveToJson(formula: PositiveFormula): JsonValue = formula match
    case PositiveConstant(value) => JObj(Vector("type" -> str("const"), "value" -> bool(value)))
    case TransitionAtom(state, action) =>
      JObj(Vector("type" -> str("atom"), "state" -> str(state), "action" -> str(action.label)))
    case PositiveAnd(operands) => JObj(Vector("type" -> str("and"), "args" -> JArr(operands.map(positiveToJson).toVector)))
    case PositiveOr(operands)  => JObj(Vector("type" -> str("or"), "args" -> JArr(operands.map(positiveToJson).toVector)))

  /** Graphviz DOT rendering of the transition structure.
    *
    * The PVWAA is alternating (transitions are AND/OR trees of pebble
    * actions, not single deterministic edges), so this collapses each
    * `(state, symbol)` transition to one edge per `TransitionAtom` it
    * mentions, labeled `symbol/action`, dropping the AND/OR combination
    * itself. That loses precision (see `--json` for the exact formula) but
    * gives a legible overview graph.
    */
  def toDot(automaton: ForwardPVWAA, name: String = "pvwaa"): String =
    def atomsOf(formula: PositiveFormula): List[(String, Action)] = formula match
      case PositiveConstant(_)           => Nil
      case TransitionAtom(state, action) => List(state -> action)
      case PositiveAnd(operands)          => operands.flatMap(atomsOf)
      case PositiveOr(operands)           => operands.flatMap(atomsOf)

    def quote(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    val edgeLabels = scala.collection.mutable.LinkedHashMap.empty[(String, String), scala.collection.mutable.ArrayBuffer[String]]
    for
      state <- automaton.states
      symbol <- automaton.alphabet
      (target, action) <- atomsOf(automaton.transitions((state, symbol)))
    do edgeLabels.getOrElseUpdate((state, target), scala.collection.mutable.ArrayBuffer.empty) += s"$symbol/${action.label}"

    val lines = scala.collection.mutable.ArrayBuffer.empty[String]
    lines += s"digraph $name {"
    lines += "  rankdir=LR;"
    lines += "  node [shape=circle, fontname=\"monospace\"];"
    lines += "  edge [fontname=\"monospace\", fontsize=10];"
    lines += "  __start__ [shape=point];"
    lines += s"  __start__ -> ${quote(automaton.initialState)};"
    for state <- automaton.states if automaton.finalStates.contains(state) do
      lines += s"  ${quote(state)} [shape=doublecircle];"
    for (state, target) <- edgeLabels.keys do
      // Not `quote(...)`: the joined "\n" here is an intentional DOT
      // newline escape, and `quote` would double its backslash.
      val label = edgeLabels((state, target)).mkString("\\n")
      lines += s"""  ${quote(state)} -> ${quote(target)} [label="$label"];"""
    lines += "}"
    lines.mkString("\n")

  def toJson(automaton: ForwardPVWAA): JsonValue =
    JObj(
      Vector(
        "kind" -> str("forward-pvwaa"),
        "alphabet" -> JArr(automaton.alphabet.map(str).toVector),
        "states" -> JArr(automaton.states.map(str).toVector),
        "initial_state" -> str(automaton.initialState),
        "final_states" -> JArr(automaton.finalStates.toList.sorted.map(str).toVector),
        "rank" -> JObj(automaton.rank.toVector.map { case (state, index) => state -> JsonValue.int(index) }),
        "transitions" -> JObj(automaton.transitions.toVector.map { case ((state, symbol), formula) =>
          s"$state\u0000$symbol" -> positiveToJson(formula)
        }),
      )
    )

  private def stateName(name: String, dual: Boolean): String = s"q:${if dual then "not:" else ""}$name"

  /** Compile a strict-future 2LTL DAG to the mirrored PVWAA construction.
    *
    * The accepted fragment is exactly the output of the current B-RASP
    * pipeline: Boolean formulas, named anchor/witness references, atoms, and
    * strict `Until`. `Until` is the future mirror of the paper's strict
    * `Since` construction. The resulting automaton accepts the word supplied
    * to it; for `mirrorDag(past)`, that word is `reverse(sourceWord)`.
    */
  def fromFuture2ltl(dag: FormulaDag): ForwardPVWAA =
    if dag.logic != Logic.FutureStrict then throw PVWAAError("from_future_2ltl expects a strict-future 2LTL DAG")

    val definitions = scala.collection.mutable.LinkedHashMap.empty[String, Formula]
    definitions ++= dag.definitions.toVector.map { case (name, formula) => name -> desugarFuture(formula) }

    val initialName: String = dag.output match
      case Reference(name, Position.I) if definitions.contains(name) => name
      case Reference(name, Position.I) => throw PVWAAError(s"output references unknown definition '$name'")
      case _ =>
        var candidate = "__output__"
        while definitions.contains(candidate) do candidate = "_" + candidate
        definitions(candidate) = desugarFuture(dag.output)
        candidate

    val names: List[String] = definitions.keys.toList
    val duals = List(false, true)

    val alphabet: List[String] =
      val declared = dag.alphabet.getOrElse(Nil)
      val discovered = definitions.values.flatMap(symbolsOf).toSet.toList.sorted
      (declared ++ discovered).distinct

    val ranks: Map[String, Int] =
      names.zipWithIndex.flatMap { case (name, index) => duals.map(dual => stateName(name, dual) -> index) }.toMap

    def top(formula: Formula, dual: Boolean, symbol: String, current: String): PositiveFormula = formula match
      case Constant(value) => PositiveConstant(value != dual)
      case Atom(kind, variable, sym) =>
        if variable != Position.I then throw PVWAAError("an atom at witness position belongs inside an Until operand")
        val value = Ltl.symbolMatches(kind, sym, symbol)
        PositiveConstant(value != dual)
      case Reference(name, variable) =>
        if variable != Position.I then throw PVWAAError("a witness reference belongs inside an Until operand")
        TransitionAtom(stateName(name, dual), Action.Goto)
      case Negation(operand) => top(operand, !dual, symbol, current)
      case Conjunction(operands) =>
        val children = operands.map(o => top(o, dual, symbol, current))
        if dual then PositiveOr(children) else PositiveAnd(children)
      case Disjunction(operands) =>
        val children = operands.map(o => top(o, dual, symbol, current))
        if dual then PositiveAnd(children) else PositiveOr(children)
      case Until(_, _, left, right) =>
        val leftP = predicate(left, dual, current)
        val rightP = predicate(right, dual, current)
        val loop = TransitionAtom(current, Action.Leave)
        if !dual then PositiveOr(List(rightP, PositiveAnd(List(leftP, loop))))
        else PositiveAnd(List(rightP, PositiveOr(List(leftP, loop))))
      case other => throw PVWAAError(s"normalise unsupported temporal operator before PVWAA translation: $other")

    def predicate(formula: Formula, dual: Boolean, current: String): PositiveFormula = formula match
      case Constant(value) => PositiveConstant(value != dual)
      case Reference(name, variable) =>
        val action = if variable == Position.I then Action.Goto else Action.Carry
        TransitionAtom(stateName(name, dual), action)
      case Negation(operand) => predicate(operand, !dual, current)
      case Conjunction(operands) =>
        val children = operands.map(o => predicate(o, dual, current))
        if dual then PositiveOr(children) else PositiveAnd(children)
      case Disjunction(operands) =>
        val children = operands.map(o => predicate(o, dual, current))
        if dual then PositiveAnd(children) else PositiveOr(children)
      case _ => throw PVWAAError("Until operands must be Boolean combinations of named i/j references")

    val finalCache = scala.collection.mutable.HashMap.empty[(String, Boolean), Boolean]
    def finalOf(formula: Formula, dual: Boolean): Boolean = formula match
      case Constant(value) => value != dual
      case Atom(kind, _, _) => (kind == AtomKind.EosAtom) != dual
      case Reference(name, variable) =>
        if variable != Position.I then throw PVWAAError("a top-level formula cannot contain a witness reference")
        finalCache.getOrElseUpdate((name, dual), finalOf(definitions(name), dual))
      case Negation(operand) => finalOf(operand, !dual)
      case Conjunction(operands) =>
        val values = operands.map(o => finalOf(o, dual))
        if dual then values.exists(identity) else values.forall(identity)
      case Disjunction(operands) =>
        val values = operands.map(o => finalOf(o, dual))
        if dual then values.forall(identity) else values.exists(identity)
      case _: Until => dual // strict Until is false at EOS; its dual is true.
      case other => throw PVWAAError(s"normalise unsupported temporal operator before PVWAA translation: $other")

    val states: List[String] = for name <- names; dual <- duals yield stateName(name, dual)

    val finalStates: Set[String] =
      (for name <- names; dual <- duals if finalOf(definitions(name), dual) yield stateName(name, dual)).toSet

    // Built directly into a mutable hash map (sized up front) rather than
    // through a `for`-comprehension into a `List`/`Vector` and then an
    // immutable map: for a large alphabet this table has `states x
    // alphabet` entries (tens of millions for a several-thousand-symbol
    // alphabet), and repeatedly growing/rehashing a persistent immutable
    // map one entry at a time dominates runtime at that scale.
    val transitionsBuilder = Map.newBuilder[(String, String), PositiveFormula]
    transitionsBuilder.sizeHint(names.length * duals.length * alphabet.length)
    for
      name <- names
      dual <- duals
    do
      val current = stateName(name, dual)
      val formula = definitions(name)
      // If `top` can't actually vary with `symbol` for this definition,
      // build it once and reuse across the whole alphabet instead of
      // rebuilding an identical tree per symbol (see `usesSymbol`).
      if usesSymbol(formula) then
        for symbol <- alphabet do transitionsBuilder += (current, symbol) -> top(formula, dual, symbol, current)
      else
        for first <- alphabet.headOption do
          val shared = top(formula, dual, first, current)
          for symbol <- alphabet do transitionsBuilder += (current, symbol) -> shared

    ForwardPVWAA(
      alphabet = alphabet,
      states = states,
      transitions = transitionsBuilder.result(),
      initialState = stateName(initialName, false),
      finalStates = finalStates,
      rank = ranks,
    )

  /** Evaluate the alternating run tree of a forward PVWAA on `word`. */
  def accepts(automaton: ForwardPVWAA, word: IndexedSeq[String]): Boolean =
    val unknown = word.toSet -- automaton.alphabet.toSet
    if unknown.nonEmpty then
      throw PVWAAError(s"word contains symbols outside automaton alphabet: ${unknown.toList.sorted}")
    val length = word.length
    val memo = scala.collection.mutable.HashMap.empty[(String, Int, Int), Boolean]

    def run(state: String, pebble: Int, head: Int): Boolean =
      val key = (state, pebble, head)
      memo.get(key) match
        case Some(value) => value
        case None =>
          val result =
            if head == length then automaton.finalStates.contains(state)
            else satisfy(automaton.transitions((state, word(head))), pebble, head)
          memo(key) = result
          result

    def satisfy(formula: PositiveFormula, pebble: Int, head: Int): Boolean = formula match
      case PositiveConstant(value) => value
      case PositiveAnd(operands)   => operands.forall(o => satisfy(o, pebble, head))
      case PositiveOr(operands)    => operands.exists(o => satisfy(o, pebble, head))
      case TransitionAtom(state, action) =>
        action match
          case Action.Goto  => run(state, pebble, pebble)
          case Action.Leave => run(state, pebble, head + 1)
          case Action.Carry => run(state, head + 1, head + 1)

    run(automaton.initialState, 0, 0)
