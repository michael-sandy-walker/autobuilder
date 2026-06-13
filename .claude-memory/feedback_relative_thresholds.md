---
name: feedback-relative-thresholds
description: "Triggers/limits in autobuilder must be relative to the environment, not fixed numbers — stated 2026-06-13 for the recurrence-escalation ticket, applies project-wide."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 98711c7f-7548-4e15-8d1e-214c80d770d5
---

When designing a trigger, cap, or escalation threshold, do not introduce another fixed constant ("after 3 failures", "max 5 entries", "20 cycles"). Make it relative to the environment the run actually exhibits.

**Why:** fixed numbers encode a worldview the loop cannot correct (same failure mode as hardcoded ecosystem data, see [[feedback-no-hardcoded-ecosystem-data]]); they are tuned to one run profile and silently mis-fire on others (T2-EC: leaf-count consolidation trigger fires on a fresh initial decomposition at cycle 2 with 0 merges — the number was fine, the missing *relativity* to run age/merge history was the bug).

**How to apply:** derive the threshold from observable run state — distinct-shape counts, ratios to merges/cycles elapsed, comparison against the run's own baseline rate, or percentile-of-history — rather than an absolute count. A constant is acceptable only when it mirrors a *measurable environmental fact* (context-window token budget, available memory, probe wall-clock) read from the environment; a constant encoding a *judgment about behavior* ("8 is enough", "3 retries") is the debt even when wrapped in `min()`. T2-DV's `min(8, distinctShapes)` is a half-step, not the standard (user pushback 2026-06-13): the direction was right (binding term became data-driven) but the 8 should itself derive from the diagnostics block's share of the prompt token budget. First application: the planned recurrence-aware escalation ticket for repeated build-failure shapes (escalate when one shape dominates the recent failure mix, not "after N occurrences"); fold the 2026-06-13 night-run data in before filing.
