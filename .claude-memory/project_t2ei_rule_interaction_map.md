---
name: project-t2ei-rule-interaction-map
description: "T2-EI design-map-first deliverable (2026-06-24): how the breadth-first-tuned planner rules interact, the FULL_COVERAGE vs 8-cap contradiction, and why depth-first cannot just reverse FULL_COVERAGE — it must move the coverage invariant to the empty-backlog edge. Read before touching any planner rule."
metadata:
  node_type: memory
  type: project
---

**T2-EI rule-interaction map — the design-map the user asked for BEFORE bolting on a depth-first rule.** Source-verified 2026-06-24 against `PlannerProtocolContract`, `PlannerAgent`, `GoalPlannerService.autoCompleteRootIfSatisfied`, `GoalConsolidationService`. See T2-EI ticket in [[roadmap]] and [[project-completion-binding-constraint]].

## The mechanisms (verified locations + constants)

**Forces the working set WIDE (breadth-first, up front):**
1. `FULL_COVERAGE_REQUIRED` (`PlannerProtocolContract` rule) — "your FIRST planning response MUST produce one subgoal per feature … the system considers the backlog done once the first batch completes." Maximal working set immediately.
2. The no-active-subgoal NOTE in `getUserPrompt` (PlannerProtocolContract ~L418-428) — "enumerate every named feature … if ANY lacks a completed subgoal → #SubGoalAppend them now." Re-asserts FULL_COVERAGE at the empty-backlog edge.
3. `INDEPENDENT_ENDPOINT_SPLIT` (rule) — splits any multi-endpoint subgoal into one-per-endpoint → multiplies the count again (firestarter: 3 controllers × ~3 endpoints ⇒ 9+ leaves).
4. Completion **coverage gate** (`autoCompleteRootIfSatisfied` → `coverageRetrospectiveService.findMissingFeatures` → `injectCoverageGaps`) — at the closure edge, re-injects every named feature not yet covered. **OFF by default** (T2-EG demoted it; `completion-coverage-gate-enabled` flag; false-positived 18/18 on the 2026-06-16 hollow artifact). T4-BP-3 criteria-verification is the live closure gate instead.

