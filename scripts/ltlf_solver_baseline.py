#!/usr/bin/env python3
"""Emptiness baselines that hand the exported LTLf formula to an off-the-shelf solver.

Generalizes `aalta_baseline.py` to five back ends behind the same front end:
`--one-variable --ltlf` exports flat LTLf text in Aalta syntax (see
`LtlfExport.scala`), which is rewritten token by token into each solver's
input language and then decided.

    aalta   aaltaf, SAT-aided explicit search           sat / unsat
    black   BLACK `solve --finite`, SAT-based bounded   SAT / UNSAT
    ltl2sat LTL2SAT, SAT reduction (Fionda & Greco)     sat / unsat
    lisa    Lisa, compositional LTLf -> DFA             EMPTY / NONEMPTY (local patch)
    lydia   Lydia, compositional LTLf -> symbolic DFA   EMPTY / NONEMPTY (local driver)

The one real difference between the input languages is the next operator.
The export uses Aalta's strong `X` and weak `N`; BLACK spells weak next
`wX`, while Lisa and Lydia follow Spot, where plain `X` is *weak* and strong
next is `X[!]`. Getting this wrong flips verdicts silently (the export's
end-of-trace test is `!(X(true))`), so every rewrite goes through
`translate`, which also checks that no proposition collides with an
operator letter.

As in the Aalta baseline, a satisfying trace is for the reversed word;
Aalta and BLACK witnesses are reversed and replayed through the compiler
with `--witness`. Lisa and Lydia only report emptiness, and the LTL2SAT
model output is not parsed, so their verdicts are checked with
`--reference` against another route's decided records instead.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import statistics
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))

from aalta_baseline import alphabet_of, classify_export, export_command, proposition_names, verify
from ltl_scaling_study import run_command


SOLVERS = ("aalta", "black", "ltl2sat", "lisa", "lydia")
DEFAULT_BIN = Path.home() / "opt" / "ltlf-solvers" / "prefix" / "bin"

TOKEN = re.compile(r"\s+|->|[()!&|]|[A-Za-z_][A-Za-z0-9_]*")
OPERATORS = {"X", "N", "U", "R", "F", "G"}

# Per-solver spelling of each Aalta token that differs; everything else is kept.
SPELLING = {
    "aalta": {},
    "black": {"N": "wX", "true": "True", "false": "False"},
    "ltl2sat": {},
    "lisa": {"X": "X[!]", "N": "X"},
    "lydia": {"X": "X[!]", "N": "X"},
}


def translate(text: str, solver: str) -> str:
    """Rewrite Aalta-syntax LTLf into `solver`'s syntax, token by token."""
    spelling = SPELLING[solver]
    out: list[str] = []
    position = 0
    while position < len(text):
        match = TOKEN.match(text, position)
        if match is None:
            raise ValueError(f"unexpected character {text[position]!r} at offset {position}")
        token = match.group()
        position = match.end()
        if token in spelling:
            token = spelling[token]
        elif solver == "lydia" and token not in OPERATORS and token not in {"true", "false"} \
                and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", token) \
                and not re.fullmatch(r"[a-z_][a-z0-9_]*", token):
            token = f'"{token}"'  # Lydia's lexer only takes lowercase identifiers bare
        out.append(token)
    return "".join(out)


def check_propositions(alphabet: list[str]) -> None:
    clash = OPERATORS.intersection(proposition_names(alphabet))
    if clash:
        raise ValueError(f"propositions {sorted(clash)} collide with LTLf operator letters")


def solver_command(args: argparse.Namespace, workdir: Path) -> tuple[list[str], bool]:
    """The command line, and whether the formula goes on stdin (else `workdir/formula`)."""
    name = args.solver
    if name == "aalta":
        return [str(args.aaltaf), *args.aalta_args.split()] + (["-e"] if args.witness else []), True
    if name == "black":
        return [str(args.bin / "black"), "solve", "--finite"] + (["-m"] if args.witness else []) + ["-"], True
    if name == "lisa":
        return [str(args.bin / "lisa"), *args.lisa_args.split(), "-ltlf", str(workdir / "formula")], False
    if name == "lydia":
        return [str(args.bin / "lydia-empty")], True
    if name == "ltl2sat":
        # LTL2SAT's own timeout is disabled; the harness enforces the budget.
        return ["java", f"-Xmx{args.heap}", "-jar", str(args.ltl2sat_jar),
                "-t", str(10 ** 9), "-f", str(workdir / "formula")], False
    raise ValueError(name)


