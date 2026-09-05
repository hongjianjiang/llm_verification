import itertools
import os
import random
import unittest
from pathlib import Path

import torch

from uhat import brasp
from uhat.extract import encode, extract, model_accepts, verify_equivalence
from uhat.model import BooleanUhat, Schedule, UhatConfig
from uhat.tasks import TASKS, enumerate_words


def _words(alphabet, count=200, max_length=12, seed=0):
    rng = random.Random(seed)
    words = enumerate_words(alphabet, 4)
    words += [
        tuple(rng.choice(alphabet) for _ in range(rng.randint(5, max_length)))
        for _ in range(count)
    ]
    return words


class BraspSemanticsTests(unittest.TestCase):
    """The Python evaluator has to agree with `Brasp.evaluate` in Scala."""

    def _program(self):
        return brasp.Program(
            (
                brasp.Bos("is_bos"),
                brasp.Symbol("is_a", "a"),
                brasp.Symbol("is_b", "b"),
                brasp.Attention("prev_a", "rightmost", brasp.TRUE, brasp.Ref("is_a", "j")),
                brasp.BoolNode(
                    "accept", brasp.conjunction([brasp.Ref("is_b"), brasp.Ref("prev_a")])
                ),
            ),
            "accept",
            ("a", "b"),
        )

    def test_ends_ab(self):
        program = self._program()
        for word, expected in [("ab", True), ("ba", False), ("aab", True), ("b", False), ("", False)]:
            self.assertEqual(brasp.accepts(program, list(word)), expected, word)

    def test_attention_is_strict_past_and_defaults_to_false(self):
        program = brasp.Program(
            (
                brasp.Bos("is_bos"),
                brasp.Symbol("is_a", "a"),
                brasp.Attention("some_a", "leftmost", brasp.Ref("is_a", "j"), brasp.TRUE),
            ),
            "some_a",
            ("a",),
        )
        # Position 1 sees only BOS, so no witness satisfies `is_a@j`.
        self.assertFalse(brasp.accepts(program, ["a"]))
        self.assertTrue(brasp.accepts(program, ["a", "a"]))

    def test_render_round_trips_precedence(self):
        expression = brasp.disjunction(
            [
                brasp.conjunction([brasp.Ref("is_a", "j"), brasp.Not(brasp.Ref("is_b", "i"))]),
                brasp.Ref("is_bos", "j"),
            ]
        )
        self.assertEqual(
            brasp.render_expr(expression, predicate=True), "is_a@j & !is_b@i | is_bos@j"
        )

    def test_conjunction_folds_contradictions(self):
        reference = brasp.Ref("is_a")
        self.assertEqual(brasp.conjunction([reference, brasp.Not(reference)]), brasp.FALSE)
        self.assertEqual(brasp.conjunction([]), brasp.TRUE)
        self.assertEqual(brasp.disjunction([]), brasp.FALSE)


class ExtractionFidelityTests(unittest.TestCase):
    """The central invariant: a hardened model *is* its extracted program.

    This holds at any weights, trained or not, so it is checkable directly on
    random initialisations -- which is a far sharper test than checking it
    only where training happened to land.
    """

    def test_random_models_match_their_extraction(self):
        for seed, (layers, heads, terms, alphabet) in enumerate(
            [
                (1, 1, 1, ("a", "b")),
                (1, 2, 2, ("a", "b")),
                (2, 2, 1, ("a", "b")),
                (2, 3, 2, ("a", "b", "c")),
                (3, 1, 3, ("a", "b")),
            ]
        ):
            with self.subTest(layers=layers, heads=heads, terms=terms):
                torch.manual_seed(seed)
                model = BooleanUhat(
                    UhatConfig(alphabet, layers=layers, heads_per_layer=heads, terms=terms)
                )
                program = extract(model)
                words = _words(alphabet, seed=seed)
                self.assertEqual(verify_equivalence(model, program, words), [])

    def test_extraction_keeps_base_nodes_and_drops_dead_heads(self):
        torch.manual_seed(0)
        model = BooleanUhat(UhatConfig(("a", "b"), layers=1, heads_per_layer=2, terms=1))
        with torch.no_grad():  # force the output to ignore everything but `is_a`
            model.output.logits.zero_()
            model.output.logits[..., 0] = 5.0
            model.output.logits[0, 0, 1, 0] = -5.0
            model.output.logits[0, 0, 1, 1] = 5.0
        program = extract(model)
        names = [s.name for s in program.subprograms]
        self.assertEqual(names, ["is_bos", "is_a", "is_b", "accept"])
        for word in _words(("a", "b"), count=50):
            self.assertEqual(brasp.accepts(program, word), bool(word) and word[-1] == "a")


