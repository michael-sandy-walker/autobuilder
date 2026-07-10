#!/usr/bin/env python3
"""
signal_lifetime_report.py — T4-BO offline analyser: "measure before tuning" for the
~20 hand-tuned decayPerCycle signal constants.

Usage: python3 scripts/signal_lifetime_report.py <path-to-behaviour.ndjson>

Reads the behaviour trace (pretty-printed, concatenated JSON objects — NOT one-object-
per-line, despite the .ndjson extension; see project_log_and_backup_conventions.md) and
produces two tables from a single pass, no external dependencies:

1. LIFETIME — per signal type, computed from signal_lifetime_observed events (each one
   an emitCycle/deathCycle/deathReason for a completed episode; emitCycle/deathCycle are
   the runtime tracker's own monotonic cycle counter, not the cycleId timestamp string,
   so deathCycle-emitCycle is directly the lifetime in cycles): emission count, lifetime
   p50/p95, and a decayed/cleared breakdown. A signal type with a lifetime dominated by
   "cleared" deaths is over-tuned by some OTHER agent's clear, not its own decay constant
   — a candidate to shorten decayPerCycle for. A signal type that never appears here at
   all (but does appear in a snapshot) never completed an episode this run — see the
   "still active" list below instead of inventing a fake lifetime for it.

2. CORRELATION — per (signalType, role), computed by joining signal_snapshot (raw
   presence at fitness-ranking time, BEFORE any agent executes — the causality-preserving
   point) against agent_selected (which role actually ran), on the shared cycleId:
       P(role selected | signal present this cycle) - P(role selected | signal absent)
   Near-zero across a real run is the "candidate for deletion" signal the ticket names —
   the constants may be fine, but the signal isn't actually steering anything.

Known limitation (documented in project_t4bo_design_note.md, not silently ignored): when
autobuilder.deliberation.meta-lane-enabled=true (non-default as of T4-AI-2+5), an
agent_selected event can come from the meta-step prefix, which executes BEFORE the fitness
pass's signal_snapshot is taken this cycle — the event schema can't distinguish "selected
by meta" from "selected by fitness pass," so a meta-lane-on run can show spurious
correlation for whatever the meta plan happened to pick. Meta lane is off by default in the
field, so this is a corpus caveat, not a bug to silently paper over.
"""

import json
import statistics
import sys
from collections import defaultdict


def iter_json_objects(text):
    """Yields each pretty-printed, whitespace-separated JSON object in `text` in order."""
    decoder = json.JSONDecoder()
    idx = 0
    n = len(text)
    while idx < n:
        while idx < n and text[idx].isspace():
            idx += 1
        if idx >= n:
            break
        obj, end = decoder.raw_decode(text, idx)
        yield obj
        idx = end


def percentile(sorted_values, pct):
    if not sorted_values:
        return None
    k = (len(sorted_values) - 1) * (pct / 100.0)
    f, c = int(k), min(int(k) + 1, len(sorted_values) - 1)
    if f == c:
        return sorted_values[f]
    return sorted_values[f] + (sorted_values[c] - sorted_values[f]) * (k - f)


