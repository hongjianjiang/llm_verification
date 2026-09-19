package brasp

import scala.collection.mutable

/** Render a strict-future **one-variable** 2LTL DAG as flat LTLf text in the
  * syntax the Aalta family of satisfiability checkers parses (`aaltaf`'s
  * `ltlparser/grammar/ltllexer.l`: `!`, `&`, `|`, `X`, `N`, `U`, `R`, `F`,
  * `G`, `true`, `false`, identifiers `[a-zA-Z_][a-zA-Z0-9_]*`).
  *
  * This is the entry point for the SAT-based emptiness baseline: instead of
  * compiling the formula into a DFA and searching it, hand the formula
  * itself to an LTLf satisfiability checker. Two things have to be bridged
  * first.
  *
  * **The formula must already be one-variable.** Aalta, like every other
  * off-the-shelf LTL tool, reads one-variable LTL, so a genuinely
  * two-variable formula has to go through `TwoLtlToOneVariable` first —
  * that elimination, not this printer, is where the exponential sits.
  * Anything still mentioning the anchor from inside a witness scope is
  * rejected here rather than silently mistranslated.
  *
  * **A word is not a trace.** This project's future-strict semantics reads
  * position `p < |w|` as the symbol `w(p)` and keeps one extra sentinel
  * position `|w|` where no symbol holds (`Ltl.atomValue`), whereas an LTLf
  * trace is a sequence of unconstrained propositional valuations. A word
  * `w` therefore corresponds to a trace of `|w| + 1` positions, and the
  * correspondence only becomes a bijection once the alphabet is spelled
  * out as a constraint (`alphabetConstraint`): exactly one symbol
  * proposition holds at every position that has a successor, none holds at
  * the last, and the trace has at least two positions because this
  * project's languages are subsets of `Sigma^+`, never containing the
  * empty word. Dropping the mutual exclusion would be unsound in the
  * direction that matters here — a trace carrying two letters at once
  * satisfies formulas no word does, turning an empty language into a
  * "sat".
  *
  * The output is one line, so it can be piped straight into `aaltaf` on
  * stdin. Note that satisfiability of this text answers emptiness for the
  * *reversed* language (`Ltl.mirrorDag`'s caveat), which is the same
  * yes/no question but a reversed witness.
  */

final case class LtlfExportError(message: String) extends RuntimeException(message)

