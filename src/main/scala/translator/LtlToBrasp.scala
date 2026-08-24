package brasp

/** Reverse compilation: strict-past 2LTL back into B-RASP.
  *
  * This is the constructive direction of `draft.pdf`'s Lemma 5.3 ("For every
  * 2LTL φ(i)-formula φ over Σ, there is a past B-RASP program Pφ ... such
  * that w |= φ(i) ⟺ w, i |= Pφ"), proved in Appendix B.4 by structural
  * induction on φ:
  *
  *   - Boolean atoms and connectives translate directly — the reverse of
  *     `BraspToLtl.translateBoolean`.
  *   - `Previous`/`Once`/`Historically` are first eliminated in favor of
  *     `Since` (paper Lem. 4.1): `Y φ = ⊥ S φ`, `P φ = ⊤ S φ`,
  *     `H φ = ¬(⊤ S ¬φ)`.
  *   - A `Since(left, right)` (`φ1 S φ2` in the paper's notation) becomes a
  *     fresh `rightmost` attention subprogram after Lem. 5.2's rewrite:
  *     `α = right ∨ ¬left`, `β = right`, giving `rightmost(α, β)`, which
  *     compiles forward to `¬α S (α ∧ β)` — equivalent to the original
  *     `left S right` (the two conditions coincide; see Lem. 5.2's proof).
  *     `left`/`right` must already be Boolean combinations of references to
  *     earlier definitions (true of anything `BraspToLtl.translateProgram`
  *     or `LtlText` produce): each `Since` contributes one fresh attention
  *     subprogram, so `σB-RASP(Pφ) = O(σ2LTL(φ))`.
  *
  * Each named definition in the input DAG becomes one B-RASP subprogram of
  * the same name; the output keeps the original name if it's already a bare
  * `Reference` at `i`, otherwise a synthetic wrapper is added (mirroring how
  * `Pvwaa.fromFuture2ltl` handles a non-reference DAG output).
  */
