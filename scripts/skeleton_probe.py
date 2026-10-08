#!/usr/bin/env python3
"""Offline experiment (T6-T, the 'ultimate split'): can a specification's missing surface be stubbed from the
compiler's own errors alone, deterministically, until the specification compiles?

Loop: compile main + tests; for each 'cannot find symbol' error add a stub (a class, a method, a constructor);
for 'Object cannot be converted to T' narrow a stubbed return type; repeat. Stubs throw
UnsupportedOperationException (methods) or are empty (classes). No model involved.

usage: skeleton_probe.py <tree>   (e.g. a tree from scripts/prepare_module_change.sh; it is CHANGED in place)

First result (2026-10-08, ladder-grind v1): the specification compiles after 4 passes (~40 s, 14 stubs: 2 classes,
10 methods, 4 return types narrowed -- two of them read from the assertion the test makes on the value), and the
yardstick scores the stub-only tree 41/51; the 9 failing tests name 4 bodies to write.
"""
import os, re, subprocess, sys

TREE = sys.argv[1]
MAIN = os.path.join(TREE, 'src/main/java')
ERR = re.compile(r'^\[ERROR\] (\S+\.java):\[(\d+),(\d+)\] (.*)$')
SYM = re.compile(r'^\[ERROR\]\s+symbol:\s+(class|method|variable|constructor) (.+)$')
LOC = re.compile(r'^\[ERROR\]\s+location:\s+((?:class|interface|package|variable \w+ of type) )?([\w.$<>,]+)')
# What an assertion on a value says about its type -- the assertion library's own vocabulary: the method the test
# calls on assertThat(x) exists only on the assert for that kind of value.
ASSERT_KIND = {'containsEntry': 'map', 'containsKey': 'map', 'containsKeys': 'map', 'doesNotContainKey': 'map',
               'containsExactly': 'list', 'containsExactlyInAnyOrder': 'list', 'hasSize': 'list', 'contains': 'list',
               'isTrue': 'boolean', 'isFalse': 'boolean', 'startsWith': 'string', 'endsWith': 'string'}
BOX = {'int': 'Integer', 'long': 'Long', 'boolean': 'Boolean', 'double': 'Double'}
stubbed_returns = {}   # (fqn, method) -> return type
log = []


def compile_tree():
    r = subprocess.run(['mvn', '-o', '-q', 'test-compile'], cwd=TREE, capture_output=True, text=True)
    out = re.sub(r'\x1b\[[0-9;]*m', '', r.stdout + r.stderr)
    return r.returncode, out.splitlines()


def errors(lines):
    out = []
    for i, l in enumerate(lines):
        m = ERR.match(l)
        if not m: continue
        e = {'file': m.group(1), 'line': int(m.group(2)), 'msg': m.group(4), 'sym': None, 'loc': None}
        for j in range(i + 1, min(i + 4, len(lines))):
            s = SYM.match(lines[j]); lo = LOC.match(lines[j])
            if s: e['sym'] = (s.group(1), s.group(2))
            if lo: e['loc'] = lo.group(2); e['lockind'] = (lo.group(1) or '').strip()
        out.append(e)
    # the same error is printed twice by the build tool
    seen, uniq = set(), []
    for e in out:
        k = (e['file'], e['line'], e['msg'], e['sym'], e['loc'])
        if k not in seen: seen.add(k); uniq.append(e)
    return uniq


def path_of(fqn):
    return os.path.join(MAIN, fqn.split('<')[0].replace('.', '/') + '.java')


def ensure_class(fqn):
    p = path_of(fqn)
    if os.path.exists(p): return False
    pkg, name = fqn.rsplit('.', 1)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, 'w').write(f'package {pkg};\n\n/** Skeleton: a stub derived from the compiler\'s errors. */\npublic class {name} {{\n}}\n')
    log.append(f'class   {fqn}')
    return True


def add_member(fqn, text, key):
    p = path_of(fqn)
    if not os.path.exists(p): return False
    src = open(p).read()
    if key in src: return False
    i = src.rstrip().rfind('}')
    src = src[:i] + '    ' + text + '\n' + src[i:]
    open(p, 'w').write(src)
    return True


def params(sig):
    inner = sig[sig.index('(') + 1:sig.rindex(')')]
    types = [t.strip() for t in inner.split(',') if t.strip()]
    types = ['Object' if t in ('<nulltype>', '<any>') else t for t in types]
    return ', '.join(f'{t} a{i}' for i, t in enumerate(types))