def classify_solver(solver: str, code: int, output: str) -> str:
    if code == -999:
        return "timeout"
    lower = output.lower()
    if code != 0 and solver != "ltl2sat":
        if any(token in lower for token in ("bad_alloc", "out of memory", "outofmemory", "memory exhausted")):
            return "size_limit"
        return "error"
    if solver in {"lisa", "lydia"}:
        if re.search(r"^(emptiness: )?nonempty\s*$", lower, re.MULTILINE):
            return "nonempty"
        if re.search(r"^(emptiness: )?empty\s*$", lower, re.MULTILINE):
            return "empty"
        return "unknown"
    if re.search(r"^\s*unsat(isfiable)?\b", lower, re.MULTILINE):
        return "empty"
    if re.search(r"^\s*sat(isfiable)?\b", lower, re.MULTILINE):
        return "nonempty"
    return "unknown"


# BLACK recurses once per nesting level and segfaults at macOS's 8 MB default
# stack on Y k=8000 (8000 nested X). Python cannot raise RLIMIT_STACK on macOS
# (setrlimit fails even below the hard limit), so the shell does it and execs
# the solver; if `ulimit` is refused the solver still runs on the default.
STACK_KB = 65520


def with_large_stack(command: list[str]) -> list[str]:
    return ["/bin/sh", "-c", f'ulimit -s {STACK_KB} 2>/dev/null; exec "$@"', "sh", *command]


def run_isolated(command: list[str], stdin_text: str | None, timeout: float, cwd: Path) -> tuple[int, float, str]:
    """Run in its own process group so a timeout also kills children (Lisa shells out to `mona`)."""
    start = time.monotonic()
    process = subprocess.Popen(
        with_large_stack(command),
        stdin=subprocess.PIPE if stdin_text is not None else subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        cwd=cwd,
        start_new_session=True,
    )
    try:
        output, _ = process.communicate(stdin_text, timeout=max(0.01, timeout))
        return process.returncode, time.monotonic() - start, output
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.communicate()
        return -999, time.monotonic() - start, ""


def parse_black_witness(output: str, alphabet: list[str]) -> list[str] | None:
    """Turn BLACK's `-m` model (`- t = 0: {a, ￢b}`) into the accepted source word.

    Same decoding as `aalta_baseline.parse_witness`: one true symbol per
    position until the symbol-free sentinel, read backwards.
    """
    to_symbol = {name: symbol for symbol, name in zip(alphabet, proposition_names(alphabet))}
    positions: list[str] = []
    # BLACK right-aligns the time step (`t =  0` once the model reaches t = 10).
    for match in re.finditer(r"^- t\s*=\s*\d+: \{(.*)\}\s*$", output, re.MULTILINE):
        literals = [token.strip() for token in match.group(1).split(",") if token.strip()]
        symbols = [to_symbol[token] for token in literals if token in to_symbol]
        if len(symbols) == 1:
            positions.append(symbols[0])
        elif not symbols:
            break
        else:
            return None
    return list(reversed(positions))


def parse_witness(solver: str, output: str, alphabet: list[str]) -> list[str] | None:
    if solver == "aalta":
        from aalta_baseline import parse_witness as parse_aalta_witness
        return parse_aalta_witness(output, alphabet)
    if solver == "black":
        return parse_black_witness(output, alphabet)
    return None


def measure(args: argparse.Namespace, input_path: Path) -> dict:
    record: dict = {
        "input": input_path.name,
        "solver": args.solver,
        "solver_flags": {"aalta": args.aalta_args, "lisa": args.lisa_args}.get(args.solver, ""),
        "bytes": input_path.stat().st_size,
        "sha256": hashlib.sha256(input_path.read_bytes()).hexdigest(),
        "timeout_seconds": args.timeout,
    }
    alphabet = alphabet_of(input_path)
    check_propositions(alphabet)
    exports: list[float] = []
    solves: list[float] = []
    totals: list[float] = []
    status = "unknown"
    text = ""
    for _ in range(args.repetitions):
        started = time.monotonic()
        code, export_wall, output = run_command(export_command(args.jar, input_path, args.heap), args.timeout)
        failure = classify_export(code, output)
        if failure is not None:
            record.update(status=failure, stage="export", detail=output.strip().splitlines()[-1:] or [])
            return record
        text = translate(output.strip().splitlines()[-1], args.solver)
        remaining = args.timeout - (time.monotonic() - started)
        if remaining <= 0:
            record.update(status="timeout", stage="export")
            return record
        workdir = Path(tempfile.mkdtemp(prefix=f"{args.solver}-"))
        try:
            command, on_stdin = solver_command(args, workdir)
            if not on_stdin:
                (workdir / "formula").write_text(text + "\n")
            code, solve_wall, solver_output = run_isolated(command, text + "\n" if on_stdin else None, remaining, workdir)
        finally:
            shutil.rmtree(workdir, ignore_errors=True)
        status = classify_solver(args.solver, code, solver_output)
        if status not in {"empty", "nonempty"}:
            record.update(status=status, stage="solve", export_seconds=export_wall,
                          detail=solver_output.strip().splitlines()[-3:])
            return record
        exports.append(export_wall)
        solves.append(solve_wall)
        totals.append(time.monotonic() - started)
        if args.witness and status == "nonempty" and "witness" not in record:
            witness = parse_witness(args.solver, solver_output, alphabet)
            if witness is not None or args.solver in {"aalta", "black"}:
                record["witness"] = witness
    record.update(
        status=status,
        formula_bytes=len(text),
        export_seconds=round(statistics.median(exports), 3),
        solve_seconds=round(statistics.median(solves), 3),
        wall_seconds=round(statistics.median(totals), 3),
        repetitions=len(totals),
    )
    return record


