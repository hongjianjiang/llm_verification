"""Train a real-valued `RealUhat` and read a B-RASP program off it.

    python3 -m uhat.real_train --brasp examples/brasp/ends_ab.brasp --layers 1

Unlike `uhat.train`, which optimises a Boolean relaxation of B-RASP, this
trains the architecture of Definition 1 -- real embeddings, bilinear scores,
linear values, residual stream, ReLU feed-forward -- and recovers the program
afterwards by enumerating reachable activation classes.

Training is soft-then-hard: an early phase attends with `softmax(beta * s)`
so gradients reach every position, then a hard phase takes the argmax in the
forward pass and differentiates the softmax, with `beta` rising throughout so
the surrogate tightens onto the model that will actually be extracted.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import torch
from torch import nn

from . import brasp
from .programs import program_task
from .real_extract import ExtractionError, build_program, class_tables
from .real_model import RealUhat, RealUhatConfig, accepts, encode
from .tasks import datasets, resolve


def train_once(task, config, words, labels, steps, seed, beta_end=32.0, hard_fraction=0.4,
               lr=0.02, gate_penalty=0.01, verbose=True):
    torch.manual_seed(seed)
    model = RealUhat(config)
    tokens, lengths = encode(words, task.alphabet)
    target = torch.tensor(labels, dtype=torch.float32)
    positives = max(target.sum().item(), 1.0)
    weight = torch.where(target > 0.5, (len(target) - positives) / positives, torch.ones_like(target))
    optimiser = torch.optim.Adam(model.parameters(), lr=lr)

    hard_start = int(steps * (1 - hard_fraction))
    for step in range(steps):
        beta = 1.0 + (beta_end - 1.0) * (step / max(steps - 1, 1))
        logits = model(tokens, lengths, beta=beta, hard=step >= hard_start)
        loss = nn.functional.binary_cross_entropy_with_logits(logits, target, weight=weight)
        # Push score gates toward zero so exact ties -- and with them
        # "attend to the previous position" -- are actually reachable.
        gates = torch.stack([h.gate for b in model.blocks for h in b.heads])
        loss = loss + gate_penalty * torch.relu(gates).sum()
        optimiser.zero_grad()
        loss.backward()
        optimiser.step()
        if verbose and step % max(steps // 6, 1) == 0:
            with torch.no_grad():
                accuracy = ((model(tokens, lengths, hard=True) > 0).float() == target).float().mean()
            phase = "hard" if step >= hard_start else f"soft beta={beta:.1f}"
            print(f"  step {step:5d}  loss {loss.item():.4f}  hard-acc {accuracy:.4f}  [{phase}]",
                  flush=True)
    with torch.no_grad():
        accuracy = ((model(tokens, lengths, hard=True) > 0).float() == target).float().mean()
    return model, float(accuracy)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--task")
    parser.add_argument("--brasp", type=Path)
    parser.add_argument("--width", type=int, default=6)
    parser.add_argument("--layers", type=int, default=1)
    parser.add_argument("--heads", type=int, default=1)
    parser.add_argument("--directions", nargs="*", default=["rightmost"])
    parser.add_argument("--steps", type=int, default=1500)
    parser.add_argument("--restarts", type=int, default=4)
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--out", type=Path)
    parser.add_argument("--json-out", type=Path)
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args(argv)

    task = program_task(args.brasp) if args.brasp else resolve(args.task)
    train_words, test_words = datasets(task)
    train_labels = [task.label(w) for w in train_words]
    config = RealUhatConfig(
        alphabet=task.alphabet, width=args.width, layers=args.layers,
        heads=args.heads, directions=tuple(args.directions),
    )
    print(f"task {task.name}: {len(train_words)} train, {len(test_words)} test; "
          f"real UHAT d={args.width} layers={args.layers} heads={args.heads}")

    started = time.monotonic()
    best, best_accuracy = None, -1.0
    for restart in range(args.restarts):
        if not args.quiet:
            print(f"restart {restart + 1}/{args.restarts}")
        model, accuracy = train_once(
            task, config, train_words, train_labels, args.steps,
            args.seed + restart, verbose=not args.quiet,
        )
        if accuracy > best_accuracy:
            best, best_accuracy = model, accuracy
        if best_accuracy == 1.0:
            break
    elapsed = time.monotonic() - started
    print(f"\nbest hard-mode train accuracy {best_accuracy:.4f}  ({elapsed:.0f}s)")

    try:
        tables = class_tables(best)
        program = build_program(best)
    except ExtractionError as error:
        print(f"extraction failed: {error}", file=sys.stderr)
        return 1
    snap = [t.values for t in tables]
    print(f"activation classes per layer: {[len(t.values) for t in tables]}; "
          f"{len(program.subprograms)} B-RASP nodes")

    check = list(train_words) + list(test_words)
    from_model = accepts(best, check, snap_to=snap)
    from_program = [brasp.accepts(program, w) for w in check]
    disagreements = sum(a != b for a, b in zip(from_model, from_program))
    print(f"model/program agreement: {disagreements} disagreements"
          + ("" if disagreements else "  -- extraction is exact"))

    train_accuracy = sum(
        brasp.accepts(program, w) == task.label(w) for w in train_words
    ) / len(train_words)
    test_accuracy = sum(
        brasp.accepts(program, w) == task.label(w) for w in test_words
    ) / len(test_words)
    print(f"program accuracy: train {train_accuracy:.4f}, longer-word test {test_accuracy:.4f}")

    text = brasp.render(program)
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(text)
        print(f"wrote {args.out}")
    if args.json_out:
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(json.dumps({
            "task": task.name, "model": "real", "width": args.width,
            "layers": args.layers, "heads": args.heads,
            "classes": [len(t.values) for t in tables],
            "nodes": len(program.subprograms),
            "train_accuracy": round(train_accuracy, 6),
            "test_accuracy": round(test_accuracy, 6),
            "disagreements": disagreements, "seconds": round(elapsed, 1),
        }, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
