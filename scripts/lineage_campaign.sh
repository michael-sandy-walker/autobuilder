#!/bin/bash
# lineage_campaign.sh — T4-CH: the deterministic outer relaunch policy, mechanized.
#
# The framework already self-resumes at boot (T4-CD: resume-if-possible-else-fresh) and
# classifies its own terminals + watch verdicts (T5-BE, T4-CE slice 1). What still needed a
# human was the relaunch seat: watch the terminal, restart the JVM, repeat until the lineage
# fulfills. This driver is that policy — nothing more. All continuation intelligence lives in
# the boot path and the in-run brakes; the driver never re-implements any of it.
#
# Usage:  scripts/lineage_campaign.sh [minutes-per-run] [max-runs]
#           minutes-per-run  cap handed to preflight live (default 45)
#           max-runs         the operator's session budget — the only numeric policy input
#                            (default 4, the size of the first hand-driven campaign)
#
# Stop conditions (all deterministic):
#   FULFILLED   a run ends GOAL_FULFILLED — the lineage is complete
#   ENVIRONMENT terminal says the environment died (model-unavailable / jvm-died / silent /
#               ABANDONED) — relaunching into a dead environment is noise
#   BUDGET      max-runs spent
#
# Machine-readable verdict: the LAST line is always
#   CAMPAIGN: FULFILLED|ENVIRONMENT|BUDGET|LAUNCH-FAILED runs=<n> ledger=<path>
#
# Ledger: one row per run under /tmp/preflight_runs/campaign-<stamp>.ledger — run number,
# corpus, terminal, smoke, merges, whether a preserve/testament exists (i.e. whether the next
# boot will resume).

set -u
cd "$(dirname "$0")/.." || exit 1
REPO="$PWD"
MINUTES="${1:-45}"
MAX_RUNS="${2:-4}"

STAMP=$(date +%Y-%m-%d_%H-%M-%S)
LEDGER="/tmp/preflight_runs/campaign-$STAMP.ledger"
mkdir -p /tmp/preflight_runs

say() { echo "[campaign] $*"; }

autobuilder_jvms() {
    for p in /proc/[0-9]*/cmdline; do
        pid="${p#/proc/}"; pid="${pid%/cmdline}"
        if tr '\0' ' ' < "$p" 2>/dev/null | grep -q 'autobuilder-.*-SNAPSHOT\.jar'; then
            echo "$pid"
        fi
    done
}

finish() { # verdict runs
    echo "# campaign verdict: $1 after $2 run(s)" >> "$LEDGER" 2>/dev/null || true
    echo "CAMPAIGN: $1 runs=$2 ledger=$LEDGER"
    exit 0
}

echo "# lineage campaign $STAMP — minutes/run=$MINUTES budget=$MAX_RUNS" > "$LEDGER"

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
    launchlog="/tmp/preflight_runs/campaign-$STAMP-run$run_no.log"
    say "run $run_no/$MAX_RUNS: launching preflight live $MINUTES (log: $launchlog)"
    PREFLIGHT_EXTRA_FLAGS="--autobuilder.service.resume-from-preserved=true" \
        setsid nohup scripts/preflight.sh live "$MINUTES" > "$launchlog" 2>&1 < /dev/null &
    launcher=$!

    # Wait on the verdict line, not the process (kill-safe: the launcher dying without a
    # verdict is itself a terminal condition).
    while kill -0 "$launcher" 2>/dev/null && ! grep -q "^PREFLIGHT:" "$launchlog" 2>/dev/null; do
        sleep 30
    done
    sleep 2
    verdict_line=$(grep "^PREFLIGHT:" "$launchlog" | tail -1)
    if [ -z "$verdict_line" ]; then
        echo "run=$run_no corpus=? terminal=launcher-died smoke=? merges=?" >> "$LEDGER"
        say "run $run_no: preflight produced no verdict (launcher died) — stopping"
        finish "ENVIRONMENT" "$run_no"
    fi

    corpus=$(echo "$verdict_line" | grep -o 'corpus=[^ )]*' | cut -d= -f2)
    terminal=$(echo "$verdict_line" | grep -o 'terminal=[^ )]*' | cut -d= -f2)
    if [ -z "$corpus" ] || [ ! -d "$corpus" ]; then
        # A FAIL verdict without a corpus (jar-build, model probe, jvm-died-at-startup) —
        # relaunching cannot help; surface and stop.
        echo "run=$run_no corpus=none verdict='$verdict_line'" >> "$LEDGER"
        say "run $run_no: no corpus in verdict — stopping ($verdict_line)"
        finish "LAUNCH-FAILED" "$run_no"
    fi
    smoke=$(grep '^smoke=' "$corpus/summary.txt" 2>/dev/null | cut -d= -f2-)
    merges=$(grep '^merges=' "$corpus/summary.txt" 2>/dev/null | cut -d= -f2)
    # T4-CK: target-aware (PREFLIGHT_TARGET is inherited by the preflight launches too).
    preserve="no"; [ -d "$REPO/${PREFLIGHT_TARGET:-firestarter}/meta/preserved-artifact" ] && preserve="yes"
    testament="no"; [ -f "$REPO/${PREFLIGHT_TARGET:-firestarter}/meta/run-testament.json" ] && testament="yes"
    echo "run=$run_no corpus=$corpus terminal=$terminal smoke=$smoke merges=$merges preserve=$preserve testament=$testament" >> "$LEDGER"
    say "run $run_no: terminal=$terminal smoke=$smoke merges=$merges preserve=$preserve"

    case "$terminal" in
        GOAL_FULFILLED)
            finish "FULFILLED" "$run_no" ;;
        model-unavailable|jvm-died*|silent-*)
            finish "ENVIRONMENT" "$run_no" ;;
    esac
    if grep -q "ABANDONED" "$corpus/ABANDONED.txt" 2>/dev/null; then
        finish "ENVIRONMENT" "$run_no"
    fi
    # Anything else (honest halt with preserve, deadline, ...) → next iteration; the boot
    # resumes when resumable state exists, else runs fresh. Either way the lineage advances.
done

finish "BUDGET" "$run_no"
