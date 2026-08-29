"""Reachable strict-past PLTL DFA construction, minimisation and monoids."""
from __future__ import annotations

from collections import deque
from dataclasses import dataclass
from itertools import product
from .pltl import *


@dataclass(frozen=True)
class DFA:
    alphabet: tuple[str, ...]
    initial: int
    accepting: frozenset[int]
    transitions: tuple[tuple[int, ...], ...]  # state × alphabet-index
    complete: bool = True

    def step(self, state: int, symbol: str) -> int:
        return self.transitions[state][self.alphabet.index(symbol)]

    def accepts(self, word: str | tuple[str, ...] | list[str]) -> bool:
        q = self.initial
        for symbol in word: q = self.step(q, symbol)
        return q in self.accepting

    @property
    def states(self) -> int: return len(self.transitions)

    def to_json(self) -> dict:
        return {"alphabet": list(self.alphabet), "initial": self.initial,
                "accepting": sorted(self.accepting), "transitions": [list(x) for x in self.transitions],
                "complete": self.complete}


def subformulas(formula: PFormula) -> tuple[PFormula, ...]:
    """Postorder, preserving shared equal nodes as one history bit."""
    result: list[PFormula] = []; seen: set[PFormula] = set()
    def visit(node: PFormula) -> None:
        if node in seen: return
        for child in pchildren(node): visit(child)
        seen.add(node); result.append(node)
    visit(formula)
    return tuple(result)


def compile_dfa(formula: PFormula, alphabet: tuple[str, ...] | list[str], cap: int = 200_000) -> DFA:
    """Compile with independent one-step recurrences for strict temporal ops.

    State zero represents the empty prefix.  It is never accepting, following
    the corpus's explicit Σ⁺ convention.
    """
    alphabet = tuple(alphabet)
    if not alphabet: raise ValueError("an alphabet needs at least one symbol")
    nodes = subformulas(formula); index = {node:i for i,node in enumerate(nodes)}
    empty = (False,) * len(nodes)

    def next_values(prev: tuple[bool,...], symbol: str, first: bool) -> tuple[bool,...]:
        now = [False] * len(nodes)
        for k,node in enumerate(nodes):
            match node:
                case PTop(): now[k] = True
                case PBot(): now[k] = False
                case PBOS(): now[k] = first
                case PLetter(s): now[k] = symbol == s
                case PBit(b): now[k] = symbol[b] == '1'
                case PNot(x): now[k] = not now[index[x]]
                case PAnd(x,y): now[k] = now[index[x]] and now[index[y]]
                case PY(x): now[k] = False if first else prev[index[x]]
                case PP(x): now[k] = False if first else (prev[index[x]] or prev[k])
                case PH(x): now[k] = True if first else (prev[index[x]] and prev[k])
                case PS(x,y): now[k] = False if first else (prev[index[y]] or (prev[index[x]] and prev[k]))
                case _: raise TypeError(node)
        return tuple(now)

    # `(False, empty)` is the only state with no consumed symbol.
    start = (False, empty)
    states = [start]; ids = {start:0}; work = deque([0]); rows: list[tuple[int,...]] = []
    accepting: set[int] = set()
    while work:
        q = work.popleft(); read, prev = states[q]
        row: list[int] = []
        for sym in alphabet:
            target = (True, next_values(prev, sym, not read))
            if target not in ids:
                if len(states) >= cap:
                    # A cap is observable; this partial machine must never be
                    # used for semantic claims, but allows corpus generation.
                    return DFA(alphabet, 0, frozenset(accepting), tuple(rows), complete=False)
                ids[target] = len(states); states.append(target); work.append(ids[target])
            row.append(ids[target])
        rows.append(tuple(row))
        if read and prev[index[formula]]: accepting.add(q)
    # all states were dequeued in numeric BFS order, so rows align with ids.
    return DFA(alphabet, 0, frozenset(accepting), tuple(rows), complete=True)


def minimize(dfa: DFA) -> DFA:
    if not dfa.complete: return dfa
    n = dfa.states; alpha = range(len(dfa.alphabet)); acc = set(dfa.accepting)
    blocks = [x for x in (acc, set(range(n))-acc) if x]
    changed = True
    while changed:
        changed = False; block_of = {q:i for i,b in enumerate(blocks) for q in b}; refined=[]
        for block in blocks:
            pieces: dict[tuple[int,...], set[int]] = {}
            for q in block:
                pieces.setdefault(tuple(block_of[dfa.transitions[q][a]] for a in alpha), set()).add(q)
            refined.extend(pieces.values())
            changed |= len(pieces)>1
        blocks = refined
    # Canonical numbering starts from the initial block, then BFS, keeping
    # serialised corpus files deterministic.
    block_of = {q:i for i,b in enumerate(blocks) for q in b}; initial_block = block_of[dfa.initial]
    order=[initial_block]; pending=deque([initial_block]); seen={initial_block}
    while pending:
        b=pending.popleft(); exemplar=next(iter(blocks[b]))
        for a in alpha:
            target=block_of[dfa.transitions[exemplar][a]]
            if target not in seen: seen.add(target); order.append(target); pending.append(target)
    new_id={b:i for i,b in enumerate(order)}
    rows=[]
    for b in order:
        q=next(iter(blocks[b])); rows.append(tuple(new_id[block_of[dfa.transitions[q][a]]] for a in alpha))
    new_acc=frozenset(new_id[b] for b in order if blocks[b] & acc)
    return DFA(dfa.alphabet, 0, new_acc, tuple(rows))


def transition_monoid(dfa: DFA, cap: int = 1_000_000) -> set[tuple[int,...]]:
    """Close letter transformations under composition, including identity."""
    if not dfa.complete: return set()
    identity=tuple(range(dfa.states)); generators=[tuple(row[a] for row in dfa.transitions) for a in range(len(dfa.alphabet))]
    monoid={identity}; work=deque([identity])
    while work:
        left=work.popleft()
        for right in generators:
            composed=tuple(right[left[q]] for q in range(dfa.states))
            if composed not in monoid:
                if len(monoid)>=cap: raise OverflowError("transition monoid cap exceeded")
                monoid.add(composed); work.append(composed)
    return monoid


def is_aperiodic(dfa: DFA, cap: int = 1_000_000) -> tuple[bool, int | None]:
    monoid=transition_monoid(dfa, cap)
    for element in monoid:
        power=element
        for k in range(1, dfa.states + 1):
            nxt=tuple(element[power[q]] for q in range(dfa.states))
            if power == nxt: break
            power=nxt
        else: return False, len(monoid)
    return True, len(monoid)


def dfa_for_recognizer(alphabet: tuple[str,...], states: int, initial: int, accepting: set[int], transition) -> DFA:
    return minimize(DFA(alphabet, initial, frozenset(accepting), tuple(tuple(transition(q,a) for a in alphabet) for q in range(states))))
