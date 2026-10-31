#!/bin/bash
# early_stop.sh -- stop a live run early when its own yardstick has stopped improving.
#
# Usage: scripts/early_stop.sh <target> [interval_min=10] [patience_min=20] [min_run_min=25] [port=8081]
#
# The rule, stated before the run rather than chosen while watching it: every <interval> minutes a
# COPY of the newest end-of-cycle checkpoint (data/<target>/checkpoint-archive) is scored with the target's declared yardstick (bootstrap/yardstick) -- a terminating one handed the copy, a serving one asked through the copy built and booted on a port of its own.
# Once the run is at least <min_run> minutes old, if the best score has not improved for <patience>
# minutes, the loop is paused (POST /autobuilder/pause) and preflight records terminal=operator-stop,
# then scores and archives the run as usual. A closure refusal with new content restarts the patience clock.
#
# The patience is the run's own, not only the constant (user, 2026-09-28: "let's make that timespan
# more dynamic"): <patience> is the floor, and a run that has improved in slow steps waits 1.5x its
# longest gap between improvements so far. And the loop acting on its own stall -- a rewind -- restarts the
# clock, so the move gets a window of its own (on 2026-09-28 a flat run was cut minutes after the loop
# reacted). The run's budget stays the hard end.
#
# A copy, because the yardstick runs the project's build: in the live tree its test reports would
# be read by the loop's own test gate as fresh evidence. The script never writes to the live tree.
# Exits when the run it was started beside is gone, so it can be started beside every run and forgotten.
set -u
TGT="${1:?usage: early_stop.sh <target> [interval_min] [patience_min] [min_run_min] [port]}"
INTERVAL=$(( ${2:-10} * 60 )); PATIENCE=$(( ${3:-20} * 60 )); MINRUN=$(( ${4:-25} * 60 )); PORT="${5:-8081}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TREE="$ROOT/data/$TGT/output"
YARD="$(grep -v '^#' "$ROOT/data/$TGT/bootstrap/yardstick" 2>/dev/null | awk 'NF{print $1; exit}')"
MODE="$(grep -v '^#' "$ROOT/data/$TGT/bootstrap/yardstick" 2>/dev/null | awk 'NF{print $2; exit}')"; MODE="${MODE:-serving}"
[ -n "$YARD" ] || { echo "early_stop: $TGT declares no yardstick" >&2; exit 1; }
# A yardstick this script cannot run must stop it at once, never read as zero: handing a serving
# yardstick a jar path scored every check 0/23, and a flat zero paused firestarter run
# 2026-09-25_21-44-42 at 30 minutes although its tree was at 23/23.
case "$MODE" in terminating|serving) ;; *) echo "early_stop: $TGT's yardstick mode '$MODE' is not one this script can run" >&2; exit 1;; esac
APORT=$(( 18000 + RANDOM % 1000 ))
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
# Alive means THIS run: the port answers AND the run's own log still begins with the line it began
# with when this watcher started. Polling the port alone let a watcher outlive its run -- three
# criterion-status runs back to back on 2026-09-26 left the first two watchers adopting the third,
# each able to pause a run whose clock it had not started. A new launch rotates the log away.
LOG="$ROOT/data/$TGT/logs/autobuilder.log"
RUNMARK=""
alive() {
    curl -s -o /dev/null -m 5 "http://localhost:$PORT/autobuilder/status" || return 1
    local head; head=$(head -1 "$LOG" 2>/dev/null)
    [ -n "$RUNMARK" ] || RUNMARK="$head"
    [ "$head" = "$RUNMARK" ]
}

