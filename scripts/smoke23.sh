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

chk "$(c -X POST $B/books -H "$P" -d '{"title":"Dune","isbn":"1234567890123"}')" 201 "POST /books valid"
BOOK1=$(jid)
chk "$(c -X POST $B/books -H "$P" -d '{"isbn":"1234567890123"}')" 400 "POST /books missing title"
chk "$(c -X POST $B/books -H "$P" -d "{\"title\":\"$(printf 'x%.0s' {1..121})\",\"isbn\":\"1234567890123\"}")" 400 "POST /books title>120"
chk "$(c -X POST $B/books -H "$P" -d '{"title":"Bad","isbn":"12345"}')" 400 "POST /books bad isbn"
chk "$(c $B/books)" 200 "GET /books"
chk "$(c $B/books/$BOOK1)" 200 "GET /books/{id}"
chk "$(c $B/books/99999)" 404 "GET /books absent"
chk "$(c -X POST $B/members -H "$P" -d '{"name":"Ann","email":"ann@x.io"}')" 201 "POST /members valid"
MEM1=$(jid)
chk "$(c -X POST $B/members -H "$P" -d '{"name":"Ann2","email":"ann@x.io"}')" 409 "POST /members dup email"
chk "$(c -X POST $B/members -H "$P" -d '{"email":"noname@x.io"}')" 400 "POST /members missing name"
chk "$(c $B/members/$MEM1)" 200 "GET /members/{id}"
chk "$(c $B/members/99999)" 404 "GET /members absent"
chk "$(c -X POST $B/loans -H "$P" -d "{\"bookId\":$BOOK1,\"memberId\":$MEM1}")" 201 "POST /loans valid"
LOAN1=$(jid)
chk "$(c -X POST $B/loans -H "$P" -d "{\"bookId\":99999,\"memberId\":$MEM1}")" 404 "POST /loans bad book"
chk "$(c -X POST $B/loans -H "$P" -d "{\"bookId\":$BOOK1,\"memberId\":99999}")" 404 "POST /loans bad member"
chk "$(c -X POST $B/loans -H "$P" -d "{\"bookId\":$BOOK1,\"memberId\":$MEM1}")" 409 "POST /loans book on loan"
for i in 2 3 4; do c -X POST $B/books -H "$P" -d "{\"title\":\"B$i\",\"isbn\":\"111111111111$i\"}" >/dev/null; done
B2=$(c $B/books >/dev/null; python3 -c "
import json
bs=json.load(open('/tmp/smoke_body'))
print(' '.join(str(b['id']) for b in bs[-3:]))" 2>/dev/null || echo "2 3 4")
set -- $B2
chk "$(c -X POST $B/loans -H "$P" -d "{\"bookId\":$1,\"memberId\":$MEM1}")" 201 "POST /loans 2nd"
chk "$(c -X POST $B/loans -H "$P" -d "{\"bookId\":$2,\"memberId\":$MEM1}")" 201 "POST /loans 3rd"
chk "$(c -X POST $B/loans -H "$P" -d "{\"bookId\":$3,\"memberId\":$MEM1}")" 422 "POST /loans 4th -> 422 LIMIT"
chk "$(c -X POST $B/loans/$LOAN1/return)" 200 "POST return"
chk "$(c -X POST $B/loans/$LOAN1/return)" 409 "POST return again"
chk "$(c -X POST $B/loans/99999/return)" 404 "POST return absent"
chk "$(c "$B/loans?memberId=$MEM1")" 200 "GET /loans?memberId"
echo "SMOKE: $pass/23"
