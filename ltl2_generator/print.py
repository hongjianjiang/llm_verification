"""Stable ASCII, LaTeX, JSON, and Scala .ltl interchange printers."""
from __future__ import annotations
import sys, threading

from dataclasses import asdict, is_dataclass
from .ast import *



def run_deep(work):
    """Run `work()` with a stack deep enough for deeply-nested formulas.

    The AST dataclasses are `frozen=True`, so their generated `__hash__` --
    and every structural walk over them -- recurses once per nesting level.
    A chain of 500 past operators exceeds the interpreter's default stack
    long before it exceeds any other budget, so run such traversals on a
    thread with a large one rather than lowering what the families may
    express.
    """
    box: list = []
    def target() -> None:
        box.append(work())
    limit, size = sys.getrecursionlimit(), threading.stack_size()
    try:
        sys.setrecursionlimit(max(limit, 200_000))
        threading.stack_size(512 * 1024 * 1024)
        worker = threading.Thread(target=target)
        worker.start(); worker.join()
    finally:
        sys.setrecursionlimit(limit)
        try: threading.stack_size(size)
        except (ValueError, RuntimeError): pass
    if not box:
        raise RecursionError("formula too deeply nested to traverse")
    return box[0]


def ascii(node: Node) -> str:
    match node:
        case Top() | TopB(): return "T"
        case Bot() | BotB(): return "F"
        case BOS(): return "BOS"
        case Letter(symbol): return repr(symbol)
        case Bit(index): return f"bit({index})"
        case Not1(arg) | NotB(arg): return f"~({ascii(arg)})"
        case And1(l, r) | AndB(l, r): return f"({ascii(l)} & {ascii(r)})"
        case OrB(l, r): return f"({ascii(l)} | {ascii(r)})"
        case AtI(arg): return f"@i ({ascii(arg)})"
        case AtJ(arg): return f"@j ({ascii(arg)})"
        case Yst(mask): return f"Y[i,j]( {ascii(mask)} )"
        case Once(mask): return f"P[i,j]( {ascii(mask)} )"
        case Hist(mask): return f"H[i,j]( {ascii(mask)} )"
        case Since(left, right): return f"( {ascii(left)} ) S[i,j] ( {ascii(right)} )"
    raise TypeError(node)


def latex(node: Node) -> str:
    match node:
        case Top() | TopB(): return r"\top"
        case Bot() | BotB(): return r"\bot"
        case BOS(): return r"\pi_{\text{BOS}}"
        case Letter(symbol): return rf"\pi_{{{symbol}}}"
        case Bit(index): return rf"\beta_{{{index}}}"
        case Not1(arg) | NotB(arg): return rf"\neg({latex(arg)})"
        case And1(l,r) | AndB(l,r): return rf"({latex(l)} \land {latex(r)})"
        case OrB(l,r): return rf"({latex(l)} \lor {latex(r)})"
        case AtI(arg): return rf"{latex(arg)}(i)"
        case AtJ(arg): return rf"{latex(arg)}(j)"
        case Yst(mask): return rf"\mathbf{{Y}}^i_j({latex(mask)})"
        case Once(mask): return rf"\mathbf{{P}}^i_j({latex(mask)})"
        case Hist(mask): return rf"\mathbf{{H}}^i_j({latex(mask)})"
        case Since(l,r): return rf"({latex(l)})\,\mathbf{{S}}^i_j\,({latex(r)})"
    raise TypeError(node)


def json_ast(node: Node) -> dict:
    result = {"type": type(node).__name__}
    for key, value in vars(node).items():
        result[key] = json_ast(value) if isinstance(value, Node) else value
    return result


def brasp_ltl(formula: Unary, alphabet: list[str]) -> str:
    """Render the round-trippable strict-past `.ltl` format used by this repo.

    `Reference(name, @j)` is this project's re-anchoring primitive.  Every
    unary AST node therefore receives a named definition: rendering a nested
    unary expression inline would accidentally leave its temporal anchor at
    the outer ``i`` rather than the requested witness ``j``.
    """
    # Both the dict lookup below and `ref`'s own descent recurse once per
    # nesting level: the dataclasses are `frozen=True`, so their generated
    # `__hash__` walks the whole subtree.  Deep-nesting families (a chain of
    # 500 `Once`s) exceed the interpreter's default stack long before they
    # exceed anything else, so render on a thread with a large one.
    names: dict[Unary, str] = {}
    definitions: list[tuple[str, Unary]] = []

    def ref(node: Unary, pos: str) -> str:
        if node not in names:
            for child in children(node):
                if isinstance(child, Unary):
                    ref(child, "i")
                else:
                    collect_mask(child)
            names[node] = f"f_{len(names)}"
            definitions.append((names[node], node))
        return f"{names[node]}@{pos}"

    def collect_mask(node: Binary) -> None:
        match node:
            case AtI(arg) | AtJ(arg): ref(arg, "i")
            case NotB(arg): collect_mask(arg)
            case AndB(left, right) | OrB(left, right): collect_mask(left); collect_mask(right)

    def b(node: Binary) -> str:
        match node:
            case TopB(): return "true"
            case BotB(): return "false"
            case NotB(x): return f"!({b(x)})"
            case AndB(x,y): return f"({b(x)} & {b(y)})"
            case OrB(x,y): return f"({b(x)} | {b(y)})"
            case AtI(x): return ref(x, "i")
            case AtJ(x): return ref(x, "j")
        raise TypeError(node)

    def u(node: Unary) -> str:
        match node:
            case Top(): return "true"
            case Bot(): return "false"
            case BOS(): return "bos@i"
            case Letter(s): return f"sym({s})@i"
            case Bit(k): return f"bit({k})@i"
            case Not1(x): return f"!({ref(x, 'i')})"
            case And1(x,y): return f"({ref(x, 'i')} & {ref(y, 'i')})"
            case Yst(x): return f"Y({b(x)})"
            case Once(x): return f"P({b(x)})"
            case Hist(x): return f"H({b(x)})"
            case Since(x,y): return f"({b(x)}) S ({b(y)})"
        raise TypeError(node)
    def render_all() -> str:
        output = ref(formula, "i")
        lines = ["logic past-strict", "alphabet " + " ".join(alphabet), ""]
        lines.extend(f"{name} := {u(node)}" for name, node in definitions)
        lines.extend(["", "output := " + output,
                      "evaluate at i = |w| (the final input position)", ""])
        return "\n".join(lines)
    return run_deep(render_all)
