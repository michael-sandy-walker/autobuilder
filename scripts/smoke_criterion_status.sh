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
# The specification is one class or several: "The specification is A, B and C in src/test/java" (a ladder case
# from scripts/ladder_case.py carries every test class its commit shipped).
SPECS=$(grep -o 'The specification is [A-Za-z0-9_, ]*' "$ROOT/data/$TGT/bootstrap/seed.txt" | head -1 \
    | sed -e 's/The specification is //' -e 's/ in .*//' -e 's/ in$//' -e 's/ and /,/g' | tr -d ' ')
SPEC=${SPECS%%,*}
CHANGED=$(grep -o 'The one class to change is [A-Za-z0-9_]*' "$ROOT/data/$TGT/bootstrap/seed.txt" | head -1 | awk '{print $NF}')
# A change across several classes names them all: "The classes to change are A, B and C."
[ -n "$CHANGED" ] || CHANGED=$(grep -o 'The classes to change are [A-Za-z0-9_, ]*' "$ROOT/data/$TGT/bootstrap/seed.txt" | head -1 \
    | sed -e 's/The classes to change are //' -e 's/ and /,/g' | tr -d ' ')
# A seed that names the original change's classes only as ORIENTATION (ladder cases since 2026-10-06: "For
# orientation: the change that answered this ticket touched ...; which classes you change is yours to decide")
# restricts no source, so the "existing sources unchanged" check is not the seed's to ask and is left out.
ORIENTATION=$(grep -c 'For orientation: the change that answered this ticket touched' "$ROOT/data/$TGT/bootstrap/seed.txt")
[ -n "$SPEC" ] && { [ -n "$CHANGED" ] || [ "$ORIENTATION" -gt 0 ]; } \
    || { echo "the seed names no specification class or no class to change"; echo "SMOKE: 0/1"; exit 0; }
n=0; pass=0
chk(){ n=$((n+1)); if [ "$1" = "$2" ]; then pass=$((pass+1)); [ -n "${QUIET:-}" ] || echo "PASS [$n] $3"; \
       else echo "FAIL [$n] $3 (want $2 got $1)"; fi; }
SCRATCH=$(mktemp -d)
"${SMOKE_PREPARE:-$ROOT/scripts/prepare_criterion_status_change.sh}" "$TGT" "$SCRATCH/orig" > /dev/null 2>&1
diff -rq "$SCRATCH/orig/src/test" "$TREE/src/test" > /dev/null 2>&1 && u=untouched || u=modified
# Every source the module had, other than the one class the seed says to change, byte for byte.
kept=kept
[ -n "$CHANGED" ] && while IFS= read -r f; do
    case ",$CHANGED," in *",$(basename "$f" .java),"*) continue ;; esac
    cmp -s "$SCRATCH/orig/src/main/$f" "$TREE/src/main/$f" || { kept=changed; echo "      changed: $f"; }
done < <(cd "$SCRATCH/orig/src/main" && find . -name '*.java' | sort)
# The suite, run in copies: grading must not write into the artifact it grades. The pristine tree
# is run too, for the list of specification cases a tree that does not compile never reports.
cp -a "$TREE" "$SCRATCH/p" 2>/dev/null; rm -rf "${SCRATCH:?}/p/target"
(cd "$SCRATCH/p" && timeout 600 mvn -B -q -o test > "$SCRATCH/test.log" 2>&1) && suite=pass || suite=fail
(cd "$SCRATCH/orig" && timeout 600 mvn -B -q -o test > /dev/null 2>&1)
for SPEC in ${SPECS//,/ }; do
while IFS=$'\t' read -r verdict name; do
    chk "$verdict" pass "$SPEC $name"
done < <(python3 - "$SCRATCH/orig/target/surefire-reports" "$SCRATCH/p/target/surefire-reports" "$SPEC" "$SCRATCH/orig/src/test" <<'PY'
import glob, os, re, sys, xml.etree.ElementTree as ET
orig, cand, cls, src = sys.argv[1:]
def cases(d):
    out = {}
    for f in glob.glob(os.path.join(d, 'TEST-*' + cls + '.xml')):
        for tc in ET.parse(f).getroot().iter('testcase'):
            # A passing test that writes to stdout or stderr gets a <system-out>/<system-err> element too:
            # only a <failure> or <error> fails it (edit-hunks run 2026-09-30_19-04-23 scored 10/11 for a
            # case that passed and logged a warning), and a skipped case did not pass.
            tags = {c.tag for c in tc}
            out[tc.get('name')] = 'fail' if tags & {'failure', 'error'} else 'skipped' if 'skipped' in tags else 'pass'
    return out
want, got = cases(orig), cases(cand)
# A specification that names a class the pristine tree does not have yet cannot compile there, so its run
# reports no cases (edit-hunks: the spec calls EditHunks, which the change creates). The cases are then the
# spec source's own @Test methods -- plain tests only; a parameterized case is named by its run.
if not want:
    for f in glob.glob(os.path.join(src, '**', cls + '.java'), recursive=True):
        text = open(f).read()
        # Test DATA written into the spec (a text block or a string holding a test class) is not the spec's
        # own tests: blank text blocks, string literals and comments before looking for @Test methods.
        text = re.sub(r'"""(?:.|\n)*?"""', '""', text)
        text = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', text)
        text = re.sub(r'/\*(?:.|\n)*?\*/|//[^\n]*', '', text)
        for m in re.finditer(r'@(?:org\.junit\.jupiter\.api\.)?Test\b\s+(?:@\w+(?:\([^)]*\))?\s+)*void\s+(\w+)\s*\(', text):
            want[m.group(1)] = 'pass'
# A case read from the source has no parameter list; the run reports one when the method takes arguments
# ("aCase(Path)" for a @TempDir): such a case is found by its method name (ladder-rows, 2026-10-04).
bare = {}
for k, v in got.items():
    bare.setdefault(k.split('(')[0], v)
for name in sorted(want) or ['(no specification cases reported by the pristine tree)']:
    print(got.get(name, bare.get(name, 'absent')) + '\t' + name[:150])
PY
)
done
chk "$u" untouched "src/test is unmodified"
[ -n "$CHANGED" ] && chk "$kept" kept "the module's existing sources are unchanged"
chk "$suite" pass "the whole suite passes"
[ "$suite" = pass ] || echo "      $(grep -m1 -E 'Tests run:|ERROR' "$SCRATCH/test.log" 2>/dev/null | head -c 140)"
rm -rf "${SCRATCH:?}"
echo "SMOKE: $pass/$n"
