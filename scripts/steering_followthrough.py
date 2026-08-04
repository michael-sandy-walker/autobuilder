#!/usr/bin/env python3
"""
steering_followthrough.py — does a steering block's advice actually get taken?

Attribution by outcome is hopeless for these blocks. The per-run loan-conversion delta has a
standard deviation of ~31 points (observed over the 10-run T6-L window, range -70 to +22), so a
flag-based A/B needs ~39 runs PER ARM to detect a 20-point effect and ~155 per arm for 10 points —
five days and nineteen days of continuous 90-minute runs. That experiment was recommended twice on
2026-08-04 and does not survive contact with the variance.

So measure the link the block actually claims, one step earlier and one level down: **when the
block names a specific defect, is that defect gone by the next compile probe?** That is per
FIRING rather than per run, so N is an order of magnitude larger and the run-level noise does not
enter. It is the same question `CompileFixInjector` already answers about itself when it logs
"rule=X removed its targeted diagnostic".

What this can and cannot establish. It shows whether the advice was FOLLOWED — the block named a
thing and the thing changed. It does not show that following it improved the run, and a high
follow-through rate beside a flat score would itself be worth knowing: it would mean the blocks
work and the theory behind them is wrong. Keeping those two claims apart is the whole point.

  MISSING MEMBER — ADD TO <T>.java   asked for `method m(args)`  → is `symbol: method m` gone?
  MISSING TYPE — CREATE …            asked for type `T`          → is `symbol: class T` gone?
  UNWRAPPED VALUE …                  named `<T>` disagreements    → is the T mismatch gone?

"Gone" is judged against the NEXT `T1-EC` probe after the firing. A firing with no subsequent
probe is reported as `unresolved` rather than counted either way — the run ended before the advice
could be tested, which is neither compliance nor refusal.

Usage:
  steering_followthrough.py [run-dir-glob ...]     default: /tmp/preflight_runs/*
  steering_followthrough.py --json
"""
import argparse
import collections
import glob
import json
import os
import re
import sys

PROBE = re.compile(r"T1-EC: probe result=(COMPILE_OK|COMPILE_ERROR)")

BLOCKS = {
    "missing-member": re.compile(r"MISSING MEMBER . ADD TO (\w+)\.java"),
    "missing-type": re.compile(r"MISSING TYPE . CREATE"),
    "unwrapped-value": re.compile(r"UNWRAPPED VALUE . FIX THE CALL"),
}

ASK_MEMBER = re.compile(r"^\s*-\s*(?:method|constructor)\s+(\w+)\(")
ASK_TYPE = re.compile(r"^\s*-\s*(\w+)\s+—\s+referenced by")
ASK_UNWRAPPED = re.compile(r"^\s*-\s*(?:\w+\(\) chained onto a plain (\w+)"
                           r"|a plain (\w+) supplied where)")

SYM_METHOD = re.compile(r"symbol:\s*method\s+(\w+)")
SYM_CLASS = re.compile(r"symbol:\s*class\s+(\w+)")
MISMATCH = re.compile(r"incompatible types:\s*[\w.]*?(\w+)\s+cannot be converted to")


def asked_for(block, lines, start):
    """The concrete things this firing named, read from the bullet list under the heading."""
    wanted = set()
    for j in range(start + 1, min(start + 12, len(lines))):
        line = lines[j]
        if line.startswith("---") and j > start + 1:
            break
        if block == "missing-member":
            m = ASK_MEMBER.match(line)
            if m:
                wanted.add(m.group(1))
        elif block == "missing-type":
            m = ASK_TYPE.match(line)
            if m:
                wanted.add(m.group(1))
        elif block == "unwrapped-value":
            m = ASK_UNWRAPPED.match(line)
            if m:
                wanted.add(m.group(1) or m.group(2))
    return wanted


def still_present(block, window):
    """Which of the named things the NEXT probe still complains about."""
    text = "\n".join(window)
    if block == "missing-member":
        return set(SYM_METHOD.findall(text))
    if block == "missing-type":
        return set(SYM_CLASS.findall(text))
    return set(MISMATCH.findall(text)) | set(SYM_METHOD.findall(text))


def analyse_run(path):
    try:
        with open(path, errors="replace") as fh:
            lines = fh.read().split("\n")
    except OSError:
        return []
    probe_at = [i for i, l in enumerate(lines) if PROBE.search(l)]
    out = []
    for i, line in enumerate(lines):
        for block, pattern in BLOCKS.items():
            if not pattern.search(line):
                continue
            wanted = asked_for(block, lines, i)
            if not wanted:
                continue
            nxt = next((p for p in probe_at if p > i), None)
            if nxt is None:
                out.append((block, wanted, None))
                continue
            window = lines[nxt:nxt + 60]
            out.append((block, wanted, wanted - still_present(block, window)))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("patterns", nargs="*", default=["/tmp/preflight_runs/*"])
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()

    tally = collections.defaultdict(lambda: {"firings": 0, "cleared": 0,
                                             "asked": 0, "asked_cleared": 0, "unresolved": 0})
    for pattern in (args.patterns or ["/tmp/preflight_runs/*"]):
        for d in sorted(glob.glob(pattern)):
            log = os.path.join(d, "autobuilder.log")
            if not os.path.isfile(log):
                continue
            for block, wanted, cleared in analyse_run(log):
                t = tally[block]
                t["firings"] += 1
                t["asked"] += len(wanted)
                if cleared is None:
                    t["unresolved"] += 1
                    continue
                t["asked_cleared"] += len(cleared)
                if cleared == wanted:
                    t["cleared"] += 1

    if args.json:
        json.dump(tally, sys.stdout, indent=1, sort_keys=True)
        print()
        return 0

    if not tally:
        print("no steering-block firing found in the corpus")
        return 0
    print("steering follow-through — was the named defect gone by the next probe?\n")
    print(f"{'block':<18}{'firings':>8}{'fully cleared':>15}{'items':>8}{'items cleared':>15}"
          f"{'untested':>10}")
    for block, t in sorted(tally.items()):
        testable = t["firings"] - t["unresolved"]
        rate = f"{t['cleared']}/{testable}" if testable else "-"
        irate = f"{t['asked_cleared']}/{t['asked']}" if t["asked"] else "-"
        print(f"{block:<18}{t['firings']:>8}{rate:>15}{t['asked']:>8}{irate:>15}"
              f"{t['unresolved']:>10}")
    print("\n'untested' = the run ended before another probe ran; neither compliance nor refusal.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
