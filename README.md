# B-RASP verification

Compiles a Boolean B-RASP program through 2LTL → PVWAA → a Boolean-summary
automaton → a binary AIGER model, checked in-process by ABC (or, via the
non-determinized `--direct` backend, a BTOR2 model printed for you to feed
to an external solver such as rIC3 by hand). Can check inclusion /
equivalence between two programs either way.

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
| `--subset SUPERSET INPUT` | goal: `L(INPUT) ⊆ L(SUPERSET)` (any backend below) |
| `--equivalent OTHER INPUT` | goal: `L(INPUT) = L(OTHER)` (any backend below) |
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

### Native backend (`--run-native`)

`--run-native` answers the same plain-safety/subset/equivalence question as
`--run-abc`, but entirely in-process: it runs
`BooleanAutomaton.reachable` directly and checks whether any accepting
state is reachable, with no AIGER model and no external solver at
all. It exists because, for some formula families, the *smallest correct*
AIGER model this backend can build (the compact reachable-DFA
encoding described below) doesn't actually help an external IC3/PDR solver: a
minimized DFA's transition table, bit-blasted, is a lookup with no
exploitable structure, so a solver can end up *slower* on it than on the
naive per-state encoding, or even crash parsing a large enough table.
Skipping the encoding step altogether and asking `reachable` the question
directly avoids both problems — it costs exactly the BFS exploration this
backend was already going to do internally, no more.

`--native-max-states N` (default 4096, same default as
`--aiger-max-states`) caps the exploration; hitting it reports `UNKNOWN`
rather than guessing. Unlike `--aiger`, there is no non-`--run-`
form — this backend only ever answers the question, it never emits a
model — and no raw-output mode, since there is no external tool's stdout
to show. The empty-word case is reported the same "compile-time constant"
way as the other backends'.

```sh
java -jar $JAR examples/brasp/last_a.brasp --run-native
java -jar $JAR examples/ltl/two_var__same_letter_before__sigma-14.ltl --run-native --native-max-states 2000000
```

`--run-native-conflict` answers the same question via
`BooleanAutomaton.conflictWitness` instead of `reachable` — a DFS that
stops as soon as it finds a bad-prefix witness rather than materializing
the whole reachable DFA, and dedupes/cycle-detects states by their
projection onto only the formula's "relevant" states (a genuine
bisimulation w.r.t. the accept condition — see `conflictWitness`'s
doc-comment) instead of the full Boolean-summary table. That makes it the
optimized alternative to `--run-native`: cheaper whenever you only need a
witness or a PROVED/NOT PROVED/UNKNOWN verdict, not the full DFA. It
shares `--native-max-states` with `--run-native` and reports the same
three-way verdict shape (labeled `brasp-native-conflict`).

```sh
java -jar $JAR examples/brasp/last_a.brasp --run-native-conflict
java -jar $JAR examples/ltl/two_var__same_letter_before__sigma-14.ltl --run-native-conflict --native-max-states 2000000
```

### Direct/non-determinized BTOR2 output (`--direct`)

`--aiger`/`--run-native` both build on `BooleanAutomaton`, which
*determinizes* the PVWAA first (a Miyano-Hayashi-style subset construction
over Boolean-summary functions) before emitting hardware or exploring
reachability. For a handful of `two_var` formula families
(`same_letter_before`, `since_same_letter`) that determinization step
itself is exponential in the alphabet size — `checkSupportSize` already
rejects sigma=15+ outright, and `--run-native`'s own reachable-state
enumeration hits the same wall a bit later, just as expensively.

`--direct` skips determinization entirely: it encodes the
*forward PVWAA* (the alternating automaton `Pvwaa.fromFuture2ltl` produces,
before `BooleanAutomaton` ever touches it) straight to BTOR2, one Boolean
register per PVWAA state — the same trick the string solver
[Sloth](https://github.com/uuverifiers/sloth) uses for its own alternating
automata (`Carry`/`Leave` references become a freshly *guessed* Boolean
input, validated one step later by requiring the state's own transition
formula to hold — exactly a model checker's job, no subset construction
needed). Sloth's automata have no second ("pebble") position, though, so
resolving `Goto` — this project's own addition, needed for the two-variable
`f@i`/`f@j` formulas `Pvwaa` compiles — is this backend's own contribution:
it resolves against the pebble's *original* anchor symbol, frozen the
moment the referencing state was first activated, rather than becoming
another guess.

That resolution is only implemented (and only checked, by
`DirectPvwaa.checkGotoTargetsAreSimple`, before ever emitting anything) for
goto-targets that are themselves symbol-constant, with no `Carry`/`Leave`
of their own — true for every `Once`/`Hist`/`Yst`/`Since`-over-symbol-atoms
formula this project's own generators produce, but not for a formula with
one `Until` nested inside another `Until`'s operand (e.g. `at_least_two_a`'s
`Hist`-based construction) — those still need the determinized `--aiger`/`--run-abc` path.

