---
name: feedback-substrate-lift-includes-delete
description: "When proposing substrate-lift tickets, bundle the deletion of subsumed scaffolding into the same ticket — never split as a separate T5 successor"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 574059be-9aee-4669-bc3c-282473a6bda2
---

When proposing or shipping a substrate-lift ticket (a new capability that subsumes existing guards or scaffolding), the deletion of what it replaces must be part of the same ticket's scope — not filed as a separate Tier 5 follow-up. If the lift is too big to also include the delete, the lift is too big and should be split; the delete is not what falls out.

**Why:** Confirmed by the user 2026-05-22 after diagnosing the [[project-delete-debt-pattern]]. The mechanism that turns deferred deletions into permanent debt is the priority queue: T5 work stays buried under incoming T1 correctness work, so any delete filed separately as T5 is effectively never going to ship. The architectural intent (capabilities replace scar tissue) is undermined by the priority ordering even when the engineering work itself is correct.

**How to apply:** Whenever proposing a ticket that introduces a new capability or substrate replacing existing guards, include the deletion as part of that ticket's scope. When reviewing existing tickets that split substrate-lift from delete (lift in one tier, delete in T5), flag it. Do not propose substrate-lift work that defers its own deletion to a successor ticket.
