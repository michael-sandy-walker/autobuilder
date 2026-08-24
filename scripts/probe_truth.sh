#!/bin/bash
# probe_truth.sh — boot an artifact and send, for each disputed criterion, the request the PROBE
# sent beside the request the CRITERION describes. (T1-FU / T1-FV / T1-FW)
#
# WHY THIS EXISTS. Three separate probe defects were each diagnosed twice wrongly by reading
# interleaved log windows, and each fell out immediately once the two requests were placed side by
# side against a running artifact. This makes that comparison repeatable instead of a sequence of
# ad-hoc curls, so a fix can be checked with the SAME instrument before and after.
#
# Reading it: every PROBE row that differs from its CRITERION row is the harness asking a different
# question from the one the specification states — the artifact is answering correctly and being
# recorded as wrong.
#
# Usage: scripts/probe_truth.sh <path-to-jar> [port]
JAR="${1:?usage: probe_truth.sh <jar> [port]}"; PORT="${2:-18080}"
java -jar "$JAR" --server.port=$PORT > /tmp/probe_truth_app.log 2>&1 &
APP=$!
trap 'kill $APP 2>/dev/null' EXIT
until curl -s -m 2 -o /dev/null http://localhost:$PORT/books 2>/dev/null; do sleep 2; done
P='Content-Type: application/json'
code(){ curl -s -o /dev/null -w '%{http_code}' "$@"; }
row(){ printf "  %-9s %-52s -> %s\n" "$1" "$2" "$3"; }

echo "== T1-FW: query string =="
M=$(curl -s -X POST localhost:$PORT/members -H "$P" -d '{"name":"A","email":"a@x.io"}' | python3 -c "import json,sys;print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
row "PROBE"     "GET /loans"                     "$(code localhost:$PORT/loans)"
row "CRITERION" "GET /loans?memberId=$M"         "$(code "localhost:$PORT/loans?memberId=$M")"

echo "== T1-FV: entity-derived body fields =="
row "PROBE"     'POST /loans {..,createdAt,returnedAt}' "$(code -X POST localhost:$PORT/loans -H "$P" -d '{"bookId":999999999,"memberId":999999999,"createdAt":"2026-01-01T00:00:00Z","returnedAt":"2026-01-01T00:00:00Z"}')"
row "CRITERION" 'POST /loans {bookId,memberId}'        "$(code -X POST localhost:$PORT/loans -H "$P" -d '{"bookId":999999999,"memberId":999999999}')"

echo "== T1-FU: bounded-count setup =="
M2=$(curl -s -X POST localhost:$PORT/members -H "$P" -d '{"name":"B","email":"b@x.io"}' | python3 -c "import json,sys;print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
for i in 1 2 3 4; do
  B=$(curl -s -X POST localhost:$PORT/books -H "$P" -d "{\"title\":\"T$i\",\"isbn\":\"978030640615$i\"}" | python3 -c "import json,sys;print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
  row "CRITERION" "loan $i of 4 (different books)" "$(code -X POST localhost:$PORT/loans -H "$P" -d "{\"bookId\":$B,\"memberId\":$M2}")"
done
echo "  (probe truncates to ONE loan, then asserts the 4th-loan rule)"
