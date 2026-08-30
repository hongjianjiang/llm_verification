#!/usr/bin/env python3
"""Plan and collect the UHAT architecture sweep that `uhat_sweep.slurm` runs.

    scripts/uhat_sweep.py plan > results/uhat_sweep/grid.txt   # one config per line
    sbatch --array=1-$(wc -l < results/uhat_sweep/grid.txt) scripts/uhat_sweep.slurm
    scripts/uhat_sweep.py collect                              # -> results/uhat_sweep.csv

The question the sweep answers is the smallest architecture that learns each
language exactly: `collect` reports, per task, the fewest attention heads that
reached 1.0 on the held-out *longer* words.  The two non-star-free tasks are
controls -- no row for them should ever reach 1.0, at any size.
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from uhat.programs import describe, is_feasible  # noqa: E402
from uhat.tasks import TASKS, resolve  # noqa: E402

LAYERS = (1, 2, 3)
HEADS = (1, 2, 3)
TERMS = (1, 2)

FIELDS = [
    "task",
    "source",
    "star_free",
    "layers",
    "heads",
    "terms",
    "best_seed",
    "model_hard_accuracy",
    "train_accuracy",
    "test_accuracy",
    "test_positive_rate",
    "disagreements",
    "attention_ops",
    "seconds",
]


def plan(tasks, layers_axis=LAYERS, heads_axis=HEADS, terms_axis=TERMS) -> list[str]:
    return [
        f"{task} {layers} {heads} {terms}"
        for task in tasks
        for layers in layers_axis
        for heads in heads_axis
        for terms in terms_axis
    ]


def plan_programs(directory: Path, heads_axis, terms_axis, max_alphabet: int, max_depth: int):
    """Grid over every feasible `.brasp` program, layers matched to its depth.

    A program needing `d` chained attention ops cannot be computed in fewer
    than `d` layers, so a fixed layer axis would silently make most of these
    languages unlearnable rather than untrained.
    """
    lines, skipped = [], []
    for path in sorted(directory.glob("*.brasp")):
        if path.stem.startswith(("learned__", "spec__")):
            continue  # our own outputs, not benchmark languages
        try:
            facts = describe(path)
        except Exception as error:  # noqa: BLE001 - report and keep going
            skipped.append((path.stem, f"unparsable: {error}"))
            continue
        if not is_feasible(facts, max_alphabet, max_depth):
            reason = (
                f"alphabet {facts['alphabet']}" if facts["alphabet"] > max_alphabet
                else f"depth {facts['depth']}"
            )
            skipped.append((path.stem, reason))
            continue
        for layers in (facts["depth"], facts["depth"] + 1):
            for heads in heads_axis:
                for terms in terms_axis:
                    lines.append(f"{path} {layers} {heads} {terms}")
    return lines, skipped


def best(csv_path: Path, specs: Path, out: Path | None = None) -> int:
    """Per-language table: the smallest architecture that actually solved it.

    "Solved" means 1.0 on both the training words and the longer held-out
    ones. Test alone is not enough -- a program can generalise to long words
    while still missing a short one it was trained on.

    The `need` column is the specification's own attention depth, the fewest
    chained attention ops the language can be computed with. Comparing it to
    the layers the search actually needed says whether training found a
    minimal program or paid for slack.
    """
    paths = csv_path if isinstance(csv_path, list) else [csv_path]
    rows: list[dict] = []
    for path in paths:
        rows.extend(csv.DictReader(path.open()))

    by_task: dict[str, list[dict]] = {}
    for row in rows:
        by_task.setdefault(row["task"], []).append(row)

    # A language can appear twice: once trained as a built-in task and once
    # against its .brasp spec. They share a name but not a sampler or length
    # plan, so a configuration chosen on one does not transfer to the other.
    # Where both exist, keep only the spec-trained rows -- the spec is what
    # the learned program is later verified against.
    for name, candidates in list(by_task.items()):
        from_spec = [r for r in candidates if r.get("source")]
        if from_spec and len(from_spec) != len(candidates):
            by_task[name] = from_spec

    chosen: list[dict] = []

    print(f"{'language':<38}{'need':>5}{'layers':>7}{'heads':>6}{'terms':>6}"
          f"{'ops':>5}{'solved':>8}{'median s':>10}")
    print("-" * 85)
    for name in sorted(by_task):
        candidates = by_task[name]
        spec = specs / f"{name}.brasp"
        need = ""
        if spec.exists():
            try:
                need = str(describe(spec)["depth"])
            except Exception:  # noqa: BLE001
                need = "?"
        rate = next((float(r["test_positive_rate"]) for r in candidates
                     if r.get("test_positive_rate")), None)
        solved = [
            r for r in candidates
            if float(r["test_accuracy"]) == 1.0 and float(r["train_accuracy"]) == 1.0
        ]
        times = sorted(float(r["seconds"]) for r in candidates)
        median = times[len(times) // 2] if times else float("nan")
        if rate is not None and not 0.05 <= rate <= 0.95:
            print(f"{name:<38}{need:>5}{'  -- inconclusive: test set is ' + format(rate, '.0%') + ' positive':>44}")
            continue
        if not solved:
            top = max(float(r["test_accuracy"]) for r in candidates)
            print(f"{name:<38}{need:>5}{'  -- unsolved (best test ' + format(top, '.4f') + ')':>38}"
                  f"{len(candidates):>8}{median:>10.0f}")
            continue
        pick = min(solved, key=lambda r: (int(r["layers"]), int(r["heads"]),
                                          int(r["terms"]), int(r["attention_ops"])))
        chosen.append({
            "task": name,
            "layers": pick["layers"],
            "heads": pick["heads"],
            "terms": pick["terms"],
            "attention_ops": pick["attention_ops"],
            "spec_depth": need,
            "solved_configs": len(solved),
            "tried_configs": len(candidates),
            # How the trainer should be invoked: a spec file if one exists,
            # otherwise the built-in task name.
            # Prefer what the run actually used; fall back to the spec file
            # only for older rows recorded before `source` existed.
            "source": pick.get("source") or (str(spec) if spec.exists() else ""),
        })
        print(f"{name:<38}{need:>5}{pick['layers']:>7}{pick['heads']:>6}{pick['terms']:>6}"
              f"{pick['attention_ops']:>5}{f'{len(solved)}/{len(candidates)}':>8}{median:>10.0f}")

    if out is not None:
        out.parent.mkdir(parents=True, exist_ok=True)
        fields = ["task", "layers", "heads", "terms", "attention_ops",
                  "spec_depth", "solved_configs", "tried_configs", "source"]
        temporary = out.with_suffix(out.suffix + ".partial")
        with temporary.open("w", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=fields, lineterminator="\n")
            writer.writeheader()
            writer.writerows(chosen)
        temporary.replace(out)
        print(f"\nwrote {out} ({len(chosen)} languages)")
    return 0


def collect(directory: Path, out: Path) -> int:
    records = []
    for path in sorted(directory.glob("*.json")):
        try:
            records.append(json.loads(path.read_text()))
        except json.JSONDecodeError:
            print(f"skipping malformed {path}", file=sys.stderr)
    if not records:
        print(f"no result files in {directory}", file=sys.stderr)
        return 1

    records.sort(key=lambda r: (r["task"], r["layers"], r["heads"], r["terms"]))
    out.parent.mkdir(parents=True, exist_ok=True)
    temporary = out.with_suffix(out.suffix + ".partial")
    with temporary.open("w", newline="") as handle:
        writer = csv.DictWriter(
            handle, fieldnames=FIELDS, extrasaction="ignore", lineterminator="\n"
        )
        writer.writeheader()
        writer.writerows({**{"source": ""}, **r} for r in records)
    temporary.replace(out)
    print(f"wrote {out} ({len(records)} rows)")

    unfaithful = [r for r in records if r["disagreements"]]
    if unfaithful:
        print(f"WARNING: {len(unfaithful)} runs where the extracted program "
              f"disagrees with the hardened model", file=sys.stderr)

    print("\nsmallest architecture reaching 1.0 on longer words:")
    for name in dict.fromkeys(r["task"] for r in records):
        star_free = next(r["star_free"] for r in records if r["task"] == name)
        # A split with (almost) one class makes "1.0" meaningless -- always
        # rejecting scores perfectly. Report those as inconclusive.
        rate = next((r.get("test_positive_rate") for r in records if r["task"] == name), None)
        if rate is not None and not 0.05 <= rate <= 0.95:
            print(f"  {name:<26} INCONCLUSIVE: test set is {rate:.1%} positive")
            continue
        solved = [r for r in records if r["task"] == name and r["test_accuracy"] == 1.0]
        marker = "" if star_free else "   <- NOT star-free, should stay unsolved"
        if not solved:
            best = max(
                (r["test_accuracy"] for r in records if r["task"] == name), default=float("nan")
            )
            print(f"  {name:<16} unsolved (best test accuracy {best:.4f}){marker}")
            continue
        smallest = min(solved, key=lambda r: (r["attention_ops"], r["layers"], r["terms"]))
        print(
            f"  {name:<16} {smallest['attention_ops']} attention op(s) "
            f"[layers={smallest['layers']} heads={smallest['heads']} "
            f"terms={smallest['terms']}]{marker}"
        )
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    subparsers = parser.add_subparsers(dest="command", required=True)

    planner = subparsers.add_parser("plan", help="print the sweep grid, one config per line")
    planner.add_argument("--tasks", nargs="*", default=sorted(TASKS))
    # dot_depth k needs roughly k chained attention ops, so its layer axis has
    # to track k rather than sit at the default 1-3.
    planner.add_argument("--layers", nargs="*", type=int, default=list(LAYERS))
    planner.add_argument("--heads", nargs="*", type=int, default=list(HEADS))
    planner.add_argument("--terms", nargs="*", type=int, default=list(TERMS))

    programs = subparsers.add_parser(
        "programs", help="grid over every feasible .brasp program in a directory"
    )
    programs.add_argument("--dir", type=Path, default=Path("examples/brasp"))
    programs.add_argument("--heads", nargs="*", type=int, default=[1, 2])
    programs.add_argument("--terms", nargs="*", type=int, default=[1, 2])
    programs.add_argument("--max-alphabet", type=int, default=4)
    programs.add_argument("--max-depth", type=int, default=8)
    programs.add_argument("--report-skipped", action="store_true")

    chooser = subparsers.add_parser("best", help="per-language table of the best architecture")
    chooser.add_argument("--csv", type=Path, nargs="+", default=[Path("results/uhat_sweep.csv")])
    chooser.add_argument("--specs", type=Path, default=Path("examples/brasp"))
    chooser.add_argument("--out", type=Path, help="also write the picks as CSV")

    collector = subparsers.add_parser("collect", help="fold result JSON into a CSV")
    collector.add_argument("--dir", type=Path, default=Path("results/uhat_sweep"))
    collector.add_argument("--out", type=Path, default=Path("results/uhat_sweep.csv"))

    args = parser.parse_args(argv)
    if args.command == "plan":
        for name in args.tasks:
            try:
                resolve(name)
            except KeyError as error:
                parser.error(str(error).strip("'"))
        print("\n".join(plan(args.tasks, args.layers, args.heads, args.terms)))
        return 0
    if args.command == "best":
        return best(args.csv, args.specs, args.out)
    if args.command == "programs":
        lines, skipped = plan_programs(
            args.dir, args.heads, args.terms, args.max_alphabet, args.max_depth
        )
        print("\n".join(lines))
        if args.report_skipped:
            for name, reason in skipped:
                print(f"# skipped {name}: {reason}", file=sys.stderr)
        return 0
    return collect(args.dir, args.out)


if __name__ == "__main__":
    sys.exit(main())
