#!/usr/bin/env python3
"""Prepare the small-parameter UHAT study for the eight Figure 2 families.

The verification plot uses parameters as large as 64,000, which would require
equally deep UHATs for several families.  This script keeps the same family
definitions and builds a trainable grid at k in {1,2,4,8} or sigma in {2,4}.
The original BOS-induced empty ``mono`` family is replaced only in this
training study by ``mono_word``: the final symbol must be strictly greater
than every earlier *word* symbol.

Typical use:

    python3 scripts/uhat_figure2_study.py prepare
    sbatch --array=1-$(wc -l < results/uhat_figure2/grid.txt)%40 \
        scripts/uhat_sweep.slurm results/uhat_figure2/grid.txt \
        results/uhat_figure2/runs
    python3 scripts/uhat_sweep.py collect \
        --dir results/uhat_figure2/runs \
        --out results/uhat_figure2/sweep.csv
    python3 scripts/uhat_sweep.py best \
        --csv results/uhat_figure2/sweep.csv \
        --specs results/uhat_figure2/specs \
        --out results/uhat_figure2/best.csv
    python3 scripts/uhat_verify.py \
        --csv results/uhat_figure2/sweep.csv \
        --specs results/uhat_figure2/specs \
        --learned results/uhat_figure2/runs/programs \
        --out results/uhat_figure2/equivalence.csv

To train one strictly larger model per language after the architecture sweep:

    python3 scripts/uhat_figure2_study.py prepare-large
    sbatch -p gpu-rtx8000 --gres=gpu:1 --array=1-26%4 \
        scripts/uhat_sweep.slurm results/uhat_figure2_large/grid.txt \
        results/uhat_figure2_large/runs
"""

from __future__ import annotations

import argparse
import hashlib
import itertools
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from scripts.brasp_families import marked_same_program, marks_program  # noqa: E402
from uhat import brasp  # noqa: E402
from uhat.programs import describe  # noqa: E402
from uhat.tasks import resolve  # noqa: E402


DEPTH_PARAMETERS = (1, 2, 4, 8)
ALPHABET_PARAMETERS = (2, 4)
LMS_PARAMETERS = (1, 2, 4)


def _symbols(sigma: int) -> tuple[str, ...]:
    return tuple(f"s{index}" for index in range(sigma))


def _disjunction(terms: list[str]) -> str:
    return " | ".join(terms) if terms else "false"


def dot_program(k: int) -> str:
    alphabet = ("a", "b")
    lines = ["alphabet a b", "", "is_a = symbol a", "is_b = symbol b"]
    current = f"is_{alphabet[0]}"
    for index in range(1, k):
        lines.append(f"seen_{index} = rightmost({current}@j, true)")
        lines.append(f"prefix_{index + 1} = is_{alphabet[index % 2]} & seen_{index}")
        current = f"prefix_{index + 1}"
    lines.append(f"seen_final = rightmost({current}@j, true)")
    lines.append(f"accept = {current} | seen_final")
    lines += ["", "output accept"]
    return "\n".join(lines) + "\n"


def y_program(k: int) -> str:
    lines = ["alphabet a b", "", "is_a = symbol a"]
    current = "is_a"
    for depth in range(1, k + 1):
        lines.append(f"prev_{depth} = rightmost(true, {current}@j)")
        current = f"prev_{depth}"
    lines += ["", f"output {current}"]
    return "\n".join(lines) + "\n"


def no2a_program(k: int) -> str:
    lines = ["alphabet a b", "", "is_a = symbol a"]
    current = "is_a"
    for depth in range(1, k + 1):
        lines.append(f"prev_{depth} = rightmost(true, {current}@j)")
        current = f"prev_{depth}"
    lines.append(f"bad = is_a & {current}")
    lines.append("bad_before_end = rightmost(bad@j, true)")
    lines.append("accept = !bad_before_end")
    lines += ["", "output accept"]
    return "\n".join(lines) + "\n"


