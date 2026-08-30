"""Train a Boolean-bottleneck UHAT, read a B-RASP program off it, check both.

    python3 -m uhat.train --task ends_ab --layers 1 --heads 2 \
        --out examples/brasp/learned__ends_ab.brasp \
        --jar target/scala-3.5.1/brasp-verification.jar

Training runs in two phases.  The soft phase relaxes the gate choices and
pushes intermediate activations toward 0/1; the hard phase switches to argmax
gates and straight-through binarised activations, so the last stretch of
training optimises the discrete program that will actually be extracted.
That is what makes extraction faithful rather than hopeful.
"""

from __future__ import annotations

import argparse
import json
import random
import subprocess
import sys
import time
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Sequence

import torch
from torch import nn

from . import brasp
from .extract import encode, extract, model_accepts, verify_equivalence
from .model import BooleanUhat, Schedule, UhatConfig
from .programs import program_task
from .tasks import TASKS, Task, datasets, resolve

_EPS = 1e-6


@dataclass
class Fit:
    model: BooleanUhat
    hard_accuracy: float
    loss: float
    seed: int


def _labels(task: Task, words: Sequence[Sequence[str]]) -> torch.Tensor:
    return torch.tensor([float(task.label(word)) for word in words])


def _accuracy(predicted: Sequence[bool], labels: torch.Tensor) -> float:
    correct = sum(int(p) == int(l) for p, l in zip(predicted, labels.tolist()))
    return correct / max(len(predicted), 1)


def train_once(
    task: Task,
    config: UhatConfig,
    schedule: Schedule,
    words: Sequence[Sequence[str]],
    seed: int,
    verbose: bool = True,
    device: str = "cpu",
) -> Fit:
    torch.manual_seed(seed)
    random.seed(seed)

    model = BooleanUhat(config).to(device)
    tokens, lengths = encode(words, task.alphabet)
    tokens, lengths = tokens.to(device), lengths.to(device)
    labels = _labels(task, words).to(device)

    positives = labels.sum().clamp(min=1.0)
    negatives = (1 - labels).sum().clamp(min=1.0)
    weights = torch.where(labels > 0.5, negatives / positives, torch.ones_like(labels))

    base_width = 1 + len(task.alphabet)
    valid = torch.arange(tokens.shape[1], device=device).unsqueeze(0) <= lengths.unsqueeze(1)

    hard_start = int(schedule.steps * (1 - schedule.hard_fraction))
    optimiser = torch.optim.Adam(model.parameters(), lr=schedule.lr)

    for step in range(schedule.steps):
        hard = step >= hard_start
        if step == hard_start:
            optimiser = torch.optim.Adam(model.parameters(), lr=schedule.hard_lr)
        hold = schedule.tau_hold * hard_start
        progress = min(max(step - hold, 0.0) / max(hard_start - hold, 1.0), 1.0)
        tau = schedule.tau_start + (schedule.tau_end - schedule.tau_start) * progress

        if schedule.batch and schedule.batch < len(words):
            rows = torch.randint(0, len(words), (schedule.batch,), device=device)
        else:
            rows = slice(None)

        acceptance, features = model(tokens[rows], lengths[rows], tau=tau, hard=hard)
        loss = nn.functional.binary_cross_entropy(
            acceptance.clamp(_EPS, 1 - _EPS), labels[rows], weight=weights[rows]
        )
        if not hard and features.shape[-1] > base_width:
            derived = features[..., base_width:]
            mask = valid[rows].unsqueeze(-1).float()
            crispness = (4 * derived * (1 - derived) * mask).sum() / mask.sum().clamp(min=1.0)
            loss = loss + schedule.binarisation_weight * crispness
        if not hard:
            loss = loss + schedule.entropy_weight * progress * model.gate_entropy(tau)

        optimiser.zero_grad()
        loss.backward()
        optimiser.step()

        if verbose and (step % schedule.log_every == 0 or step == schedule.steps - 1):
            with torch.no_grad():
                hard_acc = _accuracy(model_accepts(model, words), labels)
            phase = "hard" if hard else f"soft tau={tau:.2f}"
            print(
                    f"  step {step:5d}  loss {loss.item():.4f}  hard-acc {hard_acc:.4f}  [{phase}]",
                    flush=True,
                )

    final = _accuracy(model_accepts(model, words), labels)
    return Fit(model, final, float(loss.item()), seed)


