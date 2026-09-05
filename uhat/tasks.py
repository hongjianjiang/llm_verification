"""Target languages, and the word samplers used to train against them.

All but the last two are star-free, so a masked hard-attention transformer
can recognise them exactly.  `parity_a` and `equal_blocks` are not, and are
here as negative controls: training should visibly fail to generalise on
them however long it runs, because no model in this class computes them.
"""

from __future__ import annotations

import random
import re
from dataclasses import dataclass
from itertools import product
from typing import Callable, Sequence


@dataclass(frozen=True)
class Task:
    name: str
    alphabet: tuple[str, ...]
    predicate: Callable[[Sequence[str]], bool]
    star_free: bool = True
    sampler: "Callable[[Sequence[str], int, int, random.Random], list[tuple[str, ...]]] | None" = None
    """Draws candidate words; `None` means uniform.  A family whose label is
    almost always the same under uniform sampling needs its own."""
    lengths: tuple[int, tuple[int, int], tuple[int, int]] | None = None
    """`(enumerate_upto, train_range, test_range)`, when the defaults do not
    suit -- a family parameterised by `k` needs words long enough to show the
    pattern at all."""

    def label(self, word: Sequence[str]) -> bool:
        return self.predicate(word)


def _factor(word: Sequence[str], pattern: str) -> bool:
    return pattern in "".join(word)


