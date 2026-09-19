#!/usr/bin/env python3
"""Emptiness baseline that calls an LTLf satisfiability checker directly.

The DFA baseline in `ltl_scaling_study.py` answers emptiness by building an
automaton and searching it. This route keeps the same front end — the
one-variable elimination every off-the-shelf LTL tool needs — but replaces
the back end with `aaltaf` (Aalta over finite traces), which decides
satisfiability of the formula itself with a SAT solver and never constructs
a DFA.

Two steps per instance: `--one-variable --ltlf` exports flat LTLf text (see
`LtlfExport.scala` for the trace encoding), then `aaltaf` reads it on stdin.
`sat` means the language is nonempty, `unsat` that it is empty; the witness
aaltaf prints is for the reversed word, since the export mirrors the past
formula into its future form.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import statistics
import subprocess
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))

from ltl_scaling_study import run_command


def export_command(jar: Path, input_path: Path, heap: str) -> list[str]:
    return ["java", f"-Xmx{heap}", "-jar", str(jar), str(input_path), "--one-variable", "--ltlf"]


def classify_export(code: int, output: str) -> str | None:
    """A failure status for the export step, or None when it succeeded."""
    lower = output.lower()
    if code == -999:
        return "timeout"
    if code != 0:
        if any(token in lower for token in ("exceed", "too large", "case split", "outofmemory")):
            return "size_limit"
        return "error"
    return None


def classify_solver(code: int, output: str) -> str:
    lower = output.lower()
    if code == -999:
        return "timeout"
    if code != 0:
        return "error"
    if re.search(r"^\s*unsat\s*$", lower, re.MULTILINE):
        return "empty"
    if re.search(r"^\s*sat\s*$", lower, re.MULTILINE):
        return "nonempty"
    return "unknown"


def alphabet_of(input_path: Path) -> list[str]:
    for line in input_path.read_text().splitlines():
        if line.startswith("alphabet "):
            return line[len("alphabet ") :].split()
    raise ValueError(f"{input_path} declares no alphabet")


def proposition_names(alphabet: list[str]) -> list[str]:
    """Mirrors `LtlfExport.propositionNames`."""
    positional = [f"sym{index}" for index in range(len(alphabet))]
    preferred = [
        symbol if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", symbol) else fallback
        for symbol, fallback in zip(alphabet, positional)
    ]
    return preferred if len(set(preferred)) == len(preferred) else positional


def parse_witness(output: str, alphabet: list[str]) -> list[str] | None:
    """Turn aaltaf's `-e` evidence into the accepted source word.

    Each evidence line is one trace position, e.g. `((! b), a, )`. The
    export gives every position exactly one true symbol proposition except
    the final sentinel, which has none. The trace is the reversal of the
    source word, so the word is the positive literals read backwards.
    """
    to_symbol = {name: symbol for symbol, name in zip(alphabet, proposition_names(alphabet))}
    positions: list[str] = []
    for line in output.splitlines():
        line = line.strip()
        if not line.startswith("((") and not re.match(r"^\([a-zA-Z_]", line):
            continue
        positive = [
            token.strip()
            for token in line.strip("()").split(",")
            if token.strip() and not token.strip().startswith("(!")
        ]
        symbols = [to_symbol[token] for token in positive if token in to_symbol]
        if len(symbols) == 1:
            positions.append(symbols[0])
        elif not symbols:
            break  # the symbol-free sentinel ends the word
        else:
            return None
    return list(reversed(positions))


def measure(args: argparse.Namespace, input_path: Path) -> dict:
    record: dict = {
        "input": input_path.name,
        "bytes": input_path.stat().st_size,
        "sha256": hashlib.sha256(input_path.read_bytes()).hexdigest(),
        "timeout_seconds": args.timeout,
    }
    exports: list[float] = []
    solves: list[float] = []
    totals: list[float] = []
    status = "unknown"
    text = ""
    for repetition in range(args.repetitions):
        started = time.monotonic()
        code, export_wall, output = run_command(export_command(args.jar, input_path, args.heap), args.timeout)
        failure = classify_export(code, output)
        if failure is not None:
            record.update(status=failure, stage="export", detail=output.strip().splitlines()[-1:] or [])
            return record
        text = output.strip().splitlines()[-1]
        remaining = args.timeout - (time.monotonic() - started)
        if remaining <= 0:
            record.update(status="timeout", stage="export")
            return record
        solver = [str(args.aaltaf)] + (["-e"] if args.witness else [])
        code, solve_wall, solver_output = run_command_with_input(solver, text + "\n", remaining)
        status = classify_solver(code, solver_output)
        if status in {"timeout", "error", "unknown"}:
            record.update(status=status, stage="solve", export_seconds=export_wall,
                          detail=solver_output.strip().splitlines()[-3:])
            return record
        exports.append(export_wall)
        solves.append(solve_wall)
        totals.append(time.monotonic() - started)
        if args.witness and status == "nonempty" and "witness" not in record:
            witness = parse_witness(solver_output, alphabet_of(input_path))
            record["witness"] = witness
    record.update(
        status=status,
        formula_bytes=len(text),
        export_seconds=round(statistics.median(exports), 3),
        solve_seconds=round(statistics.median(solves), 3),
        wall_seconds=round(statistics.median(totals), 3),
        repetitions=len(totals),
    )
    return record


def run_command_with_input(command: list[str], stdin_text: str, timeout: float) -> tuple[int, float, str]:
    start = time.monotonic()
    try:
        completed = subprocess.run(
            command,
            input=stdin_text,
            capture_output=True,
            text=True,
            timeout=max(0.01, timeout),
        )
        return completed.returncode, time.monotonic() - start, completed.stdout + completed.stderr
    except subprocess.TimeoutExpired:
        return -999, time.monotonic() - start, ""


def verify(args: argparse.Namespace, input_path: Path, record: dict) -> dict:
    """Replay a reported witness through the compiler's own evaluator."""
    if record.get("status") != "nonempty" or not record.get("witness"):
        return record
    word = " ".join(record["witness"])
    # Replay through the Boolean automaton, not plain `--word`: the reference
    # evaluator re-scans the word at every nested past operator, which is
    # exponential on deep formulas and did not finish a 100-letter dot witness.
    code, _, output = run_command(
        ["java", f"-Xmx{args.heap}", "-jar", str(args.jar), str(input_path), "--boolean-automaton", "--word", word],
        args.timeout,
    )
    if code == -999:
        record["witness_accepted"] = None
        record["witness_replay"] = "timeout"
    else:
        record["witness_accepted"] = code == 0 and output.strip().endswith("true")
    return record


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("inputs", type=Path, nargs="+", help=".ltl instances to measure")
    parser.add_argument("--jar", type=Path, default=Path("target/scala-3.5.1/brasp-verification.jar"))
    parser.add_argument("--aaltaf", type=Path, default=Path("../aaltaf/aaltaf"))
    parser.add_argument("--timeout", type=float, default=120)
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--heap", default="4g")
    parser.add_argument("--witness", action="store_true", help="ask aaltaf for evidence and replay it")
    parser.add_argument("--out", type=Path, help="write one JSON record per instance here")
    args = parser.parse_args()
    args.jar = args.jar.resolve()
    args.aaltaf = args.aaltaf.resolve()

    records = []
    for input_path in args.inputs:
        record = measure(args, input_path)
        if args.witness:
            record = verify(args, input_path, record)
        records.append(record)
        print(json.dumps(record), flush=True)
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text("".join(json.dumps(record) + "\n" for record in records))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
