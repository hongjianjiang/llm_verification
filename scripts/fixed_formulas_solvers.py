#!/usr/bin/env python3
"""Run Aalta, Lisa and BLACK on the generating formulas of LTL-learning benchmarks.

Each benchmark directory (`Fixed_Formulas/`, `DoubleCounter/`) holds one JSON
file per (formula, sample size, trace length); the files of one formula differ
only in their sample traces. So each distinct generating formula is decided
once for LTLf satisfiability, together with its negation (validity).
Verdicts are cross-checked against brute-force enumeration of all traces up
to `--brute-length` (a "nonempty" there is a certificate; "empty" only means
no model that short). Each generating formula is also evaluated on its
files' sample traces, as a check that the benchmark labels are consistent.

The formulas are in Spot syntax: `X[!]` is strong next, plain `X` weak next.
They are parsed once and printed per solver (Aalta: `X` strong, `N` weak,
`<->` expanded; Lisa: Spot spelling; BLACK: `X` strong, `wX` weak,
`True`/`False`), so the next-operator semantics cannot
drift between solvers. The evaluator used for the cross-checks reads the same
tree. BLACK's model (`-m`) is evaluated on the formula, so its "nonempty"
verdicts carry a checked witness. Solver invocation and output classification come from
`ltlf_solver_baseline.py`.
"""

from __future__ import annotations

import argparse
import itertools
import json
import re
from pathlib import Path
import statistics
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parent))

from ltlf_solver_baseline import DEFAULT_BIN, classify_solver, run_isolated


# --- a tiny LTLf parser/evaluator for the brute-force cross-check ------------

def tokenize(text: str) -> list[str]:
    tokens, i = [], 0
    while i < len(text):
        if text[i].isspace():
            i += 1
        elif text.startswith("X[!]", i):
            tokens.append("X"); i += 4  # strong next
        elif text.startswith("<->", i):
            tokens.append("<->"); i += 3
        elif text.startswith("->", i):
            tokens.append("->"); i += 2
        elif text.startswith("&&", i) or text.startswith("||", i):
            tokens.append(text[i]); i += 2
        elif text[i] in "()!&|":
            tokens.append(text[i]); i += 1
        else:
            j = i
            while j < len(text) and (text[j].isalnum() or text[j] == "_"):
                j += 1
            if j == i:
                raise ValueError(f"unexpected {text[i]!r} in {text!r}")
            word = text[i:j]
            tokens.append("N" if word == "X" else word)  # Spot's plain X is weak next
            i = j
    return tokens


def parse(text: str):
    tokens = tokenize(text)
    pos = 0

    def peek():
        return tokens[pos] if pos < len(tokens) else None

    def take(expected=None):
        nonlocal pos
        token = tokens[pos]
        if expected is not None and token != expected:
            raise ValueError(f"expected {expected!r}, got {token!r}")
        pos += 1
        return token

    def implication():
        left = disjunction()
        if peek() in {"->", "<->"}:
            op = take(); return (op, left, implication())
        return left

    def disjunction():
        left = conjunction()
        while peek() == "|":
            take(); left = ("|", left, conjunction())
        return left

    def conjunction():
        left = until()
        while peek() == "&":
            take(); left = ("&", left, until())
        return left

    def until():
        left = unary()
        if peek() in {"U", "R"}:
            op = take(); return (op, left, until())
        return left

    def unary():
        token = peek()
        if token in {"!", "F", "G", "X", "N"}:
            take(); return (token, unary())
        if token == "(":
            take(); inner = implication(); take(")"); return inner
        take(); return ("ap", token)

    tree = implication()
    if pos != len(tokens):
        raise ValueError(f"trailing tokens in {text!r}")
    return tree


