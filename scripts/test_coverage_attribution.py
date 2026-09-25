#!/usr/bin/env python3
"""Which test methods cover an entry point: call it on its own type AND assert a value.

The one home of this rule for the adopted-library yardsticks (scripts/smoke_participation.sh) and
for the code-lineage overlap measurement (scripts/code_lineage_overlap.py), so the score and the
measurement cannot drift apart.

A call is attributed to a type by its RECEIVER, because a method name can exist on more than one
type: the type itself (a static call), a local, field or parameter declared with that type (or
`var x = Type.of(..)` / `new Type(..)`), or a call chain that starts at the type -- read by walking
back over the chain, with comments removed first. A value assertion is an equality, exact
contents, a size or null (assertNull is assertEquals(null, x)); for a boolean entry point also
assertTrue/assertFalse around the call, or assertThat(<call>).isTrue()/isFalse(). assertNotNull
and "not empty" do not count. Methods are split at @Test; a helper method's assertions count only
inside the test that holds them.

Usage: test_coverage_attribution.py covered <src/test dir> <Type> <method>   -> covered|uncovered
"""
import os
import re
import sys

VALUE = re.compile(r'assertEquals\(|assertNull\(|assertIterableEquals\(|assertArrayEquals\(|assertLinesMatch\(|assertSame\('
                   r'|\.isEqualTo\(|\.isCloseTo\(|\.containsExactly(InAnyOrder)?\(|\.hasSize\(|\.containsOnly\(|\.isSameAs\('
                   r'|\.isNull\(|\.containsEntry\(|\.isEmpty\(')


def _names_of_type(src, cls):
    t = re.escape(cls)
    ns = set(re.findall(r'(?<![\w.])' + t + r'(?:<[^>]*>)?\s+(\w+)\s*[=;,)]', src))
    ns |= set(re.findall(r'\bvar\s+(\w+)\s*=\s*(?:new\s+' + t + r'\b|' + t + r'\.\w+\()', src))
    return ns


def _receiver(chunk, dot):
    """Walk back from the '.' before the method over identifiers, dots and balanced (...) groups."""
    i = dot
    while i > 0:
        c = chunk[i - 1]
        if c == ')':
            depth, j = 0, i - 1
            while j >= 0:
                depth += chunk[j] == ')'
                depth -= chunk[j] == '('
                if depth == 0:
                    break
                j -= 1
            i = j
        elif c.isalnum() or c in '_.':
            i -= 1
        elif c.isspace() and chunk[:i].rstrip().endswith('new'):
            i = len(chunk[:i].rstrip()) - 3
        else:
            break
    return chunk[i:dot].strip()


def _attributed(chunk, names, cls, m):
    for mt in re.finditer(r'\.' + re.escape(m) + r'\(', chunk):
        recv = _receiver(chunk, mt.start())
        head = re.split(r'[.(\s]', recv)[0] if recv else ''
        if head == 'new':
            head = re.split(r'[.(\s]', recv[3:].strip())[0]
        if head == cls or head in names:
            return True
    return False


def strip_comments(src):
    src = re.sub(r'/\*.*?\*/', ' ', src, flags=re.S)
    return re.sub(r'//[^\n]*', '', src)


def test_methods(root):
    """(relative file, method name, chunk) for every @Test in every .java under root."""
    out = []
    for d, _, fs in os.walk(root):
        for f in sorted(fs):
            if not f.endswith('.java'):
                continue
            path = os.path.join(d, f)
            src = strip_comments(open(path, encoding='utf-8', errors='replace').read())
            for chunk in src.split('@Test')[1:]:
                name = re.search(r'void\s+(\w+)\s*\(', chunk)
                out.append((os.path.relpath(path, root), name.group(1) if name else '?', chunk, src))
    return out


def covering(root, cls, m):
    """The (file, method) pairs that call cls.m on its own type and assert a value."""
    truth = re.compile(r'assert(True|False)\([^;]*\.' + re.escape(m) + r'\(|assertThat\([^;]*\.' + re.escape(m)
                       + r'\([^;]*\)\s*\.(isTrue|isFalse)\(')
    hits, names_cache = [], {}
    for rel, name, chunk, src in test_methods(root):
        if not re.search(r'\b' + re.escape(cls) + r'\b', src):
            continue
        names = names_cache.setdefault(rel, _names_of_type(src, cls))
        if _attributed(chunk, names, cls, m) and (VALUE.search(chunk) or truth.search(chunk)):
            hits.append((rel, name))
    return hits


if __name__ == '__main__':
    if len(sys.argv) != 5 or sys.argv[1] != 'covered':
        sys.exit(__doc__)
    print('covered' if covering(sys.argv[2], sys.argv[3], sys.argv[4]) else 'uncovered')
