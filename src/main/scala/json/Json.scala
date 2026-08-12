package brasp

/** A minimal, dependency-free JSON representation: enough to parse B-RASP
  * program specifications and Kind2 JSON reports, and to pretty-print our own
  * `--json` output. Object field order is preserved (insertion order), and
  * numbers are kept as raw literal text so arbitrarily large integers (e.g.
  * automaton state counts) survive round-tripping without precision loss.
  */
enum JsonValue:
  case JNull
  case JBool(value: Boolean)
  case JNum(raw: String)
  case JStr(value: String)
  case JArr(items: Vector[JsonValue])
  case JObj(fields: Vector[(String, JsonValue)])

  def asString: Option[String] = this match
    case JsonValue.JStr(value) => Some(value)
    case _                     => None

  def asBoolean: Option[Boolean] = this match
    case JsonValue.JBool(value) => Some(value)
    case _                      => None

  def asArray: Option[Vector[JsonValue]] = this match
    case JsonValue.JArr(items) => Some(items)
    case _                     => None

  def asObject: Option[Vector[(String, JsonValue)]] = this match
    case JsonValue.JObj(fields) => Some(fields)
    case _                      => None

  /** Last-wins lookup, matching the semantics of Python's `json.loads` for
    * objects with duplicate keys.
    */
  def field(key: String): Option[JsonValue] =
    asObject.flatMap(_.findLast(_._1 == key).map(_._2))

  def asBigInt: Option[BigInt] = this match
    case JsonValue.JNum(raw) => scala.util.Try(BigInt(raw)).toOption
    case _                   => None

  def asInt: Option[Int] = asBigInt.filter(_.isValidInt).map(_.toInt)

object JsonValue:
  def int(value: Int): JsonValue = JNum(value.toString)
  def bigInt(value: BigInt): JsonValue = JNum(value.toString)
  def str(value: String): JsonValue = JStr(value)
  def bool(value: Boolean): JsonValue = JBool(value)
  def arr(items: JsonValue*): JsonValue = JArr(items.toVector)
  def obj(fields: (String, JsonValue)*): JsonValue = JObj(fields.toVector)

final case class JsonError(message: String) extends RuntimeException(message)