def holds(tree, trace: list[frozenset], i: int) -> bool:
    op = tree[0]
    n = len(trace)
    if op == "ap":
        return tree[1] == "true" or (tree[1] != "false" and tree[1] in trace[i])
    if op == "!":
        return not holds(tree[1], trace, i)
    if op == "&":
        return holds(tree[1], trace, i) and holds(tree[2], trace, i)
    if op == "|":
        return holds(tree[1], trace, i) or holds(tree[2], trace, i)
    if op == "->":
        return not holds(tree[1], trace, i) or holds(tree[2], trace, i)
    if op == "<->":
        return holds(tree[1], trace, i) == holds(tree[2], trace, i)
    if op == "X":
        return i + 1 < n and holds(tree[1], trace, i + 1)
    if op == "N":
        return i + 1 >= n or holds(tree[1], trace, i + 1)
    if op == "F":
        return any(holds(tree[1], trace, k) for k in range(i, n))
    if op == "G":
        return all(holds(tree[1], trace, k) for k in range(i, n))
    if op == "U":
        return any(holds(tree[2], trace, k) and all(holds(tree[1], trace, m) for m in range(i, k))
                   for k in range(i, n))
    if op == "R":
        return all(holds(tree[2], trace, k) or any(holds(tree[1], trace, m) for m in range(i, k))
                   for k in range(i, n))
    raise ValueError(op)


def brute_force(tree, props: list[str], max_length: int, max_traces: int) -> str:
    """"nonempty" if some trace of length <= max_length is a model, else "none<=L" for the
    longest length L fully enumerated within max_traces traces."""
    letters = [frozenset(p for p, bit in zip(props, bits) if bit)
               for bits in itertools.product([0, 1], repeat=len(props))]
    checked = 0
    for length in range(1, max_length + 1):
        if checked + len(letters) ** length > max_traces:
            return f"none<={length - 1}"
        for trace in itertools.product(letters, repeat=length):
            if holds(tree, list(trace), 0):
                return "nonempty"
        checked += len(letters) ** length
    return f"none<={max_length}"


def sample_consistency(tree, record: dict) -> tuple[int, int]:
    """How many positive traces satisfy and negative traces violate the formula."""

    def word(sample: dict) -> list[frozenset]:
        length = len(next(iter(sample.values())))
        return [frozenset(p for p, bits in sample.items() if bits[k]) for k in range(length)]

    pos = sum(holds(tree, word(t), 0) for t in record["positive_traces"])
    neg = sum(not holds(tree, word(t), 0) for t in record["negative_traces"])
    return pos, neg


# --- solver runs --------------------------------------------------------------

def render(tree, solver: str) -> str:
    """Print a parse tree in `solver`'s syntax, fully parenthesized."""
    op = tree[0]
    if op == "ap":
        if solver == "black" and tree[1] in {"true", "false"}:
            return tree[1].capitalize()
        return tree[1]
    if len(tree) == 2:
        name = {"lisa": {"X": "X[!]", "N": "X"}, "black": {"N": "wX"}}.get(solver, {}).get(op, op)
        return f"{name}({render(tree[1], solver)})"
    left, right = render(tree[1], solver), render(tree[2], solver)
    if op == "<->" and solver == "aalta":
        return f"(({left} -> {right}) & ({right} -> {left}))"
    return f"({left} {op} {right})"


def black_model(output: str) -> list[frozenset]:
    """BLACK's `-m` finite model (`- t = 0: {a, ￢b}`); unlisted propositions are false."""
    trace = []
    for match in re.finditer(r"^- t\s*=\s*\d+: \{(.*)\}\s*$", output, re.MULTILINE):
        literals = [token.strip() for token in match.group(1).split(",") if token.strip()]
        trace.append(frozenset(t for t in literals if not t.startswith(("￢", "!"))))
    return trace


