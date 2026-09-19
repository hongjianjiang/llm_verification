package brasp

import java.nio.file.{Files, Path}

/** One-off check that `TwoLtlToOneVariable` preserves the language on real
  * benchmark instances, not just the two formulas `TranslatorSuite` covers.
  *
  * Every baseline except the PVWAA route goes through this translation, so
  * agreement between those baselines says nothing about it: a bug here would
  * make them all wrong the same way. This compares the source formula and its
  * translation under `Ltl.evaluate`, the reference semantics, which no
  * automaton or solver is involved in, on every word up to a length bound.
  *
  * Usage: `Test/runMain brasp.OneVariableConsistency MAX_WORDS FILE...`; the
  * length bound per file is the largest one whose word count stays within
  * MAX_WORDS.
  */
object OneVariableConsistency:
  private def words(length: Int, alphabet: IndexedSeq[String]): Iterator[IndexedSeq[String]] =
    if length == 0 then Iterator.single(IndexedSeq.empty)
    else words(length - 1, alphabet).flatMap(prefix => alphabet.iterator.map(prefix :+ _))

  /** On a large-stack thread, like `Translator.main`: the benchmark formulas
    * nest far deeper than the default main-thread stack allows.
    */
  def main(args: Array[String]): Unit =
    var code = 0
    val worker = new Thread(null, () => code = run(args), "consistency-worker", 1024L * 1024 * 1024)
    worker.start()
    worker.join()
    if code != 0 then sys.exit(code)

  private def run(args: Array[String]): Int =
    val budget = args.head.toLong
    var failures = 0
    for file <- args.tail do
      val text = Files.readString(Path.of(file))
      val source =
        if file.endsWith(".ltl") then LtlText.parse(text)
        else BraspToLtl.translateProgram(BraspText.parse(text))
      val alphabet = source.alphabet.getOrElse(Nil).toIndexedSeq
      val result =
        try Right(TwoLtlToOneVariable.translate(source))
        catch case TwoLtlToOneVariable.TranslationTooLarge(_) => Left("size limit (not checked)")
      result match
        case Left(reason) => println(s"${Path.of(file).getFileName}: $reason")
        case Right(translated) =>
          var maxLength = 0
          var total = 1L
          while total + math.pow(alphabet.length, maxLength + 1).toLong <= budget do
            maxLength += 1
            total += math.pow(alphabet.length, maxLength).toLong
          var checked = 0L
          var accepted = 0L
          var mismatch: Option[IndexedSeq[String]] = None
          val start = System.nanoTime()
          for length <- 1 to maxLength; word <- words(length, alphabet) if mismatch.isEmpty do
            val expected = Ltl.evaluate(source, word)
            if Ltl.evaluate(translated, word) != expected then mismatch = Some(word)
            if expected then accepted += 1
            checked += 1
          val seconds = (System.nanoTime() - start) / 1e9
          mismatch match
            case Some(word) =>
              failures += 1
              println(s"${Path.of(file).getFileName}: MISMATCH on ${word.mkString(" ")}")
            case None =>
              println(f"${Path.of(file).getFileName}: agree on all $checked words up to length $maxLength ($accepted accepted, $seconds%.1f s)")
    if failures > 0 then 1 else 0
