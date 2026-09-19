#!/usr/bin/env python3
"""Emptiness baseline: one-variable elimination, then native DFA search.

The same command as the `dfa` route of `ltl_scaling_study.py`
(`--one-variable --run-native`), as a standalone runner over a list of
instances so it can be rerun with a different time limit.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import statistics
import sys
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
    command = ["java", f"-Xmx{args.heap}", "-jar", str(args.jar), str(input_path),
               "--one-variable", "--run-native", "--native-max-states", str(args.max_dfa_states), "--timing"]
    walls = []
    status = "unknown"
    for _ in range(args.repetitions):
        code, wall, output = run_command(command, args.timeout)
        status = classify(code, output)
        if status not in {"empty", "nonempty"}:
            record.update(status=status, wall_seconds_at_failure=round(wall, 3),
                          detail=output.strip().splitlines()[-1:])
            return record
        walls.append(wall)
    record.update(status=status, wall_seconds=round(statistics.median(walls), 3), repetitions=len(walls))
    return record


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("inputs", type=Path, nargs="+", help=".ltl or .brasp instances to measure")
    parser.add_argument("--jar", type=Path, default=Path("target/scala-3.5.1/brasp-verification.jar"))
    parser.add_argument("--timeout", type=float, default=120)
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--max-dfa-states", type=int, default=50_000_000)
    parser.add_argument("--heap", default="4g")
    parser.add_argument("--out", type=Path, help="write one JSON record per instance here")
    args = parser.parse_args()
    args.jar = args.jar.resolve()

    records = []
    for input_path in args.inputs:
        started = time.monotonic()
        record = measure(args, input_path)
        record["elapsed_seconds"] = round(time.monotonic() - started, 1)
        records.append(record)
        print(json.dumps(record), flush=True)
        if args.out:
            args.out.parent.mkdir(parents=True, exist_ok=True)
            args.out.write_text("".join(json.dumps(r) + "\n" for r in records))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
