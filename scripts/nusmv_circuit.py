#!/usr/bin/env python3
"""Check the direct 2LTL -> summary circuit with NuSMV instead of ABC.

Same circuit, different checker: each input is compiled exactly as in
`direct_circuit.py` (`brasp.CircuitStudy <input> <route> model.aig`), the
AIGER is translated to SMV, and NuSMV decides whether the bad output is
reachable. That separates the encoding from the solver: ABC's PDR and
NuSMV's engines see the same transition system.

    python3 scripts/nusmv_circuit.py examples/ltl --nusmv tmp/nusmv/NuSMV-2.7.1-macosx/bin/NuSMV
    python3 scripts/nusmv_circuit.py results/aalta_baseline_20260918/inputs/y_depth__k-100.ltl --engine kind

Engines (NuSMV 2.7.1 has no IC3; both engines below are complete):
  bdd   `go; check_invar`                        BDD forward reachability
  kind  `go_bmc; check_invar_bmc_inc -a dual -s forward`  SAT k-induction; stopping at
                                                 the bound without a verdict is
                                                 `bound_limit`, never `empty`

Translation: the compiler writes plain binary AIGER (pre-1.9), so latches reset
to 0, and the single output is the bad signal. Inputs become IVARs, latches
become boolean VARs with `init := FALSE`, and AND gates become DEFINEs.
NuSMV rejects input variables in an INVARSPEC, and the bad output does read
the input symbol, so `bad` is latched into `bad_seen` (`next(bad_seen) := bad`)
and the property is `INVARSPEC !bad_seen`. A bad state is reachable at step t
iff `bad_seen` holds at step t + 1, so the verdict is unchanged.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from direct_circuit import ROOT, add_solver_arguments, natural_key, run, write_record  # noqa: E402


def read_aiger(data: bytes) -> tuple[int, list[int], list[int], list[tuple[int, int, int]]]:
    """Plain binary AIGER -> (inputs, latch next literals, outputs, ands as (lhs, rhs0, rhs1))."""
    newline = data.index(b"\n")
    header = data[:newline].decode().split()
    if header[0] != "aig" or len(header) != 6:
        raise ValueError(f"expected a plain binary AIGER header 'aig M I L O A', got {header}")
    _, inputs, latches, outputs, ands = map(int, header[1:])
    position = newline + 1

    def line() -> int:
        nonlocal position
        end = data.index(b"\n", position)
        fields = data[position:end].split()
        position = end + 1
        if len(fields) != 1:
            raise ValueError("latch reset values / AIGER 1.9 sections are not supported")
        return int(fields[0])

    latch_next = [line() for _ in range(latches)]
    output_literals = [line() for _ in range(outputs)]

    def delta() -> int:
        nonlocal position
        value, shift = 0, 0
        while True:
            byte = data[position]
            position += 1
            value |= (byte & 0x7F) << shift
            if byte < 0x80:
                return value
            shift += 7

    gates = []
    for k in range(ands):
        lhs = 2 * (inputs + latches + 1 + k)
        rhs0 = lhs - delta()
        rhs1 = rhs0 - delta()
        gates.append((lhs, rhs0, rhs1))
    return inputs, latch_next, output_literals, gates


def to_smv(data: bytes) -> str:
    inputs, latch_next, outputs, gates = read_aiger(data)
    if len(outputs) != 1:
        raise ValueError(f"expected exactly one (bad) output, got {len(outputs)}")
    first_latch, first_and = inputs + 1, inputs + len(latch_next) + 1

    def name(variable: int) -> str:
        if variable < first_latch:
            return f"i{variable}"
        return f"l{variable}" if variable < first_and else f"a{variable}"

    def literal(value: int) -> str:
        if value < 2:
            return "TRUE" if value else "FALSE"
        return ("!" if value & 1 else "") + name(value >> 1)

    lines = ["MODULE main"]
    if inputs:
        lines.append("IVAR")
        lines += [f"  i{v} : boolean;" for v in range(1, first_latch)]
    lines.append("VAR")
    lines += [f"  l{v} : boolean;" for v in range(first_latch, first_and)]
    lines.append("  bad_seen : boolean;")
    if gates:
        lines.append("DEFINE")
        lines += [f"  {name(lhs >> 1)} := {literal(a)} & {literal(b)};" for lhs, a, b in gates]
    lines.append("ASSIGN")
    for index, successor in enumerate(latch_next):
        latch = name(first_latch + index)
        lines += [f"  init({latch}) := FALSE;", f"  next({latch}) := {literal(successor)};"]
    lines += ["  init(bad_seen) := FALSE;", f"  next(bad_seen) := {literal(outputs[0])};",
              "INVARSPEC !bad_seen", ""]
    return "\n".join(lines)


def commands(engine: str, bound: int) -> str:
    check = "go\ncheck_invar\n" if engine == "bdd" else f"go_bmc\ncheck_invar_bmc_inc -a dual -s forward -k {bound}\n"
    return check + "quit\n"


def classify(code: int, raw: str, engine: str) -> str:
    if code == -999:
        return "timeout"
    if code != 0:
        return ("size_limit" if re.search(r"out of memory|bad_alloc|memory exhausted|stack overflow", raw, re.I)
                else "error")
    verdicts = re.findall(r"^-- invariant .* is (true|false)\s*$", raw, re.M)
    if verdicts == ["true"]:
        return "empty"
    if verdicts == ["false"]:
        return "nonempty"
    if engine == "kind" and re.search(r"no (proof or )?counterexample found with bound", raw, re.I):
        return "bound_limit"
    return "unknown"


def solve(ltl: Path, args: argparse.Namespace) -> dict:
    """Compile `ltl`, translate to SMV and run NuSMV, all under one total deadline."""
    start = time.monotonic()
    with tempfile.TemporaryDirectory(prefix="nusmv-circuit-") as tmp:
        aig = Path(tmp) / "model.aig"
        compile_cmd = ["java", f"-Xmx{args.heap}", f"-Xss{args.stack}",
                       f"-Ddirect.maxCellSymbols={args.budget}",
                       "-cp", str(args.jar.resolve()), "brasp.CircuitStudy", str(ltl), args.route, str(aig)]
        code, compile_s, raw = run(compile_cmd, args.timeout)
        record = {"input": ltl.name, "route": args.route, "solver": "nusmv", "engine": args.engine,
                  "bound": args.bound if args.engine == "kind" else None,
                  "compile_wall_seconds": round(compile_s, 3)}
        if code == 0:
            translate_start = time.monotonic()
            smv = Path(tmp) / "model.smv"
            smv.write_text(to_smv(aig.read_bytes()))
            script = Path(tmp) / "commands.cmd"
            script.write_text(commands(args.engine, args.bound))
            record["translate_seconds"] = round(time.monotonic() - translate_start, 3)
            record["smv_bytes"] = smv.stat().st_size
            # NuSMV flattens DEFINEs recursively along the logic depth; give it
            # the same maximal stack ABC gets in `direct_circuit.py`.
            nusmv_cmd = ["/bin/sh", "-c", 'ulimit -s 65520 2>/dev/null; exec "$@"', "sh",
                         str(args.nusmv.resolve()), "-source", str(script), str(smv)]
            code, solve_s, solver_raw = run(nusmv_cmd, args.timeout - (time.monotonic() - start))
            record["solver_wall_seconds"] = round(solve_s, 3)
            raw += "\n" + solver_raw
            if args.keep_models:
                keep = args.out / "models" / ltl.stem
                keep.mkdir(parents=True, exist_ok=True)
                for path in (aig, smv, script):
                    (keep / path.name).write_bytes(path.read_bytes())
            record["status"] = classify(code, raw, args.engine)
        else:
            record["status"] = "timeout" if code == -999 else (
                "size_limit" if any(s in raw.lower() for s in ("exceed", "too large", "outofmemory")) else "error")
            record["stage"] = "compile"
        record["wall_seconds"] = round(time.monotonic() - start, 3)
        header = re.search(r"header=aig (\d+) (\d+) (\d+) (\d+) (\d+)", raw)
        if header:
            record["aig"] = dict(zip(["variables", "inputs", "latches", "outputs", "ands"], map(int, header.groups())))
        record["log"] = raw
        return record


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("inputs", type=Path, nargs="+", help=".ltl files, or directories to scan for *.ltl")
    add_solver_arguments(p, "results/nusmv_circuit")
    p.add_argument("--nusmv", type=Path, default=ROOT / "tmp/nusmv/NuSMV-2.7.1-macosx/bin/NuSMV")
    p.add_argument("--engine", choices=["bdd", "kind"], default="bdd")
    p.add_argument("--bound", type=int, default=10000, help="k-induction depth limit for --engine kind")
    p.add_argument("--reference", type=Path, action="append", default=[],
                   help="records.jsonl with {input, status}; decided verdicts are compared (repeatable)")
    p.add_argument("--keep-going", action="store_true",
                   help="do not skip the larger instances of a family after a smaller one fails")
    p.add_argument("--keep-models", action="store_true", help="save model.aig / model.smv under --out/models")
    args = p.parse_args()
    args.out = args.out / args.engine

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
            write_record({"input": path.name, "route": args.route, "engine": args.engine, "status": "skipped",
                          "reason": "a smaller instance of this family already failed", "log": ""}, args)
            continue
        record = solve(path.resolve(), args)
        if record["status"] in ("empty", "nonempty"):
            if path.name in reference:
                record["expected"] = reference[path.name]
                record["matches_expected"] = record["status"] == reference[path.name]
        else:
            failed.add(family)
        write_record(record, args)


if __name__ == "__main__":
    main()
