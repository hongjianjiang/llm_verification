import unittest

import torch

from uhat.model import Schedule, UhatConfig
from uhat.tasks import TASKS, enumerate_words
from uhat.train import probability_loss, train_once


class HardLossTests(unittest.TestCase):
    def test_unique_sampling_is_balanced_reproducible_and_excludes_seen_words(self):
        from scripts.uhat_data_ablation import unique_pool
        from uhat.tasks import resolve
        task = resolve('figure2_y__k-4')
        excluded = enumerate_words(task.alphabet, 4)
        first = unique_pool(task, 128, task.lengths[1], 19, excluded)
        second = unique_pool(task, 128, task.lengths[1], 19, excluded)
        self.assertEqual(first, second)
        self.assertEqual(len(set(first)), 128)
        self.assertFalse(set(first).intersection(excluded))
        for size in [32, 64, 128]:
            self.assertEqual(sum(task.label(w) for w in first[:size]), size // 2)
        self.assertTrue(all(task.lengths[1][0] <= len(w) <= task.lengths[1][1] for w in first))

    def test_forward_loss_unchanged_but_gradients_restored(self):
        values = torch.tensor([0., 1.], requires_grad=True)
        labels = torch.tensor([1., 0.])
        weights = torch.ones(2)
        old = probability_loss(values, labels, weights, hard=True, mode='legacy')
        old.backward()
        self.assertEqual(values.grad.abs().sum().item(), 0)
        values.grad.zero_()
        new = probability_loss(values, labels, weights, hard=True)
        self.assertEqual(old.item(), new.item())
        new.backward()
        self.assertTrue(torch.isfinite(values.grad).all())
        self.assertLess(values.grad[0].item(), 0)
        self.assertGreater(values.grad[1].item(), 0)

    def test_actual_hard_phase_updates_parameters(self):
        torch.set_num_threads(1)
        task = TASKS['ends_ab']
        # One term avoids a separately saturated OR of two constant-true terms.
        config = UhatConfig(task.alphabet, layers=1, heads_per_layer=1, terms=1)
        words = enumerate_words(task.alphabet, 3)
        models = {}
        for mode in ['legacy', 'straight_through']:
            schedule = Schedule(steps=4, hard_fraction=1, tau_start=1, tau_end=1, hard_loss=mode)
            models[mode] = train_once(task, config, schedule, words, 5, False).model
        torch.manual_seed(5)
        from uhat.model import BooleanUhat
        initial = BooleanUhat(config)
        self.assertTrue(all(torch.equal(a, b) for a, b in
                            zip(initial.parameters(), models['legacy'].parameters())))
        self.assertTrue(any(not torch.equal(a, b) for a, b in
                            zip(initial.parameters(), models['straight_through'].parameters())))

    def test_surrogate_hard_phase_gets_gradient_through_saturated_terms(self):
        # Two terms: the case the straight-through variant above avoids.
        torch.set_num_threads(1)
        task = TASKS['ends_ab']
        config = UhatConfig(task.alphabet, layers=1, heads_per_layer=1, terms=2)
        words = enumerate_words(task.alphabet, 3)
        schedule = Schedule(steps=4, hard_fraction=1, tau_start=1, tau_end=1,
                            hard_loss='surrogate')
        trained = train_once(task, config, schedule, words, 5, False).model
        torch.manual_seed(5)
        from uhat.model import BooleanUhat
        initial = BooleanUhat(config)
        self.assertTrue(any(not torch.equal(a, b) for a, b in
                            zip(initial.parameters(), trained.parameters())))

    def test_hard_phase_never_ends_below_its_starting_point(self):
        # Same seed => same soft phase, so every mode enters the hard phase with
        # the model 'legacy' freezes.  Keeping the best hard model means a
        # destructive hard phase (huge lr) cannot return anything worse.
        torch.set_num_threads(1)
        task = TASKS['ends_ab']
        config = UhatConfig(task.alphabet, layers=1, heads_per_layer=2, terms=2)
        words = enumerate_words(task.alphabet, 4)
        accuracy = {}
        for mode in ['legacy', 'straight_through', 'surrogate']:
            schedule = Schedule(steps=200, hard_fraction=0.5, hard_lr=5.0, hard_loss=mode)
            accuracy[mode] = train_once(task, config, schedule, words, 0, False).hard_accuracy
        self.assertGreaterEqual(accuracy['straight_through'], accuracy['legacy'])
        self.assertGreaterEqual(accuracy['surrogate'], accuracy['legacy'])


class JarCheckTests(unittest.TestCase):
    JAR = 'target/scala-3.5.1/brasp-verification.jar'

    def test_scala_boolean_automaton_agrees_with_python_on_every_short_word(self):
        import os
        import shutil
        from pathlib import Path
        from uhat import brasp
        from uhat.train import jar_check
        if not (os.path.exists(self.JAR) and shutil.which('java')):
            self.skipTest('needs the assembled jar and java')
        path = Path('examples/brasp/ends_ab.brasp')
        program = brasp.parse(path.read_text())
        words = enumerate_words(program.alphabet, 6)
        self.assertIn((), [tuple(w) for w in words])
        self.assertEqual(jar_check(self.JAR, path, program, words), [])


if __name__ == '__main__':
    unittest.main()
