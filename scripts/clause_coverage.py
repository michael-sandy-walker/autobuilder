#!/usr/bin/env python3
"""T4-DA: how many of the specification's clauses a run's PLAN actually articulates.

The evaluator for the `t4da-clause-claimed-after-readout` watch, and the instrument that
built its control.

WHY IT EXISTS. A run producing a work item for a clause the seed-coverage readout named
proves nothing on its own: the planner reads the seed too. The question is whether a
readout-enabled run articulates clauses that runs WITHOUT the readout never do. Across the
three frozen runs at df2a907 (no readout, one build, one seed), 11 of the 16 seed clauses
were never fully articulated by any run's plan. Those 11 are the discriminating set and the
per-run baseline is 0.

DECISION RULE, fixed before the data (see the watch entry): three or more readout-enabled
runs averaging >= 1 closed clause supports the mechanism; averaging 0 refutes it, and the
readout should be re-read as inert prompt weight rather than defended.

This deliberately mirrors SeedCoverageAnalyzer's rules rather than importing them — it has
to run over archived corpora with no JVM. The mirror is asserted by test_clause_coverage.py
against the figures the Java side produces on the same corpora; if the two ever disagree,
the Java side is right and this file is stale.

    clause_coverage.py <run_dir> [<run_dir> ...]           per-run articulated clauses
    clause_coverage.py --control <run_dir> ...             clauses NO listed run articulates
    clause_coverage.py --against-control <file> <run_dir>  score a run against a saved control
"""
import json
import os
import re
import sys

NON_TERM = re.compile(r"[^A-Za-z0-9{}\\]+")
CLAUSE_BREAK = re.compile(r"(?<=[.;])\s+")
MIN_CLAUSE_TERMS = 3


def canonical(term):
    """Mirror of SeedCoverageAnalyzer.canonical — plural and singular must not disagree."""
    return term[:-1] if len(term) >= 5 and term.endswith("s") else term


def terms(text):
    out = set()
    for raw in NON_TERM.split((text or "").lower()):
        if raw and (len(raw) >= 3 or any(c.isdigit() for c in raw)):
            out.add(canonical(raw))
    return out


def clauses(seed):
    out = []
    for line in (seed or "").splitlines():
        for fragment in CLAUSE_BREAK.split(line):
            clause = fragment.strip()
            if len(terms(clause)) >= MIN_CLAUSE_TERMS:
                out.append(clause)
    return out


def plan_terms(goal_stack_path):
    """Every term of every subgoal description. The ROOT is excluded: its description IS the
    specification, so counting it would make every clause trivially articulated — the defect
    T4-DA's first field firing exposed."""
    with open(goal_stack_path, errors="replace") as fh:
        tree = json.load(fh)["goalTree"]
    descriptions = []

    def walk(node, depth):
        if depth > 0:
            descriptions.append(node.get("description") or "")
        for child in node.get("subGoals") or []:
            walk(child, depth + 1)

    walk(tree, 0)
    vocabulary = set()
    for description in descriptions:
        vocabulary |= terms(description)
    return vocabulary, len(descriptions)


def articulated(seed, vocabulary):
    """The clauses whose every distinctive term appears somewhere in the plan."""
    return [c for c in clauses(seed) if not (terms(c) - vocabulary)]


def seed_of(run_dir):
    """The specification, preferring the run's own copy over the repo's current one."""
    for candidate in (os.path.join(run_dir, "seed.txt"),
                      os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                   "..", "firestarter", "bootstrap", "seed.txt")):
        if os.path.isfile(candidate):
            with open(candidate, errors="replace") as fh:
                return fh.read()
    return None


def scan(run_dir):
    stack = os.path.join(run_dir, "GoalStack.json")
    seed = seed_of(run_dir)
    if not os.path.isfile(stack) or not seed:
        return None
    vocabulary, goals = plan_terms(stack)
    return {"run": os.path.basename(os.path.abspath(run_dir)),
            "goals": goals,
            "clauses": len(clauses(seed)),
            "articulated": articulated(seed, vocabulary)}


def main(argv):
    if not argv:
        print(__doc__)
        return 2

    if argv[0] == "--control":
        runs = [scan(d) for d in argv[1:]]
        runs = [r for r in runs if r]
        if not runs:
            print("clause_coverage: no readable run directories")
            return 1
        seed = seed_of(argv[1])
        ever = set()
        for r in runs:
            ever |= set(r["articulated"])
        never = [c for c in clauses(seed) if c not in ever]
        print("control over %d run(s) — %d of %d clauses articulated by NO run:"
              % (len(runs), len(never), len(clauses(seed))))
        for c in never:
            print("  -", c)
        return 0

    if argv[0] == "--against-control":
        with open(argv[1], errors="replace") as fh:
            control = [line.strip()[2:] for line in fh if line.strip().startswith("- ")]
        for run_dir in argv[2:]:
            r = scan(run_dir)
            if not r:
                print("%s: unreadable" % run_dir)
                continue
            closed = [c for c in control if c in r["articulated"]]
            print("%s  closed %d of %d control clauses" % (r["run"], len(closed), len(control)))
            for c in closed:
                print("     +", c[:96])
        return 0

    for run_dir in argv:
        r = scan(run_dir)
        if not r:
            print("%s: no GoalStack.json or no seed" % run_dir)
            continue
        print("%s  goals=%d  articulated %d of %d clauses"
              % (r["run"], r["goals"], len(r["articulated"]), r["clauses"]))
        for c in r["articulated"]:
            print("     +", c[:96])
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
