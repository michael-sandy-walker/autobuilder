#!/usr/bin/env python3
"""T6-U, the spikes' blind spot: a claim test that fails on every throwaway implementation is kept only when a
REPAIRED implementation -- written from the wording, told the dropped claims (never their tests) -- passes it AND
every test already accepted. Execution proves one implementation can meet the claim and everything else; a test that
contradicts an accepted one cannot pass beside it. No reading decides.

Offline, over a claims directory of claim_tests.py + spikes.py (the receiver-questions data: claims 30-33 right and
dropped by every spike; 18 contradicts an accepted claim; 15, 16 compatible either way).

Usage: repair.py <case> <claims-dir> [--refs real,...]
"""
import argparse, json, os, re, shutil, sys, tempfile, glob, subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.dirname(HERE)); sys.path.insert(0, HERE)
from derived_test_replay import ask, sh  # noqa: E402
from spikes import SPIKE, files_of  # noqa: E402

ROUNDS = 3

REPAIR = SPIKE + """
Some claims about the change are listed below. Earlier implementations of the same specification did not meet them; \
meet them as well as everything else the specification says, as the specification words them."""


def failures(tree, tests, only):
    """The runner's failure messages for the tests in `only`, run together with `tests` in a copy of tree."""
    with tempfile.TemporaryDirectory() as d:
        t = os.path.join(d, 't')
        shutil.copytree(tree, t, ignore=shutil.ignore_patterns('target'))
        for p, c in tests.items():
            os.makedirs(os.path.dirname(os.path.join(t, p)), exist_ok=True)
            open(os.path.join(t, p), 'w').write(c)
        subprocess.run('mvn -B -o test -Dmaven.test.failure.ignore=true', cwd=t, shell=True, capture_output=True)
        out = []
        for p in only:
            name = os.path.basename(p)[:-5]
            for f in glob.glob(t + f'/target/surefire-reports/TEST-*.{name}.xml'):
                for m in re.finditer(r'<(?:failure|error) message="([^"]*)"', open(f).read()):
                    out.append(f'{name}: ' + m.group(1).replace('&quot;', '"').replace('&lt;', '<').replace('&gt;', '>')
                               .replace('&#10;', ' ')[:400])
        return '\n'.join(out)[:4000]


def run_all(tree, tests):
    """Every test of `tests` (path -> text) run together in a copy of tree: path -> passed."""
    with tempfile.TemporaryDirectory() as d:
        t = os.path.join(d, 't')
        shutil.copytree(tree, t, ignore=shutil.ignore_patterns('target'))
        for p, c in tests.items():
            os.makedirs(os.path.dirname(os.path.join(t, p)), exist_ok=True)
            open(os.path.join(t, p), 'w').write(c)
        left = dict(tests)
        result = {}
        for _ in range(len(tests) + 1):
            out = subprocess.run('mvn -B -o test -Dmaven.test.failure.ignore=true', cwd=t, shell=True,
                                 capture_output=True, text=True).stdout
            reports = glob.glob(t + '/target/surefire-reports/TEST-*.xml')
            if reports:
                bad = set()
                for f in reports:
                    x = open(f).read()
                    m = re.search(r'tests="(\d+)" errors="(\d+)" skipped="\d+" failures="(\d+)"', x)
                    if m and int(m[2]) + int(m[3]) > 0:
                        bad.add(f.split('TEST-')[1][:-4].split('.')[-1])
                for p in left:
                    result[p] = os.path.basename(p)[:-5] not in bad and any(
                        f.endswith('.' + os.path.basename(p)[:-5] + '.xml') for f in reports)
                return result
            broken = [p for p in left if os.path.basename(p) in out]
            if not broken:
                return {p: False for p in tests}
            for p in broken:
                result[p] = False
                os.remove(os.path.join(t, p))
                del left[p]
        return {p: result.get(p, False) for p in tests}


