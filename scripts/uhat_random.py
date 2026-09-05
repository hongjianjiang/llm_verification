#!/usr/bin/env python3
"""Generate random star-free languages as B-RASP programs, with a manifest.

    scripts/uhat_random.py --count 40 --empty 8 --out examples/brasp/random

Random *B-RASP programs* rather than random regular languages: everything
B-RASP expresses is star-free by construction, so no draw has to be discarded
for falling outside the class. What does get discarded is degeneracy -- most
random programs are the empty language -- and a deliberate handful of empty
ones is kept anyway, so the emptiness check has positives to find rather than
only negatives to confirm.
"""

from __future__ import annotations

import argparse
import csv
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from uhat import brasp  # noqa: E402
from uhat.random_programs import (  # noqa: E402
    canonical_dfa,
    describe_language,
    random_program,
    sample_diverse,
    sample_languages,
)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--count", type=int, default=40, help="non-degenerate languages")
    parser.add_argument("--empty", type=int, default=8, help="empty ones, as emptiness controls")
    parser.add_argument("--out", type=Path, default=Path("examples/brasp/random"))
    parser.add_argument("--manifest", type=Path, default=Path("results/uhat_random.csv"))
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument("--max-depth", type=int, default=4)
    parser.add_argument("--min-depth", type=int, default=1)
    parser.add_argument("--attention-ops", type=int, nargs=2, default=[2, 4],
                        help="range of attention ops per program; raise it to reach deeper languages")
    parser.add_argument(
        "--min-states",
        type=int,
        default=4,
        help="minimum minimal-DFA states; 0 restores the old program-only "
        "filter, which produced 48 programs computing 34 languages, most of "
        "them decided by the final symbol",
    )
    args = parser.parse_args(argv)

    rng = random.Random(args.seed)
    if args.min_states > 0:
        # Exclude anything the readable catalogue already names, so what
        # survives is the tail that a person would not have written down.
        import importlib.util

        spec = importlib.util.spec_from_file_location(
            "_ident", Path(__file__).resolve().parent / "uhat_identify.py"
        )
        identify = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(identify)
        known = []
        for alphabet in (("a", "b"), ("a", "b", "c")):
            for program in identify.catalogue(alphabet).values():
                signature, _ = canonical_dfa(program)
                known.append(signature)
        kept, tally = sample_diverse(
            args.count, rng, min_states=args.min_states,
            min_depth=args.min_depth, max_depth=args.max_depth,
            attention_ops=tuple(args.attention_ops), known=known,
        )
        print(f"drew {tally['drawn']}: {tally['degenerate']} degenerate, "
              f"{tally['too_shallow']} out of depth range, "
              f"{tally['too_simple']} under {args.min_states} DFA states, "
              f"{tally['duplicate']} duplicates, {tally['kept']} kept")
    else:
        kept, tally = sample_languages(args.count, rng, max_depth=args.max_depth)
        print(f"drew {tally['drawn']} programs: {tally['empty']} empty, "
              f"{tally['universal']} universal, {tally['too_deep']} out of depth range, "
              f"{tally['kept']} kept")

    empties = []
    while len(empties) < args.empty:
        program = random_program(rng, rng.choice([("a", "b"), ("a", "b", "c")]),
                                 rng.randint(1, 3), rng.randint(0, 2))
        facts = describe_language(program, rng)
        if facts["short_rate"] == 0.0 and facts["long_rate"] == 0.0 and facts["depth"] >= 1:
            empties.append((program, facts))
    print(f"plus {len(empties)} deliberately empty languages as emptiness controls")

    args.out.mkdir(parents=True, exist_ok=True)
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    rows = []
    for index, (program, facts) in enumerate(kept + empties):
        name = f"rand_{index:03d}"
        (args.out / f"{name}.brasp").write_text(brasp.render(program))
        rows.append({"name": name, "path": str(args.out / f"{name}.brasp"),
                     "expected_empty": facts["long_rate"] == 0.0 and facts["short_rate"] == 0.0,
                     "dfa_states": facts.get("dfa_states", ""), **facts})
    with args.manifest.open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]), lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
    print(f"wrote {len(rows)} programs to {args.out} and {args.manifest}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
