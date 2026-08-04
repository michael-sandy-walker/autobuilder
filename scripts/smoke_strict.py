#!/usr/bin/env python3
"""
smoke_strict.py — T6-K: a second score that refuses points awarded for missing code.

Five of the 23 checks assert a 404. An endpoint that does not exist 404s *everything*, so those
five pass whether the behaviour was implemented or the code is simply absent — the referee pays
for the absence it is meant to penalise.

This does NOT change `smoke`. That number keeps its meaning so the 60 runs of ledger stay
readable, exactly as T6-A added `smoke_preserved` beside `smoke` rather than redefining it. Both
numbers are meant to be reported together; reporting only the strict one would be re-basing while
claiming not to.

The gate needs no new probing. Each expected-404 check already has a WITNESS elsewhere in the
suite — a check that exercises the same endpoint on the happy path. The witness must prove
EXISTENCE, not correctness: a witness that passed obviously reached the endpoint, and a witness
that failed still proves it is there whenever it observed any status other than 404. Only a
witness that itself 404s leaves the expected-404 point unearned.

  check                          witness that the endpoint exists
  [7]  GET /books absent    404  [6]  GET /books/{id}     200
  [12] GET /members absent  404  [11] GET /members/{id}   200
  [14] POST /loans bad book 404  [13] POST /loans valid   201
  [15] POST /loans bad mem  404  [13] POST /loans valid   201
  [22] POST return absent   404  [20] POST return         200

A withheld point is reported as `discounted`, never silently dropped, so the difference between
the two scores is always explainable check by check.

Usage:
  smoke_strict.py [run-dir-glob ...]      default: /tmp/preflight_runs/*
  smoke_strict.py --json
"""
import argparse
import glob
import json
import os
import re
import sys

FAIL_RE = re.compile(r"^FAIL \[(\d+)\]")
PASS_RE = re.compile(r"^PASS \[(\d+)\]")
GOT_RE = re.compile(r"got (\d{3})")

# expected-404 check -> the check whose PASS proves the endpoint is actually there
WITNESS = {7: 6, 12: 11, 14: 13, 15: 13, 22: 20}

LABELS = {7: "GET /books absent", 12: "GET /members absent", 14: "POST /loans bad book",
          15: "POST /loans bad member", 22: "POST return absent"}


def read_sheet(path):
    """{check -> (passed, observed_status_or_None)}."""
    results = {}
    try:
        with open(path, errors="replace") as fh:
            for line in fh:
                m = FAIL_RE.match(line)
                if m:
                    got = GOT_RE.search(line)
                    results[int(m.group(1))] = (False, int(got.group(1)) if got else None)
                    continue
                m = PASS_RE.match(line)
                if m:
                    results[int(m.group(1))] = (True, None)
    except OSError:
        return None
    return results or None


def endpoint_exists(results, witness):
    """Does the witness show the endpoint is THERE? Existence, not correctness.

    A witness that PASSED obviously reached it. A witness that FAILED still proves existence
    whenever it observed any status other than 404 — `want 200 got 204` is a wrong status from a
    live endpoint, not an absent one. Only a witness that itself 404s, or whose status is unknown,
    leaves the expected-404 point unearned. Getting this wrong in the strict direction was the
    first cut of this script: it withheld check 22 from a 22/23 run whose return endpoint plainly
    existed and answered 204, which is the over-discounting this second score must not introduce.
    """
    entry = results.get(witness)
    if entry is None:
        return False
    passed, status = entry
    if passed:
        return True
    return status is not None and status != 404


def score(results):
    """(lenient, strict, [discounted checks]) for one run's per-check sheet."""
    lenient = sum(1 for passed, _ in results.values() if passed)
    discounted = []
    for check, witness in WITNESS.items():
        entry = results.get(check)
        if entry and entry[0] and not endpoint_exists(results, witness):
            discounted.append(check)
    return lenient, lenient - len(discounted), sorted(discounted)


# Which summary field each sheet is the companion of. The two sheets score DIFFERENT TREES —
# `smoke` is the final files, `smoke_preserved` the best saved copy — so a strict number must say
# which one it belongs to. Emitting one strict score beside both was the first wiring of this and
# it read as "4 points withheld" on a run where nothing was withheld at all: the gap was entirely
# that the two numbers described different artifacts.
SHEETS = {"artifact-smoke.txt": "artifact", "preserved-smoke.txt": "preserved"}


def score_dir(run_dir):
    """{'artifact': (lenient, strict, discounted), 'preserved': (...)} for whichever sheets exist."""
    out = {}
    for name, label in SHEETS.items():
        sheet = read_sheet(os.path.join(run_dir, name))
        if sheet:
            out[label] = score(sheet)
    return out


def collect(patterns):
    out = {}
    for pattern in patterns:
        for d in sorted(glob.glob(pattern)):
            if not os.path.isdir(d):
                continue
            for name in ("preserved-smoke.txt", "artifact-smoke.txt"):
                sheet = read_sheet(os.path.join(d, name))
                if sheet:
                    out[os.path.basename(d.rstrip("/"))] = sheet
                    break
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("patterns", nargs="*", default=["/tmp/preflight_runs/*"])
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()

    runs = collect(args.patterns or ["/tmp/preflight_runs/*"])
    if not runs:
        print("no run carries a per-check smoke sheet")
        return 1

    rows = {name: score(sheet) for name, sheet in runs.items()}

    if args.json:
        payload = {}
        for pattern in (args.patterns or ["/tmp/preflight_runs/*"]):
            for d in sorted(glob.glob(pattern)):
                if not os.path.isdir(d):
                    continue
                per = score_dir(d)
                if per:
                    payload[os.path.basename(d.rstrip("/"))] = {
                        label: {"smoke": l, "smoke_strict": st, "discounted": disc}
                        for label, (l, st, disc) in per.items()}
        json.dump(payload, sys.stdout, indent=1, sort_keys=True)
        print()
        return 0

    affected = {n: r for n, r in rows.items() if r[2]}
    print(f"T6-K strict score — {len(runs)} runs with a per-check sheet\n")
    print(f"{'run':<24}{'smoke':>7}{'strict':>8}   points withheld")
    for name, (lenient, strict, disc) in sorted(rows.items()):
        if not disc:
            continue
        why = ", ".join(f"[{c}] {LABELS[c]}" for c in disc)
        print(f"{name:<24}{lenient:>7}{strict:>8}   {why}")
    if not affected:
        print("  (none — every expected-404 point in this corpus was earned)")

    total_l = sum(r[0] for r in rows.values())
    total_s = sum(r[1] for r in rows.values())
    print(f"\n{len(affected)} of {len(runs)} runs collected at least one unearned point")
    print(f"corpus totals: smoke {total_l} → strict {total_s} "
          f"({(total_l - total_s) / len(runs):.2f} points per run withheld)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
