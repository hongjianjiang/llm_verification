"""Portable benchmark records and metric collection."""
from __future__ import annotations
from dataclasses import dataclass, field
from typing import Callable
from .ast import Unary, uses_two_variable
from .print import ascii, latex, json_ast
from .eliminate import eliminate, elimination_metrics
from .pltl import pltl_ascii
from .dfa import DFA, compile_dfa, minimize, is_aperiodic


@dataclass(frozen=True)
class Benchmark:
    id: str
    family: str
    params: dict
    alphabet: tuple[str, ...]
    formula: Unary | None
    expected_star_free: bool
    description: str
    provenance: str = "designed"
    dfa_factory: Callable[[], DFA] | None = field(default=None, compare=False, repr=False)
    extra_metrics: dict = field(default_factory=dict)

    def dfa(self, cap: int = 200_000) -> DFA:
        if self.dfa_factory: return self.dfa_factory()
        if self.formula is None: raise ValueError("benchmark has neither formula nor DFA")
        # Some deliberately succinct instances have an enormous *expanded*
        # history-vector state space.  Family generators may supply a smaller
        # exploration budget; this is recorded as a compilation timeout rather
        # than silently pretending the incomplete product is a DFA.
        cap = min(cap, int(self.extra_metrics.get("compile_state_cap", cap)))
        return minimize(compile_dfa(eliminate(self.formula), self.alphabet, cap))

    def record(self, cap: int = 200_000) -> tuple[dict, DFA | None]:
        record = {"id": self.id, "family": self.family, "params": self.params,
                  "alphabet": list(self.alphabet), "expected": {"star_free": self.expected_star_free,
                  "empty": False, "universal": False}, "provenance": self.provenance,
                  "description": self.description, "conventions": {"strict": True, "empty_word": "excluded"}}
        if self.formula is not None:
            pl = eliminate(self.formula)
            record.update({"formula_ast": json_ast(self.formula), "formula_ascii": ascii(self.formula),
                           "formula_latex": latex(self.formula), "formula_pltl": pltl_ascii(pl),
                           "formula_ltl_mirrored": None})
            metrics = {"size_ltl2": self.formula.size, "temporal_depth": self.formula.temporal_depth,
                       "y_depth": self.formula.y_depth, "uses_two_variable": uses_two_variable(self.formula)}
            metrics.update(elimination_metrics(self.formula))
        else: metrics = {"size_ltl2": None, "size_pltl": None, "size_pltl_naive": None,
                         "size_pltl_optimised": None, "blowup_ratio": None, "temporal_depth": None,
                         "y_depth": None, "uses_two_variable": False}
        metrics.update(self.extra_metrics)
        dfa = self.dfa(cap)
        metrics["compilation_timeout"] = not dfa.complete
        if dfa.complete:
            try:
                # The monoid closure can itself be exponentially larger than
                # the minimised DFA.  Keep this independent cap visible too.
                aperiodic, monoid_size = is_aperiodic(dfa, cap=int(self.extra_metrics.get("monoid_cap", 1_000)))
                metrics.update({"dfa_states": dfa.states, "monoid_size": monoid_size, "aperiodic": aperiodic, "monoid_timeout": False})
            except OverflowError:
                metrics.update({"dfa_states": dfa.states, "monoid_size": None, "aperiodic": None, "monoid_timeout": True})
        else: metrics.update({"dfa_states": None, "monoid_size": None, "aperiodic": None})
        record["metrics"] = metrics
        return record, dfa if dfa.complete else None
