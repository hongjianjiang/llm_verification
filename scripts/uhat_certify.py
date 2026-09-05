#!/usr/bin/env python3
"""Run both model checks over a training run, and report the table columns.

    scripts/uhat_certify.py --run results/uhat_iid --out results/uhat_iid_certified.csv

A run directory holds one `<stem>.json` record per trained cell and the
extracted program at `programs/<stem>.brasp`. For each cell this asks the
checker two questions and times both:

  equivalence  is the extracted program the same language as the
               specification it was trained against?
  emptiness    does the extracted program accept anything at all?

Equivalence is the guarantee; emptiness is the check that needs no
specification, and so is the one still available when no ground-truth program
exists -- it catches a model that has collapsed to rejecting every word, which
a rejection-skewed test set scores highly.

Both were previously done by throwaway scripts, which meant the numbers in the
paper could not be regenerated from the repository. Grouping by minimal-DFA
size reproduces the per-group medians and ranges directly when `--dfa` is
given.
"""

from __future__ import annotations

import argparse
import collections
import csv
import json
import statistics as st
import subprocess
import time
from pathlib import Path


def _run(jar: str, args: list[str], timeout: int) -> tuple[str, float]:
    started = time.time()
    result = subprocess.run(
        ["java", "-jar", jar, *args, "--run-abc"],
        capture_output=True, text=True, timeout=timeout,
    )
    head = result.stdout.strip().splitlines()[0] if result.stdout.strip() else ""
    return head, time.time() - started


def equivalence(jar: str, spec: Path, learned: Path, timeout: int) -> tuple[str, float]:
    head, secs = _run(jar, ["--equivalent", str(spec), str(learned)], timeout)
    if "PROVED — the languages are equivalent" in head:
        return "PROVED", secs
    return ("DIFFERENT" if "NOT PROVED" in head else "ERROR"), secs


def emptiness(jar: str, learned: Path, timeout: int) -> tuple[str, float]:
    # ABC proves the bad state unreachable exactly when the language is empty,
    # so PROVED here means empty and NOT PROVED means a witness exists.
    head, secs = _run(jar, [str(learned)], timeout)
    if "NOT PROVED" in head:
        return "non-empty", secs
    return ("empty" if "PROVED" in head else "ERROR"), secs


def _float(value) -> float | None:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


FIELDS = [
    "task", "stem", "source", "layers", "heads", "terms", "train_seconds",
    "train_accuracy", "holdout_accuracy", "test_accuracy", "certified",
    "equivalence", "equivalence_seconds", "emptiness", "emptiness_seconds",
]


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--run", type=Path, required=True,
                        help="directory of <stem>.json records with a programs/ subdirectory")
    parser.add_argument("--jar", default="target/scala-3.5.1/brasp-verification.jar")
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--out", type=Path)
    parser.add_argument("--dfa", type=Path, nargs="*", default=[],
                        help="uhat_dfa.py CSVs, to group the summary by minimal-DFA size")
    parser.add_argument("--certified-only", action="store_true",
                        help="check only the cells scoring 1.0 on training and held-out words")
    args = parser.parse_args(argv)

    # Task names repeat across the sampled corpora, so anything keyed by name
    # silently mixes languages; the specification path is the only safe key.
    facts: dict[str, dict] = {}
    for path in args.dfa:
        corpus = path.stem.replace("uhat_dfa_", "")
        for row in csv.DictReader(path.open()):
            facts[f"examples/brasp/{corpus}/{row['language']}.brasp"] = row

    rows = []
    for path in sorted(args.run.glob("*.json")):
        record = json.loads(path.read_text())
        learned = args.run / "programs" / f"{path.stem}.brasp"
        source = record.get("source", "")
        if not learned.exists() or not source:
            continue
        train, holdout = _float(record.get("train_accuracy")), _float(record.get("holdout_accuracy"))
        certified = train == 1.0 and (holdout is None or holdout == 1.0)
        if args.certified_only and not certified:
            continue
        eq_verdict, eq_secs = equivalence(args.jar, Path(source), learned, args.timeout)
        mt_verdict, mt_secs = emptiness(args.jar, learned, args.timeout)
        rows.append({
            "task": record.get("task", path.stem), "stem": path.stem, "source": source,
            "layers": record.get("layers", ""), "heads": record.get("heads", ""),
            "terms": record.get("terms", ""), "train_seconds": record.get("seconds", ""),
            "train_accuracy": train, "holdout_accuracy": holdout,
            "test_accuracy": _float(record.get("test_accuracy")), "certified": int(certified),
            "equivalence": eq_verdict, "equivalence_seconds": round(eq_secs, 2),
            "emptiness": mt_verdict, "emptiness_seconds": round(mt_secs, 2),
        })
        print(f"{path.stem:<30} {eq_verdict:<9} {eq_secs:>5.2f}s   "
              f"{mt_verdict:<9} {mt_secs:>5.2f}s", flush=True)

    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        with args.out.open("w", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=FIELDS)
            writer.writeheader()
            writer.writerows(rows)
        print(f"\nwrote {args.out} ({len(rows)} rows)")

    certified = [r for r in rows if r["certified"]]
    print(f"\ncertified {len(certified)} of {len(rows)}: "
          f"{sum(1 for r in certified if r['equivalence'] == 'PROVED')} proved equivalent, "
          f"{sum(1 for r in certified if r['equivalence'] == 'DIFFERENT')} different; "
          f"{sum(1 for r in certified if r['emptiness'] == 'non-empty')} non-empty, "
          f"{sum(1 for r in certified if r['emptiness'] == 'empty')} empty")
    if not certified:
        return 0

    def line(label, group):
        eq = [r["equivalence_seconds"] for r in group]
        mt = [r["emptiness_seconds"] for r in group]
        tr = [float(r["train_seconds"]) for r in group if r["train_seconds"] != ""]
        print(f"{label:>8} {len(group):>4} {st.median(tr):>8.0f} "
              f"{st.median(eq):>7.2f} {min(eq):>6.2f}-{max(eq):<6.2f} "
              f"{st.median(mt):>7.2f} {min(mt):>6.2f}-{max(mt):<6.2f}")

    print(f"\n{'|Q|':>8} {'n':>4} {'train':>8} {'equiv':>7} {'range':>13} {'empty':>7} {'range':>13}")
    if facts:
        def bucket(states: int):
            return states if states <= 8 else "9-15" if states <= 15 else ">=16"
        groups = collections.defaultdict(list)
        for row in certified:
            fact = facts.get(row["source"])
            if fact:
                groups[bucket(int(fact["dfa_states"]))].append(row)
        for key in sorted(groups, key=lambda k: (isinstance(k, str), k if isinstance(k, int) else 99)):
            line(str(key), groups[key])
    else:
        line("all", certified)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