def main():
    a = argparse.ArgumentParser()
    a.add_argument('case'); a.add_argument('claims'); a.add_argument('--refs', default='')
    o = a.parse_args()
    claims = {r['id']: r for r in json.load(open(os.path.join(o.claims, 'claims.json')))}
    sp = json.load(open(os.path.join(o.claims, 'spikes.json')))
    beside = None
    accepted, dropped = {}, {}
    for r in sp['claims']:
        c = claims[r['id']]
        test = c['scored'][0]['test']
        pkg = re.search(r'^package\s+([\w.]+);', test, re.M).group(1)
        name = re.search(r'class (Claim\d+Test)', test).group(1)
        path = 'src/test/java/' + pkg.replace('.', '/') + f'/{name}.java'
        (accepted if any(x == 'PASS' for x in r['spikes']) else dropped)[path] = (r['id'], test, c)
    print(f'accepted {len(accepted)}, dropped {len(dropped)}: {[v[0] for v in dropped.values()]}', flush=True)
    pristine = os.path.join(tempfile.mkdtemp(), 'p')
    sh(f'scripts/prepare_module_change.sh {o.case} {pristine}', ROOT)
    seed = open(os.path.join(ROOT, 'data', o.case, 'bootstrap/seed.txt')).read()
    listing = lambda root, sub: ''.join(f'--- {os.path.relpath(os.path.join(dp, f), root)} ---\n'
                                        + open(os.path.join(dp, f)).read() + '\n'
                                        for dp, _, fs in os.walk(os.path.join(root, sub)) for f in sorted(fs)
                                        if f.endswith('.java'))
    claim_list = '\n'.join(f'- {v[2]["claim"]}' for v in dropped.values())
    user = (f"SPECIFICATION:\n{seed}\n\nTHE PROJECT'S SOURCES AS THEY ARE NOW:\n{listing(pristine, 'src/main')}\n\n"
            f"THE EXISTING TESTS:\n{listing(pristine, 'src/test')}\n\nCLAIMS EARLIER IMPLEMENTATIONS DID NOT MEET:\n{claim_list}")
    tree = os.path.join(tempfile.mkdtemp(), 't')
    shutil.copytree(pristine, tree)
    answer = ask(REPAIR, user)
    for attempt in range(2):
        for p, c in files_of(answer).items():
            if p.startswith('src/main/'):
                os.makedirs(os.path.dirname(os.path.join(tree, p)), exist_ok=True)
                open(os.path.join(tree, p), 'w').write(c)
        rc, out = sh('mvn -B -q -o test-compile', tree)
        if rc == 0 or attempt == 1:
            break
        answer = ask(REPAIR, user + f"\n\nYOUR EARLIER CHANGE:\n{answer}\n\nIT DID NOT COMPILE:\n"
                     + '\n'.join(l for l in out.splitlines() if 'ERROR' in l)[:4000] + "\nWrite the corrected full files.")
    print('repaired implementation compiles' if rc == 0 else 'repaired implementation does not compile', flush=True)
    # Iterate as the loop does: the dropped claims' failures (what the runner reported -- never a test's source) go
    # back, for a few rounds. A round that breaks an accepted test is undone.
    everything = {p: v[1] for p, v in {**accepted, **dropped}.items()}
    best = None   # (dropped passing, results) of the best state in which every accepted test still passes
    backup = None
    for rnd in range(ROUNDS + 1 if rc == 0 else 0):
        res = run_all(tree, everything)
        still = [p for p in dropped if not res[p]]
        broke_now = [p for p in accepted if not res[p]]
        print(f'round {rnd}: dropped passing {len(dropped) - len(still)}/{len(dropped)}, accepted broken {len(broke_now)}',
              flush=True)
        if broke_now:
            # A change that breaks an accepted test is undone: the accepted tests are the wording's, already checked.
            if backup:
                shutil.rmtree(tree); shutil.copytree(backup, tree)
                print(f'round {rnd}: undone -- it broke accepted tests', flush=True)
            res = best[1] if best else res
            still = [p for p in dropped if not res[p]]
        elif best is None or len(dropped) - len(still) > best[0]:
            best = (len(dropped) - len(still), res)
        if not still or rnd == ROUNDS:
            break
        backup = os.path.join(tempfile.mkdtemp(), 'b'); shutil.copytree(tree, backup)
        report = failures(tree, everything, still)
        answer = ask(REPAIR, user + f"\n\nYOUR CURRENT CHANGE:\n{answer}\n\nCHECKS OF THOSE CLAIMS STILL FAIL, AS THE "
                     f"TEST RUNNER REPORTED THEM:\n{report}\nWrite the corrected full files.")
        for p, c in files_of(answer).items():
            if p.startswith('src/main/'):
                os.makedirs(os.path.dirname(os.path.join(tree, p)), exist_ok=True)
                open(os.path.join(tree, p), 'w').write(c)
        if sh('mvn -B -q -o test-compile', tree)[0] != 0:
            shutil.rmtree(tree); shutil.copytree(backup, tree)
            print(f'round {rnd}: the change did not compile -- undone', flush=True)
    shutil.copytree(tree, os.path.join(o.claims, 'repair-spike'), dirs_exist_ok=True)
    res = best[1] if best else {p: False for p in everything}
    acc_ok = all(res[p] for p in accepted)
    broke = [accepted[p][0] for p in accepted if not res[p]]
    print(f'accepted tests still passing on the repaired implementation: {sum(res[p] for p in accepted)}/{len(accepted)}'
          f'{" -- broken: " + str(broke) if broke else ""}', flush=True)
    out = []
    for p, (cid, test, c) in dropped.items():
        keep = acc_ok and res[p]
        out.append({'id': cid, 'passes_repair': res[p], 'keep': keep, 'claim': c['claim']})
        print(f'[claim {cid}] passes the repaired implementation: {res[p]} -> {"KEEP" if keep else "DROP"}  {c["claim"][:80]}',
              flush=True)
    json.dump({'accepted_ok': acc_ok, 'broken_accepted': broke, 'dropped': out},
              open(os.path.join(o.claims, 'repair.json'), 'w'), indent=1)


if __name__ == '__main__':
    main()
