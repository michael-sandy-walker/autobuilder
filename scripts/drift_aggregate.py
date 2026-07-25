#!/usr/bin/env python3
"""
drift_aggregate.py — T6-B cross-run drift aggregator.

drift_report.py answers "did THIS run end worse than a state it saved?" one run at a time.
This is the batch view: read N runs and report the DISTRIBUTION, so systematic drift becomes
visible instead of anecdotal.

The drift axis that exists population-wide is the POST-PRODUCTIVITY TAIL: the stretch a run
keeps executing after its last productive event. Per run:

  peak_k / peak_frac   the last productive cycle and its position k/N
  tail_cycles/_frac    cycles after the peak, absolute and as a share of the run
  tail_min             wall-clock minutes after the peak
  tail_tokens/_frac    LLM tokens spent in the tail (only for token-instrumented runs)
  tail_work_ratio      tail attempt-rate / pre-peak attempt-rate — is the tail still working?
  tail_class           churn (still attempting) · idle (ticking, barely attempting) · none

"Productive" has three readings, all reported; the default view is the most conservative:
  merge     a mutation reached the filesystem
  closure   a goal/subgoal reached COMPLETED
  criteria  an acceptance criterion passed that had never passed before
  any       whichever happened latest (smallest tail — the benefit-of-the-doubt reading)

tail_class is deliberately RUN-RELATIVE: a tail counts as churn when it attempts at least half
the rate of that run's own pre-peak phase, so a slow run is not scored against a fast one's pace.

Dual-score fields (score_delta, smoke, smoke_preserved) are folded in from a corpus summary.txt
when the input is a preflight corpus; archived behaviour logs contribute the tail axis only.

Usage:
  drift_aggregate.py <path>...            corpus dirs, behaviour*.ndjson files, or dirs to scan
  drift_aggregate.py backup --min-bytes 100000 --json rows.json
Never throws on a bad run; unreadable inputs are skipped and counted. Exit 0 always.
"""
import argparse
import collections
import datetime as dt
import glob
import json
import os
import re
import sys

PRODUCTIVITY_KINDS = ("merge", "closure", "criteria", "any")
DEFAULT_KIND = "any"
# Events that mean "the loop spent an attempt this cycle" — the work-rate numerator.
ATTEMPT_EVENTS = ("mutation_proposed", "build_fix_attempt", "mutation_injected")
# A tail attempting at least this share of the run's own pre-peak rate is still working, not idling.
CHURN_RATE_SHARE = 0.5
FRAC_DIGITS = re.compile(r"(\.\d{6})\d+")


def decode_stream(path):
    """Yield events from a behaviour file (pretty-printed concatenated JSON, not line-JSON)."""
    with open(path, errors="replace") as fh:
        raw = fh.read()
    dec = json.JSONDecoder()
    i, n = 0, len(raw)
    while i < n:
        while i < n and raw[i] in " \n\r\t":
            i += 1
        if i >= n:
            return
        try:
            obj, i = dec.raw_decode(raw, i)
        except ValueError:
            return  # truncated tail (killed run) — keep what parsed
        yield obj


def parse_ts(text):
    if not text:
        return None
    try:
        return dt.datetime.fromisoformat(FRAC_DIGITS.sub(r"\1", text))
    except ValueError:
        return None


def percentile(values, q):
    """Nearest-rank percentile of a non-empty sorted-able sequence."""
    xs = sorted(values)
    if not xs:
        return None
    idx = min(len(xs) - 1, max(0, int(round(q * (len(xs) - 1)))))
    return xs[idx]


def median(values):
    return percentile(values, 0.5)


def read_summary(corpus):
    """key=value fields from a preflight corpus summary.txt, or {}."""
    path = os.path.join(corpus, "summary.txt")
    out = {}
    if not os.path.isfile(path):
        return out
    try:
        with open(path, errors="replace") as fh:
            for line in fh:
                if "=" in line:
                    k, v = line.split("=", 1)
                    out[k.strip()] = v.strip()
    except OSError:
        return {}
    return out


