#!/usr/bin/env python3
"""Laroussinie-Markey-Schnoebelen (LICS 2002, Thm. 3.1/3.3) as a block language.

Their witness psi'_n = G N psi_n states that *any two* positions agreeing on
p_1..p_n also agree on p_0; every PLTL/LTL equivalent has size Omega(2^n)
(with Omega(2^n) distinct subformulas, so no DAG sharing helps), and every
Buchi automaton needs Omega(2^(2^n)) states.

Here each position's valuation is flattened into a block of n+1 bits. Over
{0,1} alone the block boundaries would need counting modulo n+1, which no
2LTL (star-free) formula can do, so every block starts with a separator:

    words  (# b_0 b_1 ... b_n)^+   over  {b0, b1, sep},  bit k of a block = p_k
    L_n    every two blocks with equal bits b_1..b_n have equal b_0

The strict-past 2LTL formula, evaluated at the last position, is O(n):

    d_0 = sep, d_k = !sep & Y d_{k-1}          (last separator k positions back)
    end = d_{n+1}                               (a complete block ends here)
    bit_k = Y^{n-k} b1                          (b_k of the block ending here)
    good = (sep -> Y bos | Y end) & (!sep -> d_1 | ... | d_{n+1})
    ok   = end -> H !(end@j & AND_{k>=1} bit_k@i <-> bit_k@j & !(bit_0@i <-> bit_0@j))
    L_n  = end & good & H(bos | good) & ok & H(bos | ok)

    python3 scripts/lms_blocks.py write --n 1 2 ... --out DIR   # .ltl inputs
    python3 scripts/lms_blocks.py check --n 1 2 3               # vs the jar's evaluator
"""
from __future__ import annotations

import argparse
import itertools
import random
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "target/scala-3.5.1/brasp-verification.jar"
SEP, ZERO, ONE = "sep", "b0", "b1"


def lms_ltl(n: int) -> str:
    lines = ["logic past-strict", f"alphabet {ZERO} {ONE} {SEP}", "",
             f"sep := sym({SEP})@i", f"one := sym({ONE})@i", "start := bos@i", "d_0 := sep@i"]
    # Every temporal operand is a Boolean combination of named references, as the
    # PVWAA route requires; inline temporal operators are named first.
    for k in range(1, n + 2):
        lines += [f"y_{k} := Y(d_{k - 1}@j)", f"d_{k} := (!(sep@i) & y_{k}@i)"]
    lines += [f"end := d_{n + 1}@i", "one_0 := one@i"]
    lines += [f"one_{m} := Y(one_{m - 1}@j)" for m in range(1, n + 1)]
    lines += [f"bit_{k} := one_{n - k}@i" for k in range(n + 1)]
    lines += ["prev_end := Y(end@j)", "prev_bos := Y(start@j)",
              "inblock := (" + " | ".join(f"d_{k}@i" for k in range(1, n + 2)) + ")",
              "good := ((!(sep@i) | prev_bos@i | prev_end@i) & (sep@i | inblock@i))"]
    agree = lambda k: f"((bit_{k}@i & bit_{k}@j) | (!(bit_{k}@i) & !(bit_{k}@j)))"
    conflict = " & ".join(["end@j"] + [agree(k) for k in range(1, n + 1)] + [f"!{agree(0)}"])
    lines += [f"no_conflict := H(!(({conflict})))",
              "ok := (!(end@i) | no_conflict@i)",
              "all_good := H((start@j | good@j))",
              "all_ok := H((start@j | ok@j))",
              "accept := (end@i & good@i & all_good@i & ok@i & all_ok@i)", "",
              "output := accept@i", "evaluate at i = |w| (the final input position)", ""]
    return "\n".join(lines)


def accepts(word: list[str], n: int) -> bool:
    """Reference semantics: well-formed blocks, and no two blocks conflict."""
    if not word or len(word) % (n + 2):
        return False
    blocks = [word[s:s + n + 2] for s in range(0, len(word), n + 2)]
    if any(b[0] != SEP or SEP in b[1:] for b in blocks):
        return False
    seen = {}
    for b in blocks:
        inputs, output = tuple(b[2:]), b[1]
        if seen.setdefault(inputs, output) != output:
            return False
    return True


def sample_words(n: int, count: int, rng: random.Random) -> list[list[str]]:
    """Mostly well-formed words (accepted and conflicting), plus corrupted ones."""
    words = []
    for _ in range(count):
        blocks = rng.randint(1, 4)
        w = []
        for _ in range(blocks):
            w += [SEP] + [rng.choice([ZERO, ONE]) for _ in range(n + 1)]
        kind = rng.random()
        if kind < 0.2:  # corrupt: flip one position to anything
            p = rng.randrange(len(w)); w[p] = rng.choice([ZERO, ONE, SEP])
        elif kind < 0.3:  # truncate
            w = w[:rng.randrange(1, len(w) + 1)]
        words.append(w)
    return words


def check(args) -> None:
    rng = random.Random(20260922)
    for n in args.n:
        with open(args.tmp / f"lms_{n}.ltl", "w") as handle:
            handle.write(lms_ltl(n))
        ltl = args.tmp / f"lms_{n}.ltl"
        # All words up to length n+3 exhaustively, then a random sample of longer ones.
        words = [list(w) for length in range(1, n + 4) for w in itertools.product([ZERO, ONE, SEP], repeat=length)]
        words = rng.sample(words, min(len(words), args.short)) + sample_words(n, args.long, rng)
        bad, accepted = [], 0
        for w in words:
            out = subprocess.run(["java", "-jar", str(JAR), str(ltl), "--word", " ".join(w)],
                                 capture_output=True, text=True).stdout.strip()
            got = out.endswith("true")
            accepted += got
            if got != accepts(w, n):
                bad.append((w, got))
        print(f"n={n}: {len(words)} words, {accepted} accepted, {len(bad)} disagreements", flush=True)
        if bad:
            print("  e.g.", bad[:3]); raise SystemExit(1)


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("command", choices=["write", "check"])
    p.add_argument("--n", type=int, nargs="+", default=[1, 2, 3])
    p.add_argument("--out", type=Path, default=ROOT / "examples/ltl")
    p.add_argument("--tmp", type=Path, default=Path("/tmp"))
    p.add_argument("--short", type=int, default=60, help="check: sampled short words")
    p.add_argument("--long", type=int, default=60, help="check: sampled block-structured words")
    args = p.parse_args()
    if args.command == "write":
        args.out.mkdir(parents=True, exist_ok=True)
        for n in args.n:
            (args.out / f"lms_blocks__n-{n}.ltl").write_text(lms_ltl(n))
    else:
        args.tmp.mkdir(parents=True, exist_ok=True)
        check(args)


if __name__ == "__main__":
    main()
