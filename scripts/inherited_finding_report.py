"""T4-DR pre-step: how often does a run INHERIT a tree already carrying a
detectable structural finding in its FIRST cycle?

Corpus floor is 57f84eb9 (2026-08-24 20:21) -- the commit that introduced the
UnregisteredInjectedType fact. No predecessor detector exists, so a run before
that cannot carry the sentence and is not evidence either way.
"""
import json, re, glob, os, sys, subprocess
from datetime import datetime

FLOOR = datetime(2026, 8, 24, 20, 21, 24)
# 3685db5c -- T1-FX moved detection to a disk read of the MERGED tree, so a
# finding introduced by a merge is now seen in that merge's own cycle.
T1FX = datetime(2026, 9, 2, 16, 15, 23)

# The two tree-property findings whose repairs hang off the merge hook.
ANCHORS = {
    "unregistered_injected": "is a constructor parameter of",
    "peer_placement": "types named by the peers in",
}
TS = re.compile(r"^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\.\d+ ")


def read_behaviour(path):
    t = open(path, encoding="utf-8", errors="replace").read()
    d = json.JSONDecoder()
    i, recs = 0, []
    while i < len(t):
        while i < len(t) and t[i].isspace():
            i += 1
        if i >= len(t):
            break
        try:
            o, e = d.raw_decode(t, i)
        except ValueError:
            break
        recs.append(o)
        i = e
    return recs


def parse_ts(s):
    for f in ("%Y-%m-%dT%H:%M:%S.%f", "%Y-%m-%dT%H:%M:%S", "%Y-%m-%d %H:%M:%S"):
        try:
            return datetime.strptime(s[:26] if "." in s else s[:19], f)
        except ValueError:
            pass
    return None


def collect(roots):
    """Return {run_start_datetime: {'log':path,'beh':path}} deduped by run start."""
    logs, behs = {}, {}
    for root in roots:
        for p in glob.glob(os.path.join(root, "**", "autobuilder-*.log"), recursive=True):
            m = re.search(r"autobuilder-(\d{4}-\d{2}-\d{2})_(\d{2})-(\d{2})-(\d{2})\.log$", p)
            if not m:
                continue
            dt = datetime.strptime(f"{m.group(1)} {m.group(2)}:{m.group(3)}:{m.group(4)}", "%Y-%m-%d %H:%M:%S")
            # keep the largest copy of a duplicated run
            if dt not in logs or os.path.getsize(p) > os.path.getsize(logs[dt]):
                logs[dt] = p
        for p in glob.glob(os.path.join(root, "**", "behaviour-*.ndjson"), recursive=True):
            m = re.search(r"behaviour-(\d{4}-\d{2}-\d{2})_(\d{2})-(\d{2})-(\d{2})\.ndjson$", p)
            if not m:
                continue
            dt = datetime.strptime(f"{m.group(1)} {m.group(2)}:{m.group(3)}:{m.group(4)}", "%Y-%m-%d %H:%M:%S")
            if dt not in behs or os.path.getsize(p) > os.path.getsize(behs[dt]):
                behs[dt] = p
    return logs, behs


def pair(logdt, behs):
    """Behaviour file is stamped at first-event time, a few seconds after JVM start."""
    best = None
    for bdt in behs:
        delta = (bdt - logdt).total_seconds()
        if 0 <= delta <= 120 and (best is None or delta < best[0]):
            best = (delta, bdt)
    return best[1] if best else None


def main(roots):
    logs, behs = collect(roots)
    rows = []
    for logdt in sorted(logs):
        if logdt < FLOOR:
            continue
        lp = logs[logdt]
        bdt = pair(logdt, behs)
        cycle2 = None
        merges = ncycles = 0
        merge_ts = []
        if bdt:
            recs = read_behaviour(behs[bdt])
            starts = [parse_ts(r["timestamp"]) for r in recs
                      if r.get("event") == "cycle_start" and r.get("timestamp")]
            starts = [s for s in starts if s]
            ncycles = len(starts)
            if len(starts) >= 2:
                cycle2 = starts[1]
            merge_ts = sorted(t for t in (parse_ts(r["timestamp"]) for r in recs
                              if r.get("event") == "mutation_merged" and r.get("timestamp")) if t)
            merges = len(merge_ts)
        first_hit = {}
        total_hits = {k: 0 for k in ANCHORS}
        # Pre-filter in C: only walk a log that actually carries an anchor.
        # The fact is rendered INSIDE a prompt block, so the matching line has no
        # timestamp of its own -- the walk carries the last timestamped line forward.
        present = [n for n, frag in ANCHORS.items()
                   if subprocess.call(["grep", "-qF", frag, lp]) == 0]
        if present:
            with open(lp, encoding="utf-8", errors="replace") as fh:
                cur = None
                for line in fh:
                    m = TS.match(line)
                    if m:
                        cur = datetime.strptime(m.group(1), "%Y-%m-%d %H:%M:%S")
                        continue
                    for name in present:
                        if ANCHORS[name] in line:
                            total_hits[name] += 1
                            if name not in first_hit and cur:
                                first_hit[name] = cur
        rows.append(dict(run=logdt, log=lp, beh=behs.get(bdt) if bdt else None,
                         cycles=ncycles, merges=merges, cycle2=cycle2,
                         first_hit=first_hit, hits=total_hits, merge_ts=merge_ts,
                         target=lp.split("/data/")[-1].split("/")[0] if "/data/" in lp else "?"))
    return rows


