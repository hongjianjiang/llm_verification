package brasp

import scala.collection.immutable.VectorMap

/** The classical route from \TLTL{} down to *one-variable* past-LTL, kept as a
  * baseline for the automaton constructions that avoid it.
  *
  * A two-variable formula may consult the anchor from inside a temporal
  * operand: `f S_j^i (g(i) & h(j))` scans for a witness `j` while still
  * reading `g` at the fixed anchor `i`. One-variable LTL has no way to say
  * that, and the standard remedy is to case-split on the anchor's *type* --
  * the vector of truth values of the anchor-referenced subformulas. Each
  * valuation turns the operand into a one-variable formula, and the anchor's
  * actual type is pinned outside the operator, where it is evaluable:
  *
  * {{{
  *   T(psi(i, j))  ==  OR over b of  ( AND_n [n(i) <-> b(n)]  AND  T(psi[b]) )
  * }}}
  *
  * The disjunction ranges over `2^|A|` valuations, `A` being the anchor
  * references inside the operand -- this is the exponential the pebble exists
  * to avoid, and it is the point of keeping this translation around: it makes
  * the cost concrete rather than asymptotic. `monotone_past` at alphabet size
  * `sigma` splits on all `sigma` symbol predicates at once, so its single
  * `H` operator alone becomes `2^sigma` disjuncts.
  *
  * The result is pebble-free in the sense that matters downstream: no
  * `Reference(_, Position.I)` survives anywhere, so
  * `Pvwaa.fromFuture2ltl` emits no `goto` atom and the automaton it builds is
  * an ordinary VWAA. Getting there needs a second step after the split ---
  * the guards it introduces are themselves anchor references --- so every
  * remaining `@i` reference is inlined. Inlining is sound precisely because
  * those references sit at the anchor level already, the same position the
  * enclosing formula is evaluated at; `@j` references are left alone, since
  * substituting one would silently move its body to a different position.
  */
