#!/usr/bin/env python3
"""Systematic paired scaling study for DFA and PVWAA-to-circuit checking.

The plan uses geometric parameter grids for the eight language families in
the paper.  Each Slurm array cell owns one formula and runs both routes in a
random order.  Successful routes are measured three times; a timeout or a
deterministic construction failure is recorded once and not repeated.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import random
import re
import signal
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


WINDOW_GRID = [2**i for i in range(13)]          # 1 ... 4096
Y_GRID = [2**i for i in range(16)]               # 1 ... 32768
NO2A_GRID = [2**i for i in range(17)]            # 1 ... 65536
ALPHABET_GRID = [2**i for i in range(1, 9)]      # 2 ... 256
NESTING_GRID = [2**i for i in range(12)]         # 1 ... 2048


def run_command(args: list[str], timeout: float) -> tuple[int, float, str]:
    start = time.monotonic()
    process = subprocess.Popen(
        args,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        start_new_session=True,
    )
    try:
        output, _ = process.communicate(timeout=max(0.01, timeout))
        return process.returncode, time.monotonic() - start, output
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        output, _ = process.communicate()
        return -999, time.monotonic() - start, output


def document(alphabet: list[str], definitions: list[str], output: str) -> str:
    return "\n".join(
        [
            "logic past-strict",
            "alphabet " + " ".join(alphabet),
            "",
            *definitions,
            "",
            f"output := {output}",
            "evaluate at i = |w| (the final input position)",
            "",
        ]
    )


def binary_disjunction(terms: list[str]) -> str:
    """Render De Morgan disjunction without artificial parser-depth growth."""
    return "!(" + " & ".join(f"!({term})" for term in terms) + ")"


def dot_text(k: int) -> str:
    alphabet = ["a", "b"]
    definitions = ["f_0 := sym(a)@i"]
    symbol_ids = {"a": 0}
    if k > 1:
        definitions.append("f_1 := sym(b)@i")
        symbol_ids["b"] = 1
    current = 0
    for index in range(1, k):
        once = len(definitions)
        definitions.append(f"f_{once} := P(f_{current}@j)")
        current = len(definitions)
        symbol = alphabet[index % 2]
        definitions.append(f"f_{current} := (f_{symbol_ids[symbol]}@i & f_{once}@i)")
    neg_current = len(definitions)
    definitions.append(f"f_{neg_current} := !(f_{current}@i)")
    once_current = len(definitions)
    definitions.append(f"f_{once_current} := P(f_{current}@j)")
    neg_once = len(definitions)
    definitions.append(f"f_{neg_once} := !(f_{once_current}@i)")
    both_negated = len(definitions)
    definitions.append(f"f_{both_negated} := (f_{neg_current}@i & f_{neg_once}@i)")
    output = len(definitions)
    definitions.append(f"f_{output} := !(f_{both_negated}@i)")
    return document(alphabet, definitions, f"f_{output}@i")


def y_text(k: int, no_two_a: bool = False) -> str:
    definitions = ["f_0 := sym(a)@i"]
    current = 0
    for _ in range(k):
        current = len(definitions)
        definitions.append(f"f_{current} := Y(f_{current - 1}@j)")
    if no_two_a:
        conjunction = len(definitions)
        definitions.append(f"f_{conjunction} := (f_0@i & f_{current}@i)")
        negated = len(definitions)
        definitions.append(f"f_{negated} := !(f_{conjunction}@i)")
        current = len(definitions)
        definitions.append(f"f_{current} := H(f_{negated}@j)")
    return document(["a", "b"], definitions, f"f_{current}@i")


def alphabet_text(family: str, sigma: int) -> str:
    letters = [f"s{index}" for index in range(sigma)]
    definitions = [f"f_{index} := sym({letter})@i" for index, letter in enumerate(letters)]
    same_terms = [f"(f_{index}@i & f_{index}@j)" for index in range(sigma)]
    same = binary_disjunction(same_terms)
    output = len(definitions)
    if family == "slb":
        definitions.append(f"f_{output} := P({same})")
        alphabet = letters
    elif family == "mono":
        smaller_terms = [
            f"(f_{right}@i & f_{left}@j)"
            for right in range(sigma)
            for left in range(right)
        ]
        definitions.append(f"f_{output} := H({binary_disjunction(smaller_terms)})")
        alphabet = letters
    elif family == "since":
        marker = len(definitions)
        definitions.append(f"f_{marker} := sym(marker)@i")
        output = len(definitions)
        definitions.append(f"f_{output} := ({same}) S (f_{marker}@j)")
        alphabet = ["marker", *letters]
    else:
        raise ValueError(family)
    return document(alphabet, definitions, f"f_{output}@i")


def ltl_formulas() -> list[tuple[str, str, int, str, str]]:
    rows: list[tuple[str, str, int, str, str]] = []
    rows.extend(("dot", "window depth", k, dot_text(k), "nonempty") for k in WINDOW_GRID)
    rows.extend(("Y", "window depth", k, y_text(k), "nonempty") for k in Y_GRID)
    rows.extend(("no2a", "window depth", k, y_text(k, no_two_a=True), "nonempty") for k in NO2A_GRID)
    for sigma in ALPHABET_GRID:
        rows.append(("slb", "alphabet size", sigma, alphabet_text("slb", sigma), "nonempty"))
        rows.append(("mono", "alphabet size", sigma, alphabet_text("mono", sigma), "empty"))
        rows.append(("since", "alphabet size", sigma, alphabet_text("since", sigma), "nonempty"))
    return rows


def plan(out: Path) -> int:
    from brasp_families import marked_same_program, marks_program

    inputs = out / "inputs"
    records = out / "records"
    logs = out / "logs"
    slurm = out / "slurm"
    for directory in (inputs, records, logs, slurm):
        directory.mkdir(parents=True, exist_ok=True)

    manifest = []
    for family, axis, parameter, text, expected in ltl_formulas():
        suffix = "sigma" if axis == "alphabet size" else "k"
        path = inputs / f"{family}__{suffix}-{parameter}.ltl"
        path.write_text(text)
        manifest.append(
            {
                "family": family,
                "axis": axis,
                "parameter": parameter,
                "input": path.name,
                "format": "ltl",
                "expected": expected,
            }
        )

    for family, generator in (("marks", marks_program), ("same", lambda k: marked_same_program(k, 2))):
        for parameter in NESTING_GRID:
            path = inputs / f"{family}__k-{parameter}.brasp"
            path.write_text(generator(parameter))
            manifest.append(
                {
                    "family": family,
                    "axis": "nesting depth",
                    "parameter": parameter,
                    "input": path.name,
                    "format": "brasp",
                    "expected": "nonempty",
                }
            )

    manifest.sort(key=lambda row: (row["family"], row["parameter"]))
    for cell, row in enumerate(manifest):
        path = inputs / row["input"]
        row.update(
            cell=cell,
            bytes=path.stat().st_size,
            sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
        )
    (out / "manifest.jsonl").write_text("".join(json.dumps(row) + "\n" for row in manifest))
    (out / "PLAN.md").write_text(
        "# Systematic LTL scaling study\n\n"
        f"The manifest contains {len(manifest)} instances from eight language families. "
        "Window, alphabet, and nesting parameters use powers of two. Each successful "
        "route is measured three times; terminal failures are measured once.\n"
    )
    print(f"planned {len(manifest)} instances in {out}")
    return len(manifest)


def classify(code: int, output: str) -> str:
    lower = output.lower()
    if code == -999:
        return "timeout"
    if code != 0:
        if any(token in lower for token in ("exceed", "too large", "case split", "outofmemory")):
            return "size_limit"
        return "error"
    if "was asserted" in lower or "counter-example" in lower or "not proved" in lower:
        return "nonempty"
    if (
        "property proved" in lower
        or "brasp-native: proved" in lower
        or re.search(r"\bstatus\s*=\s*1\b", lower)
    ):
        return "empty"
    if "unknown" in lower:
        return "unknown"
    return "unknown"


def run_cell(args: argparse.Namespace) -> int:
    rows = [json.loads(line) for line in (args.out / "manifest.jsonl").read_text().splitlines()]
    cell = rows[args.cell]
    input_path = (args.out / "inputs" / cell["input"]).resolve()
    jar = args.jar.resolve()
    abc = args.abc.resolve()
    records_dir = args.out / "records"
    logs_dir = args.out / "logs"
    records_dir.mkdir(parents=True, exist_ok=True)
    logs_dir.mkdir(parents=True, exist_ok=True)

    measurements = []
    terminal_routes: set[str] = set()
    rng = random.Random(20260912 + args.cell)
    for repetition in range(args.repetitions):
        routes = ["dfa", "circuit"]
        rng.shuffle(routes)
        for route in routes:
            if route in terminal_routes:
                continue
            record = {
                **cell,
                "route": route,
                "repetition": repetition,
                "timeout_seconds": args.timeout,
                "host": os.uname().nodename,
                "jar_sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
            }
            started = time.monotonic()
            with tempfile.TemporaryDirectory(prefix="ltl-scaling-") as temporary:
                aig = str(Path(temporary) / "model.aig")
                if route == "dfa":
                    command = [
                        "java",
                        f"-Xmx{args.heap}",
                        "-jar",
                        str(jar),
                        str(input_path),
                        "--one-variable",
                        "--run-native",
                        "--native-max-states",
                        str(args.max_dfa_states),
                        "--timing",
                    ]
                else:
                    command = [
                        "java",
                        f"-Xmx{args.heap}",
                        "-cp",
                        str(jar),
                        "brasp.CircuitStudy",
                        str(input_path),
                        "realizable",
                        aig,
                    ]
                code, compile_wall, output = run_command(command, args.timeout)
                record["compile_wall_seconds"] = compile_wall
                record["command"] = command
                if code == 0 and route == "circuit":
                    remaining = args.timeout - (time.monotonic() - started)
                    solver_command = [
                        str(abc),
                        "-c",
                        f"read_aiger {aig}; print_stats; scleanup; dc2; print_stats; pdr; print_status",
                    ]
                    code, solve_wall, solver_output = run_command(solver_command, remaining)
                    record["solver_wall_seconds"] = solve_wall
                    record["solver_command"] = solver_command
                    output += "\n" + solver_output

                status = classify(code, output)
                record.update(
                    status=status,
                    wall_seconds=time.monotonic() - started,
                    matches_expected=(status == cell["expected"]) if status in {"empty", "nonempty"} else None,
                    structural_metrics={
                        key: value
                        for key, value in re.findall(
                            r"(states|goto|max_support|full_cells|support_cells|realizable_cells|compile_seconds|encode_seconds)=([^\s]+)",
                            output,
                        )
                    },
                )
                log_path = logs_dir / f"{args.cell:03d}_{repetition}_{route}.txt"
                log_path.write_text(output)
                record["log"] = log_path.name
                measurements.append(record)
                (records_dir / f"{args.cell:03d}.json").write_text(json.dumps(measurements, indent=2) + "\n")
                print(args.cell, cell["family"], cell["parameter"], repetition, route, status, f"{record['wall_seconds']:.3f}s", flush=True)
                if status in {"timeout", "size_limit", "error"}:
                    terminal_routes.add(route)

    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    subparsers = parser.add_subparsers(dest="command", required=True)
    planner = subparsers.add_parser("plan")
    planner.add_argument("--out", type=Path, required=True)

    runner = subparsers.add_parser("run")
    runner.add_argument("--out", type=Path, required=True)
    runner.add_argument("--cell", type=int, required=True)
    runner.add_argument("--jar", type=Path, required=True)
    runner.add_argument("--abc", type=Path, required=True)
    runner.add_argument("--timeout", type=int, default=120)
    runner.add_argument("--repetitions", type=int, default=3)
    runner.add_argument("--max-dfa-states", type=int, default=50_000_000)
    runner.add_argument("--heap", default="6g")
    args = parser.parse_args()
    if args.command == "plan":
        plan(args.out)
        return 0
    return run_cell(args)


if __name__ == "__main__":
    raise SystemExit(main())
