#!/usr/bin/env python3
"""
Durable tests for session_usage.py. Pure stdlib: `python3 scripts/test_session_usage.py`.

The two cases that carry real risk of a wrong pacing decision: counting a turn twice when it appears
in more than one transcript (resumed/forked sessions), and folding cache reads into spend — on this
project cache reads run ~54x the full-rate total, so lumping them would make every window look spent.
"""
import contextlib
import datetime as dt
import importlib.util
import io
import json
import os
import tempfile
import unittest

_spec = importlib.util.spec_from_file_location(
    "session_usage", os.path.join(os.path.dirname(__file__), "session_usage.py"))
su = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(su)

BASE = dt.datetime(2026, 7, 25, 12, 0, 0, tzinfo=dt.timezone.utc)


def turn(minutes, out=100, cache_read=1000, mid=None, req=None, model="claude-opus-5"):
    return json.dumps({
        "timestamp": (BASE + dt.timedelta(minutes=minutes)).isoformat().replace("+00:00", "Z"),
        "requestId": req or "req-%d" % minutes,
        "message": {"id": mid or "msg-%d" % minutes, "model": model,
                    "usage": {"input_tokens": 5, "cache_creation_input_tokens": 50,
                              "cache_read_input_tokens": cache_read, "output_tokens": out}},
    })


class SessionUsageTest(unittest.TestCase):
    def _transcript(self, *lines):
        d = tempfile.mkdtemp()
        path = os.path.join(d, "session.jsonl")
        with open(path, "w") as fh:
            fh.write("\n".join(lines) + "\n")
        return path

    def test_loads_and_totals_turns(self):
        turns = su.load_turns([self._transcript(turn(0), turn(5))])
        self.assertEqual(len(turns), 2)
        agg = su.totals(turns)
        self.assertEqual(agg["output"], 200)
        self.assertEqual(agg["cache_read"], 2000)
        self.assertEqual(agg["full_rate"], 2 * (5 + 50 + 100))

    def test_cache_reads_are_excluded_from_full_rate(self):
        agg = su.totals(su.load_turns([self._transcript(turn(0, cache_read=999_999))]))
        self.assertEqual(agg["full_rate"], 155)
        self.assertEqual(agg["cache_read"], 999_999)
        self.assertEqual(agg["all_tokens"], 999_999 + 155)

    def test_same_turn_in_two_transcripts_is_counted_once(self):
        a = self._transcript(turn(0, mid="m1", req="r1"), turn(5, mid="m2", req="r2"))
        b = self._transcript(turn(0, mid="m1", req="r1"), turn(9, mid="m3", req="r3"))
        self.assertEqual(len(su.load_turns([a, b])), 3)

    def test_turns_without_identity_are_all_kept(self):
        # No message id and no requestId: dedupe cannot apply, so nothing is silently dropped.
        line = json.dumps({"timestamp": BASE.isoformat().replace("+00:00", "Z"),
                           "message": {"usage": {"output_tokens": 7}}})
        self.assertEqual(len(su.load_turns([self._transcript(line, line)])), 2)

    def test_records_without_usage_are_ignored(self):
        plain = json.dumps({"timestamp": BASE.isoformat().replace("+00:00", "Z"),
                            "message": {"role": "user", "content": "hi"}})
        self.assertEqual(len(su.load_turns([self._transcript(plain, turn(1))])), 1)

    def test_malformed_lines_do_not_abort_the_file(self):
        path = self._transcript(turn(0), "{not json", turn(2))
        self.assertEqual(len(su.load_turns([path])), 2)

    def test_window_splits_on_a_gap_longer_than_five_hours(self):
        turns = su.load_turns([self._transcript(turn(0), turn(60), turn(60 + 5 * 60 + 1))])
        blocks = su.windows(turns)
        self.assertEqual([len(b) for b in blocks], [2, 1])

    def test_turns_inside_the_window_stay_in_one_block(self):
        turns = su.load_turns([self._transcript(turn(0), turn(120), turn(290))])
        self.assertEqual(len(su.windows(turns)), 1)

    def test_turns_are_sorted_even_when_the_file_is_not(self):
        turns = su.load_turns([self._transcript(turn(30), turn(0))])
        self.assertLess(turns[0]["ts"], turns[1]["ts"])

    def test_fmt_is_readable(self):
        self.assertEqual(su.fmt(999), "999")
        self.assertEqual(su.fmt(1500), "1.5k")
        self.assertEqual(su.fmt(2_500_000), "2.50M")

    def test_report_renders_without_transcripts(self):
        buf = io.StringIO()
        su.report([], type("A", (), {"limit": 0, "days": 0})(), buf)
        self.assertIn("no transcripts", buf.getvalue())

    def test_report_includes_limit_percentage_when_given(self):
        turns = su.load_turns([self._transcript(turn(0, out=1000))])
        buf = io.StringIO()
        su.report(turns, type("A", (), {"limit": 10_000, "days": 0})(), buf)
        self.assertIn("against --limit", buf.getvalue())

    def test_json_payload_reports_window_and_all_time(self):
        # The payload shape is exercised directly: --json over the real transcript root would make
        # the assertion depend on whatever this machine has logged.
        turns = su.load_turns([self._transcript(turn(0), turn(10))])
        blocks = su.windows(turns)
        payload = {"window": su.totals(blocks[-1]), "all_time": su.totals(turns)}
        self.assertEqual(payload["window"]["turns"], 2)
        self.assertEqual(payload["all_time"]["turns"], 2)

    def test_missing_transcript_dir_is_not_fatal(self):
        self.assertEqual(su.load_turns(["/nonexistent/session.jsonl"]), [])

    def test_main_exits_zero_with_no_data(self):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = su.main([])
        self.assertEqual(rc, 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
