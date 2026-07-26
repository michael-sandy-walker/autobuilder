#!/usr/bin/env python3
"""preserved_freshness.py — T6-F: is this run's `preserved-artifact/` actually THIS run's?

The target's `meta/preserved-artifact/` and its provenance survive across runs, so the mere
existence of a copy says nothing about which run produced it. Before T6-F, `preflight.sh`
branched on existence alone: a killed run that inherited a parent's copy scored THAT as its
own `smoke_preserved` and skipped T6-A's offline reconstruction — the one path that exists
for killed runs. Observed 2026-07-26: runs 3 and 4 of one series both reported a copy whose
provenance was written at the end of run 2, while run 3's own tree was the lineage's first
bootable artifact.

Attribution rule, in order of strength:

  1. `fromCycle` / `haltCycle` appears among the cycles THIS run logged  -> fresh
     (exact and timezone-free: a cycle id is minted by the run that ran it)
  2. the cycle is absent from this run's log                            -> stale
  3. no provenance beside the copy                                      -> stale
     (`PreservedArtifactService` always writes provenance with the copy, so a copy without
     one cannot be attributed to this run — and an unattributable copy must not be trusted)
  4. no run log to compare against                                      -> unknown
     (the caller decides; preflight treats unknown as stale for the same reason as 3)

Prints ONE line to stdout: `fresh:<cycle>` | `stale:<reason>` | `unknown:<reason>`.
Always exits 0 — this is an advisory the caller acts on, never a gate that can abort a run.
"""

import argparse
import json
import re
import sys
from pathlib import Path

# Only the cycle-start BANNER counts as "this run ran it". Matching a bare `cycle-\d+`
# anywhere is wrong and was caught against real logs: a resumed run prints its parent's id in
# `T4-CD: resumed from preserved artifact (fromCycle=…)` and echoes it inside JSON payloads
# (`attemptId`/`cycleId`), so a bare-id rule called an inherited copy "fresh" — a no-op on the
# exact defect T6-F exists for.
CYCLE_RE = re.compile(r"\bCYCLE (cycle-\d+)\b")


def cycles_in_log(log_path: Path) -> set:
    """The cycle ids THIS run ran, taken from its cycle-start banners. Read with
    errors=replace: a killed run's log can end mid-write with invalid UTF-8, and that must not
    make a fresh copy look stale."""
    try:
        text = log_path.read_text(errors="replace")
    except OSError:
        return set()
    return set(CYCLE_RE.findall(text))


def claimed_cycle(provenance: dict) -> str:
    """The cycle the preserved copy came from. `fromCycle` is the selected snapshot; `haltCycle`
    is the cycle that triggered preservation. Either one identifies the producing run."""
    for key in ("fromCycle", "haltCycle"):
        value = provenance.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return ""


def classify(provenance_path: Path, run_log_path: Path) -> str:
    if not provenance_path.is_file():
        return "stale:no-provenance"
    try:
        provenance = json.loads(provenance_path.read_text(errors="replace"))
    except (OSError, ValueError) as exc:
        return f"stale:unreadable-provenance ({type(exc).__name__})"
    if not isinstance(provenance, dict):
        return "stale:unreadable-provenance (not an object)"

    cycle = claimed_cycle(provenance)
    if not cycle:
        return "stale:provenance-names-no-cycle"

    if not run_log_path.is_file():
        return f"unknown:no-run-log ({cycle})"

    logged = cycles_in_log(run_log_path)
    if not logged:
        return f"unknown:run-log-has-no-cycles ({cycle})"
    if cycle in logged:
        return f"fresh:{cycle}"
    return f"stale:cycle-not-in-this-run ({cycle})"


def main() -> int:
    ap = argparse.ArgumentParser(description="T6-F preserved-copy freshness check")
    ap.add_argument("--provenance", required=True,
                    help="path to preserved-artifact-provenance.json")
    ap.add_argument("--run-log", required=True,
                    help="path to THIS run's autobuilder.log")
    args = ap.parse_args()
    print(classify(Path(args.provenance), Path(args.run_log)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
