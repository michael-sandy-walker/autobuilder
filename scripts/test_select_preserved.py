#!/usr/bin/env python3
"""
Durable tests for select_preserved.py (T6-A) — the offline replica of
PreservedArtifactService selection. Pure stdlib unittest, no maven / model / network:
run with `python3 scripts/test_select_preserved.py`.

These pin the selection SEMANTICS against PreservedArtifactService (the compile-verify walk
and boot/smoke live in preflight and are exercised end-to-end there, not here). Each case
mirrors a rule in the Java service so a drift in either surfaces as a failure.
"""
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

_spec = importlib.util.spec_from_file_location(
    "select_preserved", os.path.join(os.path.dirname(__file__), "select_preserved.py"))
sp = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(sp)

SCRIPT = Path(__file__).resolve().parent / "select_preserved.py"


def _stamp(recorded_at, entry_point=False, build_result="COMPILE_OK", merged=False):
    return dict(cycleId="c", entryPointPresent=entry_point, buildResult=build_result,
                merged=merged, recordedAt=recorded_at)


class SelectPreservedTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp()

    def _write(self, stamps):
        """stamps: {snapshot_name: state_dict}."""
        for name, state in stamps.items():
            d = os.path.join(self.root, name)
            os.makedirs(d)
            with open(os.path.join(d, sp.STATE_FILE), "w") as fh:
                json.dump(state, fh)

    def _order(self, provenance=None):
        probe, facts = sp.select(self.root, provenance)
        return [name for _, name, _ in probe], facts

    # -- run-relative entry-point bar -------------------------------------------------
    def test_no_entry_point_anywhere_is_not_required(self):
        # Library-shaped run: no snapshot ever had a main() -> the bar does not demand one,
        # and probe order is newest-eligible then merged-eligible (newest-first).
        self._write({
            "c-3": _stamp("2026-01-01T00:03:00Z"),
            "c-2": _stamp("2026-01-01T00:02:00Z", merged=True),
            "c-1": _stamp("2026-01-01T00:01:00Z", merged=True),
        })
        order, facts = self._order()
        self.assertFalse(facts["entryPointRequired"])
        self.assertEqual(order, ["c-3", "c-2", "c-1"])

    def test_one_entry_point_makes_it_required_and_filters_the_rest(self):
        # Once any snapshot had a main(), snapshots lacking one drop out of eligibility.
        self._write({
            "c-3": _stamp("2026-01-01T00:03:00Z", entry_point=False),
            "c-2": _stamp("2026-01-01T00:02:00Z", entry_point=True, merged=True),
        })
        order, facts = self._order()
        self.assertTrue(facts["entryPointRequired"])
        self.assertEqual(order, ["c-2"])  # newest (c-3) dropped: no entry point

    def test_prior_lineage_provenance_forces_required(self):
        # A resumed lineage whose parent preserved/expected an entry point must not fall back
        # to a tree that lost it, even if no snapshot of THIS run has one -> abstain.
        self._write({"c-1": _stamp("2026-01-01T00:01:00Z", entry_point=False, merged=True)})
        prov = os.path.join(self.root, "prov.json")
        with open(prov, "w") as fh:
            json.dump({"entryPointPresent": True, "entryPointRequired": True}, fh)
        order, facts = self._order(prov)
        self.assertTrue(facts["entryPointRequired"])
        self.assertEqual(order, [])

    # -- recorded-failure disqualification --------------------------------------------
    def test_recorded_failure_disqualifies_but_null_and_skipped_do_not(self):
        self._write({
            "c-3": _stamp("2026-01-01T00:03:00Z", build_result="COMPILE_FAILED"),
            "c-2": _stamp("2026-01-01T00:02:00Z", build_result=None),
            "c-1": _stamp("2026-01-01T00:01:00Z", build_result="SKIPPED", merged=True),
        })
        order, facts = self._order()
        self.assertEqual(facts["eligible"], 2)
        self.assertEqual(order, ["c-2", "c-1"])  # c-3 (failure) gone; newest eligible then merged

    # -- ordering ---------------------------------------------------------------------
    def test_probe_order_is_newest_then_merged_newest_first(self):
        self._write({
            "c-5": _stamp("2026-01-01T00:05:00Z"),               # newest, unmerged
            "c-4": _stamp("2026-01-01T00:04:00Z", merged=True),  # merged fallbacks, newest-first
            "c-3": _stamp("2026-01-01T00:03:00Z"),               # unmerged -> not a fallback
            "c-2": _stamp("2026-01-01T00:02:00Z", merged=True),
        })
        order, _ = self._order()
        self.assertEqual(order, ["c-5", "c-4", "c-2"])

    def test_null_recorded_at_sorts_oldest(self):
        # nullsFirst in the Java comparator -> a null timestamp is the OLDEST, never the newest.
        self._write({
            "c-2": _stamp("2026-01-01T00:02:00Z"),
            "c-null": _stamp(None),
        })
        order, _ = self._order()
        self.assertEqual(order[0], "c-2")

    # -- abstain edges ----------------------------------------------------------------
    def test_empty_dir_abstains(self):
        order, facts = self._order()
        self.assertEqual(order, [])
        self.assertEqual(facts["reason"], "no-stamped-snapshots")

    def test_all_failures_abstains(self):
        self._write({"c-1": _stamp("2026-01-01T00:01:00Z", build_result="COMPILE_FAILED")})
        order, facts = self._order()
        self.assertEqual(order, [])
        self.assertEqual(facts["reason"], "none-pass-bar")

    def test_snapshot_without_stamp_is_ignored(self):
        # A pre-T4-CA snapshot dir (no state file) is not a candidate.
        self._write({"c-1": _stamp("2026-01-01T00:01:00Z")})
        os.makedirs(os.path.join(self.root, "c-unstamped"))
        order, facts = self._order()
        self.assertEqual(facts["considered"], 1)
        self.assertEqual(order, ["c-1"])



