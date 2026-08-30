# Training a UHAT and verifying what it learned

A masked unique-hard-attention transformer (UHAT) recognises exactly the
star-free languages, and so does B-RASP — which is what the rest of this
repository compiles and model-checks. This package closes the loop from the
other end: it *trains* a model in that class, reads a B-RASP program off the
weights, and hands it to the existing verification routes.

```
train (torch)  ->  extract  ->  .brasp  ->  2LTL -> PVWAA -> circuit -> abc
```

## Quickstart

```bash
python3 -m uhat.train --task ends_ab --layers 1 --heads 2 --terms 1 \
    --out examples/brasp/learned__ends_ab.brasp \
    --jar target/scala-3.5.1/brasp-verification.jar
```

which prints the learned program

```
h1_1   = rightmost(true, !is_a@j)
accept = !is_bos & !is_a & is_b & !h1_1
```

and then, against a hand-written specification of the same language:

```bash
java -jar target/scala-3.5.1/brasp-verification.jar \
    --equivalent spec_ends_ab.brasp examples/brasp/learned__ends_ab.brasp --run-abc
# ABC: PROVED — the languages are equivalent.
```

That last step is the point: the claim is not "the model scored well on a test
set" but "the program the model computes is *the* language", proved.

## Why extraction is exact

The model is a UHAT whose every activation is one Boolean per position, and
whose score, value, and combination functions are small DNFs over the
Booleans available at that layer — i.e. the shape of a B-RASP subprogram.
Two things make the read-off exact rather than approximate:

* **The attention relaxation is exact, not merely smooth.** Writing `s_j` for
  the probability that `j` satisfies a head's score predicate, the
  probability that `j` is the *rightmost* such position is
  `s_j * prod_{j<k<i} (1 - s_k)`, with `prod_{k<i} (1 - s_k)` left over for
  "nothing matched", which contributes `false`. At 0/1 scores this *is*
  `Brasp.evaluate`; in between it is differentiable with closed-form suffix
  products. No temperature annealing on the attention itself.
* **Training ends in the discrete regime.** The last quarter of training runs
  with argmax gates and straight-through binarised activations, so the thing
  being optimised at the end is the program that gets extracted.

`tests/test_uhat.py` checks the invariant directly — a hardened model and its
extraction agree on every word, at random weights, across five architectures.
That is a sharper test than checking it only where training happened to land.

## What the knobs do, and one that bites

`--layers`, `--heads`, `--terms` set the architecture; unused heads are pruned
out of the extracted program, so over-provisioning costs nothing in the output.

The one non-obvious hazard is `Schedule.binarisation_weight`, which pushes
derived features toward 0/1 during the soft phase. It is **off by default and
should stay off**: at 0.05 it takes `ends_ab` from 6/6 restarts solved to 1/6.
It bites before a head carries any information, freezing it at a constant, and
the hard phase discretises the features anyway. When a run stalls, the
signature is a plateau where the output uses only base features — e.g.
`accept = !is_a & is_b`, which is "ends in b", 0.75 accuracy, all
false-positives and no false-negatives.

Restarts matter more than steps. Failures are greedy-basin failures, not slow
convergence, and a failed restart is usually visible within a few hundred
steps.

## Sweeping on Slurm

Each cell of the sweep is an independent CPU job of a few minutes — the model
is a handful of small tensors, so there is nothing to put on a GPU. The
parallelism that pays is across configurations and restarts.

```bash
mkdir -p results/uhat_sweep/logs
scripts/uhat_sweep.py plan > results/uhat_sweep/grid.txt
sbatch --array=1-$(wc -l < results/uhat_sweep/grid.txt) scripts/uhat_sweep.slurm
scripts/uhat_sweep.py collect          # -> results/uhat_sweep.csv
```

`collect` reports, per language, the smallest architecture that reached 1.0 on
held-out *longer* words — training is on words up to length 16 and testing on
17–40, so a program that merely memorised short words fails there.

Smoke-test one element on the submit node before spending an allocation:

```bash
SLURM_ARRAY_TASK_ID=1 STEPS=400 RESTARTS=1 scripts/uhat_sweep.slurm
```

## Training on the repository's own benchmark languages

Any B-RASP program is a training task. The jar turns an `.ltl` benchmark into
one, `uhat.brasp.parse` reads it, and the program itself labels the words:

```bash
java -jar $JAR examples/ltl/two_var__prev_repeats__sigma-2.ltl --brasp
python3 -m uhat.train --brasp examples/brasp/two_var__prev_repeats__sigma-2.brasp \
    --layers 1 --heads 2 --terms 2
```

**Label through B-RASP, never through `ltl2_generator.eval`.** B-RASP has a BOS
position at index 0 that the LTL2 evaluator does not, and `H(mask)` quantifies
over `j < i`, so the two disagree whenever the mask needs a real symbol at `j`
-- on `ab`, `two_var__monotone_past` is *true* under the LTL2 evaluator and
*false* under the jar. Training against the LTL side would produce programs
that disagree with the specification they are meant to be checked against.
The B-RASP evaluator here agrees with the jar on every language tested.

`scripts/uhat_sweep.py programs` builds a grid over every feasible program,
with the layer axis matched to each program's attention depth:

```bash
scripts/uhat_sweep.py programs --report-skipped > results/uhat_sweep/grid_programs.txt
sbatch --array=1-$(wc -l < results/uhat_sweep/grid_programs.txt) \
    scripts/uhat_sweep.slurm results/uhat_sweep/grid_programs.txt
```

Feasibility is decided by two numbers, both read off the program:

