---
name: project-local-first-constraint
description: "Autobuilder MUST be able to run fully locally — a hosted-model 'upgrade' (gpt-4o etc.) is off the table by design; the residual model-precision ceiling must be addressed by a better LOCAL model or by mechanism, never by going hosted."
metadata: 
  node_type: memory
  type: project
  originSessionId: 75bbeea6-76b2-4c61-8d0c-716b0b1102aa
---

Running locally is a **hard, foundational constraint** of autobuilder, not a deployment preference (user, 2026-06-16: "The whole point of autobuilder is to be able running it also locally. That would break the whole idea."). The loop runs on a local model via Ollama — `qwen3-coder:30b` at `localhost:11434` (`application.yml:240`); commented alternatives are all local (`deepseek-coder-v2:16b`, `glm-4.7-flash`, `qwen3-30b-tuned`). The `gpt-4o` / `api.openai.com` lines are commented out and stay that way.

**Correction to prior framing:** the roadmap repeatedly cites a "user-owned reviewer/verifier model-upgrade decision" as the high-leverage lever for the residual failure ceiling (verifier false-positives, reviewer over-matching, planner empty-output→amputation, behavioural-criteria misses). I had been treating that as "point the calls at a stronger hosted model." **That reading is wrong for this project** — hosted breaks local-first.

Under the constraint, the residual model-precision ceiling has only two legitimate attack surfaces:
1. **A better LOCAL model** when one is available (bigger/tuned local weights), or per-role local selection (stronger local model for verifier/reviewer only). Still bounded by the user's VRAM/throughput.
2. **Mechanism that extracts more reliability from a fixed weak local model** — self-consistency / multi-sample vote on the verifier and fold-reviewer, decomposing the verification task, retrieving the exact code span before judging, tighter structured prompts, planner empty-output recovery. This is framework work I can do; it respects local-first.

**How to apply:** never propose a hosted-model swap as "the lever." When triaging the residual ceiling, prefer mechanism-side robustness for the precision-critical roles (verifier, reviewer, planner) over any model-hosting move. Treat the roadmap's "model upgrade decision" as "local model improvement OR mechanism," and scrub that hosted implication where it appears. Kin to [[feedback-activity-not-progress]] (the lever is last-mile model precision) and the in-JVM/no-separate-server preference in [[feedback-tts-marytts]].
