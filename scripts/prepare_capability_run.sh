#!/bin/bash
# prepare_capability_run.sh -- lay out a capability-import case's frozen receiver for a run (T4-EI): its code into
# data/<case>/output, its goal tree into data/<case>/meta/GoalStack.json. The source stays where it is frozen
# (bootstrap/source), read-only; the run brings capabilities from it.
# Usage: scripts/prepare_capability_run.sh <case>
set -euo pipefail
CASE="${1:?usage: prepare_capability_run.sh <case>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
B="$ROOT/data/$CASE/bootstrap"; DEST="$ROOT/data/$CASE"
[ -d "$B/receiver/output" ] || { echo "refusing: $B/receiver/output is missing" >&2; exit 1; }
[ -e "$DEST/output" ] && [ -n "$(ls -A "$DEST/output" 2>/dev/null)" ] && { echo "refusing: $DEST/output is not empty" >&2; exit 1; }
[ -e "$DEST/meta" ] && [ -n "$(ls -A "$DEST/meta" 2>/dev/null)" ] && { echo "refusing: $DEST/meta is not empty" >&2; exit 1; }
# The frozen source must still be what was frozen: a run measured against a changed source measures nothing.
( cd "$B" && sha256sum --quiet -c SOURCE-DIGESTS.txt ) || { echo "refusing: the frozen source or receiver changed" >&2; exit 1; }
mkdir -p "$DEST/output" "$DEST/meta"
cp -a "$B/receiver/output/." "$DEST/output/"
cp "$B/receiver/meta/GoalStack.json" "$DEST/meta/GoalStack.json"
echo "laid out $CASE's receiver into $DEST"
