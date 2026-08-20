"""Immutable abstract syntax trees for the two sorts of strict past LTL₂."""
from __future__ import annotations

from dataclasses import dataclass
from functools import cached_property
from typing import Iterable, Union


class Node:
    @cached_property
    def alphabet(self) -> frozenset[str]:
        out: set[str] = set(); stack = [self]
        while stack:
            node = stack.pop()
            if isinstance(node, Letter): out.add(node.symbol)
            stack.extend(children(node))
        return frozenset(out)

    @cached_property
    def temporal_depth(self) -> int:
        values: dict[int, int] = {}; stack: list[tuple[Node,bool]] = [(self,False)]
        while stack:
            node, done = stack.pop()
            if done:
                values[id(node)] = max((values[id(c)] for c in children(node)), default=0) + int(isinstance(node,(Yst,Once,Hist,Since)))
            else:
                stack.append((node,True)); stack.extend((c,False) for c in children(node))
        return values[id(self)]

    @cached_property
    def y_depth(self) -> int:
        values: dict[int, int] = {}; stack: list[tuple[Node,bool]] = [(self,False)]
        while stack:
            node, done = stack.pop()
            if done: values[id(node)] = max((values[id(c)] for c in children(node)), default=0) + int(isinstance(node,Yst))
            else: stack.append((node,True)); stack.extend((c,False) for c in children(node))
        return values[id(self)]

    @cached_property
    def size(self) -> int:
        total = 0; stack = [self]
        while stack:
            node = stack.pop(); total += 1; stack.extend(children(node))
        return total


class Unary(Node):
    pass


class Binary(Node):
    pass


@dataclass(frozen=True)
class Top(Unary): pass
@dataclass(frozen=True)
class Bot(Unary): pass
@dataclass(frozen=True)
class BOS(Unary): pass
@dataclass(frozen=True)
class Letter(Unary):
    symbol: str
@dataclass(frozen=True)
class Not1(Unary):
    arg: Unary
@dataclass(frozen=True)
class And1(Unary):
    left: Unary
    right: Unary
@dataclass(frozen=True)
class Yst(Unary):
    mask: Binary
@dataclass(frozen=True)
class Once(Unary):
    mask: Binary
@dataclass(frozen=True)
class Hist(Unary):
    mask: Binary
@dataclass(frozen=True)
class Since(Unary):
    left: Binary
    right: Binary


@dataclass(frozen=True)
class TopB(Binary): pass
@dataclass(frozen=True)
class BotB(Binary): pass
@dataclass(frozen=True)
class NotB(Binary):
    arg: Binary
@dataclass(frozen=True)
class AndB(Binary):
    left: Binary
    right: Binary
@dataclass(frozen=True)
class OrB(Binary):
    left: Binary
    right: Binary
@dataclass(frozen=True)
class AtI(Binary):
    arg: Unary
@dataclass(frozen=True)
class AtJ(Binary):
    arg: Unary


def children(node: Node) -> tuple[Node, ...]:
    if isinstance(node, (Not1, NotB)):
        return (node.arg,)
    if isinstance(node, (And1, AndB, OrB)):
        return (node.left, node.right)
    if isinstance(node, (Yst, Once, Hist)):
        return (node.mask,)
    if isinstance(node, Since):
        return (node.left, node.right)
    if isinstance(node, (AtI, AtJ)):
        return (node.arg,)
    return ()


def _letters(node: Node) -> Iterable[str]:
    if isinstance(node, Letter):
        yield node.symbol
    for child in children(node):
        yield from _letters(child)


def _temporal_depth(node: Node) -> int:
    child_depth = max((_temporal_depth(c) for c in children(node)), default=0)
    return child_depth + int(isinstance(node, (Yst, Once, Hist, Since)))


def _y_depth(node: Node) -> int:
    child_depth = max((_y_depth(c) for c in children(node)), default=0)
    return child_depth + int(isinstance(node, Yst))


def uses_two_variable(node: Node) -> bool:
    """Whether a temporal mask actually mentions both its anchor and witness."""
    stack=[node]
    while stack:
        current=stack.pop()
        if isinstance(current, (Yst, Once, Hist, Since)):
            masks = (current.mask,) if not isinstance(current, Since) else (current.left, current.right)
            if any(_has_i(m) and _has_j(m) for m in masks): return True
        stack.extend(children(current))
    return False


def _has_i(node: Node) -> bool:
    stack=[node]
    while stack:
        current=stack.pop()
        if isinstance(current, AtI): return True
        stack.extend(children(current))
    return False


def _has_j(node: Node) -> bool:
    stack=[node]
    while stack:
        current=stack.pop()
        if isinstance(current, AtJ): return True
        stack.extend(children(current))
    return False


def not1(arg: Unary) -> Unary:
    if isinstance(arg, Top): return Bot()
    if isinstance(arg, Bot): return Top()
    if isinstance(arg, Not1): return arg.arg
    return Not1(arg)


def and1(*args: Unary) -> Unary:
    flat: list[Unary] = []
    for arg in args:
        if isinstance(arg, Bot): return Bot()
        if isinstance(arg, Top): continue
        flat.append(arg)
    if not flat: return Top()
    result = flat[0]
    for arg in flat[1:]: result = And1(result, arg)
    return result


def or1(*args: Unary) -> Unary:
    return not1(and1(*(not1(a) for a in args)))


def notb(arg: Binary) -> Binary:
    if isinstance(arg, TopB): return BotB()
    if isinstance(arg, BotB): return TopB()
    if isinstance(arg, NotB): return arg.arg
    return NotB(arg)


def andb(*args: Binary) -> Binary:
    flat: list[Binary] = []
    for arg in args:
        if isinstance(arg, BotB): return BotB()
        if isinstance(arg, TopB): continue
        flat.append(arg)
    if not flat: return TopB()
    result = flat[0]
    for arg in flat[1:]: result = AndB(result, arg)
    return result


def orb(*args: Binary) -> Binary:
    return notb(andb(*(notb(a) for a in args)))
