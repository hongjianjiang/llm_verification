# B-RASP verification

Compiles a Boolean B-RASP program through 2LTL → PVWAA → a Boolean-summary
automaton → a Kind2-checkable Lustre monitor, and can check inclusion /
equivalence between two programs with Kind2.

## Build

```sh
sbt assembly
JAR=target/scala-3.5.1/brasp-verification.jar
```

Use the jar (not `sbt run`) for anything you redirect or pipe — sbt prefixes
every line with its own logging, which corrupts captured output.

## CLI flags

| Flag | Output |
| --- | --- |
| *(none)* | strict-past 2LTL program (text) |
| `--future` | strict-future 2LTL (mirrored, evaluated on `reverse(w)`) |
| `--pvwaa` | forward pebble VWAA |
| `--boolean-automaton` | Boolean-summary automaton (reverses the PVWAA) |
| `--lustre` | Lustre monitor for the Boolean-summary automaton |
| `--kind2-safety` | Kind2 contract: the monitor never accepts |
| `--kind2-subset SUPERSET INPUT` | Kind2 contract: `L(INPUT) ⊆ L(SUPERSET)` |
| `--kind2-equivalent OTHER INPUT` | Kind2 contract: `L(INPUT) = L(OTHER)` |
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

### Lustre monitor interface (`--lustre`)

Emits a node with a restartable interface:

```
node NAME(symbol: int; valid: bool; start: bool; last: bool)
  returns (accept_prefix: bool; accept_word: bool; input_ok: bool);
```

- `symbol` — index into the program's alphabet (see the node's leading comments).
- `valid` — does this tick present a symbol at all (`false` = stutter/pause)?
- `start` — reset the monitor to its initial state on this tick?
- `last` — does the word end on this tick?
- `accept_prefix` — is the prefix consumed since the last reset accepted?
- `accept_word` — `last and accept_prefix`.
- `input_ok` — is a `valid` tick's `symbol` actually in range?

`--kind2-safety`/`--kind2-subset`/`--kind2-equivalent` wrap this in a
contract that pins `valid = true`, `start = (true -> false)`, `last = true`
— i.e. one continuous word, one symbol per tick — and expose just
`symbol: int` to Kind2.

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
java -jar target/scala-3.5.1/brasp-verification.jar examples/brasp/last_a.brasp --lustre > monitor.lus
java -jar target/scala-3.5.1/brasp-verification.jar examples/brasp/last_a.brasp --kind2-safety > safety.lus
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

Check property using Kind2 model:
```sh
java -jar target/scala-3.5.1/brasp-verification.jar --kind2-subset examples/brasp/all_words.brasp examples/brasp/a_is_last.brasp > subset.lus
java -jar target/scala-3.5.1/brasp-verification.jar --run-kind2 --kind2-subset examples/brasp/all_words.brasp examples/brasp/a_is_last.brasp
java -jar target/scala-3.5.1/brasp-verification.jar --run-kind2 --kind2-equivalent examples/brasp/last_a.brasp examples/brasp/last_a.brasp
```

Run a generated Kind2 model directly:

```sh
/Users/alexander/work/kind2 -json safety.lus
```
