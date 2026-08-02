#!/usr/bin/env python3
"""Tests for attention_report.py — stdlib only, no maven/model/network.

Run: python3 scripts/test_attention_report.py
"""
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from attention_report import (  # noqa: E402
    classify, conversion, family_weights, goal_index, measure_run, pearson, read_cycles,
)


class TestClassify(unittest.TestCase):
    def test_loan_404_is_referential(self):
        self.assertEqual(classify("POST /loans returns 404 when bookId does not exist"),
                         {"loans_ref"})

    def test_loan_422_is_limit(self):
        self.assertEqual(
            classify("POST /loans returns HTTP 422 when member has 3 or more active loans"),
            {"loans_limit"})

    def test_loan_400_is_unmeasured_because_no_check_scores_it(self):
        self.assertEqual(classify("POST /loans with invalid input returns HTTP 400"),
                         {"loans_unmeasured"})

    def test_loan_work_naming_no_status_is_structural(self):
        self.assertEqual(classify("Create the Loan entity class with required fields"),
                         {"loans_structural"})

    def test_book_validation(self):
        self.assertEqual(classify("POST /books returns HTTP 400 when title is missing"),
                         {"validation"})

    def test_duplicate_email_is_validation_not_a_loan_conflict(self):
        self.assertEqual(classify("POST /members returns 409 on duplicate email"), {"validation"})

    def test_create_happy_path(self):
        self.assertEqual(classify("POST /books with valid data returns HTTP 201"), {"creates"})

    def test_returns_201_does_not_make_a_create_look_like_loan_work(self):
        """'return' is not a loan word — criteria say 'returns HTTP 201' constantly."""
        self.assertNotIn("loans_ref", classify("POST /books returns HTTP 201 with the body"))

    def test_empty_json_array_listing_is_not_validation(self):
        """Regression: bare 'empty' pulled a GET listing into the validation family."""
        self.assertEqual(classify("The response body contains an empty JSON array "
                                  "when no books are stored"), {"other"})

    def test_structural_file_creation_is_not_the_create_endpoint(self):
        """Regression: bare 'create' pulled 'Create BookController.java' into creates."""
        self.assertEqual(classify("Create BookController.java"), {"other"})

    def test_never_empty(self):
        self.assertEqual(classify(""), {"other"})
        self.assertEqual(classify(None), {"other"})

    def test_a_criterion_can_belong_to_two_families(self):
        both = classify("POST /loans returns 404 when book absent and 422 when over the limit")
        self.assertEqual(both, {"loans_ref", "loans_limit"})


class TestFamilyWeights(unittest.TestCase):
    def test_weights_sum_to_one(self):
        entry = {"description": "d", "criteria": [
            "POST /loans returns 404 when book does not exist",
            "POST /books returns 400 when title is missing",
            "Create the Loan repository",
        ], "build_fix": False}
        weights = family_weights(entry)
        self.assertAlmostEqual(sum(weights.values()), 1.0)

    def test_a_two_family_criterion_splits_rather_than_double_counts(self):
        entry = {"description": "d",
                 "criteria": ["POST /loans returns 404 when absent and 422 over the limit"],
                 "build_fix": False}
        weights = family_weights(entry)
        self.assertAlmostEqual(weights["loans_ref"], 0.5)
        self.assertAlmostEqual(weights["loans_limit"], 0.5)
        self.assertAlmostEqual(sum(weights.values()), 1.0)

    def test_falls_back_to_the_description_when_a_subgoal_has_no_criteria(self):
        entry = {"description": "Implement POST /loans returning 404 for a missing book",
                 "criteria": [], "build_fix": False}
        self.assertEqual(set(family_weights(entry)), {"loans_ref"})


