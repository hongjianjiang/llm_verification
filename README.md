# B-RASP verification

Compiles a Boolean B-RASP program through 2LTL → PVWAA → an automaton, and
checks nonemptiness by either of two routes (see below). Can check inclusion
/ equivalence between two programs either way.

## Build

Run commands from the repository root with Java and sbt installed.

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

For a single input, `PROVED` means no accepted bad prefix is reachable;
`NOT PROVED` means a bad prefix is reachable. For inclusion or equivalence,
`PROVED` means the requested relation holds. `UNKNOWN` means the check did
not decide the property; inspect the backend output for incomplete results.

**`1LTL`** — the classical route. Eliminate the second variable in the logic,
then build the automaton explicitly:

```bash
java -jar $JAR examples/ltl/dot_depth__k-800__sigma-2.ltl --one-variable --run-native --native-max-states 50000000
```

**`ABC`** — keep the pebble, compile to a circuit, model-check it:

```bash
java -jar $JAR examples/ltl/two_var__same_letter_before__sigma-256.ltl --run-abc
java -jar $JAR examples/ltl/two_var__monotone_past__sigma-256.ltl --run-abc
```

Use `--abc-bin /path/to/abc` to select an ABC executable explicitly.

## Batch runs

The shell runners require Bash and GNU `timeout` (or `gtimeout`). Their
wall-clock measurement also requires `date +%s.%N` support. On macOS,
install GNU coreutils and put its `gnubin` directory on `PATH` so `date`
supports nanoseconds.

```sh
# Run all .ltl examples through the classical route.
bash scripts/run_1ltl.sh

# Run all .ltl or .brasp examples through ABC.
ABC_BIN=/path/to/abc bash scripts/run_abc.sh
ABC_BIN=/path/to/abc bash scripts/run_brasp.sh

# Pass files or shell-expanded globs to select a smaller batch.
TIMEOUT=60 REPS=3 OUT=results/1ltl_selected.csv \
  bash scripts/run_1ltl.sh examples/ltl/y_depth__k-10.ltl
```

| Setting | Default | Applies to |
| --- | --- | --- |
| `JAR` | `target/scala-3.5.1/brasp-verification.jar` | All runners |
| `TIMEOUT` | `120` seconds per invocation | All runners |
| `REPS` | `1` | The three verification runners |
| `ABC_BIN` | `../abc/abc` | `run_abc.sh`, `run_brasp.sh` |
| `MAX_STATES` | `50000000` | `run_1ltl.sh` |
| `OUT` | `results/1ltl_examples.csv`, `results/abc_examples.csv`, or `results/brasp_examples.csv` | Corresponding verification runner |

Each verification runner writes one CSV row per input and progress to
stderr. The 1LTL CSV contains `instance,verdict,compile_s,explore_s,total_s,wall_s,disjuncts`;
the ABC and B-RASP CSVs contain `instance,verdict,compile_s,encode_s,abc_s,total_s,wall_s`.
With `REPS>1`, `wall_s` is the median wall time; phase timings retain the
last available timing report rather than a median. Changing verdicts are
marked `MIXED:...`. Other batch statuses include `TIMEOUT`, `ERROR`, and
`UNKNOWN`; the 1LTL runner also reports `BLOWUP` when variable elimination
refuses a case split (`disjuncts` records the exponent in `2^n`). A completed
run replaces its output CSV, so use distinct `OUT` paths to retain runs.

To translate LTL examples back into B-RASP:

```sh
bash scripts/ltl_to_brasp.sh examples/ltl/y_depth__k-10.ltl
```

Omit the file arguments to translate every `examples/ltl/*.ltl` input.
This writes `examples/brasp/<stem>.brasp`, overwriting existing files with
the same stem, and reports created, updated, unchanged, or failed outputs.

## CLI flags

| Flag | Output |
| --- | --- |
| *(none)* | strict-past 2LTL program (text) |
| `--future` | strict-future 2LTL (mirrored, evaluated on `reverse(w)`) |
| `--pvwaa` | forward pebble VWAA |
| `--boolean-automaton` | Boolean-summary automaton (reverses the PVWAA) |
| `--one-variable` | eliminate the second variable before automaton construction |
| `--run-native` | check reachability with the native backend |
| `--native-max-states N` | native exploration budget (CLI default: `4096`) |
| `--run-abc` | encode a circuit and check it with ABC |
| `--abc-bin PATH` | ABC executable |
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
the same input stem (e.g. `contains_aba`), in which case the round-tripped
`--brasp` output overwrites it.

## Examples

Build the jar first, then run these commands from the repository root.

```sh
java -jar target/scala-3.5.1/brasp-verification.jar examples/brasp/last_a.brasp
java -jar target/scala-3.5.1/brasp-verification.jar examples/brasp/last_a.brasp --future
java -jar target/scala-3.5.1/brasp-verification.jar examples/brasp/last_a.brasp --pvwaa
java -jar target/scala-3.5.1/brasp-verification.jar examples/brasp/last_a.brasp --boolean-automaton
java -jar target/scala-3.5.1/brasp-verification.jar examples/brasp/ends_ab.brasp --word ab
java -jar target/scala-3.5.1/brasp-verification.jar examples/brasp/contains_aba.brasp --ltl > roundtrip.ltl
java -jar target/scala-3.5.1/brasp-verification.jar roundtrip.ltl --pvwaa
java -jar target/scala-3.5.1/brasp-verification.jar roundtrip.ltl --brasp
sbt test
```

