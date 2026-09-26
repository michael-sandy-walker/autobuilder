#!/bin/bash
# prepare_module_change.sh -- the pristine tree of a CHANGE target cut from Autobuilder itself, described by
# data: data/<target>/bootstrap/module/files.txt (paths read from git at bootstrap/origin-rev), module/pom.xml,
# module/README.md, and the target's specification tests (bootstrap/spec), which fail until the code provides
# them. The generic form of prepare_criterion_status_change.sh: a new toy case is a directory, not a script.
#
# Usage: scripts/prepare_module_change.sh <target> <dest-dir>
set -euo pipefail
TGT="${1:?usage: prepare_module_change.sh <target> <dest-dir>}"
DEST="${2:?usage: prepare_module_change.sh <target> <dest-dir>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
B="$ROOT/data/$TGT/bootstrap"
REV="$(cat "$B/origin-rev")"
[ -e "$DEST" ] && [ -n "$(ls -A "$DEST" 2>/dev/null)" ] && { echo "refusing: $DEST is not empty" >&2; exit 1; }
mkdir -p "$DEST"
grep -vE '^\s*(#|$)' "$B/module/files.txt" | while read -r f; do
    mkdir -p "$DEST/$(dirname "$f")"
    git -C "$ROOT" show "$REV:$f" > "$DEST/$f"
done
cp "$B/module/pom.xml" "$B/module/README.md" "$DEST/"
cp -a "$B/spec/src/test/." "$DEST/src/test/"
echo "prepared $TGT: $(find "$DEST/src/test" -name '*.java' | wc -l) test sources into $DEST"
