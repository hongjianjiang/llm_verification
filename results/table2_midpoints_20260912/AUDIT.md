# Table 2 midpoint measurements

Six intermediate instances were measured serially with three repetitions per
successful route and a 120-second per-route limit. A terminal timeout or
construction-size failure was not repeated. Wall times include JVM startup.

| Family | Parameter | DFA | PVWAA-to-circuit |
| --- | ---: | ---: | ---: |
| `dot` | 400 | 3.73 s | 1.04 s |
| `Y` | 500 | timeout | 0.86 s |
| `no2a` | 4000 | timeout | 1.16 s |
| `mono` | 32 | size limit | 0.93 s |
| `since` | 32 | size limit | 0.72 s |
| `slb` | 32 | size limit | 0.70 s |

All definite verdicts match the expected language classification. The paper
rounds successful medians to one decimal place, consistently with the original
Table 2 measurements.
