#!/usr/bin/env python3
"""A change target's seed, generated from its TICKET's own words (T4-CF: the second goal from a ticket).

The user's requirement (2026-09-26): when a ticket leads to importing a project, the seed must carry
what the ticket names -- otherwise the seed-status rule (T3-CB) correctly treats it as unimportant and
nothing asserts it. And the seed carries the ticket's words, not an algorithm derived from them: the
first toy case finished in one cycle because its seed held the derived decision table.

What it does: quotes the selected passage of a roadmap ticket or a design note, removing only what
names nothing in the adopted project (ticket ids, [[memory links]], run ids); frames it with the
module's README, the specification class and the one class to change; then CHECKS that every
identifier the passage names which exists in the module, and every number, reached the seed.
Identifiers the module does not define are listed as a warning for the operator to rephrase.

Usage: seed_from_ticket.py --passage-from {roadmap:<ID>|<note.md>} [--section <heading text>] [--keep <regex>] [--lines <a-b,c-d>] [--before <date>]
                           --module-dir <prepared tree> --spec <SpecClass> --change <Class> [--out seed.txt]
Exit 1 (and no file) when the check fails.
"""
import argparse
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
TICKET_ID = re.compile(r'\(?`?\bT\d-[A-Z]{1,3}(?:-\d+)?\b`?\)?')
LINK = re.compile(r'\s*\(?\[\[[^\]]+\]\]\)?')
RUN_ID = re.compile(r'`?\b20\d\d-\d\d-\d\d_\d\d-\d\d-\d\d\b`?')


def passage(src, section, keep, before=None, lines_spec=None):
    if src.startswith('roadmap:'):
        tid = src.split(':', 1)[1]
        lines = open(os.path.join(ROOT, '.claude-memory/roadmap.md')).read().split('\n')
        start = next(i for i, l in enumerate(lines) if re.match(r'^- \[.\] \*\*' + re.escape(tid) + r':', l))
        end = start + 1
        while end < len(lines) and (lines[end].startswith('  ') or lines[end] == ''):
            end += 1
        body = lines[start:end]
    else:
        body = open(src).read().split('\n')
    if lines_spec:
        keep_idx = []
        for part in lines_spec.split(','):
            lo, hi = (int(x) for x in part.split('-'))
            keep_idx += range(lo - 1, hi)
        body = [body[i] for i in keep_idx if i < len(body)]
    if section:
        s = next(i for i, l in enumerate(body) if l.lstrip('#- *').startswith(section) or section in l and l.startswith('#'))
        e = next((j for j in range(s + 1, len(body)) if body[j].startswith('#')), len(body))
        body = body[s:e]
    if keep:
        body = [l for l in body if re.search(keep, l)]
    if before:
        # Appends are dated ("- **2026-09-26 ..."); a passage is the ticket as FILED, not what was learned later.
        body = [l for l in body if not (m := re.match(r'\s*- \*\*(20\d\d-\d\d-\d\d)', l)) or m.group(1) < before]
    return '\n'.join(body).strip()


def clean(text):
    for a, b in (('\u2014', '--'), ('\u2013', '-'), ('\u2192', '->'), ('\u2018', "'"), ('\u2019', "'"),
                 ('\u201c', '"'), ('\u201d', '"'), ('\u2026', '...'), ('\u2265', '>='), ('\u2264', '<=')):
        text = text.replace(a, b)
    text = re.sub(r'[^\x00-\x7f]', '', text)   # emoji and markers mean nothing to the adopted project
    text = LINK.sub('', text)
    text = RUN_ID.sub('a recorded run', text)
    text = TICKET_ID.sub('', text)
    text = re.sub(r'\*\*|__', '', text)
    text = re.sub(r'[ \t]{2,}', ' ', text)
    return text


def module_identifiers(module_dir):
    ids = set()
    for d, _, fs in os.walk(os.path.join(module_dir, 'src', 'main')):
        for f in fs:
            if f.endswith('.java'):
                src = open(os.path.join(d, f), errors='replace').read()
                ids.add(f[:-5])
                ids.update(re.findall(r'\b(?:public|static)[^;{=]*?\b(\w+)\s*\(', src))
                ids.update(re.findall(r'\b(?:record|class|enum|interface)\s+(\w+)', src))
    return ids


def named(text):
    return set(re.findall(r'`([^`]+)`', text)) | set(re.findall(r'\b[A-Z][a-z]+(?:[A-Z][a-z0-9]*)+\b', text))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--passage-from', required=True)
    ap.add_argument('--section')
    ap.add_argument('--keep')
    ap.add_argument('--module-dir', required=True)
    ap.add_argument('--spec', required=True)
    ap.add_argument('--change', required=True)
    ap.add_argument('--lines', help='line ranges of the source to quote, e.g. 43-54,80-83')
    ap.add_argument('--before', help='drop dated appends on or after this date (YYYY-MM-DD)')
    ap.add_argument('--out')
    a = ap.parse_args()

    raw = passage(a.passage_from, a.section, a.keep, a.before, a.lines)
    quoted = clean(raw)
    readme = open(os.path.join(a.module_dir, 'README.md')).read().strip().split('\n')
    readme = ' '.join(l for l in readme if not l.startswith('#')).strip()
    seed = f'''  An existing project, adopted, and now asked to grow. It was written by someone else and it
  already builds. In its own words, from its README:

    "{readme}"

  It is a library. Its existing behaviour is characterised by the tests already in src/test/java.
  The change wanted is written in the ticket that asked for it. In the ticket's own words:

{chr(10).join('    ' + l for l in quoted.split(chr(10)))}

  The one class to change is {a.change}. Its signature and the meaning of what it returns are in its
  class comment; its method is not implemented yet.

  The specification is {a.spec} in src/test/java: its tests say exactly what the method must return,
  using real data from the test resources, and they fail until the code provides it.

  OBJECTIVE: every test in {a.spec} passes, and the whole suite passes.

  Change only {a.change} under src/main/java, and read what the code declares through the classes that
  already read it. DO NOT modify anything under src/test: the specification and the existing tests are
  the operator's, and if one of them fails, the code is what is wrong. Keep every other class exactly as
  it is, and keep the project's packages, classes and naming.
'''
    # The check: what the ticket names and the module defines, and every number, reached the seed.
    mod = module_identifiers(a.module_dir)
    wanted = {n for n in named(raw) if re.sub(r'\W.*$', '', n.split('.')[-1].split('(')[0]) in mod}
    missing = sorted(n for n in wanted if n not in seed)
    numbers = set(re.findall(r'(?<![\w-])\d+(?![\w-])', clean(raw)))
    missing += sorted(n for n in numbers if not re.search(r'(?<![\w-])' + re.escape(n) + r'(?![\w-])', seed))
    undefined = sorted(n for n in named(quoted) if n not in wanted and n.split('(')[0] not in mod)
    for n in (a.spec, a.change):
        if n not in seed:
            missing.append(n)
    if missing:
        print('seed_from_ticket: REFUSED -- the seed does not carry what the ticket names: ' + ', '.join(missing), file=sys.stderr)
        sys.exit(1)
    if undefined:
        print('seed_from_ticket: warning -- named in the passage and not defined by the module (rephrase if they '
              'confuse): ' + ', '.join(undefined), file=sys.stderr)
    print(f'seed_from_ticket: {len(wanted)} module identifier(s) and {len(numbers)} number(s) carried', file=sys.stderr)
    if a.out:
        open(a.out, 'w').write(seed)
    else:
        sys.stdout.write(seed)


if __name__ == '__main__':
    main()
