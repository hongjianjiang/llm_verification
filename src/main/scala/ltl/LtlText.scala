package brasp

import scala.collection.immutable.VectorMap

/** A readable, round-trippable text syntax for `FormulaDag` (compiled
  * 2LTL), used for `.ltl` files.
  *
  * `Ltl.render` is eyeball-friendly but ambiguous: a symbol atom and a
  * reference to a same-named definition both render as `x(i)`. This format
  * resolves that with an explicit `sym(...)` wrapper, so it can be parsed
  * back into the exact same `FormulaDag` — `render(parse(text)) == text`
  * (up to the input's own formatting) and `parse(render(dag)) == dag`.
  *
  * {{{
  * logic past-strict
  * alphabet a b
  *
  * is_a := sym(a)@i
  * last_a_is_a := (!(is_a@j)) S (is_a@j & is_a@j)
  *
  * output := last_a_is_a@i
  * evaluate at i = |w| (the final input position)
  * }}}
  *
  * Each non-blank, non-comment line is `logic past-strict|future-strict`,
  * `alphabet SYM...` (required, one symbol or more — see `BraspText` for
  * why), `evaluate at TEXT`, `output := EXPR`, or `NAME := EXPR`. `EXPR`
  * uses `!`, `&`, `|`, parentheses, `true`/`false`,
  * `bos@i`/`eos@j`, `sym(SYM)@i`, `bit(N)@i` (does character `N` of the
  * current symbol equal `'1'`? — used for multi-proposition alphabets,
  * see `Ltlf`), `NAME@j` references, unary `Y/P/H/X/F/G`,
  * and binary `S`/`U` (both temporal operators always implicitly `^i_j`,
  * the only pairing this fragment ever produces). `#` starts a comment.
  */