Cross-check a word against every compiled stage at once:

```sh
# --word prints a verdict instead of a model, so it does not auto-save the
# .ltl the second group reads; this first line is what writes it.
java -jar target/scala-3.5.1/brasp-verification.jar examples/brasp/contains_ab.brasp --ltl

java -jar target/scala-3.5.1/brasp-verification.jar examples/brasp/contains_ab.brasp --word ab
java -jar target/scala-3.5.1/brasp-verification.jar examples/brasp/contains_ab.brasp --pvwaa --word ab
java -jar target/scala-3.5.1/brasp-verification.jar examples/brasp/contains_ab.brasp --boolean-automaton --word ab

java -jar target/scala-3.5.1/brasp-verification.jar ltl/contains_ab.ltl --word ab
java -jar target/scala-3.5.1/brasp-verification.jar ltl/contains_ab.ltl --pvwaa --word ab
java -jar target/scala-3.5.1/brasp-verification.jar ltl/contains_ab.ltl --boolean-automaton --word ab
```

Check a subset/equivalence property with ABC:
```sh
java -jar target/scala-3.5.1/brasp-verification.jar --run-abc --subset examples/brasp/last_a.brasp examples/brasp/first_equals_last.brasp
java -jar target/scala-3.5.1/brasp-verification.jar --run-abc --equivalent examples/brasp/last_a.brasp examples/brasp/last_a.brasp
```

## Timing

Add `--timing` to print phase durations to stderr. `compile` covers parsing
and compilation through the Boolean-summary automaton, including variable
elimination when requested. ABC adds `encode` and `abc`; the native route
adds `explore`. `total` sums these phases and excludes JVM startup, while
the batch runners' `wall_s` measures the whole invocation.

## NuSMV comparison baseline (Figure 3)

`scripts/nusmv_baseline.py` reuses `--one-variable --ltlf`: 2LTL variable
elimination and mirroring produce future LTLf, then a finite-prefix encoding
produces an ordinary infinite-trace LTL specification in an SMV model.
The prefix includes the export's final symbol-free sentinel. A fresh `active`
state bit starts true and cannot restart after becoming false; the
counterexample condition requires it eventually to become false. Strong/weak next,
until/release, and eventually/always are relativized to this prefix. Thus
NuSMV finding a counterexample means the original language is **nonempty**.
Symbol propositions are renamed to avoid SMV keyword collisions.

Two NuSMV engines are available:

- `--engine bdd`: complete symbolic LTL checking (`go; check_ltlspec`). A true
  specification proves emptiness.
- `--engine bmc`: incremental SAT-based bounded checking
  (`go_bmc; check_ltlspec_bmc_inc -k N`). A counterexample proves nonemptiness;
  exhausting `--bound` is recorded as `bound_limit`, never as emptiness.

NuSMV builds the symbolic model / Boolean expression representation itself;
this baseline does not pass through our conditional-summary circuit or ABC.
See the [NuSMV manual](https://nusmv.fbk.eu/userman/v27/nusmv.pdf) and
[official downloads](https://nusmv.fbk.eu/downloads.html).

```sh
python3 scripts/nusmv_baseline.py examples/ltl/y_depth__k-10.ltl \
  --nusmv /path/to/NuSMV --engine bdd --out results/nusmv_smoke/records.jsonl

# All 77 instances currently plotted in Figure 3, including the later additions.
# Two concurrent engines, 900 s end-to-end, 4 GB Java heap each, one repetition.
# Larger family instances are explicitly skipped after timeout/size-limit failures.
python3 scripts/run_nusmv_figure3.py --nusmv /path/to/NuSMV --repetitions 1 --jobs 2
MPLBACKEND=Agg python3 scripts/plot_nusmv_figure3.py results/nusmv_figure3_20260923

# Resume interrupted runs; hashes/settings must match.
python3 scripts/run_nusmv_figure3.py --nusmv /path/to/NuSMV --repetitions 1 --jobs 2 --resume

# Real-solver semantic tests, including every Boolean trace of length 1..3.
NUSMV_BIN=/absolute/path/to/NuSMV python3 -m pytest tests/test_nusmv_baseline.py
```

The BMC experiment uses a maximum bound of 10,000 transitions. Successful
timings include JVM startup, variable elimination, SMV encoding, and NuSMV.
Terminal failures are not repeated. The result directory contains a manifest,
machine/tool metadata, per-repetition timing samples, formulas, SMV models,
counterexample words (reversed back to source order), and logs. Reference
verdicts are checked for every conclusive result. The comparison plot and
table retain skipped and bound-limited outcomes explicitly. Existing Aalta,
Lisa, and circuit curves reuse their recorded measurements.

## License

This project is licensed under the [MIT License](LICENSE).
