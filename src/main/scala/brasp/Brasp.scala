package brasp

/** Formal abstract syntax for the Boolean, past-masked B-RASP fragment. */

enum Position:
  case I, J

  def render: String = this match
    case Position.I => "i"
    case Position.J => "j"

object Position:
  def fromString(value: String): Option[Position] = value match
    case "i" => Some(Position.I)
    case "j" => Some(Position.J)
    case _   => None

final case class BraspError(message: String) extends RuntimeException(message)

enum BooleanExpression:
  case Const(value: Boolean)
  case Ref(name: String, position: Position = Position.I)
  case Not(operand: BooleanExpression)
  case And(operands: List[BooleanExpression])
  case Or(operands: List[BooleanExpression])

enum Subprogram:
  case Bos(name: String)
  case SymbolNode(name: String, symbol: String)
  case BooleanNode(name: String, expression: BooleanExpression)
  case RightmostAttention(name: String, score: BooleanExpression, value: BooleanExpression)
  case LeftmostAttention(name: String, score: BooleanExpression, value: BooleanExpression)

object Subprogram:
  extension (subprogram: Subprogram)
    def name: String = subprogram match
      case Subprogram.Bos(n)                     => n
      case Subprogram.SymbolNode(n, _)            => n
      case Subprogram.BooleanNode(n, _)           => n
      case Subprogram.RightmostAttention(n, _, _) => n
      case Subprogram.LeftmostAttention(n, _, _)  => n

final case class Program(subprograms: List[Subprogram], output: String, alphabet: Option[List[String]] = None):
  def validate(): Unit =
    if subprograms.isEmpty then throw BraspError("'program' must not be empty")
    val defined = scala.collection.mutable.LinkedHashSet.empty[String]
    for subprogram <- subprograms do
      if subprogram.name.isEmpty || defined.contains(subprogram.name) then
        throw BraspError(s"duplicate or empty subprogram name: '${subprogram.name}'")
      for reference <- Brasp.references(subprogram) do
        if !defined.contains(reference.name) then
          throw BraspError(s"reference to undefined or later subprogram: '${reference.name}'")
      defined += subprogram.name
    if !defined.contains(output) then throw BraspError("'output' must name a subprogram")
    alphabet.foreach { symbols =>
      if symbols.distinct.length != symbols.length || symbols.exists(_.isEmpty) then
        throw BraspError("'alphabet' must contain distinct non-empty symbols")
    }

final case class Evaluation(values: Map[String, Vector[Boolean]]):
  def at(name: String, position: Int): Boolean = values(name)(position)

