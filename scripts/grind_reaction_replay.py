#!/usr/bin/env python3
"""T4-DY slice 1 (the gate): after the loop noticed a grind, what reaction followed, and did the grind stop?

For every archived run with a `grind_detected` event: each failure signature, when it was first
noticed (its third occurrence) and last noticed (the notices come at 3, 6, 12, ... so the last one is
a lower bound on its count), the reactions between the first notice and the end of the run -- a
change of subgoal, a planning run, a rewind, a merge -- and whether the signature was STILL being
noticed in the run's last quarter (a grind that outlived every reaction).

Offline, read-only; reads ~/.autobuilder/run-archive/<run>/behaviour.ndjson.gz and summary.txt.
Usage: grind_reaction_replay.py [--runs N]   (default: every run that has a grind event)
"""
import collections
import gzip
import json
import os
import re
import sys

ARCHIVE = os.path.expanduser('~/.autobuilder/run-archive')


def events(path):
    s = gzip.open(path, 'rt', errors='replace').read()
    dec, i = json.JSONDecoder(), 0
    while True:
        while i < len(s) and s[i].isspace():
            i += 1
        if i >= len(s):
            return
        try:
            o, i = dec.raw_decode(s, i)
        except ValueError:
            return
        yield o


def main():
    rows, totals = [], collections.Counter()
    for run in sorted(os.listdir(ARCHIVE)):
        f = os.path.join(ARCHIVE, run, 'behaviour.ndjson.gz')
        if not os.path.exists(f):
            continue
        ev = list(events(f))
        if not any(e.get('event') == 'grind_detected' for e in ev):
            continue
        summ = open(os.path.join(ARCHIVE, run, 'summary.txt')).read() if os.path.exists(os.path.join(ARCHIVE, run, 'summary.txt')) else ''
        m = re.search(r'terminal=(\S+)', summ)
        terminal = (m.group(1) if m else '?').split(':')[0]
        cycles = [e.get('cycleId') for e in ev if e.get('event') == 'cycle_start']
        pos = {c: i for i, c in enumerate(cycles)}
        n = len(cycles)
        subgoal_at, last_sub = [], None
        for e in ev:
            if e.get('event') == 'cycle_start':
                sg = (e.get('payload') or {}).get('currentSubGoalId')
                subgoal_at.append(sg != last_sub and last_sub is not None)
                last_sub = sg
        sigs = collections.OrderedDict()
        for e in ev:
            if e.get('event') != 'grind_detected':
                continue
            p = e.get('payload') or {}
            s = sigs.setdefault(p.get('signature'), {'first': pos.get(e.get('cycleId'), 0), 'last': 0, 'count': 0})
            s['last'] = pos.get(e.get('cycleId'), s['last'])
            s['count'] = max(s['count'], p.get('occurrences') or 0)
        for sig, s in sigs.items():
            after = [e for e in ev if e.get('event') in ('planning_run', 'rewind_performed', 'mutation_merged', 'cycle_start')
                     and pos.get(e.get('cycleId'), -1) >= s['first']]
            reactions = collections.Counter(e['event'] for e in after if e['event'] != 'cycle_start')
            switches = sum(1 for i in range(s['first'], n) if i < len(subgoal_at) and subgoal_at[i])
            outlived = n > 0 and s['last'] >= 0.75 * n and s['count'] >= 6
            rows.append((run, terminal, sig[:90], s['count'], s['first'], s['last'], n, switches,
                         reactions.get('planning_run', 0), reactions.get('rewind_performed', 0),
                         reactions.get('mutation_merged', 0), outlived))
            totals['signatures'] += 1
            totals['outlived' if outlived else 'faded'] += 1
            if outlived:
                totals['outlived_in_unfulfilled' if terminal != 'GOAL_FULFILLED' else 'outlived_in_fulfilled'] += 1
    for r in rows:
        print(f"{r[0]} {r[1]:<14} x{r[3]:<3} cycles {r[4]}-{r[5]}/{r[6]}  after first notice: "
              f"{r[7]} subgoal switches, {r[8]} replans, {r[9]} rewinds, {r[10]} merges  "
              f"{'OUTLIVED' if r[11] else 'faded'}  | {r[2]}")
    print(dict(totals))


if __name__ == '__main__':
    main()