object LtlToBrasp:
  import Formula.*

  final case class ReverseTranslationError(message: String) extends RuntimeException(message)

  private def normalize(formula: Formula): Formula = formula match
    case Previous(anchor, witness, operand) => Since(anchor, witness, Constant(false), normalize(operand))
    case Once(anchor, witness, operand)     => Since(anchor, witness, Constant(true), normalize(operand))
    case Historically(anchor, witness, operand) =>
      Negation(Since(anchor, witness, Constant(true), Negation(normalize(operand))))
    case Since(anchor, witness, left, right) => Since(anchor, witness, normalize(left), normalize(right))
    case Negation(operand)     => Negation(normalize(operand))
    case Conjunction(operands) => Conjunction(operands.map(normalize))
    case Disjunction(operands) => Disjunction(operands.map(normalize))
    case Constant(_) | Atom(_, _, _) | Reference(_, _) => formula
    case Next(_, _, _) | Eventually(_, _, _) | Always(_, _, _) | Until(_, _, _, _) =>
      throw ReverseTranslationError(s"expected a strict-past formula, found a future operator: ${Ltl.render(formula)}")

  /** Translate a Boolean-shape formula (no atoms, no temporal operators —
    * only what `Since`'s already-inductively-translated operands are
    * assumed to contain) into a B-RASP Boolean expression. `predicate`
    * mirrors `Brasp`'s own front-end rule: only inside a `rightmost`'s
    * score/value may a reference name a witness (`j`) position.
    */
  private def toBooleanExpression(formula: Formula, predicate: Boolean): BooleanExpression = formula match
    case Constant(value) => BooleanExpression.Const(value)
    case Reference(name, position) =>
      if !predicate && position != Position.I then
        throw ReverseTranslationError(s"a witness (j) reference outside a rightmost score/value: $name@j")
      BooleanExpression.Ref(name, position)
    case Negation(operand)     => BooleanExpression.Not(toBooleanExpression(operand, predicate))
    case Conjunction(operands) => BooleanExpression.And(operands.map(toBooleanExpression(_, predicate)))
    case Disjunction(operands) => BooleanExpression.Or(operands.map(toBooleanExpression(_, predicate)))
    case other =>
      throw ReverseTranslationError(
        s"expected a Boolean combination of references, found: ${Ltl.render(other)} " +
          "(give it its own named definition instead of nesting it inline)"
      )

  def translate(dag: FormulaDag): Program =
    if dag.logic != Logic.PastStrict then throw ReverseTranslationError("translate expects a strict-past 2LTL DAG")

    val subprograms = scala.collection.mutable.ArrayBuffer.empty[Subprogram]
    // Seeded with every definition's own name up front (not grown
    // incrementally as the loop below visits them) so fresh names picked
    // for `BitAtom`'s helper subprograms — and for the synthetic output
    // name below — can never collide with a *later* definition's name
    // either, not just an earlier one.
    val names = scala.collection.mutable.HashSet.from(dag.definitions.keys)

    // `BitAtom(index)` (see `Ltl.AtomKind`) has no direct B-RASP equivalent
    // — B-RASP can only test literal symbol equality (`symbol SYM`) — so
    // each one expands to `Or` over a fresh `symbol SYM` helper subprogram
    // per matching alphabet symbol, exactly the construction `Ltlf` itself
    // used to build eagerly for every symbol before `BitAtom` existed. This
    // is intentionally lazy and `--brasp`-only: it doesn't run on the
    // `--aiger`/`--direct` paths (`Pvwaa`/`BooleanAutomaton`/`DirectPvwaa`/
    // `Aiger` never call into this translator), so it can't reintroduce the
    // `2^|AP|` blowup `BitAtom` exists to avoid there. Cached per matching alphabet
    // symbol (not per `BitAtom` occurrence): two propositions whose bit
    // patterns happen to overlap share the same helper subprograms.
    val symbolHelpers = scala.collection.mutable.LinkedHashMap.empty[String, String]
    def helperFor(symbol: String): String =
      symbolHelpers.getOrElseUpdate(
        symbol, {
          var candidate = s"is_$symbol"
          while names.contains(candidate) do candidate = "_" + candidate
          names += candidate
          subprograms += Subprogram.SymbolNode(candidate, symbol)
          candidate
        },
      )

    def translateDefinition(name: String, formula: Formula): Subprogram =
      normalize(formula) match
        case Atom(AtomKind.BosAtom, Position.I, _) => Subprogram.Bos(name)
        case Atom(AtomKind.SymbolAtom, Position.I, Some(symbol)) => Subprogram.SymbolNode(name, symbol)
        case Atom(AtomKind.BitAtom, Position.I, Some(indexText)) =>
          val alphabet = dag.alphabet.getOrElse(
            throw ReverseTranslationError(s"$name: a bit-position atom needs a declared alphabet")
          )
          val index = indexText.toInt
          val matches = alphabet.filter(symbol => symbol(index) == '1')
          val refs = matches.map(symbol => BooleanExpression.Ref(helperFor(symbol), Position.I))
          Subprogram.BooleanNode(name, BooleanExpression.Or(refs))
        case Atom(_, position, _) =>
          throw ReverseTranslationError(s"$name: an atom definition must be at position i, not ${position.render}")
        case Since(_, _, left, right) =>
          // Lem. 5.2: rewrite `left S right` to `!alpha S (alpha & beta)` with
          // alpha = right | !left, beta = right, then read off rightmost's args.
          val alpha = Disjunction(List(right, Negation(left)))
          Subprogram.RightmostAttention(name, toBooleanExpression(alpha, predicate = true), toBooleanExpression(right, predicate = true))
        case other => Subprogram.BooleanNode(name, toBooleanExpression(other, predicate = false))

    for (name, formula) <- dag.definitions do subprograms += translateDefinition(name, formula)

    val outputName = dag.output match
      case Reference(name, Position.I) if names.contains(name) => name
      case _ =>
        var candidate = "__output__"
        while names.contains(candidate) do candidate = "_" + candidate
        names += candidate
        subprograms += translateDefinition(candidate, dag.output)
        candidate

    val program = Program(subprograms.toList, outputName, dag.alphabet)
    program.validate()
    program
