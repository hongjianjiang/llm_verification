"""Extract a B-RASP program from a real-valued `RealUhat`.

Unlike `uhat.extract`, which reads gates off a model that was already
Boolean, this has to bridge real vectors to Boolean predicates. The bridge is
the fact that makes masked UHAT star-free in the first place:

    with no position embeddings and a finite alphabet, the set of activation
    vectors reachable at each layer is finite.

Layer 0 ranges over `|Sigma| + 1` embeddings. A layer-`l+1` activation is
`ffn(c) + c` where `c = x_i + sum_h V_h x_{j_h}`, a function of the query's
class and one witness class per head -- so `|V_{l+1}| <= |V_l| * (|V_l|+1)^H`,
counting the "no witness" case that the BOS position takes. Closing over
*all* such tuples over-approximates the reachable set, which is sound: the
extracted program is correct on every word, and at worst carries classes that
no word reaches.

The other half is turning a real argmax into Boolean attention. Once
activations range over finitely many classes, `f_S` is a finite table over
class pairs. Sorting its distinct values descending gives score levels, and
"attend to the best-scoring position" becomes a priority cascade: try level 1,
then level 2, and so on, taking the rightmost (or leftmost) match at the first
level that has one. That is exactly what `C` does, which is why the trained
model folds tie-breaking into the score as `+/- eps * j` and the extracted
program drops it in favour of the direction.
"""

from __future__ import annotations

from dataclasses import dataclass

import torch

from . import brasp
from .real_model import RealUhat, RealUhatLayer

_TOLERANCE = 1e-6


class ExtractionError(RuntimeError):
    pass


def _dedupe(vectors: torch.Tensor, tolerance: float = _TOLERANCE) -> torch.Tensor:
    """Distinct rows, up to `tolerance`."""
    kept: list[torch.Tensor] = []
    for row in vectors:
        if not any(torch.allclose(row, other, atol=tolerance) for other in kept):
            kept.append(row)
    return torch.stack(kept) if kept else vectors[:0]


def _index_of(vector: torch.Tensor, table: torch.Tensor, tolerance: float = _TOLERANCE) -> int:
    for index, row in enumerate(table):
        if torch.allclose(vector, row, atol=tolerance):
            return index
    raise ExtractionError("activation vector escaped the closed value set")


@dataclass
class LayerClasses:
    """One layer's reachable activation values and how they are reached."""

    values: torch.Tensor
    """`(classes, width)`."""
    transition: dict[tuple[int, tuple[int | None, ...]], int]
    """`(query class, witness class per head) -> class`; `None` = no witness."""


def _apply_layer(
    layer: RealUhatLayer, query: torch.Tensor, witnesses: list[torch.Tensor | None]
) -> torch.Tensor:
    total = query.clone()
    for head, witness in zip(layer.heads, witnesses):
        if witness is not None:
            total = total + head.value(witness)
    return layer.ffn(total) + total


def close_layer(layer: RealUhatLayer, previous: torch.Tensor, cap: int = 20000) -> LayerClasses:
    """Every activation this layer can produce from `previous`'s classes."""
    heads = len(layer.heads)
    options: list[int | None] = [None] + list(range(len(previous)))
    combinations = len(previous) * len(options) ** heads
    if combinations > cap:
        raise ExtractionError(
            f"{combinations} class combinations exceeds the cap of {cap}; "
            "use fewer heads, fewer layers, or a smaller alphabet"
        )

    produced: list[torch.Tensor] = []
    tuples: list[tuple[int, tuple[int | None, ...]]] = []

    def recurse(chosen: list[int | None]):
        if len(chosen) == heads:
            for q in range(len(previous)):
                witnesses = [None if c is None else previous[c] for c in chosen]
                produced.append(_apply_layer(layer, previous[q], witnesses))
                tuples.append((q, tuple(chosen)))
            return
        for option in options:
            recurse(chosen + [option])

    with torch.no_grad():
        recurse([])
        stacked = torch.stack(produced)
        values = _dedupe(stacked)
        transition = {
            key: _index_of(vector, values) for key, vector in zip(tuples, stacked)
        }
    return LayerClasses(values, transition)


def score_levels(
    head, query_values: torch.Tensor, witness_values: torch.Tensor
) -> list[list[list[int]]]:
    """Per query class, witness classes grouped by score, best first.

    The `+/- eps * j` tie-break the model trains with is deliberately dropped:
    within one group every witness scores identically, so the group's winner is
    whichever the head's direction selects, which is what `rightmost`/
    `leftmost` mean in B-RASP.
    """
    with torch.no_grad():
        table = torch.relu(head.gate) * (query_values @ head.score @ witness_values.T)
    groups: list[list[list[int]]] = []
    for row in table:
        order: dict[float, list[int]] = {}
        for index, value in enumerate(row.tolist()):
            key = next((k for k in order if abs(k - value) <= 1e-9), value)
            order.setdefault(key, []).append(index)
        groups.append([order[k] for k in sorted(order, reverse=True)])
    return groups


def class_tables(model: RealUhat, cap: int = 20000) -> list[LayerClasses]:
    """Close every layer, bottom up, starting from the embeddings."""
    values = model.embedding.weight.detach()
    tables: list[LayerClasses] = []
    for block in model.blocks:
        table = close_layer(block, values, cap)
        tables.append(table)
        values = table.values
    return tables


def _bits(count: int) -> int:
    width = 1
    while (1 << width) < max(count, 2):
        width += 1
    return width


