"""B-RASP abstract syntax, `.brasp` renderer, and a reference evaluator.

This mirrors `src/main/scala/brasp/Brasp.scala` and
`src/main/scala/brasp/BraspText.scala` closely enough that a program built
here round-trips through the Scala front end unchanged: position 0 is the
BOS position, ordinary word index `r` is B-RASP position `r + 1`, attention
is strict-past, and an attention op whose score matches nothing yields
`false`.
"""

from __future__ import annotations

import re
import sys
import threading
from dataclasses import dataclass
from typing import Sequence

# --- Boolean expressions ---------------------------------------------------


@dataclass(frozen=True)
class Const:
    value: bool


@dataclass(frozen=True)
class Ref:
    name: str
    at: str = "i"  # "i" or "j"; "j" only legal inside an attention argument


@dataclass(frozen=True)
class Not:
    arg: "Expr"


@dataclass(frozen=True)
class And:
    args: tuple["Expr", ...]


@dataclass(frozen=True)
class Or:
    args: tuple["Expr", ...]


Expr = Const | Ref | Not | And | Or

TRUE = Const(True)
FALSE = Const(False)


def conjunction(operands: Sequence[Expr]) -> Expr:
    """`And` with constant folding, and with contradictory literals collapsed."""
    flat: list[Expr] = []
    for operand in operands:
        if isinstance(operand, Const):
            if not operand.value:
                return FALSE
            continue
        if isinstance(operand, And):
            flat.extend(operand.args)
        else:
            flat.append(operand)
    unique: list[Expr] = []
    for operand in flat:
        if operand not in unique:
            unique.append(operand)
    for operand in unique:
        if Not(operand) in unique:
            return FALSE
    if not unique:
        return TRUE
    if len(unique) == 1:
        return unique[0]
    return And(tuple(unique))


def disjunction(operands: Sequence[Expr]) -> Expr:
    """`Or` with constant folding."""
    flat: list[Expr] = []
    for operand in operands:
        if isinstance(operand, Const):
            if operand.value:
                return TRUE
            continue
        if isinstance(operand, Or):
            flat.extend(operand.args)
        else:
            flat.append(operand)
    unique: list[Expr] = []
    for operand in flat:
        if operand not in unique:
            unique.append(operand)
    if not unique:
        return FALSE
    if len(unique) == 1:
        return unique[0]
    return Or(tuple(unique))


def references(expression: Expr) -> set[str]:
    match expression:
        case Const():
            return set()
        case Ref(name, _):
            return {name}
        case Not(arg):
            return references(arg)
        case And(args) | Or(args):
            return set().union(*(references(a) for a in args)) if args else set()
    raise TypeError(expression)


# --- Subprograms -----------------------------------------------------------


@dataclass(frozen=True)
class Bos:
    name: str


@dataclass(frozen=True)
class Symbol:
    name: str
    symbol: str


@dataclass(frozen=True)
class BoolNode:
    name: str
    expr: Expr


@dataclass(frozen=True)
class Attention:
    name: str
    direction: str  # "rightmost" | "leftmost"
    score: Expr
    value: Expr


Subprogram = Bos | Symbol | BoolNode | Attention


@dataclass(frozen=True)
class Program:
    subprograms: tuple[Subprogram, ...]
    output: str
    alphabet: tuple[str, ...]


def subprogram_references(subprogram: Subprogram) -> set[str]:
    match subprogram:
        case Bos() | Symbol():
            return set()
        case BoolNode(_, expr):
            return references(expr)
        case Attention(_, _, score, value):
            return references(score) | references(value)
    raise TypeError(subprogram)


def prune(program: Program) -> Program:
    """Drop subprograms the output does not transitively depend on."""
    by_name = {s.name: s for s in program.subprograms}
    live = {program.output}
    frontier = [program.output]
    while frontier:
        current = frontier.pop()
        for name in subprogram_references(by_name[current]):
            if name not in live:
                live.add(name)
                frontier.append(name)
    kept = tuple(s for s in program.subprograms if s.name in live)
    return Program(kept, program.output, program.alphabet)


# --- `.brasp` rendering ----------------------------------------------------

_PRECEDENCE = {Or: 0, And: 1, Not: 2, Const: 3, Ref: 3}


def render_expr(expression: Expr, predicate: bool, min_precedence: int = 0) -> str:
    match expression:
        case Const(value):
            body = "true" if value else "false"
        case Ref(name, at):
            body = f"{name}@{at}" if predicate else name
        case Not(arg):
            body = "!" + render_expr(arg, predicate, 2)
        case And(args):
            body = " & ".join(render_expr(a, predicate, 2) for a in args)
        case Or(args):
            body = " | ".join(render_expr(a, predicate, 1) for a in args)
        case _:
            raise TypeError(expression)
    return f"({body})" if _PRECEDENCE[type(expression)] < min_precedence else body


def render(program: Program) -> str:
    return _run_deep(lambda: _render(program))


