package brasp

import scala.collection.mutable

/** Hash-consed reduced ordered binary decision diagrams (ROBDDs) over a
  * fixed global variable order.
  *
  * `BooleanAutomaton`'s per-state Boolean-summary function used to be
  * represented as an explicit, unshared truth table (`Vector[Boolean]` of
  * size `2^|support(state)|`) — see that file's history. That representation
  * has a fixed shape (always `2^n` cells) regardless of which function it
  * currently holds, which is exactly why it was usable as hardware-circuit
  * state in the first place (a circuit's topology is fixed at synthesis
  * time; only the *values* flowing through it vary). But it pays for that
  * fixed shape by materializing every one of the `2^n` cells even when the
  * function has enormous redundancy — which real specifications routinely
  * do (an arithmetic/counter-style formula, in particular, is close to the
  * textbook case an ROBDD is good at).
  *
  * A `Node` here is *not* meant to be encoded directly as hardware state —
  * a BDD's shape varies per function, and a fixed circuit can't represent a
  * varying shape. It exists purely to make `BooleanAutomaton`'s internal
  * `transition`/`diagonal`/`reachable` computations cheap: building a
  * state's new summary via `Manager.and`/`or`/`ite` costs time proportional
  * to the *produced* graph size (which collapses via hash-consing whenever
  * sub-formulas coincide, including — crucially — the `Leave` case, which
  * now just re-uses another state's already-built `Node` unchanged, no
  * per-state local-index bookkeeping needed at all), not `2^n`. Once
  * `BooleanAutomaton.reachable` can therefore actually finish exploring a
  * formula's *true* reachable state space, that small explicit result is
  * what backends should encode into hardware — not this graph's shape.
  */
final case class BddError(message: String) extends RuntimeException(message)

