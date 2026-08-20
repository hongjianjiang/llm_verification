package brasp

/** Translates the "Separated Normal Form" output of xsk07/LTLpSeparator
  * (a Java tool implementing Gabbay's Separation Theorem for LTLp — LTL
  * with both past and future operators mixed in one formula) into this
  * project's own fragment.
  *
  * LTLpSeparator's `matrix.json` (from running its `-a` flag) is a JSON
  * array of `[past, present, future]` string triples, each already reduced
  * to just `!`/`&`/`|`/`S`/`U` and atoms/`true`/`false` (its own `-c` step
  * eliminates `G`/`F`/`H`/`O`/`Y`/`X`/`->`/`<->` before separation). The
  * matrix represents a disjunction over rows of `pastᵢ ∧ presentᵢ ∧
  * futureᵢ`, all three components evaluated at the *same* position — this
  * is exactly Gabbay's guarantee: `original(t) ≡ ⋁ᵢ(pastᵢ(t) ∧ presentᵢ(t)
  * ∧ futureᵢ(t))` for every t, in particular t = 0.
  *
  * This project (like `Ltlf.scala`) always evaluates a closed formula at
  * the trace's start boundary (t = 0, no real predecessor). At that
  * boundary any genuinely temporal past component is provably vacuous —
  * `Since`'s existential ranges over zero prior positions — so every row's
  * past component evaluates to a *constant* (`evalPastOnEmptyHistory`),
  * with no automaton needed to determine it. Rows whose past component is
  * false at t = 0 can never contribute and are dropped; for the rest, only
  * `presentᵢ ∧ futureᵢ` survives, which is a pure future-strict formula —
  * directly `LtlfFormula.And`/`Or`, reusing `Ltlf.compileToFuture`
  * unchanged. This is what makes translation possible at all despite this
  * project's automaton construction only ever accepting a single-direction
  * (`PastStrict` xor `FutureStrict`) `FormulaDag` — see the write-up this
  * was worked out from for the full argument.
  */

final case class LtlpError(message: String) extends RuntimeException(message)

enum LtlpFormula:
  case True
  case False
  case Prop(name: String)
  case Not(operand: LtlpFormula)
  case And(left: LtlpFormula, right: LtlpFormula)
  case Or(left: LtlpFormula, right: LtlpFormula)
  case Since(left: LtlpFormula, right: LtlpFormula)
  case Until(left: LtlpFormula, right: LtlpFormula)

