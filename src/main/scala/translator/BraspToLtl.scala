package brasp

import scala.collection.immutable.VectorMap

/** Forward compilation: B-RASP into strict-past 2LTL.
  *
  * This is the constructive direction of `draft.pdf`'s Lemma 5.1 ("For every
  * past B-RASP program P over Σ, there is a 2LTL φ(i)-formula φP with
  * σ2LTL(φP) = O(σB-RASP(P)) such that w, i |= P ⟺ w |= φP(i)") — the
  * mirror of `LtlToBrasp`'s Lemma 5.3. By structural induction over the
  * program's subprograms, in order:
  *
  *   - `bos` / `symbol w` become atoms `BOS(i)` / `w(i)`.
  *   - Boolean connectives (`not`/`and`/`or`) translate directly to their
  *     LTL counterparts, applied to the inductively-built subformulas.
  *   - `rightmost[S]V` becomes `¬S S (S ∧ V)`: the strict-Since witness is
  *     forced to the latest prior position matching `S`, exactly the
  *     rightmost-attention semantics — this is the same identity
  *     `LtlToBrasp` runs in reverse (Lem. 5.2).
  *   - `leftmost[S]V` has no such one-step identity (it would need a third
  *     position to say "no earlier match"), so `BraspNormalize` rewrites it
  *     into rightmost attention first; see that object for the construction.
  */
object BraspToLtl:
  import BooleanExpression as BE
  import Subprogram as SP

  private def translateBoolean(expression: BooleanExpression): Formula = expression match
    case BE.Const(value)   => Formula.Constant(value)
    case BE.Ref(name, pos) => Formula.Reference(name, pos)
    case BE.Not(operand)   => Formula.Negation(translateBoolean(operand))
    case BE.And(operands)  => Formula.Conjunction(operands.map(translateBoolean))
    case BE.Or(operands)   => Formula.Disjunction(operands.map(translateBoolean))

  /** Compile validated past B-RASP into strict-past 2LTL.
    *
    * For `Rightmost[S]V` this creates `¬S S (S ∧ V)`. The left operand
    * forces the strict-Since witness to be the latest prior score match.
    * `Leftmost[S]V` is rewritten into rightmost attention first, so the
    * resulting DAG defines more names than the input program had — every
    * input name still keeps its own meaning.
    */
  def translateProgram(program: Program): FormulaDag =
    program.validate()
    val rightmostOnly = BraspNormalize.leftmostToRightmost(program)
    val entries: List[(String, Formula)] = rightmostOnly.subprograms.map { subprogram =>
      val formula: Formula = subprogram match
        case SP.Bos(_)                     => Formula.Atom(AtomKind.BosAtom, Position.I)
        case SP.SymbolNode(_, symbol)      => Formula.Atom(AtomKind.SymbolAtom, Position.I, Some(symbol))
        case SP.BooleanNode(_, expression) => translateBoolean(expression)
        case SP.RightmostAttention(_, score, value) =>
          val s = translateBoolean(score)
          val v = translateBoolean(value)
          Formula.Since(Position.I, Position.J, Formula.Negation(s), Formula.Conjunction(List(s, v)))
        // Unreachable: `BraspNormalize.leftmostToRightmost` above either
        // removed every leftmost node or refused with its own explanation.
        case SP.LeftmostAttention(name, _, _) =>
          throw TranslationError(s"$name: leftmost attention survived normalization to rightmost attention")
      subprogram.name -> formula
    }
    FormulaDag(
      logic = Logic.PastStrict,
      definitions = VectorMap.from(entries),
      output = Formula.Reference(rightmostOnly.output, Position.I),
      evaluationPoint = "i = |w| (the final input position)",
      alphabet = rightmostOnly.alphabet,
    )

  /** Parse JSON syntax and compile it to strict-past 2LTL. */
  def translate(specification: JsonValue): FormulaDag =
    try translateProgram(Brasp.fromJson(specification))
    catch case BraspError(message) => throw TranslationError(message)
