#!/usr/bin/env python3
"""T4-CI phase 1: organic run-health baseline — per-corpus feature vectors + separation test.

"Healthy" is derived from the run population, not a model prior: extract the same mechanical
feature vector from every preflight corpus, label each run by its RECORDED outcome, and test
whether the known-defect runs separate from the healthy population. Stdlib only.

Usage:
  run_profile.py --corpus <dir>                 # print one corpus's feature vector
  run_profile.py --baseline <runs-root> [--labels <json>]
                                                # profile every corpus under runs-root, build
                                                # the labeled baseline, run the separation test
  run_profile.py --report <corpus> --baseline <runs-root> --labels <json>
                                                # phase 2: one corpus's health table vs the
                                                # healthy envelope (preflight wiring; never
                                                # gates — informational context, not an alarm)

Labels JSON: {"<corpus-basename>": "healthy" | "defect" | "environment", ...}
Unlabeled corpora join the population as "unlabeled" (they widen the percentile context but
never count as healthy for the envelope).
"""

import argparse
import datetime as dt
import glob
import json
import os
import re
import sys
from collections import Counter

_HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_LABELS = os.path.join(_HERE, "run_labels.json")
DEFAULT_FROZEN = os.path.join(_HERE, "run_health_baseline.json")
# Where a labeled run's behaviour log survives after its /tmp corpus is gone.
DEFAULT_LOG_GLOBS = (os.path.join(os.path.dirname(_HERE), "backup", "*", "logs", "behaviour-*.ndjson"),
                     os.path.join(os.path.dirname(_HERE), "firestarter", "logs", "behaviour-*.ndjson"))

# ---------------------------------------------------------------- extraction

def parse_behaviour(path):
    try:
        with open(path, errors="replace") as f:
            s = f.read()
    except OSError:
        return []
    events, dec, i = [], json.JSONDecoder(), 0
    while i < len(s):
        while i < len(s) and s[i] in " \r\n\t":
            i += 1
        if i >= len(s):
            break
        try:
            obj, j = dec.raw_decode(s, i)
            events.append(obj)
            i = j
        except ValueError:
            i = s.find("\n", i)
            if i < 0:
                break
            i += 1
    return events


def behaviour_file(corpus):
    p = os.path.join(corpus, "behaviour.ndjson")
    if os.path.exists(p):
        return p
    pl = os.path.join(corpus, "partial_logs")
    if os.path.isdir(pl):
        for f in sorted(os.listdir(pl)):
            if f.startswith("behaviour"):
                return os.path.join(pl, f)
    return p


def profile(corpus):
    """The feature vector for a corpus directory."""
    return profile_events(parse_behaviour(behaviour_file(corpus)))


