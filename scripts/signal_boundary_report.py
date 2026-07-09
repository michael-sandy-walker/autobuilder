#!/usr/bin/env python3
"""
signal_boundary_report.py — T4-J Phase 0: retrospective signal-system mining.

Reads behaviour traces (pretty-printed CONCATENATED JSON objects — not one-per-line,
despite the .ndjson extension; see project_log_and_backup_conventions.md) and produces
the audit tables for the T4-J signal-system audit (project_t4j_design_note.md, Q1-Q7):

  1. CENSUS        — per signal type: lifetime deaths (decayed vs cleared), snapshot
                     presence, classification (DECAY_DRIVEN / CLEAR_DOMINANT / IMMORTAL /
                     NEVER_EMITTED / MIXED / THIN).
  2. MARGINS       — top-1 vs top-2 fitness-margin distribution, close-call role pairs,
                     sub-jitter fraction (margin < explorationJitter → coin-flip today).
  3. ABLATION      — per signal: in how many recorded rankings would removing JUST this
                     signal flip the top role? Snapshots record strength but not age, so a
                     signal's contribution is bounded [2s, 2s+1] (W_SIGNAL=2, recency<=1);
                     results are reported as definite-flip / indeterminate / no-flip.
                     Requires cycles where signal_snapshot AND agent_fitness_ranked join.
  4. SATURATION    — fraction of snapshot entries pinned at MAX_STRENGTH=3.0 per type
                     (a pinned signal's strength differences carry no information — Q2).
  5. CO-OCCURRENCE — Jaccard similarity of presence sets, top pairs (Q4 redundancy leads).
  6. NATURAL EXPERIMENTS — cycles with the IDENTICAL set of present signal types whose
                     top-ranked role differs (how far layer-0 evidence alone can carry).

Role->supported-signal sets are parsed from BasicSignals.java at runtime (not hardcoded
here) so the report tracks the code. Fitness weights mirrored from
AdaptiveExecutionEngine: W_SIGNAL=2.0, W_RECENCY=1.0, jitter=0.01.

Usage:
  python3 scripts/signal_boundary_report.py <behaviour.ndjson> [more.ndjson ...]

De-duplication is the CALLER's job: the /tmp/t4bo_aggregate/*_all.ndjson files are
concatenations of the individual /tmp/preflight_runs corpora — pass one or the other,
never both.
"""

import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

W_SIGNAL = 2.0
JITTER = 0.01
MAX_STRENGTH = 3.0

BASIC_SIGNALS = Path(__file__).resolve().parent.parent / \
    "src/main/java/com/walpro/autobuilder/core/lifecycle/BasicSignals.java"


def parse_role_sets(source_path):
    """Extract <ROLE>_SIGNALS = Set.of(...) memberships from BasicSignals.java."""
    text = source_path.read_text()
    sets = {}
    for m in re.finditer(
            r"Set<SignalType>\s+(\w+_SIGNALS)\s*=\s*Set\.of\(([^;]*?)\);",
            text, re.S):
        name, body = m.group(1), m.group(2)
        role = name.replace("_SIGNALS", "").lower()
        members = set(re.findall(r"\b([A-Z][A-Z_]+)\b", body))
        sets[role] = members
    return sets


def parse_all_types(source_path):
    return set(re.findall(r'SignalType\.of\("([A-Z_]+)"\)', source_path.read_text()))


def iter_events(path):
    dec = json.JSONDecoder()
    buf = Path(path).read_text()
    idx, n = 0, len(buf)
    while idx < n:
        while idx < n and buf[idx] in " \t\n\r":
            idx += 1
        if idx >= n:
            break
        obj, end = dec.raw_decode(buf, idx)
        yield obj
        idx = end


def classify(deaths_decayed, deaths_cleared, presence, snapshots):
    total = deaths_decayed + deaths_cleared
    if presence == 0 and total == 0:
        return "NEVER_EMITTED"
    if total == 0:
        return "IMMORTAL" if snapshots and presence / snapshots > 0.5 else "THIN"
    if total < 5:
        return "THIN"
    frac = deaths_decayed / total
    if frac >= 0.5:
        return "DECAY_DRIVEN"
    if frac <= 0.10:
        return "CLEAR_DOMINANT"
    return "MIXED"


