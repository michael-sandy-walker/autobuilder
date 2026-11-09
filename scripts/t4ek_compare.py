#!/usr/bin/env python3
"""T4-EK slice (a): a reverse-engineered account of firestarter against its truth.

Usage: scripts/t4ek_compare.py <account.json>

The comparison rule was stated on the ticket before the run (2026-11-09):
  - criteria: recall against the 23 checks of scripts/smoke23.sh; a check counts when at least one
    ANCHORED claim asserts it (its LAST request; setup requests do not count). Bar: >= 12/23.
  - surviving claims that assert none of the checks are listed as "true but unstated".
  - capabilities: each of the seed's resources (books, members, loans) and the error contract maps
    to >= 1 reconstructed capability, by the declarations it cites (printed for a person to judge).
2026-11-09, features decided with the user (on the ticket, before the next run): a claim is KEPT when it
is anchored by its FEATURE (fails with that feature's handlers taken out); the layer verdict is shown
beside it. DoD of that step: features exactly books/members/loans; recall >= 18/23; loans depends on
books and members, books and members on nothing.

A check is matched by predicates on the claim's final request -- method, path shape, expected status and
the condition its body shows -- so the score does not depend on reading the claim's sentence.
"""
import json
import re
import sys


def shape(path):
    path = path.split('?')[0]
    segs = []
    for s in path.strip('/').split('/'):
        if s.startswith('$') or re.fullmatch(r'\d+|[0-9a-f-]{36}|\{.*\}', s):
            segs.append('{id}')
        else:
            segs.append(s)
    return '/' + '/'.join(segs)


def body(req):
    # A reference to an earlier answer ($N.field) stands unquoted where the value goes; quote it to parse the body.
    text = re.sub(r'(?<!")(\$\d+\.[A-Za-z_]\w*)', r'"\1"', req.get('body') or 'null')
    try:
        return json.loads(text)
    except ValueError:
        return None


def is_ref(v):
    return isinstance(v, str) and v.startswith('$')


def posts_to(seq, p):
    return sum(1 for r in seq[:-1] if r['method'] == 'POST' and shape(r['path']) == p)


def blank(b, k):
    return not isinstance(b, dict) or k not in b or b[k] is None or (isinstance(b[k], str) and not b[k].strip())


CHECKS = [
    (1, 'POST /books valid', 'POST', '/books', 201, lambda b, s: True),
    (2, 'POST /books missing title', 'POST', '/books', 400, lambda b, s: blank(b, 'title')),
    (3, 'POST /books title>120', 'POST', '/books', 400,
     lambda b, s: isinstance(b, dict) and isinstance(b.get('title'), str) and len(b['title']) > 120),
    (4, 'POST /books bad isbn', 'POST', '/books', 400,
     lambda b, s: isinstance(b, dict) and not blank(b, 'title') and not blank(b, 'isbn')
     and not re.fullmatch(r'\d{13}', str(b['isbn']))),
    (5, 'GET /books', 'GET', '/books', 200, lambda b, s: True),
    (6, 'GET /books/{id}', 'GET', '/books/{id}', 200, lambda b, s: True),
    (7, 'GET /books absent', 'GET', '/books/{id}', 404, lambda b, s: True),
    (8, 'POST /members valid', 'POST', '/members', 201, lambda b, s: True),
    (9, 'POST /members dup email', 'POST', '/members', 409, lambda b, s: True),
    (10, 'POST /members missing name', 'POST', '/members', 400, lambda b, s: blank(b, 'name')),
    (11, 'GET /members/{id}', 'GET', '/members/{id}', 200, lambda b, s: True),
    (12, 'GET /members absent', 'GET', '/members/{id}', 404, lambda b, s: True),
    (13, 'POST /loans valid', 'POST', '/loans', 201, lambda b, s: True),
    (14, 'POST /loans bad book', 'POST', '/loans', 404,
     lambda b, s: isinstance(b, dict) and not is_ref(b.get('bookId')) and is_ref(b.get('memberId'))),
    (15, 'POST /loans bad member', 'POST', '/loans', 404,
     lambda b, s: isinstance(b, dict) and is_ref(b.get('bookId')) and not is_ref(b.get('memberId'))),
    (16, 'POST /loans book on loan', 'POST', '/loans', 409, lambda b, s: True),
    (17, 'POST /loans 2nd', 'POST', '/loans', 201, lambda b, s: posts_to(s, '/loans') >= 1),
    (18, 'POST /loans 3rd', 'POST', '/loans', 201, lambda b, s: posts_to(s, '/loans') >= 2),
    (19, 'POST /loans 4th -> 422', 'POST', '/loans', 422, lambda b, s: True),
    (20, 'POST return', 'POST', '/loans/{id}/return', 200, lambda b, s: True),
    (21, 'POST return again', 'POST', '/loans/{id}/return', 409, lambda b, s: True),
    (22, 'POST return absent', 'POST', '/loans/{id}/return', 404, lambda b, s: True),
    (23, 'GET /loans?memberId', 'GET', '/loans', 200,
     lambda b, s: 'memberId=' in s[-1]['path']),
]


