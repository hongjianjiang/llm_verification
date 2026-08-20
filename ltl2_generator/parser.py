"""Parser for the compact, human-editable ASCII LTL₂ syntax."""
from __future__ import annotations

import re
from .ast import *


class ParseError(ValueError):
    pass


TOKEN = re.compile(r"\s*(?:(Y|P|H|S|BOS)|(@i|@j)|([A-Za-z_][A-Za-z0-9_]*)|'([^']*)'|(\[|\]|\(|\)|,|~|&|\||⊤|⊥))")


class _Parser:
    def __init__(self, text: str):
        self.tokens: list[str] = []
        offset = 0
        while offset < len(text):
            if text[offset:].isspace():
                break
            m = TOKEN.match(text, offset)
            if not m:
                raise ParseError(f"unexpected input at {text[offset:offset + 20]!r}")
            groups = m.groups()
            self.tokens.append("'" + groups[3] + "'" if groups[3] is not None else next(x for x in groups if x is not None))
            offset = m.end()
        self.pos = 0

    def take(self, expected: str | None = None) -> str:
        if self.pos == len(self.tokens):
            raise ParseError("unexpected end of formula")
        value = self.tokens[self.pos]
        if expected is not None and value != expected:
            raise ParseError(f"expected {expected!r}, got {value!r}")
        self.pos += 1
        return value

    def maybe(self, value: str) -> bool:
        if self.pos < len(self.tokens) and self.tokens[self.pos] == value:
            self.pos += 1
            return True
        return False

    def unary(self) -> Unary:
        result = self.u_and()
        if self.pos != len(self.tokens):
            raise ParseError(f"unexpected token {self.tokens[self.pos]!r}")
        return result

    def u_and(self) -> Unary:
        result = self.u_prefix()
        while self.maybe("&"):
            result = And1(result, self.u_prefix())
        return result

    def u_prefix(self) -> Unary:
        if self.maybe("~"):
            return Not1(self.u_prefix())
        if self.maybe("⊤") or self.maybe("T"): return Top()
        if self.maybe("⊥") or self.maybe("F"): return Bot()
        if self.maybe("BOS"): return BOS()
        if self.pos < len(self.tokens) and self.tokens[self.pos].startswith("'"):
            return Letter(self.take()[1:-1])
        if self.maybe("("):
            inside = self.u_and()
            self.take(")")
            return inside
        if self.pos < len(self.tokens) and self.tokens[self.pos] in {"Y", "P", "H"}:
            op = self.take(); self._indices(); self.take("(")
            mask = self.binary(); self.take(")")
            return {"Y": Yst, "P": Once, "H": Hist}[op](mask)
        raise ParseError("expected a unary formula")

    def _indices(self) -> None:
        self.take("["); self.take("i") if self.pos < len(self.tokens) and self.tokens[self.pos] == "i" else self.take("@i")
        self.take(","); self.take("j") if self.pos < len(self.tokens) and self.tokens[self.pos] == "j" else self.take("@j"); self.take("]")

    def binary(self) -> Binary:
        return self.b_or()

    def b_or(self) -> Binary:
        result = self.b_and()
        while self.maybe("|"):
            result = OrB(result, self.b_and())
        return result

    def b_and(self) -> Binary:
        result = self.b_prefix()
        while self.maybe("&"):
            result = AndB(result, self.b_prefix())
        return result

    def b_prefix(self) -> Binary:
        if self.maybe("~"): return NotB(self.b_prefix())
        if self.maybe("⊤") or self.maybe("T"): return TopB()
        if self.maybe("⊥") or self.maybe("F"): return BotB()
        if self.maybe("@i"): return AtI(self.u_prefix())
        if self.maybe("@j"): return AtJ(self.u_prefix())
        if self.maybe("("):
            left = self.b_or(); self.take(")")
            if self.maybe("S"):
                self._indices(); self.take("("); right = self.b_or(); self.take(")")
                # The top-level Since grammar is unary; this error prevents a
                # confusing accidental binary nested temporal operator.
                raise ParseError("S[i,j] is a unary temporal operator; write it around the two masks")
            return left
        raise ParseError("expected a binary mask")


def parse(text: str) -> Unary:
    """Parse a formula.  Supports `(<mask>) S[i,j] (<mask>)` at unary level."""
    # A since formula's operands are binary; recognise its outer form without
    # compromising normal parenthesised unary formulas.
    p = _Parser(text)
    try:
        return p.unary()
    except ParseError:
        pass
    # Split at the unique outer S[i,j] by a small balanced scanner.
    depth = 0
    for m in re.finditer(r"\(|\)|S\s*\[\s*i\s*,\s*j\s*\]", text):
        token = m.group(0)
        if token == "(": depth += 1
        elif token == ")": depth -= 1
        elif depth == 0:
            left, right = text[:m.start()].strip(), text[m.end():].strip()
            if left.startswith("(") and left.endswith(")"): left = left[1:-1]
            if right.startswith("(") and right.endswith(")"): right = right[1:-1]
            lp, rp = _Parser(left), _Parser(right)
            l, r = lp.binary(), rp.binary()
            if lp.pos == len(lp.tokens) and rp.pos == len(rp.tokens):
                return Since(l, r)
    raise ParseError("invalid LTL₂ formula")
