#!/usr/bin/env python3
"""Tests for preserved_freshness.py (T6-F). Stdlib only, no network, no repo state."""

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import preserved_freshness as pf  # noqa: E402

SCRIPT = Path(__file__).resolve().parent / "preserved_freshness.py"


class FreshnessTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)

    def write_prov(self, **fields):
        p = self.tmp / "prov.json"
        p.write_text(json.dumps(fields))
        return p

    def write_log(self, text):
        p = self.tmp / "autobuilder.log"
        p.write_text(text)
        return p

    # --- the defect this ticket exists for ------------------------------------
    def test_copy_from_an_earlier_run_is_stale(self):
        """The observed 2026-07-26 case: provenance names run 2's cycle, log is run 3's."""
        prov = self.write_prov(fromCycle="cycle-1785066271355", entryPointPresent=False)
        log = self.write_log("CYCLE cycle-1785067028110 start\nCYCLE cycle-1785067099001 start\n")
        self.assertEqual(pf.classify(prov, log), "stale:cycle-not-in-this-run (cycle-1785066271355)")

    def test_copy_from_this_run_is_fresh(self):
        prov = self.write_prov(fromCycle="cycle-42")
        log = self.write_log("CYCLE cycle-41 x\nCYCLE cycle-42 y\n")
        self.assertEqual(pf.classify(prov, log), "fresh:cycle-42")

    # --- attribution fallbacks -----------------------------------------------
    def test_haltCycle_is_used_when_fromCycle_absent(self):
        prov = self.write_prov(haltCycle="cycle-7")
        log = self.write_log("CYCLE cycle-7 z")
        self.assertEqual(pf.classify(prov, log), "fresh:cycle-7")

    def test_fromCycle_wins_over_haltCycle(self):
        prov = self.write_prov(fromCycle="cycle-1", haltCycle="cycle-2")
        log = self.write_log("CYCLE cycle-1 a\nCYCLE cycle-2 b")
        self.assertEqual(pf.classify(prov, log), "fresh:cycle-1")

    def test_unattributable_copy_is_stale_not_trusted(self):
        """No provenance beside the copy — PreservedArtifactService always writes one, so this
        cannot be attributed to this run and must not be scored as its own."""
        log = self.write_log("CYCLE cycle-9")
        self.assertEqual(pf.classify(self.tmp / "absent.json", log), "stale:no-provenance")

    def test_blank_and_missing_cycle_fields_are_stale(self):
        log = self.write_log("CYCLE cycle-9")
        self.assertEqual(pf.classify(self.write_prov(fromCycle="   "), log),
                         "stale:provenance-names-no-cycle")
        self.assertEqual(pf.classify(self.write_prov(other=1), log),
                         "stale:provenance-names-no-cycle")

    def test_non_string_cycle_field_is_stale(self):
        log = self.write_log("CYCLE cycle-9")
        self.assertEqual(pf.classify(self.write_prov(fromCycle=123), log),
                         "stale:provenance-names-no-cycle")

    def test_unreadable_provenance_is_stale(self):
        p = self.tmp / "prov.json"
        p.write_text("{not json")
        log = self.write_log("CYCLE cycle-9")
        self.assertTrue(pf.classify(p, log).startswith("stale:unreadable-provenance"))

    def test_provenance_that_is_a_list_is_stale(self):
        p = self.tmp / "prov.json"
        p.write_text("[1, 2]")
        log = self.write_log("CYCLE cycle-9")
        self.assertTrue(pf.classify(p, log).startswith("stale:unreadable-provenance"))

    # --- unknown: cannot compare ---------------------------------------------
    def test_missing_run_log_is_unknown_not_fresh(self):
        prov = self.write_prov(fromCycle="cycle-5")
        out = pf.classify(prov, self.tmp / "nope.log")
        self.assertEqual(out, "unknown:no-run-log (cycle-5)")

    def test_run_log_without_cycles_is_unknown(self):
        prov = self.write_prov(fromCycle="cycle-5")
        log = self.write_log("boot lines only, no cycles here\n")
        self.assertEqual(pf.classify(prov, log), "unknown:run-log-has-no-cycles (cycle-5)")

    # --- robustness ----------------------------------------------------------
    def test_killed_run_log_with_invalid_utf8_still_matches(self):
        """A killed run's log can end mid-write; that must not make a fresh copy look stale."""
        prov = self.write_prov(fromCycle="cycle-99")
        log = self.tmp / "autobuilder.log"
        log.write_bytes(b"CYCLE cycle-99 running\n\xff\xfe truncated")
        self.assertEqual(pf.classify(prov, log), "fresh:cycle-99")

    def test_cycle_id_is_matched_whole_not_by_prefix(self):
        """cycle-123 must not be considered present because cycle-1234 is in the log."""
        prov = self.write_prov(fromCycle="cycle-123")
        log = self.write_log("CYCLE cycle-1234 elsewhere\n")
        self.assertEqual(pf.classify(prov, log), "stale:cycle-not-in-this-run (cycle-123)")

    def test_cycles_in_log_reads_every_id(self):
        log = self.write_log("a CYCLE cycle-1 b\nCYCLE cycle-2 c\nCYCLE cycle-2 d")
        self.assertEqual(pf.cycles_in_log(log), {"cycle-1", "cycle-2"})

    def test_cycles_in_log_on_missing_file_is_empty(self):
        self.assertEqual(pf.cycles_in_log(self.tmp / "nope"), set())

    def test_real_resumed_log_shape_is_stale(self):
        """REGRESSION (the bare-id rule shipped and was a no-op here): a resumed run prints its
        PARENT's cycle id in the T4-CD resume line and echoes it in JSON payloads, while running
        only its own cycles. Verbatim shapes from /tmp/preflight_runs/2026-07-26_13-45-50."""
        prov = self.write_prov(fromCycle="cycle-1785066271355")
        log = self.write_log(
            "2026-07-26 13:46:04.269 [main] INFO  c.w.a.core.init.RunResumeService - "
            "T4-CD: resumed from preserved artifact (fromCycle=cycle-1785066271355, "
            "verifiedAtRestore=COMPILE_OK)\n"
            '  "attemptId" : "cycle-1785066271355",\n'
            '  "cycleId" : "cycle-1785066271355",\n'
            "CYCLE cycle-1785066364275 begins\n"
            "CYCLE cycle-1785066376839 begins\n")
        self.assertEqual(pf.classify(prov, log),
                         "stale:cycle-not-in-this-run (cycle-1785066271355)")

    def test_own_banner_beats_a_mere_mention(self):
        """The same id mentioned in prose AND run as a banner is fresh — the banner decides."""
        prov = self.write_prov(fromCycle="cycle-500")
        log = self.write_log("resumed from preserved artifact (fromCycle=cycle-500)\n"
                             "CYCLE cycle-500 begins\n")
        self.assertEqual(pf.classify(prov, log), "fresh:cycle-500")

    # --- CLI contract preflight depends on ------------------------------------
    def test_cli_prints_one_line_and_exits_zero(self):
        prov = self.write_prov(fromCycle="cycle-8")
        log = self.write_log("CYCLE cycle-8")
        r = subprocess.run([sys.executable, str(SCRIPT), "--provenance", str(prov),
                            "--run-log", str(log)], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(r.stdout.strip(), "fresh:cycle-8")
        self.assertEqual(len(r.stdout.strip().splitlines()), 1)

    def test_cli_exits_zero_even_when_stale(self):
        """Advisory, never a gate: preflight must not abort a run over this."""
        prov = self.write_prov(fromCycle="cycle-1785066271355")
        log = self.write_log("CYCLE cycle-1785067028110")
        r = subprocess.run([sys.executable, str(SCRIPT), "--provenance", str(prov),
                            "--run-log", str(log)], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertTrue(r.stdout.startswith("stale:"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
