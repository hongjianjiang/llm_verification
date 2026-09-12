# Independent emptiness audit

MPI array `50507772` checked all 300 extracted programs independently of their
labeling specifications. Eight shards ran on the `cpu` partition on September
11, 2026. Every shard completed with exit code zero in 59--65 seconds. Each
query used the same verifier JAR and ABC binary as the archived equivalence
study, with a 120-second per-query limit.

All 300 queries returned definite verdicts: 299 programs are nonempty and one
is empty. All 180 pilot programs are nonempty. Among the 120 gate-ablation
programs, 119 are nonempty; the empty program is
`200_gate_ends_ab_d6_l1_h1_s5_fixed_one`. Its extracted program is inequivalent
to the specification, and its training and validation accuracies are 0.764 and
0.706, respectively. Thus no program proved equivalent to a nonempty
specification is classified as empty.

| Study | Programs | Nonempty | Empty | Median time (s) | Range (s) |
|---|---:|---:|---:|---:|---:|
| Pilot | 180 | 180 | 0 | 1.21 | 0.81--3.90 |
| Gate ablation | 120 | 119 | 1 | 0.93 | 0.82--1.10 |
| Overall | 300 | 299 | 1 | 1.00 | 0.81--3.90 |

The `real_uhat_systematic_20260911/emptiness` directory contains one JSON
record and one raw solver log per extraction. The audit verified all program,
JAR, and ABC hashes against the corresponding training and equivalence
records. The reproducible runner is `scripts/real_uhat_emptiness.py`, with its
Slurm configuration in `scripts/real_uhat_emptiness.slurm`.
