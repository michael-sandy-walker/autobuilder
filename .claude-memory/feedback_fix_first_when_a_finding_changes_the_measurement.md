---
name: feedback-fix-first-when-a-finding-changes-the-measurement
description: "When a finding changes what a running field batch measures, stop the batch and fix first -- the user chose this twice (2026-09-28, 2026-09-29); never let a batch build from uncommitted code."
metadata:
  type: feedback
---

When a defect found mid-batch changes what the batch is measuring (a trigger the batch evaluates, a bookkeeping the yardstick reads), stop the batch cleanly and fix it before measuring further. The user chose this twice: "Let's do the latter. It could lead to something more useful more sooner than later" (2026-09-28, the "0 done" defect behind the flat-root trigger) and "a" (2026-09-29, the planner pruning finished work).

**Why:** a batch on a trigger that is about to change measures nothing the next build keeps; the fix is usually cheaper than the hours of runs.

**How to apply:** state the finding and the two options (stop and fix / let the batch finish) with a recommendation, then act. Stop cleanly: kill the driver first, then the early_stop watcher, then POST /autobuilder/pause and let preflight score and archive. And never edit or compile `src/` while a batch's driver can still start a run -- each run builds its jar from the working tree, so an uncommitted change silently enters the batch (caught once on 2026-09-29 in the driver's 30-second gap). Related: [[feedback-never-edit-a-running-script]], [[feedback-no-heavy-mvn-beside-a-live-run]].
