#!/usr/bin/env python3
"""T6-U: score what the loop's DerivedTests froze (meta/derived-tests.json) against trees whose answer is known.

Every written claim test -- accepted (frozen) or not -- is run on the ticket's real implementation (decides) and on
the other reference trees. The rule fixed before the measurement: zero harmful tests frozen (failing on the real
implementation AND every other reference), and at least half of each case's marked requirements answered.

Usage: score_frozen.py <derived-tests.json> <real-tree>[,<more>] [--out results.json]
"""
import argparse, json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
from derived_test_replay import run_one  # noqa: E402


def main():
    a = argparse.ArgumentParser()
    a.add_argument('entries'); a.add_argument('refs'); a.add_argument('--out')
    o = a.parse_args()
    refs = o.refs.split(',')
    rows = []
    for e in json.load(open(o.entries)):
        if not e.get('path'):
            continue
        res = [run_one(r, e['path'], e['content']) for r in refs]
        harmful = res[0] != 'PASS' and all(x != 'PASS' for x in res[1:])
        rows.append({'requirement': e['requirement'], 'claim': e['claim'], 'status': e['status'], 'real': res[0],
                     'others_pass': sum(x == 'PASS' for x in res[1:]), 'harmful': harmful, 'reason': e['reason']})
        print(f"{e['status']:10} real={res[0]:9} others={rows[-1]['others_pass']}/{len(res) - 1} "
              f"{'HARMFUL ' if harmful else ''}{e['claim'][:90]}", flush=True)
    frozen = [r for r in rows if r['status'] == 'ACCEPTED']
    reqs = {r['requirement'] for r in rows}
    answered = {q for q in reqs if all(r['status'] == 'ACCEPTED' for r in rows if r['requirement'] == q)}
    print(f"\nwritten {len(rows)}, frozen {len(frozen)}, frozen wrong on the real implementation "
          f"{sum(r['real'] != 'PASS' for r in frozen)}, frozen harmful {sum(r['harmful'] for r in frozen)}; "
          f"right but not frozen {sum(r['real'] == 'PASS' and r['status'] != 'ACCEPTED' for r in rows)}; "
          f"requirements with a frozen test {len({r['requirement'] for r in frozen})}/{len(reqs)}, "
          f"answered by the gate {len(answered)}/{len(reqs)}")
    if o.out:
        json.dump(rows, open(o.out, 'w'), indent=1)


if __name__ == '__main__':
    main()