class ModelShapeTests(unittest.TestCase):
    def test_encode_places_bos_first_and_pads(self):
        tokens, lengths = encode([("a",), ("b", "a", "b"), ()], ("a", "b"))
        self.assertEqual(lengths.tolist(), [1, 3, 0])
        self.assertEqual(tokens[0].tolist(), [0, 1, -1, -1])
        self.assertEqual(tokens[1].tolist(), [0, 2, 1, 2])
        self.assertEqual(tokens[2].tolist(), [0, -1, -1, -1])

    def test_soft_and_hard_agree_when_gates_are_saturated(self):
        torch.manual_seed(3)
        model = BooleanUhat(UhatConfig(("a", "b"), layers=2, heads_per_layer=2, terms=2))
        with torch.no_grad():
            for parameter in model.parameters():
                parameter.mul_(40.0)  # drive every softmax to one-hot
        words = _words(("a", "b"), count=60)
        self.assertEqual(
            model_accepts(model, words, hard=True), model_accepts(model, words, hard=False)
        )


class TaskTests(unittest.TestCase):
    def test_labels(self):
        self.assertTrue(TASKS["contains_aba"].label(list("bbabab")))
        self.assertFalse(TASKS["contains_aba"].label(list("bbaab")))
        self.assertTrue(TASKS["a_star_b_star"].label(list("aabbb")))
        self.assertFalse(TASKS["a_star_b_star"].label(list("aabba")))
        self.assertTrue(TASKS["parity_a"].label(list("aabb")))
        self.assertFalse(TASKS["parity_a"].label(list("abb")))
        self.assertFalse(TASKS["parity_a"].star_free)


class TomitaSpecTests(unittest.TestCase):
    """The hand-written specs must be the Tomita languages, not near-misses.

    These specs are what a learned program is proved equivalent to, so an
    error here would silently turn every proof about them into a proof about
    the wrong language.
    """

    def test_specs_match_their_predicates_exhaustively(self):
        from uhat.tasks import TOMITA

        for number in (1, 2, 4, 7):
            with self.subTest(tomita=number):
                program = brasp.parse(
                    Path(f"examples/brasp/tomita_{number}.brasp").read_text()
                )
                task = TOMITA[f"tomita_{number}"]
                for length in range(1, 12):
                    for word in itertools.product(("0", "1"), repeat=length):
                        self.assertEqual(
                            brasp.accepts(program, list(word)),
                            task.label(list(word)),
                            f"tomita_{number} disagrees on {''.join(word)!r}",
                        )

    def test_star_free_classification(self):
        from uhat.tasks import TOMITA

        star_free = {n for n in range(1, 8) if TOMITA[f"tomita_{n}"].star_free}
        # Bhattamishra et al. (EMNLP 2020) report transformers failing on
        # exactly the non-star-free three.
        self.assertEqual(star_free, {1, 2, 4, 7})


