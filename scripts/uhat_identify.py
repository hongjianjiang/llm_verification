#!/usr/bin/env python3
"""Identify each language by proving it equal to a readable reference program.

The Myhill-Nerode route in `uhat_dfa.py` reconstructs an automaton from
sampled residuals, so its verdicts are evidence rather than proof. This route
goes through the verifier instead: build a catalogue of small, human-readable
B-RASP programs, and for each language ask the model checker whether it is
*equal* to one of them, or failing that which catalogue entries are supersets
of it.

Equality and inclusion are decided by the same ABC path the rest of the
pipeline uses, so a match here is proved, not sampled. Candidates are
pre-filtered in Python first -- agreeing on every word to length 8 -- because
the checker costs seconds per pair and the catalogue is large.

    scripts/uhat_identify.py --dir examples/brasp/random
"""

from __future__ import annotations

import argparse
import csv
import itertools
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from uhat import brasp  # noqa: E402


def _base(alphabet):
    names = {"bos": "is_bos"}
    subs = [brasp.Bos("is_bos")]
    for s in alphabet:
        names[s] = f"is_{s}"
        subs.append(brasp.Symbol(f"is_{s}", s))
    return subs, names


def catalogue(alphabet: tuple[str, ...]) -> dict[str, brasp.Program]:
    """Small readable languages over `alphabet`, as B-RASP programs."""
    out: dict[str, brasp.Program] = {}

    def make(name, extra, accept):
        subs, _ = _base(alphabet)
        out[name] = brasp.Program(tuple(subs + extra), "accept", alphabet)
        out[name] = brasp.Program(tuple(subs + extra + [brasp.BoolNode("accept", accept)]),
                                  "accept", alphabet)

    make("empty", [], brasp.FALSE)
    make("universal", [], brasp.TRUE)
    for x in alphabet:
        ix = brasp.Ref(f"is_{x}")
        ixj = brasp.Ref(f"is_{x}", "j")
        # last symbol is x
        make(f"last_{x}", [], ix)
        make(f"not_last_{x}", [], brasp.Not(ix))
        # some position holds x
        seen = brasp.Attention(f"seen_{x}", "rightmost", ixj, brasp.TRUE)
        make(f"contains_{x}", [seen],
             brasp.disjunction([ix, brasp.Ref(f"seen_{x}")]))
        make(f"no_{x}", [seen],
             brasp.conjunction([brasp.Not(ix), brasp.Not(brasp.Ref(f"seen_{x}"))]))
        # first symbol is x
        first = brasp.Attention(f"first_{x}", "leftmost",
                                brasp.Not(brasp.Ref("is_bos", "j")), ixj)
        make(f"first_is_{x}", [first],
             brasp.disjunction([brasp.Ref(f"first_{x}"),
                                brasp.conjunction([brasp.Ref("is_bos", "i"), ix])]))
        # previous symbol is x
        prev = brasp.Attention(f"prev_{x}", "rightmost", brasp.TRUE, ixj)
        make(f"prev_is_{x}", [prev], brasp.Ref(f"prev_{x}"))
        for y in alphabet:
            iy = brasp.Ref(f"is_{y}")
            # ends with the factor xy
            p2 = brasp.Attention(f"p_{x}", "rightmost", brasp.TRUE, ixj)
            make(f"ends_{x}{y}", [p2], brasp.conjunction([iy, brasp.Ref(f"p_{x}")]))
            # x occurs strictly before some y (scattered subsequence xy)
            sx = brasp.Attention(f"sx_{x}", "rightmost", ixj, brasp.TRUE)
            at_y = brasp.BoolNode(f"aty_{x}{y}", brasp.conjunction([iy, brasp.Ref(f"sx_{x}")]))
            any_y = brasp.Attention(f"anyy_{x}{y}", "rightmost",
                                    brasp.Ref(f"aty_{x}{y}", "j"), brasp.TRUE)
            make(f"{x}_before_{y}", [sx, at_y, any_y],
                 brasp.disjunction([brasp.Ref(f"aty_{x}{y}"), brasp.Ref(f"anyy_{x}{y}")]))
    return out


