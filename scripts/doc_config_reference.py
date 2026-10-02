#!/usr/bin/env python3
"""Generates docs/configuration.md: every configuration key the code reads, its default and the class reading it.

The keys are read from the code itself -- each @Value("${key:default}") under src/main/java -- and the value
application.yml sets, when it sets one, is shown beside the default. Generated, so it cannot drift from the code;
re-run it after adding or renaming a key (scripts/doc_check.py fails on a key the documents name and the code
no longer reads).

usage: doc_config_reference.py [--out docs/configuration.md]
"""
import argparse, os, re, sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
VALUE = re.compile(r'@(?:[\w.]+\.)?Value\(\s*"\$\{([A-Za-z0-9._-]+)(?::([^}]*))?\}"\s*\)')
PROPS = re.compile(r'@ConfigurationProperties\(\s*prefix\s*=\s*"([^"]+)"\s*\)')
# a bound field: "private <type> name [= default];" in a @ConfigurationProperties class
FIELD = re.compile(r'^\s*private\s+(?!static|final)[\w<>,.?\[\] ]+?\s+(\w+)\s*(?:=\s*([^;]+))?;', re.M)


def kebab(name):
    return re.sub(r'(?<=[a-z0-9])([A-Z])', r'-\1', name).lower()


def comment_above(lines, i):
    """The comment directly above line i (annotations skipped): a /** */ or /* */ block, or // lines; '' when none."""
    j = i - 1
    # annotations on the same field (@Setter, @Min) are skipped; another key's @Value line is not this key's comment
    while j >= 0 and lines[j].strip().startswith('@') and '${' not in lines[j]:
        j -= 1
    if j < 0:
        return ''
    t = lines[j].strip()
    out = []
    if t.endswith('*/'):
        while j >= 0:
            out.insert(0, lines[j])
            if '/*' in lines[j]:
                break
            j -= 1
    elif t.startswith('//'):
        while j >= 0 and lines[j].strip().startswith('//'):
            out.insert(0, lines[j].strip()[2:])
            j -= 1
    text = ' '.join(re.sub(r'^\s*(/\*\*?|\*/?|\*)', '', l).replace('*/', '') for l in out)
    text = re.sub(r'\{@(?:code|link|linkplain)\s+([^}]*)\}', r'`\1`', text)
    text = re.sub(r'<[^>]+>', ' ', text)
    return re.sub(r'\s+', ' ', text).strip()


def describe(comment):
    """A comment's first sentence, the way a reader of the reference needs it: no ticket tag in front."""
    c = re.sub(r'^(?:[A-Z]\d-[A-Z]{1,3}(?:-\d+)?[\w ,/()-]*?:\s*)+', '', comment)
    m = re.match(r'(.+?\.)(?:\s|$)', c)
    first = (m.group(1) if m else c).strip()
    first = first[:1].upper() + first[1:] if first else ''
    return first.rstrip(';').replace('|', '\\|')


def code_keys():
    keys = {}
    src = os.path.join(ROOT, 'src/main/java')
    for d, _, files in os.walk(src):
        for f in files:
            if not f.endswith('.java'):
                continue
            text = open(os.path.join(d, f), encoding='utf-8').read()
            lines = text.split('\n')
            line_of = lambda pos: text.count('\n', 0, pos)
            props = PROPS.search(text)
            if props:
                body = text.index('{', props.end())
                depth_at = lambda pos: text.count('{', body, pos) - text.count('}', body, pos)
                for m in FIELD.finditer(text):
                    if depth_at(m.start()) != 1:
                        continue   # a field of a nested class is a setting of its top field, not a key
                    k = props.group(1) + '.' + kebab(m.group(1))
                    default = m.group(2).strip().strip('"') if m.group(2) else None
                    keys.setdefault(k, {'default': default, 'classes': set(), 'about': ''})
                    keys[k]['classes'].add(f[:-5])
                    keys[k]['about'] = keys[k]['about'] or describe(comment_above(lines, line_of(m.start(1))))
            for m in VALUE.finditer(text):
                k, default = m.group(1), m.group(2)
                # "${key:" + CONSTANT + "}": the default is a constant of the same file
                c = re.fullmatch(r'"\s*\+\s*(\w+)\s*\+\s*"', default or '')
                if c:
                    const = re.search(r'\b' + c.group(1) + r'\s*=\s*([^;]+);', text)
                    default = const.group(1).strip().strip('"').replace('_', '') if const else c.group(1)
                keys.setdefault(k, {'default': default, 'classes': set(), 'about': ''})
                keys[k]['classes'].add(f[:-5])
                keys[k]['about'] = keys[k]['about'] or describe(comment_above(lines, line_of(m.start())))
                if keys[k]['default'] is None and default is not None:
                    keys[k]['default'] = default
    return keys


def yml_values():
    try:
        import yaml
    except ImportError:
        return {}
    out = {}
    for doc in yaml.safe_load_all(open(os.path.join(ROOT, 'src/main/resources/application.yml'))):
        if not doc:
            continue
        if (((doc.get('spring') or {}).get('config') or {}).get('activate') or {}).get('on-profile'):
            continue   # profile blocks are documented with the profiles, not as the base value

        def flat(d, p=''):
            if isinstance(d, dict):
                for k, v in d.items():
                    yield from flat(v, f'{p}.{k}' if p else str(k))
            else:
                yield p, d
        for k, v in flat(doc):
            out[k] = v
    return out


def main():
    a = argparse.ArgumentParser()
    a.add_argument('--out', default=os.path.join(ROOT, 'docs/configuration.md'))
    o = a.parse_args()
    keys, yml = code_keys(), yml_values()
    groups = {}
    for k in keys:
        prefix = '.'.join(k.split('.')[:2])
        groups.setdefault(prefix, []).append(k)
    lines = ['# Autobuilder -- configuration reference', '',
             'Generated by `scripts/doc_config_reference.py` from the code: every key a class reads with `@Value` or binds',
             'through `@ConfigurationProperties` (nested settings of those classes are listed by their top field), its',
             'default in the code, and the value `application.yml` sets when it sets one (profile blocks aside -- see',
             '[technical.md](technical.md), section 11). Set a key on the command line (`--key=value`) or in a profile.',
             'What a key does is the first sentence of the comment on the field that reads it, so it is written once, in',
             'the code.',
             '', f'{len(keys)} keys.', '']
    for prefix in sorted(groups):
        lines += [f'## `{prefix}`', '', '| Key | What it does | Default in code | application.yml | Read by |',
                  '|---|---|---|---|---|']
        for k in sorted(groups[prefix]):
            d = keys[k]['default']
            y = yml.get(k)
            lines.append('| `{}` | {} | {} | {} | {} |'.format(
                k, keys[k]['about'] or '(not described)', '`' + d + '`' if d not in (None, '') else ('(empty)' if d == '' else '(required)'),
                '`' + (str(y).lower() if isinstance(y, bool) else str(y)) + '`' if y is not None else '',
                ', '.join('`' + c + '`' for c in sorted(keys[k]['classes']))))
        lines.append('')
    open(o.out, 'w', encoding='utf-8').write('\n'.join(lines))
    print(f'{len(keys)} keys -> {o.out}')


if __name__ == '__main__':
    main()
