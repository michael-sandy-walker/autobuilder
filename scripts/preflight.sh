#!/bin/bash
# preflight.sh — one-command validation for autobuilder changes, safe for autonomous driving.
#
# Usage:  scripts/preflight.sh quick          # stage scenarios + targeted tests (~1 min, no model)
#         scripts/preflight.sh full           # full suite + opt-in layer-2 evals (needs local model)
#         scripts/preflight.sh live [minutes] # capped live dev-auto run (default 90), watcher,
#                                             # terminal classification, dual smoke, run_dir preservation
#
# T6-A dual smoke (summary.txt / PREFLIGHT line): live mode reports TWO scores.
#   smoke=            score of the run's FINAL files (unchanged meaning — past runs stay comparable)
#   smoke_preserved=  score of the best compiling artifact the run saved
#   preserved_source= where that copy came from:
#                       loop-preserved            in-process T4-CA hook fired THIS run (honest halt)
#                       reconstructed:<cycle>     offline re-selection over THIS run's snapshots
#                       reconstruction-uncompilable  no snapshot compiled offline
#                       no-eligible-snapshot      nothing passed the run-relative bar
#                       none                      no preserved copy and no snapshots to select from
#                       …-after-stale-loop-copy   T6-F: a copy inherited from an EARLIER run was
#                                                 present and correctly ignored; the score above
#                                                 comes from this run's own snapshots instead
#
# Machine-readable result: the LAST line is always
#         PREFLIGHT: PASS|FAIL mode=<mode> [failures: ...]
# Everything the driver needs is in that line + the preserved run_dir dir it names.
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
    #
    # The process must ALSO be a JVM. Matching the jar name alone matches anything that merely
    # MENTIONS it: on 2026-07-30 a session's own run monitor checked liveness by grepping for the
    # jar name, so its shells carried the pattern in their command lines and this guard reported
    # five live JVMs when none existed, blocking a relaunch. That is the same self-match family the
    # runbook records twice already, one level up — the fix is to require the thing being counted
    # to be the thing we care about, not a string about it.
    for p in /proc/[0-9]*/cmdline; do
        pid="${p#/proc/}"; pid="${pid%/cmdline}"
        [ "$(cat "/proc/$pid/comm" 2>/dev/null)" = "java" ] || continue
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
    local url model effort t0 t1
    url=$(grep -A4 '^llm:' src/main/resources/application.yml | grep 'url:' | grep -v '^\s*#' | awk '{print $2}')
    model=$(grep -A8 '^llm:' src/main/resources/application.yml | grep 'model:' | grep -v '^\s*#' | awk '{print $2}')
    # Probe with the DEPLOYED reasoning budget, and count hidden reasoning as an answer.
    # 2026-08-22: the probe asked for a 5-token reply and sent no budget, so on a reasoning-capable
    # model (qwen3.8) all five tokens went to hidden reasoning, `content` came back empty, and a
    # healthy endpoint was reported unreachable — the gate was testing something the app never does.
    # Unique key in the file, so no -A offset to keep in sync with the block above.
    effort=$(grep -E '^[[:space:]]*reasoning-effort:[[:space:]]*[^[:space:]]' src/main/resources/application.yml \
        | grep -v '^[[:space:]]*#' | head -1 | awk '{print $2}')
    local effort_field=""
    [ -n "$effort" ] && effort_field=",\"reasoning_effort\":\"$effort\""
    # Two calls: the first absorbs a model LOAD, the second is the one timed.
    # 2026-08-22: a series run died here on model-slow:18559ms that was purely 17 GB coming off
    # disk (an eval run had just evicted the model). The gate exists to catch an ORPHANED
    # GENERATION still occupying the endpoint -- that condition makes BOTH calls slow, while a
    # cold load only makes the first one slow. Timing the second tells the two apart; timing the
    # first cannot, and cost a run.
    local probe_body
    probe_body="{\"model\":\"$model\",\"messages\":[{\"role\":\"user\",\"content\":\"Say OK\"}],\"max_tokens\":16$effort_field}"
    ask() {
        curl -s --max-time 120 "$url" -H 'Content-Type: application/json' -d "$probe_body" \
            | python3 -c "import json,sys; m=json.load(sys.stdin)['choices'][0]['message']; print(((m.get('content') or '') + (m.get('reasoning') or '')).strip())" 2>/dev/null
    }
    local warm
    warm=$(ask)
    if [ -z "$warm" ]; then
        say "G2: model at $url did not answer (warm-up call)"
        FAILURES+=("model-unreachable")
        return 1
    fi
    t0=$(date +%s%N)
    local out
    out=$(ask)
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

