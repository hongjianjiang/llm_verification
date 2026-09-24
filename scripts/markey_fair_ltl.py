#!/usr/bin/env python3
"""Markey's agreement family: a fair hand-written LTLf baseline vs the direct circuit.

The generic 2LTL -> one-variable LTLf export (`--one-variable --ltlf`) splits
on all 2^(2(n+1)) valuations of the six-per-bit query facts, keeps the
inconsistent ones, leaves `false & ...` constants in place and spells every
bit test as a disjunction of letters. Its size therefore overstates what an
LTL solver must be given. This script builds the smallest direct LTLf
encoding we know and measures the solvers on that instead.

Language (markey_agreement, n inputs): letters are bit vectors (p0, p1..pn).
A word is accepted iff no earlier position -- BOS included, which every bit
test reads as the all-zero letter -- carries the last letter's *twin*, the
same inputs p1..pn with the opposite output p0. Mirrored so that the last
position is position 0 (as the export does), over propositions p0..pn:

    OR_{c != (p0=1, inputs 0)}  ( c at 0  &  N G !twin(c) )

2^(n+1) - 1 disjuncts of size O(n): O(n 2^n), in line with the 2^Omega(n)
lower bound for pure-future formulas (Laroussinie, Markey, Schnoebelen,
LICS 2002). There is no alphabet constraint: every bit vector is a letter.

    python3 scripts/markey_fair_ltl.py check              # equivalence checks
    python3 scripts/markey_fair_ltl.py run --n 1 2 ... 12 # timings -> --out
"""
from __future__ import annotations

import argparse
import itertools
import json
import random
import re
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "scripts"))
JAR = ROOT / "target/scala-3.5.1/brasp-verification.jar"
AALTA = ROOT.parent / "aaltaf/aaltaf"
LISA = Path.home() / "opt/ltlf-solvers/prefix/bin/lisa"


# --- the language -------------------------------------------------------

def letters(n: int) -> list[str]:
    """Letter strings as in examples/ltl: character k is proposition p_k."""
    return ["".join(bits) for bits in itertools.product("01", repeat=n + 1)]


def twin(letter: str) -> str:
    return ("1" if letter[0] == "0" else "0") + letter[1:]


def accepts(word: list[str]) -> bool:
    """Reference semantics, straight from the definition (BOS = all-zero letter)."""
    earlier = set(word[:-1]) | {"0" * len(word[-1])}
    return twin(word[-1]) not in earlier


def compact_ltlf(n: int) -> str:
    def literal(k: int, bit: str) -> str:
        return f"p{k}" if bit == "1" else f"!p{k}"
    def is_letter(c: str) -> str:
        return "(" + " & ".join(literal(k, b) for k, b in enumerate(c)) + ")"
    cases = [f"({is_letter(c)} & N(G(!{is_letter(twin(c))})))"
             for c in letters(n) if twin(c) != "0" * (n + 1)]
    return " | ".join(cases)


def markey_2ltl(n: int) -> str:
    """The same .ltl the benchmark generator writes (examples/ltl for n <= 8)."""
    from ltl2_generator.families.literature import _agree, _bit_alphabet
    from ltl2_generator.ast import Hist, andb, notb, orb
    from ltl2_generator.print import brasp_ltl
    antecedent = andb(*(_agree(k) for k in range(1, n + 1)))
    return brasp_ltl(Hist(orb(notb(antecedent), _agree(0))), list(_bit_alphabet(n + 1)))


# --- a small LTLf evaluator (Aalta syntax) -------------------------------

TOKEN = re.compile(r"\s*(->|<->|[()!&|]|[A-Za-z_][A-Za-z0-9_]*)")


def parse(text: str):
    tokens = TOKEN.findall(text)
    pos = 0
    def peek():
        return tokens[pos] if pos < len(tokens) else None
    def take(expected=None):
        nonlocal pos
        tok = tokens[pos]
        if expected is not None and tok != expected:
            raise ValueError(f"expected {expected}, got {tok}")
        pos += 1
        return tok
    def implication():
        left = disjunction()
        if peek() == "->":
            take(); return ("|", ("!", left), implication())
        if peek() == "<->":
            take(); right = implication(); return ("<->", left, right)
        return left
    def disjunction():
        node = conjunction()
        while peek() == "|":
            take(); node = ("|", node, conjunction())
        return node
    def conjunction():
        node = binary()
        while peek() == "&":
            take(); node = ("&", node, binary())
        return node
    def binary():
        node = unary()
        while peek() in ("U", "R"):
            op = take(); node = (op, node, unary())
        return node
    def unary():
        tok = peek()
        if tok == "!":
            take(); return ("!", unary())
        if tok in ("X", "N", "G", "F"):
            take(); return (tok, unary())
        if tok == "(":
            take(); node = implication(); take(")"); return node
        take()
        return ("const", tok == "true") if tok in ("true", "false") else ("prop", tok)
    node = implication()
    if pos != len(tokens):
        raise ValueError(f"trailing input at token {pos}: {tokens[pos:pos + 5]}")
    return node


