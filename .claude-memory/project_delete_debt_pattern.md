---
name: project-delete-debt-pattern
description: "Substrate-lift deletions get filed as T5 follow-ups and never reach the front of the queue, causing scaffolding to accumulate even as capabilities replace guards"
metadata: 
  node_type: memory
  type: project
  originSessionId: 574059be-9aee-4669-bc3c-282473a6bda2
---

Substrate-lift work in autobuilder consistently ships the new capability but defers the deletion of subsumed scaffolding into a separate Tier 5 ticket. T5 work then stays buried under incoming Tier 1 correctness work, so the deletions never reach the front of the queue — and the codebase carries more scaffolding than the current architecture actually requires.

**Why:** Diagnosed 2026-05-22 during a reflective conversation about whether autobuilder is making real progress toward autonomy. Concrete evidence in the active roadmap at that date: T5-O (drop Maven-specific stderr summariser, unlocked when T4-AN-4 shipped 2026-05-22), T5-W (migrate skeleton resolver away from Kind-dispatch to adaptive Classification), T2-DG ("subsumed by T2-DD shipped 2026-05-20" — unresolved), the `autobuilder.build.enabled` TODO at `application.yml:50`, the `PlannerAgent` `PLANNING_THROTTLED` commented-out block, and the missing T4-BB upgrade trigger criterion. Each one is a substrate lift that shipped without its delete.

**How to apply:** When proposing or reviewing substrate-lift work, surface the question "what scaffolding does this make redundant, and is that deletion bundled into this ticket?" Flag any pattern where the deletion is being split into a separate T5 successor — that's the mechanism by which the debt becomes permanent. See [[feedback-substrate-lift-includes-delete]] for the proposed rule the user adopted. Related framework: [[project-three-disciplines-lens]] (this is discipline #3 failing).