object LtlText:
  import Formula.*

  def render(dag: FormulaDag): String =
    val logicLine = "logic " + (dag.logic match
      case Logic.PastStrict   => "past-strict"
      case Logic.FutureStrict => "future-strict")
    val alphabetLines = dag.alphabet.map(symbols => s"alphabet ${symbols.mkString(" ")}").toList
    val defLines = dag.definitions.toVector.map { case (name, formula) => s"$name := ${renderFormula(formula)}" }.toList
    val outputLine = s"output := ${renderFormula(dag.output)}"
    val evaluateLine = s"evaluate at ${dag.evaluationPoint}"
    (List(logicLine) ++ alphabetLines ++ List("") ++ defLines ++ List("", outputLine, evaluateLine)).mkString("\n")

  private def renderFormula(formula: Formula): String = formula match
    case Constant(value)                             => if value then "true" else "false"
    case Atom(AtomKind.BosAtom, variable, _)          => s"bos@${posText(variable)}"
    case Atom(AtomKind.EosAtom, variable, _)          => s"eos@${posText(variable)}"
    case Atom(AtomKind.SymbolAtom, variable, symbol)  => s"sym(${symbol.getOrElse("")})@${posText(variable)}"
    case Atom(AtomKind.BitAtom, variable, symbol)     => s"bit(${symbol.getOrElse("")})@${posText(variable)}"
    case Reference(name, variable)                    => s"$name@${posText(variable)}"
    case Negation(operand)                            => s"!(${renderFormula(operand)})"
    case Conjunction(operands)                        => "(" + operands.map(renderFormula).mkString(" & ") + ")"
    case Disjunction(operands)                        => "(" + operands.map(renderFormula).mkString(" | ") + ")"
    case Previous(_, _, operand)                      => s"Y(${renderFormula(operand)})"
    case Once(_, _, operand)                          => s"P(${renderFormula(operand)})"
    case Historically(_, _, operand)                  => s"H(${renderFormula(operand)})"
    case Next(_, _, operand)                          => s"X(${renderFormula(operand)})"
    case Eventually(_, _, operand)                    => s"F(${renderFormula(operand)})"
    case Always(_, _, operand)                        => s"G(${renderFormula(operand)})"
    case Since(_, _, left, right)                     => s"(${renderFormula(left)}) S (${renderFormula(right)})"
    case Until(_, _, left, right)                     => s"(${renderFormula(left)}) U (${renderFormula(right)})"

  private def posText(position: Position): String = position match
    case Position.I => "i"
    case Position.J => "j"

  def parse(source: String): FormulaDag =
    var logic: Option[Logic] = None
    var alphabet: Option[List[String]] = None
    var outputFormula: Option[Formula] = None
    var evaluationPoint: Option[String] = None
    val definitions = scala.collection.mutable.ArrayBuffer.empty[(String, Formula)]

    for (rawLine, index) <- source.linesIterator.zipWithIndex do
      val line = stripComment(rawLine).trim
      if line.nonEmpty then
        try
          if line == "logic" || line.startsWith("logic ") then
            if logic.isDefined then throw LtlError("'logic' declared more than once")
            logic = Some(line.stripPrefix("logic").trim match
              case "past-strict"   => Logic.PastStrict
              case "future-strict" => Logic.FutureStrict
              case other           => throw LtlError(s"expected 'past-strict' or 'future-strict', got '$other'")
            )
          else if line == "alphabet" || line.startsWith("alphabet ") then
            if alphabet.isDefined then throw LtlError("'alphabet' declared more than once")
            val rest = line.stripPrefix("alphabet").trim
            if rest.isEmpty then throw LtlError("'alphabet' needs at least one symbol")
            alphabet = Some(rest.split("\\s+").toList)
          else if line == "evaluate" || line.startsWith("evaluate ") then
            if evaluationPoint.isDefined then throw LtlError("'evaluate at' declared more than once")
            val rest = line.stripPrefix("evaluate").trim
            if rest != "at" && !rest.startsWith("at ") then throw LtlError("expected 'evaluate at TEXT'")
            evaluationPoint = Some(rest.stripPrefix("at").trim)
          else
            val arrowIndex = line.indexOf(":=")
            if arrowIndex < 0 then
              throw LtlError("expected 'logic ...', 'alphabet ...', 'evaluate at ...', 'output := EXPR', or 'NAME := EXPR'")
            val lhs = line.substring(0, arrowIndex).trim
            val formula = new ExprParser(line.substring(arrowIndex + 2)).parseFull()
            if lhs == "output" then
              if outputFormula.isDefined then throw LtlError("'output' declared more than once")
              outputFormula = Some(formula)
            else
              if !isIdentifier(lhs) then throw LtlError(s"invalid subprogram name: '$lhs'")
              definitions += lhs -> formula
        catch case LtlError(message) => throw LtlError(s"line ${index + 1}: $message")

    val resolvedLogic = logic.getOrElse(throw LtlError("missing 'logic' declaration"))
    val resolvedAlphabet = alphabet.getOrElse(throw LtlError("missing 'alphabet SYM...' declaration"))
    val resolvedOutput = outputFormula.getOrElse(throw LtlError("missing 'output := EXPR' declaration"))
    val resolvedEvaluation = evaluationPoint.getOrElse(throw LtlError("missing 'evaluate at TEXT' declaration"))
    FormulaDag(resolvedLogic, VectorMap.from(definitions), resolvedOutput, resolvedEvaluation, alphabet = Some(resolvedAlphabet))

  private def stripComment(line: String): String =
    val hashIndex = line.indexOf('#')
    if hashIndex < 0 then line else line.substring(0, hashIndex)

  private def isIdentifier(text: String): Boolean =
    text.nonEmpty && (text.head.isLetter || text.head == '_') && text.forall(c => c.isLetterOrDigit || c == '_')

  private final class ExprParser(text: String):
    private var pos = 0

    private def fail(message: String): Nothing = throw LtlError(message)
    private def atEnd: Boolean = pos >= text.length
    private def peek: Char = text(pos)
    private def skipSpaces(): Unit = while !atEnd && text(pos).isWhitespace do pos += 1

    private def identifier(): String =
      skipSpaces()
      if atEnd || !(peek.isLetter || peek == '_') then fail(s"expected an identifier at '${text.substring(pos)}'")
      val start = pos
      while !atEnd && (peek.isLetterOrDigit || peek == '_') do pos += 1
      text.substring(start, pos)

    private def expectChar(c: Char): Unit =
      skipSpaces()
      if atEnd || peek != c then fail(s"expected '$c'")
      pos += 1

    private def tryChar(c: Char): Boolean =
      skipSpaces()
      if !atEnd && peek == c then
        pos += 1
        true
      else false

    private def tryWord(word: String): Boolean =
      skipSpaces()
      if pos + word.length > text.length || !text.regionMatches(pos, word, 0, word.length) then false
      else
        val boundaryOk =
          pos + word.length == text.length || !(text(pos + word.length).isLetterOrDigit || text(pos + word.length) == '_')
        if boundaryOk then
          pos += word.length
          true
        else false

    /** Like `tryWord`, but only commits if the keyword is immediately
      * followed (ignoring spaces) by `required`. Without this, a reference
      * named e.g. `Y` or `bos` would be swallowed by the keyword branch
      * below instead of falling through to the identifier case.
      */
    private def tryKeyword(word: String, required: Char): Boolean =
      skipSpaces()
      if pos + word.length > text.length || !text.regionMatches(pos, word, 0, word.length) then false
      else
        val boundaryOk =
          pos + word.length == text.length || !(text(pos + word.length).isLetterOrDigit || text(pos + word.length) == '_')
        if !boundaryOk then false
        else
          var lookahead = pos + word.length
          while lookahead < text.length && text(lookahead).isWhitespace do lookahead += 1
          if lookahead < text.length && text(lookahead) == required then
            pos += word.length
            true
          else false

    private def position(): Position =
      expectChar('@')
      identifier() match
        case "i"   => Position.I
        case "j"   => Position.J
        case other => fail(s"expected 'i' or 'j' after '@', got '$other'")

    def parseFull(): Formula =
      val result = orExpr()
      skipSpaces()
      if !atEnd then fail(s"unexpected trailing text: '${text.substring(pos)}'")
      result

    private def orExpr(): Formula =
      var operands = List(andExpr())
      while tryChar('|') do operands = operands :+ andExpr()
      if operands.length == 1 then operands.head else Disjunction(operands)

    private def andExpr(): Formula =
      var operands = List(notExpr())
      while tryChar('&') do operands = operands :+ notExpr()
      if operands.length == 1 then operands.head else Conjunction(operands)

    private def notExpr(): Formula =
      if tryChar('!') then Negation(notExpr()) else sinceUntilExpr()

    private def sinceUntilExpr(): Formula =
      val left = baseExpr()
      if tryWord("S") then Since(Position.I, Position.J, left, baseExpr())
      else if tryWord("U") then Until(Position.I, Position.J, left, baseExpr())
      else left

    private def parenExpr(): Formula =
      expectChar('(')
      val inner = orExpr()
      expectChar(')')
      inner

    private def baseExpr(): Formula =
      skipSpaces()
      if atEnd then fail("expected an expression")
      if peek == '(' then parenExpr()
      else if tryWord("true") then Constant(true)
      else if tryWord("false") then Constant(false)
      else if tryKeyword("bos", '@') then Atom(AtomKind.BosAtom, position())
      else if tryKeyword("eos", '@') then Atom(AtomKind.EosAtom, position())
      else if tryKeyword("sym", '(') then
        expectChar('(')
        skipSpaces()
        val start = pos
        while !atEnd && peek != ')' do pos += 1
        if atEnd then fail("unterminated 'sym(...)'")
        val symbol = text.substring(start, pos).trim
        pos += 1 // ')'
        Atom(AtomKind.SymbolAtom, position(), Some(symbol))
      else if tryKeyword("bit", '(') then
        expectChar('(')
        skipSpaces()
        val start = pos
        while !atEnd && peek != ')' do pos += 1
        if atEnd then fail("unterminated 'bit(...)'")
        val index = text.substring(start, pos).trim
        pos += 1 // ')'
        Atom(AtomKind.BitAtom, position(), Some(index))
      else if tryKeyword("Y", '(') then Previous(Position.I, Position.J, parenExpr())
      else if tryKeyword("P", '(') then Once(Position.I, Position.J, parenExpr())
      else if tryKeyword("H", '(') then Historically(Position.I, Position.J, parenExpr())
      else if tryKeyword("X", '(') then Next(Position.I, Position.J, parenExpr())
      else if tryKeyword("F", '(') then Eventually(Position.I, Position.J, parenExpr())
      else if tryKeyword("G", '(') then Always(Position.I, Position.J, parenExpr())
      else Reference(identifier(), position())
