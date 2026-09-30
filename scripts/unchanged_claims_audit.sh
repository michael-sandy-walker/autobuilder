#!/bin/bash
# unchanged_claims_audit.sh -- how many of the criteria that SAY something stayed unchanged does
# UnchangedClaims recognise (and so answer from the changes since adoption rather than a reading)?
# Reads acceptance criteria from archived runs' GoalStack.json.gz; a broad keyword net picks the candidates,
# the real UnchangedClaims.isClaim (target/classes, so build first) decides. Candidates it misses are listed
# for a reader to sort into whole no-change claims and other uses of the words (member-level, behaviour).
#
# Usage: scripts/unchanged_claims_audit.sh [<run> ...]   (default: every run in ~/.autobuilder/run-archive)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
python3 - "$TMP/candidates.txt" "$@" <<'PY'
import gzip, json, os, re, sys
out_path, runs = sys.argv[1], sys.argv[2:]
arch = os.path.expanduser('~/.autobuilder/run-archive')
KW = re.compile(r"(unchanged|unmodified|not (?:be )?(?:modified|changed|altered|touched)|no (?:files?|class(?:es)?|changes?|tests?|sources?)\b[^.]*(?:modified|changed|touched|altered)|only \S+ (?:is|was|has been|gets) (?:modified|changed)|only \S+ under \S+ (?:is|was|has been|gets) (?:modified|changed)|remains? (?:as|intact|untouched)|preserved|kept as|left intact|untouched|as adopted)", re.I)
def nodes(g):
    if not isinstance(g, dict): return
    yield g
    for s in g.get('subGoals') or []: yield from nodes(s)
found = set(); n = 0
for r in runs or sorted(os.listdir(arch)):
    f = os.path.join(arch, r, 'GoalStack.json.gz')
    if not os.path.exists(f) or os.path.exists(os.path.join(arch, r, 'INVALID.txt')): continue
    try: d = json.load(gzip.open(f))
    except Exception: continue
    if 'goalTree' not in d: continue
    n += 1
    for g in nodes(d['goalTree']):
        for c in (g.get('metadata') or {}).get('acceptanceCriteria') or []:
            if isinstance(c, str) and KW.search(c): found.add(c.strip())
open(out_path, 'w').write('\n'.join(sorted(found)))
print(f'{n} run(s), {len(found)} distinct candidate criteria', file=sys.stderr)
PY
javac -proc:none -cp "$ROOT/target/classes" -d "$TMP" "$ROOT/scripts/audit/Claims.java"
java -cp "$TMP:$ROOT/target/classes" Claims "$TMP/candidates.txt" | sort
