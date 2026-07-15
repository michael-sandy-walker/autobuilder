---
name: project-run-lifecycle-runbook
description: "How to start/stop/monitor autobuilder runs without the 2026-06-12 stumbles: brake-pause keeps the JVM alive on port 8081, pgrep self-match, FILE_BASE_PATH, monitor halt-pattern variants."
metadata: 
  node_type: memory
  type: project
  originSessionId: e5ead51e-ff28-4bd5-8310-673469c4a385
---

**Launch:** `FILE_BASE_PATH=firestarter setsid nohup java -jar target/autobuilder-1.0.1-SNAPSHOT.jar --spring.profiles.active=dev-auto > /tmp/autobuilder-stdout.log 2>&1 < /dev/null & disown`. Forgetting `FILE_BASE_PATH` → instant logback crash on `/data/firestarter`. Rebuild the jar (`mvn package -DskipTests`) after any framework change — the run uses the jar, not the source tree.

**⚠ setsid is NOT optional (burned 2026-07-15):** launching `preflight.sh live` as a harness-tracked background task (run_in_background) looked safe — but when the harness stopped the task it killed the whole process GROUP, taking the nohup'd JVM down mid-run (~19 min in, no terminal, no corpus). nohup does not change the process group. Always `setsid nohup scripts/preflight.sh live 45 > <launchlog> 2>&1 < /dev/null & disown`, then watch for completion with a separate kill-safe loop that only greps the launch log for `PREFLIGHT:` — that watcher dying harms nothing.

**Stop — the brake/pause trap:** a T4-I brake or SYSTEM_PAUSED terminal pauses the LOOP but keeps the JVM alive, logging "System is paused. Skipping run" every minute and **holding port 8081**. A relaunch then dies at boot with a port conflict (burned 2026-06-12 20:04: silent 15 min before diagnosis). Always kill the old JVM first and confirm: `ss -tln | grep 8081` empty before relaunch.

**Process matching — pgrep self-match:** `pgrep -f "java -jar.*autobuilder"` matches the *calling shell's own wrapper* (it embeds the pattern/launch text) → false "app UP", and `pkill -f` once killed my own command chain mid-pipeline. Reliable liveness: `for p in $(pgrep -x java); do tr '\0' ' ' < /proc/$p/cmdline | grep -q "autobuilder-1.0.1-SNAPSHOT.jar" && echo up; done`. Note `pgrep -x java` also matches the IntelliJ build JVM and neo4j — always confirm via the jar name in cmdline, never kill on a loose match.

**Monitor patterns (what a watcher must catch):** terminal markers vary — `T2-DR: root … marked (COMPLETED|GIVEN_UP)`, `root convergence stall` (live log has SPACES; the signal name `root-convergence-stall` does NOT appear), `Result: GOAL_EXHAUSTED`, `System is paused` (exclude the per-minute "Skipping run" repeats), `exhaustion runaway`. Also watch behaviour.ndjson mtime for silence ≥15 min (8 min is too twitchy — host load spikes legitimately starve the local model for minutes; report `/proc/loadavg` with any silence event to distinguish environment pressure from a wedge) and Neo4j `ServiceUnavailableException` bursts (Docker Desktop backend has crashed under memory pressure and taken the neo4j container with it, 2026-06-12 ~10:50).

See [[project-log-and-backup-conventions]] for which log files to read and [[project-tests-rotate-live-logs]] for why `mvn test` must not run while a run is live.
