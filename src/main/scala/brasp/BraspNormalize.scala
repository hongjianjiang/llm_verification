package brasp

/** Rewrite `leftmost` attention into an equivalent rightmost-only program.
  *
  * The 2LTL compiler (`BraspToLtl`) has a direct identity for rightmost
  * attention only: `Rightmost[S]V` is `¬S S (S ∧ V)`, because a strict-Since
  * pins its witness to the *latest* prior score match. There is no matching
  * one-step identity for `Leftmost[S]V` — saying "`j` is the *earliest* prior
  * match" needs a third position `k` ("no `k < j` matches `S(i, k)`")
  * alongside the query `i` and the witness `j`, and the whole pipeline is
  * two-variable.
  *
  * The way out is that the third variable is only needed because `S(i, k)`
  * still mentions `i`. Split the score on the finitely many valuations of the
  * variables it reads at the query position and that dependency disappears:
  * writing `S(i, j) ≡ ⋁_g A_g(i) ∧ B_g(j)` with the `A_g` mutually exclusive,
  * the earliest witness under `A_g(i)` is just the first position of the whole
  * word satisfying `B_g` — a property of `j` alone, so it can be precomputed
  * once per residual instead of re-derived per query:
  *
  * {{{
  * seen_g  = rightmost(B_g@j, true)        // some earlier position matches B_g
  * first_g = B_g@i & !seen_g               // ... so this is the first match
  * at_g    = rightmost(first_g@j, V)       // the unique earlier first match
  * name    = A_1 & at_1 | ... | A_n & at_n
  * }}}
  *
  * `first_g` holds at exactly one position of any word (or at none), so the
  * rightmost witness in `at_g` *is* the leftmost witness of the original
  * score. A residual `B_g` that simplifies to `false` selects nothing and is
  * dropped; when every residual does, the node is the constant `false`, which
  * is what an empty candidate set evaluates to.
  *
  * The split is over the score's query-position (`@i`) variables only — the
  * value expression is carried through untouched, and may keep reading both
  * positions. Irrelevant variables are folded away as they are found (both
  * branches identical), and residuals are shared, so the case count is
  * typically far below the `2^k` worst case; `maxScoreCases` bounds it.
  */
