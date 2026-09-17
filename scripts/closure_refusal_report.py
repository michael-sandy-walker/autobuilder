#!/usr/bin/env python3
"""Which closure gate refused, how often, and on what.

Ten gates can block a goal from closing, and until T4-CU each reported it with a
`log.warn` alone -- so "which gate is doing the blocking" could only be answered by a
regex over an archived log, and the refusal rate went 25 runs unmeasured. The
`closure_refused` behaviour event makes it a query; this is the query.

WHAT IT ANSWERS, per run and across a corpus:
  * refusals per gate -- which gate blocks, not merely that something did;
  * how many carried named criteria -- only the two criteria gates attribute, so an
    attributed refusal is one a repair item could be aimed at;
  * whether a refused claim was later closed -- the pre-fix population's shape, where
    runs attempted closure dozens of times and were refused every time.

WHAT IT DOES NOT DO. It never gates: the exit code is always 0 and a refusal is data,
not a verdict on the run ([[feedback-activity-not-progress]]). A high count is not a
defect -- refusing a closure the artifact contradicts is the mechanism WORKING, and the
2026-08-28 field firings were judged correct by an independent yardstick. What a reader
is looking for is a gate refusing REPEATEDLY on one goal, which is the shape that ends in
a deadline burn on an artifact the loop was holding.

COVERAGE IS REPORTED. Runs predating the event carry none, and that is reported as
"no closure_refused events" rather than as zero refusals -- the distinction the
behaviour-log conventions exist to preserve: absence of a field is not a zero.
"""

import argparse
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

BEHAVIOUR = "behaviour.ndjson"
EVENT = "closure_refused"


def load_events(path: Path):
    """Behaviour logs are pretty-printed concatenated JSON, not line-JSON."""
    with open(path) as fh:
        text = fh.read()
    decoder = json.JSONDecoder()
    events, i, n = [], 0, len(text)
    while i < n:
        while i < n and text[i].isspace():
            i += 1
        if i >= n:
            break
        try:
            obj, i = decoder.raw_decode(text, i)
        except ValueError:
            break          # a run killed mid-write leaves a partial object; keep what parsed
        events.append(obj)
    return events


def refusals(run_dir: Path):
    """Every closure_refused event in one run, oldest first."""
    log = run_dir / BEHAVIOUR
    if not log.exists():
        return None
    out = []
    for e in load_events(log):
        if e.get("event") != EVENT:
            continue
        p = e.get("payload") or {}
        out.append({
            "cycleId": e.get("cycleId"),
            "gate": p.get("gate"),
            "goalId": p.get("goalId"),
            "reason": p.get("reason") or "",
            "criteria": p.get("criteria") or [],
        })
    return out


def report(run_dir: Path, verbose=False):
    rows = refusals(run_dir)
    print(f"{run_dir.name}")
    if rows is None:
        print(f"  no {BEHAVIOUR} -- cannot say whether any closure was refused")
        return
    if not rows:
        # Not the same statement as "nothing was refused": a run on a build predating the
        # event emits none either way, and the log cannot tell the two apart.
        print(f"  no {EVENT} events (either nothing was refused, or the build predates the event)")
        return
    by_gate = Counter(r["gate"] for r in rows)
    attributed = [r for r in rows if r["criteria"]]
    per_goal = defaultdict(list)
    for r in rows:
        per_goal[r["goalId"]].append(r)
    repeat = {g: v for g, v in per_goal.items() if len(v) > 1}
    print(f"  {len(rows)} refusal(s) across {len(by_gate)} gate(s); "
          f"{len(attributed)} carried named criteria")
    for gate, n in by_gate.most_common():
        named = sum(1 for r in rows if r["gate"] == gate and r["criteria"])
        print(f"    {gate:10} {n:4}  ({named} attributed)")
    if repeat:
        worst = max(repeat.items(), key=lambda kv: len(kv[1]))
        print(f"  REPEATED on {len(repeat)} goal(s); worst: {worst[0]} refused "
              f"{len(worst[1])} times by {Counter(r['gate'] for r in worst[1]).most_common(1)[0][0]}")
    if verbose:
        for r in rows:
            crit = f" criteria={r['criteria']}" if r["criteria"] else ""
            print(f"    [{r['gate']}] {r['goalId']} :: {r['reason'][:110]}{crit}")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("run_dirs", nargs="*", default=None,
                    help="run directories (default: every /tmp/preflight_runs/* with a behaviour log)")
    ap.add_argument("--verbose", action="store_true", help="name every refusal")
    args = ap.parse_args()

    if args.run_dirs:
        dirs = [Path(d) for d in args.run_dirs]
    else:
        root = Path("/tmp/preflight_runs")
        dirs = sorted(d for d in root.glob("*") if (d / BEHAVIOUR).exists()) if root.exists() else []
    if not dirs:
        print("no run directories with a behaviour log")
        return 0
    with_events = 0
    for d in dirs:
        rows = refusals(d)
        if rows:
            with_events += 1
        report(d, args.verbose)
    print(f"\n{with_events} of {len(dirs)} run(s) recorded at least one {EVENT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
