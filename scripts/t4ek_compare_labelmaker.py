#!/usr/bin/env python3
"""T4-EK slice (a2), library-style: a reverse-engineered account of stripped labelmaker against its truth.

Usage: scripts/t4ek_compare_labelmaker.py <account.json>

The rule was stated on the ticket before the run (2026-11-09): the truth is LabelerSpecTest's 8 tests; a test is
recalled when a KEPT claim (anchored by its feature) matches its predicate, on the entry point the claim reaches and
its test body. Bar >= 5/8; (1) is an expected miss (an enum's values() is not a declared method). DoD also: features
exactly Zone and Labeler; Labeler depends on Zone.
"""
import json
import re
import sys


ENTRY = {'of': 'Zone.of', 'checkChar': 'Labeler.checkChar', 'next': 'Labeler.next', 'isValid': 'Labeler.isValid'}


def subjects(j):
    """The entry methods the claim's LAST assertion is about: named in it, or whose result it uses through a variable.
    The instrument reads this from the claim since the product no longer does (attribution there is observed)."""
    b = body(j)
    statements = [st for st in re.split(r';\s*\n|;\s*$', b) if st.strip()]
    asserts = [st for st in statements if re.search(r'\bassert\w*\s*\(|\bfail\s*\(', st)]
    if not asserts:
        return set()
    last = asserts[-1]
    found = {ENTRY[m] for m in re.findall(r'\.(of|checkChar|next|isValid)\s*\(', last)}
    for var, call in re.findall(r'(\w+)\s*=\s*[\w.]*\.(of|checkChar|next|isValid)\s*\(', b):
        if re.search(r'\b' + re.escape(var) + r'\b', last):
            found.add(ENTRY[call])
    return found


def reaches(j):
    """Kept for the predicates below: any subject that starts with what they ask for."""
    return ' '.join(sorted(subjects(j)))


def body(j):
    return j['claim'].get('test') or ''


def refused_then_next(b):
    m = re.search(r'assertThrows\([^;]*?next\(', b, re.S)
    if not m:
        return False
    end = b.find(';', m.end())
    return end >= 0 and 'next(' in b[end:]


CHECKS = [
    (1, 'zones are the seven in order', lambda j: re.search(r'values\(\)|ordinal\(\)', body(j))),
    (2, 'zone is the digit sum mod seven', lambda j: 'Zone.of' in subjects(j)
     and re.search(r'assertEquals\(\s*Zone\.[A-Z]+', body(j))),
    (3, 'zone refuses anything but five digits', lambda j: 'Zone.of' in subjects(j)
     and 'IllegalArgumentException' in body(j)),
    (4, 'check char follows the weighted rule', lambda j: 'Labeler.checkChar' in subjects(j)
     and 'assertEquals' in body(j)),
    (5, 'next numbers from one across postcodes', lambda j: 'Labeler.next' in subjects(j)
     and len(re.findall(r'\.next\(', body(j))) >= 2 and re.search(r'0000[2-9]|000[1-9]\d', body(j))),
    # The rule as stated: an expected-exception assertion on next AND a later next call -- after that assertion's
    # statement ends, not the next( inside it (the first version of this script counted that one; fixed before scoring).
    (6, 'a refused postcode uses up no number', lambda j: refused_then_next(body(j))),
    (7, 'each labeler counts on its own', lambda j: 'Labeler.next' in subjects(j)
     and len(re.findall(r'new Labeler\(\)', body(j))) >= 2),
]


def main():
    acc = json.load(open(sys.argv[1]))
    claims = acc['claims']
    print(f"source: {acc['source']}\nkind: {acc.get('kind')}\nduration: {acc['durationMs'] / 60000:.1f} min\n"
          f"verdicts: {acc['verdicts']}")
    print('\nFEATURES:')
    for f in acc['features']:
        print(f"  {f['name']}: {len(f['claims'])} kept; depends on {f['dependsOn'] or 'nothing'}; made of {f['madeOf']}\n"
              f"      entry points: {', '.join(f['entryPoints'])}")
    print('\nVARIANTS:')
    for v in acc['variants']:
        print(f"  {v['capability'] or 'as-is'}: built={v['built']} ran={v['ran']} stubbed={len(v['stubbedMethods'])}"
              + (f" why={(v.get('why') or '')[:160]}" if v.get('why') else ''))
    kept = [j for j in claims if (j.get('byFeature') or {}).get('verdict') == 'anchored']
    recalled = 0
    print('\nCHECKS:')
    for n, name, pred in CHECKS:
        hits = [j['claim']['id'] for j in kept if pred(j)]
        recalled += bool(hits)
        print(f"  [{n}] {'HIT ' if hits else 'miss'} {name:42} {','.join(hits) or '-'}")
    valid = [j for j in kept if 'Labeler.isValid' in subjects(j)]
    asserts_true = [j['claim']['id'] for j in valid if 'assertTrue' in body(j)]
    asserts_false = [j['claim']['id'] for j in valid if 'assertFalse' in body(j)]
    hit8 = bool(asserts_true and asserts_false)
    recalled += hit8
    print(f"  [8] {'HIT ' if hit8 else 'miss'} {'isValid accepts only correct codes':42} true:{','.join(asserts_true) or '-'} "
          f"false:{','.join(asserts_false) or '-'}")
    print(f"\nRECALL: {recalled}/8 (bar >= 5)")
    print('\nALL CLAIMS:')
    for j in claims:
        bf = j.get('byFeature') or {}
        obs = ' '.join(f"{o['variant']}={'pass' if o['held'] else 'fail'}" for o in j['observations'])
        print(f"  {j['claim']['id']} {bf.get('verdict'):12} (layer {j['verdict']}) {bf.get('feature')} {sorted(subjects(j))} :: {j['claim']['text']}\n"
              f"      {obs}" + (f"  dependsOn={bf['dependsOn']}" if bf.get('dependsOn') else ''))


if __name__ == '__main__':
    main()
