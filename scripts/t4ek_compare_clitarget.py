#!/usr/bin/env python3
"""T4-EK slice (a2), command-style: a reverse-engineered account of stripped clitarget against its truth.

Usage: scripts/t4ek_compare_clitarget.py <account.json>

The rule was stated on the ticket BEFORE the run (2026-11-09): the truth is scripts/smoke_cli_quoted.sh's 19 checks;
a check is recalled when a KEPT claim (anchored by its feature) matches its predicate on the claim's run -- the
command word (its first argument), the files it writes, the exit status it states and the text its output must
contain. Bar >= 10/19. Check 2 ("prints nothing") is an expected miss: a claim states text the output CONTAINS,
never that it is empty. The command words come from the yardstick (it is the instrument, not the code).
"""
import csv
import io
import json
import re
import sys

COMMANDS = {'check', 'count', 'column'}
ROW_ISSUE = re.compile(r'line (\d+): expected (\d+) fields, found (\d+)')


def run_of(j):
    return j['claim'].get('run') or {}


def word(j):
    args = run_of(j).get('args') or []
    return args[0] if args else None


def files(j):
    return run_of(j).get('files') or {}


def file_arg(j):
    """The content of the file the run names as its first operand, or None when it names no written file."""
    args = run_of(j).get('args') or []
    if len(args) < 2:
        return None
    return files(j).get(args[1])


def out(j):
    return run_of(j).get('outputContains') or ''


def code(j):
    return j['claim'].get('expected')


def rows(text):
    """(physical start line, fields) per row, with quotes and embedded newlines read as RFC 4180 does. Strict: a quote
    never closed is no row at all (a first version accepted it, so a file cut off inside a quote counted as a row
    spanning lines; fixed before scoring)."""
    reader = csv.reader(io.StringIO(text), strict=True)
    result, start = [], 1
    try:
        for fields in reader:
            result.append((start, fields))
            start = reader.line_num + 1
    except csv.Error:
        return []
    return result


def quoted_comma(text):
    return text is not None and re.search(r'"[^"]*,[^"]*"', text) is not None


def quoted_newline(text):
    """A field holding a line break -- read by the CSV rules, not by a pattern across fields (a first version matched
    from one field's closing quote over the line break to the next field's opening quote; fixed before scoring)."""
    return text is not None and any('\n' in f for _, fields in rows(text) for f in fields)


def raw_fields(line):
    """The fields of one physical line as written, quotes kept, split on commas outside quotes."""
    out, cur, quoted = [], '', False
    for c in line:
        if c == '"':
            quoted = not quoted
        if c == ',' and not quoted:
            out.append(cur)
            cur = ''
        else:
            cur += c
    out.append(cur)
    return out


def malformed(text):
    if not text:
        return []
    r = rows(text)
    if not r:
        return []
    width = len(r[0][1])
    return [(line, len(f)) for line, f in r[1:] if len(f) != width]


def column_after_quoted(j):
    text, args = file_arg(j), run_of(j).get('args') or []
    if text is None or len(args) < 3:
        return False
    r = rows(text)
    if not r or args[2] not in r[0][1]:
        return False
    # A field BEFORE the asked column is quoted in some data row (a first version looked for a quote anywhere on the
    # line, so asking for the quoted column itself counted; fixed before scoring).
    wanted = r[0][1].index(args[2])
    raw_lines = text.split('\n')
    for line, _ in r[1:]:
        if line - 1 >= len(raw_lines):
            continue
        raw = raw_fields(raw_lines[line - 1])
        if any(f.strip().startswith('"') for f in raw[:wanted]):
            return True
    return False


def names_row(j, which):
    m = ROW_ISSUE.search(out(j))
    bad = malformed(file_arg(j))
    if not m or not bad:
        return False
    named = int(m.group(1))
    return named == bad[0][0] if which == 'first' else len(bad) >= 2 and named != bad[0][0]


def column_unknown(j):
    text, args = file_arg(j), run_of(j).get('args') or []
    if text is None or len(args) < 3:
        return False
    r = rows(text)
    return bool(r) and args[2] not in r[0][1]


def wrong_count(j):
    args = run_of(j).get('args') or []
    need = {'check': 2, 'count': 2, 'column': 3}.get(word(j))
    return need is not None and len(args) != need


