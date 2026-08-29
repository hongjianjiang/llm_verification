"""Turn a trained `BooleanUhat` into a B-RASP program.

Extraction is a read-off, not an approximation: taking the argmax of each
gate is exactly what `hard=True` does in the forward pass, so a model trained
to convergence in the hard regime and the program produced here compute the
same function by construction.  `verify_equivalence` checks that empirically
anyway, since a model that is *not* fully hardened would otherwise extract
silently and wrongly.
"""

from __future__ import annotations

from typing import Sequence

import torch

from . import brasp
from .model import IGNORE, POSITIVE, BooleanUhat, GatedDnf, symbol_feature_name


def _dnf_expression(
    module: GatedDnf, feature_names: Sequence[str], predicate: bool
) -> brasp.Expr:
    with torch.no_grad():
        choices = module.gate_probabilities(tau=1.0, hard=False).argmax(dim=-1)
    n_terms, n_sides, n_features = choices.shape
    terms = []
    for term in range(n_terms):
        literals: list[brasp.Expr] = []
        for side in range(n_sides):
            at = "j" if (predicate and side == 1) else "i"
            for feature in range(n_features):
                choice = int(choices[term, side, feature])
                if choice == IGNORE:
                    continue
                reference = brasp.Ref(feature_names[feature], at)
                literals.append(reference if choice == POSITIVE else brasp.Not(reference))
        terms.append(brasp.conjunction(literals))
    return brasp.disjunction(terms)


def extract(model: BooleanUhat) -> brasp.Program:
    config = model.config
    subprograms: list[brasp.Subprogram] = [brasp.Bos("is_bos")]
    subprograms += [
        brasp.Symbol(symbol_feature_name(index, symbol), symbol)
        for index, symbol in enumerate(config.alphabet)
    ]

    live = len(subprograms)
    for heads, names in zip(model.layers, model.layer_feature_names):
        visible = model.feature_names[:live]
        for head, name in zip(heads, names):
            subprograms.append(
                brasp.Attention(
                    name,
                    head.direction,
                    _dnf_expression(head.score, visible, predicate=True),
                    _dnf_expression(head.value, visible, predicate=True),
                )
            )
        live += len(names)

    subprograms.append(
        brasp.BoolNode("accept", _dnf_expression(model.output, model.feature_names, predicate=False))
    )
    return prune_attentions(brasp.Program(tuple(subprograms), "accept", config.alphabet))


def prune_attentions(program: brasp.Program) -> brasp.Program:
    """Drop attention heads the output does not depend on, keeping base nodes.

    The `bos` and `symbol` nodes stay even when unreferenced: downstream
    tooling reads the alphabet a program actually mentions, and dropping them
    would quietly shrink it.
    """
    pruned = brasp.prune(program)
    kept = {s.name for s in pruned.subprograms}
    subprograms = tuple(
        s
        for s in program.subprograms
        if isinstance(s, (brasp.Bos, brasp.Symbol)) or s.name in kept
    )
    return brasp.Program(subprograms, program.output, program.alphabet)


def encode(words: Sequence[Sequence[str]], alphabet: Sequence[str]) -> tuple[torch.Tensor, torch.Tensor]:
    """Pack words into `(tokens, lengths)`; index 0 is the BOS position."""
    index_of = {symbol: index for index, symbol in enumerate(alphabet)}
    width = max((len(word) for word in words), default=0) + 1
    tokens = torch.full((len(words), width), -1, dtype=torch.long)
    lengths = torch.tensor([len(word) for word in words], dtype=torch.long)
    for row, word in enumerate(words):
        tokens[row, 0] = 0
        for position, symbol in enumerate(word):
            tokens[row, position + 1] = index_of[symbol] + 1
    return tokens, lengths


def model_accepts(
    model: BooleanUhat, words: Sequence[Sequence[str]], hard: bool = True
) -> list[bool]:
    device = next(model.parameters()).device
    tokens, lengths = encode(words, model.config.alphabet)
    tokens, lengths = tokens.to(device), lengths.to(device)
    with torch.no_grad():
        acceptance, _ = model(tokens, lengths, tau=1.0, hard=hard)
    return [bool(value > 0.5) for value in acceptance]


def verify_equivalence(
    model: BooleanUhat, program: brasp.Program, words: Sequence[Sequence[str]]
) -> list[Sequence[str]]:
    """Words where the hardened model and the extracted program disagree."""
    from_model = model_accepts(model, words, hard=True)
    return [
        word
        for word, predicted in zip(words, from_model)
        if predicted != brasp.accepts(program, word)
    ]
