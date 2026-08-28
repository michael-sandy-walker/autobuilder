#!/bin/bash
# run_series.sh — T4-CH: the deterministic outer relaunch policy, mechanized.
#
# The framework already self-resumes at boot (T4-CD: resume-if-possible-else-fresh) and
# classifies its own terminals + watch results (T5-BE, T4-CE slice 1). What still needed a
# human was the relaunch seat: watch the terminal, restart the JVM, repeat until the lineage
# fulfills. This driver is that policy — nothing more. All continuation intelligence lives in
# the boot path and the in-run brakes; the driver never re-implements any of it.
#
# Usage:  scripts/run_series.sh [minutes-per-run] [max-runs]
#           minutes-per-run  cap handed to preflight live (default 45)
#           max-runs         the operator's session budget — the only numeric policy input
#                            (default 4, the size of the first hand-driven run series)
#
# Stop conditions (all deterministic):
#   FULFILLED   a run ends GOAL_FULFILLED — the lineage is complete
#   ENVIRONMENT terminal says the environment died (model-unavailable / jvm-died / silent /
#               ABANDONED) — relaunching into a dead environment is noise
#   BUDGET      max-runs spent
#
# Machine-readable result: the LAST line is always
#   SERIES: FULFILLED|ENVIRONMENT|BUDGET|LAUNCH-FAILED runs=<n> ledger=<path>
#
# Ledger: one row per run under /tmp/preflight_runs/series-<stamp>.ledger — run number,
# run_dir, terminal, smoke, merges, whether a preserve/end-report exists (i.e. whether the next
# boot will resume).

set -u
cd "$(dirname "$0")/.." || exit 1
REPO="$PWD"
MINUTES="${1:-45}"
MAX_RUNS="${2:-4}"

STAMP=$(date +%Y-%m-%d_%H-%M-%S)
LEDGER="/tmp/preflight_runs/series-$STAMP.ledger"
mkdir -p /tmp/preflight_runs

say() { echo "[series] $*"; }

autobuilder_jvms() {
    for p in /proc/[0-9]*/cmdline; do
        pid="${p#/proc/}"; pid="${pid%/cmdline}"
        if tr '\0' ' ' < "$p" 2>/dev/null | grep -q 'autobuilder-.*-SNAPSHOT\.jar'; then
            echo "$pid"
        fi
    done
}

finish() { # result runs
    echo "# series result: $1 after $2 run(s)" >> "$LEDGER" 2>/dev/null || true
    echo "SERIES: $1 runs=$2 ledger=$LEDGER"
    exit 0
}

echo "# lineage run series $STAMP — minutes/run=$MINUTES budget=$MAX_RUNS" > "$LEDGER"

run_no=0
while [ "$run_no" -lt "$MAX_RUNS" ]; do
    run_no=$((run_no + 1))

    # The relaunch seat's one safety duty: never launch over a live JVM (paused runs hold 8081).
    alive=$(autobuilder_jvms)
    if [ -n "$alive" ]; then
        say "run $run_no: killing leftover autobuilder JVM(s): $alive"
        for p in $alive; do kill "$p" 2>/dev/null; done
        sleep 5
        for p in $(autobuilder_jvms); do kill -9 "$p" 2>/dev/null; done
        sleep 2
    fi

    # Resume flag always on — the BOOT decides fresh-vs-resume (T4-CD semantics); the driver
    # deliberately carries no continuation policy of its own.
    launchlog="/tmp/preflight_runs/series-$STAMP-run$run_no.log"
    say "run $run_no/$MAX_RUNS: launching preflight live $MINUTES (log: $launchlog)"
    PREFLIGHT_EXTRA_FLAGS="--autobuilder.service.resume-from-preserved=true" \
        setsid nohup scripts/preflight.sh live "$MINUTES" > "$launchlog" 2>&1 < /dev/null &
    launcher=$!

    # Wait on the result line, not the process (kill-safe: the launcher dying without a
    # result is itself a terminal condition).
    while kill -0 "$launcher" 2>/dev/null && ! grep -q "^PREFLIGHT:" "$launchlog" 2>/dev/null; do
        sleep 30
    done
    sleep 2
    result_line=$(grep "^PREFLIGHT:" "$launchlog" | tail -1)
    if [ -z "$result_line" ]; then
        echo "run=$run_no run_dir=? terminal=launcher-died smoke=? merges=?" >> "$LEDGER"
        say "run $run_no: preflight produced no result (launcher died) — stopping"
        finish "ENVIRONMENT" "$run_no"
    fi

    run_dir=$(echo "$result_line" | grep -oE '\brun=[^ )]*' | cut -d= -f2)
    terminal=$(echo "$result_line" | grep -o 'terminal=[^ )]*' | cut -d= -f2)
    if [ -z "$run_dir" ] || [ ! -d "$run_dir" ]; then
        # A FAIL result without a run_dir (jar-build, model probe, jvm-died-at-startup) —
        # relaunching cannot help; surface and stop.
        echo "run=$run_no run_dir=none result='$result_line'" >> "$LEDGER"
        say "run $run_no: no run_dir in result — stopping ($result_line)"
        finish "LAUNCH-FAILED" "$run_no"
    fi
    smoke=$(grep '^smoke=' "$run_dir/summary.txt" 2>/dev/null | cut -d= -f2-)
    merges=$(grep '^merges=' "$run_dir/summary.txt" 2>/dev/null | cut -d= -f2)
    # T4-CK: target-aware (PREFLIGHT_TARGET is inherited by the preflight launches too).
    preserve="no"; [ -d "$REPO/data/${PREFLIGHT_TARGET:-firestarter}/meta/preserved-artifact" ] && preserve="yes"
    end_report="no"; [ -f "$REPO/data/${PREFLIGHT_TARGET:-firestarter}/meta/run-end-report.json" ] && end_report="yes"
    echo "run=$run_no run_dir=$run_dir terminal=$terminal smoke=$smoke merges=$merges preserve=$preserve end_report=$end_report" >> "$LEDGER"
    say "run $run_no: terminal=$terminal smoke=$smoke merges=$merges preserve=$preserve"

    case "$terminal" in
        GOAL_FULFILLED)
            finish "FULFILLED" "$run_no" ;;
        model-unavailable|jvm-died*|silent-*)
            finish "ENVIRONMENT" "$run_no" ;;
    esac
    if grep -q "ABANDONED" "$run_dir/ABANDONED.txt" 2>/dev/null; then
        finish "ENVIRONMENT" "$run_no"
    fi
    # Anything else (honest halt with preserve, deadline, ...) → next iteration; the boot
    # resumes when resumable state exists, else runs fresh. Either way the lineage advances.
done

finish "BUDGET" "$run_no"
