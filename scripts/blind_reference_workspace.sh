#!/bin/bash
# blind_reference_workspace.sh -- an isolated workspace in which an independent writer (a fresh agent that
# has seen neither the loop's code nor the session's port) implements a ticket toy case from exactly what
# the loop had: the pristine module at bootstrap/origin-rev, the seed, and the specification tests.
# Its result is the REFERENCE in the operator's port review (design/ports/<target>/).
#
# No git history, no run data, no Autobuilder sources: the workspace is a plain directory outside the
# repository. What the writer must not read is stated in TASK.md; this machine cannot enforce it, so the
# review packet records that limit.
#
# Usage: scripts/blind_reference_workspace.sh <target> [dest-dir]   (default ~/.autobuilder/blind/<target>-<stamp>)
set -euo pipefail
TGT="${1:?usage: blind_reference_workspace.sh <target> [dest-dir]}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="${2:-$HOME/.autobuilder/blind/$TGT-$(date +%Y-%m-%d_%H-%M-%S)}"
B="$ROOT/data/$TGT/bootstrap"
# A toy case described as data (bootstrap/module/) is prepared generically; an older one by its own script.
PREPARE="$ROOT/scripts/prepare_module_change.sh"
[ -d "$B/module" ] || PREPARE="$ROOT/scripts/prepare_${TGT//-/_}_change.sh"
"$PREPARE" "$TGT" "$DEST" > /dev/null
cp "$B/seed.txt" "$DEST/SEED.txt"
CHANGE=$(grep -o 'The one class to change is [A-Za-z0-9_]*' "$B/seed.txt" | head -1 | awk '{print $NF}')
SPEC=$(grep -o 'The specification is [A-Za-z0-9_]*' "$B/seed.txt" | head -1 | awk '{print $NF}')
cat > "$DEST/TASK.md" <<EOF
# Task: an independent reference implementation

You are writing the REFERENCE implementation for a code review. Another implementation of the same task
exists; you must not see it, so that yours is an independent comparison.

- Read SEED.txt: it is the task, exactly as it was given.
- Change only $CHANGE under src/main/java. The specification is $SPEC under src/test/java; the whole suite
  must pass: \`mvn -o -q test\` in this directory.
- Work ONLY inside this directory ($DEST). Do not read any other path on this machine: not a git
  repository, not ~/.autobuilder, not any data/ folder, not any other copy of $CHANGE.java.
- Write the code you would want to maintain: small, no hard-coded tables of platform facts where they can
  be discovered, existing declarations and comments kept.
- When the suite passes, write REFERENCE-NOTES.md here: what you changed and why, in a few lines, and
  anything in the task you found ambiguous.
EOF
echo "$DEST"
