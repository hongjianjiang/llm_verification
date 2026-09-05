#!/usr/bin/env python3
"""Train the corpus at several training coverages, so "wrong but untestable" becomes a rate.

    scripts/uhat_coverage.py plan  --best results/uhat_*_best.csv --levels 5 4 3 2
    UPTO=4 sbatch --array=1-$(wc -l < results/uhat_coverage/upto4/grid.txt) \
        scripts/uhat_sweep.slurm results/uhat_coverage/upto4/grid.txt results/uhat_coverage/upto4
    scripts/uhat_coverage.py collect --root results/uhat_coverage

A model is trained on every word up to length `upto` plus a sample of longer
ones, and tested on a band of much longer ones. Between the two lies a length
range that neither touches. The single counterexample we have -- a model that
matched its specification on every word it was ever shown and still computed a
different language -- lives exactly there.

Turning `upto` down widens that band on purpose. Each level yields a
population of models that score 1.0 on everything the method can test, and the
checker then says what fraction of them are actually wrong. That fraction, as
a function of coverage, is the measurement: it says how much of what passes
for verification by testing is not verification at all.

One architecture per language (the smallest that solved it), so a level costs
about an eighth of a full sweep.
"""

from __future__ import annotations

import argparse
import csv
import json
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

VERIFY_FIELDS = [
    "task", "upto", "layers", "heads", "terms", "source",
    "train_accuracy", "test_accuracy", "perfect", "verdict",
]

COLLECT_FIELDS = [
    "task", "source", "upto", "layers", "heads", "terms",
    "train_words", "model_hard_accuracy", "train_accuracy", "test_accuracy",
    "disagreements", "seconds",
]


def plan(best_paths: list[Path], levels: list[int], root: Path) -> int:
    rows: list[dict] = []
    for path in best_paths:
        rows.extend(csv.DictReader(path.open()))
    # `best` writes one row per language already; guard anyway, since the
    # three corpora were sampled independently and do repeat languages.
    seen, unique = set(), []
    for row in rows:
        key = row["source"] or row["task"]
        if key in seen:
            continue
        seen.add(key)
        unique.append(row)

    for level in levels:
        directory = root / f"upto{level}"
        directory.mkdir(parents=True, exist_ok=True)
        lines = [
            f"{row['source'] or row['task']} {row['layers']} {row['heads']} {row['terms']}"
            for row in unique
        ]
        (directory / "grid.txt").write_text("\n".join(lines) + "\n")
        print(f"{directory/'grid.txt'}: {len(lines)} cells")
        print(
            f"  UPTO={level} sbatch --array=1-{len(lines)}%40 "
            f"scripts/uhat_sweep.slurm {directory/'grid.txt'} {directory}"
        )
    return 0


def run_local(root: Path, levels: list[int], steps: int, restarts: int, limit: int | None) -> int:
    """Sequential fallback for a machine with no scheduler; the grid is the same."""
    for level in levels:
        directory = root / f"upto{level}"
        grid = [line.split() for line in (directory / "grid.txt").read_text().split("\n") if line]
        if limit:
            grid = grid[:limit]
        (directory / "programs").mkdir(parents=True, exist_ok=True)
        for index, (task, layers, heads, terms) in enumerate(grid, 1):
            stem = f"{Path(task).stem}__l{layers}_h{heads}_t{terms}"
            print(f"[upto{level} {index}/{len(grid)}] {stem}", flush=True)
            subprocess.run(
                [sys.executable, "-u", "-m", "uhat.train", "--brasp", task,
                 "--layers", layers, "--heads", heads, "--terms", terms,
                 "--steps", str(steps), "--restarts", str(restarts),
                 "--enumerate-upto", str(level), "--quiet",
                 "--out", str(directory / "programs" / f"{stem}.brasp"),
                 "--json-out", str(directory / f"{stem}.json")],
                check=True,
            )
    return 0


