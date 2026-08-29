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


def datasets(
    task: Task,
    enumerate_upto: int = 8,
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
        enumerate_upto, train_length, test_length = task.lengths
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
