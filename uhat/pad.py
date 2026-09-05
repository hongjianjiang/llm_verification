"""Raise a program's attention depth without changing the language it computes.

The corpus was sampled three times with different `--attention-ops` ranges, so
depth and language vary together: the deep languages are also the ones a
person would call complicated.  That confounds "learnability is flat in
attention depth".  Padding separates them -- same language, same minimal DFA,
same monoid, but a program that needs `d` chained attention ops instead of the
`d0` it was born with.

The padding step is

    pad = rightmost(is_bos@j, prev@i)

At any query `i >= 1` the score `is_bos@j` is satisfied by `j = 0` and by
nothing else, so the op attends to the BOS position and returns `prev` read
back at the query itself: `pad = prev` on every position of a nonempty word.
At `i = 0` no `j < 0` exists and the op yields `false`, which is invisible
because acceptance is read at position `|w|` and languages here are subsets of
`Sigma^+`.  Each step is a real attention op on the critical path -- a model
cannot skip a layer to compute it -- but it is semantically the identity.
"""

from __future__ import annotations

from . import brasp
from .programs import attention_depth


def _fresh(existing: set[str], stem: str) -> str:
    if stem not in existing:
        return stem
    index = 1
    while f"{stem}_{index}" in existing:
        index += 1
    return f"{stem}_{index}"


def pad_program(program: brasp.Program, target_depth: int) -> brasp.Program:
    """Return an equivalent program whose attention depth is `target_depth`.

    Raises ValueError if the program is already deeper: padding only adds.
    """
    current = attention_depth(program)
    if target_depth < current:
        raise ValueError(f"cannot pad depth {current} down to {target_depth}")
    if target_depth == current:
        return program

    names = {s.name for s in program.subprograms}
    bos = next((s.name for s in program.subprograms if isinstance(s, brasp.Bos)), None)
    subprograms = list(program.subprograms)
    if bos is None:
        bos = _fresh(names, "is_bos")
        names.add(bos)
        subprograms.insert(0, brasp.Bos(bos))

    previous = program.output
    for step in range(target_depth - current):
        name = _fresh(names, f"pad_{step + 1}")
        names.add(name)
        subprograms.append(
            brasp.Attention(
                name=name,
                direction="rightmost",
                score=brasp.Ref(bos, "j"),
                value=brasp.Ref(previous, "i"),
            )
        )
        previous = name

    return brasp.Program(tuple(subprograms), previous, program.alphabet)
