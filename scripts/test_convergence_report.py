#!/usr/bin/env python3
"""
Durable tests for convergence_report.py. Pure stdlib, no maven/model:
run with `python3 scripts/test_convergence_report.py`.

The cases that matter are the ones the ledger's own shape invites getting wrong: a
build/boot failure is a ZERO and not missing data, while a genuinely absent field is
missing and not a zero -- conflating those two is exactly what inflated an earlier
baseline by dropping 26 failed runs from the mean. The failure decomposition is tested
because the two kinds of zero need different fixes, and a separation statistic computed
from one sample is tested to return nothing rather than a confident number.
"""
import contextlib
import gzip
import importlib.util
import io
import json
import os
import tempfile
import unittest

_spec = importlib.util.spec_from_file_location(
    "convergence_report", os.path.join(os.path.dirname(__file__), "convergence_report.py"))
cr = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cr)


class SmokeScoreTest(unittest.TestCase):
    def test_numeric_score_is_parsed(self):
        self.assertEqual(cr.smoke_score("SMOKE: 23/23"), 23)
        self.assertEqual(cr.smoke_score("SMOKE: 5/23"), 5)

    def test_build_and_boot_failure_are_zero_not_missing(self):
        # The distinction the earlier baseline got wrong: these runs produced an artifact
        # that passes nothing. That is a measurement of zero, not an absence of measurement.
        self.assertEqual(cr.smoke_score("artifact-build-failed"), 0)
        self.assertEqual(cr.smoke_score("boot-failed"), 0)

    def test_absent_smoke_is_missing_not_zero(self):
        self.assertIsNone(cr.smoke_score(None))


class FeatureTest(unittest.TestCase):
    def test_absent_field_yields_absent_feature(self):
        f = cr.features({"merges": "10"})
        self.assertEqual(f["merges"], 10.0)
        self.assertNotIn("total_cycles", f)
        self.assertNotIn("merges_per_cycle", f)  # no denominator => no rate, not a zero rate

    def test_rates_are_derived_when_both_parts_present(self):
        f = cr.features({"merges": "10", "total_cycles": "50", "run_duration_min": "25"})
        self.assertAlmostEqual(f["merges_per_cycle"], 0.2)
        self.assertAlmostEqual(f["cycles_per_min"], 2.0)

    def test_zero_denominator_does_not_raise(self):
        f = cr.features({"merges": "3", "total_cycles": "0", "run_duration_min": "0"})
        self.assertNotIn("merges_per_cycle", f)
        self.assertNotIn("cycles_per_min", f)

    def test_peak_position_is_a_fraction_of_the_run(self):
        self.assertAlmostEqual(cr.features({"peaked_at_cycle": "cycle-9 (71/74)"})["peak_position"],
                               71 / 74)
        # Still improving at the end reads as 1.0 -- the run was cut off, not drifting.
        self.assertAlmostEqual(cr.features({"peaked_at_cycle": "cycle-9 (74/74)"})["peak_position"],
                               1.0)

    def test_unparseable_peak_is_absent(self):
        self.assertNotIn("peak_position", cr.features({"peaked_at_cycle": "none"}))


class BandTest(unittest.TestCase):
    def test_bands_cover_the_scale(self):
        self.assertEqual(cr.band(0), "failed")
        self.assertEqual(cr.band(15), "partial")
        self.assertEqual(cr.band(16), "strong")
        self.assertEqual(cr.band(23), "perfect")


class SeparationTest(unittest.TestCase):
    def test_returns_none_when_a_sample_is_too_small(self):
        self.assertIsNone(cr.cohens_d([1.0], [2.0, 3.0]))

    def test_returns_none_when_there_is_no_spread(self):
        self.assertIsNone(cr.cohens_d([2.0, 2.0], [2.0, 2.0]))

    def test_sign_says_which_side_is_higher(self):
        d = cr.cohens_d([10.0, 12.0], [1.0, 3.0])
        self.assertGreater(d, 0)


