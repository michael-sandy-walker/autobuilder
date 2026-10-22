#!/bin/bash
# prepare_session_case.sh -- T4-EC stage 4 slice 4: make an integration case's CHILD through the bridge, from the
# merge session tracked with the case (bootstrap/session/edits.ndjson), once. The child goes to bootstrap/child and is
# tracked; every run then gets its own copy of the session to report into (prepare_integration_run.sh).
# Needs the application on PORT (it serves POST /autobuilder/merge-sessions/{id}/integrate).
#
# Usage: scripts/prepare_session_case.sh <case> <into-label> <into-run> <into-dir> <brought-label> <brought-run> <brought-dir> [PORT]
set -euo pipefail
CASE="${1:?usage: prepare_session_case.sh <case> <into-label> <into-run> <into-dir> <brought-label> <brought-run> <brought-dir> [port]}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
B="$ROOT/data/$CASE/bootstrap"
PORT="${8:-8097}"
SID="$CASE-prepare"
SESSIONS="${MERGE_SESSIONS_DIR:-$HOME/.autobuilder/merge-sessions}"
rm -rf "$SESSIONS/$SID" "$B/child"
mkdir -p "$SESSIONS/$SID"
cp "$B/session/edits.ndjson" "$SESSIONS/$SID/edits.ndjson"
python3 - "$B" "$SID" "$2" "$3" "$4" "$5" "$6" "$7" "$PORT" <<'PY'
import json, sys, urllib.request
b, sid, il, ir, idir, bl, br, bdir, port = sys.argv[1:]
lines = [l.rstrip("\n") for l in open(b + "/integration/obligations.txt") if l.strip() and not l.startswith("#")]
body = {"into": {"label": il, "runId": ir, "dir": idir}, "brought": {"label": bl, "runId": br, "dir": bdir},
        "goal": open(b + "/integration/goal.txt").read(), "obligations": lines, "child": b + "/child"}
req = urllib.request.Request(f"http://localhost:{port}/autobuilder/merge-sessions/{sid}/integrate", data=json.dumps(body).encode(),
                             headers={"Content-Type": "application/json"})
try:
    r = json.load(urllib.request.urlopen(req))
except urllib.error.HTTPError as e:
    sys.exit("the bridge refused: " + e.read().decode())
print("bridge:", r["files"], "files, conflicts", r["conflicts"])
PY
# The integration's specification goes with the receiving module's tests.
INTO_DIR=$(python3 -c "import re,sys; print(re.search(r'<module>([^<]+)</module>\s*</modules>', open(sys.argv[1]).read()).group(1))" "$B/child/output/pom.xml")
mkdir -p "$B/child/output/$INTO_DIR/src/test/java"; cp -a "$B/integration/spec/." "$B/child/output/$INTO_DIR/src/test/java/"
cp "$B/child/bootstrap/seed.txt" "$B/child/bootstrap/seed.obligations.txt" "$B/"
[ -f "$B/child/bootstrap/protected-paths.txt" ] && cp "$B/child/bootstrap/protected-paths.txt" "$B/"
{ echo "# The case: every source either project already has stays exactly as it is."
  (cd "$B/child/output" && find . -path '*/src/main/*' -name '*.java' | sed 's|^\./||' | sort); } >> "$B/protected-paths.txt"
echo "prepared $CASE from session $SID: $(find "$B/child/output" -type f | wc -l) files; decisions:"
python3 -c "import json,sys; [print('  -', d) for d in json.load(open(sys.argv[1]))['decisions']]" "$B/child/meta/merge-session.json"
