#!/usr/bin/env python3
"""Markey agreement under the paper's routes: PVWAA-to-circuit and its three baselines.

Same protocol as the scaling comparison (`ltl_scaling_study.py`,
`circuit_one_variable_baseline.py`): 4 GB heap, wall time including JVM
start-up, median of three successful runs, a terminal failure is not
repeated, and after a failure the larger n of that route are skipped.

    pvwaa            CircuitStudy realizable        -> ABC scleanup; dc2; pdr
    pvwaa_unguarded  the same with -Dpvwaa.maxTotalWork raised past the 500,000 guard
    ltl_circuit      CircuitStudy one-variable      -> ABC (variable elimination first)
    dfa              --one-variable --run-native    (explicit DFA exploration)

Aalta is measured by ltlf_solver_baseline.py (the A_export records).

    python3 scripts/markey_paper_routes.py INPUT_DIR --out OUT.jsonl
"""
from __future__ import annotations

import argparse
import json
import re
import statistics
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from ltl_scaling_study import classify, run_command

ROOT = Path(__file__).resolve().parents[1]
ROUTES = ["pvwaa", "pvwaa_unguarded", "ltl_circuit", "dfa"]


def once(route: str, ltl: Path, args) -> dict:
    jar = str(args.jar.resolve())
    started = time.monotonic()
    with tempfile.TemporaryDirectory(prefix="markey-") as tmp:
        aig = str(Path(tmp) / "model.aig")
        java = ["java", f"-Xmx{args.heap}"]
        if route == "pvwaa_unguarded":
            java.append(f"-Dpvwaa.maxTotalWork={10**12}")
        if route == "dfa":
            cmd = java + ["-jar", jar, str(ltl), "--one-variable", "--run-native",
                          "--native-max-states", "50000000", "--timing"]
        else:
            cmd = java + ["-cp", jar, "brasp.CircuitStudy", str(ltl),
                          "one-variable" if route == "ltl_circuit" else "realizable", aig]
        code, _, output = run_command(cmd, args.timeout)
        header = re.search(r"header=aig (\d+) (\d+) (\d+) (\d+) (\d+)", output)
        if code == 0 and route != "dfa":
            remaining = args.timeout - (time.monotonic() - started)
            code, _, solver = run_command([str(args.abc.resolve()), "-c",
                f"read_aiger {aig}; print_stats; scleanup; dc2; print_stats; pdr; print_status"], remaining)
            output += "\n" + solver
        status = classify(code, output)
        if status == "error" and "cap of" in output.lower():
            status = "size_limit"
        record = {"status": status, "seconds": time.monotonic() - started}
        if header:
            record["latches"], record["ands"] = int(header.group(3)), int(header.group(5))
        if status not in ("empty", "nonempty"):
            record["detail"] = output.strip().splitlines()[-1:]
        return record


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("inputs", type=Path, help="directory with markey_agreement__n-<n>.ltl")
    p.add_argument("--n", type=int, nargs="+", default=list(range(1, 13)))
    p.add_argument("--routes", nargs="+", default=ROUTES, choices=ROUTES)
    p.add_argument("--jar", type=Path, default=ROOT / "target/scala-3.5.1/brasp-verification.jar")
    p.add_argument("--abc", type=Path, default=ROOT.parent / "abc/abc")
    p.add_argument("--timeout", type=float, default=120)
    p.add_argument("--repetitions", type=int, default=3)
    p.add_argument("--heap", default="4g")
    p.add_argument("--out", type=Path, required=True)
    args = p.parse_args()
    failed, records = set(), []
    for n in args.n:
        ltl = (args.inputs / f"markey_agreement__n-{n}.ltl").resolve()
        record = {"n": n}
        for route in args.routes:
            if route in failed:
                record[route] = {"status": "skipped"}
                continue
            runs = []
            for _ in range(args.repetitions):
                runs.append(once(route, ltl, args))
                if runs[-1]["status"] not in ("empty", "nonempty"):
                    break
            result = runs[-1]
            if result["status"] in ("empty", "nonempty"):
                result = dict(result, seconds=round(statistics.median(r["seconds"] for r in runs), 3))
            else:
                result.pop("seconds")
                failed.add(route)
            record[route] = result
        records.append(record)
        print(json.dumps(record), flush=True)
        args.out.write_text("".join(json.dumps(r) + "\n" for r in records))


if __name__ == "__main__":
    main()
