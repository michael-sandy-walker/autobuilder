#!/usr/bin/env python3
"""bring_inputs.py -- T4-EC: post a plan of inputs to a held application, apply its scripted decisions, release the run.

  scripts/bring_inputs.py <plan.json> [--port 8081] [--goal-file FILE] [--no-start]

The plan: {"inputs": [{"label", "runId", "dir", "intent", "runs"}...], runs on the module that runs, "decisions": [{"match": "<words of the subject>", "decide": "input|receiving"}...]}
--goal-file first sets the goal of a NEW project. A conflict no decision matches stays open, is printed, and fails the
call: an open conflict refuses closure, so it should not pass unnoticed.
"""
import argparse
import json
import sys
import urllib.error
import urllib.request


def call(base, path, body=None, method='POST', timeout=1800, text=None):
    data = text.encode() if text is not None else (json.dumps(body).encode() if body is not None else b'')
    kind = 'text/plain' if text is not None else 'application/json'
    req = urllib.request.Request(base + path, data=data if method == 'POST' else None, headers={'Content-Type': kind}, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('plan')
    ap.add_argument('--port', default='8081')
    ap.add_argument('--goal-file')
    ap.add_argument('--no-start', action='store_true')
    a = ap.parse_args()
    base = f'http://localhost:{a.port}/autobuilder'
    plan = json.load(open(a.plan))
    if a.goal_file:
        print('goal', call(base, '/goal', text=open(a.goal_file).read()).get('goalId'), flush=True)
    for i in plan['inputs']:
        body = {k: i.get(k) for k in ('label', 'runId', 'dir', 'intent', 'runs') if k in i or k != 'runs'} | {'addedBy': 'operator'}
        r = call(base, '/inputs', body)
        print(i['label'], 'corrections', r['corrections'], 'unmet', r['unmet'], 'conflicts', [c['subject'] for c in r['conflicts']], flush=True)
    left = []
    for c in call(base, '/inputs/conflicts', method='GET'):
        rule = next((d for d in plan.get('decisions', []) if d['match'].lower() in c['subject'].lower()), None)
        if rule is None:
            left.append(c['subject'])
            continue
        call(base, '/inputs/conflicts/' + c['id'], {'decision': rule['decide'], 'by': 'operator (scripted)'}, timeout=300)
        print('decided', rule['decide'], '|', c['subject'], flush=True)
    if not a.no_start:
        print(call(base, '/start?mode=ADOPT_PROJECT', timeout=60).get('message'), flush=True)
    if left:
        sys.exit('open conflicts, nothing decides them: ' + '; '.join(left))


if __name__ == '__main__':
    try:
        main()
    except urllib.error.HTTPError as e:
        sys.exit(f'refused: {e.code} {e.read().decode()[:400]}')