def profile_events(events):
    """The feature vector. Every feature is a per-cycle rate or a share so runs of different
    lengths compare honestly ([[feedback-relative-thresholds]]).

    Split out from `profile` so a frozen baseline can be rebuilt from a bare behaviour log when the
    corpus directory is gone (see `freeze_baseline`)."""
    if not events:
        return None
    hist = Counter(e.get("event", "?") for e in events)
    cycles = max(1, hist.get("cycle_start", 0))

    # per-cycle rates
    f = {
        "cycles": cycles,
        "merge_rate": hist.get("mutation_merged", 0) / cycles,
        "reject_rate": hist.get("mutation_rejected", 0) / cycles,
        "inject_rate": hist.get("mutation_injected", 0) / cycles,
        "build_fix_rate": hist.get("build_fix_attempt", 0) / cycles,
        "skip_rate": hist.get("agent_skipped", 0) / cycles,
        "proposal_rate": hist.get("mutation_proposed", 0) / cycles,
    }

    # planner-only cycles: cycle windows whose selected agents include planner but neither
    # evolution nor merge produced anything (the resume-starvation shape: 21/21 such cycles)
    per_cycle_roles = {}
    per_cycle_merged = {}
    for e in events:
        cid = e.get("cycleId")
        if cid is None:
            continue
        ev = e.get("event")
        p = e.get("payload") or {}
        if ev == "agent_selected":
            per_cycle_roles.setdefault(cid, set()).add(p.get("role"))
        elif ev == "mutation_proposed":
            per_cycle_roles.setdefault(cid, set()).add("_proposed")
        elif ev == "mutation_merged":
            per_cycle_merged[cid] = True
    cycle_ids = list(per_cycle_roles.keys())
    planner_only = sum(
        1 for cid in cycle_ids
        if "planner" in per_cycle_roles[cid]
        and "_proposed" not in per_cycle_roles[cid]
        and not per_cycle_merged.get(cid))
    f["planner_only_share"] = planner_only / max(1, len(cycle_ids))

    # same-token injection re-fires: how often the SAME (rule, token) injection repeats —
    # the 39-minute skeleton-repository grind's signature
    inj = Counter()
    for e in events:
        if e.get("event") == "mutation_injected":
            p = e.get("payload") or {}
            inj[(p.get("rule"), p.get("missingToken"))] += 1
    f["max_inject_refires"] = max(inj.values()) if inj else 0
    f["inject_refire_share"] = (
        sum(v - 1 for v in inj.values()) / max(1, sum(inj.values())) if inj else 0.0)

    # merge drought: longest gap between merges as a share of the run (index-based over the
    # event stream — proportional, not wall-clock, so paused tails don't distort)
    merge_idx = [i for i, e in enumerate(events) if e.get("event") == "mutation_merged"]
    if merge_idx:
        gaps = []
        prev = 0
        for i in merge_idx + [len(events) - 1]:
            gaps.append(i - prev)
            prev = i
        f["merge_drought_share"] = max(gaps) / max(1, len(events))
    else:
        f["merge_drought_share"] = 1.0

    # churn tail: share of the stream after the LAST merge (the broken-tail anatomy)
    f["tail_share"] = (
        (len(events) - 1 - merge_idx[-1]) / max(1, len(events)) if merge_idx else 1.0)

    return f


FEATURES = ["merge_rate", "reject_rate", "inject_rate", "build_fix_rate", "skip_rate",
            "proposal_rate", "planner_only_share", "max_inject_refires",
            "inject_refire_share", "merge_drought_share", "tail_share"]

# Directional reading of "worse": for every feature here, HIGH = less healthy, except
# merge_rate/proposal_rate where LOW is the unhealthy direction.
LOW_IS_BAD = {"merge_rate", "proposal_rate"}


def percentile(sorted_vals, p):
    if not sorted_vals:
        return None
    k = (len(sorted_vals) - 1) * p
    lo, hi = int(k), min(int(k) + 1, len(sorted_vals) - 1)
    return sorted_vals[lo] + (sorted_vals[hi] - sorted_vals[lo]) * (k - lo)


# ---------------------------------------------------------------- baseline

def build_baseline(root, labels):
    rows = {}
    for name in sorted(os.listdir(root)):
        corpus = os.path.join(root, name)
        if not os.path.isdir(corpus):
            continue
        f = profile(corpus)
        if f is None or f["cycles"] < 3:
            continue  # fragments carry no shape
        rows[name] = f
    healthy = {n: f for n, f in rows.items() if labels.get(n) == "healthy"}
    return rows, healthy