if __name__ == "__main__":
    rows = main(sys.argv[1:])
    print(f"runs at or after the detector's floor ({FLOOR:%Y-%m-%d %H:%M}): {len(rows)}\n")
    hdr = f"{'run':21} {'target':12} {'cyc':>4} {'mrg':>4} {'inherited?':>11}  finding"
    print(hdr); print("-" * len(hdr))
    inherited = no_merge_inherited = 0
    for r in rows:
        tags = []
        for name in ANCHORS:
            t = r["first_hit"].get(name)
            if not t:
                continue
            prior = sum(1 for m in r["merge_ts"] if m < t)
            after = sum(1 for m in r["merge_ts"] if m > t)
            mark = "INHERITED" if prior == 0 else f"after {prior} merges"
            tags.append(f"{name}[{mark}, {after} merges left, x{r['hits'][name]}]")
        inh = any("INHERITED" in t for t in tags)
        if inh:
            inherited += 1
            if r["merges"] == 0:
                no_merge_inherited += 1
        print(f"{r['run']:%Y-%m-%d_%H-%M-%S} {r['target']:12} {r['cycles']:4} {r['merges']:4} "
              f"{'YES' if inh else '-':>11}  {', '.join(tags) or '-'}")
    print()
    print(f"INHERITED a finding (no merge preceded it) : {inherited}/{len(rows)}")
    print(f"  ...of those, ZERO merges all run, so the merge hook never ran at all: {no_merge_inherited}")
    post = [r for r in rows if r["run"] >= T1FX]
    pf = [r for r in post if r["first_hit"]]
    print()
    print(f"POST-T1-FX subset (runs after {T1FX:%Y-%m-%d %H:%M}, detection reads the merged tree): {len(post)} runs")
    print(f"  runs carrying ANY finding: {len(pf)}")
    for r in pf:
        prior = {n: sum(1 for m in r["merge_ts"] if m < t) for n, t in r["first_hit"].items()}
        print(f"    {r['run']:%Y-%m-%d_%H-%M-%S}  merges={r['merges']}  first-hit-after-merges={prior}")
    # Many corpus entries are aborted launches (0-4 cycles) that never had a chance
    # to render anything; the rate that matters is over runs that actually ran.
    subst = [r for r in rows if r["cycles"] >= 5]
    subst_post = [r for r in subst if r["run"] >= T1FX]
    si = sum(1 for r in subst if r["first_hit"] and
             any(sum(1 for m in r["merge_ts"] if m < t) == 0 for t in r["first_hit"].values()))
    spi = sum(1 for r in subst_post if r["first_hit"] and
              any(sum(1 for m in r["merge_ts"] if m < t) == 0 for t in r["first_hit"].values()))
    print()
    print(f"SUBSTANTIAL runs (>=5 cycles): {len(subst)} of {len(rows)}; inherited a finding: {si}")
    print(f"  substantial AND post-T1-FX: {len(subst_post)}; inherited a finding: {spi}")

    stranded = [r for r in rows if r["first_hit"] and
                all(sum(1 for m in r["merge_ts"] if m > t) == 0 for t in r["first_hit"].values())]
    print()
    print(f"NO MERGE LEFT after the finding appeared (hook can never act again): {len(stranded)}/{len(rows)}")
    for r in stranded:
        era = "post-T1-FX" if r["run"] >= T1FX else "pre-T1-FX"
        print(f"    {r['run']:%Y-%m-%d_%H-%M-%S}  merges={r['merges']}  cycles={r['cycles']}  [{era}]")
