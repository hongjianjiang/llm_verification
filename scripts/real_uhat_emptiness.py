#!/usr/bin/env python3
"""Check emptiness of every extracted program in a real-UHAT study."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import time


def run_check(command: list[str], timeout: int) -> tuple[str, float, str]:
    started = time.monotonic()
    process = subprocess.Popen(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        start_new_session=True,
    )
    try:
        output, _ = process.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        output, _ = process.communicate()
        return "timeout", time.monotonic() - started, output

    if process.returncode:
        status = "error"
    elif "Property proved" in output:
        status = "empty"
    elif "was asserted" in output or "counter-example" in output:
        status = "nonempty"
    elif "unsupported" in output.lower():
        status = "unsupported"
    else:
        status = "unknown"
    return status, time.monotonic() - started, output


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--abc", type=Path, required=True)
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--index", type=int, default=0)
    parser.add_argument("--shards", type=int, default=1)
    args = parser.parse_args()

    destination = args.out / "emptiness"
    destination.mkdir(exist_ok=True)
    manifest = [
        json.loads(line)
        for line in (args.out / "manifest.jsonl").read_text().splitlines()
    ]
    jar = args.jar.resolve()
    abc = args.abc.resolve()

    for position, cell in enumerate(manifest):
        if position % args.shards != args.index:
            continue
        program = args.out / "programs" / f"{cell['id']}.brasp"
        result_path = destination / f"{cell['id']}.json"
        row = {
            "id": cell["id"],
            "task": cell["task"],
            "study": cell["study"],
            "status": "no_extracted_program",
        }
        if program.exists():
            command = [
                "java",
                "-Xmx4g",
                "-jar",
                str(jar),
                str(program.resolve()),
                "--run-abc",
                "--abc-bin",
                str(abc),
                "--abc-raw",
                "--timing",
            ]
            status, elapsed, output = run_check(command, args.timeout)
            row.update(
                status=status,
                seconds=elapsed,
                timeout=args.timeout,
                program_sha256=hashlib.sha256(program.read_bytes()).hexdigest(),
                jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
                abc_sha256=hashlib.sha256(abc.read_bytes()).hexdigest(),
                command=command,
                scope="Independent emptiness check of the extracted program.",
            )
            (destination / f"{cell['id']}.log").write_text(output)
        result_path.write_text(json.dumps(row, indent=2) + "\n")
        print(cell["id"], row["status"], flush=True)


if __name__ == "__main__":
    main()
