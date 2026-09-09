"""Tests for the T6-R survivability analysis.

The subject is a MEASUREMENT, so the tests pin the things that would make a
measurement lie: the outcome parser, the baseline (which is what makes an
unbalanced classifier look good), and the permutation test (which is what keeps
a best-of-N search honest).
"""
import unittest
import survivability_report as sr


class StrictShareTest(unittest.TestCase):

    def test_reads_the_strict_score(self):
        self.assertAlmostEqual(sr.strict_share({'smoke_strict': '23/23 discounted=none'}), 1.0)
        self.assertAlmostEqual(sr.strict_share({'smoke_strict': '0/23 discounted=7,12'}), 0.0)

    def test_absent_or_unparseable_is_not_zero(self):
        # A run with no strict score must drop OUT of the population, never count as
        # a collapse -- 41 of 123 ledger rows predate the field.
        self.assertIsNone(sr.strict_share({}))
        self.assertIsNone(sr.strict_share({'smoke_strict': 'none'}))

    def test_zero_denominator_does_not_divide(self):
        self.assertIsNone(sr.strict_share({'smoke_strict': '0/0'}))


class PeakFractionTest(unittest.TestCase):

    def test_reads_position_over_total(self):
        self.assertAlmostEqual(sr.peak_fraction({'peaked_at_cycle': 'cycle-17 (8/16)'}), 0.5)

    def test_unknown_peak_is_none(self):
        self.assertIsNone(sr.peak_fraction({'peaked_at_cycle': 'unknown (not-in-run-log)'}))


class ThresholdTest(unittest.TestCase):

    def test_finds_a_clean_split(self):
        rows = [({'x': v}, v >= 5) for v in range(10)]
        t, d, ok, n, errs = sr.best_threshold(rows, 'x')
        self.assertEqual((t, d, ok, n), (5, '>=', 10, 10))
        self.assertEqual(errs, [])

    def test_declines_a_population_too_small_to_mean_anything(self):
        self.assertIsNone(sr.best_threshold([({'x': 1}, True)] * 4, 'x'))

    def test_declines_when_one_class_is_absent(self):
        self.assertIsNone(sr.best_threshold([({'x': i}, True) for i in range(12)], 'x'))

    def test_an_uninformative_feature_still_scores_the_baseline(self):
        # 8 good, 2 bad, feature is noise: the best threshold reaches 80% by calling
        # everything good. This is why lift over baseline is the number reported.
        rows = [({'x': 1}, True)] * 8 + [({'x': 1}, False)] * 2
        self.assertEqual(sr.best_threshold(rows, 'x')[2] / 10, 0.8)


class PermutationTest(unittest.TestCase):

    def test_pure_noise_is_not_significant(self):
        rows = [({'a': i % 3, 'b': i % 4}, i % 5 == 0) for i in range(30)]
        p = sr.permutation_p(rows, ['a', 'b'], trials=200)
        self.assertGreater(p, 0.05)

    def test_a_perfect_separator_is_significant(self):
        rows = [({'a': float(i)}, i >= 15) for i in range(30)]
        p = sr.permutation_p(rows, ['a'], trials=200)
        self.assertLess(p, 0.05)

    def test_is_deterministic_for_a_seed(self):
        rows = [({'a': i % 3}, i % 2 == 0) for i in range(20)]
        self.assertEqual(sr.permutation_p(rows, ['a'], trials=100),
                         sr.permutation_p(rows, ['a'], trials=100))


if __name__ == '__main__':
    unittest.main()
