#!/usr/bin/env python3
"""
Durable tests for detector_replay.py (T6-D). Pure stdlib:
`python3 scripts/test_detector_replay.py`.

The cases that carry real risk of a wrong disposition decision downstream (T5-BH):
  - a FULFILLED run below the honest smoke bar must count as BAD, not good — that is the
    false-completion class this project cares most about;
  - `fired=0` must surface as an explicit INERT finding, never as absence from the report,
    because silent inertness is the failure this harness exists to catch;
  - a rule whose inputs are not logged must report NO, never a clean pass.
"""
import contextlib
import importlib.util
import io
import json
import os
import tempfile
import unittest

_spec = importlib.util.spec_from_file_location(
    "detector_replay", os.path.join(os.path.dirname(__file__), "detector_replay.py"))
dr = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(dr)


def ev(cycle, event, payload=None, minute=0):
    return {"timestamp": "2026-07-25T10:%02d:00.000000+02:00" % minute,
            "cycleId": cycle, "event": event, "payload": payload or {}}


class DetectorReplayTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def _run_dir(self, name, events, summary=None):
        run_dir = os.path.join(self.dir, name)
        os.makedirs(run_dir, exist_ok=True)
        with open(os.path.join(run_dir, "behaviour.ndjson"), "w") as fh:
            for e in events:
                fh.write(json.dumps(e, indent=1) + "\n")
        if summary:
            with open(os.path.join(run_dir, "summary.txt"), "w") as fh:
                for k, v in summary.items():
                    fh.write("%s=%s\n" % (k, v))
        return run_dir

    def _row(self, events, summary=None, name="run"):
        c = self._run_dir(name, events, summary)
        return dr.analyse_run(name, os.path.join(c, "behaviour.ndjson"), c)

    def _report(self, run_dirs, rule=None):
        buf = io.StringIO()
        rows = []
        for c in run_dirs:
            rows.append(dr.analyse_run(os.path.basename(c),
                                       os.path.join(c, "behaviour.ndjson"), c))
        dr.report([r for r in rows if r], 0, rule, buf)
        return buf.getvalue()

    # --- outcome classification --------------------------------------------

    def test_fulfilled_below_the_bar_is_bad_not_good(self):
        # The false-completion class: FULFILLED yet under-scoring must never read as success.
        row = self._row([ev("c1", "cycle_start")],
                        {"terminal": "GOAL_FULFILLED", "smoke": "SMOKE: 14/23"})
        self.assertEqual(row["outcome"], "bad")

    def test_fulfilled_at_or_above_the_bar_is_good(self):
        row = self._row([ev("c1", "cycle_start")],
                        {"terminal": "GOAL_FULFILLED", "smoke": "SMOKE: 20/23"})
        self.assertEqual(row["outcome"], "good")

    def test_build_and_boot_failures_are_bad(self):
        for token in ("artifact-build-failed", "boot-failed"):
            row = self._row([ev("c1", "cycle_start")],
                            {"terminal": "deadline", "smoke": token}, name="r_" + token)
            self.assertEqual(row["outcome"], "bad", token)

    def test_fulfilled_without_a_score_refuses_to_guess(self):
        row = self._row([ev("c1", "cycle_start")], {"terminal": "GOAL_FULFILLED"})
        self.assertEqual(row["outcome"], "unknown")

    def test_no_summary_at_all_is_unknown_not_bad(self):
        row = self._row([ev("c1", "cycle_start"), ev("c1", "cycle_end", {"resultState": "CONTINUE"})])
        self.assertEqual(row["outcome"], "unknown")

    # --- liveness -----------------------------------------------------------

    def test_never_fired_detector_is_reported_as_an_explicit_finding(self):
        c = self._run_dir("quiet", [ev("c1", "cycle_start")])
        text = self._report([c])
        self.assertIn("ScopeThrashSignalAnalyzer", text)
        self.assertIn("INERT", text)
        self.assertIn("never fired in the whole population", text)

    def test_firings_are_counted_per_rule_not_just_per_class(self):
        # PatternSignalAnalyzer reports as "PatternSignalAnalyzer:<rule>"; the per-rule split is what
        # revealed that only one of its four rules has ever fired.
        events = [ev("c1", "cycle_start"),
                  ev("c1", "pattern_detected", {"detector": "PatternSignalAnalyzer:empty-diff",
                                                "signalEmitted": "STAGNATION"}),
                  ev("c2", "pattern_detected", {"detector": "PatternSignalAnalyzer:empty-diff",
                                                "signalEmitted": "STAGNATION"}),
                  ev("c3", "pattern_detected", {"detector": "PatternSignalAnalyzer:thrash",
                                                "signalEmitted": "LOW_CONFIDENCE"})]
        text = self._report([self._run_dir("fires", events)])
        self.assertIn("empty-diff", text)
        self.assertIn("thrash", text)
        row = self._row(events, name="fires2")
        self.assertEqual(len(row["firings"]), 3)

    # --- association --------------------------------------------------------

    def test_small_scored_population_is_flagged_as_not_evidence(self):
        c1 = self._run_dir("a", [ev("c1", "cycle_start"),
                                ev("c1", "pattern_detected", {"detector": "ScopeThrashSignalAnalyzer"})],
                          {"terminal": "deadline", "smoke": "boot-failed"})
        text = self._report([c1])
        self.assertIn("not evidence", text)
        self.assertIn("Ground truth is scarce by history", text)

    def test_terminal_proxy_tier_is_reported_separately_and_labelled_weaker(self):
        c1 = self._run_dir("f", [ev("c1", "cycle_start"),
                                ev("c1", "cycle_end", {"resultState": "GOAL_FULFILLED"}),
                                ev("c1", "pattern_detected", {"detector": "ScopeThrashSignalAnalyzer"})])
        text = self._report([c1])
        self.assertIn("tier 2: terminal proxy", text)
        self.assertIn("does not certify them", text)

    # --- replayability ------------------------------------------------------

    def test_unlogged_rules_report_NO_rather_than_a_clean_pass(self):
        text = self._report([self._run_dir("x", [ev("c1", "cycle_start")])], rule="fingerprint-repeat")
        self.assertIn("fingerprint-repeat", text)
        self.assertIn("NO", text)
        self.assertIn("refused: inputs are not present", text)
        self.assertIn("observability gap", text)

    def test_thrash_replay_counts_state_flips(self):
        events = [ev("c1", "cycle_start"), ev("c1", "mutation_negotiated", {"finalState": "ACCEPTED"}),
                  ev("c2", "cycle_start"), ev("c2", "mutation_negotiated", {"finalState": "REJECTED"}),
                  ev("c3", "cycle_start"), ev("c3", "mutation_negotiated", {"finalState": "REJECTED"}),
                  ev("c4", "cycle_start"), ev("c4", "mutation_negotiated", {"finalState": "ACCEPTED"})]
        row = self._row(events, name="flips")
        fired, decisions = dr.replay_thrash(row["_per_cycle"], row["_order"])
        self.assertEqual(decisions, 4)
        self.assertEqual(fired, ["c2", "c4"])      # two flips; the REJECTED->REJECTED repeat is not one

    def test_thrash_replay_falls_back_to_the_llm_state(self):
        events = [ev("c1", "cycle_start"), ev("c1", "mutation_scored", {"llmState": "ACCEPTED"}),
                  ev("c2", "cycle_start"), ev("c2", "mutation_scored", {"llmState": "REJECTED"})]
        row = self._row(events, name="fallback")
        fired, decisions = dr.replay_thrash(row["_per_cycle"], row["_order"])
        self.assertEqual((len(fired), decisions), (1, 2))

    def test_replay_states_its_upper_bound_caveat(self):
        c = self._run_dir("t", [ev("c1", "cycle_start"),
                               ev("c1", "mutation_negotiated", {"finalState": "ACCEPTED"}),
                               ev("c2", "cycle_start"),
                               ev("c2", "mutation_negotiated", {"finalState": "REJECTED"})])
        self.assertIn("upper bound", self._report([c], rule="thrash"))

    # --- robustness ---------------------------------------------------------

    def test_truncated_file_keeps_what_parsed(self):
        c = self._run_dir("killed", [ev("c1", "cycle_start"), ev("c2", "cycle_start")])
        path = os.path.join(c, "behaviour.ndjson")
        with open(path) as fh:
            text = fh.read()
        with open(path, "w") as fh:
            fh.write(text + '{ "timestamp": "2026-07-25T10:0')
        self.assertEqual(dr.analyse_run("killed", path, c)["cycles"], 2)

    def test_cycleless_run_is_skipped(self):
        c = self._run_dir("empty", [])
        self.assertIsNone(dr.analyse_run("empty", os.path.join(c, "behaviour.ndjson"), c))

    def test_largest_copy_of_a_repeated_run_wins(self):
        a = self._run_dir("b1", [ev("c1", "cycle_start")])
        b = self._run_dir("b2", [ev("c1", "cycle_start"), ev("c2", "cycle_start")])
        for d in (a, b):
            os.rename(os.path.join(d, "behaviour.ndjson"),
                      os.path.join(d, "behaviour-2026-07-25_10-00-00.ndjson"))
        found = dr.scan_inputs([self.dir], 0)
        self.assertEqual(len(found), 1)
        self.assertIn("b2", found[0][1])

    def test_main_exits_zero_on_a_missing_path(self):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            self.assertEqual(dr.main(["/nonexistent/run_dir"]), 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