def natural_key(path: Path):
    """Sort `k-100` after `k-20`, so every family runs smallest first."""
    return [int(t) if t.isdigit() else t for t in re.split(r"(\d+)", path.name)]


def expand_inputs(paths: list[Path]) -> list[Path]:
    """Files as given; a directory contributes its *.ltl files, smallest instance first."""
    files: list[Path] = []
    for path in paths:
        files += sorted(path.glob("*.ltl"), key=natural_key) if path.is_dir() else [path]
    return files


def family_of(path: Path) -> str:
    return re.split(r"__(?:k|n|sigma)-\d", path.stem)[0]


def load_reference(path: Path | None) -> dict[str, str]:
    if path is None:
        return {}
    verdicts: dict[str, str] = {}
    for line in path.read_text().splitlines():
        if line.strip():
            record = json.loads(line)
            if record.get("status") in {"empty", "nonempty"}:
                verdicts[record["input"]] = record["status"]
    return verdicts


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("inputs", type=Path, nargs="+", help=".ltl/.brasp instances, or directories of .ltl files")
    parser.add_argument("--solver", choices=SOLVERS, required=True)
    parser.add_argument("--jar", type=Path, default=Path("target/scala-3.5.1/brasp-verification.jar"))
    parser.add_argument("--bin", type=Path, default=DEFAULT_BIN, help="directory holding black, lisa, lydia-empty")
    parser.add_argument("--aaltaf", type=Path, default=Path("../aaltaf/aaltaf"))
    parser.add_argument("--ltl2sat-jar", type=Path, default=DEFAULT_BIN.parent / "share" / "ltl2sat" / "LTL2SAT.jar",
                        help="LTL2SAT.jar; its glucose/ and Aalta/ helpers must sit next to it")
    parser.add_argument("--timeout", type=float, default=120)
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--heap", default="4g")
    parser.add_argument("--aalta-args", default="",
                        help="extra aaltaf flags, e.g. '-blsc' for its BLSC search instead of the default CDLSC")
    parser.add_argument("--lisa-args", default="",
                        help="extra Lisa flags, e.g. '-nap 1000' to keep Lisa on its Spot route instead of "
                             "MONA, which aborts past 65534 BDD variables (since/slb/mono at sigma=12)")
    parser.add_argument("--witness", action="store_true", help="ask Aalta/BLACK for a model and replay it")
    parser.add_argument("--reference", type=Path, help="records.jsonl whose decided verdicts must agree")
    parser.add_argument("--out", type=Path, help="write one JSON record per instance here (updated after each)")
    parser.add_argument("--skip-after-failure", action="store_true",
                        help="once an instance fails (timeout, size limit, error), record the rest of its "
                             "family as skipped; pair with smallest-first inputs such as a directory")
    args = parser.parse_args()
    args.jar = args.jar.resolve()
    args.bin = args.bin.resolve()
    args.aaltaf = args.aaltaf.resolve()
    args.ltl2sat_jar = args.ltl2sat_jar.resolve()

    reference = load_reference(args.reference)
    records = []
    failed: set[str] = set()
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
    for input_path in expand_inputs(args.inputs):
        if args.skip_after_failure and family_of(input_path) in failed:
            record = {"input": input_path.name, "solver": args.solver, "status": "skipped",
                      "reason": "a smaller instance of this family already failed"}
        else:
            record = measure(args, input_path)
            if args.witness and "witness" in record:
                record = verify(args, input_path, record)
            if record["input"] in reference and record.get("status") in {"empty", "nonempty"}:
                record["matches_reference"] = record["status"] == reference[record["input"]]
            if record["status"] not in {"empty", "nonempty"}:
                failed.add(family_of(input_path))
        records.append(record)
        print(json.dumps(record), flush=True)
        if args.out:
            args.out.write_text("".join(json.dumps(record) + "\n" for record in records))
    mismatches = [record["input"] for record in records if record.get("matches_reference") is False]
    if mismatches:
        print(f"verdict disagrees with reference on: {', '.join(mismatches)}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
