---
name: project-t4cn-connection-kind-table
description: "T4-CN design deliverable (2026-07-22): the connection-kind table — which graph nodes can connect, as what, under which rules; goal↔goal merge fully decided (slice 1, implemented), other kinds sketched as future slices with their existing mechanisms named. Checkpoint-A artifact of the wave-2 plan; read before building any further connection kind."
metadata: 
  node_type: memory
  type: project
  originSessionId: 5796ce03-2a72-4b4d-97e0-1ef53fea290d
---

# T4-CN — Connection-kind table (design deliverable, 2026-07-22)

**Scope:** `checkConnection(a, b) → {kind, allowed, reason, consequences}` over the orchestration graph. One model, three consumers (workbench drag feedback · headless REST pre-check · eventually the loop's own consolidation, recorded-not-applied first — T4-CC principle). This table maps every rule to the EXISTING mechanism that owns it ([[feedback-one-invariant-one-home]]) — the check never invents a second policy; it renders the policies that already exist as an answer BEFORE the action.

## Decided principles (all kinds)

1. **The check answers, it never acts.** `allowed=false` is a feedback payload (HTTP 200), not an error. The reason names the owning rule.
2. **One home per rule:** every predicate delegates to (or is extracted from) the service that already enforces it — fold semantics live in `GoalConsolidationService`, similarity in `GoalSimilarityService`, tree lookups in `GoalPlannerService`. The check must never drift from what the fold would actually do.
3. **`consequences` is a dry-run, not prose:** computed with the same code paths the real fold uses (criteria-carry dedup via `CriterionResultEntity.keyOf`, `chooseGoalToKeep`, mergeCount addition) — honest feedback about the merge's effect IS the point of the design.
4. **Slice-1 lineage rule:** the in-memory tree is single-lineage by construction (T1-FM ownership gate at resume), so "both ids resolve in the current tree" = same-lineage. An unresolvable id is answered as *cross-lineage/cross-run gated on T4-CJ slice 2*, not as a 404.
5. **Similarity grades, not gates:** ≥ 0.88 (`GoalSimilarityService` duplicate threshold) = duplicate-grade; [floor, 0.88) = reviewer-grade — the loop itself would confirm such a pair with the symmetric equivalence review (`GoalAdoptionService.findEquivalent`); in the operator path the operator's judgment stands in for that review, and the known reviewer over-match ceiling rides as a recorded risk in the answer text. Below the consolidation `similarity-floor` (config, default 0.5) = refused (not worth any reviewer's time; same floor the pass uses).

## Kind 1 — GOAL_MERGE (goal↔goal fold) — SLICE 1, DECIDED + IMPLEMENTED

Existing mechanism: `GoalConsolidationService.fold` (T4-AJ Part B) via `GoalAdoptionService.findEquivalent`. The absorbed goal → `SUBSUMED/COVERED`, criteria carried onto an ACTIVE kept goal (T2-DR banking — folding never silently drops spec coverage), mergeCount carried always.

Refusal rows (ordered; first hit answers):

| # | Condition | Reason (owning rule) |
|---|---|---|
| 1 | a == b | a goal cannot merge with itself |
| 2 | either id not in the current tree | cross-lineage/cross-run connects gated on T4-CJ slice 2 + T1-FM ownership |
| 3 | either node is the root (`GoalType.GOAL`) | roots are never folded — a root absorbs work via its subtree, never via subsumption |
| 4 | either description is null | fold equivalence is description-based; an undescribed goal cannot be reviewed |
| 5 | either is BUILD_FIX-tagged | BUILD_FIX dedup is owned by the injector; diagnostic-shaped descriptions over-match |
| 6 | either is `SUBSUMED` | already folded once — one fold per goal (grab-bag/cascade guard, 2026-06-11 runaway) |
| 7 | ancestor↔descendant | decomposition, not duplication (`related` rule of the consolidation pass) |
| 8 | `COMPLETED`↔`COMPLETED` | both banked; nothing to fold and no consumer for the answer |
| 9 | `ACTIVE`↔`COMPLETED` | **no existing fold case** — an ACTIVE duplicate of completed work is injection-dedup territory; folding it would silently drop its unmet criteria (T2-DR banking violated). Named honestly as a gap, not silently allowed |
| 10 | `GIVEN_UP`↔`GIVEN_UP` | no side can keep the work — a fold needs an ACTIVE (carries criteria) or COMPLETED (already banked) kept goal |
| 11 | similarity < consolidation floor | descriptions unrelated; below the same floor the pass uses before spending a reviewer call |

Allowed rows:

| Status pair | Fold case | Kept goal | Criteria carry |
|---|---|---|---|
| ACTIVE↔ACTIVE | `active-duplicate` | `chooseGoalToKeep` (current subgoal first, then higher mergeCount, then stable) | yes — dry-run count |
| GIVEN_UP↔ACTIVE | `given-up-covered` | the ACTIVE side | yes — dry-run count |
| GIVEN_UP↔COMPLETED | `given-up-covered` | the COMPLETED side | no — COMPLETED already banked its criteria |

`consequences` payload: kept/absorbed ids, fold case, absorbed status after (`SUBSUMED`), `criteriaCarried` + `criteriaAlreadyCovered` (dry-run of the real carry dedup), `mergeCountAfter`, `unmetCriteriaOnAbsorbed` (honesty for GIVEN_UP folds: 0 means this fold does not unblock root closure).

Deliberate slice-1 non-rules: the consolidation pass's per-run `foldParticipants` freeze and reviewer-call budget are PASS-scoped cost/cascade guards, not compatibility facts — an operator gesture is a different actor with its own confirm step; the freeze is not rendered as a refusal. (If operator-driven fold cascades ever show up in the data, revisit.)

## Kind 2 — SUBGOAL_REBIND (subgoal → new parent) — FUTURE SLICE (not decided)

Existing mechanisms: `GoalPlannerService.setParent`-style re-pointing + `GoalAdoptionService.findAdoptiveParent` (orphan adoption). Rules to design: no SUBGOAL_OF cycles (T1-DD family as PRE-check) · target parent not terminal (no ACTIVE child under COMPLETED/GIVEN_UP parent — the T1-DD invariant inverted) · currentSubGoalId stability across the move.

## Kind 3 — ATTEMPT_ATTRIBUTION (evolution attempt / action → goal) — FUTURE SLICE (not decided)

Existing mechanism: the `*ActionRegistrar` write paths already attribute at write time; re-attribution is a graph edit with no current service owner — needs a designed home first (likely graph-service level, not tree level). Do not build a second write path from the UI.

## Kind 4 — REOPEN_LINK (terminal goal ← new work) — FUTURE SLICE (not decided)

Existing mechanism: `GoalPlannerService.reactivateGoal` (T2-DR semantics, ancestor-cascade so the node is reachable). The "connection" reading: linking new work to a terminal node first requires the reopen; the check should answer with the cascade's real extent (which ancestors flip).

## Slice-1 implementation (2026-07-22)

`ConnectionCompatibilityService` (goal package) + GET `/autobuilder/goals/connection-check?a=&b=` (via `GoalExplorerService`/`GoalExplorerController`, read-path — no lock, same as `/tree`). Fold helpers (`relatedByDecomposition`, `isBuildFix`, `chooseGoalToKeep`, criteria-carry plan) exposed from `GoalConsolidationService` rather than duplicated. Unit tests cover every table row.
