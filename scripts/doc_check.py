#!/usr/bin/env python3
"""Keeps docs/ true to the code: every class, configuration key, script and endpoint the documents name in
backticks must still exist. A rename that orphans the documentation fails here instead of rotting silently,
which is what happened to README.md and to CLAUDE.md's "scheduled every 60 s".

What is checked, in docs/technical.md and docs/functional.md (configuration.md is generated from the code):
  `CamelCase` / `CamelCase.member`   a class of that name under src/main/java or src/main/resources/static
  `autobuilder.*` `llm.*` `spring.*` `server.*` `file.*`   a key the code reads or application.yml sets
                                                           (a trailing .* names a prefix)
  `scripts/...`                      an existing file
  `/autobuilder/...` `/events/...` `/files/...` `/tts/...`   a path some controller maps

usage: doc_check.py   (exit 1 and a list when something named is gone)
"""
import os, re, sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
DOCS = ['docs/technical.md', 'docs/functional.md']
TICK = re.compile(r'`([^`\n]+)`')
CAMEL = re.compile(r'^([A-Z][a-z0-9]+(?:[A-Z][a-z0-9]*)+)(?:\.[A-Za-z_]\w*)?(?:\(\))?$')
KEY = re.compile(r'^((?:autobuilder|llm|spring|server|file)\.[a-z0-9.*-]+)(?:=.*)?$')
FILE = re.compile(r'\.(?:json|ndjson|txt|md|html|log|xml|yml|jar|java|sh|py)$')
ENDPOINT = re.compile(r'^(?:(?:GET|POST|PUT|PATCH|DELETE) )?(/(?:autobuilder|events|files|tts)(?:/[\w{}.:-]*)*)')


def classes():
    out = set()
    for base in ('src/main/java', 'src/main/resources'):
        for d, _, files in os.walk(os.path.join(ROOT, base)):
            for f in files:
                if f.endswith('.java'):
                    out.add(f[:-5])
                    text = open(os.path.join(d, f), encoding='utf-8').read()
                    out.update(re.findall(r'\b(?:class|record|interface|enum)\s+([A-Z]\w*)', text))
    return out


def keys():
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import doc_config_reference as ref
    out = set(ref.code_keys())
    try:
        import yaml
        for doc in yaml.safe_load_all(open(os.path.join(ROOT, 'src/main/resources/application.yml'))):
            def flat(d, p=''):
                if isinstance(d, dict):
                    for k, v in d.items():
                        yield from flat(v, f'{p}.{k}' if p else str(k))
                else:
                    yield p
            out.update(flat(doc or {}))
    except ImportError:
        pass
    return out


def endpoints():
    out = []
    for d, _, files in os.walk(os.path.join(ROOT, 'src/main/java')):
        for f in files:
            if not f.endswith('.java'):
                continue
            text = open(os.path.join(d, f), encoding='utf-8').read()
            if '@RestController' not in text and '@Controller' not in text:
                continue
            base = re.search(r'@RequestMapping\(\s*(?:value\s*=\s*|path\s*=\s*)?"([^"]*)"', text)
            base = base.group(1) if base else ''
            for m in re.finditer(r'@(?:Get|Post|Put|Delete|Patch)Mapping\(\s*(?:value\s*=\s*|path\s*=\s*)?"([^"]*)"', text):
                out.append(base + m.group(1))
            for m in re.finditer(r'@(?:Get|Post|Put|Delete|Patch)Mapping\s*(?:\(\s*\))?\s*\n', text):
                out.append(base)
    return out


def endpoint_known(path, mapped):
    want = re.sub(r'\{[^}]*\}', '{}', path.rstrip('/'))
    for m in mapped:
        have = re.sub(r'\{[^}]*\}', '{}', m.rstrip('/'))
        if have == want or have.startswith(want + '/') or (want.endswith('...') and have.startswith(want[:-3])):
            return True
    return False


def main():
    known_classes, known_keys, mapped = classes(), keys(), endpoints()
    problems = []
    for doc in DOCS:
        path = os.path.join(ROOT, doc)
        for n, line in enumerate(open(path, encoding='utf-8'), 1):
            for token in TICK.findall(line):
                t = token.strip()
                if t.startswith('scripts/'):
                    script = t.split()[0]
                    if not os.path.exists(os.path.join(ROOT, script)):
                        problems.append(f'{doc}:{n}: script {script} not found')
                    continue
                if FILE.search(t):
                    continue   # a file name, e.g. GoalStack.json or autobuilder.log
                c, k, e = CAMEL.match(t), KEY.match(t), ENDPOINT.match(t)
                if c and c.group(1) not in known_classes:
                    problems.append(f'{doc}:{n}: class {c.group(1)} not found')
                elif k:
                    key = k.group(1)
                    if key.endswith('.*'):
                        if not any(x.startswith(key[:-1]) for x in known_keys):
                            problems.append(f'{doc}:{n}: no key under {key}')
                    elif key not in known_keys:
                        problems.append(f'{doc}:{n}: key {key} not read by the code nor set in application.yml')
                elif e and not endpoint_known(e.group(1), mapped):
                    problems.append(f'{doc}:{n}: endpoint {e.group(1)} not mapped by any controller')
    for p in problems:
        print(p)
    print(f'doc_check: {len(problems)} problem(s) in {", ".join(DOCS)}')
    sys.exit(1 if problems else 0)


if __name__ == '__main__':
    main()
