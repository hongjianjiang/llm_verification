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
java -jar $JAR examples/ltl/two_var__same_letter_before__sigma-1024.ltl --run-abc
java -jar $JAR examples/brasp/two_var__monotone_past__sigma-256.ltl --run-abc
```


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
| `--timing` | phase breakdown to stderr (see below) |

`--pvwaa`, `--boolean-automaton`, the default 2LTL stage, and `--brasp` all
auto-save a copy of their output next to what they print — `graphs/<input
stem>_pvwaa.dot`, `graphs/<input stem>_boolean_automaton.dot`,
`ltl/<input stem>.ltl` (or `_future.ltl`), and
`examples/brasp/<input stem>.brasp` respectively — creating the directory if
needed. Note the last one can collide with a hand-written example source of
the same input stem (e.g. `at_least_two_a`), in which case the round-tripped
`--brasp` output overwrites it.

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