class FailureSplitTest(unittest.TestCase):
    def test_drifted_and_never_there_are_separated(self):
        runs = [
            ("drifted", 0, {}, {"drift_tail_cycles": 3.0}),
            ("never", 0, {}, {"drift_tail_cycles": 0.0}),
            ("never-nodata", 0, {}, {}),
            ("fine", 23, {}, {"drift_tail_cycles": 0.0}),
        ]
        drifted, never = cr.failure_split(runs)
        self.assertEqual(drifted, ["drifted"])
        self.assertEqual(sorted(never), ["never", "never-nodata"])

    def test_a_successful_run_is_never_a_failure(self):
        drifted, never = cr.failure_split([("ok", 22, {}, {"drift_tail_cycles": 9.0})])
        self.assertEqual((drifted, never), ([], []))


class EndToEndTest(unittest.TestCase):
    def _ledger(self, runs):
        fh = tempfile.NamedTemporaryFile("w", suffix=".json", delete=False)
        json.dump({"note": "test", "runs": runs}, fh)
        fh.close()
        return fh.name

    def test_machine_readable_last_line(self):
        path = self._ledger({
            "a": {"smoke": "SMOKE: 23/23", "merges": "28", "total_cycles": "73",
                  "drift_tail_cycles": "0"},
            "b": {"smoke": "artifact-build-failed", "merges": "18", "total_cycles": "74",
                  "drift_tail_cycles": "3"},
            "c": {"smoke": "boot-failed", "merges": "5", "total_cycles": "51",
                  "drift_tail_cycles": "0"},
        })
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = cr.main(["--ledger", path])
        os.unlink(path)
        self.assertEqual(rc, 0)
        last = buf.getvalue().strip().splitlines()[-1]
        self.assertIn("runs=3", last)
        self.assertIn("failed=2", last)
        self.assertIn("drifted=1", last)
        self.assertIn("never-there=1", last)

    def test_empty_ledger_reports_rather_than_crashes(self):
        path = self._ledger({})
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = cr.main(["--ledger", path])
        os.unlink(path)
        self.assertEqual(rc, 1)
        self.assertIn("runs=0", buf.getvalue())

    def test_rows_that_are_not_dicts_are_skipped(self):
        path = self._ledger({"note": "a string row", "a": {"smoke": "SMOKE: 20/23"}})
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            cr.main(["--ledger", path])
        os.unlink(path)
        self.assertIn("runs=1", buf.getvalue())


class BehaviourParsingTest(unittest.TestCase):
    def _write(self, text):
        fh = tempfile.NamedTemporaryFile("w", suffix=".ndjson", delete=False)
        fh.write(text); fh.close()
        return fh.name

    def test_concatenated_pretty_printed_objects_are_parsed(self):
        # The file is named .ndjson but is NOT line-delimited -- a line reader gets zero events.
        path = self._write('{\n  "event": "a"\n}\n{\n  "event": "b"\n}\n')
        evs = cr.read_behaviour(path); os.unlink(path)
        self.assertEqual([e["event"] for e in evs], ["a", "b"])

    def test_truncated_trailing_object_ends_the_scan_without_failing(self):
        # A run killed at its deadline leaves a half-written object; earlier events still count.
        path = self._write('{"event": "a"}\n{"event": "b"')
        evs = cr.read_behaviour(path); os.unlink(path)
        self.assertEqual([e["event"] for e in evs], ["a"])


def _cyc(cid, merged, outcome="COMPILE_OK", state="PROGRESSING"):
    return {"cycleId": cid, "event": "cycle_end",
            "payload": {"merged": merged, "buildOutcome": outcome, "resultState": state}}


