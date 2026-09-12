"""A real-valued masked unique-hard-attention transformer.

This is Definition 1 of Yang/Chiang (arXiv:2310.13897) as literally as a
trainable module can be, in contrast to `uhat.model`, which relaxes B-RASP:

    c_i = att(x_1..x_n)_i + x_i
    y_i = ffn(c_i) + c_i

with a bilinear score `f_S(x_i, x_j) = x_i^T W x_j`, a linear value
`f_V(x_j) = V x_j`, a 2-layer ReLU feed-forward network, strict past masking
`M(i,j) = (j < i)`, and unique hard attention: position `i` attends to the
single best-scoring unmasked position, ties broken leftmost or rightmost by
the function `C`. There are no position embeddings, which is what keeps the
recognised class at the star-free languages.

Two details carry the definition's fine print:

* **Tie-breaking is exact, by index.** An earlier version nudged scores by
  `+/- eps * j`, which is only faithful while `eps * n` stays under the
  smallest genuine score gap -- at random initialisation that bound was two
  positions. Instead `C` is implemented directly: leftmost takes the first
  maximal index, rightmost the last.
* **`U_i = {} => c_i = 0`.** Under strict past masking that is exactly the
  BOS position, which attends nowhere and contributes no attention term.

Training uses a straight-through estimator: the forward pass takes the hard
argmax, the backward pass differentiates `softmax(beta * scores)`, with
`beta` annealed upward so the surrogate tightens onto the hard model.
"""

from __future__ import annotations

from dataclasses import dataclass

import torch
from torch import nn

_NEG_INF = -1e9


@dataclass
class RealUhatConfig:
    alphabet: tuple[str, ...]
    width: int = 8
    """`d` in the definition."""
    layers: int = 2
    heads: int = 1
    ffn_multiplier: int = 2
    directions: tuple[str, ...] = ("rightmost",)
    """Cycled over the heads of each layer; `C` in the definition."""

    def head_direction(self, index: int) -> str:
        return self.directions[index % len(self.directions)]


class RealAttentionHead(nn.Module):
    def __init__(self, width: int, direction: str):
        super().__init__()
        if direction not in ("rightmost", "leftmost"):
            raise ValueError(f"unknown direction: {direction}")
        self.direction = direction
        self.score = nn.Parameter(torch.randn(width, width) / width**0.5)
        self.value = nn.Linear(width, width, bias=False)
        # Without position embeddings a score depends only on the *classes* of
        # i and j, so two positions holding the same symbol score identically
        # and only `C` separates them. "Attend to the previous position" is
        # therefore reachable only when every score ties -- a measure-zero
        # configuration that gradient descent approaches but never attains.
        # This gate makes it attainable: at `relu(gate) == 0` every score is
        # exactly equal and the tie-break alone chooses, which is the head
        # that recognises `ends_ab`.
        self.gate = nn.Parameter(torch.ones(()))

    def scores(self, x: torch.Tensor) -> torch.Tensor:
        """`(batch, i, j)` bilinear scores under strict past masking."""
        n = x.shape[1]
        raw = torch.relu(self.gate) * torch.einsum("bid,de,bje->bij", x, self.score, x)
        strict_past = torch.tril(torch.ones(n, n, device=x.device, dtype=torch.bool), -1)
        return raw.masked_fill(~strict_past.unsqueeze(0), _NEG_INF)

    def chosen(self, scores: torch.Tensor) -> torch.Tensor:
        """The index `C(B_i)` selects, `(batch, i, 1)`.

        `argmax` returns the *first* maximal index, which is leftmost; reversing
        the axis turns it into the last, which is rightmost. Both are exact on
        genuine ties, which is the point.
        """
        if self.direction == "leftmost":
            return scores.argmax(dim=-1, keepdim=True)
        n = scores.shape[-1]
        return n - 1 - scores.flip(-1).argmax(dim=-1, keepdim=True)

    def forward(self, x: torch.Tensor, beta: float, hard: bool) -> torch.Tensor:
        scores = self.scores(x)
        soft = torch.softmax(beta * scores, dim=-1)
        if hard:
            index = self.chosen(scores)
            onehot = torch.zeros_like(soft).scatter_(-1, index, 1.0)
            weights = onehot + soft - soft.detach()  # straight-through
        else:
            weights = soft

        # U_i empty -- under strict past masking, exactly position 0 -- must
        # contribute nothing rather than a softmax over an empty set.
        n = x.shape[1]
        has_witness = (torch.arange(n, device=x.device) > 0).to(x.dtype)
        weights = weights * has_witness.view(1, n, 1)
        return self.value(torch.einsum("bij,bjd->bid", weights, x))


