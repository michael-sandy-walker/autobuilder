#!/usr/bin/env python3
"""Which runs converge, and what separates them from the ones that don't.

The run ledger records an OUTCOME per run (smoke) and a handful of process facts
(merges, cycles, duration, drift tail, repeat proposals). Nothing until now put the two
together, so "why does identical configuration produce 23/23 and a broken tree?" had no
instrument behind it -- only per-run anecdote.

This report computes per-run convergence features, bands runs by outcome, and ranks the
features by how well they separate a failed run from a strong one. It also splits failures
into the two kinds that need different fixes:

    drifted      the run HELD a better artifact and ended after its peak
    never-there  no cycle in the run produced a working artifact

Deliberately conservative about what it claims:
  * every feature reports its COVERAGE -- the ledger is sparse and most fields are absent
    on older rows, so a strong-looking separation over 9 runs is labelled as such;
  * separation is Cohen's d, printed with n, never a p-value -- with n this small a
    significance test would imply more than the data carries;
  * features are tagged EARLY or LATE. Only an EARLY feature (one observable while the run
    is still going) can ever become a live brake; a LATE feature explains, but cannot warn.

PREFIX MODE answers the question the ledger cannot: the ledger stores end-of-run TOTALS, so it
can only show that failed runs repeated more IN TOTAL -- never that you could have told at cycle
20. Prefix mode reads each run's behaviour.ndjson, truncates it to the first K cycles, and prints
the same features computed from that prefix alongside the run's FINAL outcome. If the bands
separate on a prefix, an early brake has something to key on; if they do not, it does not.

Usage:  scripts/convergence_report.py [--ledger PATH] [--min-coverage N]
        scripts/convergence_report.py --run-dirs DIR [DIR ...] [--prefix K]
Last line is machine readable:
    CONVERGENCE: runs=<n> failed=<n> drifted=<n> never-there=<n> top-early=<feature|none>
    CONVERGENCE-PREFIX: runs=<n> prefix=<k> separating=<feature|none>
"""
import argparse, json, os, re, statistics as st, sys

# Outcome bands. The smoke score is a stateful chain, so these are coarse on purpose --
# comparing bands, never adjacent totals.
BANDS = [("failed", 0, 0), ("partial", 1, 15), ("strong", 16, 21), ("perfect", 22, 23)]

# EARLY = observable while the run is still running, so it could drive a live decision.
# LATE  = only knowable once the run is over; explanatory only.
FEATURE_KIND = {
    "merges": "EARLY", "total_cycles": "LATE", "merges_per_cycle": "EARLY",
    "run_duration_min": "LATE", "median_cycle_seconds": "EARLY",
    "cycles_per_min": "EARLY", "repeat_proposals": "EARLY", "repeats_refused": "EARLY",
    "repeat_rate": "EARLY", "criteria_authored": "EARLY",
    "injector_files_created": "EARLY", "drift_tail_cycles": "LATE",
    "peak_position": "LATE", "score_delta": "LATE",
}


def smoke_score(raw):
    """Numeric smoke score. A build/boot failure is 0 -- nothing passed -- not missing data."""
    if raw is None:
        return None
    m = re.search(r"(\d+)/23", raw)
    return int(m.group(1)) if m else 0


def num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def features(row):
    """Per-run convergence features. Absent inputs yield absent features, never zeros --
    a missing field is not a measurement of zero."""
    f = {}
    for k in ("merges", "total_cycles", "run_duration_min", "median_cycle_seconds",
              "repeat_proposals", "repeats_refused", "criteria_authored",
              "injector_files_created", "drift_tail_cycles", "score_delta"):
        v = num(row.get(k))
        if v is not None:
            f[k] = v
    if "merges" in f and f.get("total_cycles"):
        f["merges_per_cycle"] = f["merges"] / f["total_cycles"]
    if f.get("run_duration_min"):
        f["cycles_per_min"] = f.get("total_cycles", 0) / f["run_duration_min"]
    if "repeat_proposals" in f and f.get("total_cycles"):
        f["repeat_rate"] = f["repeat_proposals"] / f["total_cycles"]
    # How far through the run the best artifact appeared: 1.0 = still improving at the end.
    m = re.search(r"\((\d+)/(\d+)\)", str(row.get("peaked_at_cycle", "")))
    if m and int(m.group(2)):
        f["peak_position"] = int(m.group(1)) / int(m.group(2))
    return f


def band(score):
    for name, lo, hi in BANDS:
        if lo <= score <= hi:
            return name
    return "unknown"


