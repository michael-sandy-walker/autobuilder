#!/bin/bash
# smoke_integration.sh -- the yardstick for a T4-EC stage 2 integration case (data/ledger-import-*), terminating mode.
# Usage: <artifact-jar>; SMOKE_TARGET names the case. Last line: SMOKE: <pass>/<checks>.
#
# Asserts ONLY what the case's seed states: every case of each specification class it names passes (one check per test
# case, read from the run's own reports in every module), the tests are as in the child (both projects' and the
# integration's), every class either project already had is unchanged, LedgerImport reads its lines with linetokens'
# LineTokenizer and the tree declares no second tokenizer, and the whole suite passes. The pristine tree is the case's
# tracked child (bootstrap/child/output).
JAR="${1:?usage: smoke_integration.sh <artifact-jar>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TGT="${SMOKE_TARGET:?SMOKE_TARGET must name the case}"
TREE="$(cd "$(dirname "$JAR")/.." && pwd)"
# A module's jar: the build root is the directory above whose pom lists the modules.
grep -q '<modules>' "$TREE/../pom.xml" 2>/dev/null && TREE="$(cd "$TREE/.." && pwd)"
ORIG="$ROOT/data/$TGT/bootstrap/child/output"
SPECS=$(grep -o 'The specification is [A-Za-z0-9_, ]*' "$ROOT/data/$TGT/bootstrap/seed.txt" | head -1 \
    | sed -e 's/The specification is //' -e 's/ in .*//' -e 's/ and /,/g' | tr -d ' ')
n=0; pass=0
chk(){ n=$((n+1)); if [ "$1" = "$2" ]; then pass=$((pass+1)); [ -n "${QUIET:-}" ] || echo "PASS [$n] $3"; \
       else echo "FAIL [$n] $3 (want $2 got $1)"; fi; }
SCRATCH=$(mktemp -d)
cp -a "$TREE" "$SCRATCH/p" 2>/dev/null; find "$SCRATCH/p" -name target -type d -prune -exec rm -rf {} +
(cd "$SCRATCH/p" && timeout 900 mvn -B -q -o test > "$SCRATCH/test.log" 2>&1) && suite=pass || suite=fail
for SPEC in ${SPECS//,/ }; do
  while IFS=$'\t' read -r verdict name; do chk "$verdict" pass "$SPEC $name"; done < <(python3 - "$SCRATCH/p" "$ORIG" "$SPEC" <<'PY'
import glob, os, re, sys, xml.etree.ElementTree as ET
tree, orig, cls = sys.argv[1:]
got = {}
for f in glob.glob(os.path.join(tree, '**', 'surefire-reports', 'TEST-*' + cls + '.xml'), recursive=True):
    for tc in ET.parse(f).getroot().iter('testcase'):
        tags = {c.tag for c in tc}
        got[tc.get('name')] = 'fail' if tags & {'failure', 'error'} else 'skipped' if 'skipped' in tags else 'pass'
want = []
for f in glob.glob(os.path.join(orig, '**', cls + '.java'), recursive=True):
    text = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', open(f).read())
    want += re.findall(r'@Test\s+void\s+(\w+)\s*\(', text)
for name in sorted(want) or ['(no cases found in the specification source)']:
    print(got.get(name, 'absent') + '\t' + name)
PY
)
done
# Every test file of the child, as it was; every main source of the child, as it was.
tests=untouched; kept=kept
while IFS= read -r f; do
  cmp -s "$ORIG/$f" "$TREE/$f" || { case "$f" in */src/test/*|src/test/*) tests=modified ;; *) kept=changed ;; esac; echo "      changed: $f"; }
done < <(cd "$ORIG" && find . -type f \( -path '*/src/test/*' -o -path '*/src/main/*' \) | sed 's|^\./||' | sort)
chk "$tests" untouched "the tests are as in the child"
chk "$kept" kept "every class either project had is unchanged"
USES="$ROOT/data/$TGT/bootstrap/integration/uses.txt"
if [ -f "$USES" ]; then
  # What the seed says the integration must reuse rather than rewrite: "<Class> <Reference>", one per line.
  while read -r cls ref; do
    [ -z "$cls" ] && continue
    F=$(find "$TREE" -path '*/src/main/*' -name "$cls.java" -not -path '*/target/*' | head -1)
    used=no; [ -n "$F" ] && grep -q "\b$ref\b" "$F" && used=yes
    chk "$used" yes "$cls uses $ref"
  done < <(grep -vE '^\s*(#|$)' "$USES")
else
  LI=$(find "$TREE" -path '*/src/main/*' -name LedgerImport.java -not -path '*/target/*' | head -1)
  uses=no; [ -n "$LI" ] && grep -q 'LineTokenizer' "$LI" && uses=yes
  own=$(find "$TREE" -path '*/src/main/*' -name '*.java' -not -path '*/target/*' -not -path '*/org/linekit/*' -exec grep -l 'class [A-Za-z]*Tokeni[sz]er\b' {} + 2>/dev/null | head -1)
  [ -n "$own" ] && { uses=second-tokenizer; echo "      declares a tokenizer: ${own#$TREE/}"; }
  chk "$uses" yes "LedgerImport reads its lines with LineTokenizer, and no second tokenizer is declared"
fi
chk "$suite" pass "the whole suite passes"
[ "$suite" = pass ] || echo "      $(grep -m1 -E 'Tests run:|ERROR' "$SCRATCH/test.log" 2>/dev/null | head -c 160)"
rm -rf "${SCRATCH:?}"
echo "SMOKE: $pass/$n"