def holds(formula, trace: list[set[str]]) -> bool:
    """LTLf truth at position 0 of a finite nonempty trace (sets of true propositions)."""
    length, memo = len(trace), {}
    def vec(node):
        if node in memo:
            return memo[node]
        op = node[0]
        if op == "const": v = [node[1]] * length
        elif op == "prop": v = [node[1] in s for s in trace]
        elif op == "!": v = [not x for x in vec(node[1])]
        elif op == "&": a, b = vec(node[1]), vec(node[2]); v = [x and y for x, y in zip(a, b)]
        elif op == "|": a, b = vec(node[1]), vec(node[2]); v = [x or y for x, y in zip(a, b)]
        elif op == "<->": a, b = vec(node[1]), vec(node[2]); v = [x == y for x, y in zip(a, b)]
        elif op == "X": a = vec(node[1]); v = a[1:] + [False]
        elif op == "N": a = vec(node[1]); v = a[1:] + [True]
        elif op in ("G", "F", "U", "R"):
            a = vec(node[1]); b = vec(node[2]) if op in ("U", "R") else None
            v, acc = [False] * length, (op in ("G", "R"))
            for i in range(length - 1, -1, -1):
                if op == "G": acc = a[i] and acc
                elif op == "F": acc = a[i] or acc
                elif op == "U": acc = b[i] or (a[i] and (acc if i < length - 1 else False))
                else: acc = b[i] and (a[i] or (acc if i < length - 1 else True))
                v[i] = acc
        else: raise ValueError(op)
        memo[node] = v
        return v
    return vec(formula)[0]


def bits_trace(word: list[str]) -> list[set[str]]:
    """Mirrored word over p0..pn: the last letter becomes position 0."""
    return [{f"p{k}" for k, b in enumerate(letter) if b == "1"} for letter in reversed(word)]


def export_trace(word: list[str], alphabet: list[str]) -> list[set[str]]:
    """The export's trace: mirrored word over sym<index>, plus one empty end position."""
    return [{f"sym{alphabet.index(letter)}"} for letter in reversed(word)] + [set()]


def run(cmd, stdin=None, timeout=60.0, cwd=None):
    start = time.monotonic()
    try:
        p = subprocess.run(cmd, input=stdin, capture_output=True, text=True, timeout=timeout, cwd=cwd)
        return p.returncode, time.monotonic() - start, p.stdout + p.stderr
    except subprocess.TimeoutExpired:
        return -999, time.monotonic() - start, ""


# --- check: compact == export == 2LTL on all short words ------------------

def check(args) -> None:
    for n in args.n:
        alphabet = letters(n)
        with tempfile.TemporaryDirectory() as tmp:
            ltl = Path(tmp) / f"markey_{n}.ltl"
            ltl.write_text(markey_2ltl(n))
            assert ltl.read_text() == (ROOT / f"examples/ltl/markey_agreement__n-{n}.ltl").read_text(), \
                "generator disagrees with examples/ltl"
            code, _, out = run(["java", "-Xss512m", "-jar", str(JAR), str(ltl), "--one-variable", "--ltlf"], timeout=300)
            export = parse(out.strip().splitlines()[-1])
            compact = parse(compact_ltlf(n))
            words = [list(w) for length in range(1, args.length + 1)
                     for w in itertools.product(alphabet, repeat=length)]
            bad = [w for w in words if not (accepts(w) == holds(compact, bits_trace(w))
                                            == holds(export, export_trace(w, alphabet)))]
            # Ground truth for `accepts`: the compiler's own evaluator on a sample.
            sample = random.Random(n).sample(words, min(args.jar_sample, len(words)))
            jar_bad = []
            for w in sample:
                _, _, out = run(["java", "-jar", str(JAR), str(ltl), "--word", " ".join(w)])
                if out.strip().endswith("true") != accepts(w):
                    jar_bad.append(w)
        print(f"n={n}: {len(words)} words up to length {args.length}: "
              f"{len(bad)} disagreements (definition / compact LTLf / export); "
              f"{len(jar_bad)} of {len(sample)} disagree with the jar's --word evaluator", flush=True)
        if bad or jar_bad:
            print("  e.g.", (bad or jar_bad)[:3]); sys.exit(1)


# --- run: timings ---------------------------------------------------------

