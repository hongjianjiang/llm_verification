#!/usr/bin/env python3
"""Both emptiness routes over the generated B-RASP families.

    scripts/family_routes.py scan                     # one run each, find the breaking point
    scripts/family_routes.py measure --cells first:2,first:5,first:8,...

`scan` is deliberately single-run and cheap: it locates where each route stops
finishing, so the expensive median-of-3 pass is spent only on the cells a table
would actually report. Timings from `scan` are not for publication -- a single
run on a contended machine has already produced one wrong verdict in this
project's history, which is why `measure` exists separately and refuses to
share a machine.
"""

from __future__ import annotations

import argparse
import csv
import statistics as st
import subprocess
import time
from pathlib import Path

JAR = "target/scala-3.5.1/brasp-verification.jar"
ABC = "/Users/alexander/work/abc/abc"
# `generateSafetyAuto` falls back to a bounded reachable-DFA search when the
# support check fails; at the 4096 default that fallback truncates and the
# instance is refused rather than solved.
AIGER_MAX_STATES = 50_000


def run(path: Path, route: str, budget: int) -> tuple[str, float]:
    args = ([str(path), "--run-abc", "--abc-bin", ABC,
             "--aiger-max-states", str(AIGER_MAX_STATES)] if route == "abc"
            else [str(path), "--one-variable", "--run-native", "--native-max-states", "50000000"])
    started = time.time()
    try:
        done = subprocess.run(["java", "-jar", JAR] + args,
                              capture_output=True, text=True, timeout=budget + 5)
        out = done.stdout + done.stderr
        if "exceeded" in out or "too large" in out or "case split" in out:
            return "BLOWUP", time.time() - started
        if "NOT PROVED" in out:
            return "NONEMPTY", time.time() - started
        if "UNKNOWN" in out:
            return "UNKNOWN", time.time() - started
        if "PROVED" in out:
            return "EMPTY", time.time() - started
        return "?", time.time() - started
    except subprocess.TimeoutExpired:
        return "TIMEOUT", budget + 5


def programs(root: Path):
    for path in sorted(root.glob("*.brasp")):
        family, _, k = path.stem.partition("__k-")
        yield family, int(k), path


def scan(root: Path, budget: int, out: Path | None) -> int:
    rows = []
    print(f"{'program':<26}{'dfa':>10}{'s':>7}{'abc':>10}{'s':>7}")
    for family, k, path in programs(root):
        dv, dt = run(path, "dfa", budget)
        av, at = run(path, "abc", budget)
        rows.append(dict(family=family, k=k, dfa=dv, dfa_seconds=round(dt, 1),
                         abc=av, abc_seconds=round(at, 1)))
        print(f"{path.stem:<26}{dv:>10}{dt:>7.1f}{av:>10}{at:>7.1f}", flush=True)
    if out:
        with out.open("w", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
            writer.writeheader(); writer.writerows(rows)
        print(f"\nwrote {out}")
    return 0


def measure(root: Path, cells: list[str], budget: int, out: Path | None) -> int:
    rows = []
    print(f"{'program':<26}{'dfa':>10}{'median':>8}  {'runs':<14}{'abc':>10}{'median':>8}  runs")
    for cell in cells:
        family, _, k = cell.partition(":")
        path = root / f"{family}__k-{k}.brasp"
        if not path.exists():
            print(f"{cell}: missing"); continue
        record = {"family": family, "k": int(k)}
        for route in ("dfa", "abc"):
            runs = [run(path, route, budget)]
            # A cell that cannot finish once will not finish three times; not
            # repeating it keeps the pass affordable.
            if runs[0][0] != "TIMEOUT":
                runs += [run(path, route, budget) for _ in range(2)]
            verdicts = [v for v, _ in runs]
            record[route] = max(set(verdicts), key=verdicts.count)
            record[f"{route}_median"] = round(st.median(t for _, t in runs), 1)
            record[f"{route}_runs"] = " ".join(f"{t:.0f}" for _, t in runs)
        rows.append(record)
        print(f"{path.stem:<26}{record['dfa']:>10}{record['dfa_median']:>8.1f}  "
              f"{record['dfa_runs']:<14}{record['abc']:>10}{record['abc_median']:>8.1f}  "
              f"{record['abc_runs']}", flush=True)
    if out and rows:
        with out.open("w", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
            writer.writeheader(); writer.writerows(rows)
        print(f"\nwrote {out}")
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("scan", "measure"):
        p = sub.add_parser(name)
        p.add_argument("--root", type=Path, default=Path("examples/brasp/families"))
        p.add_argument("--budget", type=int, default=120)
        p.add_argument("--out", type=Path)
        if name == "measure":
            p.add_argument("--cells", required=True,
                           help="comma-separated family:k, e.g. first:2,first:5,marks:8")
    args = parser.parse_args(argv)
    if args.command == "scan":
        return scan(args.root, args.budget, args.out)
    return measure(args.root, args.cells.split(","), args.budget, args.out)


if __name__ == "__main__":
    raise SystemExit(main())
