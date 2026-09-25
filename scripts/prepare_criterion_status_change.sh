#!/bin/bash
# prepare_criterion_status_change.sh -- the pristine tree of the criterion-status CHANGE target: the
# module as extracted at its origin-rev (with its own regression tests) + the target's specification
# tests (bootstrap/spec), which fail until the code provides them.
#
# Usage: scripts/prepare_criterion_status_change.sh <target> <dest-dir>
set -euo pipefail
TGT="${1:?usage: prepare_criterion_status_change.sh <target> <dest-dir>}"
DEST="${2:?usage: prepare_criterion_status_change.sh <target> <dest-dir>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
"$ROOT/scripts/extract_criterion_status.sh" "$(cat "$ROOT/data/$TGT/bootstrap/origin-rev")" "$DEST" > /dev/null
cp -a "$ROOT/data/$TGT/bootstrap/spec/src/test/." "$DEST/src/test/"
echo "prepared $TGT: $(find "$DEST/src/test" -name '*.java' | wc -l) test sources into $DEST"
