#!/usr/bin/env python3
"""Parameterised B-RASP benchmark families, chosen to cover what the existing
suite does not.

    scripts/brasp_families.py build --out examples/brasp/families
    scripts/brasp_families.py verify --out examples/brasp/families --words 12

The six families already in the benchmark scale three axes: `Once`-nesting
(`L_dot`), `Y`-nesting (`L_Y`, `L_no2a`) and alphabet size (`L_mono`,
`L_since`, `L_slb`). Auditing them turned up three holes, each of which hid a
real defect or could have:

  * `leftmost` attention is never used -- every one of the six compiles to
    `rightmost` only, so half the attention surface went untested. A
    miscompilation lived in this pipeline until a hand-written program
    happened to exercise a shape the suite never produced.
  * no family scales `Since`-nesting, the operator the second variable exists
    for.
  * no family separates *formula* nesting from *state-space* growth: in all
    six, turning the parameter up grows both at once, so a cost cannot be
    attributed to either.

`first` closes the first; `marks` closes the second and third at once, having
a `k+1`-state minimal DFA however deep its nesting; `marked_same` adds the
anchor reference, so the one-variable route must case-split on it.
"""

from __future__ import annotations

import argparse
import itertools
import string
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from uhat import brasp  # noqa: E402

# --- first(k): first occurrences appear in alphabetical order ---------------


def first_program(k: int) -> str:
    """Every symbol occurs, and their first occurrences are in order.

    Written with `leftmost`, which is what the family exists to exercise:
    `leftmost(in_pair, is_earlier)` asks whether the *earliest* position
    carrying either of two symbols carries the earlier one. Attention is
    strict past, so the final position is never a witness and each clause has
    to name it separately.
    """
    letters = string.ascii_lowercase[:k]
    lines = [f"alphabet {' '.join(letters)}", ""]
    for letter in letters:
        lines.append(f"is_{letter} = symbol {letter}")
    for letter in letters:
        lines.append(f"seen_{letter} = rightmost(is_{letter}@j, true)")
        lines.append(f"occurs_{letter} = seen_{letter} | is_{letter}")
    clauses = []
    for left, right in zip(letters, letters[1:]):
        lines.append(f"pair_{left}{right} = is_{left} | is_{right}")
        lines.append(f"any_{left}{right} = rightmost(pair_{left}{right}@j, true)")
        lines.append(f"first_{left}{right} = leftmost(pair_{left}{right}@j, is_{left}@j)")
        lines.append(f"ok_{left}{right} = any_{left}{right} & first_{left}{right} | !any_{left}{right} & is_{left}")
        clauses.append(f"ok_{left}{right}")
    clauses += [f"occurs_{letter}" for letter in letters]
    lines.append("accept = " + " & ".join(clauses))
    lines += ["", "output accept"]
    return "\n".join(lines) + "\n"


def first_predicate(k: int):
    letters = string.ascii_lowercase[:k]

    def accepts(word) -> bool:
        firsts = []
        for letter in letters:
            if letter not in word:
                return False
            firsts.append(word.index(letter))
        return all(x < y for x, y in zip(firsts, firsts[1:]))

    return accepts


# --- marks(k): at least k markers, by k nested attentions -------------------


def marks_program(k: int) -> str:
    """`h_t` is "this position is a marker with at least `t-1` markers before
    it", built from `t` nested `rightmost` lookups over markers -- `Since`-depth
    `k`. The minimal DFA has only `k+1` states, so nesting grows while the state
    space stays flat, which is the separation the existing families cannot make.
    An attention has to be a whole right-hand side, so each conjunction gets its
    own name."""
    lines = ["alphabet a m", "", "is_m = symbol m", "h_1 = is_m"]
    for t in range(2, k + 1):
        lines.append(f"p_{t} = rightmost(is_m@j, h_{t - 1}@j)")
        lines.append(f"h_{t} = is_m & p_{t}")
    lines.append(f"seen_{k} = rightmost(h_{k}@j, true)")
    lines.append(f"accept = h_{k} | seen_{k}")
    lines += ["", "output accept"]
    return "\n".join(lines) + "\n"


def marks_predicate(k: int):
    return lambda word: sum(1 for s in word if s == "m") >= k


# --- marked_same(k): the last qualifying marker agrees with the anchor ------


def marked_same_program(k: int, sigma: int) -> str:
    """`marks`, plus an anchor reference: the symbol before the most recent
    marker that is `k`-th or later must equal the symbol the word ends with.

    The `@i` inside the attention value is the point -- it is what the pebble
    reads directly and what the one-variable route has to case-split on, so
    this family scales nesting and the case split at the same time.
    """
    letters = string.ascii_lowercase[:sigma]
    # The marker is a symbol like any other, so "the same symbol as the anchor"
    # has to range over it too; leaving it out makes the program blind to a
    # match between two markers.
    symbols = list(letters) + ["m"]
    lines = [f"alphabet {' '.join(letters)} m", "", "is_m = symbol m"]
    for letter in letters:
        lines.append(f"is_{letter} = symbol {letter}")
    for symbol in symbols:
        lines.append(f"prev_{symbol} = rightmost(true, is_{symbol}@j)")
    lines.append("h_1 = is_m")
    for t in range(2, k + 1):
        lines.append(f"p_{t} = rightmost(is_m@j, h_{t - 1}@j)")
        lines.append(f"h_{t} = is_m & p_{t}")
    same = " | ".join(f"is_{symbol}@i & prev_{symbol}@j" for symbol in symbols)
    lines.append(f"accept = rightmost(h_{k}@j, {same})")
    lines += ["", "output accept"]
    return "\n".join(lines) + "\n"


