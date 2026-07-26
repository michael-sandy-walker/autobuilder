#!/usr/bin/env python3
"""
Durable tests for drift_aggregate.py (T6-B cross-run drift aggregate). Pure stdlib, no maven/model:
run with `python3 scripts/test_drift_aggregate.py`.

The cases that matter are the ones the field batch was too small to produce: a run that peaks
mid-way and keeps CHURNING afterwards, versus one that peaks mid-way and merely IDLES, versus a
run that stays productive to its last cycle. The churn/idle split is run-relative, so a slow run
whose tail matches its own pace must read as churn.
"""
import contextlib
import importlib.util
import io
import json
import os
import tempfile
import unittest

_spec = importlib.util.spec_from_file_location(
    "drift_aggregate", os.path.join(os.path.dirname(__file__), "drift_aggregate.py"))
da = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(da)


def ev(cycle, event, minute, payload=None):
    return {
        "timestamp": "2026-07-24T10:%02d:00.123456789+02:00" % minute,
        "cycleId": cycle,
        "event": event,
        "payload": payload or {},
    }


def write_behaviour(path, events):
    """Behaviour files are pretty-printed concatenated JSON objects, not line-JSON."""
    with open(path, "w") as fh:
        for e in events:
            fh.write(json.dumps(e, indent=2) + "\n")


class DriftAggregateTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def _run_dir(self, name, events, summary=None):
        run_dir = os.path.join(self.dir, name)
        os.makedirs(run_dir)
        write_behaviour(os.path.join(run_dir, "behaviour.ndjson"), events)
        if summary:
            with open(os.path.join(run_dir, "summary.txt"), "w") as fh:
                for k, v in summary.items():
                    fh.write("%s=%s\n" % (k, v))
        return run_dir

    def _row(self, events, summary=None, name="run"):
        run_dir = self._run_dir(name, events, summary)
        return da.analyse_run(name, os.path.join(run_dir, "behaviour.ndjson"), run_dir)

    def _report(self, paths, kind="any"):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            da.main(list(paths) + ["--kind", kind])
        return buf.getvalue()

    # --- tail measurement ---------------------------------------------------

    def test_peaks_midway_and_churns_on(self):
        # 4 cycles; last merge in cycle 2; cycles 3-4 keep proposing at the same rate -> churn.
        events = [
            ev("c1", "mutation_proposed", 0), ev("c1", "cycle_end", 1, {"resultState": "CONTINUE"}),
            ev("c2", "mutation_proposed", 2), ev("c2", "mutation_merged", 3),
            ev("c3", "mutation_proposed", 4), ev("c3", "mutation_rejected", 5),
            ev("c4", "mutation_proposed", 6),
            ev("c4", "cycle_end", 7, {"resultState": "STAGNATING"}),
        ]
        row = self._row(events)
        self.assertEqual(row["cycles"], 4)
        self.assertEqual(row["merges"], 1)
        self.assertEqual(row["terminal"], "STAGNATING")
        d = row["any"]
        self.assertEqual((d["peak_k"], d["tail_cycles"]), (2, 2))
        self.assertEqual(d["peak_frac"], 0.5)
        self.assertEqual(d["tail_frac"], 0.5)
        self.assertEqual(d["tail_class"], "churn")   # tail rate 1.0 vs pre-peak rate 1.0
        self.assertEqual(d["tail_work_ratio"], 1.0)
        self.assertEqual(d["tail_min"], 4.0)         # cycle-2 first stamp (10:02) -> last (10:06)

    def test_idle_tail_is_not_churn(self):
        # Tail cycles tick but attempt nothing -> idle, the livelock shape.
        events = [
            ev("c1", "mutation_proposed", 0), ev("c1", "mutation_proposed", 1),
            ev("c2", "mutation_merged", 2),
            ev("c3", "agent_skipped", 3), ev("c4", "agent_skipped", 4),
        ]
        d = self._row(events)["any"]
        self.assertEqual(d["tail_cycles"], 2)
        self.assertEqual(d["tail_work_ratio"], 0.0)
        self.assertEqual(d["tail_class"], "idle")

    def test_productive_to_the_last_cycle_has_no_tail(self):
        events = [ev("c1", "mutation_proposed", 0), ev("c2", "mutation_merged", 1)]
        d = self._row(events)["any"]
        self.assertEqual((d["tail_cycles"], d["tail_frac"]), (0, 0.0))
        self.assertEqual(d["peak_frac"], 1.0)
        self.assertEqual(d["tail_class"], "none")

    def test_slow_run_matching_its_own_pace_reads_as_churn(self):
        # Pre-peak rate 0.5 attempts/cycle; tail rate 0.5 -> run-relative, so churn despite
        # being half the pace of the fast run in the first test.
        events = [
            ev("c1", "mutation_proposed", 0), ev("c2", "agent_skipped", 1),
            ev("c2", "mutation_merged", 2),
            ev("c3", "build_fix_attempt", 3), ev("c4", "agent_skipped", 4),
        ]
        d = self._row(events)["any"]
        self.assertEqual(d["tail_work_ratio"], 1.0)
        self.assertEqual(d["tail_class"], "churn")

    # --- the three productivity readings -----------------------------------

    def test_each_productivity_reading_finds_its_own_peak(self):
        events = [
            ev("c1", "mutation_merged", 0),
            ev("c2", "mutation_scored", 1, {"criteriaAssessments": ["[1] ✓ book persists"]}),
            ev("c3", "goal_status_changed", 2, {"to": "COMPLETED"}),
            ev("c4", "agent_skipped", 3),
        ]
        row = self._row(events)
        self.assertEqual(row["merge"]["peak_k"], 1)
        self.assertEqual(row["criteria"]["peak_k"], 2)
        self.assertEqual(row["closure"]["peak_k"], 3)
        self.assertEqual(row["any"]["peak_k"], 3)     # latest of the three -> smallest tail
        self.assertEqual(row["any"]["tail_cycles"], 1)

    def test_only_a_first_time_criterion_pass_counts_as_progress(self):
        # Re-passing an already-passed criterion is not new progress; a failing one never was.
        events = [
            ev("c1", "mutation_scored", 0, {"criteriaAssessments": ["[1] ✓ isbn validated"]}),
            ev("c2", "mutation_scored", 1, {"criteriaAssessments": ["[1] ✓ isbn validated",
                                                                   "[2] ✗ loan persists"]}),
            ev("c3", "mutation_scored", 2, {"criteriaAssessments": ["[2] ✗ loan persists"]}),
        ]
        row = self._row(events)
        self.assertEqual(row["criteria"]["peak_k"], 1)
        self.assertEqual(row["criteria"]["tail_cycles"], 2)
        self.assertIsNone(row["merge"])               # never merged under this reading

    def test_run_with_no_productive_event_at_all(self):
        row = self._row([ev("c1", "agent_skipped", 0), ev("c2", "agent_skipped", 1)])
        self.assertEqual(row["cycles"], 2)
        for kind in da.PRODUCTIVITY_KINDS:
            self.assertIsNone(row[kind])

    # --- tokens -------------------------------------------------------------

    def test_token_tail_measured_when_instrumented(self):
        events = [
            ev("c1", "llm_call", 0, {"totalTokens": 100}), ev("c1", "mutation_merged", 1),
            ev("c2", "llm_call", 2, {"totalTokens": 300}),
        ]
        row = self._row(events)
        self.assertTrue(row["instrumented"])
        self.assertEqual(row["tokens"], 400)
        self.assertEqual(row["any"]["tail_tokens"], 300)
        self.assertEqual(row["any"]["tail_token_frac"], 0.75)

    def test_uninstrumented_run_reports_no_token_frac_rather_than_zero(self):
        # Pre-T5-R runs carry no llm_call at all — a fake 0.0 would understate corpus-wide waste.
        row = self._row([ev("c1", "mutation_merged", 0), ev("c2", "agent_skipped", 1)])
        self.assertFalse(row["instrumented"])
        self.assertIsNone(row["any"]["tail_token_frac"])

    # --- run_dir fields, robustness, report ---------------------------------

    def test_summary_dual_score_fields_are_folded_in(self):
        row = self._row([ev("c1", "mutation_merged", 0)],
                        summary={"terminal": "GOAL_FULFILLED", "smoke": "SMOKE: 15/23",
                                 "smoke_preserved": "SMOKE: 20/23", "score_delta": "+5"})
        self.assertEqual(row["summary_score_delta"], "+5")
        self.assertEqual(row["summary_smoke_preserved"], "SMOKE: 20/23")

    def test_score_delta_num_ignores_incomparable_readings(self):
        self.assertEqual(da.score_delta_num("+5"), 5)
        self.assertEqual(da.score_delta_num("-2"), -2)
        self.assertIsNone(da.score_delta_num("n/a (boot-failed vs boot-failed)"))
        self.assertIsNone(da.score_delta_num(""))

    def test_truncated_file_keeps_what_parsed(self):
        # An externally killed run leaves a half-written object; the run must still be readable.
        run_dir = self._run_dir("killed", [ev("c1", "mutation_merged", 0), ev("c2", "agent_skipped", 1)])
        path = os.path.join(run_dir, "behaviour.ndjson")
        with open(path) as fh:
            text = fh.read()
        with open(path, "w") as fh:
            fh.write(text + '{ "timestamp": "2026-07-24T10:09:0')
        row = da.analyse_run("killed", path, run_dir)
        self.assertEqual(row["cycles"], 2)

    def test_empty_and_cycleless_inputs_are_skipped_not_fatal(self):
        empty = self._run_dir("empty", [])
        self.assertIsNone(da.analyse_run("empty", os.path.join(empty, "behaviour.ndjson"), empty))

    def test_largest_copy_wins_when_backups_repeat_a_run(self):
        # The same run's log copied into two backup dirs must be counted once.
        short = self._run_dir("b1", [ev("c1", "mutation_merged", 0)])
        long = self._run_dir("b2", [ev("c1", "mutation_merged", 0), ev("c2", "agent_skipped", 1)])
        os.rename(os.path.join(short, "behaviour.ndjson"),
                  os.path.join(short, "behaviour-2026-07-24_10-00-00.ndjson"))
        os.rename(os.path.join(long, "behaviour.ndjson"),
                  os.path.join(long, "behaviour-2026-07-24_10-00-00.ndjson"))
        found = da.scan_inputs([self.dir], 0)
        self.assertEqual(len(found), 1)
        self.assertIn("b2", found[0][1])            # the fuller copy

    def test_report_renders_a_distribution_over_several_runs(self):
        a = self._run_dir("runA", [ev("c1", "mutation_proposed", 0), ev("c2", "mutation_merged", 1),
                                  ev("c3", "mutation_proposed", 2), ev("c4", "mutation_proposed", 3)])
        b = self._run_dir("runB", [ev("c1", "mutation_merged", 0)],
                         summary={"score_delta": "+3"})
        text = self._report([a, b])
        self.assertIn("T6-B cross-run drift aggregate — 2 runs", text)
        self.assertIn("Tail character", text)
        self.assertIn("ended worse than its best saved state: 1/1", text)
        self.assertIn("no run in this batch carries llm_call token telemetry", text)

    def test_per_run_table_lists_every_run_when_unlimited(self):
        rows = [self._row([ev("c1", "mutation_merged", 0), ev("c2", "agent_skipped", 1)], name="r%d" % i)
                for i in range(3)]
        buf = io.StringIO()
        da.per_run_table(rows, "any", None, buf)
        text = buf.getvalue()
        for i in range(3):
            self.assertIn("r%d" % i, text)
        self.assertNotIn("more runs", text)

    def test_per_run_table_truncates_and_says_so(self):
        rows = [self._row([ev("c1", "mutation_merged", 0), ev("c2", "agent_skipped", 1)], name="r%d" % i)
                for i in range(4)]
        buf = io.StringIO()
        da.per_run_table(rows, "any", 2, buf)
        self.assertIn("top 2", buf.getvalue())
        self.assertIn("... 2 more runs", buf.getvalue())

    def test_report_on_a_batch_with_no_productive_event_does_not_crash(self):
        a = self._run_dir("idle", [ev("c1", "agent_skipped", 0)])
        self.assertIn("nothing to distribute", self._report([a]))


if __name__ == "__main__":
    unittest.main(verbosity=2)