def _class_match(prefix: str, index: int, width: int, at: str) -> brasp.Expr:
    """`index`'s binary code as a conjunction of bit literals."""
    literals: list[brasp.Expr] = []
    for bit in range(width):
        reference = brasp.Ref(f"{prefix}_b{bit}", at)
        literals.append(reference if (index >> bit) & 1 else brasp.Not(reference))
    return brasp.conjunction(literals)


def build_program(model: RealUhat, cap: int = 20000) -> brasp.Program:
    """The B-RASP program a `RealUhat` computes.

    Classes are encoded in binary rather than one-hot: a head must publish
    *which* class it selected, and one attention op per (score level, class)
    is quadratic, while one per (score level, bit) is not.
    """
    alphabet = model.config.alphabet
    subprograms: list[brasp.Subprogram] = [brasp.Bos("is_bos")]
    symbol_names = []
    for index, symbol in enumerate(alphabet):
        name = f"is_{symbol}" if symbol.isalnum() and not symbol[0].isdigit() else f"is_sym{index}"
        symbol_names.append(name)
        subprograms.append(brasp.Symbol(name, symbol))

    with torch.no_grad():
        embeddings = model.embedding.weight.detach()
        base_values = _dedupe(embeddings)
        # token 0 is BOS, token 1+i is alphabet[i]
        token_class = [_index_of(row, base_values) for row in embeddings]

    tables = []
    values = base_values
    for block in model.blocks:
        table = close_layer(block, values, cap)
        tables.append(table)
        values = table.values

    # --- layer 0 bits, straight off the symbol indicators -------------------
    width0 = _bits(len(base_values))
    for bit in range(width0):
        members = [
            brasp.Ref("is_bos" if token == 0 else symbol_names[token - 1])
            for token, klass in enumerate(token_class)
            if (klass >> bit) & 1
        ]
        subprograms.append(brasp.BoolNode(f"L0_b{bit}", brasp.disjunction(members)))

    previous_values, previous_width, previous_prefix = base_values, width0, "L0"

    for depth, (block, table) in enumerate(zip(model.blocks, tables)):
        witness_prefixes = []
        for head_index, head in enumerate(block.heads):
            groups = score_levels(head, previous_values, previous_values)
            level_count = max(len(g) for g in groups)
            tag = f"L{depth}h{head_index}"

            hit_names, select_names = [], []
            for level in range(level_count):
                terms = []
                for query, levels in enumerate(groups):
                    if level >= len(levels):
                        continue
                    witnesses = brasp.disjunction(
                        [_class_match(previous_prefix, w, previous_width, "j")
                         for w in levels[level]]
                    )
                    terms.append(brasp.conjunction(
                        [_class_match(previous_prefix, query, previous_width, "i"), witnesses]
                    ))
                predicate = brasp.disjunction(terms)

                hit = f"{tag}_hit{level}"
                subprograms.append(brasp.Attention(hit, "rightmost", predicate, brasp.TRUE))
                hit_names.append(hit)

                bits = []
                for bit in range(previous_width):
                    name = f"{tag}_lvl{level}_b{bit}"
                    subprograms.append(brasp.Attention(
                        name, head.direction, predicate,
                        brasp.Ref(f"{previous_prefix}_b{bit}", "j"),
                    ))
                    bits.append(name)
                select_names.append(bits)

            # The winning level is the highest-scoring one that matched.
            first = []
            for level in range(level_count):
                first.append(brasp.conjunction(
                    [brasp.Ref(hit_names[level])]
                    + [brasp.Not(brasp.Ref(h)) for h in hit_names[:level]]
                ))
                subprograms.append(brasp.BoolNode(f"{tag}_first{level}", first[-1]))

            for bit in range(previous_width):
                subprograms.append(brasp.BoolNode(
                    f"{tag}_b{bit}",
                    brasp.disjunction([
                        brasp.conjunction([
                            brasp.Ref(f"{tag}_first{level}"),
                            brasp.Ref(select_names[level][bit]),
                        ])
                        for level in range(level_count)
                    ]),
                ))
            subprograms.append(brasp.BoolNode(
                f"{tag}_none",
                brasp.conjunction([brasp.Not(brasp.Ref(h)) for h in hit_names]),
            ))
            witness_prefixes.append(tag)

        # --- this layer's class bits, from the transition table -------------
        width = _bits(len(table.values))
        contributions: dict[int, list[brasp.Expr]] = {bit: [] for bit in range(width)}
        for (query, witnesses), produced in table.transition.items():
            literals = [_class_match(previous_prefix, query, previous_width, "i")]
            for tag, witness in zip(witness_prefixes, witnesses):
                if witness is None:
                    literals.append(brasp.Ref(f"{tag}_none"))
                else:
                    literals.append(brasp.Not(brasp.Ref(f"{tag}_none")))
                    literals.append(_class_match(tag, witness, previous_width, "i"))
            term = brasp.conjunction(literals)
            for bit in range(width):
                if (produced >> bit) & 1:
                    contributions[bit].append(term)
        for bit in range(width):
            subprograms.append(brasp.BoolNode(
                f"L{depth + 1}_b{bit}", brasp.disjunction(contributions[bit])
            ))
        previous_values, previous_width, previous_prefix = table.values, width, f"L{depth + 1}"

    with torch.no_grad():
        logits = model.readout(previous_values).squeeze(-1)
    accepting = [
        _class_match(previous_prefix, index, previous_width, "i")
        for index, value in enumerate(logits.tolist())
        if value > 0
    ]
    subprograms.append(brasp.BoolNode("accept", brasp.disjunction(accepting)))
    return brasp.Program(tuple(subprograms), "accept", alphabet)