object LtlfExport:
  import Formula.*

  /** `!(X true)` — the last position of a finite trace, where this
    * project's future-strict reading puts the symbol-free sentinel, hence
    * also how an `EosAtom` is spelled.
    */
  private val Last = "!(X(true))"

  /** A trace position that still has a successor, i.e. one of the `|w|`
    * positions that actually carries a symbol.
    */
  private val Alive = "X(true)"

  private val BosMarker = "bos_marker"

  private def isIdentifier(token: String): Boolean =
    token.nonEmpty && (token.head.isLetter || token.head == '_') &&
      token.forall(c => c.isLetterOrDigit || c == '_')

  /** Proposition names for the alphabet: keep readable tokens (`a`,
    * `marker`) as themselves, and fall back to positional names for
    * alphabets this syntax cannot spell — the large families run past `z`
    * into `{`, `|`, `~`, which `ltllexer.l`'s `ID` rejects. A collision
    * between a kept token and a generated one demotes the whole alphabet
    * to positional names rather than risking two letters sharing a
    * proposition.
    */
  def propositionNames(alphabet: List[String]): List[String] =
    val positional = alphabet.indices.map(index => s"sym$index").toList
    val preferred = alphabet.zip(positional).map((symbol, fallback) => if isIdentifier(symbol) then symbol else fallback)
    if preferred.distinct.length == preferred.length then preferred else positional

  private def conjoin(parts: List[String]): String = parts match
    case Nil         => "true"
    case one :: Nil  => one
    case _           => parts.mkString("(", " & ", ")")

  private def disjoin(parts: List[String]): String = parts match
    case Nil         => "false"
    case one :: Nil  => one
    case _           => parts.mkString("(", " | ", ")")

  /** The alphabet spelled as an LTLf constraint — see this object's
    * doc-comment for why each conjunct is needed. Mutual exclusion is
    * pairwise, so this is quadratic in the alphabet size; that cost is
    * inherent to encoding a `Sigma`-letter alphabet as independent
    * propositions and is part of what the baseline measures.
    */
  def alphabetConstraint(propositions: List[String], usesBos: Boolean): String =
    val someSymbol = disjoin(propositions)
    val pairs =
      for
        left <- propositions.indices.toList
        right <- (left + 1) until propositions.length
      yield s"!(${propositions(left)} & ${propositions(right)})"
    val noSymbol = conjoin(propositions.map(name => s"!($name)"))
    val parts = List(
      Alive,
      s"G(($Alive) -> ${conjoin(someSymbol :: pairs)})",
      s"G(($Last) -> $noSymbol)",
    ) ++ (if usesBos then List(s"$BosMarker & X(G(!($BosMarker)))") else Nil)
    conjoin(parts)

  private def collectReferences(formula: Formula): List[String] =
    val found = mutable.LinkedHashSet.empty[String]
    val pending = mutable.ArrayDeque[Formula](formula)
    while pending.nonEmpty do
      pending.removeLast() match
        case Reference(name, _)                     => found += name
        case Constant(_) | Atom(_, _, _)            => ()
        case Negation(operand)                      => pending += operand
        case Conjunction(operands)                  => pending ++= operands
        case Disjunction(operands)                  => pending ++= operands
        case Previous(_, _, operand)                => pending += operand
        case Once(_, _, operand)                    => pending += operand
        case Historically(_, _, operand)            => pending += operand
        case Next(_, _, operand)                    => pending += operand
        case Eventually(_, _, operand)              => pending += operand
        case Always(_, _, operand)                  => pending += operand
        case Since(_, _, left, right)               => pending += left; pending += right
        case Until(_, _, left, right)               => pending += left; pending += right
    found.toList

  /** Definition names in dependency order, so each body can be rendered
    * once into a cache and reused wherever it is referenced. Iterative
    * (like `Ltl.mirror`'s trampoline) because these DAGs nest hundreds of
    * levels deep.
    */
  private def dependencyOrder(dag: FormulaDag, roots: List[String]): List[String] =
    val order = mutable.ListBuffer.empty[String]
    val visited = mutable.HashSet.empty[String]
    val onPath = mutable.HashSet.empty[String]
    val stack = mutable.ArrayDeque.empty[(String, Boolean)]
    roots.foreach(name => stack += ((name, false)))
    while stack.nonEmpty do
      val (name, expanded) = stack.removeLast()
      if expanded then
        onPath -= name
        if !visited(name) then
          visited += name
          order += name
      else if !visited(name) then
        if onPath(name) then throw LtlfExportError(s"definition '$name' is cyclic")
        val body = dag.definitions.getOrElse(name, throw LtlfExportError(s"output references unknown definition '$name'"))
        onPath += name
        stack += ((name, true))
        collectReferences(body).foreach(child => if !visited(child) then stack += ((child, false)))
    order.toList

  private enum Task:
    case Emit(text: String)
    case Walk(formula: Formula, here: Position)

  /** Render one formula, with `here` naming the position variable that
    * means "the current position" at this point in the walk — `Position.I`
    * at the top of a definition body, and the witness variable inside each
    * temporal operator. An atom or reference at the *other* variable, or a
    * temporal operator anchored anywhere but `here`, is exactly what
    * "still two-variable" looks like, and is rejected.
    *
    * Strictness is where the two logics differ and has to be bridged
    * operator by operator: this project's `Next`/`Eventually`/`Until` all
    * start strictly *after* the anchor, while LTLf's `F`/`U` include the
    * current position, so each picks up a strong `X`. `Always` is the
    * dual, and takes the weak `N` so that it holds vacuously at the last
    * position, where there is no strictly later one to constrain.
    */
  private def renderFormula(
      dag: FormulaDag,
      formula: Formula,
      propositions: Map[String, String],
      cache: collection.Map[String, String],
      cap: Int,
  ): String =
    val out = StringBuilder()
    val stack = mutable.ArrayDeque.empty[Task]
    stack += Task.Walk(formula, Position.I)

    def emit(text: String): Unit =
      out ++= text
      if out.length > cap then
        throw LtlfExportError(
          s"the flat LTLf text for this formula is too large (over $cap characters): LTLf syntax has no way to " +
            "share a repeated subformula, so a compact DAG can only be written out as a tree"
        )

    def push(tasks: Task*): Unit =
      var index = tasks.length - 1
      while index >= 0 do
        stack += tasks(index)
        index -= 1

    def symbolProposition(symbol: Option[String]): String =
      val token = symbol.getOrElse(throw LtlfExportError("a symbol atom carries no symbol"))
      propositions.getOrElse(token, throw LtlfExportError(s"symbol '$token' is not in the declared alphabet"))

    /** A `BitAtom` tests one character of the current symbol, so over a
      * one-hot alphabet encoding it is the disjunction of the letters whose
      * encoding has that character set.
      */
    def bitProposition(index: Option[String]): String =
      val position = index.flatMap(_.toIntOption).getOrElse(throw LtlfExportError("a bit atom carries no character index"))
      val matching = propositions.toList.collect {
        case (symbol, name) if position < symbol.length && symbol(position) == '1' => name
      }
      disjoin(matching.sorted)

    def unsupported(what: String): Nothing =
      throw LtlfExportError(
        s"$what cannot be exported to LTLf: the formula is still two-variable, so run the one-variable " +
          "elimination (--one-variable) first"
      )

    while stack.nonEmpty do
      stack.removeLast() match
        case Task.Emit(text) => emit(text)
        case Task.Walk(current, here) =>
          current match
            case Constant(value) => emit(if value then "true" else "false")
            case Atom(kind, variable, symbol) =>
              if variable != here then unsupported(s"an atom at position ${variable}")
              kind match
                case AtomKind.SymbolAtom => emit(symbolProposition(symbol))
                case AtomKind.BitAtom    => emit(bitProposition(symbol))
                case AtomKind.EosAtom    => emit(Last)
                case AtomKind.BosAtom    => emit(BosMarker)
            case Reference(name, variable) =>
              if variable != here then unsupported(s"a reference to '$name' at position ${variable}")
              emit(cache.getOrElse(name, throw LtlfExportError(s"definition '$name' was not rendered")))
            case Negation(operand) =>
              push(Task.Emit("!("), Task.Walk(operand, here), Task.Emit(")"))
            case Conjunction(operands) =>
              if operands.isEmpty then emit("true")
              else push((Task.Emit("(") +: interleave(operands, here, " & ") :+ Task.Emit(")"))*)
            case Disjunction(operands) =>
              if operands.isEmpty then emit("false")
              else push((Task.Emit("(") +: interleave(operands, here, " | ") :+ Task.Emit(")"))*)
            case Next(anchor, witness, operand) =>
              if anchor != here then unsupported("a next operator")
              push(Task.Emit("X("), Task.Walk(operand, witness), Task.Emit(")"))
            case Eventually(anchor, witness, operand) =>
              if anchor != here then unsupported("an eventually operator")
              push(Task.Emit("X(F("), Task.Walk(operand, witness), Task.Emit("))"))
            case Always(anchor, witness, operand) =>
              if anchor != here then unsupported("an always operator")
              push(Task.Emit("N(G("), Task.Walk(operand, witness), Task.Emit("))"))
            case Until(anchor, witness, left, right) =>
              if anchor != here then unsupported("an until operator")
              push(
                Task.Emit("X(("),
                Task.Walk(left, witness),
                Task.Emit(") U ("),
                Task.Walk(right, witness),
                Task.Emit("))"),
              )
            case Previous(_, _, _) | Once(_, _, _) | Historically(_, _, _) | Since(_, _, _, _) =>
              throw LtlfExportError(
                "past operators cannot be exported to LTLf: mirror the formula to its strict-future form first " +
                  "(Ltl.mirrorDag), which swaps Y/S for X/U and reverses the language"
              )
    out.result()

  private def interleave(operands: List[Formula], here: Position, separator: String): List[Task] =
    operands.zipWithIndex.flatMap { (operand, index) =>
      if index == 0 then List(Task.Walk(operand, here)) else List(Task.Emit(separator), Task.Walk(operand, here))
    }

  /** The full LTLf text: alphabet constraint conjoined with the formula,
    * on one line, ready for `aaltaf` on stdin.
    */
  def render(dag: FormulaDag, cap: Int = 1 << 26): String =
    if dag.logic != Logic.FutureStrict then
      throw LtlfExportError("LTLf export expects a strict-future 2LTL DAG (mirror the past formula first)")
    val alphabet = dag.alphabet.getOrElse(throw LtlfExportError("LTLf export needs the declared alphabet"))
    val names = propositionNames(alphabet)
    val propositions = alphabet.zip(names).toMap

    val order = dependencyOrder(dag, collectReferences(dag.output))
    val cache = mutable.HashMap.empty[String, String]
    for name <- order do
      cache(name) = renderFormula(dag, dag.definitions(name), propositions, cache, cap)
    val body = renderFormula(dag, dag.output, propositions, cache, cap)

    val usesBos = body.contains(BosMarker)
    conjoin(List(alphabetConstraint(names, usesBos), body))
