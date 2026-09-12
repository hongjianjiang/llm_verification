"""Numerical edge cases that change hard attention or extracted semantics."""
import unittest

import torch

from uhat.model import _straight_through
from uhat.real_extract import _apply_layer, _dedupe, _index_of, score_levels
from uhat.real_model import RealAttentionHead, RealUhatConfig, RealUhatLayer


class RealUhatNumericsTests(unittest.TestCase):
    def test_balanced_diagnostics_cover_both_labels_in_every_band(self):
        from scripts.real_uhat_study import TASKS, balanced_bands, predicate
        for task in TASKS:
            bands = balanced_bands(task)
            self.assertEqual(bands, balanced_bands(task))
            for name, words in bands.items():
                lo, hi = map(int, name.split('_')[1:])
                self.assertEqual(len(words), 256)
                self.assertEqual(sum(predicate(task, w) for w in words), 128)
                self.assertTrue(all(lo <= len(w) <= hi for w in words))

    def test_mask_excludes_positions_even_below_old_sentinel(self):
        for direction in ("leftmost", "rightmost"):
            head = RealAttentionHead(1, direction)
            with torch.no_grad():
                head.score.fill_(-2e9)
                head.value.weight.fill_(1)
            x = torch.tensor([[[1.], [2.], [3.]]])
            self.assertTrue(torch.equal(head(x, beta=1, hard=True),
                                        torch.tensor([[[0.], [1.], [1.]]])))

    def test_bos_only_soft_and_hard_have_finite_gradients(self):
        for hard in (False, True):
            head = RealAttentionHead(2, "rightmost")
            x = torch.ones(1, 1, 2, requires_grad=True)
            y = head(x, beta=32, hard=hard)
            self.assertTrue(torch.equal(y, torch.zeros_like(y)))
            y.sum().backward()
            for tensor in [x, *head.parameters()]:
                self.assertIsNotNone(tensor.grad)
                self.assertTrue(torch.isfinite(tensor.grad).all())

    def test_straight_through_forward_is_bitwise_hard(self):
        soft = torch.tensor([.004, .007, .009], requires_grad=True)
        hard = torch.ones_like(soft)
        result = _straight_through(hard, soft)
        self.assertTrue(torch.equal(result, hard))
        result.sum().backward()
        self.assertTrue(torch.equal(soft.grad, torch.ones_like(soft)))

    def test_real_hard_forward_selects_value_without_rounding_weights(self):
        head = RealAttentionHead(1, "rightmost")
        with torch.no_grad():
            head.score.zero_()
            head.value.weight.fill_(1)
        # At six witnesses, (1 + 1/6) - 1/6 rounds below 1 in float32.
        x = torch.arange(1., 8.).reshape(1, 7, 1)
        self.assertTrue(torch.equal(head(x, beta=1, hard=True),
                                    torch.arange(7.).reshape(1, 7, 1)))

    def test_closure_adds_head_sum_before_residual(self):
        layer = RealUhatLayer(RealUhatConfig(("a", "b"), width=1, heads=2))
        with torch.no_grad():
            for parameter in layer.parameters():
                parameter.zero_()
            layer.heads[0].value.weight.fill_(1e20)
            layer.heads[1].value.weight.fill_(-1e20)
        result = _apply_layer(layer, torch.tensor([3.]), [torch.ones(1), torch.ones(1)])
        self.assertTrue(torch.equal(result, torch.tensor([3.])))

    def test_distinct_scores_are_not_turned_into_ties(self):
        head = RealAttentionHead(1, "rightmost")
        with torch.no_grad():
            head.score.fill_(1)
        self.assertEqual(score_levels(head, torch.tensor([[1.]]),
                                      torch.tensor([[0.], [5e-10], [5e-10]])),
                         [[[1, 2], [0]]])

    def test_nearby_activation_values_remain_distinct(self):
        values = torch.tensor([[1.], [1.000005], [1.]])
        table = _dedupe(values)
        self.assertTrue(torch.equal(table, values[:2]))
        self.assertEqual(_index_of(values[1], table), 1)


if __name__ == "__main__":
    unittest.main()
