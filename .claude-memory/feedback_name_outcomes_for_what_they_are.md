---
name: feedback-name-outcomes-for-what-they-are
description: "Labels for run outcomes and states must say what happened -- a run I stop is \"stopped\", not \"died\"; name things for what they are"
metadata:
  node_type: memory
  type: feedback
  originSessionId: 79f58bde-7f77-445d-8d0d-850f6f537ed7
  modified: 2026-10-17T16:37:46.401Z
---

When I stop a field run myself, its recorded terminal must say so (`jvm-stopped`), not `jvm-died`. The user, 2026-10-17, after field77 was stopped by me and preflight recorded `terminal=jvm-died`: "call the stopped jvm jvm-stopped instead of jvm-died if you stop it, etc. Generally, call it what it is."

**Why:** a label that names a different event (a crash vs a deliberate stop) misleads every later reader of the record -- run tallies, baselines over the archive, and the next session reading results.txt.

**How to apply:** stop runs through the mechanism that records the stop (my interpretation: `scripts/stop_run.sh`, which marks the stop before killing the JVM, so preflight records `jvm-stopped`); never kill the JVM bare. The same goes for any status word, log line or report I write: describe the actual event, not a near neighbour. Related: [[feedback-neutral-vocabulary]], [[project-run-lifecycle-runbook]].
