#!/usr/bin/env python3
"""Identify each language as a DFA, and verify star-freeness independently.

The random languages are star-free *by construction* -- they are B-RASP
programs, and B-RASP expresses exactly the star-free languages. That is an
argument, not a check. This script produces a check: build the minimal DFA of
each language by Myhill-Nerode, then test whether its transition monoid is
aperiodic, which is Schutzenberger's criterion for star-freeness. A failure
would mean the generator, the evaluator, or the theory is wrong.

The DFA is built from residuals over a bounded suffix set, so it is minimal
only if that set separates every pair of inequivalent residuals. Rather than
assume it, the result is checked against the program itself on long random
words; a `verified` column of `False` means the suffix bound was too small.

    scripts/uhat_dfa.py --dir examples/brasp/random --out results/uhat_dfa.csv
"""

from __future__ import annotations

import argparse
import csv
import random
import sys
from itertools import product
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from ltl2_generator.dfa import DFA, is_aperiodic, minimize  # noqa: E402
from uhat import brasp  # noqa: E402


def dfa_from_program(program: brasp.Program, suffix_length: int, cap: int = 400) -> DFA | None:
    return dfa_from_predicate(
        program.alphabet,
        lambda w: brasp.accepts(program, list(w)),
        suffix_length,
        cap,
    )


def dfa_from_predicate(alphabet, predicate, suffix_length: int, cap: int = 400) -> DFA | None:
    """Minimal DFA by Myhill-Nerode, residuals probed on a bounded suffix set."""
    suffixes = [
        tuple(s) for n in range(suffix_length + 1) for s in product(alphabet, repeat=n)
    ]
    memo: dict[tuple[str, ...], bool] = {}

    def accepts(word: tuple[str, ...]) -> bool:
        if word not in memo:
            memo[word] = predicate(word)
        return memo[word]

    def signature(prefix: tuple[str, ...]) -> tuple[bool, ...]:
        return tuple(accepts(prefix + s) for s in suffixes)

    start = ()
    seen = {signature(start): 0}
    representatives = [start]
    transitions: list[list[int]] = []
    accepting: set[int] = set()
    queue = [start]
    while queue:
        prefix = queue.pop(0)
        index = seen[signature(prefix)]
        while len(transitions) <= index:
            transitions.append([0] * len(alphabet))
        # Acceptance belongs to the residual, not to this representative. A
        # state reached by the empty prefix can also be reached by non-empty
        # words (for parity, by `b`), so excluding the empty word here empties
        # the accepting set and minimisation collapses the whole automaton.
        if accepts(prefix):
            accepting.add(index)
        for position, symbol in enumerate(alphabet):
            nxt = prefix + (symbol,)
            key = signature(nxt)
            if key not in seen:
                if len(seen) >= cap:
                    return None
                seen[key] = len(seen)
                representatives.append(nxt)
                queue.append(nxt)
            transitions[index][position] = seen[key]
    return minimize(DFA(tuple(alphabet), 0, frozenset(accepting), tuple(map(tuple, transitions))))


def verify(dfa: DFA, predicate, alphabet, rng: random.Random, samples: int = 600) -> int:
    """Words where the DFA and the language's own definition disagree."""
    bad = 0
    for _ in range(samples):
        word = tuple(rng.choice(alphabet) for _ in range(rng.randint(1, 40)))
        if dfa.accepts(word) != predicate(word):
            bad += 1
    return bad


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dir", type=Path, default=Path("examples/brasp/random"))
    parser.add_argument("--out", type=Path, default=Path("results/uhat_dfa.csv"))
    parser.add_argument("--suffix", type=int, default=5)
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument(
        "--controls",
        action="store_true",
        help="run the same check on the Tomita grammars instead, three of which "
        "are known NOT to be star-free -- without them a clean sweep of "
        "'aperiodic' proves nothing, because a checker that always says yes "
        "would produce it too",
    )
    args = parser.parse_args()

    rng = random.Random(args.seed)
    rows = []

    if args.controls:
        from uhat.tasks import TASKS, TOMITA

        print(f"{'language':<16}{'states':>8}{'aperiodic':>11}{'monoid':>8}"
              f"{'expected':>12}{'verdict':>10}")
        print("-" * 65)
        checks = list(TOMITA.items()) + [(k, TASKS[k]) for k in ("parity_a", "a_star_b_star")]
        for name, task in checks:
            dfa = dfa_from_predicate(task.alphabet, lambda w, t=task: t.label(list(w)), args.suffix)
            if dfa is None:
                print(f"{name:<16}{'  too many states':>27}")
                continue
            aperiodic, size = is_aperiodic(dfa)
            expected = task.star_free
            print(f"{name:<16}{dfa.states:>8}{str(aperiodic):>11}{str(size):>8}"
                  f"{str(expected):>12}{'ok' if aperiodic == expected else 'MISMATCH':>10}")
        return 0

    print(f"{'language':<14}{'|S|':>5}{'states':>8}{'aperiodic':>11}{'monoid':>8}"
          f"{'verified':>10}{'class':>12}")
    print("-" * 68)
    for path in sorted(args.dir.glob("*.brasp")):
        program = brasp.parse(path.read_text())
        dfa = dfa_from_program(program, args.suffix)
        if dfa is None:
            print(f"{path.stem:<14}{len(program.alphabet):>5}{'  too many states':>27}")
            continue
        aperiodic, size = is_aperiodic(dfa)
        mismatches = verify(dfa, lambda w: brasp.accepts(program, list(w)),
                            program.alphabet, rng)
        trivial = len(dfa.accepting) == 0 or dfa.accepting == frozenset(range(dfa.states))
        label = "empty" if not dfa.accepting else ("universal" if trivial else "star-free")
        if not aperiodic:
            label = "NOT star-free"
        rows.append({
            "language": path.stem, "alphabet": len(program.alphabet),
            "dfa_states": dfa.states, "aperiodic": aperiodic,
            "monoid_size": size, "dfa_matches_program": mismatches == 0,
            "classification": label,
        })
        print(f"{path.stem:<14}{len(program.alphabet):>5}{dfa.states:>8}"
              f"{str(aperiodic):>11}{str(size):>8}{str(mismatches == 0):>10}{label:>12}", flush=True)

    if rows:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        with args.out.open("w", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=list(rows[0]), lineterminator="\n")
            writer.writeheader()
            writer.writerows(rows)
        ok = sum(1 for r in rows if r["aperiodic"])
        ver = sum(1 for r in rows if r["dfa_matches_program"])
        print(f"\n{len(rows)} languages; {ok} aperiodic (star-free), "
              f"{ver} with a DFA verified against the program")
        print(f"DFA states: min {min(r['dfa_states'] for r in rows)}, "
              f"max {max(r['dfa_states'] for r in rows)}")
        print(f"wrote {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
