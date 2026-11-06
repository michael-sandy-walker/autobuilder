#!/bin/bash
# smoke_capability_import.sh -- the yardstick for a capability-import case (T4-EI; data/firestarter-cashbook).
# Usage: <artifact-jar> (terminating mode) or <tree>; SMOKE_TARGET names the case (default firestarter-cashbook).
# Last line: SMOKE: <pass>/<checks>.
#
# Asserts ONLY what the case's assignment and its two descriptions state (bootstrap/README.md): the source is
# unchanged; the import recorded a capability taken as code and one adapted; the code's placed files are in the
# tree; no loan-rule declaration and no web layer is in src/main; none of the source's exception classes was placed;
# a receiver class extends IllegalArgumentException and the ledger throws it; LedgerSpecTest passes; the whole suite
# passes. Absence is absence of the declarations named, not proof that no behaviour of a left-out capability came
# along in other words.
ARG="${1:?usage: smoke_capability_import.sh <artifact-jar>|<tree>}"
if [ -d "$ARG" ]; then TREE="$(cd "$ARG" && pwd)"; else TREE="$(cd "$(dirname "$ARG")/.." && pwd)"; fi
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TGT="${SMOKE_TARGET:-firestarter-cashbook}"
B="$ROOT/data/$TGT/bootstrap"; META="$ROOT/data/$TGT/meta"
pass=0; n=0
check() { n=$((n + 1)); if [ "$2" = 0 ]; then pass=$((pass + 1)); echo "PASS [$n] $1"; else echo "FAIL [$n] $1${3:+ -- $3}"; fi; }

( cd "$B" && sha256sum --quiet -c SOURCE-DIGESTS.txt > /dev/null 2>&1 ); check "the frozen source is unchanged" $?

REC=$(ls -t "$META"/inputs/*/capabilities.json 2>/dev/null | head -1)
modes=""; [ -n "$REC" ] && modes=$(python3 -c "import json,sys; print(' '.join(c['mode'] for c in json.load(open(sys.argv[1]))['capabilities']))" "$REC")
echo "$modes" | grep -qw code && echo "$modes" | grep -qw adapt; check "the import recorded a capability taken as code and one adapted" $? "${REC:-no capabilities.json}"

missing=""
if [ -n "$REC" ]; then
    missing=$(python3 -c "
import json, os, sys
r = json.load(open(sys.argv[1]))
for c in r['capabilities']:
    if c['mode'] == 'code':
        for p in c['placed']:
            if not os.path.isfile(os.path.join(sys.argv[2], p)): print(p)
" "$REC" "$TREE")
    placed=$(python3 -c "import json,sys; print(sum(len(c['placed']) for c in json.load(open(sys.argv[1]))['capabilities'] if c['mode']=='code'))" "$REC")
    [ -z "$missing" ] && [ "$placed" -gt 0 ]; check "the code capability's placed files are in the tree ($placed)" $? "$missing"
else
    check "the code capability's placed files are in the tree" 1 "no capabilities.json"
fi

MAIN="$TREE/src/main/java"
# The loan rules are LoanService's -- a book once on loan, at most 3 active loans a member, a loan returned once -- and
# the refusals that say so. LoanRepository's queries (hasActiveLoanForBook, countActiveLoansForMember) are the store's,
# as the cut made them: v1 of this check named them before any run, which the assignment does not (v2, 2026-11-07).
hits=$(grep -rlE 'class LoanService\b|already on loan|maximum of 3 active loans|already returned' "$MAIN" 2>/dev/null)
[ -z "$hits" ]; check "no loan rule in src/main" $? "$hits"
hits=$(grep -rlE '@RestController|@RestControllerAdvice|GlobalExceptionHandler' "$MAIN" 2>/dev/null)
[ -z "$hits" ]; check "no web layer in src/main" $? "$hits"
hits=$(find "$MAIN" -path '*com/example/library/domain/exception/*.java' 2>/dev/null)
[ -z "$hits" ]; check "none of the source's exception classes was placed" $? "$hits"

typed=$(grep -rlE 'class [A-Za-z0-9_]+ extends IllegalArgumentException' "$MAIN/org/cashbook" 2>/dev/null)
[ -n "$typed" ]; check "a cashbook class extends IllegalArgumentException" $?
names=$(for f in $typed; do grep -oE 'class [A-Za-z0-9_]+ extends IllegalArgumentException' "$f" | awk '{print $2}'; done | sort -u)
thrown=1
for nm in $names; do grep -rqE "throw new $nm\b" "$MAIN/org/cashbook/ledger/Entry.java" "$MAIN/org/cashbook/ledger/Ledger.java" 2>/dev/null && thrown=0; done
check "the ledger throws it" $thrown "${names:-none}"

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
cp -a "$TREE/." "$WORK/"; rm -rf "$WORK/target"
( cd "$WORK" && mvn -q test > "$WORK/.smoke-test.log" 2>&1 ); suite=$?
spec=$(python3 - "$WORK" <<'PY'
import glob, sys, xml.etree.ElementTree as E
t = f = 0
for p in glob.glob(sys.argv[1] + '/target/surefire-reports/TEST-*LedgerSpecTest.xml'):
    r = E.parse(p).getroot(); t += int(r.get('tests', 0)); f += int(r.get('failures', 0)) + int(r.get('errors', 0))
print(t, f)
PY
)
set -- $spec
[ "${1:-0}" -gt 0 ] && [ "${2:-1}" = 0 ]; check "LedgerSpecTest passes (${1:-0} tests, ${2:-?} failing)" $?
check "the whole suite passes" $suite "$(grep -m1 -E 'ERROR|FAIL' "$WORK/.smoke-test.log")"
echo "SMOKE: $pass/$n"
