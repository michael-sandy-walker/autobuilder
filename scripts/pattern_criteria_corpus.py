#!/usr/bin/env python3
"""The labelled corpus of PATTERN criteria (T3-CB): every distinct acceptance criterion in the graph
whose endpoint names its input as a pattern (`?name={var}`), labelled by the status it asserts.

Criteria are read from `CriterionResult.criterionText` -- where criteria live, not the logs, which
carry re-renders and quotations (T3-CC's measurement). Work items quoting other criteria
(`[BUILD_FIX]`) are excluded. The label is read with the criterion parser's own grammar
(BehaviouralCriterionParser: a verb-qualified path; a status introduced by a marker):
  success  every asserted status is in 200-299     error  some asserted status is outside it
  none     no status is asserted
`parses` says whether the parser reads exactly one endpoint and exactly one status -- the only
criteria a rule built on it can judge.

Usage: pattern_criteria_corpus.py [out.tsv]     (reads the running `neo4j` container)
Columns: label, status (or -), parses (yes/no), text. Sorted by text, so a re-export diffs cleanly.
"""
import json
import re
import subprocess
import sys

ENDPOINT = re.compile(r'\b(GET|POST|PUT|PATCH|DELETE)\s+(/[A-Za-z0-9_\-{}/.?=&]*)')
STATUS = re.compile(r'\b(?:HTTP|status(?:\s+code)?|returns?|answers?|responds?\s+with|→)\s+(\d{3})\b', re.I)


def normalize(p):
    p = p.strip().split('?', 1)[0]
    if not p:
        return None
    p = re.sub('/{2,}', '/', p if p.startswith('/') else '/' + p)
    p = re.sub(r'\{[^}]*\}', '{}', p)
    p = p[:-1] if len(p) > 1 and p.endswith('/') else p
    return None if p.lower() == '/' else p.lower()


def stated_pattern(text):
    for m in ENDPOINT.finditer(text):
        raw = m.group(2)
        q = raw.find('?')
        if 0 <= q < len(raw) - 1 and raw[q + 1:].strip():
            return '{' in raw[q + 1:]
    return False


def main(argv):
    out = subprocess.run(['docker', 'exec', 'neo4j', 'cypher-shell', '-u', 'neo4j', '-p', 'your_password',
                          '--format', 'plain',
                          "MATCH (c:CriterionResult) WHERE c.criterionText CONTAINS '?' "
                          "RETURN DISTINCT c.criterionText AS t"],
                         check=True, capture_output=True, text=True).stdout.split('\n')[1:]
    texts = sorted({json.loads(l) if l.startswith('"') else l for l in (x.strip() for x in out) if l})
    rows = []
    for t in texts:
        if 'BUILD_FIX' in t or not stated_pattern(t):
            continue
        codes = sorted({int(c) for c in STATUS.findall(t) if 100 <= int(c) <= 599})
        label = 'none' if not codes else 'success' if all(200 <= c < 300 for c in codes) else 'error'
        eps = {m.group(1).upper() + ' ' + normalize(m.group(2)) for m in ENDPOINT.finditer(t) if normalize(m.group(2))}
        parses = len(eps) == 1 and len(codes) == 1
        rows.append((label, str(codes[0]) if len(codes) == 1 else '-', 'yes' if parses else 'no',
                     ' '.join(t.split())))
    dest = open(argv[0], 'w') if argv else sys.stdout
    for r in rows:
        dest.write('\t'.join(r) + '\n')
    counts = {k: sum(1 for r in rows if r[0] == k) for k in ('success', 'error', 'none')}
    print(f'{len(rows)} pattern criteria: {counts}', file=sys.stderr)


if __name__ == '__main__':
    main(sys.argv[1:])
