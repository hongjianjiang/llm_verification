import itertools
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from nusmv_baseline import classify, measure, parse, to_smv, witness


def finite(nodes, root, trace):
    """Independent finite-trace semantics, computed backwards in time."""
    table = []
    for op, children in nodes:
        values = []
        for i in reversed(range(len(trace))):
            a = table[children[0]][i] if children else None
            b = table[children[1]][i] if len(children) > 1 else None
            last = i == len(trace) - 1
            if not children:
                value = op == "true" or (op != "false" and op in trace[i])
            elif op == "!": value = not a
            elif op == "&": value = a and b
            elif op == "|": value = a or b
            elif op == "->": value = not a or b
            elif op == "X": value = not last and table[children[0]][i + 1]
            elif op == "N": value = last or table[children[0]][i + 1]
            elif op == "F": value = a or (not last and values[-1])
            elif op == "G": value = a and (last or values[-1])
            elif op == "U": value = b or (a and not last and values[-1])
            elif op == "R": value = b and (a or last or values[-1])
            else: raise AssertionError(op)
            values.append(value)
        table.append(list(reversed(values)))
    return table[root][0]


class EncodingTest(unittest.TestCase):
    def test_deep_formula_and_reserved_names(self):
        smv, names = to_smv("X(" * 10000 + "active & MODULE" + ")" * 10000)
        self.assertEqual(names, {"active": "p0", "MODULE": "p1"})
        self.assertEqual(smv.count("X(active &"), 10000)

    def test_rejects_invalid_input(self):
        for formula in ("", "(a", "a)", "a b", "a &", "a @ b", "()"):
            with self.subTest(formula=formula), self.assertRaises(ValueError):
                parse(formula)

    def test_results_do_not_overclaim_bmc(self):
        self.assertEqual(classify(0, "-- specification p is true\n", "bdd"), "empty")
        self.assertEqual(classify(0, "-- specification p is false\n", "bmc"), "nonempty")
        self.assertEqual(classify(0, "-- no counterexample found with bound 100\n", "bmc"), "bound_limit")
        self.assertEqual(classify(0, "-- specification p is true\n", "bmc"), "unknown")
        self.assertEqual(classify(1, "-- specification p is false\n", "bdd"), "error")
        self.assertEqual(classify(-999, "", "bdd"), "timeout")

    def test_sparse_witness_reversed_at_sentinel(self):
        output = """-> State: 1.1 <-
  active = TRUE
  p0 = FALSE
  p1 = TRUE
-> State: 1.2 <-
  p0 = TRUE
  p1 = FALSE
-> State: 1.3 <-
  p0 = FALSE
-> State: 1.4 <-
  active = FALSE
"""
        self.assertEqual(witness(output, {"a": "p0", "b": "p1"}, ["a", "b"]), ["a", "b"])

    @unittest.skipUnless(os.environ.get("NUSMV_BIN"), "set NUSMV_BIN for real solver semantics checks")
    def test_all_short_traces_against_finite_semantics(self):
        atoms = ["a", "!a", "true", "false"]
        formulas = atoms + [f"{op}({a})" for op in ("X", "N", "F", "G", "!") for a in atoms]
        formulas += [f"({a}) {op} ({b})" for op in ("U", "R", "&", "|", "->")
                     for a in atoms for b in atoms]
        formulas += ["G(a -> X(a))", "!(N(G(a)))", "X(F(a U !a))", "N(false) R X(true)"]
        with tempfile.TemporaryDirectory() as tmp:
            for length in (1, 2, 3):
                for bits in itertools.product((False, True), repeat=length):
                    trace = [{"a"} if bit else set() for bit in bits]
                    model = ["MODULE main", f"VAR step : 0..{length};",
                             "ASSIGN init(step) := 0;",
                             f"next(step) := case step < {length} : step + 1; TRUE : step; esac;",
                             f"DEFINE active := step < {length};",
                             "p0 := " + (" | ".join(f"step = {i}" for i, bit in enumerate(bits) if bit) or "FALSE") + ";"]
                    for formula in formulas:
                        model.append("LTLSPEC" + to_smv(formula)[0].split("LTLSPEC", 1)[1])
                    path = Path(tmp) / "fixed.smv"
                    path.write_text("\n".join(model))
                    result = subprocess.run([os.environ["NUSMV_BIN"], "-s", str(path)],
                                            capture_output=True, text=True, timeout=30)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    actual = re.findall(r"^-- specification .* is (true|false)$", result.stdout, re.M)
                    expected = ["false" if finite(*parse(f), trace) else "true" for f in formulas]
                    self.assertEqual(actual, expected, (bits, result.stdout))

    @unittest.skipUnless(os.environ.get("NUSMV_BIN"), "set NUSMV_BIN for compiler integration checks")
    def test_compiler_pipeline_and_source_witness(self):
        root = Path(__file__).resolve().parents[1]
        jar = root / "target/scala-3.5.1/brasp-verification.jar"
        if not jar.exists():
            self.skipTest("build the compiler jar first")
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            # Contradiction tests sound emptiness; tautology tests nonempty-word
            # and sentinel boundaries. Same-letter-before exercises elimination.
            for label, body, expected in (
                ("false", "sym(a)@i & !sym(a)@i", "empty"),
                ("true", "sym(a)@i | !sym(a)@i", "nonempty"),
                ("two_variable", "P(f_0@i & f_0@j)", "nonempty"),
            ):
                source = directory / f"{label}.ltl"
                source.write_text("logic past-strict\nalphabet a b\nf_0 := sym(a)@i\nf_1 := " + body +
                                  "\noutput := f_1@i" +
                                  "\nevaluate at i = |w| (the final input position)\n")
                for engine in ("bdd", "bmc"):
                    args = argparse.Namespace(jar=jar, nusmv=Path(os.environ["NUSMV_BIN"]),
                                              engine=engine, timeout=30, heap="4g", repetitions=1,
                                              bound=10, out=directory / "records.jsonl")
                    record = measure(args, source)
                    self.assertEqual(record["status"], "bound_limit" if engine == "bmc" and expected == "empty"
                                     else expected, record)
                    if expected == "nonempty":
                        self.assertTrue(record["witness"], record)
                        replay = subprocess.run(["java", "-jar", str(jar), str(source), "--word",
                                                 " ".join(record["witness"])],
                                                capture_output=True, text=True, timeout=30)
                        self.assertEqual(replay.returncode, 0, replay.stderr)
                        self.assertEqual(replay.stdout.strip(), "true")


if __name__ == "__main__":
    unittest.main()