def main(paths):
    role_sets = parse_role_sets(BASIC_SIGNALS)
    all_types = parse_all_types(BASIC_SIGNALS)

    deaths = defaultdict(Counter)          # type -> {decayed, cleared}
    presence = Counter()                   # type -> snapshot appearances
    pinned = Counter()                     # type -> appearances at MAX_STRENGTH
    snapshots = 0
    cooccur = Counter()                    # frozenset pair -> count
    margins = []                           # (margin, top_role, second_role)
    ablation = defaultdict(Counter)        # type -> {flip, indeterminate, noflip}
    state_top = defaultdict(Counter)       # frozenset(types) -> Counter(top role)

    # per-cycle join buffers
    cyc_snapshot = {}
    cyc_ranking = {}

    for path in paths:
        for obj in iter_events(path):
            ev, cid = obj.get("event"), obj.get("cycleId")
            p = obj.get("payload", {})
            if ev == "signal_lifetime_observed":
                r = p.get("deathReason")
                if r in ("decayed", "cleared"):
                    deaths[p.get("signalType")][r] += 1
            elif ev == "signal_snapshot":
                snapshots += 1
                sig = p.get("signals", {})
                for k, v in sig.items():
                    presence[k] += 1
                    if v >= MAX_STRENGTH - 1e-9:
                        pinned[k] += 1
                keys = sorted(sig)
                for i in range(len(keys)):
                    for j in range(i + 1, len(keys)):
                        cooccur[(keys[i], keys[j])] += 1
                cyc_snapshot[cid] = sig
            elif ev == "agent_fitness_ranked":
                cyc_ranking[cid] = p.get("rankings", [])

    # margins + ablation + natural experiments over joined cycles
    for cid, rankings in cyc_ranking.items():
        if len(rankings) < 2:
            continue
        ranked = sorted(rankings, key=lambda r: -r["score"])
        top, second = ranked[0], ranked[1]
        margin = top["score"] - second["score"]
        margins.append((margin, top["role"], second["role"]))

        sig = cyc_snapshot.get(cid)
        if sig is None:
            continue
        state_top[frozenset(sig)][top["role"]] += 1
        top_set = role_sets.get(top["role"], set())
        sec_set = role_sets.get(second["role"], set())
        for name, strength in sig.items():
            in_top, in_sec = name in top_set, name in sec_set
            if in_top == in_sec:      # supports both or neither: removal cancels out
                continue
            lo, hi = W_SIGNAL * strength, W_SIGNAL * strength + 1.0
            if in_top:                # removing it weakens the top role
                if margin < lo:
                    ablation[name]["flip"] += 1
                elif margin > hi:
                    ablation[name]["noflip"] += 1
                else:
                    ablation[name]["indeterminate"] += 1
            else:                     # supports second only: removal widens the gap
                ablation[name]["noflip"] += 1

    # ---- report ----
    print(f"corpora: {len(paths)} file(s), snapshots={snapshots}, "
          f"rankings={len(cyc_ranking)}, joined={sum(1 for c in cyc_ranking if c in cyc_snapshot)}")

    print("\n== 1. CENSUS ==")
    print(f"{'type':32s} {'decayed':>7s} {'cleared':>7s} {'present':>8s} {'class':>14s}")
    for t in sorted(all_types):
        d, c = deaths[t]["decayed"], deaths[t]["cleared"]
        cls = classify(d, c, presence[t], snapshots)
        print(f"{t:32s} {d:7d} {c:7d} {presence[t]:8d} {cls:>14s}")

    print("\n== 2. MARGINS ==")
    ms = sorted(m for m, _, _ in margins)
    n = len(ms)
    if n:
        for q in (10, 25, 50, 75, 90):
            print(f"  p{q}: {ms[n * q // 100]:.3f}")
        sub = sum(1 for m in ms if m < JITTER)
        close = sum(1 for m in ms if m < 0.25)
        print(f"  sub-jitter (<{JITTER}): {sub}/{n} = {sub / n * 100:.1f}%")
        print(f"  close (<0.25): {close}/{n} = {close / n * 100:.1f}%")
        pairs = Counter(tuple(sorted((a, b))) for m, a, b in margins if m < 0.25)
        print(f"  close pairs: {dict(pairs.most_common(6))}")

    print("\n== 3. ABLATION (single-signal removal vs top-2 flip) ==")
    print(f"{'type':32s} {'flips':>6s} {'indet':>6s} {'noflip':>7s}")
    for t, c in sorted(ablation.items(), key=lambda kv: -kv[1]["flip"]):
        print(f"{t:32s} {c['flip']:6d} {c['indeterminate']:6d} {c['noflip']:7d}")

    print("\n== 4. SATURATION (share of presence pinned at 3.0) ==")
    for t in sorted(presence, key=lambda t: -(pinned[t] / presence[t])):
        print(f"{t:32s} {pinned[t]:5d}/{presence[t]:5d} = {pinned[t] / presence[t] * 100:5.1f}%")

    print("\n== 5. CO-OCCURRENCE (top Jaccard pairs) ==")
    jacc = []
    for (a, b), both in cooccur.items():
        union = presence[a] + presence[b] - both
        if union >= 30:
            jacc.append((both / union, a, b, both))
    for j, a, b, both in sorted(jacc, reverse=True)[:12]:
        print(f"  {j:.2f}  {a} + {b}  (together {both}x)")

    print("\n== 6. NATURAL EXPERIMENTS (identical present-type set, different top role) ==")
    multi = {k: v for k, v in state_top.items() if sum(v.values()) > 1}
    varied = {k: v for k, v in multi.items() if len(v) > 1}
    print(f"  states seen >1x: {len(multi)}; of those with DIFFERENT top roles: {len(varied)}")
    for k, v in sorted(varied.items(), key=lambda kv: -sum(kv[1].values()))[:8]:
        print(f"    {dict(v)}  <- {sorted(k)}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(sys.argv[1:])
