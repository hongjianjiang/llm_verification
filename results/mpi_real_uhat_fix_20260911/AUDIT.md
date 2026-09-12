# Frozen-checkpoint numerical-fix audit

MPI job array 50502127 completed all eight shards successfully (1:44–2:02 per shard). No training was repeated. The original study and its checkpoints were preserved; the exact patched source snapshot is in `source.tar.gz`.

All 300 checkpoint hashes match the original records. All 300 re-extracted programs are byte-for-byte unchanged. Fresh raw ABC verdicts match all original certificates: **191 equivalent, 109 inequivalent** (pilot 138/180; gate 53/120). The original program certificates remain applicable.

Ordinary predictions before/after the fixes and corrected snapped predictions agree in all **690,000** model–word evaluations: 459,600 original evaluations plus 230,400 balanced diagnostics. Recomputed original accuracies also match the archived records. The balanced diagnostics compare numerical models; programs were not re-interpreted on those new words. The archived program/model comparisons plus unchanged program hashes support the original finite fidelity checks, not an all-input numerical correspondence theorem.

## Balanced long-word diagnostics

Each task uses 128 positive and 128 negative samples in each length band, shared across model seeds. Sampling is deterministic (20260913 + task index) and with replacement. Rare classes are explicitly constructed: all-a positives; repeated-final negatives with a constant opposite-symbol prefix; subsequence-ab negatives of the form b* a*; and all-b negatives for since-a mixed with words ending in a. Other conditions use constrained suffixes or rejection sampling. These are targeted diagnostic distributions, not uniform-word generalization estimates. Repeated words are retained; unique-word counts are saved per record.

| Study | Perfect uniform long bands | Perfect balanced long bands | Inequivalent among balanced-perfect |
|---|---:|---:|---:|
| pilot | 170/180 | 140/180 | 2 |
| gate | 96/120 | 53/120 | 0 |

Pilot mean numerical accuracy across all 30 runs per task:

| Task | Length 9–16 | Length 17–32 | Length 33–64 |
|---|---:|---:|---:|
| all_a | 100.00% | 100.00% | 100.00% |
| ends_a | 100.00% | 100.00% | 100.00% |
| ends_ab | 99.40% | 99.48% | 99.32% |
| repeat_final | 95.74% | 95.91% | 95.82% |
| since_a | 94.90% | 94.53% | 94.53% |
| subsequence_ab | 73.59% | 71.71% | 69.27% |

Two inequivalent pilot programs pass every balanced long band:

- `035_pilot_ends_ab_d6_l2_h1_s0_learned_penalty`
- `037_pilot_ends_ab_d6_l2_h1_s2_learned_penalty`

## Interpretation

The edge-case regressions establish real implementation defects (finite masking sentinel, rounded straight-through weights, approximate score ties and activation merging, and multihead residual accumulation order). They do not explain the failed training runs in this cohort: these fixes change neither the emitted programs nor any checked prediction. The measured success rates belong to archived training; a fresh training experiment with corrected gradients has not been run.

Local verification: the existing UHAT/extraction suite and seven initial numerical regressions passed (23 tests, one optional Scala bridge test skipped). The final numerical/sampling suite has eight passing tests, including both-label coverage in all diagnostic bands. The manuscript was compiled and its edited tables visually inspected.

Reproduce this audit with `python3 results/mpi_real_uhat_fix_20260911/audit_recheck.py`. It checks IDs, checkpoint/program hashes, fresh raw solver verdicts, prediction vectors, archived accuracy agreement, and diagnostic class counts.