def solve(solver: str, tree, args: argparse.Namespace) -> dict:
    text = render(tree, solver)
    if solver == "aalta":
        command, on_stdin = [str(args.aaltaf)], True
    elif solver == "black":
        command, on_stdin = [str(args.bin / "black"), "solve", "--finite", "-m", "-"], True
    else:
        command, on_stdin = [str(args.bin / "lisa"), "-ltlf", "formula"], False
    statuses, times = [], []
    for _ in range(args.repetitions):
        with tempfile.TemporaryDirectory() as tmp:
            workdir = Path(tmp)
            (workdir / "formula").write_text(text + "\n")
            code, wall, output = run_isolated(command, text + "\n" if on_stdin else None, args.timeout, workdir)
        statuses.append(classify_solver(solver, code, output))
        times.append(wall)
        if statuses[-1] not in {"empty", "nonempty"}:
            break
    status = statuses[0] if len(set(statuses)) == 1 else "inconsistent"
    witness_ok = None
    if solver == "black" and status == "nonempty":
        model = black_model(output)
        witness_ok = bool(model) and holds(tree, model, 0)
    return {"solver_input": text, "status": status, "witness_ok": witness_ok,
            "wall_seconds": statistics.median(times), "wall_all": times,
            "last_output": output.strip().splitlines()[-3:] if status not in {"empty", "nonempty"} else None}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--formulas", type=Path, nargs="+", default=[Path("Fixed_Formulas")],
                        help="benchmark directories")
    parser.add_argument("--solvers", nargs="+", default=["aalta", "lisa", "black"],
                        choices=["aalta", "lisa", "black"])
    parser.add_argument("--bin", type=Path, default=DEFAULT_BIN)
    parser.add_argument("--aaltaf", type=Path, default=Path("../aaltaf/aaltaf"))
    parser.add_argument("--timeout", type=float, default=60)
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--brute-length", type=int, default=8)
    parser.add_argument("--brute-traces", type=int, default=2_000_000, help="enumeration budget per query")
    parser.add_argument("--out", type=Path, required=True, help="output directory")
    args = parser.parse_args()
    args.aaltaf = args.aaltaf.resolve()  # solvers run in a scratch directory
    args.out.mkdir(parents=True, exist_ok=True)

    # Group the benchmark files by generating formula.
    groups: dict[str, dict] = {}
    for path in sorted(p for d in args.formulas for p in d.glob("*.json")):
        if path.name == "formulas.json":
            continue
        record = json.loads(path.read_text())
        family = f"{path.parent.name}/{Path(record['name']).stem}"
        group = groups.setdefault(family, {"formula": record["generating_formula"],
                                           "props": record["atomic_propositions"], "files": []})
        assert group["formula"] == record["generating_formula"], path
        pos, neg = sample_consistency(parse(record["generating_formula"]), record)
        group["files"].append({"file": path.name, "positive_ok": f"{pos}/{len(record['positive_traces'])}",
                               "negative_ok": f"{neg}/{len(record['negative_traces'])}"})

    records = []
    for family, group in groups.items():
        for query, formula in (("sat", group["formula"]), ("neg", f"!({group['formula']})")):
            tree = parse(formula)
            reference = brute_force(tree, group["props"], args.brute_length, args.brute_traces)
            record = {"family": family, "query": query, "formula": formula, "brute_force": reference,
                      "files": len(group["files"])}
            for solver in args.solvers:
                result = solve(solver, tree, args)
                record[solver] = result
                agrees = result["status"] == reference or (
                    reference.startswith("none") and result["status"] in {"empty", "nonempty"})
                witness = {None: "", True: "  witness ok", False: "  WITNESS FAILS"}[result["witness_ok"]]
                print(f"{family:30s} {query:3s} {solver:5s} {result['status']:9s} "
                      f"{result['wall_seconds']:7.3f}s  brute={reference:9s} {'ok' if agrees else 'MISMATCH'}{witness}",
                      flush=True)
            records.append(record)

    (args.out / "records.jsonl").write_text("".join(json.dumps(r) + "\n" for r in records))
    (args.out / "sample_consistency.json").write_text(json.dumps(groups, indent=1) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
