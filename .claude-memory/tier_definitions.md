# Tier definitions — canonical

Single source of truth for the T1–T5 tiers used in `roadmap.md` and `roadmap-overview.md`. Linked from `feedback_ticket_tiering.md`. **If anything else carries a copy of this table, that copy is a bug — replace it with a link.**

| Tier | Name | Criterion |
|---|---|---|
| **1** | **Correctness** | Something is observably broken or produces wrong/harmful output. The loop misbehaves, wastes significant resources, or produces incorrect state. Fix unconditionally. |
| **2** | **Steering quality** | The loop runs correctly but cannot steer itself well — signals don't flow where they should, agents repeat mistakes they've been penalized for, orchestration is blind to its own history. Makes the system *dumb*, not broken. |
| **3** | **Feature completion** | Partially-built features that exist in code but aren't wired end-to-end. The scaffold is there; work remains to activate it. |
| **4** | **New systems** | Greenfield — no scaffold exists yet. Requires design before implementation. |
| **5** | **Quality / observability** | Doesn't change behavior but improves debuggability, reduces noise, or improves resource usage. |

**Decision procedure** (walk down in order, stop at first match):

1. Is the code misbehaving / producing wrong output? → T1.
2. Does the code run correctly but make a dumb decision? → T2.
3. Does a scaffold exist but the feature isn't wired end-to-end? → T3.
4. Is this greenfield with no scaffold? → T4.
5. No behavior change, just debuggability / noise / resources? → T5.

Outcome severity affects *priority within tier*, never *tier choice*. See `feedback_ticket_tiering.md` for the recurring mis-classification patterns and how to catch them.
