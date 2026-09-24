#!/bin/bash
# smoke_structdiff.sh -- the yardstick for the structdiff target (a module of Autobuilder itself,
# extracted by scripts/extract_structdiff.sh and adopted as a library).
# Usage: scripts/smoke_structdiff.sh <artifact-jar>     (preflight's "terminating" mode)
# Last line: SMOKE: <pass>/11
#
# Asserts ONLY what bootstrap/seed.txt states: every listed entry point is covered by a test, the
# library code is not modified, and the project's own suite passes. It does NOT grade what any
# entry point returns -- the seed asks for behaviour to be characterised, not corrected.
JAR="${1:?usage: smoke_structdiff.sh <artifact-jar>}"
TREE="$(cd "$(dirname "$JAR")/.." && pwd)"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
n=0; pass=0
chk(){ n=$((n+1)); if [ "$1" = "$2" ]; then pass=$((pass+1)); echo "PASS [$n] $3"; \
       else echo "FAIL [$n] $3 (want $2 got $1)"; fi; }

# An entry point counts as covered when ONE test method calls it AND asserts a value -- an equality,
# exact contents or a size. assertNotNull, assertTrue and "not empty" alone do not count: the seed
# asks for what the entry point actually returns, and the second adopted project's first run passed
# the older reference-only check with tests that asserted little beyond non-null.
# Methods are split at @Test; a helper method's assertions count only inside the test that holds them.
covered(){
    python3 - "$TREE/src/test" "$1" "$2" <<'PY'
import os, re, sys
root, cls, m = sys.argv[1], sys.argv[2], sys.argv[3]
call = re.compile(r'(^|[^A-Za-z0-9_])' + re.escape(m) + r'\(')
value = re.compile(r'assertEquals\(|assertIterableEquals\(|assertArrayEquals\(|assertLinesMatch\(|assertSame\('
                   r'|\.isEqualTo\(|\.containsExactly(InAnyOrder)?\(|\.hasSize\(|\.containsOnly\(|\.isSameAs\(')
for d, _, fs in os.walk(root):
    for f in fs:
        if not f.endswith('.java'):
            continue
        src = open(os.path.join(d, f), encoding='utf-8', errors='replace').read()
        if not re.search(r'\b' + re.escape(cls) + r'\b', src):
            continue
        for chunk in src.split('@Test')[1:]:
            if call.search(chunk) and value.search(chunk):
                print('covered'); sys.exit()
print('uncovered')
PY
}
for e in JavaStructureParser.parse StructuralDiffBuilder.diff StructuralJavaDiffBuilder.diffJavaFile \
         DiffSummarizer.summarize DiffSummarizer.summarizePlainText DiffSummarizer.summarizeForPrompt \
         StructuralDiffUtils.flatten StructuralDiffUtils.filterByType StructuralDiffUtils.hasChanges; do
    chk "$(covered "${e%%.*}" "${e#*.}")" covered "$e is covered by a test that asserts a value"
done

# The library code is the operator's: compared against a fresh extraction of the original.
REV=$(cat "$ROOT/data/structdiff/bootstrap/origin-rev" 2>/dev/null)
SCRATCH=$(mktemp -d)
if [ -n "$REV" ] && "$ROOT/scripts/extract_structdiff.sh" "$REV" "$SCRATCH/orig" > /dev/null 2>&1; then
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