class PrefixFeatureTest(unittest.TestCase):
    def test_truncates_by_cycle_not_by_event(self):
        evs = [_cyc("c1", True), _cyc("c2", True), _cyc("c3", False)]
        f = cr.prefix_features(evs, 2)
        self.assertEqual(f["cycles"], 2)
        self.assertAlmostEqual(f["merge_rate"], 1.0)   # c3 excluded

    def test_rates_reflect_the_prefix(self):
        evs = [_cyc("c1", True), _cyc("c2", False, "COMPILE_ERROR", "STAGNATING")]
        f = cr.prefix_features(evs, 5)
        self.assertAlmostEqual(f["merge_rate"], 0.5)
        self.assertAlmostEqual(f["compile_ok_rate"], 0.5)
        self.assertAlmostEqual(f["stagnating_rate"], 0.5)

    def test_repair_concentrated_on_one_file_scores_higher_than_spread(self):
        def fix(cid, files):
            return {"cycleId": cid, "event": "build_fix_attempt",
                    "payload": {"attemptNumber": 1, "mutatedFiles": files}}
        same = cr.prefix_features([_cyc("c1", False), fix("c1", ["A"]), fix("c1", ["A"])], 5)
        spread = cr.prefix_features([_cyc("c1", False), fix("c1", ["A"]), fix("c1", ["B"])], 5)
        self.assertGreater(same["fix_file_concentration"], spread["fix_file_concentration"])

    def test_no_completed_cycles_yields_nothing(self):
        self.assertIsNone(cr.prefix_features([{"cycleId": "c1", "event": "cycle_start"}], 5))


class ArchiveReadingTest(unittest.TestCase):
    """The T6-N archive stores the behaviour file gzipped. If the instrument cannot read the
    archive, retaining it buys nothing -- that is the whole point of keeping it."""

    def test_gzipped_behaviour_file_is_read(self):
        d = tempfile.mkdtemp()
        with gzip.open(os.path.join(d, "behaviour.ndjson.gz"), "wt", encoding="utf-8") as fh:
            json.dump(_cyc("c1", True), fh)
        evs = cr.read_behaviour(os.path.join(d, "behaviour.ndjson.gz"))
        self.assertEqual(len(evs), 1)

    def test_prefix_mode_accepts_an_archived_dir(self):
        d = tempfile.mkdtemp()
        with gzip.open(os.path.join(d, "behaviour.ndjson.gz"), "wt", encoding="utf-8") as fh:
            json.dump(_cyc("c1", True), fh)
        with open(os.path.join(d, "summary.txt"), "w") as fh:
            fh.write("smoke=SMOKE: 23/23\n")
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            cr.prefix_mode([d], 15)
        self.assertIn("CONVERGENCE-PREFIX: runs=1", buf.getvalue())


class PrefixModeTest(unittest.TestCase):
    def test_machine_readable_line_and_missing_dirs_are_skipped(self):
        d = tempfile.mkdtemp()
        with open(os.path.join(d, "behaviour.ndjson"), "w") as fh:
            json.dump(_cyc("c1", True), fh); fh.write("\n")
        with open(os.path.join(d, "summary.txt"), "w") as fh:
            fh.write("smoke=SMOKE: 23/23\n")
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            cr.prefix_mode([d, tempfile.mkdtemp()], 15)
        out = buf.getvalue()
        self.assertIn("no behaviour.ndjson -- skipped", out)
        self.assertIn("CONVERGENCE-PREFIX: runs=1 prefix=15", out)

    def test_outcome_reads_a_build_failure_as_zero(self):
        d = tempfile.mkdtemp()
        with open(os.path.join(d, "summary.txt"), "w") as fh:
            fh.write("terminal=deadline\nsmoke=artifact-build-failed\n")
        self.assertEqual(cr.run_dir_outcome(d), 0)

    def test_missing_summary_is_unknown_not_zero(self):
        self.assertIsNone(cr.run_dir_outcome(tempfile.mkdtemp()))


if __name__ == "__main__":
    unittest.main(verbosity=2)
