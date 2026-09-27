#!/usr/bin/env python3
"""The operator's port review packet for a loop-built ticket solution (design/ports/<target>/REVIEW.md).

The operator reviews every port (2026-09-27: "the review stage should be done by me ... which then needs
approval, followed by the decision if the porting goes through or not"). This lays four versions of the
one class the ticket changes side by side and MEASURES them; it writes no opinion. The better/worse
assessment is a separate section, written by an independent comparer, and the decision is the operator's.

  origin     the class at the toy case's bootstrap/origin-rev (what the loop was given)
  loop       what the loop wrote (run-archive/<run>/artifact-src.tar.gz)
  reference  an independent implementation by a writer that saw neither of the others
             (scripts/blind_reference_workspace.sh)
  port       what is (or would be) in Autobuilder's src/ (a commit, or a proposed file)
  proposed   optional: a change proposed after review, not in src/ (--proposed-file, with any other
             file it needs in the module copy via --proposed-extra <source>:<path in the module>)

Usage: scripts/port_review.py --target T --run RUN --reference DIR [--port-commit C | --port-file F]
                              [--differential SCRIPT] [--state STATE]
"""
import argparse
import difflib
import io
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import xml.etree.ElementTree as ET

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
ARCHIVE = os.path.expanduser('~/.autobuilder/run-archive')


def sh(cmd, cwd=ROOT, check=True):
    return subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, check=check).stdout


def seed_names(target):
    seed = open(os.path.join(ROOT, 'data', target, 'bootstrap', 'seed.txt')).read()
    change = re.search(r'The one class to change is (\w+)', seed).group(1)
    spec = re.search(r'The specification is (\w+)', seed).group(1)
    return change, spec


def prepare_script(target):
    """A toy case described as data (bootstrap/module/) is prepared generically; an older one by its own script."""
    if os.path.isdir(os.path.join(ROOT, 'data', target, 'bootstrap', 'module')):
        return os.path.join(ROOT, 'scripts', 'prepare_module_change.sh')
    return os.path.join(ROOT, 'scripts', 'prepare_%s_change.sh' % target.replace('-', '_'))


def class_path(target, change):
    files = os.path.join(ROOT, 'data', target, 'bootstrap', 'module', 'files.txt')
    if os.path.exists(files):
        for line in open(files):
            line = line.strip()
            if line.endswith('/' + change + '.java') and line.startswith('src/main/'):
                return line
    d = tempfile.mkdtemp(prefix='port-review-path-')
    sh([prepare_script(target), target, os.path.join(d, 'm')])
    for base, _, fs in os.walk(os.path.join(d, 'm', 'src', 'main')):
        if change + '.java' in fs:
            rel = os.path.relpath(os.path.join(base, change + '.java'), os.path.join(d, 'm'))
            shutil.rmtree(d, ignore_errors=True)
            return rel
    raise SystemExit('the module has no ' + change + '.java')


def from_tarball(run, rel):
    with tarfile.open(os.path.join(ARCHIVE, run, 'artifact-src.tar.gz')) as t:
        for name in ('./' + rel, rel):
            try:
                return t.extractfile(name).read().decode()
            except KeyError:
                continue
    raise SystemExit('the archived artifact of %s has no %s' % (run, rel))


def metrics(origin, text):
    o, n = origin.splitlines(), text.splitlines()
    sm = difflib.SequenceMatcher(a=o, b=n, autojunk=False)
    added = removed = 0
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag in ('replace', 'delete'):
            removed += i2 - i1
        if tag in ('replace', 'insert'):
            added += j2 - j1
    removed_comments = sum(1 for tag, i1, i2, _, _ in sm.get_opcodes() if tag in ('replace', 'delete')
                           for l in o[i1:i2] if l.strip().startswith(('//', '*', '/**')))
    keywords = {'return', 'new', 'if', 'for', 'while', 'switch', 'catch', 'throw', 'else', 'case'}
    methods = lambda s: {m for m in re.findall(r'^\s*(?:(?:public|private|protected|static|final|default)\s+)+[\w<>\[\], ?]+\s+(\w+)\s*\(', s, re.M)
                         if m not in keywords}
    tables = len(re.findall(r'\{\s*(?:"[^"]*"\s*,\s*){2,}"[^"]*"', text)) - len(re.findall(r'\{\s*(?:"[^"]*"\s*,\s*){2,}"[^"]*"', origin))
    return {'lines': len(n), 'added': added, 'removed': removed, 'removed_comment_lines': removed_comments,
            'new_methods': sorted(methods(text) - methods(origin)), 'new_literal_tables': max(0, tables)}