object Json:
  import JsonValue.*

  def parse(text: String): JsonValue =
    val parser = new Parser(text)
    val value = parser.parseTop()
    value

  def render(value: JsonValue, indent: Int = 2): String =
    val sb = new StringBuilder
    def pad(level: Int): String = " " * (indent * level)
    def go(v: JsonValue, level: Int): Unit = v match
      case JNull        => sb.append("null")
      case JBool(b)      => sb.append(if b then "true" else "false")
      case JNum(raw)     => sb.append(raw)
      case JStr(s)       => sb.append(quote(s))
      case JArr(items) =>
        if items.isEmpty then sb.append("[]")
        else
          sb.append("[\n")
          for (item, idx) <- items.zipWithIndex do
            sb.append(pad(level + 1))
            go(item, level + 1)
            if idx != items.length - 1 then sb.append(",")
            sb.append("\n")
          sb.append(pad(level)).append("]")
      case JObj(fields) =>
        if fields.isEmpty then sb.append("{}")
        else
          sb.append("{\n")
          for ((k, fv), idx) <- fields.zipWithIndex do
            sb.append(pad(level + 1)).append(quote(k)).append(": ")
            go(fv, level + 1)
            if idx != fields.length - 1 then sb.append(",")
            sb.append("\n")
          sb.append(pad(level)).append("}")
    go(value, 0)
    sb.toString

  private def quote(s: String): String =
    val sb = new StringBuilder("\"")
    for ch <- s do
      ch match
        case '"'  => sb.append("\\\"")
        case '\\' => sb.append("\\\\")
        case '\n' => sb.append("\\n")
        case '\r' => sb.append("\\r")
        case '\t' => sb.append("\\t")
        case c if c < 0x20 => sb.append(f"\\u$c%04x".replace(" ", "0"))
        case c    => sb.append(c)
    sb.append("\"")
    sb.toString

  private final class Parser(text: String):
    private var pos = 0

    def parseTop(): JsonValue =
      skipWhitespace()
      val value = parseValue()
      skipWhitespace()
      if pos != text.length then fail("trailing data after JSON value")
      value

    private def fail(message: String): Nothing =
      throw JsonError(s"$message at position $pos")

    private def atEnd: Boolean = pos >= text.length

    private def peek: Char =
      if atEnd then fail("unexpected end of input")
      text(pos)

    private def skipWhitespace(): Unit =
      while !atEnd && (text(pos) == ' ' || text(pos) == '\t' || text(pos) == '\n' || text(pos) == '\r') do pos += 1

    private def expect(c: Char): Unit =
      if atEnd || text(pos) != c then fail(s"expected '$c'")
      pos += 1

    private def parseValue(): JsonValue =
      skipWhitespace()
      if atEnd then fail("unexpected end of input")
      peek match
        case '{' => parseObject()
        case '[' => parseArray()
        case '"' => JsonValue.JStr(parseString())
        case 't' => parseLiteral("true", JsonValue.JBool(true))
        case 'f' => parseLiteral("false", JsonValue.JBool(false))
        case 'n' => parseLiteral("null", JsonValue.JNull)
        case c if c == '-' || c.isDigit => parseNumber()
        case c => fail(s"unexpected character '$c'")

    private def parseLiteral(literal: String, value: JsonValue): JsonValue =
      if pos + literal.length > text.length || text.substring(pos, pos + literal.length) != literal then
        fail(s"expected '$literal'")
      pos += literal.length
      value

    private def parseObject(): JsonValue =
      expect('{')
      skipWhitespace()
      val fields = Vector.newBuilder[(String, JsonValue)]
      if !atEnd && peek == '}' then pos += 1
      else
        var continue = true
        while continue do
          skipWhitespace()
          val key = parseString()
          skipWhitespace()
          expect(':')
          val value = parseValue()
          fields += key -> value
          skipWhitespace()
          if !atEnd && peek == ',' then
            pos += 1
          else
            expect('}')
            continue = false
      JsonValue.JObj(fields.result())

    private def parseArray(): JsonValue =
      expect('[')
      skipWhitespace()
      val items = Vector.newBuilder[JsonValue]
      if !atEnd && peek == ']' then pos += 1
      else
        var continue = true
        while continue do
          items += parseValue()
          skipWhitespace()
          if !atEnd && peek == ',' then
            pos += 1
          else
            expect(']')
            continue = false
      JsonValue.JArr(items.result())

    private def parseString(): String =
      expect('"')
      val sb = new StringBuilder
      var closed = false
      while !closed do
        if atEnd then fail("unterminated string")
        val c = text(pos)
        pos += 1
        if c == '"' then closed = true
        else if c == '\\' then
          if atEnd then fail("unterminated escape")
          val esc = text(pos)
          pos += 1
          esc match
            case '"'  => sb.append('"')
            case '\\' => sb.append('\\')
            case '/'  => sb.append('/')
            case 'b'  => sb.append('\b')
            case 'f'  => sb.append('\f')
            case 'n'  => sb.append('\n')
            case 'r'  => sb.append('\r')
            case 't'  => sb.append('\t')
            case 'u' =>
              if pos + 4 > text.length then fail("invalid unicode escape")
              val hex = text.substring(pos, pos + 4)
              pos += 4
              sb.append(Integer.parseInt(hex, 16).toChar)
            case other => fail(s"invalid escape '\\$other'")
        else sb.append(c)
      sb.toString

    private def parseNumber(): JsonValue =
      val start = pos
      if !atEnd && text(pos) == '-' then pos += 1
      while !atEnd && text(pos).isDigit do pos += 1
      if !atEnd && text(pos) == '.' then
        pos += 1
        while !atEnd && text(pos).isDigit do pos += 1
      if !atEnd && (text(pos) == 'e' || text(pos) == 'E') then
        pos += 1
        if !atEnd && (text(pos) == '+' || text(pos) == '-') then pos += 1
        while !atEnd && text(pos).isDigit do pos += 1
      if pos == start then fail("invalid number")
      JsonValue.JNum(text.substring(start, pos))
