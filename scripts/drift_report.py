#!/usr/bin/env python3
"""
drift_report.py — per-run drift overview for the T6-A dual score.

The point of scoring both the final files and the best saved (preserved/reconstructed) version
is the GAP between them: when the preserved score beats the final score, the run drifted DOWNHILL
from a better state it already had on disk. This script quantifies that per run so a batch of runs
can later be researched for systematic drift:

  score_delta        preserved - final  (positive = the run ended WORSE than its best saved state)
  run_duration_min   wall-clock span of the run
  total_cycles       cycles executed
  peaked_at_cycle    the best saved cycle and its position (k/N) in the run
  drift_tail_cycles  cycles executed AFTER the peak (the stretch spent drifting)
  drift_tail_min     wall-clock minutes after the peak

Reads the corpus's autobuilder.log (ordered `CYCLE cycle-<id>` lines carry timestamps) and, for a
loop-preserved run, meta/preserved-artifact-provenance.json for the peak cycle. Scores are passed
in (the caller has them). Emits key=value lines; missing inputs degrade to `n/a`, never throw.

Usage: drift_report.py <corpus> --final <smoke> --preserved <smoke_preserved> --source <preserved_source>
"""
import argparse
import datetime as dt
import json
import os
import re
import sys

CYCLE_RE = re.compile(r"^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+).*CYCLE (cycle-\d+)")
SCORE_RE = re.compile(r"SMOKE:\s*(\d+)/(\d+)")


def score_num(text):
    """The pass count from a 'SMOKE: n/23' line, or None for a failure token / missing."""
    if not text:
        return None
    m = SCORE_RE.search(text)
    return int(m.group(1)) if m else None


def parse_cycles(applog):
    """Ordered [(datetime, cycle_id)] from the log, first occurrence of each cycle id."""
    out, seen = [], set()
    if not os.path.isfile(applog):
        return out
    with open(applog, errors="replace") as fh:
        for line in fh:
            m = CYCLE_RE.match(line)
            if not m:
                continue
            cid = m.group(2)
            if cid in seen:
                continue
            seen.add(cid)
            try:
                ts = dt.datetime.strptime(m.group(1), "%Y-%m-%d %H:%M:%S.%f")
            except ValueError:
                continue
            out.append((ts, cid))
    return out


def peak_cycle(corpus, source):
    """The best-saved cycle id: from a reconstructed source token, else loop-preserved provenance."""
    if source and source.startswith("reconstructed:"):
        return source.split(":", 1)[1]
    if source == "loop-preserved":
        prov = os.path.join(corpus, "preserved-artifact-provenance.json")
        if os.path.isfile(prov):
            try:
                with open(prov) as fh:
                    return json.load(fh).get("fromCycle")
            except (OSError, ValueError):
                return None
    return None


def main(argv):
    ap = argparse.ArgumentParser(description="T6-A per-run drift overview.")
    ap.add_argument("corpus")
    ap.add_argument("--final", default="")
    ap.add_argument("--preserved", default="")
    ap.add_argument("--source", default="")
    args = ap.parse_args(argv)

    fields = {}
    fnum, pnum = score_num(args.final), score_num(args.preserved)
    if fnum is not None and pnum is not None:
        fields["score_delta"] = "%+d" % (pnum - fnum)  # + => final worse than best saved (drift)
    else:
        fields["score_delta"] = "n/a (%s vs %s)" % (args.final or "?", args.preserved or "?")

    cycles = parse_cycles(os.path.join(args.corpus, "autobuilder.log"))
    if cycles:
        start, end = cycles[0][0], cycles[-1][0]
        fields["run_duration_min"] = "%.1f" % ((end - start).total_seconds() / 60.0)
        fields["total_cycles"] = str(len(cycles))
        best = peak_cycle(args.corpus, args.source)
        idx = next((i for i, (_, cid) in enumerate(cycles) if cid == best), None)
        if best and idx is not None:
            fields["peaked_at_cycle"] = "%s (%d/%d)" % (best, idx + 1, len(cycles))
            fields["drift_tail_cycles"] = str(len(cycles) - idx - 1)
            fields["drift_tail_min"] = "%.1f" % ((end - cycles[idx][0]).total_seconds() / 60.0)
        else:
            # peak cycle not in this run's log (e.g. reconstructed from a cross-run snapshot)
            fields["peaked_at_cycle"] = "%s (not-in-run-log)" % (best or "unknown")
            fields["drift_tail_cycles"] = "n/a"
            fields["drift_tail_min"] = "n/a"
    else:
        for k in ("run_duration_min", "total_cycles", "peaked_at_cycle",
                  "drift_tail_cycles", "drift_tail_min"):
            fields[k] = "n/a"

    for k in ("score_delta", "run_duration_min", "total_cycles",
              "peaked_at_cycle", "drift_tail_cycles", "drift_tail_min"):
        print("%s=%s" % (k, fields[k]))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except Exception as e:  # never break the preflight summary
        sys.stderr.write("drift_report: %s\n" % e)
        print("score_delta=n/a")
        sys.exit(0)
