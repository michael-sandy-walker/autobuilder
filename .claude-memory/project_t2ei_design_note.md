---
name: project-t2ei-design-note
description: "T2-EI 'the right way' design note (2026-06-24): structure-first goal closure. Synthesises the rule-interaction map + the depth-first experiment + the user's design refinements into principles (structure over serialization, state-driven generation, hierarchical earned closure, revisable skeleton, query-not-inject) + a concrete model + open questions + a proposed first slice. Read with [[project-t2ei-rule-interaction-map]]."
metadata:
  node_type: memory
  type: project
---

**T2-EI design note — structure-first goal closure ("depth-first, the right way").** A design to poke holes in BEFORE coding, same discipline that made the rule map pay off. Built from: the rule-interaction map ([[project-t2ei-rule-interaction-map]]), the 2026-06-24 depth-first experiment, and the design dialogue with the user. Honours [[project-local-first-constraint]] (no hosted model — mechanism is the lever), [[project-introspection-as-north-star]] (a loop that consults a self-model of its state before acting), [[feedback-activity-not-progress]] (source structure from the symbol/grammar model, not string-scraping), [[feedback-relative-thresholds]], [[feedback-no-defensive-recast]].

## How we got here (scope evolved — and that's fine)
Started as "get one ticket (T2-EI) done." The rule map found FULL_COVERAGE vs the 8-cap fight and the empty-backlog completion trap. The experiment (prompt-directive depth-first) **confirmed the working-set mechanism** (max-active 11→3, backlog-cap-throttle 11→0, cfs/cyc 4.9→0.74, OK-ratio 0.55→0.92, 18 merges/19 cyc, model OBEYED the directive) but **died at cyc 19** to a closure brittleness. Diagnosing that surfaced a deeper, more principled redesign. The user's steer: we're flexible; the skeleton/plan is not the ultimate source of truth — it evolves (mirrors this very session).

## What the experiment settled (and my retractions)
- **Confirmed:** a coherent, smaller working set sharply reduces cross-file churn and keeps the build green. The local model CAN follow structure when the working set is small.
- **Retracted #1:** the scope guard did NOT flag the fatal leaf "because it's a test." It flagged a **mutation-vs-subgoal mismatch** — the subgoal was "write a test," the evolver's diff was domain/repo refactoring. Tests are not treated as out-of-scope. "Fix the spurious test subgoal" was the wrong framing; dropped.
- **Retracted #2:** "depth-first = one resource at a time" was crude. The benefit is **structural scoping of the working set**, not serialization. Nothing wrong with several resources in flight.
- **Found (worse than first stated):** a leaf has **no real per-leaf retry budget** — it is marked GIVEN_UP the first cycle `GoalExhaustionDetector` declares it exhausted (the `1/5` in the log is the *root-runaway* counter, not per-leaf retries). And closure is **flat**: `autoCompleteRootIfSatisfied` treats ANY GIVEN_UP descendant (however deep) as a direct root blocker, ignoring the tree. A half-built hierarchical mechanism exists (`exhaustParentIfStuck`, T2-AL) but closure doesn't use it.