def main():
    if len(sys.argv) != 2:
        print("usage: signal_lifetime_report.py <path-to-behaviour.ndjson>", file=sys.stderr)
        sys.exit(1)

    with open(sys.argv[1], "r", encoding="utf-8") as f:
        text = f.read()

    # emission-cycle-tracker-space episodes (exact lifetimes, exact death reason)
    episodes = defaultdict(list)  # signalType -> list[(emitCycle, deathCycle, deathReason)]

    # snapshot timeline (file order): cycleId -> set of present signal type names
    snapshot_by_cycle = {}
    snapshot_order = []  # cycleIds in the order their snapshot appeared

    # cycleId -> set of roles selected (agent_selected only — fitness-pass + meta both land here)
    selected_by_cycle = defaultdict(set)

    for obj in iter_json_objects(text):
        event = obj.get("event")
        payload = obj.get("payload") or {}
        cycle_id = obj.get("cycleId")

        if event == "signal_lifetime_observed":
            episodes[payload.get("signalType")].append((
                payload.get("emitCycle"), payload.get("deathCycle"), payload.get("deathReason")
            ))
        elif event == "signal_snapshot":
            present = set((payload.get("signals") or {}).keys())
            snapshot_by_cycle[cycle_id] = present
            snapshot_order.append(cycle_id)
        elif event == "agent_selected":
            role = payload.get("role")
            if role:
                selected_by_cycle[cycle_id].add(role)

    # ---- LIFETIME TABLE ----
    print("=" * 78)
    print("SIGNAL LIFETIME (from signal_lifetime_observed — completed episodes only)")
    print("=" * 78)
    if not episodes:
        print("(no signal_lifetime_observed events found — is the tracker wired in?)")
    else:
        # T4-BZ-3: request-family lifecycles report "consumed" (addressed mechanism acted) and
        # "expired" (validity horizon passed / verified early resolution) alongside the stored
        # signals' decayed/cleared pair.
        header = (f"{'signalType':30} {'n':>4} {'p50':>6} {'p95':>6} "
                  f"{'decayed':>8} {'cleared':>8} {'consumed':>9} {'expired':>8}")
        print(header)
        print("-" * len(header))
        for signal_type in sorted(episodes.keys()):
            deaths = episodes[signal_type]
            lifetimes = sorted(d[1] - d[0] for d in deaths)
            decayed = sum(1 for d in deaths if d[2] == "decayed")
            cleared = sum(1 for d in deaths if d[2] == "cleared")
            consumed = sum(1 for d in deaths if d[2] == "consumed")
            expired = sum(1 for d in deaths if d[2] == "expired")
            p50 = percentile(lifetimes, 50)
            p95 = percentile(lifetimes, 95)
            print(f"{signal_type:30} {len(deaths):>4} {p50:>6.1f} {p95:>6.1f} "
                  f"{decayed:>8} {cleared:>8} {consumed:>9} {expired:>8}")

    # "still active" — present in the LAST snapshot, informational only (no invented lifetime;
    # snapshot-index space is not the same unit as the tracker's cycle counter, see the module
    # docstring / design note — deliberately not merged into the table above).
    if snapshot_order:
        last_cycle = snapshot_order[-1]
        still_active = sorted(snapshot_by_cycle[last_cycle])
        print()
        print(f"Still present at the final snapshot (cycle {last_cycle}, {len(snapshot_order)} total snapshots):")
        print(f"  {', '.join(still_active) if still_active else '(none)'}")

    signal_types_ever_seen = set()
    for present in snapshot_by_cycle.values():
        signal_types_ever_seen |= present
    never_completed = sorted(signal_types_ever_seen - set(episodes.keys()))
    if never_completed:
        print(f"Seen in snapshots but never completed an episode this run: {', '.join(never_completed)}")

    # ---- CORRELATION TABLE ----
    print()
    print("=" * 78)
    print("SIGNAL -> ACTION CORRELATION (P(role selected | signal present) - P(... | absent))")
    print("=" * 78)

    universe = list(snapshot_by_cycle.keys())
    if not universe:
        print("(no signal_snapshot events found — is the tracker wired in?)")
        return

    roles = sorted({r for roles_ in selected_by_cycle.values() for r in roles_})
    rows = []
    for signal_type in sorted(signal_types_ever_seen):
        with_cycles = [c for c in universe if signal_type in snapshot_by_cycle[c]]
        without_cycles = [c for c in universe if signal_type not in snapshot_by_cycle[c]]
        if not with_cycles or not without_cycles:
            continue  # always present or never present this run — nothing to contrast
        for role in roles:
            k_with = sum(1 for c in with_cycles if role in selected_by_cycle.get(c, ()))
            k_without = sum(1 for c in without_cycles if role in selected_by_cycle.get(c, ()))
            p_with = k_with / len(with_cycles)
            p_without = k_without / len(without_cycles)
            delta = p_with - p_without
            rows.append((signal_type, role, len(with_cycles), p_with, len(without_cycles), p_without, delta))

    rows.sort(key=lambda r: -abs(r[6]))
    if not rows:
        print("(no signal had both present and absent cycles this run — need a longer run)")
    else:
        header = f"{'signalType':24} {'role':11} {'n+':>4} {'P(role|+)':>10} {'n-':>5} {'P(role|-)':>10} {'delta':>7}"
        print(header)
        print("-" * len(header))
        for signal_type, role, n_with, p_with, n_without, p_without, delta in rows:
            print(f"{signal_type:24} {role:11} {n_with:>4} {p_with:>10.2f} {n_without:>5} {p_without:>10.2f} {delta:>+7.2f}")


if __name__ == "__main__":
    main()
