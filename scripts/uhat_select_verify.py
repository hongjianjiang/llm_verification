#!/usr/bin/env python3
"""Select one trained model per language and formally check its extraction.

Reproduces the verification step of the Figure 2 UHAT study
(`results/uhat_figure2/verification/`) for any study root prepared by
`uhat_figure2_study.py prepare` and trained with `uhat_sweep.slurm` (or its
local equivalent):

  selection   an exact fit (training and longer test accuracy both 1.0) of the
              smallest architecture (layers, heads, terms) when one exists,
              otherwise best-effort: highest training accuracy, then test
              accuracy, then smallest architecture. On results/uhat_figure2
              this reproduces 25 of the 26 recorded selections.
  equivalence the extracted program against its specification,
              `--equivalent SPEC LEARNED --run-abc`
  emptiness   the extracted program through the direct 2LTL -> circuit route

    python3 scripts/uhat_select_verify.py results/uhat_lms_20260922
"""
from __future__ import annotations

import argparse
import csv
import glob
import hashlib
import json
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from direct_circuit import run, solve  # noqa: E402

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import itertools  # noqa: E402
from uhat import brasp  # noqa: E402
from uhat.train import jar_check  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]


def select(runs: list[dict]) -> tuple[dict, str]:
    size = lambda r: (r["layers"], r["heads"], r["terms"])
    exact = [r for r in runs if r["train_accuracy"] == 1 and r["test_accuracy"] == 1]
    if exact:
        return min(exact, key=size), "exact"
    return min(runs, key=lambda r: (-r["train_accuracy"], -r["test_accuracy"], *size(r))), "best-effort"


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("root", type=Path)
    p.add_argument("--jar", type=Path, default=ROOT / "target/scala-3.5.1/brasp-verification.jar")
    p.add_argument("--abc", type=Path, default=ROOT.parent / "abc/abc")
    p.add_argument("--timeout", type=float, default=900)
    args = p.parse_args()
    out = args.root / "verification"
    out.mkdir(exist_ok=True)
    runs = defaultdict(list)
    for path in glob.glob(str(args.root / "runs" / "*.json")):
        record = json.loads(Path(path).read_text())
        runs[record["task"]].append(record)
    manifest = {json.loads(l)["name"]: json.loads(l) for l in (args.root / "manifest.jsonl").read_text().splitlines()}

    selected, equivalence, emptiness = [], [], []
    jar_sha = hashlib.sha256(args.jar.read_bytes()).hexdigest()
    for task in sorted(runs, key=lambda t: (manifest[t]["family"], manifest[t]["parameter"])):
        pick, kind = select(runs[task])
        stem = f"{task}__l{pick['layers']}_h{pick['heads']}_t{pick['terms']}"
        learned = args.root / "runs" / "programs" / f"{stem}.brasp"
        selected.append({"id": stem, "task": task, "selection": kind, "layers": pick["layers"],
                         "heads": pick["heads"], "terms": pick["terms"], "attention_ops": pick["attention_ops"],
                         "train_accuracy": pick["train_accuracy"], "test_accuracy": pick["test_accuracy"],
                         "tried_configs": len(runs[task]),
                         "exact_configs": sum(r["train_accuracy"] == 1 and r["test_accuracy"] == 1 for r in runs[task])})

        code, seconds, output = run(["java", "-Xss512m", "-jar", str(args.jar), "--equivalent",
                                     manifest[task]["spec"], str(learned), "--run-abc",
                                     "--abc-bin", str(args.abc.resolve())], args.timeout)
        if code == -999:
            verdict = "timeout"
        elif "PROVED — the languages are equivalent" in output and "NOT PROVED" not in output:
            verdict = "equivalent"
        elif "NOT PROVED" in output and "(sat)" in output:
            verdict = "inequivalent"  # a distinguishing word exists
        else:
            verdict = "error"
        (out / f"{stem}.equivalence.txt").write_text(output)
        equivalence.append({"task": task, "selection": kind, "train_accuracy": pick["train_accuracy"],
                            "test_accuracy": pick["test_accuracy"], "layers": pick["layers"], "heads": pick["heads"],
                            "terms": pick["terms"], "attention_ops": pick["attention_ops"], "formal_verdict": verdict,
                            "verification_seconds": round(seconds, 3),
                            "program_sha256": hashlib.sha256(learned.read_bytes()).hexdigest(), "jar_sha256": jar_sha})

        # The proofs are about the Scala Boolean automaton; the training labels and
        # model agreement are about the Python evaluator. Compare the two on every
        # word up to a length bound (at most ~4000 words), empty word included.
        program = brasp.parse(learned.read_text())
        size, upto = len(program.alphabet), 0
        while sum(size ** k for k in range(upto + 2)) <= 4000:
            upto += 1
        words = [w for k in range(upto + 1) for w in itertools.product(program.alphabet, repeat=k)]
        mismatches = jar_check(str(args.jar), learned, program, words)
        equivalence[-1].update(jar_words=len(words), jar_upto=upto, jar_mismatches=len(mismatches))

        ns = argparse.Namespace(route="direct-realizable", jar=args.jar, abc=args.abc, timeout=args.timeout,
                                heap="4g", stack="512m", budget=10**12)
        record = solve(learned.resolve(), ns)
        (out / f"{stem}.emptiness.txt").write_text(record.pop("log"))
        emptiness.append({"task": task, "status": record["status"], "seconds": record["wall_seconds"]})
        print(task, kind, stem, verdict, record["status"], f"jar-mismatches={len(mismatches)}", flush=True)

    (out / "selected.jsonl").write_text("".join(json.dumps(r, sort_keys=True) + "\n" for r in selected))
    for name, rows in (("equivalence.csv", equivalence), ("emptiness.csv", emptiness)):
        with (out / name).open("w", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
            writer.writeheader()
            writer.writerows(rows)


if __name__ == "__main__":
    main()