def aalta(formula: str, flags: list[str], timeout: float, n: int) -> dict:
    with tempfile.TemporaryDirectory() as tmp:  # aaltaf leaves cnf.dimacs<pid> files in its cwd
        code, wall, out = run([str(AALTA), *flags, "-e"], formula + "\n", timeout, cwd=tmp)
    if code == -999:
        return {"status": "timeout"}
    if "unsat" in out.split():
        return {"status": "empty", "seconds": round(wall, 3)}
    if "sat" not in out.split():
        return {"status": "error", "detail": out.strip().splitlines()[-2:]}
    trace = [set(re.findall(r"p\d+", line)) for line in out.splitlines() if line.startswith("(")]
    # Aalta prints its model position by position; replay it on the definition.
    word = ["".join("1" if f"p{k}" in s else "0" for k in range(n + 1)) for s in reversed(trace)]
    return {"status": "nonempty", "seconds": round(wall, 3), "witness_ok": bool(word) and accepts(word)}


def lisa(formula: str, timeout: float, flags: list[str] = ()) -> dict:
    spot = re.sub(r"\bX\(", "X[!](", formula)
    spot = re.sub(r"\bN\(", "X(", spot)
    with tempfile.TemporaryDirectory() as tmp:
        (Path(tmp) / "formula").write_text(spot + "\n")
        code, wall, out = run([str(LISA), *flags, "-ltlf", "formula"], timeout=timeout, cwd=tmp)
    if code == -999:
        return {"status": "timeout"}
    low = out.lower()
    if re.search(r"^(emptiness: )?nonempty\s*$", low, re.M):
        return {"status": "nonempty", "seconds": round(wall, 3)}
    if re.search(r"^(emptiness: )?empty\s*$", low, re.M):
        return {"status": "empty", "seconds": round(wall, 3)}
    status = "size_limit" if any(s in low for s in ("bad_alloc", "out of memory")) else "error"
    return {"status": status, "detail": out.strip().splitlines()[-2:]}


def direct(n: int, timeout: float, inputs: Path, heap: str = "16g") -> dict:
    from direct_circuit import solve
    ltl = inputs / f"markey_agreement__n-{n}.ltl"
    ltl.write_text(markey_2ltl(n))
    ns = argparse.Namespace(route="direct-realizable", jar=JAR, abc=ROOT.parent / "abc/abc",
                            timeout=timeout, heap=heap, stack="512m", budget=10**12)
    r = solve(ltl.resolve(), ns)
    out = {"status": r["status"]}
    if r["status"] in ("empty", "nonempty"):
        out["seconds"] = r["wall_seconds"]
    if "aig" in r:
        out["latches"], out["ands"] = r["aig"]["latches"], r["aig"]["ands"]
    return out


def median_of(fn, reps: int) -> dict:
    runs = []
    for _ in range(reps):
        runs.append(fn())
        if "seconds" not in runs[-1]:
            return runs[-1]  # a failed run is final: no point repeating a timeout
    best = dict(runs[0])
    best["seconds"] = sorted(r["seconds"] for r in runs)[reps // 2]
    return best


def run_all(args) -> None:
    args.out.mkdir(parents=True, exist_ok=True)
    inputs = args.out / "inputs"; inputs.mkdir(exist_ok=True)
    failed: set[str] = set()
    records = []
    for n in args.n:
        formula = compact_ltlf(n)
        (inputs / f"markey_{n}_compact.ltlf").write_text(formula + "\n")
        record = {"n": n, "alphabet_size": 2 ** (n + 1), "compact_ltlf_bytes": len(formula)}
        solvers = {
            "aalta_default": lambda: aalta(formula, [], args.timeout, n),
            "aalta_blsc": lambda: aalta(formula, ["-blsc"], args.timeout, n),
            "lisa": lambda: lisa(formula, args.timeout, args.lisa_args.split()),
            "direct_circuit": lambda: direct(n, args.timeout, inputs, args.heap),
        }
        for name, fn in solvers.items():
            if name not in args.solvers:
                continue
            record[name] = {"status": "skipped"} if name in failed else median_of(fn, args.repetitions)
            if record[name]["status"] not in ("empty", "nonempty", "skipped"):
                failed.add(name)
        records.append(record)
        print(json.dumps(record), flush=True)
        (args.out / "records.jsonl").write_text("".join(json.dumps(r) + "\n" for r in records))


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("command", choices=["check", "run"])
    p.add_argument("--n", type=int, nargs="+", default=[1, 2, 3])
    p.add_argument("--length", type=int, default=4, help="check: all words up to this length")
    p.add_argument("--jar-sample", type=int, default=25, help="check: words replayed through the jar")
    p.add_argument("--solvers", nargs="+", default=["aalta_default", "aalta_blsc", "lisa", "direct_circuit"])
    p.add_argument("--timeout", type=float, default=120)
    p.add_argument("--heap", default="16g", help="JVM heap for the direct circuit route")
    p.add_argument("--lisa-args", default="", help="extra Lisa flags, e.g. '-nap 1000' for its Spot route")
    p.add_argument("--repetitions", type=int, default=3)
    p.add_argument("--out", type=Path, default=ROOT / "results/markey_fair_ltl")
    args = p.parse_args()
    check(args) if args.command == "check" else run_all(args)


if __name__ == "__main__":
    main()
