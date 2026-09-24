---
name: feedback-waiter-fires-on-loop-terminal
description: a run waiter must fire when the LOOP ends (terminal cycle result in the app log), not only on preflight's final PREFLIGHT line minutes later
metadata:
  type: feedback
---
Arm run waiters on the loop's own terminal line (`Result: GOAL_FULFILLED|GOAL_EXHAUSTED` in `data/<target>/logs/autobuilder.log`, or the app process exiting) AND on preflight's final `PREFLIGHT:` line, reporting at the first.

**Why:** 2026-09-24 run 12: the loop ended 19:44:41, preflight's scoring and archiving printed `PREFLIGHT:` at 19:46:52; the user saw the finish in the interface and asked "Why don't you notice, that the run finished?" Twice the same day the user noticed a finished run before I did.

**How to apply:** e.g. `until grep -qE "Result: GOAL_(FULFILLED|EXHAUSTED)" $APPLOG || grep -q "PREFLIGHT:" $LAUNCHLOG; do sleep 15; done`, then report the loop's terminal at once and the score when preflight lands. Related: [[feedback-monitor-every-terminal]], [[project-run-lifecycle-runbook]].
