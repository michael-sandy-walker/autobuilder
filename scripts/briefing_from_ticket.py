#!/usr/bin/env python3
"""A ticket toy case's BRIEFING (T4-EA): what a developer would read before working on the ticket.

Writes data/<target>/bootstrap/briefing/:
  ticket.md       the ticket's whole thread (body and appends), ids and memory links removed
  design.md       the design notes the ticket names (--note), if any
  callers.md      where the class to change is used OUTSIDE the extracted module, a few lines around each use
  conventions.md  the code conventions: the memory's "Code and design constraints" rules (always shown)

Everything is read from git at the toy case's bootstrap/origin-rev -- the repository as it stood when the
ticket was written -- so nothing learned later (a solution, a port, a review) reaches the loop.

Usage: scripts/briefing_from_ticket.py --target T --ticket T4-XY [--note .claude-memory/<note>.md ...]
"""
import argparse
import os
import re
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
sys.path.insert(0, os.path.dirname(__file__))
from seed_from_ticket import clean  # noqa: E402  one cleaning rule for seeds and briefings

CONTEXT = 4


def git(*args):
    return subprocess.run(['git', '-C', ROOT] + list(args), capture_output=True, text=True).stdout


def at(rev, path):
    return git('show', '%s:%s' % (rev, path))


def ticket_thread(rev, tid):
    lines = at(rev, '.claude-memory/roadmap.md').split('\n')
    start = next(i for i, l in enumerate(lines) if re.match(r'^- \[.\] \*\*' + re.escape(tid) + r':', l))
    end = start + 1
    while end < len(lines) and (lines[end].startswith('  ') or lines[end] == ''):
        end += 1
    # each top-level append becomes its own section: "- **...**" at column 0
    body = [re.sub(r'^  (- \*\*)', r'\1', l) if re.match(r'^  - \*\*', l) else l for l in lines[start:end]]
    body[0] = re.sub(r'^- \[.\] ', '- ', body[0])
    return '# The ticket\n\n' + clean('\n'.join(body)).strip() + '\n'


def callers(rev, cls, module_files):
    hits = git('grep', '-n', '-w', cls, rev, '--', 'src/main')
    by_file = {}
    for h in hits.splitlines():
        _, path, line, _ = h.split(':', 3)
        if path in module_files or path.endswith('/' + cls + '.java'):
            continue
        by_file.setdefault(path, []).append(int(line))
    out = ['# Where %s is used outside the module\n' % cls]
    for path, nums in sorted(by_file.items()):
        src = at(rev, path).split('\n')
        keep = sorted({n for x in nums for n in range(max(1, x - CONTEXT), min(len(src), x + CONTEXT) + 1)})
        out.append('## %s\n\n```java' % path.replace('src/main/java/', ''))
        prev = None
        for n in keep:
            if prev is not None and n != prev + 1:
                out.append('    ...')
            out.append(src[n - 1])
            prev = n
        out.append('```\n')
    return '\n'.join(out) + '\n' if by_file else ''


def conventions(rev):
    index = at(rev, '.claude-memory/MEMORY.md').split('\n')
    try:
        i = next(k for k, l in enumerate(index) if l.strip().startswith('Code and design constraints'))
    except StopIteration:
        return ''
    files = []
    for l in index[i + 1:]:
        if not l.startswith('- '):
            break
        m = re.match(r'- `([\w.-]+\.md)`', l)
        if m:
            files.append(m.group(1))
    out = ['# Conventions of this codebase\n']
    for f in files:
        text = at(rev, '.claude-memory/' + f)
        body = re.sub(r'(?s)^---.*?---\s*', '', text).strip()
        rule = body.split('\n\n')[0]
        why = re.search(r'\*\*Why:\*\*(.+?)(?:\n\n|\Z)', body, re.S)
        out.append('## %s\n\n%s\n%s' % (f.replace('feedback_', '').replace('.md', '').replace('_', ' '),
                                        clean(rule), ('Why: ' + clean(why.group(1).strip())) if why else ''))
    return '\n\n'.join(out).strip() + '\n'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--target', required=True)
    ap.add_argument('--ticket', required=True)
    ap.add_argument('--note', action='append', default=[])
    a = ap.parse_args()
    b = os.path.join(ROOT, 'data', a.target, 'bootstrap')
    rev = open(os.path.join(b, 'origin-rev')).read().strip()
    seed = open(os.path.join(b, 'seed.txt')).read()
    change = re.search(r'The one class to change is (\w+)', seed).group(1)
    module = set()
    files_txt = os.path.join(b, 'module', 'files.txt')
    if os.path.exists(files_txt):
        module = {l.strip() for l in open(files_txt) if l.strip() and not l.startswith('#')}
    out = os.path.join(b, 'briefing')
    os.makedirs(out, exist_ok=True)
    docs = {'ticket.md': ticket_thread(rev, a.ticket),
            'design.md': '\n'.join('# ' + os.path.basename(n) + '\n\n' + clean(re.sub(r'(?s)^---.*?---\s*', '', at(rev, n)))
                                   for n in a.note),
            'callers.md': callers(rev, change, module),
            'conventions.md': conventions(rev)}
    for name, text in docs.items():
        path = os.path.join(out, name)
        if text.strip():
            open(path, 'w').write(text)
            print('briefing_from_ticket: %s (~%d tokens)' % (os.path.relpath(path, ROOT), len(text) // 4))
        elif os.path.exists(path):
            os.remove(path)


if __name__ == '__main__':
    main()