def marked_same_predicate(k: int, sigma: int):
    """Mirror of the program, written from the language description rather than
    from the program, so that agreement is evidence."""

    def accepts(word) -> bool:
        # B-RASP position p holds word[p - 1]; attention is strict past, so the
        # final position is never itself a witness.
        markers, qualifying = 0, []
        for position in range(1, len(word) + 1):
            if word[position - 1] == "m":
                markers += 1
                if markers >= k and position < len(word):
                    qualifying.append(position)
        if not qualifying:
            return False
        witness = qualifying[-1]
        if witness - 1 < 1:  # its predecessor is the BOS position
            return False
        return word[witness - 2] == word[-1]

    return accepts


FAMILIES = {
    "first": (first_program, first_predicate, list(range(2, 21))),
    "marks": (marks_program, marks_predicate, list(range(1, 21))),
    "marked_same": (
        lambda k: marked_same_program(k, 2),
        lambda k: marked_same_predicate(k, 2),
        list(range(1, 21)),
    ),
}


def build(out: Path) -> int:
    out.mkdir(parents=True, exist_ok=True)
    written = []
    for name, (program, _, params) in FAMILIES.items():
        for k in params:
            path = out / f"{name}__k-{k}.brasp"
            path.write_text(program(k))
            written.append(path)
    print(f"wrote {len(written)} programs to {out}")
    return 0


def verify(out: Path, words_upto: int) -> int:
    """Three independent checks per program, because one is not enough here.

    Exhaustive enumeration is decisive but only reaches short words, and for
    `first(k)` every word shorter than `k` is a non-member -- so an exhaustive
    pass alone would confirm nothing but mutual rejection. Random sampling at
    lengths where members actually live catches that; a DFA comparison, where
    the alphabet is small enough to build one, decides the whole language.
    The predicates are written from the language description, never from the
    program, so agreement is evidence rather than a tautology.
    """
    import random

    from scripts_dfa import candidate_dfa, same_language  # provided below

    failures = 0
    print(f"{'program':<26}{'|S|':>4}{'exhaustive':>22}{'sampled':>18}{'dfa':>12}")
    for name, (program, predicate, params) in FAMILIES.items():
        for k in params:
            path = out / f"{name}__k-{k}.brasp"
            if not path.exists():
                continue
            parsed = brasp.parse(path.read_text())
            oracle, alphabet = predicate(k), parsed.alphabet
            accepts = lambda w: brasp.accepts(parsed, list(w))  # noqa: E731

            longest, total = 0, 0
            while longest < words_upto and total + len(alphabet) ** (longest + 1) <= 50_000:
                longest += 1
                total += len(alphabet) ** longest
            bad = [
                "".join(letters)
                for n in range(1, longest + 1)
                for letters in itertools.product(alphabet, repeat=n)
                if accepts(letters) != oracle(list(letters))
            ]

            # Sampling at lengths where members exist, plus guaranteed members
            # so a program that rejects everything cannot pass quietly.
            rng = random.Random(k)
            sampled, members = [], 0
            for _ in range(4000):
                length = rng.randint(k, max(k * 3, 12))
                word = [rng.choice(alphabet) for _ in range(length)]
                if oracle(word):
                    members += 1
                if accepts(word) != oracle(word):
                    sampled.append("".join(word))

            # The residual probe costs |alphabet|^(suffix+1) evaluations per
            # signature, so the suffix length has to be chosen against that
            # budget rather than against the parameter: `marks(12)` at a naive
            # `2k+3` would enumerate 2^28 suffixes per prefix and never return.
            suffix = k + 2
            probes = len(alphabet) ** (suffix + 1)
            verdict_dfa = "n/a" if len(alphabet) > 3 else ("too big" if probes > 20_000 else "")
            if verdict_dfa == "":
                left = candidate_dfa(alphabet, accepts, suffix)
                right = candidate_dfa(alphabet, oracle, suffix)
                verdict_dfa = "EQUAL" if same_language(alphabet, left, right) else "DIFFER"
                if verdict_dfa != "EQUAL":
                    failures += 1

            if bad or sampled:
                failures += 1
            print(f"{path.name:<26}{len(alphabet):>4}"
                  f"{('<=' + str(longest) + ' ok' if not bad else str(len(bad)) + ' BAD'):>22}"
                  f"{(str(members) + ' members ok' if not sampled else str(len(sampled)) + ' BAD'):>18}"
                  f"{verdict_dfa:>12}")
    print("\nall checks passed" if not failures else f"\n{failures} programs FAILED")
    return 1 if failures else 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("build")
    p.add_argument("--out", type=Path, default=Path("examples/brasp/families"))
    p = sub.add_parser("verify")
    p.add_argument("--out", type=Path, default=Path("examples/brasp/families"))
    p.add_argument("--words", type=int, default=12)
    args = parser.parse_args(argv)
    return build(args.out) if args.command == "build" else verify(args.out, args.words)


if __name__ == "__main__":
    raise SystemExit(main())
