#!/usr/bin/env python3
"""Tests for clause_coverage.py — the evaluator for the t4da-clause-claimed-after-readout watch."""
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import clause_coverage as cc  # noqa: E402


SEED = (
    "Books: POST /books (create; title required, max 120 chars).\n"
    "Members: POST /members (create; email required, must be unique).\n"
    "All data stored in in-memory collections (no database).\n"
)


def run_dir(tmp, name, descriptions, root=SEED):
    path = os.path.join(tmp, name)
    os.makedirs(path)
    tree = {"description": root,
            "subGoals": [{"description": d, "subGoals": []} for d in descriptions]}
    with open(os.path.join(path, "GoalStack.json"), "w") as fh:
        json.dump({"goalTree": tree}, fh)
    with open(os.path.join(path, "seed.txt"), "w") as fh:
        fh.write(root)
    return path


class ClauseCoverage(unittest.TestCase):

    def test_the_root_goal_is_excluded_from_the_plan(self):
        # The root's description IS the specification. Counting it makes every clause trivially
        # articulated — exactly the defect T4-DA's first field firing exposed, where the readout
        # printed 0 unclaimed and would have reported no drift forever.
        with tempfile.TemporaryDirectory() as tmp:
            d = run_dir(tmp, "only-root", [])
            self.assertEqual(cc.scan(d)["articulated"], [])

    def test_a_clause_needs_every_term_present_not_merely_some(self):
        with tempfile.TemporaryDirectory() as tmp:
            # The seed splits on ";" exactly as the real one does, so "title required, max 120
            # chars)." is its own clause — naming the endpoint alone does not articulate it.
            partial = run_dir(tmp, "partial", ["Implement POST /books to create a book"])
            articulated = cc.scan(partial)["articulated"]
            self.assertIn("Books: POST /books (create;", articulated)
            self.assertNotIn("title required, max 120 chars).", articulated)

            full = run_dir(tmp, "full", [
                "Implement POST /books to create a book where the title is required, max 120 chars)"])
            self.assertIn("title required, max 120 chars).", cc.scan(full)["articulated"])

    def test_plural_and_singular_do_not_disagree(self):
        # Mirrors SeedCoverageAnalyzer.canonical. Without it "collections" and "collection" are
        # different terms and a clause is reported unarticulated purely on a plural.
        self.assertEqual(cc.canonical("collections"), cc.canonical("collection"))
        self.assertEqual(cc.canonical("loans"), "loan")
        self.assertEqual(cc.canonical("has"), "has")  # too short to reduce

    def test_short_and_numeric_terms_follow_the_java_rule(self):
        t = cc.terms("max 120 chars a b")
        self.assertIn("120", t)
        self.assertIn("max", t)
        self.assertNotIn("a", t)

    def test_control_is_the_clauses_no_run_articulates(self):
        with tempfile.TemporaryDirectory() as tmp:
            a = run_dir(tmp, "a", [
                "Implement POST /books to create a book where the title is required, max 120 chars)"])
            b = run_dir(tmp, "b", ["Implement something unrelated"])
            ever = set(cc.scan(a)["articulated"]) | set(cc.scan(b)["articulated"])
            never = [c for c in cc.clauses(SEED) if c not in ever]

            self.assertNotIn("title required, max 120 chars).", never)
            self.assertIn("All data stored in in-memory collections (no database).", never)

    def test_a_control_run_scores_zero_against_its_own_control(self):
        # The scoring path must not credit a run for a clause its own control says it never made.
        with tempfile.TemporaryDirectory() as tmp:
            b = run_dir(tmp, "b", ["Implement something unrelated"])
            control = [c for c in cc.clauses(SEED) if c not in cc.scan(b)["articulated"]]
            closed = [c for c in control if c in cc.scan(b)["articulated"]]
            self.assertEqual(closed, [])

    def test_a_run_that_closes_a_control_clause_is_credited(self):
        with tempfile.TemporaryDirectory() as tmp:
            b = run_dir(tmp, "b", ["Implement something unrelated"])
            control = [c for c in cc.clauses(SEED) if c not in cc.scan(b)["articulated"]]

            better = run_dir(tmp, "better", [
                "Implement something unrelated",
                "Implement POST /books to create a book where the title is required, max 120 chars)"])
            closed = [c for c in control if c in cc.scan(better)["articulated"]]
            self.assertIn("title required, max 120 chars).", closed)

    def test_a_fragment_too_short_to_check_is_not_a_clause(self):
        self.assertEqual(cc.clauses("Do it."), [])

    def test_an_unreadable_run_yields_no_opinion_rather_than_a_crash(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertIsNone(cc.scan(os.path.join(tmp, "absent")))

    def test_the_runs_own_seed_wins_over_the_repos_current_one(self):
        # A corpus scored months later must be judged against the specification it was given,
        # not against whatever the repo holds today.
        with tempfile.TemporaryDirectory() as tmp:
            d = run_dir(tmp, "old", ["Implement the widget"], root="Build a widget that spins.\n")
            self.assertIn("Build a widget that spins.", cc.seed_of(d))


if __name__ == "__main__":
    unittest.main()
