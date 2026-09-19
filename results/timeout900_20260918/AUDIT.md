# 900-second reruns of every previously timed-out instance

Every instance that timed out at 120 s was rerun at 900 s on 2026-09-18/19,
with the same runners, inputs and repetition rule (three repetitions when
solved, one when a run fails): 13 DFA (`scripts/dfa_baseline.py`), 15 Aalta
(`scripts/aalta_baseline.py`), and 3 one-variable-circuit
(`scripts/circuit_one_variable_baseline.py`). The PVWAA route never timed out.
The three routes ran as three concurrent lanes, each serial inside, so newly
solved times carry some load from the other lanes. Size-limit refusals and
earlier successes were not rerun, since neither depends on the time limit.

Newly solved at 900 s:

| Route | Instance | Median wall time |
| --- | --- | ---: |
| DFA | dot k=3200 | 410.0 s |
| DFA | Y k=20 | 228.2 s |
| DFA | marks k=2400 | 236.5 s |
| DFA | same k=1200 | 381.6 s |
| Aalta | dot k=100 | 426.8 s |
| Aalta | since σ=12 | 133.4 s |

The DFA runs on no2a k=24, 1000 and 4000 stopped after 12–15 minutes with an
exception the runner classifies as a size limit. The run kept only the last
line of the stack trace, so which limit it was (the BDD node cap or the 4 GB
heap) was not recorded. All other reruns timed out again. All verdicts match
the expected classification.

The Aalta record for dot k=100 first reported `witness_accepted: false`. That
was a runner bug, not a wrong witness: replay used plain `--word`, whose
reference evaluator is exponential in the nesting of past operators and did not
finish within the limit on the 100-letter witness `(ab)^50`. Replaying through
the Boolean automaton (`--boolean-automaton --word`) accepts it in under a
second, and a one-letter-short control is rejected. The runner now replays that
way and records a replay timeout as a timeout. The archived record was
corrected by hand.
