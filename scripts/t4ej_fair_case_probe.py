#!/usr/bin/env python3
"""T4-EJ fair-case probe: the run's model, given pantry's tree and the adapted capability's description with NO
reference offer, writes the change; the written Pantry is compiled with the withheld spec laid over it.
usage: t4ej_fair_case_probe.py <repo root> <out dir> <k> -- one JSON line per call. <out dir> must hold system.txt:
EvolutionProtocolContract.getSystemPrompt() (jshell --class-path target/classes). PROBE_TEMPERATURE (default 0)."""
import json, os, re, shutil, subprocess, sys, time, urllib.request
ROOT, OUT, K = sys.argv[1], sys.argv[2], int(sys.argv[3])
C = os.path.join(ROOT, 'data/labelmaker-pantry/bootstrap')
R = os.path.join(C, 'receiver/output')
system = open(os.path.join(OUT, 'system.txt')).read()
seed = open(os.path.join(C, 'seed.txt')).read()
readme = open(os.path.join(C, 'README.md')).read()
desc = ' '.join(l.lstrip('> ').strip() for l in readme.split('\n') if l.startswith('>'))
files = ['src/main/java/org/pantry/store/Box.java', 'src/main/java/org/pantry/store/Pantry.java',
         'src/test/java/org/pantry/store/PantrySpecTest.java', 'pom.xml']
src = '\n'.join(f"```{'xml' if f.endswith('xml') else 'java'} [{f}]\n{open(os.path.join(R, f)).read()}```\n" for f in files)
cap = ("Capabilities taken from labelmaker, as chosen:\n- Label codes, adapted -- re-implemented in this project's own "
       "structure, none of labelmaker's code for it placed: " + desc)
user = f"""Current Goal: {seed}

{cap}
Current SubGoal: {cap}

--- PROBLEM CONTEXT ---
(none)

--- SOURCE OF THE EXISTING FILES THIS WORK NAMES ---
These files exist, and this is exactly what they say now. To change one, send an edit block: each SEARCH part copied exactly from here, then what replaces it; everything you leave out stays as it is.

{src}
Respond with:
- One or more edit blocks for files that already exist (only the parts you change), and code blocks for new files
- Or one or more #Delete directives
- Or `#NoDiff` on its own line (followed by a one-sentence explanation) if the current output already satisfies all criteria

Do not return unchanged files.
Do not include commentary outside code blocks (except when responding with #NoDiff).


--- PROTECTED PATHS (declared by the operator) ---
These paths are not yours to change; every write to them is refused:
  - src/test/**
"""
open(os.path.join(OUT, 'user.txt'), 'w').write(user)
for i in range(K):
    temp = float(os.environ.get('PROBE_TEMPERATURE', '0'))
    body = {'model': 'qwen3.8', 'temperature': temp, 'max_tokens': 8192,
            'messages': [{'role': 'system', 'content': system}, {'role': 'user', 'content': user}]}
    req = urllib.request.Request('http://localhost:11434/v1/chat/completions', data=json.dumps(body).encode(),
                                 headers={'Content-Type': 'application/json'})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=900) as r:
        text = json.load(r)['choices'][0]['message'].get('content') or ''
    open(os.path.join(OUT, f'answer-{i}.txt'), 'w').write(text)
    # Every whole-file block the model wrote for src/main replaces that file in a scratch copy (edit blocks are
    # applied only when they are whole files: a probe, not the loop's merge -- unapplied answers are reported).
    work = os.path.join(OUT, f'tree-{i}'); shutil.rmtree(work, ignore_errors=True); shutil.copytree(R, work)
    blocks = re.findall(r'```\w*\s*\[([^\]\n]+)\]\n(.*?)```', text, re.S)
    whole = [(p, b) for p, b in blocks if '<<<<<<< SEARCH' not in b]
    for p, b in whole:
        if p.startswith('src/main/'):
            os.makedirs(os.path.dirname(os.path.join(work, p)), exist_ok=True); open(os.path.join(work, p), 'w').write(b)
    edit_problems = []
    for p, b in blocks:
        if '<<<<<<< SEARCH' not in b or not p.startswith('src/main/'): continue
        f = os.path.join(work, p); cur = open(f).read() if os.path.exists(f) else ''
        for srch, repl in re.findall(r'<<<<<<< SEARCH\n(.*?)\n?=======\n(.*?)\n?>>>>>>> REPLACE', b, re.S):
            if cur.count(srch) == 1: cur = cur.replace(srch, repl)
            else: edit_problems.append(f'{p}: SEARCH occurs {cur.count(srch)} times')
        open(f, 'w').write(cur)
    shutil.copytree(os.path.join(C, 'withheld/src/test'), os.path.join(work, 'src/test'), dirs_exist_ok=True)
    subprocess.run(['mvn', '-B', '-q', '-o', 'test'], cwd=work, capture_output=True, timeout=600)
    rep = os.path.join(work, 'target/surefire-reports/org.pantry.store.PantryLabelSpecTest.txt')
    res = open(rep).read().split('\n')[3].strip() if os.path.exists(rep) else 'no report (did not compile or not applied)'
    codes = sorted(set(re.findall(r'[A-Z]{2}-\d{5}-[A-Z]', text)))
    print(json.dumps({'i': i, 'temperature': temp, 'seconds': round(time.time() - t0), 'blocks': [p for p, _ in blocks],
                      'whole_files_applied': [p for p, _ in whole if p.startswith('src/main/')],
                      'withheld': res, 'edit_problems': edit_problems, 'codes_in_answer': codes, 'mentions_reference': 'labelmaker' in text.lower(), 'answer_sha': __import__('hashlib').sha256(text.encode()).hexdigest()[:12]}), flush=True)