class TestReadCycles(unittest.TestCase):
    LOG = (
        "2026-01-01 INFO CompileProbeService - T1-EC: probe result=COMPILE_ERROR (1 files)\n"
        "2026-01-01 INFO CompileProbeService - CompileFixInjector: re-probe after rule=x "
        "result=COMPILE_OK (pass 1)\n"
        "2026-01-01 INFO CycleLoggingService - CYCLE cycle-1 goal=g sub=s1 prog=P mut=ACCEPTED "
        "result=CONTINUE flags=[paused=false,merged=true,promoted=false]\n"
        "2026-01-01 INFO CompileProbeService - T1-EC: probe result=COMPILE_OK (1 files)\n"
        "2026-01-01 INFO CycleLoggingService - CYCLE cycle-2 goal=g sub=s2 prog=P mut=REJECTED "
        "result=CONTINUE flags=[paused=false,merged=false,promoted=false]\n"
    )

    def _write(self, text):
        tmp = tempfile.NamedTemporaryFile("w", suffix=".log", delete=False)
        tmp.write(text)
        tmp.close()
        return tmp.name

    def test_parses_cycle_subgoal_merge_and_state(self):
        cycles = read_cycles(self._write(self.LOG))
        self.assertEqual([(c[0], c[1], c[2], c[3]) for c in cycles],
                         [("cycle-1", "s1", True, "ACCEPTED"),
                          ("cycle-2", "s2", False, "REJECTED")])

    def test_first_probe_is_the_models_own_output_and_the_repair_is_kept_apart(self):
        first_cycle = read_cycles(self._write(self.LOG))[0]
        self.assertEqual(first_cycle[4], "COMPILE_ERROR")   # what the model wrote
        self.assertEqual(first_cycle[5], "COMPILE_OK")      # what the injector left behind

    def test_a_cycle_with_no_repair_settles_at_its_own_probe(self):
        second = read_cycles(self._write(self.LOG))[1]
        self.assertEqual(second[4], "COMPILE_OK")
        self.assertEqual(second[5], "COMPILE_OK")

    def test_probes_do_not_leak_across_the_cycle_boundary(self):
        """A probe belongs to the cycle whose CYCLE line follows it, not to the next one."""
        cycles = read_cycles(self._write(self.LOG))
        self.assertEqual(cycles[1][4], "COMPILE_OK")

    def test_missing_log_is_empty_not_an_exception(self):
        self.assertEqual(read_cycles("/nonexistent/autobuilder.log"), [])


class TestGoalIndex(unittest.TestCase):
    def _stack(self, tree):
        tmp = tempfile.NamedTemporaryFile("w", suffix=".json", delete=False)
        json.dump({"goalTree": tree}, tmp)
        tmp.close()
        return tmp.name

    def test_walks_nested_subgoals_and_reads_criteria(self):
        path = self._stack({"id": "root", "description": "root", "subGoals": [
            {"id": "a", "description": "A", "metadata": {"acceptanceCriteria": ["c1", "c2"]},
             "subGoals": [{"id": "b", "description": "B", "metadata": {}}]}]})
        index = goal_index(path)
        self.assertEqual(set(index), {"root", "a", "b"})
        self.assertEqual(index["a"]["criteria"], ["c1", "c2"])

    def test_build_fix_subgoals_are_flagged(self):
        path = self._stack({"id": "r", "description": "r", "subGoals": [
            {"id": "f", "description": "[BUILD_FIX] Resolve missing symbol Loan",
             "metadata": {}}]})
        self.assertTrue(goal_index(path)["f"]["build_fix"])
        self.assertFalse(goal_index(path)["r"]["build_fix"])

    def test_unreadable_stack_yields_nothing_rather_than_raising(self):
        self.assertEqual(goal_index("/nonexistent/GoalStack.json"), {})


