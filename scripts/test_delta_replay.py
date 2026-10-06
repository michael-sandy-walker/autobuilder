#!/usr/bin/env python3
"""Replays the archived runs' evaluations against the tree each candidate would have replaced, by the project's
own test run (T6-T): how often a candidate the operator's suite shows BETTER was rejected, and how often a WORSE
one was merged.

The verdicts are the ones the loop records itself since T6-T (the test_delta event, TestRunGate.compareWithTree):
BETTER -- the tree's tests had never run on a merged tree (they did not compile) and the candidate's ran, or fewer
fail and none that passed breaks; WORSE -- more fail; SAME -- as many, or a trade. A comparison whose failing list
was cut in the log ("... more failing, not listed") is counted apart, never guessed.

Read from each run's autobuilder log: the evaluator's PROJECT TEST RUN block (the candidate's run) and the cycle's
decision line. Only runs that show that block (a declared suite) count. Logs are read line by line: whole logs
held in memory killed a scan once (2026-10-06).

usage: test_delta_replay.py [--archive DIR] [--case NAME] [--list]
"""
import argparse, collections, gzip, os, re

HDR = "PROJECT TEST RUN (the project's own suite, run on exactly these files):"
CNT = re.compile(r'^\s+(\d+) run, (\d+) failed, (\d+) errors')
NAME = re.compile(r'^    (\w+\.[\w$]+(?:\([^)]*\))?(?:\[\d+\])?) -- ')
CYC = re.compile(r'CycleLoggingService - CYCLE (cycle-\d+) .* mut=(ACCEPTED|REJECTED)')
TS = re.compile(r'^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)')


def case_of(run_dir):
    p = os.path.join(run_dir, 'target.txt')
    text = open(p).read() if os.path.exists(p) else ''
    m = re.search(r'toycase=(\S+)', text) or re.search(r'target=(\S+)', text)
    return m.group(1) if m else '?'


def verdict(cand, tree):
    """cand / tree: (failing count, failing names, list complete); tree None: its tests never ran."""
    if tree is None:
        return 'BETTER'
    if not (cand[2] and tree[2]):
        return 'CUT'
    newly_failing = cand[1] - tree[1]
    if cand[0] > tree[0]:
        return 'WORSE'
    return 'BETTER' if cand[0] < tree[0] and not newly_failing else 'SAME'


def replay(log_path):
    """Yields (time, verdict, decision) per evaluated candidate; None when the run shows no declared suite."""
    tree = None; cand = None; mode = None; seen = False; ts = None; out = []
    with gzip.open(log_path, 'rt', errors='replace') as f:
        for line in f:
            line = line.rstrip('\n')
            m = TS.match(line)
            if m:
                ts = m.group(1)
            if mode == 'count':
                c = CNT.match(line)
                mode = 'names' if c else None
                if c:
                    cand = [int(c.group(2)) + int(c.group(3)), set(), True]
                continue
            if mode == 'names':
                if line.startswith('  Failing:'):
                    continue
                n = NAME.match(line)
                if n:
                    cand[1].add(n.group(1)); continue
                if 'more failing, not listed' in line:
                    cand[2] = False; continue
                mode = None
            if line == HDR:
                mode = 'count'; seen = True; continue
            c = CYC.search(line)
            if c:
                if cand is not None:
                    out.append((ts, verdict(cand, tree), c.group(2)))
                    if c.group(2) == 'ACCEPTED':
                        tree = cand
                cand = None
    return out if seen else None


def main():
    a = argparse.ArgumentParser()
    a.add_argument('--archive', default=os.path.expanduser('~/.autobuilder/run-archive'))
    a.add_argument('--case')
    a.add_argument('--list', action='store_true', help='list each run with a better-rejected or worse-merged candidate')
    o = a.parse_args()
    total = collections.Counter(); per_case = collections.defaultdict(collections.Counter); listed = []
    for r in sorted(os.listdir(o.archive)):
        d = os.path.join(o.archive, r); log = os.path.join(d, 'autobuilder.log.gz')
        if not os.path.exists(log):
            continue
        case = case_of(d)
        if o.case and case != o.case:
            continue
        rows = replay(log)
        if rows is None:
            continue
        total['runs'] += 1; per_case[case]['runs'] += 1
        hits = []
        for ts, v, decision in rows:
            key = f'{v} {decision}'
            total[key] += 1; per_case[case][key] += 1
            if key in ('BETTER REJECTED', 'WORSE ACCEPTED'):
                hits.append(f'{ts[11:16]} {key}')
        if hits:
            listed.append((r, case, hits))
    print(f"runs with a declared suite: {total['runs']}")
    for k in sorted(k for k in total if k != 'runs'):
        print(f'  {k:18} {total[k]}')
    print('per case (better rejected / worse merged / runs):')
    for c, v in sorted(per_case.items()):
        print(f"  {c:28} {v['BETTER REJECTED']:4} {v['WORSE ACCEPTED']:4} {v['runs']:4}")
    if o.list:
        for r, c, hits in listed:
            print(f'  {r} {c}: ' + '; '.join(hits))


if __name__ == '__main__':
    main()
