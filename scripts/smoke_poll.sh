#!/bin/bash
# smoke_poll.sh — the 21-check behaviour smoke against a booted poll-voting-API artifact
# (the T4-CK second target). Usage: scripts/smoke_poll.sh http://localhost:8085
# Last line: SMOKE: <pass>/21
B="${1:?usage: smoke_poll.sh <base-url>}"
P='Content-Type: application/json'
n=0; pass=0
c(){ curl -s -o /tmp/smoke_body -w '%{http_code}' "$@"; }
chk(){ n=$((n+1)); if [ "$1" = "$2" ]; then pass=$((pass+1)); echo "PASS [$n] $3 ($1)"; else echo "FAIL [$n] $3 (want $2 got $1) | $(head -c 100 /tmp/smoke_body)"; fi; }
jid(){ python3 -c "import json;print(json.load(open('/tmp/smoke_body'))['id'])" 2>/dev/null || echo 1; }
# body-content check: python expression over the last response body; truthy = pass
chkbody(){ n=$((n+1)); if python3 -c "
import json,sys
b=json.load(open('/tmp/smoke_body'))
sys.exit(0 if ($1) else 1)" 2>/dev/null; then pass=$((pass+1)); echo "PASS [$n] $2"; else echo "FAIL [$n] $2 | $(head -c 120 /tmp/smoke_body)"; fi; }

chk "$(c -X POST $B/polls -H "$P" -d '{"question":"Best language?","options":["Java","Kotlin","Scala"]}')" 201 "POST /polls valid"
POLL1=$(jid)
chk "$(c -X POST $B/polls -H "$P" -d '{"options":["A","B"]}')" 400 "POST /polls missing question"
chk "$(c -X POST $B/polls -H "$P" -d "{\"question\":\"$(printf 'q%.0s' {1..201})\",\"options\":[\"A\",\"B\"]}")" 400 "POST /polls question>200"
chk "$(c -X POST $B/polls -H "$P" -d '{"question":"One option?","options":["Only"]}')" 400 "POST /polls 1 option"
chk "$(c -X POST $B/polls -H "$P" -d '{"question":"Dups?","options":["A","A"]}')" 400 "POST /polls duplicate options"
chk "$(c $B/polls)" 200 "GET /polls"
chk "$(c $B/polls/$POLL1)" 200 "GET /polls/{id}"
chk "$(c $B/polls/99999)" 404 "GET /polls absent"
chk "$(c -X POST $B/polls/$POLL1/votes -H "$P" -d '{"voterEmail":"ann@x.io","option":"Java"}')" 201 "POST vote valid"
chk "$(c -X POST $B/polls/99999/votes -H "$P" -d '{"voterEmail":"ann@x.io","option":"Java"}')" 404 "POST vote absent poll"
chk "$(c -X POST $B/polls/$POLL1/votes -H "$P" -d '{"voterEmail":"bob@x.io","option":"Rust"}')" 400 "POST vote option not in poll"
chk "$(c -X POST $B/polls/$POLL1/votes -H "$P" -d '{"voterEmail":"ann@x.io","option":"Kotlin"}')" 409 "POST vote repeat voter"
chk "$(c -X POST $B/polls/$POLL1/votes -H "$P" -d '{"voterEmail":"bob@x.io","option":"Java"}')" 201 "POST vote 2nd voter"
chk "$(c $B/polls/$POLL1/results)" 200 "GET results"
chkbody "b.get('tallies',{}).get('Java')==2" "results tally Java==2"
chkbody "b.get('tallies',{}).get('Scala')==0" "results include zero-vote option"
chk "$(c $B/polls/99999/results)" 404 "GET results absent"
chk "$(c -X POST $B/polls/$POLL1/close)" 200 "POST close"
chk "$(c -X POST $B/polls/$POLL1/close)" 409 "POST close again"
chk "$(c -X POST $B/polls/$POLL1/votes -H "$P" -d '{"voterEmail":"eve@x.io","option":"Java"}')" 422 "POST vote after close"
c $B/polls/$POLL1/results >/dev/null
chkbody "b.get('closed')==True" "results closed flag"
echo "SMOKE: $pass/21"