Where it applies, the resulting model is dramatically smaller and faster to
check than every other backend on the same input: `same_letter_before` at
sigma=16 fails outright on every other backend (`checkSupportSize`) or times
out (`--run-native`); this model, checked with rIC3, solves it in under a
second, and scales to sigma=256 in well under a minute.

```sh
java -jar $JAR examples/brasp/last_a.brasp --direct > model.btor2
/Users/alexander/work/rIC3/target/release/ric3 check model.btor2 --cex ic3
java -jar $JAR examples/ltl/two_var__same_letter_before__sigma-256.ltl --direct > model.btor2
/Users/alexander/work/rIC3/target/release/ric3 check model.btor2 --cex ic3
```

There is no AIGER/ABC sibling of this backend: on the `dot_depth` family,
the same non-determinized construction encoded to AIGER and run through
ABC's `pdr` scales far worse with formula depth than the determinized
`--aiger`/`--run-abc` path (times out well before `--run-abc` does), so it
isn't wired up here — where `--direct` applies, stick with its BTOR2 output
(fed to rIC3 by hand); elsewhere, use `--aiger`/`--run-abc`.

### ABC backend (`--aiger`, `--run-abc`)

[ABC](https://github.com/berkeley-abc/abc)'s `pdr` (Property Directed
Reachability, i.e. IC3) is the only backend in this project that runs a
solver automatically — `--direct` only ever
prints a model for you to check with an external tool by hand. `--aiger`
prints the model, `--run-abc` runs ABC on it directly (implies `--aiger`);
`--subset`/`--equivalent` still pick *what* it checks, same as every other
backend.

| Flag | Effect |
| --- | --- |
| `--aiger` | print the binary AIGER model instead of the plain automaton |
| `--aiger-max-states N` | reachable-state cap for the compact DFA encoding below (default 4096) |
| `--run-abc` | run ABC's `pdr` on it and print a summary (implies `--aiger`) |
| `--abc-bin PATH` | ABC executable (default `../abc/abc`, sibling to this repo) |
| `--abc-raw` | print ABC's raw stdout instead of the summary |

The AIGER model prefers a compact, minimized encoding: it first explores
the automaton's *actually reachable* states (breadth-first, up to
`--aiger-max-states`) and, if that exploration completes, minimizes
the resulting DFA (`BooleanAutomaton.minimize`, exact Moore-style partition
refinement — `reachable`'s own dedup is exact structural equality only, so
this routinely merges away a further 30-95% of the states on real
specifications) before emitting `ceil(log2(stateCount))` latches encoding the
current state in binary, with a next-state lookup bit-blasted from
`dfa.transitions` — its size tracks the real *minimized* reachable-state
count, not the per-state Boolean-summary support size.
If exploration is truncated instead, it falls back to the older direct
encoding (one Boolean latch per summary cell, `bad` = "nonempty prefix
accepted"). Either way every latch is canonicalized to physically reset to
0 — the standard XOR-with-init trick for the summary-cell encoding, and for
free for the DFA encoding since its state `0` is always the initial state —
because this build's binary AIGER reader only supports that (pre-1.9, no
explicit-reset-field) latch format. ABC's `&read` (its other, ASCII-capable
AIGER reader) was tried first and rejected: it silently treats every latch
as uninitialized regardless of what the file declares, giving wrong
verdicts rather than an error, so `Abc.run` always goes through the classic
`read_aiger` + `pdr` pipeline instead.

`Abc.run`'s script always runs `scleanup; dc2` before `pdr`. It matters a
lot on formula families with small per-state local support but a long
syntactic chain (e.g. `dot_depth`): `checkSupportSize` passes trivially
there, so `generateSafetyAuto` picks the explicit direct-table encoding
without ever attempting the compact DFA one — leaving a lot of purely
structural redundancy in the model. `scleanup` (structural sequential
cleanup, no SAT/induction) and `dc2` (combinational don't-care-based
resynthesis) are both cheap and clean most of that up before `pdr` ever
sees it — measured on `dot_depth`: k=1600 went from 119.6s to 6.7s, and
k=2400/k=3200 (previously timing out past 150s) now solve in 12.3s/16.6s.
(`&scorr`, ABC's *sequential* redundancy checker, was tried first and
rejected: it's itself SAT/induction-based and didn't finish in 60s on a
16k-latch model — no cheaper than the `pdr` problem it would be
preprocessing away.)

Only power-of-two alphabets are supported either way — AIGER has no way to
express a non-power-of-two symbol range (unlike a word-level format like
BTOR2, which can add an explicit `constraint` line ruling out-of-range
values).

```sh
java -jar $JAR examples/brasp/last_a.brasp --run-abc
java -jar $JAR --run-abc --equivalent examples/brasp/last_a.brasp examples/brasp/last_a.brasp
java -jar $JAR examples/brasp/last_a.brasp --aiger > model.aig
/Users/alexander/work/abc/abc -c "read_aiger model.aig; pdr; print_status"
```

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