def equal_blocks_sampler(alphabet, count, min_length, max_length, rng):
    """`a^i b^j` with `i` close to `j`, so the language is actually sampled.

    Uniform words never hit `a^k b^k`, which made this "control" vacuous: the
    test set had zero positives and a program that always rejects scored a
    perfect 1.0. A control only controls if both classes appear.
    """
    first, second = alphabet[0], alphabet[1 % len(alphabet)]
    words = []
    for _ in range(count):
        half = max(1, rng.randint(min_length, max_length) // 2)
        other = half if rng.random() < 0.5 else max(1, half + rng.choice([-2, -1, 1, 2]))
        words.append(tuple([first] * half + [second] * other))
    return words


def _runs(word: Sequence[str]) -> list[tuple[str, int]]:
    runs: list[tuple[str, int]] = []
    for symbol in word:
        if runs and runs[-1][0] == symbol:
            runs[-1] = (symbol, runs[-1][1] + 1)
        else:
            runs.append((symbol, 1))
    return runs


def mutation_sampler(make_positive, rate: float = 0.5):
    """Constructed positives, half of them perturbed into near-misses.

    Uniform sampling is useless for most of the Tomita languages -- a random
    length-30 word is in `1*` with probability 2^-30 -- and sampling only
    positives is worse, because the model never sees the boundary. Building a
    positive and then flipping, inserting, or deleting one symbol puts the
    negatives right next to it, which is what makes the boundary learnable.
    """

    def sample(alphabet, count, min_length, max_length, rng):
        words = []
        for _ in range(count):
            length = rng.randint(min_length, max_length)
            word = list(make_positive(alphabet, length, rng))
            if rng.random() >= rate and word:
                operation = rng.choice(("flip", "insert", "delete"))
                index = rng.randrange(len(word))
                if operation == "flip":
                    others = [s for s in alphabet if s != word[index]]
                    word[index] = rng.choice(others or list(alphabet))
                elif operation == "insert":
                    word.insert(index, rng.choice(list(alphabet)))
                elif len(word) > 1:
                    del word[index]
            words.append(tuple(word))
        return words

    return sample


def _alternating(alphabet, length, rng):
    first, second = alphabet[1], alphabet[0]  # (10)* over {0,1}
    return [first if i % 2 == 0 else second for i in range(2 * max(1, length // 2))]


def _no_three_zeros(alphabet, length, rng):
    zero, one = alphabet[0], alphabet[1]
    word: list[str] = []
    while len(word) < length:
        word.extend([zero] * rng.randint(0, 2))
        word.append(one)
    return word[:length]


def _four_blocks(alphabet, length, rng):
    zero, one = alphabet[0], alphabet[1]
    cuts = sorted(rng.randint(0, length) for _ in range(3))
    sizes = [b - a for a, b in zip([0] + cuts, cuts + [length])]
    word: list[str] = []
    for symbol, size in zip((zero, one, zero, one), sizes):
        word.extend([symbol] * size)
    return word


def _tomita_3(word: Sequence[str]) -> bool:
    """Every odd-length run of 1s is followed by an even-length run of 0s."""
    runs = _runs(word)
    for index, (symbol, size) in enumerate(runs):
        if symbol == "1" and size % 2 == 1 and index + 1 < len(runs):
            following = runs[index + 1]
            if following[0] == "0" and following[1] % 2 == 1:
                return False
    return True


TOMITA: dict[str, Task] = {}
for _k, (_predicate, _star_free, _positive) in {
    1: (lambda w: set(w) <= {"1"}, True, lambda a, n, r: [a[1]] * n),
    2: (lambda w: "".join(w) in {"10" * k for k in range(1, 64)}, True, _alternating),
    3: (_tomita_3, False, None),
    4: (lambda w: "000" not in "".join(w), True, _no_three_zeros),
    5: (lambda w: list(w).count("0") % 2 == 0 and list(w).count("1") % 2 == 0, False, None),
    6: (lambda w: (list(w).count("1") - list(w).count("0")) % 3 == 0, False, None),
    7: (lambda w: "".join(w).count("10") <= 1, True, _four_blocks),
}.items():
    TOMITA[f"tomita_{_k}"] = Task(
        name=f"tomita_{_k}",
        alphabet=("0", "1"),
        predicate=_predicate,
        star_free=_star_free,
        # 3, 5 and 6 are modular-counting languages; uniform words already
        # split them close to evenly, so they need no constructed positives.
        sampler=mutation_sampler(_positive) if _positive else None,
        lengths=(8, (1, 16), (17, 40)),
    )

TASKS: dict[str, Task] = {
    "ends_ab": Task("ends_ab", ("a", "b"), lambda w: "".join(w).endswith("ab")),
    "contains_ab": Task("contains_ab", ("a", "b"), lambda w: _factor(w, "ab")),
    "contains_aba": Task("contains_aba", ("a", "b"), lambda w: _factor(w, "aba")),
    "first_a": Task("first_a", ("a", "b"), lambda w: len(w) > 0 and w[0] == "a"),
    "last_a": Task("last_a", ("a", "b"), lambda w: len(w) > 0 and w[-1] == "a"),
    "a_star_b_star": Task("a_star_b_star", ("a", "b"), lambda w: not _factor(w, "ba")),
    "a_before_b": Task(
        "a_before_b",
        ("a", "b"),
        lambda w: any(w[i] == "a" and "b" in w[i + 1 :] for i in range(len(w))),
    ),
    "c_after_ab": Task(
        "c_after_ab",
        ("a", "b", "c"),
        lambda w: any(_factor(w[:i], "ab") for i in range(len(w)) if w[i] == "c"),
    ),
    "parity_a": Task(
        "parity_a", ("a", "b"), lambda w: list(w).count("a") % 2 == 0, star_free=False
    ),
    "equal_blocks": Task(
        "equal_blocks",
        ("a", "b"),
        lambda w: "".join(w) in {"a" * k + "b" * k for k in range(1, 64)},
        star_free=False,
        sampler=equal_blocks_sampler,
    ),
}


def contains_subsequence(word: Sequence[str], pattern: Sequence[str]) -> bool:
    """Whether `pattern` appears in `word` as a scattered subsequence."""
    remaining = iter(word)
    return all(any(symbol == wanted for symbol in remaining) for wanted in pattern)


def dot_depth_pattern(k: int, alphabet: Sequence[str]) -> tuple[str, ...]:
    return tuple(alphabet[i % len(alphabet)] for i in range(k))


def block_sampler(max_blocks: int):
    """Sample words as a random sequence of runs, not a random letter string.

    For the `dot_depth` family the label is "contains `abab...` of length k as
    a scattered subsequence", which for a two-letter alphabet is essentially
    "has at least k runs".  Uniform words of length 30 have ~20 runs, so at
    any k worth training they are positive with overwhelming probability and
    carry no signal.  Controlling the run count directly gives both classes at
    every length.
    """

    def sample(alphabet, count, min_length, max_length, rng):
        words = []
        for _ in range(count):
            length = rng.randint(min_length, max_length)
            blocks = rng.randint(1, max(1, min(max_blocks, length)))
            # A random composition of `length` into `blocks` positive parts.
            cuts = sorted(rng.sample(range(1, length), blocks - 1)) if blocks > 1 else []
            sizes = [b - a for a, b in zip([0] + cuts, cuts + [length])]
            word: list[str] = []
            previous = None
            for size in sizes:
                choices = [s for s in alphabet if s != previous] or list(alphabet)
                previous = rng.choice(choices)
                word.extend([previous] * size)
            words.append(tuple(word))
        return words

    return sample


def balanced_sample(
    task: "Task",
    count: int,
    min_length: int,
    max_length: int,
    rng: random.Random,
    attempts: int = 60,
) -> list[tuple[str, ...]]:
    """Draw `count` words, as close to a 50/50 label split as the sampler allows."""
    draw = task.sampler or (lambda alphabet, n, lo, hi, r: sample_words(alphabet, n, lo, hi, r))
    wanted = count // 2
    buckets: dict[bool, list[tuple[str, ...]]] = {True: [], False: []}
    for _ in range(attempts):
        for word in draw(task.alphabet, count, min_length, max_length, rng):
            bucket = buckets[task.label(word)]
            if len(bucket) < wanted:
                bucket.append(word)
        if all(len(b) >= wanted for b in buckets.values()):
            break
    words = buckets[True] + buckets[False]
    rng.shuffle(words)
    return words


def enumerate_words(alphabet: Sequence[str], max_length: int) -> list[tuple[str, ...]]:
    words: list[tuple[str, ...]] = []
    for length in range(max_length + 1):
        words.extend(product(alphabet, repeat=length))
    return words


def sample_words(
    alphabet: Sequence[str],
    count: int,
    min_length: int,
    max_length: int,
    rng: random.Random,
) -> list[tuple[str, ...]]:
    return [
        tuple(rng.choice(alphabet) for _ in range(rng.randint(min_length, max_length)))
        for _ in range(count)
    ]


def dot_depth_task(k: int, sigma: int) -> Task:
    """`dot_depth__k-K__sigma-S`, matching `examples/brasp/dot_depth__*.brasp`.

    The B-RASP program for this family is a chain of `k` attention ops, each
    reading the one below, so a model needs at least that many layers -- there
    is no way to compute it in fewer.
    """
    alphabet = tuple(chr(ord("a") + i) for i in range(sigma))
    pattern = dot_depth_pattern(k, alphabet)
    return Task(
        name=f"dot_depth__k-{k}__sigma-{sigma}",
        alphabet=alphabet,
        predicate=lambda word, pattern=pattern: contains_subsequence(word, pattern),
        star_free=True,
        sampler=block_sampler(max_blocks=2 * k + 2),
        lengths=(min(7, max(4, 2 * k)), (k, 4 * k + 4), (4 * k + 5, 10 * k + 10)),
    )


_DOT_DEPTH = re.compile(r"^dot_depth__k-(\d+)__sigma-(\d+)$")


def resolve(name: str) -> Task:
    """Look up a static task, or build a parameterised one from its name."""
    if name in TASKS:
        return TASKS[name]
    if name in TOMITA:
        return TOMITA[name]
    match = _DOT_DEPTH.match(name)
    if match:
        k, sigma = int(match.group(1)), int(match.group(2))
        if k < 1 or sigma < 2:
            raise KeyError(f"{name}: need k >= 1 and sigma >= 2")
        return dot_depth_task(k, sigma)
    raise KeyError(
        f"unknown task {name!r}; expected one of {', '.join(sorted(TASKS))} "
        f"or dot_depth__k-<K>__sigma-<S>"
    )


def iid_split(
    alphabet: Sequence[str],
    upto: int,
    fraction: float = 0.8,
    seed: int = 0,
) -> tuple[list[tuple[str, ...]], list[tuple[str, ...]]]:
    """A classical random split of every word up to `upto`: 80% train, 20% held out.

    This is the evaluation protocol most machine-learning work uses, and it is
    a strictly weaker check than the length split `datasets` builds. Train and
    holdout are drawn from one population, so a model that has fitted the
    lengths it was shown scores well on the holdout without generalising past
    them at all. Reporting both is what makes the difference visible.
    """
    words = enumerate_words(alphabet, upto)
    rng = random.Random(seed)
    rng.shuffle(words)
    cut = int(len(words) * fraction)
    return words[:cut], words[cut:]


def population_upto(alphabet: Sequence[str], budget: int = 5000) -> int:
    """Largest length whose full enumeration still fits in `budget` words.

    Chosen per alphabet so a two-letter and a three-letter language get
    comparably sized populations rather than comparable lengths.
    """
    size, upto, total = len(alphabet), 0, 1
    while total + size ** (upto + 1) <= budget:
        upto += 1
        total += size**upto
    return upto


def datasets(
    task: Task,
    enumerate_upto: int | None = None,
    train_samples: int = 512,
    train_length: tuple[int, int] = (9, 16),
    test_samples: int = 2000,
    test_length: tuple[int, int] = (17, 40),
    seed: int = 0,
) -> tuple[list[tuple[str, ...]], list[tuple[str, ...]]]:
    """Train on short words, test on strictly longer ones.

    The split is by length on purpose: a program extracted from a model that
    merely memorised short words will fail here, and length generalisation is
    the whole point of reading a symbolic program off the weights.
    """
    rng = random.Random(seed)
    if task.lengths is not None:
        planned, train_length, test_length = task.lengths
        # An explicit `enumerate_upto` is a deliberate choice of how much of
        # the language the model is shown, so it outranks the task's own plan;
        # without this the plan silently pins coverage and the flag is dead.
        enumerate_upto = planned if enumerate_upto is None else enumerate_upto
    elif enumerate_upto is None:
        enumerate_upto = 8
    train = enumerate_words(task.alphabet, enumerate_upto)
    train += balanced_sample(task, train_samples, *train_length, rng=rng)
    test = balanced_sample(task, test_samples, *test_length, rng=rng)
    return train, test


def _first_index(word: Sequence[str], symbol: str) -> int:
    """Index of the first `symbol`, or `len(word)` when it does not occur."""
    for index, letter in enumerate(word):
        if letter == symbol:
            return index
    return len(word)


# "First occurrence" languages. All five are star-free, so a masked
# hard-attention transformer can recognise them exactly -- but the natural
# B-RASP program for each attends to the *earliest* matching position, so
# extraction tends to produce `leftmost` heads. Until the translator learned
# to normalise those (`BraspNormalize`), such a program could be trained and
# evaluated but not verified, which is the gap these add coverage for.
TASKS.update(
    {
        "first_equals_last": Task(
            "first_equals_last", ("a", "b"), lambda w: len(w) > 0 and w[0] == w[-1]
        ),
        "starts_ab": Task(
            "starts_ab", ("a", "b"), lambda w: "".join(w).startswith("ab")
        ),
        "first_a_before_first_b": Task(
            "first_a_before_first_b",
            ("a", "b"),
            lambda w: "a" in w and "b" in w and _first_index(w, "a") < _first_index(w, "b"),
        ),
        "contains_abc": Task(
            "contains_abc", ("a", "b", "c"), lambda w: _factor(w, "abc")
        ),
        "no_c_before_first_b": Task(
            "no_c_before_first_b",
            ("a", "b", "c"),
            lambda w: "c" not in w[: _first_index(w, "b")],
        ),
    }
)
