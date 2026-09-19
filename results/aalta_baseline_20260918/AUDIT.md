# Aalta (LTLf satisfiability) emptiness baseline

Route: `.ltl`/`.brasp` → `--one-variable --ltlf` (one-variable elimination,
mirror to strict-future form, flat LTLf text with the alphabet constraint;
see `src/main/scala/ltlf/LtlfExport.scala`) → `aaltaf` on stdin.
`scripts/aalta_baseline.py`, 120 s budget per instance covering export and
solve, three repetitions per decided instance (medians), 4 GB heap, `--witness`
(each `sat` witness is reversed and replayed with `--word`). Wall times include
JVM startup. Run serially on a MacBook Pro.

Solver: `aaltaf` at github.com/lijwen2748/aaltaf commit 858885b, built with
`g++ -std=c++03 -DYYMAXDEPTH=100000000 -O2` and three local patches:
MiniSat's `mkLit` default argument moved to the definition (modern clang
rejects the friend default), the input buffer `MAXN` raised from 100000 to
2^26 characters, and the Bison parser stack raised past its 10000 default.
Without the last one, five instances failed in the parser with "memory
exhausted"; `records_rerun_parser_depth.jsonl` holds their rerun (all five
then time out in the solver), superseding those rows of `records.jsonl`.

All 30 decided instances match the expected verdict, and all 25 `sat`
witnesses were accepted on replay.

| Family | Aalta decides | Fails |
| --- | --- | --- |
| dot | none | timeout from k=100 |
| Y | k ≤ 100 (k=100: 5.2 s) | timeout at 500, 8000 |
| no2a | k ≤ 1000 (k=1000: 54.2 s) | timeout at 4000, 64000 |
| mono | σ ≤ 12 (σ=12: 26.0 s) | size limit (one-variable elimination) from σ=16 |
| since | σ ≤ 8 (σ=8: 2.0 s) | timeout at 12, size limit from 16 |
| slb | σ ≤ 12 (σ=12: 113.8 s) | size limit from 16 |
| marks | none | timeout from k=600 |
| same | none | timeout from k=300 |
