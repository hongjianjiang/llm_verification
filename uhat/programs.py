"""Turn any B-RASP program into a training task.

This is how the repository's own benchmark languages become trainable: the
jar converts `examples/ltl/X.ltl` into a `.brasp` program, `uhat.brasp.parse`
reads it, and `uhat.brasp.accepts` labels words.

Labelling through B-RASP rather than through `ltl2_generator.eval` is not an
implementation detail. B-RASP has a BOS position at index 0 that the LTL2
evaluator does not, so `H(mask)` -- which quantifies over `j < i` -- differs
between them whenever the mask needs a real symbol at `j`: on `ab`,
`two_var__monotone_past` is true under the LTL2 evaluator and false under the
jar. Training against the LTL side would produce programs that disagree with
the very specification they are supposed to be checked against.
"""

from __future__ import annotations

import random
from pathlib import Path
from typing import Sequence

from . import brasp
from .tasks import Task, block_sampler, sample_words


def attention_depth(program: brasp.Program) -> int:
    """Longest chain of attention ops, i.e. the layers a model needs.

    Attention ops that only read base features can share one layer; one that
    reads another's output needs a layer below it.
    """
    depth: dict[str, int] = {}
    for subprogram in program.subprograms:
        below = max((depth.get(name, 0) for name in brasp.subprogram_references(subprogram)), default=0)
        depth[subprogram.name] = below + (1 if isinstance(subprogram, brasp.Attention) else 0)
    return depth.get(program.output, 0)


def attention_count(program: brasp.Program) -> int:
    return sum(1 for s in program.subprograms if isinstance(s, brasp.Attention))


def mixed_sampler(max_blocks: int = 12):
    """Half uniform words, half run-structured ones.

    Uniform sampling alone is degenerate for languages whose label is
    essentially "how many runs does this word have" (the `dot_depth` family);
    run-structured sampling alone under-covers languages that care about
    particular letters in particular places. Mixing gives both classes for
    almost everything in `examples/`.
    """
    blocks = block_sampler(max_blocks)

    def sample(alphabet, count, min_length, max_length, rng):
        half = count // 2
        return (
            sample_words(alphabet, count - half, min_length, max_length, rng)
            + blocks(alphabet, half, min_length, max_length, rng)
        )

    return sample


def program_task(path: str | Path, name: str | None = None) -> Task:
    """A `Task` whose labels come from evaluating the `.brasp` program."""
    program = brasp.parse(Path(path).read_text())
    alphabet = program.alphabet
    enumerate_upto = 7 if len(alphabet) <= 2 else (5 if len(alphabet) <= 4 else 3)
    return Task(
        name=name or Path(path).stem,
        alphabet=alphabet,
        predicate=lambda word, program=program: brasp.accepts(program, list(word)),
        star_free=True,  # everything B-RASP expresses is star-free by definition
        sampler=mixed_sampler(),
        lengths=(enumerate_upto, (1, 16), (17, 40)),
    )


def describe(path: str | Path) -> dict:
    """Size facts used to decide whether a language is worth training on."""
    program = brasp.parse(Path(path).read_text())
    return {
        "name": Path(path).stem,
        "path": str(path),
        "alphabet": len(program.alphabet),
        "subprograms": len(program.subprograms),
        "attention_ops": attention_count(program),
        "depth": attention_depth(program),
    }


def is_feasible(facts: dict, max_alphabet: int = 4, max_depth: int = 8) -> bool:
    """Whether a model small enough to train could express this language.

    Depth is the binding constraint: a program needing `d` chained attention
    ops needs at least `d` layers, and the discrete search gets rapidly harder
    with depth. Alphabet size is the other: base features are one-hot per
    symbol, so a 256-letter alphabet means a 257-feature DNF at every gate.
    """
    return facts["alphabet"] <= max_alphabet and 1 <= facts["depth"] <= max_depth
