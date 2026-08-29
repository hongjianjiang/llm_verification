#!/usr/bin/env python3
"""Time one training step of `BooleanUhat` on a given device.

Answers "would a GPU help?" with numbers instead of a shape argument. The
configurations span the real range: the toy star-free tasks have short words
and small batches, `dot_depth` at larger k has both longer words and more
layers, and the pairwise attention predicates make the cost grow as
`batch * n^2 * terms * heads * layers`.

    python3 scripts/uhat_bench.py --device cpu
    python3 scripts/uhat_bench.py --device cuda
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

import torch
from torch import nn

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from uhat.model import BooleanUhat, UhatConfig  # noqa: E402

CONFIGS = [
    # label,                                   batch, n,  layers, heads, terms
    ("ends_ab        B=1023 N=17  L1 H2 T2", 1023, 17, 1, 2, 2),
    ("dot_depth k=3  B=1200 N=17  L2 H2 T2", 1200, 17, 2, 2, 2),
    ("dot_depth k=5  B=1500 N=25  L3 H2 T2", 1500, 25, 3, 2, 2),
    ("dot_depth k=8  B=2000 N=37  L4 H3 T2", 2000, 37, 4, 3, 2),
    ("stress         B=4096 N=64  L4 H4 T3", 4096, 64, 4, 4, 3),
]


def bench(device: str, batch: int, n: int, layers: int, heads: int, terms: int, steps: int = 20):
    torch.manual_seed(0)
    alphabet = ("a", "b")
    model = BooleanUhat(
        UhatConfig(alphabet, layers=layers, heads_per_layer=heads, terms=terms)
    ).to(device)
    tokens = torch.randint(1, 3, (batch, n), device=device)
    tokens[:, 0] = 0
    lengths = torch.full((batch,), n - 1, device=device)
    labels = torch.randint(0, 2, (batch,), device=device).float()
    optimiser = torch.optim.Adam(model.parameters(), lr=0.05)

    def one_step():
        acceptance, _ = model(tokens, lengths)
        loss = nn.functional.binary_cross_entropy(acceptance.clamp(1e-6, 1 - 1e-6), labels)
        optimiser.zero_grad()
        loss.backward()
        optimiser.step()

    for _ in range(5):  # warm up: allocator, autotuning, kernel load
        one_step()
    if device.startswith("cuda"):
        torch.cuda.synchronize()

    started = time.perf_counter()
    for _ in range(steps):
        one_step()
    if device.startswith("cuda"):
        torch.cuda.synchronize()
    return (time.perf_counter() - started) / steps


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--device", default="cpu")
    parser.add_argument("--steps", type=int, default=20)
    parser.add_argument(
        "--limit",
        type=int,
        help="run only the first N configs; the last one retains ~6GB of "
        "activations and is slow enough on CPU to eat a short time limit",
    )
    args = parser.parse_args()

    if args.device.startswith("cuda") and not torch.cuda.is_available():
        print("cuda requested but not available", file=sys.stderr)
        return 1

    name = args.device
    if args.device.startswith("cuda"):
        name = f"cuda ({torch.cuda.get_device_name(0)})"
    print(f"device: {name}   torch {torch.__version__}   threads {torch.get_num_threads()}")
    print(f"{'config':<40} {'pair elems':>12} {'ms/step':>10}")
    for label, batch, n, layers, heads, terms in CONFIGS[: args.limit]:
        seconds = bench(args.device, batch, n, layers, heads, terms, args.steps)
        elements = batch * n * n * terms * 2 * heads * layers
        print(f"{label:<40} {elements:>12,} {seconds * 1000:>9.1f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
