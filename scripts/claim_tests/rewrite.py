#!/usr/bin/env python3
"""T6-U, the badly written test: a claim test that fails on every throwaway implementation is written ONCE more, the
writer shown what the runner (or the compiler) reported for it on the first implementation -- and told that the
implementation may be the one that is wrong. The rewrite is kept only when the claim's quoted words are still in it
and it then passes on at least one throwaway implementation, as any new test must.

Why: scoring the dropped tests of five writings on the reference trees (2026-10-27) -- 65 of 68 in field111's two
writings fail on the ticket's real implementation, so the spikes dropped them rightly; but most are right CLAIMS with
a broken test (an edit block with no closing fence, an invented result type), and edit-hunks' "occurs N times" kept
no test in any of three writings. Repairing the implementations cannot recover those: the test is what is wrong.

Offline, over a writing's directory (derived-tests.json + derived-spikes/spike-*). Writes rewrite.json beside --out:
every dropped test, its rewrite, and where each passed; then score with score_frozen.py.

Usage: rewrite.py <case> <writing-dir> <out.json>
"""
import argparse, json, os, re, shutil, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.dirname(HERE)); sys.path.insert(0, HERE)
from derived_test_replay import ask, run_one, sh  # noqa: E402
from claim_tests import WRITE, fenced  # noqa: E402

REWRITE = WRITE + """

Your earlier test for this claim was run against an implementation written from the same specification, and the \
report of that run is shown. That implementation may itself be wrong. Correct the test where IT is wrong -- how it \
builds its input, how it calls the code, what it names -- and keep the claim's expected outcome exactly as the claim \
and the specification give it."""

QUOTED = re.compile(r'"([^"]{3,})"|`([^`]{3,})`')


def comparable(t):
    return re.sub(r'\s+', ' ', t.replace('\\', '')).strip()


def quoted_pattern(q):
    text = comparable(q)
    out, at = '', 0
    for m in re.finditer(r'(?<![A-Za-z0-9_])[A-Z](?![A-Za-z0-9_])', text):
        out += re.escape(text[at:m.start()]) + r'[A-Za-z0-9_.]+'
        at = m.end()
    return re.compile(out + re.escape(text[at:]))


def missing_quotes(text, test):
    body = comparable(test)
    return [a or b for a, b in QUOTED.findall(text) if not quoted_pattern(a or b).search(body)]


def report(tree, path, content):
    """What the build said about this one test in a copy of tree: compiler errors naming it, or its failures."""
    with tempfile.TemporaryDirectory() as d:
        t = os.path.join(d, 't')
        shutil.copytree(tree, t, ignore=shutil.ignore_patterns('target'))
        f = os.path.join(t, path)
        os.makedirs(os.path.dirname(f), exist_ok=True)
        open(f, 'w').write(content)
        cls = os.path.basename(path).rsplit('.', 1)[0]
        rc, out = sh(f'mvn -B -o test -Dtest={cls} -Dsurefire.failIfNoSpecifiedTests=false', t)
        reports = os.path.join(t, 'target/surefire-reports')
        for x in sorted(os.listdir(reports)) if os.path.isdir(reports) else []:
            if x.endswith(f'.{cls}.txt'):
                return '\n'.join(open(os.path.join(reports, x)).read().splitlines()[:40])[:3000]
        # no report: it did not compile -- the compiler's lines about this file
        all_lines = out.splitlines()
        lines = []
        for i, l in enumerate(all_lines):
            if '[ERROR]' in l and os.path.basename(path) in l:
                lines += [x.replace(t + '/', '') for x in all_lines[i:i + 3]]   # "symbol:" / "location:" follow
        return '\n'.join(lines)[:3000] or out[-2000:]


