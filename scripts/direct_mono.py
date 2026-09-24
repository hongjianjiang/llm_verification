#!/usr/bin/env python3
"""Run the direct 2LTL -> summary circuit route (plus ABC) on mono(k), e.g. k = 2000.

mono(k) is the circuit-study family H(smaller) over letters s0..s{k-1}: every
earlier position (BOS included) must carry a strictly smaller letter than the
current one. BOS is never a letter, so the language is empty.

`circuit_study.py` renders `smaller` as the k(k-1)/2 pairs
(s_a@i & s_b@j), b < a, nested one parenthesis per pair (~2M levels and ~50 MB
at k = 2000). Here it is written in the equivalent linear form

    lt_1 := s0@i            lt_a := lt_{a-1}@i | s_{a-1}@i
    mono := H( |_a (s_a@i & lt_a@j) )

which is the same formula after distributing (lt_a is false at BOS, like
every symbol test). `--check` compares both renderings on small k.

    python3 scripts/direct_mono.py 2000
    python3 scripts/direct_mono.py 2000 --route direct-support --timeout 3600
"""
import argparse
import sys
from pathlib import Path

from direct_circuit import ROOT, add_solver_arguments, solve, write_record


def mono_ltl(k: int) -> str:
    letters = [f"s{a}" for a in range(k)]
    lines = ["logic past-strict", "alphabet " + " ".join(letters), ""]
    lines += [f"sym_{a} := sym(s{a})@i" for a in range(k)]
    for a in range(1, k):
        lines.append(f"lt_{a} := sym_0@i" if a == 1 else f"lt_{a} := (lt_{a - 1}@i | sym_{a - 1}@i)")
    terms = [f"(sym_{a}@i & lt_{a}@j)" for a in range(1, k)] or ["false"]
    lines += [f"smaller_hist := H({' | '.join(terms)})", "",
              "output := smaller_hist@i", "evaluate at i = |w| (the final input position)", ""]
    return "\n".join(lines)


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("k", type=int, nargs="+", help="alphabet sizes, e.g. 2000")
    add_solver_arguments(p, "results/direct_mono")
    p.add_argument("--check", action="store_true",
                   help="for k <= 32, also run circuit_study's quadratic rendering and compare verdicts")
    args = p.parse_args()
    (args.out / "inputs").mkdir(parents=True, exist_ok=True)

    for k in args.k:
        ltl = (args.out / "inputs" / f"mono_{k}.ltl").resolve()
        ltl.write_text(mono_ltl(k))
        record = solve(ltl, args)
        record.update(k=k, expected="empty", matches_expected=record["status"] == "empty")
        if args.check and k <= 32:
            sys.path.insert(0, str(ROOT)); sys.path.insert(0, str(ROOT / "scripts"))
            from ltl2_generator.ast import AtI, AtJ, Hist, Letter, andb, orb
            from ltl2_generator.print import brasp_ltl
            letters = [f"s{a}" for a in range(k)]
            smaller = orb(*[andb(AtI(Letter(letters[a])), AtJ(Letter(letters[b])))
                            for a in range(k) for b in range(a)])
            quad = (args.out / "inputs" / f"mono_{k}_quadratic.ltl").resolve()
            quad.write_text(brasp_ltl(Hist(smaller), letters))
            record["quadratic_status"] = solve(quad, args)["status"]
        write_record(record, args)


if __name__ == "__main__":
    main()
