#!/usr/bin/env python3
"""
Durable tests for failure_family.py (T6-M). Pure stdlib, no maven/model:
run with `python3 scripts/test_failure_family.py`.

The cases that matter are the ones that would quietly corrupt a distribution rather than
crash: a HEALTHY run being classified from its mid-run diagnostics (every run hits missing
symbols on the way, so the loudest family would swallow the corpus), and a fallback
classification being reported as though it carried the same weight as the scorer's own log.
"""
import contextlib
import gzip
import importlib.util
import io
import os
import tempfile
import unittest

_spec = importlib.util.spec_from_file_location(
    "failure_family", os.path.join(os.path.dirname(__file__), "failure_family.py"))
ff = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ff)


def rundir(files=None):
    """Build a run directory. Filenames are LITERAL -- an earlier version mangled them from
    kwargs and silently wrote artifact.build.log instead of artifact-build.log, so the code
    never saw the file and two tests failed against correct behaviour."""
    d = tempfile.mkdtemp()
    for name, body in (files or {}).items():
        path = os.path.join(d, name)
        if path.endswith(".gz"):
            with gzip.open(path, "wt", encoding="utf-8") as fh:
                fh.write(body)
        else:
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(body)
    return d


class ClassifyBuildTest(unittest.TestCase):
    def test_missing_type_wins_and_names_the_symbol(self):
        fam, detail = ff.classify_build("[ERROR] symbol:   class LoanNotFoundException")
        self.assertEqual(fam, "compile-missing-type")
        self.assertEqual(detail, "LoanNotFoundException")

    def test_missing_member_when_no_type_is_missing(self):
        fam, detail = ff.classify_build("symbol:   method createLoan(java.lang.Long)")
        self.assertEqual(fam, "compile-missing-member")
        self.assertIn("createLoan", detail)

    def test_packaging_failure_is_its_own_family(self):
        fam, _ = ff.classify_build("[ERROR] Unable to find main class")
        self.assertEqual(fam, "packaging")

    def test_empty_text_classifies_nothing(self):
        self.assertEqual(ff.classify_build("   "), (None, None))


class ClassifyBootTest(unittest.TestCase):
    def test_missing_bean_is_wiring_and_names_the_bean(self):
        fam, detail = ff.classify_boot(
            "No qualifying bean of type 'com.example.library.domain.repository.BookRepository' available")
        self.assertEqual(fam, "wiring")
        self.assertEqual(detail, "BookRepository")

    def test_other_startup_failure_is_boot_other(self):
        fam, _ = ff.classify_boot("APPLICATION FAILED TO START\nDescription: port in use")
        self.assertEqual(fam, "boot-other")


class OutcomeGateTest(unittest.TestCase):
    def test_scored_run_reads_its_score(self):
        self.assertEqual(ff.outcome(rundir({"summary.txt": "smoke=SMOKE: 23/23\n"})), 23)

    def test_build_failure_reads_as_zero(self):
        self.assertEqual(ff.outcome(rundir({"summary.txt": "smoke=artifact-build-failed\n"})), 0)

    def test_no_summary_is_unknown_not_zero(self):
        self.assertIsNone(ff.outcome(rundir()))

    def test_a_healthy_run_is_not_classified_from_its_mid_run_diagnostics(self):
        # The corrupting case: every run hits missing symbols on the way to succeeding.
        d = rundir({"summary.txt": "smoke=SMOKE: 23/23\n",
                    "autobuilder.log": "symbol:   class SomethingTransient\n"})
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            ff.main([d])
        out = buf.getvalue()
        self.assertIn("not-a-failure (23/23)", out)
        self.assertIn("classified=0", out)
        self.assertNotIn("compile-missing-type ", out.split("distribution")[1])


class ProvenanceTest(unittest.TestCase):
    def test_scorer_log_is_preferred_over_the_run_log(self):
        d = rundir({"summary.txt": "smoke=artifact-build-failed\n",
                    "artifact-build.log": "symbol:   class RealCause\n",
                    "autobuilder.log": "symbol:   class MidRunNoise\n"})
        fam, detail, prov = ff.classify(d)
        self.assertEqual((fam, detail), ("compile-missing-type", "RealCause"))
        self.assertEqual(prov, "artifact-build.log")

    def test_run_log_fallback_declares_itself_weaker(self):
        d = rundir({"summary.txt": "smoke=artifact-build-failed\n",
                    "autobuilder.log": "symbol:   class OnlyEvidence\n"})
        _, _, prov = ff.classify(d)
        self.assertIn("weaker", prov)

    def test_gzipped_archive_log_is_read(self):
        d = rundir({"summary.txt": "smoke=artifact-build-failed\n"})
        with gzip.open(os.path.join(d, "autobuilder.log.gz"), "wt", encoding="utf-8") as fh:
            fh.write("symbol:   class Archived\n")
        fam, detail, _ = ff.classify(d)
        self.assertEqual((fam, detail), ("compile-missing-type", "Archived"))

    def test_nothing_to_read_is_unknown(self):
        self.assertEqual(ff.classify(rundir({"summary.txt": "smoke=boot-failed\n"}))[0], "unknown")


class EndToEndTest(unittest.TestCase):
    def test_machine_readable_last_line(self):
        a = rundir({"summary.txt": "smoke=artifact-build-failed\n",
                    "artifact-build.log": "symbol:   class A\n"})
        b = rundir({"summary.txt": "smoke=boot-failed\n",
                    "artifact-boot.log": "No qualifying bean of type 'x.Y' available\n"})
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = ff.main([a, b])
        self.assertEqual(rc, 0)
        last = buf.getvalue().strip().splitlines()[-1]
        self.assertIn("runs=2", last)
        self.assertIn("classified=2", last)

    def test_no_dirs_reports_rather_than_crashes(self):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = ff.main(["/nonexistent-path-xyz"])
        self.assertEqual(rc, 1)
        self.assertIn("runs=0", buf.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)