* **depth** -- the longest chain of attention ops. A program needing `d` of
  them cannot be computed in fewer than `d` layers, so the layer axis has to
  track it; a fixed axis silently makes most of these languages unlearnable
  rather than untrained. Beyond about 8 the discrete search stops finding
  anything.
* **alphabet** -- base features are one-hot per symbol, so `sigma-256` means a
  257-feature DNF at every gate.

Of the repository's benchmark languages, 17 pass both filters. The rest are
excluded by alphabet (the `sigma-8` and larger `two_var` instances) or by
depth (`dot_depth` k>=100, `y_depth` k>=800, `gastin_oddoux` k>=10,
`markey_agreement` n>=3). Those remain fine *verification* benchmarks -- they
are simply out of reach for gradient descent over a discrete program.

## Proving the result, not just measuring it

Accuracy on held-out words is evidence; `scripts/uhat_verify.py` produces a
proof. For every benchmark language the sweep solved, it takes the smallest
architecture that reached 1.0 and model-checks the extracted program against
the original specification:

```bash
scripts/uhat_verify.py --csv results/uhat_sweep.csv
# 23 proved equivalent, 0 provably different, 0 unsupported by the checker
```

`scripts/uhat_retrain.sh` reads `results/uhat_best.csv` (written by
`uhat_sweep.py best --out`) and retrains every solved language at its best
known architecture -- locally, or as a Slurm array with `SLURM=1`, on GPU
with `GPU=1`. Prefer the GPU when the run is small enough that every array
element starts at once: concurrency only beats a per-element speedup while
elements queue behind each other, and at 28 elements with 40-way concurrency
wall-clock is just the slowest element. Measured on this workload, 31:23 on
cpu20 against 11:22 on gpu22.

Two things it has to get right, both learned the hard way:

* **Do not let two definitions of a language share a name.** A built-in task
  and a `.brasp` spec of the same language have the same name but different
  samplers and length plans, so a configuration chosen on one does not
  transfer to the other. Fourteen languages had their architecture picked on
  built-in data and retrained against the spec; two came back below 1.0, and
  four others had looked like they solved *below* their spec's attention
  depth -- an artefact of the easier sampler, not a shallower program. Every
  run now records `source`, and `best` keeps the spec-trained rows when both
  exist.
* **Select on train *and* test accuracy, not test alone.** A program can score
  1.0 on the long-word test set and still miss a short word it was trained on.
  Picking such a row made the checker report a real difference -- the shortest
  distinguishing word for `gastin_oddoux_depth__k-1` was `'b'`, length one --
  which better model selection avoids entirely.
* **Distinguish a checker limitation from a counterexample.** The ABC route's
  translator cannot yet normalise `leftmost` attention, and it refuses rather
  than answering. Reporting that as "not proved" is a false inequivalence, so
  the script reports UNSUPPORTED and prefers an equally-solved program built
  only from `rightmost` ops when one exists.

## The Tomita benchmark from the literature

The seven Tomita grammars are the standard benchmark for regular-language
learning, and Bhattamishra et al. (EMNLP 2020, arXiv:2009.11264) report that
transformers generalise on four of them and fail on three -- exactly the three
that are not star-free. `uhat.tasks.TOMITA` has all seven, over `{0,1}`:

| | language | star-free |
| --- | --- | --- |
| 1 | `1*` | yes |
| 2 | `(10)*` | yes |
| 3 | every odd run of 1s is followed by an even run of 0s | **no** |
| 4 | no factor `000` | yes |
| 5 | even number of 0s and even number of 1s | **no** |
| 6 | `(#1 - #0) mod 3 == 0` | **no** |
| 7 | `0*1*0*1*` | yes |

The sweep reproduces the split, and adds what an accuracy benchmark cannot:
the four successes are *proved* equal to hand-written specifications, not
merely measured on held-out words.

| | best architecture | robustness | verdict |
| --- | --- | --- | --- |
| tomita_1 | l1 h1 t1 | 12/12 | PROVED |
| tomita_2 | l2 h1 t2 | 7/12 | PROVED |
| tomita_4 | l3 h1 t1 | 11/12 | PROVED |
| tomita_7 | l3 h1 t2 | 10/12 | PROVED |
| tomita_3 | -- | 0/12 | unsolved, best test 0.6490 |
| tomita_5 | -- | 0/12 | unsolved, best test 0.5025 |
| tomita_6 | -- | 0/12 | unsolved, best test 0.5030 |

The specs in `examples/brasp/tomita_{1,2,4,7}.brasp` are checked against their
Python predicates over every word to length 14 -- 32766 each -- by
`tests/test_uhat.py`, because a wrong spec would silently turn every proof
about them into a proof about a different language.

Most of these languages need a sampler of their own. A uniform length-30 word
is in `1*` with probability 2^-30, so `mutation_sampler` builds a positive and
perturbs half of them into near-misses, which is also what puts negatives next
to the decision boundary rather than far from it.

## Negative controls

`parity_a` and `equal_blocks` are not star-free, so no model in this class
computes them. They are in the task list on purpose: they should stay unsolved
at every architecture in the sweep, and a row claiming otherwise is a bug in
the harness, not a result.

That is not hypothetical. `equal_blocks` first "solved" at 1.0 with a single
attention op -- because uniform sampling never produces `a^k b^k`, its test
set had **zero** positives, and a program that always rejects scored
perfectly. It now has a constructive sampler, every run records
`test_positive_rate`, and `collect` reports any split outside 5-95% positive
as INCONCLUSIVE rather than solved. When a control looks like it passed,
check the label balance before believing it.
