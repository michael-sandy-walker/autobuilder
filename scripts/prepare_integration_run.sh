#!/bin/bash
# prepare_integration_run.sh -- lay out an integration case's tracked child for a run (T4-EC stage 2): its code into
# <dest>/output, its merged goal tree into <dest>/meta/GoalStack.json; for the dependency layout, the brought project
# is installed into the local Maven repository from the copy kept with the case.
# Usage: scripts/prepare_integration_run.sh <case> <dest>   (dest = the target directory, e.g. data/<case>)
set -euo pipefail
CASE="${1:?usage: prepare_integration_run.sh <case> <dest>}"; DEST="${2:?}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
C="$ROOT/data/$CASE/bootstrap/child"
[ -e "$DEST/output" ] && [ -n "$(ls -A "$DEST/output" 2>/dev/null)" ] && { echo "refusing: $DEST/output is not empty" >&2; exit 1; }
mkdir -p "$DEST/output" "$DEST/meta"
cp -a "$C/output/." "$DEST/output/"
cp "$C/meta/GoalStack.json" "$DEST/meta/GoalStack.json"
if [ -d "$C/dependency/linetokens" ]; then
    S=$(mktemp -d); cp -a "$C/dependency/linetokens/." "$S/"
    (cd "$S" && mvn -B -q -o install > "$S.install.log" 2>&1) || { echo "installing the dependency failed: $S.install.log" >&2; exit 1; }
    rm -rf "$S"
fi
echo "laid out $CASE into $DEST"
