package brasp

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.collection.mutable

/** Empirically checks the question that decides whether extending
  * `DirectPvwaa` to unsupported ("non-simple") `Goto` targets can stay
  * within plain BTOR2/rIC3/ABC (one extra "shadow" register pair per Goto
  * edge, exactly mirroring the existing `active`/`guess` machinery but
  * rooted at the position the edge fires from) or genuinely needs a
  * DQBF-shaped encoding (unboundedly many simultaneously-live differently
  * rooted instances of the same state).
  *
  * The question, made concrete: while `Pvwaa.accepts`'s own recursive
  * `run`/`satisfy` (the trusted reference semantics) is evaluating some
  * `run(state, pebble1, head1)` for a "non-simple" Goto target (a state
  * whose own transition formula contains `Carry`/`Leave`, i.e. exactly
  * what `DirectPvwaa.checkGotoTargetsAreSimple` rejects), does it ever
  * recursively need `run(state, pebble2, head2)` for the *same* state with
  * `pebble2 != pebble1`, *before* the first has resolved? If never (across
  * every word tried), one shadow register pair per Goto edge suffices — a
  * same-day extension, no DQBF solver needed. If it does, a fixed circuit
  * can't statically allocate enough registers, which is exactly the
  * situation a 2-DQBF-style existential-function encoding is for.
  */
