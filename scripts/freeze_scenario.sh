#!/bin/bash
# freeze_scenario.sh — turn a real run's tree into a replayable test scenario.
#
# Usage:
#   scripts/freeze_scenario.sh <source-tree> <scenario-name> ["what went wrong"]
#
#   <source-tree>   a directory holding the sources to freeze. Any of:
#                     /tmp/preflight_runs/<stamp>/artifact          the run's final tree
#                     data/<target>/checkpoint-archive/<cycleId>    ONE CYCLE, kept across resets
#                     data/<target>/snapshots/<cycleId>             this run's per-cycle copy
#   <scenario-name> directory name under src/test/resources/scenarios/
#
# Why the archive is the interesting source: it is the only per-cycle tree that survives a reset,
# so "the run that went wrong at cycle X" is still freezable after the next run has started.
#
# The scenario is source + provenance, never a binary: a test asserts on what the deterministic
# machinery makes of the tree, so a fix can be re-checked against the exact tree that defeated it.
set -u
cd "$(dirname "$0")/.." || exit 1

SRC="${1:-}"; NAME="${2:-}"; NOTE="${3:-}"
if [ -z "$SRC" ] || [ -z "$NAME" ]; then
    echo "usage: $0 <source-tree> <scenario-name> [\"what went wrong\"]" >&2; exit 2
fi
[ -d "$SRC" ] || { echo "no such directory: $SRC" >&2; exit 2; }

DEST="src/test/resources/scenarios/$NAME"
if [ -e "$DEST" ]; then
    echo "scenario already exists: $DEST (delete it first to re-freeze)" >&2; exit 2
fi

# Sources only. Build output regenerates and would make the scenario large and stale-prone.
mkdir -p "$DEST/tree"
count=0
while IFS= read -r f; do
    rel="${f#"$SRC"/}"
    mkdir -p "$DEST/tree/$(dirname "$rel")"
    cp "$f" "$DEST/tree/$rel"
    count=$((count + 1))
done < <(find "$SRC" -type f \( -name '*.java' -o -name 'pom.xml' -o -name '*.properties' -o -name '*.yml' \) \
         -not -path '*/target/*' | sort)

[ "$count" -gt 0 ] || { echo "no source files found under $SRC" >&2; rm -rf "$DEST"; exit 2; }

{
    echo "# Frozen by scripts/freeze_scenario.sh — provenance for the tree in tree/."
    echo "name=$NAME"
    echo "source=$SRC"
    echo "frozen=$(date -Iseconds)"
    echo "files=$count"
    [ -n "$NOTE" ] && echo "note=$NOTE"
    # Carry the run identity when the source is a preflight run_dir, so a scenario can be traced
    # back to the trace and logs that explain it.
    [ -f "$SRC/../target.txt" ] && cat "$SRC/../target.txt"
    [ -f "$SRC/../console.log" ] && echo "run_dir=$(cd "$SRC/.." && pwd)"
} > "$DEST/manifest.properties"

echo "froze $count file(s) into $DEST"
sed 's/^/  /' "$DEST/manifest.properties"
