#!/usr/bin/env python3
"""The public methods and constructors of Java sources, with their text normalised -- enough to say
whether a declaration that existed before still exists UNCHANGED (signature, annotations and body).

Used by the change-target yardstick for a seed that says "keep the existing public methods as they
are". A brace matcher over comment-free, string-masked source; not a parser, and it does not need to
be one: it compares two versions of the same file, so any declaration it cannot delimit the same way
twice reads as changed, which is the safe direction.

Usage: java_members.py changed <original src/main> <candidate src/main>
  prints one line per public member of the original that is missing or different; nothing if none.
"""
import os
import re
import sys


def _mask(src):
    src = re.sub(r'/\*.*?\*/', lambda m: ' ' * len(m.group()), src, flags=re.S)
    src = re.sub(r'//[^\n]*', lambda m: ' ' * len(m.group()), src)
    return re.sub(r'"(?:\\.|[^"\\\n])*"', lambda m: '"' + 'x' * (len(m.group()) - 2) + '"', src)


SIG = re.compile(r'((?:@[\w.]+(?:\([^)]*\))?\s+)*)public\s+(?:static\s+|final\s+|synchronized\s+)*'
                 r'(?:<[^>]+>\s+)?(?:[\w.<>\[\], ?]+\s+)?(\w+)\s*\(([^)]*)\)\s*(?:throws [\w., ]+)?\s*\{')


TYPE_HEADER = re.compile(r'\b(?:record|class|interface|enum)\s+$')


def _body_end(masked, open_brace):
    depth, i = 0, open_brace
    while i < len(masked):
        depth += masked[i] == '{'
        depth -= masked[i] == '}'
        if depth == 0:
            return i
        i += 1
    return len(masked) - 1


def members(src):
    """{"Name(params)": normalised text} for every public method or constructor with a body, and
    "Name{compact}" for a record's compact constructor. Type declarations are not members."""
    masked = _mask(src)
    out = {}
    records = set(re.findall(r'\brecord\s+(\w+)', masked))
    for m in re.finditer(r'public\s+(\w+)\s*\{', masked):
        if m.group(1) in records:
            i = _body_end(masked, m.end() - 1)
            out[f'{m.group(1)}{{compact}}'] = re.sub(r'\s+', ' ', src[m.start():i + 1]).strip()
    for m in SIG.finditer(masked):
        if TYPE_HEADER.search(masked[m.start():m.start(2)]):
            continue
        depth, i = 0, m.end() - 1
        while i < len(masked):
            depth += masked[i] == '{'
            depth -= masked[i] == '}'
            if depth == 0:
                break
            i += 1
        params = ','.join(p.strip().split()[-2] if len(p.strip().split()) > 1 else p.strip()
                          for p in m.group(3).split(',') if p.strip())
        key = f'{m.group(2)}({params})'
        text = re.sub(r'\s+', ' ', src[m.start():i + 1]).strip()
        out[key] = text
    return out


def changed(orig_root, cand_root):
    report = []
    for d, _, fs in os.walk(orig_root):
        for f in sorted(fs):
            if not f.endswith('.java'):
                continue
            rel = os.path.relpath(os.path.join(d, f), orig_root)
            before = members(open(os.path.join(d, f), encoding='utf-8').read())
            cp = os.path.join(cand_root, rel)
            after = members(open(cp, encoding='utf-8').read()) if os.path.isfile(cp) else {}
            cls = f[:-5]
            for k, v in before.items():
                if k not in after:
                    report.append(f'{cls}.{k} is gone')
                elif after[k] != v:
                    report.append(f'{cls}.{k} was changed')
    return report


if __name__ == '__main__':
    if len(sys.argv) != 4 or sys.argv[1] != 'changed':
        sys.exit(__doc__)
    for line in changed(sys.argv[2], sys.argv[3]):
        print(line)
