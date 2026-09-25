#!/usr/bin/env python3
"""T4-CS gate, code layer: what do two lineages' TREES share, and what would merging them buy?

The graph-layer sibling (scripts/lineage_overlap.py, T6-G) found two runs of ONE seed share almost
nothing worth reconciling. Its re-entry condition is a pair from DIFFERENT seeds. This measures such
a pair on disk, for trees whose library code is identical (an adopted project with src/main
protected), so that every assertion either suite makes was checked against the same code: a merge
can conflict only STRUCTURALLY -- the same test class written twice -- never on behaviour.

Per tree: test files and methods, and for every entry point named by either seed, the test methods
that cover it (scripts/test_coverage_attribution.py, the yardsticks' own rule). Then: shared and
colliding files, entry points each parent covers, and what the union of both parents would cover.

Usage: code_lineage_overlap.py <treeA> <seedA> <treeB> <seedB> [--json]
  tree = a project root (with src/main, src/test); seed = its bootstrap/seed.txt
Offline, read-only.
"""
import filecmp
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import test_coverage_attribution as tca  # noqa: E402


def entry_points(seed_path):
    s = open(seed_path).read()
    block = s[s.index('These are its entry points:'):s.index('OBJECTIVE')]
    seen = []
    for t, m in re.findall(r'\b([A-Z]\w*)\.([a-z]\w*)\(', block):
        if (t, m) not in seen:
            seen.append((t, m))
    return seen


def tree_view(tree, entries):
    root = os.path.join(tree, 'src', 'test')
    methods = [(rel, name) for rel, name, _, _ in tca.test_methods(root)] if os.path.isdir(root) else []
    files = sorted({rel for rel, _ in methods} | set(
        os.path.relpath(os.path.join(d, f), root) for d, _, fs in os.walk(root) for f in fs if f.endswith('.java')
    )) if os.path.isdir(root) else []
    cover = {f'{t}.{m}': tca.covering(root, t, m) if os.path.isdir(root) else [] for t, m in entries}
    return {'files': files, 'methods': methods, 'cover': cover}


def main(argv):
    if len(argv) < 4:
        sys.exit(__doc__)
    ta, sa, tb, sb = argv[:4]
    ea, eb = entry_points(sa), entry_points(sb)
    union = ea + [e for e in eb if e not in ea]
    A, B = tree_view(ta, union), tree_view(tb, union)
    ra, rb = os.path.join(ta, 'src', 'test'), os.path.join(tb, 'src', 'test')
    both = sorted(set(A['files']) & set(B['files']))
    identical = [f for f in both if filecmp.cmp(os.path.join(ra, f), os.path.join(rb, f), shallow=False)]
    colliding = [f for f in both if f not in identical]
    same_named = sorted(set(A['methods']) & set(B['methods']))
    main_same = all(filecmp.cmp(os.path.join(ta, 'src/main', r), os.path.join(tb, 'src/main', r), shallow=False)
                    for d, _, fs in os.walk(os.path.join(ta, 'src/main')) for f in fs
                    for r in [os.path.relpath(os.path.join(d, f), os.path.join(ta, 'src/main'))])
    rows = []
    for t, m in union:
        k = f'{t}.{m}'
        rows.append({'entry': k, 'seedA': (t, m) in ea, 'seedB': (t, m) in eb,
                     'coveredA': len(A['cover'][k]), 'coveredB': len(B['cover'][k])})
    covA = {r['entry'] for r in rows if r['coveredA']}
    covB = {r['entry'] for r in rows if r['coveredB']}
    seedA = {f'{t}.{m}' for t, m in ea}
    seedB = {f'{t}.{m}' for t, m in eb}
    out = {
        'src_main_identical': main_same,
        'entry_points': {'seedA': len(ea), 'seedB': len(eb), 'shared_by_seeds': len(seedA & seedB), 'union': len(union)},
        'files': {'A': len(A['files']), 'B': len(B['files']), 'same_path': len(both),
                  'identical': len(identical), 'colliding': colliding},
        'methods': {'A': len(A['methods']), 'B': len(B['methods']), 'same_file_and_name': len(same_named)},
        'coverage': {
            'A_on_seedA': len(covA & seedA), 'A_on_seedB': len(covA & seedB),
            'B_on_seedA': len(covB & seedA), 'B_on_seedB': len(covB & seedB),
            'covered_by_both': sorted(covA & covB), 'only_A': sorted(covA - covB), 'only_B': sorted(covB - covA),
            'union_covers': len(covA | covB), 'of_union': len(union),
        },
        'rows': rows,
    }
    if '--json' in argv:
        print(json.dumps(out, indent=1))
        return
    c = out['coverage']
    print(f"src/main identical: {main_same}")
    print(f"entry points: seed A {len(ea)}, seed B {len(eb)}, named by both {len(seedA & seedB)}, union {len(union)}")
    print(f"test files: A {len(A['files'])}, B {len(B['files'])}, same path {len(both)} "
          f"({len(identical)} identical, {len(colliding)} colliding: {', '.join(os.path.basename(f) for f in colliding) or '-'})")
    print(f"test methods: A {len(A['methods'])}, B {len(B['methods'])}, same file+name {len(same_named)}")
    print(f"coverage: A on its seed {c['A_on_seedA']}/{len(ea)}, A on B's {c['A_on_seedB']}/{len(eb)}; "
          f"B on its seed {c['B_on_seedB']}/{len(eb)}, B on A's {c['B_on_seedA']}/{len(ea)}")
    print(f"covered by both: {len(c['covered_by_both'])}; only A: {len(c['only_A'])} {c['only_A']}; only B: {len(c['only_B'])} {c['only_B']}")
    print(f"a union of both parents would cover {c['union_covers']}/{len(union)}; "
          f"best parent {max(len(covA), len(covB))}/{len(union)}")


if __name__ == '__main__':
    main(sys.argv[1:])
