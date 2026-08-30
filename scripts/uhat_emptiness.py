#!/usr/bin/env python3
"""Model-check each learned program for emptiness, with timings.

For every language in a picks CSV, this reports the four numbers the
experiment asks for: how long training took, what accuracy it reached, whether
the extracted program's language is empty, and how long that proof took.

Emptiness is asked of the *learned* program, via the same ABC route the rest
of the pipeline uses. A verdict of PROVED means no non-empty word is accepted
-- the language is empty. NOT PROVED means a witness exists, so the language
is non-empty. The distinction matters because an empty language is exactly
what a collapsed model produces: a program that rejects everything scores well
on a skewed test set and is worthless, and emptiness is the cheapest way to
catch that without looking at a specification.

    scripts/uhat_emptiness.py --csv results/uhat_all_best.csv \
        --learned results/uhat_experiment/programs --json-dir results/uhat_experiment
"""

from __future__ import annotations

import argparse
import csv
import json
import subprocess
import sys
import time
from pathlib import Path


def stem_of(row: dict) -> str:
    return f"{row['task']}__l{row['layers']}_h{row['heads']}_t{row['terms']}"


def emptiness(jar: str, path: Path, timeout: int) -> tuple[bool | None, str, float]:
    """`(empty, verdict, seconds)` for one program, via the ABC route."""
    started = time.perf_counter()
    try:
        result = subprocess.run(
            ["java", "-jar", jar, str(path), "--run-abc"],
            capture_output=True, text=True, timeout=timeout,
        )
    except subprocess.TimeoutExpired:
        return None, "timeout", time.perf_counter() - started
    elapsed = time.perf_counter() - started
    output = (result.stdout + result.stderr).strip()
    if "NOT PROVED" in output:
        return False, "non-empty", elapsed
    if "PROVED" in output:
        return True, "empty", elapsed
    return None, "error", elapsed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--csv", type=Path, default=Path("results/uhat_all_best.csv"))
    parser.add_argument("--learned", type=Path, default=Path("results/uhat_experiment/programs"))
    parser.add_argument("--json-dir", type=Path, default=Path("results/uhat_experiment"))
    parser.add_argument("--jar", default="target/scala-3.5.1/brasp-verification.jar")
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--out", type=Path, default=Path("results/uhat_experiment.csv"))
    parser.add_argument(
        "--specs",
        type=Path,
        help="also model-check each specification, and compare its emptiness "
        "with the learned program's; disagreement means the model did not "
        "learn the language even where accuracy says it did",
    )
    args = parser.parse_args()

    fields = [
        "task", "layers", "heads", "terms", "attention_ops",
        "train_seconds", "train_accuracy", "test_accuracy",
        "empty", "verdict", "verify_seconds", "spec_empty", "agrees",
    ]
    records = []
    print(f"{'language':<38}{'train s':>9}{'train':>8}{'test':>8}{'empty':>8}{'verify s':>10}")
    print("-" * 81)

    for row in csv.DictReader(args.csv.open()):
        stem = stem_of(row)
        learned = args.learned / f"{stem}.brasp"
        measurements = args.json_dir / f"{stem}.json"
        if not learned.exists() or not measurements.exists():
            print(f"{row['task']:<38}  MISSING {learned.name}")
            continue
        trained = json.loads(measurements.read_text())

        empty, verdict, elapsed = emptiness(args.jar, learned, args.timeout)

        spec_empty: bool | None = None
        agrees = ""
        if args.specs:
            spec = args.specs / f"{row['task']}.brasp"
            if spec.exists():
                spec_empty, _, spec_seconds = emptiness(args.jar, spec, args.timeout)
                elapsed += spec_seconds
                agrees = "" if spec_empty is None or empty is None else str(spec_empty == empty).lower()

        records.append({
            "task": row["task"], "layers": row["layers"], "heads": row["heads"],
            "terms": row["terms"], "attention_ops": row["attention_ops"],
            "train_seconds": round(trained["seconds"], 1),
            "train_accuracy": trained["train_accuracy"],
            "test_accuracy": trained["test_accuracy"],
            "empty": "" if empty is None else str(empty).lower(),
            "verdict": verdict, "verify_seconds": round(elapsed, 2),
            "spec_empty": "" if spec_empty is None else str(spec_empty).lower(),
            "agrees": agrees,
        })
        print(f"{row['task']:<38}{trained['seconds']:>9.0f}"
              f"{trained['train_accuracy']:>8.4f}{trained['test_accuracy']:>8.4f}"
              f"{verdict:>8}{elapsed:>10.2f}", flush=True)

    if records:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        with args.out.open("w", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=fields, lineterminator="\n")
            writer.writeheader()
            writer.writerows(records)
        empty_count = sum(1 for r in records if r["empty"] == "true")
        print(f"\n{len(records)} languages; {empty_count} empty, "
              f"{len(records) - empty_count} non-empty")
        compared = [r for r in records if r["agrees"]]
        if compared:
            matched = sum(1 for r in compared if r["agrees"] == "true")
            print(f"emptiness matches the specification on {matched}/{len(compared)}")
            for r in compared:
                if r["agrees"] != "true":
                    print(f"  MISMATCH {r['task']}: spec {r['spec_empty']}, learned {r['empty']}")
        print(f"training total {sum(r['train_seconds'] for r in records):.0f}s, "
              f"verification total {sum(r['verify_seconds'] for r in records):.1f}s")
        print(f"wrote {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
