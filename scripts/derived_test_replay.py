#!/usr/bin/env python3
"""T6-U step 1: score the checks on tests written from a requirement's wording, against trees whose answer is known.

Each recorded writing (meta/derived-tests-attempts.ndjson, from DerivedTests) is run twice: on the case's pristine
tree (scripts/prepare_module_change.sh) and on a KNOWN-CORRECT tree (a run that met the goal with the specification
tests given). The correct tree is the instrument only; the loop never sees it.

  WRONG    -- fails or does not compile on the correct tree: freezing it blocks correct code
  VACUOUS  -- a NEW-behaviour test that already passes on the pristine tree: it tests nothing the change adds
  RIGHT    -- otherwise

Then every check combination is scored on the same writings: which it would accept, and how many of those are WRONG.
The decision rule, fixed before the results (user agreed, 2026-10-24): a combination may freeze only with ZERO wrong
accepted across the sample, while at least half of each case's marked requirements get an accepted right test.

Usage: derived_test_replay.py <case> <attempts.ndjson> <correct-tree> <labels.json> [--review] [--out results.json]
  labels.json: {"<requirement text>": "new" | "existing"} -- which lines describe behaviour the pristine tree has.
  --review: also ask the model (llm.api.* of application.yml) the revised review question for every writing.
"""
import argparse, hashlib, json, os, re, shutil, subprocess, sys, tempfile, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DT = os.path.join(ROOT, 'src/main/java/com/walpro/autobuilder/goal/DerivedTests.java')

COMPLETENESS = re.compile(r'\.(containsExactly|containsExactlyInAnyOrder|containsOnly|hasSize)\s*\(')

REVISED_REVIEW = """You check whether a test checks ONE requirement, as that requirement is worded. You see the requirement, \
the specification it comes from, the project's existing tests (which show how the code behaves today), and the test. \
You do not see the code that will meet the requirement, and must not guess at it.
The test passes the check only when ALL of these hold:
- it exercises the cases and forms THIS requirement names, with the values and wordings it gives;
- every expected value it asserts is one the requirement, the specification or the existing tests state -- it \
must not assert a value it would have had to work out or invent, such as the complete list of something when the \
wording gives only examples, or an exact output the wording does not give;
- it asserts nothing about other requirements' behaviour.
Answer with the first line exactly "VERDICT: PASS" or "VERDICT: FAIL", then one short paragraph."""


def sh(cmd, cwd, timeout=600):
    try:
        p = subprocess.run(cmd, cwd=cwd, shell=True, capture_output=True, text=True, timeout=timeout)
        return p.returncode, p.stdout + p.stderr
    except subprocess.TimeoutExpired:
        return 124, 'timeout'


def run_one(tree, path, content):
    """PASS / FAIL / NOCOMPILE for the one test class at path, run in a copy of tree."""
    with tempfile.TemporaryDirectory() as d:
        t = os.path.join(d, 't')
        shutil.copytree(tree, t, ignore=shutil.ignore_patterns('target'))
        f = os.path.join(t, path)
        os.makedirs(os.path.dirname(f), exist_ok=True)
        open(f, 'w').write(content)
        cls = os.path.basename(path).rsplit('.', 1)[0]
        rc, out = sh(f'mvn -B -q -o test -Dtest={cls} -Dsurefire.failIfNoSpecifiedTests=false', t)
        if rc == 0:
            return 'PASS'
        if 'COMPILATION ERROR' in out or 'cannot find symbol' in out:
            return 'NOCOMPILE'
        return 'FAIL'


def llm_config():
    yml = open(os.path.join(ROOT, 'src/main/resources/application.yml')).read()
    url = re.search(r'^\s*url:\s*(http\S+)', yml.split('\nllm:', 1)[1], re.M).group(1)
    model = re.search(r'^\s*model:\s*(\S+)', yml.split('\nllm:', 1)[1], re.M).group(1)
    return url, model


