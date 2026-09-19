# Table 2 intermediate DFA breakpoints

Twenty-three instances around each DFA breakpoint for `Y`, `no2a`, `mono`,
`since`, and `slb` were measured serially with three repetitions per successful
route, a 120-second per-route limit, and a 4 GB heap
(`scripts/ltl_scaling_study.py run`). A terminal timeout or size-limit failure
was not repeated. Wall times include JVM startup. Inputs were produced with
`generate_inputs.py`, which reproduces the existing `examples/ltl` inputs byte
for byte. All definite verdicts match the expected classification.

| Family | Parameter | DFA | PVWAA-to-circuit |
| --- | ---: | ---: | ---: |
| `Y` | 12 | 1.16 s | 0.55 s |
| `Y` | 14 | 2.08 s | 0.80 s |
| `Y` | 16 | 10.80 s | 0.92 s |
| `Y` | 20 | timeout | 0.68 s |
| `no2a` | 8 | 0.71 s | 0.66 s |
| `no2a` | 12 | 1.50 s | 0.64 s |
| `no2a` | 16 | 12.89 s | 0.62 s |
| `no2a` | 24 | timeout | 0.87 s |
| `mono` | 9 | 2.08 s | 0.85 s |
| `mono` | 10 | 4.97 s | 0.65 s |
| `mono` | 11 | 15.35 s | 0.61 s |
| `mono` | 12 | 68.04 s | 1.29 s |
| `mono` | 16 | size limit | 0.63 s |
| `since` | 2 | 0.66 s | 0.63 s |
| `since` | 4 | 0.74 s | 0.61 s |
| `since` | 6 | 1.60 s | 0.60 s |
| `since` | 12 | timeout | 0.82 s |
| `since` | 16 | size limit | 0.85 s |
| `slb` | 2 | 0.72 s | 1.30 s |
| `slb` | 4 | 0.89 s | 0.85 s |
| `slb` | 6 | 1.73 s | 0.95 s |
| `slb` | 12 | timeout | 1.45 s |
| `slb` | 16 | size limit | 1.22 s |

A parallel DFA-only screen (four concurrent runs, not paper timings) found no
solvable `since`/`slb` instance between alphabet sizes 9 and 14, so 8 is the DFA
breakpoint for both; hence the smaller alphabets 2, 4, 6.
