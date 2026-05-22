---
name: project-three-disciplines-lens
description: "Three-discipline frame for evaluating whether autobuilder is gaining autonomy vs. accumulating scaffolding — failures→priors, capabilities-replace-scar-tissue, delete-subsumed"
metadata: 
  node_type: memory
  type: project
  originSessionId: 574059be-9aee-4669-bc3c-282473a6bda2
---

Frame for evaluating whether autobuilder is genuinely getting more autonomous over time vs. just accumulating guards. Three disciplines:

1. **Failures become priors, not patches.** When the LLM repeats a class of mistake, the lesson belongs in the prompt, heuristic, or policy — not in a guard around the output. Status as of 2026-05-22: partial. Hand-coded heuristics encode failure-derived rules (`ScoreRegressionHeuristic`, `ProblemReductionHeuristic`, `NoMeaningfulChangeHeuristic`, `BuildErrorClassifier`). The missing piece is a learner that reads `behaviour-*.ndjson` and proposes new heuristics — captured by open ticket T4-E ("Dynamic heuristics: LLM-proposed evaluation rules").

2. **Capabilities replace scar tissue.** A guard is scar tissue, a capability is muscle. Status as of 2026-05-22: strong — this is the discipline that's actually working. Chain L shipped four resolver modes each retiring a class of would-be guards; T4-AN-4 replaced Maven-specific patterns with category-based dispatch.

3. **Actively delete what gets subsumed.** Status as of 2026-05-22: weak — see [[project-delete-debt-pattern]] and the rule in [[feedback-substrate-lift-includes-delete]].

**Why:** Crystallised 2026-05-22 in a reflective conversation about whether autobuilder is making real progress toward autonomy or just running a patch treadmill. The user engaged with the frame and validated its application to the current project state.

**How to apply:** When the user asks "are we making progress?" or surfaces concern about a patch treadmill, evaluate against these three. When proposing new work, check which discipline it serves — a guard-only ticket that neither raises a prior nor lifts a substrate should be questioned. Re-evaluate the status notes periodically; they reflect a snapshot, not a steady state.
