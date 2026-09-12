package brasp

import java.nio.file.{Files, Path}

/** Explicit routes for experiments. Production auto routing is unchanged. */
object CircuitStudy:
  def main(args: Array[String]): Unit =
    require(args.length == 3, "CircuitStudy INPUT ROUTE OUTPUT.aig")
    val start = System.nanoTime()
    val input = Path.of(args(0))
    val text = Files.readString(input)
    val dag = if args(0).endsWith(".ltl") then LtlText.parse(text)
      else BraspToLtl.translateProgram(BraspText.parse(text))
    val future = if dag.logic == Logic.FutureStrict then dag else Translator.mirrorToFuture(dag)
    val automaton = BooleanAutomaton.fromForwardPvwaa(Pvwaa.fromFuture2ltl(future))
    val compileSeconds = (System.nanoTime() - start) / 1e9
    val route = args(1)
    println(s"route=$route states=${automaton.source.states.size} goto=${automaton.gotoSupport.size} max_support=${automaton.support.values.map(_.size).maxOption.getOrElse(0)} full_cells=${BigInt(automaton.source.states.size) * BigInt(2).pow(automaton.gotoSupport.size)} support_cells=${automaton.support.values.map(s => BigInt(2).pow(s.size)).sum} realizable_cells=${automaton.source.states.map(s => BooleanAutomaton.realizableAbstractionCount(automaton, s)).sum} compile_seconds=$compileSeconds")
    val encodeStart = System.nanoTime()
    val model = route match
      case "realizable" => Aiger.generateSafety(automaton)
      case "support" => Aiger.generateSafety(automaton, reduceRealizable = false)
      case "full" =>
        val support = automaton.source.states.map(s => s -> automaton.gotoSupport).toMap
        val expanded = automaton.copy(support = support,
          supportIndex = support.view.mapValues(_.zipWithIndex.toMap).toMap)
        // diagonalOf fixes self/higher coordinates to false while reading
        // previously computed lower coordinates; no cyclic selector is built.
        Aiger.generateSafety(expanded, reduceRealizable = false)
      case other => throw IllegalArgumentException(s"unknown explicit study route: $other")
    val encodeSeconds = (System.nanoTime() - encodeStart) / 1e9
    Files.write(Path.of(args(2)), model)
    val header = new String(model.takeWhile(_ != '\n'.toByte), java.nio.charset.StandardCharsets.US_ASCII)
    println(s"route=$route states=${automaton.source.states.size} goto=${automaton.gotoSupport.size} max_support=${automaton.support.values.map(_.size).maxOption.getOrElse(0)} full_cells=${BigInt(automaton.source.states.size) * BigInt(2).pow(automaton.gotoSupport.size)} support_cells=${automaton.support.values.map(s => BigInt(2).pow(s.size)).sum} realizable_cells=${automaton.source.states.map(s => BooleanAutomaton.realizableAbstractionCount(automaton, s)).sum} compile_seconds=$compileSeconds encode_seconds=$encodeSeconds header=$header")
