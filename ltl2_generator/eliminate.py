"""Exact per-operator type elimination from LTL₂ to one-variable past LTL."""
from __future__ import annotations

from itertools import product
from .ast import *
from .pltl import *


def eliminate(formula: Unary, *, optimised: bool = True) -> PFormula:
    """Eliminate the anchor variable using local maximal-consistent types.

    The direct type expansion applies to all strict temporal operators.  It is
    intentionally local to each mask, which prevents unrelated outer
    subformulas from multiplying the formula size.
    """
    def u(node: Unary) -> PFormula:
        match node:
            case Top(): return PTop()
            case Bot(): return PBot()
            case BOS(): return PBOS()
            case Letter(s): return PLetter(s)
            case Bit(k): return PBit(k)
            case Not1(x): return pnot(u(x))
            case And1(x,y): return pand(u(x),u(y))
            case Yst(mask): return expand_one(mask, PY)
            case Once(mask): return expand_one(mask, PP)
            case Hist(mask): return expand_one(mask, PH)
            case Since(left,right): return expand_two(left,right)
        raise TypeError(node)

    def i_nodes(mask: Binary) -> tuple[Unary, ...]:
        found: set[Unary] = set()
        def visit(node: Binary) -> None:
            if isinstance(node, AtI): found.add(node.arg)
            elif isinstance(node, (NotB,)): visit(node.arg)
            elif isinstance(node, (AndB, OrB)): visit(node.left); visit(node.right)
        visit(mask)
        return tuple(sorted(found, key=repr))

    def substitute(mask: Binary, assignment: dict[Unary, bool]) -> PFormula:
        match mask:
            case TopB(): return PTop()
            case BotB(): return PBot()
            case NotB(x): return pnot(substitute(x,assignment))
            case AndB(x,y): return pand(substitute(x,assignment), substitute(y,assignment))
            case OrB(x,y): return por(substitute(x,assignment), substitute(y,assignment))
            case AtI(x): return PTop() if assignment[x] else PBot()
            case AtJ(x): return u(x)
        raise TypeError(mask)

    def type_guard(nodes: tuple[Unary,...], assignment: dict[Unary,bool]) -> PFormula:
        # A positive letter determines all other letter atoms automatically.
        # This is the useful form of the per-operator DNF shortcut for the
        # symbolic-alphabet coupling formulas.
        if nodes and all(isinstance(node, Letter) for node in nodes):
            selected = [node for node in nodes if assignment[node]]
            if selected:
                return u(selected[0])
        return pand(*(u(x) if assignment[x] else pnot(u(x)) for x in nodes))

    def assignments(nodes: tuple[Unary,...]):
        # The formula's “types” are maximal *consistent* assignments, not all
        # arbitrary bit vectors.  Letter predicates at one position are
        # one-hot; enumerating 2^|Σ| valuations for them is both unsound as a
        # description of types and fatal to the coupling family at |Σ|=32.
        if nodes and all(isinstance(node, Letter) for node in nodes):
            for chosen in range(-1, len(nodes)):
                yield {node: index == chosen for index, node in enumerate(nodes)}
            return
        for bits in product((False,True), repeat=len(nodes)):
            yield dict(zip(nodes,bits))

    def expand_one(mask: Binary, op):
        nodes = i_nodes(mask)
        return por(*(pand(type_guard(nodes,a), op(substitute(mask,a))) for a in assignments(nodes)))

    def expand_two(left: Binary, right: Binary) -> PFormula:
        nodes = tuple(sorted(set(i_nodes(left)) | set(i_nodes(right)), key=repr))
        return por(*(pand(type_guard(nodes,a), PS(substitute(left,a), substitute(right,a))) for a in assignments(nodes)))

    return u(formula)


def elimination_metrics(formula: Unary) -> dict[str, int | float]:
    # The implementation already has the specified local per-operator
    # restriction.  Keeping both fields makes records future-proof if a global
    # baseline is added later.
    optimised = eliminate(formula, optimised=True)
    naive = eliminate(formula, optimised=False)
    return {"size_pltl": optimised.size, "size_pltl_optimised": optimised.size,
            "size_pltl_naive": naive.size, "blowup_ratio": round(optimised.size / formula.size, 6)}
