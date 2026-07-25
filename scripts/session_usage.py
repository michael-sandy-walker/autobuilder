#!/usr/bin/env python3
"""
session_usage.py — token spend of the assistant's own sessions, read from local transcripts.

For pacing a self-paced autonomous loop: before starting another iteration, check what the current
usage window has already cost. Claude Code writes a JSONL transcript per session under
`~/.claude/projects/<slug>/`, and each assistant turn carries a `message.usage` block, so this needs
no external tool and no network.

WHAT THIS CANNOT DO: the account's plan limit is not in the transcripts, so nothing here reports
"% of limit". It reports spend and burn rate; pass `--limit` if you know your cap and want a
percentage. Windows are the rolling 5-hour blocks usage limits are assessed over: a window opens at
the first turn after a >5h gap and runs 5 hours.

Cache reads are counted separately from fresh input because they are billed at a small fraction of
the input rate — lumping them in overstates spend several-fold on a long session (this project's
sessions read >280k cached tokens per turn).

Usage:
  session_usage.py                    current window + today, this project
  session_usage.py --all-projects     every project's transcripts
  session_usage.py --days 7           per-day totals
  session_usage.py --limit 500000     add a percent-of-limit column for the current window
  session_usage.py --json
Exit 0 always; missing or unreadable transcripts degrade to zeros.
"""
import argparse
import datetime as dt
import glob
import json
import os
import sys

WINDOW_HOURS = 5
TRANSCRIPT_ROOT = os.path.expanduser("~/.claude/projects")
# Slug Claude Code derives from the PROJECT ROOT path (this file lives one level down, in scripts/).
PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
THIS_PROJECT = "-" + PROJECT_ROOT.lstrip("/").replace("/", "-")


def transcripts(all_projects):
    if all_projects:
        return sorted(glob.glob(os.path.join(TRANSCRIPT_ROOT, "*", "*.jsonl")))
    own = sorted(glob.glob(os.path.join(TRANSCRIPT_ROOT, THIS_PROJECT, "*.jsonl")))
    if own:
        return own
    # A moved or differently-slugged checkout: match any project dir ending in this basename.
    tail = "-" + os.path.basename(PROJECT_ROOT)
    return sorted(p for d in glob.glob(os.path.join(TRANSCRIPT_ROOT, "*" + tail))
                  for p in glob.glob(os.path.join(d, "*.jsonl")))


def parse_ts(text):
    if not text:
        return None
    try:
        return dt.datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        return None


def load_turns(paths):
    """[{ts, model, input, cache_write, cache_read, output}] deduped across transcripts.

    A turn can appear in more than one transcript (resumed or forked sessions), so identity is the
    message id plus request id — counting a turn twice would inflate the window.
    """
    seen, turns = set(), []
    for path in paths:
        try:
            fh = open(path, errors="replace")
        except OSError:
            continue
        with fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                try:
                    rec = json.loads(line)
                except ValueError:
                    continue
                msg = rec.get("message") or {}
                usage = msg.get("usage")
                if not isinstance(usage, dict):
                    continue
                key = (msg.get("id"), rec.get("requestId"))
                if key != (None, None):
                    if key in seen:
                        continue
                    seen.add(key)
                ts = parse_ts(rec.get("timestamp"))
                if ts is None:
                    continue
                turns.append({
                    "ts": ts,
                    "model": msg.get("model") or "unknown",
                    "input": usage.get("input_tokens") or 0,
                    "cache_write": usage.get("cache_creation_input_tokens") or 0,
                    "cache_read": usage.get("cache_read_input_tokens") or 0,
                    "output": usage.get("output_tokens") or 0,
                })
    turns.sort(key=lambda t: t["ts"])
    return turns


def windows(turns):
    """Group turns into rolling 5-hour blocks; a gap longer than the window opens a new one."""
    out = []
    for turn in turns:
        if out and (turn["ts"] - out[-1][0]["ts"]).total_seconds() <= WINDOW_HOURS * 3600 \
                and (turn["ts"] - out[-1][0]["ts"]).total_seconds() >= 0:
            out[-1].append(turn)
        else:
            out.append([turn])
    return out