## Principles (the model we converged on)
- **P1 — Structure over serialization.** The lever is a *coherent working set*, achieved by **structural scoping**, not by capping to one resource. Separate **structure/skeleton planning** (what nodes exist and how they relate) from **resource generation** (filling a node with real code). Currently the planner conflates them. When filling a node, the orchestrator surfaces only the *structurally related* context (the node's resource group, its dependencies) — so the working set stays coherent regardless of how many resources exist in total.
- **P2 — State-driven generation, not prompt-obedience.** The orchestrator (executive) surfaces the states the system is currently in; the LLM (generative) responds to that state. Control flows via *information*, not via directives we hope the model obeys ([[project-t4bq-naming-consistency-ab]]: it ignored the naming directive; it happened to obey depth-first this time — don't build on luck). This is a concrete step toward [[project-introspection-as-north-star]] — the loop consulting a model of its own state.
- **P3 — Hierarchical, earned closure.** A leaf failure is judged against its **parent's** requirements first. If the parent meets its own criteria *without* that child (the child was extra/optional — e.g. an unrequested test), absorb the failure locally; it never reaches the root. Escalate one level at a time; the **root** fails only when a **required** resource genuinely cannot complete. Give-up is **earned** via a real, relative retry budget (tuned at `GoalExhaustionDetector` / the exhaustion path), not a first-strike.
- **P4 — Revisable skeleton, not frozen truth.** Plan a basic structural skeleton up front, but treat it as a living scaffold the loop revises as it learns — NOT an immutable source of truth. (Like this session: began narrow, restructured as understanding grew.)
- **P5 — "What's next?" is a query, not "that's next!".** Deterministic traversal over the (revisable) structure answers "which nodes are unbuilt/incomplete?" — no closure-time LLM that *mints* paraphrased subgoals (the coverage gate's brittleness: non-deterministic, re-introduces synonyms, false-positived 18/18). Re-arming the coverage gate for depth-first was the nearest hook, not the right one.

## Concrete model
1. **Structure layer in the goal tree.** root → resource/structure nodes → leaves. The skeleton is established once and **revised** thereafter (P4). Resource nodes can be many and concurrent (P1) — no artificial serialization.
2. **Two separable activities** (P1): a *structural* pass maintains the skeleton; a *generative* pass fills a node. The generative prompt is scoped to the node's structural neighbourhood, keeping the working set coherent.
3. **Recursive/hierarchical closure** (P3): a node is satisfied iff its *required* children are satisfied. Optional/extra children failing is absorbed at the node. Replaces the flat "any descendant blocks root."
4. **Required vs extra children.** A child is *required* if it traces to a named requirement in the root spec; *extra* if the planner/analysis added it speculatively (e.g. the unrequested test). Extra failures don't block.
5. **Earned leaf give-up** (P3): a per-leaf retry budget before GIVEN_UP, relative to the run's environment ([[feedback-relative-thresholds]]).

## Parking lot (future, user-flagged)
- **Cross-node adoption / parent starvation.** An orphaned or failed child that nonetheless satisfies *another* node's criteria could be **adopted** by that node instead of discarded — handling "parent starvation." Mentioned for future development, not this pass.

## Decisions taken (2026-06-25)
- **D1 — Required-vs-extra classification: origin-based first** (planner first-decomposition = required; later/nested/analysis-injected = extra). Light, no new machinery. **Criteria-traceability-to-spec is the follow-up = T4-BT** (a new classification capability → T4 by mechanism-tiering, [[feedback-ticket-tiering]]).
- **D2 — Structure store: hybrid.** *Intended* structure = revisable goal-tree nodes (reuse the existing tree + the planner's append/remove as the "structural pass"; this is the precondition hierarchical closure recurses over, and P4-revisable for free). *Actual/built* structure = derived **live** from the symbol model, **never stored** (ground truth on disk; storing it is the stale-fact trap — [[feedback-capability-gap-not-prohibition]], [[feedback-activity-not-progress]]). "What's next" = intended − actual, computed fresh (P5). A node carries only intent + criteria, **never** "am I built." Keeps the first slice unblocked: hierarchical closure runs on existing parent nesting; the *formal* resource layer is a later slice.

## Open questions (still open — poke here)
1. **Skeleton seeding** (less critical now it's revisable, P4 + D2): deterministic spec-parse of the seed vs a single up-front LLM "list the structure" call, then revised. How much do we trust spec-parsing? (D2 settles *where* intent lives; this is *how it's first populated*.)
2. **Where exactly does working-set coherence come from** under P1 if not serialization — structurally-scoped context windows? per-resource symbol grounding? Needs to be made concrete so it's testable on the harness.
3. **Retry budget relative to what** — stagnating cycles vs the node's sibling progress vs run-wide merge cadence?

## Proposed first slice (smallest principled step, NOT a decision yet)
Make **closure hierarchical + give-up earned** (P3) behind the existing `depth-first-decomposition` flag, and **drop the coverage-gate re-arm** (P5) — replace its closure-edge role with deterministic "are required resources unbuilt?" traversal. This directly fixes the brittleness that killed the run, is consistent with one-or-several resources (P1), and lets us re-run to see whether a resource formally completes and the loop moves on. Defer the structure-layer refactor (concrete model #1–2), required/extra origin rules, and adoption (parking lot) to later slices once the re-run validates.

## State of the code right now
Experiment scaffolding is in place, **default-off, 58 planner tests green, uncommitted/unpushed**: `autobuilder.goal.depth-first-decomposition` flag + `DEPTH_FIRST_RESOURCE` contract rule + depth-first empty-backlog NOTE (`PlannerProtocolContract`, threaded via `PlannerLLMAdapter`). This is the *probe*, not the design above — the design supersedes the prompt-directive approach (P2).
