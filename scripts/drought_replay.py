#!/usr/bin/env python3
"""Would a candidate convergence brake have halted a run, and would that have cost anything?

The root-convergence brake halts on a fixed count of consecutive cycles without a
completed-with-merge subgoal. `T2-FH` proposes replacing that constant with a window
derived from the run's own history. Nothing could test such a proposal before it was
built, so the ticket's own gate -- "do not build before that replay" -- had no instrument
behind it. This is that instrument.

WHAT IT REPLAYS. Per run, the merge-or-not sequence is read from `cycle_end` payloads in
the archived `behaviour.ndjson`, and a candidate rule is asked, at every cycle and seeing
only the cycles before it, whether it would halt there. No rule may consult a later cycle;
a rule that does is not a brake, it is a postmortem.

HOW A HALT IS SCORED, stated before any rule was run. The ledger records each run's
`peaked_at_cycle` -- the cycle whose artifact scored best on the external smoke yardstick.
Against that:

    harmful   the rule halts BEFORE the peak, so the run's best artifact is never reached
    useful    the rule halts AFTER the peak, and the cycles between are drift the run
              spent getting worse
    silent    the rule never fires

Harm and benefit are NOT commensurable and are never summed. One harmful halt kills a
run's best work; a useful halt saves wall-clock on a run that had already finished
improving. A rule is reported as passing only if it is harmless on the whole corpus, and
among harmless rules the one saving the most drift cycles wins.

WHY PEAK POSITION AND NOT THE FINAL SCORE. A run that drifts still ships its preserved
peak (`T3-BY`), so drift costs time rather than quality. The brake's job is therefore to
stop spending budget after the run has stopped improving -- not to protect the artifact,
which is already protected.

COVERAGE IS REPORTED, NEVER ASSUMED. Runs whose per-cycle logs died with /tmp, and runs
absent from the ledger or carrying no peak, are counted and named rather than silently
dropped -- the corpus is sparse and shrinking, and a rule that looks clean on nine runs
should say it was nine.
"""
import argparse
import glob
import json
import os
import re
import sys
from collections import Counter

LEDGER = "scripts/run_outcomes.json"
RUNS_GLOB = "/tmp/preflight_runs"


def load_events(path):
    """Behaviour logs are pretty-printed concatenated JSON, not line-JSON."""
    with open(path) as fh:
        text = fh.read()
    decoder = json.JSONDecoder()
    events = []
    i, n = 0, len(text)
    while i < n:
        while i < n and text[i].isspace():
            i += 1
        if i >= n:
            break
        obj, i = decoder.raw_decode(text, i)
        events.append(obj)
    return events


def sequences(run_dir):
    """Two bools per completed cycle, in cycle order.

    `merged`   the cycle landed a mutation -- what `T2-FH`'s proposal measures.
    `progress` a subgoal reached COMPLETED in that cycle -- the proxy for
               `countCompletedSubgoalsWithMerges`, which is what the PRODUCTION brake
               counts. Replaying a candidate against merges while calling the fixed-20
               baseline a merge rule would compare the proposal to a brake that does not
               exist, so both sequences are built and each rule declares which it reads.
    """
    files = glob.glob(os.path.join(run_dir, "*behaviour*.ndjson"))
    if not files:
        return None, None
    events = load_events(files[0])
    order, completed = [], set()
    for e in events:
        cid = e.get("cycleId")
        if cid is None:
            continue
        if e.get("event") == "cycle_end":
            order.append((cid, bool(e.get("payload", {}).get("merged"))))
        elif e.get("event") == "goal_status_changed" \
                and e.get("payload", {}).get("to") == "COMPLETED":
            completed.add(cid)
    if not order:
        return None, None
    return [m for _, m in order], [cid in completed for cid, _ in order]


def peak_cycle(entry):
    """`peaked_at_cycle` reads e.g. 'cycle-178... (7/23)' -- the position, not the id."""
    m = re.search(r"\((\d+)/(\d+)\)", str(entry.get("peaked_at_cycle") or ""))
    return (int(m.group(1)), int(m.group(2))) if m else None


# ---------------------------------------------------------------- candidate rules

def rule_fixed(seq, window):
    """Today's brake: halt after `window` consecutive merge-free cycles."""
    drought = 0
    for n, merged in enumerate(seq, 1):
        drought = 0 if merged else drought + 1
        if drought >= window:
            return n
    return None


