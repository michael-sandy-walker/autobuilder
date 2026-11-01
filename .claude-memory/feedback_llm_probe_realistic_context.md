---
name: feedback-llm-probe-realistic-context
description: "When probing the local model's behaviour on a hand-built prompt, the synthetic context must match the real run distribution or it manufactures false findings"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 80ff4f1f-e032-46c7-bba0-923ec7a61618
---

When you build a synthetic context to probe how the local model responds to a prompt (e.g. a behavioural eval test), the fabricated inputs MUST resemble what the loop actually produces in that situation — otherwise the model's reaction to your unrealistic input reads as a model/prompt "gap" that does not exist in production.

**Why:** 2026-07-24, T2-EP surface-1 behavioural gate. A hand-built rollback scenario paired a `ROLLBACK STATE` block with a unanimously HEALTHY artifact history (three "no notable patterns" summaries + all-ACCEPTED rising scores). The model returned NOOP, looking like it ignored the ROLLBACK_AWARENESS rule. It was a false alarm: a real rollback cycle's history carries the stagnation/rejection that led to the broken build, never an all-healthy history. Corpus check settled it — 31/31 REAL rollback interpretation prompts answered `Status: APPLIED` and named the rollback. Rebuilding the probe with a realistic stagnating history reproduced the correct APPLIED.

**How to apply:** before asserting a model "gap" from a synthetic prompt, (1) pull real instances of that prompt shape from the backup corpus (`[USR]` → `[LLM RESPONSE]` pairs) and read the ground-truth responses; (2) make the synthetic fixture's contradicting/corroborating signals match that real distribution; (3) keep the A/B (change present vs absent) as the load-bearing assertion — it stays valid even when the absolute expectation is mis-set. Ties to [[feedback-calibrate-not-flip]] and [[feedback-replay-over-batches]]. Related: [[project-t4ap-prompt-audit]] (the compaction work these probes validate).

**2026-11-01, the request's settings count too, not only its context.** An offline replay of a landing question passed 3 of 3 and failed live, because the replay omitted the run's `reasoning-effort: none` (same prompt: `none` NONE 4/4, omitted or `low` the right answer 4/4). A replay sends what `LLMClient` sends: model, temperature, reasoning effort, token cap. Compare one logged live answer with the replay's before calling a replay passed.
