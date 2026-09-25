#!/usr/bin/env python3
"""How modular is Autobuilder? The transitive closure of every class in src/main over the codebase's
own dependencies -- the classes one would have to extract with it to run it as a standalone project.

A class's dependencies are the Autobuilder classes it imports and the same-package classes it names
(same-package references need no import). Comments and string literals are ignored.

Used to choose a module to adopt as a toy case (T4-CQ: structdiff and participation were chosen with
it) and to see whether a ticket's change could land in a small closure (T4-CF: a goal from a real
ticket needs one). Baseline 2026-09-25: 478 classes; 145 have a closure of more than 50 classes;
17% of the lines sit in classes whose closure is at most 15 classes and free of Spring wiring.

Usage: module_closures.py summary            the distribution and the extractable share
       module_closures.py of <Class> [...]   closure size, lines, Spring use, outside libraries
       module_closures.py candidates         small, Spring-free closures with no test that names them
Offline, read-only.
"""
import collections
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
MAIN, TEST, PREFIX = os.path.join(ROOT, 'src/main/java'), os.path.join(ROOT, 'src/test/java'), 'com.walpro.autobuilder.'


def load():
    cls = {}
    for d, _, fs in os.walk(MAIN):
        for f in fs:
            if f.endswith('.java'):
                p = os.path.join(d, f)
                cls[os.path.relpath(p, MAIN)[:-5].replace(os.sep, '.')] = open(p, errors='replace').read()
    return cls


def strip(s):
    return re.sub(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"', '', s, flags=re.S)


def dependencies(cls):
    by_pkg = collections.defaultdict(set)
    for fq in cls:
        by_pkg[fq.rsplit('.', 1)[0]].add(fq)
    deps = {}
    for fq, src in cls.items():
        body, ds = strip(src), set()
        for m in re.findall(r'^import (?:static )?(com\.walpro\.autobuilder\.[\w.]+?)(?:\.\*)?;', src, re.M):
            while m and m not in cls and '.' in m:
                m = m.rsplit('.', 1)[0]
            if m in cls:
                ds.add(m)
        for other in by_pkg[fq.rsplit('.', 1)[0]]:
            if other != fq and re.search(r'\b' + other.rsplit('.', 1)[1] + r'\b', body):
                ds.add(other)
        deps[fq] = ds
    return deps


def closure(deps, fq):
    seen, stack = {fq}, [fq]
    while stack:
        for d in deps[stack.pop()]:
            if d not in seen:
                seen.add(d)
                stack.append(d)
    return seen


def describe(cls, deps, fq):
    c = closure(deps, fq)
    ext = set()
    for x in c:
        for i in re.findall(r'^import (?:static )?([\w.]+);', cls[x], re.M):
            if not i.startswith(('java.', 'javax.', 'com.walpro')):
                ext.add('.'.join(i.split('.')[:2]))
    spring = sum(1 for x in c if 'org.springframework' in cls[x])
    return len(c), sum(cls[x].count('\n') for x in c), spring, sorted(ext)


def main(argv):
    cls = load()
    deps = dependencies(cls)
    if not argv or argv[0] == 'summary':
        bands, small, total = collections.Counter(), 0, 0
        for fq in cls:
            n, _, spring, _ = describe(cls, deps, fq)
            band = '<=5' if n <= 5 else '<=15' if n <= 15 else '<=50' if n <= 50 else '>50'
            bands[(band, 'spring' if spring else 'spring-free')] += 1
            lines = cls[fq].count('\n')
            total += lines
            if n <= 15 and not spring:
                small += lines
        print(f'{len(cls)} classes')
        for k in sorted(bands):
            print(f'  closure {k[0]:>4} classes, {k[1]:<11} {bands[k]}')
        print(f'lines in classes with a closure of <=15 classes and no Spring wiring: {small} of {total} ({100 * small // total}%)')
    elif argv[0] == 'of':
        simple = collections.defaultdict(list)
        for fq in cls:
            simple[fq.rsplit('.', 1)[1]].append(fq)
        for name in argv[1:]:
            for fq in simple.get(name, []):
                n, lines, spring, ext = describe(cls, deps, fq)
                print(f'{fq[len(PREFIX):]:60} closure {n:4} classes {lines:6} lines  spring {spring:3}  outside {ext}')
    elif argv[0] == 'candidates':
        tests = []
        for d, _, fs in os.walk(TEST):
            tests += [open(os.path.join(d, f), errors='replace').read() for f in fs if f.endswith('.java')]
        alltests = '\n'.join(tests)
        for fq in sorted(cls):
            n, lines, spring, ext = describe(cls, deps, fq)
            if 2 <= n <= 16 and lines >= 120 and spring == 0:
                named = sum(1 for x in closure(deps, fq) if re.search(r'\b' + x.rsplit('.', 1)[1] + r'\b', alltests))
                print(f'{fq[len(PREFIX):]:60} closure {n:3} classes {lines:5} lines  test-named members {named}  outside {ext}')
    else:
        sys.exit(__doc__)


if __name__ == '__main__':
    main(sys.argv[1:])
