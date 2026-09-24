#!/usr/bin/env python3
"""Run the direct 2LTL -> summary circuit route (plus ABC) on .ltl files.

Each input is compiled by `brasp.CircuitStudy <input> <route> model.aig`
(strict-future inputs are mirrored to past, which preserves emptiness) and
model-checked with the ABC script used by `circuit_study.py`:
`scleanup; dc2; pdr`. One JSON record per input goes to `records.jsonl`, the
raw compiler + ABC output to `logs/`.

    python3 scripts/direct_circuit.py examples/ltl
    python3 scripts/direct_circuit.py examples/ltl/two_var__monotone_past__sigma-*.ltl --timeout 600
    python3 scripts/direct_circuit.py examples/ltl --reference results/aalta_baseline_20260918/records.jsonl
"""
import argparse
import json
import re
import subprocess
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def natural_key(path: Path):
    """Sort `k-100` after `k-20`, so every family runs smallest first."""
    return [int(t) if t.isdigit() else t for t in re.split(r"(\d+)", path.name)]


def run(cmd: list[str], timeout: float) -> tuple[int, float, str]:
    start = time.monotonic()
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=max(0.01, timeout))
        return p.returncode, time.monotonic() - start, p.stdout + p.stderr
    except subprocess.TimeoutExpired as e:
        out = e.stdout.decode(errors="replace") if isinstance(e.stdout, bytes) else (e.stdout or "")
        return -999, time.monotonic() - start, out


def classify(code: int, raw: str) -> str:
    if code == -999:
        return "timeout"
    if code != 0:
        return "size_limit" if any(s in raw.lower() for s in ("exceed", "too large", "outofmemory")) else "error"
    if "Property proved" in raw:
        return "empty"
    if "was asserted" in raw or "counter-example" in raw:
        return "nonempty"
    return "unknown"


def solve(ltl: Path, args: argparse.Namespace) -> dict:
    """Compile `ltl` to AIGER and run ABC under one total deadline. `log` holds the raw output."""
    start = time.monotonic()
    with tempfile.TemporaryDirectory(prefix="direct-circuit-") as tmp:
        aig = str(Path(tmp) / "model.aig")
        compile_cmd = ["java", f"-Xmx{args.heap}", f"-Xss{args.stack}",
                       f"-Ddirect.maxCellSymbols={args.budget}",
                       "-cp", str(args.jar.resolve()), "brasp.CircuitStudy", str(ltl), args.route, aig]
        code, compile_s, raw = run(compile_cmd, args.timeout)
        record = {"input": ltl.name, "route": args.route, "compile_wall_seconds": round(compile_s, 3),
                  "compile_command": compile_cmd}
        if code == 0:
            # ABC's AIGER reader recurses along the logic depth; deep circuits (depth > ~65k)
            # overflow the default 8 MB stack and segfault, so run it with the maximum.
            abc_cmd = ["/bin/sh", "-c", 'ulimit -s 65520 2>/dev/null; exec "$@"', "sh",
                       str(args.abc.resolve()), "-c",
                       f"read_aiger {aig}; print_stats; scleanup; dc2; print_stats; pdr; print_status"]
            code, solve_s, solver_raw = run(abc_cmd, args.timeout - (time.monotonic() - start))
            record["solver_wall_seconds"] = round(solve_s, 3)
            raw += "\n" + solver_raw
        record["status"] = classify(code, raw)
        record["wall_seconds"] = round(time.monotonic() - start, 3)
        record["metrics"] = dict(re.findall(r"(rows|max_support|support_cells|realizable_cells|circuit_seconds)=(\S+)", raw))
        header = re.search(r"header=aig (\d+) (\d+) (\d+) (\d+) (\d+)", raw)
        if header:
            record["aig"] = dict(zip(["variables", "inputs", "latches", "outputs", "ands"], map(int, header.groups())))
        record["log"] = raw
        return record


def add_solver_arguments(p: argparse.ArgumentParser, out: str) -> None:
    p.add_argument("--route", default="direct-realizable",
                   choices=["direct-realizable", "direct-support", "realizable", "support"],
                   help="direct-*: the direct summary circuit; realizable/support: through the PVWAA")
    p.add_argument("--jar", type=Path, default=ROOT / "target/scala-3.5.1/brasp-verification.jar")
    p.add_argument("--abc", type=Path, default=ROOT.parent / "abc/abc")
    p.add_argument("--timeout", type=float, default=1800, help="total seconds per input (compile + ABC)")
    p.add_argument("--heap", default="16g")
    p.add_argument("--stack", default="512m", help="JVM thread stack; deep formulas recurse per nesting level")
    p.add_argument("--budget", type=int, default=10**12, help="cell-symbol budget (the matched default is 500000)")
    p.add_argument("--out", type=Path, default=ROOT / out)


def write_record(record: dict, args: argparse.Namespace) -> None:
    (args.out / "logs").mkdir(parents=True, exist_ok=True)
    (args.out / "logs" / f"{Path(record['input']).stem}_{args.route}.txt").write_text(record.pop("log"))
    with (args.out / "records.jsonl").open("a") as handle:
        handle.write(json.dumps(record) + "\n")
    shown = {k: v for k, v in record.items() if k not in ("compile_command", "metrics")}
    print(json.dumps(shown), flush=True)


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("inputs", type=Path, nargs="+", help=".ltl files, or directories to scan for *.ltl")
    add_solver_arguments(p, "results/direct_circuit")
    p.add_argument("--reference", type=Path, action="append", default=[],
                   help="records.jsonl with {input, status}; decided verdicts are compared (repeatable)")
    p.add_argument("--repetitions", type=int, default=1,
                   help="runs per input; the record holds the median time of the successful runs "
                        "(a failed run is final and not repeated)")
    p.add_argument("--keep-going", action="store_true",
                   help="do not skip the larger instances of a family after a smaller one fails")
    args = p.parse_args()

    files = []
    for path in args.inputs:
        files += sorted(path.glob("*.ltl"), key=natural_key) if path.is_dir() else [path]
    reference = {}
    for path in args.reference:
        for line in path.read_text().splitlines():
            r = json.loads(line)
            if r.get("status") in ("empty", "nonempty"):
                reference[Path(r["input"]).name] = r["status"]

    failed: set[str] = set()
    for path in files:
        family = re.split(r"__(?:k|n|sigma)-\d", path.stem)[0]
        if family in failed and not args.keep_going:
            write_record({"input": path.name, "route": args.route, "status": "skipped",
                          "reason": "a smaller instance of this family already failed", "log": ""}, args)
            continue
        runs = [solve(path.resolve(), args)]
        while len(runs) < args.repetitions and runs[-1]["status"] in ("empty", "nonempty"):
            runs.append(solve(path.resolve(), args))
        record = runs[-1]
        if record["status"] in ("empty", "nonempty") and len(runs) > 1:
            walls = sorted(r["wall_seconds"] for r in runs)
            record.update(wall_seconds=walls[len(walls) // 2], wall_all=walls, repetitions=len(runs))
        if record["status"] in ("empty", "nonempty") and path.name in reference:
            record["expected"] = reference[path.name]
            record["matches_expected"] = record["status"] == reference[path.name]
        elif record["status"] not in ("empty", "nonempty"):
            failed.add(family)
        write_record(record, args)


if __name__ == "__main__":
    main()
