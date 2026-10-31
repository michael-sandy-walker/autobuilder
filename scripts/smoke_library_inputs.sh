#!/bin/bash
# smoke_library_inputs.sh -- the yardstick of the T4-EC measuring case (data/library-inputs), per stage.
# Usage: SMOKE_STAGE=<stage> [SMOKE_TREE=<tree>] scripts/smoke_library_inputs.sh <base-url>
# Last line: SMOKE: <pass>/<checks>.
#
# Stages, each cumulative: base (no input yet), clitarget (the import and export), shelfkit (its ISBN rule, the
# decided title limit, its own tests), withdrawn (clitarget taken out again, shelfkit's part stays). SMOKE_TREE is the
# project's tree, for the check that shelfkit's own tests pass there.
#
# The 23 checks are smoke23.sh's, read from it, with two changes the case states. Its ISBNs fail shelfkit's check digit,
# so they are replaced by valid ones at every stage, keeping the stages comparable and smoke23.sh itself untouched. And
# from the shelfkit stage on, a 121-character title is accepted: 200 wins (bootstrap/inputs/inputs.json, decisions).
# Asserts only what the seed and the intents in inputs.json state.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
URL="${1:?usage: smoke_library_inputs.sh <base-url>}"
STAGE="${SMOKE_STAGE:-shelfkit}"
case "$STAGE" in base|clitarget|shelfkit|withdrawn) ;; *) echo "unknown stage: $STAGE"; exit 2 ;; esac
case "$STAGE" in shelfkit|withdrawn) DECIDED=1 ;; *) DECIDED= ;; esac

valid_isbn(){ case "$1" in 2) echo 9780000000002 ;; 3) echo 9780000000019 ;; 4) echo 9780000000026 ;; esac; }
GEN=$(mktemp)
sed -e 's/1234567890123/1234567890128/g' \
    -e 's/111111111111\$i/$(valid_isbn $i)/' \
    -e '/^echo "SMOKE: /d' "$ROOT/scripts/smoke23.sh" > "$GEN"
[ -n "$DECIDED" ] && sed -i 's/" 400 "POST \/books title>120"/" 201 "POST \/books title of 121 characters (200 wins)"/' "$GEN"
set -- "$URL"
. "$GEN"
rm -f "$GEN"

B="$URL"; CSV='Content-Type: text/csv'
title_listed(){ c "$B/books" >/dev/null; python3 -c "
import json,sys
print('yes' if sys.argv[1] in [b.get('title') for b in json.load(open('/tmp/smoke_body'))] else 'no')" "$1" 2>/dev/null || echo no; }
long(){ printf 'x%.0s' $(seq 1 "$1"); }

if [ "$STAGE" = clitarget ] || [ "$STAGE" = shelfkit ]; then
  GOOD=$'title,isbn\n"Dune, Part Two",9780441013593\n"He said ""hi""",9780000000033\n'
  chk "$(c -X POST "$B/books/import" -H "$CSV" --data-binary "$GOOD")" 201 "POST /books/import valid"
  chk "$(python3 -c "import json;print(json.load(open('/tmp/smoke_body')).get('imported'))" 2>/dev/null)" 2 "import reports the rows"
  chk "$(title_listed 'Dune, Part Two')" yes "a quoted comma stays in one field"
  chk "$(title_listed 'He said "hi"')" yes "a doubled quote is one quote"
  chk "$(c "$B/books/export")" 200 "GET /books/export"
  chk "$(head -1 /tmp/smoke_body | tr -d '\r')" "title,isbn" "export header"
  chk "$(grep -cF '"Dune, Part Two",9780441013593' /tmp/smoke_body)" 1 "export quotes a comma"
  chk "$(grep -cF '"He said ""hi""",9780000000033' /tmp/smoke_body)" 1 "export doubles a quote"
  chk "$(c -X POST "$B/books/import" -H "$CSV" --data-binary $'title,isbn\nKept out,9780000000040\none-field-only\n')" 400 "import refuses a row of the wrong width"
  chk "$(title_listed 'Kept out')" no "a refused import creates no book"
  chk "$(c -X POST "$B/books/import" -H "$CSV" --data-binary $'title,isbn\nBad digits,12345\n')" 400 "import validates the ISBN"
fi

if [ -n "$DECIDED" ]; then
  chk "$(c -X POST "$B/books" -H "$P" -d '{"title":"Wrong digit","isbn":"1234567890123"}')" 400 "a wrong check digit is refused"
  chk "$(c -X POST "$B/books" -H "$P" -d '{"title":"Right digit","isbn":"9780000000057"}')" 201 "a right check digit is accepted"
  chk "$(c -X POST "$B/books" -H "$P" -d "{\"title\":\"$(long 200)\",\"isbn\":\"9780000000064\"}")" 201 "a title of 200 characters (200 wins)"
  chk "$(c -X POST "$B/books" -H "$P" -d "{\"title\":\"$(long 201)\",\"isbn\":\"9780000000071\"}")" 400 "a title of 201 characters"
  ran=absent
  if [ -n "${SMOKE_TREE:-}" ]; then
    SCRATCH=$(mktemp -d); cp -a "$SMOKE_TREE" "$SCRATCH/p"; find "$SCRATCH/p" -name target -type d -prune -exec rm -rf {} +
    (cd "$SCRATCH/p" && timeout 600 mvn -B -q -o test > "$SCRATCH/test.log" 2>&1)
    ran=$(python3 - "$SCRATCH/p" <<'PY'
import glob, os, sys, xml.etree.ElementTree as ET
fs = glob.glob(os.path.join(sys.argv[1], '**', 'surefire-reports', 'TEST-*ShelfkitTest.xml'), recursive=True)
if not fs: print('absent'); sys.exit()
r = ET.parse(fs[0]).getroot()
print('pass' if int(r.get('tests')) >= 7 and r.get('failures') == '0' and r.get('errors') == '0' else 'fail')
PY
)
    rm -rf "$SCRATCH"
  fi
  chk "$ran" pass "shelfkit's own tests pass in the project"
fi

if [ "$STAGE" = withdrawn ]; then
  chk "$([ "$(c -X POST "$B/books/import" -H "$CSV" --data-binary $'title,isbn\nGone,9780000000088\n')" = 201 ] && echo present || echo gone)" gone "the import is gone"
  chk "$(title_listed 'Gone')" no "nothing was imported"
  chk "$([ "$(c "$B/books/export")" = 200 ] && echo present || echo gone)" gone "the export is gone"
fi
echo "SMOKE: $pass/$n"
