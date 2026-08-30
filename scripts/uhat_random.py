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
    describe_language,
    random_program,
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
    args = parser.parse_args(argv)

    rng = random.Random(args.seed)
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
                     **facts})
    with args.manifest.open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]), lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
    print(f"wrote {len(rows)} programs to {args.out} and {args.manifest}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
