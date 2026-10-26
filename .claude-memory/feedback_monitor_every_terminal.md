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

**2026-10-26, a scripted WAIT counts too.** A derivation-only "warm" script waited only for the derivation's summary
line; the 60-minute run it started ended at its deadline mid-repair, printed no such line, and the script -- with a
batch chained behind it -- waited for ever (found by the user asking "why was it stuck?"). Every `until grep ...`
in a driver also ends when the run itself ends (`PREFLIGHT:` in its launch log), and a run started only to produce
an artifact gets a deadline sized to that artifact (edit-hunks' writing plus three repairs is well over an hour).


2026-10-26 night (field111): **a process check must not match its own text, nor hide a process whose text contains the
check.** `pgrep -af ... | grep -v pgrep` hid a live chain wrapper because the wrapper's own command line contained
`pgrep -f '[p]reflight.sh live'`; believing it dead, I started the batch by hand and the wrapper started a second driver,
two drivers launched runs on one target, and three runs were wasted. Then a launcher waiting on
`until ! pgrep -f "preflight.sh live"` matched ITSELF and never proceeded. Anchor the pattern on the process's argv start
(`pgrep -f "^/bin/bash /path/run.sh"`, `"^/bin/bash scripts/preflight.sh"`), and before starting a batch by hand list the
full `ps -eo pid,ppid,args` tree, not a filtered one. A prev/ directory a driver moves state into must not already exist:
`mv` into an existing one fails, the prepare step refuses, and the run boots on the leftover tree (a one-cycle false
GOAL_FULFILLED); drivers now refuse an existing prev dir and stop on a failed mv.