def context_of(case):
    seed = open(os.path.join(ROOT, 'data', case, 'bootstrap/seed.txt')).read()
    pristine = tempfile.mkdtemp(); os.rmdir(pristine)
    rc, out = sh(f'scripts/prepare_module_change.sh {case} {pristine}', ROOT)
    if rc:
        sys.exit(out)
    tests = {}
    for dp, _, fs in os.walk(os.path.join(pristine, 'src/test')):
        for fn in sorted(fs):
            if fn.endswith('.java'):
                p = os.path.join(dp, fn)
                tests[os.path.relpath(p, pristine)] = open(p).read()
    existing = ''.join(f'--- {k} ---\n{v}\n' for k, v in sorted(tests.items()))
    cpf = os.path.join(tempfile.gettempdir(), 'rewrite-classpath.txt')
    if not os.path.exists(cpf):
        sh(f'mvn -q -o dependency:build-classpath -Dmdep.outputFile={cpf}', ROOT)
    cp = open(cpf).read().strip()
    mains = [os.path.join(dp, f) for dp, _, fs in os.walk(os.path.join(pristine, 'src/main')) for f in fs if f.endswith('.java')]
    rc, outline = sh(f"java -cp target/classes:{cp} scripts/claim_tests/Outline.java {' '.join(mains)}", ROOT)
    outline = outline.replace(pristine + '/', '')
    return (f"SPECIFICATION:\n{seed}\n\nTHE PROJECT'S DECLARATIONS AS THEY ARE NOW (bodies left out):\n{outline}\n\n"
            f"THE EXISTING TESTS:\n{existing}")


def main():
    a = argparse.ArgumentParser()
    a.add_argument('case'); a.add_argument('writing'); a.add_argument('out')
    o = a.parse_args()
    entries = json.load(open(os.path.join(o.writing, 'derived-tests.json')))
    spikes = sorted(os.path.join(o.writing, 'derived-spikes', s) for s in os.listdir(os.path.join(o.writing, 'derived-spikes'))
                    if re.fullmatch(r'spike-\d+', s))
    context = context_of(o.case)
    by_path = {}
    for e in entries:
        if e.get('status') != 'ACCEPTED' and e.get('path'):
            by_path.setdefault(e['path'], []).append(e)
    rows = []
    for path, es in by_path.items():
        e = es[0]
        name = os.path.basename(path).rsplit('.', 1)[0]
        said = report(spikes[0], path, e['content'])
        header = f"```java [{path}]"
        answer = ask(REWRITE, f"CLAIM: {e['claim']}\n\nREQUIREMENT:\n{e['requirement']}\n\n{context}\n\nYOUR EARLIER TEST:\n"
                     f"```java [{path}]\n{e['content']}```\n\nTHE RUN ON ONE IMPLEMENTATION REPORTED:\n{said}\n\nName the class "
                     f"{name}. Open the block exactly as:\n{header}")
        new = fenced(answer, name)
        why = None
        if new is None:
            why = 'no test in the answer'
        elif missing_quotes(e['claim'], new):
            why = 'dropped the claim\'s quoted words ' + str(missing_quotes(e['claim'], new))
        passes = [run_one(s, path, new) for s in spikes] if why is None else []
        row = {'path': path, 'claims': [x['claim'] for x in es], 'requirement': e['requirement'],
               'claim': e['claim'], 'old': e['content'], 'report': said, 'content': new, 'refused': why,
               'spikes': passes, 'status': 'ACCEPTED' if 'PASS' in passes else 'UNACCEPTED',
               'reason': why or f"rewrite passed on {passes.count('PASS')} of {len(spikes)}"}
        rows.append(row)
        print(f"{row['status']:10} {row['reason'][:60]:60} {e['claim'][:80]}", flush=True)
        json.dump(rows, open(o.out, 'w'), indent=1)
    print(f"\ndropped {len(rows)}, rewritten and accepted {sum(r['status'] == 'ACCEPTED' for r in rows)}, "
          f"refused {sum(bool(r['refused']) for r in rows)}")


if __name__ == '__main__':
    main()
