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

## Random star-free languages, and what emptiness certifies

The benchmark languages were written by hand, so they share whatever habits
their authors had. `scripts/uhat_random.py` removes that by drawing random
*B-RASP programs* -- everything B-RASP expresses is star-free by
construction, so no draw has to be discarded for falling outside the class.
It deliberately keeps a handful of empty languages, which are the controls
for the emptiness checker: without them the check has only negatives to
confirm and no positives to find.

Two corpora, both swept on Slurm:

| corpus | languages | spec depth | sweep | learned | verified |
| --- | --- | --- | --- | --- | --- |
| `random_deep` | 28 | 1-8 | 224 cells, 11.9 h | 19 | 19/19 |
| `random_deeper` | 19 | 1-12 | 151 cells, 13.6 h | 13 | 13/13 |

"Learned" means train *and* long-word test both 1.0, excluding the empty
controls; the balance is 5 and 3 languages that no architecture in the grid
solved. In all 32 selected rows the chosen layer count equals the
specification's attention depth exactly -- the layer axis is tracking the
quantity it is supposed to track, not being over-provisioned into a fit.

```bash
scripts/uhat_emptiness.py --csv results/uhat_deep_best.csv \
    --learned results/uhat_deep_final/programs \
    --json-dir results/uhat_deep_final --specs examples/brasp/random_deep \
    --out results/uhat_deep_emptiness_gated.csv
# emptiness matches the specification on 19/19 non-trivial languages
```

Verification is the cheap half: 38.9 s for the 19, 22.4 s for the 13, against
11.9 and 13.6 hours of training.

**An emptiness verdict is a far weaker certificate than an equivalence proof,
and has to be gated before it means anything.** The first run over the deep
corpus reported "emptiness matches the specification on 28/28", which reads
as conclusive and is not. Four of those 28 were the empty controls: a
specification with no positive words makes every training label false, so
"reject everything" scores 1.0 on train and test, and `empty == empty` is
then confirmed for a program an *untrained* model would also produce. Five
more were languages the model did not learn at all -- 0.85 to 0.94 train
accuracy, programs provably not the specification -- and they agreed on
non-emptiness anyway, which is the clearest possible demonstration that the
signal is weak: it passed for programs already known to be wrong. The
remaining 19 gave the base-rate answer, since 24 of 28 specifications are
non-empty.

`uhat_emptiness.py` therefore classifies every row before it may vote, using
the same two criteria as `uhat_sweep.py best` -- `test_positive_rate` inside
5-95%, and train and test both 1.0. A gated row is still model-checked and
still has its verdict written, with a `status` of `degenerate` or `unsolved`
saying why it does not count; languages whose *specification* is empty are
reported on their own line rather than inside the rate, because agreement
there is automatic. `--ungated` restores the old behaviour, and reproduces
the old number exactly: 24/24 plus 4 trivial.

The controls keep their real job. Across both corpora all seven empty
languages came back `empty`, which is what shows the ABC route answers
correctly on a language that genuinely has no words. What they cannot do is
stand as evidence that training learned anything, and folding them into one
agreement rate claimed exactly that.

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

## What the checker measures that accuracy cannot

The corpus result -- 133 of 134 extracted programs proved equal to the
specification they were trained on -- reads as a soundness demo, and the one
model that was *not* equal is the only part of it that says something about
learning rather than about the checker. Three experiments turn that anecdote
into a measurement. All three reuse the sweep runner, so a level is planned as
a grid and submitted the same way as any other array.

### Coverage: how much of "verified by testing" is wrong

A model is trained on every word up to length `upto` plus a sample of longer
ones, and tested on 2000 words of length 17-40. Between the two lies a band of
lengths that neither touches, and that band is where a model that fits its
data without learning its language hides.

`--enumerate-upto` moves that boundary. It used to be dead for `.brasp` tasks:
`program_task` always fills in a length plan, and `datasets` let the plan
overwrite the argument, so every run enumerated to 7 (two letters) or 5
(more), whatever the flag said. It is now an override, which is what makes the
sweep possible:

    scripts/uhat_coverage.py plan --best results/uhat_*_best.csv --levels 5 4 3 2
    UPTO=4 sbatch --array=1-122%40 scripts/uhat_sweep.slurm \
        results/uhat_coverage/upto4/grid.txt results/uhat_coverage/upto4
    scripts/uhat_coverage.py verify --out results/uhat_coverage_verdicts.csv

One architecture per language -- the smallest that solved it -- so a level is
122 cells, about an eighth of a full sweep. `verify` reports, per level, how
many models scored 1.0 on everything the method can test and how many of
*those* the checker says compute the wrong language.

The answer, over 488 models on an A40: almost none.

| `upto` | train words | test-perfect | proved | wrong | rate |
| --- | --- | --- | --- | --- | --- |
| 5 | 876 / 575 | 121 | 121 | 0 | 0.0% |
| 4 | 633 / 543 | 118 | 118 | 0 | 0.0% |
| 3 | 552 / 527 | 117 | 117 | 0 | 0.0% |
| 2 | 525 / 519 | 120 | 119 | 1 | 0.8% |

(train words given for the three- and two-letter alphabets.) The expected
result was a wrong-rate rising as the exhaustive band shrank. It does not
rise; one model escapes at the most degraded coverage, `rand_050` at l4 h1 t2,
about the same 1-in-120 as the original corpus.

