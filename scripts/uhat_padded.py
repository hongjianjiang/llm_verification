#!/usr/bin/env python3
"""Build a depth-padded corpus: same languages, deliberately deeper programs.

    scripts/uhat_padded.py build --manifest results/uhat_random100.csv \
        --best results/uhat_random100_best.csv --depths 3 6 9 --count 12 --verify
    scripts/uhat_padded.py plan --root examples/brasp/padded

In the sampled corpora, depth and language vary together: the deep programs
were drawn with a wider `--attention-ops` range and compute languages that are
also harder by every other measure. So "learnability is nearly flat in
attention depth" is confounded -- nothing holds the language fixed.

Padding holds it fixed. `uhat.pad` rewrites a program to any greater depth
without changing what it accepts, so a language can be presented to the
trainer as a depth-3 problem and again as a depth-9 one. If learnability
really is flat in depth, both should be learned; if the depth of the *program*
is what matters rather than the complexity of the *language*, the padded
variants should fail.

`--verify` proves each padded program equivalent to its source with the model
checker rather than trusting the transform -- the pipeline checking its own
experimental apparatus.
"""

from __future__ import annotations

import argparse
import csv
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from uhat import brasp  # noqa: E402
from uhat.pad import pad_program  # noqa: E402
from uhat.programs import attention_count, attention_depth  # noqa: E402

FIELDS = ["name", "source", "language", "base_depth", "depth", "attention_ops",
          "dfa_states", "alphabet", "path", "equivalent"]


def build(manifest: Path, best: Path, depths: list[int], count: int,
          out: Path, jar: str, verify: bool, timeout: int) -> int:
    solved = {row["task"] for row in csv.DictReader(best.open())}
    candidates = [
        row for row in csv.DictReader(manifest.open())
        if row["name"] in solved
        and row["expected_empty"] != "True"
        and int(row["depth"]) <= min(depths)
    ]
    # Spread over minimal-DFA size rather than taking the first N: the point
    # is to vary depth at fixed language, over a range of languages.
    candidates.sort(key=lambda r: (int(r["dfa_states"]), r["name"]))
    if count and len(candidates) > count:
        step = len(candidates) / count
        candidates = [candidates[int(index * step)] for index in range(count)]

    out.mkdir(parents=True, exist_ok=True)
    rows = []
    for row in candidates:
        source = Path(row["path"])
        program = brasp.parse(source.read_text())
        base = attention_depth(program)
        for depth in depths:
            if depth < base:
                continue
            name = f"{row['name']}_d{depth}"
            padded = pad_program(program, depth)
            path = out / f"{name}.brasp"
            path.write_text(brasp.render(padded))
            verdict = ""
            if verify:
                result = subprocess.run(
                    ["java", "-jar", jar, "--equivalent", str(source), str(path), "--run-abc"],
                    capture_output=True, text=True, timeout=timeout,
                )
                head = result.stdout.strip().splitlines()[0] if result.stdout.strip() else ""
                verdict = "PROVED" if "PROVED — the languages are equivalent" in head else "FAILED"
            rows.append({
                "name": name, "source": str(source), "language": row["name"],
                "base_depth": base, "depth": attention_depth(padded),
                "attention_ops": attention_count(padded),
                "dfa_states": row["dfa_states"], "alphabet": row["alphabet"],
                "path": str(path), "equivalent": verdict,
            })
            print(f"{name:<18} depth {base} -> {attention_depth(padded):<3} {verdict}")

    manifest_out = out.parent / f"{out.name}_manifest.csv"
    with manifest_out.open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(rows)
    print(f"\nwrote {len(rows)} programs to {out} and {manifest_out}")
    if verify:
        bad = [r["name"] for r in rows if r["equivalent"] != "PROVED"]
        print(f"padding verified language-preserving on {len(rows) - len(bad)}/{len(rows)}"
              + (f"; FAILED: {bad}" if bad else ""))
        return 1 if bad else 0
    return 0


def plan(root: Path, grid: Path) -> int:
    lines = []
    for path in sorted(root.glob("*.brasp")):
        depth = attention_depth(brasp.parse(path.read_text()))
        for layers in (depth, depth + 1):
            for heads in (1, 2):
                for terms in (1, 2):
                    lines.append(f"{path} {layers} {heads} {terms}")
    grid.parent.mkdir(parents=True, exist_ok=True)
    grid.write_text("\n".join(lines) + "\n")
    print(f"{grid}: {len(lines)} cells")
    print(f"  sbatch --array=1-{len(lines)}%40 scripts/uhat_sweep.slurm {grid} {grid.parent}")
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("build")
    p.add_argument("--manifest", type=Path, required=True)
    p.add_argument("--best", type=Path, required=True)
    p.add_argument("--depths", type=int, nargs="+", default=[3, 6, 9])
    p.add_argument("--count", type=int, default=12)
    p.add_argument("--out", type=Path, default=Path("examples/brasp/padded"))
    p.add_argument("--jar", default="target/scala-3.5.1/brasp-verification.jar")
    p.add_argument("--verify", action="store_true")
    p.add_argument("--timeout", type=int, default=300)

    p = sub.add_parser("plan")
    p.add_argument("--root", type=Path, default=Path("examples/brasp/padded"))
    p.add_argument("--grid", type=Path, default=Path("results/uhat_padded/grid.txt"))

    args = parser.parse_args(argv)
    if args.command == "build":
        return build(args.manifest, args.best, args.depths, args.count,
                     args.out, args.jar, args.verify, args.timeout)
    return plan(args.root, args.grid)


if __name__ == "__main__":
    raise SystemExit(main())
