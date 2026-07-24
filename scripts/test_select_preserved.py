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
import tempfile
import unittest

_spec = importlib.util.spec_from_file_location(
    "select_preserved", os.path.join(os.path.dirname(__file__), "select_preserved.py"))
sp = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(sp)


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


if __name__ == "__main__":
    unittest.main(verbosity=2)