Part of the reason is visible in the table: cutting `upto` from 5 to 2 removes
at most 351 words, because the 512 sampled words of length 1-16 dominate and
never change. The exhaustive band shrinks 28-fold; total supervision drops
about 40%. The other part is the protocol itself, which the next section
measures directly.

### Where the disagreements live

`scripts/uhat_witness.py` enumerates in length order and reports the first
word on which a learned program and its specification differ, plus the
disagreement rate at each length. On the one counterexample the sweep has
produced so far (`rand_014`, deeper corpus, l9 h1 t2):

| length | 6 | 7 | 8 | 9 | 10 |
| --- | --- | --- | --- | --- | --- |
| disagreements | 4/729 | 12/2187 | 30/6561 | 78/19683 | 192/59049 |
| rate | 0.55% | 0.55% | 0.46% | 0.40% | 0.33% |

The shortest distinguishing word is `abbcbb`, at length 6 -- one step past the
enumeration boundary at 5. Past the range that can be enumerated the density
keeps falling: 3 of 3000 uniformly sampled words of length 11-16 (0.100%), 0
of 1500 inside the test band at 17-40, and 0 of 400 at 41-80.

That is the mechanism behind "no testing regime the method applies would have
rejected it", and it is sharper than saying the languages differ only in a
narrow band. They differ at every length; the difference is simply thinnest
where the method looks. Testing for length generalisation samples long words
on purpose, and long words are exactly where these two languages most nearly
agree.

### Depth padding: the language or the program?

Depth and language vary together in the sampled corpora -- the deep programs
were drawn with a wider `--attention-ops` range and compute languages that are
harder by every other measure -- so "learnability is nearly flat in attention
depth" is confounded. `uhat.pad` breaks the confound by rewriting a program to
any greater depth without changing what it accepts:

    pad = rightmost(is_bos@j, prev@i)

At any query `i >= 1` the score is satisfied only by `j = 0`, so the op
attends to the BOS position and hands back `prev` read at the query itself. It
is the identity on every position of a nonempty word, and still a real
attention op that a model must spend a layer on.

    scripts/uhat_padded.py build --manifest results/uhat_random100.csv \
        --best results/uhat_random100_best.csv --depths 3 6 9 --count 12 --verify
    scripts/uhat_padded.py plan

`--verify` does not trust the transform: it proves each padded program
equivalent to its source with the model checker. All 36 programs (12 languages
at depths 3, 6 and 9) come back PROVED, which is the pipeline checking its own
experimental apparatus.

Over 288 training runs, learnability is flat:

| | d3 | d6 | d9 |
| --- | --- | --- | --- |
| languages learned | 12/12 | 12/12 | 12/12 |
| configurations at 1.0/1.0 | 96/96 | 95/96 | 95/96 |
| smallest solving architecture | l3 h1 t1 | l6 h1 t1 | l9 h1 t1 |
| median training | 81s | 123s | 156s |
| extraction proved equal to its spec | 12/12 | 12/12 | 12/12 |

Depth costs layers and training time -- the smallest architecture that solves
a language is always exactly `layers = depth, 1 head, 1 term` -- but not
success. And the strong form holds too: for all 12 languages the program
extracted from the depth-3 presentation is *proved equal* to the one extracted
from the depth-9 presentation. Extraction does not depend on how the target
was written. The claim that difficulty tracks the algebraic complexity of the
language rather than the depth of the program now rests on a controlled
comparison rather than on a corpus where the two moved together.

### Reproducing the checked numbers

`scripts/uhat_certify.py` runs both model checks over a training run and
prints the columns the tables report:

    scripts/uhat_certify.py --run results/uhat_iid --certified-only \
        --out results/uhat_iid_certified.csv \
        --dfa results/uhat_dfa_random*.csv

Equivalence asks whether the extracted program is the same language as the
specification it was trained against; emptiness asks whether it accepts
anything at all, and needs no specification, so it is what remains when no
ground-truth program exists. Both are timed, and `--dfa` groups the summary by
minimal-DFA size. Note that task names repeat across the three sampled corpora
-- `rand_016` exists in all three -- so every join here is on the
specification path rather than the name.

### Which test protocol actually catches a wrong model

`--split iid` trains on a classical 80/20 split -- one population of every
word up to length 11 (two letters, 4095 words) or 7 (three letters, 3280),
randomly split -- while still evaluating on the 2000 long words. Both
protocols are then measured on the same 122 models, and the checker says which
verdicts were right.

| protocol | models passing | proved | wrong | rate |
| --- | --- | --- | --- | --- |
| 80/20 i.i.d. (train + 20% holdout) | 81 | 81 | 0 | 0.0% |
| length split (train + 2000 long words) | 109 | 106 | 3 | 2.8% |
| both | 81 | 81 | 0 | 0.0% |

The classical split is the *stronger* certificate here. It passes fewer models
and every one of them is correct; the length split passes 28 more, three of
which compute the wrong language -- caught in the holdout by one or two words
out of 656-819 while scoring a clean 1.0 on 2000 long ones.

This is the witness measurement seen from the other side. Disagreements appear
one step past the exhaustively-trained boundary and thin out exponentially
with length, so a protocol that samples *long* words is aimed away from the
defect by construction, while a random holdout over *short* words samples
exactly where it lives. It also explains the flat coverage table above: that
sweep used the length protocol throughout, which was never going to show a
rising wrong-rate, because it does not look where the wrongness is.
