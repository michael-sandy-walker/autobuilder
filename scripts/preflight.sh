#!/bin/bash
# preflight.sh — one-command validation for autobuilder changes, safe for autonomous driving.
#
# Usage:  scripts/preflight.sh quick          # stage scenarios + targeted tests (~1 min, no model)
#         scripts/preflight.sh full           # full suite + opt-in layer-2 evals (needs local model)
#         scripts/preflight.sh live [minutes] # capped live dev-auto run (default 45), watcher,
#                                             # terminal classification, smoke, corpus preservation
#
# Machine-readable verdict: the LAST line is always
#         PREFLIGHT: PASS|FAIL mode=<mode> [failures: ...]
# Everything the driver needs is in that line + the preserved corpus dir it names.
#
# HARD SAFETY GATES (violations of these burned real runs — do not remove):
#   G1  No repo mvn while ANY autobuilder JVM is alive (rotates live logs, can reset the live
#       GoalStack). quick/full refuse to run; live kills only what it started.
#   G2  Live mode probes the local model BEFORE launching (an orphaned generation in the model
#       queue kills the next JVM at context refresh).
#   G3  Live mode resolves the run's OWN timestamped log files after launch (the unsuffixed
#       logs/behaviour.ndjson can be rotated away mid-run; a glob EXCLUDES the current run).

set -u
cd "$(dirname "$0")/.." || exit 1
REPO="$PWD"
MODE="${1:-quick}"
FAILURES=()

say() { echo "[preflight] $*"; }
fail_out() {
    echo "PREFLIGHT: FAIL mode=$MODE failures: ${FAILURES[*]}"
    exit 1
}
pass_out() {
    echo "PREFLIGHT: PASS mode=$MODE $*"
    exit 0
}

autobuilder_jvms() {
    # /proc cmdline scan — pgrep self-matches (runbook trap).
    for p in /proc/[0-9]*/cmdline; do
        pid="${p#/proc/}"; pid="${pid%/cmdline}"
        if tr '\0' ' ' < "$p" 2>/dev/null | grep -q 'autobuilder-.*-SNAPSHOT\.jar'; then
            echo "$pid"
        fi
    done
}

guard_no_jvm() {
    local alive
    alive=$(autobuilder_jvms)
    if [ -n "$alive" ]; then
        say "G1 VIOLATION: autobuilder JVM(s) alive: $alive — mvn would rotate live logs / reset live state"
        FAILURES+=("jvm-alive:$alive")
        fail_out
    fi
}

probe_model() {
    local url model t0 t1
    url=$(grep -A4 '^llm:' src/main/resources/application.yml | grep 'url:' | grep -v '^\s*#' | awk '{print $2}')
    model=$(grep -A8 '^llm:' src/main/resources/application.yml | grep 'model:' | grep -v '^\s*#' | awk '{print $2}')
    t0=$(date +%s%N)
    local out
    out=$(curl -s --max-time 30 "$url" -H 'Content-Type: application/json' \
        -d "{\"model\":\"$model\",\"messages\":[{\"role\":\"user\",\"content\":\"Say OK\"}],\"max_tokens\":5}" \
        | python3 -c "import json,sys; print(json.load(sys.stdin)['choices'][0]['message']['content'])" 2>/dev/null)
    t1=$(date +%s%N)
    local ms=$(( (t1 - t0) / 1000000 ))
    if [ -z "$out" ]; then
        say "G2: model at $url did not answer"
        FAILURES+=("model-unreachable")
        return 1
    fi
    if [ "$ms" -gt 15000 ]; then
        say "G2: model answered but took ${ms}ms — likely an orphaned generation in the queue; do NOT launch"
        FAILURES+=("model-slow:${ms}ms")
        return 1
    fi
    say "model probe OK (${ms}ms)"
    return 0
}

run_quick() {
    guard_no_jvm
    say "stage scenarios + gate/closure/verification tests"
    FILE_BASE_PATH=firestarter mvn -q test \
        -Dtest='PreservedRunRegressionScenarioTest,RootConvergenceTrackerTest,GiveUpEdgeScenarioTest,GoalPlannerServiceClosureTest,CriteriaGroundingServiceTest,CriteriaVerificationServiceTest,AutobuilderServiceTest,WiringFactsAnalyzerTest' \
        > /tmp/preflight_quick.log 2>&1
    if [ $? -ne 0 ]; then
        grep -E '<<< (FAILURE|ERROR)|Tests run.*Failures: [1-9]' /tmp/preflight_quick.log | head -5
        FAILURES+=("quick-tests (log: /tmp/preflight_quick.log)")
        fail_out
    fi
    pass_out "(stage scenarios + targeted tests green)"
}

