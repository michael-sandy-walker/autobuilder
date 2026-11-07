#!/bin/bash
# smoke_capability_reference.sh -- the yardstick for a capability-REFERENCE case (T4-EJ; data/labelmaker-pantry).
# Usage: <artifact-jar> (terminating mode) or <tree>; SMOKE_TARGET names the case (default labelmaker-pantry).
# Last line: SMOKE: <pass>/<checks>.
#
# Asserts only what the case states (bootstrap/README.md): the frozen source and receiver are unchanged; the import
# recorded the capability as adapted (and says whether its source was kept); evolution asked the reference and got
# an answer; every test of the WITHHELD specification passes when laid over a copy of the tree (the loop never saw
# it); the receiver's own specification is unmodified and passes; the whole suite passes.
# A control run (reference not kept) is expected to fail "asked"; it is scored by the same checks.
ARG="${1:?usage: smoke_capability_reference.sh <artifact-jar>|<tree>}"
if [ -d "$ARG" ]; then TREE="$(cd "$ARG" && pwd)"; else TREE="$(cd "$(dirname "$ARG")/.." && pwd)"; fi
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TGT="${SMOKE_TARGET:-labelmaker-pantry}"
B="$ROOT/data/$TGT/bootstrap"; META="$ROOT/data/$TGT/meta"; LOGS="$ROOT/data/$TGT/logs"
W="$B/withheld/src/test"
pass=0; n=0
check() { n=$((n + 1)); if [ "$2" = 0 ]; then pass=$((pass + 1)); echo "PASS [$n] $1"; else echo "FAIL [$n] $1${3:+ -- $3}"; fi; }

( cd "$B" && sha256sum --quiet -c SOURCE-DIGESTS.txt > /dev/null 2>&1 ); check "the frozen source and receiver are unchanged" $?

REC=$(ls -t "$META"/inputs/*/capabilities.json 2>/dev/null | head -1)
kept=""
[ -n "$REC" ] && kept=$(python3 -c "
import json, sys
r = json.load(open(sys.argv[1]))
print(' '.join(str(c.get('reference', False)).lower() for c in r['capabilities'] if c['mode'] == 'adapt'))" "$REC")
[ -n "$kept" ]; check "the import recorded the capability as adapted (source kept: ${kept:-n/a})" $? "${REC:-no capabilities.json}"

asked=$(cat "$LOGS/autobuilder.log" 2>/dev/null | grep -cE 'T4-EI: evolution asked the reference .* answered$')
[ "$asked" -gt 0 ]; check "evolution asked the reference and got an answer ($asked)" $?

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/orig"; cp -a "$B/receiver/output/." "$WORK/orig/"
diff -rq "$WORK/orig/src/test" "$TREE/src/test" > /dev/null 2>&1; check "the receiver's specification (src/test) is unmodified" $?

mkdir -p "$WORK/t"; cp -a "$TREE/." "$WORK/t/"
rm -rf "$WORK/t/target"
( cd "$WORK/t" && timeout 600 mvn -B -q -o test > "$WORK/test.log" 2>&1 ); suite=$?
mkdir -p "$WORK/t/src/test"; cp -a "$W/." "$WORK/t/src/test/"
( cd "$WORK/t" && timeout 600 mvn -B -q -o test > "$WORK/test-withheld.log" 2>&1 )
# One check per case: the withheld spec's @Test methods (a tree that does not compile reports none, so the names
# come from the source), then the receiver's own spec as a whole.
while IFS=$'\t' read -r verdict name; do
    [ "$verdict" = pass ]; check "withheld $name" $?
done < <(python3 - "$WORK/t/target/surefire-reports" "$W" <<'PY'
import glob, os, re, sys, xml.etree.ElementTree as ET
reports, src = sys.argv[1:]
for f in sorted(glob.glob(os.path.join(src, '**', '*.java'), recursive=True)):
    cls = os.path.basename(f)[:-5]
    names = re.findall(r'@Test\s+void\s+(\w+)', open(f).read())
    got = {}
    for x in glob.glob(os.path.join(reports, 'TEST-*' + cls + '.xml')):
        for tc in ET.parse(x).getroot().iter('testcase'):
            tags = {c.tag for c in tc}
            got[tc.get('name')] = 'fail' if tags & {'failure', 'error', 'skipped'} else 'pass'
    for nm in names:
        print(got.get(nm, 'fail') + '\t' + cls + ' ' + nm)
PY
)
spec=$(python3 - "$WORK/t/target/surefire-reports" <<'PY'
import glob, sys, xml.etree.ElementTree as E
t = f = 0
for p in glob.glob(sys.argv[1] + '/TEST-*PantrySpecTest.xml'):
    r = E.parse(p).getroot(); t += int(r.get('tests', 0)); f += int(r.get('failures', 0)) + int(r.get('errors', 0))
print(t, f)
PY
)
set -- $spec
[ "${1:-0}" -gt 0 ] && [ "${2:-1}" = 0 ]; check "PantrySpecTest passes (${1:-0} tests, ${2:-?} failing)" $?
check "the whole suite passes (without the withheld spec)" $suite "$(grep -m1 -E 'ERROR|FAIL' "$WORK/test.log")"
echo "SMOKE: $pass/$n"
