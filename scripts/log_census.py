#!/usr/bin/env python3
"""T5-BL: which production log call sites have never fired, per target.

A deletion argument needs a firing count per MECHANISM, and the cheapest proxy the corpus holds is
the log call each mechanism makes. This lists every log.info/warn/error call site under
src/main/java, takes the literal text of its format string up to the first placeholder, and counts
the archived runs whose application log carries a TIMESTAMPED line from that class starting with
that text. Timestamped lines only: prompts echo earlier log text, and a phrase in a prompt is not a
firing (feedback-grep-the-behaviour-not-the-name).

Counts are per target, because a mechanism silent on the target it does not serve is correctly
self-gated, not dead. The output is a POOL to read, never a delete list.

Usage: scripts/log_census.py [--archive DIR] [--min-literal N] [--site PATTERN]
"""
import argparse
import collections
import gzip
import os
import re
import sys

LOG_CALL = re.compile(r'\blog\.(info|warn|error)\(\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)', re.S)
LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')
PLACEHOLDER = re.compile(r'\{\}|%[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]')
LINE = re.compile(r'^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d+ \[[^\]]*\] (?:INFO|WARN|ERROR)\s+(\S+) - (.*)$')


def call_sites(root, min_literal):
    sites = []
    for dirpath, _, files in os.walk(os.path.join(root, 'src/main/java')):
        for f in files:
            if not f.endswith('.java'):
                continue
            path = os.path.join(dirpath, f)
            src = open(path, encoding='utf-8').read()
            cls = f[:-5]
            for m in LOG_CALL.finditer(src):
                text = ''.join(LITERAL.findall(m.group(2)))
                # Only the escapes a format string uses; a codec-level unescape mangles UTF-8.
                text = text.replace('\\"', '"').replace('\\n', '\n').replace('\\t', '\t').replace('\\\\', '\\')
                # Both placeholder styles end the literal part: slf4j {} and String.formatted %s.
                prefix = PLACEHOLDER.split(text, maxsplit=1)[0]
                # The log line carries the message's FIRST line; later lines are unprefixed.
                prefix = prefix.split('\n', 1)[0]
                if len(prefix.strip()) < min_literal:
                    continue
                line = src[:m.start()].count('\n') + 1
                sites.append((cls, prefix, m.group(1).upper(), os.path.relpath(path, root) + ':' + str(line)))
    return sites


def run_target(run_dir):
    t = os.path.join(run_dir, 'target.txt')
    if os.path.exists(t):
        return open(t).read().strip().rsplit('/', 1)[-1] or '?'
    return '?'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--archive', default=os.path.expanduser('~/.autobuilder/run-archive'))
    ap.add_argument('--min-literal', type=int, default=12)
    ap.add_argument('--root', default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    ap.add_argument('--show-fired', action='store_true')
    args = ap.parse_args()

    sites = call_sites(args.root, args.min_literal)
    by_class = collections.defaultdict(list)
    for i, (cls, prefix, _, _) in enumerate(sites):
        by_class[cls].append((i, prefix))

    fired = collections.defaultdict(collections.Counter)   # site -> target -> runs
    runs_per_target = collections.Counter()
    for run in sorted(os.listdir(args.archive)):
        log = os.path.join(args.archive, run, 'autobuilder.log.gz')
        if not os.path.exists(log):
            continue
        target = run_target(os.path.join(args.archive, run))
        runs_per_target[target] += 1
        seen = set()
        with gzip.open(log, 'rt', errors='replace') as fh:
            for raw in fh:
                m = LINE.match(raw)
                if not m:
                    continue
                cls = m.group(1).rsplit('.', 1)[-1]
                cands = by_class.get(cls)
                if not cands:
                    continue
                msg = m.group(2)
                for i, prefix in cands:
                    if i not in seen and msg.startswith(prefix):
                        seen.add(i)
        for i in seen:
            fired[i][target] += 1

    targets = sorted(runs_per_target)
    print('runs per target:', dict(runs_per_target))
    print('call sites with a literal prefix >= %d chars: %d' % (args.min_literal, len(sites)))
    silent = [i for i in range(len(sites)) if not fired[i]]
    print('never fired in any run: %d' % len(silent))
    for i in silent:
        cls, prefix, level, where = sites[i]
        print('  %-5s %-60s %s' % (level, where, prefix[:90].replace('\n', ' ')))
    if args.show_fired:
        for i in range(len(sites)):
            if fired[i]:
                print('  fired', dict(fired[i]), sites[i][3])
    # Known-positive check: a census that finds nothing is broken, not clean.
    positives = sum(1 for i in range(len(sites)) if fired[i])
    print('sites fired at least once: %d' % positives)
    if positives == 0:
        sys.exit('no call site matched any run: the matcher is broken')


if __name__ == '__main__':
    main()
