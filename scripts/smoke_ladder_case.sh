#!/bin/bash
# The yardstick for a ladder case (scripts/ladder_case.py): smoke_criterion_status.sh's checks over the case's
# pristine tree -- every case of every specification class passes, src/test is unmodified, the module's other
# sources are unchanged, the whole suite passes.
# The case is the tree's own: ladder_case.py names each module's pom artifactId after its case, so the tree
# being scored says which case it is (the callers -- preflight, early_stop -- pass only the artifact path;
# the first version read PREFLIGHT_TARGET, which early_stop's environment does not carry, and scored "?").
HERE="$(cd "$(dirname "$0")" && pwd)"
JAR="${1:?usage: smoke_ladder_case.sh <artifact-jar>}"
TREE="$(cd "$(dirname "$JAR")/.." && pwd)"
CASE=$(python3 -c "import re,sys; m=re.search(r'<artifactId>([^<]+)</artifactId>', re.sub(r'<parent>.*?</parent>', '', open(sys.argv[1]).read(), flags=re.S)); print(m.group(1) if m else '')" "$TREE/pom.xml" 2>/dev/null)
[ -n "$CASE" ] && [ -d "$HERE/../data/$CASE/bootstrap" ] || { echo "smoke_ladder_case: no ladder case named by $TREE/pom.xml"; echo "SMOKE: 0/1"; exit 0; }
SMOKE_TARGET="$CASE" SMOKE_PREPARE="$HERE/prepare_module_change.sh" exec "$HERE/smoke_criterion_status.sh" "$@"
