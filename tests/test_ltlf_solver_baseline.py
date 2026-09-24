import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))

from ltlf_solver_baseline import check_propositions, classify_solver, parse_black_witness, translate


# The export's end-of-trace test and a weak always, in Aalta syntax.
AALTA = "(a & !(X(true)) & N(G((a -> false))))"


class TranslateTest(unittest.TestCase):
    def test_aalta_is_identity(self):
        self.assertEqual(translate(AALTA, "aalta"), AALTA)

    def test_black_spells_weak_next_and_constants(self):
        # Lowercase true/false are propositions to BLACK, not constants.
        self.assertEqual(translate(AALTA, "black"), "(a & !(X(True)) & wX(G((a -> False))))")

    def test_spot_style_next_is_weak(self):
        # Spot, Lisa and Lydia read plain X as weak next; strong next is X[!].
        expected = "(a & !(X[!](true)) & X(G((a -> false))))"
        self.assertEqual(translate(AALTA, "lisa"), expected)
        self.assertEqual(translate(AALTA, "lydia"), expected)

    def test_identifiers_are_not_operators(self):
        self.assertEqual(translate("Xa & sym12 & bos_marker", "lisa"), "Xa & sym12 & bos_marker")

    def test_lydia_quotes_uppercase_propositions(self):
        self.assertEqual(translate("Big & X(true)", "lydia"), '"Big" & X[!](true)')

    def test_rejects_propositions_named_like_operators(self):
        with self.assertRaises(ValueError):
            check_propositions(["a", "X"])


class OutputTest(unittest.TestCase):
    def test_verdicts(self):
        self.assertEqual(classify_solver("black", 0, "UNSAT\n"), "empty")
        self.assertEqual(classify_solver("black", 0, "SAT\nFinite model:\n"), "nonempty")
        self.assertEqual(classify_solver("lisa", 0, "Final result: 3\nEmptiness: EMPTY\n"), "empty")
        self.assertEqual(classify_solver("lisa", 0, "Starting the decomposition phase\n"), "unknown")
        self.assertEqual(classify_solver("lydia", 0, "states: 4\nNONEMPTY\n"), "nonempty")
        self.assertEqual(classify_solver("lydia", -999, ""), "timeout")

    def test_black_witness_handles_padded_time_steps(self):
        model = "SAT\nFinite model:\n- t =  0: {b, ￢a}\n- t =  1: {￢b, a}\n- t = 10: {a, ￢b}\n- t = 11: {￢a, ￢b}\n"
        self.assertEqual(parse_black_witness(model, ["a", "b"]), ["a", "a", "b"])


if __name__ == "__main__":
    unittest.main()