class TestMeasureRun(unittest.TestCase):
    def _run_dir(self, cycles_log, tree, smoke):
        d = tempfile.mkdtemp()
        with open(os.path.join(d, "autobuilder.log"), "w") as fh:
            fh.write(cycles_log)
        with open(os.path.join(d, "GoalStack.json"), "w") as fh:
            json.dump({"goalTree": tree}, fh)
        with open(os.path.join(d, "artifact-smoke.txt"), "w") as fh:
            fh.write(smoke)
        return d

    @staticmethod
    def _cycle(n, sub, merged, mut="ACCEPTED"):
        return ("2026-01-01 INFO CompileProbeService - T1-EC: probe result=COMPILE_OK (1 files)\n"
                f"2026-01-01 INFO CycleLoggingService - CYCLE cycle-{n} goal=g sub={sub} prog=P "
                f"mut={mut} result=CONTINUE "
                f"flags=[paused=false,merged={'true' if merged else 'false'},promoted=false]\n")

    TREE = {"id": "root", "description": "root", "subGoals": [
        {"id": "loan", "description": "loans",
         "metadata": {"acceptanceCriteria": ["POST /loans returns 404 when book does not exist"]}},
        {"id": "val", "description": "validation",
         "metadata": {"acceptanceCriteria": ["POST /books returns 400 when title is missing"]}},
        {"id": "fix", "description": "[BUILD_FIX] Resolve missing symbol Loan", "metadata": {}},
    ]}
    SMOKE = "PASS [2] x\nFAIL [14] y\nFAIL [15] z\nPASS [22] w\n"

    def test_attention_shares_are_computed_over_resolved_feature_cycles(self):
        log = (self._cycle(1, "loan", True) + self._cycle(2, "val", True)
               + self._cycle(3, "val", False))
        got = measure_run(self._run_dir(log, self.TREE, self.SMOKE))
        self.assertAlmostEqual(got["attention"]["loans_ref"], 1 / 3)
        self.assertAlmostEqual(got["attention"]["validation"], 2 / 3)

    def test_build_fix_cycles_are_held_out_of_attention(self):
        log = (self._cycle(1, "loan", True) + self._cycle(2, "val", True)
               + self._cycle(3, "fix", True))
        got = measure_run(self._run_dir(log, self.TREE, self.SMOKE))
        self.assertEqual(got["resolved_cycles"], 2)
        self.assertEqual(got["repair_cycles"], 1)
        self.assertAlmostEqual(got["attention"]["loans_ref"], 0.5)

    def test_a_subgoal_absent_from_the_stack_is_unresolved_not_attributed(self):
        log = self._cycle(1, "loan", True) + self._cycle(2, "gone-in-a-replan", True)
        got = measure_run(self._run_dir(log, self.TREE, self.SMOKE))
        self.assertAlmostEqual(got["unresolved_share"], 0.5)
        self.assertAlmostEqual(got["attention"]["loans_ref"], 1.0)

    def test_family_pass_rate_reads_the_smoke_sheet(self):
        got = measure_run(self._run_dir(self._cycle(1, "loan", True), self.TREE, self.SMOKE))
        self.assertAlmostEqual(got["pass"]["loans_ref"], 1 / 3)   # 22 passed, 14 and 15 did not
        self.assertAlmostEqual(got["pass"]["validation"], 1.0)    # only check 2 was observed

    def test_a_family_with_no_observed_check_is_none_not_zero(self):
        got = measure_run(self._run_dir(self._cycle(1, "loan", True), self.TREE,
                                        "PASS [2] x\n"))
        self.assertIsNone(got["pass"]["loans_limit"])

    def test_conversion_is_merges_over_cycles_within_the_run(self):
        log = (self._cycle(1, "loan", True) + self._cycle(2, "loan", False)
               + self._cycle(3, "val", True) + self._cycle(4, "val", True))
        got = measure_run(self._run_dir(log, self.TREE, self.SMOKE))
        self.assertAlmostEqual(conversion(got, ("loans_ref",)), 0.5)
        self.assertAlmostEqual(conversion(got, ("validation",)), 1.0)

    def test_conversion_abstains_when_the_run_barely_touched_the_family(self):
        got = measure_run(self._run_dir(self._cycle(1, "val", True), self.TREE, self.SMOKE))
        self.assertIsNone(conversion(got, ("loans_ref",)))

    def test_a_run_missing_any_of_the_three_inputs_is_skipped(self):
        d = self._run_dir(self._cycle(1, "loan", True), self.TREE, self.SMOKE)
        os.remove(os.path.join(d, "artifact-smoke.txt"))
        self.assertIsNone(measure_run(d))


class TestPearson(unittest.TestCase):
    def test_perfect_positive(self):
        self.assertAlmostEqual(pearson([1, 2, 3], [2, 4, 6]), 1.0)

    def test_perfect_negative(self):
        self.assertAlmostEqual(pearson([1, 2, 3], [6, 4, 2]), -1.0)

    def test_abstains_below_three_points(self):
        self.assertIsNone(pearson([1, 2], [1, 2]))

    def test_abstains_on_a_constant_series(self):
        self.assertIsNone(pearson([1, 1, 1], [1, 2, 3]))


if __name__ == "__main__":
    unittest.main(verbosity=2)