def asserts(claim, check):
    _, _, method, path, status, pred = check
    seq = claim['sequence']
    last = seq[-1]
    return (last['method'] == method and shape(last['path']) == path and claim['expected'] == status
            and pred(body(last), seq))


def main():
    acc = json.load(open(sys.argv[1]))
    claims = acc['claims']
    caps = {c['id']: c for c in acc['capabilities']}
    print(f"source: {acc['source']}\nduration: {acc['durationMs'] / 60000:.1f} min\nverdicts: {acc['verdicts']}")
    print('\nCAPABILITIES (judge by the declarations cited):')
    for c in acc['capabilities']:
        print(f"  {c['id']} {c['name']} -- {c['summary']}\n      {', '.join(c['declarations'])}")
    print('\nVARIANTS:')
    for v in acc['variants']:
        print(f"  {v['capability'] or 'as-is'}: built={v['built']} ran={v.get('ran', v.get('served'))} stubbed={len(v['stubbedMethods'])}"
              + (f" why={v['why'][:120]}" if v.get('why') else ''))
    def kept(j):
        return j['byFeature']['verdict'] if j.get('byFeature') else j['verdict']
    if acc.get('features') is not None:
        print('\nFEATURES (from the served surface):')
        for f in acc['features']:
            print(f"  {f['name']}: {len(f['claims'])} kept claim(s); depends on {f['dependsOn'] or 'nothing'}; "
                  f"made of {f['madeOf']}\n      entry points: {', '.join(f.get('entryPoints') or f.get('routes') or [])}")
    anchored = [j for j in claims if kept(j) == 'anchored']
    held = [j for j in claims if kept(j) in ('anchored', 'not-anchored')]
    print('\nCHECKS (anchored claims asserting it | held-but-not-anchored ones, for information):')
    recalled = 0
    matched_ids = set()
    for check in CHECKS:
        a = [j['claim']['id'] for j in anchored if asserts(j['claim'], check)]
        h = [j['claim']['id'] for j in held if kept(j) == 'not-anchored' and asserts(j['claim'], check)]
        matched_ids.update(a)
        recalled += bool(a)
        print(f"  [{check[0]:2}] {'HIT ' if a else 'miss'} {check[1]:28} {','.join(a) or '-':20} | {','.join(h)}")
    print(f"\nRECALL: {recalled}/23 (bar >= 12)")
    print('\nANCHORED CLAIMS THAT ASSERT NO CHECK ("true but unstated"):')
    for j in anchored:
        if j['claim']['id'] not in matched_ids:
            print(f"  {j['claim']['id']} [{caps.get(j['claim']['capability'], {}).get('name', '?')}] {j['claim']['text']}")
    print('\nALL CLAIMS:')
    for j in claims:
        k = j['claim']
        obs = ' '.join(f"{o['variant']}={o['status'] if o['status'] is not None else o['unobserved']}" for o in j['observations'])
        bf = j.get('byFeature') or {}
        print(f"  {k['id']} {kept(j):12} (layer {j['verdict']}) [{bf.get('feature')}/{k['capability']}] "
              f"expect {k['expected']} :: {k['text']}\n"
              f"      {obs}" + (f"  alsoNeeds={j['alsoNeeds']}" if j['alsoNeeds'] else '')
              + (f"  dependsOn={bf['dependsOn']}" if bf.get('dependsOn') else ''))


if __name__ == '__main__':
    main()