CHECKS = [
    (1, 'quoted commas do not split a field', lambda j: word(j) == 'check' and quoted_comma(file_arg(j)) and code(j) == 0),
    (2, 'well-formed file prints nothing (EXPECTED MISS)', lambda j: False),
    (3, 'count ignores quoted commas', lambda j: word(j) == 'count' and quoted_comma(file_arg(j)) and out(j).strip().isdigit()),
    (4, 'column unquotes and keeps embedded commas', lambda j: word(j) == 'column' and quoted_comma(file_arg(j)) and ',' in out(j)),
    (5, 'column reads the field after a quoted one', lambda j: word(j) == 'column' and code(j) == 0 and out(j) and column_after_quoted(j)),
    (6, 'a row spanning physical lines counts once', lambda j: word(j) == 'count' and quoted_newline(file_arg(j)) and out(j).strip().isdigit()),
    (7, 'a row spanning physical lines is well formed', lambda j: word(j) == 'check' and quoted_newline(file_arg(j)) and code(j) == 0),
    (8, 'malformed file exits 2', lambda j: word(j) == 'check' and malformed(file_arg(j)) and code(j) == 2),
    (9, 'both malformed rows reported', lambda j: word(j) == 'check' and len(malformed(file_arg(j))) >= 2 and 'line ' in out(j)),
    (10, 'first malformed row named exactly', lambda j: word(j) == 'check' and names_row(j, 'first')),
    (11, 'second malformed row named exactly', lambda j: word(j) == 'check' and names_row(j, 'later')),
    (12, 'empty file has no header -- check exits 2', lambda j: word(j) == 'check' and file_arg(j) == '' and code(j) == 2),
    (13, 'empty file counts zero rows', lambda j: word(j) == 'count' and file_arg(j) == '' and out(j).strip() == '0'),
    (14, 'unknown column exits 4', lambda j: word(j) == 'column' and column_unknown(j) and code(j) == 4),
    (15, 'unknown column names itself', lambda j: word(j) == 'column' and column_unknown(j) and 'no such column: ' in out(j)),
    (16, 'missing file exits 1', lambda j: word(j) in COMMANDS and not wrong_count(j) and file_arg(j) is None
     and len(run_of(j).get('args') or []) >= 2 and code(j) == 1),
    (17, 'unknown command exits 3', lambda j: word(j) is not None and word(j).lower() not in COMMANDS and code(j) == 3),
    (18, 'command words are case sensitive', lambda j: word(j) is not None and word(j) not in COMMANDS
     and word(j).lower() in COMMANDS and code(j) == 3),
    (19, 'wrong argument count exits 5', lambda j: wrong_count(j) and code(j) == 5),
]


def main():
    acc = json.load(open(sys.argv[1]))
    claims = acc['claims']
    print(f"source: {acc['source']}\nkind: {acc.get('kind')}\nduration: {acc['durationMs'] / 60000:.1f} min\n"
          f"verdicts: {acc['verdicts']}")
    print('\nFEATURES:')
    for f in acc['features']:
        print(f"  {f['name']}: {len(f['claims'])} kept; depends on {f['dependsOn'] or 'nothing'}; made of {f['madeOf']}\n"
              f"      entry points: {', '.join(f['entryPoints'])}")
    print('\nVARIANTS:')
    for v in acc['variants']:
        print(f"  {v['capability'] or 'as-is'}: built={v['built']} ran={v['ran']} stubbed={len(v['stubbedMethods'])}"
              + (f" why={(v.get('why') or '')[:160]}" if v.get('why') else ''))
    kept = [j for j in claims if (j.get('byFeature') or {}).get('verdict') == 'anchored']
    recalled = 0
    print('\nCHECKS:')
    for n, name, pred in CHECKS:
        hits = [j['claim']['id'] for j in kept if pred(j)]
        recalled += bool(hits)
        print(f"  [{n:2}] {'HIT ' if hits else 'miss'} {name:48} {','.join(hits) or '-'}")
    print(f"\nRECALL: {recalled}/19 (bar >= 10)")
    print('\nALL CLAIMS:')
    for j in claims:
        bf = j.get('byFeature') or {}
        r = run_of(j)
        obs = ' '.join(f"{o['variant']}={o['status'] if o['status'] is not None else o['unobserved']}" for o in j['observations'])
        print(f"  {j['claim']['id']} {bf.get('verdict'):12} (layer {j['verdict']}) run {r.get('args')} exit {code(j)}"
              f" out {out(j)!r} :: {j['claim']['text']}\n      {obs}")


if __name__ == '__main__':
    main()
