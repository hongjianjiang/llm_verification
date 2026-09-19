# One-variable LTL → sequential circuit emptiness baseline

Route: `.ltl`/`.brasp` → `TwoLtlToOneVariable` → mirror → PVWAA (no goto atoms,
i.e. an ordinary VWAA) → AIGER with one latch per state → ABC `pdr`
(`brasp.CircuitStudy INPUT one-variable OUT.aig`). Everything after the
one-variable step is identical to the PVWAA-to-circuit route. The runner is
`scripts/circuit_one_variable_baseline.py`. Each instance gets a 120 s budget
covering compile and solve, and is measured three times, reporting the median.
Heap is 4 GB. Wall times include JVM startup. Runs were serial on a MacBook Pro,
on the same 53 inputs as `results/aalta_baseline_20260918`.

The results are split across three files. `records.jsonl` holds 24 instances;
the run was then stopped by hand. `records_part2.jsonl` holds the remaining 29.
`records_rerun_stack.jsonl` holds 13 instances that first crashed with a JVM
`StackOverflowError`, because `CircuitStudy` then ran on the default
main-thread stack. They were rerun after it was moved to a 256 MB-stack worker
thread like `Translator.main`. Those rows supersede the earlier ones.

All 43 decided instances match the expected verdict.

| Family | Decides | Fails |
| --- | --- | --- |
| dot | k ≤ 3200 (78.5 s) | — |
| Y | k ≤ 8000 (14.5 s) | — |
| no2a | k ≤ 4000 (3.5 s) | compile timeout at 64000 |
| mono | σ ≤ 12 (60.1 s, 4,120 latches) | size limit from σ=16 |
| since | σ ≤ 8 (1.1 s) | compile timeout at 12, size limit from 16 |
| slb | σ ≤ 8 (1.0 s) | compile timeout at 12, size limit from 16 |
| marks | k ≤ 2400 (41.8 s) | — |
| same | k ≤ 1200 (11.5 s) | — |

The ABC solve is at most 3 s everywhere. The time goes into compiling the
one-variable formula.