def fit_best(
    task: Task,
    config: UhatConfig,
    schedule: Schedule,
    words: Sequence[Sequence[str]],
    verbose: bool = True,
    device: str = "cpu",
) -> Fit:
    best: Fit | None = None
    for restart in range(schedule.restarts):
        seed = schedule.seed + restart
        if verbose:
            print(f"restart {restart + 1}/{schedule.restarts} (seed {seed})", flush=True)
        fit = train_once(task, config, schedule, words, seed, verbose, device)
        if best is None or (fit.hard_accuracy, -fit.loss) > (best.hard_accuracy, -best.loss):
            best = fit
        if best.hard_accuracy == 1.0:
            break
    assert best is not None
    return best


def jar_check(
    jar: str, path: Path, program: brasp.Program, words: Sequence[Sequence[str]]
) -> list[str]:
    """Round-trip a sample of words through the Scala evaluator."""
    mismatches = []
    for word in words:
        if not word:
            continue  # `--word ''` has no spelling on the command line
        text = "".join(word) if all(len(s) == 1 for s in word) else " ".join(word)
        result = subprocess.run(
            ["java", "-jar", jar, str(path), "--word", text],
            capture_output=True,
            text=True,
            check=True,
        )
        answer = result.stdout.strip() == "true"
        if answer != brasp.accepts(program, word):
            mismatches.append(text)
    return mismatches