object Brasp:
  import BooleanExpression.*
  import Subprogram.*
  import JsonValue.*

  def referencesExpr(expression: BooleanExpression): List[Ref] = expression match
    case r: Ref     => List(r)
    case Const(_)   => Nil
    case Not(o)     => referencesExpr(o)
    case And(os)    => os.flatMap(referencesExpr)
    case Or(os)     => os.flatMap(referencesExpr)

  def references(subprogram: Subprogram): List[Ref] = subprogram match
    case Bos(_) | SymbolNode(_, _)             => Nil
    case BooleanNode(_, expression)            => referencesExpr(expression)
    case RightmostAttention(_, score, value)   => referencesExpr(score) ++ referencesExpr(value)
    case LeftmostAttention(_, score, value)    => referencesExpr(score) ++ referencesExpr(value)

  private def parseExpression(value: JsonValue, predicate: Boolean): BooleanExpression =
    val fields = value.asObject.getOrElse(throw BraspError("Boolean expressions must be JSON objects"))
    val operation = value.field("op").flatMap(_.asString)
    operation match
      case Some("const") =>
        val boolValue = value.field("value").flatMap(_.asBoolean).getOrElse(
          throw BraspError("a const expression needs Boolean 'value'")
        )
        Const(boolValue)
      case Some("ref") =>
        val name = value.field("name").flatMap(_.asString).getOrElse(
          throw BraspError("a reference needs string 'name'")
        )
        val rawPosition = value.field("at").flatMap(_.asString).getOrElse("i")
        val position = Position.fromString(rawPosition).getOrElse(
          throw BraspError("a reference position must be 'i' or 'j'")
        )
        if !predicate && fields.exists(_._1 == "at") then
          throw BraspError("ordinary Boolean program expressions cannot specify 'at'")
        Ref(name, if predicate then position else Position.I)
      case Some("not") =>
        val arg = value.field("arg").getOrElse(JNull)
        Not(parseExpression(arg, predicate))
      case Some(op @ ("and" | "or")) =>
        val arguments = value.field("args").flatMap(_.asArray).filter(_.nonEmpty).getOrElse(
          throw BraspError(s"$op needs a non-empty 'args' list")
        )
        val operands = arguments.toList.map(argument => parseExpression(argument, predicate))
        if op == "and" then And(operands) else Or(operands)
      case other =>
        throw BraspError(s"unsupported Boolean operation: ${other.map(o => s"'$o'").getOrElse("None")}")

  def fromJson(specification: JsonValue): Program =
    val programField = specification.field("program").flatMap(_.asArray)
    val items = programField.getOrElse(throw BraspError("input must be an object with a 'program' list"))
    val entries = scala.collection.mutable.ArrayBuffer.empty[Subprogram]
    for item <- items do
      val name = item.field("name").flatMap(_.asString).filter(_.nonEmpty).getOrElse(
        throw BraspError("every program entry needs a non-empty string 'name'")
      )
      val operation = item.field("op").flatMap(_.asString)
      operation match
        case Some("bos") => entries += Bos(name)
        case Some("symbol") =>
          val symbol = item.field("symbol").flatMap(_.asString).filter(_.nonEmpty).getOrElse(
            throw BraspError(s"$name: symbol nodes need a non-empty 'symbol'")
          )
          entries += SymbolNode(name, symbol)
        case Some("const") =>
          val value = item.field("value").flatMap(_.asBoolean).getOrElse(
            throw BraspError(s"$name: const nodes need Boolean 'value'")
          )
          entries += BooleanNode(name, Const(value))
        case Some(op @ ("not" | "and" | "or")) =>
          val withoutName = item.asObject.getOrElse(Vector.empty).filterNot(_._1 == "name")
          entries += BooleanNode(name, parseExpression(JObj(withoutName), predicate = false))
        case Some(op @ ("rightmost" | "attention-rightmost")) =>
          val score = parseExpression(item.field("score").getOrElse(JNull), predicate = true)
          val value = parseExpression(item.field("value").getOrElse(JNull), predicate = true)
          entries += RightmostAttention(name, score, value)
        case Some(op @ ("leftmost" | "attention-leftmost")) =>
          val score = parseExpression(item.field("score").getOrElse(JNull), predicate = true)
          val value = parseExpression(item.field("value").getOrElse(JNull), predicate = true)
          entries += LeftmostAttention(name, score, value)
        case other =>
          throw BraspError(s"$name: unsupported subprogram operation ${other.map(o => s"'$o'").getOrElse("None")}")
    val rawAlphabet = specification.field("alphabet").getOrElse(throw BraspError("missing required 'alphabet' field"))
    val alphabet =
      val symbols = rawAlphabet.asArray.map(_.map(_.asString))
      symbols match
        case Some(opts) if opts.nonEmpty && opts.forall(_.isDefined) => Some(opts.map(_.get).toList)
        case Some(opts) if opts.isEmpty => throw BraspError("'alphabet' must contain at least one symbol")
        case _ => throw BraspError("'alphabet' must be a list of strings")
    val output = specification.field("output") match
      case Some(value) => value.asString.getOrElse(throw BraspError("'output' must name a subprogram"))
      case None        => entries.lastOption.map(_.name).getOrElse(throw BraspError("'output' must name a subprogram"))
    val program = Program(entries.toList, output, alphabet)
    program.validate()
    program

  private def evaluateExpression(
      expression: BooleanExpression,
      values: Map[String, Vector[Boolean]],
      query: Int,
      witness: Int,
  ): Boolean = expression match
    case Const(value) => value
    case Ref(name, position) =>
      val index = if position == Position.I then query else witness
      values(name)(index)
    case Not(operand)  => !evaluateExpression(operand, values, query, witness)
    case And(operands) => operands.forall(evaluateExpression(_, values, query, witness))
    case Or(operands)  => operands.exists(evaluateExpression(_, values, query, witness))

  /** Evaluate every B-RASP subprogram at every position of `word`.
    *
    * `word(0)` is the first ordinary symbol. The evaluator inserts the `BOS`
    * position at index zero, so ordinary word index `r` is B-RASP position
    * `r + 1`.
    */
  def evaluate(program: Program, word: IndexedSeq[String]): Evaluation =
    program.validate()
    val length = word.length
    val values = scala.collection.mutable.LinkedHashMap.empty[String, Vector[Boolean]]
    for subprogram <- program.subprograms do
      val snapshot = values.toMap
      val result = Vector.newBuilder[Boolean]
      for query <- 0 to length do
        val answer = subprogram match
          case Bos(_)              => query == 0
          case SymbolNode(_, sym)  => query > 0 && word(query - 1) == sym
          case BooleanNode(_, expr) => evaluateExpression(expr, snapshot, query, query)
          case RightmostAttention(_, score, value) =>
            val candidates = (0 until query).filter(w => evaluateExpression(score, snapshot, query, w))
            if candidates.isEmpty then false
            else evaluateExpression(value, snapshot, query, candidates.max)
          case LeftmostAttention(_, score, value) =>
            val candidates = (0 until query).filter(w => evaluateExpression(score, snapshot, query, w))
            if candidates.isEmpty then false
            else evaluateExpression(value, snapshot, query, candidates.min)
        result += answer
      values(subprogram.name) = result.result()
    Evaluation(values.toMap)

  /** Whether the selected output subprogram is true at the final position. */
  def accepts(program: Program, word: IndexedSeq[String]): Boolean =
    evaluate(program, word).at(program.output, word.length)