def ask(system, user):
    url, model = llm_config()
    body = json.dumps({'model': model, 'temperature': 0.0, 'messages': [
        {'role': 'system', 'content': system}, {'role': 'user', 'content': user}]}).encode()
    req = urllib.request.Request(url, body, {'Content-Type': 'application/json', 'Authorization': 'Bearer local'})
    with urllib.request.urlopen(req, timeout=600) as r:
        return json.load(r)['choices'][0]['message']['content']


def original_review():
    """The REVIEW_SYSTEM text block of DerivedTests.java, as Java reads it: a trailing backslash joins lines."""
    src = open(DT).read()
    block = src.split('REVIEW_SYSTEM = """', 1)[1].split('""";', 1)[0]
    lines = block.split('\n')[1:]
    indent = min(len(l) - len(l.lstrip()) for l in lines if l.strip())
    text = '\n'.join(l[indent:] for l in lines).rstrip()
    return text.replace('\\\n', '').replace('\\"', '"')


def verdict(answer):
    first = (answer or '').strip().split('\n', 1)[0].upper()
    return 'VERDICT: PASS' in first


def main():
    a = argparse.ArgumentParser()
    a.add_argument('case'); a.add_argument('attempts'); a.add_argument('correct'); a.add_argument('labels')
    a.add_argument('--review', action='store_true'); a.add_argument('--out')
    o = a.parse_args()
    labels = json.load(open(o.labels))
    attempts, seen = [], set()
    for line in open(o.attempts):
        if not line.strip():
            continue
        e = json.loads(line)
        h = hashlib.sha256((e['requirement'] + e['content']).encode()).hexdigest()
        if h not in seen:
            seen.add(h); attempts.append(e)
    with tempfile.TemporaryDirectory() as d:
        pristine = os.path.join(d, 'pristine')
        rc, out = sh(f'scripts/prepare_module_change.sh {o.case} {pristine}', ROOT)
        if rc:
            sys.exit(out)
        seed = open(os.path.join(ROOT, 'data', o.case, 'bootstrap/seed.txt')).read()
        existing = ''
        for dp, _, fs in os.walk(os.path.join(pristine, 'src/test')):
            for fn in sorted(fs):
                if fn.endswith('.java'):
                    p = os.path.join(dp, fn)
                    existing += f'--- {os.path.relpath(p, pristine)} ---\n' + open(p).read() + '\n'
        rows = []
        for i, e in enumerate(attempts, 1):
            kind = labels.get(e['requirement'])
            if kind is None:
                sys.exit('no label for requirement: ' + e['requirement'])
            p, g = run_one(pristine, e['path'], e['content']), run_one(o.correct, e['path'], e['content'])
            klass = 'WRONG' if g != 'PASS' else ('VACUOUS' if kind == 'new' and p == 'PASS' else 'RIGHT')
            row = {'n': i, 'requirement': e['requirement'], 'attempt': e['attempt'], 'kind': kind,
                   'pristine': p, 'correct': g, 'class': klass,
                   'quote_ok': not e['reason'].startswith('the requirement quotes'),
                   'review_ok': bool(e['accepted']),
                   'pristine_ok': (p != 'PASS') if kind == 'new' else (p == 'PASS'),
                   'no_completeness': COMPLETENESS.search(e['content']) is None}
            if o.review:
                ans = ask(REVISED_REVIEW, f"REQUIREMENT:\n{e['requirement']}\n\nSPECIFICATION:\n{seed}\n\n"
                                          f"THE EXISTING TESTS:\n{existing}\n\nTEST:\n{e['content']}")
                row['revised_review_ok'] = verdict(ans)
                orig = ask(original_review(), "REQUIREMENT:\n" + e['requirement'] + "\n\nSPECIFICATION:\n" + seed
                           + "\n\nTEST:\n" + e['content'])
                row['original_review_ok'] = verdict(orig)
                row['revised_review'] = ans.strip()[:600]
            rows.append(row)
            print(f"[{i}/{len(attempts)}] {klass:8} pristine={p:9} correct={g:9} {kind:8} a{e['attempt']} "
                  f"{e['requirement'][:70]}", flush=True)
    if o.out:
        json.dump(rows, open(o.out, 'w'), indent=1)


if __name__ == '__main__':
    main()
