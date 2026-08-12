package brasp

/** A terse line-oriented textual syntax for B-RASP programs, compiling
  * directly to the same `Program` AST as the JSON front end (`Brasp.fromJson`).
  *
  * {{{
  * alphabet a b
  *
  * is_a       = symbol a
  * a_before   = rightmost(is_a@j, true)
  * contains_a = is_a | a_before
  *
  * output contains_a
  * }}}
  *
  * Each non-blank, non-comment line is one statement: `alphabet SYM...`
  * (required — a program's compiled automaton only knows about symbols
  * actually referenced by a `symbol` node, so an omitted or too-small
  * alphabet silently limits what downstream tooling accepts, even though
  * `Brasp.accepts` itself doesn't consult it), `output NAME`, or
  * `NAME = EXPR`. `EXPR` is one of `bos`, `symbol SYM`, `const true|false`,
  * `rightmost(SCORE, VALUE)`, `leftmost(SCORE, VALUE)`, or a Boolean
  * combination of earlier names using `!`, `&`, `|`, and parentheses.
  * Inside a `rightmost`/`leftmost` argument, a reference may pick its
  * position with `@i` (default) or `@j`; elsewhere a position suffix is
  * rejected, mirroring the JSON front end's `predicate` rule. `#` starts a
  * line comment.
  */