def _render(program: Program) -> str:
    lines = [f"alphabet {' '.join(program.alphabet)}", ""]
    for subprogram in program.subprograms:
        match subprogram:
            case Bos(name):
                lines.append(f"{name} = bos")
            case Symbol(name, symbol):
                lines.append(f"{name} = symbol {symbol}")
            case BoolNode(name, expr):
                lines.append(f"{name} = {render_expr(expr, predicate=False)}")
            case Attention(name, direction, score, value):
                rendered_score = render_expr(score, predicate=True)
                rendered_value = render_expr(value, predicate=True)
                lines.append(f"{name} = {direction}({rendered_score}, {rendered_value})")
    lines += ["", f"output {program.output}"]
    return "\n".join(lines) + "\n"


# --- `.brasp` parsing ------------------------------------------------------

_IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")

# What counts as whitespace has to match Java's `Character.isWhitespace`, not
# Python's `str.isspace`: the large-alphabet examples use \x85 (NEL) as an
# ordinary symbol, and Python -- but not Java -- calls it whitespace.
_SPACE = " \t\n\r\f\v"


class BraspParseError(ValueError):
    pass


def _splice(operands: list, operand, kind) -> None:
    """Append `operand`, flattening `(a & (b & c))` into one n-ary node.

    Associativity makes this semantics-preserving, and it matters: the
    large-alphabet examples nest one paren per symbol, and a 256-deep tree
    exceeds CPython 3.12's C recursion limit when rendered -- a limit
    `sys.setrecursionlimit` cannot raise.
    """
    if isinstance(operand, kind):
        operands.extend(operand.args)
    else:
        operands.append(operand)


class _LineParser:
    """Mirrors `BraspText.LineParser` in Scala, including its precedence."""

    def __init__(self, text: str):
        self.text = text
        self.pos = 0

    def _fail(self, message: str):
        raise BraspParseError(f"{message} at {self.text[self.pos:self.pos + 30]!r}")

    def _skip(self):
        while self.pos < len(self.text) and self.text[self.pos] in _SPACE:
            self.pos += 1

    def _identifier(self) -> str:
        self._skip()
        match = _IDENTIFIER.match(self.text, self.pos)
        if not match:
            self._fail("expected an identifier")
        self.pos = match.end()
        return match.group(0)

    def _symbol_token(self) -> str:
        self._skip()
        if self.pos >= len(self.text):
            self._fail("expected a symbol")
        if self.text[self.pos] == '"':
            self.pos += 1
            start = self.pos
            while self.pos < len(self.text) and self.text[self.pos] != '"':
                self.pos += 1
            if self.pos >= len(self.text):
                self._fail("unterminated quoted symbol")
            value = self.text[start:self.pos]
            self.pos += 1
            return value
        start = self.pos
        while self.pos < len(self.text) and self.text[self.pos] not in _SPACE and self.text[self.pos] not in "(),":
            self.pos += 1
        if self.pos == start:
            self._fail("expected a symbol")
        return self.text[start:self.pos]

    def _char(self, expected: str):
        self._skip()
        if self.pos >= len(self.text) or self.text[self.pos] != expected:
            self._fail(f"expected {expected!r}")
        self.pos += 1

    def _try_char(self, expected: str) -> bool:
        self._skip()
        if self.pos < len(self.text) and self.text[self.pos] == expected:
            self.pos += 1
            return True
        return False

    def symbols(self) -> list[str]:
        result = []
        self._skip()
        while self.pos < len(self.text):
            result.append(self._symbol_token())
            self._skip()
        return result

    def sole_identifier(self) -> str:
        name = self._identifier()
        self._skip()
        if self.pos < len(self.text):
            self._fail("unexpected trailing text")
        return name

    def definition(self, name: str) -> Subprogram:
        self._skip()
        start = self.pos
        leading = self._identifier() if (self.pos < len(self.text) and (self.text[self.pos].isalpha() or self.text[self.pos] == "_")) else None
        if leading == "bos":
            result: Subprogram = Bos(name)
        elif leading == "symbol":
            result = Symbol(name, self._symbol_token())
        elif leading == "const":
            word = self._identifier()
            if word not in ("true", "false"):
                self._fail("expected true or false")
            result = BoolNode(name, Const(word == "true"))
        elif leading in ("rightmost", "leftmost"):
            self._char("(")
            score = self._or(predicate=True)
            self._char(",")
            value = self._or(predicate=True)
            self._char(")")
            result = Attention(name, leading, score, value)
        else:
            self.pos = start  # not a keyword: re-read it as a reference
            result = BoolNode(name, self._or(predicate=False))
        self._skip()
        if self.pos < len(self.text):
            self._fail("unexpected trailing text")
        return result

    def _or(self, predicate: bool) -> Expr:
        operands: list[Expr] = []
        _splice(operands, self._and(predicate), Or)
        while self._try_char("|"):
            _splice(operands, self._and(predicate), Or)
        return operands[0] if len(operands) == 1 else Or(tuple(operands))

    def _and(self, predicate: bool) -> Expr:
        operands: list[Expr] = []
        _splice(operands, self._not(predicate), And)
        while self._try_char("&"):
            _splice(operands, self._not(predicate), And)
        return operands[0] if len(operands) == 1 else And(tuple(operands))

    def _not(self, predicate: bool) -> Expr:
        if self._try_char("!"):
            return Not(self._not(predicate))
        return self._atom(predicate)

    def _atom(self, predicate: bool) -> Expr:
        self._skip()
        if self.pos >= len(self.text):
            self._fail("expected an expression")
        if self._try_char("("):
            inner = self._or(predicate)
            self._char(")")
            return inner
        name = self._identifier()
        if name == "true":
            return Const(True)
        if name == "false":
            return Const(False)
        at = "i"
        if self._try_char("@"):
            at = self._identifier()
            if at not in ("i", "j"):
                self._fail("a reference position must be i or j")
            if not predicate:
                self._fail("ordinary Boolean expressions cannot specify a position")
        return Ref(name, at)