def step(errs, pkg_of_test):
    changed = 0
    for e in errs:
        kind_sym = e['sym']; loc = e['loc']
        if kind_sym and kind_sym[0] == 'variable' and kind_sym[1][:1].isupper() and not kind_sym[1].isupper():
            # an unknown capitalised receiver is a class called statically
            changed += ensure_class(pkg_of_test(e['file']) + '.' + kind_sym[1])
        elif kind_sym and kind_sym[0] == 'class':
            name = kind_sym[1]
            pkg = loc if loc and not loc[0].isupper() and '.' in loc and not loc.split('.')[-1][0].isupper() else pkg_of_test(e['file'])
            changed += ensure_class(pkg + '.' + name)
        elif kind_sym and kind_sym[0] == 'method' and loc:
            sig = kind_sym[1]; name = sig.split('(')[0]
            fqn = loc.split('<')[0]
            static = 'static ' if e.get('lockind') in ('class', 'interface') and not e['file'].startswith(MAIN) and \
                re.search(r'\b' + re.escape(fqn.rsplit('.', 1)[1]) + r'\.' + re.escape(name) + r'\(', open(e['file']).read().splitlines()[e['line'] - 1]) else ''
            if fqn.startswith('org.assertj'):
                continue
            key = f' {name}('
            stubbed_returns.setdefault((fqn, name), 'Object')
            ret = stubbed_returns[(fqn, name)]
            if add_member(fqn, f'public {static}{ret} {name}({params(sig)}) {{ throw new UnsupportedOperationException("skeleton"); }}', key):
                log.append(f'method  {fqn}.{sig} -> {ret}'); changed += 1
        elif 'constructor' in e['msg'] and 'cannot be applied' in e['msg']:
            m = re.search(r'constructor (\w+) in class ([\w.]+)', e['msg'])
            found = None
            # the 'found:' line follows the error
            if m:
                fqn = m.group(2)
                found = e.get('found')
                if found is not None:
                    args = ', '.join(f'{t.strip()} a{i}' for i, t in enumerate(found.split(',')) if t.strip() and t.strip() != 'no arguments')
                    if add_member(fqn, f'public {m.group(1)}({args}) {{ }}', f'public {m.group(1)}({args})'):
                        log.append(f'ctor    {fqn}({found})'); changed += 1
    return changed


def attach_found(lines, errs):
    for e in errs:
        for i, l in enumerate(lines):
            if l.startswith('[ERROR] ' + e['file'] + ':[' + str(e['line'])):
                for j in range(i + 1, min(i + 5, len(lines))):
                    m = re.match(r'^\[ERROR\]\s+found:\s+(.*)$', lines[j])
                    if m: e['found'] = m.group(1).strip()
                break


def narrow(errs):
    """'Object cannot be converted to T' on a line that calls a stubbed method: that method returns T."""
    changed = 0
    for e in errs:
        m = re.match(r'incompatible types: (?:java\.lang\.)?Object cannot be converted to (.+)$', e['msg'])
        if not m: continue
        want = m.group(1).strip()
        text = open(e['file']).read().splitlines()[e['line'] - 1]
        for (fqn, name), ret in list(stubbed_returns.items()):
            if ret == 'Object' and re.search(r'\.' + name + r'\(', text):
                p = path_of(fqn); src = open(p).read()
                new = src.replace(f'public Object {name}(', f'public {want} {name}(', 1)
                if new != src:
                    open(p, 'w').write(new); stubbed_returns[(fqn, name)] = want
                    log.append(f'narrow  {fqn}.{name} -> {want}'); changed += 1
    # an assertion method missing on ObjectAssert<Object>: the stubbed method on that line returns that kind of value
    for e in errs:
        if not (e['sym'] and e['sym'][0] == 'method' and e['loc'] and e['loc'].startswith('org.assertj.core.api.ObjectAssert')):
            continue
        meth = e['sym'][1].split('(')[0]; kind = ASSERT_KIND.get(meth)
        args = [BOX.get(t.strip(), t.strip().replace('java.lang.', '')) for t in e['sym'][1][e['sym'][1].index('(') + 1:-1].split(',') if t.strip()]
        want = {'map': f'java.util.Map<{args[0] if args else "Object"}, {args[1] if len(args) > 1 else "Object"}>',
                'list': f'java.util.List<{args[0] if args else "Object"}>', 'boolean': 'boolean', 'string': 'String'}.get(kind)
        if not want: continue
        text = open(e['file']).read().splitlines()[e['line'] - 1]
        for (fqn, name), ret in list(stubbed_returns.items()):
            if ret == 'Object' and re.search(r'\.' + name + r'\(', text):
                p = path_of(fqn); src = open(p).read()
                new = re.sub(r'public (static )?Object ' + name + r'\(', lambda m: f'public {m.group(1) or ""}{want} {name}(', src, count=1)
                if new != src:
                    open(p, 'w').write(new); stubbed_returns[(fqn, name)] = want
                    log.append(f'narrow  {fqn}.{name} -> {want} (from .{meth} on its value)'); changed += 1
    return changed


def pkg_of_test(path):
    src = open(path).read()
    m = re.search(r'^package ([\w.]+);', src, re.M)
    return m.group(1) if m else ''


for it in range(1, 25):
    code, lines = compile_tree()
    errs = errors(lines)
    attach_found(lines, errs)
    print(f'iteration {it}: exit={code}, {len(errs)} distinct error(s)')
    if code == 0:
        print('SPEC COMPILES'); break
    n = step(errs, pkg_of_test) + narrow(errs)
    if n == 0:
        print('NO PROGRESS; remaining errors:')
        for e in errs[:12]:
            print('  ', os.path.relpath(e['file'], TREE), e['line'], e['msg'][:110], e['sym'] or '', e['loc'] or '')
        break
print('\n'.join(log))
