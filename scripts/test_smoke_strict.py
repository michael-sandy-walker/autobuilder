#!/usr/bin/env python3
"""Tests for smoke_strict.py — stdlib only.

Run: python3 scripts/test_smoke_strict.py
"""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import smoke_strict as ss  # noqa: E402


def sheet(*lines):
    tmp = tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False)
    tmp.write("\n".join(lines) + "\n")
    tmp.close()
    return ss.read_sheet(tmp.name)


class WitnessRuleTest(unittest.TestCase):
    def test_absent_endpoint_404s_everything_so_the_point_is_withheld(self):
        # The whole reason this score exists: nothing is deployed, so both the happy path and the
        # bad-id path 404, and the suite pays for the absence it means to penalise.
        s = sheet("FAIL [13] POST /loans valid (want 201 got 404) | ",
                  "PASS [14] POST /loans bad book (404)")
        lenient, strict, disc = ss.score(s)
        self.assertEqual((lenient, strict), (1, 0))
        self.assertEqual(disc, [14])

    def test_a_live_endpoint_answering_the_wrong_status_still_proves_existence(self):
        # The bug this file exists to prevent. `want 200 got 204` is a live endpoint with a wrong
        # status, not a missing one — withholding here demotes a genuinely good run.
        s = sheet("FAIL [20] POST return (want 200 got 204) | ",
                  "PASS [22] POST return absent (404)")
        lenient, strict, disc = ss.score(s)
        self.assertEqual((lenient, strict), (1, 1))
        self.assertEqual(disc, [])

    def test_a_passing_witness_earns_the_point(self):
        s = sheet("PASS [13] POST /loans valid (201)",
                  "PASS [15] POST /loans bad member (404)")
        self.assertEqual(ss.score(s)[2], [])

    def test_a_witness_that_itself_404s_withholds_the_point(self):
        s = sheet("FAIL [6] GET /books/{id} (want 200 got 404) | ",
                  "PASS [7] GET /books absent (404)")
        self.assertEqual(ss.score(s)[2], [7])

    def test_an_unobserved_witness_withholds_rather_than_assumes(self):
        s = sheet("PASS [12] GET /members absent (404)")
        self.assertEqual(ss.score(s)[2], [12])

    def test_a_failing_expected404_check_is_never_discounted(self):
        # Only PASSES can be unearned; a failure is already costing the run its point.
        s = sheet("FAIL [13] POST /loans valid (want 201 got 404) | ",
                  "FAIL [14] POST /loans bad book (want 404 got 409) | ")
        self.assertEqual(ss.score(s), (0, 0, []))

    def test_checks_outside_the_expected404_set_are_untouched(self):
        s = sheet("PASS [1] POST /books valid (201)", "PASS [5] GET /books (200)")
        self.assertEqual(ss.score(s), (2, 2, []))

    def test_the_lenient_score_is_never_changed_by_this_module(self):
        """`smoke` keeps its meaning so 60 runs of ledger stay readable."""
        s = sheet("FAIL [13] POST /loans valid (want 201 got 404) | ",
                  "PASS [14] POST /loans bad book (404)",
                  "PASS [15] POST /loans bad member (404)",
                  "PASS [1] POST /books valid (201)")
        lenient, strict, disc = ss.score(s)
        self.assertEqual(lenient, 3)          # unchanged: three checks reported PASS
        self.assertEqual(strict, 1)
        self.assertEqual(disc, [14, 15])

    def test_missing_sheet_returns_none_rather_than_raising(self):
        self.assertIsNone(ss.read_sheet("/nonexistent/smoke.txt"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