object BraspNormalize:
  import BooleanExpression.*
  import Subprogram.*

  /** Ceiling on the leaves of one score's query-side case split. */
  val defaultMaxScoreCases: Int = 4096

  private object SplitTooLarge extends RuntimeException("score case split exceeded its budget")

  def hasLeftmost(program: Program): Boolean = program.subprograms.exists {
    case _: LeftmostAttention => true
    case _                    => false
  }

  /** Return an equivalent program with no `leftmost` subprogram.
    *
    * Equivalent in the strong sense: every subprogram of `program` keeps its
    * name and its value at every position, so callers may still read the
    * original output name. Programs without `leftmost` are returned as-is.
    */
  def leftmostToRightmost(program: Program, maxScoreCases: Int = defaultMaxScoreCases): Program =
    program.validate()
    if !hasLeftmost(program) then program
    else
      val used = scala.collection.mutable.HashSet.from(program.subprograms.map(_.name))
      def fresh(base: String): String =
        var candidate = base
        var counter = 0
        while used.contains(candidate) do
          counter += 1
          candidate = s"${base}_$counter"
        used += candidate
        candidate

      // Residual score -> the name of its "first position matching it" marker.
      // Shared across every `leftmost` node in the program: an equal residual
      // names equal (hence equally early) subprograms, so the marker defined
      // for the first such node is in scope for all the later ones.
      val markers = scala.collection.mutable.HashMap.empty[BooleanExpression, String]
      val rewritten = scala.collection.mutable.ArrayBuffer.empty[Subprogram]

      for subprogram <- program.subprograms do
        subprogram match
          case LeftmostAttention(name, score, value) =>
            val queryVariables = Brasp.referencesExpr(score).collect { case Ref(n, Position.I) => n }.distinct
            val cases =
              try splitScore(score, queryVariables, maxScoreCases)
              catch
                case SplitTooLarge =>
                  throw TranslationError(
                    s"$name: cannot normalize this leftmost attention to rightmost attention — its score reads " +
                      s"${queryVariables.length} subprograms at the query position " +
                      s"(${queryVariables.take(6).mkString(", ")}${if queryVariables.length > 6 then ", ..." else ""}), " +
                      s"and the case split over them does not collapse below the $maxScoreCases-case budget. " +
                      "Simplify the score so it depends on fewer '@i' references, or rewrite the subprogram " +
                      "with rightmost attention."
                  )

            // Group the mutually exclusive query-side conditions by residual:
            // one marker and one witness lookup per *distinct* residual, not
            // per case. Insertion-ordered for a deterministic result.
            val grouped = scala.collection.mutable.LinkedHashMap.empty[BooleanExpression, List[BooleanExpression]]
            for (condition, residual) <- cases if residual != Const(false) do
              grouped(residual) = grouped.getOrElse(residual, Nil) :+ condition

            val disjuncts = grouped.toList.map { (residual, conditions) =>
              val marker = markers.getOrElseUpdate(
                residual, {
                  val seen = fresh(s"${name}_seen")
                  rewritten += RightmostAttention(seen, residual, Const(true))
                  val first = fresh(s"${name}_first")
                  rewritten += BooleanNode(first, simplify(And(List(atQuery(residual), Not(Ref(seen))))))
                  first
                },
              )
              val witness = fresh(s"${name}_at")
              rewritten += RightmostAttention(witness, Ref(marker, Position.J), value)
              simplify(And(List(disjunction(conditions), Ref(witness))))
            }
            rewritten += BooleanNode(name, if disjuncts.isEmpty then Const(false) else simplify(disjunction(disjuncts)))

          case other => rewritten += other

      val result = Program(rewritten.toList, program.output, program.alphabet)
      result.validate()
      result

  private def disjunction(operands: List[BooleanExpression]): BooleanExpression =
    if operands.length == 1 then operands.head else Or(operands)

  /** Case-split `score` on `queryVariables` (the names it reads at `@i`).
    *
    * Returns `(condition, residual)` pairs whose conditions are mutually
    * exclusive and jointly exhaustive, and whose residuals read the witness
    * position only. A variable both branches agree on is dropped rather than
    * split, which is what keeps real scores far below `2^k` cases.
    */
  private def splitScore(
      score: BooleanExpression,
      queryVariables: List[String],
      budget: Int,
  ): List[(BooleanExpression, BooleanExpression)] = queryVariables match
    case Nil => List(Const(true) -> simplify(score))
    case variable :: rest =>
      val positive = simplify(substituteQuery(score, variable, true))
      val negative = simplify(substituteQuery(score, variable, false))
      if positive == negative then splitScore(positive, rest, budget)
      else
        val positiveCases = splitScore(positive, rest, budget)
        val negativeCases = splitScore(negative, rest, budget)
        if positiveCases.length + negativeCases.length > budget then throw SplitTooLarge
        positiveCases.map((condition, residual) =>
          simplify(And(List(Ref(variable), condition))) -> residual
        ) ++ negativeCases.map((condition, residual) =>
          simplify(And(List(Not(Ref(variable)), condition))) -> residual
        )

  /** Replace every `name@i` reference by a constant, leaving `name@j` alone. */
  private def substituteQuery(expression: BooleanExpression, name: String, value: Boolean): BooleanExpression =
    expression match
      case Ref(referenced, Position.I) if referenced == name => Const(value)
      case constant: Const                                   => constant
      case reference: Ref                                    => reference
      case Not(operand)                                      => Not(substituteQuery(operand, name, value))
      case And(operands) => And(operands.map(substituteQuery(_, name, value)))
      case Or(operands)  => Or(operands.map(substituteQuery(_, name, value)))

  /** Re-read a witness-position expression at the query position, so a
    * residual score can be evaluated by an ordinary Boolean subprogram.
    */
  private def atQuery(expression: BooleanExpression): BooleanExpression = expression match
    case constant: Const => constant
    case Ref(name, _)    => Ref(name, Position.I)
    case Not(operand)    => Not(atQuery(operand))
    case And(operands)   => And(operands.map(atQuery))
    case Or(operands)    => Or(operands.map(atQuery))

  /** Constant folding plus a canonical operand order, so that two residuals
    * denoting the same predicate compare equal and share one marker.
    */
  private def simplify(expression: BooleanExpression): BooleanExpression = expression match
    case constant: Const => constant
    case reference: Ref  => reference
    case Not(operand) =>
      simplify(operand) match
        case Const(value) => Const(!value)
        case Not(inner)   => inner
        case other        => Not(other)
    case And(operands) => junction(operands, conjunction = true)
    case Or(operands)  => junction(operands, conjunction = false)

  private def junction(operands: List[BooleanExpression], conjunction: Boolean): BooleanExpression =
    val absorbing = Const(!conjunction)
    val neutral = Const(conjunction)
    val flattened = scala.collection.mutable.ArrayBuffer.empty[BooleanExpression]
    def collect(operand: BooleanExpression): Unit = operand match
      case And(inner) if conjunction  => inner.foreach(collect)
      case Or(inner) if !conjunction  => inner.foreach(collect)
      case other                      => flattened += other
    operands.foreach(operand => collect(simplify(operand)))
    if flattened.contains(absorbing) then absorbing
    else
      val kept = flattened.filterNot(_ == neutral).distinct.sortBy(key).toList
      val contradictory = kept.exists {
        case Not(inner) => kept.contains(inner)
        case _          => false
      }
      if contradictory then absorbing
      else
        kept match
          case Nil          => neutral
          case single :: Nil => single
          case many         => if conjunction then And(many) else Or(many)

  private def key(expression: BooleanExpression): String = expression match
    case Const(value)        => if value then "1" else "0"
    case Ref(name, position) => s"r$name@${position.render}"
    case Not(operand)        => s"!(${key(operand)})"
    case And(operands)       => operands.map(key).mkString("&(", ",", ")")
    case Or(operands)        => operands.map(key).mkString("|(", ",", ")")
