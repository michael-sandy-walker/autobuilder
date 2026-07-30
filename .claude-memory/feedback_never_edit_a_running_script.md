---
name: feedback-never-edit-a-running-script
description: "Never edit preflight.sh (or any shell script) while a run is executing it — bash reads scripts incrementally, so edits land half-applied and silently"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: df54f45e-184f-4e0a-8554-38e62ac1303c
  modified: 2026-07-30T19:26:32.858Z
---

While a live run is in flight, do not edit `scripts/preflight.sh` or any other shell script the running process is executing. Prepare the change in the scratchpad and apply it after the run terminates.

**Why:** bash reads a script incrementally from a file offset rather than loading it whole. Editing it mid-execution shifts the offsets, so the running instance can execute a mixture of old and new text, or skip a region entirely — with no error. On 2026-07-30 I added `criteria_executable`, `criteria_authored`, `clauses_closed_vs_control` and `median_cycle_seconds` to preflight's summary block during run `2026-07-30_18-39-00`. The run completed and reported `PREFLIGHT: PASS`, and **none of the four fields appeared in `summary.txt`** — not even as empty values, which is what an executed `echo` would have produced. The block was never reached. Nothing warned.

This is the same family as the process-matching traps already recorded in [[project-run-lifecycle-runbook]]: an operation that looks harmless because the failure is silent.

**How to apply:** while a run is live, restrict edits to Java sources and tests (the jar is already built, so they cannot affect it), to memory and roadmap files, and to Python scripts the run does not invoke mid-flight. For anything preflight itself reads or executes — the script, and by extension `watches.json`, which it evaluates at the end — write the intended change to the scratchpad and apply it once the JVM is down and scoring has finished. When a summary field is expected and absent, suspect this before suspecting the extraction logic: an absent line means the code never ran, whereas a wrong line means the code ran and the logic is wrong.