def run_suite(target, rel, text, spec, differential, out_tsv, extra=None):
    d = tempfile.mkdtemp(prefix='port-review-')
    mod = os.path.join(d, 'm')
    sh([prepare_script(target), target, mod])
    open(os.path.join(mod, rel), 'w').write(text)
    for dest, content in (extra or {}).items():
        os.makedirs(os.path.dirname(os.path.join(mod, dest)), exist_ok=True)
        open(os.path.join(mod, dest), 'w').write(content)
    env = dict(os.environ, MAVEN_OPTS='-Xmx768m')
    subprocess.run(['mvn', '-o', '-q', 'test'], cwd=mod, capture_output=True, text=True, env=env)
    total = fail = 0
    spec_total = spec_pass = 0
    rep = os.path.join(mod, 'target', 'surefire-reports')
    if os.path.isdir(rep):
        for f in os.listdir(rep):
            if not (f.startswith('TEST-') and f.endswith('.xml')):
                continue
            for tc in ET.parse(os.path.join(rep, f)).getroot().iter('testcase'):
                ok = not list(tc) or all(c.tag == 'system-out' or c.tag == 'system-err' for c in tc)
                total += 1
                fail += 0 if ok else 1
                if f.endswith(spec + '.xml'):
                    spec_total += 1
                    spec_pass += 1 if ok else 0
    if differential:
        subprocess.run([differential, mod, out_tsv], capture_output=True, text=True)
    shutil.rmtree(d, ignore_errors=True)
    return {'suite': '%d run, %d failed' % (total, fail) if total else 'did not compile',
            'spec': '%d/%d' % (spec_pass, spec_total) if spec_total else '-'}


def answers(path):
    out = {}
    if os.path.exists(path):
        for line in open(path):
            p = line.rstrip('\n').split('\t')
            if len(p) == 3:
                out[(p[0], p[1])] = p[2]
    return out


