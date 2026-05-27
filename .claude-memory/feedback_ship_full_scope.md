---
name: feedback-ship-full-scope
description: "Ship a new capability in one scope: build it, rewire every existing caller, and delete the now-dead code — never split the migration or delete into a follow-up ticket"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 574059be-9aee-4669-bc3c-282473a6bda2
---

When a ticket introduces a new capability that is meant to replace existing scattered code, the same ticket must do three things in one scope: (1) build the new capability, (2) change every existing call site to use it, and (3) delete the now-dead code. Do not ship step 1 alone and file steps 2+3 as a follow-up ticket.

**Why:** Confirmed by the user 2026-05-22 and clarified again 2026-05-22 (second session). The failure mode is two-step. First, if the call-site migration is deferred, the new capability becomes a sixth option alongside the existing five rather than replacing them — both the new and old paths run. Second, the leftover "migrate + delete" ticket lands as Tier 5 quality work, stays buried under incoming Tier 1 correctness work, and never reaches the top of the queue. See [[project-delete-debt-pattern]] for the accumulated evidence. Earlier framing used the term "substrate-lift" for the same pattern; that jargon was dropped 2026-05-22 because it obscured rather than clarified the rule. Renamed from `feedback-capability-ticket-includes-delete` on 2026-05-27 — the old slug was too abstract for future readers; `ship-full-scope` names the rule directly.

**How to apply:** When proposing or reviewing a ticket that introduces a new capability subsuming existing code, confirm the ticket scope includes (a) rewiring every existing caller onto the new capability and (b) deleting the now-dead code paths. If either is being split into a follow-up ticket, push back — that's the mechanism by which the codebase carries scaffolding the architecture has already moved past. If the work is too big to fit one ticket, split it into smaller capability-plus-migration tickets, not capability-now + delete-later.