class RealUhatExtractionTests(unittest.TestCase):
    """The real-valued model's program must match it exactly, at any weights.

    This is the harder direction than `uhat.extract`: there the model was
    already Boolean, here real activations have to be shown to range over
    finitely many classes and a real argmax has to become a Boolean cascade.
    Checking it at random weights is what makes it a claim about the
    construction rather than about wherever training happened to land.
    """

    def test_random_real_models_match_their_extraction(self):
        import torch

        from uhat.real_extract import build_program, class_tables
        from uhat.real_model import RealUhat, RealUhatConfig, accepts

        cases = [
            (6, 1, 1, ("a", "b"), ("rightmost",)),
            (6, 1, 2, ("a", "b"), ("rightmost", "leftmost")),
            (8, 2, 1, ("a", "b"), ("leftmost",)),
            (5, 1, 1, ("a", "b", "c"), ("rightmost",)),
        ]
        for seed, (width, layers, heads, alphabet, directions) in enumerate(cases):
            with self.subTest(width=width, layers=layers, heads=heads, sigma=len(alphabet)):
                torch.manual_seed(seed)
                model = RealUhat(RealUhatConfig(
                    alphabet, width=width, layers=layers, heads=heads, directions=directions
                ))
                tables = class_tables(model)
                program = build_program(model)
                longest = 7 if len(alphabet) == 2 else 5
                words = [
                    tuple(w)
                    for n in range(1, longest + 1)
                    for w in itertools.product(alphabet, repeat=n)
                ]
                from_model = accepts(model, words, snap_to=[t.values for t in tables])
                from_program = [brasp.accepts(program, w) for w in words]
                self.assertEqual(from_model, from_program)

    def test_score_gate_makes_exact_ties_reachable(self):
        """At `relu(gate) == 0` every score ties, so `C` alone selects.

        Without this the head cannot express "the previous position": scores
        depend only on the classes of i and j, so equal symbols score equally
        and only the tie-break separates them.
        """
        import torch

        from uhat.real_model import RealUhat, RealUhatConfig, encode

        torch.manual_seed(0)
        model = RealUhat(RealUhatConfig(("a", "b"), width=6, layers=1, heads=1))
        head = model.blocks[0].heads[0]
        with torch.no_grad():
            head.gate.fill_(-1.0)  # relu -> 0
        tokens, _ = encode([("a", "a", "a", "b")], ("a", "b"))
        scores = head.scores(model.embedding(tokens))[0]
        self.assertTrue(torch.allclose(scores[3, :3], torch.zeros(3)))
        self.assertEqual(int(head.chosen(scores)[3].item()), 2)  # rightmost = i-1


class PaddingTests(unittest.TestCase):
    """`uhat.pad` must add depth and change nothing else.

    The depth-padding experiment rests entirely on this: if a padded program
    computed a slightly different language, every conclusion drawn from
    comparing it to its source would be about two different targets.
    """

    def test_padding_preserves_the_language(self):
        from uhat.pad import pad_program
        from uhat.programs import attention_depth

        for name in ("random100/rand_012", "random100/rand_032", "random100/rand_000"):
            source = Path("examples/brasp") / f"{name}.brasp"
            if not source.exists():
                self.skipTest(f"{source} not generated")
            program = brasp.parse(source.read_text())
            base = attention_depth(program)
            for target in (base + 1, base + 4, 9):
                padded = pad_program(program, target)
                self.assertEqual(attention_depth(padded), target)
                for length in range(0, 9):
                    for word in itertools.product(program.alphabet, repeat=length):
                        self.assertEqual(
                            brasp.accepts(program, list(word)),
                            brasp.accepts(padded, list(word)),
                            f"{name} padded to {target} differs on {''.join(word) or 'eps'}",
                        )

    def test_padding_refuses_to_shrink(self):
        from uhat.pad import pad_program
        from uhat.programs import attention_depth

        source = Path("examples/brasp/random100/rand_000.brasp")
        if not source.exists():
            self.skipTest(f"{source} not generated")
        program = brasp.parse(source.read_text())
        with self.assertRaises(ValueError):
            pad_program(program, attention_depth(program) - 1)


@unittest.skipUnless(os.environ.get("UHAT_SLOW_TESTS"), "set UHAT_SLOW_TESTS=1")
class TrainingTests(unittest.TestCase):
    def test_ends_ab_trains_and_generalises(self):
        from uhat.train import fit_best
        from uhat.tasks import datasets

        task = TASKS["ends_ab"]
        train_words, test_words = datasets(task, enumerate_upto=6, train_samples=128)
        fit = fit_best(
            task,
            UhatConfig(task.alphabet, layers=1, heads_per_layer=2, terms=1),
            Schedule(steps=800, restarts=4),
            train_words,
            verbose=False,
        )
        program = extract(fit.model)
        self.assertEqual(verify_equivalence(fit.model, program, test_words), [])
        self.assertTrue(all(brasp.accepts(program, w) == task.label(w) for w in test_words))


if __name__ == "__main__":
    unittest.main()
