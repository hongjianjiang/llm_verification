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

Rows are gated before they may contribute to the agreement rate, because an
ungated rate is not evidence.  A language whose *specification* is empty has
no positive words at all, so every training label is false, "reject
everything" scores 1.0 on train and test, and the checker duly confirms
empty == empty -- a row an untrained model would also produce.  A language
the model did not actually learn can likewise agree on non-emptiness while
being a different language.  Both are recorded, with a `status`, and both are
kept out of the headline number.  `--ungated` restores the old behaviour of
counting every row.

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


def classify(trained: dict) -> str:
    """`ok`, `degenerate`, or `unsolved` -- may this row carry evidence?

    The same two criteria `uhat_sweep.py best` applies, checked here as well
    because this script is routinely pointed at an unfiltered CSV.
    """
    rate = trained.get("test_positive_rate")
    if rate is not None and not 0.05 <= float(rate) <= 0.95:
        return "degenerate"
    if float(trained["train_accuracy"]) < 1.0 or float(trained["test_accuracy"]) < 1.0:
        return "unsolved"
    return "ok"


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
    parser.add_argument(
        "--ungated",
        action="store_true",
        help="let degenerate and unsolved rows count toward the agreement "
        "rate, as this script did before the gate existed",
    )
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
        "test_positive_rate", "status",
        "empty", "verdict", "verify_seconds", "spec_empty", "agrees",
    ]
    records = []
    print(f"{'language':<38}{'train s':>9}{'train':>8}{'test':>8}{'empty':>11}"
          f"{'verify s':>10}  {'status'}")
    print("-" * 92)

    for row in csv.DictReader(args.csv.open()):
        stem = stem_of(row)
        learned = args.learned / f"{stem}.brasp"
        measurements = args.json_dir / f"{stem}.json"
        if not learned.exists() or not measurements.exists():
            print(f"{row['task']:<38}  MISSING {learned.name}")
            continue
        trained = json.loads(measurements.read_text())
        status = classify(trained)

        empty, verdict, elapsed = emptiness(args.jar, learned, args.timeout)

        spec_empty: bool | None = None
        agrees = ""
        if args.specs:
            spec = args.specs / f"{row['task']}.brasp"
            if spec.exists():
                spec_empty, _, spec_seconds = emptiness(args.jar, spec, args.timeout)
                elapsed += spec_seconds
                # A gated row still gets its verdicts recorded; what it does
                # not get is a vote, so leave `agrees` empty unless it counts.
                if (status == "ok" or args.ungated) and None not in (spec_empty, empty):
                    agrees = str(spec_empty == empty).lower()

        records.append({
            "task": row["task"], "layers": row["layers"], "heads": row["heads"],
            "terms": row["terms"], "attention_ops": row["attention_ops"],
            "train_seconds": round(trained["seconds"], 1),
            "train_accuracy": trained["train_accuracy"],
            "test_accuracy": trained["test_accuracy"],
            "test_positive_rate": trained.get("test_positive_rate", ""),
            "status": status,
            "empty": "" if empty is None else str(empty).lower(),
            "verdict": verdict, "verify_seconds": round(elapsed, 2),
            "spec_empty": "" if spec_empty is None else str(spec_empty).lower(),
            "agrees": agrees,
        })
        print(f"{row['task']:<38}{trained['seconds']:>9.0f}"
              f"{trained['train_accuracy']:>8.4f}{trained['test_accuracy']:>8.4f}"
              f"{verdict:>11}{elapsed:>10.2f}  {status}", flush=True)

    if records:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        with args.out.open("w", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=fields, lineterminator="\n")
            writer.writeheader()
            writer.writerows(records)
        empty_count = sum(1 for r in records if r["empty"] == "true")
        print(f"\n{len(records)} languages; {empty_count} empty, "
              f"{len(records) - empty_count} non-empty")

        degenerate = [r for r in records if r["status"] == "degenerate"]
        unsolved = [r for r in records if r["status"] == "unsolved"]
        for label, bucket, why in (
            ("degenerate", degenerate, "test set is all one class, so "
             "'reject everything' already scores 1.0"),
            ("unsolved", unsolved, "program is provably not the specification, "
             "so agreement on emptiness says nothing"),
        ):
            if bucket:
                print(f"\n{len(bucket)} {label} (excluded from the rate -- {why}):")
                for r in bucket:
                    print(f"  {r['task']:<24} train {float(r['train_accuracy']):.4f}"
                          f"  test {float(r['test_accuracy']):.4f}"
                          f"  positives {r['test_positive_rate']}"
                          f"  spec {r['spec_empty'] or '?'}")

        compared = [r for r in records if r["agrees"]]
        if compared:
            # An empty specification makes agreement automatic rather than
            # earned; count those separately instead of inside the rate.
            trivial = [r for r in compared if r["spec_empty"] == "true"]
            earned = [r for r in compared if r["spec_empty"] != "true"]
            matched = sum(1 for r in earned if r["agrees"] == "true")
            print(f"\nemptiness matches the specification on {matched}/{len(earned)}"
                  f" non-trivial languages")
            if trivial:
                print(f"  plus {len(trivial)} with an empty specification, where "
                      f"agreement is automatic: "
                      f"{', '.join(r['task'] for r in trivial)}")
            for r in compared:
                if r["agrees"] != "true":
                    print(f"  MISMATCH {r['task']}: spec {r['spec_empty']}, learned {r['empty']}")
        print(f"training total {sum(r['train_seconds'] for r in records):.0f}s, "
              f"verification total {sum(r['verify_seconds'] for r in records):.1f}s")
        print(f"wrote {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
