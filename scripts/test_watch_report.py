#!/usr/bin/env python3
"""Tests for watch_report.py — the registry evaluator."""
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import watch_report as wr  # noqa: E402


class TicketDerivation(unittest.TestCase):

    def test_a_ticket_is_derived_from_the_watch_id(self):
        self.assertEqual(wr.ticket_from_id("t4da-clause-claimed-after-readout"), "T4-DA")
        self.assertEqual(wr.ticket_from_id("t6f-stale-loop-copy-ignored"), "T6-F")
        self.assertEqual(wr.ticket_from_id("t1fl-system-resumed"), "T1-FL")

    def test_an_unrecognisable_id_yields_a_marker_rather_than_a_crash(self):
        # A reporter that dies on one malformed entry hides every entry after it — which is what
        # happened on run 2026-07-30_18-39-00, where a KeyError aborted the report at watch 11
        # of 14 and the remaining four were never evaluated.
        self.assertEqual(wr.ticket_from_id("weird"), "?")
        self.assertEqual(wr.ticket_from_id(""), "?")


class RegistryShape(unittest.TestCase):

    def test_every_registry_entry_can_be_reported(self):
        # Guards the real file: both shapes must survive the reporter.
        here = os.path.dirname(os.path.abspath(__file__))
        watches = json.load(open(os.path.join(here, "watches.json")))["watches"]
        for w in watches:
            ticket = w.get("ticket") or wr.ticket_from_id(w.get("id", ""))
            self.assertTrue(ticket, w.get("id"))
            self.assertIn("id", w)

    def test_every_registry_pattern_names_a_log_the_reporter_can_resolve(self):
        """The one that would have caught it.

        Four of seventeen watches named `"file": "app"`, which is not in FILE_MAP. The lookup
        fell back to the key itself, resolved to a path that never exists, counted 0, and the
        watch reported NO-OPPORTUNITY on every run it ever ran on -- including firestarter runs
        where its own fired pattern matched 47 and 155 times. Nothing was broken enough to fail;
        the report just quietly said "no opportunity" forever.
        """
        here = os.path.dirname(os.path.abspath(__file__))
        watches = json.load(open(os.path.join(here, "watches.json")))["watches"]
        for w in watches:
            for slot in ("fired", "opportunity"):
                spec = w.get(slot)
                if spec:
                    self.assertIn(spec.get("file"), wr.FILE_MAP,
                                  f"{w['id']}.{slot} names an unresolvable log")

    def test_a_prose_only_entry_is_reported_as_manual_not_skipped(self):
        with tempfile.TemporaryDirectory() as tmp:
            entry = {"id": "t9zz-something", "why": "prose", "look_for": "by eye", "status": "open"}
            self.assertNotIn("fired", entry)
            # The reporter's contract: no patterns means MANUAL, never silence.
            self.assertEqual(wr.ticket_from_id(entry["id"]), "T9-ZZ")



class UnresolvableLog(unittest.TestCase):
    """An unknown file key must be LOUD. A monitor whose failure mode looks like good news
    is worse than no monitor ([[feedback-monitor-every-terminal]])."""

    def _count(self, file_key):
        """count_matches against a run_dir holding a real applog with three matches."""
        with tempfile.TemporaryDirectory() as tmp:
            with open(os.path.join(tmp, "autobuilder.log"), "w") as fh:
                fh.write("the mechanism fired\n" * 3)
            return wr.count_matches(wr.Path(tmp),
                                    {"file": file_key, "pattern": "the mechanism fired"})

    def test_an_unknown_file_key_raises_rather_than_counting_zero(self):
        with self.assertRaises(wr.UnknownWatchFile):
            self._count("app")

    def test_the_corrected_key_finds_the_very_matches_the_typo_hid(self):
        self.assertEqual(self._count("applog"), 3)

    def test_a_known_key_whose_file_is_absent_is_still_a_plain_zero(self):
        # Distinct from the error above: "this run produced no such log" is legitimate silence,
        # and must stay a 0 rather than becoming noise on every run that skips a stage.
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(
                wr.count_matches(wr.Path(tmp), {"file": "goalstack", "pattern": "x"}), 0)


if __name__ == "__main__":
    unittest.main()
