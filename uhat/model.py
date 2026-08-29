"""A trainable unique-hard-attention transformer with a Boolean bottleneck.

Every activation in this model is a single Boolean per position, and every
score / value / combination function is a small DNF over the Booleans
available at that layer.  That is exactly the shape of a B-RASP subprogram,
so a hardened model *is* a B-RASP program rather than something that has to
be approximated by one (see `uhat.extract`).

The relaxation used for training is exact rather than merely smooth.  Writing
`s_j` for the probability that position `j` satisfies a head's score
predicate, the probability that `j` is the *rightmost* satisfying position is

    w_j = s_j * prod_{j < k < i} (1 - s_k)

and the probability that nothing matches is `prod_{k < i} (1 - s_k)`, whose
contribution is `false`.  When the `s_j` are 0/1 this is precisely the
rightmost-match semantics of `Brasp.evaluate`; in between it is
differentiable, with closed-form suffix products.  So no temperature
annealing is needed on the attention itself -- only on the gates that decide
which literals appear in each DNF.
"""

from __future__ import annotations

from dataclasses import dataclass, field

import torch
from torch import nn

IGNORE = 0
POSITIVE = 1
NEGATIVE = 2

# Softmax bias applied to the "ignore" gate at initialisation, and the noise
# on the two polarity gates.  The bias keeps the AND-products from
# underflowing before any structure is found; the noise is deliberately large,
# because the gradient reaching a derived feature is proportional to
# `p_positive - p_negative` for the gate reading it, and a symmetric
# initialisation makes that ~0 -- heads then stay invisible to the layers
# above them and never get trained.
_IGNORE_INIT = 1.5
_POLARITY_NOISE = 0.5

_EPS = 1e-6


def symbol_feature_name(index: int, symbol: str) -> str:
    """A `.brasp` identifier for the "position holds `symbol`" feature."""
    return f"is_{symbol}" if symbol.isalnum() and not symbol[0].isdigit() else f"is_sym{index}"


@dataclass
class UhatConfig:
    alphabet: tuple[str, ...]
    layers: int = 2
    heads_per_layer: int = 2
    terms: int = 2
    """DNF terms per score / value / output predicate (1 = plain conjunction)."""
    directions: tuple[str, ...] = ("rightmost", "leftmost")
    """Cycled over the heads of each layer."""

    def head_direction(self, index: int) -> str:
        return self.directions[index % len(self.directions)]


@dataclass
class Schedule:
    steps: int = 3000
    hard_fraction: float = 0.25
    """Final fraction of training run with argmax gates and binarised features."""
    tau_start: float = 1.0
    tau_end: float = 0.05
    tau_hold: float = 0.5
    """Fraction of the soft phase held at `tau_start` before annealing begins.

    Annealing early is actively harmful: it sharpens the softmax around the
    initialisation, which is mostly "ignore", and freezes the search before
    any head has been recruited.
    """
    entropy_weight: float = 0.02
    """Pressure toward one-hot gates, so the soft and hard phases agree."""
    lr: float = 0.05
    hard_lr: float = 0.01
    binarisation_weight: float = 0.0
    """Pressure on derived features toward 0/1.  Off by default, and measured:

    at 0.05 it takes `ends_ab` from 6/6 restarts solved to 1/6.  It bites
    before a head carries any information, freezing it at a constant, and
    the hard phase discretises the features anyway.
    """
    seed: int = 0
    batch: int = 0
    """0 = full batch."""
    restarts: int = 1
    log_every: int = 250
    extra: dict = field(default_factory=dict)


def _straight_through(hard: torch.Tensor, soft: torch.Tensor) -> torch.Tensor:
    return hard + soft - soft.detach()


def binarise(x: torch.Tensor) -> torch.Tensor:
    """Round to 0/1 in the forward pass, identity in the backward pass."""
    return _straight_through((x > 0.5).to(x.dtype), x)


class GatedDnf(nn.Module):
    """`OR` of `terms` conjunctions of gated literals over the live features.

    Each (term, side, feature) triple owns a 3-way choice: omit the feature,
    use it positively, or use it negated.  `side` is the position the literal
    reads -- `i` (the query) or, for predicates used inside an attention op,
    `j` (the witness).
    """

    def __init__(self, n_features: int, n_terms: int, use_j: bool):
        super().__init__()
        self.use_j = use_j
        sides = 2 if use_j else 1
        logits = torch.randn(n_terms, sides, n_features, 3) * _POLARITY_NOISE
        logits[..., IGNORE] += _IGNORE_INIT
        self.logits = nn.Parameter(logits)

    def gate_probabilities(self, tau: float, hard: bool) -> torch.Tensor:
        soft = torch.softmax(self.logits / tau, dim=-1)
        if not hard:
            return soft
        index = soft.argmax(dim=-1, keepdim=True)
        return _straight_through(torch.zeros_like(soft).scatter_(-1, index, 1.0), soft)

    def gate_entropy(self, tau: float) -> torch.Tensor:
        p = torch.softmax(self.logits / tau, dim=-1)
        return -(p * (p + 1e-9).log()).sum(dim=-1).mean()

    def _side_terms(self, x: torch.Tensor, probabilities: torch.Tensor, side: int) -> torch.Tensor:
        """Per-position product of one side's literals -> `(batch, n, terms)`."""
        p = probabilities[:, side]  # (terms, features, 3)
        value = x.unsqueeze(-2)  # (batch, n, 1, features)
        literal = p[..., IGNORE] + p[..., POSITIVE] * value + p[..., NEGATIVE] * (1 - value)
        return literal.prod(dim=-1)  # (batch, n, terms)

    def forward_query(self, x: torch.Tensor, tau: float, hard: bool) -> torch.Tensor:
        """`(batch, n)` truth of an `i`-only predicate."""
        assert not self.use_j
        terms = self._side_terms(x, self.gate_probabilities(tau, hard), 0)
        return 1 - (1 - terms).prod(dim=-1)

    def forward_pair(self, x: torch.Tensor, tau: float, hard: bool) -> torch.Tensor:
        """`(batch, n, n)` truth of a predicate over the query `i` and witness `j`."""
        assert self.use_j
        probabilities = self.gate_probabilities(tau, hard)
        query = self._side_terms(x, probabilities, 0)  # (batch, i, terms)
        witness = self._side_terms(x, probabilities, 1)  # (batch, j, terms)
        terms = query.unsqueeze(2) * witness.unsqueeze(1)  # (batch, i, j, terms)
        return 1 - (1 - terms).prod(dim=-1)


