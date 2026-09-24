#!/usr/bin/env python3
"""Emptiness baseline: one-variable LTL checked as a sequential circuit.

The same backend as the PVWAA-to-circuit route (`CircuitStudy` → AIGER →
ABC `pdr`), but with the two-variable formula first reduced to one-variable
LTL by `TwoLtlToOneVariable`. The resulting PVWAA has no goto atoms, so the
circuit is the classical one-latch-per-state encoding of an LTL formula's
very weak alternating automaton. Comparing the two isolates what the pebble
buys: everything downstream of the formula is identical.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import statistics
import sys
import tempfile
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))

from ltl_scaling_study import classify, run_command


def measure(args: argparse.Namespace, input_path: Path) -> dict:
    record: dict = {
        "input": input_path.name,
        "bytes": input_path.stat().st_size,
        "sha256": hashlib.sha256(input_path.read_bytes()).hexdigest(),
        "timeout_seconds": args.timeout,
    }
    compiles, solves, totals = [], [], []
    status = "unknown"
    for _ in range(args.repetitions):
        started = time.monotonic()
        with tempfile.TemporaryDirectory(prefix="circuit-1var-") as temporary:
            aig = str(Path(temporary) / "model.aig")
            command = ["java", f"-Xmx{args.heap}", "-cp", str(args.jar), "brasp.CircuitStudy",
                       str(input_path), "one-variable", aig]
            code, compile_wall, output = run_command(command, args.timeout)
            if code != 0:
                lower = output.lower()
                status = classify(code, output)
                if status == "error" and "cap of" in lower:
                    status = "size_limit"
                record.update(status=status, stage="compile", detail=output.strip().splitlines()[-1:])
                return record
            metrics = {key: int(value) for key, value in re.findall(r"\b(states|goto)=(\d+)", output)}
            remaining = args.timeout - (time.monotonic() - started)
            solver = [str(args.abc), "-c", f"read_aiger {aig}; print_stats; scleanup; dc2; print_stats; pdr; print_status"]
            code, solve_wall, solver_output = run_command(solver, remaining)
            status = classify(code, output + "\n" + solver_output)
            if status not in {"empty", "nonempty"}:
                record.update(status=status, stage="solve", compile_seconds=compile_wall, **metrics)
                return record
        compiles.append(compile_wall)
        solves.append(solve_wall)
        totals.append(time.monotonic() - started)
    record.update(
        status=status,
        compile_seconds=round(statistics.median(compiles), 3),
        solve_seconds=round(statistics.median(solves), 3),
        wall_seconds=round(statistics.median(totals), 3),
        repetitions=len(totals),
        **metrics,
    )
    return record


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("inputs", type=Path, nargs="*", help=".ltl or .brasp instances to measure")
    parser.add_argument("--manifest", type=Path, help="TSV: task, input name, path")
    parser.add_argument("--jar", type=Path, default=Path("target/scala-3.5.1/brasp-verification.jar"))
    parser.add_argument("--abc", type=Path, default=Path("../abc/abc"))
    parser.add_argument("--timeout", type=float, default=120)
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--heap", default="4g")
    parser.add_argument("--out", type=Path, help="write one JSON record per instance here")
    parser.add_argument("--reference", type=Path, help="JSONL expected classifications")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--skip-after-failure", action="store_true",
                        help="manifest only: skip larger family cases after timeout/size limit")
    args = parser.parse_args()
    args.jar = args.jar.resolve()
    args.abc = args.abc.resolve()
    if not args.jar.is_file() or not args.abc.is_file():
        parser.error("jar and ABC executable must exist")
    entries = [(path.stem, path) for path in args.inputs]
    if args.manifest:
        entries += [(row[0], Path(row[2])) for line in args.manifest.read_text().splitlines()
                    if line.strip() for row in [line.split("\t")]]
    if not entries:
        parser.error("provide inputs or --manifest")
    reference = {}
    if args.reference:
        reference = {row["input"]: row["status"] for line in args.reference.read_text().splitlines()
                     if line.strip() for row in [json.loads(line)]}
    previous = {}
    if args.resume and args.out and args.out.exists():
        previous = {row["input"]: row for line in args.out.read_text().splitlines()
                    if line.strip() for row in [json.loads(line)]}

    records, failed = [], {}
    for task, input_path in entries:
        family = task.split("__")[0]
        digest = hashlib.sha256(input_path.read_bytes()).hexdigest()
        if input_path.name in previous:
            record = previous[input_path.name]
            if record.get("sha256") != digest or record.get("timeout_seconds") != args.timeout:
                parser.error(f"resume configuration/input mismatch for {input_path}")
        elif args.skip_after_failure and args.manifest and family in failed:
            record = dict(input=input_path.name, sha256=digest, timeout_seconds=args.timeout,
                          status="skipped", reason=failed[family])
        else:
            record = measure(args, input_path)
        record["task"] = task
        record["solver"] = "abc-ltl-circuit"
        if record["status"] in {"timeout", "size_limit"}:
            failed[family] = input_path.name + ": " + record["status"]
        if input_path.name in reference and record["status"] in {"empty", "nonempty"}:
            record["matches_reference"] = record["status"] == reference[input_path.name]
        records.append(record)
        print(json.dumps(record), flush=True)
        if args.out:
            args.out.parent.mkdir(parents=True, exist_ok=True)
            checkpoint = args.out.with_suffix(args.out.suffix + ".tmp")
            checkpoint.write_text("".join(json.dumps(r) + "\n" for r in records))
            checkpoint.replace(args.out)
        if record.get("matches_reference") is False:
            print(f"reference mismatch on {input_path}; stopping", file=sys.stderr)
            return 1
    return int(any(r.get("matches_reference") is False or r["status"] in {"error", "unknown"}
                   for r in records))


if __name__ == "__main__":
    raise SystemExit(main())