def cohens_d(a, b):
    """Standardised separation between two samples. None when either side is too small or
    has no spread -- a d computed from one point is noise wearing a number's clothes."""
    if len(a) < 2 or len(b) < 2:
        return None
    va, vb = st.pstdev(a), st.pstdev(b)
    pooled = ((va ** 2 + vb ** 2) / 2) ** 0.5
    if pooled == 0:
        return None
    return (st.mean(a) - st.mean(b)) / pooled


def load(path):
    with open(path) as fh:
        raw = json.load(fh)
    runs = raw.get("runs", raw)
    out = []
    for name, row in runs.items():
        if not isinstance(row, dict):
            continue
        s = smoke_score(row.get("smoke"))
        if s is None:
            continue
        out.append((name, s, row, features(row)))
    return out


def failure_split(runs):
    """Failures need different fixes depending on whether the run ever had a good artifact."""
    drifted, never = [], []
    for name, score, row, f in runs:
        if score != 0:
            continue
        (drifted if f.get("drift_tail_cycles", 0) > 0 else never).append(name)
    return drifted, never


def read_behaviour(path):
    """Parse a behaviour file into events.

    These files are CONCATENATED pretty-printed JSON objects, not line-delimited JSON, despite
    the .ndjson name -- reading them line by line yields nothing. Truncated trailing objects are
    expected (a run is killed mid-write at the deadline) and end the scan without failing.
    """
    with open(path, encoding="utf-8", errors="replace") as fh:
        raw = fh.read()
    dec, i, out = json.JSONDecoder(), 0, []
    while i < len(raw):
        while i < len(raw) and raw[i] in " \n\r\t":
            i += 1
        if i >= len(raw):
            break
        try:
            obj, i = dec.raw_decode(raw, i)
        except ValueError:
            break
        out.append(obj)
    return out


def prefix_features(events, k):
    """Convergence features computed from the FIRST k cycles only.

    k is a cycle count, not an event count, so runs of different speeds are compared at the same
    point in their own progress rather than at the same wall-clock moment.
    """
    ends, fixes, order = [], [], []
    for e in events:
        cid = e.get("cycleId")
        if cid and cid not in order:
            order.append(cid)
        if e.get("event") == "cycle_end":
            ends.append((cid, e.get("payload") or {}))
        elif e.get("event") == "build_fix_attempt":
            fixes.append((cid, e.get("payload") or {}))
    keep = set(order[:k])
    ends = [(c, p) for c, p in ends if c in keep]
    fixes = [(c, p) for c, p in fixes if c in keep]
    if not ends:
        return None
    n = len(ends)
    merged = sum(1 for _, p in ends if p.get("merged"))
    ok = sum(1 for _, p in ends if p.get("buildOutcome") == "COMPILE_OK")
    stag = sum(1 for _, p in ends if p.get("resultState") == "STAGNATING")
    attempts = [p.get("attemptNumber", 0) or 0 for _, p in fixes]
    touched = []
    for _, p in fixes:
        touched.extend(p.get("mutatedFiles") or [])
    # Repair pressure concentrated on few files is the thrash signature; spread over many is work.
    concentration = (len(touched) / len(set(touched))) if touched else 0.0
    return {
        "cycles": n,
        "merge_rate": merged / n,
        "compile_ok_rate": ok / n,
        "stagnating_rate": stag / n,
        "fix_attempts_per_cycle": len(fixes) / n,
        "max_fix_attempt": max(attempts) if attempts else 0,
        "fix_file_concentration": concentration,
    }


def run_dir_outcome(d):
    """Final smoke score for a run directory, from the summary preflight wrote beside it."""
    try:
        with open(os.path.join(d, "summary.txt")) as fh:
            for line in fh:
                if line.startswith("smoke="):
                    return smoke_score(line.split("=", 1)[1].strip())
    except OSError:
        pass
    return None