class HardAttentionHead(nn.Module):
    def __init__(self, n_features: int, n_terms: int, direction: str):
        super().__init__()
        if direction not in ("rightmost", "leftmost"):
            raise ValueError(f"unknown direction: {direction}")
        self.direction = direction
        self.score = GatedDnf(n_features, n_terms, use_j=True)
        self.value = GatedDnf(n_features, n_terms, use_j=True)

    def forward(self, x: torch.Tensor, valid: torch.Tensor, tau: float, hard: bool) -> torch.Tensor:
        n = x.shape[1]
        score = self.score.forward_pair(x, tau, hard)
        value = self.value.forward_pair(x, tau, hard)

        strict_past = torch.tril(torch.ones(n, n, device=x.device, dtype=x.dtype), diagonal=-1)
        score = score * strict_past * valid.unsqueeze(1).to(x.dtype)

        # Masked-out witnesses contribute a factor of 1, so they drop out of
        # the products below without any further bookkeeping.
        complement = (1 - score).clamp(min=_EPS)
        if self.direction == "rightmost":
            suffix = complement.flip(-1).cumprod(-1).flip(-1)  # prod over k >= j
            others = torch.cat([suffix[..., 1:], torch.ones_like(suffix[..., :1])], dim=-1)
        else:
            prefix = complement.cumprod(-1)  # prod over k <= j
            others = torch.cat([torch.ones_like(prefix[..., :1]), prefix[..., :-1]], dim=-1)

        weight = score * others
        return (weight * value).sum(dim=-1)


class BooleanUhat(nn.Module):
    """Masked (strict-past) unique-hard-attention transformer, Boolean valued."""

    def __init__(self, config: UhatConfig):
        super().__init__()
        self.config = config
        self.feature_names: list[str] = ["is_bos"] + [
            symbol_feature_name(index, symbol) for index, symbol in enumerate(config.alphabet)
        ]
        self.layer_feature_names: list[list[str]] = []

        self.layers = nn.ModuleList()
        width = len(self.feature_names)
        for layer_index in range(config.layers):
            heads = nn.ModuleList(
                HardAttentionHead(width, config.terms, config.head_direction(head_index))
                for head_index in range(config.heads_per_layer)
            )
            self.layers.append(heads)
            names = [f"h{layer_index + 1}_{k + 1}" for k in range(config.heads_per_layer)]
            self.layer_feature_names.append(names)
            self.feature_names += names
            width += config.heads_per_layer
        self.output = GatedDnf(width, config.terms, use_j=False)

    def gate_entropy(self, tau: float) -> torch.Tensor:
        """Mean gate entropy over every predicate; 0 once all gates are one-hot."""
        modules = [m for m in self.modules() if isinstance(m, GatedDnf)]
        return torch.stack([m.gate_entropy(tau) for m in modules]).mean()

    def base_features(self, tokens: torch.Tensor) -> torch.Tensor:
        """`tokens`: `(batch, n)` with 0 = BOS marker, `1 + id` for a symbol, -1 for padding."""
        n = tokens.shape[1]
        positions = torch.arange(n, device=tokens.device).unsqueeze(0)
        columns = [(positions.expand_as(tokens) == 0)]
        columns += [tokens == index + 1 for index in range(len(self.config.alphabet))]
        return torch.stack(columns, dim=-1).float()

    def forward(
        self,
        tokens: torch.Tensor,
        lengths: torch.Tensor,
        tau: float = 1.0,
        hard: bool = False,
    ) -> tuple[torch.Tensor, torch.Tensor]:
        """Returns `(acceptance, all_features)`; acceptance is read at the last position."""
        n = tokens.shape[1]
        valid = torch.arange(n, device=tokens.device).unsqueeze(0) <= lengths.unsqueeze(1)
        x = self.base_features(tokens)
        for heads in self.layers:
            outputs = [head(x, valid, tau, hard) for head in heads]
            stacked = torch.stack(outputs, dim=-1)
            if hard:
                stacked = binarise(stacked)
            x = torch.cat([x, stacked], dim=-1)
        per_position = self.output.forward_query(x, tau, hard)
        acceptance = per_position.gather(1, lengths.unsqueeze(1)).squeeze(1)
        return acceptance, x
