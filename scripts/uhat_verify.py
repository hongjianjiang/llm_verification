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
import time
from pathlib import Path


def stem_of(row: dict, flat: bool = False) -> str:
    if flat:
        return row["task"]
    return f"{row['task']}__l{row['layers']}_h{row['heads']}_t{row['terms']}"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--csv", type=Path, default=Path("results/uhat_sweep.csv"))
    parser.add_argument("--specs", type=Path, default=Path("examples/brasp"))
    parser.add_argument("--learned", type=Path, default=Path("results/uhat_sweep/programs"))
    parser.add_argument("--jar", default="target/scala-3.5.1/brasp-verification.jar")
    parser.add_argument("--timeout", type=int, default=300)
    parser.add_argument(
        "--out",
        type=Path,
        help="write one row per language: verdict and seconds, so the cost of "
        "an equivalence proof can be reported alongside training cost",
    )
    parser.add_argument(
        "--flat",
        action="store_true",
        help="learned programs are named <task>.brasp, as scripts/uhat_retrain.sh "
        "writes them, rather than <task>__l<L>_h<H>_t<T>.brasp",
    )
    args = parser.parse_args()

    rows = list(csv.DictReader(args.csv.open()))

    # Two shapes of CSV are accepted: a full sweep (many rows per language,
    # with accuracies to select on) and a picks file from
    # `uhat_sweep.py best --out`, which has one already-chosen row per
    # language and no accuracy columns.
    preselected = bool(rows) and "test_accuracy" not in rows[0]

    best: dict[str, dict] = {}
    for row in rows:
        # Both, not just test: a program can score 1.0 on the long-word test
        # set while still missing short words it was trained on, and picking
        # such a row makes the checker report a difference that better model
        # selection would have avoided.
        if not preselected and (
            float(row["test_accuracy"]) != 1.0 or float(row["train_accuracy"]) != 1.0
        ):
            continue
        spec = args.specs / f"{row['task']}.brasp"
        if not spec.exists():
            continue  # a built-in task, with no specification file to check against
        key = row["task"]
        # `leftmost` heads used to be steered around here, because the ABC
        # route could not translate them; `BraspNormalize` normalises them to
        # rightmost attention now, so the smallest program wins outright.
        if preselected:
            best[key] = ((), row)
            continue
        rank = (int(row["attention_ops"]), int(row["layers"]), stem_of(row))
        if key not in best or rank < best[key][0]:
            best[key] = (rank, row)
    best = {name: row for name, (_, row) in best.items()}

    if not best:
        print("no solved rows with a specification file to check against", file=sys.stderr)
        return 1

    proved = differ = errored = 0
    records: list[dict] = []
    for name, row in sorted(best.items()):
        spec = args.specs / f"{name}.brasp"
        learned = args.learned / f"{stem_of(row, args.flat)}.brasp"
        if not learned.exists():
            print(f"{name:<40} MISSING {learned}")
            errored += 1
            continue
        started = time.perf_counter()
        try:
            result = subprocess.run(
                ["java", "-jar", args.jar, "--equivalent", str(spec), str(learned), "--run-abc"],
                capture_output=True, text=True, timeout=args.timeout,
            )
        except subprocess.TimeoutExpired:
            print(f"{name:<40} TIMEOUT after {args.timeout}s")
            errored += 1
            continue
        elapsed = time.perf_counter() - started
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
        records.append({
            "task": name, "layers": row["layers"], "heads": row["heads"],
            "terms": row["terms"], "attention_ops": row["attention_ops"],
            "verdict": mark, "equivalence_seconds": round(elapsed, 2),
        })
        print(f"{name:<40} {mark:<12} [{row['attention_ops']} ops, l{row['layers']} h{row['heads']} t{row['terms']}]"
              f" {elapsed:6.2f}s")

    if args.out and records:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        with args.out.open("w", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=list(records[0]), lineterminator="\n")
            writer.writeheader()
            writer.writerows(records)
        print(f"wrote {args.out}")

    print(f"\n{proved} proved equivalent, {differ} provably different, {errored} errored")
    return 0 if differ == 0 and errored == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
