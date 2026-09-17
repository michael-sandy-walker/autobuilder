#!/usr/bin/env python3
"""
Durable tests for closure_refusal_report.py. Pure stdlib, no maven/model:
run with `python3 scripts/test_closure_refusal_report.py`.

The case that matters most is the KNOWN POSITIVE. A readout over a corpus whose runs
mostly predate the event it reads will answer "none" for every one of them, and an
author who only ever sees that cannot tell a working reader from a broken predicate --
which is the error this project keeps paying for. So a hand-built log carrying real
closure_refused events is asserted here, not just an empty one.

The second is the absence distinction: a run with no events must not be reported as a
run with zero refusals, because a build predating the event emits none either way and
the log cannot tell those apart.
"""
import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path

_spec = importlib.util.spec_from_file_location(
    "crr", os.path.join(os.path.dirname(__file__), "closure_refusal_report.py"))
crr = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(crr)


def _write(tmp, events):
    """Behaviour logs are pretty-printed concatenated JSON, not line-JSON."""
    p = Path(tmp) / crr.BEHAVIOUR
    with open(p, "w") as fh:
        for e in events:
            fh.write(json.dumps(e, indent=2))
            fh.write("\n")
    return Path(tmp)


def _refusal(gate, goal, reason="the running application answered 201", criteria=None, cycle="c1"):
    return {"timestamp": "2026-09-17T23:00:00Z", "cycleId": cycle, "event": "closure_refused",
            "payload": {"gate": gate, "goalId": goal, "reason": reason,
                        "criteria": criteria or []}}


class KnownPositiveTest(unittest.TestCase):
    def test_refusals_are_found_and_grouped_by_gate(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = _write(tmp, [
                _refusal("T4-CU", "goal-a", criteria=["POST /books returns HTTP 400"]),
                _refusal("T4-BV", "goal-root", reason="the application exits at startup"),
                _refusal("T4-CU", "goal-b", criteria=["GET /loans returns HTTP 404"]),
            ])
            rows = crr.refusals(d)
            self.assertEqual(len(rows), 3)
            self.assertEqual([r["gate"] for r in rows], ["T4-CU", "T4-BV", "T4-CU"])
            self.assertEqual(rows[0]["criteria"], ["POST /books returns HTTP 400"])

    def test_a_gate_that_judges_no_criteria_carries_none(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = _write(tmp, [_refusal("T4-BV", "goal-root")])
            self.assertEqual(crr.refusals(d)[0]["criteria"], [])

    def test_other_events_are_ignored(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = _write(tmp, [
                {"cycleId": "c1", "event": "cycle_end", "payload": {"merged": True}},
                _refusal("T4-CU", "goal-a", criteria=["x"]),
            ])
            self.assertEqual(len(crr.refusals(d)), 1)


class AbsenceTest(unittest.TestCase):
    def test_a_log_with_no_refusals_is_empty_not_none(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = _write(tmp, [{"cycleId": "c1", "event": "cycle_end", "payload": {}}])
            self.assertEqual(crr.refusals(d), [])

    def test_a_missing_log_is_none_not_empty(self):
        """None means "cannot say"; [] means "said, and it was none". A pre-event build and
        a clean run are different facts and the report must not merge them."""
        with tempfile.TemporaryDirectory() as tmp:
            self.assertIsNone(crr.refusals(Path(tmp)))


class RobustnessTest(unittest.TestCase):
    def test_a_log_truncated_mid_write_keeps_what_parsed(self):
        # A run killed mid-cycle leaves a partial trailing object. Losing the whole file
        # for it would turn a killed run into a silent zero.
        with tempfile.TemporaryDirectory() as tmp:
            d = _write(tmp, [_refusal("T4-CU", "goal-a", criteria=["x"])])
            with open(Path(d) / crr.BEHAVIOUR, "a") as fh:
                fh.write('{"event": "closure_refused", "payl')
            self.assertEqual(len(crr.refusals(d)), 1)

    def test_a_payload_missing_its_fields_does_not_throw(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = _write(tmp, [{"cycleId": "c1", "event": "closure_refused"}])
            rows = crr.refusals(d)
            self.assertEqual(rows[0]["criteria"], [])
            self.assertIsNone(rows[0]["gate"])


class ReportTest(unittest.TestCase):
    def test_repeated_refusal_on_one_goal_is_called_out(self):
        # The shape that ends in a deadline burn on an artifact the loop was holding:
        # one goal refused over and over. A total alone hides it.
        import io, contextlib
        with tempfile.TemporaryDirectory() as tmp:
            d = _write(tmp, [_refusal("T4-CU", "goal-a", criteria=["x"], cycle=f"c{i}")
                             for i in range(4)])
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                crr.report(d)
            out = buf.getvalue()
            self.assertIn("REPEATED on 1 goal(s)", out)
            self.assertIn("refused 4 times", out)

    def test_a_missing_log_says_it_cannot_say(self):
        import io, contextlib
        with tempfile.TemporaryDirectory() as tmp:
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                crr.report(Path(tmp))
            self.assertIn("cannot say", buf.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=1)