def slb_program(sigma: int) -> str:
    symbols = _symbols(sigma)
    lines = [f"alphabet {' '.join(symbols)}", ""]
    lines += [f"is_{symbol} = symbol {symbol}" for symbol in symbols]
    same = _disjunction([f"is_{symbol}@i & is_{symbol}@j" for symbol in symbols])
    lines += [f"accept = rightmost({same}, true)", "", "output accept"]
    return "\n".join(lines) + "\n"


def mono_word_program(sigma: int) -> str:
    """Final symbol exceeds every earlier word symbol; BOS is ignored."""
    symbols = _symbols(sigma)
    lines = [f"alphabet {' '.join(symbols)}", ""]
    lines += [f"is_{symbol} = symbol {symbol}" for symbol in symbols]
    violations = [
        f"is_{current}@i & is_{earlier}@j"
        for current_index, current in enumerate(symbols)
        for earlier in symbols[current_index:]
    ]
    lines.append(f"bad = rightmost({_disjunction(violations)}, true)")
    lines.append(f"is_word = {_disjunction([f'is_{symbol}' for symbol in symbols])}")
    lines.append("accept = is_word & !bad")
    lines += ["", "output accept"]
    return "\n".join(lines) + "\n"


def since_program(sigma: int) -> str:
    symbols = _symbols(sigma)
    alphabet = ("marker", *symbols)
    lines = [f"alphabet {' '.join(alphabet)}", "", "is_marker = symbol marker"]
    lines += [f"is_{symbol} = symbol {symbol}" for symbol in symbols]
    same = _disjunction([f"is_{symbol}@i & is_{symbol}@j" for symbol in symbols])
    lines.append(f"accept = rightmost(is_marker@j | !({same}), is_marker@j)")
    lines += ["", "output accept"]
    return "\n".join(lines) + "\n"


def lms_program(n: int) -> str:
    """LMS'02 Thm. 3.3 as blocks `sep b_0 ... b_n` (scripts/lms_blocks.py): any two
    blocks that agree on b_1..b_n agree on b_0. `rightmost(true, x@j)` is Y x and
    `!rightmost(x@j, true)` is H !x, as in the other families."""
    lines = ["alphabet b0 b1 sep", "", "is_bos = bos", "is_sep = symbol sep",
             "is_one = symbol b1", "d_0 = is_sep"]
    for k in range(1, n + 2):
        lines += [f"y_{k} = rightmost(true, d_{k - 1}@j)", f"d_{k} = !is_sep & y_{k}"]
    lines += [f"block_end = d_{n + 1}", "one_0 = is_one"]
    lines += [f"one_{m} = rightmost(true, one_{m - 1}@j)" for m in range(1, n + 1)]
    lines += [f"bit_{k} = one_{n - k}" for k in range(n + 1)]
    lines += ["prev_end = rightmost(true, block_end@j)",
              "prev_bos = rightmost(true, is_bos@j)",
              "in_block = " + " | ".join(f"d_{k}" for k in range(1, n + 2)),
              "good = (!is_sep | prev_bos | prev_end) & (is_sep | in_block)"]
    agree = lambda k: f"((bit_{k}@i & bit_{k}@j) | (!bit_{k}@i & !bit_{k}@j))"
    conflict = " & ".join(["block_end@j"] + [agree(k) for k in range(1, n + 1)] + [f"!{agree(0)}"])
    lines += [f"conflict = rightmost({conflict}, true)",
              "ok = !block_end | !conflict",
              "bad_before = rightmost(!is_bos@j & !good@j, true)",
              "not_ok_before = rightmost(!is_bos@j & !ok@j, true)",
              "accept = block_end & good & !bad_before & ok & !not_ok_before",
              "", "output accept"]
    return "\n".join(lines) + "\n"


def specifications() -> list[tuple[str, str, int, str]]:
    rows: list[tuple[str, str, int, str]] = []
    depth_generators = {
        "dot": dot_program,
        "y": y_program,
        "no2a": no2a_program,
        "marks": marks_program,
        "same": lambda k: marked_same_program(k, 2),
    }
    for family, generator in depth_generators.items():
        for parameter in DEPTH_PARAMETERS:
            rows.append((family, "k", parameter, generator(parameter)))
    alphabet_generators = {
        "slb": slb_program,
        "mono_word": mono_word_program,
        "since": since_program,
    }
    for family, generator in alphabet_generators.items():
        for parameter in ALPHABET_PARAMETERS:
            rows.append((family, "sigma", parameter, generator(parameter)))
    for parameter in LMS_PARAMETERS:
        rows.append(("lms", "n", parameter, lms_program(parameter)))
    return rows


