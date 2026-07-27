---
name: feedback-monitor-every-terminal
description: "A run monitor must match EVERY terminal state, and stopping one for noise means re-arming a narrower one — not going blind."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 0708b08d-d453-408c-a6ef-dffaf6dc7a3b
  modified: 2026-07-27T14:33:45.808Z
---

When watching a live run, the monitor filter must cover **every** way the run can end —
`GOAL_FULFILLED`, all ten pause reasons (`root convergence stall`, `stagnation runaway`,
`exhaustion runaway`, `build-blocked-deadlock`, `root-exhaustion`, `planner-output-amputation`,
`empty-backlog-injection-limit`, `null-subgoal-halt`, breaker pause, user pause), the single
`run end report written (<reason>)` line that names all of them, and JVM death — not the subset
expected from the change under test.

**Why:** 2026-07-27, run 4 reached `GOAL_FULFILLED` with `SMOKE: 21/23` at 15:45 and it went
unreported for 43 minutes, until the user asked "anything new?". Two compounding mistakes: the
run-3 monitor was stopped because closure-edge `package gate result=COMPILE_OK` lines had become
routine noise — but that same filter carried the terminal markers, so quieting the noise threw
away the signal — and then no monitor was armed for run 4 at all. The user's correction was
"I meant in your monitor", after I had misread it as a question about the product code.

**How to apply:** arm a monitor at every run launch, keyed on terminals plus the few
change-specific markers being verified. If it gets noisy, stop it and immediately re-arm a
narrower one that still carries every terminal — never leave a live run unwatched. A background
waiter on the `PREFLIGHT:` line is a fallback for run END, not a substitute for noticing what the
run DID. Related: [[project-run-lifecycle-runbook]] (halt-marker variants),
[[feedback-escalate-unrecoverable-stagnation]] (watchers must inform early, not just at terminal
markers and silence).