def prefix_mode(dirs, k):
    rows = []
    for d in dirs:
        beh = os.path.join(d, "behaviour.ndjson")
        if not os.path.exists(beh):
            print(f"  {os.path.basename(d):26s} (no behaviour.ndjson -- skipped)")
            continue
        f = prefix_features(read_behaviour(beh), k)
        if f is None:
            print(f"  {os.path.basename(d):26s} (no completed cycles -- skipped)")
            continue
        rows.append((os.path.basename(d), run_dir_outcome(d), f))

    if not rows:
        print(f"\nCONVERGENCE-PREFIX: runs=0 prefix={k} separating=none")
        return 1

    feats = ["merge_rate", "compile_ok_rate", "stagnating_rate",
             "fix_attempts_per_cycle", "max_fix_attempt", "fix_file_concentration"]
    print(f"\n=== prefix mode: first {k} cycles, final outcome in the last column ===\n")
    print(f"  {'run':26s} {'cyc':>4s} " + " ".join(f"{x[:9]:>9s}" for x in feats) + f" {'FINAL':>7s}")
    for name, score, f in sorted(rows, key=lambda r: -(r[1] if r[1] is not None else -1)):
        vals = " ".join(f"{f[x]:9.2f}" for x in feats)
        print(f"  {name:26s} {f['cycles']:4d} {vals} {str(score):>7s}")

    failed = [f for _, s, f in rows if s == 0]
    good = [f for _, s, f in rows if s is not None and s >= 16]
    best = None
    if failed and good:
        print(f"\n  separation at this prefix (failed n={len(failed)} vs strong n={len(good)}):")
        for x in feats:
            d = cohens_d([f[x] for f in failed], [f[x] for f in good])
            if d is None:
                print(f"    {x:24s}   (too few runs to separate)")
                continue
            print(f"    {x:24s} d={d:6.2f}")
            if best is None or abs(d) > abs(best[1]):
                best = (x, d)
    else:
        print("\n  (need both failed and strong runs present to separate)")
    sep = best[0] if best and abs(best[1]) >= 0.8 else "none"
    print(f"\nCONVERGENCE-PREFIX: runs={len(rows)} prefix={k} separating={sep}")
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--ledger", default="scripts/run_outcomes.json")
    ap.add_argument("--min-coverage", type=int, default=8,
                    help="features present on fewer runs than this are reported but not ranked")
    ap.add_argument("--run-dirs", nargs="+", metavar="DIR",
                    help="preflight run_dirs to analyse per-cycle instead of the ledger")
    ap.add_argument("--prefix", type=int, default=15,
                    help="how many leading cycles prefix mode may look at")
    args = ap.parse_args(argv)

    if args.run_dirs:
        return prefix_mode(args.run_dirs, args.prefix)

    runs = load(args.ledger)
    if not runs:
        print("CONVERGENCE: runs=0 failed=0 drifted=0 never-there=0 top-early=none")
        return 1

    print(f"=== convergence report over {len(runs)} scored runs ===\n")

    print("outcome bands")
    for name, lo, hi in BANDS:
        members = [r for r in runs if band(r[1]) == name]
        if members:
            scores = [r[1] for r in members]
            print(f"  {name:8s} {lo:2d}-{hi:2d}  n={len(members):3d}  "
                  f"mean {st.mean(scores):5.1f}  merges "
                  f"{st.mean([r[3]['merges'] for r in members if 'merges' in r[3]] or [0]):5.1f}")

    drifted, never = failure_split(runs)
    print(f"\nfailure decomposition ({len(drifted) + len(never)} runs scoring 0)")
    print(f"  drifted off a working artifact : {len(drifted):3d}  -> a preserve/restore problem")
    print(f"  never reached one              : {len(never):3d}  -> a generation problem")

    failed = [r for r in runs if r[1] == 0]
    good = [r for r in runs if r[1] >= 16]
    print(f"\nfeature separation, failed (n={len(failed)}) vs strong+perfect (n={len(good)})")
    print(f"  {'feature':24s} {'kind':6s} {'cov':>5s} {'failed':>9s} {'strong':>9s} {'d':>7s}")
    ranked = []
    for feat in sorted(FEATURE_KIND):
        fa = [r[3][feat] for r in failed if feat in r[3]]
        ga = [r[3][feat] for r in good if feat in r[3]]
        cov = len([r for r in runs if feat in r[3]])
        d = cohens_d(fa, ga)
        fm = f"{st.mean(fa):9.2f}" if fa else "        -"
        gm = f"{st.mean(ga):9.2f}" if ga else "        -"
        ds = f"{d:7.2f}" if d is not None else "      -"
        flag = "" if cov >= args.min_coverage else "  (thin)"
        print(f"  {feat:24s} {FEATURE_KIND[feat]:6s} {cov:5d} {fm} {gm} {ds}{flag}")
        if d is not None and cov >= args.min_coverage:
            ranked.append((abs(d), feat, d))

    ranked.sort(reverse=True)
    early = [x for x in ranked if FEATURE_KIND[x[1]] == "EARLY"]
    print("\nranked by separation (coverage >= %d)" % args.min_coverage)
    for a, feat, d in ranked[:6]:
        print(f"  {abs(d):5.2f}  {feat:24s} {FEATURE_KIND[feat]}  "
              f"({'higher' if d > 0 else 'lower'} in failed runs)")
    if not ranked:
        print("  (nothing meets the coverage bar -- the ledger is too sparse to rank)")

    top_early = early[0][1] if early else "none"
    print(f"\ntop EARLY separator: {top_early}"
          + ("" if early else "  -- no early feature separates; a live brake has nothing to key on"))

    print(f"\nCONVERGENCE: runs={len(runs)} failed={len(failed)} drifted={len(drifted)} "
          f"never-there={len(never)} top-early={top_early}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
