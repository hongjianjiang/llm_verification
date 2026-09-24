package brasp

import scala.collection.immutable.VectorMap
import scala.collection.mutable

/** Variable elimination into an ordinary strict-past LTL DAG. Definitions
  * are retained, not recursively inlined. Only temporal operands lose their
  * query coordinates; references at the current position are ordinary DAG
  * abbreviations. Symbol-determined coordinates use the same cover as the
  * direct summary compiler; temporal coordinates remain unrestricted.
  */
object SharedOneVariableLtl:
  import Formula.*
  final case class Result(dag: FormulaDag, supportCases: BigInt, retainedCases: BigInt)

  def translate(dag: FormulaDag, reduceRealizable: Boolean = true): Result =
    def fail(s: String): Nothing = throw TwoLtlToOneVariable.TranslationTooLarge(s)
    if dag.logic != Logic.PastStrict then fail("shared LTL translation requires strict-past input")
    def body(n: String): Formula = dag.definitions.getOrElse(n, fail(s"unknown definition: $n"))
    def operands(f: Formula): List[Formula] = f match
      case Previous(Position.I, Position.J, p) => List(p)
      case Once(Position.I, Position.J, p) => List(p)
      case Historically(Position.I, Position.J, p) => List(p)
      case Since(Position.I, Position.J, p, q) => List(p, q)
      case _ => fail("expected a normalized strict-past temporal operator")
    val visited = mutable.Set.empty[String]
    val visiting = mutable.Set.empty[String]
    val definitionOrder = mutable.ArrayBuffer.empty[String]
    val symbols = mutable.Set.empty[String]
    def visit(f: Formula, predicate: Boolean = false): Unit = f match
      case Constant(_) => ()
      case Atom(AtomKind.EosAtom, _, _) => fail("past EOS tests are unsupported")
      case Atom(k, v, s) =>
        if !predicate && v != Position.I then fail("witness atom outside temporal operand")
        if k == AtomKind.SymbolAtom then symbols += s.get
      case Reference(n, v) =>
        if !predicate && v != Position.I then fail("witness reference outside temporal operand")
        if visiting(n) then fail(s"cyclic definition: $n")
        if !visited(n) then
          visiting += n
          visit(body(n))
          visiting -= n
          visited += n
          definitionOrder += n
      case Negation(p) => visit(p, predicate)
      case Conjunction(ps) => ps.foreach(visit(_, predicate))
      case Disjunction(ps) => ps.foreach(visit(_, predicate))
      case t =>
        if predicate then fail("name nested temporal operands in the formula DAG first")
        operands(t).foreach(visit(_, true))
    visit(dag.output)
    val alphabet = (dag.alphabet.getOrElse(Nil) ++ symbols.toList.sorted).distinct
    if alphabet.isEmpty then fail("shared LTL translation requires a nonempty alphabet")
    val staticMemo = mutable.Map.empty[Formula, Boolean]
    def isStatic(f: Formula): Boolean = staticMemo.getOrElseUpdate(f, f match
      case Constant(_) | Atom(_, _, _) => true
      case Reference(n, _) => isStatic(body(n))
      case Negation(p) => isStatic(p)
      case Conjunction(ps) => ps.forall(isStatic)
      case Disjunction(ps) => ps.forall(isStatic)
      case _ => false)
    val symbolMemo = mutable.Map.empty[(Formula, Option[String]), Boolean]
    def atSymbol(f: Formula, s: Option[String]): Boolean = symbolMemo.getOrElseUpdate((f, s), f match
      case Constant(v) => v
      case Atom(AtomKind.BosAtom, _, _) => s.isEmpty
      case Atom(k, _, a) => s.exists(Ltl.symbolMatches(k, a, _))
      case Reference(n, _) => atSymbol(body(n), s)
      case Negation(p) => !atSymbol(p, s)
      case Conjunction(ps) => ps.forall(atSymbol(_, s))
      case Disjunction(ps) => ps.exists(atSymbol(_, s))
      case _ => fail("non-static coordinate in symbol cover"))
    def keys(f: Formula): List[Formula] = f match
      case r @ Reference(_, Position.I) => List(r)
      case a @ Atom(_, Position.I, _) => List(a)
      case Negation(p) => keys(p)
      case Conjunction(ps) => ps.flatMap(keys)
      case Disjunction(ps) => ps.flatMap(keys)
      case _ => Nil

    val pool = mutable.Map.empty[Formula, Formula]
    def share(f: Formula): Formula = pool.getOrElseUpdate(f, f)
    def neg(f: Formula): Formula = f match
      case Constant(v) => Constant(!v)
      case Negation(p) => p
      case _ => share(Negation(f))
    def combine(ps: List[Formula], and: Boolean): Formula =
      if ps.contains(Constant(!and)) then Constant(!and)
      else
        val kept = ps.filterNot(_ == Constant(and)).distinct
        kept match
          case Nil => Constant(and)
          case p :: Nil => p
          case _ => share(if and then Conjunction(kept) else Disjunction(kept))

    var definitions = VectorMap.empty[String, Formula]
    val named = mutable.Map.empty[Formula, String]
    var serial = 0
    def name(f: Formula): Formula =
      val n = named.getOrElseUpdate(f, {
        var candidate = s"__ltl_case_$serial"
        serial += 1
        while dag.definitions.contains(candidate) do
          candidate = s"__ltl_case_$serial"
          serial += 1
        definitions = definitions.updated(candidate, f)
        candidate
      })
      Reference(n, Position.I)
    var supportCases = BigInt(0)
    var retainedCases = BigInt(0)
    val memo = mutable.Map.empty[Formula, Formula]
    def ensure(n: String): Unit =
      if !definitions.contains(n) then
        val translated = translateFormula(body(n))
        definitions = definitions.updated(n, translated)
    def substitute(f: Formula, beta: Map[Formula, Boolean]): Formula = f match
      // Only leaves can be coordinates. Looking up whole Boolean subtrees
      // here would repeatedly hash their descendants on every case split.
      case Reference(_, Position.I) | Atom(_, Position.I, _) => Constant(beta(f))
      case Reference(n, Position.J) => ensure(n); f
      case Negation(p) => neg(substitute(p, beta))
      case Conjunction(ps) => combine(ps.map(substitute(_, beta)), true)
      case Disjunction(ps) => combine(ps.map(substitute(_, beta)), false)
      case _ => f
    def translateFormula(f: Formula): Formula = memo.getOrElseUpdate(f, f match
      case Constant(_) | Atom(_, _, _) => f
      case Reference(n, _) => ensure(n); f
      case Negation(p) => neg(translateFormula(p))
      case Conjunction(ps) => combine(ps.map(translateFormula), true)
      case Disjunction(ps) => combine(ps.map(translateFormula), false)
      case t =>
        val ps = operands(t)
        val support = ps.flatMap(keys).distinct.toVector
        val fixed = support.indices.filter(i => reduceRealizable && isStatic(support(i))).toVector
        val free = support.indices.filterNot(fixed.contains).toVector
        val patterns = (None :: alphabet.map(Some(_))).map(s => fixed.map(i => atSymbol(support(i), s))).distinct
        supportCases += BigInt(2).pow(support.size)
        retainedCases += BigInt(patterns.size) * BigInt(2).pow(free.size)
        if retainedCases * alphabet.size > 500000 then
          fail(s"shared LTL case split exceeds 500000 case-symbol evaluations: $retainedCases cases")
        val guards = support.map(translateFormula)
        val branches = for
          pattern <- patterns
          bits <- 0 until (1 << free.size)
        yield
          val values = Array.fill(support.size)(false)
          fixed.zip(pattern).foreach((i, v) => values(i) = v)
          free.zipWithIndex.foreach((i, bit) => values(i) = ((bits >> bit) & 1) != 0)
          val beta = support.zip(values.toVector).toMap
          val qs = ps.map(substitute(_, beta))
          val specialized = t match
            case Previous(_, _, _) => Previous(Position.I, Position.J, qs.head)
            case Once(_, _, _) => Once(Position.I, Position.J, qs.head)
            case Historically(_, _, _) => Historically(Position.I, Position.J, qs.head)
            case Since(_, _, _, _) => Since(Position.I, Position.J, qs.head, qs(1))
            case _ => fail("unsupported temporal operator")
          combine(guards.zip(values).map((g, v) => if v then g else neg(g)).toList :+ name(specialized), true)
        combine(branches, false))
    // Translate dependencies first: ensure/substitute then only read already
    // translated definitions, even for the 64,000-step benchmark chains.
    definitionOrder.foreach(ensure)
    val output = translateFormula(dag.output)
    Result(dag.copy(definitions = definitions, output = output, alphabet = Some(alphabet)), supportCases, retainedCases)
