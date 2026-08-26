#!/bin/bash
# smoke_cli_quoted.sh — behaviour smoke for the QUOTED-FIELD CLI target (T4-DD's harder non-REST
# specification: the one where the obvious implementation is wrong).
# Usage: scripts/smoke_cli_quoted.sh <path-to-jar>
# Last line: SMOKE: <pass>/19
#
# Same SHAPE as smoke23.sh and smoke_cli.sh — numbered checks, one line each, a machine-readable
# last line. What it adds over smoke_cli.sh is a SEPARATING axis: a reader that splits on commas
# passes the arity and exit-code checks and fails the quoting ones, so a plausible-but-wrong
# implementation scores partway rather than zero. That separation is the point of the harder spec.
JAR="${1:?usage: smoke_cli_quoted.sh <jar>}"
n=0; pass=0
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT

printf 'name,note,city\nada,"born 1815, London",london\ngrace,"said ""hello""",new york\n' > "$WORK/good.csv"
printf 'name,note,city\nada,"born 1815, London"\ngrace,x,y,z\n'                            > "$WORK/bad.csv"
printf 'name,note\nada,"line one\nline two"\ngrace,plain\n'                                > "$WORK/multi.csv"
: > "$WORK/empty.csv"

run(){ out=$(java -jar "$JAR" "$@" 2>"$WORK/err"); code=$?; err=$(cat "$WORK/err"); }
chk(){ n=$((n+1)); if [ "$1" = "$2" ]; then pass=$((pass+1)); echo "PASS [$n] $3"; else echo "FAIL [$n] $3 (want [$2] got [$1])"; fi; }

# Quoting — the axis a naive comma split gets wrong.
run check "$WORK/good.csv";            chk "$code" 0 "quoted commas do not split a field"
run check "$WORK/good.csv";            chk "$(printf '%s' "$out" | wc -c)" 0 "well-formed file prints nothing"
run count "$WORK/good.csv";            chk "$out" 2 "count ignores quoted commas"
run column "$WORK/good.csv" note;      chk "$out" "$(printf 'born 1815, London\nsaid "hello"')" "column unquotes and keeps embedded commas"
run column "$WORK/good.csv" city;      chk "$out" "$(printf 'london\nnew york')" "column reads the field after a quoted one"

# Multi-line quoted fields: one row, physical line numbering.
run count "$WORK/multi.csv";           chk "$out" 2 "a row spanning physical lines counts once"
run check "$WORK/multi.csv";           chk "$code" 0 "a row spanning physical lines is well formed"

# Malformed rows.
run check "$WORK/bad.csv";             chk "$code" 2 "malformed file exits 2"
run check "$WORK/bad.csv";             chk "$(printf '%s' "$out" | grep -c '^line ')" 2 "both malformed rows reported"
run check "$WORK/bad.csv";             chk "$(printf '%s' "$out" | head -1)" "line 2: expected 3 fields, found 2" "first malformed row named exactly"
run check "$WORK/bad.csv";             chk "$(printf '%s' "$out" | tail -1)" "line 3: expected 3 fields, found 4" "second malformed row named exactly"

# Empty file.
run check "$WORK/empty.csv";           chk "$code" 2 "empty file has no header — check exits 2"
run count "$WORK/empty.csv";           chk "$out" 0 "empty file counts zero rows"

# Exit-status surface.
run column "$WORK/good.csv" salary;    chk "$code" 4 "unknown column exits 4"
run column "$WORK/good.csv" salary;    chk "$(printf '%s' "$err" | grep -c 'no such column: salary')" 1 "unknown column names itself on stderr"
run check "$WORK/missing.csv";         chk "$code" 1 "missing file exits 1"
run frobnicate "$WORK/good.csv";       chk "$code" 3 "unknown command exits 3"
run CHECK "$WORK/good.csv";            chk "$code" 3 "command words are case sensitive"
run count;                             chk "$code" 5 "wrong argument count exits 5"

echo "SMOKE: $pass/$n"