def _validate(name: str, text: str) -> dict:
    program = brasp.parse(text)
    task = resolve(name)
    checked = 0
    exhaustive_upto = 5 if len(program.alphabet) <= 3 else 3
    for length in range(exhaustive_upto + 1):
        for word in itertools.product(program.alphabet, repeat=length):
            actual = brasp.accepts(program, list(word))
            expected = task.label(word)
            if actual != expected:
                raise AssertionError(
                    f"{name} disagrees on {''.join(word)!r}: program={actual}, task={expected}"
                )
            checked += 1
    # Exercise both constructed classes at the lengths used for training.
    from uhat.tasks import datasets

    train, test = datasets(task, train_samples=32, test_samples=64, seed=0)
    for word in train[-32:] + test:
        actual = brasp.accepts(program, list(word))
        expected = task.label(word)
        if actual != expected:
            raise AssertionError(
                f"{name} disagrees on sampled {''.join(word)!r}: program={actual}, task={expected}"
            )
        checked += 1
    rates = {
        "train_positive_rate": sum(task.label(word) for word in train) / len(train),
        "test_positive_rate": sum(task.label(word) for word in test) / len(test),
    }
    if not 0.20 <= rates["test_positive_rate"] <= 0.80:
        raise AssertionError(f"{name} has an unbalanced test set: {rates['test_positive_rate']:.1%}")
    return {"validated_words": checked, **rates}


def prepare(root: Path, heads: list[int], terms: list[int], families: list[str] | None = None,
            parameters: list[int] | None = None) -> int:
    specs = root / "specs"
    specs.mkdir(parents=True, exist_ok=True)
    manifest = []
    grid = []
    for family, axis, parameter, text in specifications():
        if families and family not in families:
            continue
        if parameters and parameter not in parameters:
            continue
        name = f"figure2_{family}__{axis}-{parameter}"
        path = specs / f"{name}.brasp"
        path.write_text(text)
        validation = _validate(name, text)
        facts = describe(path)
        manifest.append(
            {
                "family": family,
                "axis": axis,
                "parameter": parameter,
                "name": name,
                "spec": str(path),
                "sha256": hashlib.sha256(text.encode()).hexdigest(),
                **facts,
                **validation,
            }
        )
        for layers in sorted({facts["depth"], facts["depth"] + 1}):
            for head in heads:
                for term in terms:
                    grid.append(f"{path} {layers} {head} {term}")

    manifest.sort(key=lambda row: (row["family"], row["parameter"]))
    (root / "manifest.jsonl").write_text(
        "".join(json.dumps(row, sort_keys=True) + "\n" for row in manifest)
    )
    (root / "grid.txt").write_text("\n".join(grid) + "\n")
    (root / "README.md").write_text(
        "# Figure 2 family UHAT study\n\n"
        f"{len(manifest)} small-parameter languages and {len(grid)} architecture cells. "
        "The study uses k in {1,2,4,8} and sigma in {2,4}. "
        "`mono_word` repairs the BOS-induced empty control by comparing the final "
        "symbol only with earlier word symbols.\n\n"
        "```sh\n"
        f"sbatch -p cpu -A cpu --qos=cpu --array=1-{len(grid)}%40 "
        f"--output={root/'runs'/'logs'}/%A_%a.log scripts/uhat_sweep.slurm "
        f"{root/'grid.txt'} {root/'runs'}\n"
        f"python3 scripts/uhat_sweep.py collect --dir {root/'runs'} --out {root/'sweep.csv'}\n"
        f"python3 scripts/uhat_sweep.py best --csv {root/'sweep.csv'} --specs {root/'specs'} --out {root/'best.csv'}\n"
        f"python3 scripts/uhat_verify.py --csv {root/'sweep.csv'} --specs {root/'specs'} "
        f"--learned {root/'runs/programs'} --out {root/'equivalence.csv'}\n"
        "```\n"
    )
    print(f"wrote {len(manifest)} specifications and {len(grid)} cells under {root}")
    print(
        f"sbatch -p cpu -A cpu --qos=cpu --array=1-{len(grid)}%40 "
        f"--output={root/'runs'/'logs'}/%A_%a.log scripts/uhat_sweep.slurm "
        f"{root/'grid.txt'} {root/'runs'}"
    )
    return 0


