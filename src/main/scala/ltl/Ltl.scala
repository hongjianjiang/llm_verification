package brasp

import scala.collection.immutable.VectorMap
import scala.util.control.TailCalls.{TailRec, done, tailcall}

/** Formal abstract syntax for strict two-variable LTL over finite words.
  *
  * `FormulaDag` names shared top-level subformulas, avoiding accidental
  * expansion when compiling B-RASP programs.
  */

final case class LtlError(message: String) extends RuntimeException(message)

/** `BitAtom`'s `symbol` field holds a decimal character index (not a
  * literal alphabet token like `SymbolAtom`'s): it matches iff that
  * character of the current input symbol is `'1'`. Used by `Ltlf` to test
  * one atomic proposition directly against a multi-proposition-valuation
  * alphabet, instead of `SymbolAtom`-testing full valuations one by one.
  */
enum AtomKind:
  case BosAtom, EosAtom, SymbolAtom, BitAtom

  def jsonLabel: String = this match
    case AtomKind.BosAtom    => "bos"
    case AtomKind.EosAtom    => "eos"
    case AtomKind.SymbolAtom => "symbol"
    case AtomKind.BitAtom    => "bit"

enum Logic:
  case PastStrict, FutureStrict

  def jsonLabel: String = this match
    case Logic.PastStrict   => "2LTL-past-strict"
    case Logic.FutureStrict => "2LTL-future-strict"

enum Formula:
  case Constant(value: Boolean)
  case Atom(kind: AtomKind, variable: Position, symbol: Option[String] = None)
  case Reference(name: String, variable: Position)
  case Negation(operand: Formula)
  case Conjunction(operands: List[Formula])
  case Disjunction(operands: List[Formula])
  case Previous(anchor: Position, witness: Position, operand: Formula)
  case Once(anchor: Position, witness: Position, operand: Formula)
  case Historically(anchor: Position, witness: Position, operand: Formula)
  case Since(anchor: Position, witness: Position, left: Formula, right: Formula)
  case Next(anchor: Position, witness: Position, operand: Formula)
  case Eventually(anchor: Position, witness: Position, operand: Formula)
  case Always(anchor: Position, witness: Position, operand: Formula)
  case Until(anchor: Position, witness: Position, left: Formula, right: Formula)

final case class FormulaDag(
    logic: Logic,
    definitions: VectorMap[String, Formula],
    output: Formula,
    evaluationPoint: String,
    acceptedLanguage: Option[String] = None,
    alphabet: Option[List[String]] = None,
)

