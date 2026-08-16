package brasp

import scala.collection.immutable.VectorMap
import scala.collection.mutable

/** LTLf (finite-trace LTL) front end: parses the standard/Spot-compatible
  * textual syntax used across the SynthesisLab `LTLf_Learning_Benchmarks`
  * suite (`G`, `F`, `X`, `U`, `R`, `W`, `!`, `&&`/`&`, `||`/`|`, `->`,
  * `<->`, parentheses, bare identifiers, `true`/`false`) and compiles it
  * into this project's strict-future `FormulaDag`, then (via
  * `Ltl.mirrorToPast`) into strict-past 2LTL — from there, the existing
  * `LtlToBrasp` handles "into our format" (`.brasp`).
  *
  * Two things this front end is NOT: it doesn't produce a past formula
  * for the SAME (unreversed) word as the LTLf input — see `mirrorToPast`'s
  * doc-comment, that's a fundamentally different (and much harder) problem
  * — and LTLf traces are multi-proposition Boolean valuations per step,
  * not this project's usual one-named-symbol-per-step model, so the
  * alphabet here is every possible valuation of the declared atomic
  * propositions (`2^|AP|` symbols), with one derived Boolean definition per
  * proposition, a direct `AtomKind.BitAtom` testing that proposition's own
  * character of the current symbol.
  *
  * Earlier versions instead gave every one of the `2^|AP|` alphabet symbols
  * its own named `SymbolAtom` definition and defined each proposition as a
  * `Disjunction` over the (up to `2^|AP|-1`) symbols where its bit is set.
  * That makes every downstream stage — `Pvwaa`'s `states x alphabet`
  * transition table foremost — scale with `2^|AP|` states, not `|AP|`
  * propositions, which is intractable past a dozen or so propositions
  * (`DoubleCounter`/`Nim`/`SingleCounter`-style benchmarks routinely
  * declare 12-24). `BitAtom` tests one character of the *concrete* symbol
  * directly, so a proposition needs exactly one state regardless of
  * alphabet size, and the `2^|AP|` axis only ever shows up as the (cheap,
  * O(1)-per-symbol) loop `Pvwaa` already runs to populate that table.
  */

final case class LtlfError(message: String) extends RuntimeException(message)

enum LtlfFormula:
  case True
  case False
  case Prop(name: String)
  case Not(operand: LtlfFormula)
  case And(left: LtlfFormula, right: LtlfFormula)
  case Or(left: LtlfFormula, right: LtlfFormula)
  case Implies(left: LtlfFormula, right: LtlfFormula)
  case Iff(left: LtlfFormula, right: LtlfFormula)
  case Next(operand: LtlfFormula)
  case WeakNext(operand: LtlfFormula)
  case Eventually(operand: LtlfFormula)
  case Always(operand: LtlfFormula)
  case Until(left: LtlfFormula, right: LtlfFormula)
  case Release(left: LtlfFormula, right: LtlfFormula)
  case WeakUntil(left: LtlfFormula, right: LtlfFormula)