object GotoOverlapCheck:

  import PositiveFormula.*

  /** Every Goto-target whose own transitions are not "simple" in
    * `DirectPvwaa.checkGotoTargetsAreSimple`'s sense — collected instead of
    * thrown, since here we want to go on and probe them.
    */
  def nonSimpleGotoTargets(automaton: ForwardPVWAA): Set[String] =
    val gotoTargets = mutable.LinkedHashSet.empty[String]
    def collect(f: PositiveFormula): Unit = f match
      case PositiveConstant(_)                 => ()
      case _: SymbolTest                        => ()
      case PositiveAnd(operands)                => operands.foreach(collect)
      case PositiveOr(operands)                 => operands.foreach(collect)
      case TransitionAtom(target, Action.Goto)  => gotoTargets += target
      case TransitionAtom(_, Action.Carry | Action.Leave) => ()
    for state <- automaton.states do collect(automaton.transitions(state))

    def isSimple(state: String, seen: Set[String]): Boolean =
      if seen.contains(state) then true
      else
        def check(f: PositiveFormula): Boolean = f match
          case PositiveConstant(_)                            => true
          case _: SymbolTest                                   => true
          case PositiveAnd(operands)                           => operands.forall(check)
          case PositiveOr(operands)                            => operands.forall(check)
          case TransitionAtom(_, Action.Carry | Action.Leave) => false
          case TransitionAtom(target, Action.Goto)             => isSimple(target, seen + state)
        check(automaton.transitions(state))

    gotoTargets.filterNot(target => isSimple(target, Set.empty)).toSet

  final case class OverlapWitness(state: String, outerPebble: Int, innerPebble: Int, word: IndexedSeq[String])

  /** Mirrors `Pvwaa.accepts`'s `run`/`satisfy` exactly, but additionally
    * maintains, per watched state, the stack of pebble values for which an
    * evaluation is currently open (entered but not yet resolved) — pushed
    * on entry to `run(state, pebble, pebble)` (a genuine "root": the point
    * where `head == pebble`, i.e. either the automaton's own initial call
    * or the moment a `Goto`/fresh `Carry` re-anchors this state), popped
    * once that call returns. A push that finds a *different* pebble
    * already on the stack for the same state is exactly the overlap this
    * whole check is looking for.
    */
  def findOverlap(automaton: ForwardPVWAA, word: IndexedSeq[String], watch: Set[String]): Option[OverlapWitness] =
    val length = word.length
    val memo = mutable.HashMap.empty[(String, Int, Int), Boolean]
    val openStack = mutable.HashMap.empty[String, List[Int]].withDefaultValue(Nil)
    var witness: Option[OverlapWitness] = None

    def run(state: String, pebble: Int, head: Int): Boolean =
      val key = (state, pebble, head)
      memo.get(key) match
        case Some(value) => value
        case None =>
          val isRoot = head == pebble && watch.contains(state)
          if isRoot then
            val existing = openStack(state)
            if witness.isEmpty then
              existing.find(_ != pebble).foreach(other => witness = Some(OverlapWitness(state, other, pebble, word)))
            openStack(state) = pebble :: existing
          val result =
            if head == length then automaton.finalStates.contains(state)
            else satisfy(automaton.transitions(state), word(head), pebble, head)
          if isRoot then openStack(state) = openStack(state).tail
          memo(key) = result
          result

    def satisfy(formula: PositiveFormula, symbol: String, pebble: Int, head: Int): Boolean = formula match
      case PositiveConstant(value)     => value
      case SymbolTest(kind, sym, dual) => Ltl.symbolMatches(kind, sym, symbol) != dual
      case PositiveAnd(operands)       => operands.forall(o => satisfy(o, symbol, pebble, head))
      case PositiveOr(operands)        => operands.exists(o => satisfy(o, symbol, pebble, head))
      case TransitionAtom(state, action) =>
        action match
          case Action.Goto  => run(state, pebble, pebble)
          case Action.Leave => run(state, pebble, head + 1)
          case Action.Carry => run(state, head + 1, head + 1)

    run(automaton.initialState, 0, 0)
    witness

  /** All words over `alphabet` of length `0..maxLength`, shortest first. */
  private def allWords(alphabet: List[String], maxLength: Int): Iterator[IndexedSeq[String]] =
    def go(len: Int): Iterator[IndexedSeq[String]] =
      if len == 0 then Iterator(Vector.empty[String])
      else go(len - 1).flatMap(prefix => alphabet.iterator.map(sym => prefix :+ sym))
    (0 to maxLength).iterator.flatMap(go)

  def readFile(file: File): String = new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)

  def loadAutomaton(input: File): ForwardPVWAA =
    val dag =
      if input.getName.endsWith(".ltl") then LtlText.parse(readFile(input))
      else BraspToLtl.translateProgram(BraspText.parse(readFile(input)))
    val future = if dag.logic == Logic.FutureStrict then dag else Translator.mirrorToFuture(dag)
    Pvwaa.fromFuture2ltl(future)

  def main(args: Array[String]): Unit =
    if args.isEmpty then
      System.err.println("usage: GotoOverlapCheck FILE.brasp|FILE.ltl [maxWordLength]")
      sys.exit(2)
    val input = new File(args(0))
    val maxLength = if args.length > 1 then args(1).toInt else 14

    val automaton = loadAutomaton(input)
    val watch = nonSimpleGotoTargets(automaton)

    println(s"${input.getName}: ${automaton.states.size} states, alphabet {${automaton.alphabet.mkString(", ")}}")
    if watch.isEmpty then
      println("no non-simple goto-targets in this automaton — DirectPvwaa already supports it as-is, nothing to check.")
    else
      println(s"non-simple goto-targets to watch: ${watch.toList.sorted.mkString(", ")}")
      println(s"trying every word of length 0..$maxLength over the alphabet...")
      var checked = 0
      var found: Option[OverlapWitness] = None
      val it = allWords(automaton.alphabet, maxLength)
      while it.hasNext && found.isEmpty do
        val word = it.next()
        checked += 1
        found = findOverlap(automaton, word, watch)
      found match
        case Some(w) =>
          println(s"OVERLAP FOUND after $checked word(s): state '${w.state}' needed simultaneously-open " +
            s"instances rooted at pebble ${w.outerPebble} and pebble ${w.innerPebble} on word " +
            s"\"${w.word.mkString}\" (length ${w.word.length}).")
          println("-> a single shadow register per Goto edge is NOT enough; this needs the DQBF-shaped encoding.")
        case None =>
          println(s"no overlap in any of the $checked words tried (length 0..$maxLength).")
          println("-> one shadow register pair per Goto edge, rooted at the firing position, looks sufficient here — " +
            "plain BTOR2/rIC3/ABC, no DQBF solver needed for this automaton. (Not a proof for all words/lengths.)")
