#!/bin/bash
# smoke_cli.sh — behaviour smoke for the CLI target (T4-DD's non-REST second target).
# Usage: scripts/smoke_cli.sh <path-to-jar>
# Last line: SMOKE: <pass>/12
#
# Deliberately the same SHAPE as smoke23.sh — numbered checks, one line each, a machine-readable
# last line — so the harness scores it identically. What differs is the observation channel:
# exit status and stdout instead of HTTP status and a JSON body. That difference is the point:
# it is the thing T4-DD's "execution and observation" seam has never had to vary.
JAR="${1:?usage: smoke_cli.sh <jar>}"
n=0; pass=0
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
printf 'name,age,city\nada,36,london\ngrace,45,new york\n'            > "$WORK/good.csv"
printf 'name,age,city\nada,36\ngrace,45,new york,extra\n'             > "$WORK/bad.csv"

run(){ out=$(java -jar "$JAR" "$@" 2>"$WORK/err"); code=$?; err=$(cat "$WORK/err"); }
chk(){ n=$((n+1)); if [ "$1" = "$2" ]; then pass=$((pass+1)); echo "PASS [$n] $3"; else echo "FAIL [$n] $3 (want $2 got $1)"; fi; }

run check "$WORK/good.csv";        chk "$code" 0 "check well-formed exits 0"
run check "$WORK/good.csv";        chk "$(echo -n "$out" | wc -l)" 0 "check well-formed prints nothing"
run check "$WORK/bad.csv";         chk "$code" 2 "check malformed exits 2"
run check "$WORK/bad.csv";         chk "$(echo "$out" | grep -c '^line ')" 2 "check reports both malformed rows"
run check "$WORK/bad.csv";         chk "$(echo "$out" | head -1 | grep -c 'line 2')" 1 "malformed rows reported in line order"
run count "$WORK/good.csv";        chk "$out" 2 "count prints data rows only"
run count "$WORK/good.csv";        chk "$code" 0 "count exits 0"
run column "$WORK/good.csv" age;   chk "$out" "$(printf '36\n45')" "column prints values in file order"
run column "$WORK/good.csv" salary;chk "$code" 4 "unknown column exits 4"
run check "$WORK/missing.csv";     chk "$code" 1 "missing file exits 1"
run frobnicate "$WORK/good.csv";   chk "$code" 3 "unknown command exits 3"
run count;                         chk "$code" 5 "wrong argument count exits 5"

echo "SMOKE: $pass/12"