object Ltlf:
  import LtlfFormula as F

  // ---------------------------------------------------------------------
  // Parser
  // ---------------------------------------------------------------------

  def parse(text: String): LtlfFormula = new Parser(text).parseFull()

  private final class Parser(text: String):
    private var pos = 0

    private def fail(message: String): Nothing =
      throw LtlfError(s"$message at position $pos in '$text'")
    private def atEnd: Boolean = pos >= text.length
    private def peek: Char = text(pos)
    private def skipSpaces(): Unit = while !atEnd && text(pos).isWhitespace do pos += 1

    private def isIdentChar(c: Char): Boolean = c.isLetterOrDigit || c == '_'

    /** Try a symbolic (non-alphabetic) operator like `&&`/`->`/`<->`. */
    private def tryOp(op: String): Boolean =
      skipSpaces()
      if pos + op.length <= text.length && text.regionMatches(pos, op, 0, op.length) then
        pos += op.length
        true
      else false

    /** Try a keyword operator (`G`/`F`/`X`/`U`/`R`/`W`/`true`/`false`) —
      * only matches on a word boundary, so it doesn't swallow the prefix
      * of a longer identifier (e.g. a proposition literally named `Ga`).
      */
    private def tryWord(word: String): Boolean =
      skipSpaces()
      if pos + word.length > text.length || !text.regionMatches(pos, word, 0, word.length) then false
      else
        val boundaryOk = pos + word.length == text.length || !isIdentChar(text(pos + word.length))
        if boundaryOk then
          pos += word.length
          true
        else false

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
      if atEnd || !(peek.isLetter || peek == '_') then fail(s"expected an identifier at '${text.substring(pos)}'")
      val start = pos
      while !atEnd && isIdentChar(peek) do pos += 1
      text.substring(start, pos)

    def parseFull(): LtlfFormula =
      val result = iffExpr()
      skipSpaces()
      if !atEnd then fail(s"unexpected trailing text: '${text.substring(pos)}'")
      result

    private def iffExpr(): LtlfFormula =
      var left = impliesExpr()
      while tryOp("<->") do left = F.Iff(left, impliesExpr())
      left

    private def impliesExpr(): LtlfFormula =
      val left = orExpr()
      if tryOp("->") then F.Implies(left, impliesExpr()) else left // right-associative

    private def orExpr(): LtlfFormula =
      var left = andExpr()
      while tryOp("||") || tryChar('|') do left = F.Or(left, andExpr())
      left

    private def andExpr(): LtlfFormula =
      var left = temporalBinExpr()
      while tryOp("&&") || tryChar('&') do left = F.And(left, temporalBinExpr())
      left

    private def temporalBinExpr(): LtlfFormula =
      var left = unaryExpr()
      var continue = true
      while continue do
        if tryWord("U") then left = F.Until(left, unaryExpr())
        else if tryWord("R") then left = F.Release(left, unaryExpr())
        else if tryWord("W") then left = F.WeakUntil(left, unaryExpr())
        else continue = false
      left

    private def unaryExpr(): LtlfFormula =
      if tryChar('!') then F.Not(unaryExpr())
      else if tryWord("G") then F.Always(unaryExpr())
      else if tryWord("F") then F.Eventually(unaryExpr())
      else if tryWord("X") then
        // `X[!]` (strong/"strict" next, requires a next position) vs bare
        // `X` (weak next, vacuously true with no next position) are
        // distinct operators in this benchmark suite's own generators
        // (see e.g. Generating_Formulas/gen_singlecounter.py's `Next` vs
        // `WeakNext`) — not just optional syntax sugar for the same thing.
        skipSpaces()
        if tryChar('[') then
          if !tryChar('!') then fail("expected '!' after 'X['")
          if !tryChar(']') then fail("expected ']' after 'X[!'")
          F.Next(unaryExpr())
        else F.WeakNext(unaryExpr())
      else atomExpr()

    private def atomExpr(): LtlfFormula =
      skipSpaces()
      if atEnd then fail("expected an expression")
      if peek == '(' then
        pos += 1
        val inner = iffExpr()
        expectChar(')')
        inner
      else if tryWord("true") then F.True
      else if tryWord("false") then F.False
      else F.Prop(identifier())

  // ---------------------------------------------------------------------
  // Desugaring: eliminate ->, <->, R, W in favor of !/&/|/U/G
  // ---------------------------------------------------------------------

  def desugar(formula: LtlfFormula): LtlfFormula = formula match
    case F.True | F.False | F.Prop(_) => formula
    case F.Not(operand)      => F.Not(desugar(operand))
    case F.And(left, right)  => F.And(desugar(left), desugar(right))
    case F.Or(left, right)   => F.Or(desugar(left), desugar(right))
    case F.Implies(left, right) => F.Or(F.Not(desugar(left)), desugar(right))
    case F.Iff(left, right) =>
      val l = desugar(left)
      val r = desugar(right)
      F.And(F.Or(F.Not(l), r), F.Or(l, F.Not(r)))
    case F.Next(operand)      => F.Next(desugar(operand))
    // WeakX(y) ≡ ¬StrongX(¬y): vacuously true with no next position,
    // otherwise y must hold there — reuses `Next`'s (already EOS-guarded,
    // see `Builder.nameOf`) compilation entirely, no separate compiler case.
    case F.WeakNext(operand) => F.Not(F.Next(F.Not(desugar(operand))))
    case F.Eventually(operand) => F.Eventually(desugar(operand))
    case F.Always(operand)    => F.Always(desugar(operand))
    case F.Until(left, right) => F.Until(desugar(left), desugar(right))
    case F.Release(left, right) =>
      F.Not(F.Until(F.Not(desugar(left)), F.Not(desugar(right))))
    case F.WeakUntil(left, right) =>
      val l = desugar(left)
      F.Or(F.Until(l, desugar(right)), F.Always(l))

  // ---------------------------------------------------------------------
  // Compiler: desugared LtlfFormula -> strict-future FormulaDag
  // ---------------------------------------------------------------------

  private final class Builder(atomicPropositions: List[String]):
    if atomicPropositions.isEmpty then throw LtlfError("at least one atomic proposition is required")
    if atomicPropositions.distinct.length != atomicPropositions.length then
      throw LtlfError("atomic propositions must be distinct")

    val alphabet: List[String] =
      (0 until (1 << atomicPropositions.length)).map { symbolIndex =>
        atomicPropositions.indices.map(bitPos => if ((symbolIndex >> bitPos) & 1) == 1 then '1' else '0').mkString
      }.toList

    val definitions: mutable.LinkedHashMap[String, Formula] = mutable.LinkedHashMap.empty
    private var counter = 0
    private def freshName(prefix: String): String =
      counter += 1
      s"${prefix}_$counter"

    private def define(prefix: String, formula: Formula): String =
      val name = freshName(prefix)
      definitions(name) = formula
      name

    // LtlToBrasp only accepts named *references* inside Boolean combinations,
    // not bare Atoms (mirroring how BraspToLtl itself always wraps a symbol
    // atom in its own named SymbolNode definition first) — so each
    // proposition gets its own named atom definition upfront, on first use.
    private val propositionNames = mutable.Map.empty[String, String]

    /** The name of a named definition `p_holds := bit(K)@i`, `K` being
      * `p`'s own character position in an alphabet symbol string — built
      * once per proposition and reused for every occurrence. `alphabet`'s
      * construction above lays out character `bitPos` as the `bitPos`-th
      * bit of the symbol's index, matching `BitAtom`'s semantics exactly.
      */
    def propositionRef(prop: String): String =
      propositionNames.getOrElseUpdate(
        prop, {
          val bitPosition = atomicPropositions.indexOf(prop)
          if bitPosition < 0 then throw LtlfError(s"undeclared atomic proposition: '$prop'")
          define(prop, Formula.Atom(AtomKind.BitAtom, Position.I, Some(bitPosition.toString)))
        },
      )

    private def labelFor(formula: LtlfFormula): String = formula match
      case F.Next(_)       => "x"
      case F.Eventually(_) => "f"
      case F.Always(_)     => "g"
      case F.Until(_, _)   => "u"
      case _               => "sub"

    /** Compile `formula`, embeddable directly (inline) at the current query
      * position `I` — used for Boolean combinators, which don't need their
      * own named definition.
      *
      * Temporal operators are different: `LtlToBrasp` only recognizes a
      * `Since` (what `Once`/`Previous`/`Until` all normalize to after
      * mirroring) when it's the *entire, standalone* body of its own named
      * definition — never embedded as a sub-term of a `Conjunction`/
      * `Disjunction`, not even one level deep. So every bare temporal-op
      * `Formula` node here is `define`d on its own immediately, and
      * everything else only ever combines a `Reference` to it.
      *
      * `G` doesn't compile via this project's own `Always` at all: mirrored,
      * `Always` becomes `Historically`, which `LtlToBrasp` can't translate
      * under any circumstances (it normalizes to a `Negation`-wrapped
      * `Since`, a shape `toBooleanExpression` doesn't accept either). `G(x)`
      * is compiled as `¬F(¬x)` instead, reusing `Eventually`'s compilation
      * (and, as a bonus, its already-correct EOS-boundary behavior — see
      * `compileToFuture`'s doc comment on why `Eventually`/`Until` don't
      * need special EOS handling but a direct `Always` would).
      *
      * Memoized on the input `LtlfFormula` (structural/value equality, same
      * as `propositionRef`'s cache below): a temporal-operator subformula
      * that recurs verbatim elsewhere in the source (common in the
      * arithmetic-style generated benchmarks, e.g. `SingleCounter`'s
      * repeated carry-bit checks) would otherwise get a fresh `define` —
      * and so a fresh PVWAA state — at every occurrence instead of sharing
      * the one already compiled. Left unmemoized, that duplication doesn't
      * show up as merely a few extra lines: each duplicate adds its own
      * entry to `gotoSupport`, and every state whose local support includes
      * it pays for that with a *doubling* of its `2^|support|`-sized
      * Boolean-summary table (`BooleanAutomaton.fromForwardPvwaa`) — the
      * same exponent every backend (`Lustre`/`Kind2`/`Btor2`/`Aiger`)
      * materializes one register/cell per entry of.
      */
    private val compileCache = mutable.Map.empty[LtlfFormula, Formula]
    def compile(formula: LtlfFormula): Formula = compileCache.getOrElseUpdate(
      formula,
      formula match
        case F.True       => Formula.Constant(true)
        case F.False      => Formula.Constant(false)
        case F.Prop(name) => Formula.Reference(propositionRef(name), Position.I)
        case F.Not(operand)     => Formula.Negation(compile(operand))
        case F.And(left, right) => Formula.Conjunction(List(compile(left), compile(right)))
        case F.Or(left, right)  => Formula.Disjunction(List(compile(left), compile(right)))
        case F.Next(operand) =>
          val name = nameOf(operand)
          val bareName = define("x_strict", Formula.Next(Position.I, Position.J, Formula.Reference(name, Position.J)))
          Formula.Reference(bareName, Position.I)
        case F.Eventually(operand) =>
          val name = nameOf(operand)
          val bareName = define("f_strict", Formula.Eventually(Position.I, Position.J, Formula.Reference(name, Position.J)))
          Formula.Disjunction(List(Formula.Reference(name, Position.I), Formula.Reference(bareName, Position.I)))
        case F.Always(operand) => compile(F.Not(F.Eventually(F.Not(operand))))
        case F.Until(left, right) =>
          val leftName = nameOf(left)
          val rightName = nameOf(right)
          val bareName = define(
            "u_strict",
            Formula.Until(Position.I, Position.J, Formula.Reference(leftName, Position.J), Formula.Reference(rightName, Position.J)),
          )
          Formula.Disjunction(
            List(
              Formula.Reference(rightName, Position.I),
              Formula.Conjunction(List(Formula.Reference(leftName, Position.I), Formula.Reference(bareName, Position.I))),
            )
          )
        case F.Implies(_, _) | F.Iff(_, _) | F.Release(_, _) | F.WeakUntil(_, _) | F.WeakNext(_) =>
          throw LtlfError(s"internal error: $formula should have been desugared before compilation"),
    )

    // Lazy: only ever materialized if some formula actually needs an
    // operand named (i.e. contains a temporal operator at all).
    private lazy val eosName: String = define("is_eos", Formula.Atom(AtomKind.EosAtom, Position.I))

    /** Name `formula` as the operand of a temporal operator — referenced at
      * a witness position that could legitimately land exactly on the EOS
      * boundary (this project's own `Next`/`Eventually`/`Until` all reach
      * position `length` inclusive, not just up to it). A plain
      * `compile(formula)` would be wrong there whenever `formula` contains
      * a negation: the symbol atoms it bottoms out in are correctly
      * `false` past the end of the word, but negating that flips it to
      * `true` — the opposite of "this proposition isn't defined here,
      * exclude it". So every operand definition bakes in its own
      * `¬EOS ∧ ...` guard once, here, rather than needing it repeated at
      * every place the operand gets referenced.
      * Memoized like `compile` above, and for the same reason: `Until`
      * names its left and right operands via separate calls, so without
      * this cache two `Until`s sharing an identical operand (or an
      * operand identical to some other temporal operator's) would still
      * get two separate `sub_N` definitions and two separate PVWAA states.
      */
    private val nameOfCache = mutable.Map.empty[LtlfFormula, String]
    private def nameOf(formula: LtlfFormula): String =
      nameOfCache.getOrElseUpdate(
        formula,
        define(labelFor(formula), Formula.Conjunction(List(Formula.Negation(Formula.Reference(eosName, Position.I)), compile(formula)))),
      )

  /** Compile a (not-yet-desugared) LTLf formula into a strict-future
    * `FormulaDag`, faithful to standard non-strict LTLf semantics (`X`/`F`/
    * `G`/`U` all include "now", matching e.g. Spot's `spot.from_ltlf`).
    */
  def compileToFuture(formula: LtlfFormula, atomicPropositions: List[String]): FormulaDag =
    val builder = Builder(atomicPropositions)
    val topFormula = builder.compile(desugar(formula))
    val outputName =
      topFormula match
        case Formula.Reference(name, Position.I) if builder.definitions.contains(name) => name
        case _ =>
          val name = "output_1"
          builder.definitions(name) = topFormula
          name
    FormulaDag(
      logic = Logic.FutureStrict,
      definitions = VectorMap.from(builder.definitions),
      output = Formula.Reference(outputName, Position.I),
      evaluationPoint = "i = 0 (LTLf semantics: the first input position, non-strict future operators)",
      alphabet = Some(builder.alphabet),
    )

  /** Compile LTLf text straight to the strict-past `FormulaDag` this
    * project's other backends (`LtlToBrasp`, Kind2, rIC3, ABC, ...)
    * consume. Per `Ltl.mirrorToPast`'s doc-comment, this describes the
    * REVERSAL of the original LTLf formula's language, not the formula's
    * own language — callers comparing against reference traces need to
    * reverse those traces too.
    */
  def compileToPast(text: String, atomicPropositions: List[String]): FormulaDag =
    Ltl.mirrorToPast(compileToFuture(parse(text), atomicPropositions))
