#!/bin/bash
# smoke_criterion_status.sh -- the yardstick for the criterion-status CHANGE target (prepared by
# scripts/prepare_criterion_status_change.sh). Usage: <artifact-jar> (terminating mode); SMOKE_TARGET
# names the target (default criterion-status); SMOKE_PREPARE the script that prepares its pristine tree
# (default prepare_criterion_status_change.sh). Last line: SMOKE: <pass>/<checks>.
#
# Asserts ONLY what the target's seed states: every case of the named specification class passes (one
# check per test case, parameterized invocations included, read from the test run's own report), src/test
# is unmodified, the module's existing sources are unchanged, and the whole suite passes -- which
# includes Autobuilder's own tests of the module.
JAR="${1:?usage: smoke_criterion_status.sh <artifact-jar>}"
TREE="$(cd "$(dirname "$JAR")/.." && pwd)"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TGT="${SMOKE_TARGET:-criterion-status}"
SPEC=$(grep -o 'The specification is [A-Za-z0-9_]*' "$ROOT/data/$TGT/bootstrap/seed.txt" | head -1 | awk '{print $NF}')
CHANGED=$(grep -o 'The one class to change is [A-Za-z0-9_]*' "$ROOT/data/$TGT/bootstrap/seed.txt" | head -1 | awk '{print $NF}')
[ -n "$SPEC" ] && [ -n "$CHANGED" ] || { echo "the seed names no specification class or no class to change"; echo "SMOKE: 0/1"; exit 0; }
n=0; pass=0
chk(){ n=$((n+1)); if [ "$1" = "$2" ]; then pass=$((pass+1)); [ -n "${QUIET:-}" ] || echo "PASS [$n] $3"; \
       else echo "FAIL [$n] $3 (want $2 got $1)"; fi; }
SCRATCH=$(mktemp -d)
"${SMOKE_PREPARE:-$ROOT/scripts/prepare_criterion_status_change.sh}" "$TGT" "$SCRATCH/orig" > /dev/null 2>&1
diff -rq "$SCRATCH/orig/src/test" "$TREE/src/test" > /dev/null 2>&1 && u=untouched || u=modified
# Every source the module had, other than the one class the seed says to change, byte for byte.
kept=kept
while IFS= read -r f; do
    [ "$(basename "$f")" = "$CHANGED.java" ] && continue
    cmp -s "$SCRATCH/orig/src/main/$f" "$TREE/src/main/$f" || { kept=changed; echo "      changed: $f"; }
done < <(cd "$SCRATCH/orig/src/main" && find . -name '*.java' | sort)
# The suite, run in copies: grading must not write into the artifact it grades. The pristine tree
# is run too, for the list of specification cases a tree that does not compile never reports.
cp -a "$TREE" "$SCRATCH/p" 2>/dev/null; rm -rf "${SCRATCH:?}/p/target"
(cd "$SCRATCH/p" && timeout 600 mvn -B -q -o test > "$SCRATCH/test.log" 2>&1) && suite=pass || suite=fail
(cd "$SCRATCH/orig" && timeout 600 mvn -B -q -o test > /dev/null 2>&1)
while IFS=$'\t' read -r verdict name; do
    chk "$verdict" pass "$SPEC $name"
done < <(python3 - "$SCRATCH/orig/target/surefire-reports" "$SCRATCH/p/target/surefire-reports" "$SPEC" <<'PY'
import glob, os, sys, xml.etree.ElementTree as ET
orig, cand, cls = sys.argv[1:]
def cases(d):
    out = {}
    for f in glob.glob(os.path.join(d, 'TEST-*' + cls + '.xml')):
        for tc in ET.parse(f).getroot().iter('testcase'):
            out[tc.get('name')] = 'fail' if list(tc) else 'pass'
    return out
want, got = cases(orig), cases(cand)
for name in sorted(want) or ['(no specification cases reported by the pristine tree)']:
    print(got.get(name, 'absent') + '\t' + name[:150])
PY
)
chk "$u" untouched "src/test is unmodified"
chk "$kept" kept "the module's existing sources are unchanged"
chk "$suite" pass "the whole suite passes"
[ "$suite" = pass ] || echo "      $(grep -m1 -E 'Tests run:|ERROR' "$SCRATCH/test.log" 2>/dev/null | head -c 140)"
rm -rf "${SCRATCH:?}"
echo "SMOKE: $pass/$n"