def totals(turns):
    keys = ("input", "cache_write", "cache_read", "output")
    agg = {k: sum(t[k] for t in turns) for k in keys}
    # Fresh input + output is the spend that is billed at full rate; cache reads are a fraction.
    agg["full_rate"] = agg["input"] + agg["cache_write"] + agg["output"]
    agg["all_tokens"] = agg["full_rate"] + agg["cache_read"]
    agg["turns"] = len(turns)
    return agg


def fmt(n):
    if n >= 1_000_000:
        return "%.2fM" % (n / 1e6)
    if n >= 1_000:
        return "%.1fk" % (n / 1e3)
    return str(n)


def report(turns, args, out):
    write = out.write
    if not turns:
        write("session_usage: no transcripts with usage data found\n")
        return
    now = dt.datetime.now(dt.timezone.utc)
    blocks = windows(turns)
    current = blocks[-1]
    opened = current[0]["ts"]
    age_min = (now - opened).total_seconds() / 60.0
    remaining_min = max(0.0, WINDOW_HOURS * 60 - age_min)
    agg = totals(current)

    write("CURRENT USAGE WINDOW (rolling %dh block)\n" % WINDOW_HOURS)
    write("  opened %s · %.0f min ago · ~%.0f min left in the block\n"
          % (opened.astimezone().strftime("%H:%M"), age_min, remaining_min))
    write("  turns %d · full-rate %s (fresh input %s + cache writes %s + output %s)\n"
          % (agg["turns"], fmt(agg["full_rate"]), fmt(agg["input"]),
             fmt(agg["cache_write"]), fmt(agg["output"])))
    write("  cache reads %s (billed at a small fraction — kept separate on purpose)\n"
          % fmt(agg["cache_read"]))
    if age_min > 1:
        write("  burn rate %s full-rate tokens/min · %s output/min\n"
              % (fmt(int(agg["full_rate"] / age_min)), fmt(int(agg["output"] / age_min))))
    if args.limit:
        pct = 100.0 * agg["full_rate"] / args.limit
        write("  against --limit %s: %.1f%% used\n" % (fmt(args.limit), pct))
        if remaining_min > 0 and pct < 100:
            write("  at this rate the window ends at ~%.0f%% of that limit\n"
                  % (pct * (age_min + remaining_min) / age_min if age_min > 1 else pct))

    today = [t for t in turns if t["ts"].astimezone().date() == now.astimezone().date()]
    tagg = totals(today)
    write("\nTODAY  turns %d · full-rate %s · cache reads %s\n"
          % (tagg["turns"], fmt(tagg["full_rate"]), fmt(tagg["cache_read"])))

    if args.days:
        write("\nPER DAY (last %d)\n" % args.days)
        by_day = {}
        for t in turns:
            by_day.setdefault(t["ts"].astimezone().date(), []).append(t)
        for day in sorted(by_day)[-args.days:]:
            d = totals(by_day[day])
            write("  %s  turns %4d · full-rate %8s · output %7s\n"
                  % (day, d["turns"], fmt(d["full_rate"]), fmt(d["output"])))

    models = {}
    for t in current:
        models.setdefault(t["model"], []).append(t)
    if len(models) > 1:
        write("\nCURRENT WINDOW BY MODEL\n")
        for name, group in sorted(models.items(), key=lambda kv: -totals(kv[1])["full_rate"]):
            write("  %-24s full-rate %8s · turns %d\n"
                  % (name, fmt(totals(group)["full_rate"]), len(group)))


def main(argv):
    ap = argparse.ArgumentParser(description="Token spend from local Claude Code transcripts.")
    ap.add_argument("--all-projects", action="store_true")
    ap.add_argument("--days", type=int, default=0, help="also show per-day totals")
    ap.add_argument("--limit", type=int, default=0,
                    help="your full-rate token cap per window, if you know it")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)

    turns = load_turns(transcripts(args.all_projects))
    if args.json:
        blocks = windows(turns)
        payload = {"window": totals(blocks[-1]) if blocks else {},
                   "window_opened": blocks[-1][0]["ts"].isoformat() if blocks else None,
                   "all_time": totals(turns)}
        print(json.dumps(payload, indent=1))
        return 0
    report(turns, args, sys.stdout)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except Exception as exc:  # pacing info must never break the session it is pacing
        sys.stderr.write("session_usage: %s\n" % exc)
        sys.exit(0)
