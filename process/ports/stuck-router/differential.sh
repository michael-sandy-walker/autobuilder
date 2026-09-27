#!/bin/bash
# differential.sh <module-dir> <out.tsv> -- the stuck router's answers to every archived signature
# (differential-signatures.tsv), from the StuckQuestions in <module-dir>. Used by process/port_review.py.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
MOD="$1"; OUT="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"
cp "$HERE/DifferentialDumpTest.java" "$MOD/src/test/java/com/walpro/autobuilder/core/build/"
(cd "$MOD" && MAVEN_OPTS=-Xmx768m mvn -o -q test -Dtest=DifferentialDumpTest -Dsurefire.failIfNoSpecifiedTests=false \
    "-DargLine=-Ddiff.in=$HERE/differential-signatures.tsv -Ddiff.out=$OUT" > /dev/null 2>&1) || true
rm -f "$MOD/src/test/java/com/walpro/autobuilder/core/build/DifferentialDumpTest.java"
[ -s "$OUT" ] || { echo "differential.sh: no answers written" >&2; exit 1; }