def scan_inputs(paths, min_bytes):
    """Resolve inputs to [(run_id, behaviour_path, corpus_dir_or_None)], deduped by file name.

    Backups hold repeated copies of the same run's log; the largest copy wins (a later backup may
    hold a truncated or an extended version of the same file — the longer one is the fuller record).
    """
    found = {}

    def offer(behaviour, corpus):
        try:
            size = os.path.getsize(behaviour)
        except OSError:
            return
        if size < min_bytes:
            return
        key = os.path.basename(behaviour)
        if key == "behaviour.ndjson" and corpus:
            key = os.path.basename(os.path.abspath(corpus)) + "/" + key  # unsuffixed live log
        prev = found.get(key)
        if prev is None or size > prev[2]:
            found[key] = (behaviour, corpus, size)

    for path in paths:
        if os.path.isfile(path):
            offer(path, os.path.dirname(path))
        elif os.path.isdir(path):
            direct = os.path.join(path, "behaviour.ndjson")
            if os.path.isfile(direct):
                offer(direct, path)  # a preflight corpus
            for sub in glob.glob(os.path.join(path, "**", "behaviour*.ndjson"), recursive=True):
                offer(sub, os.path.dirname(sub))
    return [(rid, p, c) for rid, (p, c, _) in sorted(found.items())]


def analyse_run(run_id, behaviour, corpus):
    """One row of the batch view, or None when the file holds no cycle at all."""
    order, seen = [], set()
    first_ts, tokens, attempts = {}, {}, {}
    last_productive = {kind: None for kind in PRODUCTIVITY_KINDS}
    passed_criteria = set()
    merges, terminal = 0, None

    for ev in decode_stream(behaviour):
        name = ev.get("event") or ev.get("type")
        cid = ev.get("cycleId")
        payload = ev.get("payload") or {}
        if cid and cid not in seen:
            seen.add(cid)
            order.append(cid)
            first_ts[cid] = parse_ts(ev.get("timestamp"))
        if name in ATTEMPT_EVENTS and cid:
            attempts[cid] = attempts.get(cid, 0) + 1
        if name == "llm_call" and cid:
            tokens[cid] = tokens.get(cid, 0) + (payload.get("totalTokens") or 0)
        elif name == "mutation_merged" and cid:
            merges += 1
            last_productive["merge"] = last_productive["any"] = cid
        elif name == "goal_status_changed" and cid and payload.get("to") == "COMPLETED":
            last_productive["closure"] = last_productive["any"] = cid
        elif name == "mutation_scored" and cid:
            for line in payload.get("criteriaAssessments") or []:
                if "✓" not in line:
                    continue
                text = line.split("]", 1)[-1].strip()
                if text and text not in passed_criteria:
                    passed_criteria.add(text)
                    last_productive["criteria"] = last_productive["any"] = cid
        elif name == "cycle_end" and payload.get("resultState"):
            terminal = payload["resultState"]
        elif name == "system_paused":
            terminal = "SYSTEM_PAUSED"

    total = len(order)
    if total == 0:
        return None

    stamps = [first_ts[c] for c in order if first_ts.get(c)]
    end_ts = stamps[-1] if stamps else None
    row = {
        "run": run_id,
        "cycles": total,
        "merges": merges,
        "terminal": terminal,
        "tokens": sum(tokens.values()),
        "instrumented": bool(tokens),
        "duration_min": round((stamps[-1] - stamps[0]).total_seconds() / 60.0, 1)
        if len(stamps) > 1 else 0.0,
    }
    index = {cid: i + 1 for i, cid in enumerate(order)}

    for kind in PRODUCTIVITY_KINDS:
        cid = last_productive[kind]
        if cid is None:
            row[kind] = None
            continue
        k = index[cid]
        tail = order[k:]
        tail_tokens = sum(tokens.get(c, 0) for c in tail)
        pre_attempts = sum(attempts.get(c, 0) for c in order[:k])
        tail_attempts = sum(attempts.get(c, 0) for c in tail)
        pre_rate = pre_attempts / k if k else 0.0
        tail_rate = tail_attempts / len(tail) if tail else 0.0
        ratio = round(tail_rate / pre_rate, 3) if pre_rate else None
        if not tail:
            tail_class = "none"
        elif ratio is None:
            tail_class = "churn" if tail_rate else "idle"
        else:
            tail_class = "churn" if ratio >= CHURN_RATE_SHARE else "idle"
        row[kind] = {
            "peak_k": k,
            "peak_frac": round(k / total, 3),
            "tail_cycles": len(tail),
            "tail_frac": round(len(tail) / total, 3),
            "tail_min": round(max(0.0, (end_ts - first_ts[cid]).total_seconds() / 60.0), 1)
            if end_ts and first_ts.get(cid) else None,
            "tail_tokens": tail_tokens,
            "tail_token_frac": round(tail_tokens / row["tokens"], 3) if row["tokens"] else None,
            "tail_work_ratio": ratio,
            "tail_class": tail_class,
        }

    summary = read_summary(corpus) if corpus else {}
    for field in ("terminal", "smoke", "smoke_preserved", "preserved_source", "score_delta"):
        if field in summary:
            row["summary_" + field] = summary[field]
    return row


