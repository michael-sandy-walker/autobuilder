#!/usr/bin/env python3
"""T4-EQ: what a model builds from a seed alone -- one prompt, no loop -- scored by the case's withheld specification.

The loop's own pacing decided the seed comparison of 2026-11-15/16 (two of three runs never reached the second class,
whatever the seed), so this asks the question without the loop: the seed in, the classes the seed names out, the
withheld specification tests laid over them in a scratch project, each test case pass or fail.

  t4eq_direct_build.py <target> [--samples N] [--temperature T] [--out DIR]

The target is a toy case with bootstrap/seed.txt, bootstrap/module/pom.xml and bootstrap/withheld/src/test (the
labelmaker-seed-* cases). The model, its endpoint and the reasoning setting are the run's (application.yml): qwen3.8,
reasoning "none". Temperature 0 is the run's setting and gives one answer per input, so --samples > 1 needs a
temperature above 0 and is reported as such.
"""
import argparse, json, os, re, shutil, subprocess, sys, tempfile, urllib.request
import xml.etree.ElementTree as ET

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
URL = 'http://localhost:11434/v1/chat/completions'
MODEL = 'qwen3.8'

PROMPT = """Below is the specification of a small Java project. Write its source code.

{seed}

Write every class the specification names, complete, under src/main/java. Answer with one fenced block per file,
each opened by ```java followed by the file's path in square brackets, for example:

```java [src/main/java/org/example/Thing.java]
package org.example;
...
```

Nothing else.
"""

FILE = re.compile(r"```java\s*\[([^\]]+)\]\s*\n(.*?)```", re.S)


def ask(seed, temperature):
    body = {'model': MODEL, 'temperature': temperature, 'reasoning_effort': 'none',
            'messages': [{'role': 'system', 'content': 'You are a careful Java developer.'},
                         {'role': 'user', 'content': PROMPT.format(seed=seed)}]}
    req = urllib.request.Request(URL, json.dumps(body).encode(), {'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=600) as r:
        return json.load(r)['choices'][0]['message']['content']


def score(target, files, work):
    b = os.path.join(ROOT, 'data', target, 'bootstrap')
    os.makedirs(work)
    shutil.copy(os.path.join(b, 'module', 'pom.xml'), work)
    shutil.copytree(os.path.join(b, 'withheld', 'src', 'test'), os.path.join(work, 'src', 'test'))
    for path, text in files.items():
        p = os.path.normpath(os.path.join(work, path))
        if not p.startswith(os.path.join(work, 'src', 'main') + os.sep):
            continue
        os.makedirs(os.path.dirname(p), exist_ok=True)
        open(p, 'w').write(text)
    r = subprocess.run(['mvn', '-B', '-q', '-o', 'test', '-Dsurefire.failIfNoSpecifiedTests=false'], cwd=work,
                       capture_output=True, text=True)
    out = r.stdout + r.stderr
    rep = os.path.join(work, 'target', 'surefire-reports')
    cases = {}
    if os.path.isdir(rep):
        for f in os.listdir(rep):
            if f.startswith('TEST-') and f.endswith('.xml'):
                for tc in ET.parse(os.path.join(rep, f)).getroot().iter('testcase'):
                    bad = tc.find('failure') is not None or tc.find('error') is not None
                    cases[tc.get('name')] = 'fail' if bad else 'pass'
    compile_error = None
    if not cases:
        m = re.search(r'\[ERROR\][^\n]*\.java:\[\d+,\d+\][^\n]*', out)
        compile_error = m.group(0)[:300] if m else out.strip()[-300:]
    return cases, compile_error


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('target')
    ap.add_argument('--samples', type=int, default=1)
    ap.add_argument('--temperature', type=float, default=0.0)
    ap.add_argument('--out', default=None)
    a = ap.parse_args()
    if a.samples > 1 and a.temperature == 0:
        sys.exit('temperature 0 gives one answer per input: use --samples 1, or a temperature above 0')
    seed = open(os.path.join(ROOT, 'data', a.target, 'bootstrap', 'seed.txt')).read()
    out = a.out or tempfile.mkdtemp(prefix=f't4eq-direct-{a.target}-')
    os.makedirs(out, exist_ok=True)
    for n in range(1, a.samples + 1):
        answer = ask(seed, a.temperature)
        d = os.path.join(out, f'sample-{n}')
        os.makedirs(d, exist_ok=True)
        open(os.path.join(d, 'answer.md'), 'w').write(answer)
        files = {p.strip(): t for p, t in FILE.findall(answer)}
        cases, err = score(a.target, files, os.path.join(d, 'project'))
        passed = sum(1 for v in cases.values() if v == 'pass')
        line = {'target': a.target, 'temperature': a.temperature, 'sample': n, 'files': sorted(files),
                'passed': passed, 'cases': cases, 'compile_error': err}
        open(os.path.join(d, 'result.json'), 'w').write(json.dumps(line, indent=1))
        print(json.dumps(line), flush=True)


if __name__ == '__main__':
    main()
