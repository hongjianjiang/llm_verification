#!/usr/bin/env python3
"""The depth-bounded Dyck family: reference automata, and an exact checker.

    scripts/dyck_family.py facts --max-depth 20
    scripts/dyck_family.py check examples/brasp/dyck/dyck_d2.brasp --depth 2

`D(n)` is the Dyck language over one bracket pair restricted to nesting depth
at most `n`: balanced, never negative, never deeper than `n`. Its minimal DFA
is the bounded counter on `n+2` states, and it is star-free at every depth we
have checked, so it belongs in this pipeline's language class.

The point of this module is `check`. Verifying a candidate program by
enumerating words works only while the words are short: the shortest member of
`D(20)` is 40 symbols, so no feasible enumeration distinguishes a correct
program from a plausible wrong one. Instead we build the candidate's minimal
DFA by Myhill-Nerode and compare it with the reference counter -- a decision
procedure rather than a sample, which is what makes a depth-20 claim worth
anything. A `--words` pass is kept as an independent cross-check at the small
depths where it is still affordable.
"""

from __future__ import annotations

import argparse
import itertools
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from ltl2_generator.dfa import dfa_for_recognizer, is_aperiodic, minimize  # noqa: E402
from uhat import brasp  # noqa: E402

ALPHABET = ("a", "b")  # a opens, b closes


def predicate(depth_bound: int):
    """Membership in D(depth_bound). The empty word is out of scope here, as
    everywhere else in this pipeline: acceptance is read at the last position
    of a nonempty word."""

    def accepts(word) -> bool:
        level = 0
        for symbol in word:
            level += 1 if symbol == "a" else -1
            if level < 0 or level > depth_bound:
                return False
        return level == 0 and len(word) > 0

    return accepts


def reference_dfa(depth_bound: int):
    """The bounded counter, with the empty word excluded.

    State `0` is "nothing read yet" and state `k+1` is depth `k`, so the start
    is separate from the accepting depth-0 state: acceptance here is read at
    the last position of a *nonempty* word, and a reference that accepted the
    empty word would differ from every program in this pipeline by exactly
    that one word.
    """
    start, sink = 0, depth_bound + 2

    def transition(state: int, symbol: str) -> int:
        if state == sink:
            return sink
        level = 0 if state == start else state - 1
        nxt = level + (1 if symbol == "a" else -1)
        return nxt + 1 if 0 <= nxt <= depth_bound else sink

    return minimize(
        dfa_for_recognizer(ALPHABET, depth_bound + 3, start, {1}, transition)
    )


def candidate_dfa(accepts, suffix_length: int, cap: int = 4000):
    """Minimal DFA of a candidate, by residuals over a bounded suffix set."""
    import importlib.util

    spec = importlib.util.spec_from_file_location(
        "_dyck_dfa", Path(__file__).resolve().parent / "uhat_dfa.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.dfa_from_predicate(ALPHABET, accepts, suffix_length, cap)


def same_language(left, right) -> bool:
    """Product-construction emptiness of the symmetric difference."""
    if left is None or right is None:
        return False
    seen, frontier = {(0, 0)}, [(0, 0)]
    while frontier:
        p, q = frontier.pop()
        if (p in left.accepting) != (q in right.accepting):
            return False
        for index in range(len(ALPHABET)):
            step = (left.transitions[p][index], right.transitions[q][index])
            if step not in seen:
                seen.add(step)
                frontier.append(step)
    return True


def facts(max_depth: int) -> int:
    print(f"{'n':>3}{'|Q|':>6}{'monoid':>9}{'aperiodic':>11}{'shortest member':>18}")
    for n in range(1, max_depth + 1):
        dfa = reference_dfa(n)
        aperiodic, monoid = is_aperiodic(dfa)
        print(f"{n:>3}{dfa.states:>6}{str(monoid):>9}{str(aperiodic):>11}{2 * n:>18}")
    return 0


def check(path: Path, depth_bound: int, suffix_length: int, words_upto: int) -> int:
    program = brasp.parse(path.read_text())
    accepts = lambda w: brasp.accepts(program, list(w))  # noqa: E731
    reference, candidate = reference_dfa(depth_bound), candidate_dfa(accepts, suffix_length)
    if candidate is None:
        print(f"{path.name}: could not build a minimal DFA within the cap")
        return 1
    exact = same_language(candidate, reference)
    print(f"{path.name} vs D({depth_bound}): "
          f"states {candidate.states} vs {reference.states}, "
          f"languages {'EQUAL' if exact else 'DIFFER'}")

    if words_upto:
        oracle, bad = predicate(depth_bound), []
        for length in range(1, words_upto + 1):
            for letters in itertools.product(ALPHABET, repeat=length):
                if accepts(letters) != oracle(letters):
                    bad.append("".join(letters))
        print(f"  words to length {words_upto}: "
              + ("all agree" if not bad else f"{len(bad)} disagree, e.g. {bad[:5]}"))
        if bad:
            exact = False
    return 0 if exact else 1


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("facts", help="reference automaton facts per depth")
    p.add_argument("--max-depth", type=int, default=20)
    p = sub.add_parser("check", help="is this program exactly D(n)?")
    p.add_argument("path", type=Path)
    p.add_argument("--depth", type=int, required=True)
    p.add_argument("--suffix-length", type=int, default=0,
                   help="residual probe depth; defaults to 2n+3, enough to separate the counter's states")
    p.add_argument("--words", type=int, default=0, help="also compare on every word to this length")
    args = parser.parse_args(argv)
    if args.command == "facts":
        return facts(args.max_depth)
    return check(args.path, args.depth, args.suffix_length or 2 * args.depth + 3, args.words)


if __name__ == "__main__":
    raise SystemExit(main())
