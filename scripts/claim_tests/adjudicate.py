#!/usr/bin/env python3
"""T6-U step 1: when a claim's test fails on every throwaway implementation, which side contradicts the wording?

The spikes share the model's blind spots (receiver-questions: all three missed the compiler's record-component form,
so the four right tests of it failed everywhere), so failing every spike cannot by itself drop a test. A judge sees
the claim, its source words, the requirement, the specification, the test and the failure it gave on a spike -- never
the reference trees -- and answers TEST (the test contradicts the wording), IMPLEMENTATION (the implementation does)
or UNSETTLED. J independent verdicts; the test is kept only on a majority for IMPLEMENTATION.

Usage: adjudicate.py <case> <claims-dir> [--judges 3]
"""
import argparse, json, os, re, shutil, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.dirname(HERE))
from derived_test_replay import ask  # noqa: E402

ADJUDICATE = """A test was written for one claim of a change, from the wording alone. An independent implementation \
was also written from the same wording, without seeing the test. The test fails on that implementation. Decide which \
side contradicts the WORDING (the claim's source words, the requirement and the specification) -- not which is more \
plausible, and not which you would have written:
- TEST: the test asserts something the wording does not say, or contradicts it, or is itself faulty (a wrong \
pattern, a malformed input, an assumption about the language or its libraries that does not hold);
- IMPLEMENTATION: the wording says what the test asserts, and the implementation does not do it;
- UNSETTLED: the wording does not decide it.
Answer with the first line exactly one of "VERDICT: TEST", "VERDICT: IMPLEMENTATION", "VERDICT: UNSETTLED", then one \
short paragraph."""


def failure(tree, path, content):
    with tempfile.TemporaryDirectory() as d:
        t = os.path.join(d, 't')
        shutil.copytree(tree, t, ignore=shutil.ignore_patterns('target'))
        open(os.path.join(t, path), 'w').write(content)
        cls = os.path.basename(path)[:-5]
        p = subprocess.run(f'mvn -B -o test -Dtest={cls} -Dsurefire.failIfNoSpecifiedTests=false', cwd=t, shell=True,
                           capture_output=True, text=True)
        keep = [l for l in p.stdout.splitlines() if '[ERROR]' in l and ('Test' in l or '.java' in l or 'expected' in l.lower())
                or 'Expect' in l or l.startswith('  ')]
        return '\n'.join(keep)[:3000]


def main():
    a = argparse.ArgumentParser()
    a.add_argument('case'); a.add_argument('claims'); a.add_argument('--judges', type=int, default=3)
    o = a.parse_args()
    seed = open(os.path.join(ROOT, 'data', o.case, 'bootstrap/seed.txt')).read()
    claims = {r['id']: r for r in json.load(open(os.path.join(o.claims, 'claims.json')))}
    sp = json.load(open(os.path.join(o.claims, 'spikes.json')))
    trees = [x['tree'] for x in sp['spikes'] if x['compiles']]
    out = []
    for r in sp['claims']:
        if not trees or any(x == 'PASS' for x in r['spikes']):
            continue
        c = claims[r['id']]
        test = c['scored'][0]['test']
        pkg = re.search(r'^package\s+([\w.]+);', test, re.M).group(1)
        name = re.search(r'class (Claim\d+Test)', test).group(1)
        path = 'src/test/java/' + pkg.replace('.', '/') + f'/{name}.java'
        fail = failure(trees[0], path, test)
        votes = []
        for j in range(o.judges):
            ans = ask(ADJUDICATE, f"CLAIM ({c['source']}): {c['claim']}\nSOURCE WORDS: {c['words']}\n\nREQUIREMENT:\n"
                                  f"{c['requirement']}\n\nSPECIFICATION:\n{seed}\n\nTEST:\n{test}\n\n"
                                  f"WHAT THE TEST REPORTED ON THE IMPLEMENTATION:\n{fail}")
            m = re.search(r'VERDICT:\s*(TEST|IMPLEMENTATION|UNSETTLED)', (ans or '').upper())
            votes.append(m.group(1) if m else 'NONE')
        keep = votes.count('IMPLEMENTATION') > len(votes) / 2
        out.append({'id': r['id'], 'real': r['real'], 'others_pass': r['others_pass'], 'votes': votes, 'keep': keep})
        print(f"[claim {r['id']}] real={r['real']:9} votes={votes} -> {'KEEP' if keep else 'DROP'}  {c['claim'][:80]}",
              flush=True)
    json.dump(out, open(os.path.join(o.claims, 'adjudicated.json'), 'w'), indent=1)


if __name__ == '__main__':
    main()