def diff_block(a, b, an, bn):
    d = ''.join(difflib.unified_diff(a.splitlines(True), b.splitlines(True), an, bn, n=2))
    return '```diff\n' + (d or '(identical)\n') + '```\n'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--target', required=True)
    ap.add_argument('--run', required=True)
    ap.add_argument('--reference', required=True, help='the blind workspace directory')
    ap.add_argument('--port-commit')
    ap.add_argument('--port-file')
    ap.add_argument('--proposed-file')
    ap.add_argument('--proposed-extra', action='append', default=[])
    ap.add_argument('--differential')
    ap.add_argument('--state', default='awaiting-review')
    a = ap.parse_args()

    change, spec = seed_names(a.target)
    rel = class_path(a.target, change)
    origin_rev = open(os.path.join(ROOT, 'data', a.target, 'bootstrap', 'origin-rev')).read().strip()
    versions = {'origin': sh(['git', 'show', origin_rev + ':' + rel]),
                'loop': from_tarball(a.run, rel),
                'reference': open(os.path.join(a.reference, rel)).read()}
    if a.port_commit:
        versions['port'] = sh(['git', 'show', a.port_commit + ':' + rel.replace('src/main/', 'src/main/')])
    elif a.port_file:
        versions['port'] = open(a.port_file).read()
    extras = {}
    if a.proposed_file:
        versions['proposed'] = open(a.proposed_file).read()
        for spec_ in a.proposed_extra:
            src, dest = spec_.split(':', 1)
            extras[dest] = open(src).read()

    out_dir = os.path.join(ROOT, 'design', 'ports', a.target)
    os.makedirs(out_dir, exist_ok=True)
    for k, v in versions.items():
        os.makedirs(os.path.join(out_dir, k), exist_ok=True)
        open(os.path.join(out_dir, k, change + '.java'), 'w').write(v)
    notes = os.path.join(a.reference, 'REFERENCE-NOTES.md')
    if os.path.exists(notes):
        shutil.copy(notes, os.path.join(out_dir, 'reference', 'REFERENCE-NOTES.md'))

    results, diffs = {}, {}
    for k, v in versions.items():
        tsv = os.path.join(out_dir, k, 'differential.tsv')
        results[k] = run_suite(a.target, rel, v, spec, a.differential, tsv, extras if k == 'proposed' else None)
        results[k].update(metrics(versions['origin'], v))
        diffs[k] = answers(tsv)

    w = io.StringIO()
    w.write('# Port review: %s\n\n' % a.target)
    w.write('- **Ticket toy case:** `data/%s` (seed: change `%s`, specification `%s`)\n' % (a.target, change, spec))
    w.write('- **Loop run:** `%s` (`~/.autobuilder/run-archive/%s/artifact-src.tar.gz`)\n' % (a.run, a.run))
    w.write('- **Port:** %s\n' % ('commit `%s`' % a.port_commit if a.port_commit else ('proposed file' if a.port_file else 'none yet')))
    w.write('- **State:** `%s` -- the operator decides; see Decision at the end.\n' % a.state)
    w.write('- **Reference:** written blind in `%s` from the pristine module, the seed and the spec only. '
            'The writer was instructed not to read anything outside that directory; this machine does not '
            'enforce it.\n\n' % a.reference)
    w.write('The four versions are in `origin/`, `loop/`, `reference/`, `port/` beside this file.\n\n')
    w.write('## Measured (no opinion)\n\n')
    w.write('| version | suite | spec | lines | +added / -removed vs origin | origin comment lines removed | new methods | new literal tables |\n')
    w.write('|---|---|---|---|---|---|---|---|\n')
    for k in versions:
        r = results[k]
        w.write('| %s | %s | %s | %d | +%d / -%d | %d | %s | %d |\n' % (
            k, r['suite'], r['spec'], r['lines'], r['added'], r['removed'], r['removed_comment_lines'],
            ', '.join(r['new_methods']) or '-', r['new_literal_tables']))
    if a.differential:
        keys = sorted(set().union(*[set(d) for d in diffs.values()]))
        w.write('\n### Behaviour beyond the specification\n\n')
        w.write('Every input of `%s`, answered by each version. Listed: the inputs where the versions do NOT all agree '
                '(%d inputs in all).\n\n' % (os.path.relpath(a.differential, ROOT), len(keys)))
        disagree = [k for k in keys if len({diffs[v].get(k) for v in versions if v != 'origin'}) > 1]
        changed = [k for k in keys if k not in disagree and diffs['origin'].get(k) != diffs['loop'].get(k)]
        w.write('Not listed: %d input(s) where loop, reference and port agree with each other but differ from origin '
                '(the change itself); their answers are in `<version>/differential.tsv`.\n\n' % len(changed))
        if not disagree:
            w.write('None: loop, reference and port answer every input identically.\n')
        for key in disagree:
            w.write('- `%s` (tree `%s`)\n' % (key[1][:160], key[0]))
            for v in versions:
                w.write('  - %s: `%s`\n' % (v, (diffs[v].get(key) or '-')[:220]))
    w.write('\n## Diffs\n\n')
    for k in [v for v in versions if v != 'origin']:
        w.write('### origin -> %s\n\n' % k)
        w.write(diff_block(versions['origin'], versions[k], 'origin/' + change + '.java', k + '/' + change + '.java'))
    if 'port' in versions:
        w.write('### loop -> port (what the port review changed in the loop\'s code)\n\n')
        w.write(diff_block(versions['loop'], versions['port'], 'loop/' + change + '.java', 'port/' + change + '.java'))
    if 'proposed' in versions and 'port' in versions:
        w.write('### port -> proposed (the change proposed after this review)\n\n')
        w.write(diff_block(versions['port'], versions['proposed'], 'port/' + change + '.java', 'proposed/' + change + '.java'))
        for dest in extras:
            w.write('\nThe proposed version also needs `%s` in the module (not shown here).\n' % dest)
    w.write('\n## Assessment: better / worse\n\n'
            'In `ASSESSMENT.md` beside this file, written by an independent comparer (a fresh agent) that had no hand '
            'in the loop\'s code, the reference or the port. An opinion, kept apart from the measurements above.\n')
    w.write('\n## Decision (the operator)\n\n'
            '- [ ] approve the port as it is\n- [ ] approve with changes: ...\n- [ ] decline (revert or do not port)\n')
    open(os.path.join(out_dir, 'REVIEW.md'), 'w').write(w.getvalue())
    print(os.path.join(out_dir, 'REVIEW.md'))


if __name__ == '__main__':
    main()
