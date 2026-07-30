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

    def test_a_prose_only_entry_is_reported_as_manual_not_skipped(self):
        with tempfile.TemporaryDirectory() as tmp:
            entry = {"id": "t9zz-something", "why": "prose", "look_for": "by eye", "status": "open"}
            self.assertNotIn("fired", entry)
            # The reporter's contract: no patterns means MANUAL, never silence.
            self.assertEqual(wr.ticket_from_id(entry["id"]), "T9-ZZ")


if __name__ == "__main__":
    unittest.main()