def separation_report(rows, healthy, labels):
    print(f"# run-health baseline: {len(rows)} profiled corpora, "
          f"{len(healthy)} labeled healthy, "
          f"{sum(1 for v in labels.values() if v == 'defect')} labeled defect")
    if len(healthy) < 5:
        print("TOO FEW healthy-labeled corpora for an envelope — label more first.")
        return

    env = {}
    for feat in FEATURES:
        vals = sorted(f[feat] for f in healthy.values())
        env[feat] = {
            "p10": percentile(vals, 0.10),
            "p50": percentile(vals, 0.50),
            "p90": percentile(vals, 0.90),
        }

    print("\n## healthy envelope (p10 / median / p90)")
    for feat in FEATURES:
        e = env[feat]
        print(f"  {feat:22s} {e['p10']:8.3f} {e['p50']:8.3f} {e['p90']:8.3f}")

    print("\n## per-corpus anomalies vs the healthy envelope "
          "(direction-aware: only the unhealthy side counts)")
    defect_hits = {}
    for name, f in sorted(rows.items()):
        label = labels.get(name, "unlabeled")
        anomalies = []
        for feat in FEATURES:
            v, e = f[feat], env[feat]
            if feat in LOW_IS_BAD:
                if v < e["p10"]:
                    anomalies.append(f"{feat}={v:.3f}<p10({e['p10']:.3f})")
            else:
                if v > e["p90"]:
                    anomalies.append(f"{feat}={v:.3f}>p90({e['p90']:.3f})")
        marker = {"defect": "DEFECT ", "healthy": "healthy", "environment": "env    "}.get(
            label, "       ")
        if anomalies or label == "defect":
            print(f"  [{marker}] {name}: {'; '.join(anomalies) if anomalies else '(no anomaly!)'}")
        if label == "defect":
            defect_hits[name] = anomalies

    print("\n## separation result")
    flagged = sum(1 for a in defect_hits.values() if a)
    print(f"  defect corpora outliers on >=1 unhealthy-direction feature: "
          f"{flagged}/{len(defect_hits)}")
    fp = sum(1 for n, f in rows.items() if labels.get(n) == "healthy" and any(
        (f[feat] < env[feat]["p10"] if feat in LOW_IS_BAD else f[feat] > env[feat]["p90"])
        for feat in FEATURES))
    print(f"  healthy corpora flagged (expected ~2/10 per feature by construction, fewer overall): {fp}/{len(healthy)}")


STAMP = re.compile(r"(\d{4}-\d{2}-\d{2})_(\d{2})-(\d{2})-(\d{2})")
# A corpus dir is created seconds before the JVM opens its log, so a labeled corpus and its
# behaviour file share a timestamp only to the minute. Observed offsets: 12-24s.
STAMP_TOLERANCE_S = 120


def stamp_seconds(name):
    """(date, seconds-into-the-day) from a corpus or log name, or None."""
    m = STAMP.search(name or "")
    if not m:
        return None
    day, hh, mm, ss = m.groups()
    return day, int(hh) * 3600 + int(mm) * 60 + int(ss)


def match_behaviour_log(run_name, pool):
    """The behaviour log belonging to a labeled corpus, matched by timestamp within tolerance."""
    want = stamp_seconds(run_name)
    if not want:
        return None
    best = None
    for path in pool:
        got = stamp_seconds(os.path.basename(path))
        if not got or got[0] != want[0]:
            continue
        delta = abs(got[1] - want[1])
        if delta <= STAMP_TOLERANCE_S and (best is None or delta < best[0]):
            best = (delta, path)
    return best[1] if best else None


def freeze_baseline(labels, log_globs, out_path, root=None):
    """Persist the labeled runs' FEATURE VECTORS so the envelope outlives their corpora.

    The envelope was silently empty in the field from 2026-07-17 on: labels are committed but the
    corpora they name lived in `/tmp/preflight_runs`, which is cleared on reboot, so every field
    health report read "healthy baseline too small (0)". Eleven numbers per run are what the
    comparison actually needs, so those are what get stored.
    """
    pool = []
    for pattern in log_globs:
        pool.extend(glob.glob(pattern))
    frozen, missing = {}, []
    for name, label in sorted(labels.items()):
        features = None
        if root:
            corpus = os.path.join(root, name)
            if os.path.isdir(corpus):
                features = profile(corpus)
        if features is None:
            log = match_behaviour_log(name, pool)
            if log:
                events = parse_behaviour(log)
                if events:
                    features = profile_events(events)
        if features is None:
            missing.append(name)
            continue
        frozen[name] = {"label": label, "features": features}
    payload = {"generated": dt.datetime.now().strftime("%Y-%m-%d"),
               "note": "Feature vectors for scripts/run_labels.json runs. Regenerate with "
                       "run_profile.py --freeze. Durable on purpose: the corpora themselves live "
                       "in /tmp and do not survive a reboot.",
               "runs": frozen}
    with open(out_path, "w") as fh:
        json.dump(payload, fh, indent=1, sort_keys=True)
    healthy = sum(1 for v in frozen.values() if v["label"] == "healthy")
    print(f"# froze {len(frozen)} labeled runs ({healthy} healthy) -> {out_path}")
    if missing:
        print(f"# no data found for {len(missing)}: {', '.join(missing)}")
    return frozen


