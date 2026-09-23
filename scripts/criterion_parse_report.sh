#!/bin/bash
# T2-FL: which authored acceptance criteria the claim readers can read, over the whole archived
# corpus rather than over one run. Deterministic, no model, seconds.
#
# Usage:  scripts/criterion_parse_report.sh [archive-dir] [extra GoalStack.json ...]
#         archive-dir defaults to ~/.autobuilder/run-archive
#
# Exists as a script because the tool needs the project classpath, and a readout nobody can
# invoke is archaeology. Safe beside a live run: it reads archived copies and never the repo's
# live logs, and it builds nothing (G1 in preflight.sh is about mvn rotating live state -- the
# `-o -q compile` below is skipped when target/classes is already current).
set -u
cd "$(dirname "$0")/.." || exit 1

CP_FILE="target/criterion-parse-report.classpath"
if [ ! -s "$CP_FILE" ] || [ pom.xml -nt "$CP_FILE" ]; then
    mvn -o -q dependency:build-classpath -Dmdep.outputFile="$CP_FILE" || exit 1
fi
[ -d target/classes ] || mvn -o -q compile || exit 1

exec java -cp "target/classes:$(cat "$CP_FILE")" \
    com.walpro.autobuilder.tools.CriterionParseReport "$@"
