#!/usr/bin/env python3
"""T6-U step 1 (revised, user agreed 2026-10-24): tests from a requirement's wording by divide and conquer and a vote.

Offline prototype -- a measuring instrument, not the loop. For each requirement marked `gate: derived-test`:

  1. split it into ATOMIC claims (one situation, one expected outcome), each with the source of its expected outcome:
     STATED (the wording gives it), ENVIRONMENT (a fact of the runtime -- computed in the test, never a literal),
     OPEN (the wording does not fix it: no test);
  2. write N tests per non-OPEN claim -- the model's own variation (measured: 0 of 22 identical prompts gave the
     same answer twice at temperature 0) is the independence of the attempts;
  3. a judge groups the N by "same situation, same expected outcome", J times; agreement = largest group / N, and
     the judge is stable when its largest group is the same every time;
  4. a claim below the provisional split level, or with an unstable judge, is split again (up to MAX_DEPTH); one
     that is atomic and still scatters is OPEN by disagreement;
  5. every group's representative is run on the pristine tree and on the reference trees (the ticket's real
     implementation first), so agreement can be scored against correctness -- the thresholds come from this data,
     never picked in advance.

Usage: claim_tests.py <case> <labels.json> <ref-tree>[,<more>] <out-dir> [--n 5] [--judges 3] [--only SUBSTRING]
"""
import argparse, hashlib, json, os, re, subprocess, sys, tempfile, time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.dirname(HERE))
from derived_test_replay import ask, run_one, sh  # noqa: E402

SPLIT_LEVEL = 0.5     # provisional: drives the recursion of THIS measurement only; the real level comes from its data
MAX_DEPTH = 2

SOURCES = """For each claim say where its expected outcome comes from:
- STATED: the requirement or the specification gives it -- copy the words that give it;
- ENVIRONMENT: a fact about the language runtime or its standard library (for example which methods a standard \
class declares) -- a test will compute it from the runtime;
- OPEN: the wording does not fix it.
Use only situations the requirement or the specification names or plainly implies; never invent an input form.
Answer with JSON only: [{"claim": "...", "source": "STATED|ENVIRONMENT|OPEN", "words": "..."}]"""

DECOMPOSE = ("You split ONE requirement of a change to an existing project into atomic claims a test can check. "
             "A claim is one situation (an input) and one expected outcome.\n" + SOURCES)

SPLIT = ("Independent tests written for this claim did not agree on what it checks. Split it into smaller claims, "
         "each one situation and one expected outcome, so that each is fixed by the wording -- or mark a part OPEN "
         "when the wording does not fix it.\n" + SOURCES)

WRITE = """You write ONE test class with ONE test method for ONE claim about a change to an existing project. The \
code that meets it has not been written yet: write the test from the claim, the requirement and the specification, \
so that it fails until the code does what the claim says.
- Check exactly this claim -- its situation and its expected outcome -- and nothing else.
- Assert only what the claim's source gives. A fact about the runtime (an ENVIRONMENT claim) is computed in the \
test from the runtime itself, for example by reflection -- never written out as a literal list or value.
- Do not name a type, method or value the specification and the declarations shown do not name; where the result's \
type is not named, do not name it.
- Use the same language, libraries and assertion style as the existing tests shown.
Answer with the one file in a fenced block whose opening line names its path in brackets, as shown."""

JUDGE = """You compare tests written for the same claim. Put two tests in the same group when they check the same \
situation and expect the same outcome; names, style and helper code do not matter. Answer with JSON only: a list of \
groups, each a list of test numbers, for example [[1,3,4],[2],[5]]."""


def jsonish(text, opener):
    """The first JSON value in a model's answer that starts with `opener`."""
    if not text:
        return None
    i = text.find(opener)
    while i >= 0:
        for j in range(len(text), i, -1):
            try:
                return json.loads(text[i:j])
            except Exception:
                continue
        i = text.find(opener, i + 1)
    return None


def call(system, user, log):
    t = time.time()
    a = ask(system, user)
    log.write(json.dumps({'system': system[:60], 'seconds': round(time.time() - t, 1), 'answer': a}) + '\n')
    log.flush()
    return a


def fenced(answer, name):
    """The one file asked for: from the first fence to the LAST, as DerivedTests.located takes it."""
    if not answer:
        return None
    m = re.search(r'(?m)^[ \t]*```[^\n]*\n', answer)
    close = answer.rfind('```')
    if not m or close <= m.end():
        return None
    body = answer[m.end():close]
    return body if name in body else None