object Ltlp:
  import LtlpFormula as F

  // ---------------------------------------------------------------------
  // Parser for one matrix cell: LTLpSeparator's reduced grammar, exactly
  // its `grammar.jjt` restricted to the operators that survive `-c`
  // (`!`, `&`, `|`, `S`, `U`, atoms, `true`/`false`, parens — no
  // `->`/`<->`/`O`/`H`/`Y`/`F`/`G`/`X`/`W`, all eliminated before a
  // formula ever reaches `matrix.json`). Precedence (loosest to
  // tightest): `|` `&` `{S,U}` `!` atom — note this does NOT match
  // `LtlText`'s own precedence (there `!` binds *looser* than `S`/`U`);
  // matching LTLpSeparator's actual grammar is what matters here.
  // ---------------------------------------------------------------------

  private final class CellParser(text: String):
    private var pos = 0

    private def fail(message: String): Nothing =
      throw LtlpError(s"$message at position $pos in '$text'")
    private def atEnd: Boolean = pos >= text.length
    private def peek: Char = text(pos)
    private def skipSpaces(): Unit = while !atEnd && text(pos).isWhitespace do pos += 1
    private def isIdentChar(c: Char): Boolean = c.isLower || c.isDigit || c == '_'

    private def tryChar(c: Char): Boolean =
      skipSpaces()
      if !atEnd && peek == c then
        pos += 1
        true
      else false

    private def expectChar(c: Char): Unit =
      skipSpaces()
      if atEnd || peek != c then fail(s"expected '$c'")
      pos += 1

    private def identifier(): String =
      skipSpaces()
      if atEnd || !peek.isLower then fail(s"expected a lowercase identifier at '${text.substring(pos)}'")
      val start = pos
      while !atEnd && isIdentChar(peek) do pos += 1
      text.substring(start, pos)

    def parseFull(): LtlpFormula =
      val result = orExpr()
      skipSpaces()
      if !atEnd then fail(s"unexpected trailing text: '${text.substring(pos)}'")
      result

    private def orExpr(): LtlpFormula =
      var result = andExpr()
      while tryChar('|') do result = F.Or(result, andExpr())
      result

    private def andExpr(): LtlpFormula =
      var result = binaryTempExpr()
      while tryChar('&') do result = F.And(result, binaryTempExpr())
      result

    private def binaryTempExpr(): LtlpFormula =
      var result = unaryExpr()
      var continue = true
      while continue do
        if tryChar('S') then result = F.Since(result, unaryExpr())
        else if tryChar('U') then result = F.Until(result, unaryExpr())
        else continue = false
      result

    private def unaryExpr(): LtlpFormula =
      if tryChar('!') then F.Not(unaryExpr()) else atomExpr()

    private def atomExpr(): LtlpFormula =
      skipSpaces()
      if atEnd then fail("expected an expression")
      if peek == '(' then
        pos += 1
        val inner = orExpr()
        expectChar(')')
        inner
      else
        identifier() match
          case "true"  => F.True
          case "false" => F.False
          case name    => F.Prop(name)

  def parseCell(text: String): LtlpFormula = new CellParser(text).parseFull()

  /** Parse a `matrix.json` file (LTLpSeparator's `-a` output): a JSON
    * array of 3-element string arrays, `[past, present, future]` per row.
    */
  def parseMatrix(json: String): List[(LtlpFormula, LtlpFormula, LtlpFormula)] =
    val rows = Json.parse(json).asArray.getOrElse(throw LtlpError("matrix.json: expected a top-level JSON array"))
    rows.toList.map { row =>
      val cells = row.asArray.getOrElse(throw LtlpError("matrix.json: expected each row to be a JSON array"))
      if cells.length != 3 then throw LtlpError(s"matrix.json: expected each row to have exactly 3 cells, got ${cells.length}")
      def cellText(index: Int): String =
        cells(index).asString.getOrElse(throw LtlpError(s"matrix.json: expected cell $index to be a string"))
      (parseCell(cellText(0)), parseCell(cellText(1)), parseCell(cellText(2)))
    }

  /** Evaluate a pure-past cell as if at the trace's start (zero prior
    * positions) — directly mirrors `Ltl.evaluateFormula`'s `Since`/`Atom`
    * cases specialized to `word.length == 0`, `anchor == 0`:
    *   - any atom-based content is `false` (`Ltl.atomValue`'s `PastStrict`
    *     case requires `position > 0`, never true at position 0);
    *   - `Since`'s witness search ranges over `0 until 0` — always empty,
    *     so always `false`, regardless of its operands.
    * No automaton or word simulation is needed — this is exact, not an
    * approximation, precisely because the range being quantified over is
    * empty by construction. Throws if `formula` contains `Until`: a cell
    * classified as pure-past by LTLpSeparator's own separator should never
    * contain a future operator; if it does, something upstream is broken
    * and this should fail loudly rather than silently misjudge the row.
    */
  def evalPastOnEmptyHistory(formula: LtlpFormula): Boolean = formula match
    case F.True          => true
    case F.False         => false
    case F.Prop(_)        => false
    case F.Not(operand)   => !evalPastOnEmptyHistory(operand)
    case F.And(left, right) => evalPastOnEmptyHistory(left) && evalPastOnEmptyHistory(right)
    case F.Or(left, right)  => evalPastOnEmptyHistory(left) || evalPastOnEmptyHistory(right)
    case F.Since(_, _)    => false
    case F.Until(_, _)    => throw LtlpError(s"a pure-past matrix cell must not contain 'U': $formula")

  /** `present`/`future` cells never contain `Since` (only pure-past cells
    * do) — this is exactly the subset `LtlfFormula` already covers, so
    * translation reuses `Ltlf.compileToFuture` unchanged rather than
    * needing a second compiler.
    */
  private def toLtlf(formula: LtlpFormula): LtlfFormula = formula match
    case F.True             => LtlfFormula.True
    case F.False            => LtlfFormula.False
    case F.Prop(name)        => LtlfFormula.Prop(name)
    case F.Not(operand)      => LtlfFormula.Not(toLtlf(operand))
    case F.And(left, right)  => LtlfFormula.And(toLtlf(left), toLtlf(right))
    case F.Or(left, right)   => LtlfFormula.Or(toLtlf(left), toLtlf(right))
    case F.Until(left, right) => LtlfFormula.Until(toLtlf(left), toLtlf(right))
    case F.Since(_, _)       => throw LtlpError(s"a present/future matrix cell must not contain 'S': $formula")

  /** Every `Prop` occurring anywhere across the matrix, sorted for a
    * deterministic bit-index assignment (`Ltlf.Builder.alphabet`'s
    * character-position encoding is order-sensitive).
    */
  def propositionsUsed(rows: List[(LtlpFormula, LtlpFormula, LtlpFormula)]): List[String] =
    def props(formula: LtlpFormula): Set[String] = formula match
      case F.True | F.False    => Set.empty
      case F.Prop(name)         => Set(name)
      case F.Not(operand)       => props(operand)
      case F.And(left, right)   => props(left) ++ props(right)
      case F.Or(left, right)    => props(left) ++ props(right)
      case F.Since(left, right) => props(left) ++ props(right)
      case F.Until(left, right) => props(left) ++ props(right)
    rows.flatMap { case (past, present, future) => props(past) ++ props(present) ++ props(future) }.toSet.toList.sorted

  /** Translate a full separated matrix into this project's strict-past
    * `FormulaDag` (via `Ltl.mirrorToPast`, matching `Ltlf.compileToPast`'s
    * own convention — the compiled program's language is the *reversal*
    * of the original LTLp formula's language, same caveat as there).
    *
    * Drops every row whose past component is false at the trace's start
    * (see `evalPastOnEmptyHistory`); for the rest, keeps only
    * `presentᵢ ∧ futureᵢ` (the past component, being true at an empty
    * history, contributes nothing further). If no row survives, the
    * original formula is unsatisfiable at t = 0 and this compiles to the
    * constant `false`.
    */
  def translateMatrix(rows: List[(LtlpFormula, LtlpFormula, LtlpFormula)], atomicPropositions: List[String]): FormulaDag =
    val survivingRows: List[LtlfFormula] = rows.collect {
      case (past, present, future) if evalPastOnEmptyHistory(past) =>
        LtlfFormula.And(toLtlf(present), toLtlf(future))
    }
    val combined: LtlfFormula = survivingRows match
      case Nil          => LtlfFormula.False
      case first :: rest => rest.foldLeft(first)(LtlfFormula.Or.apply)
    // `compileToFuture` already calls `Ltlf.desugar` internally; `combined`
    // only ever uses True/False/Prop/Not/And/Or/Until, all pass-through or
    // directly handled there, so no separate desugar step is needed here.
    Ltl.mirrorToPast(Ltlf.compileToFuture(combined, atomicPropositions))

  /** Convenience entry point: `matrix.json` text straight to a compiled
    * `FormulaDag`, auto-discovering the atomic-proposition list from
    * whatever `Prop`s actually occur (sorted, so the encoding is
    * deterministic across runs of the same input).
    */
  def translate(matrixJson: String): FormulaDag =
    val rows = parseMatrix(matrixJson)
    translateMatrix(rows, propositionsUsed(rows))