def rule_own_record(seq, margin):
    """T2-FH's proposal: halt when the current drought exceeds the longest drought this
    run has ALREADY recovered from, plus a margin. The record starts at zero, which is
    the property the ticket assumed was a safe default."""
    record = current = 0
    for n, merged in enumerate(seq, 1):
        if merged:
            record = max(record, current)
            current = 0
        else:
            current += 1
            if current > record + margin:
                return n
    return None


# Each rule declares the sequence it reads: "progress" (what production counts) or
# "merged" (what T2-FH's proposal counts). Mixing them silently was the first error this
# harness made and is the one it now cannot repeat.
RULES = [
    ("fixed-20 on progress (production)", "progress", lambda s: rule_fixed(s, 20)),
    ("fixed-10 on progress", "progress", lambda s: rule_fixed(s, 10)),
    ("own-record m=0 on merges", "merged", lambda s: rule_own_record(s, 0)),
    ("own-record m=1 on merges", "merged", lambda s: rule_own_record(s, 1)),
    ("own-record m=2 on merges", "merged", lambda s: rule_own_record(s, 2)),
    ("own-record m=3 on merges", "merged", lambda s: rule_own_record(s, 3)),
    ("own-record m=5 on merges", "merged", lambda s: rule_own_record(s, 5)),
    ("own-record m=2 on progress", "progress", lambda s: rule_own_record(s, 2)),
    ("own-record m=5 on progress", "progress", lambda s: rule_own_record(s, 5)),
]


def collect(ledger_path=LEDGER, runs_root=RUNS_GLOB):
    ledger = json.load(open(ledger_path))["runs"]
    corpus, skipped = [], Counter()
    for run, entry in sorted(ledger.items()):
        run_dir = os.path.join(runs_root, run)
        if not os.path.isdir(run_dir):
            skipped["no run directory (died with /tmp)"] += 1
            continue
        merged, progress = sequences(run_dir)
        if not merged:
            skipped["no behaviour log"] += 1
            continue
        pk = peak_cycle(entry)
        if not pk:
            skipped["ledger row carries no peak"] += 1
            continue
        corpus.append(dict(run=run, merged=merged, progress=progress,
                           peak=pk[0], cycles=pk[1],
                           terminal=str(entry.get("terminal", "?")).replace("honest-halt:", ""),
                           smoke=entry.get("smoke_score") or entry.get("smoke")))
    return corpus, skipped


def evaluate(corpus, rule, signal):
    harmful, useful, silent, saved = [], [], 0, 0
    for r in corpus:
        at = rule(r[signal])
        if at is None:
            silent += 1
        elif at < r["peak"]:
            harmful.append((r["run"], at, r["peak"], r["smoke"]))
        else:
            useful.append((r["run"], at, r["peak"]))
            saved += len(r[signal]) - at
    return harmful, useful, silent, saved


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ledger", default=LEDGER)
    ap.add_argument("--runs", default=RUNS_GLOB)
    ap.add_argument("--verbose", action="store_true", help="name every harmful halt")
    args = ap.parse_args()

    corpus, skipped = collect(args.ledger, args.runs)
    total = sum(skipped.values()) + len(corpus)
    print(f"CORPUS: {len(corpus)} replayable runs of {total} in the ledger")
    for reason, n in skipped.most_common():
        print(f"  skipped {n:3}  {reason}")
    drift = [r for r in corpus if r["peak"] / r["cycles"] < 0.75]
    print(f"  of the replayable: {len(drift)} drifted (peak <75% of run), "
          f"{len(corpus) - len(drift)} peaked at or near the end")

    print("\nNOTE: these rules are replayed BARE. The production brake consults four rescue")
    print("gates first (verify-before-pause, injection re-check, packaging rescue, trend")
    print("extension), so the production column understates how safe today's brake is and")
    print("any replacement would inherit the same rescues. The comparison between rules on")
    print("this harness is like-for-like; the absolute harm counts are an upper bound.")
    print(f"\n{'rule':36} {'harmful':>8} {'useful':>7} {'silent':>7} {'saved':>7}   verdict")
    for name, signal, rule in RULES:
        harmful, useful, silent, saved = evaluate(corpus, rule, signal)
        verdict = "PASSES (harmless)" if not harmful else f"REGRESSION on {len(harmful)} run(s)"
        print(f"{name:36} {len(harmful):8} {len(useful):7} {silent:7} {saved:7}   {verdict}")
        if harmful and args.verbose:
            for run, at, pk, smoke in harmful[:6]:
                print(f"{'':38}halts {run} at cycle {at}, peak is {pk} ({smoke})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