object Bdd:

  sealed trait Node:
    def id: Int

  case object True extends Node:
    val id = 0
  case object False extends Node:
    val id = 1

  /** `id` is assigned once per *distinct* `(variable, low, high)` triple by
    * `Manager.mkNode` (hash-consing) — two `Branch`es are semantically equal
    * iff they are reference-equal, so `equals`/`hashCode` are defined
    * directly on `id` rather than the default case-class field-by-field
    * comparison, which would otherwise re-walk (an already-deduplicated,
    * so redundant) subgraph on every comparison.
    */
  final class Branch private[Bdd] (val variable: String, val low: Node, val high: Node, val id: Int) extends Node:
    override def equals(other: Any): Boolean = other match
      case b: Branch => b.id == id
      case _         => false
    override def hashCode(): Int = id
    override def toString: String = s"Branch($variable, $low, $high)#$id"

  /** Total size of a node's DAG (distinct sub-nodes, including itself but
    * excluding the shared `True`/`False` terminals) — a meaningful "how big
    * is this function, really" metric, unlike the old `2^|support|` bound
    * which was the same for every function over `n` variables regardless of
    * its actual complexity.
    */
  def size(node: Node): Int =
    val seen = mutable.Set.empty[Int]
    def visit(n: Node): Unit = n match
      case True | False => ()
      case b: Branch =>
        if !seen.contains(b.id) then
          seen += b.id
          visit(b.low)
          visit(b.high)
    visit(node)
    seen.size

  /** Every distinct variable `node` actually tests, in the manager's global
    * order — a state's *true* dependency set, which can be (and often is,
    * after reduction collapses redundant tests) smaller than the syntactic
    * `goto`/`leave` closure `BooleanAutomaton.supportOf` used to compute.
    */
  def usedVariables(node: Node): Set[String] =
    val seen = mutable.Set.empty[Int]
    val vars = mutable.Set.empty[String]
    def visit(n: Node): Unit = n match
      case True | False => ()
      case b: Branch =>
        if !seen.contains(b.id) then
          seen += b.id
          vars += b.variable
          visit(b.low)
          visit(b.high)
    visit(node)
    vars.toSet

  /** Owns the hash-consing unique table and the `ite` memo cache for one
    * fixed variable order. A `Node` built by one `Manager` must never be
    * passed to another (their `id` numbering isn't comparable) — every BDD
    * in a single `ReverseBooleanAutomaton` shares one `Manager`, which is
    * exactly what lets `Leave` reuse another state's `Node` as-is: same
    * manager, same variable identities, no translation needed.
    *
    * `order`'s ranking must be total over every variable ever passed to
    * `variable`/`mkNode` — `ReverseBooleanAutomaton.gotoSupport`'s existing
    * rank order is used for this (see `BooleanAutomaton.fromForwardPvwaa`).
    * `ite`'s recursion below relies on it to always recurse on the
    * lowest-ranked variable among its three operands, which is what keeps
    * every produced node's variable order consistent (a precondition for
    * hash-consing to actually detect equivalent subgraphs as equal).
    *
    * Rejects a construction outright once the unique table would exceed
    * `maxNodes` distinct branch nodes — the BDD analogue of the old
    * per-state `2^|support| <= 2^24` cap (`BooleanAutomaton.checkSupportSize`),
    * except sized against the graph's *actual* measured complexity instead
    * of a syntactic (and, for exactly the formulas motivating this file,
    * wildly pessimistic) bound.
    */
  final class Manager(order: List[String], maxNodes: Int = 4_000_000):
    private val rank: Map[String, Int] = order.zipWithIndex.toMap
    private def rankOf(variable: String): Int =
      rank.getOrElse(variable, throw BddError(s"variable '$variable' is not in this manager's order"))

    private val uniqueTable = mutable.HashMap.empty[(String, Int, Int), Branch]
    private var nextId = 2 // 0/1 reserved for True/False

    def nodeCount: Int = uniqueTable.size

    /** The ROBDD "make" operation: reduction rule 1 (a node whose two
      * children are identical is redundant — the variable never affects the
      * outcome, so skip it) followed by reduction rule 2 (hash-cons against
      * any existing node with the same `(variable, low, high)`, so
      * structurally identical subgraphs are always the same object).
      */
    private def mkNode(variable: String, low: Node, high: Node): Node =
      if low.id == high.id then low
      else
        uniqueTable.getOrElseUpdate(
          (variable, low.id, high.id), {
            if uniqueTable.size >= maxNodes then
              throw BddError(
                s"BDD construction exceeded the node-count limit of $maxNodes while branching on '$variable' " +
                  "— this formula's Boolean-summary functions are too complex for this backend's encoding"
              )
            val id = nextId
            nextId += 1
            new Branch(variable, low, high, id)
          },
        )

    def variable(name: String): Node =
      rankOf(name) // validates membership in `order` even though the rank value itself isn't used here
      mkNode(name, False, True)

    private val iteCache = mutable.HashMap.empty[(Int, Int, Int), Node]

    /** `if condition then thenNode else elseNode`, the universal BDD
      * combinator every other operation (`and`/`or`/`not`) is defined in
      * terms of — the standard variable-order recursive `apply` algorithm,
      * memoized on the three operand ids (safe: a BDD node's meaning is
      * fully determined by its id, so equal-id triples always recurse to
      * an equal result).
      */
    def ite(condition: Node, thenNode: Node, elseNode: Node): Node =
      (condition, thenNode, elseNode) match
        case (True, t, _)      => t
        case (False, _, e)     => e
        case (c, True, False)  => c
        case (c, t, e) if t.id == e.id => t
        case _ =>
          iteCache.getOrElseUpdate(
            (condition.id, thenNode.id, elseNode.id), {
              val topVariable = List(condition, thenNode, elseNode).collect { case b: Branch => rankOf(b.variable) }.min
              def restrict(node: Node, value: Boolean): Node = node match
                case b: Branch if rankOf(b.variable) == topVariable => if value then b.high else b.low
                case other                                          => other
              val variable = order(topVariable)
              val low = ite(restrict(condition, false), restrict(thenNode, false), restrict(elseNode, false))
              val high = ite(restrict(condition, true), restrict(thenNode, true), restrict(elseNode, true))
              mkNode(variable, low, high)
            },
          )

    def not(a: Node): Node = ite(a, False, True)
    def and(a: Node, b: Node): Node = ite(a, b, False)
    def or(a: Node, b: Node): Node = ite(a, True, b)

    def and(nodes: IterableOnce[Node]): Node = nodes.iterator.foldLeft(True: Node)(and)
    def or(nodes: IterableOnce[Node]): Node = nodes.iterator.foldLeft(False: Node)(or)

    /** Evaluate `node` under `assignment`, walking down exactly one path
      * (root to a terminal) — `O(path length)`, never `O(|node|)`, and
      * never even inspects a variable the (already-reduced) graph doesn't
      * actually test along that path. `assignment` returning `false` for a
      * variable it has no opinion on matches
      * `BooleanAutomaton.diagonal`'s old `result.getOrElse(goto, false)`
      * default for not-yet-resolved (same-or-higher-rank) support atoms.
      */
    def eval(node: Node, assignment: String => Boolean): Boolean = node match
      case True      => true
      case False     => false
      case b: Branch => eval(if assignment(b.variable) then b.high else b.low, assignment)
