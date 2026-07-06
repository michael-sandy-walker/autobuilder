# Autonomous test-loop runbook

Written for a driving agent session (e.g. Claude Opus) validating autobuilder changes without
human supervision. Everything here is mechanical: run the command, read the last line, follow
the classification table. Do not improvise around the safety gates.

## The one command

```bash
scripts/preflight.sh quick            # ~1 min, no model needed — stage scenarios + targeted tests
scripts/preflight.sh full             # full suite + layer-2 evals (needs the local model)
scripts/preflight.sh live [minutes]   # capped live run, default 45 min — the real field validation
```

The **last line** of output is always machine-readable:

```
PREFLIGHT: PASS|FAIL mode=<mode> [failures: ...] [(terminal=... smoke=... corpus=...)]
```

## Safety rules (each one burned a real run — never bypass)

1. **Never run `mvn` in this repo yourself.** Only through `preflight.sh` — it refuses while an
   autobuilder JVM is alive. A raw `mvn test` rotates a live run's logs and can reset its
   GoalStack (burned 2026-07-03 ×2, 2026-07-07).
2. **Never launch a run yourself.** Only `preflight.sh live` — it probes the model first (an
   orphaned generation in the local model's queue kills the next JVM at startup) and kills only
   the JVM it started.
3. **Never read `logs/behaviour.ndjson` by glob assumption.** The corpus dir preflight prints
   contains the correct files for that run.
4. **Never `git push`, never `git checkout` / branch-switch** (the repo's `.claude-memory/` is
   live memory — switching branches mutates it). Commits are allowed on `main` only, message
   suffixed `Co-Authored-By:` per repo convention.
5. Port 8081 (app) and 8085 (smoke) belong to preflight. If `guard_no_jvm` reports an alive JVM
   you did not start, STOP and report — do not kill it.

## The loop

```
while work remains:
  1. scripts/preflight.sh quick        → FAIL: stop, report the failing test + log path
  2. scripts/preflight.sh full         → FAIL: stop, report
  3. scripts/preflight.sh live 45      → read the last line + corpus/summary.txt
  4. classify (table below), append one block to /tmp/preflight_runs/loop_report.md
  5. if classification demands a code change → STOP and report (the driver does not write code)
  6. else continue
```

## Terminal classification

| `terminal=` | `smoke=` | meaning | action |
|---|---|---|---|
| GOAL_FULFILLED | ≥20/23 | honest fulfillment | record; continue loop |
| GOAL_FULFILLED | <20/23 | POSSIBLE FALSE COMPLETION | STOP — report with corpus path; this is the highest-value finding |
| GOAL_EXHAUSTED | ≥20/23 | POSSIBLE FALSE GIVE-UP | STOP — report; second-highest value |
| GOAL_EXHAUSTED | <20/23 | honest give-up | record blocker from GoalStack.json; continue |
| PAUSED / deadline | any | brake or slow run | record; continue |
| jvm-died / silent-* | any | infrastructure failure | STOP — report console.log tail |

**Smoke `boot-failed` or `artifact-build-failed` with terminal=GOAL_FULFILLED is always a
false completion. STOP and report.**

## What to read per run (in order, nothing else needed)

1. `corpus/summary.txt` — terminal, smoke score, merge count, fix-marker census.
2. If classification says STOP: also attach the last 30 lines of `corpus/autobuilder.log`
   and the `blockers=[...]` line if present (`grep 'marked GIVEN_UP' corpus/autobuilder.log`).

## Fix-marker census (what "the fixes activated" means)

| marker in summary.txt | mechanism | healthy signal |
|---|---|---|
| `grounding_stamps` | criteria spec-grounding (run-19 fix) | >0 on runs where the planner authors criteria |
| `noDiff_refusals` | #NoDiff promotion gate (run-18 fix) | ≥0; >0 means it prevented a closed-but-unmet state |
| `t2dr_reopens` | closure-edge reopen (run-18 fix) | ≥0; >0 means a contradiction was recycled, not fatal |
| `positive_facts` | throw-mapping facts (run-18 fix) | >0 whenever criteria verification ran |
| `quarantine` | broken-test quarantine (run-17 fix) | >0 only if the model authored broken tests |

A marker at 0 is NOT a failure — most fire only when their trap condition arises. Report the
census; do not interpret beyond the table.

## Report block format (append per run to /tmp/preflight_runs/loop_report.md)

```
## <timestamp> — <mode> — <PASS|FAIL>
terminal=<...> smoke=<...> merges=<...>
census: grounding=<n> refusals=<n> reopens=<n> facts=<n> quarantine=<n>
corpus: <path>
verdict-free note: <one sentence, e.g. "honest exhaustion on missing POST /loans, model ceiling shape">
[STOP-reason if stopped]
```