def agrees(a: brasp.Program, b: brasp.Program, words) -> bool:
    return all(brasp.accepts(a, list(w)) == brasp.accepts(b, list(w)) for w in words)


def contained(a: brasp.Program, b: brasp.Program, words) -> bool:
    """Sampled test for L(a) subset-of L(b)."""
    return all((not brasp.accepts(a, list(w))) or brasp.accepts(b, list(w)) for w in words)


def check(jar: str, mode: str, superset: Path, inp: Path, timeout: int) -> bool:
    flag = "--equivalent" if mode == "eq" else "--subset"
    result = subprocess.run(
        ["java", "-jar", jar, flag, str(superset), str(inp), "--run-abc"],
        capture_output=True, text=True, timeout=timeout,
    )
    out = result.stdout + result.stderr
    return "PROVED" in out and "NOT PROVED" not in out


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dir", type=Path, default=Path("examples/brasp/random"))
    parser.add_argument("--out", type=Path, default=Path("results/uhat_identified.csv"))
    parser.add_argument("--jar", default="target/scala-3.5.1/brasp-verification.jar")
    parser.add_argument("--timeout", type=int, default=300)
    parser.add_argument("--maxlen", type=int, default=8)
    args = parser.parse_args(argv)

    tmp = Path(tempfile.mkdtemp(prefix="uhat_cat_"))
    words_for = {}
    cats = {}
    for alphabet in (("a", "b"), ("a", "b", "c")):
        cats[alphabet] = catalogue(alphabet)
        top = args.maxlen if len(alphabet) == 2 else 6
        words_for[alphabet] = [
            tuple(w) for n in range(1, top + 1) for w in itertools.product(alphabet, repeat=n)
        ]
        for name, program in cats[alphabet].items():
            (tmp / f"{len(alphabet)}_{name}.brasp").write_text(brasp.render(program))
    print(f"catalogue: {len(cats[('a','b')])} programs over 2 symbols, "
          f"{len(cats[('a','b','c')])} over 3")

    rows = []
    print(f"\n{'language':<12}{'identified as':<26}{'proof':<10}{'supersets (proved)'}")
    print("-" * 78)
    for path in sorted(args.dir.glob("*.brasp")):
        program = brasp.parse(path.read_text())
        alphabet = program.alphabet
        words = words_for[alphabet]
        cat = cats[alphabet]

        identity, proof, supers = "", "", []
        for name, reference in cat.items():
            if agrees(program, reference, words):
                ref_path = tmp / f"{len(alphabet)}_{name}.brasp"
                if check(args.jar, "eq", ref_path, path, args.timeout):
                    identity, proof = name, "PROVED"
                else:
                    identity, proof = name, "sampled only"
                break
        if not identity:
            for name, reference in cat.items():
                if name in ("universal",) or not contained(program, reference, words):
                    continue
                ref_path = tmp / f"{len(alphabet)}_{name}.brasp"
                if check(args.jar, "sub", ref_path, path, args.timeout):
                    supers.append(name)
                if len(supers) >= 3:
                    break
        rows.append({"language": path.stem, "alphabet": len(alphabet),
                     "identified_as": identity, "proof": proof,
                     "proved_supersets": " ".join(supers)})
        print(f"{path.stem:<12}{identity or '—':<26}{proof:<10}{' '.join(supers)}", flush=True)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]), lineterminator="\n")
        writer.writeheader(); writer.writerows(rows)
    named = sum(1 for r in rows if r["proof"] == "PROVED")
    print(f"\n{named}/{len(rows)} languages proved equal to a catalogue entry; "
          f"{sum(1 for r in rows if r['proved_supersets'])} others bounded by a proved superset")
    print(f"wrote {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
