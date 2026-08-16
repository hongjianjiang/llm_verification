# Translating LTLf Benchmarks into `ltl_examples/`

This note documents how `ltl_examples/` gets populated from
[SynthesisLab/LTLf_Learning_Benchmarks](https://github.com/SynthesisLab/LTLf_Learning_Benchmarks)-shaped
data: the compiler stage that does the actual translation, a scalability
bug in that compiler that had to be fixed before most of this benchmark
suite was tractable at all, and the batch process used to turn ~15,900 raw
benchmark files into the 1857 `.ltl`/`.brasp` pairs now under
`ltl_examples/`.

## 1. What "translation" means here

`src/main/scala/ltlf/Ltlf.scala` compiles standard LTLf text (`G`/`F`/`X`/
`U`/`R`/`W`, `X[!]` strong vs. bare `X` weak next, etc.) into this project's
own 2LTL formalism. The compile is two stages:

```
Ltlf.compileToFuture(text, aps)      LTLf text -> strict-future 2LTL
Ltl.mirrorToPast(futureDag)          strict-future -> strict-past 2LTL
```

`Ltlf.compileToPast` composes both, and is what every `.ltl` file under
`ltl_examples/` is built from — **every file is `logic past-strict`**,
never `future-strict`. Because of the mirror, a compiled program's
language is the *reversal* of the source LTLf formula's language: `word`
is accepted by the compiled `.ltl` iff `reverse(word)` satisfies the
original formula, not `word` itself. `Ltl.mirrorToPast`'s doc-comment has
the full reasoning; every correctness check described below evaluates
compiled programs on the reversed trace to account for it.

LTLf traces are multi-proposition Boolean valuations per step, not this
project's usual one-named-symbol-per-step model, so the compiled
alphabet is every valuation of the declared atomic propositions
(`2^|AP|` symbols).

### The CLI

`LtlfBatch` (`src/main/scala/ltlf/LtlfBatch.scala`) drives this over a
directory of benchmark files, one `.brasp`/`.ltl` pair per formula:

```sh
# formulas.txt-shaped input: "formula";["ap0","ap1",...];"name";"source" per line
sbt "runMain brasp.LtlfBatch path/to/formulas.txt ltl_examples/Fixed_Formulas"

# one *.json instance file per formula (generating_formula/atomic_propositions fields)
sbt "runMain brasp.LtlfBatch --json-dir path/to/Generating_Instances/Subset ltl_examples/Subset"
```

For repeated invocations (see §4) it's much faster to skip `sbt`'s
startup cost and call the already-assembled jar's class directly:
`java -cp target/scala-3.5.1/brasp-verification.jar brasp.LtlfBatch ...`.

## 2. The scalability bug: one state per alphabet symbol

The original `Ltlf.Builder` compiled each atomic proposition `p` as a
named definition `p := (disjunction of every alphabet symbol whose bit
for p is set)` — which required first giving **every one of the `2^|AP|`
alphabet symbols its own named `is_$symbol := sym($symbol)@i` state**.
That's fine for the hand-written examples this tool was originally built
for (alphabets of a handful of symbols), but LTLf benchmarks routinely
declare 12-24 atomic propositions — `2^17 = 131072` states for a 17-AP
formula — and every downstream stage (`Pvwaa`'s `states x alphabet`
transition table, `BooleanAutomaton`'s per-symbol loop, `Btor2`'s
per-state symbol mux) is `O(states x alphabet)`. Concretely, a 12-AP
`DoubleCounter` instance produced a 4203-line `.ltl` file with 4199
definitions, and **translating it never finished** — 180s+ and 4.5GB of
memory with zero output, for `--btor2` generation alone, before rIC3 ever
ran.

### The fix

Added `AtomKind.BitAtom` (`src/main/scala/ltl/Ltl.scala`): an atom whose
`symbol` field holds a decimal *character index*, not a literal alphabet
token — it matches iff that character of the concrete input symbol is
`'1'`. `Ltlf.scala` now compiles each atomic proposition directly to one
`BitAtom`, tested against the character position that already encodes it
in the alphabet's bit-vector encoding — no per-symbol enumeration, no
`2^|AP|`-sized disjunction. `Pvwaa.scala` (`usesSymbol`, `top`) and
`LtlText.scala` (round-trip `bit(N)@i` syntax) were taught to recognize
it; `LtlToBrasp.scala` expands it back to the old OR-of-symbols form
lazily, only when `--brasp` output is actually requested (never on the
`--btor2`/`--run-ric3` path), so the one place that still needs the
`2^|AP|` expansion doesn't reintroduce the blowup for verification.

Effect on the `DoubleCounter` example above: 4203 lines / 4199
definitions -> **107 lines / 103 definitions**; `--btor2` generation went
from "never finishes" to 6 seconds. Every existing test still passes
(`sbt test`), and a new independent cross-check (§5) confirms the
compiled language is unchanged.

## 3. Where the benchmark data actually lives

`benchmarks/<Family>/*.json` (gitignored, not part of the repo) holds the
raw per-instance files, one JSON object each, with (among other things)
`generating_formula`, `atomic_propositions`, and a `name` field that's
often just provenance (e.g. `".../DoubleCounter/1.txt"`), not a usable
output name. Every family follows the same shape now, including the ones
that used to be line-based (`Fixed_Formulas`, `DoubleCounter`,
`SingleCounter`, `Random_Conjuncts_from_Basis`).

Each *distinct* formula is resampled ~9x with different trace-count/
trace-length metadata (`number_positive_traces`, `max_length_traces`,
...) that's irrelevant to translation — those fields don't affect
`generating_formula`/`atomic_propositions` at all. Deduplicating on
`(generating_formula, atomic_propositions)` collapses ~15,900 raw files
down to **1857 distinct formulas**:

| Family | Raw files | Distinct formulas |
|---|---|---|
| Fixed_Formulas | 82 | 9 |
| DoubleCounter | 18 | 2 |
| SingleCounter | 46 | 5 |
| Nim | 63 | 7 |
| OrderedSequence | 2160 | 225 |
| Subset | 1800 | 191 |
| Subword | 1256 | 240 |
| RandomBooleanCombinationsofFactors | 1499 | 270 |
| Random_Conjuncts_from_Basis | 8172 | 908 |
| **Hamming** | 500 | **0 — untranslatable** |

`Hamming` is a pure trace-classification benchmark: `generating_formula`
is `""` in all 500 instances (confirmed by inspection, not just by
`LtlfBatch`'s own doc-comment) — there is no LTLf formula to compile,
so `LtlfBatch --json-dir` correctly skips it entirely (`skipped 500
file(s) with no generating_formula`).

## 4. Building `ltl_examples/`

1. **Dedup + name.** For each family, group `benchmarks/<Family>/*.json`
   by `(generating_formula, atomic_propositions)`, keep one
   representative file per group, and copy it into
   `benchmarks/_dedup/<Family>/<name>.json` under a clean name (matching
   the existing `ltl_examples/` convention where one already existed —
   e.g. `nim_heaps=2tokens=1`, `random_conjuncts_from_basis_conjuncts=…`
   — falling back to the raw filename stem, or `<family>_ap<N>_<i>` for
   `DoubleCounter`/`SingleCounter` instances with no canonical
   `bits=N`-style name recoverable from the smaller reference clone).
   `LtlfBatch --json-dir` names its output after the *input file's own
   basename*, so this step is what actually controls the final
   `ltl_examples/<Family>/<name>.ltl` filenames.
2. **Translate.** For each family, `java -cp $JAR brasp.LtlfBatch
   --json-dir benchmarks/_dedup/<Family> ltl_examples/<Family>`. Driven by
   a small batched wave loop (each wave stages only the not-yet-written
   formulas via symlinks, so a run that gets interrupted or hits a slow
   instance loses no completed work) with a per-family timeout, falling
   back to isolating stragglers one at a time on a short timeout if a
   wave makes no progress. In practice this was never needed: every AP
   count across the whole dataset tops out at 14 (well inside the fast
   regime the `BitAtom` fix established — nothing here approaches the
   17-AP scale that's still slow), so translation finished all 1857
   formulas in **31 seconds with zero failures**.
3. **Clean up orphans.** Any `.ltl`/`.brasp` file under `ltl_examples/`
   with no matching `benchmarks/_dedup/<Family>/<name>.json` is stale
   (typically left over from an earlier, differently-parameterized data
   pull) and gets deleted rather than kept around indefinitely.

## 5. Correctness verification

`src/test/scala/translator/LtlExamplesCrossCheckSuite.scala` cross-checks
every `.ltl` file under `ltl_examples/<Family>/` against its original
source in `benchmarks/_dedup/<Family>/`, using an independent,
from-scratch, textbook-definition brute-force LTLf evaluator (deliberately
*not* reusing `Ltlf.desugar`/`Ltlf.compile` — see the doc-comment on
`bruteForce`) — the same technique `LtlfSuite` already used for the
hand-curated `Fixed_Formulas` set, extended to every instance actually
present.

For each formula: parse the original LTLf text, parse the compiled
`.ltl`, generate random traces at lengths 0-8 (10 samples each), and
assert the brute-force verdict on the original formula equals
`Ltl.evaluate` on the compiled program run on the *reversed* trace (per
§1's mirroring). All 1857 formulas pass — zero mismatches.

Skips gracefully (no failures reported) if `benchmarks/_dedup/` isn't
present, since that data is local/gitignored and outside the repo.
