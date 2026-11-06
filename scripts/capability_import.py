#!/usr/bin/env python3
"""capability_import.py -- T4-EI: a capability import driven through a held application's endpoints, step by step.

  capability_import.py discover <source dir> [--label L] [--port 8081]     what the source can do; prints the list
  capability_import.py choose <selection> <choices.json> [--port 8081]     the operator's choices; prints the preview
  capability_import.py import <selection> [--no-start] [--port 8081]       lands, keeps, releases (ADOPT_PROJECT)

choices.json maps a discovered capability's id to its choice, written AFTER reading the list discover printed:
  {"c1": {"mode": "code", "description": "...", "reference": false}, "c3": {"mode": "adapt", ...}}
A capability not named is left out. An open conflict after the import stops the script: decide it, then keep by hand.
"""
import argparse
import json
import sys
import urllib.error
import urllib.request


def call(base, path, body=None, method='POST', timeout=1800):
    data = json.dumps(body).encode() if body is not None else b''
    req = urllib.request.Request(base + path, data=data if method == 'POST' else None,
                                 headers={'Content-Type': 'application/json'}, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        sys.exit(f'{method} {path}: HTTP {e.code} {e.read().decode(errors="replace")}')


def show_preview(p):
    print('problems:', p['problems'] or 'none')
    for f in p['files']:
        print('  placed', f, '<-', ', '.join(p['provenance'].get(f, [])))
    for title, key in (('cut out', 'cutOut'), ('shared', 'shared'), ('unattributed', 'unattributed'), ('relies on', 'dependencies')):
        for x in p[key]:
            print(f'  {title}: {x}')
    print('intent:\n' + p['intent'])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('step', choices=['discover', 'choose', 'import'])
    ap.add_argument('args', nargs='*')
    ap.add_argument('--label')
    ap.add_argument('--port', default='8081')
    ap.add_argument('--no-start', action='store_true')
    a = ap.parse_args()
    base = f'http://localhost:{a.port}/autobuilder'
    if a.step == 'discover':
        import os
        src = os.path.abspath(a.args[0])
        label = a.label or os.path.basename(src.rstrip('/'))
        s = call(base, '/capability-imports', {'label': label, 'runId': None, 'dir': src, 'addedBy': 'operator (scripted)'})
        print('selection', s['id'], '--', len(s['capabilities']), 'capabilities,', len(s['probes']), 'probes')
        for c in s['capabilities']:
            print(f"\n{c['id']}: {c['name']} -- {c['summary']}")
            print('   declarations:', ', '.join(c['declarations']))
            print('   dependencies:', ', '.join(c['dependencies']) or 'none')
            print('   reference proposed:', c['reference'], '--', c.get('referenceWhy'))
        if s['unknown']:
            print('\nunknown declarations dropped:', ', '.join(s['unknown']))
    elif a.step == 'choose':
        made = json.load(open(a.args[1]))
        choices = [{'capability': k, 'mode': v['mode'], 'description': v.get('description', ''),
                    'reference': bool(v.get('reference'))} for k, v in made.items()]
        s = call(base, '/capability-imports/' + a.args[0] + '/choices', choices)
        show_preview(s['preview'])
    else:
        r = call(base, '/capability-imports/' + a.args[0] + '/import')
        print('input', r['inputId'], 'placed', r['placed'], 'conflicts', [c['subject'] for c in r['conflicts']], 'unmet', r['unmet'])
        open_ = call(base, '/inputs/conflicts', method='GET')
        if open_:
            sys.exit('open conflicts -- decide them, then keep the import: ' + ', '.join(c['subject'] for c in open_))
        call(base, '/inputs/' + r['inputId'] + '/keep?by=operator%20(scripted)', timeout=120)
        print('kept')
        if not a.no_start:
            call(base, '/start?mode=ADOPT_PROJECT', timeout=120)
            print('released: ADOPT_PROJECT')


if __name__ == '__main__':
    main()
