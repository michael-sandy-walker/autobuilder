#!/bin/bash
# early_stop.sh -- stop a live run early when its own yardstick has stopped improving.
#
# Usage: scripts/early_stop.sh <target> [interval_min=10] [patience_min=20] [min_run_min=25] [port=8081]
#
# The rule, stated before the run rather than chosen while watching it: every <interval> minutes a
# COPY of the newest end-of-cycle checkpoint (data/<target>/checkpoint-archive) is scored with the target's declared yardstick (bootstrap/yardstick).
# Once the run is at least <min_run> minutes old, if the best score has not improved for <patience>
# minutes, the loop is paused (POST /autobuilder/pause) and preflight records terminal=operator-stop,
# then scores and archives the run as usual. A closure refusal with new content restarts the patience clock.
#
# A copy, because the yardstick runs the project's build: in the live tree its test reports would
# be read by the loop's own test gate as fresh evidence. The script never writes to the live tree.
# Exits when the app is gone, so it can be started beside every run and forgotten.
set -u
TGT="${1:?usage: early_stop.sh <target> [interval_min] [patience_min] [min_run_min] [port]}"
INTERVAL=$(( ${2:-10} * 60 )); PATIENCE=$(( ${3:-20} * 60 )); MINRUN=$(( ${4:-25} * 60 )); PORT="${5:-8081}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TREE="$ROOT/data/$TGT/output"
YARD="$(grep -v '^#' "$ROOT/data/$TGT/bootstrap/yardstick" 2>/dev/null | awk 'NF{print $1; exit}')"
[ -n "$YARD" ] || { echo "early_stop: $TGT declares no yardstick" >&2; exit 1; }
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
alive() { curl -s -o /dev/null -m 5 "http://localhost:$PORT/autobuilder/status"; }

start=$SECONDS; best=-1; best_at=$SECONDS
while alive; do
    sleep "$INTERVAL"
    alive || break
    # The newest end-of-cycle checkpoint, not the live tree: during evaluation the probe leaves an
    # unmerged candidate on disk, and scoring it let a staged file count as progress (run
    # 2026-09-25_03-03-00 read 6/11 live with nothing merged). Falls back to output/ without one.
    SRC="$TREE"; CK=$(ls -d "$ROOT/data/$TGT/checkpoint-archive"/cycle-* 2>/dev/null | sort | tail -1)
    [ -n "$CK" ] && [ -d "$CK/src" ] && SRC="$CK"
    rm -rf "$WORK/output"; cp -a "$SRC" "$WORK/output"; rm -rf "$WORK/output/target" "$WORK/output/__cache" "$WORK/output/__context.json"
    mkdir -p "$WORK/output/target"; : > "$WORK/output/target/probe.jar"
    line=$("$ROOT/$YARD" "$WORK/output/target/probe.jar" 2>/dev/null | grep -E '^SMOKE: [0-9]+/[0-9]+' | tail -1)
    score=$(echo "$line" | sed -E 's/^SMOKE: ([0-9]+)\/.*/\1/'); score=${score:-0}
    age=$(( (SECONDS - start) / 60 ))
    if [ "$score" -gt "$best" ]; then best=$score; best_at=$SECONDS; fi
    # A closure refusal hands the loop new, precise work that no yardstick score can show yet, so a
    # refusal saying something NEW restarts the patience clock (run 2026-09-25_05-14-55 was stopped 10
    # minutes after one). The same refusal repeated is no new work: run 2026-09-25_06-26-20 refused the
    # same three items every cycle, and restarting on each would let a run live on repetition alone.
    last_refusal=$(grep -E 'root closure (refused|blocked)' "$ROOT/data/$TGT/logs/autobuilder.log" 2>/dev/null \
        | tail -1 | sed -E 's/^.* - //' | md5sum | cut -c1-12)
    if [ -n "${last_refusal:-}" ] && [ "$last_refusal" != "d41d8cd98f00" ] && [ "$last_refusal" != "${seen_refusal:-}" ]; then
        [ -n "${seen_refusal:-}" ] && { best_at=$SECONDS; echo "early_stop: a new closure refusal since the last check -- patience restarted"; }
        seen_refusal=$last_refusal
    fi
    stale=$(( (SECONDS - best_at) / 60 ))
    echo "early_stop: ${age}m ${line:-SMOKE: ?} best=$best unchanged ${stale}m"
    if [ $((SECONDS - start)) -ge "$MINRUN" ] && [ $((SECONDS - best_at)) -ge "$PATIENCE" ]; then
        echo "early_stop: STOP -- best $best unchanged for ${stale}m at ${age}m; pausing the loop"
        curl -s -X POST "http://localhost:$PORT/autobuilder/pause"; echo
        exit 0
    fi
done
echo "early_stop: app gone -- nothing to do"
