---
name: feedback-roadmap-is-the-queue
description: "Sessions pick work from roadmap-overview/roadmap.md ONLY. Direction/plan notes are rationale behind tickets, never a parallel queue — never list them under Roadmap in MEMORY.md, never justify 'what's next' from a plan narrative. Scope: governs the product queue only; the development-process axis is a separate, real queue at .planning/ROADMAP.md — see the Orientation section of MEMORY.md."
metadata:
  node_type: memory
  type: feedback
---

The user objected (2026-07-14) to `project_reliability_plan.md` living under the Roadmap section of MEMORY.md: *"We don't have the roadmap for nothing. Now every session feels more and more like just improvising again."* Second correction of the same class — 2026-07-13 already established that directions "get NO automatic priority; the tiers decide order."

**Why:** a plan/direction note under the Roadmap heading becomes a competing queue. Sessions then navigate by its narrative ("Theme 2 is next", "the plan's note says study X") instead of by the filed tiers — which is improvisation with a paper trail. The roadmap files exist precisely so that "what's next" has one answer surface.

**Scope (2026-09-08, ORIENT-03):** this rule governs the product queue. There is a second surface, the development-process axis, at `.planning/ROADMAP.md` with requirements in `.planning/REQUIREMENTS.md` — a real queue for its own question, not a direction note in the sense this rule targets. The boundary between them, and what each surface is for, is recorded once, in the Orientation section of `MEMORY.md`, and is deliberately not restated here. Nothing changes for the product queue: it still answers what Autobuilder gets built next from `roadmap-overview.md` and `roadmap.md` only.

**How to apply:**
- MEMORY.md's Roadmap section lists ONLY `roadmap-overview.md`, `roadmap.md`, `tier_definitions.md`. Direction/rationale notes go under Project insights.
- "What's next" answers come from the active queue in [[roadmap-overview]] (roadmap.md wins on disagreement) — cite tickets, not themes.
- A direction note earns execution ONLY by filing a ticket into its tier first (as T4-CA/T5-BE/T4-CB were). If a candidate isn't worth filing, it isn't worth doing.
- Related: [[feedback-ticket-definition-of-done]] (close on DoD; residuals are NOTES, not hovering "mid-flight" status).