object TwoLtlToOneVariable:

  final case class TranslationTooLarge(message: String) extends RuntimeException(message)

  import Formula.*

  /** Names referenced at the anchor inside a temporal operand -- the
    * variables the case split ranges over. Operands are Boolean combinations
    * of references (`Pvwaa`'s `predicate` accepts nothing else), so this is
    * the whole of the operand's anchor dependence.
    */
  private def anchorReferences(formula: Formula): Set[String] = formula match
    case Reference(name, Position.I) => Set(name)
    case Reference(_, _)             => Set.empty
    case Negation(operand)           => anchorReferences(operand)
    case Conjunction(operands)       => operands.flatMap(anchorReferences).toSet
    case Disjunction(operands)       => operands.flatMap(anchorReferences).toSet
    case _                           => Set.empty

  private def substitute(formula: Formula, valuation: Map[String, Boolean]): Formula = formula match
    case Reference(name, Position.I) if valuation.contains(name) => Constant(valuation(name))
    case Negation(operand)                                       => Negation(substitute(operand, valuation))
    case Conjunction(operands)                                   => Conjunction(operands.map(substitute(_, valuation)))
    case Disjunction(operands)                                   => Disjunction(operands.map(substitute(_, valuation)))
    case other                                                   => other

  private def mapOperands(formula: Formula, f: Formula => Formula): Formula = formula match
    case Previous(a, w, operand)     => Previous(a, w, f(operand))
    case Once(a, w, operand)         => Once(a, w, f(operand))
    case Historically(a, w, operand) => Historically(a, w, f(operand))
    case Next(a, w, operand)         => Next(a, w, f(operand))
    case Eventually(a, w, operand)   => Eventually(a, w, f(operand))
    case Always(a, w, operand)       => Always(a, w, f(operand))
    case Since(a, w, left, right)    => Since(a, w, f(left), f(right))
    case Until(a, w, left, right)    => Until(a, w, f(left), f(right))
    case other                       => other

  private def operandsOf(formula: Formula): List[Formula] = formula match
    case Previous(_, _, operand)     => List(operand)
    case Once(_, _, operand)         => List(operand)
    case Historically(_, _, operand) => List(operand)
    case Next(_, _, operand)         => List(operand)
    case Eventually(_, _, operand)   => List(operand)
    case Always(_, _, operand)       => List(operand)
    case Since(_, _, left, right)    => List(left, right)
    case Until(_, _, left, right)    => List(left, right)
    case _                           => Nil

  private def isTemporal(formula: Formula): Boolean = operandsOf(formula).nonEmpty

  /** One temporal operator, case-split on its operands' anchor references. */
  private def split(formula: Formula, cap: Int): Formula =
    val names = operandsOf(formula).flatMap(anchorReferences).distinct
    if names.isEmpty then formula
    else
      // Checked *before* building: the whole point of this translation is
      // that the product below is exponential, so materializing it and then
      // measuring would be the one mistake that matters here.
      val width = if names.length > 30 then BigInt(2).pow(31) else BigInt(2).pow(names.length)
      val estimated = width * BigInt(names.length + 1 + size(formula).toInt)
      if estimated > BigInt(cap) then
        throw TranslationTooLarge(
          s"the case split over ${names.length} anchor references would need 2^${names.length} disjuncts " +
            s"and about $estimated nodes, over this translation's cap of $cap — this is the exponential blow-up " +
            "the two-variable form avoids, not a limitation of the implementation"
        )
      val disjuncts =
        for bits <- 0 until (1 << names.length) yield
          val valuation = names.zipWithIndex.map((name, i) => name -> (((bits >> i) & 1) == 1)).toMap
          val guards = names.map(name => if valuation(name) then Reference(name, Position.I) else Negation(Reference(name, Position.I)))
          Conjunction(guards :+ mapOperands(formula, substitute(_, valuation)))
      Disjunction(disjuncts.toList)

  /** Walk the anchor-level structure, splitting every temporal operator. */
  private def translateBody(formula: Formula, cap: Int): Formula = formula match
    case Negation(operand)     => Negation(translateBody(operand, cap))
    case Conjunction(operands) => Conjunction(operands.map(translateBody(_, cap)))
    case Disjunction(operands) => Disjunction(operands.map(translateBody(_, cap)))
    case other if isTemporal(other) => split(other, cap)
    case other                      => other

  private def size(formula: Formula): Long = formula match
    case Negation(operand)     => 1 + size(operand)
    case Conjunction(operands) => 1 + operands.map(size).sum
    case Disjunction(operands) => 1 + operands.map(size).sum
    case other                 => 1 + operandsOf(other).map(size).sum

  /** Translate `dag` into an equivalent one-variable formula.
    *
    * `cap` bounds both the width of a single case split and the size of the
    * inlined result; exceeding it raises [[TranslationTooLarge]] rather than
    * running the machine out of memory, since on the two-variable families
    * the blow-up is the expected outcome.
    */
  def translate(dag: FormulaDag, cap: Int = 1 << 22): FormulaDag =
    var translated = VectorMap.empty[String, Formula]
    for (name, body) <- dag.definitions do translated = translated.updated(name, translateBody(body, cap))

    // Inline every anchor reference. Memoized, so shared subformulas stay
    // shared as objects even though the result is conceptually a tree.
    val inlined = scala.collection.mutable.Map.empty[String, Formula]
    def inlineIn(formula: Formula): Formula = formula match
      case Reference(name, Position.I) => bodyOf(name)
      case Negation(operand)           => Negation(inlineIn(operand))
      case Conjunction(operands)       => Conjunction(operands.map(inlineIn))
      case Disjunction(operands)       => Disjunction(operands.map(inlineIn))
      case other                       => mapOperands(other, inlineIn)
    def bodyOf(name: String): Formula =
      inlined.getOrElseUpdate(
        name, {
          val body = translated.getOrElse(name, throw TranslationTooLarge(s"unknown definition '$name'"))
          inlineIn(body)
        },
      )

    val output = inlineIn(dag.output)
    val total = size(output)
    if total > cap then
      throw TranslationTooLarge(
        s"the one-variable formula has $total nodes, over this translation's cap of $cap — " +
          "the two-variable source is exponentially more succinct"
      )

    // Only definitions still reached through a witness reference survive; the
    // anchor-level ones have been inlined away.
    def witnessReferences(formula: Formula): Set[String] = formula match
      case Reference(name, Position.J) => Set(name) ++ witnessReferences(bodyOf(name))
      case Reference(_, _)             => Set.empty
      case Negation(operand)           => witnessReferences(operand)
      case Conjunction(operands)       => operands.flatMap(witnessReferences).toSet
      case Disjunction(operands)       => operands.flatMap(witnessReferences).toSet
      case other                       => operandsOf(other).flatMap(witnessReferences).toSet
    val kept = witnessReferences(output)
    val definitions = dag.definitions.keys.filter(kept).foldLeft(VectorMap.empty[String, Formula]) { (acc, name) =>
      acc.updated(name, bodyOf(name))
    }
    dag.copy(definitions = definitions, output = output)
