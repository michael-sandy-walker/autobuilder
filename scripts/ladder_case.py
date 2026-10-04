#!/usr/bin/env python3
"""Turns a past commit into a toy case of the ticket ladder (T6-T), in the layout every change target uses
(data/<name>/bootstrap/: seed, protected paths, module/files.txt + pom, spec/, origin-rev, yardstick), so
scripts/prepare_module_change.sh prepares it and preflight runs it like any other.

The cut is ladder_harvest.py's: C's changed classes whose own closure stays within --max-closure, the main
files that closure reaches as they were BEFORE C, the existing tests of the cut that reach nothing outside it,
and C's own tests as the specification. The module pom is Autobuilder's pom as it was before C (every version
C was built against), renamed, without the packaging and diagram plugins. The seed quotes the commit message
as the ticket's words.

usage: ladder_case.py <commit> <name> [--max-closure N] [--any-library] [--withheld]
  --withheld   the harder rung: the specification tests are not given to the loop; the yardstick runs them
"""
import argparse, os, re, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ladder_harvest as h  # noqa: E402

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')


def cut(commit, max_closure, any_library):
    c = next((x for x in h.candidates(2000) if x['hash'].startswith(commit)), None)
    if c is None:
        sys.exit(f'{commit}: not a commit that changed main code and tests')
    before = h.Tree(c['hash'] + '^')
    light = (lambda ext: True) if any_library else (lambda ext: all(x.startswith(h.LIGHT_LIBRARIES) for x in ext))
    core, closure = [], set()
    for p in c['main']:
        text = before.text(p) if p in before.paths else h.git('show', f"{c['hash']}:{p}")
        files, ext = before.closure([text])
        if len(files) <= max_closure and light(ext):
            core.append(p); closure |= files
    allowed = closure | {p for p in core if p in before.paths}
    names = {p.rsplit('/', 1)[1][:-5] for p in core}
    reaches = lambda text: any(re.search(r'\b' + n + r'\b', text) for n in names)
    spec = []
    for t in c['test']:
        try:
            text = h.git('show', f"{c['hash']}:{t}")
        except h.subprocess.CalledProcessError:
            continue
        files, ext = before.closure([text])
        if reaches(text) and files <= allowed and light(ext):
            spec.append(t)
    # The cut's existing tests: the behaviour it already has, which must keep passing.
    existing = []
    for t in before.paths:
        if not t.startswith(h.TEST) or t in spec:
            continue
        text = before.text(t)
        if not reaches(text):
            continue
        files, ext = before.closure([text])
        if files <= allowed and light(ext):
            existing.append(t)
    return c, before, core, sorted(allowed), spec, sorted(existing)


def module_pom(rev, name):
    pom = h.git('show', f'{rev}:pom.xml')
    pom = re.sub(r'(<artifactId>)autobuilder(</artifactId>)', r'\1' + name + r'\2', pom, count=1)
    for plugin in ('spring-boot-maven-plugin', 'plantuml-generator-maven-plugin'):
        pom = re.sub(r'\s*<plugin>(?:(?!</plugin>).)*?<artifactId>' + plugin + r'</artifactId>.*?</plugin>', '', pom, flags=re.S)
    return pom


def seed(c, core, before, spec, withheld):
    changed = sorted(p.rsplit('/', 1)[1][:-5] for p in core if p in before.paths)
    new = sorted(p.rsplit('/', 1)[1][:-5] for p in core if p not in before.paths)
    new_pkgs = sorted({p[len(h.MAIN):].rsplit('/', 1)[0].replace('/', '.') for p in core if p not in before.paths})
    specs = sorted(t.rsplit('/', 1)[1][:-5] for t in spec)
    body = h.git('log', '-1', '--format=%B', c['hash']).strip()
    body = '\n'.join(l for l in body.split('\n') if not l.startswith(('Co-Authored-By', 'Claude-Session')))
    ticket = '\n'.join('    ' + l if l.strip() else '' for l in body.split('\n')).rstrip()
    def listed(xs):
        return xs[0] if len(xs) == 1 else ', '.join(xs[:-1]) + ' and ' + xs[-1]
    out = ['  An existing project, adopted, and now asked to change. It was written by someone else and it already',
           '  builds; its existing behaviour is characterised by the tests in src/test/java.',
           '  The change wanted is written in the ticket that asked for it. In the ticket\'s own words:', '', ticket, '']
    if changed:
        out.append(f"  The classes to change are {listed(changed)}." if len(changed) > 1
                   else f"  The one class to change is {changed[0]}.")
    if new:
        out.append(f"  Add the new class{'es' if len(new) > 1 else ''} {listed(new)} in package{'s' if len(new_pkgs) > 1 else ''} "
                   f"{listed(new_pkgs)}.")
    out.append('  They already work as their class comments say, and their existing tests must keep passing.')
    if withheld:
        out += ['', f"  Write the tests the change needs yourself, beside the existing ones; the operator will check the",
                f"  change with tests of their own."]
    else:
        out += ['', f"  The specification is {listed(specs)} in src/test/java: {'their' if len(specs) > 1 else 'its'} tests say exactly what the",
                '  code must do, and they fail until the code provides it.', '',
                f"  OBJECTIVE: every test in {listed(specs)} passes, and the whole suite passes."]
    out += ['', '  DO NOT modify the existing tests under src/test: they are the operator\'s, and if one of them fails, the',
            '  code is what is wrong. Keep the project\'s packages, classes and naming.']
    return '\n'.join(out) + '\n'


