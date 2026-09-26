---
name: feedback-cut-flat-runs-short
description: the user wants unpromising runs stopped early, by a rule stated in advance (scripts/early_stop.sh), rather than every run using its full cap -- but NOT at ~30-40 min on firestarter (use patience 30, minimum 45 there)
metadata:
  type: feedback
---
Start `scripts/early_stop.sh <target>` beside every field run: it pauses a run whose yardstick (scored on the newest end-of-cycle checkpoint) has not improved for 20 minutes after a 25-minute minimum, and a closure refusal with new content restarts the clock.

**Why:** 2026-09-25 the user asked "is there a way to accelerate this if there is not a promising result?" -- runs 3 and 4 had each spent their last 25-30 minutes flat. With the rule, ten runs that night ended at 30-50 minutes instead of 60, and the one that completed (run 14) was never cut.

**How to apply:** state the rule before the run and do not tune it while watching one; when a stop looks wrong, fix the rule from evidence (it was corrected three times: score checkpoints not the live tree, restart on a refusal, only on a NEW refusal). Related: [[project-run-lifecycle-runbook]], [[feedback-waiter-fires-on-loop-terminal]].

**2026-09-26 correction from the user: "30 minutes is too soon."** Said while a firestarter run sat at 22/23 at the 30-minute score. Under the defaults (25 min minimum, 20 min patience) a flat score from minute 20 pauses the run at about 40 minutes, but firestarter's true completions land at 25-34 min and later. For firestarter use `scripts/early_stop.sh firestarter 10 30 45` (patience 30, minimum 45), which in practice lets a 60-minute run use its full cap unless it is flat from very early. The defaults stay for the small change targets, whose completions take 2-10 minutes. Do not shorten these while watching a run.
