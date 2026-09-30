#!/bin/bash
# smoke_participation_change.sh -- the yardstick for a CHANGE target on the participation module
# (prepared by scripts/prepare_participation_change.sh). Usage: <artifact-jar> (terminating mode);
# SMOKE_TARGET names the target. Last line: SMOKE: <pass>/<spec tests + 3>.
#
# Asserts ONLY what the target's seed states: every test of the named specification class passes
# (one check per test method, read from the test run's own report), src/test is unmodified (the
# specification and the existing tests are the operator's), the existing public methods are
# unchanged, and the whole suite passes -- which includes the characterization suite.
JAR="${1:?usage: smoke_participation_change.sh <artifact-jar>}"
TREE="$(cd "$(dirname "$JAR")/.." && pwd)"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TGT="${SMOKE_TARGET:?SMOKE_TARGET must name the target}"
SPEC=$(grep -o 'The specification is [A-Za-z0-9_]*' "$ROOT/data/$TGT/bootstrap/seed.txt" | head -1 | awk '{print $NF}')
[ -n "$SPEC" ] || { echo "no specification class named in the seed"; echo "SMOKE: 0/1"; exit 0; }
n=0; pass=0
chk(){ n=$((n+1)); if [ "$1" = "$2" ]; then pass=$((pass+1)); echo "PASS [$n] $3"; \
       else echo "FAIL [$n] $3 (want $2 got $1)"; fi; }
SCRATCH=$(mktemp -d)
# The operator's tests, as prepared, against the tree's src/test.
"$ROOT/scripts/prepare_participation_change.sh" "$TGT" "$SCRATCH/orig" > /dev/null 2>&1
diff -rq "$SCRATCH/orig/src/test" "$TREE/src/test" > /dev/null 2>&1 && u=untouched || u=modified
# The suite, run in a copy: grading must not write into the artifact it grades.
cp -a "$TREE" "$SCRATCH/p" 2>/dev/null; rm -rf "${SCRATCH:?}/p/target"
(cd "$SCRATCH/p" && timeout 600 mvn -B -q -o test > "$SCRATCH/test.log" 2>&1) && suite=pass || suite=fail
# Per spec method: passed iff the report lists it without a failure or error. A tree whose tests do
# not compile has no report, so every spec method fails -- as it should.
SPEC_SRC=$(find "$SCRATCH/orig/src/test" -name "$SPEC.java" | head -1)
for m in $(python3 - "$SPEC_SRC" <<'PY'
import re, sys
src = open(sys.argv[1]).read()
print(' '.join(re.findall(r'@Test\s+(?:public\s+)?void\s+(\w+)\s*\(', src)))
PY
); do
    r=$(python3 - "$SCRATCH/p/target/surefire-reports" "$SPEC" "$m" <<'PY'
import glob, os, sys, xml.etree.ElementTree as ET
d, cls, m = sys.argv[1:]
for f in glob.glob(os.path.join(d, 'TEST-*' + cls + '.xml')):
    for tc in ET.parse(f).getroot().iter('testcase'):
        if tc.get('name') == m:
            tags = {c.tag for c in tc}  # system-out/err on a passing test is not a failure
            print('fail' if tags & {'failure', 'error'} else 'skipped' if 'skipped' in tags else 'pass'); sys.exit()
print('absent')
PY
)
    chk "$r" pass "$SPEC.$m passes"
done
chk "$u" untouched "src/test is unmodified"
# "Keep the existing public methods as they are": every public method and constructor of the original
# library is still there with the same signature, annotations and body (whitespace aside). Additions
# are what the seed asks for; the suite passing does not show this, because the existing tests pin
# only some of the existing behaviour (participation-roles run 2026-09-25_19-57-35 passed 8/8 while
# its rewrite of ParticipationWindows had changed eight existing members, re-grant among them).
CH=$(python3 "$ROOT/scripts/java_members.py" changed "$SCRATCH/orig/src/main" "$TREE/src/main")
[ -z "$CH" ] && k=kept || k=changed
chk "$k" kept "the existing public methods are unchanged"
[ -z "$CH" ] || echo "$CH" | head -5 | sed 's/^/      /'
chk "$suite" pass "the whole suite passes"
[ "$suite" = pass ] || echo "      $(grep -m1 -E 'Tests run:|ERROR' "$SCRATCH/test.log" 2>/dev/null | head -c 140)"
rm -rf "${SCRATCH:?}"
echo "SMOKE: $pass/$n"