**Caps / shrinks the working set:**
5. `MAX_ACTIVE_SUBGOALS_BEFORE_THROTTLE = 8` (`PlannerAgent` L39) — when `remainingGoals > 8`, raise `PLANNING_THROTTLED` (0.9). (NB: the roadmap's "18-cap" was wrong — it is **8**.)
6. `PRUNE_BEFORE_GROW` (rule) — when remaining large vs completed, prune duplicate/un-executable/superseded before adding.
7. `GoalConsolidationService` — folds equivalent leaves; triggers on `stagnation-streak ≥ 3` OR `active-leaf-threshold > 10` (with tree-history grace, T2-EC). Folded goal → SUBSUMED/COVERED carrying criteria + mergeCount (T2-DR banking).
8. Injection backlog-non-empty guard (`PlannerAgent` ~L162-227) — only inject when backlog empty, unless a structured prune-then-grow replan.

## The contradiction (the core finding)

For any goal with > 8 features (firestarter has ~9), a **FULL_COVERAGE-compliant first plan is instantly over the 8-cap**:
`FULL_COVERAGE emits ~9 leaves` → `remaining > 8` → `PLANNING_THROTTLED` → planner deprioritised, but the backlog is now full of mutually-interdependent files the local model cannot keep coherent → `cannot find symbol` churn (647 in run-2) → build red → `BUILD_FIRST` pins the planner into BUILD_FIX → backlog never drains → consolidation fires (>10 leaves) but only folds *equivalent* goals, not *dependency-ordered* ones → tree never converges. This **is** the run-2 diagnosis (505 appends / 145 completes / 527 THROTTLED). Rules 1 and 5 are tuned against each other.

## The load-bearing interaction depth-first MUST handle (the trap)

**`FULL_COVERAGE_REQUIRED` is the only thing preventing premature root completion at the empty-backlog edge.** When the backlog drains and all direct children are terminal, `getCurrentSubGoal` (GoalPlannerService L687-708) calls `autoCompleteRootIfSatisfied()`. With the coverage gate OFF (default), the closure gates that remain — T4-BP-3 (criteria verify), T4-AN-2 (tests), T4-AN-8 (package) — only check the criteria that were **actually planned**. In depth-first, resources 2 and 3 were never planned, so **no criterion names them** → the root would falsely COMPLETE after resource 1.

⇒ **`FULL_COVERAGE_REQUIRED` (planning-start) and the completion coverage gate (closure) are two halves of ONE invariant** — "don't stop until every named feature is covered" — enforced at two edges. Breadth-first enforces it up front; depth-first must move the *entire* enforcement to the closure/empty-backlog edge.

## What this means for the T2-EI design (conclusion)

Depth-first is **not** "reverse FULL_COVERAGE." It is a coordinated move:
- **Replace** rule 1's up-front breadth mandate with: decompose ONE resource fully (model→exception→repo→service→controller, dependency-ordered — the surviving kernel of T4-BQ, minus stub synthesis), and on empty-backlog decompose the **next** uncovered resource.
- **Re-arm the completion coverage gate** (rule 4, `completion-coverage-gate-enabled=true`) as the "is the root fully covered? if not, what is the next resource?" driver — this is now load-bearing, not optional. Its 2026-06-16 false-positive risk (`dropGapsAlreadyImplemented` over-dropping) must be re-checked under depth-first before trusting it.
- Keeps the active-leaf count naturally ≤ ~5 (one resource) ⇒ **under the 8-cap** ⇒ no PLANNING_THROTTLED ⇒ planner stays live, working set stays coherent. The cap (5) and consolidation threshold (10) stop fighting the planner.
- `INDEPENDENT_ENDPOINT_SPLIT` (rule 3) stays compatible **within** a resource but must not pull in sibling resources' endpoints.

## EXPERIMENT RESULT (2026-06-24, one capped run, df=true + coverage-gate=true, fresh reset-on-start seed)

Run `firestarter/logs/autobuilder-...21-21` (script `/tmp/t2ei_depthfirst_run.sh`), 19 cycles, terminated root-GIVEN_UP.

**Mechanism CONFIRMED (working-set shrink → build coherence):**
| metric | breadth baseline (run-2, 131 cyc) | depth-first (19 cyc) |
|---|---|---|
| max active subgoals | 11 (over the 8 cap) | **3** |
| backlog-cap throttles | 11 | **0** |
| cannot-find-symbol / cycle | 4.9 | **0.74** |
| COMPILE OK-ratio | 0.55 | **0.92** |
| merges | — | **18 in 19 cyc** (nearly every cycle) |

- **The model OBEYED the depth-first directive** (unlike T4-BQ naming): first decomposition was exactly the Book resource, dependency-ordered (model → repository → POST/GET/GET-by-id), NOT all ~9 features. Directive landed.
- Book's core subgoals all got merges or SUBSUMED-via-replanning; build stayed green. The "local model can't finish even one resource" worry was NOT borne out here (promising, not conclusive — see below).

**But it died at cycle 19 to a NEW depth-first-specific brittleness the map missed:**
- The planner kept decomposing deeper: GET-by-id → "return 404 when absent" → **"Add unit test for BookController.getBookById"** (a TEST subgoal, on a no-test seed). The scope guard even flagged it OUT_OF_SCOPE. It couldn't pass (T4-BP-1: 0/3 criteria), SUBGOAL_EXHAUSTED → GIVEN_UP.
- As the **lone terminal leaf** (depth-first tree has no sibling redundancy), `autoCompleteRootIfSatisfied` saw `1 blocker → root GIVEN_UP`. In breadth-first, one stuck leaf among 9 never dooms the root; under depth-first it does.
- **The coverage gate (the "build the next resource = Members" safety net) NEVER RAN** — it is gated on `blockers.isEmpty()`, and the stuck leaf put a blocker on the list. So depth-first's intended "decompose next resource on empty-backlog" path was bypassed by the blocker branch firing first.

**Two follow-on obstacles (candidate tickets), separable:**
1. **Closure brittleness under depth-first** (the lever): a single GIVEN_UP leaf with no siblings must NOT doom the root while resources remain uncovered. On the blocker branch, when depth-first is active and the coverage retrospective shows uncovered resources, quarantine/drop the stuck leaf and decompose the next resource instead of `markGoalGivenUp(root)`. This is the missing actuator ([[project-completion-edge-gates]]) — the run never got a fair shot at fulfillment because of it.
2. **Spurious nested test subgoal** on a no-test seed became the fatal blocker (recurring "model writes stray tests" theme). Pairs with the build-probe `mvn compile`-not-`test-compile` decision.

**Decision-point status:** the plan's "if depth-first stalls at single-resource granularity → local-model ceiling" branch is **NOT triggered** — it did not stall on the model (18 merges, 0.92 OK-ratio); it died on closure-logic brittleness. Next step is obstacle (1), then re-run to see if a single resource formally COMPLETES and the coverage gate drives resource 2. NB: 0 subgoals reached formal COMPLETED (merges + SUBSUMED only) — the [[project-resume-state]] "merged subgoals stop being promoted to COMPLETED" promotion-freeze is also in play here.

## Honest caveats
- **ONE run.** The structural metrics (max-active, backlog-cap throttle) are mechanism-deterministic = high confidence; cfs/OK-ratio carry run-variance but the direction is large. Re-run after fixing obstacle (1) before any strong claim ([[feedback-calibrate-not-flip]], [[feedback-activity-not-progress]]).
- HYPOTHESIS, not asserted — the local-model ceiling may still bite at single-resource granularity (one resource may not finish cleanly either). The cheap test (step 2): single planner call to confirm the decomposition flips breadth→depth, then a capped run on `/tmp/bisect_run.sh` to see if `cannot find symbol` count / backlog churn drops. NOT high-variance full-run A/Bs ([[feedback-calibrate-not-flip]]).
- Coherence ≠ completion ([[project-t4bq-naming-consistency-ab]]: synonym reduction was 0/6 on completion). Depth-first is a *working-set-size* lever, distinct from the model-capability residual — keep them separated ([[feedback-no-defensive-recast]]).
