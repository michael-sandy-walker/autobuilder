---
name: feedback-capability-gap-not-prohibition
description: "When the loop hits a current limitation, model it as a revisable capability gap — not a permanent prohibition, not prose predicting the result, not a TTL'd stored fact."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 91c7825d-a207-496d-bdbb-8476485de5b1
---

When a deterministic gate rejects an action the loop can't currently perform (e.g. T4-BH package-fragmentation rejecting a type relocation), three reflexes are all wrong and were corrected during T2-DZ:

1. **Don't feed the LLM prose predicting the outcome.** "The build forbids X, so this will be rejected on every retry" is persuasion, not mechanism — the model must read, believe, and choose to comply. Instead the *system* gains a function: mechanically recognise the situation and act (here: park the subgoal). Surface it to other agents only through existing channels (the GIVEN_UP resolution shows up in abandoned-goal surfaces), not a bespoke coaching prompt block.

2. **Don't encode a current capability limit as a permanent prohibition.** No "structurally impossible / categorically infeasible / never". A gate rejecting something today means *the current capability set can't do it*, not that it's impossible in principle — a future capability (T4-BL accept-by-refactor) may unlock it. Bake in a ban (prose OR a hard guard that drops the action) and that future capability has to fight and unwind your scar tissue. Model it as a **revisable capability gap**: name the missing capability, record it as recoverable metadata, give the work up via `transitionGoal(GIVEN_UP)` so reactivation machinery can revive it.

3. **Don't TTL-store a fact you can derive live.** The one-cycle `PackageFragmentationEvidence` carry-forward is right for a *per-cycle reactive hint* (evolution placement correction) but wrong for a *durable fact*: the observed run re-proposed at cycles 11/12/13/**17**, so a one-cycle window misses the 13→17 gap. Where a fact is recomputable from the tree (the gate already scans it every cycle), derive it live — nothing to TTL, nothing to go stale, no frozen worldview. See [[feedback-no-hardcoded-ecosystem-data]].

Also: scope such records **per-entity at the right granularity** — T2-DZ keys the gap by simple type name → its exact (sub-)package (`com.example.demo` ≠ `com.example.demo.member`), never a package-wide rejection that would block unrelated new types.

**Why:** this is the [[project-three-disciplines-lens]] "capabilities-replace-scar-tissue" discipline made concrete, and the [[project-introspection-as-north-star]] "self-model it consults" — a revisable gap record is part of that model; a hardcoded ban is not.

**How to apply:** when tempted to write "do NOT do X / X is impossible" (in a prompt or a guard), stop. Ask: is X impossible, or just unsupported now? Name the missing capability, record the gap revisably, and let the existing give-up/reactivate path park the work. Verify behaviour actually changes per [[feedback-verify-diff-is-substantive]].
