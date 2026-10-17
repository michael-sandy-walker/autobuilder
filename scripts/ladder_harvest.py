#!/usr/bin/env python3
"""Candidates for the ticket ladder: past commits of this repository that changed main code AND shipped tests,
each with what cutting it out as a toy case would take.

For a commit C the toy case is: the tree as it was before C (C^), cut down to the smallest set of main
sources that compiles with C's tests -- the main files C changed that already existed, plus everything they
and the tests reference, followed transitively through imports and same-package names. The loop gets that
cut-down tree and the ticket; C's own tests are the specification (and, at the harder rung, withheld).

The size of the cut is the point: a class inside a central service drags most of Autobuilder in with it (the
first version of this script followed the whole commit and cut 297 files for the commit parameterized-rows
was made from, where the hand cut took 4). So the cut is the commit's LIGHT core: the changed classes whose own
closure stays within --max-closure, and the commit's tests that reach nothing outside that core's closure.
Every candidate is reported with its band (changed classes in the core), the classes left out, the tests
kept, the closure, and the libraries outside the JDK and this project the core imports.

usage: ladder_harvest.py [--commits N] [--band 1-2|3-5|6-10|11+] [--max-closure N] [--any-library]
"""
import argparse, collections, re, subprocess, sys

ROOT_PKG = 'com.walpro.autobuilder'
MAIN = 'src/main/java/'
TEST = 'src/test/java/'
IMPORT = re.compile(r'^\s*import\s+(?:static\s+)?([\w.]+?)(?:\.\*)?\s*;', re.M)
PACKAGE = re.compile(r'^\s*package\s+([\w.]+)\s*;', re.M)
# Libraries a toy case's module pom can carry (data, not a framework rule): plain jars. Spring, Neo4j and the
# like need an application context the cut cannot bring along; a class importing them is left out of the core.
LIGHT_LIBRARIES = ('com.github.javaparser', 'lombok', 'org.slf4j', 'org.junit', 'org.assertj', 'org.mockito',
                   'com.fasterxml.jackson', 'org.w3c.dom', 'org.xml.sax')
WORD = re.compile(r'\b[A-Z]\w*\b')
QUALIFIED = re.compile(r'(?<![\w.])' + re.escape(ROOT_PKG) + r'(?:\.\w+)+')
# comments and literals name classes without using them: a Javadoc mentioning TestRunGate is no dependency
NOISE = re.compile(r'/\*.*?\*/|//[^\n]*|"""(?:.|\n)*?"""|"(?:\\.|[^"\\\n])*"|\'(?:\\.|[^\'\\])\'', re.S)


def git(*args):
    return subprocess.run(['git', *args], capture_output=True, text=True, check=True).stdout


class Tree:
    """The java sources of one commit, read on demand."""

    def __init__(self, rev):
        self.rev = rev
        self.paths = [p for p in git('ls-tree', '-r', '--name-only', rev).split('\n') if p.endswith('.java')]
        self.by_fqn = {}
        self.by_pkg = collections.defaultdict(dict)
        # test sources, by name: a test reaches its helpers (fixtures, harnesses) the way code reaches code
        self.test_fqn = {}
        self.test_pkg = collections.defaultdict(dict)
        for p in self.paths:
            root = MAIN if p.startswith(MAIN) else TEST if p.startswith(TEST) else None
            if not root:
                continue
            fqn = p[len(root):-5].replace('/', '.')
            pkg, simple = fqn.rsplit('.', 1)
            if root == MAIN:
                self.by_fqn[fqn] = p
                self.by_pkg[pkg][simple] = p
            else:
                self.test_fqn[fqn] = p
                self.test_pkg[pkg][simple] = p
        self._text = {}

    def text(self, path):
        if path not in self._text:
            try:
                self._text[path] = git('show', f'{self.rev}:{path}')
            except subprocess.CalledProcessError:
                self._text[path] = ''
        return self._text[path]

    def refs(self, source, tests=False, unresolved=None):
        """The main files this source refers to: its imports, and names of its own package. With tests, the test
        sources it refers to as well (a fixture class); an import of the project this tree cannot resolve is added
        to unresolved when one is given -- dropped in silence, it shipped a test without its fixture (ladder-grind)."""
        out, external = set(), set()
        fqns = {**self.by_fqn, **self.test_fqn} if tests else self.by_fqn
        pkgs = self.by_pkg
        if tests:
            pkgs = collections.defaultdict(dict, {k: dict(v) for k, v in self.by_pkg.items()})
            for k, v in self.test_pkg.items():
                pkgs[k].update(v)
        pkg = PACKAGE.search(source)
        pkg = pkg.group(1) if pkg else ''
        for imp in IMPORT.findall(source):
            if imp in fqns:
                out.add(fqns[imp])
            elif imp in pkgs:                              # import of a whole package
                out.update(pkgs[imp].values())
            else:
                # a static import or a nested type: the longest prefix that is a class
                parts = imp.split('.')
                for k in range(len(parts) - 1, 1, -1):
                    if '.'.join(parts[:k]) in fqns:
                        out.add(fqns['.'.join(parts[:k])]); break
                else:
                    if imp.startswith(ROOT_PKG):
                        if unresolved is not None:
                            unresolved.add(imp)
                    elif not imp.startswith(('java.', 'javax.')):
                        external.add('.'.join(parts[:3]))
        code = NOISE.sub(' ', source)
        # A project class written out in full where it is used, with no import: GoalGraphService names
        # com.walpro.autobuilder.goal.GoalTransition so, and the cut of 355c6ecb left it out and did not compile.
        for q in QUALIFIED.findall(code):
            parts = q.split('.')
            for k in range(len(parts), 3, -1):
                if '.'.join(parts[:k]) in fqns:
                    out.add(fqns['.'.join(parts[:k])]); break
        same = pkgs.get(pkg, {})
        for w in set(WORD.findall(code)):
            # a sibling test class named in a test is no helper of it
            if w in same and (same[w].startswith(MAIN) or not re.search(r'Tests?$', w)):
                out.add(same[w])
        return out, external

    def closure(self, seeds_sources, tests=False, unresolved=None):
        """Every main file the sources reach, and the external packages on the way; with tests, every test
        helper too (the caller tells them apart by root)."""
        seen, external, todo = set(), set(), []
        for s in seeds_sources:
            r, e = self.refs(s, tests, unresolved)
            todo.extend(r); external |= e
        while todo:
            p = todo.pop()
            if p in seen:
                continue
            seen.add(p)
            r, e = self.refs(self.text(p), tests, unresolved)
            external |= e
            todo.extend(r - seen)
        return seen, external