def main():
    a = argparse.ArgumentParser()
    a.add_argument('case'); a.add_argument('labels'); a.add_argument('refs'); a.add_argument('out')
    a.add_argument('--n', type=int, default=5); a.add_argument('--judges', type=int, default=3)
    a.add_argument('--only')
    o = a.parse_args()
    os.makedirs(o.out, exist_ok=True)
    log = open(os.path.join(o.out, 'calls.ndjson'), 'a')
    labels = json.load(open(o.labels))
    refs = o.refs.split(',')
    seed = open(os.path.join(ROOT, 'data', o.case, 'bootstrap/seed.txt')).read()
    pristine = tempfile.mkdtemp()
    os.rmdir(pristine)
    rc, out = sh(f'scripts/prepare_module_change.sh {o.case} {pristine}', ROOT)
    if rc:
        sys.exit(out)
    tests = {}
    for dp, _, fs in os.walk(os.path.join(pristine, 'src/test')):
        for fn in sorted(fs):
            if fn.endswith('.java'):
                p = os.path.join(dp, fn)
                tests[os.path.relpath(p, pristine)] = open(p).read()
    existing = ''.join(f'--- {k} ---\n{v}\n' for k, v in sorted(tests.items()))
    cpf = os.path.join(o.out, 'classpath.txt')
    if not os.path.exists(cpf):
        sh(f'mvn -q -o dependency:build-classpath -Dmdep.outputFile={cpf}', ROOT)
    cp = open(cpf).read().strip()
    mains = [os.path.join(dp, f) for dp, _, fs in os.walk(os.path.join(pristine, 'src/main')) for f in fs if f.endswith('.java')]
    rc, outline = sh(f"java -cp target/classes:{cp} scripts/claim_tests/Outline.java {' '.join(mains)}", ROOT)
    outline = outline.replace(pristine + '/', '')
    beside = os.path.dirname(sorted(tests)[0])
    context = (f"SPECIFICATION:\n{seed}\n\nTHE PROJECT'S DECLARATIONS AS THEY ARE NOW (bodies left out):\n{outline}\n\n"
               f"THE EXISTING TESTS:\n{existing}")

    results = []
    counter = [0]

    def claims_of(system, requirement, extra):
        ans = call(system, f"REQUIREMENT:\n{requirement}\n{extra}\n\n{context}", log)
        got = jsonish(ans, '[')
        return [c for c in (got or []) if isinstance(c, dict) and c.get('claim')]

    def handle(requirement, claim, depth):
        counter[0] += 1
        cid = counter[0]
        row = {'id': cid, 'requirement': requirement, 'kind': labels[requirement], 'depth': depth,
               'claim': claim['claim'], 'source': claim.get('source', 'OPEN').upper(), 'words': claim.get('words', '')}
        print(f"[claim {cid}] d{depth} {row['source']:11} {row['claim'][:90]}", flush=True)
        if row['source'] == 'OPEN':
            row['outcome'] = 'OPEN-stated'
            results.append(row); return
        name = f'Claim{cid:03d}Test'
        header = f"```java [{beside}/{name}.java]"
        samples = []
        for s in range(o.n):
            ans = call(WRITE, f"CLAIM ({row['source']}): {row['claim']}\nWORDS THAT GIVE IT: {row['words']}\n\n"
                              f"REQUIREMENT:\n{requirement}\n\n{context}\n\nName the class {name}. Open the block "
                              f"exactly as:\n{header}", log)
            body = fenced(ans, name)
            if body:
                samples.append(body)
        row['samples'] = len(samples)
        if len(samples) < 2:
            row['outcome'] = 'NO-SAMPLES'
            results.append(row); return
        numbered = '\n\n'.join(f'TEST {i + 1}:\n{t}' for i, t in enumerate(samples))
        groupings = []
        for j in range(o.judges):
            g = jsonish(call(JUDGE, f"CLAIM: {row['claim']}\n\n{numbered}", log), '[')
            ok = isinstance(g, list) and all(isinstance(x, list) for x in g)
            groupings.append(sorted([sorted(int(i) for i in x) for x in g], key=len, reverse=True) if ok else None)
        largest = [g[0] if g else None for g in groupings]
        row['judge_stable'] = all(l is not None and l == largest[0] for l in largest)
        main = largest[0] or [1]
        row['agreement'] = round(len(main) / len(samples), 2)
        row['groups'] = groupings[0]
        # Score the majority's representative and one of each other group: does agreement predict correctness?
        scored = []
        for gi, group in enumerate(groupings[0] or [[1]]):
            t = samples[group[0] - 1]
            path = f'{beside}/{name}.java'
            p = run_one(pristine, path, t)
            rr = [run_one(r, path, t) for r in refs]
            scored.append({'group': gi, 'size': len(group), 'pristine': p, 'real': rr[0],
                           'others_pass': sum(x == 'PASS' for x in rr[1:]), 'test': t})
        row['scored'] = scored
        weak = row['agreement'] < SPLIT_LEVEL or not row['judge_stable']
        if weak and depth < MAX_DEPTH:
            row['outcome'] = 'SPLIT'
            results.append(row)
            for sub in claims_of(SPLIT, requirement, f"\nCLAIM THAT WAS NOT AGREED ON:\n{row['claim']}"):
                handle(requirement, sub, depth + 1)
            return
        row['outcome'] = 'OPEN-disagreed' if weak else 'AGREED'
        results.append(row)
        print(f"   -> {row['outcome']} agreement={row['agreement']} stable={row['judge_stable']} "
              f"majority: pristine={scored[0]['pristine']} real={scored[0]['real']} others={scored[0]['others_pass']}",
              flush=True)

    for requirement in labels:
        if o.only and o.only not in requirement:
            continue
        print(f"== {requirement[:110]}", flush=True)
        for c in claims_of(DECOMPOSE, requirement, ''):
            handle(requirement, c, 0)
        json.dump(results, open(os.path.join(o.out, 'claims.json'), 'w'), indent=1)
    json.dump(results, open(os.path.join(o.out, 'claims.json'), 'w'), indent=1)


if __name__ == '__main__':
    main()
