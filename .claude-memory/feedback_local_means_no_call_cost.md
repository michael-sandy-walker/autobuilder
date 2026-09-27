---
name: feedback-local-means-no-call-cost
description: the loop runs on a local model, so LLM call/token cost is not a constraint when designing or picking work; wall-clock time on the box still is
metadata:
  type: feedback
---
Do not design for, or prioritise by, the cost of LLM calls, tokens or model "budget". The loop runs on a local model;
calls cost nothing but time. What still matters: wall-clock time (runs are capped, the box is shared with Docker and the
model), prompt size against the model's context window (T4-AP), and memory on the box.

**Why:** 2026-09-27 the user, on a ticket framed around the loop's observation budget: "we don't need to worry about
the budget the loop uses, because it's local."

**How to apply:** when a ticket or design argues from call cost ("saves N calls", "budget for looks"), re-read it for
what remains without that argument -- correctness, time, context size -- and judge it on that. Related:
[[project-local-first-constraint]], [[project-t4ap-prompt-audit]].