def _enumerate_budget(alphabet: Sequence[str], requested: int, budget: int = 1200) -> int:
    size = len(alphabet)
    upto = requested
    while upto > 0 and sum(size**k for k in range(upto + 1)) > budget:
        upto -= 1
    return upto


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--task",
        default="ends_ab",
        help="a name from uhat.tasks.TASKS, or dot_depth__k-<K>__sigma-<S>",
    )
    parser.add_argument(
        "--brasp",
        type=Path,
        help="train against a .brasp program instead of a built-in task; the "
        "program labels the words, so the result can be checked against it "
        "with --equivalent",
    )
    parser.add_argument("--layers", type=int, default=2)
    parser.add_argument("--heads", type=int, default=2)
    parser.add_argument("--terms", type=int, default=2)
    parser.add_argument("--steps", type=int, default=3000)
    parser.add_argument("--restarts", type=int, default=3)
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--lr", type=float, default=0.05)
    parser.add_argument("--hard-lr", type=float, default=0.01)
    parser.add_argument("--hard-fraction", type=float, default=0.25)
    parser.add_argument("--enumerate-upto", type=int, default=8)
    parser.add_argument("--batch", type=int, default=0, help="0 = full batch")
    parser.add_argument("--out", type=Path, help="write the extracted .brasp program here")
    parser.add_argument("--json-out", type=Path, help="write one machine-readable result record here")
    parser.add_argument("--jar", help="brasp-verification.jar, for a round-trip check")
    parser.add_argument(
        "--device",
        default="cpu",
        help="cpu or cuda; cuda is 3.5x faster on the small tasks and 30x on "
        "the long-word dot_depth cells (measured, A100 vs 4 cpu cores)",
    )
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args(argv)

    if args.brasp:
        task = program_task(args.brasp)
    else:
        try:
            task = resolve(args.task)
        except KeyError as error:
            parser.error(str(error).strip("'"))
    verbose = not args.quiet
    # A task that carries its own length plan knows better than the CLI default.
    upto = (
        task.lengths[0]
        if task.lengths is not None
        else _enumerate_budget(task.alphabet, args.enumerate_upto)
    )
    train_words, test_words = datasets(task, enumerate_upto=upto, seed=args.seed)

    config = UhatConfig(
        alphabet=task.alphabet,
        layers=args.layers,
        heads_per_layer=args.heads,
        terms=args.terms,
    )
    schedule = Schedule(
        steps=args.steps,
        hard_fraction=args.hard_fraction,
        lr=args.lr,
        hard_lr=args.hard_lr,
        seed=args.seed,
        batch=args.batch,
        restarts=args.restarts,
    )

    print(
        f"task {task.name} ({'star-free' if task.star_free else 'NOT star-free'}), "
        f"alphabet {{{', '.join(task.alphabet)}}}, "
        f"{len(train_words)} train words (len <= {upto} enumerated + samples), "
        f"{len(test_words)} longer test words"
    )
    started = time.monotonic()
    fit = fit_best(task, config, schedule, train_words, verbose, args.device)
    elapsed = time.monotonic() - started
    print(f"\nbest restart: seed {fit.seed}, hard-mode train accuracy {fit.hard_accuracy:.4f}")

    program = extract(fit.model)
    text = brasp.render(program)
    print("\n--- extracted B-RASP -------------------------------------------")
    print(text.rstrip())
    print("----------------------------------------------------------------\n")

    disagreements = verify_equivalence(fit.model, program, list(train_words) + list(test_words))
    print(f"model/program agreement: {len(disagreements)} disagreements", end="")
    if disagreements:
        print(f"  (e.g. {''.join(disagreements[0]) or '<empty>'})")
    else:
        print("  -- extraction is exact")

    train_accuracy = _accuracy(
        [brasp.accepts(program, w) for w in train_words], _labels(task, train_words)
    )
    test_accuracy = _accuracy(
        [brasp.accepts(program, w) for w in test_words], _labels(task, test_words)
    )
    print(f"program accuracy: train {train_accuracy:.4f}, longer-word test {test_accuracy:.4f}")

    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(text)
        print(f"wrote {args.out}")

    if args.json_out:
        attention_ops = sum(
            1 for s in program.subprograms if isinstance(s, brasp.Attention)
        )
        record = {
            "task": task.name,
            # Which definition produced these numbers. A built-in task and a
            # .brasp spec of the same language share a name but not a sampler
            # or length plan, so a config chosen on one does not transfer to
            # the other; without this the two collapse in the sweep CSV.
            "source": str(args.brasp) if args.brasp else "",
            "star_free": task.star_free,
            "layers": args.layers,
            "heads": args.heads,
            "terms": args.terms,
            "steps": args.steps,
            "restarts": args.restarts,
            "seed": args.seed,
            "best_seed": fit.seed,
            "model_hard_accuracy": round(fit.hard_accuracy, 6),
            "train_accuracy": round(train_accuracy, 6),
            "test_accuracy": round(test_accuracy, 6),
            "test_positive_rate": round(float(_labels(task, test_words).mean()), 6),
            "test_words": len(test_words),
            "disagreements": len(disagreements),
            "attention_ops": attention_ops,
            "seconds": round(elapsed, 1),
            "program": text,
        }
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(json.dumps(record, indent=2) + "\n")
        print(f"wrote {args.json_out}")

    if args.jar:
        path = args.out
        if path is None:
            path = Path("uhat_extracted.brasp")
            path.write_text(text)
        rng = random.Random(args.seed)
        sample = rng.sample(list(test_words), min(8, len(test_words)))
        mismatches = jar_check(args.jar, path, program, sample)
        print(f"jar round-trip on {len(sample)} words: {len(mismatches)} mismatches")
        if mismatches:
            print(f"  first mismatch: {mismatches[0]}")
            return 1

    return 0 if (not disagreements and test_accuracy == 1.0) else 0


if __name__ == "__main__":
    sys.exit(main())
