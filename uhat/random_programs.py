"""Randomly generate star-free languages, as random B-RASP programs.

Sampling a star-free language directly is awkward -- you would have to sample
a regular language and test its syntactic monoid for aperiodicity, rejecting
most of what you draw. Sampling a *B-RASP program* instead makes the class
free: everything B-RASP expresses is star-free by construction, so every
program drawn here is a valid target and none has to be discarded for being
outside the class.

What does have to be watched is degeneracy. A random program is quite often
the empty or the universal language, which trains to 1.0 by always rejecting
or always accepting and says nothing. `describe_language` measures that, and
the emptiness check turns it from a nuisance into a signal.
"""

from __future__ import annotations

import random
from typing import Sequence

from . import brasp


def _random_dnf(
    names: Sequence[str],
    rng: random.Random,
    predicate: bool,
    terms: int = 2,
    literals: int = 2,
    require_witness: bool = False,
) -> brasp.Expr:
    """A small DNF over `names`, with `@i`/`@j` sides when `predicate`.

    `require_witness` forces every clause to read at least one feature at `j`.
    Without it a head's value can depend only on the query position, so the
    head reports nothing about where it attended and the language collapses to
    a local letter predicate -- which is how an earlier version of this
    generator produced 48 programs computing 34 languages, most of them
    "look at the last symbol".
    """
    clauses = []
    for _ in range(rng.randint(1, terms)):
        chosen = []
        for _ in range(rng.randint(1, literals)):
            name = rng.choice(names)
            at = rng.choice(("i", "j")) if predicate else "i"
            reference = brasp.Ref(name, at)
            chosen.append(brasp.Not(reference) if rng.random() < 0.4 else reference)
        if require_witness and not any(
            isinstance(x, brasp.Ref) and x.at == "j"
            or isinstance(x, brasp.Not) and getattr(x.arg, "at", "i") == "j"
            for x in chosen
        ):
            witness = brasp.Ref(rng.choice(names), "j")
            chosen.append(brasp.Not(witness) if rng.random() < 0.35 else witness)
        clauses.append(brasp.conjunction(chosen))
    return brasp.disjunction(clauses)


def random_program(
    rng: random.Random,
    alphabet: tuple[str, ...] = ("a", "b"),
    attention_ops: int = 3,
    boolean_ops: int = 2,
) -> brasp.Program:
    """A random program whose output depends on at least one attention op."""
    subprograms: list[brasp.Subprogram] = [brasp.Bos("is_bos")]
    names = ["is_bos"]
    for index, symbol in enumerate(alphabet):
        name = f"is_{symbol}" if symbol.isalnum() and not symbol[0].isdigit() else f"is_s{index}"
        subprograms.append(brasp.Symbol(name, symbol))
        names.append(name)

    plan = ["attention"] * attention_ops + ["boolean"] * boolean_ops
    rng.shuffle(plan)
    # The last op must be an attention op, so the language actually needs one;
    # otherwise the "random star-free language" is just a letter predicate.
    plan.append("attention")

    last_attention = None
    for step, kind in enumerate(plan):
        name = f"n{step}"
        if kind == "attention":
            direction = rng.choice(("rightmost", "leftmost"))
            score = _random_dnf(names, rng, predicate=True)
            value = _random_dnf(names, rng, predicate=True, require_witness=True)
            subprograms.append(brasp.Attention(name, direction, score, value))
            last_attention = name
        else:
            subprograms.append(brasp.BoolNode(name, _random_dnf(names, rng, predicate=False)))
        names.append(name)

    output_expr = brasp.conjunction(
        [brasp.Ref(last_attention), _random_dnf(names, rng, predicate=False)]
    ) if rng.random() < 0.5 else brasp.Ref(last_attention)
    subprograms.append(brasp.BoolNode("accept", output_expr))
    return brasp.Program(tuple(subprograms), "accept", alphabet)


def acceptance_rate(
    program: brasp.Program, rng: random.Random, low: int, high: int, samples: int
) -> float:
    words = [
        tuple(rng.choice(program.alphabet) for _ in range(rng.randint(low, high)))
        for _ in range(samples)
    ]
    return sum(brasp.accepts(program, w) for w in words) / len(words)


def describe_language(
    program: brasp.Program, rng: random.Random, samples: int = 120
) -> dict:
    """Program shape plus sampled acceptance at short and long lengths.

    Short words are screened first and the long pass is skipped when the short
    one already shows the language is empty or universal. The evaluator is
    `O(nodes * n^2)` per word, so screening on length<=8 before touching
    length<=40 is roughly a twenty-fold saving on the draws that get rejected
    -- which is most of them.
    """
    from .programs import attention_depth

    facts = {
        "alphabet": len(program.alphabet),
        "depth": attention_depth(program),
        "attention_ops": sum(1 for s in program.subprograms if isinstance(s, brasp.Attention)),
    }
    short = acceptance_rate(program, rng, 1, 8, samples)
    facts["short_rate"] = round(short, 4)
    if short in (0.0, 1.0):
        facts["long_rate"] = short  # degenerate on short words; do not pay for long ones
        return facts
    facts["long_rate"] = round(acceptance_rate(program, rng, 17, 28, samples), 4)
    return facts


