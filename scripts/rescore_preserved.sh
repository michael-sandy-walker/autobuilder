#!/usr/bin/env bash
# T6-K: backfill per-check smoke sheets for runs scored before preflight preserved them.
#
# preflight now writes `<tag>-smoke.txt` alongside the total, but every earlier run recorded only
# `SMOKE: n/23`, and the standing rule is to compare failing check SETS rather than totals. The
# preserved artifact trees still exist for those runs, and booting one is deterministic: the tree
# is the input, so this is a MEASUREMENT, not a reconstruction. Runs whose tree is gone are skipped
# and stay silent rather than being guessed at.
#
# Refuses to run beside a live autobuilder. Each artifact costs a `mvn package` plus a JVM boot,
# and a full build beside a live run has already taken this box to load 159 with no free memory and
# killed the run through Neo4j timeouts.
#
# Usage: rescore_preserved.sh [run-dir-glob]        default /tmp/preflight_runs/*
set -uo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SMOKE="${PREFLIGHT_SMOKE:-$REPO/scripts/smoke23.sh}"
PATTERN="${1:-/tmp/preflight_runs/*}"
PORT="${RESCORE_PORT:-8097}"

for p in $(pgrep -x java 2>/dev/null); do
    if tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null | grep -q "autobuilder-1.0.1-SNAPSHOT.jar"; then
        echo "refusing: an autobuilder run is live (pid $p) — re-scoring builds and boots artifacts"
        exit 2
    fi
done

scored=0; skipped=0; failed=0
for dir in $PATTERN; do
    [ -d "$dir" ] || continue
    name=$(basename "$dir")
    # Already has a sheet (either preserved by preflight or by an earlier pass of this script).
    if [ -s "$dir/preserved-smoke.txt" ] || [ -s "$dir/artifact-smoke.txt" ]; then
        continue
    fi
    tree=""
    for t in preserved-artifact artifact preserved-reconstructed; do
        [ -f "$dir/$t/pom.xml" ] && { tree="$dir/$t"; break; }
    done
    [ -n "$tree" ] || { skipped=$((skipped+1)); continue; }

    ( cd "$tree" && mvn -q clean package -DskipTests > "$dir/rescore-build.log" 2>&1 )
    jar=$(ls "$tree"/target/*.jar 2>/dev/null | head -1)
    if [ -z "$jar" ]; then
        echo "$name: artifact-build-failed (unchanged from its recorded score)"
        failed=$((failed+1)); continue
    fi

    java -jar "$jar" --server.port="$PORT" > "$dir/rescore-boot.log" 2>&1 &
    pid=$!
    sleep 14
    if grep -q 'Started' "$dir/rescore-boot.log"; then
        "$SMOKE" "http://localhost:$PORT" > "$dir/preserved-smoke.txt" 2>/dev/null
        echo "$name: $(tail -1 "$dir/preserved-smoke.txt")  failed=[$(sed -n 's/^FAIL \[\([0-9]*\)\].*/\1/p' "$dir/preserved-smoke.txt" | paste -sd, -)]"
        scored=$((scored+1))
    else
        echo "$name: boot-failed"
        rm -f "$dir/preserved-smoke.txt"
        failed=$((failed+1))
    fi
    kill "$pid" 2>/dev/null
    wait "$pid" 2>/dev/null
done

echo
echo "re-scored $scored, no-tree $skipped, unbuildable/unbootable $failed"