def prepare_large(
    root: Path,
    base_root: Path,
    layer_extra: int,
    heads: int,
    terms: int,
) -> int:
    """Plan one larger architecture for every language in the base study."""
    base_manifest = base_root / "manifest.jsonl"
    if not base_manifest.exists():
        raise FileNotFoundError(
            f"no base manifest at {base_manifest}; run the prepare command first"
        )

    root.mkdir(parents=True, exist_ok=True)
    (root / "runs" / "logs").mkdir(parents=True, exist_ok=True)
    manifest = []
    grid = []
    for line in base_manifest.read_text().splitlines():
        row = json.loads(line)
        spec = Path(row["spec"])
        if not spec.exists():
            raise FileNotFoundError(f"missing specification {spec}")
        layers = int(row["depth"]) + layer_extra
        grid.append(f"{spec} {layers} {heads} {terms}")
        manifest.append(
            {
                **row,
                "base_depth": row["depth"],
                "layers": layers,
                "heads": heads,
                "terms": terms,
                "layer_extra": layer_extra,
            }
        )

    (root / "manifest.jsonl").write_text(
        "".join(json.dumps(row, sort_keys=True) + "\n" for row in manifest)
    )
    (root / "grid.txt").write_text("\n".join(grid) + "\n")
    (root / "README.md").write_text(
        "# Figure 2 larger-model UHAT study\n\n"
        f"One larger model for each of the {len(grid)} languages in "
        f"`{base_root}`: specification depth plus {layer_extra} layers, "
        f"{heads} heads per layer, and {terms} Boolean terms. Training keeps "
        "the main study protocol of three restarts and 1,500 steps.\n\n"
        "```sh\n"
        f"sbatch -p gpu-rtx8000 --gres=gpu:1 --array=1-{len(grid)}%4 "
        f"--output={root/'runs'/'logs'}/%A_%a.log scripts/uhat_sweep.slurm "
        f"{root/'grid.txt'} {root/'runs'}\n"
        "```\n"
    )
    print(f"wrote {len(grid)} larger-model cells under {root}")
    print(
        f"sbatch -p gpu-rtx8000 --gres=gpu:1 --array=1-{len(grid)}%4 "
        f"--output={root/'runs'/'logs'}/%A_%a.log scripts/uhat_sweep.slurm "
        f"{root/'grid.txt'} {root/'runs'}"
    )
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    subparsers = parser.add_subparsers(dest="command", required=True)
    command = subparsers.add_parser("prepare")
    command.add_argument("--root", type=Path, default=Path("results/uhat_figure2"))
    command.add_argument("--heads", nargs="*", type=int, default=[1, 2])
    command.add_argument("--terms", nargs="*", type=int, default=[1, 2])
    command.add_argument("--families", nargs="*", help="prepare only these families (default: all)")
    command.add_argument("--parameters", nargs="*", type=int,
                         help="keep only these k / sigma / n values (default: all)")
    large = subparsers.add_parser("prepare-large")
    large.add_argument(
        "--root", type=Path, default=Path("results/uhat_figure2_large")
    )
    large.add_argument(
        "--base-root", type=Path, default=Path("results/uhat_figure2")
    )
    large.add_argument("--layer-extra", type=int, default=2)
    large.add_argument("--heads", type=int, default=4)
    large.add_argument("--terms", type=int, default=4)
    args = parser.parse_args(argv)
    if args.command == "prepare":
        return prepare(args.root, args.heads, args.terms, args.families, args.parameters)
    return prepare_large(
        args.root, args.base_root, args.layer_extra, args.heads, args.terms
    )


if __name__ == "__main__":
    raise SystemExit(main())
