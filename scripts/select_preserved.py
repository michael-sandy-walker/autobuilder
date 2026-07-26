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
                            [--run-log <this run's autobuilder.log>]
        T6-F: pass --run-log so only THIS run's snapshots are eligible — snapshots/ accumulates
        across a lineage, and without it a prior run's tree can be reported as this run's.
Stdout: one absolute snapshot dir per line, in probe order (empty when nothing passes the bar).
Stderr: a one-line selection summary (considered / eligible / entryPointRequired).
Exit:   0 when at least one candidate passes the bar; 3 when the run abstains (no candidate).
"""
import argparse
import json
import os
import pathlib
import sys

STATE_FILE = "autobuilder-snapshot-state.json"


def is_recorded_failure(build_result):
    # Mirror PreservedArtifactService.isRecordedFailure: only a recorded non-OK/non-SKIPPED
    # result disqualifies; null (no build ran that cycle) is unknown, not failing.
    return build_result is not None and build_result not in ("COMPILE_OK", "SKIPPED")


def read_candidates(snapshots_dir, own_cycles=None):
    """Snapshots this run may be scored on.

    T6-F: `snapshots/` accumulates across every run of a lineage, so without `own_cycles` a
    reconstruction can select a PREVIOUS run's tree and report it as this run's preserved
    artifact — the same defect as trusting an inherited `preserved-artifact/` copy, one level
    down. Snapshot directories are named by the cycle that produced them, so restricting to the
    cycle ids this run actually ran makes prior runs historical rather than eligible.
    `own_cycles=None` keeps the pre-T6-F behaviour for callers that have no run log.
    """
    out = []
    if not os.path.isdir(snapshots_dir):
        return out
    for name in os.listdir(snapshots_dir):
        if own_cycles is not None and name not in own_cycles:
            continue  # produced by another run of this lineage
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


def select(snapshots_dir, provenance_path=None, own_cycles=None):
    candidates = read_candidates(snapshots_dir, own_cycles)
    if not candidates:
        reason = "no-stamped-snapshots" if own_cycles is None else "no-stamped-snapshots-for-this-run"
        return [], dict(considered=0, eligible=0, entryPointRequired=False, reason=reason)

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
    ap.add_argument("--run-log", help="T6-F: THIS run's autobuilder.log — restricts candidates to "
                                      "the cycles this run actually ran, so a prior run's snapshot "
                                      "cannot be reported as this run's preserved artifact")
    args = ap.parse_args(argv)

    own_cycles = None
    if args.run_log:
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        from preserved_freshness import cycles_in_log  # one primitive, two consumers
        own_cycles = cycles_in_log(pathlib.Path(args.run_log))
        sys.stderr.write("select_preserved: run-scoped to %d cycle(s) from %s\n"
                         % (len(own_cycles), args.run_log))

    probe_order, facts = select(args.snapshots_dir, args.provenance, own_cycles)
    sys.stderr.write(
        "select_preserved: considered={considered} eligible={eligible} "
        "entryPointRequired={entryPointRequired} reason={reason}\n".format(**facts))
    for d, _, _ in probe_order:
        print(d)
    return 0 if probe_order else 3


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
