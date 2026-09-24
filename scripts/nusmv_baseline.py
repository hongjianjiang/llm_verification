#!/usr/bin/env python3
"""2LTL -> one-variable LTLf -> infinite LTL -> NuSMV (BDD or SAT BMC).

The active prefix includes the export's symbol-free sentinel. It starts
true, eventually ends, and never restarts. Relativizing every temporal
operator to this prefix preserves finite-trace semantics, including weak
next and negation. Checking the negation asks whether any accepted word
exists: a false specification means nonempty. BMC exhaustion is unknown.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import statistics
import sys
import time

from aalta_baseline import alphabet_of, classify_export, export_command, proposition_names
from ltlf_solver_baseline import check_propositions, expand_inputs, load_reference, run_isolated

TOKEN = re.compile(r"\s+|->|[()!&|]|[A-Za-z_][A-Za-z0-9_]*")
UNARY = {"!", "X", "N", "F", "G"}
PRECEDENCE = {"->": 1, "|": 2, "&": 3, "U": 4, "R": 4}


def parse(text: str) -> tuple[list[tuple[str, tuple[int, ...]]], int]:
    """Iterative shunting-yard parser; deep Y instances exceed Python recursion."""
    nodes, values, operators = [], [], []
    def reduce():
        op = operators.pop()
        arity = 1 if op in UNARY else 2
        if len(values) < arity:
            raise ValueError(f"missing operand for {op}")
        children = tuple(values[-arity:])
        del values[-arity:]
        values.append(len(nodes))
        nodes.append((op, children))
    pos, expecting = 0, True
    while pos < len(text):
        match = TOKEN.match(text, pos)
        if not match:
            raise ValueError(f"unexpected character at {pos}")
        token, pos = match.group(), match.end()
        if token.isspace():
            continue
        if expecting:
            if token in UNARY or token == "(":
                operators.append(token)
                continue
            if token in PRECEDENCE or token == ")":
                raise ValueError(f"expected operand, got {token}")
            values.append(len(nodes))
            nodes.append((token, ()))
            expecting = False
        elif token == ")":
            while operators and operators[-1] != "(":
                reduce()
            if not operators:
                raise ValueError("unmatched closing parenthesis")
            operators.pop()
        elif token in PRECEDENCE:
            while operators and operators[-1] != "(" and (
                operators[-1] in UNARY or
                PRECEDENCE[operators[-1]] > PRECEDENCE[token] or
                (PRECEDENCE[operators[-1]] == PRECEDENCE[token] and token not in {"->", "U", "R"})
            ):
                reduce()
            operators.append(token)
            expecting = True
        else:
            raise ValueError(f"expected operator, got {token}")
    if expecting:
        raise ValueError("missing final operand")
    while operators:
        if operators[-1] == "(":
            raise ValueError("unclosed parenthesis")
        reduce()
    if len(values) != 1:
        raise ValueError("invalid formula")
    return nodes, values[0]


def to_smv(text: str) -> tuple[str, dict[str, str]]:
    nodes, root = parse(text)
    names = {op: f"p{index}" for index, op in enumerate(dict.fromkeys(
        op for op, children in nodes if not children and op not in {"true", "false"}
    ))}
    # Emit from an explicit stack in linear time, with no recursive string copies.
    output, pending = [], [root]
    while pending:
        item = pending.pop()
        if isinstance(item, str):
            output.append(item)
            continue
        op, children = nodes[item]
        if not children:
            output.append({"true": "TRUE", "false": "FALSE"}.get(op, names.get(op, op)))
            continue
        if op == "!":
            pieces = ["!(", children[0], ")"]
        elif op in {"X", "N", "F", "G"}:
            prefix = {"X": "X(active & (", "N": "X(!active | (",
                      "F": "F(active & (", "G": "G(!active | ("}[op]
            pieces = [prefix, children[0], "))"]
        elif op in {"U", "R"}:
            middle = ") U (active & (" if op == "U" else ") V (!active | ("
            pieces = ["((", children[0], middle, children[1], ")))"]
        else:
            pieces = ["(", children[0], f" {op} ", children[1], ")"]
        pending.extend(reversed(pieces))
    model = ["MODULE main", "VAR", "  active : boolean;"]
    model += [f"  {name} : boolean;" for name in names.values()]
    model += ["INIT active", "TRANS !active -> !next(active)",
              "LTLSPEC !(F(!active) & (" + "".join(output) + "));", ""]
    return "\n".join(model), names


def classify(code: int, output: str, engine: str) -> str:
    if code == -999:
        return "timeout"
    if code != 0:
        return "size_limit" if re.search(r"out of memory|bad_alloc|memory exhausted", output, re.I) else "error"
    verdicts = re.findall(r"^-- specification .* is (true|false)\s*$", output, re.M)
    if verdicts == ["false"]:
        return "nonempty"
    if verdicts == ["true"] and engine == "bdd":
        return "empty"
    if engine == "bmc" and "no counterexample found with bound" in output:
        return "bound_limit"
    return "unknown"


def witness(output: str, names: dict[str, str], alphabet: list[str]) -> list[str] | None:
    symbols = {names[p]: s for s, p in zip(alphabet, proposition_names(alphabet)) if p in names}
    state, word = {}, []
    for block in re.split(r"-> State: [^\n]*<-", output)[1:]:
        state.update(dict(re.findall(r"^\s*(\w+) = (TRUE|FALSE)\s*$", block, re.M)))
        if state.get("active") == "FALSE":
            break
        present = [symbol for p, symbol in symbols.items() if state.get(p) == "TRUE"]
        if not present:  # the active, symbol-free sentinel
            return list(reversed(word)) if word else None
        if len(present) != 1:
            return None
        word.append(present[0])
    return None


def measure(args, source: Path) -> dict:
    alphabet = alphabet_of(source)
    check_propositions(alphabet)
    if set(proposition_names(alphabet)) & {"true", "false", "bos_marker"}:
        raise ValueError("alphabet collides with reserved LTLf export names")
    record = dict(input=source.name, solver="nusmv", engine=args.engine,
                  timeout_seconds=args.timeout, bound=args.bound if args.engine == "bmc" else None,
                  sha256=hashlib.sha256(source.read_bytes()).hexdigest())
    work = args.out.parent / "artifacts" / args.engine / source.stem
    work.mkdir(parents=True, exist_ok=True)
    samples = []
    for rep in range(args.repetitions):
        start = time.monotonic()
        command = export_command(args.jar, source.resolve(), args.heap)
        code, export_s, exported = run_isolated(command, None, args.timeout, work)
        (work / f"export-{rep}.log").write_text(exported)
        failure = classify_export(code, exported)
        if failure:
            record.update(status=failure, stage="export", wall_seconds=time.monotonic() - start)
            break
        formula = exported.strip().splitlines()[-1]
        (work / "formula.ltlf").write_text(formula + "\n")
        smv, names = to_smv(formula)
        (work / "model.smv").write_text(smv)
        (work / "propositions.json").write_text(json.dumps(names, indent=2))
        encode_s = time.monotonic() - start - export_s
        remaining = args.timeout - (time.monotonic() - start)
        if remaining <= 0:
            record.update(status="timeout", stage="encode", wall_seconds=time.monotonic() - start)
            break
        commands = ("go\ncheck_ltlspec\n" if args.engine == "bdd" else
                    f"go_bmc\ncheck_ltlspec_bmc_inc -k {args.bound}\n") + "quit\n"
        (work / "commands.smv").write_text(commands)
        command = [str(args.nusmv), "-s", "-source", "commands.smv", "model.smv"]
        code, solve_s, output = run_isolated(command, None, remaining, work)
        (work / f"solver-{rep}.log").write_text(output)
        status = classify(code, output, args.engine)
        wall = time.monotonic() - start
        sample = dict(status=status, export_seconds=export_s, encode_seconds=encode_s,
                      solve_seconds=solve_s, wall_seconds=wall, returncode=code)
        samples.append(sample)
        record.update(sample, stage="solve", formula_bytes=len(formula), smv_bytes=len(smv))
        if status not in {"empty", "nonempty"}:
            break
        if len({s["status"] for s in samples}) != 1:
            record["status"] = "inconsistent"
            break
        if status == "nonempty" and "witness" not in record:
            record["witness"] = witness(output, names, alphabet_of(source))
    record["samples"] = samples
    record["repetitions"] = len(samples)
    if record["status"] in {"empty", "nonempty"}:
        for key in ("export_seconds", "encode_seconds", "solve_seconds", "wall_seconds"):
            record[key] = round(statistics.median(s[key] for s in samples), 6)
    return record


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("inputs", nargs="*", type=Path)
    parser.add_argument("--manifest", type=Path, help="TSV: task, input name, path (Figure 3)")
    parser.add_argument("--nusmv", type=Path, default=Path(shutil.which("NuSMV") or "NuSMV"))
    parser.add_argument("--jar", type=Path, default=Path("target/scala-3.5.1/brasp-verification.jar"))
    parser.add_argument("--engine", choices=("bdd", "bmc"), default="bdd")
    parser.add_argument("--bound", type=int, default=100, help="BMC maximum transition bound; exhaustion is inconclusive")
    parser.add_argument("--timeout", type=float, default=900)
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--heap", default="4g")
    parser.add_argument("--reference", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--skip-after-failure", action="store_true",
                        help="manifest only: mark larger cases skipped after timeout/size limit (never after bound limit)")
    args = parser.parse_args()
    if args.bound < 0 or args.timeout <= 0 or args.repetitions < 1:
        parser.error("invalid bound, timeout, or repetitions")
    args.jar, args.nusmv, args.out = args.jar.resolve(), args.nusmv.resolve(), args.out.resolve()
    if not args.jar.is_file() or not args.nusmv.is_file():
        parser.error("jar and NuSMV executable must exist")
    entries = [(p.stem, p) for p in expand_inputs(args.inputs)]
    if args.manifest:
        entries += [(row[0], Path(row[2])) for line in args.manifest.read_text().splitlines()
                    if line.strip() for row in [line.split("\t")]]
    if not entries:
        parser.error("provide inputs or --manifest")
    if len({source.name for _, source in entries}) != len(entries):
        parser.error("input basenames must be unique (artifact and resume keys)")
    reference = load_reference(args.reference)
    previous = {}
    if args.resume and args.out.exists():
        previous = {r["input"]: r for line in args.out.read_text().splitlines() for r in [json.loads(line)]}
    args.out.parent.mkdir(parents=True, exist_ok=True)
    failed, records = {}, []
    for task, source in entries:
        family = task.split("__")[0]
        if source.name in previous:
            record = previous[source.name]
            if (record.get("engine") != args.engine or record.get("timeout_seconds") != args.timeout or
                record.get("bound") != (args.bound if args.engine == "bmc" else None) or
                record.get("sha256") != hashlib.sha256(source.read_bytes()).hexdigest()):
                parser.error(f"resume configuration/input mismatch for {source}")
        elif args.skip_after_failure and args.manifest and family in failed:
            record = dict(input=source.name, solver="nusmv", engine=args.engine, status="skipped",
                          reason=failed[family], timeout_seconds=args.timeout,
                          bound=args.bound if args.engine == "bmc" else None,
                          sha256=hashlib.sha256(source.read_bytes()).hexdigest())
        else:
            record = measure(args, source)
        record["task"] = task
        if record["status"] in {"timeout", "size_limit"}:
            failed[family] = source.name + ": " + record["status"]
        if source.name in reference and record["status"] in {"empty", "nonempty"}:
            record["matches_reference"] = record["status"] == reference[source.name]
        records.append(record)
        checkpoint = args.out.with_suffix(args.out.suffix + ".tmp")
        checkpoint.write_text("".join(json.dumps(r) + "\n" for r in records))
        checkpoint.replace(args.out)
        print(json.dumps(record), flush=True)
        if record.get("matches_reference") is False:
            print(f"reference mismatch on {source}; stopping for investigation", file=sys.stderr)
            return 1
    return int(any(r.get("matches_reference") is False or r["status"] in {"error", "inconsistent"} for r in records))


if __name__ == "__main__":
    sys.exit(main())
