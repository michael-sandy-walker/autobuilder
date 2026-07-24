#!/usr/bin/env python3
"""
select_preserved.py — offline replica of PreservedArtifactService selection (T6-A).

Reproduces, outside the JVM, the snapshot selection the in-process T4-CA hook runs at an
honest halt — for the runs where the hook never fired at all: a run stopped by the preflight
time limit (or any external kill) leaves its per-cycle snapshots on disk but no
meta/preserved-artifact/. This script reads the per-snapshot stamps that
MutationSnapshotService writes (autobuilder-snapshot-state.json) and emits the *probe order*:
the candidate snapshot dirs to compile-verify, best first. The caller compile-verifies each in
turn and keeps the first that compiles — the same walk PreservedArtifactService.preserve() does
with JavacDirectProbe (a stamped buildResult can be stale, so the stamp alone is never trusted).

Selection mirrors PreservedArtifactService exactly:
  * run-relative entry-point bar — an entry point is required only if any snapshot of this run
    ever had one (a library-shaped target never grows one), OR a prior lineage provenance shows
    the lineage expected one;
  * a recorded build FAILURE disqualifies; null / COMPILE_OK / SKIPPED do not;
  * newest-first by recordedAt then cycle name;
  * probe order = newest eligible candidate, then every merged eligible candidate
    (newest-first) — a merged stamp's result came from that cycle's own post-merge gate, so
    those are the trustworthy fallbacks when the speculative newest tree fails to compile.

Usage:  select_preserved.py <snapshots_dir> [--provenance <prior-provenance.json>]
Stdout: one absolute snapshot dir per line, in probe order (empty when nothing passes the bar).
Stderr: a one-line selection summary (considered / eligible / entryPointRequired).
Exit:   0 when at least one candidate passes the bar; 3 when the run abstains (no candidate).
"""
import argparse
import json
import os
import sys

STATE_FILE = "autobuilder-snapshot-state.json"


def is_recorded_failure(build_result):
    # Mirror PreservedArtifactService.isRecordedFailure: only a recorded non-OK/non-SKIPPED
    # result disqualifies; null (no build ran that cycle) is unknown, not failing.
    return build_result is not None and build_result not in ("COMPILE_OK", "SKIPPED")


def read_candidates(snapshots_dir):
    out = []
    if not os.path.isdir(snapshots_dir):
        return out
    for name in os.listdir(snapshots_dir):
        d = os.path.join(snapshots_dir, name)
        state_path = os.path.join(d, STATE_FILE)
        if not os.path.isfile(state_path):
            continue  # pre-T4-CA snapshot — no recorded facts
        try:
            with open(state_path) as fh:
                state = json.load(fh)
        except (OSError, ValueError):
            continue
        out.append((os.path.abspath(d), name, state))
    return out


def read_prior_provenance(path):
    if not path or not os.path.isfile(path):
        return None
    try:
        with open(path) as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return None


def select(snapshots_dir, provenance_path=None):
    candidates = read_candidates(snapshots_dir)
    if not candidates:
        return [], dict(considered=0, eligible=0, entryPointRequired=False, reason="no-stamped-snapshots")

    lineage = read_prior_provenance(provenance_path)
    lineage_expects_ep = bool(lineage) and (
        lineage.get("entryPointPresent") or lineage.get("entryPointRequired"))
    entry_point_required = lineage_expects_ep or any(
        s.get("entryPointPresent") for _, _, s in candidates)

    eligible = [
        c for c in candidates
        if (not entry_point_required or c[2].get("entryPointPresent"))
        and not is_recorded_failure(c[2].get("buildResult"))
    ]
    # newest-first: ascending by (recordedAt, name) with null recordedAt treated as smallest
    # (nullsFirst), then reversed — identical ordering to the Java comparator.
    eligible.sort(key=lambda c: (c[2].get("recordedAt") or "", c[1]))
    eligible.reverse()

    if not eligible:
        return [], dict(considered=len(candidates), eligible=0,
                        entryPointRequired=entry_point_required, reason="none-pass-bar")

    newest = eligible[0]
    probe_order = [newest]
    probe_order.extend(c for c in eligible if c[2].get("merged") and c is not newest)
    return probe_order, dict(considered=len(candidates), eligible=len(eligible),
                             entryPointRequired=entry_point_required, reason="ok")


def main(argv):
    ap = argparse.ArgumentParser(description="Offline PreservedArtifactService selection (T6-A).")
    ap.add_argument("snapshots_dir")
    ap.add_argument("--provenance", help="prior lineage provenance JSON (raises the entry-point bar)")
    args = ap.parse_args(argv)

    probe_order, facts = select(args.snapshots_dir, args.provenance)
    sys.stderr.write(
        "select_preserved: considered={considered} eligible={eligible} "
        "entryPointRequired={entryPointRequired} reason={reason}\n".format(**facts))
    for d, _, _ in probe_order:
        print(d)
    return 0 if probe_order else 3


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