def score_delta_num(text):
    """The signed integer of a summary.txt score_delta, or None for an `n/a (...)` reading."""
    if not text:
        return None
    m = re.match(r"^([+-]?\d+)$", text.strip())
    return int(m.group(1)) if m else None


def report(rows, kind, skipped, out, table_limit=10):
    write = out.write
    write("T6-B cross-run drift aggregate — %d runs (productivity = %s)\n" % (len(rows), kind))
    if skipped:
        write("  %d input(s) skipped (unreadable or no cycles)\n" % skipped)
    have = [r for r in rows if r.get(kind)]
    if not have:
        write("  no run carried a productive event under this reading — nothing to distribute\n")
        return
    write("  runs with a productive event: %d/%d\n" % (len(have), len(rows)))

    peak = [r[kind]["peak_frac"] for r in have]
    tail_c = [r[kind]["tail_cycles"] for r in have]
    tail_f = [r[kind]["tail_frac"] for r in have]
    write("\nPeak position (k/N — 1.0 = stayed productive to the last cycle)\n")
    write("  p10 %.2f · median %.2f · p90 %.2f\n" % (
        percentile(peak, 0.1), median(peak), percentile(peak, 0.9)))
    write("\nTail length after the peak\n")
    write("  cycles: median %d · p75 %d · p90 %d · max %d\n" % (
        median(tail_c), percentile(tail_c, 0.75), percentile(tail_c, 0.9), max(tail_c)))
    write("  share of run: median %.2f · p75 %.2f · p90 %.2f · max %.2f\n" % (
        median(tail_f), percentile(tail_f, 0.75), percentile(tail_f, 0.9), max(tail_f)))
    for share in (0.10, 0.25, 0.50):
        n = sum(1 for f in tail_f if f > share)
        write("  runs spending >%2d%% of their cycles in the tail: %d/%d (%d%%)\n" % (
            share * 100, n, len(have), round(100 * n / len(have))))

    classes = collections.Counter(r[kind]["tail_class"] for r in have)
    write("\nTail character (run-relative: churn = attempting >=%d%% of its own pre-peak rate)\n"
          % (CHURN_RATE_SHARE * 100))
    for name in ("none", "idle", "churn"):
        if classes[name]:
            write("  %-5s %3d runs (%d%%)\n" % (name, classes[name],
                                                round(100 * classes[name] / len(have))))
    churn = [r for r in have if r[kind]["tail_class"] == "churn"]
    if churn:
        write("  churn tails — cycles: median %d · max %d\n" % (
            median([r[kind]["tail_cycles"] for r in churn]),
            max(r[kind]["tail_cycles"] for r in churn)))

    inst = [r for r in have if r["instrumented"] and r[kind]["tail_token_frac"] is not None]
    write("\nToken spend in the tail (token-instrumented runs only: %d/%d)\n" % (len(inst), len(have)))
    if inst:
        fracs = [r[kind]["tail_token_frac"] for r in inst]
        total_tokens = sum(r["tokens"] for r in inst)
        total_tail = sum(r[kind]["tail_tokens"] for r in inst)
        write("  share of a run's tokens: median %.3f · p75 %.3f · p90 %.3f · max %.3f\n" % (
            median(fracs), percentile(fracs, 0.75), percentile(fracs, 0.9), max(fracs)))
        write("  corpus total: %.2fM of %.2fM tokens (%.1f%%) spent after the last productive event\n"
              % (total_tail / 1e6, total_tokens / 1e6, 100 * total_tail / total_tokens
                 if total_tokens else 0.0))
        for share in (0.10, 0.25):
            n = sum(1 for f in fracs if f > share)
            write("  runs spending >%2d%% of tokens in the tail: %d/%d (%d%%)\n" % (
                share * 100, n, len(inst), round(100 * n / len(inst))))
    else:
        write("  none — no run in this batch carries llm_call token telemetry\n")

    deltas = [score_delta_num(r.get("summary_score_delta")) for r in rows]
    deltas = [d for d in deltas if d is not None]
    write("\nDual-score drift (corpora carrying a comparable score_delta: %d/%d)\n"
          % (len(deltas), len(rows)))
    if deltas:
        worse = sum(1 for d in deltas if d > 0)
        write("  ended worse than its best saved state: %d/%d · median delta %+d · max %+d\n" % (
            worse, len(deltas), median(deltas), max(deltas)))
    else:
        write("  none comparable — dual scoring needs both trees to build (T6-A)\n")

    write("\nBy terminal\n")
    by = collections.defaultdict(list)
    for r in have:
        by[r["terminal"] or "unrecorded"].append(r)
    for name, group in sorted(by.items(), key=lambda kv: -len(kv[1])):
        write("  %-16s n=%3d · tail cycles median %4d · tail share median %.2f · churn %d\n" % (
            name, len(group), median([r[kind]["tail_cycles"] for r in group]),
            median([r[kind]["tail_frac"] for r in group]),
            sum(1 for r in group if r[kind]["tail_class"] == "churn")))

    per_run_table(rows, kind, table_limit, out)

