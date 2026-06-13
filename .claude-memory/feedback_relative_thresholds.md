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

**How to apply:** derive the threshold from observable run state — distinct-shape counts, ratios to merges/cycles elapsed, comparison against the run's own baseline rate, or percentile-of-history — rather than an absolute count. A constant is acceptable only when it mirrors a *measurable environmental fact* (context-window token budget, available memory, probe wall-clock) read from the environment; a constant encoding a *judgment about behavior* ("8 is enough", "3 retries") is the debt even when wrapped in `min()`.

**Shipped 2026-06-13 (commits `9ca1d06`, `7c4c686`):** the two examples above are now done the right way, both verified at suite 1560.
- T2-EC: deleted `fresh-tree-grace-cycles: 5`; the leaf-count trigger now waits for a merge OR one observed cycle per active leaf (grace scales with the tree it measures).
- T2-DV: deleted `MAX_ENTRIES = 8`; new `PromptTokenBudget` (`protocol/PromptTokenBudget.java`) is the codebase's first prompt-budget primitive — deployed context window minus response reserve, read from `llm.api.{context-window-tokens,response-reserve-tokens}`. `CompileDiagnosticPromptBlock` fills by severity until the headroom remaining at its assembly point is exhausted. The chars/4 token estimate must be used on both sides of any comparison so the systematic error cancels.

Next application: the recurrence-aware escalation actuator (T2-EE, `454921b`) re-pointed the same-failure streak at pre-merge probe rejections — escalation keys on shape identity, no fixed N. Any further escalation tuning follows the same rule.
