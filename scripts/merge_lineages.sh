#!/bin/bash
# merge_lineages.sh -- T4-CS, first slice: build a CHILD project from two lineages' trees whose
# library code is identical (an adopted project, src/main protected in both runs), by merging their
# TEST layers member by member (core/merge/LineageTestMerge) and then CHECKING the claim: the child's
# own suite must pass. Deterministic, no model call.
#
# Usage: scripts/merge_lineages.sh <treeA> <labelA> <treeB> <labelB> <childDir>
#   tree = a project root (pom.xml, src/main, src/test); the child is built from treeA's non-test files.
#   Writes <childDir>/MERGE-PROVENANCE.tsv (file, member, parent), MERGE-CONFLICTS.txt, MERGE-SUITE.txt.
# Last line: MERGE: ok|refused|suite-failing ...
#
# Refuses when src/main differs: merging two versions of the LIBRARY is the hard half of T4-CS and
# is not what this slice does -- two parents that changed the code would need a real code merge.
set -u
A="${1:?usage: merge_lineages.sh <treeA> <labelA> <treeB> <labelB> <childDir>}"; LA="${2:?}"
B="${3:?}"; LB="${4:?}"; C="${5:?}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR=$(ls "$ROOT"/target/autobuilder-*-SNAPSHOT.jar 2>/dev/null | head -1)
[ -n "$JAR" ] || { echo "MERGE: refused -- no application jar under target/ (mvn package first)"; exit 1; }
[ -e "$C" ] && [ -n "$(ls -A "$C" 2>/dev/null)" ] && { echo "MERGE: refused -- $C is not empty"; exit 1; }
if ! diff -rq "$A/src/main" "$B/src/main" > /dev/null 2>&1; then
    echo "MERGE: refused -- src/main differs between the parents; this slice merges test layers over identical code"
    exit 1
fi
mkdir -p "$C"
( cd "$A" && tar --exclude=./src/test --exclude=./target -cf - . ) | ( cd "$C" && tar -xf - )
java -cp "$JAR" -Dloader.main=com.walpro.autobuilder.core.merge.LineageTestMerge \
    org.springframework.boot.loader.launch.PropertiesLauncher "$A/src/test" "$B/src/test" "$C" "$LA" "$LB" \
    || { echo "MERGE: refused -- the merge itself failed"; exit 1; }
# The claim is checked, not assumed: every test from both parents must still pass in the child.
if (cd "$C" && timeout 900 mvn -B -q -o test > "$C/MERGE-SUITE.txt" 2>&1); then
    n=$(cat "$C"/target/surefire-reports/TEST-*.xml 2>/dev/null | grep -o '<testsuite [^>]*tests="[0-9]*"' | grep -o 'tests="[0-9]*"' | grep -o '[0-9]*' | paste -sd+ | bc)
    rm -rf "${C:?}/target"
    echo "MERGE: ok -- $(grep -c . "$C/MERGE-CONFLICTS.txt") conflict(s), child suite passes (${n:-?} tests); provenance in $C/MERGE-PROVENANCE.tsv"
else
    echo "MERGE: suite-failing -- the child's suite does not pass; see $C/MERGE-SUITE.txt"
    exit 1
fi
