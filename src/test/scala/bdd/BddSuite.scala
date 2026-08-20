package brasp

class BddSuite extends munit.FunSuite:

  private def allAssignments(vars: List[String]): List[Map[String, Boolean]] =
    if vars.isEmpty then List(Map.empty)
    else for rest <- allAssignments(vars.tail); bit <- List(false, true) yield rest.updated(vars.head, bit)

  test("hash-consing: structurally identical subgraphs are the same object") {
    val mgr = Bdd.Manager(List("a", "b"))
    val x1 = mgr.and(mgr.variable("a"), mgr.variable("b"))
    val x2 = mgr.and(mgr.variable("a"), mgr.variable("b"))
    assert(x1.id == x2.id, "identical (op, operands) should hash-cons to the same node id")
  }

  test("reduction: a variable whose two branches agree is elided") {
    val mgr = Bdd.Manager(List("a"))
    // ite(a, True, True) == True regardless of a's order (variable is redundant).
    val node = mgr.ite(mgr.variable("a"), Bdd.True, Bdd.True)
    assertEquals(node, Bdd.True: Bdd.Node)
  }

  test("and/or/not match brute-force truth tables over 4 variables") {
    val vars = List("a", "b", "c", "d")
    val mgr = Bdd.Manager(vars)
    val leaves = vars.map(v => v -> mgr.variable(v)).toMap

    // (a & b) | (!c & d), built two different ways, must agree with a direct evaluator.
    val bdd = mgr.or(mgr.and(leaves("a"), leaves("b")), mgr.and(mgr.not(leaves("c")), leaves("d")))

    for assignment <- allAssignments(vars) do
      val expected = (assignment("a") && assignment("b")) || (!assignment("c") && assignment("d"))
      assertEquals(mgr.eval(bdd, assignment), expected, s"mismatch at $assignment")
  }

  test("ite is the universal combinator: ite(c, t, e) matches (c&t)|(!c&e)") {
    val vars = List("c", "t", "e")
    val mgr = Bdd.Manager(vars)
    val leaves = vars.map(v => v -> mgr.variable(v)).toMap
    val bdd = mgr.ite(leaves("c"), leaves("t"), leaves("e"))
    for assignment <- allAssignments(vars) do
      val expected = if assignment("c") then assignment("t") else assignment("e")
      assertEquals(mgr.eval(bdd, assignment), expected, s"mismatch at $assignment")
  }

  test("eval defaults unassigned variables to false, matching the old diagonal default") {
    val mgr = Bdd.Manager(List("a", "b"))
    val bdd = mgr.and(mgr.variable("a"), mgr.variable("b"))
    assertEquals(mgr.eval(bdd, Map("a" -> true).withDefaultValue(false)), false)
    assertEquals(mgr.eval(bdd, Map("a" -> true, "b" -> true).withDefaultValue(false)), true)
  }

  test("size counts distinct nodes, not 2^n truth-table rows") {
    val n = 10
    val vars = (0 until n).map(i => s"v$i").toList
    val mgr = Bdd.Manager(vars)
    // A function of a single variable, disguised behind n irrelevant conjuncts
    // eliminated by reduction: v0 & (v1|!v1) & (v2|!v2) & ... == v0.
    val redundant = vars.tail.map(v => mgr.or(mgr.variable(v), mgr.not(mgr.variable(v))))
    val bdd = mgr.and(mgr.variable(vars.head) :: redundant)
    assertEquals(bdd, mgr.variable(vars.head))
    assert(Bdd.size(bdd) <= 1, s"expected a single-node BDD after reduction, got size ${Bdd.size(bdd)}")
    assertEquals(Bdd.usedVariables(bdd), Set(vars.head))
  }

  test("manager rejects an out-of-order variable") {
    val mgr = Bdd.Manager(List("a"))
    intercept[BddError](mgr.variable("not-in-order"))
  }

  test("manager enforces its node-count cap") {
    val vars = (0 until 20).map(i => s"v$i").toList
    val mgr = Bdd.Manager(vars, maxNodes = 5)
    // Parity's BDD is linear-size (one node per variable) but still exceeds a cap of 5.
    intercept[BddError] {
      vars.map(mgr.variable).reduceLeft((acc, v) => mgr.ite(acc, mgr.not(v), v))
    }
  }
