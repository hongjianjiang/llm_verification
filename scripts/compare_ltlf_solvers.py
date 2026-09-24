#!/usr/bin/env python3
"""Rank the LTLf solver baselines by speed on a common time budget.

Reads `records_<solver>.jsonl` from a `ltlf_solver_baseline.py` run, plus the
earlier Aalta run (120 s budget, cut down to this run's budget), and prints

  * per solver: instances decided, PAR-2 score (a failure costs twice the
    budget), and total time on the instances every solver decided;
  * per instance: each solver's wall time and the fastest one.

Wall times include the shared export step (JVM startup and one-variable
elimination), so they are comparable across solvers. PAR-2 is the usual
SAT-competition ranking; "common" totals compare speed without the
coverage differences.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import statistics


def load(path: Path) -> dict[str, dict]:
    records = {}
    for line in path.read_text().splitlines():
        if line.strip().startswith("{"):
            record = json.loads(line)
            records[record["input"]] = record
    return records


def decided_time(record: dict | None, budget: float) -> float | None:
    if record is None or record.get("status") not in {"empty", "nonempty"}:
        return None
    wall = record.get("wall_seconds")
    return wall if wall is not None and wall <= budget else None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("run", type=Path, help="directory with instances.tsv and records_<solver>.jsonl")
    parser.add_argument("--budget", type=float, default=60.0)
    parser.add_argument("--aalta", type=Path, nargs="*", default=[
        Path("results/aalta_baseline_20260918/records.jsonl"),
        Path("results/aalta_baseline_20260918/records_rerun_parser_depth.jsonl"),
    ])
    parser.add_argument("--markdown", type=Path, help="also write the tables here")
    args = parser.parse_args()

    instances = [line.split("\t") for line in (args.run / "instances.tsv").read_text().splitlines() if line.strip()]
    solvers: dict[str, dict[str, dict]] = {}
    aalta: dict[str, dict] = {}
    for path in args.aalta:
        if path.exists():
            aalta.update(load(path))  # later files supersede earlier ones
    if aalta:
        solvers["aalta"] = aalta
    for path in sorted(args.run.glob("log_*.txt")):
        # The log holds the same records as they finish; records_*.jsonl appears only at the end.
        name = path.stem.removeprefix("log_")
        final = args.run / f"records_{name}.jsonl"
        solvers[name] = load(final if final.exists() else path)
    # Only instances every solver has attempted, so a run still in progress compares fairly.
    instances = [row for row in instances if all(row[2] in records for records in solvers.values())]

    times = {name: {row[2]: decided_time(records.get(row[2]), args.budget) for row in instances}
             for name, records in solvers.items()}
    common = [row[2] for row in instances if all(times[name][row[2]] is not None for name in solvers)]
    wrong = {name: sorted(i for i, r in records.items() if r.get("matches_reference") is False)
             for name, records in solvers.items()}

    lines = [f"{len(instances)} instances, {args.budget:g} s budget (wall time incl. export)", ""]
    lines += ["| solver | decided | PAR-2 (s) | total on common | median on common | wrong |",
              "| --- | ---: | ---: | ---: | ---: | ---: |"]
    ranking = []
    for name in solvers:
        solved = [t for t in times[name].values() if t is not None]
        par2 = sum(t if t is not None else 2 * args.budget for t in times[name].values())
        on_common = [times[name][i] for i in common]
        ranking.append((par2, name))
        lines.append(f"| {name} | {len(solved)}/{len(instances)} | {par2:.0f} | "
                     f"{sum(on_common):.1f} | {statistics.median(on_common) if on_common else float('nan'):.2f} | "
                     f"{len(wrong[name])} |")
    lines += ["", f"common instances (decided by all): {len(common)}",
              "ranking by PAR-2: " + " < ".join(name for _, name in sorted(ranking)), ""]

    lines += ["| family | param | " + " | ".join(solvers) + " | fastest |",
              "| --- | ---: | " + " | ".join("---:" for _ in solvers) + " | --- |"]
    wins = {name: 0 for name in solvers}
    for family, parameter, instance in instances:
        cells = []
        for name in solvers:
            t = times[name][instance]
            status = solvers[name].get(instance, {}).get("status", "-")
            cells.append(f"{t:.1f}" if t is not None else ("TO" if status == "timeout" else
                                                           "SL" if status == "size_limit" else status))
        decided = {name: times[name][instance] for name in solvers if times[name][instance] is not None}
        fastest = min(decided, key=decided.get) if decided else "none"
        if decided:
            wins[fastest] += 1
        lines.append(f"| {family} | {parameter} | " + " | ".join(cells) + f" | {fastest} |")
    lines += ["", "fastest-on-instance counts: " + ", ".join(f"{n} {c}" for n, c in wins.items())]
    if any(wrong.values()):
        lines.append("verdicts disagreeing with the reference: " + str({n: w for n, w in wrong.items() if w}))

    text = "\n".join(lines)
    print(text)
    if args.markdown:
        args.markdown.write_text(text + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