def band(n):
    return '1-2' if n <= 2 else '3-5' if n <= 5 else '6-10' if n <= 10 else '11+'


def candidates(n):
    log = git('log', '--no-merges', '--no-renames', f'-n{n}', '--numstat', '--format=@@%H %ad %s', '--date=short')
    cur = None
    for line in log.split('\n') + ['@@']:
        if line.startswith('@@'):
            if cur and cur['main'] and cur['test']:
                yield cur
            if line == '@@':
                break
            h, d, s = (line[2:].split(' ', 2) + [''])[:3]
            cur = {'hash': h, 'date': d, 'subject': s, 'main': [], 'test': []}
        elif line and cur is not None:
            parts = line.split('\t')
            if len(parts) == 3 and parts[2].endswith('.java'):
                if parts[2].startswith(MAIN):
                    cur['main'].append(parts[2])
                elif parts[2].startswith(TEST):
                    cur['test'].append(parts[2])


def main():
    a = argparse.ArgumentParser()
    a.add_argument('--commits', type=int, default=400)
    a.add_argument('--band')
    a.add_argument('--max-closure', type=int, default=30)
    a.add_argument('--any-library', action='store_true',
                   help="allow every library Autobuilder itself depends on (the module pom then carries Autobuilder's own dependencies)")
    o = a.parse_args()
    light = (lambda ext: True) if o.any_library else (lambda ext: all(x.startswith(LIGHT_LIBRARIES) for x in ext))
    print('\t'.join(['commit', 'date', 'band', 'core', 'left_out', 'new', 'tests', 'closure', 'external', 'subject']))
    for c in candidates(o.commits):
        before = Tree(c['hash'] + '^')
        core, left, closure, external = [], [], set(), set()
        for p in c['main']:
            text = before.text(p) if p in before.paths else git('show', f"{c['hash']}:{p}")
            files, ext = before.closure([text])
            if len(files) <= o.max_closure and light(ext):
                core.append(p); closure |= files; external |= ext
            else:
                left.append(p)
        if not core:
            continue
        allowed = closure | {p for p in core if p in before.paths}
        tests = []
        for t in c['test']:
            try:
                files, ext = before.closure([git('show', f"{c['hash']}:{t}")])
            except subprocess.CalledProcessError:
                continue                                    # deleted by the commit
            core_simple = {p.rsplit('/', 1)[1][:-5] for p in core}
            reaches_core = any(re.search(r'\b' + n + r'\b', git('show', f"{c['hash']}:{t}")) for n in core_simple)
            if reaches_core and files <= allowed and light(ext):
                tests.append(t)
        if not tests:
            continue
        b = band(len(core))
        if o.band and b != o.band:
            continue
        new = sum(1 for p in core if p not in before.paths)
        print('\t'.join([c['hash'][:10], c['date'], b, str(len(core)), str(len(left)), str(new), str(len(tests)),
                         str(len(allowed)), ','.join(sorted(external)) or '-', c['subject'][:90]]))
        sys.stdout.flush()


if __name__ == '__main__':
    main()
