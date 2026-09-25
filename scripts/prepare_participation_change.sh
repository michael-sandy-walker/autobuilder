#!/bin/bash
# prepare_participation_change.sh -- the pristine tree of a CHANGE target on the participation
# module (T4-CQ: continue an adopted project by changing its code; T4-CS: two such lineages give
# parents whose library code differs). The tree = the module as extracted at its origin-rev
# + the module's characterization suite (the regression suite: existing behaviour, 38 tests)
# + the target's own specification tests (bootstrap/spec), which fail until the code provides them.
#
# Usage: scripts/prepare_participation_change.sh <target> <dest-dir>
set -euo pipefail
TGT="${1:?usage: prepare_participation_change.sh <target> <dest-dir>}"
DEST="${2:?usage: prepare_participation_change.sh <target> <dest-dir>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
"$ROOT/scripts/extract_participation.sh" "$(cat "$ROOT/data/$TGT/bootstrap/origin-rev")" "$DEST" > /dev/null
cp -a "$ROOT/data/participation/bootstrap/characterization/src/test" "$DEST/src/"
cp -a "$ROOT/data/$TGT/bootstrap/spec/src/test/." "$DEST/src/test/"
echo "prepared $TGT: $(find "$DEST/src/test" -name '*.java' | wc -l) test sources into $DEST"
