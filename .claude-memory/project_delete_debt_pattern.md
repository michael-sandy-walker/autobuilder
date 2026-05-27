---
name: project-delete-debt-pattern
description: "Tickets that ship a new capability but defer the caller-migration and delete into a Tier 5 follow-up leave the old scattered code alive — the follow-up never reaches the top of the queue, so scaffolding accumulates"
metadata: 
  node_type: memory
  type: project
  originSessionId: 574059be-9aee-4669-bc3c-282473a6bda2
---

Autobuilder consistently ships new capabilities but defers the work of rewiring existing call sites onto the new capability — and deleting the now-redundant code — into separate Tier 5 follow-up tickets. The follow-ups then stay buried under incoming Tier 1 correctness work, so the caller migration and deletion never reach the front of the queue. Result: the new capability exists, but the old scattered code keeps running alongside it, and the codebase carries more scaffolding than the architecture currently requires.

**Why:** Diagnosed 2026-05-22 during a reflective conversation about whether autobuilder is making real progress toward autonomy. Concrete evidence in the active roadmap at that date: T5-O (drop Maven-specific stderr summariser, unlocked when T4-AN-4 shipped 2026-05-22), T5-W (migrate skeleton resolver away from Kind-dispatch to adaptive `Classification`), T2-DG ("subsumed by T2-DD shipped 2026-05-20" — unresolved), the `autobuilder.build.enabled` TODO at `application.yml:50`, the `PlannerAgent` `PLANNING_THROTTLED` commented-out block, and the missing T4-BB upgrade trigger criterion. Each one is a capability that shipped without its caller-migration + delete.

**How to apply:** When proposing or reviewing work that introduces a new capability, surface the question: "what existing code is this meant to replace, and is the rewiring + deletion bundled into this ticket?" Flag any pattern where rewiring or deletion is being split into a separate Tier 5 successor — that's the mechanism by which the debt becomes permanent. See [[feedback-ship-full-scope]] for the rule the user adopted. Related framework: [[project-three-disciplines-lens]] (this is the third discipline failing).
