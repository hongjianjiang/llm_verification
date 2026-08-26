# B-RASP verification

Compiles a Boolean B-RASP program through 2LTL → PVWAA → an automaton, and
checks nonemptiness by either of two routes (see below). Can check inclusion
/ equivalence between two programs either way.

## Build

```sh
sbt assembly
JAR=target/scala-3.5.1/brasp-verification.jar
```

Use the jar (not `sbt run`) for anything you redirect or pipe — sbt prefixes
every line with its own logging, which corrupts captured output.

## The two routes

These are the two routes the paper compares. Both answer the same
nonemptiness question and print the same `PROVED` / `NOT PROVED` / `UNKNOWN`
verdict.

**`1LTL`** — the classical route. Eliminate the second variable in the logic,
then build the automaton explicitly:

```bash
java -jar $JAR examples/ltl/dot_depth__k-800__sigma-2.ltl --one-variable --run-native --native-max-states 50000000
```

**`ABC`** — keep the pebble, compile to a circuit, model-check it:

```bash
java -jar $JAR examples/ltl/dot_depth__k-800__sigma-2.ltl --run-abc
```

`1LTL` fails in two distinguishable ways: it reports the one-variable
translation blowing past its size cap (on genuinely two-variable formulas, no
automaton is ever built), or it exhausts `--native-max-states`. `ABC` needs
the `abc` binary — `../abc/abc` by default, or `--abc-bin PATH`.

## CLI flags

| Flag | Output |
| --- | --- |
| *(none)* | strict-past 2LTL program (text) |
| `--future` | strict-future 2LTL (mirrored, evaluated on `reverse(w)`) |
| `--pvwaa` | forward pebble VWAA |
| `--boolean-automaton` | Boolean-summary automaton (reverses the PVWAA) |
| `--subset SUPERSET INPUT` | goal: `L(INPUT) ⊆ L(SUPERSET)` (either route above) |
| `--equivalent OTHER INPUT` | goal: `L(INPUT) = L(OTHER)` (either route above) |
| `--aiger` | binary AIGER model of the Boolean-summary automaton (ABC backend) |
| `--brasp` | 2LTL back into a B-RASP program |
| `--ltl` | compiled formula in `.ltl` text syntax |
| `--json` | current stage as JSON |
| `--dot` | current stage as Graphviz DOT (`--pvwaa --dot` / `--boolean-automaton --dot`) |
| `--word TEXT` | `true`/`false`, does the program accept `TEXT` |

`--pvwaa`, `--boolean-automaton`, the default 2LTL stage, and `--brasp` all
auto-save a copy of their output next to what they print — `graphs/<input
stem>_pvwaa.dot`, `graphs/<input stem>_boolean_automaton.dot`,
`ltl/<input stem>.ltl` (or `_future.ltl`), and
`examples/brasp/<input stem>.brasp` respectively — creating the directory if
needed. Note the last one can collide with a hand-written example source of
the same input stem (e.g. `at_least_two_a`), in which case the round-tripped
`--brasp` output overwrites it.

## Converting LTLf benchmarks (`Ltlf`, `LtlfBatch`)

`src/main/scala/ltlf/Ltlf.scala` compiles standard LTLf text (the syntax
used across [SynthesisLab/LTLf_Learning_Benchmarks](https://github.com/SynthesisLab/LTLf_Learning_Benchmarks)
and produced by Spot's `spot.from_ltlf`: `G`/`F`/`U`/`R`/`W`, `X[!]` (strong
next) vs. bare `X` (weak next — these are genuinely different operators in
this benchmark suite's own generator scripts, not just alternative syntax
for the same thing), `!`, `&&`/`&`, `||`/`|`, `->`, `<->`, parentheses, bare
identifiers, `true`/`false`) into this project's strict-past 2LTL, from
which the existing `LtlToBrasp` produces a `.brasp` program as usual.

**This is deliberately the reversed language, not the original one** — see
`Ltl.mirrorToPast`'s doc-comment. A true same-word translation would need
automaton synthesis (Krohn-Rhodes-style aperiodic decomposition); mirroring
the direct, mechanical future-strict translation is the tractable
alternative, reusing only already-tested code. Any reference traces need
reversing before comparing them against the emitted program.