class RunScopingTest(unittest.TestCase):
    """T6-F: snapshots/ accumulates across a lineage; only this run's cycles are eligible."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.snaps = Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)

    def snap(self, cycle, **state):
        d = self.snaps / cycle
        d.mkdir()
        state.setdefault("buildResult", "COMPILE_OK")
        state.setdefault("recordedAt", "2026-07-26T12:00:00Z")
        (d / sp.STATE_FILE).write_text(json.dumps(state))
        return d

    def test_prior_runs_snapshots_are_excluded(self):
        self.snap("cycle-100")            # a previous run's
        mine = self.snap("cycle-200")     # this run's
        order, facts = sp.select(str(self.snaps), None, {"cycle-200"})
        self.assertEqual([Path(d).name for d, _, _ in order], ["cycle-200"])
        self.assertEqual(facts["considered"], 1)
        self.assertTrue(str(mine))

    def test_without_scoping_the_prior_run_is_still_considered(self):
        """Backwards compatibility: own_cycles=None keeps pre-T6-F behaviour."""
        self.snap("cycle-100")
        self.snap("cycle-200")
        _, facts = sp.select(str(self.snaps), None, None)
        self.assertEqual(facts["considered"], 2)

    def test_scoping_to_a_run_with_no_snapshots_abstains_distinctly(self):
        self.snap("cycle-100")
        order, facts = sp.select(str(self.snaps), None, {"cycle-999"})
        self.assertEqual(order, [])
        self.assertEqual(facts["reason"], "no-stamped-snapshots-for-this-run")

    def test_newest_of_this_run_wins_over_a_newer_prior_run_snapshot(self):
        """The ordering hazard: a prior run's snapshot can be NEWER by recordedAt."""
        self.snap("cycle-100", recordedAt="2026-07-26T23:00:00Z")   # prior run, newer stamp
        self.snap("cycle-200", recordedAt="2026-07-26T12:00:00Z")   # this run, older stamp
        order, _ = sp.select(str(self.snaps), None, {"cycle-200"})
        self.assertEqual([Path(d).name for d, _, _ in order], ["cycle-200"])

    def test_cli_run_log_flag_scopes_selection(self):
        self.snap("cycle-100")
        self.snap("cycle-1785066364275")
        log = self.snaps.parent / "run.log"
        log.write_text("CYCLE cycle-1785066364275 begins\n")
        r = subprocess.run([sys.executable, str(SCRIPT), str(self.snaps),
                            "--run-log", str(log)], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("cycle-1785066364275", r.stdout)
        self.assertNotIn("cycle-100\n", r.stdout)
        self.assertIn("run-scoped to 1 cycle(s)", r.stderr)
        log.unlink()


if __name__ == "__main__":
    unittest.main(verbosity=2)
