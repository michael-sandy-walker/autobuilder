#!/usr/bin/env python3
"""
Durable tests for run_profile.py's frozen health baseline. Pure stdlib:
`python3 scripts/test_run_profile_baseline.py`.

Why this exists: the phase-2 health report was silently inert in the field from 2026-07-17 to
2026-07-25 — `run_labels.json` is committed, but the envelope was rebuilt by re-profiling the
corpora it names, and those live in `/tmp/preflight_runs`, which a reboot clears. Every field report
read "healthy baseline too small (0) — no comparison" and nothing noticed, because an instrument
that abstains looks the same as one that has nothing to say. These tests pin the durable path.
"""
import contextlib
import importlib.util
import io
import json
import os
import tempfile
import unittest

_spec = importlib.util.spec_from_file_location(
    "run_profile", os.path.join(os.path.dirname(__file__), "run_profile.py"))
rp = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(rp)


def behaviour(cycles=6, merged=3, extra=()):
    """A minimal behaviour log: enough cycle_start events to clear the 3-cycle floor."""
    events = []
    for i in range(cycles):
        events.append({"cycleId": "cycle-%d" % i, "event": "cycle_start", "payload": {}})
        events.append({"cycleId": "cycle-%d" % i, "event": "agent_selected",
                       "payload": {"role": "evolution"}})
        if i < merged:
            events.append({"cycleId": "cycle-%d" % i, "event": "mutation_proposed", "payload": {}})
            events.append({"cycleId": "cycle-%d" % i, "event": "mutation_merged", "payload": {}})
    events.extend(extra)
    return "\n".join(json.dumps(e, indent=1) for e in events)


class FrozenBaselineTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.logs = os.path.join(self.dir, "logs")
        os.makedirs(self.logs)

    def _log(self, stamp, **kw):
        path = os.path.join(self.logs, "behaviour-%s.ndjson" % stamp)
        with open(path, "w") as fh:
            fh.write(behaviour(**kw))
        return path

    def _corpus(self, name, **kw):
        corpus = os.path.join(self.dir, name)
        os.makedirs(corpus, exist_ok=True)
        with open(os.path.join(corpus, "behaviour.ndjson"), "w") as fh:
            fh.write(behaviour(**kw))
        return corpus

    # --- timestamp matching -------------------------------------------------

    def test_stamp_seconds_parses_corpus_and_log_names(self):
        self.assertEqual(rp.stamp_seconds("2026-07-17_09-41-34"), ("2026-07-17", 9 * 3600 + 41 * 60 + 34))
        self.assertEqual(rp.stamp_seconds("behaviour-2026-07-17_09-41-58.ndjson"),
                         ("2026-07-17", 9 * 3600 + 41 * 60 + 58))
        self.assertIsNone(rp.stamp_seconds("no-timestamp-here"))
        self.assertIsNone(rp.stamp_seconds(None))

    def test_matches_the_nearest_log_within_tolerance(self):
        # The JVM opens its log seconds after the corpus dir is made (observed 12-24s).
        near = self._log("2026-07-17_09-41-58")
        far = self._log("2026-07-17_09-45-00")
        self.assertEqual(rp.match_behaviour_log("2026-07-17_09-41-34", [far, near]), near)

    def test_rejects_a_log_beyond_tolerance_or_on_another_day(self):
        late = self._log("2026-07-17_10-41-34")
        other_day = self._log("2026-07-18_09-41-40")
        self.assertIsNone(rp.match_behaviour_log("2026-07-17_09-41-34", [late, other_day]))

    # --- freezing -----------------------------------------------------------

    def test_freeze_reconstructs_vectors_from_logs_when_corpora_are_gone(self):
        self._log("2026-07-17_09-41-58")
        self._log("2026-07-17_10-28-50")
        out = os.path.join(self.dir, "frozen.json")
        labels = {"2026-07-17_09-41-34": "healthy", "2026-07-17_10-28-38": "healthy"}
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            frozen = rp.freeze_baseline(labels, [os.path.join(self.logs, "*.ndjson")], out)
        self.assertEqual(len(frozen), 2)
        with open(out) as fh:
            payload = json.load(fh)
        self.assertEqual(sorted(payload["runs"]), sorted(labels))
        self.assertEqual(payload["runs"]["2026-07-17_09-41-34"]["label"], "healthy")
        self.assertIn("merge_rate", payload["runs"]["2026-07-17_09-41-34"]["features"])

    def test_freeze_prefers_a_live_corpus_over_a_log(self):
        self._corpus("2026-07-17_09-41-34", cycles=9, merged=9)
        self._log("2026-07-17_09-41-58", cycles=6, merged=0)
        out = os.path.join(self.dir, "frozen.json")
        with contextlib.redirect_stdout(io.StringIO()):
            frozen = rp.freeze_baseline({"2026-07-17_09-41-34": "healthy"},
                                        [os.path.join(self.logs, "*.ndjson")], out, root=self.dir)
        self.assertEqual(frozen["2026-07-17_09-41-34"]["features"]["cycles"], 9)

    def test_freeze_reports_labels_it_could_not_resolve(self):
        out = os.path.join(self.dir, "frozen.json")
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            frozen = rp.freeze_baseline({"2026-01-01_00-00-00": "healthy"},
                                        [os.path.join(self.logs, "*.ndjson")], out)
        self.assertEqual(frozen, {})
        self.assertIn("no data found for 1", buf.getvalue())

    # --- loading ------------------------------------------------------------

    def test_load_frozen_returns_only_healthy_vectors(self):
        out = os.path.join(self.dir, "frozen.json")
        with open(out, "w") as fh:
            json.dump({"runs": {"a": {"label": "healthy", "features": {"merge_rate": 1.0}},
                                "b": {"label": "defect", "features": {"merge_rate": 0.0}},
                                "c": {"label": "healthy"}}}, fh)
        loaded = rp.load_frozen(out)
        self.assertEqual(list(loaded), ["a"])       # defect excluded, feature-less entry excluded

    def test_load_frozen_tolerates_a_missing_or_broken_file(self):
        self.assertEqual(rp.load_frozen(os.path.join(self.dir, "nope.json")), {})
        bad = os.path.join(self.dir, "bad.json")
        with open(bad, "w") as fh:
            fh.write("{not json")
        self.assertEqual(rp.load_frozen(bad), {})

    # --- the report falls back ---------------------------------------------

    def _frozen_with(self, n, out=None):
        out = out or os.path.join(self.dir, "frozen.json")
        runs = {}
        for i in range(n):
            self._log("2026-07-17_%02d-00-10" % (i + 1))
            runs["2026-07-17_%02d-00-00" % (i + 1)] = "healthy"
        with contextlib.redirect_stdout(io.StringIO()):
            rp.freeze_baseline(runs, [os.path.join(self.logs, "*.ndjson")], out)
        return out

    def test_report_uses_the_frozen_baseline_when_live_corpora_are_missing(self):
        frozen = self._frozen_with(6)
        target = self._corpus("2026-07-25_12-00-00")
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rp.health_report(target, os.path.join(self.dir, "no-such-root"), {}, frozen)
        text = buf.getvalue()
        self.assertIn("baseline source: frozen baseline", text)
        self.assertNotIn("baseline too small", text)
        self.assertIn("features outside the healthy envelope", text)

    def test_report_still_abstains_when_no_baseline_is_available_anywhere(self):
        target = self._corpus("2026-07-25_12-00-00")
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rp.health_report(target, os.path.join(self.dir, "no-such-root"), {},
                             os.path.join(self.dir, "missing.json"))
        self.assertIn("baseline too small", buf.getvalue())

    def test_a_run_is_never_compared_against_itself(self):
        out = os.path.join(self.dir, "frozen.json")
        frozen = self._frozen_with(6, out)
        # Add the target itself as a 7th healthy entry, then confirm it is dropped from the envelope.
        with open(frozen) as fh:
            payload = json.load(fh)
        payload["runs"]["2026-07-25_12-00-00"] = {"label": "healthy",
                                                  "features": rp.profile(self._corpus("2026-07-25_12-00-00"))}
        with open(frozen, "w") as fh:
            json.dump(payload, fh)
        loaded = rp.load_frozen(frozen)
        self.assertIn("2026-07-25_12-00-00", loaded)
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rp.health_report(os.path.join(self.dir, "2026-07-25_12-00-00"),
                             os.path.join(self.dir, "no-such-root"), {}, frozen)
        self.assertIn("healthy baseline n=6", buf.getvalue())   # 7 frozen, self excluded

    def test_too_few_cycles_abstains_before_touching_the_baseline(self):
        thin = self._corpus("2026-07-25_13-00-00", cycles=1, merged=0)
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rp.health_report(thin, self.dir, {}, self._frozen_with(6))
        self.assertIn("too few cycles", buf.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)