LTLf traces are multi-proposition Boolean valuations per step (not this
project's usual one-named-symbol-per-step model), so the alphabet is every
valuation of the declared atomic propositions (`2^|AP|` symbols).

```sh
sbt "runMain brasp.LtlfBatch /path/to/LTLf_Learning_Benchmarks/Fixed_Formulas/formulas.txt out_dir"
sbt "runMain brasp.LtlfBatch --json-dir /path/to/LTLf_Learning_Benchmarks/Generating_Instances/Subset out_dir"
```

The first form reads `formulas.txt`-style lines
(`"formula";["ap0","ap1",...];"name";"source"`) — both `Fixed_Formulas/
formulas.txt` itself and the same shape the `Generating_Formulas/gen_*.py`
scripts write (e.g. `SingleCounter/4.txt`; note their AP list is Python's
`str()` of a list, single-quoted, not JSON — `LtlfBatch.parseLine` handles
both). The second (`--json-dir`) form instead reads every `*.json` instance
file directly in a directory — the shape `Generating_Instances/gen_*.py`
write, e.g. `Subset/trace_length=10....json` — pulling out just the
`generating_formula`/`atomic_propositions` fields (the sampled traces
themselves aren't needed here). Either way, one `.brasp` and one `.ltl` file
is written per formula, named after the benchmark's own `name` field (or,
for `--json-dir`, the input file's basename). `Hamming` has no formula at
all (`generating_formula` is always `""` — pure trace-classification, not
translatable) and is silently skipped.

`ltl_examples/` in this repo holds one generated example per benchmark
family (`Fixed_Formulas`, `SingleCounter`, `DoubleCounter`, `Nim`,
`Random_Conjuncts_from_Basis`, `OrderedSequence`,
`RandomBooleanCombinationsofFactors`, `Subset`, `Subword`) produced this
way, each spot-checked against the assembled jar.

## `.brasp` syntax

```
alphabet a b

is_a       = symbol a
a_before   = rightmost(is_a@j, true)
contains_a = is_a | a_before

output contains_a
```

- `alphabet SYM...` — required, declares the input alphabet.
- `output NAME` — the program's verdict (optional; defaults to the last `NAME`).
- `NAME = bos` / `NAME = symbol SYM` / `NAME = const true|false`
- `NAME = rightmost(SCORE, VALUE)` / `NAME = leftmost(SCORE, VALUE)`
- `NAME = EXPR` — `!`, `&`, `|`, parentheses, over earlier names.
- `# ...` — line comment.
- `i` is the query position, `j` the attended position; `@i`/`@j` only valid
  inside `rightmost`/`leftmost` arguments.

Leftmost attention and forward references are rejected — normalize to
rightmost attention first.

## Examples

Assumes `target/scala-3.5.1/brasp-verification.jar` is still set from the `Build` step above (same shell session).

```sh
java -jar target/scala-3.5.1/brasp-verification.jar examples/brasp/last_a.brasp
java -jar target/scala-3.5.1/brasp-verification.jar examples/brasp/last_a.brasp --future
java -jar target/scala-3.5.1/brasp-verification.jar examples/brasp/last_a.brasp --pvwaa
java -jar target/scala-3.5.1/brasp-verification.jar examples/brasp/last_a.brasp --boolean-automaton
java -jar target/scala-3.5.1/brasp-verification.jar examples/brasp/ends_in_ab.brasp --word ab
java -jar target/scala-3.5.1/brasp-verification.jar examples/brasp/at_least_two_a.brasp --ltl > at_least_two_a.ltl
java -jar target/scala-3.5.1/brasp-verification.jar examples/ltl/at_least_two_a.ltl --pvwaa
java -jar target/scala-3.5.1/brasp-verification.jar examples/ltl/at_least_two_a.ltl --brasp
sbt test
```

Cross-check a word against every compiled stage at once:

```sh
java -jar target/scala-3.5.1/brasp-verification.jar examples/brasp/contains_a.brasp --word ab
java -jar target/scala-3.5.1/brasp-verification.jar examples/brasp/contains_a.brasp --pvwaa --word ab
java -jar target/scala-3.5.1/brasp-verification.jar examples/brasp/contains_a.brasp --boolean-automaton --word ab

java -jar target/scala-3.5.1/brasp-verification.jar examples/ltl/contains_a.ltl --word ab
java -jar target/scala-3.5.1/brasp-verification.jar examples/ltl/contains_a.ltl --pvwaa --word ab
java -jar target/scala-3.5.1/brasp-verification.jar examples/ltl/contains_a.ltl --boolean-automaton --word ab
```

Check a subset/equivalence property with ABC:
```sh
java -jar target/scala-3.5.1/brasp-verification.jar --run-abc --subset examples/brasp/all_words.brasp examples/brasp/a_is_last.brasp
java -jar target/scala-3.5.1/brasp-verification.jar --run-abc --equivalent examples/brasp/last_a.brasp examples/brasp/last_a.brasp
```
