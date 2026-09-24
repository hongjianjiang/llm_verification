# LTLf solver baselines

`scripts/ltlf_solver_baseline.py --solver {aalta,black,lisa,lydia,ltl2sat}` runs
the one-variable LTLf export (`--one-variable --ltlf`) through an off-the-shelf
solver and records emptiness, in the same record format as
`aalta_baseline.py`. `install.sh` builds BLACK, Lisa and the Lydia driver into
`~/opt/ltlf-solvers/prefix` without sudo.

| Solver | Approach | Input spelling | Verdict | Witness |
| --- | --- | --- | --- | --- |
| Aalta (`aaltaf`) | SAT-aided explicit search | as exported (`X` strong, `N` weak) | `sat`/`unsat` | replayed |
| BLACK (`solve --finite`, Z3) | SAT-based bounded | `N` → `wX`, `true`/`false` → `True`/`False` | `SAT`/`UNSAT` | replayed |
| Lisa | compositional LTLf → DFA (Spot + MONA) | `X` → `X[!]`, `N` → `X` | `EMPTY`/`NONEMPTY` (patched) | none |
| Lydia (`lydia-empty`) | LTLf → LDLf → MONA DFA | `X` → `X[!]`, `N` → `X`; non-lowercase names quoted | `EMPTY`/`NONEMPTY` | none |
| LTL2SAT | SAT reduction | not yet integrated, see below | | |

Lisa and Lydia verdicts cannot be replayed; pass `--reference` a decided
records file (e.g. the Aalta or circuit run) to flag disagreements.

## Why each local change exists

- **Next-operator spelling.** Spot, Lisa and Lydia read plain `X` as *weak*
  next. Passing the export through unchanged turns the end-of-trace test
  `!(X(true))` into `false`, and verdicts flip silently.
- **BLACK constants.** Lowercase `true`/`false` parse as proposition names.
- **`lisa.patch`.**
  1. Lisa only prints the DFA size, so the patch adds an emptiness check
     (`dfwa::is_empty`, after symbolizing an explicit result).
  2. `compute_final_states` required the end-of-trace edge to leave the
     state. When Spot merges the post-end sink into a live state (as for
     `!X[!]true`), that state was never final, and Lisa reported a
     satisfiable formula empty. Dropping the condition only adds words whose
     prefix is already accepted, so emptiness is unchanged otherwise.
  3. Lisa copied the formula into `ltlf.mona` as comments. A long one
     overflows MONA's lexer (`YYLMAX`); MONA aborts, Lisa reads an empty DFA
     file and prints no verdict. Seen on `mono` σ=8.
- **`lydia_empty.cpp`.** The stock `lydia` binary requires Syft, which is
  only used for synthesis. The driver builds the DFA exactly as the CLI does
  with `--no-empty` and asks MONA for an accepting example.
- **`bddx_shim.h`.** Spot 2.15's BuDDy header declares `typedef int BDD`,
  which clashes with CUDD's `CUDD::BDD`. Lisa includes both.

Lisa runs in a fresh scratch directory per call (it writes `ltlf.mona` and
`mona.dfa` into its working directory), and every solver runs in its own
process group so a timeout also kills Lisa's `mona` children.

## Sanity checks passed (2026-09-21)

On hand-written formulas covering strong/weak next at the end of a trace, all
of BLACK, Lisa (both its Spot and MONA paths) and Lydia returned the expected
verdict. On the small benchmark instances (Y k≤20, no2a k≤16, mono σ=8, since
σ≤8, slb σ≤4), every decided verdict matched Aalta, and every BLACK witness
was accepted on replay.

## LTL2SAT

Not installed. The jar is only published at
`https://www.mat.unical.it/fionda/systems/LTL2SAT.jar`, whose TLS certificate
has expired, and it needs `glucose/` and `Aalta/` executables beside it. The
runner already has a `--solver ltl2sat` entry, but its input syntax and output
format still need to be checked against the jar.
