import itertools
import tempfile
import unittest
from pathlib import Path

from ltl2.ast import *
from ltl2.eval import accepts
from ltl2.parser import parse
from ltl2.print import ascii
from ltl2.eliminate import eliminate
from ltl2.pltl import eval_pltl
from ltl2.dfa import compile_dfa, minimize
from ltl2.build import build
from ltl2.families.core import core_benchmarks


class Ltl2Tests(unittest.TestCase):
    def test_strict_boundary_semantics(self):
        a = AtJ(Letter("a"))
        self.assertFalse(accepts(Once(a), "a"))
        self.assertTrue(accepts(Hist(a), "a"))  # vacuous at first position
        self.assertFalse(accepts(Yst(a), "a"))
        self.assertFalse(accepts(Since(a, a), "a"))
        self.assertTrue(accepts(Yst(a), "aa"))

    def test_round_trip_ascii(self):
        formula = Since(AndB(AtI(Letter("a")), AtJ(Not1(Letter("b")))), AtJ(BOS()))
        self.assertEqual(parse(ascii(formula)), formula)

    def test_elimination_and_dfa_agree(self):
        alphabet = ("a", "b")
        formula = Since(AndB(AtI(Letter("a")), AtJ(Letter("a"))), AtJ(BOS()))
        plain = eliminate(formula)
        dfa = minimize(compile_dfa(plain, alphabet))
        for length in range(1, 7):
            for word in itertools.product(alphabet, repeat=length):
                self.assertEqual(accepts(formula, word), eval_pltl(plain, word), word)
                self.assertEqual(accepts(formula, word), dfa.accepts(word), word)

    def test_core_aperiodicity_when_compiled(self):
        for benchmark in core_benchmarks():
            record, _ = benchmark.record(cap=100)
            aperiodic = record["metrics"]["aperiodic"]
            if aperiodic is not None:
                self.assertEqual(aperiodic, benchmark.expected_star_free, benchmark.id)

    def test_deterministic_build(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first, second = root / "first", root / "second"
            build(first, cap=100); build(second, cap=100)
            self.assertEqual((first / "benchmarks.jsonl").read_bytes(), (second / "benchmarks.jsonl").read_bytes())


if __name__ == "__main__":
    unittest.main()
