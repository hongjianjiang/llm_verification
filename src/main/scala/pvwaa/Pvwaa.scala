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
  /** An unresolved symbol-dependent leaf — `Ltl.symbolMatches(kind, symbol,
    * concreteSymbol) != dual` once a concrete symbol is known, mirroring the
    * `PositiveConstant(value != dual)` a `Ltl.Atom` leaf used to be resolved
    * into eagerly, once per alphabet symbol, at automaton-construction time.
    * Keeping it as a leaf instead is what lets `ForwardPVWAA.transitions`
    * hold one formula per *state* rather than one per `(state, symbol)` —
    * see `ForwardPVWAA`'s own doc-comment.
    */
  case SymbolTest(kind: AtomKind, symbol: Option[String], dual: Boolean)
  case PositiveAnd(operands: List[PositiveFormula])
  case PositiveOr(operands: List[PositiveFormula])

/** A one-pebble alternating automaton whose head moves left to right.
  *
  * A configuration is `(state, pebble, head)`, with `head >= pebble`.
  * `carry` moves both positions one step right, `leave` advances only the
  * head, and `goto` returns the head to the pebble. At `head == |word|`, the
  * head is on the implicit EOS boundary and acceptance is determined by
  * `finalStates`.
  *
  * `transitions` holds exactly one formula per state, *not* one per
  * `(state, symbol)` — symbol-dependence lives inside the formula itself,
  * as `PositiveFormula.SymbolTest` leaves, resolved against a concrete
  * symbol by whoever's walking the formula (`Pvwaa.accepts`, each backend's
  * own encoder), not baked in at construction time. Before this, a formula
  * whose truth genuinely varies per symbol (any `SymbolAtom`/`BitAtom` use)
  * was rebuilt once per alphabet symbol here, making this map — and every
  * backend that reads it — `O(states x |alphabet|)` even for automata whose
  * *actual* per-state formula size is small; for a 14-atomic-proposition
  * LTLf formula (`|alphabet| = 2^14`) that alone took several seconds and
  * over a million map entries before any backend ran at all.
  */
final case class ForwardPVWAA(
    alphabet: List[String],
    states: List[String],
    transitions: Map[String, PositiveFormula],
    initialState: String,
    finalStates: Set[String],
    rank: Map[String, Int],
)

object Pvwaa:
  import Formula.*
  import PositiveFormula.*
  import JsonValue.*

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
    case SymbolTest(kind, symbol, dual) =>
      val base = s"${kind.jsonLabel}(${symbol.getOrElse("?")})"
      if dual then s"¬$base" else base
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
    val body = for state <- automaton.states yield s"  δ($state) = ${positiveRender(automaton.transitions(state))}"
    (header ++ body).mkString("\n")

  private def positiveToJson(formula: PositiveFormula): JsonValue = formula match
    case PositiveConstant(value) => JObj(Vector("type" -> str("const"), "value" -> bool(value)))
    case TransitionAtom(state, action) =>
      JObj(Vector("type" -> str("atom"), "state" -> str(state), "action" -> str(action.label)))
    case SymbolTest(kind, symbol, dual) =>
      JObj(
        Vector(
          "type" -> str("symbol_test"),
          "kind" -> str(kind.jsonLabel),
          "symbol" -> symbol.map(str).getOrElse(JNull),
          "dual" -> bool(dual),
        )
      )
    case PositiveAnd(operands) => JObj(Vector("type" -> str("and"), "args" -> JArr(operands.map(positiveToJson).toVector)))
    case PositiveOr(operands)  => JObj(Vector("type" -> str("or"), "args" -> JArr(operands.map(positiveToJson).toVector)))

  /** Graphviz DOT rendering of the transition structure.
    *
    * The PVWAA is alternating (transitions are AND/OR trees of pebble
    * actions, not single deterministic edges), so this collapses each
    * state's transition formula to one edge per `TransitionAtom` it
    * mentions, labeled by action, dropping the AND/OR combination and any
    * `SymbolTest` guards. That loses precision (see `--json` for the exact
    * formula) but gives a legible overview graph.
    */
  def toDot(automaton: ForwardPVWAA, name: String = "pvwaa"): String =
    def atomsOf(formula: PositiveFormula): List[(String, Action)] = formula match
      case PositiveConstant(_) | SymbolTest(_, _, _) => Nil
      case TransitionAtom(state, action)              => List(state -> action)
      case PositiveAnd(operands)                       => operands.flatMap(atomsOf)
      case PositiveOr(operands)                        => operands.flatMap(atomsOf)

    def quote(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    val edgeLabels = scala.collection.mutable.LinkedHashMap.empty[(String, String), scala.collection.mutable.ArrayBuffer[String]]
    for
      state <- automaton.states
      (target, action) <- atomsOf(automaton.transitions(state))
    do edgeLabels.getOrElseUpdate((state, target), scala.collection.mutable.ArrayBuffer.empty) += action.label

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
        "transitions" -> JObj(automaton.transitions.toVector.map { case (state, formula) => state -> positiveToJson(formula) }),
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

    def top(formula: Formula, dual: Boolean, current: String): PositiveFormula = formula match
      case Constant(value) => PositiveConstant(value != dual)
      case Atom(kind, variable, sym) =>
        if variable != Position.I then throw PVWAAError("an atom at witness position belongs inside an Until operand")
        SymbolTest(kind, sym, dual)
      case Reference(name, variable) =>
        if variable != Position.I then throw PVWAAError("a witness reference belongs inside an Until operand")
        TransitionAtom(stateName(name, dual), Action.Goto)
      case Negation(operand) => top(operand, !dual, current)
      case Conjunction(operands) =>
        val children = operands.map(o => top(o, dual, current))
        if dual then PositiveOr(children) else PositiveAnd(children)
      case Disjunction(operands) =>
        val children = operands.map(o => top(o, dual, current))
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

    // One formula per state — symbol-dependence lives inside it now
    // (`PositiveFormula.SymbolTest`), not in how many times `top` gets
    // called, so this is `O(states)`, not `O(states x alphabet)`.
    val transitionsBuilder = Map.newBuilder[String, PositiveFormula]
    transitionsBuilder.sizeHint(names.length * duals.length)
    for
      name <- names
      dual <- duals
    do
      val current = stateName(name, dual)
      transitionsBuilder += current -> top(definitions(name), dual, current)

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
            else satisfy(automaton.transitions(state), word(head), pebble, head)
          memo(key) = result
          result

    def satisfy(formula: PositiveFormula, symbol: String, pebble: Int, head: Int): Boolean = formula match
      case PositiveConstant(value)          => value
      case SymbolTest(kind, sym, dual)      => Ltl.symbolMatches(kind, sym, symbol) != dual
      case PositiveAnd(operands)            => operands.forall(o => satisfy(o, symbol, pebble, head))
      case PositiveOr(operands)             => operands.exists(o => satisfy(o, symbol, pebble, head))
      case TransitionAtom(state, action) =>
        action match
          case Action.Goto  => run(state, pebble, pebble)
          case Action.Leave => run(state, pebble, head + 1)
          case Action.Carry => run(state, head + 1, head + 1)

    run(automaton.initialState, 0, 0)
