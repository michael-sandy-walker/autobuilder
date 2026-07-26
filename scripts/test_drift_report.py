#!/usr/bin/env python3
"""
Durable tests for drift_report.py (T6-A drift overview). Pure stdlib, no maven/model:
run with `python3 scripts/test_drift_report.py`. The key case is a run that PEAKS mid-way
and drifts after — the situation the mechanism exists to expose, which the first field runs
did not produce (both peaked at their final cycle).
"""
import contextlib
import importlib.util
import io
import os
import tempfile
import unittest

_spec = importlib.util.spec_from_file_location(
    "drift_report", os.path.join(os.path.dirname(__file__), "drift_report.py"))
dr = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(dr)

LOG = """2026-07-24 10:00:00.000 [scheduling-1] INFO x - CYCLE cycle-1 goal=g result=CONTINUE
2026-07-24 10:05:00.000 [scheduling-1] INFO x - CYCLE cycle-2 goal=g result=CONTINUE
2026-07-24 10:10:00.000 [scheduling-1] INFO x - CYCLE cycle-3 goal=g result=CONTINUE
2026-07-24 10:15:00.000 [scheduling-1] INFO x - CYCLE cycle-4 goal=g result=GOAL_FULFILLED
"""


class DriftReportTest(unittest.TestCase):
    def setUp(self):
        self.run_dir = tempfile.mkdtemp()
        with open(os.path.join(self.run_dir, "autobuilder.log"), "w") as fh:
            fh.write(LOG)

    def _run(self, final, preserved, source):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            dr.main([self.run_dir, "--final", final, "--preserved", preserved, "--source", source])
        return dict(line.split("=", 1) for line in buf.getvalue().splitlines())

    def test_score_num_parses_and_ignores_tokens(self):
        self.assertEqual(dr.score_num("SMOKE: 20/23"), 20)
        self.assertIsNone(dr.score_num("artifact-build-failed"))
        self.assertIsNone(dr.score_num(""))

    def test_drift_when_run_peaks_midway(self):
        # best saved = cycle-2 (2nd of 4); final 15, preserved 20 -> the run drifted downhill.
        out = self._run("SMOKE: 15/23", "SMOKE: 20/23", "reconstructed:cycle-2")
        self.assertEqual(out["score_delta"], "+5")               # ended 5 worse than its best
        self.assertEqual(out["total_cycles"], "4")
        self.assertEqual(out["run_duration_min"], "15.0")
        self.assertEqual(out["peaked_at_cycle"], "cycle-2 (2/4)")
        self.assertEqual(out["drift_tail_cycles"], "2")          # 2 cycles after the peak
        self.assertEqual(out["drift_tail_min"], "10.0")

    def test_no_drift_when_peak_is_final_cycle(self):
        out = self._run("SMOKE: 23/23", "SMOKE: 23/23", "reconstructed:cycle-4")
        self.assertEqual(out["score_delta"], "+0")
        self.assertEqual(out["peaked_at_cycle"], "cycle-4 (4/4)")
        self.assertEqual(out["drift_tail_cycles"], "0")

    def test_delta_na_when_a_score_is_a_failure_token(self):
        out = self._run("artifact-build-failed", "artifact-build-failed", "loop-preserved")
        self.assertTrue(out["score_delta"].startswith("n/a"))

    def test_peak_cycle_not_in_log_degrades_gracefully(self):
        out = self._run("SMOKE: 10/23", "SMOKE: 12/23", "reconstructed:cycle-999")
        self.assertEqual(out["score_delta"], "+2")               # delta still computed
        self.assertIn("not-in-run-log", out["peaked_at_cycle"])
        self.assertEqual(out["drift_tail_cycles"], "n/a")

    def test_missing_log_all_na_but_delta_survives(self):
        empty = tempfile.mkdtemp()
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            dr.main([empty, "--final", "SMOKE: 5/23", "--preserved", "SMOKE: 9/23", "--source", ""])
        out = dict(line.split("=", 1) for line in buf.getvalue().splitlines())
        self.assertEqual(out["score_delta"], "+4")
        self.assertEqual(out["total_cycles"], "n/a")


if __name__ == "__main__":
    unittest.main(verbosity=2)