def main():
    a = argparse.ArgumentParser()
    a.add_argument('commit'); a.add_argument('name')
    a.add_argument('--max-closure', type=int, default=60)
    a.add_argument('--any-library', action='store_true')
    a.add_argument('--withheld', action='store_true')
    o = a.parse_args()
    c, before, core, files, spec, existing = cut(o.commit, o.max_closure, o.any_library)
    if not core or not spec:
        sys.exit('no cut: the commit has no light core with tests of its own')
    b = os.path.join(ROOT, 'data', o.name, 'bootstrap')
    if os.path.exists(b):
        sys.exit(f'{b} exists; a toy case is versioned, never overwritten')
    os.makedirs(os.path.join(b, 'module'))
    rev = h.git('rev-parse', c['hash'] + '^').strip()
    open(os.path.join(b, 'origin-rev'), 'w').write(rev + '\n')
    open(os.path.join(b, 'module', 'files.txt'), 'w').write(
        f"# The cut of {c['hash'][:10]} ({c['subject'][:80]}): main sources and their existing tests as they were\n"
        f"# before it, read from git at origin-rev. Generated by scripts/ladder_case.py.\n" + '\n'.join(files + existing) + '\n')
    open(os.path.join(b, 'module', 'pom.xml'), 'w').write(module_pom(rev, o.name))
    open(os.path.join(b, 'module', 'README.md'), 'w').write(
        f"# {o.name}\n\nA part of Autobuilder, cut out as it was before {c['hash'][:10]}: "
        + ', '.join(sorted(p.rsplit('/', 1)[1][:-5] for p in files)) + '.\n')
    spec_root = os.path.join(b, 'withheld' if o.withheld else 'spec')
    for t in spec:
        dst = os.path.join(spec_root, t); os.makedirs(os.path.dirname(dst), exist_ok=True)
        open(dst, 'w').write(h.git('show', f"{c['hash']}:{t}"))
    open(os.path.join(b, 'seed.txt'), 'w').write(seed(c, core, before, spec, o.withheld))
    open(os.path.join(b, 'protected-paths.txt'), 'w').write(
        '# T4-CQ: paths under output/ the loop may not write, one glob per line.\n'
        + ('# The existing tests are the operator\'s; the loop may ADD tests (the withheld rung).\n'
           + '\n'.join(sorted(existing)) + '\n' if o.withheld
           else '# The tests are the operator\'s: the specification and the existing behaviour. The loop changes the code.\nsrc/test/**\n'))
    open(os.path.join(b, 'yardstick'), 'w').write(
        '# T4-DG: the yardstick this target is scored by. One line: <script> [mode]\n'
        'scripts/smoke_ladder_case.sh terminating\n')
    open(os.path.join(b, 'VERSION'), 'w').write('1\n')
    open(os.path.join(b, 'CHANGELOG.md'), 'w').write(
        f"# {o.name}\n\n## 1\nGenerated by scripts/ladder_case.py from {c['hash'][:10]}: {len(core)} classes in the cut "
        f"({sum(1 for p in core if p not in before.paths)} new), {len(files)} main files, {len(existing)} existing tests, "
        f"{len(spec)} specification test(s){' (withheld)' if o.withheld else ''}.\n")
    print(f"{o.name}: {len(core)} classes, {len(files)} main files, {len(existing)} existing tests, {len(spec)} spec -> {b}")


if __name__ == '__main__':
    main()
