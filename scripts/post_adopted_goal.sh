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
LOG="$ROOT/data/$TGT/logs/autobuilder.log"
[ -f "$GOAL" ] || { echo "no goal file: $GOAL" >&2; exit 1; }
end=$((SECONDS + 900))
# Only a log written after this script started: launched beside a run, the unsuffixed log is still the PREVIOUS
# run's until the new JVM rotates it, and it holds both lines below (2026-11-04: the goal was posted 14 s before the
# app existed, and the run waited for a goal until it was called silent).
since=$(mktemp)
trap 'rm -f "$since"' EXIT
# Both lines: the adopt line is logged before the web server is up, and posting then races Tomcat.
until [ -f "$LOG" ] && [ "$LOG" -nt "$since" ] && grep -q 'T4-CQ: adopted' "$LOG" && grep -q 'Started AutobuilderApplication' "$LOG"; do
    [ $SECONDS -gt $end ] && { echo "the app never adopted $TGT within 15 minutes" >&2; exit 1; }
    sleep 5
done
curl -s "http://localhost:$PORT/autobuilder/cold-read" > "$ROOT/data/$TGT/logs/coldread.txt"
echo "cold read: $(wc -c < "$ROOT/data/$TGT/logs/coldread.txt") bytes -> data/$TGT/logs/coldread.txt"
curl -s -X POST -H 'Content-Type: text/plain' --data-binary "@$GOAL" "http://localhost:$PORT/autobuilder/goal"
echo
