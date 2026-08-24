#!/bin/bash
# smoke23.sh — the 23-check behaviour smoke against a booted library-API artifact.
# Usage: scripts/smoke23.sh http://localhost:8085
# Last line: SMOKE: <pass>/23
B="${1:?usage: smoke23.sh <base-url>}"
P='Content-Type: application/json'
n=0; pass=0
c(){ curl -s -o /tmp/smoke_body -w '%{http_code}' "$@"; }
chk(){ n=$((n+1)); if [ "$1" = "$2" ]; then pass=$((pass+1)); echo "PASS [$n] $3 ($1)"; else echo "FAIL [$n] $3 (want $2 got $1) | $(head -c 100 /tmp/smoke_body)"; fi; }
jid(){ python3 -c "import json;print(json.load(open('/tmp/smoke_body'))['id'])" 2>/dev/null || echo 1; }
# T6-P: emit an id as a JSON LITERAL. The bodies below interpolate ids unquoted, which is only
# valid JSON when the artifact chose numeric ids. An artifact that chose UUIDs — a legitimate
# design this specification does not forbid — got `{"bookId":e6de91fa-...}`, which no parser
# accepts, so every POST /loans answered 400 "Invalid request body" and checks [13]-[21] failed
# as a block: nine of twenty-three, capping such a run at 14/23 for the suite's defect rather
# than the artifact's. Proven on run 2026-08-24_19-46-29 by booting its own artifact — the
# suite's body returned 400 and the SAME body with the ids quoted returned 201.
# Numeric ids are emitted bare, exactly as before, so every previously scored run is unaffected
# and the ledger stays comparable (the T6-A precedent: do not silently redefine `smoke`).
jlit(){ case "$1" in (''|*[!0-9]*) printf '"%s"' "$1";; (*) printf '%s' "$1";; esac; }

chk "$(c -X POST $B/books -H "$P" -d '{"title":"Dune","isbn":"1234567890123"}')" 201 "POST /books valid"
BOOK1=$(jid)
# T6-P: an "absent" id has to be the same SHAPE as the ids this artifact issues, or the request
# fails on parsing/binding instead of on the resource being missing — which tests nothing. The
# three path checks below passed on the UUID run only because its controllers happened to declare
# the path variable as String; a controller declaring the id's real type would have answered 400
# to "99999" and failed a check about a MISSING resource for a reason that has nothing to do with
# one. Numeric artifacts keep 99999 exactly as before.
case "$BOOK1" in (''|*[!0-9]*) ABSENT="00000000-0000-0000-0000-000000000000";; (*) ABSENT=99999;; esac
chk "$(c -X POST $B/books -H "$P" -d '{"isbn":"1234567890123"}')" 400 "POST /books missing title"
chk "$(c -X POST $B/books -H "$P" -d "{\"title\":\"$(printf 'x%.0s' {1..121})\",\"isbn\":\"1234567890123\"}")" 400 "POST /books title>120"
chk "$(c -X POST $B/books -H "$P" -d '{"title":"Bad","isbn":"12345"}')" 400 "POST /books bad isbn"
chk "$(c $B/books)" 200 "GET /books"
chk "$(c $B/books/$BOOK1)" 200 "GET /books/{id}"
chk "$(c $B/books/$ABSENT)" 404 "GET /books absent"
chk "$(c -X POST $B/members -H "$P" -d '{"name":"Ann","email":"ann@x.io"}')" 201 "POST /members valid"
MEM1=$(jid)
chk "$(c -X POST $B/members -H "$P" -d '{"name":"Ann2","email":"ann@x.io"}')" 409 "POST /members dup email"
chk "$(c -X POST $B/members -H "$P" -d '{"email":"noname@x.io"}')" 400 "POST /members missing name"
chk "$(c $B/members/$MEM1)" 200 "GET /members/{id}"
chk "$(c $B/members/$ABSENT)" 404 "GET /members absent"
chk "$(c -X POST $B/loans -H "$P" -d "{\"bookId\":$(jlit "$BOOK1"),\"memberId\":$(jlit "$MEM1")}")" 201 "POST /loans valid"
LOAN1=$(jid)
chk "$(c -X POST $B/loans -H "$P" -d "{\"bookId\":$(jlit "$ABSENT"),\"memberId\":$(jlit "$MEM1")}")" 404 "POST /loans bad book"
chk "$(c -X POST $B/loans -H "$P" -d "{\"bookId\":$(jlit "$BOOK1"),\"memberId\":$(jlit "$ABSENT")}")" 404 "POST /loans bad member"
chk "$(c -X POST $B/loans -H "$P" -d "{\"bookId\":$(jlit "$BOOK1"),\"memberId\":$(jlit "$MEM1")}")" 409 "POST /loans book on loan"
for i in 2 3 4; do c -X POST $B/books -H "$P" -d "{\"title\":\"B$i\",\"isbn\":\"111111111111$i\"}" >/dev/null; done
B2=$(c $B/books >/dev/null; python3 -c "
import json
bs=json.load(open('/tmp/smoke_body'))
print(' '.join(str(b['id']) for b in bs[-3:]))" 2>/dev/null || echo "2 3 4")
set -- $B2
chk "$(c -X POST $B/loans -H "$P" -d "{\"bookId\":$(jlit "$1"),\"memberId\":$(jlit "$MEM1")}")" 201 "POST /loans 2nd"
chk "$(c -X POST $B/loans -H "$P" -d "{\"bookId\":$(jlit "$2"),\"memberId\":$(jlit "$MEM1")}")" 201 "POST /loans 3rd"
chk "$(c -X POST $B/loans -H "$P" -d "{\"bookId\":$(jlit "$3"),\"memberId\":$(jlit "$MEM1")}")" 422 "POST /loans 4th -> 422 LIMIT"
chk "$(c -X POST $B/loans/$LOAN1/return)" 200 "POST return"
chk "$(c -X POST $B/loans/$LOAN1/return)" 409 "POST return again"
chk "$(c -X POST $B/loans/$ABSENT/return)" 404 "POST return absent"
chk "$(c "$B/loans?memberId=$MEM1")" 200 "GET /loans?memberId"
echo "SMOKE: $pass/23"
