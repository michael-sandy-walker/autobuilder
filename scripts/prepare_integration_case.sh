#!/bin/bash
# prepare_integration_case.sh -- T4-EC stage 2: make a toy case's CHILD from two parent snapshots, once. The child is the
# input of every run of the case, so it is written into data/<case>/bootstrap/child and tracked (the parents live
# outside the repository). Layout from the case name (ledger-import-packages|dependency|modules).
#
# Usage: scripts/prepare_integration_case.sh <case>
#   INTO / BROUGHT: the parent snapshots (default ~/.autobuilder/cashbook-after-run1, ~/.autobuilder/linetokens-after-run1)
#   INTO_RUN / BROUGHT_RUN: the runs that grew them.
set -euo pipefail
CASE="${1:?usage: prepare_integration_case.sh <case>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
B="$ROOT/data/$CASE/bootstrap"
INTO="${INTO:-$HOME/.autobuilder/cashbook-after-run1}"; INTO_RUN="${INTO_RUN:-2026-10-21_14-04-07}"
BROUGHT="${BROUGHT:-$HOME/.autobuilder/linetokens-after-run1}"; BROUGHT_RUN="${BROUGHT_RUN:-2026-10-21_00-42-59}"
LAYOUT=$(echo "${CASE##*-}" | tr a-z A-Z)
JAR=$(ls "$ROOT"/target/autobuilder-*-SNAPSHOT.jar | head -1)
rm -rf "$B/child"
java -cp "$JAR" -Dloader.main=com.walpro.autobuilder.core.merge.ProjectMerge org.springframework.boot.loader.launch.PropertiesLauncher \
    integrate "$LAYOUT" "$INTO" cashbook "$INTO_RUN" "$BROUGHT" linetokens "$BROUGHT_RUN" \
    "$B/integration/goal.txt" "$B/integration/obligations.txt" "$B/child" 2>&1 | grep -v '^SLF4J\|DEBUG\|INFO' || true
[ -f "$B/child/meta/GoalStack.json" ] || { echo "the merge wrote no child" >&2; exit 1; }
# The integration's specification goes with the receiving project's tests.
T=src/test/java; [ "$LAYOUT" = MODULES ] && T=cashbook/src/test/java
mkdir -p "$B/child/output/$T"; cp -a "$B/integration/spec/." "$B/child/output/$T/"
# A dependency is installed from the brought project's own tree, kept with the case.
if [ "$LAYOUT" = DEPENDENCY ]; then
    mkdir -p "$B/child/dependency"; cp -a "$BROUGHT/output" "$B/child/dependency/linetokens"; rm -rf "$B/child/dependency/linetokens/target"
fi
# The loop reads the seed, obligations and protected paths from bootstrap/.
cp "$B/child/bootstrap/seed.txt" "$B/child/bootstrap/seed.obligations.txt" "$B/"
[ -f "$B/child/bootstrap/protected-paths.txt" ] && cp "$B/child/bootstrap/protected-paths.txt" "$B/"
# The seed keeps every class either project already has exactly as it is: each one is protected whole (new classes
# beside them stay writable).
{ echo "# The case: every source either project already has stays exactly as it is."
  (cd "$B/child/output" && find . -path '*/src/main/*' -name '*.java' | sed 's|^\./||' | sort); } >> "$B/protected-paths.txt"
echo "prepared $CASE ($LAYOUT): $(find "$B/child/output" -type f | wc -l) files; $(cat "$B/child/MERGE-NOTES.txt" 2>/dev/null | head -2)"
