package brasp

/** Language inclusion reduction for Boolean B-RASP programs. */
object Inclusion:
  import BooleanExpression.*
  import Subprogram.*

  private def renameExpression(expression: BooleanExpression, names: Map[String, String]): BooleanExpression =
    expression match
      case c: Const             => c
      case Ref(name, position)  => Ref(names(name), position)
      case Not(operand)         => Not(renameExpression(operand, names))
      case And(operands)        => And(operands.map(renameExpression(_, names)))
      case Or(operands)         => Or(operands.map(renameExpression(_, names)))

  private def renameProgram(program: Program, prefix: String): Program =
    val names = program.subprograms.zipWithIndex.map { case (s, index) => s.name -> s"$prefix$index" }.toMap
    val renamed = program.subprograms.map { subprogram =>
      val name = names(subprogram.name)
      subprogram match
        case Bos(_)                              => Bos(name)
        case SymbolNode(_, symbol)               => SymbolNode(name, symbol)
        case BooleanNode(_, expression)          => BooleanNode(name, renameExpression(expression, names))
        case RightmostAttention(_, score, value) =>
          RightmostAttention(name, renameExpression(score, names), renameExpression(value, names))
        case LeftmostAttention(_, score, value) =>
          LeftmostAttention(name, renameExpression(score, names), renameExpression(value, names))
    }
    Program(renamed, names(program.output), program.alphabet)

  private def combineAlphabets(left: Option[List[String]], right: Option[List[String]]): Option[List[String]] =
    val combined = (left.getOrElse(Nil) ++ right.getOrElse(Nil)).distinct
    if combined.isEmpty then None else Some(combined)

  /** Build a program recognizing `L(subset) \ L(superset)`.
    *
    * The result is false on every word exactly when `L(subset) subset-of
    * L(superset)`. Subprogram names are alpha-renamed before composition, so
    * callers may use the same names in both input programs.
    */
  def counterexampleProgram(subset: Program, superset: Program): Program =
    subset.validate()
    superset.validate()
    val left = renameProgram(subset, "left_")
    val right = renameProgram(superset, "right_")
    val output = "subset_counterexample"
    val alphabet = combineAlphabets(left.alphabet, right.alphabet)
    val result = Program(
      left.subprograms ++ right.subprograms :+ BooleanNode(output, And(List(Ref(left.output), Not(Ref(right.output))))),
      output,
      alphabet,
    )
    result.validate()
    result

  /** Build a program recognizing `L(leftProgram) xor L(rightProgram)`.
    *
    * Its language is empty exactly when the two input programs accept the
    * same finite words, including the empty word.
    */
  def equivalenceCounterexampleProgram(leftProgram: Program, rightProgram: Program): Program =
    leftProgram.validate()
    rightProgram.validate()
    val left = renameProgram(leftProgram, "left_")
    val right = renameProgram(rightProgram, "right_")
    val output = "equivalence_counterexample"
    val alphabet = combineAlphabets(left.alphabet, right.alphabet)
    val result = Program(
      left.subprograms ++ right.subprograms :+ BooleanNode(
        output,
        Or(
          List(
            And(List(Ref(left.output), Not(Ref(right.output)))),
            And(List(Ref(right.output), Not(Ref(left.output)))),
          )
        ),
      ),
      output,
      alphabet,
    )
    result.validate()
    result
