---
name: feedback-progress-updates-during-batches
description: "During unattended field batches, post a short update after every run (result, what fired, what it means) -- the user asked for it on 2026-09-28."
metadata:
  type: feedback
---

The user asked "Can you update the progress from time to time?" during the first dossier batch (2026-09-28). Since then: a background watcher that wakes on each run's result line, and a short update per run -- terminal and score, what the mechanism under test did, and one sentence of reading -- with the batch verdict recorded on the ticket at the end.

**Why:** batches run for hours; silence reads as nothing happening.

**How to apply:** arm the per-run watcher right after launching (`until [ $(wc -l < results) -gt $n ]`), not only a watcher for DONE. Background watchers can be reaped under memory pressure -- if one is killed, say so and check the batch directly. Related: [[feedback-monitor-every-terminal]].