start=$SECONDS; best=-1; best_at=$SECONDS; longest_gap=0
while alive; do
    sleep "$INTERVAL"
    alive || break
    # The newest end-of-cycle checkpoint, not the live tree: during evaluation the probe leaves an
    # unmerged candidate on disk, and scoring it let a staged file count as progress (run
    # 2026-09-25_03-03-00 read 6/11 live with nothing merged). Falls back to output/ without one.
    SRC="$TREE"; CK=$(ls -d "$ROOT/data/$TGT/checkpoint-archive"/cycle-* 2>/dev/null | sort | tail -1)
    [ -n "$CK" ] && [ -d "$CK/src" ] && SRC="$CK"
    rm -rf "$WORK/output"; cp -a "$SRC" "$WORK/output"; rm -rf "$WORK/output/target" "$WORK/output/__cache" "$WORK/output/__context.json"
    if [ "$MODE" = terminating ]; then
        mkdir -p "$WORK/output/target"; : > "$WORK/output/target/probe.jar"
        line=$("$ROOT/$YARD" "$WORK/output/target/probe.jar" 2>/dev/null | grep -E '^SMOKE: [0-9]+/[0-9]+' | tail -1)
    else
        # Serving: the yardstick asks a RUNNING artifact, as preflight's final score does -- build the
        # copy, boot it on a port of its own, score its URL, stop it. A copy that does not build or
        # boot scores what it serves: nothing.
        line=""
        if (cd "$WORK/output" && timeout 300 mvn -B -q -o package -DskipTests > "$WORK/build.log" 2>&1); then
            AJAR=$(ls "$WORK"/output/target/*.jar 2>/dev/null | grep -v original | head -1)
            [ -f "$WORK/output/.run-module" ] && AJAR=$(ls "$WORK/output/$(cat "$WORK/output/.run-module")"/target/*.jar 2>/dev/null | grep -v original | head -1)
            [ -n "$AJAR" ] || AJAR=$(ls "$WORK"/output/*/target/*.jar 2>/dev/null | grep -v original | head -1)   # a multi-module build (T4-EC)
            if [ -n "$AJAR" ]; then
                java -jar "$AJAR" --server.port="$APORT" > "$WORK/boot.log" 2>&1 &
                APID=$!
                for _ in $(seq 1 30); do grep -q 'Started' "$WORK/boot.log" 2>/dev/null && break; sleep 2; done
                grep -q 'Started' "$WORK/boot.log" 2>/dev/null \
                    && line=$("$ROOT/$YARD" "http://localhost:$APORT" 2>/dev/null | grep -E '^SMOKE: [0-9]+/[0-9]+' | tail -1)
                kill "$APID" 2>/dev/null; wait "$APID" 2>/dev/null
            fi
        fi
        [ -n "$line" ] || line="SMOKE: 0/? (did not build or boot)"
    fi
    score=$(echo "$line" | sed -E 's/^SMOKE: ([0-9]+)\/.*/\1/'); score=${score:-0}
    age=$(( (SECONDS - start) / 60 ))
    if [ "$score" -gt "$best" ]; then
        [ "$best" -ge 0 ] && [ $((SECONDS - best_at)) -gt "$longest_gap" ] && longest_gap=$((SECONDS - best_at))
        best=$score; best_at=$SECONDS
    fi
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
    # The loop's own reaction to a stall -- a rewind -- is new work no score shows yet: the clock
    # restarts once per distinct reaction, never on a repeat.
    last_reaction=$(grep -E "T4-DQ: REWOUND to " "$LOG" 2>/dev/null | tail -1 | md5sum | cut -c1-12)
    # At most one such restart per patience window: a stream of moves kept a flat run alive to its
    # budget (run 2026-09-29_16-21-43, 8 moves in 40 minutes). Relative, like the patience itself.
    if [ "$last_reaction" != "d41d8cd98f00" ] && [ "$last_reaction" != "${seen_reaction:-d41d8cd98f00}" ]; then
        seen_reaction=$last_reaction
        if [ $((SECONDS - ${reaction_restart_at:--999999})) -ge "${patience_now:-$PATIENCE}" ]; then
            best_at=$SECONDS; reaction_restart_at=$SECONDS
            echo "early_stop: the loop acted on its stall (a rewind) -- patience restarted"
        else
            echo "early_stop: another move within one patience window of the last restart -- not restarting"
        fi
    fi
    # The run's own pace: at least the stated patience, and 1.5x the longest wait between improvements so far.
    patience_now=$PATIENCE
    [ $(( longest_gap * 3 / 2 )) -gt "$patience_now" ] && patience_now=$(( longest_gap * 3 / 2 ))
    stale=$(( (SECONDS - best_at) / 60 ))
    echo "early_stop: ${age}m ${line:-SMOKE: ?} best=$best unchanged ${stale}m patience $((patience_now / 60))m"
    # At the maximum the score cannot improve, and the only progress left is the loop closing: a
    # flat maximum is not a stall, and stopping it would throw away the closure (firestarter run
    # 2026-09-25_21-44-42's tree was at 23/23 when a flat reading cut it). The budget ends such a run.
    max=$(echo "$line" | sed -nE 's/^SMOKE: [0-9]+\/([0-9]+).*/\1/p')
    if [ -n "$max" ] && [ "$best" -ge "$max" ]; then
        [ -z "${said_max:-}" ] && { echo "early_stop: at the maximum ($best/$max) -- not stopping; the budget ends this run"; said_max=1; }
        continue
    fi
    if [ $((SECONDS - start)) -ge "$MINRUN" ] && [ $((SECONDS - best_at)) -ge "$patience_now" ]; then
        echo "early_stop: STOP -- best $best unchanged for ${stale}m at ${age}m; pausing the loop"
        curl -s -X POST "http://localhost:$PORT/autobuilder/pause"; echo
        exit 0
    fi
done
echo "early_stop: the run it was started beside is gone -- nothing to do"
