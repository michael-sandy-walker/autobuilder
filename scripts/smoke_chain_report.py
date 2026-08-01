#!/usr/bin/env python3
"""
smoke_chain_report.py — T6-K: which smoke check is actually broken, across runs.

`smoke23.sh` is sequential and stateful. Check 1 creates the book whose id checks 6/13/15/16 use;
check 8 creates the member checks 11/13/14/16..19/23 use; check 13 creates the loan checks 20/21
use. When a create fails, `jid()` silently falls back to id 1 and every downstream check probes a
resource that may not exist. So a raw "check 20 failed" tally counts echoes as defects, and the
loudest number names the wrong target.

This report separates the two:

  RAW        how often a check failed at all.
  ORIGINATED how often it failed while every check it depends on PASSED — i.e. the artifact really
             does get this one wrong, independently. This is the actionable column.
  ECHOED     raw minus originated: failures fully explained by an upstream break.

A check with a high raw count and near-zero originated count needs no work; fixing its feeder
fixes it. A check with a high originated count is a real, isolated defect and is where a run's
score is actually lost.

Input: the per-check outputs preflight now preserves (`*-smoke.txt` in each run dir). Runs that
predate that capture contribute nothing rather than a guess — the same discipline as the outcome
ledger's refusal to invent historical scores.

Usage:
  smoke_chain_report.py [run-dir-glob ...]      default: /tmp/preflight_runs/*
  smoke_chain_report.py --json
"""
import argparse
import glob
import json
import os
import re
import sys

FAIL_RE = re.compile(r"^FAIL \[(\d+)\]")
PASS_RE = re.compile(r"^PASS \[(\d+)\]")
TOTAL_RE = re.compile(r"^SMOKE:\s*(\d+)/(\d+)")

# What each check needs to have gone right before its own result means anything. Derived from
# smoke23.sh by reading which shell variable each request interpolates, plus the two semantic
# prerequisites where a check asserts a *consequence* of an earlier one (16 needs a loan to exist
# before "already on loan" can be 409; 21 needs 20's return to have happened).
DEPENDS_ON = {
    1: [], 2: [], 3: [], 4: [], 5: [],
    6: [1],            # GET /books/{id} uses BOOK1
    7: [],
    8: [],
    9: [], 10: [],
    11: [8],           # GET /members/{id} uses MEM1
    12: [],
    13: [1, 8],        # POST /loans uses BOOK1 + MEM1
    14: [8],           # bad book id, real member
    15: [1],           # real book, bad member id
    16: [1, 8, 13],    # "already on loan" presupposes 13 created one
    17: [8], 18: [8], 19: [8],
    20: [13],          # return uses LOAN1
    21: [13, 20],      # "return again" presupposes 20 returned it
    22: [],
    23: [8],           # list by MEM1
}

LABELS = {
    1: "POST /books valid", 2: "POST /books missing title", 3: "POST /books title>120",
    4: "POST /books bad isbn", 5: "GET /books", 6: "GET /books/{id}", 7: "GET /books absent",
    8: "POST /members valid", 9: "POST /members dup email", 10: "POST /members missing name",
    11: "GET /members/{id}", 12: "GET /members absent", 13: "POST /loans valid",
    14: "POST /loans bad book", 15: "POST /loans bad member", 16: "POST /loans book on loan",
    17: "POST /loans 2nd", 18: "POST /loans 3rd", 19: "POST /loans 4th -> 422 LIMIT",
    20: "POST return", 21: "POST return again", 22: "POST return absent",
    23: "GET /loans?memberId",
}


def read_run(path):
    """One run's outcome: {check -> True/False} and its total, or None when it was never scored."""
    results, total = {}, None
    try:
        with open(path, errors="replace") as fh:
            for line in fh:
                m = FAIL_RE.match(line)
                if m:
                    results[int(m.group(1))] = False
                    continue
                m = PASS_RE.match(line)
                if m:
                    results[int(m.group(1))] = True
                    continue
                m = TOTAL_RE.match(line)
                if m:
                    total = (int(m.group(1)), int(m.group(2)))
    except OSError:
        return None
    return (results, total) if results else None


def collect(patterns):
    """Prefers the preserved artifact's sheet — the tree `smoke_preserved` scores."""
    runs = {}
    for pattern in patterns:
        for d in sorted(glob.glob(pattern)):
            if not os.path.isdir(d):
                continue
            for name in ("preserved-smoke.txt", "artifact-smoke.txt"):
                got = read_run(os.path.join(d, name))
                if got:
                    runs[os.path.basename(d.rstrip("/"))] = got
                    break
    return runs


def analyse(runs):
    raw, originated, appeared = {}, {}, {}
    for results, _ in runs.values():
        for check, ok in results.items():
            appeared[check] = appeared.get(check, 0) + 1
            if ok:
                continue
            raw[check] = raw.get(check, 0) + 1
            # Originated only when every dependency was itself observed AND passed. A dependency
            # that was not observed is unknown, not passed, so the failure is left unattributed
            # rather than credited to this check.
            deps = DEPENDS_ON.get(check, [])
            if all(results.get(d) is True for d in deps):
                originated[check] = originated.get(check, 0) + 1
    return raw, originated, appeared


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("patterns", nargs="*", default=["/tmp/preflight_runs/*"])
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()

    runs = collect(args.patterns or ["/tmp/preflight_runs/*"])
    if not runs:
        print("no run carries a per-check smoke sheet yet — preflight started preserving them "
              "2026-08-01; re-score preserved artifacts to backfill")
        return 0

    raw, originated, appeared = analyse(runs)
    totals = [t[0] for _, t in runs.values() if t]

    if args.json:
        json.dump({"runs": len(runs), "raw": raw, "originated": originated,
                   "appeared": appeared}, sys.stdout, indent=1, sort_keys=True)
        print()
        return 0

    print(f"{len(runs)} run(s) with a per-check sheet"
          + (f"; scores {sorted(totals)}" if totals else ""))
    print()
    print(f"{'#':>3} {'check':<30} {'raw':>5} {'originated':>11} {'echoed':>7}   share of runs")
    for check in sorted(appeared):
        r, o, n = raw.get(check, 0), originated.get(check, 0), appeared[check]
        if not r:
            continue
        bar = "#" * o + "." * (r - o)
        print(f"{check:>3} {LABELS.get(check, '?'):<30} {r:>5} {o:>11} {r - o:>7}   "
              f"{bar} {100 * o / n:.0f}% originated")
    print()
    print("ORIGINATED is the actionable column — the check failed while everything it depends on "
          "passed.\nA raw count with no originated failures is an echo: fix its feeder, not it.")
    ranked = sorted(originated.items(), key=lambda kv: -kv[1])[:5]
    if ranked:
        print("\ntop independent defects:")
        for check, count in ranked:
            print(f"  [{check:>2}] {LABELS.get(check, '?'):<30} {count}/{appeared[check]} runs")
    return 0


if __name__ == "__main__":
    sys.exit(main())
