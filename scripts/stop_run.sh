#!/bin/bash
# Stops a live field run on purpose and records that it was stopped, so preflight ends it as
# terminal=jvm-stopped rather than jvm-died (the user, 2026-10-17: "call it what it is").
# usage: scripts/stop_run.sh "<reason>" [run_dir]   -- run_dir defaults to the newest one whose JVM is alive.
REASON="${1:?usage: stop_run.sh \"<reason>\" [run_dir]}"
RUN_DIR="${2:-}"
if [ -z "$RUN_DIR" ]; then
    for d in $(ls -td /tmp/preflight_runs/*/ 2>/dev/null); do
        p=$(cat "$d/jvm.pid" 2>/dev/null) || continue
        if [ -n "$p" ] && kill -0 "$p" 2>/dev/null; then RUN_DIR="${d%/}"; break; fi
    done
fi
[ -n "$RUN_DIR" ] && [ -f "$RUN_DIR/jvm.pid" ] || { echo "stop_run: no live run found"; exit 1; }
PID=$(cat "$RUN_DIR/jvm.pid")
kill -0 "$PID" 2>/dev/null || { echo "stop_run: the JVM of $RUN_DIR (pid $PID) is not running"; exit 1; }
printf 'stopped %s\nreason: %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$REASON" > "$RUN_DIR/STOPPED.txt"
kill "$PID" && echo "stop_run: stopped $RUN_DIR (pid $PID): $REASON"
