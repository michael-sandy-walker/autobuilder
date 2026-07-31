#!/usr/bin/env python3
"""
outcome_ledger.py — T6-E: a durable, append-only record of what each run actually produced.

Outcome ground truth is the scarcest thing in this project. T6-D found it exists for only 14 of 237
recorded runs, which caps every outcome-linked validation — a detector cannot be shown to predict
anything without it. Investigating why revealed the cause is not that runs go unscored, but that
**scores are ephemeral**: preflight writes `summary.txt` into the run's own directory under
`/tmp/preflight_runs/`, and a reboot clears it. Scored series referenced in earlier session notes are simply gone.

This is the third instance of one pattern:
  T6-A  the best verified artifact was discarded at the terminal      -> preserve it
  T6-C  the health envelope was rebuilt from run dirs living in /tmp  -> freeze the feature vectors
  T6-E  run outcomes were written to /tmp and lost                    -> append them to the repo

So the ledger keeps one small row per run, in the repository, where it survives. It grows
monotonically: every future scored run adds a row, so the population available for outcome-linked
validation only ever increases.

**Deliberately NOT retroactive.** Historical smoke scores are unrecoverable — no `SMOKE:` record
exists anywhere in the archive, and scoring an old run would need its per-run output tree, which was
never kept. Inventing labels for those 223 runs would manufacture ground truth, so the ledger stays
silent about them and says so.

Usage:
  outcome_ledger.py --append <run-dir>     record one run (idempotent; safe to re-run)
  outcome_ledger.py --seed <path>...       scan for run directories, record every scored one
  outcome_ledger.py --report               coverage, outcome mix, and what is still missing
  outcome_ledger.py --json                 machine-readable
Exit 0 always: a bookkeeping step must never fail the run it is recording.
"""
import argparse
import datetime as dt
import glob
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
LEDGER = os.path.join(HERE, "run_outcomes.json")
SMOKE_RE = re.compile(r"SMOKE:\s*(\d+)/(\d+)")
# Fields worth keeping per run: the outcome itself plus the drift context T6-A/T6-B compute.
CARRIED = ("terminal", "smoke", "smoke_preserved", "preserved_source", "score_delta",
           "run_duration_min", "total_cycles", "peaked_at_cycle", "drift_tail_cycles", "merges",
           # T2-EX: how much of the run was verifiable at all. Across three frozen runs of one
           # build and one seed the executable share was 71%, 31% and 74%, and the run at 31% was
           # dark on verification for its whole length with nothing recording it. Carried so the
           # six-run question — is authoring quality a cause of a bad run or a symptom of one? —
           # is answerable from the ledger rather than asserted from three runs. Absent on runs
           # predating the readout, which is why it is read with .get and never assumed.
           "criteria_executable", "criteria_authored",
           # Provenance: "computed-offline" marks a share derived from the archived
           # GoalStack rather than reported by the run itself. Unlike T6-E's refusal to
           # backfill smoke scores — where the artifacts were gone — the input here still
           # exists and the computation is deterministic, so the value is a measurement
           # rather than a reconstruction. The label keeps the two distinguishable.
           "criteria_source",
           # T4-DA: clauses closed against the df2a907 control (baseline 0 per run), and the
           # median cycle so the watch can judge NET effect. The readout costs ~665 est tokens per
           # call and 23% of the evolution prompt, so a run that closes one clause and loses eight
           # cycles is not a gain — recording only the benefit side would hide that.
           "clauses_closed_vs_control", "median_cycle_seconds",
           # T6-J: per-category wall-clock, so "where did the run go" is answerable
           # from the ledger. Absent on runs predating the instrumentation.
           "cost_readout",
           # T2-EY: how often the loop re-proposed what it had already been refused, and how much
           # of that the gate stopped before an evaluator call. Both sides land for the same reason
           # the T4-DA pair does — a mechanism that records only what it saved reads as a free win,
           # and the exposure is the finding. Absent on runs predating the ledger, so read with
           # .get; a missing value means unknown, never zero.
           "repeat_proposals", "repeats_refused")


def read_summary(run_dir):
    path = os.path.join(run_dir, "summary.txt")
    if not os.path.isfile(path):
        return {}
    out = {}
    try:
        with open(path, errors="replace") as fh:
            for line in fh:
                if "=" in line:
                    k, v = line.split("=", 1)
                    out[k.strip()] = v.strip()
    except OSError:
        return {}
    return out


def smoke_pass(text):
    """Passing checks from a `SMOKE: n/23` reading, or None for a failure token / absence."""
    m = SMOKE_RE.search(text or "")
    return int(m.group(1)) if m else None


def smoke_total(text):
    m = SMOKE_RE.search(text or "")
    return int(m.group(2)) if m else None


def is_scored(summary):
    """A run counts as scored when its smoke reading is conclusive — a number OR a definite failure.

    `artifact-build-failed` and `boot-failed` ARE outcomes (the artifact did not work), so they carry
    ground truth. `skipped` and absence do not.
    """
    smoke = summary.get("smoke", "")
    if smoke_pass(smoke) is not None:
        return True
    return any(tok in smoke for tok in ("artifact-build-failed", "boot-failed"))


def load():
    try:
        with open(LEDGER) as fh:
            payload = json.load(fh)
    except (OSError, ValueError):
        return {"note": "", "runs": {}}
    payload.setdefault("runs", {})
    return payload


