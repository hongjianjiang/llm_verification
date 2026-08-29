#!/usr/bin/env python3
"""Prove each learned program equivalent to the specification it was trained on.

This is the step the whole pipeline exists for. Training reports accuracy on
held-out words; this reports a proof. For every benchmark language that the
sweep solved, take the smallest architecture that reached 1.0 and ask the
model checker whether the extracted program and the original specification
accept the same language.

    scripts/uhat_verify.py --csv results/uhat_sweep.csv
"""

from __future__ import annotations

import argparse
import csv
import subprocess
import sys
from pathlib import Path


def stem_of(row: dict) -> str:
    return f"{row['task']}__l{row['layers']}_h{row['heads']}_t{row['terms']}"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--csv", type=Path, default=Path("results/uhat_sweep.csv"))
    parser.add_argument("--specs", type=Path, default=Path("examples/brasp"))
    parser.add_argument("--learned", type=Path, default=Path("results/uhat_sweep/programs"))
    parser.add_argument("--jar", default="target/scala-3.5.1/brasp-verification.jar")
    parser.add_argument("--timeout", type=int, default=300)
    args = parser.parse_args()

    rows = list(csv.DictReader(args.csv.open()))

    best: dict[str, dict] = {}
    for row in rows:
        # Both, not just test: a program can score 1.0 on the long-word test
        # set while still missing short words it was trained on, and picking
        # such a row makes the checker report a difference that better model
        # selection would have avoided.
        if float(row["test_accuracy"]) != 1.0 or float(row["train_accuracy"]) != 1.0:
            continue
        spec = args.specs / f"{row['task']}.brasp"
        if not spec.exists():
            continue  # a built-in task, with no specification file to check against
        key = row["task"]
        # `leftmost` heads used to be steered around here, because the ABC
        # route could not translate them; `BraspNormalize` normalises them to
        # rightmost attention now, so the smallest program wins outright.
        rank = (int(row["attention_ops"]), int(row["layers"]), stem_of(row))
        if key not in best or rank < best[key][0]:
            best[key] = (rank, row)
    best = {name: row for name, (_, row) in best.items()}

    if not best:
        print("no solved rows with a specification file to check against", file=sys.stderr)
        return 1

    proved = differ = errored = 0
    for name, row in sorted(best.items()):
        spec = args.specs / f"{name}.brasp"
        learned = args.learned / f"{stem_of(row)}.brasp"
        if not learned.exists():
            print(f"{name:<40} MISSING {learned}")
            errored += 1
            continue
        try:
            result = subprocess.run(
                ["java", "-jar", args.jar, "--equivalent", str(spec), str(learned), "--run-abc"],
                capture_output=True, text=True, timeout=args.timeout,
            )
        except subprocess.TimeoutExpired:
            print(f"{name:<40} TIMEOUT after {args.timeout}s")
            errored += 1
            continue
        output = (result.stdout + result.stderr).strip()
        if "NOT PROVED" in output:
            differ += 1
            mark = "DIFFERENT"
        elif "PROVED" in output:
            proved += 1
            mark = "PROVED"
        else:
            errored += 1
            mark = "ERROR"
        print(f"{name:<40} {mark:<12} [{row['attention_ops']} ops, l{row['layers']} h{row['heads']} t{row['terms']}]")

    print(f"\n{proved} proved equivalent, {differ} provably different, {errored} errored")
    return 0 if differ == 0 and errored == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
