---
name: feedback-ticket-tiering
description: Recurring tier-classification drift patterns and the decision procedure that catches each — canonical tier definitions live in tier_definitions.md
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 70dbab0e-fa97-4b9f-a34e-e71c45408cb6
---

When filing roadmap tickets, classify by *mechanism*, not by how bad the outcome looks. **Canonical definitions and decision procedure: [[tier-definitions]] (`memory/tier_definitions.md`).** This memory owns the *application layer* on top of that — the recurring mis-classifications and how to catch each one before they re-cause drift.

**Recurring mis-classifications (and the test that catches each):**

1. **Outcome-severity → T1 drift.** A policy/heuristic doing exactly what it's coded to do, but making a dumb decision → **T2**, not T1, even when the outcome is "wrong output." The test: *is the code doing what it was coded to do?* If yes but the policy is dumb → T2. T1 is reserved for genuine logic bugs (e.g. T1-BH was correctly T1: an actual logic error in `deriveCycleResult`).
2. **"Capability completion" → T4 drift.** Extending an existing feature with new code is **T3**, not T4. T4 is *greenfield* — no scaffold. If a class, registry, or service already exists and the ticket extends its behaviour end-to-end, that's T3. T4-BE/BF/BG/BH/BI all opened with the phrase *"capability completion of [existing capability]"* — by the canonical definition, those should have been T3. The slip happened because they involved new classes/records; "new code" felt system-like.
3. **Refactor → T5 drift.** A rename or abstraction-completion that doesn't change behaviour is **T3** if it activates a partially-built feature; **T5 only** if it's about debuggability, noise, or resource use. T5-Z (`WellKnownPackageRegistry` jargon removal) was re-tiered to **T3-BU** on 2026-05-27 because the registry's abstraction is the partially-built feature being completed, not an observability concern.
4. **Type-safety improvements → T1 drift.** A change that makes the compiler enforce something the code already does correctly is **T5**, not T1 — no observable bug, just compile-time safety / debuggability. T1-EV (`MarkCompletedOutcome` → sealed type) was re-tiered to **T5-AC** on 2026-05-27 for this reason.
5. **Missing detection/validation capability → T4 if no scaffold exists.** "More intelligence" the system never had — a reviewer, a new validator. If no scaffold exists → T4. If a scaffold exists but isn't fully wired → T3.

**Don't over-prioritize completion-semantics / false-completion tickets.** The user considers them lower-value than they look ("there are more important things"). A missing-capability ticket whose absence ships broken code *can* warrant priority within its tier (T4-BA was prioritized).

**Why this memory was rewritten:** On 2026-05-22 the user first re-tiered T1-EP → T2-DP and T1-EQ → T4-BA, and the original version of this memory only defined T1, T2, T4 — leaving T3 and T5 undefined. That gap caused months of drift: capability completions slid into T4 because T3 was effectively invisible, and refactors slid into T5 because no other bucket was offered. On 2026-05-27 the user surfaced the drift ("What happened to T3? T4 was 'new systems' originally. Now you skipped T3 for a very long time. T5 was more 'monitoring/quality'."). Same session: tier definitions were extracted to [[tier-definitions]] as the canonical single source of truth (so the roadmap files and this memory all link to the same table instead of carrying drift-prone copies), and this memory was scoped down to own only the mis-classification patterns.

**How to apply:** Use the decision procedure in [[tier-definitions]] (walk T1→T5 in order, stop at first match). Outcome severity affects *priority within tier*, never *tier choice*.
