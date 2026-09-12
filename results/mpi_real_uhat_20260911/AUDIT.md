# Audited systematic-study results

All MPI jobs have finished. This audit joins 300 manifest cells with their records and certificates, verifies program/specification hashes, and checks raw ABC verdicts. All 300 completed extraction and all six evaluation bands; no missing cells or ambiguous equivalence verdicts were found. Checkpoints remain on MPI; records, programs, and raw solver logs are copied here.

## Real-UHAT pilot

| Task | Certified equivalent | Attempted |
|---|---:|---:|
| all_a | 30 | 30 |
| ends_a | 30 | 30 |
| ends_ab | 26 | 30 |
| repeat_final | 23 | 30 |
| since_a | 23 | 30 |
| subsequence_ab | 6 | 30 |

Overall: **138/180 (76.7%)** pilot programs equivalent; 42 inequivalent. These certify extracted programs against their specifications on nonempty words. They do not establish universal equality between those programs and unsnapped numerical models.

## Gate ablation

Each entry is certified-equivalent seeds out of ten, at width 6, one layer, one head. Seeds and datasets are paired across conditions.

| Task | Fixed one | Learned | Learned + penalty | Fixed zero |
|---|---:|---:|---:|---:|
| ends_ab | 0/10 | 5/10 | 10/10 | 10/10 |
| repeat_final | 4/10 | 3/10 | 6/10 | 0/10 |
| since_a | 5/10 | 5/10 | 5/10 | 0/10 |

The strongest controlled finding is ends-ab: the penalty condition succeeds in all ten seeds versus zero with fixed-one scores. Every successful ends-ab gate-ablation model has an exactly inactive score gate. Fixed-zero fails on repeat-final and since-a, so positional attention is not a general replacement for content-dependent attention. Ten seeds support this setup-specific conclusion, not a universal necessity claim.

## Extraction fidelity and test coverage

There were **zero observed disagreements** between ordinary hardened, snapped, and extracted predictions in all 459,600 model–word evaluations (training/validation and the exhaustive audit overlap). Each comparison is finite; this is implementation evidence, not an all-input extraction proof.

| Study | Perfect on all long bands | Inequivalent among these | Perfect training + validation | Inequivalent among these |
|---|---:|---:|---:|---:|
| pilot | 170 | 32 | 138 | 0 |
| gate | 96 | 43 | 53 | 0 |

**Test-distribution limitation:** all 768 long samples for all-a are negative, while all 768 long samples for repeat-final and subsequence-ab are positive. Report this label balance, and add balanced/adversarial test sets before claiming robust length generalization. Thirty-two pilot models pass long tests but are wrong; most already fail training. One pilot model passes training and all long tests but fails validation. No model passing both training and validation was found inequivalent.

Independent short-word checks confirm examples in `audited_witnesses.json`:

- `037_pilot_ends_ab_d6_l2_h1_s2_learned_penalty`: shortest witness `ab`; specification accepts=True, program accepts=False.
- `231_gate_repeat_final_d6_l1_h1_s2_fixed_zero`: shortest witness `ab`; specification accepts=False, program accepts=True.
- `064_pilot_subsequence_ab_d6_l1_h1_s4_learned_penalty`: shortest witness `aa`; specification accepts=False, program accepts=True.

## Circuit ablation

| Route | Instances solved in all three repetitions | Size-limited instances | Timed-out instances |
|---|---:|---:|---:|
| dfa | 19/24 | 3 | 2 |
| full | 11/24 | 13 | 0 |
| support | 21/24 | 3 | 0 |
| realizable | 24/24 | 0 | 0 |

All 225 conclusive measurements agree with the declared benchmark semantics, with no conflicting repetitions. The other 63 measurements are 57 size-limit refusals and six timeouts, not solver misclassifications.

- At lookback 32, Y takes a median 0.59 s and no2a 0.62 s with realizability reduction; the DFA route hits the 120 s deadline on all repetitions of both instances.
- At alphabet size 8, Since takes median 35.41 s via DFA versus 0.59 s via realizability reduction; same-letter-before takes 28.00 s versus 0.56 s.
- At alphabet size 32, same-letter-before has 8,589,934,656 theoretical support-table cells, reduced to 130 retained cells/latches; median runtime is 0.70 s. The unreduced support route is refused by the explicit work cap.

These are randomized-order, three-repetition measurements on allocated MPI CPUs, not a claim of an otherwise idle physical host. The 500,000 cell-symbol-work cap is an implementation guard. Do not present cap refusals as measured exponential runtimes. The mono family remains a BOS-induced empty control, not a difficult semantic proof workload.

## Limits and next use in the paper

- Insert the gate table and full pilot denominator; they support stronger claims than the original selected-success anecdote.
- Add solved counts and structural sizes to the circuit comparison. Keep the new MPI timings separate from the historical laptop table.
- Treat long-word accuracy cautiously; add balanced short/long boundary cases before using it as the main generalization metric.
- All sampled extraction sizes are (12), (48), or (12,156). No run challenges the 20,000-tuple cap, so this pilot does not measure the deeper extraction failure boundary.
- The circuit pilot covers emptiness only; equivalent/mutated-program scalability queries and an ABC preprocessing ablation remain future extensions.

Training medians should use `train_seconds`, not total Slurm elapsed time. Across the 300 cells training spans 4.56–10.30 s (median 5.52 s); equivalence checking spans 0.76–3.52 s (median 0.93 s). Long evaluation jobs should not be reported as slow training.
