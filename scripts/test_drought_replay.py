#!/usr/bin/env python3
"""
Durable tests for drought_replay.py. Pure stdlib, no maven/model:
run with `python3 scripts/test_drought_replay.py`.

The cases that matter are the ones this harness got wrong on its first pass and the ones
its conclusions rest on. It read merges while calling the result a replay of a brake that
counts COMPLETED SUBGOALS, which compares a proposal against a production rule that does
not exist -- so both sequences are built from one pass over the log and each is pinned
here. The scoring rule is tested at its boundary, because "halted before the peak" and
"halted at the peak" are the difference between killing a run's best artifact and saving
drift. And a rule is tested for only ever looking backwards: a brake that consults a later
cycle is a postmortem, and would score beautifully while being unbuildable.
"""
import importlib.util
import json
import os
import tempfile
import unittest

_spec = importlib.util.spec_from_file_location(
    "drought_replay", os.path.join(os.path.dirname(__file__), "drought_replay.py"))
dr = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(dr)


def _behaviour(tmp, events):
    """Behaviour logs are pretty-printed concatenated JSON, not line-JSON."""
    path = os.path.join(tmp, "behaviour.ndjson")
    with open(path, "w") as fh:
        for e in events:
            fh.write(json.dumps(e, indent=2))
            fh.write("\n")
    return path


def _cycle(cid, merged, completed=False):
    out = []
    if completed:
        out.append({"cycleId": cid, "event": "goal_status_changed",
                    "payload": {"goalId": "g", "from": "ACTIVE", "to": "COMPLETED"}})
    out.append({"cycleId": cid, "event": "cycle_end", "payload": {"merged": merged}})
    return out


