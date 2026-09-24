#!/usr/bin/env python3
"""Run both NuSMV baselines on every instance currently plotted in Fig. 3."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import sys
import tempfile

os.environ.setdefault("MPLBACKEND", "Agg")
os.environ.setdefault("MPLCONFIGDIR", str(Path(tempfile.gettempdir()) / "nusmv-mpl"))
os.environ.setdefault("XDG_CACHE_HOME", str(Path(tempfile.gettempdir()) / "nusmv-cache"))
from plot_exp_scaling import ROWS, LMS_N, ROOT
from ltlf_solver_baseline import load_reference


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--nusmv", type=Path, required=True)
    parser.add_argument("--out", type=Path, default=Path("results/nusmv_figure3_20260923"))
    parser.add_argument("--timeout", type=float, default=900)
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--bound", type=int, default=10000)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--jobs", type=int, choices=(1, 2), default=1,
                        help="concurrent engines; cases within each engine stay ordered")
    args = parser.parse_args()
    args.out = args.out.resolve()
    args.out.mkdir(parents=True, exist_ok=True)
    paths = {}
    for line in (ROOT / "results/lisa_figure3_900s_20260921/instances.tsv").read_text().splitlines():
        task, _, path = line.split("\t")
        paths[task] = ROOT / path
    additions = {
        "Y": ("y_n200_n300_20260922/inputs", "y_depth__k-", [200, 300]),
        "no2a": ("no2a_n200_n600_20260922/inputs", "y_depth__no_two_a__k-", [200, 600]),
        "same": ("same_n400_n500_20260922/inputs", "same__k-", [400, 500]),
        "marks": ("../examples/brasp/families", "marks__k-", [200, 800]),
    }
    for family, (directory, prefix, values) in additions.items():
        for n in values:
            # Later additions use .ltl snapshots except marks, whose source is B-RASP.
            candidate = ROOT / "results" / directory / f"{prefix}{n}.ltl"
            if not candidate.exists():
                candidate = candidate.with_suffix(".brasp")
            paths[f"{family}__{n}"] = candidate
    for n in LMS_N:
        paths[f"lms__{n}"] = ROOT / f"results/figure_direct_20260922/lms_inputs/lms_blocks__n-{n}.ltl"
    tasks = [(f, n) for f, n, *_ in ROWS] + [("lms", n) for n in LMS_N]
    entries = [(f"{f}__{n}", paths[f"{f}__{n}"]) for f, n in tasks]
    for _, path in entries:
        if not path.is_file():
            parser.error(f"missing figure input: {path}")
    manifest = args.out / "instances.tsv"
    manifest.write_text("".join(f"{task}\t{path.name}\t{path.relative_to(ROOT)}\n" for task, path in entries))
    # Use the recorded classifications: in particular, monotone_past is empty
    # under the project's boundary semantics. Do not infer truth from plot timings.
    known = {}
    for filename in (
        "figure_direct_20260922/reference.jsonl", "y_n200_n300_20260922/reference.jsonl",
        "no2a_n200_n600_20260922/reference.jsonl", "same_n400_n500_20260922/reference.jsonl",
        "marks_n200_n800_20260922/reference.jsonl",
    ):
        known.update(load_reference(ROOT / "results" / filename))
    missing = [p.name for _, p in entries if p.name not in known]
    if missing:
        parser.error(f"missing reference verdicts: {missing}")
    reference = args.out / "reference.jsonl"
    reference.write_text("".join(json.dumps(dict(input=p.name, status=known[p.name])) + "\n" for _, p in entries))
    nusmv = args.nusmv.resolve()
    metadata = dict(platform=platform.platform(), python=sys.version, timeout=args.timeout,
                    started_at=datetime.now(timezone.utc).isoformat(),
                    java_version=subprocess.run(["java", "-version"], stdout=subprocess.PIPE,
                                                stderr=subprocess.STDOUT, text=True).stdout.splitlines(),
                    repetitions=args.repetitions, jobs=args.jobs, bmc_bound=args.bound, java_heap="4g",
                    instances=len(entries), skip_policy="larger family instances skipped after timeout/size_limit",
                    nusmv=str(nusmv), nusmv_sha256=hashlib.sha256(nusmv.read_bytes()).hexdigest(),
                    jar_sha256=hashlib.sha256((ROOT / "target/scala-3.5.1/brasp-verification.jar").read_bytes()).hexdigest(),
                    nusmv_version=subprocess.run([str(nusmv), "-h"], stdout=subprocess.PIPE,
                                                 stderr=subprocess.STDOUT, text=True).stdout.splitlines()[:15])
    metadata_path = args.out / "metadata.json"
    if args.resume and metadata_path.exists():
        previous = json.loads(metadata_path.read_text())
        for key in ("timeout", "bmc_bound", "java_heap", "nusmv_sha256", "jar_sha256"):
            if previous[key] != metadata[key]:
                parser.error(f"cannot resume with different {key}")
        if previous["repetitions"] != args.repetitions:
            previous.setdefault("repetition_changes", []).append(dict(
                changed_at=datetime.now(timezone.utc).isoformat(),
                previous=previous["repetitions"], remaining=args.repetitions,
                policy="retain completed records; new count applies to remaining cases"))
        if previous.get("jobs", 1) != args.jobs:
            previous.setdefault("concurrency_changes", []).append(dict(
                changed_at=datetime.now(timezone.utc).isoformat(),
                previous=previous.get("jobs", 1), remaining=args.jobs))
        metadata["started_at"] = previous.get("started_at", metadata["started_at"])
        metadata = {**previous, **metadata}
    metadata_path.write_text(json.dumps(metadata, indent=2) + "\n")
    def run_engine(engine):
        command = [sys.executable, str(ROOT / "scripts/nusmv_baseline.py"), "--manifest", str(manifest),
                   "--nusmv", str(nusmv), "--engine", engine, "--bound", str(args.bound),
                   "--timeout", str(args.timeout), "--repetitions", str(args.repetitions),
                   "--reference", str(reference), "--out", str(args.out / f"records_{engine}.jsonl"),
                   "--skip-after-failure"] + (["--resume"] if args.resume else [])
        print("Running", engine, "on", len(entries), "instances", flush=True)
        with (args.out / f"log_{engine}.txt").open("a" if args.resume else "w") as log:
            result = subprocess.call(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
        return result
    if args.jobs == 1:
        for engine in ("bdd", "bmc"):
            result = run_engine(engine)
            if result:
                return result
        return 0
    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        results = list(pool.map(run_engine, ("bdd", "bmc")))
    return next((result for result in results if result), 0)


if __name__ == "__main__":
    raise SystemExit(main())