def verify(root: Path, jar: str, timeout: int, out: Path | None, perfect_only: bool) -> int:
    """Ask the checker about every model a level produced, and report the rate.

    The population that matters is the models that pass every test the method
    can apply -- 1.0 on the training words and 1.0 on the long held-out ones.
    Among those, the checker splits the ones that learned the language from the
    ones that merely fit the sample. That split is the measurement; accuracy
    cannot make it.
    """
    rows = []
    for directory in sorted(root.glob("upto*")):
        level = directory.name.removeprefix("upto")
        for path in sorted(directory.glob("*.json")):
            record = json.loads(path.read_text())
            source = record.get("source", "")
            if not source:
                continue
            perfect = (
                float(record.get("train_accuracy", 0)) == 1.0
                and float(record.get("test_accuracy", 0)) == 1.0
            )
            if perfect_only and not perfect:
                continue
            learned = directory / "programs" / f"{path.stem}.brasp"
            if not learned.exists():
                continue
            command = [
                "java", "-jar", jar, "--equivalent", str(source), str(learned), "--run-abc",
            ]
            try:
                result = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
                head = result.stdout.strip().splitlines()[0] if result.stdout.strip() else ""
                if "PROVED — the languages are equivalent" in head:
                    verdict = "PROVED"
                elif "NOT PROVED" in head:
                    verdict = "DIFFERENT"
                else:
                    verdict = "ERROR"
            except subprocess.TimeoutExpired:
                verdict = "TIMEOUT"
            rows.append({
                "task": record.get("task", path.stem), "upto": level,
                "layers": record.get("layers", ""), "heads": record.get("heads", ""),
                "terms": record.get("terms", ""), "source": source,
                "train_accuracy": record.get("train_accuracy", ""),
                "test_accuracy": record.get("test_accuracy", ""),
                "perfect": int(perfect), "verdict": verdict,
            })
            print(f"[upto{level}] {path.stem:<32} {verdict}", flush=True)

    if out:
        out.parent.mkdir(parents=True, exist_ok=True)
        with out.open("w", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=VERIFY_FIELDS)
            writer.writeheader()
            writer.writerows(rows)
        print(f"wrote {out} ({len(rows)} rows)")

    print()
    print(f"{'upto':>5} {'perfect':>8} {'proved':>7} {'wrong':>6}  {'wrong rate':>10}")
    by_level: dict[str, list[dict]] = {}
    for row in rows:
        by_level.setdefault(row["upto"], []).append(row)
    for level, group in sorted(by_level.items(), key=lambda kv: -int(kv[0])):
        perfect = [r for r in group if r["perfect"]]
        wrong = [r for r in perfect if r["verdict"] == "DIFFERENT"]
        proved = [r for r in perfect if r["verdict"] == "PROVED"]
        rate = f"{len(wrong) / len(perfect):.1%}" if perfect else "-"
        print(f"{level:>5} {len(perfect):>8} {len(proved):>7} {len(wrong):>6}  {rate:>10}")
    return 0


def collect(root: Path, out: Path | None) -> int:
    rows = []
    for directory in sorted(root.glob("upto*")):
        for path in sorted(directory.glob("*.json")):
            record = json.loads(path.read_text())
            rows.append({field: record.get(field, "") for field in COLLECT_FIELDS})
            rows[-1]["upto"] = record.get("enumerate_upto", directory.name.removeprefix("upto"))
    rows.sort(key=lambda r: (-int(r["upto"] or 0), r["task"]))
    if out:
        out.parent.mkdir(parents=True, exist_ok=True)
        with out.open("w", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=COLLECT_FIELDS)
            writer.writeheader()
            writer.writerows(rows)
        print(f"wrote {out} ({len(rows)} rows)")
    by_level: dict[str, list[dict]] = {}
    for row in rows:
        by_level.setdefault(str(row["upto"]), []).append(row)
    print(f"{'upto':>5} {'models':>7} {'perfect':>8}   (perfect = 1.0 on train and long test)")
    for level, group in sorted(by_level.items(), key=lambda kv: -int(kv[0] or 0)):
        perfect = [
            r for r in group
            if r["train_accuracy"] not in ("", None)
            and float(r["train_accuracy"]) == 1.0 and float(r["test_accuracy"]) == 1.0
        ]
        print(f"{level:>5} {len(group):>7} {len(perfect):>8}")
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("plan")
    p.add_argument("--best", type=Path, nargs="+", required=True)
    p.add_argument("--levels", type=int, nargs="+", default=[5, 4, 3, 2])
    p.add_argument("--root", type=Path, default=Path("results/uhat_coverage"))

    p = sub.add_parser("local", help="run a planned level here, no scheduler")
    p.add_argument("--root", type=Path, default=Path("results/uhat_coverage"))
    p.add_argument("--levels", type=int, nargs="+", required=True)
    p.add_argument("--steps", type=int, default=1500)
    p.add_argument("--restarts", type=int, default=12)
    p.add_argument("--limit", type=int, help="first N cells only, for a smoke test")

    p = sub.add_parser("verify", help="run the checker over a level's models")
    p.add_argument("--root", type=Path, default=Path("results/uhat_coverage"))
    p.add_argument("--jar", default="target/scala-3.5.1/brasp-verification.jar")
    p.add_argument("--timeout", type=int, default=300)
    p.add_argument("--out", type=Path)
    p.add_argument("--all", action="store_true",
                   help="check every model, not only the ones that score 1.0 everywhere")

    p = sub.add_parser("collect")
    p.add_argument("--root", type=Path, default=Path("results/uhat_coverage"))
    p.add_argument("--out", type=Path)

    args = parser.parse_args(argv)
    if args.command == "plan":
        return plan(args.best, args.levels, args.root)
    if args.command == "local":
        return run_local(args.root, args.levels, args.steps, args.restarts, args.limit)
    if args.command == "verify":
        return verify(args.root, args.jar, args.timeout, args.out, not args.all)
    return collect(args.root, args.out)


if __name__ == "__main__":
    raise SystemExit(main())