object Ltl:
  import Formula.*
  import JsonValue.*

  /** Does an atom of `kind` with declared field `declared` (`SymbolAtom`'s
    * literal token, or `BitAtom`'s character index) match the concrete
    * input symbol `concrete` actually present at this position? `Bos`/`Eos`
    * atoms never match here (they're positional, not symbol-dependent) —
    * callers handle those separately.
    */
  def symbolMatches(kind: AtomKind, declared: Option[String], concrete: String): Boolean = kind match
    case AtomKind.SymbolAtom => declared.contains(concrete)
    case AtomKind.BitAtom    => declared.exists(index => concrete(index.toInt) == '1')
    case AtomKind.BosAtom | AtomKind.EosAtom => false

  /** Exchange strict past and strict future operators under word reversal.
    *
    * Trampolined (`scala.util.control.TailCalls`) rather than a direct
    * recursive descent: some benchmark formulas nest a single `Formula`
    * subtree hundreds of levels deep (the `two_var/monotone_past` family
    * builds `!(... & pairwise-combination ...)` to `O(sigma^2)` levels,
    * ~860 for `sigma=42`), which overflowed the JVM's default thread stack
    * here well before any backend's own size/complexity limit would ever
    * reject the formula. The trampoline moves the "stack" onto the heap —
    * `tailcall`/`.map`/`.flatMap` build up a chain of thunks that
    * `TailRec#result`'s own loop unwinds iteratively, so recursion depth is
    * bounded by available memory, not by JVM stack size.
    */
  def mirror(formula: Formula): Formula = mirrorTC(formula).result

  private def mirrorTC(formula: Formula): TailRec[Formula] = formula match
    case Constant(_) | Reference(_, _) => done(formula)
    case Atom(AtomKind.BosAtom, variable, symbol) => done(Atom(AtomKind.EosAtom, variable, symbol))
    case Atom(AtomKind.EosAtom, variable, symbol) => done(Atom(AtomKind.BosAtom, variable, symbol))
    case a: Atom                                   => done(a)
    case Negation(operand)     => tailcall(mirrorTC(operand)).map(Negation(_))
    case Conjunction(operands) => mirrorAllTC(operands).map(Conjunction(_))
    case Disjunction(operands) => mirrorAllTC(operands).map(Disjunction(_))
    case Previous(anchor, witness, operand)     => tailcall(mirrorTC(operand)).map(Next(anchor, witness, _))
    case Once(anchor, witness, operand)         => tailcall(mirrorTC(operand)).map(Eventually(anchor, witness, _))
    case Historically(anchor, witness, operand) => tailcall(mirrorTC(operand)).map(Always(anchor, witness, _))
    case Since(anchor, witness, left, right) =>
      for l <- tailcall(mirrorTC(left)); r <- tailcall(mirrorTC(right)) yield Until(anchor, witness, l, r)
    case Next(anchor, witness, operand)         => tailcall(mirrorTC(operand)).map(Previous(anchor, witness, _))
    case Eventually(anchor, witness, operand)   => tailcall(mirrorTC(operand)).map(Once(anchor, witness, _))
    case Always(anchor, witness, operand)       => tailcall(mirrorTC(operand)).map(Historically(anchor, witness, _))
    case Until(anchor, witness, left, right) =>
      for l <- tailcall(mirrorTC(left)); r <- tailcall(mirrorTC(right)) yield Since(anchor, witness, l, r)

  /** `operands.map(mirrorTC)`, sequenced through the trampoline instead of
    * a plain `List.map` — both the per-element `mirrorTC` call *and* the
    * recursion into `tail` go through `tailcall`, so neither a deeply
    * nested operand nor a very long flat operand list can grow the JVM
    * stack.
    */
  private def mirrorAllTC(operands: List[Formula]): TailRec[List[Formula]] = operands match
    case Nil          => done(Nil)
    case head :: tail => for h <- tailcall(mirrorTC(head)); t <- tailcall(mirrorAllTC(tail)) yield h :: t

  def mirrorDag(past: FormulaDag): FormulaDag =
    if past.logic != Logic.PastStrict then throw LtlError("mirror_dag expects a strict-past 2LTL DAG")
    FormulaDag(
      logic = Logic.FutureStrict,
      definitions = VectorMap.from(past.definitions.view.mapValues(mirror)),
      output = mirror(past.output),
      evaluationPoint = "i = 0 on reverse(w); symbols occupy 0..|w|-1 and EOS is |w|",
      acceptedLanguage = Some("{ reverse(w) : w is accepted by the past formula }"),
      alphabet = past.alphabet,
    )

  /** The other direction of `mirrorDag`: `mirror` is its own inverse at the
    * per-`Formula` level, so this is `mirrorDag` with the tag/evaluation-point
    * bookkeeping flipped. `mirrorDag(mirrorToPast(future)) == future`
    * (structurally) and vice versa.
    *
    * Note this relates `future`'s language to its *reversal*, not to
    * itself: `w |= mirrorToPast(future)` iff `reverse(w) |= future`. It does
    * not answer "what past formula accepts the same words as `future`,
    * unreversed" — no such formula is obtainable by mirroring alone.
    */
  def mirrorToPast(future: FormulaDag): FormulaDag =
    if future.logic != Logic.FutureStrict then throw LtlError("mirror_to_past expects a strict-future 2LTL DAG")
    FormulaDag(
      logic = Logic.PastStrict,
      definitions = VectorMap.from(future.definitions.view.mapValues(mirror)),
      output = mirror(future.output),
      evaluationPoint = "i = |w| on reverse(w); symbols occupy 1..|w| and BOS is 0",
      acceptedLanguage = Some("{ reverse(w) : w is accepted by the future formula }"),
      alphabet = future.alphabet,
    )

  def render(formula: Formula): String = formula match
    case Constant(value) => if value then "⊤" else "⊥"
    case Atom(AtomKind.BosAtom, variable, _)    => s"BOS(${variable.render})"
    case Atom(AtomKind.EosAtom, variable, _)    => s"EOS(${variable.render})"
    case Atom(AtomKind.SymbolAtom, variable, symbol) => s"${symbol.getOrElse("")}(${variable.render})"
    case Atom(AtomKind.BitAtom, variable, symbol) => s"bit${symbol.getOrElse("?")}(${variable.render})"
    case Reference(name, variable) => s"$name(${variable.render})"
    case Negation(operand) => s"¬(${render(operand)})"
    case Conjunction(operands) => "(" + operands.map(render).mkString(" ∧ ") + ")"
    case Disjunction(operands) => "(" + operands.map(render).mkString(" ∨ ") + ")"
    case Previous(anchor, witness, operand)     => s"Y^${anchor.render}_${witness.render} (${render(operand)})"
    case Next(anchor, witness, operand)         => s"X^${anchor.render}_${witness.render} (${render(operand)})"
    case Once(anchor, witness, operand)         => s"P^${anchor.render}_${witness.render} (${render(operand)})"
    case Eventually(anchor, witness, operand)   => s"F^${anchor.render}_${witness.render} (${render(operand)})"
    case Historically(anchor, witness, operand) => s"H^${anchor.render}_${witness.render} (${render(operand)})"
    case Always(anchor, witness, operand)       => s"G^${anchor.render}_${witness.render} (${render(operand)})"
    case Since(anchor, witness, left, right) =>
      s"(${render(left)}) S^${anchor.render}_${witness.render} (${render(right)})"
    case Until(anchor, witness, left, right) =>
      s"(${render(left)}) U^${anchor.render}_${witness.render} (${render(right)})"

  def toJson(formula: Formula): JsonValue = formula match
    case Constant(value) => JObj(Vector("type" -> str("const"), "value" -> bool(value)))
    case Atom(kind, variable, symbol) =>
      val base = Vector("type" -> str("atom"), "atom" -> str(kind.jsonLabel), "var" -> str(variable.render))
      JObj(symbol.fold(base)(s => base :+ ("symbol" -> str(s))))
    case Reference(name, variable) =>
      JObj(Vector("type" -> str("ref"), "name" -> str(name), "var" -> str(variable.render)))
    case Negation(operand) => JObj(Vector("type" -> str("not"), "arg" -> toJson(operand)))
    case Conjunction(operands) =>
      JObj(Vector("type" -> str("and"), "args" -> JArr(operands.map(toJson).toVector)))
    case Disjunction(operands) =>
      JObj(Vector("type" -> str("or"), "args" -> JArr(operands.map(toJson).toVector)))
    case Previous(anchor, witness, operand) => unaryTemporalJson("previous", anchor, witness, operand)
    case Next(anchor, witness, operand)     => unaryTemporalJson("next", anchor, witness, operand)
    case Once(anchor, witness, operand)     => unaryTemporalJson("once", anchor, witness, operand)
    case Eventually(anchor, witness, operand) => unaryTemporalJson("eventually", anchor, witness, operand)
    case Historically(anchor, witness, operand) => unaryTemporalJson("historically", anchor, witness, operand)
    case Always(anchor, witness, operand)   => unaryTemporalJson("always", anchor, witness, operand)
    case Since(anchor, witness, left, right) => binaryTemporalJson("since", anchor, witness, left, right)
    case Until(anchor, witness, left, right) => binaryTemporalJson("until", anchor, witness, left, right)

  private def unaryTemporalJson(name: String, anchor: Position, witness: Position, operand: Formula): JsonValue =
    JObj(
      Vector(
        "type" -> str(name),
        "anchor" -> str(anchor.render),
        "witness" -> str(witness.render),
        "arg" -> toJson(operand),
      )
    )

  private def binaryTemporalJson(name: String, anchor: Position, witness: Position, left: Formula, right: Formula): JsonValue =
    JObj(
      Vector(
        "type" -> str(name),
        "anchor" -> str(anchor.render),
        "witness" -> str(witness.render),
        "left" -> toJson(left),
        "right" -> toJson(right),
      )
    )

  def dagToJson(dag: FormulaDag): JsonValue =
    val fields = scala.collection.mutable.ArrayBuffer(
      "logic" -> str(dag.logic.jsonLabel),
      "definitions" -> JObj(dag.definitions.toVector.map { case (name, formula) => name -> toJson(formula) }),
      "output" -> toJson(dag.output),
      "evaluation_point" -> str(dag.evaluationPoint),
    )
    dag.acceptedLanguage.foreach(language => fields += "accepted_language" -> str(language))
    JObj(fields.toVector)

  private def atomValue(logic: Logic, atom: Atom, word: IndexedSeq[String], position: Int): Boolean =
    val length = word.length
    logic match
      case Logic.PastStrict =>
        atom.kind match
          case AtomKind.BosAtom => position == 0
          case AtomKind.EosAtom => position == length
          case AtomKind.SymbolAtom | AtomKind.BitAtom =>
            position > 0 && position <= length && symbolMatches(atom.kind, atom.symbol, word(position - 1))
      case Logic.FutureStrict =>
        atom.kind match
          case AtomKind.EosAtom => position == length
          case AtomKind.BosAtom => position == 0
          case AtomKind.SymbolAtom | AtomKind.BitAtom =>
            position >= 0 && position < length && symbolMatches(atom.kind, atom.symbol, word(position))

  /** Evaluate one formula under an explicit anchor/witness valuation. */
  def evaluateFormula(
      dag: FormulaDag,
      formula: Formula,
      word: IndexedSeq[String],
      environment: Map[Position, Int],
  ): Boolean =
    formula match
      case Constant(value) => value
      case a: Atom          => atomValue(dag.logic, a, word, environment(a.variable))
      case Reference(name, variable) =>
        val nested = environment.updated(Position.I, environment(variable))
        evaluateFormula(dag, dag.definitions(name), word, nested)
      case Negation(operand)     => !evaluateFormula(dag, operand, word, environment)
      case Conjunction(operands) => operands.forall(evaluateFormula(dag, _, word, environment))
      case Disjunction(operands) => operands.exists(evaluateFormula(dag, _, word, environment))
      case Previous(anchor, witness, operand) =>
        val a = environment(anchor)
        a > 0 && evaluateFormula(dag, operand, word, environment.updated(witness, a - 1))
      case Once(anchor, witness, operand) =>
        val a = environment(anchor)
        (0 until a).exists(w => evaluateFormula(dag, operand, word, environment.updated(witness, w)))
      case Historically(anchor, witness, operand) =>
        val a = environment(anchor)
        (0 until a).forall(w => evaluateFormula(dag, operand, word, environment.updated(witness, w)))
      case Since(anchor, witness, left, right) =>
        val a = environment(anchor)
        (0 until a).exists { w =>
          evaluateFormula(dag, right, word, environment.updated(witness, w)) &&
          (w + 1 until a).forall(between => evaluateFormula(dag, left, word, environment.updated(witness, between)))
        }
      case Next(anchor, witness, operand) =>
        val a = environment(anchor)
        val length = word.length
        a < length && evaluateFormula(dag, operand, word, environment.updated(witness, a + 1))
      case Eventually(anchor, witness, operand) =>
        val a = environment(anchor)
        val length = word.length
        (a + 1 to length).exists(w => evaluateFormula(dag, operand, word, environment.updated(witness, w)))
      case Always(anchor, witness, operand) =>
        val a = environment(anchor)
        val length = word.length
        (a + 1 to length).forall(w => evaluateFormula(dag, operand, word, environment.updated(witness, w)))
      case Until(anchor, witness, left, right) =>
        val a = environment(anchor)
        val length = word.length
        (a + 1 to length).exists { w =>
          evaluateFormula(dag, right, word, environment.updated(witness, w)) &&
          (a + 1 until w).forall(between => evaluateFormula(dag, left, word, environment.updated(witness, between)))
        }

  /** Evaluate the output sentence at its designated finite-trace position.
    *
    * For a strict-past DAG, `word` is the source word and the anchor is
    * `|word|`. For a strict-future DAG, `word` is the word presented to that
    * formula (for the compiler's mirror, pass `reverse(source_word)`) and the
    * anchor is `0`.
    */
  def evaluate(dag: FormulaDag, word: IndexedSeq[String]): Boolean =
    val anchor = if dag.logic == Logic.PastStrict then word.length else 0
    evaluateFormula(dag, dag.output, word, Map(Position.I -> anchor))
