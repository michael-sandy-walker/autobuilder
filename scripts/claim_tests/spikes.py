#!/usr/bin/env python3
"""T6-U step 1, the reserve check: throwaway implementations written from the wording alone, as a test of the tests.

The vote let wrong claim tests through (claim_tests.py: five of 125 wrong, all at full agreement; three contradict
any implementation of the wording). A test that fails on every independent implementation of the wording is suspect;
a right test should pass on a right implementation. This measures whether that separates them: S spikes per case,
each written from the specification, the declarations and the existing tests -- never the claim tests -- applied to
the pristine tree, kept when it compiles (one retry with the compiler's errors); then every claim's majority test is
run on every kept spike. The withheld specification is also run on each spike, for the reading only.

Usage: spikes.py <case> <claims-dir> [--s 3]
"""
import argparse, json, os, re, shutil, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.dirname(HERE))
from derived_test_replay import ask, run_one, sh  # noqa: E402

SPIKE = """You write the change a specification asks for, in an existing project. Write the full new content of \
every source file you add or change under src/main/, each in a fenced block whose opening line names its path in \
brackets, for example:
```java [src/main/java/org/example/Thing.java]
Do not write or change any test. Keep every existing declaration the specification says to keep."""

HEADER = re.compile(r'^```\w*\s*\[([^\]]+)\]\s*$')


def files_of(answer):
    """Path blocks: a file runs from its header to the last bare fence before the next header (or the end)."""
    lines = (answer or '').split('\n')
    # A header or a closing fence starts at column 0: the same text indented inside a string or a comment of the
    # code is the code's (an edit-hunks spike writes fenced examples in its own sources).
    heads = [i for i, l in enumerate(lines) if HEADER.match(l.rstrip())]
    out = {}
    for n, h in enumerate(heads):
        end = heads[n + 1] if n + 1 < len(heads) else len(lines)
        closes = [i for i in range(h + 1, end) if lines[i].rstrip() == '```']
        if not closes:
            continue
        out[HEADER.match(lines[h].rstrip()).group(1).strip()] = '\n'.join(lines[h + 1:closes[-1]]) + '\n'
    return out


def main():
    a = argparse.ArgumentParser()
    a.add_argument('case'); a.add_argument('claims'); a.add_argument('--s', type=int, default=3)
    o = a.parse_args()
    pristine = os.path.join(tempfile.mkdtemp(), 'p')
    rc, out = sh(f'scripts/prepare_module_change.sh {o.case} {pristine}', ROOT)
    if rc:
        sys.exit(out)
    seed = open(os.path.join(ROOT, 'data', o.case, 'bootstrap/seed.txt')).read()
    tests = {}
    for dp, _, fs in os.walk(os.path.join(pristine, 'src/test')):
        for fn in sorted(fs):
            if fn.endswith('.java'):
                p = os.path.join(dp, fn)
                tests[os.path.relpath(p, pristine)] = open(p).read()
    existing = ''.join(f'--- {k} ---\n{v}\n' for k, v in sorted(tests.items()))
    sources = ''
    for dp, _, fs in os.walk(os.path.join(pristine, 'src/main')):
        for fn in sorted(fs):
            p = os.path.join(dp, fn)
            sources += f'--- {os.path.relpath(p, pristine)} ---\n' + open(p).read() + '\n'
    beside = os.path.dirname(sorted(tests)[0])
    # The spike is a throwaway implementation of the CHANGE: it sees the code as it is (it has to change it), the
    # specification and the existing tests -- never a claim test.
    user = f"SPECIFICATION:\n{seed}\n\nTHE PROJECT'S SOURCES AS THEY ARE NOW:\n{sources}\n\nTHE EXISTING TESTS:\n{existing}"
    withheld = os.path.join(ROOT, 'data', o.case, 'bootstrap/withheld/src/test')
    spikes = []
    for s in range(o.s):
        tree = os.path.join(tempfile.mkdtemp(), 't')
        shutil.copytree(pristine, tree)
        answer = ask(SPIKE, user)
        for attempt in range(2):
            files = files_of(answer)
            for path, content in files.items():
                if path.startswith('src/main/'):
                    os.makedirs(os.path.dirname(os.path.join(tree, path)), exist_ok=True)
                    open(os.path.join(tree, path), 'w').write(content)
            rc, out = sh('mvn -B -q -o test-compile', tree)
            if rc == 0 or attempt == 1:
                break
            errors = '\n'.join(l for l in out.splitlines() if 'ERROR' in l)[:4000]
            answer = ask(SPIKE, user + f"\n\nYOUR EARLIER CHANGE:\n{answer}\n\nIT DID NOT COMPILE:\n{errors}\n"
                                       "Write the corrected full files.")
        compiles = rc == 0
        spec = None
        if compiles:
            t2 = os.path.join(tempfile.mkdtemp(), 't')
            shutil.copytree(tree, t2, ignore=shutil.ignore_patterns('target'))
            shutil.copytree(withheld, os.path.join(t2, 'src/test'), dirs_exist_ok=True)
            spec = sh('mvn -B -q -o test', t2)[0] == 0
        print(f"spike {s + 1}: {len(files)} file(s), compiles={compiles}, withheld spec passes={spec}", flush=True)
        kept_tree = os.path.join(o.claims, f'spike-{s + 1}')
        if os.path.exists(kept_tree):
            shutil.rmtree(kept_tree)
        shutil.copytree(tree, kept_tree, ignore=shutil.ignore_patterns('target'))
        spikes.append({'tree': kept_tree, 'compiles': compiles, 'spec': spec, 'files': sorted(files)})
    kept = [x for x in spikes if x['compiles']]
    rows = json.load(open(os.path.join(o.claims, 'claims.json')))
    results = []
    for r in rows:
        if not r.get('scored'):
            continue
        test = r['scored'][0]['test']
        name = re.search(r'class (Claim\d+Test)', test).group(1)
        path = f'{beside}/{name}.java'
        res = [run_one(x['tree'], path, test) for x in kept]
        results.append({'id': r['id'], 'real': r['scored'][0]['real'], 'others_pass': r['scored'][0]['others_pass'],
                        'pristine': r['scored'][0]['pristine'], 'spikes': res})
        print(f"[claim {r['id']}] real={r['scored'][0]['real']:9} spikes={res}", flush=True)
    json.dump({'spikes': spikes, 'claims': results},
              open(os.path.join(o.claims, 'spikes.json'), 'w'), indent=1)


if __name__ == '__main__':
    main()
