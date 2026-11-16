#!/bin/bash
# post_adopted_goal.sh -- the operator's step in an ADOPT_PROJECT run (T4-CQ): wait until the app
# has adopted the target and is serving, save its cold read, then post the goal.
#
# Usage: scripts/post_adopted_goal.sh <target> <goal-file> [port]
#   e.g. PREFLIGHT_TARGET=structdiff PREFLIGHT_EXTRA_FLAGS="--autobuilder.start.mode=ADOPT_PROJECT" \
#          setsid nohup scripts/preflight.sh live 60 > /tmp/run.log 2>&1 < /dev/null & disown
#        scripts/post_adopted_goal.sh structdiff data/structdiff/bootstrap/seed.txt
#
# The goal file is posted byte for byte: bootstrap/seed.obligations.txt is keyed to the hash of
# exactly this text, so an edited copy makes the obligations read as SEED_MOVED.
# The cold read is saved beside the target's logs as coldread.txt.
set -u
TGT="${1:?usage: post_adopted_goal.sh <target> <goal-file> [port]}"
GOAL="${2:?usage: post_adopted_goal.sh <target> <goal-file> [port]}"
PORT="${3:-8081}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[ -f "$GOAL" ] || { echo "no goal file: $GOAL" >&2; exit 1; }
end=$((SECONDS + 900))
# Ready means: the run on THIS target says it is waiting for a goal (GET /autobuilder/start-state). Its log was the
# signal before -- a log written after this script started, holding the adopt and start lines -- but an adopted run
# waiting for its goal logs nothing, so when its last line came just before this script began, the script waited out
# its 15 minutes and the run was ended as silent (2026-11-15 rev-1 of batch field144; 2026-11-16, 11 minutes lost).
# The target in the answer keeps a previous run on the same port from being taken for this one.
want="$(cd "$ROOT/data/$TGT" 2>/dev/null && pwd)"
ready() {
    curl -s -m 10 "http://localhost:$PORT/autobuilder/start-state" 2>/dev/null | python3 -c "
import sys, json
try:
    s = json.load(sys.stdin)
except Exception:
    sys.exit(1)
sys.exit(0 if s.get('awaitingGoal') is True and s.get('targetPath') == sys.argv[1] else 1)" "$want"
}
until ready; do
    [ $SECONDS -gt $end ] && { echo "the app on port $PORT never waited for a goal on $TGT within 15 minutes" >&2; exit 1; }
    sleep 5
done
curl -s -m 120 "http://localhost:$PORT/autobuilder/cold-read" > "$ROOT/data/$TGT/logs/coldread.txt"
echo "cold read: $(wc -c < "$ROOT/data/$TGT/logs/coldread.txt") bytes -> data/$TGT/logs/coldread.txt"
answer=$(curl -s -m 120 -X POST -H 'Content-Type: text/plain' --data-binary "@$GOAL" "http://localhost:$PORT/autobuilder/goal")
echo "$answer"
echo "$answer" | grep -q '"ok" : true' || { echo "the goal was not set on $TGT" >&2; exit 1; }
