# Systematic experiment results

Automatically collected descriptive results. Equivalence verdicts concern extracted programs on nonempty words, not unsnapped numerical transformers. Missing, interrupted, timed-out, and unsupported runs remain in denominators.

| Study | Planned | Recorded | Evaluated | Equivalent | Inequivalent | Other/missing certificate |
|---|---:|---:|---:|---:|---:|---:|
| pilot | 180 | 180 | 180 | 138 | 42 | 0 |
| gate | 120 | 120 | 120 | 53 | 67 | 0 |

pilot stage outcomes: `{'evaluated': 180}`. Certificate outcomes: `{'equivalent': 138, 'inequivalent': 42}`.


gate stage outcomes: `{'evaluated': 120}`. Certificate outcomes: `{'inequivalent': 67, 'equivalent': 53}`.

## Extraction checks

Counts below include only records with a completed band evaluation. Zero observed disagreements is a finite-sample check.

| Band | Models measured | Ordinary/snapped disagreements | Snapped/program disagreements |
|---|---:|---:|---:|
| train | 300 | 0 | 0 |
| validation | 300 | 0 | 0 |
| audit_1_8 | 300 | 0 | 0 |
| test_9_16 | 300 | 0 | 0 |
| test_17_32 | 300 | 0 | 0 |
| test_33_64 | 300 | 0 | 0 |

## Circuit comparison

288 of 288 planned measurements recorded. Counts: `{'nonempty': 201, 'timeout': 6, 'size_limit': 57, 'empty': 24}`.

Conflicting semantic verdicts: `[]`.
Disagreements with benchmark expectations: `[]`.

Raw timings and structural metrics are retained per repetition. Timeouts are censored; no timeout-substituted medians or speedups are reported. Investigate any semantic inconsistency before interpreting performance.
