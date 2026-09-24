"""AIGER -> SMV translation used by scripts/nusmv_circuit.py."""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from nusmv_circuit import classify, commands, read_aiger, to_smv  # noqa: E402

NUSMV = ROOT / "tmp/nusmv/NuSMV-2.7.1-macosx/bin/NuSMV"


def binary_aiger(inputs, latch_next, outputs, ands):
    """Plain binary AIGER; `ands` are (rhs0, rhs1) in order, lhs is implicit."""
    first_and = inputs + len(latch_next) + 1
    header = f"aig {first_and - 1 + len(ands)} {inputs} {len(latch_next)} {len(outputs)} {len(ands)}\n"
    body = bytearray(header.encode())
    for literal in latch_next + outputs:
        body += f"{literal}\n".encode()
    for k, (a, b) in enumerate(ands):
        lhs = 2 * (first_and + k)
        a, b = max(a, b), min(a, b)
        for delta in (lhs - a, a - b):
            while delta >= 0x80:
                body.append(delta & 0x7F | 0x80)
                delta >>= 7
            body.append(delta)
    return bytes(body)


# Input x = var 1, latch l = var 2 (next := x), gate g = var 3.
# Reachable: bad = l & x (step 0: x; step 1: l and x).
REACHABLE = binary_aiger(1, [2], [6], [(4, 2)])
# Unreachable: bad = l & !l.
UNREACHABLE = binary_aiger(1, [2], [6], [(4, 5)])


class TranslationTests(unittest.TestCase):
    def test_reader_recovers_structure(self):
        self.assertEqual(read_aiger(REACHABLE), (1, [2], [6], [(6, 4, 2)]))
        self.assertEqual(read_aiger(UNREACHABLE), (1, [2], [6], [(6, 5, 4)]))

    def test_input_dependent_bad_is_latched_before_the_invariant(self):
        smv = to_smv(REACHABLE)
        self.assertIn("IVAR\n  i1 : boolean;", smv)
        self.assertIn("a3 := l2 & i1;", smv)
        self.assertIn("next(bad_seen) := a3;", smv)
        self.assertIn("INVARSPEC !bad_seen", smv)
        self.assertNotIn("INVARSPEC !a3", smv)

    def test_nusmv_verdicts_on_both_engines(self):
        if not NUSMV.exists():
            self.skipTest("needs the local NuSMV 2.7.1 build")
        for engine in ("bdd", "kind"):
            for model, expected in ((REACHABLE, "nonempty"), (UNREACHABLE, "empty")):
                with self.subTest(engine=engine, expected=expected), tempfile.TemporaryDirectory() as tmp:
                    (Path(tmp) / "m.smv").write_text(to_smv(model))
                    (Path(tmp) / "c.cmd").write_text(commands(engine, 20))
                    done = subprocess.run([str(NUSMV), "-source", "c.cmd", "m.smv"], cwd=tmp,
                                          capture_output=True, text=True, timeout=60)
                    self.assertEqual(classify(done.returncode, done.stdout + done.stderr, engine), expected)


if __name__ == "__main__":
    unittest.main()
