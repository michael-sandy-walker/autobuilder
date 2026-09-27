#!/usr/bin/env python3
"""Toy-case versions: which version of a task a run faced, and what changed between versions.

A toy case is data/<target>/bootstrap/ (seed, obligations, spec, module list, origin-rev, briefing, ...). The user,
2026-09-27: "That's why we version the toy cases. If there is more up-to-date code, we could update the toy cases and
version themselves. So we also have a history what changed regarding the toy cases over time."

Files kept in the bootstrap: VERSION (the current version number) and CHANGELOG.md (one entry per version: date,
reason, origin-rev, what changed, and the content digest of the bootstrap as that version).

  toycase_version.py init <target>|--all      number the bootstrap's git history v1..vN (once)
  toycase_version.py bump <target> --reason R [--rev REV]
                                              a new version; --rev moves origin-rev (the module is cut from REV)
                                              and records which module files changed between the two revs
  toycase_version.py check <target>           the version, and whether the bootstrap still matches it
  toycase_version.py status                   every target's version and check
"""
import argparse
import datetime
import hashlib
import os
import re
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
OWN = {'VERSION', 'CHANGELOG.md'}


def boot(t):
    return os.path.join(ROOT, 'data', t, 'bootstrap')


def git(*a):
    return subprocess.run(['git', '-C', ROOT] + list(a), capture_output=True, text=True).stdout.strip()


def digest(t):
    """sha256 over every bootstrap file but VERSION and CHANGELOG.md, path and content, in path order."""
    h = hashlib.sha256()
    b = boot(t)
    for d, _, fs in sorted(os.walk(b)):
        for f in sorted(fs):
            rel = os.path.relpath(os.path.join(d, f), b)
            if rel in OWN:
                continue
            h.update(rel.encode())
            with open(os.path.join(d, f), 'rb') as fh:
                h.update(fh.read())
    return h.hexdigest()[:16]


def version(t):
    p = os.path.join(boot(t), 'VERSION')
    return int(open(p).read().strip()) if os.path.exists(p) else 0


def recorded_digest(t, v):
    p = os.path.join(boot(t), 'CHANGELOG.md')
    if not os.path.exists(p):
        return None
    m = re.search(r'^## v%d .*?\n(?:.*\n)*?- bootstrap digest: `([0-9a-f]+)`' % v, open(p).read(), re.M)
    return m.group(1) if m else None


def origin(t):
    p = os.path.join(boot(t), 'origin-rev')
    return open(p).read().strip() if os.path.exists(p) else None


def targets():
    return sorted(d for d in os.listdir(os.path.join(ROOT, 'data')) if os.path.isdir(boot(d)))


def init(t):
    if version(t):
        print('%s: already versioned (v%d)' % (t, version(t)))
        return
    log = git('log', '--reverse', '--date=short', '--format=%h|%ad|%s', '--', 'data/%s/bootstrap' % t)
    commits = [c.split('|', 2) for c in log.splitlines() if c]
    entries = ['# %s -- toy-case versions\n' % t,
               'Versions v1..v%d are reconstructed from the git history of `data/%s/bootstrap/` (one per commit that '
               'changed it); each names that commit. Later versions are recorded as they are made (`toycase_version.py bump`).\n'
               % (len(commits), t)]
    for i, (h, date, subject) in enumerate(commits, 1):
        entries.append('## v%d -- %s\n\n- reconstructed from commit `%s`: %s' % (i, date, h, subject))
    uncommitted = bool(git('status', '--porcelain', '--', 'data/%s/bootstrap' % t))
    n = len(commits) + (1 if uncommitted or not commits else 0)
    tail = ('- origin-rev: `%s`\n' % origin(t) if origin(t) else '') + '- bootstrap digest: `%s`' % digest(t)
    if n > len(commits):
        entries.append('## v%d -- %s\n\n- reason: the bootstrap as it stands, with changes not yet committed\n%s'
                       % (n, datetime.date.today(), tail))
    else:
        entries[-1] += '\n' + tail
    open(os.path.join(boot(t), 'CHANGELOG.md'), 'w').write('\n\n'.join(entries) + '\n')
    open(os.path.join(boot(t), 'VERSION'), 'w').write('%d\n' % n)
    print('%s: v%d (%d reconstructed)' % (t, n, len(commits)))


def bump(t, reason, rev):
    v = version(t)
    if not v:
        sys.exit('%s is not versioned yet: run init first' % t)
    lines = ['- reason: %s' % reason]
    old = origin(t)
    if rev:
        new = git('rev-parse', rev)
        if not new:
            sys.exit('unknown rev ' + rev)
        open(os.path.join(boot(t), 'origin-rev'), 'w').write(new + '\n')
        lines.append('- origin-rev: `%s` -> `%s`' % (old[:8] if old else '-', new[:8]))
        files = os.path.join(boot(t), 'module', 'files.txt')
        if old and os.path.exists(files):
            paths = [l.strip() for l in open(files) if l.strip() and not l.startswith('#')]
            stat = git('diff', '--stat', old, new, '--', *paths)
            lines.append('- module files changed between the revs:\n\n```\n%s\n```' % (stat or '(none)'))
            if stat:
                lines.append('- regenerate what is cut from the module (seed, briefing) and re-check the spec against it')
    lines.append('- bootstrap digest: `%s`' % digest(t))
    with open(os.path.join(boot(t), 'CHANGELOG.md'), 'a') as fh:
        fh.write('\n## v%d -- %s\n\n%s\n' % (v + 1, datetime.date.today(), '\n'.join(lines)))
    open(os.path.join(boot(t), 'VERSION'), 'w').write('%d\n' % (v + 1))
    print('%s: v%d -> v%d' % (t, v, v + 1))


def check(t):
    v = version(t)
    if not v:
        return 'unversioned', False
    rec = recorded_digest(t, v)
    ok = rec == digest(t)
    return 'v%d%s' % (v, '' if ok else ' + UNVERSIONED CHANGE (bootstrap differs from v%d: bump it)' % v), ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('cmd', choices=['init', 'bump', 'check', 'status'])
    ap.add_argument('target', nargs='?')
    ap.add_argument('--all', action='store_true')
    ap.add_argument('--reason')
    ap.add_argument('--rev')
    a = ap.parse_args()
    if a.cmd == 'init':
        for t in (targets() if a.all else [a.target]):
            init(t)
    elif a.cmd == 'bump':
        if not a.reason:
            sys.exit('--reason is required: the changelog says why a version exists')
        bump(a.target, a.reason, a.rev)
    elif a.cmd == 'check':
        text, ok = check(a.target)
        print('toycase=%s %s' % (a.target, text))
        sys.exit(0 if ok else 1)
    else:
        for t in targets():
            print('%-22s %s' % (t, check(t)[0]))


if __name__ == '__main__':
    main()