class RealUhatLayer(nn.Module):
    def __init__(self, config: RealUhatConfig):
        super().__init__()
        self.heads = nn.ModuleList(
            RealAttentionHead(config.width, config.head_direction(h))
            for h in range(config.heads)
        )
        hidden = config.width * config.ffn_multiplier
        self.ffn = nn.Sequential(
            nn.Linear(config.width, hidden), nn.ReLU(), nn.Linear(hidden, config.width)
        )

    def forward(self, x: torch.Tensor, beta: float, hard: bool) -> torch.Tensor:
        attended = sum(head(x, beta, hard) for head in self.heads)
        c = attended + x
        return self.ffn(c) + c


class RealUhat(nn.Module):
    """Masked hard-attention transformer; index 0 of every input is BOS."""

    def __init__(self, config: RealUhatConfig):
        super().__init__()
        self.config = config
        # One embedding per symbol plus BOS; no position embeddings, by design.
        self.embedding = nn.Embedding(len(config.alphabet) + 1, config.width)
        self.blocks = nn.ModuleList(RealUhatLayer(config) for _ in range(config.layers))
        self.readout = nn.Linear(config.width, 1)

    def activations(
        self,
        tokens: torch.Tensor,
        beta: float = 1.0,
        hard: bool = True,
        snap_to: list[torch.Tensor] | None = None,
    ) -> list[torch.Tensor]:
        """Every layer's activations, `[emb, layer_1, ..., layer_k]`.

        `snap_to` replaces each layer's output with its nearest class
        representative. Positions sharing a class then hold bitwise identical
        vectors, so equal scores really are equal and index tie-breaking is
        exact -- which is what lets the extracted program match this model
        rather than merely approximate it.
        """
        x = self.embedding(tokens.clamp(min=0))
        states = [x]
        for depth, block in enumerate(self.blocks):
            x = block(x, beta, hard)
            if snap_to is not None:
                table = snap_to[depth]
                nearest = torch.cdist(x.reshape(-1, x.shape[-1]), table).argmin(dim=-1)
                x = table[nearest].reshape(x.shape)
            states.append(x)
        return states

    def forward(
        self,
        tokens: torch.Tensor,
        lengths: torch.Tensor,
        beta: float = 1.0,
        hard: bool = True,
        snap_to: list[torch.Tensor] | None = None,
    ) -> torch.Tensor:
        """Logit at the final input position; the language is `logit > 0`."""
        x = self.activations(tokens, beta, hard, snap_to)[-1]
        index = lengths.view(-1, 1, 1).expand(-1, 1, x.shape[-1])
        final = x.gather(1, index).squeeze(1)
        return self.readout(final).squeeze(-1)


def encode(words, alphabet, device="cpu") -> tuple[torch.Tensor, torch.Tensor]:
    """`(tokens, lengths)`; token 0 is BOS, symbol `s` is `1 + index(s)`."""
    order = {symbol: index for index, symbol in enumerate(alphabet)}
    width = max((len(word) for word in words), default=0) + 1
    tokens = torch.zeros(len(words), width, dtype=torch.long, device=device)
    lengths = torch.tensor([len(w) for w in words], dtype=torch.long, device=device)
    for row, word in enumerate(words):
        for position, symbol in enumerate(word):
            tokens[row, position + 1] = order[symbol] + 1
    return tokens, lengths


def accepts(model: RealUhat, words, device="cpu", snap_to=None) -> list[bool]:
    tokens, lengths = encode(words, model.config.alphabet, device)
    with torch.no_grad():
        logits = model(tokens, lengths, hard=True, snap_to=snap_to)
    return [bool(v > 0) for v in logits]
