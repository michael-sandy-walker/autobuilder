"""T6-R: what separates a run that converges from one that collapses?

Reads the DURABLE ledger (scripts/run_outcomes.json), not the logs: logs rotate
and only a fifth of recorded runs still have one, which is what confined the
first pass at this question to 15 runs. The graph half of the analysis lives in
--graph mode and needs Neo4j up; it reaches 161 runs for the same reason.

ANSWER (2026-09-10): no cheap feature separates the two populations beyond what
chance produces once you count how many features were tried. The one that does
separate -- merges >= 6 -- is the near-tautology the ticket already named, since
merges are how code enters the artifact the checks score.

Read the three traps this measurement walked into before trusting a new feature:

  BASELINE. The classes are unbalanced (20 perfect against 5 collapsed), so
  "always say perfect" already scores 80%. A feature at 79-83% carries NO
  information; only lift over the baseline means anything.

  MULTIPLE COMPARISONS. Trying 11 features and reporting the best is not the
  same as testing one. The permutation test shuffles the OUTCOME and refits the
  whole search, which is the only honest p-value here.

  EXTREMES FLATTER. Dropping the middle of the distribution makes every feature
  look sharper. score_max correlates +0.54 across the full range and scores WORSE
  THAN THE BASELINE at the extremes -- a feature can track the middle and be
  useless at exactly the question being asked.
"""
import json, re, sys, statistics
from collections import Counter

LEDGER = 'scripts/run_outcomes.json'


def num(v):
    try:
        return float(str(v).strip().lstrip('+'))
    except (TypeError, ValueError):
        return None


def load(path=LEDGER):
    return json.load(open(path))['runs']


def strict_share(r):
    """Strict passes over THE TARGET'S OWN CHECK COUNT. None when unscored.

    ⚠ The denominator in `smoke_strict` is always 23 -- firestarter's size -- even
    for a target with fewer checks, so a clitarget run that passed 19 of 19 records
    "19/23". Dividing by that literal makes a PERFECT run read as 83% and lands it
    in the middle of the distribution, where it silently pads the population the
    whole analysis is about. Measured: 10 of 47 scored runs are non-firestarter and
    two of them are perfect runs mis-ranked this way.

    So the target's own `smoke_total` wins when present, and the literal denominator
    is only the fallback for rows predating that field.
    """
    m = re.match(r'\s*(\d+)/(\d+)', str(r.get('smoke_strict', '')))
    if not m:
        return None
    passed = int(m.group(1))
    total = r.get('smoke_total') or int(m.group(2))
    try:
        total = int(total)
    except (TypeError, ValueError):
        total = int(m.group(2))
    return (passed / total) if total else None


def peak_fraction(r):
    """Where in the run its best state occurred, 0..1. Late peak = still improving."""
    m = re.search(r'\((\d+)/(\d+)\)', str(r.get('peaked_at_cycle', '')))
    if not m or not int(m.group(2)):
        return None
    return int(m.group(1)) / int(m.group(2))


def features(r):
    """Every cheap feature the ledger affords, named once."""
    cyc = num(r.get('total_cycles'))
    mg = num(r.get('merges'))
    dur = num(r.get('run_duration_min'))
    f = {
        'merges': mg,
        'total_cycles': cyc,
        'duration_min': dur,
        'median_cycle_s': num(r.get('median_cycle_seconds')),
        'drift_tail_cycles': num(r.get('drift_tail_cycles')),
        'criteria_authored': num(r.get('criteria_authored')),
        'criteria_executable_pct': num(r.get('criteria_executable_pct')),
        'repeat_proposals': num(r.get('repeat_proposals')),
        'repeats_refused': num(r.get('repeats_refused')),
        'clauses_closed': num(r.get('clauses_closed_vs_control')),
        'peak_fraction': peak_fraction(r),
    }
    # derived rates -- a count means little without the window it accrued over
    f['merges_per_cycle'] = (mg / cyc) if mg is not None and cyc else None
    f['merges_per_hour'] = (mg / dur * 60) if mg is not None and dur else None
    f['cycles_per_hour'] = (cyc / dur * 60) if cyc is not None and dur else None
    tail = num(r.get('drift_tail_cycles'))
    f['drift_tail_share'] = (tail / cyc) if tail is not None and cyc else None
    return f