def load_frozen(path):
    """{run: features} for healthy runs in a frozen baseline, or {} when unusable."""
    try:
        with open(path) as fh:
            payload = json.load(fh)
    except (OSError, ValueError):
        return {}
    return {name: entry["features"]
            for name, entry in (payload.get("runs") or {}).items()
            if entry.get("label") == "healthy" and entry.get("features")}


def health_report(corpus, root, labels, frozen_path=None):
    """Phase 2: one corpus vs the healthy envelope. A context table, not an alarm —
    single-feature marginal flags are common on healthy runs (11 features x p90 envelope);
    what distinguishes the recorded defect shapes is MANY flags or the distinctive axes
    (planner_only_share, tail_share, merge_rate=0)."""
    f = profile(corpus)
    name = os.path.basename(os.path.normpath(corpus))
    print(f"# run health vs population — corpus={name}")
    if f is None or f["cycles"] < 3:
        print("too few cycles for a shape — no comparison")
        return
    healthy = {}
    source = "live corpora"
    if root and os.path.isdir(root):
        _, healthy = build_baseline(root, labels)
    healthy.pop(name, None)                      # never compare a run against itself
    if len(healthy) < 5 and frozen_path:
        # The labeled corpora live in /tmp and do not survive a reboot; the frozen vectors do.
        frozen = load_frozen(frozen_path)
        frozen.pop(name, None)
        if len(frozen) > len(healthy):
            healthy, source = frozen, f"frozen baseline {os.path.basename(frozen_path)}"
    if len(healthy) < 5:
        print(f"healthy baseline too small ({len(healthy)}) — no comparison")
        return
    print(f"# baseline source: {source}")
    flags = 0
    print(f"# healthy baseline n={len(healthy)} (labels: scripts/run_labels.json)")
    print(f"{'feature':24s} {'run':>8s} {'p10':>8s} {'p50':>8s} {'p90':>8s}  result")
    for feat in FEATURES:
        vals = sorted(h[feat] for h in healthy.values())
        p10, p50, p90 = (percentile(vals, p) for p in (0.10, 0.50, 0.90))
        v = f[feat]
        if feat in LOW_IS_BAD:
            bad = v < p10
        else:
            bad = v > p90
        if bad:
            flags += 1
        print(f"{feat:24s} {v:8.3f} {p10:8.3f} {p50:8.3f} {p90:8.3f}  "
              f"{'OUTSIDE-HEALTHY' if bad else 'within'}")
    print(f"# summary: {flags}/{len(FEATURES)} features outside the healthy envelope "
          f"(1-2 marginal flags are normal; the recorded defect shapes carried 6-7 or hit "
          f"planner_only/tail/merge_rate axes)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus")
    ap.add_argument("--baseline")
    ap.add_argument("--labels")
    ap.add_argument("--report")
    ap.add_argument("--frozen", default=DEFAULT_FROZEN,
                    help="frozen baseline JSON, used when the live corpora are gone")
    ap.add_argument("--freeze", nargs="?", const=DEFAULT_FROZEN,
                    help="write the labeled runs' feature vectors to this path and exit")
    ap.add_argument("--logs", action="append", default=None,
                    help="behaviour-log glob to reconstruct vectors from (repeatable)")
    args = ap.parse_args()

    labels = {}
    if args.labels:
        with open(args.labels) as fh:
            labels = json.load(fh)
    elif os.path.isfile(DEFAULT_LABELS):
        with open(DEFAULT_LABELS) as fh:
            labels = json.load(fh)

    if args.freeze:
        freeze_baseline(labels, args.logs or DEFAULT_LOG_GLOBS, args.freeze, args.baseline)
        return

    if args.report:
        if not args.baseline and not os.path.isfile(args.frozen or ""):
            print("--report needs --baseline or a frozen baseline", file=sys.stderr)
            sys.exit(0)  # never gates
        try:
            health_report(args.report, args.baseline, labels, args.frozen)
        except Exception as e:  # informational instrument — never fails a pipeline
            print(f"# health report failed: {e}")
        return
    if args.corpus:
        f = profile(args.corpus)
        print(json.dumps(f, indent=2))
        return
    if args.baseline:
        rows, healthy = build_baseline(args.baseline, labels)
        separation_report(rows, healthy, labels)
        return
    ap.print_help()
    sys.exit(1)


if __name__ == "__main__":
    main()