run_full() {
    guard_no_jvm
    say "full suite"
    FILE_BASE_PATH=firestarter mvn -q test > /tmp/preflight_full.log 2>&1
    if [ $? -ne 0 ]; then
        grep -E '<<< (FAILURE|ERROR)' /tmp/preflight_full.log | head -5
        FAILURES+=("full-suite (log: /tmp/preflight_full.log)")
        fail_out
    fi
    if probe_model; then
        say "layer-2 verifier eval (live model)"
        T5AF_VERIFIER_EVAL=1 FILE_BASE_PATH=firestarter mvn -q test \
            -Dtest='CompletionVerifierPrecisionEvalTest' > /tmp/preflight_eval.log 2>&1 \
            || { FAILURES+=("layer2-eval (log: /tmp/preflight_eval.log)"); fail_out; }
        grep -E 'precision|recall' /tmp/preflight_eval.log | head -4
    else
        say "model unreachable — layer-2 evals SKIPPED (suite itself green)"
        FAILURES=()
    fi
    pass_out "(full suite green)"
}

run_live() {
    local minutes="${2:-45}"
    local stamp corpus jar pid t_end
    stamp=$(date +%Y-%m-%d_%H-%M-%S)
    corpus="/tmp/preflight_runs/$stamp"
    mkdir -p "$corpus"

    guard_no_jvm
    probe_model || fail_out

    say "building jar"
    mvn -q package -DskipTests > "$corpus/build.log" 2>&1 \
        || { FAILURES+=("jar-build (log: $corpus/build.log)"); fail_out; }
    jar=$(ls target/autobuilder-*-SNAPSHOT.jar | head -1)

    say "launching capped live run (${minutes} min): proven flag family + criteria grounding${PREFLIGHT_EXTRA_FLAGS:+ + extra: $PREFLIGHT_EXTRA_FLAGS}"
    # PREFLIGHT_EXTRA_FLAGS: space-separated extra --spring properties, appended AFTER the proven
    # family so an A/B arm can vary ONE flag while keeping all of preflight's safety machinery
    # (model probe, watcher, corpus capture). Recorded into the corpus for provenance. Empty by default.
    echo "${PREFLIGHT_EXTRA_FLAGS:-(none)}" > "$corpus/extra_flags.txt"
    FILE_BASE_PATH=firestarter nohup java -jar "$jar" \
        --spring.profiles.active=dev-auto,fast \
        --autobuilder.goal.depth-first-decomposition=true \
        --autobuilder.goal.admission-cap=6 \
        --autobuilder.goal.subgoal-retry-enabled=true \
        --autobuilder.goal.divide-on-exhaustion=true \
        --autobuilder.goal.structure-query-enabled=true \
        ${PREFLIGHT_EXTRA_FLAGS:-} \
        > "$corpus/console.log" 2>&1 &
    pid=$!
    say "pid $pid — corpus $corpus"
    sleep 30
    kill -0 "$pid" 2>/dev/null || { FAILURES+=("jvm-died-at-startup (see $corpus/console.log)"); fail_out; }

    # G3: resolve THIS run's log files (newest matching the launch window), not the unsuffixed pair.
    local applog behlog
    applog=$(ls -t firestarter/logs/autobuilder*.log 2>/dev/null | head -1)
    behlog=$(ls -t firestarter/logs/behaviour*.ndjson 2>/dev/null | head -1)
    say "live logs: $applog / $behlog"

    t_end=$(( $(date +%s) + minutes * 60 ))
    local terminal="deadline"
    while [ "$(date +%s)" -lt "$t_end" ]; do
        sleep 60
        kill -0 "$pid" 2>/dev/null || { terminal="jvm-died"; break; }
        if grep -qE 'Result: GOAL_FULFILLED|root .* marked COMPLETED' "$applog"; then terminal="GOAL_FULFILLED"; break; fi
        if grep -qE 'Root goal exhausted|root .* marked GIVEN_UP' "$applog"; then terminal="GOAL_EXHAUSTED"; break; fi
        if grep -qE 'Pausing — manual input required|stagnation-runaway' "$applog"; then terminal="PAUSED"; break; fi
        # T5-BE: the loop's own brake halting is an HONEST terminal, not a deadline/silence.
        # Key on the exhaustion-halt markers only (a fulfilled run also pauses, but the
        # GOAL_FULFILLED check above breaks first). Stop promptly instead of idling to the cap.
        if grep -qE 'root convergence stall|Pausing for review|build-blocked-deadlock|Exhaustion runaway detected' "$applog"; then
            # 2026-07-17 run 2 was misclassified deadline: its halt line is
            # "Exhaustion runaway detected: ... Escalating to root exhaustion" — none of the
            # prior patterns. Prose marker maps to the pausedReason spelling for the corpus.
            hreason=$(grep -oE 'root-convergence-stall|build-blocked-deadlock|Exhaustion runaway detected' "$applog" | tail -1)
            hreason=${hreason/Exhaustion runaway detected/exhaustion-runaway}
            terminal="honest-halt${hreason:+:$hreason}"
            break
        fi
        # T1-FL: 3+ consecutive breaker opens with no successful response between them = the
        # local model has been continuously unreachable through escalating cooldowns — an
        # ENVIRONMENT terminal (check ollama), not a framework one. With auto-resume the app
        # log keeps updating during a dead-model stretch, so the silence check alone would
        # let such a run idle to the cap as a misleading "deadline".
        local deadOpens
        deadOpens=$(awk '/LLM circuit breaker OPEN/{co++} /circuit breaker closed after successful/{co=0} END{print co+0}' "$applog")
        [ "$deadOpens" -ge 3 ] && { terminal="model-unavailable"; break; }
        local age=$(( $(date +%s) - $(stat -c %Y "$applog") ))
        [ "$age" -gt 900 ] && { terminal="silent-${age}s"; break; }
    done

    say "terminal: $terminal — preserving corpus + stopping JVM"
    cp "$applog" "$corpus/autobuilder.log" 2>/dev/null
    cp "$behlog" "$corpus/behaviour.ndjson" 2>/dev/null
    cp firestarter/meta/GoalStack.json "$corpus/GoalStack.json" 2>/dev/null
    cp -r firestarter/output "$corpus/artifact" 2>/dev/null
    # T4-CA: capture the preserved best-verified artifact when the halt produced one
    cp -r firestarter/meta/preserved-artifact "$corpus/preserved-artifact" 2>/dev/null
    cp firestarter/meta/preserved-artifact-provenance.json "$corpus/" 2>/dev/null
    kill "$pid" 2>/dev/null; sleep 5; kill -9 "$pid" 2>/dev/null

    # T5-BD: capture the graph's current content alongside the log/GoalStack/output corpus —
    # the primary store (Neo4j) was being silently overwritten by the next run with nothing
    # preserved. Online, non-disruptive (no db stop/restart); best-effort (never fails the run).
    "$REPO/scripts/dump_graph.sh" "$corpus" 2>&1 | sed 's/^/[preflight] /' || true

    # Ground truth: build, boot, smoke.
    local smoke="skipped"
    if [ -f "$corpus/artifact/pom.xml" ]; then
        say "ground truth: building + booting the artifact"
        ( cd "$corpus/artifact" && mvn -q clean package -DskipTests > ../artifact-build.log 2>&1 )
        if [ $? -eq 0 ]; then
            local ajar
            ajar=$(ls "$corpus"/artifact/target/*.jar 2>/dev/null | head -1)
            if [ -n "$ajar" ]; then
                java -jar "$ajar" --server.port=8085 > "$corpus/boot.log" 2>&1 &
                local apid=$!
                sleep 14
                if grep -q 'Started' "$corpus/boot.log"; then
                    smoke=$("$REPO/scripts/smoke23.sh" http://localhost:8085 2>/dev/null | tail -1)
                else
                    smoke="boot-failed"
                fi
                kill "$apid" 2>/dev/null
            fi
        else
            smoke="artifact-build-failed"
        fi
    fi

    # Fix-marker census — activation counts for the shipped mechanisms.
    {
        echo "terminal=$terminal"
        echo "smoke=$smoke"
        echo "merges=$(grep -c 'mutation_merged' "$corpus/behaviour.ndjson" 2>/dev/null)"
        echo "grounding_stamps=$(grep -c 'criteria-grounding: goal' "$corpus/autobuilder.log" 2>/dev/null)"
        echo "noDiff_refusals=$(grep -c 'verified-criteria-unmet' "$corpus/autobuilder.log" 2>/dev/null)"
        echo "t2dr_reopens=$(grep -c 'reopened for rework' "$corpus/autobuilder.log" 2>/dev/null)"
        echo "positive_facts=$(grep -c 'WITH a status mapping in place' "$corpus/autobuilder.log" 2>/dev/null)"
        echo "quarantine=$(grep -c 'T4-BN-3: quarantined' "$corpus/autobuilder.log" 2>/dev/null)"
    } | tee "$corpus/summary.txt"

    # T4-CE slice 1: evaluate the field-watch registry against this corpus. One verdict line per
    # registered watch (scripts/watches.json is the single home for watch definitions) — sessions
    # read watch_report.txt instead of hand-running greps. Best-effort, never gates the run.
    python3 "$REPO/scripts/watch_report.py" "$corpus" 2>&1 | tee "$corpus/watch_report.txt" | sed 's/^/[preflight] /' || true

    case "$terminal" in
        GOAL_FULFILLED) pass_out "(terminal=GOAL_FULFILLED smoke=$smoke corpus=$corpus)";;
        GOAL_EXHAUSTED|PAUSED|deadline|honest-halt*)
            # Honest terminals are DATA, not failures — the driver reports them for analysis.
            pass_out "(terminal=$terminal smoke=$smoke corpus=$corpus — run completed for analysis)";;
        model-unavailable)
            FAILURES+=("terminal=model-unavailable (local model unreachable through 3+ breaker cooldowns — check ollama, not the framework) corpus=$corpus"); fail_out;;
        *) FAILURES+=("terminal=$terminal corpus=$corpus"); fail_out;;
    esac
}

case "$MODE" in
    quick) run_quick;;
    full)  run_full;;
    live)  run_live "$@";;
    *) echo "usage: $0 quick|full|live [minutes]"; exit 2;;
esac
