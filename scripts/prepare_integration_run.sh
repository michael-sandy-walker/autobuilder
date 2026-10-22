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
# A child made from a merge session reports its run into its own copy of that session (T4-EC slice 4, live).
if [ -f "$C/meta/merge-session.json" ] && [ -f "$ROOT/data/$CASE/bootstrap/session/edits.ndjson" ]; then
    SID="$CASE-$(date +%Y%m%d-%H%M%S)"
    SESSIONS="${MERGE_SESSIONS_DIR:-$HOME/.autobuilder/merge-sessions}"
    mkdir -p "$SESSIONS/$SID"; cp "$ROOT/data/$CASE/bootstrap/session/edits.ndjson" "$SESSIONS/$SID/edits.ndjson"
    python3 -c "import json,sys; d=json.load(open(sys.argv[1])); d['sessionId']=sys.argv[2]; json.dump(d, open(sys.argv[3],'w'), indent=2)" \
        "$C/meta/merge-session.json" "$SID" "$DEST/meta/merge-session.json"
    echo "the run reports into merge session $SID -- canvas: /merge.html?session=$SID&follow=1"
fi
echo "laid out $CASE into $DEST"