def save(payload):
    payload["note"] = ("Durable per-run outcome ledger (T6-E). Appended by preflight; survives the "
                       "/tmp clearing that lost earlier scores. Not retroactive: archived "
                       "runs were never scored and their output trees no longer exist.")
    payload["updated"] = dt.datetime.now().strftime("%Y-%m-%d")
    with open(LEDGER, "w") as fh:
        json.dump(payload, fh, indent=1, sort_keys=True)


def append(run_dir, payload=None, quiet=False):
    """Record one run directory. Returns the row, or None when it carries no conclusive outcome."""
    own = payload is None
    payload = payload if payload is not None else load()
    run_id = os.path.basename(os.path.abspath(run_dir))
    summary = read_summary(run_dir)
    if not is_scored(summary):
        if not quiet:
            print("outcome_ledger: %s has no conclusive smoke reading — not recorded" % run_id)
        return None
    row = {k: summary[k] for k in CARRIED if k in summary}
    row["recorded"] = dt.datetime.now().strftime("%Y-%m-%d")
    passes, total = smoke_pass(summary.get("smoke")), smoke_total(summary.get("smoke"))
    if passes is not None and total:
        row["smoke_pass"] = passes
        row["smoke_total"] = total
    payload["runs"][run_id] = row
    if own:
        save(payload)
    if not quiet:
        print("outcome_ledger: recorded %s (%s)" % (run_id, row.get("smoke", "?")))
    return row


def seed(paths):
    payload = load()
    before = len(payload["runs"])
    seen = 0
    for path in paths:
        candidates = []
        if os.path.isfile(os.path.join(path, "summary.txt")):
            candidates.append(path)
        candidates += [os.path.dirname(p) for p in
                       glob.glob(os.path.join(path, "**", "summary.txt"), recursive=True)]
        for run_dir in sorted(set(candidates)):
            seen += 1
            append(run_dir, payload, quiet=True)
    save(payload)
    print("outcome_ledger: scanned %d run director%s, ledger now holds %d run(s) (+%d)"
          % (seen, "y" if seen == 1 else "ies", len(payload["runs"]),
             len(payload["runs"]) - before))
    return payload


def report(payload, out):
    runs = payload.get("runs", {})
    out.write("T6-E run-outcome ledger — %d run(s) recorded\n" % len(runs))
    if not runs:
        out.write("  empty. Seed with --seed, or let preflight append on the next live run.\n")
        return
    good = bad = 0
    for row in runs.values():
        p, t = row.get("smoke_pass"), row.get("smoke_total")
        if p is not None and t:
            # 20/23 is the honest-fulfilment bar this project uses; below it a FULFILLED run is false.
            if p * 23 >= 20 * t:
                good += 1
            else:
                bad += 1
        else:
            bad += 1                    # a build/boot failure is a conclusive bad outcome
    out.write("  outcome mix: %d good · %d bad\n" % (good, bad))
    out.write("  terminals: ")
    terms = {}
    for row in runs.values():
        t = (row.get("terminal") or "?").split(":")[0]
        terms[t] = terms.get(t, 0) + 1
    out.write(" · ".join("%s %d" % kv for kv in sorted(terms.items(), key=lambda kv: -kv[1])) + "\n")
    # T6-J: DURATION IS PART OF THE OUTCOME, not a footnote. On 2026-07-30 short measurement
    # probes (18-30 min, launched to time a per-cycle quantity) wrote rows alongside 90-minute
    # runs, and this table showed an 18-minute probe's "9/23" beside a full run's "23/23" with
    # nothing to tell them apart. A reader comparing them would reach a false conclusion about a
    # mechanism — the exact misuse this ledger exists to prevent. No threshold is applied and no
    # row is hidden: the number is shown, and judging comparability is the reader's job once they
    # can see it.
    out.write("\n  %-24s %-22s %-10s %-18s %s\n"
              % ("run", "terminal", "smoke", "preserved", "min"))
    for rid, row in sorted(runs.items()):
        out.write("  %-24s %-22s %-10s %-18s %s\n" % (rid[:24], (row.get("terminal") or "?")[:22],
                                                (row.get("smoke") or "?")[:10],
                                                (row.get("smoke_preserved") or "-")[:18],
                                                row.get("run_duration_min") or "?"))
    out.write("\n  Comparability: a run's smoke score is only meaningful against runs of similar\n"
              "  length. Short rows here are measurement probes, launched to time a per-cycle\n"
              "  quantity, and their scores must not be read against full-length runs.\n")
    out.write("\n  Scope note: this ledger only ever grows forward. The ~223 archived runs in\n"
              "  backup/ were never scored and cannot be — no SMOKE record survives and their\n"
              "  per-run output trees are gone. Outcome-linked validation (T6-D, and P2+ of the\n"
              "  pattern-recognition plan) gains power as this file fills, not by relabelling history.\n")


def main(argv):
    ap = argparse.ArgumentParser(description="T6-E durable run-outcome ledger.")
    ap.add_argument("--append", metavar="RUN_DIR")
    ap.add_argument("--seed", nargs="+", metavar="PATH")
    ap.add_argument("--report", action="store_true")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)

    if args.append:
        append(args.append)
        return 0
    if args.seed:
        payload = seed(args.seed)
        report(payload, sys.stdout)
        return 0
    payload = load()
    if args.json:
        print(json.dumps(payload, indent=1, sort_keys=True))
        return 0
    report(payload, sys.stdout)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except Exception as exc:            # never fail the run being recorded
        sys.stderr.write("outcome_ledger: %s\n" % exc)
        sys.exit(0)