def _run_deep(work):
    """Run `work()` on a thread with a stack deep enough for nested formulas.

    Both parsing and rendering recurse once per nesting level, and the
    large-alphabet examples nest far deeper than the default stack allows.
    """
    box: list = []
    error: list = []

    def target():
        sys.setrecursionlimit(max(sys.getrecursionlimit(), 200_000))
        try:
            box.append(work())
        except BaseException as problem:  # re-raised on the calling thread
            error.append(problem)

    previous = threading.stack_size(512 * 1024 * 1024)
    try:
        thread = threading.Thread(target=target)
        thread.start()
        thread.join()
    finally:
        threading.stack_size(previous)
    if error:
        raise error[0]
    return box[0]


def parse(text: str) -> Program:
    """Read the `.brasp` text syntax; the inverse of `render`.

    Deeply nested expressions recurse once per level, so this runs on a
    big-stack thread the way `ltl2_generator.print.run_deep` does -- with a
    larger stack, because the large-alphabet examples nest parentheses far
    deeper than the formula families that helper was sized for.
    """
    return _run_deep(lambda: _parse(text))


def _parse(text: str) -> Program:
    alphabet: tuple[str, ...] | None = None
    output: str | None = None
    subprograms: list[Subprogram] = []

    # Not `splitlines()`: it also breaks on \x85, \u2028 and friends, and the
    # large-alphabet examples use those code points as ordinary symbols.
    for number, raw in enumerate(text.split("\n"), start=1):
        line = raw.rstrip("\r").split("#", 1)[0].strip(_SPACE)
        if not line:
            continue
        try:
            if line == "alphabet" or line.startswith("alphabet "):
                if alphabet is not None:
                    raise BraspParseError("'alphabet' declared more than once")
                alphabet = tuple(_LineParser(line[len("alphabet"):]).symbols())
            elif line == "output" or line.startswith("output "):
                if output is not None:
                    raise BraspParseError("'output' declared more than once")
                output = _LineParser(line[len("output"):]).sole_identifier()
            elif "=" in line:
                name, body = line.split("=", 1)
                subprograms.append(_LineParser(body).definition(name.strip(_SPACE)))
            else:
                raise BraspParseError("expected 'name = expression', 'alphabet ...', or 'output NAME'")
        except BraspParseError as error:
            raise BraspParseError(f"line {number}: {error}") from None

    if not subprograms:
        raise BraspParseError("a .brasp program must define at least one subprogram")
    if alphabet is None:
        raise BraspParseError("missing 'alphabet SYM...' declaration")
    return Program(tuple(subprograms), output or subprograms[-1].name, alphabet)


# --- Reference evaluator ---------------------------------------------------


def _eval_expr(expression: Expr, values, query: int, witness: int) -> bool:
    match expression:
        case Const(value):
            return value
        case Ref(name, at):
            return values[name][query if at == "i" else witness]
        case Not(arg):
            return not _eval_expr(arg, values, query, witness)
        case And(args):
            return all(_eval_expr(a, values, query, witness) for a in args)
        case Or(args):
            return any(_eval_expr(a, values, query, witness) for a in args)
    raise TypeError(expression)


def evaluate(program: Program, word: Sequence[str]) -> dict[str, list[bool]]:
    """Every subprogram's truth value at every position, BOS included."""
    length = len(word)
    values: dict[str, list[bool]] = {}
    for subprogram in program.subprograms:
        column = []
        for query in range(length + 1):
            match subprogram:
                case Bos():
                    answer = query == 0
                case Symbol(_, symbol):
                    answer = query > 0 and word[query - 1] == symbol
                case BoolNode(_, expr):
                    answer = _eval_expr(expr, values, query, query)
                case Attention(_, direction, score, value):
                    candidates = [
                        w for w in range(query) if _eval_expr(score, values, query, w)
                    ]
                    if not candidates:
                        answer = False
                    else:
                        witness = max(candidates) if direction == "rightmost" else min(candidates)
                        answer = _eval_expr(value, values, query, witness)
                case _:
                    raise TypeError(subprogram)
            column.append(answer)
        values[subprogram.name] = column
    return values


def accepts(program: Program, word: Sequence[str]) -> bool:
    return evaluate(program, word)[program.output][len(word)]
