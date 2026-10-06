#!/usr/bin/env python3
"""Prepares the archive replay of the completion verifier (T6-T): one folder per archived run with a declared suite
and a final tree, holding what a closure attempt on that tree would be asked, and what the tree really is.

  <out>/<run>/goal.txt        the goal the verifier is given (the logged verification prompt's GOAL, else the seed)
  <out>/<run>/criteria.txt    one criterion per line: the last logged verification prompt's REQUIREMENTS, else the
                              target's obligations file without its 'rule:' lines (a closure checks those uncovered)
  <out>/<run>/tree/           the final tree (artifact-src.tar.gz), with target/surefire-reports from a real
                              `mvn test` run in it (a run cut at --test-timeout leaves TIMEOUT)
  <out>/<run>/yardstick.txt   the yardstick's score of that tree (summary.txt smoke=)

ClosureVerificationReplayLiveTest re-decides each closure with the current verifier against the local model; a
completion on a tree the yardstick does not score in full is a false completion.

usage: closure_replay_prep.py --runs FILE --out DIR [--archive DIR] [--test-timeout S]
  FILE: one run id per line (first column)
"""
import argparse, gzip, os, re, shutil, subprocess, tarfile

GOAL = re.compile(r'^GOAL:\n(.*?)\n\nREQUIREMENTS:\n(.*?)\n\n', re.S | re.M)


def last_verification(log_path):
    """The GOAL and REQUIREMENTS of the last COMPLETION check: a prompt block followed by the completion log line --
    a subgoal's check prints the same block and must not be taken for the root's."""
    goal = crit = None; pending = None; buf = []; grab = False
    with gzip.open(log_path, 'rt', errors='replace') as f:
        for line in f:
            if 'T4-BP-3: completion verification LLM call' in line and pending:
                goal, crit = pending; pending = None; continue
            if line.startswith('GOAL:'):
                grab = True; buf = [line]; pending = None; continue
            if grab:
                buf.append(line)
                if line.startswith('CURRENT PROJECT FILES:') or line.startswith('SHOWN WHOLE') or len(buf) > 400:
                    grab = False
                    m = GOAL.search(''.join(buf))
                    if m:
                        pending = (m.group(1).strip(),
                                   [re.sub(r'^\d+\.\s*', '', l).strip() for l in m.group(2).split('\n') if re.match(r'^\d+\.', l)])
    return goal, crit


def case_of(run_dir):
    text = open(os.path.join(run_dir, 'target.txt')).read()
    m = re.search(r'toycase=(\S+)', text) or re.search(r'target=(\S+)', text)
    return m.group(1) if m else None


def main():
    a = argparse.ArgumentParser()
    a.add_argument('--runs', required=True); a.add_argument('--out', required=True)
    a.add_argument('--archive', default=os.path.expanduser('~/.autobuilder/run-archive'))
    a.add_argument('--data', default=os.path.join(os.path.dirname(__file__), '..', 'data'))
    a.add_argument('--test-timeout', type=int, default=180)
    o = a.parse_args()
    for line in open(o.runs):
        if not line.strip():
            continue
        run = line.split()[0]
        src = os.path.join(o.archive, run); dst = os.path.join(o.out, run)
        prepared = os.path.exists(os.path.join(dst, 'yardstick.txt'))
        case = case_of(src)
        goal, crit = last_verification(os.path.join(src, 'autobuilder.log.gz'))
        boot = os.path.join(o.data, case or '?', 'bootstrap')
        if not crit:
            ob = os.path.join(boot, 'seed.obligations.txt')
            if not os.path.exists(ob):
                print(f'{run}: no criteria'); continue
            crit = [re.sub(r'^gate: tests\s+', '', l.strip()) for l in open(ob)
                    if l.strip() and not l.startswith('#') and not l.startswith('rule:')]
        if not goal:
            goal = open(os.path.join(boot, 'seed.txt')).read().strip()
        os.makedirs(dst, exist_ok=True)
        open(os.path.join(dst, 'goal.txt'), 'w').write(goal + '\n')
        open(os.path.join(dst, 'criteria.txt'), 'w').write('\n'.join(crit) + '\n')
        tree = os.path.join(dst, 'tree')
        if prepared:            # the tree and its test run are kept; only the criteria are derived again
            print(f'{run} {case} criteria={len(crit)} (tree kept)', flush=True)
            continue
        shutil.rmtree(tree, ignore_errors=True); os.makedirs(tree)
        with tarfile.open(os.path.join(src, 'artifact-src.tar.gz')) as t:
            t.extractall(tree)
        try:
            subprocess.run(['mvn', '-q', '-o', 'test', '-Dsurefire.printSummary=false'], cwd=tree, timeout=o.test_timeout,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except subprocess.TimeoutExpired:
            open(os.path.join(tree, 'TIMEOUT'), 'w').write('the suite did not finish\n')
        smoke = [l.split('=', 1)[1].strip() for l in open(os.path.join(src, 'summary.txt')) if l.startswith('smoke=')]
        open(os.path.join(dst, 'yardstick.txt'), 'w').write((smoke[0] if smoke else '?') + '\n')
        print(f'{run} {case} {smoke[0] if smoke else "?"} criteria={len(crit)}', flush=True)


if __name__ == '__main__':
    main()