def best_threshold(rows, key):
    """Best single-threshold split of good vs bad, with its errors.

    Returns (threshold, direction, correct, n, errors) or None. Direction '>=' means
    "good runs are the ones at or above the threshold".
    """
    vals = [(f[key], good) for f, good in rows if f.get(key) is not None]
    if len(vals) < 8:
        return None
    goods = [v for v, g in vals if g]
    bads = [v for v, g in vals if not g]
    if not goods or not bads:
        return None
    best = None
    cands = sorted({v for v, _ in vals})
    for t in cands:
        for direction in ('>=', '<'):
            ok = sum(1 for v, g in vals
                     if (g == (v >= t if direction == '>=' else v < t)))
            if best is None or ok > best[2]:
                errs = [(v, g) for v, g in vals
                        if g != (v >= t if direction == '>=' else v < t)]
                best = (t, direction, ok, len(vals), errs)
    return best


def main(argv):
    runs = load()
    # Population: runs carrying a STRICT score. The non-strict score counts checks
    # that pass because an endpoint is absent, so it cannot be an outcome variable.
    scored = {k: v for k, v in runs.items() if strict_share(v) is not None}
    print(f"ledger runs: {len(runs)}   carrying a strict score: {len(scored)}")

    # yardsticks differ per target; a share is comparable, a count is not
    tot = Counter(re.match(r'\s*\d+/(\d+)', str(v.get('smoke_strict'))).group(1)
                  for v in scored.values())
    print(f"yardstick sizes among scored runs: {dict(tot)}")

    print("\n=== OUTCOME DISTRIBUTION (the ticket's premise, checked) ===")
    shares = sorted(round(strict_share(v) * 100) for v in scored.values())
    b = Counter((s // 10) * 10 for s in shares)
    for lo in range(0, 101, 10):
        if b.get(lo):
            print(f"  {lo:3}-{min(lo+9,100):3}% : {'#'*b[lo]} ({b[lo]})")
    print(f"  median {statistics.median(shares)}%")

    # The ticket's premise is that runs split into near-perfect and near-zero with
    # little in between. Over the whole corpus that is FALSE (median 83%); it became
    # true only recently, which is a capability change worth seeing rather than a
    # constant of the system.
    print("\n=== IS IT BIMODAL? by month ===")
    per = {}
    for k, v in scored.items():
        per.setdefault(k[:7], []).append(strict_share(v) * 100)
    print(f"  {'month':9} {'n':>4} {'perfect':>8} {'near-zero':>10} {'MIDDLE':>7} {'median':>7}")
    for mo in sorted(per):
        sh = sorted(per[mo])
        print(f"  {mo:9} {len(sh):4} {sum(1 for x in sh if x >= 100):8} "
              f"{sum(1 for x in sh if x < 20):10} {sum(1 for x in sh if 20 <= x < 100):7} "
              f"{statistics.median(sh):6.0f}%")

    # THE STRATIFIER BOTH EARLIER PASSES MISSED. A run that reaches its own goal and
    # a run the harness stops are not one population, and pooling them is what made
    # "perfect versus collapsed" look like a single question with a single answer.
    print("\n=== BY TERMINAL — how the run ENDED ===")
    by = {}
    for k, v in scored.items():
        by.setdefault((v.get('terminal') or '?').split(':')[0], []).append(strict_share(v) * 100)
    print(f"  {'terminal':26} {'n':>4} {'median':>8} {'perfect':>8} {'<=40%':>7}")
    for t in sorted(by, key=lambda x: -len(by[x])):
        v = by[t]
        print(f"  {t:26} {len(v):4} {statistics.median(v):7.0f}% "
              f"{sum(1 for x in v if x >= 100):8} {sum(1 for x in v if x <= 40):7}")
    print("  NOTE: no GOAL_FULFILLED run has ever collapsed. Every collapse is a run that was")
    print("        stopped (deadline) or stopped itself (honest-halt), so 'collapse' is largely")
    print("        what an INTERRUPTED run looks like -- not a distinct failure of the loop.")

    GOOD, BAD = 1.0, 0.40
    rows = [(features(v), strict_share(v) >= GOOD) for v in scored.values()
            if strict_share(v) >= GOOD or strict_share(v) <= BAD]
    ngood = sum(1 for _, g in rows if g)
    print(f"\n=== SEPARATION TEST ===")
    print(f"comparing perfect (100%) n={ngood} against collapsed (<={int(BAD*100)}%) "
          f"n={len(rows)-ngood}; the middle is EXCLUDED, which flatters every feature")

    results = []
    for key in sorted(features(next(iter(scored.values()))).keys()):
        r = best_threshold(rows, key)
        if r:
            results.append((r[2] / r[3], key, r))

    base = ngood / len(rows)
    print(f"\nBASELINE: always answering 'perfect' scores {base*100:.0f}%. A feature at or "
          f"below that carries no information.")
    print(f"\n{'feature':26} {'acc':>6} {'lift':>6}  {'rule':>22}  errors")
    for acc, key, (t, d, ok, n, errs) in sorted(results, reverse=True):
        rule = f"good if {d} {t:.3g}"
        print(f"  {key:24} {acc*100:5.0f}% {(acc-base)*100:+5.0f}%  {rule:>22}  {len(errs)}")

    print(f"\n=== PERMUTATION TEST over all {len(results)} features ===")
    p = permutation_p(rows, [k for _, k, _ in results])
    print(f"  best observed accuracy {max(a for a, _, _ in results)*100:.0f}%")
    print(f"  shuffling the outcome reaches it in {p*100:.1f}% of trials  ->  p = {p:.3f}")
    if p > 0.05:
        print("  NOT SIGNIFICANT: consistent with no feature carrying signal.")

    print("\n=== THE TWO RUNS THE TICKET SAYS ANY ANSWER MUST GET RIGHT ===")
    for k in ('2026-09-08_23-52-13', '2026-09-08_22-32-37'):
        if k in scored:
            f = features(scored[k])
            print(f"  {k}  strict={strict_share(scored[k])*100:.0f}%  "
                  f"merges={f['merges']:.0f}  cycles={f['total_cycles']:.0f}")
    return scored, rows


def permutation_p(rows, keys, trials=2000, seed=7):
    """p for the BEST-OF-N search, which is what multiple comparisons inflate."""
    import random
    def bestacc(data, key):
        v = [(f[key], g) for f, g in data if f.get(key) is not None]
        if len(v) < 8:
            return 0.0
        return max(max(sum(1 for x, g in v if g == (x >= t if d == '>=' else x < t)) / len(v)
                       for d in ('>=', '<')) for t in sorted({x[0] for x in v}))
    obs = max(bestacc(rows, k) for k in keys)
    rng = random.Random(seed)
    labels = [g for _, g in rows]
    hits = 0
    for _ in range(trials):
        sh = labels[:]
        rng.shuffle(sh)
        perm = [(f, sh[i]) for i, (f, _) in enumerate(rows)]
        if max(bestacc(perm, k) for k in keys) >= obs:
            hits += 1
    return hits / trials


# ---------------------------------------------------------------- graph half

CYPHER_ATTEMPTS = (
    "MATCH (e:EvolutionAttempt) WHERE e.runId IS NOT NULL "
    "WITH e.runId AS run, min(e.created) AS t0, count(*) AS attempts, "
    "sum(CASE WHEN e.divergent THEN 1 ELSE 0 END) AS divergent "
    "RETURN run, t0, attempts, divergent;")

CYPHER_SCORES = (
    "MATCH (g:GlobalScore) WHERE g.runId IS NOT NULL "
    "WITH g.runId AS run, collect(toFloat(g.score)) AS s "
    "RETURN run, reduce(mx=-1.0, x IN s | CASE WHEN x>mx THEN x ELSE mx END) AS maxScore, "
    "reduce(sm=0.0, x IN s | sm+x)/size(s) AS meanScore;")

CYPHER_CRITS = (
    "MATCH (c:CriterionResult) WHERE c.runId IS NOT NULL "
    "RETURN c.runId AS run, count(*) AS crits, "
    "sum(CASE WHEN c.passed THEN 1 ELSE 0 END) AS passed;")


def cypher(query):
    """Rows from the graph, or None when it is not reachable. Never raises: the
    ledger half of this report must still run on a machine with no Neo4j."""
    import subprocess, csv as _csv
    try:
        out = subprocess.run(
            ['docker', 'exec', 'neo4j', 'cypher-shell', '-u', 'neo4j', '-p',
             'your_password', '--format', 'plain', query],
            capture_output=True, text=True, timeout=120)
    except Exception:
        return None
    if out.returncode != 0:
        return None
    rows = list(_csv.reader(out.stdout.splitlines()[1:]))
    return [[c.strip().strip('"') for c in r] for r in rows if r]


def graph_report():
    """Per-run behavioural features from the graph, joined to ledger outcomes.

    The graph is the wider and more durable instrument -- 161 runs against the
    ledger's 123 -- because it does not rotate the way logs do. The join is on
    start time: the graph stamps UTC and the ledger names local time (+2), and a
    run matches the nearest ledger row within ten minutes.

    KNOWN JOIN LIMIT, measured: two JVMs starting inside that window both match the
    same ledger row (observed once, on 2026-09-01_00-17-05). Reported rather than
    silently deduped -- the pair is visible in the count line.
    """
    import datetime
    att = cypher(CYPHER_ATTEMPTS)
    if att is None:
        print("\n=== GRAPH HALF: skipped, Neo4j not reachable ===")
        return
    scores = {r[0]: r[1:] for r in (cypher(CYPHER_SCORES) or [])}
    crits = {r[0]: r[1:] for r in (cypher(CYPHER_CRITS) or [])}
    runs = load()
    ledger_keys = [(k, datetime.datetime.strptime(k, '%Y-%m-%d_%H-%M-%S')) for k in runs]

    rows = []
    for r in att:
        run, t0 = r[0], r[1]
        try:
            t = datetime.datetime.strptime(t0[:19], '%Y-%m-%dT%H:%M:%S') + datetime.timedelta(hours=2)
        except ValueError:
            continue
        k, dt = min(ledger_keys, key=lambda x: abs((x[1] - t).total_seconds()))
        if abs((dt - t).total_seconds()) > 600:
            continue
        y = strict_share(runs[k])
        if y is None:
            continue
        attempts = float(r[2]) or None
        f = {'attempts': attempts,
             'divergent_share': (float(r[3]) / attempts) if attempts else None}
        if run in scores:
            f['score_max'] = float(scores[run][0])
            f['score_mean'] = float(scores[run][1])
        if run in crits:
            tot = float(crits[run][0])
            f['crit_evals'] = tot
            f['crit_pass_rate'] = (float(crits[run][1]) / tot) if tot else None
        rows.append((f, y))

    print(f"\n=== GRAPH HALF: {len(att)} runs in the graph, {len(rows)} joined AND scored ===")
    if len(rows) < 10:
        print("  too few to report")
        return
    print(f"  {'feature':22} {'rho':>7}   (correlation with strict share, all scored runs)")
    for key in sorted({k for f, _ in rows for k in f}):
        pairs = [(f[key], y) for f, y in rows if f.get(key) is not None]
        if len(pairs) < 15:
            continue
        print(f"  {key:22} {spearman([p[0] for p in pairs], [p[1] for p in pairs]):+7.2f}")
    ext = [(f, y >= 1.0) for f, y in rows if y >= 1.0 or y <= 0.40]
    keys = sorted({k for f, _ in ext for k in f})
    if ext and any(g for _, g in ext) and not all(g for _, g in ext):
        p = permutation_p(ext, keys)
        print(f"\n  permutation p over {len(keys)} graph features: {p:.3f}"
              f"{'  NOT SIGNIFICANT' if p > 0.05 else ''}")


def spearman(xs, ys):
    def rank(v):
        order = sorted(range(len(v)), key=lambda i: v[i])
        r = [0] * len(v)
        for pos, i in enumerate(order):
            r[i] = pos
        return r
    rx, ry = rank(xs), rank(ys)
    n = len(xs)
    mx, my = statistics.mean(rx), statistics.mean(ry)
    num = sum((rx[i] - mx) * (ry[i] - my) for i in range(n))
    den = (sum((rx[i] - mx) ** 2 for i in range(n))
           * sum((ry[i] - my) ** 2 for i in range(n))) ** 0.5
    return num / den if den else 0.0


if __name__ == '__main__':
    main(sys.argv[1:])
    if '--graph' in sys.argv[1:]:
        graph_report()
