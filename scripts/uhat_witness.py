#!/usr/bin/env python3
"""Locate the shortest word on which a learned program and its spec disagree.

    scripts/uhat_witness.py --equivalence results/uhat_equivalence_hi_deeper.csv \
        --specs examples/brasp/random_deeper --learned results/uhat_rerun_hi/programs

The checker says *whether* two programs differ; it does not say where. This
enumerates in length order and reports the first disagreement, which is the
quantity the testing story turns on: a model is trained on every word up to
the enumeration boundary and tested on a band of long words, so a
distinguishing word that falls between the two is one no testing regime the
method applies would ever have shown it.

Per pair it writes the shortest witness and, up to `--max-length`, the
disagreement rate at each length -- the histogram input for "where do the
disagreements live".
"""

from __future__ import annotations

import argparse
import csv
import itertools
import os
import sys
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from uhat import brasp  # noqa: E402

FIELDS = [
    "task", "upto", "layers", "heads", "terms", "verdict",
    "witness_length", "witness", "searched_upto", "words_searched",
    "disagreements", "profile",
]


def stem_of(row: dict, flat: bool = False) -> str:
    if flat:
        return row["task"]
    return f"{row['task']}__l{row['layers']}_h{row['heads']}_t{row['terms']}"


def _disagreements(job):
    """Words in this slice on which the two programs differ. Top level, for pickling."""
    spec, learned, words = job
    return [
        "".join(letters)
        for letters in words
        if brasp.accepts(spec, list(letters)) != brasp.accepts(learned, list(letters))
    ]


def scan(spec: brasp.Program, learned: brasp.Program, max_length: int, budget: int, jobs: int = 1):
    """Enumerate Sigma^+ in length order; return the first disagreement and a per-length profile.

    The scan does not stop at the first witness: the profile past it is what
    shows whether the two languages differ everywhere or only in a band the
    long-word test set happens to miss.
    """
    alphabet = spec.alphabet
    first: tuple[int, str] | None = None
    profile: list[tuple[int, int, int]] = []  # length, words, disagreements
    searched = 0
    for length in range(1, max_length + 1):
        # A length is enumerated whole or not at all: a partial pass could
        # miss the shortest witness and report a longer one as the shortest.
        if searched + len(alphabet) ** length > budget:
            break
        words = list(itertools.product(alphabet, repeat=length))
        if jobs > 1 and len(words) > 4096:
            chunk = (len(words) + jobs - 1) // jobs
            slices = [words[i:i + chunk] for i in range(0, len(words), chunk)]
            with ProcessPoolExecutor(max_workers=jobs) as pool:
                found = list(pool.map(_disagreements, [(spec, learned, s) for s in slices]))
            hits = [w for part in found for w in part]
        else:
            hits = _disagreements((spec, learned, words))
        seen, differ = len(words), len(hits)
        if hits and first is None:
            first = (length, min(hits))
        searched += seen
        profile.append((length, seen, differ))
        print(f"      len {length}: {differ}/{seen}", flush=True)
    return first, profile, searched


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--equivalence", type=Path, nargs="+", required=True,
                        help="CSV(s) written by scripts/uhat_verify.py")
    parser.add_argument("--specs", type=Path,
                        help="directory of specifications; unnecessary when the CSV "
                        "carries a `source` column, as the coverage sweep's does")
    parser.add_argument("--learned", type=Path, required=True,
                        help="directory of extracted programs, or -- when the CSV has "
                        "an `upto` column -- the coverage root holding upto*/programs")
    parser.add_argument("--max-length", type=int, default=16)
    parser.add_argument("--budget", type=int, default=150_000,
                        help="word budget per pair; the scan stops before the first "
                        "length it cannot enumerate exhaustively (150k reaches "
                        "length 10 on 3 letters, 16 on 2)")
    parser.add_argument("--all", action="store_true",
                        help="also scan PROVED pairs; finding a witness there is a bug")
    parser.add_argument("--jobs", type=int, default=max(1, (os.cpu_count() or 2) - 1))
    parser.add_argument("--out", type=Path)
    args = parser.parse_args(argv)

    rows = [r for path in args.equivalence for r in csv.DictReader(path.open())]
    if not args.all:
        rows = [r for r in rows if r["verdict"] != "PROVED"]

    out = []
    for row in rows:
        # The coverage sweep records the specification it trained against and
        # the level it trained at, so its CSV locates both programs on its own.
        if row.get("source"):
            spec_path = Path(row["source"])
        elif args.specs:
            spec_path = args.specs / f"{row['task']}.brasp"
        else:
            parser.error("no `source` column in the CSV; pass --specs")
        if row.get("upto"):
            learned_path = args.learned / f"upto{row['upto']}" / "programs" / f"{stem_of(row)}.brasp"
        else:
            learned_path = args.learned / f"{stem_of(row)}.brasp"
        if not spec_path.exists() or not learned_path.exists():
            print(f"{row['task']}: missing {spec_path if not spec_path.exists() else learned_path}")
            continue
        print(f"  scanning {stem_of(row)} ...", flush=True)
        spec = brasp.parse(spec_path.read_text())
        learned = brasp.parse(learned_path.read_text())
        first, profile, searched = scan(spec, learned, args.max_length, args.budget, args.jobs)
        reached = profile[-1][0] if profile else 0
        total = sum(d for _, _, d in profile)
        shape = " ".join(f"{n}:{d}/{w}" for n, w, d in profile if d)
        print(f"{stem_of(row):<28} {row['verdict']:<9} "
              f"shortest={first[1] if first else '-'} (len {first[0] if first else '-'}) "
              f"searched<={reached} disagreements={total}")
        if shape:
            print(f"    by length: {shape}")
        out.append({
            "task": row["task"], "upto": row.get("upto", ""),
            "layers": row["layers"], "heads": row["heads"],
            "terms": row["terms"], "verdict": row["verdict"],
            "witness_length": first[0] if first else "",
            "witness": first[1] if first else "",
            "searched_upto": reached, "words_searched": searched,
            "disagreements": total,
            "profile": ";".join(f"{n}:{d}/{w}" for n, w, d in profile),
        })

    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        with args.out.open("w", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=FIELDS)
            writer.writeheader()
            writer.writerows(out)
        print(f"\nwrote {args.out} ({len(out)} rows)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