def sample_languages(
    count: int,
    rng: random.Random,
    alphabets: Sequence[tuple[str, ...]] = (("a", "b"), ("a", "b", "c")),
    attention_ops: tuple[int, int] = (1, 3),
    boolean_ops: tuple[int, int] = (0, 2),
    band: tuple[float, float] = (0.05, 0.95),
    max_depth: int = 4,
    attempts_per_keep: int = 200,
) -> tuple[list[tuple[brasp.Program, dict]], dict]:
    """Draw `count` non-degenerate random star-free languages.

    Rejection sampling is doing real work here: a random program is usually
    the empty language, because conjunctions of random literals over-constrain
    and `conjunction` folds contradictory ones to `false` outright. The band
    keeps languages whose acceptance rate is non-trivial at *both* short and
    long lengths -- a language that accepts everything short and nothing long
    is learnable by length alone and teaches nothing.
    """
    kept: list[tuple[brasp.Program, dict]] = []
    tally = {"drawn": 0, "empty": 0, "universal": 0, "too_deep": 0, "kept": 0}
    low, high = band

    while len(kept) < count and tally["drawn"] < count * attempts_per_keep:
        tally["drawn"] += 1
        program = random_program(
            rng,
            rng.choice(list(alphabets)),
            rng.randint(*attention_ops),
            rng.randint(*boolean_ops),
        )
        facts = describe_language(program, rng)
        if facts["depth"] < 1 or facts["depth"] > max_depth:
            tally["too_deep"] += 1
            continue
        if facts["short_rate"] == 0.0 and facts["long_rate"] == 0.0:
            tally["empty"] += 1
            continue
        if facts["short_rate"] == 1.0 and facts["long_rate"] == 1.0:
            tally["universal"] += 1
            continue
        if not (low <= facts["long_rate"] <= high):
            continue
        kept.append((program, facts))
        tally["kept"] += 1
    return kept, tally


# --- diversity filtering, on the language rather than the program ------------


def canonical_dfa(program: brasp.Program, suffix_length: int = 5):
    """A canonical form of the language: BFS-relabelled minimal DFA.

    Two programs are the same language exactly when these agree, so this is
    both the diversity measure and the deduplication key. Filtering on the
    *program* cannot do this job -- an elaborate depth-4 program and a
    one-line one can compute the same two-state language.
    """
    import importlib.util
    from pathlib import Path as _Path

    spec = importlib.util.spec_from_file_location(
        "_uhat_dfa", _Path(__file__).resolve().parent.parent / "scripts" / "uhat_dfa.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    dfa = module.dfa_from_program(program, suffix_length)
    if dfa is None:
        return None, None

    order = {dfa.initial: 0}
    queue = [dfa.initial]
    while queue:
        state = queue.pop(0)
        for symbol in range(len(dfa.alphabet)):
            target = dfa.transitions[state][symbol]
            if target not in order:
                order[target] = len(order)
                queue.append(target)
    states = sorted(order, key=order.get)
    signature = (
        tuple(dfa.alphabet),
        tuple(tuple(order[dfa.transitions[s][a]] for a in range(len(dfa.alphabet)))
              for s in states),
        tuple(sorted(order[s] for s in dfa.accepting if s in order)),
    )
    return signature, dfa


def sample_diverse(
    count: int,
    rng: random.Random,
    min_states: int = 4,
    band: tuple[float, float] = (0.08, 0.92),
    min_depth: int = 1,
    max_depth: int = 4,
    attention_ops: tuple[int, int] = (2, 4),
    alphabets: Sequence[tuple[str, ...]] = (("a", "b"), ("a", "b", "c")),
    known: Sequence = (),
    attempts: int = 6000,
):
    """Draw `count` languages that are pairwise distinct and non-trivial.

    Three filters, in increasing cost: the acceptance-rate band rejects empty
    and universal draws cheaply; the minimal DFA must have at least
    `min_states` states, which is what rules out "look at the last symbol";
    and the canonical DFA must be new, both against the languages already kept
    and against `known` (the readable catalogue).
    """
    seen = {k for k in known if k is not None}
    kept, tally = [], {"drawn": 0, "degenerate": 0, "too_shallow": 0,
                       "too_simple": 0, "duplicate": 0, "kept": 0}

    while len(kept) < count and tally["drawn"] < attempts:
        tally["drawn"] += 1
        program = random_program(
            rng, rng.choice(list(alphabets)),
            rng.randint(*attention_ops), rng.randint(0, 2),
        )
        facts = describe_language(program, rng)
        if facts["depth"] < max(1, min_depth) or facts["depth"] > max_depth:
            tally["too_shallow"] += 1
            continue
        if not (band[0] <= facts["long_rate"] <= band[1]):
            tally["degenerate"] += 1
            continue
        signature, dfa = canonical_dfa(program)
        if dfa is None or dfa.states < min_states:
            tally["too_simple"] += 1
            continue
        if signature in seen:
            tally["duplicate"] += 1
            continue
        seen.add(signature)
        facts["dfa_states"] = dfa.states
        kept.append((program, facts))
        tally["kept"] += 1
    return kept, tally
