package brasp

import scala.collection.mutable
import java.io.ByteArrayOutputStream

/** Strict-past formula DAG -> summary circuit, without automaton construction.
  * A temporal row records its inclusive prefix fold for each query valuation.
  * The current formula reads the OLD row; next rows incorporate this position.
  * Physical latch resets encode the fold after BOS, not an empty prefix.
  */
object DirectSummary:
  import Formula.*
  final case class Result(model: Array[Byte], rows: Int, maxSupport: Int,
                          supportCells: BigInt, retainedCells: BigInt)
  private def temporal(f: Formula): Boolean = f match
    case Previous(_, _, _) | Once(_, _, _) | Historically(_, _, _) | Since(_, _, _, _) => true
    case _ => false
  private def operands(f: Formula): List[Formula] = f match
    case Previous(Position.I, Position.J, p) => List(p)
    case Once(Position.I, Position.J, p) => List(p)
    case Historically(Position.I, Position.J, p) => List(p)
    case Since(Position.I, Position.J, a, b) => List(a, b)
    case _ => throw AigerError("direct summaries require strict-past operators binding i,j")

  def generate(dag: FormulaDag, reduceRealizable: Boolean = true): Result =
    if dag.logic != Logic.PastStrict then throw AigerError("direct summaries require strict-past input")
    val rows = mutable.LinkedHashSet.empty[Formula]
    val visited = mutable.Set.empty[String]
    val visiting = mutable.Set.empty[String]
    val symbols = mutable.LinkedHashSet.empty[String]
    def definition(name: String): Formula = dag.definitions.getOrElse(name,
      throw AigerError(s"unknown definition: $name"))
    def visit(f: Formula, predicate: Boolean = false): Unit = f match
      case Constant(_) => ()
      case Atom(AtomKind.EosAtom, _, _) => throw AigerError("direct summaries do not support past EOS tests")
      case Atom(kind, position, symbol) =>
        if !predicate && position != Position.I then throw AigerError("witness atom outside temporal operand")
        if kind == AtomKind.SymbolAtom then symbols += symbol.get
      case Reference(name, position) =>
        if !predicate && position != Position.I then throw AigerError("witness reference outside temporal operand")
        if visiting(name) then throw AigerError(s"cyclic definition: $name")
        if !visited(name) then
          visiting += name
          visit(definition(name))
          visiting -= name
          visited += name
      case Negation(p) => visit(p, predicate)
      case Conjunction(ps) => ps.foreach(visit(_, predicate))
      case Disjunction(ps) => ps.foreach(visit(_, predicate))
      case t if temporal(t) =>
        if predicate then throw AigerError("name nested temporal operands in the formula DAG first")
        operands(t).foreach(visit(_, true))
        rows += t
      case _ => throw AigerError("future operator in direct strict-past circuit")
    visit(dag.output)
    val alphabet = (dag.alphabet.getOrElse(Nil) ++ symbols.toList.sorted).distinct
    if alphabet.isEmpty then throw AigerError("direct summaries require a nonempty alphabet")
    val allRows = rows.toVector

    def queryKeys(f: Formula): List[Formula] = f match
      case r @ Reference(_, Position.I) => List(r)
      case a @ Atom(_, Position.I, _) => List(a)
      case Negation(p) => queryKeys(p)
      case Conjunction(ps) => ps.flatMap(queryKeys)
      case Disjunction(ps) => ps.flatMap(queryKeys)
      case _ => Nil
    val supports = allRows.map(t => t -> operands(t).flatMap(queryKeys).distinct.toVector).toMap
    val staticMemo = mutable.Map.empty[Formula, Boolean]
    def isStatic(f: Formula): Boolean = staticMemo.getOrElseUpdate(f, f match
      case Constant(_) | Atom(_, _, _) => true
      case Reference(name, _) => isStatic(definition(name))
      case Negation(p) => isStatic(p)
      case Conjunction(ps) => ps.forall(isStatic)
      case Disjunction(ps) => ps.forall(isStatic)
      case _ => false)
    def atomAt(kind: AtomKind, symbol: Option[String], letter: Option[String]): Boolean =
      if kind == AtomKind.BosAtom then letter.isEmpty
      else letter.exists(Ltl.symbolMatches(kind, symbol, _))
    def empty(t: Formula): Boolean = t match
      case Historically(_, _, _) => true
      case _ => false
    // Temporal subformulas are evaluated at BOS only here; no earlier positions exist.
    // BOS values are queried once per cell; memoize them so deep definition chains stay linear.
    val boundaryMemo = mutable.Map.empty[Formula, Boolean]
    def atBoundaryOrStatic(f: Formula, letter: Option[String]): Boolean =
      if letter.isEmpty then boundaryMemo.get(f) match
        case Some(v) => v
        case None => val v = boundaryOrStatic(f, letter); boundaryMemo(f) = v; v
      else boundaryOrStatic(f, letter)
    def boundaryOrStatic(f: Formula, letter: Option[String]): Boolean = f match
      case Constant(v) => v
      case Atom(kind, _, symbol) => atomAt(kind, symbol, letter)
      case Reference(name, _) => atBoundaryOrStatic(definition(name), letter)
      case Negation(p) => !atBoundaryOrStatic(p, letter)
      case Conjunction(ps) => ps.forall(atBoundaryOrStatic(_, letter))
      case Disjunction(ps) => ps.exists(atBoundaryOrStatic(_, letter))
      case t if temporal(t) && letter.isEmpty => empty(t)
      case _ => throw AigerError("non-static expression in symbol abstraction")
    val pinned = allRows.map { t =>
      val keys = supports(t)
      val fixed = keys.indices.filter(i => reduceRealizable && isStatic(keys(i))).toVector
      val free = keys.indices.filterNot(fixed.contains).toVector
      val patterns = (None :: alphabet.map(Some(_))).map(s => fixed.map(i => atBoundaryOrStatic(keys(i), s))).distinct
      t -> (fixed, free, patterns)
    }.toMap
    val supportCells = allRows.map(t => BigInt(2).pow(supports(t).size)).sum
    val retained = allRows.map { t =>
      val (_, free, patterns) = pinned(t)
      BigInt(patterns.size) * BigInt(2).pow(free.size)
    }.sum
    // Matched with the PVWAA backend's budget; -Ddirect.maxCellSymbols=N overrides it for scaling runs.
    val budget = BigInt(sys.props.getOrElse("direct.maxCellSymbols", "500000"))
    if retained * alphabet.size > budget then throw AigerError(s"direct summary encoding exceeds $budget cell-symbol evaluations: $retained cells")
    val assignments = allRows.map { t =>
      val (fixed, free, patterns) = pinned(t)
      val values = for
        p <- patterns.toVector
        bits <- 0 until (1 << free.size)
      yield
        val a = Array.fill(supports(t).size)(false)
        fixed.zip(p).foreach((i, v) => a(i) = v)
        free.zipWithIndex.foreach((i, bit) => a(i) = ((bits >> bit) & 1) != 0)
        a.toVector
      t -> values
    }.toMap
    val b = new Aiger.Builder
    def bool(v: Boolean): Int = if v then b.True else b.False
    val width = if alphabet.size <= 1 then 1 else 32 - Integer.numberOfLeadingZeros(alphabet.size - 1)
    val inputs = Vector.fill(width)(b.lit(b.freshVar()))
    val cells = (for t <- allRows; a <- assignments(t) yield (t, a)).toVector
    val registers = cells.map(k => k -> b.lit(b.freshVar())).toMap
    val invalidRegister = if BigInt(alphabet.size) < BigInt(2).pow(width) then Some(b.lit(b.freshVar())) else None
    val symbolTests = alphabet.indices.map { index =>
      inputs.zipWithIndex.foldLeft(b.True) { case (acc, (lit, bit)) =>
        b.and(acc, if ((index >> bit) & 1) == 1 then lit else b.not(lit))
      }
    }.toVector
    val valid = symbolTests.foldLeft(b.False)(b.or)
    val badCode = b.or(invalidRegister.getOrElse(b.False), b.not(valid))

    def predicate(f: Formula, beta: Map[Formula, Int], witness: Formula => Int): Int = f match
      case Constant(v) => bool(v)
      case r @ Reference(_, Position.I) => beta(r)
      case a @ Atom(_, Position.I, _) => beta(a)
      case Reference(name, Position.J) => witness(Reference(name, Position.I))
      case Atom(kind, Position.J, symbol) => witness(Atom(kind, Position.I, symbol))
      case Negation(p) => b.not(predicate(p, beta, witness))
      case Conjunction(ps) => ps.foldLeft(b.True)((acc, p) => b.and(acc, predicate(p, beta, witness)))
      case Disjunction(ps) => ps.foldLeft(b.False)((acc, p) => b.or(acc, predicate(p, beta, witness)))
      case _ => throw AigerError("non-Boolean temporal operand")
    def update(t: Formula, beta: Map[Formula, Int], old: Int, witness: Formula => Int): Int =
      val ps = operands(t).map(predicate(_, beta, witness))
      t match
        case Previous(_, _, _) => ps.head
        case Once(_, _, _) => b.or(old, ps.head)
        case Historically(_, _, _) => b.and(old, ps.head)
        case Since(_, _, _, _) => b.or(ps(1), b.and(ps.head, old))
        case _ => throw AigerError("unknown summary operator")
    val resets = cells.map { case k @ (t, a) =>
      val beta = supports(t).zip(a.map(bool)).toMap
      val init = update(t, beta, bool(empty(t)), f => bool(atBoundaryOrStatic(f, None)))
      require(init == b.True || init == b.False, "nonconstant initialization")
      k -> (init == b.True)
    }.toMap
    def old(k: (Formula, Vector[Boolean])): Int = b.flip(registers(k), resets(k))
    val current = mutable.Map.empty[Formula, Int]
    def eval(f: Formula): Int = current.getOrElseUpdate(f, f match
      case Constant(v) => bool(v)
      case Atom(kind, Position.I, symbol) =>
        alphabet.indices.filter(i => atomAt(kind, symbol, Some(alphabet(i)))).foldLeft(b.False)((acc, i) => b.or(acc, symbolTests(i)))
      case Reference(name, Position.I) => eval(definition(name))
      case Negation(p) => b.not(eval(p))
      case Conjunction(ps) => ps.foldLeft(b.True)((acc, p) => b.and(acc, eval(p)))
      case Disjunction(ps) => ps.foldLeft(b.False)((acc, p) => b.or(acc, eval(p)))
      case t if temporal(t) =>
        val query = supports(t).map(eval)
        assignments(t).foldLeft(b.False) { (acc, a) =>
          val matches = query.zip(a).foldLeft(b.True) { case (lit, (q, v)) => b.and(lit, if v then q else b.not(q)) }
          b.or(acc, b.and(matches, old((t, a))))
        }
      case _ => throw AigerError("unsupported direct formula"))
    val output = b.and(eval(dag.output), b.not(badCode))
    val next = cells.map { case k @ (t, a) =>
      b.flip(update(t, supports(t).zip(a.map(bool)).toMap, old(k), eval), resets(k))
    } ++ invalidRegister.map(_ => badCode)
    val out = new ByteArrayOutputStream()
    def line(s: String): Unit = out.write((s + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII))
    line(s"aig ${b.maxVar} $width ${next.size} 1 ${b.andGates.size}")
    next.foreach(n => line(n.toString))
    line(output.toString)
    b.andGates.foreach { (lit, x, y) =>
      Aiger.encodeDelta(out, lit - math.max(x, y))
      Aiger.encodeDelta(out, math.max(x, y) - math.min(x, y))
    }
    line("c")
    alphabet.zipWithIndex.foreach((s, i) => line(s"symbol = $i represents input symbol '$s' (bit-blasted, $width-bit)."))
    Result(out.toByteArray, allRows.size, supports.values.map(_.size).maxOption.getOrElse(0), supportCells, retained)
