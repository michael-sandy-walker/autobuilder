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
        lenient, strict, disc, _ = ss.score(s)
        self.assertEqual((lenient, strict), (1, 0))
        self.assertEqual(disc, [14])

    def test_a_live_endpoint_answering_the_wrong_status_still_proves_existence(self):
        # The bug this file exists to prevent. `want 200 got 204` is a live endpoint with a wrong
        # status, not a missing one — withholding here demotes a genuinely good run.
        s = sheet("FAIL [20] POST return (want 200 got 204) | ",
                  "PASS [22] POST return absent (404)")
        lenient, strict, disc, _ = ss.score(s)
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
        self.assertEqual(ss.score(s), (0, 0, [], 2))

    def test_checks_outside_the_expected404_set_are_untouched(self):
        s = sheet("PASS [1] POST /books valid (201)", "PASS [5] GET /books (200)")
        self.assertEqual(ss.score(s), (2, 2, [], 2))

    def test_the_lenient_score_is_never_changed_by_this_module(self):
        """`smoke` keeps its meaning so 60 runs of ledger stay readable."""
        s = sheet("FAIL [13] POST /loans valid (want 201 got 404) | ",
                  "PASS [14] POST /loans bad book (404)",
                  "PASS [15] POST /loans bad member (404)",
                  "PASS [1] POST /books valid (201)")
        lenient, strict, disc, _ = ss.score(s)
        self.assertEqual(lenient, 3)          # unchanged: three checks reported PASS
        self.assertEqual(strict, 1)
        self.assertEqual(disc, [14, 15])

    def test_missing_sheet_returns_none_rather_than_raising(self):
        self.assertIsNone(ss.read_sheet("/nonexistent/smoke.txt"))


class DenominatorTest(unittest.TestCase):
    """The score carries THE SHEET'S OWN check count.

    Every consumer used to supply the denominator itself, and every one of them supplied
    firestarter's 23 -- so a 19-check CLI run that passed everything was recorded as "19/23" and
    a 12-check one as "12/23". That literal ranked two perfect runs at 83% and 52% and filed them
    mid-table, in the population an entire survivability analysis was contrasted against.
    """

    def test_the_total_is_the_sheets_own_check_count_not_a_constant(self):
        cli = sheet(*["PASS [%d] cli check %d" % (i, i) for i in range(1, 20)])
        self.assertEqual(ss.score(cli)[3], 19)
        tiny = sheet(*["PASS [%d] cli check %d" % (i, i) for i in range(1, 13)])
        self.assertEqual(ss.score(tiny)[3], 12)

    def test_failing_checks_count_toward_the_denominator(self):
        """The denominator is checks REPORTED, not checks passed -- else it is always 100%."""
        s = sheet("PASS [1] a", "FAIL [2] b (want 1 got 2) | ", "FAIL [3] c (want 1 got 2) | ")
        lenient, _, _, total = ss.score(s)
        self.assertEqual((lenient, total), (1, 3))


class ForeignSuiteTest(unittest.TestCase):
    """The witness map is the library suite's, and it must have no opinion elsewhere.

    WITNESS is keyed by check NUMBER, and numbers mean different things per target. Check [7] is
    "GET /books absent (404)" in the 23-check library suite and "a row spanning physical lines is
    well formed" in the 19-check CLI suite -- same integer, unrelated assertions.
    """

    def test_a_cli_sheet_collects_no_discount_from_the_library_witness_map(self):
        # The exact shape that would misfire: [7] passes, its "witness" [6] fails, and the CLI
        # FAIL format `got [2]` is unparsable by the three-digit status regex -- so the old rule
        # would read the witness as unobserved and withhold a point that was fully earned.
        s = sheet("FAIL [6] a row spanning physical lines counts once (want [1] got [2]) | ",
                  "PASS [7] a row spanning physical lines is well formed")
        lenient, strict, disc, total = ss.score(s)
        self.assertEqual(disc, [])
        self.assertEqual((lenient, strict, total), (1, 1, 2))

    def test_the_library_suite_still_pays_when_its_own_labels_are_present(self):
        """The gate must not switch the rule off for the suite it exists to police."""
        s = sheet("FAIL [6] GET /books/{id} (want 200 got 404) | ",
                  "PASS [7] GET /books absent (404)")
        self.assertEqual(ss.score(s)[2], [7])

    def test_a_matching_number_with_a_foreign_label_is_not_the_library_suite(self):
        s = sheet("PASS [12] empty file has no header — check exits 2")
        self.assertFalse(ss.is_own_suite(s, 12))
        self.assertEqual(ss.score(s)[2], [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
