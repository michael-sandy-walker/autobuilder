#!/usr/bin/env python3
"""
Durable tests for outcome_ledger.py (T6-E). Pure stdlib:
`python3 scripts/test_outcome_ledger.py`.

The cases that matter are about not manufacturing ground truth. A run with no conclusive smoke
reading must be left OUT of the ledger rather than recorded as anything, because the whole point of
the file is that its rows can be trusted — and outcome ground truth is the scarcest thing here
(T6-D found it existed for 14 of 237 runs).
"""
import contextlib
import importlib.util
import io
import json
import os
import tempfile
import unittest

_spec = importlib.util.spec_from_file_location(
    "outcome_ledger", os.path.join(os.path.dirname(__file__), "outcome_ledger.py"))
ol = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ol)


class OutcomeLedgerTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        # Never touch the real committed ledger from a test.
        self._real = ol.LEDGER
        ol.LEDGER = os.path.join(self.dir, "run_outcomes.json")

    def tearDown(self):
        ol.LEDGER = self._real

    def _run_dir(self, name, summary=None):
        run_dir = os.path.join(self.dir, name)
        os.makedirs(run_dir, exist_ok=True)
        if summary is not None:
            with open(os.path.join(run_dir, "summary.txt"), "w") as fh:
                for k, v in summary.items():
                    fh.write("%s=%s\n" % (k, v))
        return run_dir

    def _append(self, run_dir):
        with contextlib.redirect_stdout(io.StringIO()):
            return ol.append(run_dir)

    # --- what counts as a conclusive outcome --------------------------------

    def test_numeric_smoke_is_scored(self):
        self.assertTrue(ol.is_scored({"smoke": "SMOKE: 19/23"}))

    def test_build_and_boot_failures_are_conclusive_outcomes(self):
        # The artifact did not work — that IS ground truth, not a missing measurement.
        self.assertTrue(ol.is_scored({"smoke": "artifact-build-failed"}))
        self.assertTrue(ol.is_scored({"smoke": "boot-failed"}))

    def test_skipped_or_absent_smoke_is_not_scored(self):
        self.assertFalse(ol.is_scored({"smoke": "skipped"}))
        self.assertFalse(ol.is_scored({"terminal": "deadline"}))
        self.assertFalse(ol.is_scored({}))

    def test_unscored_run_is_refused_not_recorded_as_unknown(self):
        row = self._append(self._run_dir("unscored", {"terminal": "deadline", "smoke": "skipped"}))
        self.assertIsNone(row)
        self.assertEqual(ol.load()["runs"], {})

    def test_run_dir_without_a_summary_is_refused(self):
        self.assertIsNone(self._append(self._run_dir("bare")))

    # --- recording ----------------------------------------------------------

    def test_append_records_the_carried_fields(self):
        row = self._append(self._run_dir("2026-07-24_20-49-36", {
            "terminal": "GOAL_FULFILLED", "smoke": "SMOKE: 19/23",
            "smoke_preserved": "SMOKE: 19/23", "preserved_source": "loop-preserved",
            "score_delta": "+0", "total_cycles": "67", "merges": "19", "noise": "ignored"}))
        self.assertEqual(row["terminal"], "GOAL_FULFILLED")
        self.assertEqual((row["smoke_pass"], row["smoke_total"]), (19, 23))
        self.assertEqual(row["merges"], "19")
        self.assertNotIn("noise", row)                  # only the carried field set is kept
        self.assertIn("recorded", row)

    def test_criteria_executability_is_carried_when_the_run_reported_it(self):
        # T2-EX: the six-run question — is authoring quality a cause of a bad run or a symptom of
        # one — is only answerable if every run's share lands in the ledger as it happens.
        row = self._append(self._run_dir("2026-07-30_06-28-19", {
            "terminal": "deadline", "smoke": "SMOKE: 18/23",
            "criteria_executable": "31", "criteria_authored": "71"}))
        self.assertEqual(row["criteria_executable"], "31")
        self.assertEqual(row["criteria_authored"], "71")

    def test_a_run_predating_the_readout_carries_no_executability_rather_than_a_zero(self):
        # A run that never reported the figure has an UNKNOWN share, not a share of zero; recording
        # zero would make the older half of the ledger look uniformly unverifiable.
        row = self._append(self._run_dir("2026-07-24_20-49-36", {
            "terminal": "GOAL_FULFILLED", "smoke": "SMOKE: 19/23"}))
        self.assertNotIn("criteria_executable", row)
        self.assertNotIn("criteria_authored", row)

    def test_both_sides_of_the_readout_trade_are_carried(self):
        # T4-DA: recording clauses closed without recording what the run paid for them would let a
        # costly mechanism read as a free win. The watch judges net effect, so both land.
        row = self._append(self._run_dir("2026-07-30_18-39-00", {
            "terminal": "deadline", "smoke": "SMOKE: 18/23",
            "clauses_closed_vs_control": "1", "median_cycle_seconds": "162"}))
        self.assertEqual(row["clauses_closed_vs_control"], "1")
        self.assertEqual(row["median_cycle_seconds"], "162")

    def test_append_is_idempotent_and_keyed_by_run_id(self):
        c = self._run_dir("2026-07-24_10-24-15", {"terminal": "GOAL_FULFILLED", "smoke": "SMOKE: 23/23"})
        self._append(c)
        self._append(c)
        runs = ol.load()["runs"]
        self.assertEqual(list(runs), ["2026-07-24_10-24-15"])

    def test_a_failure_row_records_no_pass_count(self):
        row = self._append(self._run_dir("failed", {"terminal": "deadline", "smoke": "boot-failed"}))
        self.assertNotIn("smoke_pass", row)
        self.assertEqual(row["smoke"], "boot-failed")

    def test_ledger_survives_a_corrupt_file(self):
        with open(ol.LEDGER, "w") as fh:
            fh.write("{not json")
        self.assertEqual(ol.load()["runs"], {})
        self.assertIsNotNone(self._append(
            self._run_dir("after", {"terminal": "deadline", "smoke": "boot-failed"})))

    # --- seeding + report ---------------------------------------------------

    def test_seed_finds_nested_run_dirs_and_skips_unscored_ones(self):
        self._run_dir("a", {"terminal": "GOAL_FULFILLED", "smoke": "SMOKE: 21/23"})
        self._run_dir("b", {"terminal": "deadline", "smoke": "artifact-build-failed"})
        self._run_dir("c", {"terminal": "deadline", "smoke": "skipped"})
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            ol.seed([self.dir])
        runs = ol.load()["runs"]
        self.assertEqual(sorted(runs), ["a", "b"])

    def test_report_states_the_non_retroactive_scope(self):
        self._append(self._run_dir("r1", {"terminal": "GOAL_FULFILLED", "smoke": "SMOKE: 23/23"}))
        buf = io.StringIO()
        ol.report(ol.load(), buf)
        text = buf.getvalue()
        self.assertIn("1 run(s) recorded", text)
        self.assertIn("never scored and cannot be", text)     # the honest scope limit
        self.assertIn("grows forward", text)

    def test_report_counts_a_below_bar_fulfilled_run_as_bad(self):
        self._append(self._run_dir("false", {"terminal": "GOAL_FULFILLED", "smoke": "SMOKE: 14/23"}))
        buf = io.StringIO()
        ol.report(ol.load(), buf)
        self.assertIn("0 good · 1 bad", buf.getvalue())

    def test_empty_ledger_report_is_not_an_error(self):
        buf = io.StringIO()
        ol.report(ol.load(), buf)
        self.assertIn("empty", buf.getvalue())

    def test_main_exits_zero_on_a_missing_run_dir(self):
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(ol.main(["--append", "/nonexistent/run-dir"]), 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