# T6-A: build + boot + run the behaviour smoke against ONE artifact tree. Echoes the smoke
# script's last line ("SMOKE: n/23") on success, or a failure token (no-pom / artifact-build-failed
# / no-jar / boot-failed). Used for both the final-files score and the preserved score. Reads the
# caller's $run_dir and $smoke_script (dynamic scope).
build_boot_smoke() {
    local tree="$1" port="$2" tag="$3"
    [ -f "$tree/pom.xml" ] || { echo "no-pom"; return; }
    ( cd "$tree" && mvn -q clean package -DskipTests > "$run_dir/${tag}-build.log" 2>&1 )
    if [ $? -ne 0 ]; then echo "artifact-build-failed"; return; fi
    local ajar
    ajar=$(ls "$tree"/target/*.jar 2>/dev/null | head -1)
    [ -n "$ajar" ] || { echo "no-jar"; return; }
    # T4-DD item 3: the yardstick must ask the question the TARGET's completion model licenses.
    # A tool that runs and terminates never prints a readiness banner and is never there to hold a
    # port, so the serving path below scored every such artifact `boot-failed` — a FALSE ZERO in
    # the ledger regardless of how correct it was (measured on the CLI target: 12/12 by hand,
    # boot-failed in the record). The smoke script is handed the artifact itself and does its own
    # invoking. Default is `serving`, so every existing target and every archived row is unchanged.
    local mode="${PREFLIGHT_SMOKE_MODE:-serving}"
    local result
    if [ "$mode" = "terminating" ]; then
        "$smoke_script" "$ajar" > "$run_dir/${tag}-smoke.txt" 2>"$run_dir/${tag}-boot.log"
        result=$(tail -1 "$run_dir/${tag}-smoke.txt")
        echo "${result:-smoke-failed}"
        return
    fi
    java -jar "$ajar" --server.port="$port" > "$run_dir/${tag}-boot.log" 2>&1 &
    local apid=$!
    sleep 14
    if grep -q 'Started' "$run_dir/${tag}-boot.log"; then
        # T6-K: keep the per-check PASS/FAIL lines, not just the total. The standing rule is to
        # compare failing check SETS and never totals — the score is a stateful chain in which one
        # missing controller costs 8 points and makes two checks pass spuriously — but only the
        # total was ever written down, so the rule was unfollowable after the fact and re-deriving
        # a set meant rebuilding and booting a preserved artifact. Runs 2026-08-01 18-36-18 (16/23)
        # and 19-29-42 (21/23) could not be compared for exactly this reason.
        "$smoke_script" "http://localhost:$port" > "$run_dir/${tag}-smoke.txt" 2>/dev/null
        result=$(tail -1 "$run_dir/${tag}-smoke.txt")
    else
        result="boot-failed"
    fi
    kill "$apid" 2>/dev/null
    echo "${result:-boot-failed}"
}

# T6-A: does this artifact tree's SOURCES compile? (mvn compile == JavacDirectProbe COMPILE_OK) —
# the offline equivalent of PreservedArtifactService's preserve-time verification walk. A stamped
# buildResult can be stale (injected writes never face a build), so a reconstruction candidate is
# trusted only if it actually compiles here. Note: compiling is NOT the same as packageable — a
# tree can compile yet fail `mvn package` at repackage (no main class); that is still the run's
# best saved copy, and its smoke_preserved then honestly reads artifact-build-failed.
compiles() {
    local tree="$1" tag="$2"
    [ -f "$tree/pom.xml" ] || return 1
    ( cd "$tree" && mvn -q clean compile > "$run_dir/${tag}-compile.log" 2>&1 )
}

run_quick() {
    guard_no_jvm
    say "stage scenarios + gate/closure/verification tests"
    FILE_BASE_PATH=data/firestarter mvn -q test \
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
    FILE_BASE_PATH=data/firestarter mvn -q test > /tmp/preflight_full.log 2>&1
    if [ $? -ne 0 ]; then
        grep -E '<<< (FAILURE|ERROR)' /tmp/preflight_full.log | head -5
        FAILURES+=("full-suite (log: /tmp/preflight_full.log)")
        fail_out
    fi
    if probe_model; then
        say "layer-2 verifier eval (live model)"
        T5AF_VERIFIER_EVAL=1 FILE_BASE_PATH=data/firestarter mvn -q test \
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
    local minutes="${2:-90}"
    local stamp run_dir jar pid t_end
    # T4-CK: target selection. PREFLIGHT_TARGET names the target dir (the FILE_BASE_PATH the
    # app evolves); PREFLIGHT_SMOKE the matching behaviour-smoke script. Defaults preserve the
    # firestarter setup exactly; the unit-suite modes are target-independent and stay untouched.
    # PREFLIGHT_TARGET names a target; targets live under data/. Keeping it a NAME rather than a
    # path means every existing invocation, doc and archived target.txt row stays valid and
    # comparable across the run corpus, which is T6-N's evidence base.
    local target_name="${PREFLIGHT_TARGET:-firestarter}"
    local target="data/$target_name"
    local smoke_script="${PREFLIGHT_SMOKE:-$REPO/scripts/smoke23.sh}"
    stamp=$(date +%Y-%m-%d_%H-%M-%S)
    run_dir="/tmp/preflight_runs/$stamp"
    mkdir -p "$run_dir"
    echo "target=$target_name" > "$run_dir/target.txt"

    guard_no_jvm
    probe_model || fail_out

    say "building jar"
    mvn -q package -DskipTests > "$run_dir/build.log" 2>&1 \
        || { FAILURES+=("jar-build (log: $run_dir/build.log)"); fail_out; }
    jar=$(ls target/autobuilder-*-SNAPSHOT.jar | head -1)

    say "launching capped live run (${minutes} min): proven flag family + criteria grounding${PREFLIGHT_EXTRA_FLAGS:+ + extra: $PREFLIGHT_EXTRA_FLAGS}"
    # PREFLIGHT_EXTRA_FLAGS: space-separated extra --spring properties, appended AFTER the proven
    # family so an A/B arm can vary ONE flag while keeping all of preflight's safety machinery
    # (model probe, watcher, run_dir capture). Recorded into the run_dir for provenance. Empty by default.
    echo "${PREFLIGHT_EXTRA_FLAGS:-(none)}" > "$run_dir/extra_flags.txt"
    FILE_BASE_PATH="$target" nohup java -jar "$jar" \
        --spring.profiles.active=dev-auto,fast \
        --autobuilder.goal.depth-first-decomposition=true \
        --autobuilder.goal.admission-cap=6 \
        --autobuilder.goal.subgoal-retry-enabled=true \
        --autobuilder.goal.divide-on-exhaustion=true \
        --autobuilder.goal.structure-query-enabled=true \
        ${PREFLIGHT_EXTRA_FLAGS:-} \
        > "$run_dir/console.log" 2>&1 &
    pid=$!
    say "pid $pid — run_dir $run_dir"
    sleep 30
    kill -0 "$pid" 2>/dev/null || { FAILURES+=("jvm-died-at-startup (see $run_dir/console.log)"); fail_out; }

    # G3: resolve THIS run's log files (newest matching the launch window), not the unsuffixed pair.
    local applog behlog
    applog=$(ls -t "$target"/logs/autobuilder*.log 2>/dev/null | head -1)
    behlog=$(ls -t "$target"/logs/behaviour*.ndjson 2>/dev/null | head -1)
    say "live logs: $applog / $behlog"

    t_end=$(( $(date +%s) + minutes * 60 ))
    local terminal="deadline"
    while [ "$(date +%s)" -lt "$t_end" ]; do
        sleep 60
        kill -0 "$pid" 2>/dev/null || { terminal="jvm-died"; break; }
        if grep -qE 'Result: GOAL_FULFILLED|root .* marked COMPLETED' "$applog"; then
            terminal="GOAL_FULFILLED"
            # Let the framework FINISH its terminal processing before the kill: the fulfilled
            # marker prints mid-tick, but the pause branch (system_paused event + fulfilled
            # end report) runs on the NEXT tick — after interpretation's LLM call, which can
            # take a minute. A fulfilled run once got killed in that window and its lineage
            # terminal record stayed the PARENT's halt end report. Wait for the pause line
            # (bounded by the run deadline + a short grace so a wedged tick can't hang us).
            local settle_end=$(( $(date +%s) + 180 ))
            while [ "$(date +%s)" -lt "$t_end" ] && [ "$(date +%s)" -lt "$settle_end" ]; do
                grep -q 'Goal fulfilled\. Pausing' "$applog" && break
                kill -0 "$pid" 2>/dev/null || break
                sleep 10
            done
            break
        fi
        if grep -qE 'Root goal exhausted|root .* marked GIVEN_UP' "$applog"; then terminal="GOAL_EXHAUSTED"; break; fi
        if grep -qE 'Pausing — manual input required|stagnation-runaway' "$applog"; then terminal="PAUSED"; break; fi
        # T5-BE: the loop's own brake halting is an HONEST terminal, not a deadline/silence.
        # Key on the exhaustion-halt markers only (a fulfilled run also pauses, but the
        # GOAL_FULFILLED check above breaks first). Stop promptly instead of idling to the cap.
        if grep -qE 'root convergence stall|Pausing for review|build-blocked-deadlock|Exhaustion runaway detected' "$applog"; then
            # 2026-07-17 run 2 was misclassified deadline: its halt line is
            # "Exhaustion runaway detected: ... Escalating to root exhaustion" — none of the
            # prior patterns. Prose marker maps to the pausedReason spelling for the run_dir.
            hreason=$(grep -oE 'root-convergence-stall|root convergence stall|build-blocked-deadlock|Exhaustion runaway detected' "$applog" | tail -1)
            hreason=${hreason/Exhaustion runaway detected/exhaustion-runaway}
            # 2026-08-24 run 00-07-06 recorded a bare honest-halt: RootConvergenceTracker writes
            # the reason with SPACES at the moment it fires, the hyphenated token exists only in
            # the end report, and that run was killed before the report was written. Reading the
            # halt EVENT rather than a downstream report is what makes the reason survive.
            hreason=${hreason/root convergence stall/root-convergence-stall}
            # Prose token missing for this flavor (e.g. planner-output-amputation's "Pausing
            # for review") → the end-report line names the true reason for every flavor.
            if [ -z "$hreason" ]; then
                hreason=$(grep -oE 'run end report written \([a-z][a-z-]*' "$applog" | tail -1 | sed 's/.*(//')
            fi
            terminal="honest-halt${hreason:+:$hreason}"
            break
        fi
        # T4-CD single-home catch-all: the run end report is written exactly once per pause
        # episode and its log line names the real pausedReason for EVERY halt flavor — so any
        # honest halt classifies from this one line instead of a prose pattern per flavor
        # (the 12:56 run halted empty-backlog-injection-limit, matched nothing above, and
        # idled to a misleading silent-955s that stopped a healthy run series as ENVIRONMENT).
        # The GOAL_FULFILLED check above breaks first on fulfilled runs.
        if grep -q 'run end report written (' "$applog"; then
            hreason=$(grep -oE 'run end report written \([a-z][a-z-]*' "$applog" | tail -1 | sed 's/.*(//')
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

    say "terminal: $terminal — preserving run_dir + stopping JVM"
    cp "$applog" "$run_dir/autobuilder.log" 2>/dev/null
    cp "$behlog" "$run_dir/behaviour.ndjson" 2>/dev/null
    cp "$target"/meta/GoalStack.json "$run_dir/GoalStack.json" 2>/dev/null
    cp -r "$target"/output "$run_dir/artifact" 2>/dev/null
    # T4-CA: capture the preserved best-verified artifact when the halt produced one
    cp -r "$target"/meta/preserved-artifact "$run_dir/preserved-artifact" 2>/dev/null
    cp "$target"/meta/preserved-artifact-provenance.json "$run_dir/" 2>/dev/null
    # T4-CD slice 2: capture the run end report (terminal self-description) when one was written
    cp "$target"/meta/run-end-report.json "$run_dir/" 2>/dev/null
    kill "$pid" 2>/dev/null; sleep 5; kill -9 "$pid" 2>/dev/null

    # T5-BD: capture the graph's current content alongside the log/GoalStack/output run_dir —
    # the primary store (Neo4j) was being silently overwritten by the next run with nothing
    # preserved. Online, non-disruptive (no db stop/restart); best-effort (never fails the run).
    "$REPO/scripts/dump_graph.sh" "$run_dir" 2>&1 | sed 's/^/[preflight] /' || true

    # T4-CI phase 2: run-health table vs the population of labeled-healthy prior runs —
    # organic "is this shape normal" context (never gates; a table, not an alarm).
    python3 "$REPO/scripts/run_profile.py" --report "$run_dir" \
        --baseline /tmp/preflight_runs --labels "$REPO/scripts/run_labels.json" \
        > "$run_dir/health_report.txt" 2>&1 || true
    tail -1 "$run_dir/health_report.txt" | sed 's/^/[preflight] /' || true

    # Ground truth: build, boot, smoke — the score of the run's FINAL files (meaning unchanged, so
    # past runs stay comparable per T6-A).
    local smoke="skipped"
    if [ -f "$run_dir/artifact/pom.xml" ]; then
        say "ground truth: building + booting the FINAL artifact"
        smoke=$(build_boot_smoke "$run_dir/artifact" 8085 artifact)
    fi

    # T6-A: score the best saved/compiling version alongside the final files. smoke= is the final
    # files (above); smoke_preserved= is the score of the best compiling artifact the run saved.
    # Two sources: (a) an HONEST HALT already ran the in-process T4-CA hook and left
    # meta/preserved-artifact/ (captured to the run_dir) — score that copy directly; (b) a TIME-LIMIT
    # run / external kill left no preserved copy (the hook never fired) — reconstruct the hook's
    # selection offline over the snapshots the killed run left on disk, then score the reconstruction.
    # Which of the two scores becomes the headline number is decided later over a batch scored both ways.
    # T6-F: `preserved-artifact/` and its provenance SURVIVE ACROSS RUNS, so existence says
    # nothing about which run produced the copy. Before this check, an inherited copy was scored
    # as the run's own and the reconstruction below — the path that exists for killed runs — was
    # skipped (observed 2026-07-26: runs 3 and 4 both reported a copy written at the end of run 2,
    # while run 3's own tree was the lineage's first bootable artifact). Attribution is by cycle
    # id, not timestamps; `unknown` is treated as stale because an unattributable copy must not be
    # reported as this run's.
    local smoke_preserved="none" preserved_source="none"
    local loop_copy="absent" stale_note=""
    if [ -f "$run_dir/preserved-artifact/pom.xml" ]; then
        loop_copy=$(python3 "$REPO/scripts/preserved_freshness.py" \
            --provenance "$run_dir/preserved-artifact-provenance.json" \
            --run-log "$run_dir/autobuilder.log" 2>/dev/null || echo "unknown:check-failed")
        say "T6-F: loop-preserved copy is $loop_copy"
    fi
    case "$loop_copy" in fresh:*) : ;; absent) : ;; *) stale_note="-after-stale-loop-copy" ;; esac

    if [ "$loop_copy" != "absent" ] && [ -z "$stale_note" ]; then
        preserved_source="loop-preserved"
        say "T6-A: scoring the loop-preserved artifact (in-process hook fired this run)"
        smoke_preserved=$(build_boot_smoke "$run_dir/preserved-artifact" 8086 preserved)
    elif [ -d "$target/snapshots" ]; then
        say "T6-A: no usable loop-preserved copy (terminal=$terminal, copy=$loop_copy) — reconstructing from THIS run's snapshots"
        local provarg=()
        [ -f "$run_dir/preserved-artifact-provenance.json" ] && provarg=(--provenance "$run_dir/preserved-artifact-provenance.json")
        local probe_order=()
        mapfile -t probe_order < <(python3 "$REPO/scripts/select_preserved.py" "$target/snapshots" "${provarg[@]}" --run-log "$run_dir/autobuilder.log" 2>"$run_dir/preserved-select.log")
        sed 's/^/[preflight] /' "$run_dir/preserved-select.log" 2>/dev/null
        if [ "${#probe_order[@]}" -eq 0 ]; then
            preserved_source="no-eligible-snapshot$stale_note"
        else
            local selected="" cyc
            for snap in "${probe_order[@]}"; do
                cyc=$(basename "$snap")
                if compiles "$snap" "preserved-$cyc"; then selected="$snap"; break; fi
                say "T6-A: snapshot $cyc stamped compilable but fails offline compile — walking to an older merge-anchored candidate"
            done
            if [ -z "$selected" ]; then
                preserved_source="reconstruction-uncompilable$stale_note"
            else
                preserved_source="reconstructed:$(basename "$selected")$stale_note"
                rm -rf "$run_dir/preserved-reconstructed" 2>/dev/null
                cp -r "$selected" "$run_dir/preserved-reconstructed" 2>/dev/null
                say "T6-A: reconstructed preserved artifact from $(basename "$selected") — scoring it"
                smoke_preserved=$(build_boot_smoke "$selected" 8086 preserved)
            fi
        fi
    fi
    say "T6-A: smoke=$smoke smoke_preserved=$smoke_preserved preserved_source=$preserved_source"

    # Fix-marker census — activation counts for the shipped mechanisms.
    {
        echo "terminal=$terminal"
        echo "smoke=$smoke"
        echo "smoke_preserved=$smoke_preserved"
        echo "preserved_source=$preserved_source"
        # T6-K: WHICH checks failed, not just how many. A total cannot be compared across runs
        # (one absent controller moves eight checks at once and flips two the wrong way), so the
        # set is what any honest comparison needs. Emitted as the bare check numbers of the
        # PRESERVED artifact — the same tree smoke_preserved scored — and absent rather than empty
        # when nothing was scored, so "no failures" and "not measured" stay distinguishable.
        if [ -s "$run_dir/preserved-smoke.txt" ]; then
            echo "smoke_failed_checks=$(sed -n 's/^FAIL \[\([0-9]*\)\].*/\1/p' \
                "$run_dir/preserved-smoke.txt" | paste -sd, -)"
        fi
        # T6-K: the STRICT companion score. Five checks assert a 404, and an endpoint that does
        # not exist 404s everything — so those points can be collected for absent code. `smoke`
        # above keeps its meaning so the ledger stays readable (the T6-A precedent); this is the
        # second number, and the two belong together. Emitted only when a per-check sheet exists,
        # absent rather than zero otherwise.
        if [ -s "$run_dir/preserved-smoke.txt" ] || [ -s "$run_dir/artifact-smoke.txt" ]; then
            # One line per TREE. `smoke` scores the final files and `smoke_preserved` the best
            # saved copy, so a single strict number beside both is ambiguous — the first wiring
            # read as "4 points withheld" on a run where nothing was withheld and the two numbers
            # simply described different artifacts.
            python3 "$REPO/scripts/smoke_strict.py" --json "$run_dir" 2>/dev/null \
                | python3 -c 'import json,sys
try:
    d=json.load(sys.stdin)
    r=next(iter(d.values()))
    for label,field in (("artifact","smoke_strict"),("preserved","smoke_preserved_strict")):
        if label in r:
            e=r[label]
            print("%s=%d/23 discounted=%s" % (field, e["smoke_strict"],
                                              ",".join(map(str, e["discounted"])) or "none"))
except Exception:
    pass' 2>/dev/null
        fi
        echo "merges=$(grep -c 'mutation_merged' "$run_dir/behaviour.ndjson" 2>/dev/null)"
        echo "grounding_stamps=$(grep -c 'criteria-grounding: goal' "$run_dir/autobuilder.log" 2>/dev/null)"
        echo "noDiff_refusals=$(grep -c 'verified-criteria-unmet' "$run_dir/autobuilder.log" 2>/dev/null)"
        echo "t2dr_reopens=$(grep -c 'reopened for rework' "$run_dir/autobuilder.log" 2>/dev/null)"
        echo "positive_facts=$(grep -c 'WITH a status mapping in place' "$run_dir/autobuilder.log" 2>/dev/null)"
        echo "quarantine=$(grep -c 'T4-BN-3: quarantined' "$run_dir/autobuilder.log" 2>/dev/null)"
        # Verification cadence. Every execution-based check (package, boot, T4-CU criterion
        # probes) hangs off autoCompleteRootIfSatisfied, which early-returns while any subgoal is
        # ACTIVE — so a run can build for an hour with nothing but a compile probe looking at it.
        # Recorded per run so "do more closure attempts mean a better score?" is a question the
        # ledger can answer, rather than one asserted from three runs.
        echo "closure_attempts=$(grep -c 'root closure blocked' "$run_dir/autobuilder.log" 2>/dev/null)"
        echo "package_gate_runs=$(grep -c 'package gate result' "$run_dir/autobuilder.log" 2>/dev/null)"
        echo "boot_gate_runs=$(grep -c 'boot gate result' "$run_dir/autobuilder.log" 2>/dev/null)"
        echo "criterion_probe_runs=$(grep -c 'T4-CU: behavioural criteria' "$run_dir/autobuilder.log" 2>/dev/null)"
        # T6-J: where the run's wall-clock went. Before this, LLM calls and builds together
        # explained under a quarter of a run's minutes and the gates that package and boot the
        # artifact were untimed, so 70 of 89 minutes had no owner. Last readout wins — it is
        # cumulative over the session.
        echo "cost_readout=$(sed -n 's/.*T6-J: session cost — \(.*\)$/\1/p' "$run_dir/autobuilder.log" 2>/dev/null | tail -1)"
        # T2-EX: how much of this run was verifiable AT ALL. Across three frozen runs of one build
        # and one seed the executable share was 71%, 31% and 74% — the run at 31% was dark on
        # verification for its whole length and nothing recorded it. Taken from the LAST reported
        # figure, which is the plan the run ended with. Six runs of this decide whether authoring
        # quality is a cause or a symptom; nothing steers on it before then.
        # sed, not `grep -o '[0-9]*'` — the marker "T2-EX" contains a digit, so a bare number
        # pattern picks the 2 out of the label before it ever reaches the count.
        # T2-EX. `criteria_executable` captured the PERCENT while `criteria_authored` captured a
        # COUNT, under names that read as a matching pair — so "55/11" looked like a ratio of 500%
        # rather than "55%, of 11 authored". It misled its own author on 2026-08-04 within a minute
        # of being read. Both are emitted under unambiguous names now; the old field keeps its
        # historical meaning on existing rows rather than being silently redefined.
        echo "criteria_executable_pct=$(sed -n 's/.*criteria executability — [0-9]* authored, [0-9]* name an endpoint, [0-9]* executable (\([0-9]*\)%).*/\1/p' "$run_dir/autobuilder.log" 2>/dev/null | tail -1)"
        echo "criteria_executable_count=$(sed -n 's/.*criteria executability — [0-9]* authored, [0-9]* name an endpoint, \([0-9]*\) executable.*/\1/p' "$run_dir/autobuilder.log" 2>/dev/null | tail -1)"
        echo "criteria_authored=$(sed -n 's/.*criteria executability — \([0-9]*\) authored.*/\1/p' "$run_dir/autobuilder.log" 2>/dev/null | tail -1)"
        # T4-DD: the exit-status channel, reported SEPARATELY rather than folded into the fields
        # above. Those measure "how many criteria name an ENDPOINT", which is the web target's
        # question and the basis of every comparison in the 79-run ledger — redefining them would
        # silently change what past rows mean. Reporting zero while ten criteria were probed is how
        # a working channel gets called dead (observed on run 2026-08-26_14-53-24), so it gets its
        # own columns instead. Absent on a target with no command surface, which is correct: the
        # channel abstained, it did not fail.
        echo "exit_status_probe_runs=$(grep -c 'T4-DD: exit-status criteria' "$run_dir/autobuilder.log" 2>/dev/null)"
        echo "exit_status_probed=$(sed -n 's/.*exit-status criteria probed=\([0-9]*\).*/\1/p' "$run_dir/autobuilder.log" 2>/dev/null | tail -1)"
        echo "exit_status_contradicted=$(sed -n 's/.*exit-status criteria .*contradicted=\([0-9]*\).*/\1/p' "$run_dir/autobuilder.log" 2>/dev/null | tail -1)"
        # T2-EY: how much of the run was the loop re-proposing what it had already been refused,
        # and how much of that the gate stopped before an evaluator call. Both, never just the
        # second: recording only what was saved would let the exposure vanish from the record.
        # sed rather than `grep -o '[0-9]*'` — the marker "T2-EY" contains a digit.
        echo "repeat_proposals=$(sed -n 's/.*repeat proposals — [0-9]* distinct rejected, \([0-9]*\) re-proposed.*/\1/p' "$run_dir/autobuilder.log" 2>/dev/null | tail -1)"
        echo "repeats_refused=$(sed -n 's/.*repeat proposals — .* \([0-9]*\) refused before evaluation.*/\1/p' "$run_dir/autobuilder.log" 2>/dev/null | tail -1)"
        # T2-EZ: how much deterministic compile-fix work the injector produced on a red build and
        # how much of it the tree kept, alongside how many fixes it still had to re-derive across
        # cycles. The third number is the one that says whether preservation is working: kept
        # rising while re-derived holds steady would mean the preserved files are not the ones
        # being re-derived. sed rather than a bare number pattern — "T2-EZ" contains a digit.
        echo "injector_files_created=$(sed -n 's/.*injector files — \([0-9]*\) created on a red build.*/\1/p' "$run_dir/autobuilder.log" 2>/dev/null | tail -1)"
        echo "injector_files_kept=$(sed -n 's/.*injector files — [0-9]* created on a red build, \([0-9]*\) kept.*/\1/p' "$run_dir/autobuilder.log" 2>/dev/null | tail -1)"
        echo "injector_fixes_rederived=$(sed -n 's/.*injector files — .* \([0-9]*\) fix(es) re-derived across cycles.*/\1/p' "$run_dir/autobuilder.log" 2>/dev/null | tail -1)"
        # T4-DA: how many of the specification clauses that NO run of the df2a907 frozen series
        # ever articulated this run's plan does articulate. Baseline is 0 per run, verified across
        # all three controls. Scored here so the watch's three-run question accumulates by itself;
        # a run whose plan cannot be read simply reports nothing.
        echo "clauses_closed_vs_control=$(python3 "$REPO/scripts/clause_coverage.py" --against-control \
            "$REPO/scripts/clause_control_df2a907.txt" "$run_dir" 2>/dev/null \
            | sed -n 's/.*closed \([0-9]*\) of [0-9]* control clauses.*/\1/p' | tail -1)"
        echo "median_cycle_seconds=$(python3 - "$run_dir/behaviour.ndjson" <<'PYCYC' 2>/dev/null
import json, sys, datetime as dt
try:
    txt = open(sys.argv[1], errors="replace").read()
except OSError:
    raise SystemExit
dec, i, ts = json.JSONDecoder(), 0, []
while i < len(txt):
    while i < len(txt) and txt[i].isspace():
        i += 1
    if i >= len(txt):
        break
    try:
        o, i = dec.raw_decode(txt, i)
    except ValueError:
        break
    if o.get("event") == "cycle_start":
        ts.append(dt.datetime.fromisoformat(o["timestamp"]))
if len(ts) > 1:
    d = sorted((ts[k + 1] - ts[k]).total_seconds() for k in range(len(ts) - 1))
    print(int(d[len(d) // 2]))
PYCYC
)"
        # T6-A drift overview: the final-vs-best delta + where the run peaked + how long it spent
        # drifting after (score_delta > 0 => the run ended worse than a state it already saved).
        python3 "$REPO/scripts/drift_report.py" "$run_dir" \
            --final "$smoke" --preserved "$smoke_preserved" --source "$preserved_source" 2>/dev/null
    } | tee "$run_dir/summary.txt"

    # T6-E: append this run's outcome to the DURABLE ledger in the repo. summary.txt lives in the
    # /tmp run_dir and is lost when /tmp is cleared — which is exactly why outcome ground truth
    # existed for only 14 of 237 recorded runs. The ledger survives, so the scored population
    # grows monotonically. Never gates: exits 0 even if it cannot record.
    python3 "$REPO/scripts/outcome_ledger.py" --append "$run_dir" 2>/dev/null || true

    # T4-CE slice 1: evaluate the field-watch registry against this run_dir. One result line per
    # registered watch (scripts/watches.json is the single home for watch definitions) — sessions
    # read watch_report.txt instead of hand-running greps. Best-effort, never gates the run.
    python3 "$REPO/scripts/watch_report.py" "$run_dir" 2>&1 | tee "$run_dir/watch_report.txt" | sed 's/^/[preflight] /' || true

    # T6-N slice 1: archive the analysis-relevant slice of the run_dir OUTSIDE /tmp.
    #
    # run_dirs are 94-135 MB each and live in /tmp, so a reboot takes them: of 72 recorded runs
    # only 5 still had one, which left every per-cycle question (T6-N, T6-M) unanswerable no
    # matter how many runs were made. T6-E already fixed exactly this loss for the outcome
    # ledger; the lesson did not reach the corpora the ledger points AT.
    #
    # Archived slim, not whole: the graph dumps are 80 MB of the 120 and answer nothing offline,
    # while artifact-build.log / artifact-boot.log are a few KB and are the ONLY record of why an
    # artifact would not build or boot once the tree is gone. Gzipped, a run costs ~2 MB.
    # Never gates the run -- an archiving failure must not fail a run that already happened.
    local archive="${PREFLIGHT_ARCHIVE:-$HOME/.autobuilder/run-archive}/$stamp"
    if mkdir -p "$archive" 2>/dev/null; then
        for f in summary.txt target.txt extra_flags.txt watch_report.txt preserved-smoke.txt \
                 artifact-build.log artifact-boot.log; do
            [ -f "$run_dir/$f" ] && cp "$run_dir/$f" "$archive/" 2>/dev/null
        done
        for f in behaviour.ndjson autobuilder.log GoalStack.json; do
            [ -f "$run_dir/$f" ] && gzip -c "$run_dir/$f" > "$archive/$f.gz" 2>/dev/null
        done
        say "archived run to $archive ($(du -sh "$archive" 2>/dev/null | cut -f1))"
    else
        say "WARNING: could not create $archive — this run's corpus stays in /tmp only"
    fi

    case "$terminal" in
        GOAL_FULFILLED) pass_out "(terminal=GOAL_FULFILLED smoke=$smoke smoke_preserved=$smoke_preserved run=$run_dir)";;
        GOAL_EXHAUSTED|PAUSED|deadline|honest-halt*)
            # Honest terminals are DATA, not failures — the driver reports them for analysis.
            pass_out "(terminal=$terminal smoke=$smoke smoke_preserved=$smoke_preserved run=$run_dir — run completed for analysis)";;
        model-unavailable)
            FAILURES+=("terminal=model-unavailable (local model unreachable through 3+ breaker cooldowns — check ollama, not the framework) run=$run_dir"); fail_out;;
        *) FAILURES+=("terminal=$terminal run=$run_dir"); fail_out;;
    esac
}

case "$MODE" in
    quick) run_quick;;
    full)  run_full;;
    live)  run_live "$@";;
    *) echo "usage: $0 quick|full|live [minutes]"; exit 2;;
esac
