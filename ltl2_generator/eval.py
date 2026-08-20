"""Deliberately direct O(|formula| n²) semantic oracle for LTL₂."""
from __future__ import annotations

from functools import lru_cache
from .ast import *


def eval1(formula: Unary, word: tuple[str, ...] | list[str], i: int) -> bool:
    """Evaluate at one-based position *i*.  The empty word is not in scope."""
    w = tuple(word)
    if not 1 <= i <= len(w):
        raise ValueError("LTL₂ positions are one-based positions in a non-empty word")

    @lru_cache(maxsize=None)
    def one(node: Unary, pos: int) -> bool:
        match node:
            case Top(): return True
            case Bot(): return False
            case BOS(): return pos == 1
            case Letter(symbol): return w[pos - 1] == symbol
            case Not1(arg): return not one(arg, pos)
            case And1(left, right): return one(left, pos) and one(right, pos)
            case Yst(mask): return pos > 1 and two(mask, pos, pos - 1)
            case Once(mask): return any(two(mask, pos, j) for j in range(1, pos))
            case Hist(mask): return all(two(mask, pos, j) for j in range(1, pos))
            case Since(left, right):
                return any(two(right, pos, j) and all(two(left, pos, k) for k in range(j + 1, pos)) for j in range(1, pos))
        raise TypeError(node)

    @lru_cache(maxsize=None)
    def two(node: Binary, i_pos: int, j_pos: int) -> bool:
        match node:
            case TopB(): return True
            case BotB(): return False
            case NotB(arg): return not two(arg, i_pos, j_pos)
            case AndB(left, right): return two(left, i_pos, j_pos) and two(right, i_pos, j_pos)
            case OrB(left, right): return two(left, i_pos, j_pos) or two(right, i_pos, j_pos)
            case AtI(arg): return one(arg, i_pos)
            case AtJ(arg): return one(arg, j_pos)
        raise TypeError(node)

    return one(formula, i)


def eval2(formula: Binary, word: tuple[str, ...] | list[str], i: int, j: int) -> bool:
    """Public binary evaluator, useful for focused strictness tests."""
    return _eval2_direct(formula, tuple(word), i, j)


def _eval2_direct(formula: Binary, w: tuple[str, ...], i: int, j: int) -> bool:
    @lru_cache(maxsize=None)
    def one(node: Unary, pos: int) -> bool:
        match node:
            case Top(): return True
            case Bot(): return False
            case BOS(): return pos == 1
            case Letter(symbol): return w[pos - 1] == symbol
            case Not1(arg): return not one(arg, pos)
            case And1(left, right): return one(left, pos) and one(right, pos)
            case Yst(mask): return pos > 1 and two(mask, pos, pos-1)
            case Once(mask): return any(two(mask, pos, x) for x in range(1, pos))
            case Hist(mask): return all(two(mask, pos, x) for x in range(1, pos))
            case Since(left, right): return any(two(right,pos,x) and all(two(left,pos,k) for k in range(x+1,pos)) for x in range(1,pos))
        raise TypeError(node)
    @lru_cache(maxsize=None)
    def two(node: Binary, x: int, y: int) -> bool:
        match node:
            case TopB(): return True
            case BotB(): return False
            case NotB(arg): return not two(arg,x,y)
            case AndB(left,right): return two(left,x,y) and two(right,x,y)
            case OrB(left,right): return two(left,x,y) or two(right,x,y)
            case AtI(arg): return one(arg,x)
            case AtJ(arg): return one(arg,y)
        raise TypeError(node)
    return two(formula, i, j)


def accepts(formula: Unary, word: tuple[str, ...] | list[str] | str) -> bool:
    w = tuple(word)
    return bool(w) and eval1(formula, w, len(w))