def per_run_table(rows, kind, limit, out):
    """The per-run table, longest tail first. limit=None lists every run."""
    have = sorted((r for r in rows if r.get(kind)), key=lambda r: -r[kind]["tail_cycles"])
    shown = have if limit is None else have[:limit]
    out.write("\nPer-run (longest tail first%s)\n" % ("" if limit is None else ", top %d" % limit))
    out.write("  %-44s %5s %6s %6s %6s %-6s %8s %s\n" % (
        "run", "N", "peak", "tail", "share", "class", "tail_tok", "terminal"))
    for r in shown:
        d = r[kind]
        out.write("  %-44s %5d %6d %6d %6.2f %-6s %8s %s\n" % (
            r["run"][:44], r["cycles"], d["peak_k"], d["tail_cycles"], d["tail_frac"],
            d["tail_class"],
            "%.3f" % d["tail_token_frac"] if d["tail_token_frac"] is not None else "n/a",
            r["terminal"] or "unrecorded"))
    if limit is not None and len(have) > limit:
        out.write("  ... %d more runs (--table lists all)\n" % (len(have) - limit))


def main(argv):
    ap = argparse.ArgumentParser(description="T6-B cross-run drift aggregator.")
    ap.add_argument("paths", nargs="+",
                    help="corpus dirs, behaviour*.ndjson files, or dirs to scan recursively")
    ap.add_argument("--kind", default=DEFAULT_KIND, choices=PRODUCTIVITY_KINDS,
                    help="which productive event defines the peak (default: %s)" % DEFAULT_KIND)
    ap.add_argument("--min-bytes", type=int, default=0,
                    help="skip behaviour files smaller than this (drops aborted runs)")
    ap.add_argument("--json", default=None, help="also write the per-run rows as JSON")
    ap.add_argument("--table", action="store_true",
                    help="list every run in the per-run table, not just the 10 longest tails")
    args = ap.parse_args(argv)

    rows, skipped = [], 0
    for run_id, behaviour, corpus in scan_inputs(args.paths, args.min_bytes):
        try:
            row = analyse_run(run_id, behaviour, corpus)
        except Exception as exc:  # one bad run must not lose the batch
            sys.stderr.write("drift_aggregate: skipped %s (%s)\n" % (run_id, exc))
            row = None
        if row is None:
            skipped += 1
        else:
            rows.append(row)

    report(rows, args.kind, skipped, sys.stdout, None if args.table else 10)
    if args.json:
        with open(args.json, "w") as fh:
            json.dump(rows, fh, indent=1)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except Exception as exc:  # a report is never worth failing a batch over
        sys.stderr.write("drift_aggregate: %s\n" % exc)
        sys.exit(0)