object BraspText:
  import BooleanExpression.*
  import Subprogram.*

  private val keywords = Set("bos", "symbol", "const", "rightmost", "leftmost")

  def parse(source: String): Program =
    var alphabet: Option[List[String]] = None
    var output: Option[String] = None
    val subprograms = scala.collection.mutable.ArrayBuffer.empty[Subprogram]

    for (rawLine, index) <- source.linesIterator.zipWithIndex do
      val line = stripComment(rawLine).trim
      if line.nonEmpty then
        try
          if line == "alphabet" || line.startsWith("alphabet ") then
            if alphabet.isDefined then throw BraspError("'alphabet' declared more than once")
            alphabet = Some(new LineParser(line.stripPrefix("alphabet")).parseSymbolList())
          else if line == "output" || line.startsWith("output ") then
            if output.isDefined then throw BraspError("'output' declared more than once")
            output = Some(new LineParser(line.stripPrefix("output")).parseSoleIdentifier())
          else
            val equalsIndex = line.indexOf('=')
            if equalsIndex < 0 then
              throw BraspError("expected 'name = expression', 'alphabet SYM...', or 'output NAME'")
            val name = line.substring(0, equalsIndex).trim
            if !isIdentifier(name) then throw BraspError(s"invalid subprogram name: '$name'")
            subprograms += new LineParser(line.substring(equalsIndex + 1)).parseDefinition(name)
        catch case BraspError(message) => throw BraspError(s"line ${index + 1}: $message")

    if subprograms.isEmpty then throw BraspError("a .brasp program must define at least one subprogram")
    val resolvedAlphabet = alphabet.getOrElse(throw BraspError("missing 'alphabet SYM...' declaration"))
    val resolvedOutput = output.getOrElse(subprograms.last.name)
    val program = Program(subprograms.toList, resolvedOutput, Some(resolvedAlphabet))
    program.validate()
    program

  def render(program: Program): String =
    val lines = scala.collection.mutable.ArrayBuffer.empty[String]
    program.alphabet.foreach(symbols => lines += s"alphabet ${symbols.mkString(" ")}")
    if program.alphabet.isDefined then lines += ""
    lines ++= program.subprograms.map(subprogram => s"${subprogram.name} = ${renderDefinition(subprogram)}")
    lines += ""
    lines += s"output ${program.output}"
    lines.mkString("\n")

  private def renderDefinition(subprogram: Subprogram): String = subprogram match
    case Bos(_)                       => "bos"
    case SymbolNode(_, symbol)        => s"symbol $symbol"
    case BooleanNode(_, expression)   => renderExpression(expression, predicate = false, minPrecedence = 0)
    case RightmostAttention(_, s, v)  =>
      s"rightmost(${renderExpression(s, predicate = true, minPrecedence = 0)}, ${renderExpression(v, predicate = true, minPrecedence = 0)})"
    case LeftmostAttention(_, s, v) =>
      s"leftmost(${renderExpression(s, predicate = true, minPrecedence = 0)}, ${renderExpression(v, predicate = true, minPrecedence = 0)})"

  /** `|` binds loosest, then `&`, then `!`, then atoms/parens; a node is
    * wrapped in parens only when its own precedence is below what its
    * position in the tree requires, matching the grammar in `LineParser`.
    */
  private def precedence(expression: BooleanExpression): Int = expression match
    case Or(_)             => 0
    case And(_)             => 1
    case Not(_)              => 2
    case Const(_) | Ref(_, _) => 3

  private def renderExpression(expression: BooleanExpression, predicate: Boolean, minPrecedence: Int): String =
    val body = expression match
      case Const(true)  => "true"
      case Const(false) => "false"
      case Ref(name, position) =>
        if predicate then s"$name@${if position == Position.J then "j" else "i"}" else name
      case Not(operand)   => s"!${renderExpression(operand, predicate, minPrecedence = 2)}"
      case And(operands) => operands.map(renderExpression(_, predicate, minPrecedence = 2)).mkString(" & ")
      case Or(operands)  => operands.map(renderExpression(_, predicate, minPrecedence = 1)).mkString(" | ")
    if precedence(expression) < minPrecedence then s"($body)" else body

  private def stripComment(line: String): String =
    val hashIndex = line.indexOf('#')
    if hashIndex < 0 then line else line.substring(0, hashIndex)

  private def isIdentifier(text: String): Boolean =
    text.nonEmpty && (text.head.isLetter || text.head == '_') && text.forall(c => c.isLetterOrDigit || c == '_')

  private final class LineParser(text: String):
    private var pos = 0

    private def fail(message: String): Nothing = throw BraspError(message)
    private def atEnd: Boolean = pos >= text.length
    private def peek: Char = text(pos)
    private def skipSpaces(): Unit = while !atEnd && text(pos).isWhitespace do pos += 1

    private def identifier(): String =
      skipSpaces()
      if atEnd || !(peek.isLetter || peek == '_') then fail(s"expected an identifier at '${text.substring(pos)}'")
      val start = pos
      while !atEnd && (peek.isLetterOrDigit || peek == '_') do pos += 1
      text.substring(start, pos)

    private def quotedSymbol(): String =
      pos += 1 // opening quote
      val start = pos
      while !atEnd && peek != '"' do pos += 1
      if atEnd then fail("unterminated quoted symbol")
      val value = text.substring(start, pos)
      pos += 1 // closing quote
      value

    private def symbolToken(): String =
      skipSpaces()
      if atEnd then fail("expected a symbol")
      if peek == '"' then quotedSymbol()
      else
        val start = pos
        while !atEnd && !text(pos).isWhitespace && !"(),".contains(text(pos)) do pos += 1
        if pos == start then fail(s"expected a symbol at '${text.substring(pos)}'")
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

    def parseSymbolList(): List[String] =
      val symbols = scala.collection.mutable.ArrayBuffer.empty[String]
      skipSpaces()
      while !atEnd do
        symbols += symbolToken()
        skipSpaces()
      if symbols.isEmpty then fail("'alphabet' needs at least one symbol")
      symbols.toList

    def parseSoleIdentifier(): String =
      val name = identifier()
      skipSpaces()
      if !atEnd then fail(s"unexpected trailing text: '${text.substring(pos)}'")
      name

    def parseDefinition(name: String): Subprogram =
      skipSpaces()
      val startPos = pos
      val leading = if !atEnd && (peek.isLetter || peek == '_') then Some(identifier()) else None
      val result: Subprogram = leading match
        case Some("bos") => Bos(name)
        case Some("symbol") => SymbolNode(name, symbolToken())
        case Some("const") => BooleanNode(name, Const(boolLiteral()))
        case Some("rightmost") => val (s, v) = attentionArgs(); RightmostAttention(name, s, v)
        case Some("leftmost") => val (s, v) = attentionArgs(); LeftmostAttention(name, s, v)
        case _ =>
          pos = startPos // not a keyword: let the expression grammar re-read it as a reference
          BooleanNode(name, orExpr(predicate = false))
      skipSpaces()
      if !atEnd then fail(s"unexpected trailing text: '${text.substring(pos)}'")
      result

    private def boolLiteral(): Boolean = identifier() match
      case "true"  => true
      case "false" => false
      case other   => fail(s"expected 'true' or 'false', got '$other'")

    private def attentionArgs(): (BooleanExpression, BooleanExpression) =
      expectChar('(')
      val score = orExpr(predicate = true)
      expectChar(',')
      val value = orExpr(predicate = true)
      expectChar(')')
      (score, value)

    private def orExpr(predicate: Boolean): BooleanExpression =
      var operands = List(andExpr(predicate))
      while tryChar('|') do operands = operands :+ andExpr(predicate)
      if operands.length == 1 then operands.head else Or(operands)

    private def andExpr(predicate: Boolean): BooleanExpression =
      var operands = List(notExpr(predicate))
      while tryChar('&') do operands = operands :+ notExpr(predicate)
      if operands.length == 1 then operands.head else And(operands)

    private def notExpr(predicate: Boolean): BooleanExpression =
      if tryChar('!') then Not(notExpr(predicate)) else atomExpr(predicate)

    private def atomExpr(predicate: Boolean): BooleanExpression =
      skipSpaces()
      if atEnd then fail("expected an expression")
      if peek == '(' then
        pos += 1
        val inner = orExpr(predicate)
        expectChar(')')
        inner
      else
        identifier() match
          case "true"  => Const(true)
          case "false" => Const(false)
          case name =>
            var position = Position.I
            if tryChar('@') then
              if !predicate then fail("ordinary Boolean program expressions cannot specify a position")
              position = identifier() match
                case "i" => Position.I
                case "j" => Position.J
                case other => fail(s"expected 'i' or 'j' after '@', got '$other'")
            Ref(name, position)
