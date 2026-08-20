"""One-variable strict past LTL used by elimination and DFA compilation."""
from __future__ import annotations

from dataclasses import dataclass
from functools import cached_property


class PFormula:
    @cached_property
    def size(self) -> int:
        return 1 + sum(x.size for x in pchildren(self))

    @cached_property
    def temporal_depth(self) -> int:
        return max((x.temporal_depth for x in pchildren(self)), default=0) + int(isinstance(self, (PY, PP, PH, PS)))


@dataclass(frozen=True)
class PTop(PFormula): pass
@dataclass(frozen=True)
class PBot(PFormula): pass
@dataclass(frozen=True)
class PBOS(PFormula): pass
@dataclass(frozen=True)
class PLetter(PFormula): symbol: str
@dataclass(frozen=True)
class PNot(PFormula): arg: PFormula
@dataclass(frozen=True)
class PAnd(PFormula): left: PFormula; right: PFormula
@dataclass(frozen=True)
class PY(PFormula): arg: PFormula
@dataclass(frozen=True)
class PP(PFormula): arg: PFormula
@dataclass(frozen=True)
class PH(PFormula): arg: PFormula
@dataclass(frozen=True)
class PS(PFormula): left: PFormula; right: PFormula


def pchildren(node: PFormula) -> tuple[PFormula, ...]:
    if isinstance(node, PNot): return (node.arg,)
    if isinstance(node, PAnd): return (node.left, node.right)
    if isinstance(node, (PY, PP, PH)): return (node.arg,)
    if isinstance(node, PS): return (node.left, node.right)
    return ()


def pnot(arg: PFormula) -> PFormula:
    if isinstance(arg, PTop): return PBot()
    if isinstance(arg, PBot): return PTop()
    if isinstance(arg, PNot): return arg.arg
    return PNot(arg)


def pand(*args: PFormula) -> PFormula:
    values: list[PFormula] = []
    for arg in args:
        if isinstance(arg, PBot): return PBot()
        if not isinstance(arg, PTop): values.append(arg)
    if not values: return PTop()
    out = values[0]
    for arg in values[1:]: out = PAnd(out, arg)
    return out


def por(*args: PFormula) -> PFormula:
    return pnot(pand(*(pnot(x) for x in args)))


def pltl_ascii(node: PFormula) -> str:
    match node:
        case PTop(): return "TRUE"
        case PBot(): return "FALSE"
        case PBOS(): return "BOS"
        case PLetter(s): return repr(s)
        case PNot(x): return f"!({pltl_ascii(x)})"
        case PAnd(x,y): return f"({pltl_ascii(x)} & {pltl_ascii(y)})"
        case PY(x): return f"Y({pltl_ascii(x)})"
        case PP(x): return f"P({pltl_ascii(x)})"
        case PH(x): return f"H({pltl_ascii(x)})"
        case PS(x,y): return f"({pltl_ascii(x)}) S ({pltl_ascii(y)})"
    raise TypeError(node)


def eval_pltl(formula: PFormula, word: tuple[str, ...] | list[str], i: int | None = None) -> bool:
    """Independent direct evaluator for strict one-variable past LTL."""
    w = tuple(word); i = len(w) if i is None else i
    if not 1 <= i <= len(w): raise ValueError("one-based non-empty position required")
    from functools import lru_cache
    @lru_cache(maxsize=None)
    def go(node: PFormula, pos: int) -> bool:
        match node:
            case PTop(): return True
            case PBot(): return False
            case PBOS(): return pos == 1
            case PLetter(s): return w[pos-1] == s
            case PNot(x): return not go(x,pos)
            case PAnd(x,y): return go(x,pos) and go(y,pos)
            case PY(x): return pos > 1 and go(x,pos-1)
            case PP(x): return any(go(x,j) for j in range(1,pos))
            case PH(x): return all(go(x,j) for j in range(1,pos))
            case PS(x,y): return any(go(y,j) and all(go(x,k) for k in range(j+1,pos)) for j in range(1,pos))
        raise TypeError(node)
    return go(formula,i)