class SequenceTest(unittest.TestCase):
    def test_merges_and_progress_are_read_separately(self):
        """The first version conflated these, and that is the error worth pinning."""
        with tempfile.TemporaryDirectory() as tmp:
            events = (_cycle("c1", True, completed=True)
                      + _cycle("c2", True, completed=False)
                      + _cycle("c3", False, completed=True))
            _behaviour(tmp, events)
            merged, progress = dr.sequences(tmp)
            self.assertEqual(merged, [True, True, False])
            self.assertEqual(progress, [True, False, True])

    def test_a_status_change_that_is_not_completion_is_not_progress(self):
        with tempfile.TemporaryDirectory() as tmp:
            _behaviour(tmp, [
                {"cycleId": "c1", "event": "goal_status_changed",
                 "payload": {"to": "GIVEN_UP"}},
                {"cycleId": "c1", "event": "cycle_end", "payload": {"merged": False}}])
            _, progress = dr.sequences(tmp)
            self.assertEqual(progress, [False])

    def test_a_cycle_with_no_cycle_end_is_not_a_cycle(self):
        """A run killed mid-cycle leaves a started-but-unfinished tail; counting it would
        lengthen every drought by one on exactly the runs that died badly."""
        with tempfile.TemporaryDirectory() as tmp:
            _behaviour(tmp, _cycle("c1", True) + [{"cycleId": "c2", "event": "cycle_start",
                                                   "payload": {}}])
            merged, progress = dr.sequences(tmp)
            self.assertEqual(merged, [True])
            self.assertEqual(progress, [False])

    def test_a_directory_with_no_behaviour_log_yields_nothing(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(dr.sequences(tmp), (None, None))


class FixedRuleTest(unittest.TestCase):
    def test_halts_on_the_nth_consecutive_barren_cycle(self):
        self.assertEqual(dr.rule_fixed([False] * 5, 3), 3)

    def test_a_hit_resets_the_drought(self):
        self.assertIsNone(dr.rule_fixed([False, False, True, False, False], 3))

    def test_silent_when_the_run_never_reaches_the_window(self):
        self.assertIsNone(dr.rule_fixed([False, False], 3))


class OwnRecordRuleTest(unittest.TestCase):
    def test_with_no_recovered_drought_the_record_is_zero_and_it_fires_at_once(self):
        """T2-FH assumed a run with no history 'cannot halt early'. The record starts at
        zero, so the opposite holds -- this is the measured refutation, kept as a test."""
        self.assertEqual(dr.rule_own_record([False, False], margin=0), 1)

    def test_the_margin_buys_exactly_that_many_cycles(self):
        self.assertEqual(dr.rule_own_record([False] * 9, margin=3), 4)

    def test_the_record_only_grows_on_a_drought_actually_recovered_from(self):
        # One barren cycle recovered at cycle 3 sets the record to 1, so with a margin of
        # 1 the next drought may reach 2 without firing and trips on the third.
        self.assertIsNone(dr.rule_own_record([True, False, True, False, False], margin=1))
        self.assertEqual(dr.rule_own_record([True, False, True, False, False, False],
                                            margin=1), 6)

    def test_at_margin_zero_a_record_can_never_be_established(self):
        """Firing needs current > record, and the record only grows once a drought has
        been survived -- which at margin zero the rule never allows. The corpus shows this
        as 66 of 67 runs halted before their peak."""
        self.assertEqual(dr.rule_own_record([True, True, False, False, False], margin=0), 3)

    def test_a_run_that_always_lands_work_is_never_halted(self):
        self.assertIsNone(dr.rule_own_record([True] * 6, margin=0))


class OnlineOnlyTest(unittest.TestCase):
    def test_a_rule_cannot_see_past_the_cycle_it_fires_on(self):
        """Extending a run with any future must not move an already-decided halt. A rule
        that failed this would be scoring with knowledge no brake can have."""
        base = [True, False, False, False, False]
        for rule in (lambda s: dr.rule_fixed(s, 3), lambda s: dr.rule_own_record(s, 0)):
            first = rule(base)
            for tail in ([True] * 4, [False] * 4, [True, False, True, False]):
                self.assertEqual(rule(base + tail), first)


class ScoringTest(unittest.TestCase):
    def _corpus(self, seq, peak):
        return [dict(run="r", merged=seq, progress=seq, peak=peak,
                     cycles=len(seq), terminal="deadline", smoke="SMOKE: 1/1")]

    def test_halting_before_the_peak_is_harmful(self):
        harmful, useful, silent, saved = dr.evaluate(
            self._corpus([False] * 10, peak=8), lambda s: dr.rule_fixed(s, 3), "merged")
        self.assertEqual((len(harmful), len(useful), silent), (1, 0, 0))

    def test_halting_after_the_peak_is_useful_and_saves_the_remainder(self):
        harmful, useful, silent, saved = dr.evaluate(
            self._corpus([False] * 10, peak=2), lambda s: dr.rule_fixed(s, 3), "merged")
        self.assertEqual((len(harmful), len(useful), silent), (0, 1, 0))
        self.assertEqual(saved, 7)

    def test_halting_exactly_on_the_peak_counts_as_useful_not_harmful(self):
        """The boundary decides whether a rule that stops a run at its best cycle is
        recorded as destroying that artifact or as ending the run at the right moment."""
        harmful, useful, _, _ = dr.evaluate(
            self._corpus([False] * 10, peak=3), lambda s: dr.rule_fixed(s, 3), "merged")
        self.assertEqual((len(harmful), len(useful)), (0, 1))

    def test_a_silent_rule_neither_harms_nor_saves(self):
        harmful, useful, silent, saved = dr.evaluate(
            self._corpus([True] * 10, peak=10), lambda s: dr.rule_fixed(s, 3), "merged")
        self.assertEqual((len(harmful), len(useful), silent, saved), (0, 0, 1, 0))

    def test_the_declared_signal_is_the_one_read(self):
        row = dict(run="r", merged=[True] * 6, progress=[False] * 6, peak=6,
                   cycles=6, terminal="deadline", smoke=None)
        self.assertEqual(len(dr.evaluate([row], lambda s: dr.rule_fixed(s, 3), "merged")[0]), 0)
        self.assertEqual(len(dr.evaluate([row], lambda s: dr.rule_fixed(s, 3), "progress")[0]), 1)


class CoverageTest(unittest.TestCase):
    def test_runs_without_data_are_counted_and_named_not_dropped(self):
        with tempfile.TemporaryDirectory() as tmp:
            ledger = os.path.join(tmp, "led.json")
            json.dump({"runs": {"gone": {"peaked_at_cycle": "cycle-1 (1/1)"},
                                "nopeak": {}}}, open(ledger, "w"))
            runs = os.path.join(tmp, "runs")
            os.makedirs(os.path.join(runs, "nopeak"))
            _behaviour(os.path.join(runs, "nopeak"), _cycle("c1", True))
            corpus, skipped = dr.collect(ledger, runs)
            self.assertEqual(corpus, [])
            self.assertEqual(skipped["no run directory (died with /tmp)"], 1)
            self.assertEqual(skipped["ledger row carries no peak"], 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
