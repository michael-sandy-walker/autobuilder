#!/bin/bash
# smoke_participation.sh -- the yardstick for the participation target (a module of Autobuilder
# itself, extracted by scripts/extract_participation.sh and adopted as a library).
# Usage: scripts/smoke_participation.sh <artifact-jar>     (preflight's "terminating" mode)
# Last line: SMOKE: <pass>/<entry points + 2>. SMOKE_TARGET names the target (default participation).
#
# Asserts ONLY what bootstrap/seed.txt states: every listed entry point is covered by a test, the
# library code is not modified, and the project's own suite passes. It does NOT grade what any
# entry point returns -- the seed asks for behaviour to be characterised, not corrected.
JAR="${1:?usage: smoke_participation.sh <artifact-jar>}"
TREE="$(cd "$(dirname "$JAR")/.." && pwd)"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TGT="${SMOKE_TARGET:-participation}"
n=0; pass=0
chk(){ n=$((n+1)); if [ "$1" = "$2" ]; then pass=$((pass+1)); echo "PASS [$n] $3"; \
       else echo "FAIL [$n] $3 (want $2 got $1)"; fi; }

# An entry point counts as covered when ONE test method calls it ON ITS OWN TYPE and asserts a value;
# the rule lives in scripts/test_coverage_attribution.py (read its header before changing a verdict).
covered(){
    python3 "$ROOT/scripts/test_coverage_attribution.py" covered "$TREE/src/test" "$1" "$2"
}
# The entry points are the seed's own list -- read from it, so a target with another seed of the same
# module (participation-b, for T4-CS) is scored by the same instrument against ITS statement.
SEED="$ROOT/data/$TGT/bootstrap/seed.txt"
ENTRIES=$(python3 - "$SEED" <<'PY'
import re, sys
s = open(sys.argv[1]).read()
block = s[s.index('These are its entry points:'):s.index('OBJECTIVE')]
seen = []
for t, m in re.findall(r'\b([A-Z]\w*)\.([a-z]\w*)\(', block):
    if t + '.' + m not in seen: seen.append(t + '.' + m)
print(' '.join(seen))
PY
)
[ -n "$ENTRIES" ] || { echo "no entry points in $SEED"; echo "SMOKE: 0/1"; exit 0; }
for e in $ENTRIES; do
    chk "$(covered "${e%%.*}" "${e#*.}")" covered "$e is covered by a test that asserts a value"
done

# The library code is the operator's: compared against a fresh extraction of the original.
REV=$(cat "$ROOT/data/$TGT/bootstrap/origin-rev" 2>/dev/null)
SCRATCH=$(mktemp -d)
if [ -n "$REV" ] && "$ROOT/scripts/extract_participation.sh" "$REV" "$SCRATCH/orig" > /dev/null 2>&1; then
    diff -rq "$SCRATCH/orig/src/main" "$TREE/src/main" > /dev/null 2>&1 && u=untouched || u=modified
else
    u=unknown
fi
chk "$u" untouched "src/main is unmodified"

# The project's own suite. Run in a copy: grading must not write into the artifact it grades.
cp -a "$TREE" "$SCRATCH/p" 2>/dev/null; rm -rf "$SCRATCH/p/target"
if (cd "$SCRATCH/p" && timeout 600 mvn -B -q -o test > "$SCRATCH/test.log" 2>&1); then
    chk pass pass "the project's own test suite passes"
else
    chk fail pass "the project's own test suite passes"
    echo "      $(grep -m1 -E 'Tests run|ERROR' "$SCRATCH/test.log" 2>/dev/null | head -c 120)"
fi
rm -rf "$SCRATCH"
echo "SMOKE: $pass/$n"
