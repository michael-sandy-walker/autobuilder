---
name: feedback-ticket-tiering
description: How the user tiers roadmap tickets — distinguishing T1 (correctness) from T2 (steering) and T4 (new systems)
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 70dbab0e-fa97-4b9f-a34e-e71c45408cb6
---

When filing roadmap tickets, classify by *mechanism*, not by how bad the outcome looks.

- **A policy/heuristic that does exactly what it's coded to do, but makes a dumb decision → T2 (steering quality), not T1.** Even when the outcome is "wrong output" (e.g. the loop declares a goal COMPLETE on an unfinished build via a ratio threshold). The test: *is the code doing what it was coded to do?* If yes but the policy is dumb → T2. T1 is reserved for genuine logic bugs — broken code, wrong state, a real defect in the implementation (e.g. T1-BH was correctly T1: an actual logic error in `deriveCycleResult`).
- **A missing detection/validation capability ("more intelligence" — a critic the system never had) → T4 (new systems), not a bug.** Even when its absence let broken output ship. No scaffold exists → greenfield → T4.
- **Don't over-prioritize completion-semantics / false-completion tickets.** The user considers them lower-value than they look ("there are more important things"). A missing-capability ticket whose absence ships broken code *can* warrant priority within its tier (T4-BA was prioritized).

**Why:** On 2026-05-22 the user re-tiered two tickets I filed as T1. T1-EP (ratio-based root completion firing too early) → T2-DP: "not a T1, shouldn't be prioritized." T1-EQ (package fragmentation undetected) → T4-BA: "technically not a bug, it's just more intelligence — but needs priority." I had over-weighted the severity of the outcome ("false GOAL_FULFILLED ships broken code") and mis-mapped it to correctness.

**How to apply:** Before assigning a tier, ask whether the implicated code is *misbehaving* (T1) or *behaving as designed but unintelligently* (T2), or whether the fix is a *capability that does not yet exist* (T4). Match urgency to the user's view, not to outcome drama. `roadmap.md`'s "Tier definitions" table is the source of truth for the T1–T5 criteria.
