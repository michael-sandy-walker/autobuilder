#!/usr/bin/env python3
"""T6-K: the chain report must separate a real defect from its echoes, or it names the wrong target."""
import os
import shutil
import tempfile
import unittest

import smoke_chain_report as scr


def sheet(passes, fails):
    lines = [f"PASS [{c}] x ({c})" for c in passes] + [f"FAIL [{c}] x (want 1 got 2) | " for c in fails]
    lines.sort(key=lambda l: int(l.split("[")[1].split("]")[0]))
    lines.append(f"SMOKE: {len(passes)}/23")
    return "\n".join(lines) + "\n"


class ChainReportTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _run(self, name, passes, fails, fname="preserved-smoke.txt"):
        d = os.path.join(self.tmp, name)
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, fname), "w") as fh:
            fh.write(sheet(passes, fails))
        return d

    def test_a_broken_feeder_makes_its_dependents_echoes_not_defects(self):
        # POST /loans (13) fails, so return (20) and return-again (21) probe a loan that was never
        # created. Counting those as defects would send work at the return endpoint when the
        # actual break is loan creation — the exact mistake this report exists to prevent.
        allc = set(range(1, 24))
        fails = {13, 20, 21}
        self._run("2026-08-01_00-00-00", sorted(allc - fails), sorted(fails))

        raw, originated, _ = scr.analyse(scr.collect([self.tmp + "/*"]))

        self.assertEqual(raw[20], 1)
        self.assertEqual(raw[21], 1)
        self.assertNotIn(20, originated, "20 depends on 13, which failed — an echo")
        self.assertNotIn(21, originated, "21 depends on 13 and 20 — an echo")
        self.assertEqual(originated[13], 1, "13's own dependencies passed, so it is the defect")

    def test_the_same_check_failing_with_its_feeder_healthy_is_a_real_defect(self):
        # The counter-case that gives the column meaning: 20 fails while 13 succeeded, so returning
        # a loan is genuinely broken. Without this the test above would pass for a report that
        # simply never attributes anything.
        allc = set(range(1, 24))
        self._run("2026-08-01_00-00-01", sorted(allc - {20}), [20])

        _, originated, _ = scr.analyse(scr.collect([self.tmp + "/*"]))

        self.assertEqual(originated[20], 1)

    def test_an_unobserved_dependency_is_unknown_rather_than_passed(self):
        # A partial sheet must not let a check be credited as an independent defect on the
        # strength of a dependency nobody recorded.
        d = os.path.join(self.tmp, "2026-08-01_00-00-02")
        os.makedirs(d)
        with open(os.path.join(d, "preserved-smoke.txt"), "w") as fh:
            fh.write("FAIL [20] x (want 200 got 404) | \nSMOKE: 0/23\n")

        raw, originated, _ = scr.analyse(scr.collect([self.tmp + "/*"]))

        self.assertEqual(raw[20], 1)
        self.assertNotIn(20, originated)

    def test_runs_without_a_sheet_contribute_nothing(self):
        os.makedirs(os.path.join(self.tmp, "2026-07-01_00-00-00"))
        self.assertEqual(scr.collect([self.tmp + "/*"]), {})

    def test_every_check_has_a_dependency_entry_and_a_label(self):
        # A check missing from DEPENDS_ON would silently be treated as independent, which is the
        # failure mode that makes the whole report lie.
        for check in range(1, 24):
            self.assertIn(check, scr.DEPENDS_ON, f"check {check} has no dependency entry")
            self.assertIn(check, scr.LABELS, f"check {check} has no label")


if __name__ == "__main__":
    unittest.main(verbosity=2)
