# Autobuilder Roadmap

Last updated: 2026-04-21 (T3-BP shipped)
Status legend: `[ ]` open · `[~]` in progress · `[x]` done · `[>]` deferred

**Active branch:** `feature/pre-T2-AE`, branched from `edf3638` (main HEAD). Later commits on main (after edf3638) introduced deteriorating behaviour so development continues on this branch. All [x] entries up to and including edf3638 are implemented; T1-AK was the exception — incorrectly marked done, never implemented.

**Note:** Ticket ID prefix (T1/T2/T3/T4/T5) indicates the tier the ticket belongs to. Suffixes are sequential within each tier's history and do not imply ordering within the tier.

**Supplementary roadmaps:**
- `roadmap_log_findings_2026-03-24.md` — findings from live session analysis (merge graph registration bug fix, `context.merged` false-negative, unevaluated mutations silently merged, `ROLE_EVOLUTION` signal growth, `ROLE_ANALYSIS` never firing, redundant re-merging)

---

## Tier definitions

| Tier | Name | Criterion |
|---|---|---|
| **1** | **Correctness** | Something is observably broken or produces wrong/harmful output. The loop misbehaves, wastes significant resources, or produces incorrect state. Fix unconditionally. |
| **2** | **Steering quality** | The loop runs correctly but cannot steer itself well — signals don't flow where they should, agents repeat mistakes they've been penalized for, orchestration is blind to its own history. Makes the system *dumb*, not broken. |
| **3** | **Feature completion** | Partially-built features that exist in code but aren't wired end-to-end. The scaffold is there; work remains to activate it. |
| **4** | **New systems** | Greenfield — no scaffold exists yet. Requires design before implementation. |
| **5** | **Quality / observability** | Doesn't change behavior but improves debuggability, reduces noise, or improves resource usage. |

---

## Tier 1 — Correctness

- [>] **T1-D: Fix `Neo4jGoalGraphService.purge()` scope**
  - Scope delete to `(Goal)` and `(SystemState)` nodes only
  - Currently does `MATCH (n) DETACH DELETE n` — wipes all orchestration history on reset
  - Deferred: full wipe is intentional during hello-world test phase; revisit when orchestration history is worth preserving

- [x] **T1-BD: REPLAN mode blocks evolution indefinitely** (`DeliberationExecutionGate`)
  - **Root cause (2026-04-09 session):** `DeliberationExecutionGate.allowedRoles(REPLAN)` returns `Set.of("planner", "analysis")`. Evolution is completely excluded. There is no time limit, grace period, or fallback. Once REPLAN_REQUESTED fires and deliberation returns REPLAN, evolution never runs again until the signal decays — which it doesn't (see T1-BE). Observed: cycles 3–11 in the 00:42 session all ran in REPLAN mode; evolution was logged as `"Meta step skipped: DeliberationMode=REPLAN blocks role=evolution"` every cycle. Four subgoals were given up without a single evolution attempt.
  - **Fix:** Add `"evolution"` to the REPLAN allowed-roles set in `DeliberationExecutionGate`. REPLAN's intent is "run the planner first to get a better plan" — not "freeze all forward progress". The planner ranks highest via ROLE_PLANNER signal and executes first. Evolution then runs on whatever active subgoal remains. If the planner removed the current subgoal, evolution has nothing to work on and is naturally a no-op. This is the minimal, lowest-risk fix and does not change planner-first ordering.
  - **Change:** `DeliberationExecutionGate.java`: `REPLAN -> Set.of("planner", "analysis", "evolution")`.
  - **Depends on:** T1-BE (REPLAN_REQUESTED must decay naturally, otherwise evolution still can't break the lock on its own).

- [x] **T1-BE: REPLAN_REQUESTED perpetual reinforcement prevents signal decay** (`AutobuilderService`, `Signal`)
  - **Root cause (2026-04-09 session):** `AutobuilderService` emits `REPLAN_REQUESTED(1.0, decay=0.05)` on every SUBGOAL_EXHAUSTED and STAGNATING result. `Signal.reinforce()` accumulates strength (`1.0 + 1.0 = 2.0 → 3.0 → …`) and takes `Math.min(decayPerCycle, other.decayPerCycle)`, locking the decay to the slowest emitter (0.05). At decay=0.05, the signal would need 20 uninterrupted cycles to die — but it is re-emitted every cycle stagnation continues, resetting the age clock and growing strength. In the 00:42 session, REPLAN_REQUESTED was still alive at cycle 11, never having decayed.
  - **Fix A — raise decay:** Change `Signal.of(BasicSignals.REPLAN_REQUESTED, 1.0, 0.05, "exhaustion-recovery")` and the stagnation-recovery emission to `decay=0.20`. This reduces the decay horizon from 20 cycles to 5 cycles, giving a natural expiry window.
  - **Fix B — conditional re-emission:** Before emitting, check `context.hasStrongSignal(REPLAN_REQUESTED, 0.50)`. If already strong, skip re-emission and let the existing signal decay. Only emit a fresh signal when the prior one has weakened. This prevents strength accumulation.
  - **Recommended:** Apply both. Fix A prevents slow decay; Fix B prevents accumulation on fast cycles.
  - **Changes:** `AutobuilderService.java` lines 200–207 (SUBGOAL_EXHAUSTED) and 240–242 (STAGNATING): raise decay to 0.20 and add conditional-re-emission guard.

- [x] **T1-BH: False GOAL_FULFILLED when planner exhausts all subgoals** (`CycleExecutor.deriveCycleResult`)
  - **Root cause (2026-04-09 log analysis):** `deriveCycleResult` had a secondary GOAL_FULFILLED path: `(leafCompletedNow || goalProgress == PROGRESSING) && mutationState == ACCEPTED && remainingGoals.isEmpty()`. `remainingGoals` is populated from `GoalPlannerService.getRemainingGoals()` (ACTIVE subgoals only). When the planner gave up all remaining subgoals via GIVEN_UP (leaving no ACTIVE ones), `remainingGoals` became empty. Combined with ACCEPTED+PROGRESSING from a concurrent evolution cycle, the `PROGRESSING` branch of the OR fired GOAL_FULFILLED — even though the root goal was still ACTIVE and no subgoal was actually completed.
  - **Fix:** Removed the `goalProgress == PROGRESSING` branch entirely (PROGRESSING is not a completion signal). The `leafCompletedNow` path is guarded with an explicit root goal status check: only returns GOAL_FULFILLED when `root.getStatus() == COMPLETED`. The `rootAutoCompleted()` path (line above) remains the primary completion detection mechanism.
  - **Regression tests:** `runCycle_T1BH_regression_progressingAcceptedWithAllSubgoalsGivenUp_doesNotProduceFalseGoalFulfilled` and `runCycle_T1BH_leafCompletedNow_withRootCompleted_setsCycleResultGoalFulfilled`.

- [x] **T1-BJ: Planner shallow decomposition — FULL_COVERAGE_REQUIRED rule** (`PlannerProtocolContract`)
  - **Root cause (2026-04-09 session):** The planner was given a root goal specifying 10 endpoints across 3 resource types (Books, Members, Loans). Its first `#SubGoalAppend` covered only 2 endpoints (POST /books, GET /books). After those completed, it was re-invoked via `planInto` and attempted to re-add GET /books (duplicate-blocked), causing the root to auto-complete with 8 endpoints unimplemented. Existing rule `GOAL_DERIVED_SUBGOALS` says subgoals must be traceable to the root goal but does not require covering all stated requirements.
  - **Fix A — new protocol rule:** Add `FULL_COVERAGE_REQUIRED` to `PlannerProtocolContract.rules()`: "When the root goal lists N distinct named endpoints or features, your FIRST planning response MUST produce one subgoal per endpoint/feature. Do not submit a partial list expecting to add the rest later — the system considers the backlog done once the first batch completes. Count them explicitly before responding."
  - **Fix B — rewrite the "no active subgoal" NOTE block:** The NOTE at the bottom of `getUserPrompt()` (shown when `currentSubGoalDescription == null`) currently says "If the root goal is fulfilled, respond with: Message: Goal complete. Otherwise, use #SubGoalAppend." Replace with: "Before responding, enumerate every named endpoint and feature in the root goal spec. Compare against completed goals. If ALL are covered → Message: Goal complete. If ANY are missing → #SubGoalAppend them now. Do NOT say 'Goal complete' while any endpoint or feature from the root goal spec lacks a completed subgoal."
  - **Changes:** `PlannerProtocolContract.java` — add rule entry + rewrite NOTE block (~15 lines).
  - **Observed:** 2026-04-09 session (10-endpoint goal, only 2 subgoals planned, root marked complete with 2/10 done).

- [x] **T1-BK: Root auto-completion fires when `planInto` was blocked by deduplication** (`GoalPlannerService`, `PlannerAgent`, `PlanningCallback`)
  - **Root cause (2026-04-09 session):** `promoteCurrentSubGoal()` calls `planningCallback.planInto()` then unconditionally calls `autoCompleteRootIfSatisfied()`. In the session, `planInto` proposed "Implement GET /books [2 merges]" — blocked by `GoalSimilarityService` as a near-duplicate of the already-completed "Implement GET /books". Zero subgoals were injected. `autoCompleteRootIfSatisfied` saw all descendants COMPLETED → marked root COMPLETED. The system had no way to distinguish "planner said done" from "planner tried to inject work but was blocked."
  - **Fix:** Change `PlanningCallback` from `void planInto(...)` to `int planInto(...)` returning the count of successfully injected subgoals. In `PlannerAgent.planInto()`, return the count from the injection loop. In `GoalPlannerService.promoteCurrentSubGoal()`, capture the return value: if `planInto` was called AND returned 0 (no new subgoals injected, but the planner did attempt injection), skip `autoCompleteRootIfSatisfied` and instead raise `REPLAN_REQUESTED` via the stored `currentCycleContext`. This surfaces as a new planning opportunity next cycle rather than a silent false completion.
  - **Edge case:** If `planInto` was not called at all (no `shouldTriggerPlanner` condition met), `autoCompleteRootIfSatisfied` still runs as before — this covers the legitimate "planner already ran and there's genuinely nothing left" case.
  - **Changes:** `PlanningCallback.java` (return type), `PlannerAgent.planInto()` (return count), `GoalPlannerService.promoteCurrentSubGoal()` (~5 lines around the `planningCallback.planInto()` call).
  - **Observed:** 2026-04-09 session (root marked COMPLETED after planner produced 0 injected / 1 deduped subgoal under root).

- [>] **T1-BL: T2-BM veto must use criteria progress gate** (`EvaluationAgent`)
  - **Withdrawn (2026-04-10):** This was a workaround for T2-BM's side effects. The correct fix is to remove T2-BM entirely (see T1-BN). With T2-BM gone, partial-progress mutations are accepted naturally and T1-BL is not needed.
  - **Original analysis preserved for reference:** T2-BM forces REJECTED on any ✗ criterion without consulting `cumulativeCriteriaStatus`. For multi-file criteria, this creates a permanent block where alternating `[1] ✓/[2] ✗` and `[1] ✗/[2] ✓` cycles each get rejected, code never accumulates, and the subgoal exhausts. Observed 2026-04-10: `goal-649cb581` (POST /members email uniqueness) stuck for 12+ cycles.

- [>] **T1-BM: `SubGoalFulfillmentHeuristic` — all-criteria-cumulatively-met fast path** (`SubGoalFulfillmentHeuristic`)
  - **Withdrawn (2026-04-10):** This was needed to work around the STAGNATING deadlock caused by T2-BM's repeated rejections driving the LLM to report `goalProgress = STAGNATING`. With T2-BM removed (T1-BN), partial mutations are accepted, the LLM sees forward progress, and `goalProgress = PROGRESSING` — so the existing fulfillment path works without modification.

- [x] **T1-BN: Remove T2-BM hard veto — it is now superseded by T1-BG + T3-BA** (`EvaluationAgent`)
  - **Root cause (2026-04-10 analysis):** T2-BM was introduced (2026-04-09) before T1-BG existed, to prevent a specific bug: evaluator outputs ACCEPTED with a ✗ criterion → broken code merged → subgoal closed as FULFILLED. At the time, T3-BA only blocked *subgoal closure* (via `SubGoalFulfillmentHeuristic`), not *mutation acceptance*; unmet criteria had no binding effect on the merge decision.
  - **Why T2-BM is now redundant:** T1-BG's `cumulativeCriteriaStatus` map tracks which criteria have ever passed. `SubGoalFulfillmentHeuristic.hasUnmetCriterion()` uses this map to gate FULFILLED: a subgoal cannot close until all criteria are cumulatively `true`. The original T2-BM bug — premature FULFILLED with broken code — is now prevented by T3-BA+T1-BG without T2-BM.
  - **Why T2-BM is now harmful:** The 2026-04-10 session showed it creates a deadlock for multi-file criteria (e.g. "add method to MemberService AND have MemberController call it"). No single-file mutation can satisfy both simultaneously. T2-BM rejects every partial mutation; code never accumulates; the subgoal exhausts. T1-BL and T1-BM were proposed as workarounds — but the right fix is to remove the root cause.
  - **What to do:**
    1. In `EvaluationAgent.java`: delete the T2-BM hard-veto block (the `anyFailed → force mutationState = REJECTED, vetoed = true` block, ~lines 148–158). Keep `criteriaAssessments` parsing, cumulative merge, and `lastCriteriaPassCount` update — those belong to T1-BG, not T2-BM. Remove the `vetoed` flag from `EvaluationAgent` and its callsite in `HeuristicNegotiator.negotiate(...)` if it is no longer needed.
    2. In the roadmap: annotate T2-BM as superseded (see below).
    3. Mark T1-BL and T1-BM as `[>]` withdrawn (already done above).
    4. T2-BN (surface banked criteria in evolution prompt) remains a valid steering improvement independent of T2-BM removal — re-evaluate after T1-BN ships.
  - **Risk and mitigation:** A mutation with a genuinely broken ✗ criterion (e.g. evolution deleted a feature) can now be merged. This is acceptable: T3-BA keeps the subgoal open (cumulative criterion stays `false`), and the next evolution cycle corrects it. The output directory may temporarily contain incomplete code, but that is by design — it is the accumulated partial state that the system converges from.
  - **Run tests:** `mvn test` after removal to confirm no regressions.

- [x] **T1-BG: Cumulative per-criterion pass tracking** (`CycleContextData`, `EvaluationAgent`, `SubGoalFulfillmentHeuristic`, `CycleContextCoordinator`)
  - **Root cause (2026-04-09 analysis):** `criteriaAssessments` on `CycleContextData` is a fresh snapshot per evaluation cycle. `SubGoalFulfillmentHeuristic.hasUnmetCriterion()` vetoes FULFILLED whenever *any* criterion in the **current cycle's** assessment has `✗` — including criteria not addressed by this mutation (because evolution only tackles a subset of criteria per cycle). A subgoal with 4 criteria that individually take one cycle each to satisfy will never fulfill: each cycle's snapshot shows at least one `✗`, so the veto fires indefinitely. ACCEPTED merges accumulate but the subgoal never advances — it eventually exhausts and gets GIVEN_UP.
  - **Fix:**
    1. Add `Map<Integer, Boolean> cumulativeCriteriaStatus` to `CycleContextData` (index → passed, serialised in snapshot). A criterion index maps to `true` once it has passed; it never reverts to `false` (a criterion that passed is considered satisfied for the lifetime of the subgoal).
    2. In `EvaluationAgent`, after parsing `criteriaAssessments`, merge each `[N] ✓` into `cumulativeCriteriaStatus` as `true`. A `[N] ✗` only sets `false` if the index was not already `true`. Omitted indices (LLM partial compliance) are left at their prior value.
    3. In `SubGoalFulfillmentHeuristic.hasUnmetCriterion()`, consult `cumulativeCriteriaStatus` when it is non-null. A criterion is "unmet" if its index is absent from the map or maps to `false`. A criterion maps to `true` if it has ever passed, regardless of this cycle's assessment.
    4. In `CycleContextCoordinator.prepareRuntimeContext()`, reset `cumulativeCriteriaStatus` to `null` when `currentSubGoalId` changes — fresh start for each new subgoal.
  - **Result:** a subgoal is fulfilled when all its criteria have been satisfied across any set of cycles, not in a single snapshot.
  - **Depends on:** T3-AZ/T3-BC (source of machine-readable criterion assessments). T3-BI (graph persistence so this survives restart).
  - **Enables:** T2-BL (exhaustion suppression while criteria are progressing), T3-BJ (snapshot surface).

- [x] **T1-BF: Cascade GIVEN_UP wipes goals with successful merges** (`GoalPlannerService`, `GoalEntity`, `MergeAgent`)
  - **Root cause (2026-04-09 session):** `GoalPlannerService.markGoalGivenUp()` calls `collectActiveDescendants()` which returns all ACTIVE descendants unconditionally. Every child is marked GIVEN_UP regardless of whether it had prior successful merges. No merge history is stored on `GoalEntity`.
  - **Observed failure:** When `goal-4576d23a` (Refactor BookController) was given up after 1 cycle with no merge, its sibling-injected child `goal-98634b9c` was immediately cascade-given-up (`00:43:17.521: "markGoalGivenUp cascade"`), despite having later produced a merge in cycle 4. The parent/child topology meant the cascade reached it.
  - **Fix A — merge history on GoalEntity:** Add `int mergeCount` field to `GoalEntity` (persisted under metadata key `"mergeCount"`). Increment via `GoalPlannerService.incrementGoalMergeCount(goalId)`, called from `MergeAgent` after each accepted merge. Load from metadata in `Neo4jGoalGraphService`.
  - **Fix B — protect goals with merges from cascade:** In `collectActiveDescendants()`, skip descendants where `mergeCount > 0`. Goals that produced real output should not be silently wiped; let them exhaust naturally through the normal exhaustion counter.
  - **Note:** Fix A (merge tracking) is also the infrastructure for T2-BI (planner prompt enrichment). Implement T2-BI first or together.
  - **Changes:** `GoalEntity.java` (add field), `GoalPlannerService.java` (incrementMergeCount + cascade guard), `MergeAgent.java` (call incrementMergeCount), `Neo4jGoalGraphService.java` (persist/load via metadata).

- [x] **T1-BC: Evolution package coherence** (`EvolutionProtocolContract`, `CycleContextCoordinator`, `DefaultProjectStructureSummarizer`)
  - **Observed (2026-04-06 session):** Evolution generated files in `com.example.demo` early in the session, then switched to `com.example.library` in later cycles. Both packages coexist in the merged output — Spring component scan cannot reach both, imports across packages won't resolve. The output is structurally broken Java.
  - **Root cause:** The evolution prompt has no constraint on the output package namespace. The LLM picks a package freely per cycle with no awareness of what's already in `output/`.
  - **Fix:** `DefaultProjectStructureSummarizer.detectBasePackage(Path)` walks `.java` files and returns the common package prefix (null when output is empty). `CycleContextCoordinator` calls it each cycle and stores result in `CycleContextData.establishedBasePackage`. `EvolutionProtocolContract` injects a `--- PACKAGE CONSTRAINT ---` block when non-null, locking all generated files to that base package or a sub-package of it.

- [x] **T1-A: Stagnation/exhaustion recovery** (`AutobuilderService`, `CycleExecutor`)
  - `STAGNATING`: inject `REPLAN_REQUESTED` at high strength + set `DIVERGENT_MODE` flag for EvolutionLLMAdapter
  - `GOAL_EXHAUSTED` (subgoal): mark current subgoal `GIVEN_UP`, advance pointer, reset signals
  - `GOAL_EXHAUSTED` (root): pause loop, require human input

- [x] **T1-C: Complete scope guard resolution path** (`DefaultSubgoalScopeGuard`, `PlannerAgent`)
  - When `SCOPE_OUT_OF_SCOPE` + `bestMatchSubGoalId` present, resolve it
  - Option A (simpler): planner checks signal and calls `activateSubGoal(bestMatchSubGoalId)` as first action
  - Option B (cleaner): restore commented-out auto-switch, emit `SCOPE_SWITCHED` instead to avoid suppressing downstream

- [x] **T1-E: `REPLAN_REQUESTED` must bypass `PLANNING_THROTTLED`** (`PlannerAgent` / throttle guard)
  - `PLANNING_THROTTLED` has `decayPerCycle=0.0` — never decays once set
  - With `inflow=4, outflow=0` the throttle is permanent; planner stays blocked even at `REPLAN_REQUESTED=97`
  - Fix: when `REPLAN_REQUESTED` exceeds a threshold (e.g. ≥1.0), skip the throttle check or forcibly clear the signal
  - This is the single most impactful fix — unblocks planner → unblocks `#SubGoalRemove` → unblocks stagnation recovery

- [x] **T1-F: Meta deliberation leniency on `Steps:` for NOOP/Stop responses**
  - `isValidResponse` required all four headers including `steps:`, but LLM correctly omits it when `Stop: true`
  - Fix: dropped `steps:` from `isValidResponse` entirely — it is a parser concern, not a validator concern
  - Prompt updated: "Include Steps: only when Stop: false"
  - Confirmed in live run: two `Stop: true` / no `Steps:` responses accepted cleanly, zero `MetaDeliberation failed` warnings

- [x] **T1-G: Deliberation fallback when evaluation is infeasible post-merge** (`RuleBasedDeliberationProposer`, `ContextLineageTracker`)
  - Root bug: `lastEvaluatedEvolutionFingerprint` was not carried between cycles in `ContextLineageTracker`.
    Reset to null each cycle → `notYetEvaluated=true` every cycle after any evolution, even post-merge.
    Combined with decaying `EVOLUTION_OCCURRED` (~10 cycles) + `REPLAN_REQUESTED >= 0.50` → T1-G safety
    net fired every post-merge cycle → planner ran repeatedly → goal explosion.
  - Fix A (`ContextLineageTracker`): carry `lastEvaluatedEvolutionFingerprint` from previous context,
    same as `lastEvolutionFingerprint` is already carried. After a successful evaluation+merge, fingerprints
    match across cycles → `notYetEvaluated=false` → EVALUATE/T1-G branch correctly skipped.
  - Fix B (`RuleBasedDeliberationProposer`): T1-G safety net retained — when `projectDiff=null` (diff
    consumed or lost) and `REPLAN_REQUESTED >= 0.50`, fall back to REPLAN instead of silent EVALUATE.
    Now only fires when fingerprints genuinely diverge (true unevaluated pending mutation with no diff).

- [x] **T1-H: Subgoal deduplication — intent-level** (`GoalSimilarityService`, `PlannerAgent`, `PlannerProtocolContract`)
  - All four root causes resolved (T1-N completed the remaining fixes):
  - A: `NO_DUPLICATES` rule in `PlannerProtocolContract` — LLM sees full remaining/completed goal lists.
  - B: Full-scan replacement — `findDescriptionCandidates` removed; `findAllDescriptions(200)` does a single pass over all goals with no text filter. Monge-Elkan runs against all.
  - C: `createRefinementSubgoal()` guards against re-injection via `refinementCreated` metadata flag.
  - D: GIVEN_UP goals restored to dedup candidates (T1-N Fix B) + surfaced as "Abandoned Goals" block in planner prompt (T1-N Fix C) with `NO_REPEAT_ABANDONED` contract rule.

- [x] **T1-I: Fix `#SubGoalRemove` matching** (`PlannerResponseParser`, `PlannerAgent`)
  - `formatGoalInfoList` renders goals as `goalId: description` in the planner prompt
  - LLM naturally copies this format into `#SubGoalRemove` entries, producing `id: description` strings
  - Parser puts the full `id: description` string into `goalsToRemove`; `processRemovals` matches by
    `equalsIgnoreCase(desc)` → always misses → silent failure every time
  - Fix: parser extracts just the ID when entry matches `^goal-[0-9a-f-]+:\s*.*` (GOAL_ID_PREFIX pattern);
    `processRemovals` uses `entry.equalsIgnoreCase(g.getId()) || entry.equalsIgnoreCase(g.getDescription())`
    (null-safe, ID match takes precedence)

- [x] **T1-I2: Fix meta intention not reaching Planner/Evolution agents** (`PlannerProtocolContract`, `EvolutionProtocolContract`)
  - Engine correctly sets `ctx.metaIntention()` via `withMetaIntention()` before calling `agent.execute()`
  - EvaluationProtocolContract already read it correctly via `ctx.metaIntention()`
  - Planner and Evolution had a dead `metaRequest()` helper reading `dr.meta().get("metaRequest")` — a key never set anywhere
  - Fix: replaced dead helper with `metaIntention()` reading `ctx.metaIntention()` in both contracts

- [x] **T1-L: Reinforce `ROLE_EVALUATION` when unevaluated mutation is present** (`RuleBasedDeliberationProposer`)
  - **Root cause (live log 2026-03-09):** After evolution, `ROLE_EVALUATION` decays below the `canExecute`
    threshold (0.10) within 3–4 no-op cycles before evaluation runs. Deadlock: mode=EVALUATE but agent
    cannot execute; meta returns `Stop: true` every cycle; mutation stays uneval'd/unmerged forever.
  - Fix: in the `hasCandidate && notYetEvaluated` branch, add `ROLE_EVALUATION` at strength 0.5 to
    `proposedSignals`. Applied every cycle via the existing `applyProposedSignals` path in
    `AdaptiveExecutionEngine`, guaranteeing evaluation is reachable regardless of decay timing.

- [x] **T1-M: `PLANNING_THROTTLED` never releases in a fresh environment** (`PlanningThrashSignalAnalyzer`)
  - **Root cause (live log 2026-03-09 / deep analysis):** The release condition required BOTH
    `currentInflow <= inflowRelease` AND `currentRatio <= ratioRelease`. In a fresh environment all
    baseline windows are empty → `inflowRelease` clamps to 2. Inflow is an absolute count (4 goals
    added) that never decreases → `4 <= 2` is permanently false → throttle never releases even after
    all 4 goals complete. `planInto()` callback sees the stale throttle, makes the LLM call, then
    returns early without injecting the proposed subgoal. Root goal is immediately marked complete.
  - Fix: added `caughtUp` release condition: if `currentOutflow > 0 && currentRatio <= 1.0`,
    release the throttle. This handles the case where all injected goals have been worked through,
    regardless of absolute inflow count or baseline history.
  - Stagnation case covered by T1-O: GIVEN_UP goals now count toward outflow → `caughtUp` fires correctly.

- [x] **T1-N: Deduplication rework — remove keyword narrowing, restore GIVEN_UP candidates, surface GIVEN_UP to LLM** (`GoalSimilarityService`, `Neo4jGoalGraphService`, `PlannerProtocolContract`)
  - **Fix A — remove keyword narrowing (T1-H/B rework):** Drop `extractKeywords()`, the `STOP_WORDS`
    set, and the `CONTAINS` clause from `findDescriptionCandidates` entirely. Return all goals
    regardless of status (single query, no text filter, limit 200). Run Monge-Elkan against the
    full set. Simple and correct; eliminates stop-word brittleness and keyword mismatch false negatives.
  - **Fix B — restore GIVEN_UP in dedup candidates (revert T1-H/D):** Remove the `<> 'GIVEN_UP'`
    filter. All statuses participate so truly identical re-proposals of failed goals remain blocked.
  - **Fix C — surface GIVEN_UP goals in planner prompt:** Add an "Abandoned Goals" block to
    `PlannerProtocolContract.getUserPrompt()` (after Completed Goals) listing GIVEN_UP goal
    descriptions. Add a protocol rule: "Do not re-propose abandoned goals verbatim."

- [x] **T1-O: `PLANNING_THROTTLED` stuck in stagnation — GIVEN_UP goals not counted as outflow** (`PlanningThrashSignalAnalyzer`)
  - **Root cause (live log 2026-03-09):** `countCompletedSince()` only counts `GoalStatus.COMPLETED`.
    Goals that exhaust without completing (→ GIVEN_UP) are invisible to the outflow counter.
    In stagnation, `outflow` stays 0 indefinitely → T1-M's `caughtUp` release never fires.
  - Fix A: added `Instant givenUp` field to `GoalEntity`, set in `markGivenUp()`, persisted to Neo4j via `save()`, read back in `mapRowToGoalEntity()`.
  - Fix B: replaced `completedInstant()` helper with `resolvedInstant()` in `PlanningThrashSignalAnalyzer` — returns `completed` for COMPLETED, `givenUp` for GIVEN_UP, null otherwise. Both count methods now use it.

- [x] **T1-Q: GIVEN_UP cascade — orphaned active children** (`GoalPlannerService`, `autoCompleteRootIfSatisfied`)
  - `markGoalGivenUp()` only updated the single target node; children remained ACTIVE, becoming unreachable
    (resolveNextActiveLeaf skips non-ACTIVE parents so can never descend into them)
  - Fix A: `collectActiveDescendants()` recursively finds all ACTIVE descendants regardless of intermediate
    node status; `markGoalGivenUp()` marks each one GIVEN_UP and saves to graph, then does a single `persist()`
  - Fix B: `autoCompleteRootIfSatisfied()` now counts GIVEN_UP alongside COMPLETED for root auto-completion

- [x] **T1-U: LOW_CONFIDENCE over-emission** (`EvaluationAgent`, `PatternSignalAnalyzer`)
  - Two emitters were too aggressive:
  - **`EvaluationAgent`**: fires at strength 1.0 / decay 0.05 whenever `score < 0.75 || STAGNATING`.
    Scores of 0.70–0.74 triggered it every cycle; `withSignal` reinforces on each subsequent cycle → `LOW_CONFIDENCE=7.60` seen in live log.
  - **`PatternSignalAnalyzer.applyFocusDrift`**: fires when diff doesn't touch ANY path referenced by
    open problems — too sensitive with many open problems.
  - Fix: graduated emission by score distance; raised decay rate; tightened `applyFocusDrift` to require majority-miss.

- [x] **T1-V: Dynamic refinement nesting control** (`PlannerAgent`, `GoalPlannerService`)
  - Removed `createRefinementSubgoal()`, `shouldAutoRefine()`, `chooseRefinementTarget()` entirely.
    Stagnation is already handled by `AutobuilderService`; the auto-refinement added no context and was
    the direct cause of "Refine goal: Refine goal: ..." chains.
  - Also fixed the root re-entrancy bug: `applyGoalProgress(GIVEN_UP)` never advanced `currentSubGoalId`,
    so GIVEN_UP goals kept re-entering the cycle as the active subgoal and exhausting repeatedly.
    Fix: after `markGoalGivenUp()`, if the given-up goal was current, advance via
    `findNextSiblingOrAncestor` → `resolveNextActiveLeaf` fallback (mirrors the FULFILLED path).

- [x] **T1-Y: Fingerprint guard bypassed when stagnating** (`ExecutionEligibilityExplainer`)
  - "Already evaluated current evolution fingerprint" blocked re-evaluation even when stagnating,
    preventing the evaluator from generating the STAGNATING signal that drives exhaustion detection
  - Fix: blocker is skipped when `STAGNATION` or `REPETITION_DETECTED` is active

- [x] **T1-AC: `ROLE_EVALUATION` starvation when pending mutation spans multiple cycles** (`RuleBasedDeliberationProposer`)
  - **Observed (2026-03-10 session, C4→C5):** `ROLE_EVALUATION` decayed to zero over 3 cycles without reinforcement. Meta output `Stop: true`; coordinator ran evaluation anyway via non-meta path, but meta's diagnosis was wrong — it said EVALUATION was blocked while it was proceeding.
  - Fix: ensure the `hasCandidate && notYetEvaluated` → `ROLE_EVALUATION += 0.5` injection fires **every cycle** a pending mutation exists, not just once. Gate: `fingerprint != null && fingerprint != lastEvaluatedFingerprint`.

- [x] **T1-AD: Handle `currentSubGoalId is null` as terminal condition** (`GoalPlannerService`, `AdaptiveExecutionEngine`)
  - **Observed (overnight run 2026-03-11):** Logged **5,301 times** over 7+ hours. System never halted.
  - Fix A: when root goal is COMPLETED and `currentSubGoalId == null`, emit `GOAL_EXHAUSTED` and halt.
  - Fix B: when root goal is NOT completed and `currentSubGoalId == null`, allow exactly one re-plan attempt; if it fails, emit `SYSTEM_PAUSED`.
  - Fix C: downgrade log from WARN to INFO for the Fix A case (genuine completion).

- [x] **T1-AF: LLM circuit breaker** (`LLMClient`, `AutobuilderService`)
  - **Observed (overnight run 2026-03-11):** 1,292 consecutive `insufficient_quota` errors over 7.4 hours with no backoff, no pause. The system retried every ~20 seconds until manually killed.
  - Fix: consecutive-failure counter with error type classification:
    - `429 rate_limit_exceeded` → exponential backoff (30s, 60s, 120s, 300s cap), reset counter on success
    - `429 insufficient_quota` / `401` / persistent `5xx` → immediate `SYSTEM_PAUSED` signal, halt scheduler
  - After N consecutive failures (e.g. 5) of any type, emit `SYSTEM_PAUSED` as safety net

- [x] **T1-AG: Signal strength cap** (`Signal`, `AdaptiveExecutionEngine`)
  - **Observed (overnight run 2026-03-11):** `ROLE_EVOLUTION` reached strength 81.95 after 66 cycles.
    Decay rate (~0.10/cycle) negligible vs +1.0/+2.0 injections per cycle. Fitness ranking became static and meaningless.
  - Fix: switched to multiplicative decay (`strength *= 0.90`) with hard cap `MAX_SIGNAL_STRENGTH = 10.0` as safety net. Strong signals decay faster in absolute terms, preventing accumulation while preserving relative ranking.

- [x] **T1-AH: Stagnation circuit breaker** (`AutobuilderService`, `InterpretationService`, `PlannerAgent`, `CycleContextData`)
  - **Observed (overnight run 2026-03-11):** 35 of 66 cycles ended STAGNATING. Interpretation detected stagnation → emitted `REPLAN_REQUESTED` → triggered planner → inject-prune loop → stagnation again. Self-reinforcing cycle.
  - Fix: track consecutive STAGNATING cycles with no merge. After N=5 consecutive stagnating cycles:
    - Suppress `REPLAN_REQUESTED` signals from interpretation (break the feedback loop)
    - Stop creating new subgoals — planner may only prune or remove
  - If N reaches 10, emit `SYSTEM_PAUSED` with human-readable reason.
  - **Known gap:** `RuleBasedDeliberationProposer` also injects `REPLAN_REQUESTED` on stagnation — `stagnationCircuitBreakerActive` flag does not reach it. Not worth fixing until the scenario reproduces.

- [x] **T1-AK: Split GOAL_EXHAUSTED into SUBGOAL_EXHAUSTED vs root exhaustion** (`BasicSignals`, `GoalExhaustionDetector`, `DeliberationService`, `RuleBasedDeliberationProposer`, `AutobuilderService`, `CycleResultState`, `DefaultProblemDetector`)
  - `GoalExhaustionDetector` scoped to subgoals only: short-circuits when `currentSubGoalId` is null/blank/equals `currentGoalId`; always emits `SUBGOAL_EXHAUSTED`.
  - `GOAL_EXHAUSTED` = root only = halt. `SUBGOAL_EXHAUSTED` = recoverable (mark GIVEN_UP + replan).
  - `RuleBasedDeliberationProposer` replan rule targets `SUBGOAL_EXHAUSTED`; `GOAL_EXHAUSTED` returns PAUSE.
  - `DefaultProblemDetector` tracks `SUBGOAL_EXHAUSTED` as WARNING, `GOAL_EXHAUSTED` always BLOCKER.
  - Runaway backstop in `AutobuilderService`: `consecutiveSubgoalExhaustionsWithoutMerge` escalates to `GOAL_EXHAUSTED` + `SYSTEM_PAUSED` after N=5 recoveries without a merge (configurable via `autobuilder.goal.max-consecutive-subgoal-exhaustions`).

- [x] **T1-AQ: Score plateau closure override + ROLE_MERGE decay fix** (`SubGoalFulfillmentHeuristic`, `CycleExecutor`, `ContextLineageTracker`)
  - **Observed (2026-04-01 run):** Score locked at 0.7375 for 11+ consecutive accepted cycles — 1.2% below `MIN_SCORE_FOR_CLOSURE = 0.75`. Files were merged indefinitely with no goal state change.
  - **Root causes:**
    1. `SubGoalFulfillmentHeuristic` had no escape hatch for near-threshold plateau.
    2. `ROLE_MERGE` injected with `decay=0.0` — immortal, causing infinite re-merges without new evolutions.
    3. `ContextLineageTracker` carried `lastEvaluatedEvolutionFingerprint` forward after merge, causing `acceptedEvolutionPending` to fire every cycle for already-merged content.
  - **Fixes:**
    - `SubGoalFulfillmentHeuristic`: plateau-override constants `PLATEAU_OVERRIDE_CYCLES=10`, `MIN_SCORE_FOR_PLATEAU_OVERRIDE=0.65`. After 10+ flat accepted cycles with score ≥ 0.65, return `fulfilled=true` with "plateau-override" rationale.
    - `CycleExecutor`: changed injected `ROLE_MERGE` signal decay `0.0 → 1.0`.
    - `ContextLineageTracker`: if `previousContext.merged() == true`, clear `lastEvaluatedEvolutionFingerprint` to `null` on the new cycle.

- [x] **T1-AR: Exhaust counter correctness — null-subgoal halt + merge-in-cycle reset** (`GoalExhaustionDetector`, `GoalPlannerService`, `AdaptiveExecutionEngine`)
  - **Problem 1 (null-subgoal runaway):** When all subgoals are given up and `currentSubGoalId=null`, `applyGoalProgress(null, GIVEN_UP)` returned without halting — cycle continued. `consecutiveWithoutMerge` incremented to 26 before manual restart.
  - **Fix 1:** When `applyGoalProgress` is called with a null goalId, immediately inject `SYSTEM_HALTED` and stop the scheduler.
  - **Problem 2 (premature give-up despite successful merge):** Subgoals `goal-7527dc1e` and `goal-e29713ca` were evicted with `consecutiveWithoutMerge=1/5` immediately after a cycle where `Merged: true` and `promoted=true` were logged.
  - **Fix 2:** Skip the exhaust eviction check (reset counter to 0) when `context.merged()` is true for the just-completed cycle.
  - **Observed:** 2026-04-01 session.

- [x] **T1-AY: Deduplication snapshot-before-injection bug** (`GoalSimilarityService`, `PlannerAgent`)
  - **Observed:** 2026-04-01 local LLM session — "POST /tasks" silently dropped as a duplicate when no prior goals existed to conflict with it.
  - **Root cause:** `injectOutcomeGoals()` adds goals one-by-one. After "GET /tasks" is persisted, `isDuplicate("POST /tasks")` queries the graph — which now *contains* "GET /tasks". MongeElkan at threshold 0.88 scored both sharing "/tasks" as ≥ 0.88, causing false-positive deduplication within the same injection batch.
  - **Fix:** Snapshot all known descriptions via `goalGraphService.findAllDescriptions(CANDIDATE_LIMIT)` *once* before the injection loop. Check each proposed goal against the snapshot, not the growing graph. Apply the same fix in `planInto()`.

- [x] **T1-B: Prevent post-merge evolution in same cycle** (`CycleExecutor` / `AdaptiveExecutionEngine`)
  - Guard prevents evolution after merge has already occurred in the same cycle pass
  - Eliminates the `merged=true` + unmerged-evolution coexistence bug
  - Implemented in commit b8adb4f on `feature/pre-T2-AE`

- [x] **T1-AV: Skip LLM evaluation when evolution fingerprint is unchanged** (`EvaluationAgent`)
  - `EvaluationAgent.execute()` gates the LLM call on fingerprint change: if `lastEvolutionFingerprint == lastEvaluatedEvolutionFingerprint` and `EVOLUTION_FINGERPRINT_CHANGED` is absent, reuse the prior `EvaluationOutcome` (or REJECTED if none exists).
  - Commit a946968.

- [x] **T1-BB: Analysis agent gate ignores HYBRID classification** (`ExecutionEligibilityExplainer`)
  - Fixed as part of T3-F: lines 149-150 of `ExecutionEligibilityExplainer` check `kind == COGNITIVE || kind == HYBRID`.
  - No separate commit needed; the fix was already present when T1-BB was extracted.

- [x] **T1-BP: Evolution and merge run with no active subgoal (`sub=-`)** (`AdaptiveExecutionEngine`, `EvolutionAgent`, `MergeAgent`)
  - **Observed (2026-04-10 session, cycle-1775778174898):** `sub=-`, `mut=ACCEPTED`, `merged=true` — a mutation was produced and merged with `currentSubGoalId=null`. The merged code is attributed to no goal, cannot be counted toward any criterion, and has no scope guard. This is uncontrolled output that can silently corrupt the output directory.
  - **Root cause:** `EvolutionAgent` and `MergeAgent` do not gate on `currentSubGoalId != null`. When the planner hasn't set a new subgoal yet (gap between GIVEN_UP and the next REPLAN cycle injecting a replacement), evolution still fires if `ROLE_EVOLUTION` signal is strong.
  - **Fix:** In `EvolutionAgent.canExecute()`, add `context.currentSubGoalId() != null` as a hard prerequisite. Similarly guard `MergeAgent` as a safety net. No subgoal = no evolution.
  - **Changes:** `EvolutionAgent.java` (canExecute guard), `MergeAgent.java` (defensive check).

- [x] **T1-BO: `lastEvolutionOutcome` not reset on subgoal change poisons Rule C** (`ContextLineageTracker`)
  - **Observed (2026-04-10 session):** GET /books/{id} (`goal-11a907e4`) was given up after exactly 1 cycle with no merge. Root cause: `GoalExhaustionDetector` Rule C fires on "two consecutive cycles with no evolution output" (`noChanges && noEvolutionLastCycle`). The previous cycle was a repetition spin on GET /books (`mut=null`, `lastEvolutionOutcome=null`). This null carries across the subgoal boundary — so `goal-11a907e4`'s first cycle (which also produced no output in REPLAN mode) triggered Rule C as if it were a second consecutive empty cycle on the same subgoal.
  - **Fix:** In `CycleContextCoordinator.prepareRuntimeContext()`, reset `lastEvolutionOutcome` to `null` (or a sentinel) when `currentSubGoalId` changes — same as `cumulativeCriteriaStatus` is already reset. Rule C should only count consecutive empty cycles within the same subgoal's lifetime.
  - **Changes:** `CycleContextCoordinator.java` — add `lastEvolutionOutcome` to the fields cleared on subgoal change (~2 lines).

- [x] **T1-BS: `lastAttemptId` not cleared on subgoal change → sustained NOOP deadlock** (`CycleContextCoordinator`)
  - **Observed (2026-04-10 17:21 session):** After all 8 planner subgoals were completed/given-up, the T1-AD Fix B re-plan ran with `currentSubGoalId=null`. Evolution fired (`T1-BP` guard was active but this was the sub=- cycle before the guard existed or it slipped through), producing `mut=ACCEPTED, merged=false`. Planner then injected `goal-da8b723f` ("Implement POST /books"). For the next ~30 cycles every agent showed `canExecute=false`: evolution blocked by `"latest evaluation already ACCEPTED, but merge still pending"`, merge blocked by `"missing ROLE_MERGE"`, everything else blocked by their own conditions. The system decayed signals until manually aborted.
  - **Root cause:** `CycleContextCoordinator.prepareRuntimeContext()` carries `lastAttemptId` forward via `toBuilder()` without resetting it on subgoal change. `EvolutionAgent.isAcceptedNotMergedAttempt()` → `safeFetchSnapshot(goalId, newSubGoalId, oldAttemptId)` → Neo4j finds the old ACCEPTED+unmerged attempt by its ID regardless of subgoal scope → returns true → evolution permanently blocked on the new subgoal. `ExecutionEligibilityExplainer.java:287` even has a comment acknowledging the cross-subgoal leakage risk, but the fix (scoping by `attemptId`) was incomplete because `lastAttemptId` itself was never cleared.
  - **Fix:** In `CycleContextCoordinator.prepareRuntimeContext()`, reset `lastAttemptId` to `null` when `subgoalChanged()` — identical pattern to `lastCriteriaPassCount` (T2-BL). With `lastAttemptId=null`, `safeFetchSnapshot` short-circuits at line 291 and `isAcceptedNotMergedAttempt` returns false.
  - **Changes:** `CycleContextCoordinator.java` — one builder clause (~2 lines).

- [x] **T1-BT: Planner re-injects already-completed subgoals during null-subgoal re-plan** (`PlannerAgent`, `GoalSimilarityService`, `PlannerProtocolContract`)
  - **Observed (2026-04-10 17:21 session):** After all subgoals were completed/given-up and `currentSubGoalId` went to null, T1-AD Fix B triggered one re-plan. The planner LLM produced the same plan as the original and injected `goal-da8b723f: "Implement POST /books with title and isbn validation"` — an exact duplicate of the already-COMPLETED `goal-5bdd6716`. `GoalSimilarityService.isDuplicate()` did not block it. Together with T1-BS, this triggered the 30-cycle NOOP deadlock.
  - **Root cause (two factors):**
    1. `goalGraphService.findAllDescriptions()` likely excludes COMPLETED goals, so the dedup check has no reference to compare against.
    2. The T1-BJ NOTE block rewrite ("compare against completed goals; if ALL covered → Goal complete") was in the prompt, but the planner LLM ignored it and re-proposed already-completed work anyway.
  - **Candidate fixes:**
    - A: `findAllDescriptions()` in Neo4j — include COMPLETED nodes, not just ACTIVE. Dedup then catches the re-injection at the similarity check.
    - B: In the NOTE block (when `currentSubGoalId == null`), explicitly list the full set of completed subgoal descriptions so the LLM cannot overlook them.
    - C: In `PlannerAgent.autoInjectSubGoals()`, after dedup, also check against `goalPlannerService.getCompletedGoals()` descriptions directly before injecting.
  - **Fix applied:** `findAllDescriptions` already includes COMPLETED nodes (`status <> 'GIVEN_UP'`), so fix A was not needed. The actual gap: pruned goals are marked GIVEN_UP (not COMPLETED) by `processRemovals`, excluded from `findAllDescriptions`, and have only 1 GIVEN_UP match (below the retry limit of 2). Fix: `PlannerAgent.buildKnownDescriptions()` augments the dedup snapshot with descriptions of all GIVEN_UP goals where `mergeCount > 0` — these were pruned after successful work and must not be re-injected.
  - **Changes:** `PlannerAgent.java` — new `buildKnownDescriptions()` helper; both `injectOutcomeGoals()` and `planInto()` injection loops use it instead of `snapshotKnownDescriptions()` directly.

- [x] **T1-BV: Stale `SUBGOAL_EXHAUSTED` signal after mid-cycle planner removal immediately kills the next subgoal** (`PlannerAgent`, `CycleExecutor`)
  - **Observed (2026-04-10 21:52 session):** GET /books (`goal-742ee470`) was given up immediately without a single fair cycle. Cycle `1775850783759` started with POST /books as the active subgoal. GoalExhaustionDetector fired `SUBGOAL_EXHAUSTED` for POST /books at cycle start (step A of `DeliberationService.deliberate`), emitting the signal onto the context. The meta-deliberation returned mode=REPLAN (because `SUBGOAL_EXHAUSTED >= 0.75`). The planner ran as meta-step 1 and used `#SubGoalRemove` to prune POST /books (`Planner pruned subgoal [goal-c3c65cb6]`), then advanced the active subgoal to GET /books (`Advanced current subgoal to [goal-742ee470]`). The `SUBGOAL_EXHAUSTED` signal — emitted for POST /books — was never cleared from the context after the subgoal switch. At the end of the cycle, `CycleExecutor.deriveCycleResult` saw `hasSignal(SUBGOAL_EXHAUSTED)` → returned `SUBGOAL_EXHAUSTED`. `AutobuilderService` then marked GET /books (the new active subgoal) as `GIVEN_UP` with `consecutiveWithoutMerge=1/5`. Subsequent subgoals triggered the same pattern, contributing to the exhaustion of all 8 subgoals.
  - **Root cause:** `deriveCycleResult` uses `hasSignal(SUBGOAL_EXHAUSTED)` with no check on which subgoal the signal was emitted for. When the planner switches the active subgoal mid-cycle (via `#SubGoalRemove` + `promoteCurrentSubGoal`), the stale signal from the old subgoal remains on the context and causes the NEW subgoal to be immediately given up.
  - **Fix:** When `PlannerAgent` detects it has changed the active subgoal (i.e. after `processRemovals` promotes to a new subgoal ID), it should clear `SUBGOAL_EXHAUSTED` from the returned context. Alternatively, `deriveCycleResult` could record the subgoal ID at the start of the cycle and only honour `SUBGOAL_EXHAUSTED` if the active subgoal hasn't changed. The PlannerAgent approach is cleaner because the signal is explicitly associated with a subgoal that is now gone.
  - **Changes:** `PlannerAgent.java` — after `processRemovals`, if `currentSubGoalId` changed, call `context.withoutSignal(SUBGOAL_EXHAUSTED)` on the returned context (~3 lines).

- [x] **T1-BW: `GoalExhaustionDetector` Rule A fires after a single stagnating evaluation — no minimum evidence gate** (`GoalExhaustionDetector`)
  - **Observed (2026-04-10 21:52 + 22:56 sessions):** Every subgoal was exhausted after only 2 cycles. Specifically in the 22:56 session: `goal-a3421b2c` (GET /books) ran its first cycle as STAGNATING (line 195247, `result=STAGNATING, merged=true`). On the second cycle (line 195873), GoalExhaustionDetector read `stagnatingBySignal=true`, `lowEntropy=true` (1 file changed), `repetition=true`, `criteriaProgressing=false` → Rule A fired at `exhaustedByStagnationCombo=true`, confidence 0.75 → `SUBGOAL_EXHAUSTED`. At that point `cumulativeStagnatingCycles=1`. With the proposed `>= 2` gate, this would have been suppressed.
  - **Root cause:** Rule A's only suppression mechanism is `criteriaProgressing` (T2-BL fix). But on the first stagnating evaluation of a new subgoal, criteria haven't had time to progress — `criteriaProgressing = false` even when the subgoal is genuinely being worked on. A single stagnating evaluation with one-file entropy is enough to exhaust the subgoal. There is no minimum cycle count or merge count gate.
  - **Fix:** Add a minimum evidence requirement to Rule A. Require `cumulativeStagnatingCycles >= 2` before Rule A can fire. This means GoalExhaustionDetector needs at least 2 recorded stagnating cycles (from `AutobuilderService.STAGNATING` case, which increments `cumulativeStagnatingCycles`) before declaring Rule A exhaustion. One stagnating evaluation is noise; two in a row is a pattern. Alternatively, require `stagnatingBySignal` instead of `stagnatingByState || stagnatingBySignal` — this ensures the STAGNATION signal must have been present at the start of the cycle (i.e. emitted in a PREVIOUS cycle), giving every subgoal at least a 1-cycle delay before Rule A can fire.
  - **Note:** `cumulativeStagnatingCycles` is incremented by `AutobuilderService.STAGNATING` handler and stored in the subgoal entity, then read back via `goalPlannerService.getGoalMetadataInt`. It is reset to 0 on subgoal change (via entity isolation). GoalExhaustionDetector reads it from `context.cumulativeStagnatingCycles()`, which is populated from the entity at cycle start by `CycleContextCoordinator`. At the start of a stagnating cycle, the value reflects cycles recorded from prior activations — the increment for the current cycle happens later in `AutobuilderService`.
  - **Changes:** `GoalExhaustionDetector.java` — add `cumulativeStagnatingCycles >= 2` guard to Rule A (~1 line change inside `exhaustedByStagnationCombo` computation). Update tests.

- [x] **T1-BX: Stale `STAGNATION` signal from the previous subgoal causes Rule C to fire on the first cycle of a new subgoal** (`PlannerAgent`, `GoalExhaustionDetector`)
  - **Observed (2026-04-10 22:56 session):** Lines 201188, 203183, 204794, 206791 — every subgoal created after the first is immediately exhausted on its very first cycle (`mut=null, merged=false, result=SUBGOAL_EXHAUSTED`). Rule C condition: `noChanges=true` (no evolution yet — correct), `noEvolutionLastCycle=true` (T1-BO resets `lastEvolutionOutcome` on subgoal change), `stagnatingBySignal=true` ← stale `STAGNATION` signal from the prior subgoal. `STAGNATION` decays at 0.2/cycle, so starting at strength 0.7 it is still ≥ 0.50 on cycle 1 of the new subgoal and satisfies the `hasSignal(..., 0.50)` check in `GoalExhaustionDetector`.
  - **Root cause:** T1-BV clears `SUBGOAL_EXHAUSTED` when `processRemovals` advances to a new subgoal, but does not clear `STAGNATION`. The `STAGNATION` signal carries over and makes Rule C fire before the new subgoal has had any chance to produce evolution output.
  - **Fix:** In `PlannerAgent`, alongside the existing `withoutSignal(SUBGOAL_EXHAUSTED)` block (T1-BV), also call `context.withoutSignal(BasicSignals.STAGNATION)` when the active subgoal has changed after `processRemovals`. This matches the T1-BV pattern exactly — one additional `withoutSignal` call.
  - **Changes:** `PlannerAgent.java` — extend the T1-BV subgoal-change block to also clear `STAGNATION` (~1 line). Update `PlannerAgentTest` with a companion test.

- [x] **T1-BZ: `autoCompleteRootIfSatisfied` marks root COMPLETED when majority of children are GIVEN_UP, not fulfilled** (`GoalPlannerService`)
  - **Observed (2026-04-10 23:50 session):** After all subgoals were exhausted and PLANNING_THROTTLED blocked recovery, `currentSubGoalId` became null. `autoCompleteRootIfSatisfied` found no active children and auto-completed the root goal — even though only 4 of 7 subgoals were genuinely COMPLETED; the rest were GIVEN_UP. The system then emitted GOAL_EXHAUSTED with the root incorrectly recorded as done.
  - **Root cause:** `autoCompleteRootIfSatisfied` fires when there are no remaining ACTIVE children, regardless of whether they ended in COMPLETED or GIVEN_UP. It treats "nothing left to do" as "everything succeeded."
  - **Fix:** In `autoCompleteRootIfSatisfied`, check the completion ratio of children: only auto-complete the root if a strict majority (>50%) of its direct children are COMPLETED (not GIVEN_UP). If a majority are GIVEN_UP and none are ACTIVE, escalate to GOAL_EXHAUSTED instead of marking COMPLETED. A configurable threshold (e.g. `autobuilder.goal.min-completion-ratio: 0.5`) would make this tuneable.
  - **Changes:** `GoalPlannerService.java` — modify `autoCompleteRootIfSatisfied` to count COMPLETED vs GIVEN_UP children and branch accordingly. Update `GoalPlannerServiceTest` with cases for majority-GIVEN_UP (→ GOAL_EXHAUSTED) and majority-COMPLETED (→ COMPLETED).

- [x] **T1-BY: Merge-suppression guard (T1-BP) creates an immortal-subgoal deadlock when score plateaus** (`AutobuilderService`)
  - **Observed (2026-04-10 22:56 session):** Lines 195873–199752 — `goal-a3421b2c` (GET /books) produced 7 consecutive cycles of `mut=ACCEPTED, merged=true, result=SUBGOAL_EXHAUSTED`. Each cycle: GoalExhaustionDetector fires → `AutobuilderService` SUBGOAL_EXHAUSTED case → "SUBGOAL_EXHAUSTED but merge occurred — suppressing eviction" (T1-BP guard). The subgoal is therefore never evicted, never fulfilled (criteria not met; score on a plateau), and Rule A fires again next cycle. The planner eventually removes it explicitly with `#SubGoalRemove` after 7 wasted LLM cycles.
  - **Root cause:** T1-BP unconditionally suppresses eviction whenever a merge occurred in the same cycle. There is no cap on how many consecutive times this suppression can fire. A subgoal that merges accepted-but-plateau mutations indefinitely will never advance.
  - **Fix:** Track `consecutiveSuppressedEvictions` in the subgoal entity (similar to `cumulativeStagnatingCycles`). In the `SUBGOAL_EXHAUSTED` case of `AutobuilderService`, suppress eviction only if `consecutiveSuppressedEvictions < N` (suggested N=3). After N suppressions, allow eviction to proceed even if a merge occurred. This ensures a deadlocked subgoal eventually exits. Reset the counter to 0 on any successful fulfillment or on subgoal change.
  - **Changes:** `AutobuilderService.java` — add suppression counter read/increment/compare in the SUBGOAL_EXHAUSTED merge-suppression branch. `GoalPlannerService` / `GoalEntity` — add `consecutiveSuppressedEvictions` metadata field. Update tests.

- [x] **T1-CB: `getCurrentSubGoal()` clobbers T1-BZ GIVEN_UP root to COMPLETED — session terminates as GOAL_FULFILLED instead of GOAL_EXHAUSTED** (`GoalPlannerService`)
  - **Observed (2026-04-11 04:35 session):** T1-BZ correctly marked root GIVEN_UP and injected GOAL_EXHAUSTED into `GoalPlannerService.currentCycleContext`. Then `CycleExecutor.applyCompletion()` called `getCurrentSubGoal()` with `currentSubGoalId == null`. The guard in `getCurrentSubGoal()` only checked for `COMPLETED`; `GIVEN_UP` fell through to the else branch → `root.setStatus(COMPLETED)`. T1-BH then saw COMPLETED root and returned `GOAL_FULFILLED`. The system logged "Goal fulfilled. Pausing Autobuilder." for a session that actually exhausted.
  - **Root cause:** `getCurrentSubGoal()` treats any non-COMPLETED root with `currentSubGoalId == null` as "still active, needs auto-completion." It has no guard for the GIVEN_UP terminal state set by T1-BZ.
  - **Fix:** Add `else if (root.getStatus() == GIVEN_UP)` branch before the auto-complete else, logging the terminal state and returning null without any clobber.
  - **Changes:** `GoalPlannerService.java` — add GIVEN_UP guard in `getCurrentSubGoal()`. `GoalPlannerServiceTest` — two new tests: GIVEN_UP root stays GIVEN_UP; COMPLETED root stays COMPLETED.

- [x] **T1-CA: `getRemainingGoals()` returns children of GIVEN_UP parents — orphaned children create endless cascade** (`GoalPlannerService`, `PlannerAgent`)
  - **Observed (2026-04-11 session):** After ~02:29, the AnalysisAgent injected child subgoals (e.g. `goal-122df0e9` under `goal-3a74f84a`, `goal-c64f40a6` under `goal-122df0e9`, etc.) which the planner then pruned via `#SubGoalRemove`. Because `markGoalGivenUp(parentId)` only updates the parent, the ACTIVE children remained visible to `getRemainingGoals()`. The pointer advanced to each orphaned child in turn; the AnalysisAgent then injected another child under it; that child was pruned; repeat. The chain produced a new goal every ~30 seconds with no terminal condition: `goal-3a74f84a → goal-122df0e9 → goal-c64f40a6 → goal-e37e7215 → ...`
  - **Root cause:** `getRemainingGoals()` at `GoalPlannerService.java:413` filters on `status == ACTIVE && type == SUBGOAL` with no check on parent status. When a parent is given up, its ACTIVE children survive in the goal map and are returned as normal remaining goals.
  - **Fix applied (2026-04-12):** Two-part fix:
    1. `PlannerAgent.java` — after `processRemovals()`, if backlog is empty and `afterRemoval.getStatus() == GIVEN_UP`, compute a local `effectiveParentId = null` and pass it to `injectOutcomeGoals()` instead of `context.currentSubGoalId()`. Context is not mutated so `evolveGraph` and `refreshGoalState` downstream still see the original (correct) ID.
    2. `GoalPlannerService.addSubGoal()` — defensive guard: if parent status is `GIVEN_UP`, log warn and return early. Catches any caller that bypasses the PlannerAgent fix.
  - **Verified (2026-04-13 00:52 session):** "injecting at root to prevent nesting under dead parent" logged 4× with no multi-generation cascade. Cascade-of-existing-children also fired correctly at 00:54:44.
  - **Depends on:** None. Prerequisite for T2-BW (the cascade makes injection rate control meaningful — without it, budgets reset on every orphaned child).

- [x] **T1-BU: Stagnation circuit breaker re-activates during null-subgoal T1-AD recovery window** (`AutobuilderService`)
  - **Observed (2026-04-10 20:07 session):** After all subgoals were given up, `currentSubGoalId` became null. T1-AD Fix B injected `ROLE_PLANNER` for a re-plan, but this window produced STAGNATING cycles (no merge possible when there's no subgoal). Each STAGNATING cycle incremented `consecutiveStagnatingCyclesWithoutMerge`. When it hit `STAGNATION_BREAKER_THRESHOLD`, the circuit breaker was set to true — and `RuleBasedDeliberationProposer` returned `NOOP+DIVERGE` for every subsequent cycle, permanently blocking the re-plan and creating a 248-cycle NOOP loop until the run was manually stopped.
  - **Root cause:** The circuit-breaker counter was incremented unconditionally on every STAGNATING result, even when `currentSubGoalId == null`. With no active subgoal no agent can produce a merge, so stagnation during this window is a structural inevitability (not a stuck subgoal), and counting it corrupts the circuit breaker.
  - **Fix:** In the `STAGNATING` case handler in `AutobuilderService`, skip the circuit-breaker increment (and recovery signal injection) when `currentSubGoalId == null`. Also skip the `incrementGoalMetadataInt("cumulativeStagnatingCycles")` call for the same reason.
  - **Changes:** `AutobuilderService.java` — add early `break` in the STAGNATING case when `currentSubGoalId == null` (with `log.debug` for traceability); move the `incrementGoalMetadataInt` call inside the non-null guard.
  - **Tests:** Updated `runAutobuilderCycle_whenStagnating_injectsRecoverySignalsAndContinues` to use a non-null subgoal (the normal case). Added `runAutobuilderCycle_whenStagnatingWithNullSubgoal_skipsCircuitBreakerAndContinues` for the null-subgoal path.

- [x] **T1-CD: `FileVariantCollector` merges non-ACCEPTED cycles — REJECTED mutations overwrite correct output** (`FileVariantCollector`)
  - **Observed (2026-04-13 19:29 session):** `FileVariantCollector.collect()` scans every unmerged `mutations/<cycleId>/` folder without checking the cycle's acceptance state. REJECTED mutations are collected and processed by `MergeProcessor` alongside ACCEPTED ones. When `MergeProcessor` picks the best variant by score, a high-scoring-but-REJECTED mutation can outrank a lower-scoring-but-ACCEPTED one and win, overwriting correct output with rejected code.
  - **Root cause:** `collect()` reads `ctx.merged()` (already merged?) and `meta.isValid()` (non-null subgoal?) but never reads `ctx.mutationState()`.
  - **Fix:** Added `mutationState != ACCEPTED` filter in `collect()` and `getAllVariantPaths()`. Removed the redundant `mutationState` check from `ExecutionEligibilityExplainer` merge case (superseded by `hasAnyVariant()` which now only sees ACCEPTED variants). Fixed `CycleExecutor.applyMergeOnAcceptedMutation()` to check `acceptedEvolutionPending` before the `mutationState` guard (the pending path was dead code — blocked by the guard it was supposed to bypass). Added SKIPPED merge registration in `MergeAgent` when no ACCEPTED variants are found, to keep the graph in sync and prevent `isAcceptedNotMergedAttempt` from getting stuck. Added `hasAnyVariant()` guard on the evolution blocker in `ExecutionEligibilityExplainer` as a defensive backstop against graph/filesystem drift.
  - **Depends on:** T5-BA (already prevents empty-mutation folders, reduces scan noise).
  - **Enables:** T1-CE (scoping by subgoal ID — only meaningful once non-accepted cycles are filtered out).

- [x] **T1-CE: Merge batch not scoped to current subgoal — stale mutations from given-up subgoals silently merged** (`FileVariantCollector`, `MergeAgent`)
  - **Observed (2026-04-13 19:29 session):** When `MergeAgent` fires for subgoal B, `FileVariantCollector.getAllVariantPaths()` picks up ALL unmerged cycle folders — including cycles produced under subgoal A (which may be GIVEN_UP). Those mutations are merged under B's context. In the session: goal-70beb74c accumulated 20 merge payloads from prior cycles that belonged to entirely different subgoals. The output may be correct by coincidence (MergeProcessor picks highest score), but the correctness is accidental.
  - **Root cause:** `collect()` populates `SubgoalMetadata` from `ctx.currentSubGoalId()` and calls `meta.isValid()` — but never compares against the *active* subgoal. Any valid (non-null) subgoal ID passes through.
  - **Fix:** Pass `context.currentSubGoalId()` into `collect()` and `getAllVariantPaths()`. In `collect()`, after constructing `meta`, skip if `!meta.subgoalId().equals(activeSubGoalId)`. Add an overload or parameter to `getAllVariantPaths()` to apply the same filter. This ensures each merge run only processes mutations produced for the subgoal currently being merged.
  - **Edge case:** Bootstrap cycles (no subgoal ID) retain current behaviour (merge all valid variants) — the null/blank guard already handles this.
  - **Changes:** `FileVariantCollector.java` — add `activeSubGoalId` parameter to `collect()` and `getAllVariantPaths()`; add equality filter. `MergeAgent.java` — pass `context.currentSubGoalId()` at call sites. Update `FileVariantCollectorTest`.
  - **Depends on:** T1-CD (acceptance-state filter — implement first; subgoal scoping on top of it is cleaner).
  - **Note (2026-04-13):** The large batches (30 merges for goal-1fc8e3c8, 20 for goal-70beb74c) observed in the 19:29 session are a direct consequence of this missing filter. T1-CD alone reduces batch size; T1-CE removes cross-subgoal contamination.

- [x] **T1-CF: SUBGOAL_EXHAUSTED with all-criteria-met should FULFILL, not GIVE_UP** (`AutobuilderService`, `GoalPlannerService`)
  - **Observed (2026-04-13 19:29 session):** `LoanController.getLoansByMember` and `LoanService.getLoansByMember` were fully implemented and correct by 19:33. Evolution could not produce new diffs (nothing left to change). The system read this as `REPETITION_DETECTED` → `SUBGOAL_EXHAUSTED` → `GIVEN_UP`. The subgoal for `GET /loans?memberId={id}` was marked GIVEN_UP despite the feature being complete. Subsequent planner cycles re-injected test variants of the same endpoint, causing the churn described in T2-CC.
  - **Root cause:** `AutobuilderService` handles `SUBGOAL_EXHAUSTED` → `GIVEN_UP` without checking whether the subgoal's acceptance criteria are already cumulatively satisfied. `cumulativeCriteriaStatus` (T1-BG) is available and tracks exactly this — but it is never consulted in the exhaustion path.
  - **Fix:** In `AutobuilderService`'s `SUBGOAL_EXHAUSTED` case, before calling `applyGoalProgress(GIVEN_UP)`, check `goalPlannerService.areCriteriaCumulativelySatisfied(subGoalId)`. If all criteria are met (or no criteria were defined — use a `mergeCount > 0` guard as a proxy), call `applyGoalProgress(FULFILLED)` instead of `GIVEN_UP`. Log the promotion explicitly ("SUBGOAL_EXHAUSTED but all criteria met — promoting to FULFILLED instead of GIVEN_UP").
  - **Implementation note:** `areCriteriaCumulativelySatisfied(goalId)` does not yet exist on `GoalPlannerService`; add it to read `cumulativeCriteriaStatus` from the goal entity's metadata (T3-BI persists this). For goals with no criteria, fall back to: `mergeCount > 0 && consecutiveSuppressedEvictions == 0` (there were real merges but nothing further to do).
  - **Changes:** `AutobuilderService.java` — add criteria-check guard before `GIVEN_UP` in SUBGOAL_EXHAUSTED case (~8 lines). `GoalPlannerService.java` — add `areCriteriaCumulativelySatisfied(String goalId)`. `AutobuilderServiceTest` — test for exhausted-but-fulfilled promotion.
  - **Depends on:** T1-BG (cumulativeCriteriaStatus), T3-BI (persistence of cumulative criteria state), T1-BY (suppression cap — must be checked to avoid immortal subgoals).
  - **Enables:** T2-CC (the churn is caused by GIVEN_UP on fulfilled subgoals spawning recovery cycles — this removes the root cause).

- [x] **T1-CG: Evolution fingerprints bleed across subgoal boundaries — evaluation blocked on every promoted subgoal** (`ContextLineageTracker`)
  - **Observed (2026-04-13 session):** After a subgoal is promoted, `lastEvolutionFingerprint` and `lastEvaluatedEvolutionFingerprint` from the completed subgoal survive into the next cycle. On the new subgoal, evolution hasn't run yet so both fingerprints are equal (matching a prior subgoal's mutation). The evaluation eligibility guard (`alreadyEvaluatedThisFingerprint && !stagnationPresent && !hasUnevaluatedPriorEvolution`) fires, blocking evaluation. The new subgoal immediately stagnates: evolution runs (producing a new fingerprint), but a REJECTED result triggers GIVEN_UP before the system can stabilise. This produced a rapid GIVEN_UP cascade after every subgoal promotion.
  - **Root cause:** `ContextLineageTracker.enrichLineage()` already cleared fingerprints on `merged()` (to prevent spurious EVALUATE after merge), but did not clear them on subgoal change — despite `lastEvolutionOutcome` having the same clear-on-subgoal-change logic since T1-BO.
  - **Fix:** In `enrichLineage()`, apply the same `subgoalChanged()` condition to both `withLastEvolutionFingerprint` and `withLastEvaluatedEvolutionFingerprint` — one-liner change mirroring the T1-BO pattern. Added two tests: `subgoalChanged_clearsLastEvolutionFingerprint` and `subgoalUnchanged_carriesFingerprints`.
  - **Changes:** `ContextLineageTracker.java`, `ContextLineageTrackerTest.java`.

- [x] **T1-CH: Evolution fingerprints not cleared on T1-CA injection — scope guard blocks every new subgoal with stale diff** (`ContextLineageTracker`, `PlannerAgent`)
  - **Observed (2026-04-13 23:09 session):** After `goal-cf57d840` (GET /loans?memberId=) stagnated and was GIVEN_UP, T1-CA fired and injected 3 new subgoals at root. The evolution fingerprint from the dead subgoal's last mutation (loan-test code) survived into the new subgoal contexts. Every new cycle: scope guard evaluated the stale diff against the new subgoal → `OUT_OF_SCOPE` → `SUBGOAL_SCOPE_MISMATCH` signal blocked evaluation. `mut=null` cascade. After 3 consecutive `OUT_OF_SCOPE` assessments, `ScopeThrashSignalAnalyzer` gave up the new subgoal too, triggering another T1-CA injection. This repeated until the stagnation circuit breaker paused the system at 10 consecutive cycles without a merge.
  - **Root cause:** T1-CG clears `lastEvolutionFingerprint` and `lastEvaluatedEvolutionFingerprint` in `ContextLineageTracker.enrichLineage()` when the subgoal changes between cycles. But T1-CA injection happens *within* a cycle (during `PlannerAgent` execution); the newly-injected subgoal becomes current mid-cycle. The fingerprint-clearing logic in `ContextLineageTracker` only runs at cycle boundary (between `previous` and `next` context), so it never fires for within-cycle subgoal changes produced by T1-CA.
  - **Fix:** After T1-CA injects new subgoals and advances `currentSubGoalId`, the context must be treated as if the subgoal just changed. Two options: (A) emit a synthetic signal (e.g. `SUBGOAL_CHANGED`) from `PlannerAgent` after T1-CA injection, and have `ContextLineageTracker` clear fingerprints when it sees that signal regardless of whether cycle boundary is crossed; or (B) clear fingerprints directly in `GoalPlannerService.promoteCurrentSubGoal()` / the T1-CA injection path by nulling the relevant fields on the live context via `CycleContextData`. Option B is simpler — `PlannerAgent` already has access to `context` and can call a new `context.clearEvolutionFingerprints()` helper after T1-CA injection.
  - **Changes:** `CycleContextData.java` (add `clearEvolutionFingerprints()` or equivalent), `PlannerAgent.java` (call it in T1-CA injection block), tests for both paths.
  - **Depends on:** T1-CG (already done — same fingerprint fields; this ticket extends the same clearing to a second trigger point).
  - **Enables:** T1-CA injection to actually give new subgoals a clean evolution slate instead of inheriting a stale out-of-scope diff.

- [x] **T1-CI: Unachievable guard re-admits paraphrased GIVEN_UP subgoals — planner re-injects semantically identical work** (`PlannerAgent`, `GoalSimilarityService`)
  - **Observed (2026-04-13 23:09 session):** After "Refactor LoanService to support member-based loan retrieval and ensure shared state access" (`goal-81af14c0`) was GIVEN_UP at 23:09:57, the planner re-injected the exact same description as `goal-8f87762c` at 23:13:04. `countGivenUpMatches` returned 1 for both injections — but `GIVEN_UP_RETRY_LIMIT = 2` requires ≥ 2 prior given-up matches before blocking, so the first re-injection always slips through. Similarly, paraphrased variants (e.g. "Implement GET /loans?memberId={id} in LoanController..." ↔ "Implement GET /loans?memberId={id} endpoint in LoanController with proper request mapping...") score ≥ 0.88 on Monge-Elkan and are correctly identified by `countGivenUpMatches`, but again one prior match is not enough to block.
  - **Root cause:** `GIVEN_UP_RETRY_LIMIT = 2` in `PlannerAgent`. One prior GIVEN_UP is insufficient to block; the intent was to allow a single retry in case the first attempt was due to a transient context problem. In practice this just doubles the churn for every unachievable subgoal.
  - **Fix:** Lower `GIVEN_UP_RETRY_LIMIT` from `2` to `1`. A subgoal that has been GIVEN_UP once (exact or semantic match via `GoalSimilarityService`) should not be re-injected. The existing semantic matching in `countGivenUpMatches` (Monge-Elkan 0.88) already handles paraphrasing correctly — the threshold is the only thing that needs changing. If genuine retries are needed for transient failures, the appropriate gate is the stagnation circuit breaker or a more context-sensitive retry signal, not a blanket retry count.
  - **Changes:** `PlannerAgent.java` — change `GIVEN_UP_RETRY_LIMIT = 2` to `GIVEN_UP_RETRY_LIMIT = 1`. Update any comments or tests that reference the old limit.
  - **Note:** `GoalSimilarityService.countGivenUpMatches()` already uses Monge-Elkan similarity at 0.88 — no change needed there. This is a one-line constant change plus test update.

- [x] **T1-CJ: `getCurrentSubGoal()` auto-completes root to COMPLETED when all subgoals are GIVEN_UP via scope thrash** (`GoalPlannerService`)
  - **Observed (2026-04-13 23:47 session):** After `ScopeThrashSignalAnalyzer` gave up goal-242adcb4 (the last active subgoal), `currentSubGoalId` became null. On the next `getCurrentSubGoal()` call, root was still ACTIVE. The else branch at line 469–471 fired: `log.warn("no active subgoals remain; setting root to completed")` → `root.setStatus(COMPLETED)`. `CycleContextCoordinator` then injected `GOAL_EXHAUSTED` and the system paused. Root was recorded as COMPLETED despite 5 of 9 subgoals being GIVEN_UP and the implementation being incomplete.
  - **Root cause:** `getCurrentSubGoal()` has its own inline auto-complete path that is separate from `autoCompleteRootIfSatisfied()`. It fires whenever `currentSubGoalId == null`, root is ACTIVE, and `!replanPending` — with no check on the COMPLETED/GIVEN_UP ratio of children. T1-BZ added the ratio guard to `autoCompleteRootIfSatisfied()`, but `getCurrentSubGoal()`'s inline set bypasses it entirely. The `!replanPending` guard would have prevented this if `REPLAN_REQUESTED` were present, but the signal emitted by T2-AM (scope thrash detection) had decayed before the final give-up occurred.
  - **Fix:** Replace `root.setStatus(GoalStatus.COMPLETED)` at line ~471 with a call to `autoCompleteRootIfSatisfied()`. The `!replanPending` guard stays in place as the fast-path bypass; when not blocked, the decision is delegated to the existing T1-BZ ratio-check logic. Majority-GIVEN_UP sessions will then escalate to GIVEN_UP root + GOAL_EXHAUSTED rather than incorrectly marking COMPLETED.
  - **Changes:** `GoalPlannerService.java` — one-line change at ~line 471; update the warning log to reflect that auto-complete is now delegated. Update `GoalPlannerServiceTest` with a case: `currentSubGoalId == null` + majority-GIVEN_UP direct children → root ends GIVEN_UP (not COMPLETED).
  - **Depends on:** T1-BZ (done — `autoCompleteRootIfSatisfied` is the target). T1-CB (done — guards against re-clobbering a GIVEN_UP root).

- [x] **T1-CL: T1-BS incomplete — `lastAttemptId` not cleared on mid-cycle subgoal promotion** (`CycleContextCoordinator`)
  - **Observed (2026-04-14 21:43 session):** Evolution blocked on POST /members with `"Skipping evolution: current attempt is ACCEPTED but not merged yet (attemptId=cycle-1776195866587)"` for 8+ consecutive cycles. T1-BS's fix (clear `lastAttemptId` on subgoal change) did not fire because subgoal promotion happened mid-cycle: when GET /books/{id} was marked FULFILLED within cycle `cycle-1776195881135`, the context's `currentSubGoalId` was updated to POST /members before the cycle ended. The next cycle's `CycleContextCoordinator` compared `previous.currentSubGoalId` (already POST /members) against the current subgoal (also POST /members) → `subgoalChanged() = false` → `lastAttemptId` not cleared → stale attempt ID carried forward.
  - **Root cause:** `subgoalChanged()` compares subgoal IDs but cannot detect a promotion that happened within the previous cycle, because the promoted ID is already on `previous` by the time the next cycle starts.
  - **Fix:** In `CycleContextCoordinator.prepareRuntimeContext()`, also clear `lastAttemptId` when `previous.subgoalPromoted() == true`:
    ```java
    .lastAttemptId((subgoalChanged(previous, subgoal) || (previous != null && previous.subgoalPromoted())) ? null
            : (previous != null ? previous.lastAttemptId() : null))
    ```
    `subgoalPromoted` is already set to `true` in the cycle that performs the promotion and reset to `false` at the start of every new context build (line 82), so this condition fires exactly once on the cycle immediately following a promotion.
  - **Changes:** `CycleContextCoordinator.java` — one-line condition extension (~1 line).

- [x] **T1-CM: `EvolutionAgent.execute()` has redundant `isAcceptedNotMergedAttempt` guard that disagrees with eligibility system** (`EvolutionAgent`)
  - **Observed (2026-04-14 21:43 session):** Meta deliberation correctly reported `EVOLUTION: canExecute=true` (the eligibility check's `hasAnyVariant` backstop from T1-CD saw no POST /members variants on disk). Meta steps then called `EvolutionAgent.execute()`. The agent's own guard at line 52 independently re-ran `isAcceptedNotMergedAttempt` — without the `hasAnyVariant` backstop — and blocked evolution, overriding the eligibility system's correct answer.
  - **Root cause:** `EvolutionAgent.execute()` has a duplicate `isAcceptedNotMergedAttempt` check that predates T1-CD's `hasAnyVariant` backstop and was never updated. It is now strictly less accurate than the eligibility system's version in `ExecutionEligibilityExplainer`. Agents should trust that if they are called, eligibility has already approved them — a secondary veto inside `execute()` breaks that contract.
  - **Fix:** Remove the `isAcceptedNotMergedAttempt` guard at lines 52–56 of `EvolutionAgent.execute()` entirely. Rely solely on `ExecutionEligibilityExplainer.explainBlockers("evolution", ...)`, which already has the `hasAnyVariant` backstop and is the single source of truth for evolution eligibility.
  - **Changes:** `EvolutionAgent.java` — delete the early-return block (~5 lines) and the private `isAcceptedNotMergedAttempt(CycleContextData)` helper (~15 lines). `EvolutionAgentTest` — remove any tests that directly exercise the removed guard; confirm eligibility-level tests in `ExecutionEligibilityExplainerTest` cover the scenario.
  - **Note:** T1-CL is the root-cause fix; T1-CM removes the structural inconsistency. Together they eliminate both the stale `lastAttemptId` problem and the duplicate veto that amplified it.

- [x] **T1-CN: Root completion blocked by stale `REPLAN_REQUESTED` when all direct subgoals are terminal** (`GoalPlannerService`)
  - **Observed (2026-04-14 22:16 session):** All 8 original planner subgoals completed in ~3 minutes. When the last one (`goal-ff4657d6`) was fulfilled, `REPLAN_REQUESTED=0.80` was still alive in context (set by a stagnation event on the preceding subgoal). `promoteCurrentSubGoal()` hit `currentSubGoalId == null`, saw `replanPending = true`, and skipped `autoCompleteRootIfSatisfied()`. With remaining goals = 0 and all children COMPLETED, the root should have been declared done. Instead, the planner fired, saw the empty backlog, and re-injected 2 more loan subgoals — triggering the analysis decomposition chain described in T1-CO.
  - **Root cause:** The `!replanPending` guard in the `currentSubGoalId == null` branch was introduced by T1-BK to handle a deduplication-blocked injection case, not genuine completion. When the backlog is empty because all children finished, the guard is incorrectly blocking completion.
  - **Fix:** In `GoalPlannerService.promoteCurrentSubGoal()`, in the `currentSubGoalId == null` branch, before checking `replanPending`, call `autoCompleteRootIfSatisfied()` unconditionally if all direct children of the root are terminal (COMPLETED or GIVEN_UP). If `autoCompleteRootIfSatisfied()` marks the root as COMPLETED or GIVEN_UP, return immediately — the REPLAN signal is irrelevant when the backlog is genuinely empty. The `!replanPending` guard should only block completion when `getSubGoals(root)` still contains ACTIVE entries (i.e., the "null" is from dedup blocking injection, not real completion).
  - **Changes:** `GoalPlannerService.java` — in the `else` branch at ~line 487: add an early-exit `if (allDirectChildrenTerminal()) { autoCompleteRootIfSatisfied(); return null; }` before the `replanPending` check. Add private helper `allDirectChildrenTerminal()`. `GoalPlannerServiceTest` — add test: all direct children COMPLETED + REPLAN_REQUESTED present → root marked COMPLETED, planner not re-entered.
  - **Depends on:** T1-CJ (the `autoCompleteRootIfSatisfied()` delegation it introduced is the call target here).

- [x] **T1-CP: Revert T1-CA "inject at root" — nest replacement subgoals under the GIVEN_UP subgoal to preserve root completion ratio** (`PlannerAgent`, `GoalPlannerService`)
  - **Observed (2026-04-14 23:21 session):** 8 original root-level subgoals: 6 COMPLETED, 2 stagnated. Planner removed the 2 and injected 6 replacements via T1-CA's `effectiveParentId = null` → 14 direct root children, 6/14 = 43% < 50% threshold → root GIVEN_UP instead of COMPLETED. Had the replacements been nested under the 2 stagnated subgoals, root would have seen 8 direct children (6/8 = 75% → COMPLETED).
  - **Root cause:** T1-CA set `effectiveParentId = null` (root injection) to avoid nesting under a dead parent. Correct for preventing orphan cascades, but it inflates the root's direct-child denominator, making the completion ratio unreachable when multiple rounds of replanning occur.
  - **Fix A — `PlannerAgent.java`:** Remove the `effectiveParentId = null` override. Pass `currentSubGoalId` (the GIVEN_UP subgoal) directly as the parent to `injectOutcomeGoals()`. Keep the T1-CH fingerprint clearing — it is still needed when injecting under a GIVEN_UP node. The goal tree shape becomes: `root → endpoint7 (GIVEN_UP) → [replacement-a, replacement-b, ...]` instead of `root → [replacement-a, replacement-b, ...]`.
  - **Fix B — `GoalPlannerService.addSubGoal()`:** Remove the T1-CA defensive guard that rejects injection under a GIVEN_UP parent (lines ~725–730). Replace with a log-warn that records the injection without blocking it, so fresh replacement goals can be added as children of a GIVEN_UP subgoal.
  - **Fix C — Orphan cascade fix (replaces T1-CA's cascade protection):** `getRemainingGoals()` must exclude ACTIVE goals whose parent is GIVEN_UP **and** whose `planningRunId` is older than `currentPlanningRunId` — these are genuinely orphaned leftover children from a prior planning round. Freshly injected replacements (same run) share `currentPlanningRunId` and are not filtered out, so the system works on them normally. Implementation: in `getRemainingGoals()`, after the existing `status == ACTIVE && type == SUBGOAL` filter, add: `&& !isStaleOrphan(g)` where `isStaleOrphan(g)` returns true iff `g.getParentId() != null && goalMap.get(g.getParentId()).getStatus() == GIVEN_UP && g.getPlanningRunId() < currentPlanningRunId`.
  - **Changes:** `PlannerAgent.java` — remove `effectiveParentId = null` override (~6 lines). `GoalPlannerService.java` — remove GIVEN_UP guard in `addSubGoal()` (~5 lines), add `isStaleOrphan()` helper, update `getRemainingGoals()` filter. Tests: add `getRemainingGoals_excludesStaleOrphansUnderGivenUpParent` and `getRemainingGoals_includesFreshInjectionsUnderGivenUpParent`.
  - **Note:** For flat structures where the failing subgoal is itself a direct child of root, the "parent of failing goal" is root — the ratio still gets diluted in the same way as before. T1-CQ addresses that separately. This ticket restores the correct invariant (replacements are tree-local, not promoted to root) for the common nested case, and is the prerequisite for T1-CQ.
  - **Depends on:** T1-CA (reversing), T1-CH (fingerprint clearing — retained). T2-BW (injection budget — not affected by parent change).

- [x] **T1-CQ: `autoCompleteRootIfSatisfied()` ratio denominator inflated by stagnation-replan direct children** (`GoalPlannerService`)
  - **Origin:** Observed 2026-04-14 23:21 session; flat-structure residual after T1-CP.
  - **Problem:** For flat goal trees (root → subgoals directly), replacement subgoals injected by stagnation replanning are also direct children of root — T1-CP does not help because the failing subgoal's parent IS root. Each wave of replacements grows the denominator without guaranteeing more completions, making the 50% threshold harder to reach with each replan round.
  - **Fix:** In `autoCompleteRootIfSatisfied()`, compute the ratio using only direct children whose `planningRunId` equals the **minimum** `planningRunId` among all direct children (i.e. the first-wave subgoals from the initial planning response). Replacement subgoals injected in later planning rounds have a higher `planningRunId` and are excluded from the denominator. A first-wave subgoal that is GIVEN_UP counts as a failure; its nested replacements (T1-CP) are irrelevant to the root ratio.
  - **Changes:** `GoalPlannerService.java` — in `autoCompleteRootIfSatisfied()`, replace the flat `directChildren` list with `firstWaveChildren` = direct children filtered to `minPlanningRunId`. ~5 lines. `GoalPlannerServiceTest` — add test: 6 first-wave children (4 COMPLETED, 2 GIVEN_UP with nested replacements) + 4 second-wave children (all GIVEN_UP) → ratio computed as 4/6 = 67% > 50% → COMPLETED (not GIVEN_UP).
  - **Depends on:** T1-CP (establishes the nesting invariant that makes first-wave vs. replacement-wave meaningful). T2-BW (planningRunId already stamped on each subgoal — used here as the discriminator).

- [>] **T1-CO: Split and demoted (2026-04-14)** — the three-part fix was not a correctness bug; the loop was not broken, it was missing exit paths. Reassigned: **Part A → T3-BL** (criteria-banked early exit — scaffold present, needs wiring), **Part B → T2-CD** (fingerprint-repeat FULFILLED inference — steering quality), **Part C → T3-BM** (stateless LLM criteria check — new component over existing data).

- [>] **T1-CK: SUBGOAL_EXHAUSTED with partial criteria met should trigger graduation rather than GIVEN_UP** (`AutobuilderService`, `GoalPlannerService`)
  - **Problem:** T1-CF (the all-criteria-met FULFILLED promotion) only handles the case where every criterion is banked as true. When some but not all criteria are satisfied and the subgoal is exhausted, the code falls through to `GIVEN_UP` — discarding measurable progress and forcing the planner to re-derive work already done. T3-BK's graduation escape fires during normal evaluation cycles; by the time `SUBGOAL_EXHAUSTED` is reached, graduation either didn't fire or was too late.
  - **Fix:** In `AutobuilderService`'s `SUBGOAL_EXHAUSTED` case, add a second check after the T1-CF all-criteria guard: if `!areCriteriaCumulativelySatisfied(subGoalId)` but some criteria are banked as true (i.e. `criteriaPassCount > 0`), call `goalPlannerService.injectGraduationMicroSubgoals()` for the unmet criteria instead of calling `applyGoalProgress(GIVEN_UP)`. Clear exhaustion signals and inject a `REPLAN_REQUESTED` so the new micro-subgoals are picked up immediately.
  - **Implementation note:** `injectGraduationMicroSubgoals(String graduatingSubGoalId, List<Integer> unmetIndices, ...)` already exists (T3-BK). The missing piece is: (a) computing the unmet indices from `getCriteriaPassStatus` and the defined-criteria count, and (b) calling it from the exhaustion handler as a backstop. Guard with `mergeCount > 0` (at least one merge occurred — partial progress is real, not a blank subgoal). If `mergeCount == 0`, fall through to GIVEN_UP as today.
  - **Changes:** `AutobuilderService.java` — add partial-criteria backstop between T1-CF check and existing GIVEN_UP path (~15 lines). `GoalPlannerService.java` — expose a helper `getUnmetCriteriaIndices(String goalId)` returning `List<Integer>` of 1-based indices not yet banked as true. `AutobuilderServiceTest` — add test: exhausted + partial criteria + mergeCount > 0 → graduation injected, GIVEN_UP not called.
  - **Depends on:** T1-CF (the all-criteria gate comes first; this is the partial-criteria branch below it). T3-BK (graduation injection already implemented; this backstop re-uses it).

- [x] **T1-CS: T2-CC null-eval EVOLUTION_NOOP falsely promotes subgoals with zero prior implementation evidence** (`EvolutionAgent`)
  - **Observed (2026-04-16 02:18 session):** POST /members (`goal-5b53b878`) was promoted to FULFILLED at 02:18:07 via EVOLUTION_NOOP. No `Member.java`, `MemberController.java`, or any member-related code exists in the output. The LLM said `#NoDiff`; the graph had no prior evaluation for the new subgoal ID; T2-CC's null-eval branch trusted the claim and promoted immediately. The feature was never implemented.
  - **Confirmed (2026-04-16 03:47 session):** Same pattern reproduced. POST /members (`goal-7f40483e`) promoted at 03:48:11 via `Evolution noOp — #NoDiff implies current output satisfies all criteria. Promoting to FULFILLED`. Output directory contains only Book files (6 files from initial evolution). No Member files anywhere. The verify-in-place mechanism (removed by T2-CE/CF diff) would have caught this, but the current unconditional trust of `lastEvalStatus == null || "ACCEPTED"` makes it strictly worse.
  - **Root cause:** T2-CC's null-eval branch assumes `lastEvalStatus == null` implies the "cross-subgoal deferred-merge case" — that the code was already delivered by a prior subgoal's merge and this subgoal never got its own evaluation cycle. This assumption is correct for deferred-merge siblings (e.g. GET /books/{id} already implemented by the big books merge), but wrong for freshly-injected subgoals that have simply never been evolved before. The system has no way to distinguish the two cases using `findLatestEvaluationStatusForSubgoal` alone.
  - **Consequence:** The final output contains only Book-related files. POST /members, GET /members/{id}, and all Loan endpoints are absent from output despite being marked COMPLETED or GIVEN_UP. Root goal was declared FULFILLED with 3/8 endpoints actually implemented.
  - **Distinguishing the two cases:**
    - **Deferred-merge (safe to trust #NoDiff):** A prior subgoal's merge delivered the code. The LLM checks `--- CURRENT IMPLEMENTATION STATUS ---` and correctly sees it exists. `previousOutput` contains relevant files (e.g. BookController.java when handling GET /books subgoal).
    - **First-time injection (unsafe to trust #NoDiff):** Brand new subgoal, code never written. `previousOutput` contains no files relevant to this subgoal. The LLM either (a) is wrong, (b) is lazy, or (c) sees a different subgoal's code and misidentifies it as covering this subgoal.
  - **Fix (2026-04-20):** In the null-eval branch of `EvolutionAgent.runEvolution()` (where `lastEvalStatus == null`), added `hasOutputEvidenceForSubgoal()` — a tokenised keyword scan (terms ≥4 chars from description and criteria, length-filtered) against `previousOutput` file paths and content. If zero matches: emit `DIVERGENCE_REQUEST(1.0, decay=0.1)` and return (cycle ends without EVOLUTION_NOOP; next cycle runs divergent evolution naturally). If evidence found: emit EVOLUTION_NOOP as before (deferred-merge case is legitimate).
  - **Why DIVERGENCE_REQUEST not a second evolveDivergent call:** Running the LLM adapter a second time within the same cycle is expensive and could hide issues. Signalling divergence and ending the cycle cleanly is cheaper and follows the same path as other divergence triggers.
  - **Edge cases:** Empty `previousOutput` → no match → DIVERGENCE_REQUEST (correct for first-ever cycle). Empty terms (null description + null criteria) → returns `true` conservatively (allow NOOP — can't determine intent).
  - **Changes:** `EvolutionAgent.java` — new `hasOutputEvidenceForSubgoal()` helper + updated null-eval branch. Two tests: deferred-merge (evidence present → EVOLUTION_NOOP) and first-time injection (no evidence → DIVERGENCE_REQUEST, no second LLM call).
  - **Depends on:** T2-CC.

- [x] **T1-CT: Mutation meta.json double-write — `CyclePersistenceService` overwrites `MutationApplier` meta with post-promotion context** (`CyclePersistenceService`, `MutationApplier`, `FileVariantCollector`)
  - **Observed (2026-04-16 03:47 session):** Cycle 1 (`cycle-1776304026516`) evolved POST /books. `MutationApplier` wrote `meta.json` with `currentSubGoalId=goal-c8b919ad` (POST /books) during evolution at 03:47:32. At cycle end (03:47:41), `CyclePersistenceService.persistSnapshot()` re-wrote the same `meta.json` with the final context state: `currentSubGoalId=goal-5e212e11` (GET /books — the NEXT subgoal after promotion) and `merged=true` (because cycle 2 later marked it via `CycleMergeTracker`). The double-write creates two distinct problems.
  - **Problem A — subgoalId drift:** The mutation was produced FOR subgoal A, but `meta.json` records subgoal B (post-promotion). `FileVariantCollector` filters variants by `activeSubGoalId.equals(meta.id())`. On the NEXT cycle, the active subgoal is B — so the variant matches and merge can proceed. But if the variant is NOT merged immediately in that next cycle (e.g. completion fires first), the subgoal advances to C, and the variant is permanently orphaned: its meta says B, the active subgoal is C, the filter rejects it. In the 03:47 session, this one-cycle-offset worked for cycle 1→2 (merge succeeded) but failed for all subsequent cycles (variants orphaned).
  - **Problem B — false merged flag:** Cycle 2's `meta.json` shows `merged=true` because `CyclePersistenceService` wrote the context's `merged()=true` flag (set when cycle 2 merged cycle 1's files via `MergeAgent`). But cycle 2's OWN mutation files were never merged to output. `FileVariantCollector` reads `ctx.merged()` → true → skips. Cycle 2's mutation is permanently treated as already merged.
  - **Root cause:** `CyclePersistenceService.persistSnapshot()` calls `mutationSnapshotService.writeMutationMeta(context)` at cycle end when `evolutionRanThisCycle()` is true. This overwrites the initial `meta.json` written by `MutationApplier` during evolution. The final context reflects post-promotion subgoalId and the aggregate cycle `merged()` flag — neither of which describes the mutation's own state.
  - **Fix:** `CyclePersistenceService` should NOT re-write `mutations/<cycleId>/meta.json` at cycle end. The initial write by `MutationApplier` during evolution is correct: it captures the subgoalId AT THE TIME of mutation and `merged=false`. Only `CycleMergeTracker.markCycleMerged()` should update the `merged` flag (to `true`) on that specific cycle's meta. Remove the `mutationSnapshotService.writeMutationMeta(context)` call from `CyclePersistenceService.persistSnapshot()`. The snapshot (which IS the full context with post-promotion state) should only go to `snapshots/<cycleId>.json`, not to `mutations/<cycleId>/meta.json`.
  - **Changes (implemented 2026-04-20):** `CyclePersistenceService.java` — removed `writeMutationMeta(context)` call; replaced with `context.withMutationPath(...)` to preserve the path on the returned context without overwriting the file. `CycleExecutor.java` — added `persistEvaluationResultToMeta()` called immediately after `applyEvaluationInterpretationIfPresent()`, before `applyMergeOnAcceptedMutation()`. This reads the existing meta.json (correct subgoalId, merged=false) and updates ONLY `mutationState` with the evaluation result, making `FileVariantCollector`'s ACCEPTED filter work for both same-cycle and cross-cycle merge.
  - **Test:** `CyclePersistenceServiceTest` — verifies no `writeMutationMeta` call from `CyclePersistenceService`, and `mutationPath` is still set on the returned context.
  - **Depends on:** None (standalone fix). Must be fixed BEFORE T1-CU — otherwise the merge-before-fulfillment gate will still see wrong subgoalIds.

- [x] **T1-CU: Accepted mutations orphaned — fulfillment fires before merge runs** (`CycleExecutor`, `AdaptiveExecutionEngine`, `FileVariantCollector`)
  - **Observed (2026-04-16 03:47 session):** 7 of 9 cycles had `merged=false` despite ACCEPTED mutations. Only cycle 2 merged (cycle 1's files). Cycles 3, 5, 6, 8, 9 all produced accepted evolution output that sits in `mutations/` and never reached `output/`. The final output directory contains only the 6 files from the initial Book evolution — no Members, no Loans. Yet the root goal was declared FULFILLED.
  - **Root cause (two interacting mechanisms):**
    1. **Completion fires before merge:** `CycleExecutor` derives the completion decision at the end of the coordinator loop. If score-based closure fires (score above threshold + meaningful diff), the subgoal is FULFILLED and promoted IMMEDIATELY — before the merge agent has a chance to run in a subsequent coordinator iteration. In cycle 2, merge ran before completion (timing: cycle 1's pending variant was picked up by the coordinator). In cycle 3+, completion fired first, promoting the subgoal and ending the cycle.
    2. **T1-CE subgoal-scope filter blocks orphaned variants:** After promotion, `FileVariantCollector` only serves variants matching the NEW active subgoalId. The orphaned variant from the previous subgoal (with T1-CT also contributing wrong subgoalIds) is invisible. Even if the next cycle tries to merge, it sees "missing variant" → merge blocked.
  - **Consequence:** The system believes it is making progress (mutations are produced, evaluated, accepted) but no code ever reaches `output/`. The output directory freezes at whatever state the last actual merge produced.
  - **Fix — require merge before fulfillment:** In `CycleExecutor.deriveCycleResult()`, add a merge-occurrence gate to the score-based closure path. A subgoal should NOT be marked FULFILLED unless `context.merged()` is true OR the subgoal has `mergeCount > 0` on its `GoalEntity` (a prior cycle already merged for this subgoal). If the score meets the closure threshold but no merge has occurred, return `CompletionDecision[complete=false, reason=Merge not yet performed for accepted mutation]` and let the next coordinator iteration run merge.
  - **Alternative / complementary — ensure merge runs before completion:** In `AdaptiveExecutionEngine`, after meta-step completion and before the completion decision, force a merge-eligibility check. If `hasAnyVariant(context)` returns true AND `mutationState == ACCEPTED`, run the merge agent BEFORE checking completion. This ensures the cycle always: evolve → evaluate → merge → complete, in that order.
  - **Edge case — EVOLUTION_NOOP path:** When `AutobuilderService` promotes a subgoal via EVOLUTION_NOOP (#NoDiff), no merge is needed (the code is already in output from a prior merge). The merge gate should NOT block this path. Guard: `if (context.hasSignal(EVOLUTION_NOOP)) { /* skip merge-gate check */ }`.
  - **Changes:** `CycleExecutor.java` — add `!context.merged() && goalEntity.getMergeCount() == 0` guard to the score-based closure branch (~5 lines). OR `AdaptiveExecutionEngine.java` — inject merge-before-completion step (~10 lines). `CycleExecutorTest` — add test: accepted + score above threshold + merged=false → complete=false.
  - **Depends on:** T1-CT (mutation meta subgoalId must be correct for FileVariantCollector to find the variant). T1-CS (EVOLUTION_NOOP guard prevents false NoDiff promotions that bypass merge).
  - **Note:** The `FileVariantCollector` subgoal-scoping filter (T1-CE) is correct — cross-subgoal contamination is a real problem. T1-CU's fix ensures merge happens BEFORE the subgoal advances, so the filter never needs to serve orphaned variants.


- [x] **T1-CX: `persistEvaluationResultToMeta` emits spurious warning on eval-only cycles** (`CycleExecutor`)
  - **Observed (2026-04-20 12:03 session):** Cycle-1776679429054 (NOOP mode, no evolution) logged: `T1-CT: could not update mutationState in mutations/cycle-1776679429054/meta.json: Snapshot meta not found`. This fires because `persistEvaluationResultToMeta` falls through to the `mutationPath` branch: `ctx.evolutionRanThisCycle()` is false, but `ctx.mutationPath()` is non-null (carried forward from cycle 1's `CyclePersistenceService.withMutationPath(...)` call). The method then attempts to read a meta.json under the previous cycle's path, finds nothing, and logs a warning.
  - **Root cause:** `CyclePersistenceService.persistSnapshot()` sets `mutationPath = "mutations/" + ctx.cycleName()` on the returned context whenever `evolutionRanThisCycle()` is true. When the NEXT cycle inherits this context (no evolution), `ctx.mutationPath()` is non-null but refers to a folder that belongs to the prior cycle. The IOException is logged at WARN, polluting logs on every eval-only cycle.
  - **Fix (2026-04-20):** Downgraded the IOException catch in `persistEvaluationResultToMeta` from `log.warn` to `log.debug`. The cross-cycle update path is preserved intact — the `mutationPath` branch still runs and attempts the update; it just no longer logs a warning when the prior cycle's meta.json is missing. No cycleName guard added (that would have broken legitimate cross-cycle evaluation-after-evolution updates).
  - **Changes:** `CycleExecutor.java` — `log.warn` → `log.debug` in the catch block (~1 line).
  - **Depends on:** T1-CT. Minor/cosmetic — no correctness impact.

- [x] **T1-CW: `NoMeaningfulChangeHeuristic` reference to removed `evolutionVerifyInPlace` field** (`NoMeaningfulChangeHeuristic`)
  - **Observed (2026-04-16 diff review):** The T2-CE/CF diff removed `evolutionVerifyInPlace` from `CycleContextData` and `EvaluationProtocolContract`, but `NoMeaningfulChangeHeuristic` still references it in the current diff (removal of 3 lines). This is a compile-time fix — the heuristic's verify-in-place guard should already be gone.
  - **Fix:** Verify the diff for `NoMeaningfulChangeHeuristic.java` correctly removes the `if (input.evolutionVerifyInPlace()) return HeuristicOpinion.none(NAME)` guard. If the heuristic has other guards that make this path dead code, no further action needed.
  - **Changes:** `NoMeaningfulChangeHeuristic.java` — remove verify-in-place guard (~3 lines). Already part of the current diff.

- [x] **T1-CC: LLM network failures bypass circuit breaker — requests can hang indefinitely** (`LLMClient`, `LlmCircuitBreaker`)
  - **Root cause:** `LLMClient.ask()` only catches `HttpStatusCodeException` (HTTP error responses). Network-level failures — connection timeout, read timeout, connection refused, stalled local LLM — throw `ResourceAccessException` instead and propagate uncaught, bypassing the circuit breaker entirely. `RestTemplate` is constructed with no explicit timeouts (`SimpleClientHttpRequestFactory` defaults), so a local LLM that stalls mid-generation hangs the scheduler thread indefinitely.
  - **Fix A — add timeouts to RestTemplate:** Replace `new RestTemplate()` with a `RestTemplate` backed by `SimpleClientHttpRequestFactory` with `connectTimeout=5_000ms` and `readTimeout` configurable via `llm.api.read-timeout-ms` (default `120_000ms` — generous for local models). Add the config key to `application.yml`.
  - **Fix B — catch `ResourceAccessException` in `ask()`:** In the `catch` block alongside `HttpStatusCodeException`, also catch `ResourceAccessException` (Spring's wrapper for `IOException`, including timeout). Call `circuitBreaker.recordNetworkFailure()`. Add `recordNetworkFailure()` to `LlmCircuitBreaker` — routes to the same count-based threshold path as 5xx errors (increment counter, open after `CONSECUTIVE_FAILURE_THRESHOLD`). Network failures use the existing backoff rather than permanent open, since they are transient by nature.
  - **Changes:** `LLMClient.java` (~8 lines), `LlmCircuitBreaker.java` (add `recordNetworkFailure()`, ~6 lines), `application.yml` (add `llm.api.read-timeout-ms: 120000`).
  - **Note:** `connectTimeout` should be short (5s) — if the local server isn't listening that quickly it won't start. `readTimeout` must be long — local models can take 30–90s per response.

- [ ] **T1-CZ: Analysis-spawned children cascade-abandon a criteria-satisfied parent** (`GoalPlannerService`, `AnalysisAgent`)
  - **Observed (2026-04-22 00:50 session, run 20260422_2):** POST /loans (`goal-…`) accumulated all 5 acceptance criteria as `✓` with `mergeCount > 0`. AnalysisAgent then injected 2 child subgoals under it (`analysisInjectionCount=2`). Those children stagnated and were GIVEN_UP. The cascade propagated upward and the parent was marked GIVEN_UP with `resolution="superseded by replanning"`, despite criteria already being banked as satisfied. This removes ~1/8 of the first-wave completion ratio on every occurrence and contributed to the run being marked GIVEN_UP at 3/8 instead of recognising the implicit 4/8.
  - **Root cause:** `GoalPlannerService.markGoalGivenUp()` (or the analysis-injection path that triggers it via `AutobuilderService`) does not consult the parent's `cumulativeCriteriaStatus` before cascading. The cascade is symmetric: a child's exhaustion propagates regardless of whether the parent has independent evidence of completion. Analysis-spawned children are meant to be *augmentations* (extra verification, polish) — their failure does not mean the parent's original criteria are unmet.
  - **Fix — two options:**
    - **A (preferred, minimal):** Before cascading GIVEN_UP from an analysis-spawned child to its parent, consult `areCriteriaCumulativelySatisfied(parentId)` (T1-CF helper). If true and `mergeCount > 0`, promote the parent to FULFILLED instead and do not cascade. The analysis-spawned child is left GIVEN_UP on its own merits.
    - **B (stricter invariant):** Tag subgoals produced by `AnalysisAgent.autoInjectExecutableSubGoals` with `metadata["spawnedBy"]="analysis"`. In the cascade path in `markGoalGivenUp()`, children with `spawnedBy=analysis` do not propagate upward — their exhaustion is self-contained regardless of the parent's criteria state.
    - Option A is narrower and builds on existing T1-CF infrastructure. Option B is more defensible long-term because it encodes the semantic difference between planner-intent children (siblings of the parent's work) and analysis-spawned children (augmentations on top).
  - **Changes:** `GoalPlannerService.java` — add parent-criteria check (Option A) or spawner-check (Option B) before cascade propagation in `markGoalGivenUp()`. `AnalysisAgent.java` — tag injected goals with `spawnedBy=analysis` metadata (Option B). Tests: parent fulfilled with analysis children exhausted → parent stays FULFILLED; regular planner-sibling children still cascade as today.
  - **Depends on:** T1-BG (cumulativeCriteriaStatus), T1-CF (the FULFILLED promotion mechanism). T2-BW already tracks `analysisInjectionCount` per subgoal — spawner-tagging can reuse the same write path.
  - **Enables:** Correct first-wave completion ratios (T1-CQ / T2-CH) when analysis has decorated a completed subgoal with follow-up children.

---

## Tier 2 — Steering quality

- [x] **T2-BA: Redesign `DeterministicScoreHeuristic`** (`DeterministicScoreHeuristic`)
  - **Problem (observed 2026-04-07):** Two independent bugs:
    1. Confidence is hardcoded at 0.55 (always MEDIUM) — the heuristic never reaches HIGH/CRITICAL and cannot win majority vote in `HeuristicNegotiator`.
    2. `mutationState` is always echoed from the LLM (`original.mutationState()`) — the heuristic never forms its own assessment. A CRITICAL confidence simply rubber-stamps whatever the LLM already decided.
  - **Design:** Two independent dimensions must be set correctly:
    - **`mutationState`**: `globalScore ≥ 0.75 → ACCEPTED, else REJECTED`. The 0.75 threshold is the real acceptance boundary used throughout the pipeline (`EvaluationInterpreter`, `EvaluationAgent`). Note: `EvaluationConfigProperties.heuristicThreshold` (0.4) is orphaned — not used by any heuristic — do not use it here.
    - **`confidence`**: Continuous function of evidence quality, theoretically reachable to 1.0 — no artificial cap. Two axes:
      1. **Temporal consistency** — `1 / (1 + |ΔglobalScore| / k)` where k ≈ 0.05. Approaches 1.0 as score stabilises across cycles; 0.5 (neutral) when no prior score.
      2. **Spatial consistency** — `1 / (1 + cv)` where cv = stddev/mean of `perFileScores`. Approaches 1.0 when all files score identically; neutral (1.0 spatial factor) when < 2 files. Coefficient of variation, not raw variance, because it is scale-invariant.
      - Combine: geometric mean of the two factors (or temporal alone × 0.7 when no prior score exists).
  - **Scope:** `DeterministicScoreHeuristic.evaluate()` only — no changes to calling code. Existing test class `DeterministicScoreHeuristicTest` exists but tests the old stepped approach and must be rewritten.

- [x] **T2-BC: `scorePerFile` large-file penalty incorrectly applied to new ADDED files** (`DefaultMutationHeuristicAnalyzer`)
  - **Problem (observed 2026-04-07):** `scorePerFile` applied a `-0.15` diff-magnitude penalty to any file with `totalChanged > 50` lines. For `ADDED` (brand-new) files every line counts as "added", so a 60-line controller scored 0.65 (0.75 − 0.15 + 0.05 ADDED bonus) — below the 0.75 acceptance threshold. This caused `DeterministicScoreHeuristic` to say REJECTED for a perfectly valid 4-file creation cycle.
  - **Fix:** Skip the diff-magnitude block entirely when `type == DiffType.ADDED`. Content size of a new file is not churn. A new 60-line controller now scores 0.80 (0.75 + 0.05 ADDED bonus), pushing `globalScore` above 0.75.
  - **Scope:** `DefaultMutationHeuristicAnalyzer.scorePerFile()` only — move `DiffType` lookup before the magnitude block and gate the three magnitude branches on `type != DiffType.ADDED`.

- [x] **T2-BD: `PatternSignalAnalyzer.applyNoMeaningfulChange` fires on stale `projectDiff` in non-evolution cycles** (`PatternSignalAnalyzer`)
  - **Problem (observed 2026-04-07):** `applyNoMeaningfulChange` emits `STAGNATION` (strength 0.7, decay 0.20) whenever `projectDiff.changedFiles()` is empty. `projectDiff` is computed by `EvolutionAgent` and carried forward immutably on the context. In planner-only or analysis-only cycles (no evolution ran), the stale `projectDiff` from the last evolution cycle is used. If that last evolution was a no-op (re-produced already-merged content), STAGNATION was reinforced from 0.7 → 1.4 every non-evolution cycle, corrupting downstream orchestration.
  - **Fix:** Gate `applyNoMeaningfulChange` on `ctx.evolutionRanThisCycle()`. The signal now only fires when a new mutation was actually computed this cycle, not on stale carry-forward data.
  - **Scope:** `PatternSignalAnalyzer.applyNoMeaningfulChange()` — one guard line added.

- [x] **T2-BE: `REPETITION_DETECTED` not cleared when evolution produces a genuinely new fingerprint** (`EvolutionAgent`)
  - **Problem (observed 2026-04-07):** In `EvolutionAgent.updateFingerprint()`, the `isRepeat=false` branch emits `EVOLUTION_FINGERPRINT_CHANGED` but never clears `REPETITION_DETECTED`. The signal has `decayPerCycle=0.05`, so it persisted for ~20 cycles after a single repeated mutation, poisoning deliberation, meta-steps, and planner prompts long after evolution had genuinely moved on. The only existing clear was in `AutobuilderService`'s subgoal-exhaustion recovery path — far too late.
  - **Note:** T2-AX's roadmap entry mentioned this fix but the commit (8312d4e) only touched `NoMeaningfulChangeHeuristic` — the EvolutionAgent change was never implemented.
  - **Fix:** In the `else if (!Objects.equals(newFp, lastFp))` branch, chain `.withoutSignal(BasicSignals.REPETITION_DETECTED)` alongside the `EVOLUTION_FINGERPRINT_CHANGED` emission. When evolution genuinely diverges, the signal is immediately gone.
  - **Scope:** `EvolutionAgent.updateFingerprint()` — one additional `withoutSignal` call.

- [>] **T2-BB: Inject live score thresholds into interpretation agent prompt** (`InterpretationProtocolContract`, `TuningParameterStore`)
  - **Problem (observed 2026-04-07):** After 1 successful merge+promote cycle (scoreNow=0.814), the interpretation LLM described the session as "consistently stagnating below 0.82". T2-AU's sparse-history guard correctly stripped the resulting signals, but the LLM reasoning is calibrated to a ~0.82 internal benchmark that has no relationship to system configuration. Once the guard becomes inactive (≥3 interpretation cycles), the same faulty logic will produce real signal emissions.
  - **Fix:** Add a `--- SYSTEM SCORE THRESHOLDS ---` block to the interpretation prompt. Values are read live from `TuningParameterStore` (T4-J), not hardcoded strings. Exposed parameters: `score.merge-acceptance-floor`, `score.subgoal-closure`, `score.plateau-override-floor`. Include an explicit instruction: "Only flag LOW_CONFIDENCE if the score is persistently below `score.merge-acceptance-floor`, not merely below 0.82 or any other internal benchmark."
  - **Scope:** Interpretation prompt assembly only — no signal or heuristic logic changes.
  - **Depends on:** T4-J (TuningParameterStore read path must exist first).
  - **Deferred:** start after T4-J is implemented.

- [>] **T2-AS: Meta-deliberation quality — previous steps in context + role eligibility guard** (`MetaDeliberationAgent`, `OrchestrationSnapshotService`, `AdaptiveExecutionEngine`, `MetaDeliberationResponseParser`)
  - **Problem 1 (repeated identical meta-plans):** The identical 2-step evolution→evaluation plan was emitted verbatim on 3 consecutive cycles for subgoal `goal-0de36047`. The meta-deliberation context does not include the previous cycle's meta-steps, so the meta-agent cannot detect it is repeating itself.
  - **Fix 1:** Include the previous cycle's emitted meta-steps (role, reason, stop_if text) in the `OrchestrationSnapshot` or as a dedicated `--- PREVIOUS META PLAN ---` block injected into the meta-deliberation prompt. The meta-agent should be explicitly instructed to vary its plan when the previous attempt had the same structure.
  - **Problem 2 (ineligible role proposed, LLM call wasted):** Meta-agent proposed `TargetRole: MERGE` while `DeliberationMode=EVALUATE`, which blocks the merge role. The engine logged "skipped: mode blocks role=merge" and discarded the step — after the LLM call had already been made.
  - **Fix 2:** In `MetaDeliberationResponseParser` (or the meta-step executor), validate each proposed role against `AllowedRolesForMode` and log a soft warning without executing. Alternatively, inject the set of currently-allowed roles into the meta-agent's prompt more prominently.
  - **Observed:** 2026-04-01 session.
  - **PARKED (2026-04-02):** Fix 1 is counterproductive in the current architecture: `ctx.deliberationResult()` only carries the previous cycle's *meta* steps, but fitness-driven steps execute after meta within the same cycle — so "previous meta plan" is an incomplete and misleading picture of what actually ran. Fix 2 (role eligibility guard) is straightforward in isolation but not worth doing piecemeal. The entire meta mechanism is being redesigned in **T4-AI**; both problems should be addressed there with a proper full-cycle history rather than patched here.

- [x] **T2-AT: Critical heuristic findings feedback loop into evolution prompt** (`HeuristicNegotiator`, `CycleContextData`, `EvolutionProtocolContract`)
  - **Problem:** `SemanticClarityHeuristic` fired at CRITICAL confidence (0.98) with `-0.120` score penalty on at least 5 consecutive cycles, all for the same structural pattern (20 additions, 0 deletions, 0 modifications — a full-file blob replace). The heuristic finding is not surfaced in the next evolution prompt; the agent keeps producing the same shape.
  - **Fix:** When a heuristic fires at CRITICAL confidence for ≥2 consecutive cycles on the same subgoal, record the finding in `CycleContextData` (e.g. `persistentHeuristicWarnings: List<String>`). `EvolutionProtocolContract` should include these as explicit directives in the evolution prompt: e.g. "Previous evolution produced only additions with no deletions — use targeted modifications instead of full-file replacement."
  - **Scope:** Only CRITICAL-confidence findings that repeat across cycles should be surfaced, to avoid polluting the prompt with noise from one-off low-confidence penalties.
  - **Analogue:** T2-H3 (interpretation directives fed forward to next cycle) — same pattern, different source.

- [x] **T2-AV: Persist heuristic opinions from NegotiationResult to graph** (`EvaluationActionRegistrar`, `EvaluationAgent`)
  - `NegotiationResult` gains `allOpinions` field (all non-NONE opinions). `EvaluationActionRegistrar.registerEvaluation()` now takes `NegotiationResult`; writes `heuristicOpinions` (array of `{name, confidence, level, rationale, critical}`) and `criticalOpinionCount` into the artifact JSON payload. Redundant heuristic re-evaluation loop in `EvaluationAgent` replaced with a single log pass over `allOpinions`.

- [x] **T2-AW: Surface persisted CRITICAL heuristic patterns in OrchestrationSnapshot** (`OrchestrationSnapshotService`, `OrchestrationSnapshotFormatter`)
  - **Problem:** T2-AT's in-memory streak counter resets on restart and subgoal change. T2-AV now persists opinions to the graph, but nothing reads them back. Recurring CRITICAL patterns spanning restarts are invisible to the meta-deliberation agent.
  - **Fix:** In `OrchestrationSnapshotService`, query the last N evaluation artifact payloads for the current subgoal (via `AgentGraphService` or direct Cypher). Count per-heuristic CRITICAL firings across those artifacts. Include any heuristic with ≥2 CRITICAL hits in the snapshot as a `recurringCriticalHeuristics` list. Surface in `OrchestrationSnapshotFormatter` under a `--- Recurring Heuristic Warnings ---` block so the meta-deliberation agent sees persistent patterns even after a restart.
  - **Scope:** Read-only Cypher query + snapshot model field + formatter block. No write-path changes needed.

- [x] **T2-AX: `NoMeaningfulChangeHeuristic` misses structural no-ops** (`NoMeaningfulChangeHeuristic`)
  - **Problem (observed 2026-04-06):** The heuristic only fires when `changedFiles.isEmpty() && deletedPaths.isEmpty()`. The diff service reports a file as *changed* even when the mutation content is byte-for-byte identical to what was already merged into output (e.g. BookController re-produced three cycles in a row). `noChange=false` so the heuristic stays silent.
  - **Fix:** Added a `noNetChange` check: if `deletedPaths` is empty and every file in `changedFiles` has content byte-for-byte equal to what's in `input.previousOutput()`, the mutation is a structural no-op and `noChange` is set to true. The heuristic then applies the same score-based confidence tiers as the empty-diff path. No signal proxies, no diff-layer changes — the heuristic directly answers its own question using data already on `CycleContextData`.
  - Also: `EvolutionAgent` now clears `REPETITION_DETECTED` when a genuinely new fingerprint is produced (prevents ~20-cycle stale signal). `ContextLineageTracker` resets `recentEvolutionFingerprints` and both fingerprint fields on subgoal change (scopes repetition detection to the current subgoal).

- [x] **T2-AY: `SemanticClarityHeuristic` can't distinguish new-file creation from blob replace, and confidence ceiling is too low** (`SemanticClarityHeuristic`)
  - **Problem (observed 2026-04-06):** (1) When a brand-new file is created (`lastScore == null` guard works for first-ever cycle, but not when a new file is added mid-run), the heuristic sees `added=N, deleted=0` and fires a penalty — false alarm. (2) For pure-addition diffs, the confidence formula (`magnitude × volumeFactor × coherence`) is structurally capped at ~0.70 (MEDIUM) for typical 4-8 node diffs; CRITICAL (0.90) is unreachable, so T2-AT's warning mechanism and T2-AW's graph surface never activate for this heuristic.
  - **Fix (1):** Guard on whether the file being diffed is new to the output directory (not just new to the world). Proxy: if the diff is a "new file" addition (the path appears in `projectDiff` as an addition at the file level, not a modification), suppress the penalty.
  - **Fix (2):** For the case of a repeated pure-addition pattern (same `added>0, deleted=0` across ≥2 consecutive cycles on the same subgoal), escalate confidence to 0.90 (CRITICAL) — use `criticalHeuristicStreaks` already on `CycleContextData` to detect this.
  - **Scope:** `SemanticClarityHeuristic.evaluate()` + possibly a small helper on `ProjectDiffResult` to distinguish new-file-add from file-modification.

- [x] **T2-AZ: `RedundantOutputHeuristic` override is permanently disabled** (`RedundantOutputHeuristic`)
  - **Problem (observed 2026-04-06):** `override=false` hardcoded since 2025-DEC-07 with a TODO comment. The heuristic correctly detects redundant files (fires at MEDIUM 0.50 for 1, HIGH 0.75 for ≥2) but never changes the mutation state — purely advisory.
  - **Fix:** Enable override for the ≥2 case (HIGH confidence): set `mutationState=REJECTED, goalProgress=STAGNATING, suggestedScore=0.4`. Keep the single-redundant-file case at MEDIUM/no-override (it may be coincidental). Add a comment explaining the threshold choice.
  - **Scope:** `RedundantOutputHeuristic.evaluate()` — remove `override=false`, gate override on `count >= 2`.

- [x] **T2-AU: Interpretation agent over-eagerness on sparse cycle history** (`DefaultInterpretationAgent`)
  - `suppressPatternSignalsIfSparseHistory()`: counts `interpretation_summary` artifacts in `recent`; if < 3, strips `REPLAN_REQUESTED` and `LOW_CONFIDENCE` from the parsed outcome. Non-pattern signals (e.g. STAGNATION) are preserved.

- [x] **T2-D: "Possibly complete" propagation** (`AnalysisAgent`, `BasicSignals`, deliberation)
  - When analysis emits a `GoalExhausted` finding or "implementation may be complete" hypothesis,
    currently nothing actionable happens — system logs it and continues cycling
  - Surface as a distinct `POTENTIALLY_COMPLETE` signal
  - Signal triggers human-review pause (like `SYSTEM_PAUSED`) rather than continued cycling
  - Prevents wasted cycles after the loop has functionally achieved its goal

- [x] **T2-G: Conditionalize meta deliberation** (`DeliberationService`, `MetaDeliberationAgent`)
  - Meta deliberation (`MetaDeliberationAgent.decide()`) fired every cycle unconditionally, burning one LLM call even in steady-state normal flow. When it returned NOOP/Stop it was discarded; when it proposed steps they often hit `canExecute=false` → fallback deadlock-inject added competing signals.
  - Fix: meta deliberation is an **escalation path**, not a per-cycle pass. Run meta only when stuck: N consecutive cycles with no progress, OR all allowed roles are `canExecute=false`. Added `metaDeliberationCooldown` counter; reset on any cycle that produced a role execution; LLM call only fires above threshold.
  - **NOTE (open question):** meta's mode override (`plan.mode()`) stored in `meta["metaControl.mode"]` is never promoted into `DeliberationResult.mode()`; the signal-driven fallback always gates on the rule-based proposer's mode. Options: (A) promote meta's mode, or (B) skip signal-driven pass entirely when meta steps executed. Needs further thought.

- [x] **T2-H3: Propagate InterpretationAgent summary/directives to next-cycle agent prompts** (`CycleContextData`, `InterpretationService`, `PlannerProtocolContract`, `EvolutionProtocolContract`)
  - `InterpretationService` applied proposed signals but dropped `summary` and `directives` from `InterpretationOutcome`
  - Fix: added `interpretationSummary` and `interpretationDirectives` fields to `CycleContextData`
  - `InterpretationService` now stores them on the context before returning; fields carry over to next cycle naturally
  - Planner and Evolution prompts include `--- INTERPRETATION CONTEXT (previous cycle) ---` block

- [x] **T2-J: Planner pruning pressure** (`PlannerProtocolContract`)
  - Added `Remaining Goals (%d)` / `Completed Goals (%d)` counts to prompt headers
  - Added `PRUNE_BEFORE_GROW` nudge rule: when backlog is large relative to completed, review for
    near-duplicate goals, execution-requiring goals (system cannot run code), and superseded goals
  - [x] **T2-J1: Goal hierarchy visible to planner** (`PlannerProtocolContract`)
    - `formatGoalInfoList` now renders remaining goals as an indented tree using `parentId` links
    - Parent nodes annotated with `[has N active subgoals — remove children before removing this]`
    - Prompt header updated: "indented entries are active subgoals of the item above them; do NOT remove a parent while it still has active subgoals"
  - [>] **T2-J2: Pre-computed annotations** (`PlannerAgent`, `CycleContextData`, `PlannerProtocolContract`)
    - Pre-compute similarity clusters and goal kind per goal, surface as annotations (e.g. `[COGNITIVE]`, `[~similar to goal-xxx]`)
    - Requires passing service results through `CycleContextData` or a new planner-specific context object

- [x] **T2-R: Replan pattern recognition — detect thrash before throttle fires** (`RuleBasedDeliberationProposer`, new `ReplanPatternDetector`, `BasicSignals`, `CycleExecutor`)
  - New `REPLAN_THRASH_DETECTED` signal in `BasicSignals` (added to `PLANNER_SIGNALS` + `ALL`)
  - New `ReplanPatternDetector` (`analysis/pattern/`): lookback=6 planner snapshots, fires when severity ≥ 0.50; skips when `PLANNING_THROTTLED` already active; emits strength=1.0/decay=0.25
  - Wired in `CycleExecutor` after `planningThrashSignalAnalyzer`, inside the SCOPE_OUT_OF_SCOPE guard
  - `RuleBasedDeliberationProposer` handles it early → returns REPLAN + REPLAN_REQUESTED + ROLE_PLANNER

- [x] **T2-T: Skipped goals communicated to agents** (`CycleContextData`, `PlannerAgent`, `PlannerProtocolContract`)
  - Skipped (duplicate-rejected) proposals were only `log.warn` — invisible to the next planner run
  - `injectOutcomeGoals()` now returns `InjectionResult(skipped, injected)` instead of void
  - Skipped descriptions stored in `CycleContextData.skippedGoals` and carried into the next cycle
  - `PlannerProtocolContract` renders a `--- REJECTED PROPOSALS ---` block when non-empty

- [x] **T2-W: Throttle from skip ratio** (`PlannerAgent`)
  - When `skipped > injected` (majority of proposals rejected) within one planner run, raise
    `PLANNING_THROTTLED` at strength 0.6 with decay 0.15 — fades naturally over ~4 cycles

- [x] **T2-X: Early exhaustion — consecutive no-evolution cycles** (`GoalExhaustionDetector`)
  - Old Rules A+B required STAGNATION or REPETITION signals to fully build up before firing
  - New Rule C: `noChanges && lastEvolutionOutcome == null && (stagnatingSignal || lowConfidence)`
    → fires at confidence 0.70 without waiting for the full signal stack

- [x] **T2-AA: Pre-emptive exhaustion for persistently stagnating subgoals** (`GoalExhaustionDetector`, `GoalPlannerService`)
  - Subgoals that stagnated before being pre-empted had no memory of that history on re-entry.
  - Fix: track per-subgoal stagnation history (total STAGNATING cycles and consecutive REJECTED cycles) on `GoalEntity`. When accumulated evidence crosses a threshold on re-entry, fire exhaustion immediately with a shorter or zero grace period.

- [x] **T2-AB: Interpretation agent boilerplate — no steering signal emitted** (`DefaultInterpretationAgent`, interpretation prompt)
  - **Observed (2026-03-10 session):** All 5 interpretation calls returned the same paragraph verbatim with zero signals. The agent was producing boilerplate output regardless of cycle state.
  - Fix: tightened the prompt to require explicit signal recommendations even in steady-state. If no steering change is needed, output `Directives: NONE` — do not omit the field. Added `isValidResponse` check that rejects pure-filler outputs with no structured content.

- [>] **T2-AE: Evaluation LLM score anchoring at 0.85** (`EvaluationProtocolContract`)
  - Root cause: `INCLUDE_SCORE` example was literally `"Score: 0.85"` — LLM anchored to it every cycle.
  - Fix: replaced example with `"Score: 0.63 (partial implementation...)"` + added `SCORE_FROM_EVIDENCE`
    rule requiring derivation from diff observations; explicitly warns against defaulting to round values.
  - **Observed (2026-04-08 session):** Partial improvement — scores 0.75 and 0.82 appeared, but 0.85 was still the most common value (3 of 4 evaluations). Anchoring is reduced but not eliminated.
  - **Closed (2026-04-11):** Further prompt tuning has diminishing returns against instruction-following variance in the underlying model. Residual anchoring is a model behaviour, not a prompt gap. If this becomes a material problem, revisit with a structured response format (e.g. require score as a computed expression) or upgrade model. No further action planned here.

- [x] **T2-AJ: Complexity-adaptive regression tolerance — Phase 1 (file count)** (`ScoreRegressionHeuristic`, `CycleContextData`, `MergeAgent`, `ContextLineageTracker`)
  - Continuous formula: `effectiveFileCount = max(currentMutationFiles, lastAcceptedMutationFiles)`; `toleranceShift = min(0.10, extraFiles × 0.01)`; `confidenceScale = max(0.65, 1.0 - extraFiles × 0.05)`.
  - Catastrophic drop floor (`Δ ≤ -0.30`) bypasses all widening unconditionally.
  - `lastAcceptedMutationFileCount` added to `CycleContextData`; set by `MergeAgent` on successful merge; carried by `ContextLineageTracker` each cycle.

- [x] **T2-AL: Per-parent exhaustion pattern detection** (`GoalPlannerService`, `AutobuilderService`, new `ExhaustionPatternDetector`)
  - **Observed (2026-03-18 run):** Planner injected ~22 abstract/unevolvable subgoals all under the same parent. System exhausted them one by one; each SUBGOAL_EXHAUSTED triggered a replan which injected more siblings; global counter was reset mid-cascade by an unrelated merge.
  - Fix A — **per-parent exhausted-child counter**: when a parent accumulates K=3 exhausted children with zero merges contributed, mark the parent GIVEN_UP (cascade via existing `markGoalGivenUp()`).
  - Fix B — **exhaustion cluster signal**: when M subgoals exhaust within a short window AND are semantically similar, emit `PLANNER_EXHAUSTION_CLUSTER` signal to suppress further injection under that parent.

- [x] **T2-AM: Scope thrash detection** (`ScopeThrashSignalAnalyzer`, `AgentGraphService`, `Neo4jAgentGraphService`)
  - **Depends on T5-D** (scope assessments must be persisted to graph first). ✓
  - New `ScopeThrashSignalAnalyzer` queries N=3 consecutive OUT_OF_SCOPE scope action nodes for the current subgoal; emits `REPLAN_REQUESTED` (strength=1.0, decay=0.25) when threshold reached.
  - New `ScopeActionSnapshot` record + `findRecentScopeActions(subGoalId, limit)` added to `AgentGraphService` interface and `Neo4jAgentGraphService`.
  - Wired into `CycleExecutor` after `planningThrashSignalAnalyzer` (unconditional — runs even when `SCOPE_OUT_OF_SCOPE` is set, since thrash detection reads historical graph state, not in-cycle signals).

- [x] **T2-AN: REPETITION_DETECTED consistent-pattern escalation to REPLAN** (`RuleBasedDeliberationProposer`)
  - **Observed (2026-03-31 run):** `REPETITION_DETECTED` stayed above 0.90 from cycle 7 to 12, but no REPLAN fired — the co-condition `LOW_CONFIDENCE ≥ 0.90` was never met (scores 0.75–0.83).
  - Fix: replace `repetition ≥ 0.90 && lowConfidence ≥ 0.90` with `repetition.strength ≥ 2.0`. Strength ≥ 2.0 requires at least 2 consecutive re-detections — accumulated strength is the correct proxy since `Signal.reinforce()` resets age on every call.

- [x] **T2-AO: DIVERGENCE_REQUEST consumed only after actual divergence** (`EvolutionAgent`)
  - **Observed (2026-03-31 run):** `EvolutionAgent` removes `DIVERGENCE_REQUEST` on entry unconditionally (line 74), regardless of whether the LLM actually produced a different fingerprint. If the LLM ignores the divergence instruction, the signal is already gone.
  - Fix: move `withoutSignal(DIVERGENCE_REQUEST)` to after the fingerprint comparison. New fingerprint ≠ old → consume the signal. Same fingerprint → keep the signal alive for the next cycle (multi-cycle behavioral mode, not a one-shot flag).

- [x] **T2-AP: Score plateau detection — basic mechanism** (`CycleContextData`, `AutobuilderService`, `BasicSignals`, `EvolutionProtocolContract`)
  - **Observed (2026-03-31 run):** Scores locked in the 0.75–0.83 band for all 12 cycles. No "no improvement" signal existed.
  - Fix: `plateauCycles` counter in `CycleContextData`. Increment when accepted mutation has `|score - lastScore| < 0.03`. Reset on genuine improvement or rejection. After K=5 plateau cycles, emit `SCORE_PLATEAU` signal.
  - `SCORE_PLATEAU` feeds into: (A) evolution prompt — "score has been flat for N cycles, attempt a structurally different approach"; (B) deliberation as soft pressure toward exploration.
  - **Higher-tier note:** Long-term direction for plateau-breaking is hierarchical goal evaluation (T4-H).

- [x] **T2-BF: Stagnation circuit breaker + REPLAN mode = hard deadlock** (`RuleBasedDeliberationProposer`, `AutobuilderService`)
  - **Root cause (2026-04-09 analysis):** When `stagnationCircuitBreakerActive=true` (fires after 5 consecutive stagnating cycles without a merge, `STAGNATION_BREAKER_THRESHOLD`), `PlannerAgent` enters prune-only mode — it can remove goals but cannot inject new ones. If deliberation simultaneously returns `REPLAN` mode, only planner and analysis are allowed (pre-T1-BD). With T1-BD adding evolution back into REPLAN, this is partially mitigated. The remaining compound risk: circuit breaker prunes all remaining subgoals, leaving evolution with no current subgoal, and REPLAN keeps deliberation from returning NOOP where analysis could propose new executable work.
  - **Fix:** In `RuleBasedDeliberationProposer.propose()`, check `context.stagnationCircuitBreakerActive()` before returning any `DeliberationMode.REPLAN`. When the circuit breaker is active, return `DeliberationMode.NOOP` with a `DIVERGENCE_REQUEST` signal instead. Rationale: the circuit breaker was designed to suppress runaway planning, not to freeze execution. NOOP opens all roles; evolution can attempt divergence; the circuit breaker's prune-only constraint in `PlannerAgent` still holds (planner won't inject new goals). This separates the "no new planning" constraint from the "no evolution either" side-effect.
  - **Changes:** `RuleBasedDeliberationProposer.java`: add `if (context.stagnationCircuitBreakerActive()) return noop+diverge;` guard before any REPLAN return.
  - **Depends on:** T1-BD (evolution in REPLAN), T1-BE (REPLAN decay).

- [x] **T2-BG: T3-BG decompose coaching note unreachable during REPLAN lock** (`PlannerProtocolContract`)
  - **Root cause (2026-04-09 analysis):** `PlannerProtocolContract.currentSubGoalStatusBlock()` shows the decompose coaching note when `cumulativeStagnatingCycles >= 3`. But `cumulativeStagnatingCycles` is only incremented in `AutobuilderService` when cycle result is `STAGNATING` — which requires an evaluation result — which requires evolution to have run — which is blocked in REPLAN mode (pre-T1-BD). In the 00:42 session, subgoals lasted 1–2 cycles before exhaustion; the counter never reached 3. The coaching note that could have prompted `#SubGoalRemove + #SubGoalAppend` decomposition never appeared.
  - **Fix:** In `PlannerProtocolContract.currentSubGoalStatusBlock()`, show the coaching note when `stagnating >= 3 || ctx.hasStrongSignal(REPLAN_REQUESTED, 0.50)`. If REPLAN_REQUESTED is active, the subgoal is already in a stuck state regardless of how many formal STAGNATING cycles have been recorded — the planner should be coached to decompose immediately. The REPLAN signal is available on `CycleContextData` passed into `getUserPrompt()`.
  - **Changes:** `PlannerProtocolContract.java`: update `currentSubGoalStatusBlock()` condition; update the coaching note text to reflect both stagnation and REPLAN triggers.
  - **Note:** With T1-BD + T1-BE in place, evolution can now run during REPLAN, meaning STAGNATING results become possible again and the original threshold becomes reachable. T2-BG is still worth implementing as a faster trigger for the first few REPLAN cycles.

- [x] **T2-BH: `PLANNING_THROTTLED` from `PlanningThrashSignalAnalyzer` has decay=0.0 — never self-heals** (`PlanningThrashSignalAnalyzer`)
  - **Root cause (2026-04-09 analysis):** `PlanningThrashSignalAnalyzer` creates `PLANNING_THROTTLED` with `Signal.of(..., 1.0, 0.0, msg)` — decay zero means the signal is permanent until explicitly released by the analyzer's own `caughtUp` / ratio-improvement logic. The analyzer only releases it when planning inflow recovers, which requires new successful injections — but in a REPLAN lock, the planner cannot inject, so the condition never triggers. The signal compounds: planner is suppressed (by PLANNING_THROTTLED), which prevents injection, which prevents the throttle from releasing. In the 00:42 session, `PLANNING_THROTTLED` fired at `00:45:05` and blocked all subsequent planning.
  - **Fix:** Change the `PlanningThrashSignalAnalyzer` emission from `decay=0.0` to `decay=0.10`. This gives a natural expiry window of ~10 cycles. The analyzer can still release it early when conditions improve (the existing `withoutSignal(PLANNING_THROTTLED)` path at line 122 is unchanged). The `PlannerAgent` emissions (skip-ratio: decay=0.15, backlog-cap: decay=0.10) already have natural expiry; only the analyzer site was 0.0.
  - **Change:** `PlanningThrashSignalAnalyzer.java` line ~140: `Signal.of(BasicSignals.PLANNING_THROTTLED, 1.0, 0.0, msg)` → `Signal.of(BasicSignals.PLANNING_THROTTLED, 1.0, 0.10, msg)`.

- [x] **T2-BI: No merge history on `GoalEntity` — blocks cascade protection and prompt enrichment** (`GoalEntity`, `GoalPlannerService`, `MergeAgent`, `Neo4jGoalGraphService`, `PlannerProtocolContract`)
  - **Root cause (2026-04-09 analysis):** `GoalEntity` has no field tracking how many successful merges occurred under a given subgoal. This is the missing infrastructure for T1-BF (cascade protection), and also a useful prompt signal: the planner seeing "2 merges" next to a goal knows it produced real output and should not be removed lightly.
  - **Fix:**
    1. `GoalEntity.java`: add `int mergeCount` field (no Neo4j node annotation — persisted via existing `metadata` map under key `"mergeCount"`).
    2. `GoalPlannerService.java`: add `incrementGoalMergeCount(String goalId)` — calls `incrementGoalMetadataInt(goalId, "mergeCount")` (same pattern as `cumulativeStagnatingCycles`). Add `getGoalMergeCount(goalId)` for reads. In `mapGoalEntityFromMetadata()` or equivalent, load `mergeCount` from metadata on startup/graph sync.
    3. `MergeAgent.java`: call `goalPlannerService.incrementGoalMergeCount(context.currentSubGoalId())` after a successful merge (after the merge is applied and recorded).
    4. `Neo4jGoalGraphService.java`: persist `mergeCount` as part of the metadata map (already persisted as a JSON blob or map — no schema change needed).
    5. `PlannerProtocolContract.appendGoalTree()`: annotate goals with `[N merge(s)]` when `mergeCount > 0`, so the planner can see which goals produced real output.
    6. `PlannerProtocolContract.currentSubGoalStatusBlock()` (T3-BG): show current subgoal merge count.
  - **Depends on:** Nothing. Infrastructure ticket; T1-BF depends on this.
  - **Hotfix (2026-04-09):** `getMergeCount()` on `GoalEntity` is a computed getter backed by `metadata["mergeCount"]`. Jackson serialised it as a top-level `"mergeCount"` field in every `meta.json` snapshot; on deserialization (e.g. in `FileVariantCollector`) it threw `UnrecognizedPropertyException` because there was no setter, causing `hasAnyVariant()` to return false and blocking merge permanently (complete deadlock). Fixed by adding `@JsonIgnore` to `getMergeCount()` and `@JsonIgnoreProperties(ignoreUnknown=true)` to `GoalEntity`. Regression tests added to `GoalEntityTest`: `serialization_doesNotIncludeComputedGetters`, `deserialization_toleratesUnknownFields`, `roundTrip_preservesCoreFields`. **Rule: any future computed getter on `GoalEntity` must carry `@JsonIgnore`.**

- [x] **T2-BK: Subgoal-scoped signal isolation via entity persistence** (`CycleContextCoordinator`, `GoalPlannerService`, `CycleExecutor`, `BasicSignals`)
  - **Root cause (2026-04-09 analysis):** `CycleContextCoordinator.prepareRuntimeContext()` calls `previous.toBuilder()`, which copies the full signal map to the new cycle. Signals such as `STAGNATION`, `REPETITION_DETECTED`, `DIVERGENCE_REQUEST`, `LOW_CONFIDENCE`, and `SCORE_PLATEAU` describe a pattern *for a specific subgoal* and become poison when inherited by a freshly injected one. In the 2026-04-09 session, `STAGNATION` (strength=0.7, decayPerCycle=0.2) was carried into the first cycle of `goal-be7104f6`; it decayed to 0.5 but still exceeded `GoalExhaustionDetector`'s threshold of 0.50. Combined with a low-entropy first evolution (1 file changed), Rule A fired and the new subgoal was GIVEN_UP in its first cycle.
  - **Implemented (entity-backed, not strip-on-switch):** Follows the same pattern as `cumulativeStagnatingCycles` and `cumulativeCriteriaStatus`. `BasicSignals.SUBGOAL_SCOPED_SIGNALS` defines the isolated set. `CycleExecutor` persists them to `GoalEntity.metadata["subgoalSignals"]` after each `tickSignals()`. `CycleContextCoordinator` clears all subgoal-scoped types from the carried context and restores from the entity. Subgoal switch → new entity is empty → clean slate. Restart → entity has last decayed state → correctly recovered. Future multi-goal/parallel-goal scenarios work naturally since each entity carries its own signal history.

- [x] **T2-BM: Hard veto on accepted mutations with unmet criteria** (`EvaluationAgent`, `HeuristicNegotiator`)
  - **⚠ Superseded by T1-BG + T3-BA — to be removed via T1-BN.**
  - **Root cause (2026-04-09 session):** The evaluator marked criterion [2] as `✗` for the `GET /books` subgoal ("Response includes all previously created books") but still output `MutationAcceptanceState: ACCEPTED`. The heuristic score (0.79 > threshold) subsequently confirmed ACCEPTED. The unmet criterion had no binding effect on the acceptance decision. The code returned 404 on empty list (wrong) and was merged and closed as FULFILLED.
  - **Why T3-BA was insufficient at the time:** T1-BG (cumulative criteria tracking) did not yet exist. T3-BA used only the current-cycle `criteriaAssessments`, which could be incomplete (LLM omission). The subgoal could close FULFILLED even with a genuinely unmet criterion.
  - **Why T2-BM is now harmful (2026-04-10):** T1-BG's `cumulativeCriteriaStatus` now correctly gates subgoal closure via T3-BA regardless of whether individual mutations are accepted. T2-BM is redundant for its original purpose and actively creates a deadlock for multi-file criteria — see T1-BN for the full analysis and removal plan.
  - **Changes at time of implementation:** `EvaluationAgent.java` — guard after criteria parse, before `HeuristicNegotiator.negotiate()`.
  - **Observed:** 2026-04-09 session (implementation); 2026-04-10 session (harmful side-effect identified).

- [x] **T2-BL: Criteria-aware exhaustion suppression** (`GoalExhaustionDetector`, `CycleContextData`)
  - **Problem:** `GoalExhaustionDetector` Rules A and D fire when `STAGNATION` and low-entropy conditions are met, without any awareness of whether acceptance criteria are being progressively satisfied. A subgoal that is incrementally fulfilling its criteria — one `✓` per cycle — looks exactly like stagnation to the detector: ACCEPTED mutations, PROGRESSING goal state, low score delta. The result is premature exhaustion of a subgoal that is genuinely making progress.
  - **Fix:** At the start of `GoalExhaustionDetector.detect()`, compute `criteriaPassCount` from `context.cumulativeCriteriaStatus()` (count of `true` entries). Compare against `lastCriteriaPassCount` (stored in context — add this field). If `criteriaPassCount > lastCriteriaPassCount`, the subgoal is making criteria progress: return `ExhaustionReport.none("criteria progress detected — suppressing exhaustion")` before Rules A and D are evaluated. Only suppress A and D; Rules B and C (global stuckness / consecutive-no-evolution) still fire — those indicate the system has truly stopped producing output.
  - **`lastCriteriaPassCount` update:** Set at the end of each evaluation cycle (where `cumulativeCriteriaStatus` is updated) so the next cycle's detector can compare.
  - **Depends on:** T1-BG (source of `cumulativeCriteriaStatus`).

- [x] **T2-BJ: Per-parent exhaustion fires too aggressively with freshly injected subgoals** (`GoalPlannerService`, `AutobuilderService`, `application.yml`)
  - **Root cause (2026-04-09 analysis):** `exhaustParentIfStuck()` threshold is `max(minFloor=3, ceil(0.51 × totalChildren))`. In a REPLAN cycle, the planner injects a batch of 3–4 subgoals. Each gets 1–2 cycles before exhaustion fires. With 4 subgoals and minFloor=3, only 3 need to exhaust for the parent to be given up — cascading to the 4th. This can happen in 6–8 cycles. The parent is killed before any child had a real chance to evolve.
  - **Fix A — raise minFloor default:** Change `@Value("${autobuilder.goal.min-exhausted-children-floor:3}")` default to `5`. Also update `application.yml`. With minFloor=5, a parent with 4 children can never trigger per-parent exhaustion (need 5 given up, but there are only 4 children). This is a simple, safe configuration change.
  - **Fix B — exclude zero-attempt children from threshold count (longer term):** Don't count children toward the exhaustion threshold if they had zero evolution attempts (future: use `GoalEntity.evolutionAttemptCount` or check `mergeCount == 0 && no evolution graph node`). Children that were given up before evolution ever ran are meaningless signals about the parent's viability.
  - **Implement Fix A now; Fix B after T2-BI (merge tracking) provides the data.**
  - **Changes:** `AutobuilderService.java`: change `@Value` default from 3 to 5. `application.yml`: add/update `autobuilder.goal.min-exhausted-children-floor: 5`.

- [>] **T2-BP: Signal saturation — value cap removes differential information** — promoted to **T4-AL** (2026-04-15). Infrastructure-level concern requiring a signal dynamics design decision; too broad for a T2 steering fix.

- [x] **T2-BQ: Analysis agent injects off-goal subgoals** (`AnalysisProtocolContract`)
  - **Observed (2026-04-10 session):** After main subgoals exhausted, analysis injected "Improve logging" (`goal-5f767c06`, 8 cycles) and "Implement logging for critical operations" (`goal-c32b8072`, 3 cycles). Neither endpoint is in the root goal spec. Together these burned 11 of 35 cycles — 31% of the run — on irrelevant work.
  - **Root cause:** `AnalysisProtocolContract.getUserPrompt()` says "produce actionable follow-up tasks" with no constraint to the root goal. The LLM defaults to general code-review suggestions (logging, refactoring) rather than staying on-spec.
  - **Fix:** Add a `ROOT_GOAL_SCOPE` rule to `AnalysisProtocolContract`: "All entries under `ProposedExecutableSubGoals` MUST be directly required to implement a feature or endpoint named in the root goal. Do NOT propose infrastructure improvements (logging, error handling, refactoring, testing) unless explicitly required by the root goal spec."
  - **Changes:** `AnalysisProtocolContract.java` — add rule in system or user prompt (~3 lines).

- [x] **T2-BW: AnalysisAgent injection rate unconstrained — no per-subgoal budget or global cap** (`AnalysisAgent`, `AnalysisProtocolContract`, `GoalPlannerService`)
  - **Observed (2026-04-11 session):** The AnalysisAgent ran on every EXECUTABLE/HYBRID cycle and injected 1–2 new child subgoals each time. Combined with T1-CA (orphaned children), this produced a continuously growing goal chain. Even after T1-CA is fixed (cascade pruning), the AnalysisAgent can still saturate the backlog with injections that quickly stagnate and waste cycles.
  - **Root cause:** `AnalysisAgent` has no injection budget check. Unlike the planner (which skips injection when `activeSubgoalCount >= N`), the AnalysisAgent always attempts to inject follow-up subgoals whenever it classifies the current one as EXECUTABLE. There is no feedback from STAGNATION/REPETITION signals into injection throttling.
  - **Fix — two-tier budget:**

    **Tier 1 — per-subgoal budget (resets on subgoal change):**
    `budget = max(1, countUnmetCriteria(currentSubGoalId))`
    The AnalysisAgent may inject at most this many child subgoals during the current subgoal's lifetime. Rationale: each injection slot corresponds to one unmet criterion — if a criterion is already passed (T1-BG / T3-BI), there is no point injecting a subgoal to address it. Once all unmet criteria have a corresponding child injected, the budget is exhausted.

    **Tier 2 — global session budget (proportional backstop):**
    `global_remaining = sum(unmetCriteria per ACTIVE subgoal) - totalAnalysisInjectionsThisSession - sum(allCriteria per GIVEN_UP subgoal)`
    The second term tracks how much work has already been injected; the third term discounts goals that were given up (evidence of failed attempts, reducing confidence in further injection). The global budget shrinks both when criteria are genuinely met (completion) and when subgoals are given up (exhaustion). This means a stagnating session where nothing completes but everything gets given up will gradually see the global budget approach zero — preventing unbounded injection cascades.

    **LLM-directed de-escalation:**
    Add an optional `#InjectionThrottle: <reason>` directive to the AnalysisAgent response format. When the AnalysisAgent detects from its prompt context (active signals include `REPETITION_DETECTED` or `STAGNATION` at significant strength) that further injection is counterproductive, it emits this directive. The response parser reads it and sets the per-subgoal budget to 0 for the current cycle. This allows the LLM itself to recognise circular patterns and voluntarily suppress injection — analogous to the planner choosing not to emit `#SubGoalAppend` when it judges the backlog sufficient.

  - **Changes:** `AnalysisAgent.java` — read per-subgoal budget from `GoalPlannerService` before injection; decrement on inject; respect `#InjectionThrottle`. `GoalPlannerService.java` — expose `countUnmetCriteria(subGoalId)` and `totalAnalysisInjections` tracking. `AnalysisProtocolContract.java` — document `#InjectionThrottle` in the response format; add signal-awareness note prompting the LLM to throttle when repetition is detected. `application.yml` — optional: expose budget divisor as a configurable knob.
  - **Session-boundary fix (2026-04-12):** The `givenUpTotal` term was pre-counting GIVEN_UP goals from previous sessions loaded from Neo4j at startup, draining the budget before the first AnalysisAgent cycle. Fix: added `currentPlanningRunId` field (persisted in root goal metadata, incremented by `initPlanningRunId()` at startup). All new subgoals are stamped with this run ID in `addSubGoal()`. `getGlobalAnalysisInjectionBudget()` filters `givenUpTotal` to only count goals with `planningRunId == currentPlanningRunId`. Test `getGlobalAnalysisInjectionBudget_excludesGivenUpFromPriorRuns` added.
  - **Depends on:** T1-CA (cascade GIVEN_UP makes the per-subgoal budget meaningful — without it, budgets reset every time an orphaned child becomes active). T1-BG / T3-BI (cumulative criteria status is the data source for `countUnmetCriteria`).
  - **Note:** T2-BQ (root-goal scope constraint in prompt) addresses *what* the AnalysisAgent proposes. T2-BW addresses *how many* it proposes and *when* it throttles. Implement T1-CA first, then T2-BW; T2-BQ can run in parallel.

- [x] **T2-BR: `CrossFileConsistencyHeuristic` too aggressive for incremental construction** (`CrossFileConsistencyHeuristic`)
  - **Observed (2026-04-10 session):** The heuristic fired MEDIUM/0.65 on `LoanController.java` calling `bookService.markBookAsAvailable()` and `memberService.updateMemberLoanCount()` — methods not yet declared in `BookService`/`MemberService`. Result: `goalProgress=STAGNATING` on what was actually a valid incremental mutation. This compounded with `ProblemReductionHeuristic` to produce REJECTED, burning cycles on a half-built service graph.
  - **Root cause:** For a codebase being built incrementally, calling a method on a collaborator that doesn't exist *yet* is expected and correct — evolution writes the caller before the callee. The heuristic conflates "structurally incomplete" with "wrong."
  - **Fix:** Demote the heuristic to `LOW` confidence (from `MEDIUM`) when the *called service class exists in the output* but lacks the method — this is the incremental case. Only escalate to MEDIUM or higher when the called service class itself doesn't exist (the callee is entirely absent), which more reliably indicates a hallucinated dependency rather than a work-in-progress one.
  - **Changes:** `CrossFileConsistencyHeuristic.java` — add class-existence check to confidence calculation.

- [x] **T2-BS: Evolution prompt should enumerate required files for multi-file subgoals** (`EvolutionProtocolContract`, `PlannerProtocolContract`, `GoalEntity`)
  - **Context:** The parser and `EvolutionOutcome` already support multiple `FileMutation` entries per cycle — the LLM response format allows multiple fenced code blocks and the parser collects all of them with a `while` loop. The limitation is behavioural: when the prompt focuses on a single subgoal description, the LLM naturally scopes to one file. It doesn't spontaneously touch `BookService.java` and `LoanController.java` in the same response without being told to.
  - **Fix:** When a subgoal's acceptance criteria reference multiple files (e.g. "LoanController calls return endpoint" AND "BookService has markBookAsAvailable method"), surface those as an explicit `--- REQUIRED FILES ---` block in the evolution prompt: "This subgoal requires changes to the following files. Your response MUST include a code block for each." The file list can come from the planner (who could emit `#RequiredFiles` alongside `#SubGoalAppend`) or from static analysis of the acceptance criteria text.
  - **Note:** T2-BR (CrossFileConsistency calibration) reduces the penalty for partial cross-file mutations. This ticket enables the LLM to emit complete ones.

- [x] **T2-BT: Stale problem context with dead `scopeGoalId` poisons meta-deliberation with wrong file paths** (`DefaultProblemDetector`, `OrchestrationSnapshotFormatter`, `MetaDeliberationAgent`)
  - **Observed (2026-04-10 22:56 session):** Lines 206271+, ~207000 area — meta-deliberation step instructions repeatedly specify `src/main/java/com/example/library/LoanService.java` (a non-existent path; project package is `com.example.demo`). The path originates from a problem recorded while `goal-70625261` was active. After that subgoal was marked GIVEN_UP, its problem entries (with `scopeGoalId=goal-70625261`) persisted in the open-problem list and were included in the orchestration snapshot fed to the meta-deliberation LLM. The LLM then hardcoded the stale path into every evolution instruction, causing evolution to produce no meaningful changes cycle after cycle.
  - **Root cause:** `DefaultProblemDetector` records problems with a `scopeGoalId` but doesn't filter them when the scoping subgoal is no longer active. The orchestration snapshot includes all OPEN problems regardless of whether their scope is still relevant.
  - **Fix:** In `OrchestrationSnapshotFormatter` (or `DefaultProblemDetector.resolve()`), filter out problems whose `scopeGoalId` does not match the current active subgoal ID (or the root goal ID for goal-scoped problems). Alternatively, auto-resolve all problems scoped to a subgoal when that subgoal transitions to GIVEN_UP or COMPLETED.
  - **Changes:** `DefaultProblemDetector.java` — when a subgoal transitions (GIVEN_UP/COMPLETED), auto-resolve all open problems with matching `scopeGoalId`. Or: `OrchestrationSnapshotFormatter.java` — filter problem list to `scopeGoalId == currentSubGoalId || scopeGoalId == rootGoalId`.

- [x] **T2-BU: Planner injects subgoals without acceptance criteria, silencing the T2-BL criteria-progress suppression** (`PlannerProtocolContract`, `PlannerAgent`)
  - **Observed (2026-04-10 22:56 session):** Line ~208000 — "Planner injected subgoal [goal-d2bca121] with no acceptance criteria: 'Implement GET /books/{id}...'" After replanning with an exhausted backlog, the planner emitted a bare `#SubGoalAppend` entry with no `Criteria:` block. Without criteria, `cumulativeCriteriaStatus` is always empty → `currentPassCount=0`, `lastCriteriaPassCount=0`, `criteriaProgressing=false` permanently. The T2-BL suppression (`criteriaProgressing` guard on Rules A and D) therefore never activates for this subgoal, making it as easy to exhaust as if T2-BL didn't exist.
  - **Root cause:** The planner prompt NOTE block instructs the LLM to append subgoals but does not explicitly require a `Criteria:` block per entry. When the LLM is under replanning pressure (REPLAN mode, empty backlog), it tends to omit criteria.
  - **Fix A (prompt):** Add a hard rule to the planner NOTE block: "Every `#SubGoalAppend` entry MUST include a `Criteria:` block with at least one testable criterion. Entries without criteria will be ignored." **Fix B (code):** In `PlannerAgent.injectOutcomeGoals()`, if a proposed subgoal has no criteria after parsing, log a WARN and skip injection (treat as invalid). Fix A alone may be sufficient; Fix B makes it robust.
  - **Changes:** `PlannerProtocolContract.java` — add criteria-required rule to NOTE block. Optionally `PlannerAgent.java` — add criteria-presence guard in `injectOutcomeGoals`.

- [>] **T2-BV: PLANNING_THROTTLED similarity check rejects re-proposals of GIVEN_UP subgoals, blocking exhaustion recovery** (`PlannerAgent`, similarity detection)
  - **Superseded by T2-BO (2026-04-11 analysis).**
  - **Observed (2026-04-10 23:50 session):** After the first round of subgoals were all GIVEN_UP, the planner attempted to re-inject 7 fresh subgoals (POST /loans, GET /books, etc.). The similarity detector rejected all 7 as duplicates of existing goals — it compared against GIVEN_UP entries the same way it compares against COMPLETED ones. Result: `7 skipped / 0 injected → PLANNING_THROTTLED`. With the planner blocked and no active subgoal, all 5 agents became unexecutable and the system deadlocked.
  - **Root cause:** The planner's duplicate-detection logic treats GIVEN_UP goals the same as COMPLETED goals when computing the "already exists" check. But a GIVEN_UP subgoal represents a failed attempt, not a finished one — re-proposing it is legitimate recovery behaviour.
  - **Why superseded:** T2-BO changed `Neo4jGoalGraphService.findAllDescriptions()` to filter `WHERE g.status <> 'GIVEN_UP'`, so `GoalSimilarityService.isDuplicate()` no longer compares against GIVEN_UP entries at all. GIVEN_UP goals are handled separately via `countGivenUpMatches()` with a retry limit of 2 — a first-round re-proposal (1 prior GIVEN_UP match) passes through. The 7-blocked scenario this ticket describes cannot occur with T2-BO in place. **Verified in code:** `Neo4jGoalGraphService.findAllDescriptions` line 334, `PlannerAgent.injectOutcomeGoals` lines 260–263.

- [x] **T2-BN: Surface banked criteria in the evolution prompt** (`EvolutionProtocolContract`, `CycleContextData`)
  - **Note (2026-04-10):** Originally motivated as a companion to T1-BL (which was withdrawn). Still valid as a standalone steering improvement after T1-BN ships: even without T2-BM blocking, telling the evolution which criteria are already banked focuses single-file mutations on the remaining gap rather than re-implementing already-satisfied criteria.
  - **Observation window elapsed (2026-04-11):** T1-BN has been in production for multiple sessions. Sessions since T1-BN show evolution still re-implements already-satisfied criteria (e.g. criterion 1 re-touched in cycles where only criterion 2 is unmet). Natural convergence is not sufficient — the evolution has no way to know which criteria are already banked without being told. This ticket should be promoted to active.
  - **Fix:** In `EvolutionProtocolContract.getUserPrompt()`, inject a `--- CRITERIA PROGRESS ---` block when `cumulativeCriteriaStatus` is non-null and non-empty:
    ```
    --- CRITERIA PROGRESS ---
    [1] ✓ <criterion text>  ← already satisfied in a prior cycle; do NOT re-implement
    [2] ✗ <criterion text>  ← still unmet; focus your mutation here
    ```
  - **Changes:** `EvolutionProtocolContract.java` — add block rendering from `context.cumulativeCriteriaStatus()` + `context.currentSubGoalCriteria()`.
  - **Depends on:** T1-BG, T3-AW. Implement after T1-BN to observe whether natural convergence is sufficient without it.

- [x] **T2-BX: Inject structural summary of related existing files into evolution prompt** (`EvolutionProtocolContract`, `CycleContextData`)
  - **Observed (2026-04-13 session):** The evolution for "POST /loans returns 404/409/422" produced a `LoanService` that threw `ValidationException` for all error paths. `GlobalExceptionHandler.java` — generated by an earlier subgoal — mapped `ValidationException → 400`. The evolution had no visibility into this mapping, so it picked the convenient existing exception type rather than introducing specific ones. The evaluator accepted it because it saw exception-handling code without tracing the full HTTP response chain. Same issue with `MethodArgumentNotValidException`: evolution added `@Valid` to the controller but `GlobalExceptionHandler` was never updated because the evolution didn't know the handler's current content.
  - **Root cause (two layers):**
    1. **HTTP response chain opacity:** When a criterion says "returns 404 when X", the evolution needs to know: "throw `SpecificException`" in the service AND "map `SpecificException → 404`" in `GlobalExceptionHandler`. T2-BS surfaces the required *file paths* but not the *current content* of those files. The LLM can't make a targeted delta if it doesn't know what's already mapped.
    2. **Whole-file replacement risk:** When evolution rewrites a file produced by an earlier subgoal, it replaces the entire content. Without visibility into existing declarations, it may silently drop mappings added by earlier subgoals.
  - **Design principle:** Injecting full file content for every required file causes context window explosion, especially for local models. Instead, inject a *structural summary* generated by lightweight static analysis of `previousOutput` — not by an LLM call. One line per relevant declaration. Token-cheap, directly informative.
  - **Summary format per file type:**
    - `@RestController` / `@Controller`: list of `@RequestMapping` / `@GetMapping` etc. with method signatures
    - `@ControllerAdvice` / exception handler: list of `@ExceptionHandler(X.class) → HTTP status` one-liners
    - Service / component: list of public method signatures (return type + name + params)
    - Other: class name + public method names only
    - Example output:
      ```
      --- EXISTING FILE STRUCTURE ---
      GlobalExceptionHandler.java [EXISTS — retain all existing handlers, only ADD new ones]:
        @ExceptionHandler(ValidationException.class) → 400
        @ExceptionHandler(RuntimeException.class) → 500
      LoanService.java [EXISTS — update in place]:
        createLoan(String bookId, String memberId) : LoanEntity
        returnLoan(String loanId) : LoanEntity
      ```
  - **Instruction appended to prompt:** "For files marked EXISTS, your code block MUST include all existing declarations shown above plus your new additions. Do NOT omit existing methods or handlers."
  - **Scope:** This applies to required files identified by T2-BS (criteria referencing ≥2 files). For the primary subgoal file, full content injection is acceptable since evolution was already expected to rewrite it; the summary is for *related* files only.
  - **Static analysis approach:** Parse `previousOutput` with regex / simple Java AST walking (no LLM). Key patterns: `@ExceptionHandler`, `@GetMapping/@PostMapping` etc., public method signatures. Implemented as a new `FileStructureSummarizer` utility.
  - **Why not T2-BN alone:** T2-BN tells the LLM which criteria are already banked. T2-BX tells the LLM what code is already in the *related* files it must not clobber. Complementary — implement together or T2-BN first.
  - **Changes:** `EvolutionProtocolContract.java` — extend `requiredFilesBlock()` to include structural summary from `context.previousOutput()` for files that already exist. New `FileStructureSummarizer.java` utility for static analysis.
  - **Depends on:** T2-BS (the REQUIRED FILES infrastructure to extend). T2-BN (complementary).

- [x] **T2-BY: Split subgoal only when criteria span truly independent concerns** (`PlannerProtocolContract`, `AnalysisProtocolContract`)
  - **Context:** T2-BX handles the common case — one subgoal touching multiple *coordinated* files (service + handler + controller all implementing the same feature). That is NOT a split candidate; evolution should handle it in one cycle with structural summaries for context.
  - **Split case:** A subgoal whose criteria refer to two *independent* endpoints or features (e.g. "POST /loans returns 404" AND "GET /members returns paginated results") — these have no shared implementation path and would each independently warrant their own evaluation verdict.
  - **Detection heuristic (AnalysisAgent):** When `ProposedExecutableSubGoals` contains a single entry whose criteria list references ≥2 distinct HTTP verbs+paths (e.g. `POST /loans` and `GET /members` both present in criteria text), split into one subgoal per endpoint. Criteria are divided accordingly.
  - **Not a default:** Coordinated multi-file work for one endpoint stays as one subgoal — T2-BX provides the context needed. Only independent-endpoint conflation triggers a split.
  - **Changes:** `AnalysisProtocolContract.java` — add rule: "If a single ProposedExecutableSubGoal's criteria span more than one independent HTTP endpoint or feature, split it into one subgoal per endpoint with its own Criteria block." `PlannerProtocolContract.java` — same rule in the FULL_COVERAGE_REQUIRED block to prevent conflation at planning time.
  - **Depends on:** T2-BX (handles the coordinated-files case so splitting isn't needed there).

- [>] **T2-BZ: Evaluation heuristic and prompt guidance for isolated in-memory state across controllers** (`EvaluationHeuristic`, `EvolutionProtocolContract`, `PlannerProtocolContract`)
  - **Layers A+B done (2026-04-15):** Layer B done 2026-04-13. Layer A (IsolatedStateHeuristic) done 2026-04-15. Layer C reverted — the @RequestMapping noun-extraction approach was too Spring/REST-specific; the system evolves arbitrary codebases. T2-BX activation gap remains open as a separate problem.
  - **Observed (2026-04-13 session):** `LoanController` declared `private final List<Book> books = new CopyOnWriteArrayList<>()` and `private final List<Member> members = new CopyOnWriteArrayList<>()`. Since books and members are added to `BookController.books` and `MemberController.members` respectively, `LoanController.books/members` are always empty — every `POST /loans` call returns 404 regardless of how many books and members are registered. The evaluator accepted the mutation (all 4 criteria checked ✓) because the code *looks* correct statically: the null-checks, stream filters, and 404/409/422 responses are present. The runtime brokenness is invisible to static LLM review.
  - **Same class of failure previously observed (2026-04-08):** `BookResource.books` disconnected from `BookController.books`, noted in T3-BA live session finding. No remediation ticket was created at the time.
  - **Root cause (three layers):**
    1. **No shared-state mechanism detected:** Spring can wire shared state via `@Service`/`@Repository` beans, but the LLM defaults to `new CopyOnWriteArrayList<>()` field init inside each controller — identical type, separate instance.
    2. **Criteria don't require shared state:** Acceptance criteria for "POST /loans returns 404 when bookId not found" only require the *response code*, not the *lookup mechanism*. Code that consults an always-empty list satisfies the literal criterion.
    3. **T2-BX didn't fire:** The structural summary of `BookController` (which would have shown `books` as an existing managed list) was never injected because criteria for `POST /loans` contained no Java class names. The one feature designed to surface this context was blocked by its own activation gate.
  - **Fix — three independent layers, implement in order:**

    **Layer A — `IsolatedStateHeuristic` (highest priority — rejects the broken mutation):**
    Add a new `EvaluationHeuristic` that detects shadow-list declarations in mutated controllers.
    Algorithm (purely regex-based; no LLM call):
    1. For each file in `diff.changedFiles()` annotated with `@RestController` or `@Controller`:
       a. Extract field declarations matching `(List|ArrayList|CopyOnWriteArrayList|LinkedList)<(\w+)>\s+\w+\s*=\s*new` — captures the element type `T`.
       b. For each extracted `T`, scan every file in `context.previousOutput()` for the same pattern in a different `@RestController` class.
       c. If another controller already owns a `List<T>` or `CopyOnWriteArrayList<T>`, the new declaration is an isolated shadow.
    2. On match: confidence=HIGH, opinion=REJECTED, rationale: "Controller declares its own `List<T>` for type `T` owned by another controller — shared state broken at runtime. Use an injected `@Service` or move shared state to a common bean."
    3. On no match: abstain (return empty opinion).
    - **Changes:** New `IsolatedStateHeuristic.java` under `agent/evaluation/heuristic/`. Register in `HeuristicNegotiator` alongside existing heuristics. Add unit tests with representative BookController + LoanController content covering: (a) isolated shadow → REJECTED; (b) injected service field → abstain; (c) same-class type list → abstain.

    **Layer B — Planner prompt guidance (prevents the mistake instead of catching it):**
    Add a `SHARED_STATE_RULE` paragraph to `PlannerProtocolContract` alongside the existing `ACCEPTANCE_CRITERIA` rule:
    ```
    SHARED_STATE_RULE: If a subgoal's endpoint reads or writes a resource type that a *previous* subgoal's controller already manages (e.g. POST /loans reads Book and Member data created by POST /books / POST /members), its acceptance criteria MUST include a criterion explicitly requiring shared state: "* LoanController must read books and members via shared state — not private new collections". This prevents the implementation from creating isolated shadows of another controller's data.
    ```
    This fires at planning time before evolution runs, encoding the constraint in a criterion that the evaluator will check.
    - **Changes:** `PlannerProtocolContract.java` — add rule paragraph (~6 lines) in the ACCEPTANCE_CRITERIA block.

    **Layer C — Broaden T2-BX activation gate (provides context even with behavior-level criteria):** ⚠ NEEDS REWORK
    T2-BX currently fires only when criteria text contains Java class names matching existing files. This misses behavior-level criteria ("returns 404 when bookId not found"). A secondary activation path is needed, but the original design (extract noun from `@RestController` / `@RequestMapping` path) was too Spring/REST-specific — the system evolves arbitrary codebases. A general-purpose design is required before this layer can be implemented.
    - **Original approach (rejected):** extract `@RequestMapping` path segment, singularise it, match against criteria text. Fragile (requires inflection logic) and assumes Spring REST conventions.
    - **Open question:** how to detect that existing files are relevant to the current subgoal without assuming framework or domain? Candidate ideas: match significant tokens from subgoal description/criteria against filenames; use the set of files touched by sibling subgoals under the same parent goal.
    - **Note:** Layer C alone does not prevent acceptance of a broken mutation — it only provides context to evolution. Layer A is the backstop regardless.

    **Layer D — Extend `IsolatedStateHeuristic` to detect `new ServiceClass()` inside services/controllers (added 2026-04-22):**
    Run 20260422_2 surfaced a sibling failure mode of the Layer A pattern: `LoanService.isBookExists` instantiated `new BookService()` per call instead of using the injected singleton. Same broken-shared-state semantics as Layer A's shadow `List<T>` (every call sees an empty/independent collection), expressed as a constructor call rather than a field initializer. The evaluator accepted it for the same reason — statically the code looks correct.
    Algorithm (regex extension to the existing heuristic — no new heuristic class):
    1. For each file in `diff.changedFiles()` annotated with `@RestController`, `@Controller`, `@Service`, or `@Component`:
       a. Scan method bodies for `new (\w+Service|\w+Repository|\w+Controller)\s*\(` — captures the class name `C`.
       b. Cross-check `context.previousOutput()` for any file declaring `class C` annotated with `@Service`, `@Repository`, `@Component`, or `@Controller` — i.e. `C` is already a Spring-managed bean elsewhere in the codebase.
       c. If yes, the `new C()` call bypasses Spring DI and creates an isolated instance with its own state — same defect class as the shadow list.
    2. On match: confidence=HIGH, opinion=REJECTED, rationale: "`new C()` instantiates a Spring-managed bean directly, bypassing dependency injection — produces an isolated instance with independent state. Inject `C` as a constructor/field dependency instead."
    3. On no match: abstain. Self-instantiation of plain DTOs / value objects is fine — only managed-bean classes trigger.
    - **Changes:** Extend `IsolatedStateHeuristic.java` with the new scan pass. Add unit tests: (a) `new BookService()` inside a `@Service` where `BookService` is `@Service` elsewhere → REJECTED; (b) `new Book()` (DTO, no stereotype) → abstain; (c) injected `BookService` field → abstain.
    - **Depends on:** Layer A (this layer extends the same heuristic class).
    - **Origin:** Run 20260422_2 LoanService finding.

  - **Ordering:** Layer A first (stop accepting broken mutations), Layer B alongside (prevent them at planning time), Layer C after A is validated in a live session, Layer D as a follow-on regex extension once Layer A is in place.
  - **Depends on:** T2-BX (Layer C extends its activation gate). T3-AW (Layer B relies on criteria being mandatory).
  - **Mitigated by:** T3-BH (sibling context in evolution prompt would have shown that `BookController` owns books — but T3-BH is about sibling subgoals, not files already in `previousOutput`).

- [x] **T2-CA: Planner does not propagate root goal cross-cutting constraints to subgoal criteria** (`PlannerProtocolContract`, `EvaluationProtocolContract`)
  - **Observed (2026-04-13 session):** The root goal spec states: *"Invalid input returns HTTP 400 with JSON body `{"message":"<reason>"}`. Business-rule violations use the codes above with the same JSON shape."* This is a cross-cutting invariant — it applies to every endpoint, every error code. The planner wrote per-subgoal criteria purely in HTTP status-code terms ("returns 404 when bookId not found", "returns 409 when book already on loan") without mentioning the body format in any criterion. The evaluator never flagged missing response bodies because no criterion required them. Result: `GET /books/{id}`, `GET /members/{id}`, all `POST /loans` 404 paths, and the `POST /loans/{id}/return` 404 path use `ResponseEntity.notFound().build()` — empty body, violating the spec.
  - **Broader pattern:** Root goal specs routinely contain invariants that cut across all subgoals: error response format, authentication scheme, content-type requirements, data storage constraints ("no database — in-memory only"), naming conventions, logging requirements. The planner currently treats each subgoal as an independent unit and writes criteria only from the subgoal description. It never re-reads the root goal for constraints that apply universally.
  - **Why this wasn't caught earlier:** Previous sessions used a simpler root goal (no explicit error body format). The firestarter goal's error-body invariant is the first time this class of failure was clearly observable in the criteria gap.
  - **Fix — two complementary stages:**

    **Stage 1 — Planner prompt: explicit cross-cutting invariant rule (changes the criteria produced):**
    Add a `CROSS_CUTTING_INVARIANTS` rule to `PlannerProtocolContract` in the ACCEPTANCE_CRITERIA block, immediately after the main criteria instruction:
    ```
    CROSS_CUTTING_INVARIANTS: Before writing acceptance criteria for each subgoal, re-read the Root Goal description for constraints that apply to ALL subgoals (error response format, authentication, content-type, data storage model, naming conventions). For each such invariant, add at least one criterion to EVERY subgoal's Criteria block. Examples:
    - Root goal says all errors return {"message":"..."}: add "* All error responses (4xx/5xx) return JSON with a \"message\" field" to every endpoint subgoal.
    - Root goal says data is stored in-memory: add "* No database or repository — data stored in a private collection" to every controller subgoal.
    Omitting universal constraints from criteria is a planning error. Evaluators will not enforce invariants that do not appear as criteria.
    ```
    This is a prompt-only change. The planner LLM already has the root goal text in context as the `--- GOALS ---` block; the rule directs it to re-read that block for invariants before constructing each `Criteria:` sub-block.
    - **Changes:** `PlannerProtocolContract.java` — add CROSS_CUTTING_INVARIANTS rule paragraph (~8 lines).

    **Stage 2 — Evaluation prompt: root goal invariant reminder (catches what the planner misses):**
    Add a brief invariant-reminder note to `EvaluationProtocolContract.getUserPrompt()` immediately after the `--- ACCEPTANCE CRITERIA ---` block:
    ```
    ⚠ ROOT GOAL INVARIANTS: Before scoring, also verify that the mutation honours any universal constraints stated in the Root Goal description above (error response format, data storage model, etc.). If the mutation violates a universal constraint not listed as a criterion above, mark it as an implicit ✗ and factor it into your score.
    ```
    This is a defense-in-depth measure. It does not require programmatic extraction of invariants — it instructs the evaluator to re-read the `--- ROOT GOAL ---` section it already has in context and apply constraints not captured as per-subgoal criteria.
    - **Changes:** `EvaluationProtocolContract.java` — add invariant-reminder note (~3 lines) below the `--- ACCEPTANCE CRITERIA ---` render block.
    - **Caveat:** The reminder is LLM-direction only — compliance depends on the model. Stage 1 (planner) is the durable fix; Stage 2 is a catch-all for when Stage 1 is incomplete.

  - **Expected impact:** Stage 1 prevents the problem: future sessions' POST /members and POST /loans subgoals will have criteria like "* All 404/409/422 responses return `{"message":"..."}` body." Stage 2 provides a second line of defence for pre-existing subgoals injected before Stage 1 is live, or for invariants the planner still misses under token pressure.
  - **Depends on:** T3-AY (criteria mandatory — Stage 1 is only effective if criteria are required). T3-AZ (per-criterion ✗ assessment — Stage 2's implicit ✗ needs the CriteriaCheck mechanism to carry weight).
  - **Note:** This ticket addresses the *criteria gap* (planner doesn't write the right criteria). T2-BZ Layer B addresses *specific* cross-cutting constraints (shared state) with a targeted rule. T2-CA addresses the *general* case — any class of invariant the root goal might declare. Both should be implemented; T2-CA's CROSS_CUTTING_INVARIANTS rule subsumes T2-BZ Layer B, but T2-BZ's IsolatedStateHeuristic (Layer A) remains independently valuable as a heuristic backstop that doesn't depend on LLM criterion compliance.

- [x] **T2-CB: Stagnation circuit breaker does not apply to T1-CA empty-backlog injection — infinite churn loop** (`PlannerAgent`, `AutobuilderService`)
  - **Observed (2026-04-13 19:29 session, 19:36–19:39):** T2-BO fix B intentionally bypasses the stagnation circuit breaker when the backlog is empty, to prevent a deadlock where no agents can run. But each GIVEN_UP subgoal empties the backlog momentarily, then T1-CA injects a new subgoal — which immediately exhausts and empties the backlog again. The circuit breaker fires at `consecutiveWithoutMerge=5` and suppresses `REPLAN_REQUESTED`, but T1-CA bypasses it on every cycle because the backlog is transiently empty after each GIVEN_UP. Result: 7 subgoal variants for `getLoansByMember` injected and exhausted in sequence with no merge, consuming ~3 minutes of LLM budget.
  - **Root cause:** The bypass in T2-BO ("when backlog is empty, inject regardless of circuit breaker") was designed for a different scenario — backlog empty at steady state with no active subgoal. The churn scenario is: backlog empties every ~20 seconds because each injection immediately fails. These two states are indistinguishable to the current guard.
  - **Fix:** Add a per-session `consecutiveEmptyBacklogInjectionsWithoutMerge` counter (similar to `consecutiveSubgoalExhaustionsWithoutMerge`). Increment it in `PlannerAgent` each time T1-CA fires while the circuit breaker is active (`consecutiveStagnatingCyclesWithoutMerge >= circuitBreakerThreshold`). Reset it on any `merged=true` cycle. After `EMPTY_BACKLOG_INJECTION_LIMIT` (suggested: 3) consecutive bypasses without a merge, refuse further injection and inject `GOAL_EXHAUSTED` + `SYSTEM_PAUSED` instead. This preserves T2-BO's deadlock recovery for the first N attempts but terminates gracefully if they all fail.
  - **Alternative (simpler):** When T1-CA fires with the circuit breaker active and `consecutiveEmptyBacklogInjectionsWithoutMerge >= limit`, require the new subgoal to have zero prior GIVEN_UP matches (stricter than T2-BO's limit of 2). If nothing satisfies that constraint, halt.
  - **Changes:** `PlannerAgent.java` — add counter for consecutive empty-backlog injections while circuit breaker active; halt after limit. `AutobuilderService.java` (or pass counter via context) — reset on `merged=true`. Add integration test.
  - **Depends on:** T2-BO (the bypass this ticket constrains). T1-CF (if T1-CF correctly promotes exhausted-but-fulfilled subgoals to FULFILLED, the churn scenario may not arise — T2-CB is belt-and-suspenders).
  - **Note (2026-04-13):** T1-CF removes the root cause (false GIVEN_UP on fulfilled subgoals). T2-CB is the safety net when subgoals are genuinely unachievable but the planner keeps re-injecting variants.

- [x] **T2-CC: Evolution produces empty diffs on already-implemented code — no pre-flight check against current output** (`EvolutionAgent`, `EvolutionProtocolContract`)
  - **Observed (2026-04-13 19:29 session):** `LoanController.getLoansByMember` was already correctly implemented. Evolution attempted to implement it again, found nothing to change, and produced empty/no-op diffs. `REPETITION_DETECTED` accumulated → `SUBGOAL_EXHAUSTED`. The system had no way to tell "code already satisfies the subgoal" from "evolution is stuck."
  - **Root cause:** `EvolutionAgent` always attempts code generation. It is never told "here is the current output for the files in scope — check if any work is needed before generating." The structural file summaries (T2-BX) and banked criteria (T2-BN) help focus evolution, but neither signal "this criterion is already satisfied in the current output."
  - **Fix — structural diff pre-check in EvolutionAgent:** Before invoking the LLM, have `EvolutionAgent` read the current output files for the subgoal's required paths (already available via `EvolutionProtocolContract` + `FileService`). Append a `--- CURRENT IMPLEMENTATION STATUS ---` block to the evolution prompt listing: for each criterion, whether the criterion's key term (method name, HTTP path, field name) is found verbatim in the current output. If ALL criteria appear satisfied by a surface scan, include a directive: "The current output may already satisfy all criteria. First verify. If satisfied, output `#NoDiff` and a brief explanation instead of generating new code."
  - **Parser change:** `EvolutionResponseParser` must handle `#NoDiff` as a valid response — map it to a zero-file `EvolutionOutcome` with a `noOp=true` flag. `EvolutionAgent` should then emit a signal or set a context flag indicating the current subgoal may already be satisfied, which `EvaluationAgent` or `AutobuilderService` can use to trigger a criteria-only evaluation.
  - **Changes:** `EvolutionProtocolContract.java` — add `CURRENT_IMPLEMENTATION_STATUS` block builder; include `#NoDiff` in allowed response format. `EvolutionResponseParser.java` — handle `#NoDiff`. `EvolutionAgent.java` — propagate `noOp=true` signal. `AutobuilderService` or `CycleExecutor` — when `noOp=true`, skip to a criteria-only evaluation (or immediately call `areCriteriaCumulativelySatisfied`).
  - **Depends on:** T1-CF (the FULFILLED promotion that acts on a confirmed-satisfied subgoal). T2-BX (already injects current file content into evolution context — T2-CC builds on top of this). T2-BN (banked criteria in evolution prompt).
  - **Note:** This is a steering improvement (T2) because the current behaviour — generating empty diffs — is correct evolution behaviour in a narrow sense; the system just needs a way to short-circuit it when work is provably done.

- [x] **T2-CD: Fingerprint-repeat FULFILLED inference for criteria-less subgoals** (`AutobuilderService`, `AdaptiveExecutionEngine`, `OrchestrationSnapshotService`)
  - **Origin:** T1-CO Part B (demoted 2026-04-14 — steering quality, not correctness).
  - **Problem:** When `already evaluated current evolution fingerprint` fires for N consecutive cycles, the system treats it as "stuck" (emits `REPLAN_REQUESTED`) rather than "done". For subgoals without structured acceptance criteria (e.g. early-era subgoals, or goals that were injected before T3-AY was live), there is no criteria-banked state to consult (T3-BL's path). But the eval score history in the orchestration snapshot can serve as a proxy: if the score is stable and above the acceptance threshold with no regression, the work is likely already done.
  - **Fix:** Add a `consecutiveSameFingerprintCycles` counter. When it reaches N=3, query `OrchestrationSnapshotService` for `evalScoreNow` and `evalScoreBefore` for the current subgoal. If `evalScoreNow >= 0.75` AND `evalScoreNow >= evalScoreBefore - 0.05` (no score regression since last merge), call `applyGoalProgress(FULFILLED)` directly. Guard: apply only when `criteriaList` is empty (if criteria exist, T3-BL handles it more accurately). If score query fails or is below threshold, continue emitting `REPLAN_REQUESTED` as today.
  - **Changes:** `AutobuilderService.java` or `AdaptiveExecutionEngine.java` — add counter + inference branch (~20 lines). `OrchestrationSnapshotService` — expose `getLastEvalScores(goalId)` if not already queryable.
  - **Depends on:** T3-BL (Part A — applies first when criteria exist; this is the fallback for criteria-less subgoals). T1-BG (cumulativeCriteriaStatus — used to determine whether to skip to T3-BL instead).
  - **Note:** T1-CN is the prerequisite for both T2-CD and T3-BL — without it, stale REPLAN keeps injecting new work that feeds the fingerprint-repeat scenario.

- [x] **T2-CE/T2-CF: Graph-backed per-subgoal score baseline — fix cross-subgoal contamination in `ScoreRegressionHeuristic` and `ProblemReductionHeuristic`** (`AgentGraphService`, `Neo4jAgentGraphService`, `EvaluationGraphFacade`, `CycleContextData`, `CycleContextCoordinator`, `ScoreRegressionHeuristic`, `ProblemReductionHeuristic`)
  - **Observed (2026-04-16 02:18 session):** POST /loans (`goal-ad78e693`): `ScoreRegressionHeuristic` fired "Notable score drop (Δ=-0.12, current=0.67, shift=0.02, files=3)" against `lastScore=0.7925` anchored to the big books merge. `ProblemReductionHeuristic` fired "ACCEPTED without justification" at HIGH confidence. Combined: valid first mutation for a new endpoint was blocked. POST /loans/{id}/return: same pattern. Output: uncompilable project.
  - **Root cause (both heuristics):** `lastScore` is a sequential scalar — the global project quality from the last merge, whichever subgoal produced it. When subgoal B starts, `lastScore` holds subgoal A's merge result. `ScoreRegressionHeuristic` compares B's partial-file score against A's complete-implementation baseline → false regression. `ProblemReductionHeuristic` sees no score improvement vs. A's baseline → unjustified-acceptance REJECTED. Both fire on structurally false preconditions because they share the wrong baseline.
  - **Design:** Replace the single `lastScore` scalar with a graph-backed per-subgoal baseline. The graph already stores `Action {type: 'evaluation', status: 'ACCEPTED'}` nodes linked to `Artifact {score}`. A Cypher query scoped to `targetsGoalId = subGoalId` returns the most-recent accepted evaluation score for that subgoal — the correct within-subgoal baseline. No reset logic needed; the graph gives the right answer for any subgoal at any time.
  - **Implementation (4 parts):**
    1. **`AgentGraphService` + `Neo4jAgentGraphService`:** Add `Optional<Double> findLatestAcceptedScoreForSubgoal(String subGoalId)` and `List<Double> findScoreHistoryForSubgoal(String subGoalId, int limit)`. Both traverse `(Action)-[:PRODUCES]->(Artifact)` filtered by `type='evaluation'`, `status='ACCEPTED'`, `targetsGoalId=$subGoalId`, ordered by `a.timestamp DESC`.
    2. **`EvaluationGraphFacade`:** Expose both methods by delegation.
    3. **`CycleContextData`:** Add `Double lastAcceptedSubgoalScore` and `List<Double> subgoalScoreHistory`. Pre-fetched each cycle — no carry-forward required. `lastScore` stays unchanged for backward compatibility (EvaluationInterpreter logging).
    4. **`CycleContextCoordinator`:** Inject `EvaluationGraphFacade`; pre-fetch both fields at cycle start. Null when no accepted evaluation exists for the active subgoal.
  - **Heuristic changes:**
    - `ScoreRegressionHeuristic`: replace `input.lastScore()` with `input.lastAcceptedSubgoalScore()` as the baseline. Null (first mutation) → abstain (existing null-guard already handles this).
    - `ProblemReductionHeuristic`: (a) replace `input.lastScore()` with `input.lastAcceptedSubgoalScore()` in `scoreImproved` check; (b) add first-shot leniency: `if (input.lastAcceptedSubgoalScore() == null) return HeuristicOpinion.none(NAME)` before the rejection block — a first mutation has no baseline.
  - **Why graph over reset:** A `ContextLineageTracker` reset (`lastScore = null` on subgoal change) would fix the immediate problem but lose history for re-visited or replanned subgoals and requires synchronised clearing logic. The graph answer is always correct, cross-session-safe, and gives `subgoalScoreHistory` for trend analysis (meta-deliberation can see "0.70 → 0.68 → 0.65, declining").
  - **Observed impact (2026-04-16 02:18 session):** POST /loans and POST /loans/{id}/return would both have passed heuristic negotiation with these fixes. LoanController.java references (Loan, LoanService, BookService, MemberService) would have had their dependencies merged correctly.
  - **Verified (2026-04-16 03:47 session):** T2-CE/CF were active. `ScoreRegressionHeuristic` and `ProblemReductionHeuristic` do NOT appear in any heuristic opinion for the session — they correctly abstain when `lastAcceptedSubgoalScore` is null. Mutations for POST /loans and GET /members/{id} are ACCEPTED. However, the accepted mutations were never merged to output (see T1-CT and T1-CU). The heuristic fix is confirmed working; the remaining problems are in the merge pipeline.
  - **Depends on:** T2-AJ (counterpart — widens tolerance for multi-file mutations; T2-CE/CF remove the cross-subgoal contamination that T2-AJ thresholds cannot fix).

- [x] **T2-CG: ROLE_EVOLUTION signal grows unboundedly — drowns other signal priorities** (`CycleStartSignalSeeder`, `Signal`)
  - **Observed (2026-04-16 03:47 session):** `ROLE_EVOLUTION` signal grew from 1.80 (cycle 1) to 8.55 (cycle 9) over 9 cycles. `EVOLUTION_FINGERPRINT_CHANGED` grew to 5.90. `EVOLUTION_OCCURRED` grew to 5.80. Meanwhile `ROLE_EVALUATION` decayed to 0.08 (below the 0.10 threshold — blocked evaluation canExecute in one cycle). `ROLE_MERGE` remained at ~1.15–1.25 but was overwhelmed in relative priority.
  - **Root cause:** `CycleStartSignalSeeder` emits a fresh `ROLE_EVOLUTION` signal every cycle. `Signal.reinforce()` accumulates strength via addition: `1.0 + 1.0 = 2.0 → 3.0 → …`. The decay is subtracted each cycle, but the reinforcement outpaces the decay. With `decayPerCycle=0.25`, effective growth per cycle is `1.0 - 0.25 = 0.75`. Over 9 cycles: `1.8 + (8 × 0.75) = 7.8`, consistent with the observed 8.55 (slightly higher due to the initial `reinforce` setting 1.8 instead of 1.0).
  - **Effect:** Agent fitness ranking becomes dominated by evolution — other agents (merge, evaluation, planner) are relatively deprioritized. This is not immediately harmful (the meta-step system selects agents explicitly), but it distorts the signal-driven coordinator fallback and makes signal-based diagnostics unreliable.
  - **Fix:** Cap `Signal.reinforce()` at a maximum strength. Suggested: `this.strength = Math.min(this.strength + other.strength, 3.0)`. Alternatively, use a non-additive reinforcement: `this.strength = Math.max(this.strength, other.strength)` — takes the stronger of the two rather than accumulating. Apply the same cap to `EVOLUTION_OCCURRED` and `EVOLUTION_FINGERPRINT_CHANGED`.
  - **Changes:** `Signal.java` — add strength cap to `reinforce()` (~1 line). OR `CycleStartSignalSeeder.java` — don't reinforce if signal already above threshold.
  - **Depends on:** None (standalone quality fix).

- [x] **T2-CH: Replacement subgoals under GIVEN_UP parents trigger premature root auto-completion** (`GoalPlannerService`) *(formerly T1-CV)*
  - **Observed (2026-04-21 00:27 session):** Planner injected 2 ACTIVE replacement subgoals under GIVEN_UP `goal-77b73723` (GET /loans?memberId). Immediately after, `autoCompleteRootIfSatisfied` fired: 6/8 first-wave ratio = 0.75 > 0.5 → root marked COMPLETED. The 2 ACTIVE replacements were orphaned and never worked on. Root goal halted with GET /loans?memberId and GET /books/{id} unimplemented.
  - **Root cause:** `autoCompleteRootIfSatisfied` only checked whether direct children are terminal — it did NOT check whether any GIVEN_UP child had ACTIVE descendants (T1-CP replacement subgoals). Those descendants are invisible to the ratio calculation (not first-wave) but represent queued work.
  - **Fix (2026-04-21):** Added `hasActiveDescendantsOf(parentId)` helper + T2-CH guard inside `if (allTerminal)` (before the ratio check). If any GIVEN_UP first-wave child has ACTIVE descendants, auto-completion is deferred and the method returns early. Fires after all replacements have become terminal.
  - **Changes:** `GoalPlannerService.java` — `hasActiveDescendantsOf()` helper + 6-line guard in `autoCompleteRootIfSatisfied()`. Tests: 2 new tests in `GoalPlannerServiceTest`.

- [ ] **T2-CI: Merge-regression guard — reject mutations that revert established invariants** (`EvaluationHeuristic`, `CycleContextData`, post-merge snapshot)
  - **Observed (2026-04-22 00:50 session, run 20260422_2):** After 6 successful merges all using `jakarta.validation.*` imports (the project's established framework convention), a later cycle proposed a mutation that switched a controller back to `javax.validation.*`. The evaluator scored that cycle's narrow criteria in isolation, did not see the established convention, and accepted the mutation. The merge then propagated a regression that broke compilation of every previously-merged sibling controller. Each evaluation cycle is criterion-local; cross-cycle invariants are invisible.
  - **Root cause:** No mechanism captures "what has stabilised in the codebase across successful merges." Heuristics evaluate the current diff against per-subgoal criteria; they cannot reject "this diff reverts a convention that 6 prior accepted mutations established." The evaluator has no concept of repository-level invariants.
  - **Fix — invariant snapshot + REJECT heuristic:**
    1. After every accepted merge, sample a small set of cheap "invariants" from the merged file(s) and persist them to `CycleContextData` (or to the graph alongside merge records). Suggested invariants (all string-set membership, no AST):
       - **Import-prefix space:** the set of distinct top-level import prefixes seen across accepted files (e.g. `{jakarta.validation, jakarta.persistence, org.springframework}`).
       - **Base package:** the dominant `package` declaration prefix (e.g. `com.firestarter.bookloans`).
       - **Framework version markers:** presence of `@Valid`, `@Validated`, `@RestController`, `@SpringBootApplication` — the stereotype set.
       Sample only after the codebase has crossed a stability threshold (suggested: ≥ `INVARIANT_ACTIVATION_MERGES = 3` accepted merges) so we don't lock in early experimentation.
    2. Add `MergeInvariantHeuristic`: for each candidate diff, compute the same invariant set on the post-mutation file(s). If any locked-in invariant is contradicted (e.g. `jakarta.validation` was in the established prefix space, the diff replaces it with `javax.validation`), opinion=REJECTED, confidence=HIGH, rationale: "Mutation introduces import prefix `javax.validation` that conflicts with established convention `jakarta.validation` (locked in across N prior merges). Conventions established by accepted merges should not be reverted."
    3. Allow override via planner-stated intent (e.g. a subgoal whose description explicitly names "migrate from javax to jakarta" — match against subgoal text). If the diff is part of an explicit migration goal, abstain rather than reject.
  - **Changes:** New `MergeInvariantHeuristic.java` under `agent/evaluation/heuristic/`. Add invariant sampling hook in `MergeAgent` after successful merge — write to `CycleContextData.mergeInvariants` (new field) or to a `MergeInvariantsGraphFacade`. Register heuristic in `HeuristicNegotiator`. Unit tests: (a) `jakarta` established → diff using `javax` → REJECTED; (b) novel prefix not in invariant set → abstain; (c) subgoal description names migration → abstain.
  - **Depends on:** None (orthogonal to existing heuristics — slots in alongside `IsolatedStateHeuristic`, `ScoreRegressionHeuristic`, etc.).
  - **Why T2 and not T1:** This is a steering heuristic, not a state-machine bug. The evaluator's per-subgoal scoring is correct in isolation; what's missing is a cross-cycle invariant signal. T4-AN-1 (compile gate) is the harder backstop — T2-CI is the cheaper preventative check that fires before compile.
  - **Origin:** Run 20260422_2 javax-after-jakarta finding.

- [x] **T2-BO: GIVEN_UP retry limit + stagnation circuit-breaker empty-backlog bypass** (`PlannerAgent`, `GoalSimilarityService`, `GoalGraphService`, `Neo4jGoalGraphService`)
  - **Problem A — infinite re-injection of unachievable subgoals:** `GoalSimilarityService.isDuplicate()` only blocked re-injection of goals matching an ACTIVE or COMPLETED description. A subgoal that was repeatedly attempted and GIVEN_UP could be re-proposed by the planner indefinitely, burning cycles on a goal that has already proven unachievable.
  - **Problem B — stagnation circuit breaker seizes the loop when backlog is empty:** T1-AH's prune-only mode suppressed subgoal injection unconditionally. If `stagnationCircuitBreakerActive` was set while the backlog was empty (no `currentSubGoalId`), all agents were blocked on a missing subgoal and the loop could not recover without a restart.
  - **Fix A:** Add `GoalSimilarityService.countGivenUpMatches(candidate)` — counts GIVEN_UP goals with similarity ≥ threshold. `PlannerAgent` calls this in both `inject()` and `planInto()` loops: if count ≥ `GIVEN_UP_RETRY_LIMIT = 2`, the proposal is skipped and logged as unachievable. `Neo4jGoalGraphService.findDescriptionsByStatus(status, limit)` added to support the query. `findAllDescriptions` now excludes GIVEN_UP goals from the active-dedup check (they are handled separately).
  - **Fix B:** `PlannerAgent`: stagnation circuit breaker prune-only mode now only applies when `!backlogEmpty`. When the backlog is already empty, injection proceeds normally so the loop can recover.
  - **Changes:** `PlannerAgent.java` (retry limit constant + guard in inject/planInto loops + backlog-empty bypass), `GoalSimilarityService.java` (`countGivenUpMatches`), `GoalGraphService.java` (interface addition), `Neo4jGoalGraphService.java` (`findDescriptionsByStatus` + GIVEN_UP exclusion from `findAllDescriptions`).
  - **Observed:** 2026-04-10 (identified alongside T2-BL / T3-BK work).

---

## Tier 3 — Feature completion

- [x] **T3-AW: Planner-generated acceptance criteria per subgoal** (`PlannerProtocolContract`, `PlannerResponseParser`, `PlannerOutcome`, `PlannerAgent`, `GoalEntity`, `GoalPlannerService`, `Neo4jGoalGraphService`, `EvaluationProtocolContract`, `CycleContextData`)
  - **Motivation:** Local LLMs score mutations overconfidently (0.82+) without checking whether the implementation actually meets the subgoal's stated requirements. OpenAI does this implicitly; weaker models need an explicit checklist.
  - **Approach:** The planner generates 1–4 short "done when" criteria alongside each subgoal at planning time. Stored with the subgoal (via `GoalEntity.acceptanceCriteria`, persisted to Neo4j via the existing `metadata` map under key `"acceptanceCriteria"`). Hydrated into `CycleContextData.currentSubGoalCriteria` at context-refresh time and rendered as a `--- ACCEPTANCE CRITERIA ---` block in the evaluation prompt.
  - **Key changes:**
    - `PlannerProtocolContract`: add `ACCEPTANCE_CRITERIA` rule — optional `Criteria:` sub-block (1–4 `*`-prefixed lines) under each `#SubGoalAppend` entry.
    - `PlannerResponseParser`: parse `Criteria:` sub-blocks into `Map<String, List<String>> goalCriteria`; pass into `PlannerOutcome`.
    - `PlannerAgent.injectOutcomeGoals()` and `planInto()`: call `goalPlannerService.storeAcceptanceCriteria(id, criteria)` after each `addSubGoal` call.
    - `GoalPlannerService`: add `storeAcceptanceCriteria(goalId, criteria)` and `getAcceptanceCriteria(goalId)`.
    - `GoalEntity`: add `List<String> acceptanceCriteria` field.
    - `Neo4jGoalGraphService.mapRowToGoalEntity()`: populate `acceptanceCriteria` from metadata on load.
    - `CycleContextData`: add `List<String> currentSubGoalCriteria`; populated in `PlannerAgent.refreshGoalState()`.
    - `EvaluationProtocolContract.getUserPrompt()`: add `--- ACCEPTANCE CRITERIA ---` block.
  - **Relationship to T3-B:** T3-B's `expectedArtifacts` is the programmatic/heuristic counterpart (file existence assertions). Acceptance criteria are the LLM-verifiable counterpart (observable code properties). Both can coexist.
  - **Backward compatibility:** Goals without criteria get empty criteria; the evaluation block renders `(none defined)`.

- [x] **T3-AX: Full file content in evaluation prompt** (`EvaluationProtocolContract`)
  - **Motivation:** The evaluation prompt currently shows only changed file *names* (`diff.changedFiles().keySet()`). The evaluator has no access to the actual code and must reason from a diff summary alone.
  - **Fix:** Add a `--- CURRENT FILE CONTENT ---` section to `EvaluationProtocolContract.getUserPrompt()` that renders the actual content of each changed file from `context.projectDiff().changedFiles()`. Truncate per-file content at a configurable limit (default: 150 lines or 4000 chars, whichever is shorter). Add `evaluation.fileContentMaxLines` (default 150) to `application.yml`.
  - **Note:** Pairs naturally with T3-AW — the evaluator can check each acceptance criterion against the actual code rather than inferring from a diff summary.
  - **Future:** Consider method-level truncation instead of raw line-based truncation — extract each method's signature + body separately (using JavaParser) so the evaluator sees semantically meaningful units rather than an arbitrary top-N lines. Falls back to raw for non-Java files. Significantly more complex.

- [x] **T3-AY: Make planner acceptance criteria mandatory** (`PlannerProtocolContract`, `PlannerAgent`, `EvaluationProtocolContract`)
  - **Problem (observed 2026-04-07):** Evaluation prompt shows `ACCEPTANCE CRITERIA: (none defined)` — the evaluator operates without a checklist. T3-AW made `Criteria:` optional for backward compatibility; in practice the planner omits them routinely when not required.
  - **Fix:**
    1. `PlannerProtocolContract`: promote `Criteria:` from optional to required. Rule: "Every `#SubGoalAppend` entry must include a `Criteria:` sub-block with 1–4 `*`-prefixed done-when conditions. If no observable output criterion applies, write `* (subgoal is structural — no observable output criterion)`."
    2. `EvaluationProtocolContract`: replace the silent `(none defined)` render with a visible warning line: `"⚠ No acceptance criteria were defined for this subgoal. Evaluate against subgoal description only."` Makes the gap observable in logs.
    3. `PlannerAgent`: log a WARNING whenever a goal is injected with zero criteria (metric-trackable, no logic change).
  - **Relationship to T3-AW:** T3-AW built the infrastructure; T3-AY enforces its use.
  - **Observed (2026-04-08 session):** Rule took effect immediately — all 5 injected subgoals had criteria populated; all evaluation prompts showed real criteria. However the evaluator still produces vague per-criterion prose rather than structured assessments, and the fulfillment heuristic is blind to unmet criteria → see T3-AZ and T3-BA.

- [x] **T3-AZ: Per-criterion pass/fail assessment in evaluation response** (`EvaluationProtocolContract`)
  - **Problem (observed 2026-04-08):** The evaluator reads the `--- ACCEPTANCE CRITERIA ---` block but produces a single vague message sentence rather than checking each criterion individually. One run flagged an unmet criterion in prose ("no changes were made to include previously created books") but gave 0.75/ACCEPTED/PROGRESSING with no machine-readable signal. You cannot tell from the log which criteria passed and which failed.
  - **Fix:** Add a `CRITERIA_ASSESSMENT` rule to `EvaluationProtocolContract` requiring a `CriteriaCheck:` block in the response when criteria are present. Format: `[N] ✓ <criterion>` or `[N] ✗ <criterion> — <why not met>`. Insert `CriteriaCheck:` into the response format template (between `Score:` and `Message:`). Update `isValidResponse` to not require it (criteria may be absent — don't gate on it).
  - **Depends on:** T3-AY (criteria must be populated for this to fire).
  - **Enables:** T3-BA (the heuristic veto needs a machine-readable unmet signal).
  - **Done (2026-04-08):** `CRITERIA_ASSESSMENT` rule added; `CriteriaCheck:` block in response format; `EvaluationResponseParser.parseCriteriaAssessments()` extracts `[N] ✓/✗` lines; `ParsedEvaluation` wrapper carries them from adapter to agent; `CycleContextData.criteriaAssessments` field added.
  - **Live session finding (2026-04-08):** LLM partial compliance observed — in one evaluation with 2 criteria, the LLM assessed only criterion 1 (`[1] ✓`) and silently dropped criterion 2 despite acknowledging its failure in prose. The `CriteriaCheck:` format rule alone is insufficient; the LLM treats omission as acceptable. → T3-BC addresses this with a missing-criterion safety fallback.

- [x] **T3-BA: `SubGoalFulfillmentHeuristic` vetoes FULFILLED when a criterion is unmet** (`SubGoalFulfillmentHeuristic`, `CycleExecutor` or `CycleContextData`)
  - **Problem (observed 2026-04-08):** A subgoal (`GET /books`) was closed as `FULFILLED` with `rationale=score-based closure + meaningful diff` even though the evaluator had explicitly flagged criterion 2 ("Includes all previously created books") as unmet. The fulfillment path (`SubGoalFulfillmentHeuristic` → `CycleExecutor`) never consults `currentSubGoalCriteria` or the evaluator's criterion assessments.
  - **Fix:** Parse `CriteriaCheck:` lines from the evaluator response (stored on `CycleContextData` as `criteriaAssessments`). In `SubGoalFulfillmentHeuristic.decide()`: if any assessment is `✗`, return PROGRESSING (suppress FULFILLED). If all assessments are `✓` and score threshold is met, allow FULFILLED.
  - **Depends on:** T3-AZ (needs machine-readable `[N] ✗` markers).
  - **Done (2026-04-08):** `hasUnmetCriterion()` check inserted before both the `interpreter=FULFILLED` and score-based closure paths; 4 new tests added.
  - **Live session finding (2026-04-08):** Veto did not fire in either evaluation cycle. Two compounding causes: (1) LLM dropped criterion 2 entirely from `CriteriaCheck:` (no `[2] ✗` emitted) so `criteriaAssessments` contained no `✗` lines — the veto had nothing to act on; (2) criteria for subgoal 1 (POST /books) never reached the evaluator at all due to a hydration timing issue (see T3-BC and the investigation note below). The GET /books subgoal was incorrectly marked `GOAL_FULFILLED` despite a broken implementation (`BookResource.books` disconnected from `BookController.books`, always returning empty).
  - **Pattern note — chain-dependency fragility:** T3-AW → T3-AY → T3-AZ → T3-BA is a chain where each link assumes the previous delivered correctly. Both live breaks were silent: no log line indicated that criteria were absent from the prompt or that the `CriteriaCheck:` block was incomplete. Future chain-dependent features should include a per-link observability log (e.g. `"Criteria delivered to evaluator: N"`, `"CriteriaCheck lines parsed: M of N"`) so breaks are visible immediately rather than requiring post-hoc log analysis.

- [x] **T3-BC: Missing-criterion safety fallback — treat unassessed criteria as unmet** (`SubGoalFulfillmentHeuristic`, `EvaluationResponseParser` or `EvaluationAgent`)
  - **Problem (observed 2026-04-08):** LLM partial compliance with T3-AZ: the evaluator produced `[1] ✓` for 2 criteria and silently omitted `[2]`. Because no `[N] ✗` line was emitted, T3-BA's veto never fired and the subgoal closed FULFILLED despite criterion 2 being materially unmet.
  - **Fix:** In `SubGoalFulfillmentHeuristic.decide()` (or at parse time in `EvaluationAgent`), compare `currentSubGoalCriteria.size()` against the number of indexed lines in `criteriaAssessments`. If assessed count < defined count, treat the gap as unmet — i.e. suppress FULFILLED as if a `✗` line were present. This makes T3-BA robust regardless of LLM compliance level.
  - **Implementation note:** The index set of assessed criteria can be extracted from `criteriaAssessments` by parsing the `[N]` prefix. Any index N in `[1..criteriaCount]` absent from that set is an implicit fail. Alternatively, a simpler check: if `criteriaAssessments.size() < currentSubGoalCriteria.size()`, veto.
  - **Depends on:** T3-AZ (source of assessments), T3-BA (veto path to hook into).
  - **Done (2026-04-08):** `hasUnmetCriterion()` count-comparison added; `EvaluationAgent` logs WARN on mismatch.
  - **Additional fix (2026-04-08):** `EvaluationProtocolContract.buildCriteriaCheckFormat()` replaces the static `[1]`/`[2]` placeholder in the response format template with a dynamically generated fill-in-the-blank list pre-populated with each actual criterion text. This anchors the model to emit exactly N lines rather than producing from scratch. `CycleExecutor.applyCompletion()` now logs the `SubGoalFulfillmentHeuristic` rationale when fulfillment is denied (previously only "Completion signal not met" was visible). Criteria storage logs elevated from DEBUG to INFO in `PlannerAgent` and `AnalysisAgent`.
  - **Live session verification (2026-04-08 23:36 run):** Veto confirmed firing correctly — score ≥ 0.75 cycles with 1/4 criteria assessed produced `promoted=false` as expected. Dynamic format template not yet validated end-to-end (applied after that session).

- [x] **T3-BD: Investigate criteria hydration timing in bootstrap cycle** (`CycleContextCoordinator` / `PlannerAgent` / `GoalPlannerService`)
  - **Problem (observed 2026-04-08):** Criteria defined by the planner for subgoal 1 (POST /books) were present in the planner's `#SubGoalAppend` output but absent from the evaluation prompt (`"No acceptance criteria were defined for this subgoal."`). The planner and evaluator ran in the same cycle (bootstrap), suggesting that criteria are written to `GoalEntity` after the evaluation context is already built for that cycle.
  - **Investigation:** Check the execution order in `CycleContextCoordinator.refreshGoalState()` (or equivalent) relative to `PlannerAgent.injectOutcomeGoals()`. If criteria are written by the planner mid-cycle and the context snapshot was taken before the planner ran, the evaluator will always see stale (empty) criteria for any subgoal created in the same cycle it is evaluated.
  - **Fix:** `PlannerAgent.refreshGoalState()` reads `acceptanceCriteria` from current subgoal metadata and calls `withCurrentSubGoalCriteria()` after every planner run — so the evaluator in the same cycle picks up freshly injected criteria rather than the stale empty list from cycle start.
  - **Done (2026-04-08):** Fix confirmed working in live session (23:36 run) — evaluator received 4 criteria for the POST /books subgoal injected in the same bootstrap cycle.
  - **Depends on:** T3-AW (criteria infrastructure), T3-AZ/T3-BA (the feature being protected).

- [x] **T3-BE: Analysis-injected subgoals have no acceptance criteria** (`AnalysisAgent`, `AnalysisResponseParser`, `AnalysisProtocolContract`, `AnalysisOutcome`)
  - **Problem (observed 2026-04-08):** `AnalysisAgent.autoInjectExecutableSubGoals()` called `goalPlannerService.addSubGoal()` but never `setAcceptanceCriteria()`. Subgoals injected by analysis (e.g. "Implement validation for the title field…") always had 0 criteria, causing `EvaluationAgent` to warn "hydration may have failed" every cycle for those goals — even though criteria were never attached in the first place.
  - **Fix (2026-04-08):** Added optional `Criteria:` / `* criterion` sub-blocks to `AnalysisProtocolContract` response format (with a MUST requirement). Added `goalCriteria: Map<String, List<String>>` to `AnalysisOutcome`. Updated `AnalysisResponseParser` to parse criteria sub-blocks under `ProposedExecutableSubGoals`. `AnalysisAgent` now calls `setAcceptanceCriteria()` after `addSubGoal()` and logs WARN when criteria are absent.
  - **Done (2026-04-08):** 4 new parser tests, full compile green.

- [x] **T3-BF: `EvaluationResponseParser` misses criterion on inline `CriteriaCheck:` header line** (`EvaluationResponseParser`)
  - **Problem (observed 2026-04-08):** LLM wrote `CriteriaCheck: [1] ✓ criterion text` (header + first criterion on same line). `CRITERIA_LINE_PATTERN` anchors to `^`, so after trimming the line starts with `CriteriaCheck:`, not `[`, and the match fails. System logged "0 of 2 expected (complete LLM omission)" — but 1 criterion was actually present.
  - **Fix (2026-04-08):** In `parseCriteriaAssessments()`, added `flatMap` step: lines starting with `CriteriaCheck:` are replaced by their suffix (stripped of the header prefix) before pattern filtering. Empty suffix emits nothing; non-empty suffix is evaluated normally. 3 new tests added.
  - **Done (2026-04-08):** All tests green.

- [x] **T3-BG: Enrich planner prompt with subgoal health and project state** (`PlannerProtocolContract`)
  - **Problem (observed 2026-04-08 23:52 run):** When a subgoal is stagnating and REPLAN fires, the planner is repeatedly invoked but generates proposals that are (a) redundant with work already attempted and (b) blocked by the backlog guard. The planner's prompt contains only goal text, evaluation feedback summary, and interpretation context — it has no visibility into how many cycles the current subgoal has been stagnating, which acceptance criteria passed vs. failed, what files currently exist in the project, or persistent heuristic warnings. As a result, each planner invocation starts essentially blind to the actual problem.
  - **Observed failure pattern:** In the 23:52 run, a single broad subgoal ("Establish a Spring Boot application...") stagnated for 4 cycles. The planner was invoked 3 times during REPLAN — each time generating reasonable but context-free proposals that were immediately blocked by "skipping injection — 1 active subgoals already in backlog." All three proposals were discarded. The subgoal eventually exhausted and the session terminated.
  - **Mechanical note:** `PlannerAgent.processRemovals()` runs BEFORE the backlog guard. So if the planner proposes `#SubGoalRemove <stuck-goal-id>` + `#SubGoalAppend <focused replacements>`, the removal empties the backlog before the injection check — the decompose pattern already works mechanically. The planner just needs to know it should use it and when.
  - **Fix:** Add three new sections to `PlannerProtocolContract.getUserPrompt()` (all data already available on `CycleContextData`):
    1. `--- CURRENT SUBGOAL STATUS ---`: stagnating cycle count (`cumulativeStagnatingCycles`), last score + delta, criteria with pass/fail from last evaluation (`currentSubGoalCriteria` × `criteriaAssessments`), persistent heuristic warnings (`persistentHeuristicWarnings`). Shown only when a current subgoal exists.
    2. Decompose coaching note (shown only when `cumulativeStagnatingCycles >= 3`): "⚠ This subgoal has been stagnating for N cycles. Consider decomposing: use `#SubGoalRemove <id>` to remove it, then `#SubGoalAppend` with narrower, file-level replacements. Removal is processed before injection — the backlog will be empty in time for the new subgoals."
    3. `--- PROJECT STRUCTURE ---`: `ctx.projectSummary()` truncated to ~30 lines, so the planner can anchor proposed subgoals to concrete existing file paths.
  - **No logic change outside the prompt.** All data is on `CycleContextData`; the coaching note makes the existing `#SubGoalRemove + #SubGoalAppend` decompose pattern discoverable by the LLM.
  - **Expected outcome:** When a subgoal stagnates past the threshold, the planner proposes `#SubGoalRemove` + focused `#SubGoalAppend` rather than proposals that get blocked. The stuck subgoal is given up voluntarily and replaced with a more granular plan before exhaustion forces termination.
  - **Depends on:** T3-AY (criteria populated), T3-BC (unmet criteria surfaced on context).

- [x] **T3-BH: Plan rationale and sibling coordination context** (`PlannerProtocolContract`, `PlannerResponseParser`, `PlannerOutcome`, `GoalEntity.metadata`, `EvolutionProtocolContract`, `EvaluationProtocolContract`)
  - **Problem (observed 2026-04-08 runs):** When the planner proposes N subgoals as a batch, they share no common rationale. Each subgoal is a standalone description with standalone criteria. Evolution working on subgoal K doesn't know subgoals K+1…N exist — it may implement their scope. Evaluation working on subgoal K penalizes for missing everything that K+1…N are supposed to cover, creating excessive score pressure and forcing each subgoal to over-implement. Concretely: the first plan injected a single broad subgoal covering the entire API, because there was no mechanism to signal that a batch of smaller, ordered, coordinated subgoals is preferred.
  - **Root cause:** The planner response format has no way to express coordination intent across a batch of proposed subgoals. The agents that execute each subgoal have no visibility into the plan's overall structure.
  - **Fix:**
    1. **Planner emits `PlanRationale:`** — a 1–2 sentence field (required when ≥ 2 subgoals are proposed) describing what the batch collectively achieves and how the subgoals relate to each other. Example: "Subgoals 1–3 implement Books, Members, and Loans as separate self-contained resource groups in that order. Each subgoal should be evaluated only for its own resource group — do not penalise for absent sibling groups."
    2. **`PlannerResponseParser`** parses `PlanRationale:` → new field on `PlannerOutcome`.
    3. **`PlannerAgent.injectOutcomeGoals()` / `planInto()`** stores the rationale in parent `GoalEntity.metadata["planRationale"]` when subgoals are injected.
    4. **Evolution prompt** gains `--- PLAN CONTEXT ---` block: plan rationale (from parent metadata) + list of completed siblings (same `parentId`, `COMPLETED` status) + list of upcoming siblings (same `parentId`, `ACTIVE` status, excluding current). Instruction: "Focus ONLY on what this subgoal requires. Do not implement functionality covered by upcoming siblings."
    5. **Evaluation prompt** gains the same `--- PLAN CONTEXT ---` block with an explicit scoring note: "Do not penalise for absent functionality that will be handled by upcoming sibling subgoals (listed above)."
  - **Sibling data source:** `goalPlannerService.getAllGoals()` filtered by `parentId == current.parentId`. Split into completed vs. remaining (excluding current). Already available via `GoalPlannerService`.
  - **PlanRationale in prompt contract rule:** Add a `PLAN_RATIONALE` rule to `PlannerProtocolContract` (alongside the existing `Criteria:` requirement): "When proposing 2 or more subgoals, include `PlanRationale:` explaining how they collectively advance the root goal and what each covers. This rationale will be shown to the agents executing each subgoal."
  - **Depends on:** T3-BG (produces better-scoped subgoal batches for the rationale to coordinate).
  - **Note:** Sibling filtering by `parentId` requires that subgoals produced by the planner in the same batch share a common parent. This is already the case — `injectOutcomeGoals()` passes `context.currentSubGoalId()` as `parentGoalId` for all subgoals in a batch.

- [x] **T3-BB: Persist per-evaluation `CriterionResult` nodes to Neo4j** (`EvaluationActionRegistrar`, `EvaluationGraphFacade`)
  - **Problem:** `criteriaAssessments` from T3-AZ are carried on `CycleContextData` for the current cycle only; they are not persisted and are invisible to the orchestration snapshot, retrospective analysis, and any heuristic wanting to trend per-criterion failure rate across cycles.
  - **Fix:** In `EvaluationActionRegistrar.registerEvaluation()`, write each parsed `[N] ✓/✗` line as a `CriterionResult` node linked from the evaluation action node:
    ```
    (:EvaluationAction)-[:HAS_CRITERION]->(:CriterionResult {
      index: int, text: String, passed: Boolean,
      reason: String,          // ✗ failure reason if present
      cycleId: String, subgoalId: String
    })
    ```
    This schema enables per-criterion queries: "how many times did criterion 2 fail for this subgoal?" and "which criteria have never passed?".
  - **Distinct from T3-BI** (which persists *cumulative* pass status — the rolled-up view per subgoal). T3-BB is the raw event log; T3-BI is the derived summary.
  - **Depends on:** T3-AZ (source of the assessments). T3-BI and T3-BJ can read from this event log.

- [x] **T3-BI: Persist cumulative criteria status to `GoalEntity` / Neo4j** (`GoalPlannerService`, `Neo4jGoalGraphService`, `CycleContextCoordinator`, `GoalEntity`)
  - **Problem:** T1-BG's `cumulativeCriteriaStatus` lives entirely in `CycleContextData` and is lost on restart. If the system restarts mid-subgoal, all accumulated criterion passes are forgotten and the subgoal must re-earn them from scratch — cycling criteria that had already been satisfied.
  - **Fix:** Mirror the `mergeCount` pattern (T2-BI hotfix): store cumulative pass status in `GoalEntity.metadata` under key `"criteriaPassStatus"` as a JSON-serialised `Map<Integer, Boolean>`. Three integration points:
    1. **Write:** `GoalPlannerService.markCriterionPassed(goalId, criterionIndex)` — loads current metadata map, sets `criteriaPassStatus[index] = true`, saves to graph. Called from `EvaluationAgent` after each `cumulativeCriteriaStatus` update (same location as T1-BG's in-memory update).
    2. **Read on startup/sync:** `GoalPlannerService` (or `CycleContextCoordinator`) loads `criteriaPassStatus` from the active subgoal's metadata on context preparation and populates `cumulativeCriteriaStatus` on the context. This restores the accumulated state transparently after a restart.
    3. **Reset:** When a subgoal transitions to FULFILLED or GIVEN_UP, the metadata entry is no longer needed (the goal is closed). No explicit cleanup required.
  - **Graph note:** Metadata is already stored as a JSON blob on the `Goal` node; no schema change is needed. The same pattern is used for `mergeCount`, `cumulativeStagnatingCycles`, `acceptanceCriteria`.
  - **Depends on:** T1-BG (defines `cumulativeCriteriaStatus`), T2-BI (establishes the metadata persistence pattern).
  - **Enables:** T3-BJ (snapshot service reads from here rather than querying T3-BB's event log for the rolled-up view).

- [x] **T3-BJ: Surface criteria progress in orchestration snapshot** (`OrchestrationSnapshotService`, `OrchestrationSnapshotFormatter`)
  - **Problem:** The meta-deliberation agent and interpretation agent currently have no visibility into *which* criteria have been satisfied and which have persistently failed for the current subgoal. They can observe that the subgoal is PROGRESSING, but they cannot distinguish "3 of 4 criteria satisfied — one stubborn criterion remaining" from "0 of 4 criteria ever passed — subgoal is fundamentally stuck." These require completely different steering responses (targeted evolution vs. replanning), but the snapshot gives the agents no data to reason from.
  - **Fix:** In `OrchestrationSnapshotService`, for the active subgoal:
    1. Load `cumulativeCriteriaStatus` from `GoalEntity.metadata["criteriaPassStatus"]` (T3-BI) — the rolled-up per-criterion pass/fail across all cycles.
    2. Query the N most recent `CriterionResult` nodes for this subgoal (T3-BB) to determine *how long* each unmet criterion has been failing (streak count).
    3. Build a `criteriaProgressBlock`: per-criterion summary formatted as `[✓/✗/–] <text> (passed N cycles ago | failed M consecutive cycles | never assessed)`.
  - **Surface in `OrchestrationSnapshotFormatter`** under `--- CRITERIA PROGRESS ---`, between the evaluation summary and the signal list. Visible to the meta-deliberation agent, interpretation agent, and the planner's `CURRENT SUBGOAL STATUS` block.
  - **Expected steering impact:** Meta-agent proposes targeted evolution when 3/4 criteria pass (one remaining). Interpretation agent emits `REPLAN_REQUESTED` when a criterion has failed 5+ consecutive cycles with no improvement. Planner's coaching note becomes criterion-specific ("criterion 2 has never passed — the implementation of X is missing").
  - **Depends on:** T3-BB (event log for streak counts), T3-BI (cumulative status for rolled-up view). Can be partially implemented with T3-BI alone (no streak counts) and upgraded to full streaks when T3-BB is ready.

- [x] **T3-BK: Partial graduation — force-fulfill at high criteria pass rate** (`SubGoalFulfillmentHeuristic`, `GoalPlannerService`, `PlannerAgent`)
  - **Problem:** A single stubborn criterion can hold an otherwise-complete subgoal hostage indefinitely. If 3 of 4 criteria have passed across multiple cycles but criterion 4 has failed N consecutive times (likely due to a structural gap in evolution rather than a solvable incremental problem), the subgoal will exhaust and be GIVEN_UP — discarding the real progress made on the other three.
  - **Fix:** In `SubGoalFulfillmentHeuristic.decide()`, after the normal fulfillment path fails (unmet criterion veto), check graduation: if `passedCount / totalCount >= GRADUATION_RATIO` (default 0.80, configurable via `autobuilder.criteria.graduation-ratio`) AND the remaining unmet criteria have each failed for at least `GRADUATION_MIN_FAIL_STREAK` cycles (default 3, read from `cumulativeCriteriaStatus` + `lastCriteriaPassCount` history), return `FulfillmentDecision.yes(score, "graduation: M/N criteria satisfied, remaining injected as micro-subgoal")`.
  - **On graduation:** `CycleExecutor.applyCompletion()` detects the "graduation" rationale and calls `PlannerAgent` (or directly `GoalPlannerService`) to inject one micro-subgoal per unmet criterion, each with a single-criterion `Criteria:` block. The parent subgoal is then marked FULFILLED.
  - **Effect:** Progress is banked; the system moves forward rather than exhausting. The micro-subgoal is a focused, smaller target that evolution can hit in fewer cycles.
  - **Configurable:** `autobuilder.criteria.graduation-ratio: 0.80`, `autobuilder.criteria.graduation-min-fail-streak: 3`.
  - **Depends on:** T1-BG (`cumulativeCriteriaStatus`), T3-BI (streak history), T2-BL (exhaustion suppression should fire before graduation is needed — graduation is the last resort).

- [x] **T3-BP: Capture goal/subgoal completion and give-up rationale**

  - **Problem:** When a subgoal transitions to COMPLETED or GIVEN_UP, no reason is recorded. The graph shows the final state but not why — was it fulfilled by criteria? Abandoned after N stagnating cycles? Given up because the planner replaced it? This information is available at transition time but currently discarded.
  - **Fix:**
    1. Add a resolution string field to GoalEntity (Neo4j) and the JSON goal stack. Populated at transition time via GoalPlannerService.
    2. In promoteCurrentSubGoal / wherever COMPLETED / GIVEN_UP is written, pass a short rationale string. Call sites to cover:                                                                       
       - COMPLETED via SubGoalFulfillmentHeuristic → e.g. "all criteria passed, score 0.88"                         
       - GIVEN_UP via stagnation exhaustion → e.g. "given up after N stagnating cycles"
       - GIVEN_UP via planner replacement → e.g. "superseded by replanning"
       - GIVEN_UP via GOAL_EXHAUSTED signal
    3. Surface resolution in OrchestrationSnapshotFormatter under the goal/subgoal header so the LLM meta-agent can see it.
  - **Why:** Without this, post-hoc analysis (and the deliberation agent) can't distinguish a subgoal that cleanly passed from one that was silently abandoned. It also aids debugging when goals are given up prematurely.
  - **Depends on:** None — purely additive metadata.

- [>] **T3-B: Activate `GoalEntity.expectedArtifacts`**
  - **Superseded by the T3-AW/T3-AZ/T3-BA acceptance-criteria chain (2026-04-11 assessment).**
  - Original plan: Define schema (file path + existence check or content assertion), populate from planner LLM output, add artifact check to `SubGoalFulfillmentHeuristic.decide()`.
  - **Why superseded:** The `acceptanceCriteria` system (T3-AW through T3-BC) provides richer, LLM-verifiable "done-when" conditions that subsume what `expectedArtifacts` would have offered. File-existence assertions are a strict subset of criteria (a criterion can assert `* File BookController.java exists with a POST /books endpoint`). Maintaining two parallel fulfillment signals would add complexity with no clear benefit. If file-existence assertions are needed in the future, they are better expressed as a specific criterion type within the existing criteria framework rather than a separate `expectedArtifacts` field.

- [ ] **T3-G: Complexity-adaptive regression tolerance — Phase 2 (lines changed composite + calibration)** (`ScoreRegressionHeuristic`, `Neo4jScoreHistoryService`, `CycleContextData`)
  - **Status (2026-04-21):** Split into two sub-tickets. Reassessment found the core infrastructure exists — `Neo4jScoreHistoryService` persists per-file scores, global scores, and per-goal evaluation/acceptance events linked via `Cycle` nodes; `DefaultMutationHeuristicAnalyzer` already computes `linesAdded`/`linesDeleted` per file from `diffReport`. What's missing is the linkage (diff size isn't persisted alongside the cycle's score) and the calibration algorithm (current T2-AJ constants are hand-picked).
  - **Core motivation (unchanged):** File count is a crude proxy. A 1-line change to 4 files is not complex; a 500-line rewrite of 1 file is. Lines changed correlates more directly with LLM evaluation variance.
  - **Design options for composite:** (A) replace file-count breakpoints with line-count breakpoints; (B) composite score combining both; (C) weighted product. Decide based on calibration outcome.

  - [ ] **T3-G1: Persist diff size per cycle** (`Neo4jScoreHistoryService`, `CycleScoreRecorder`, `CycleContextData`)
    - **Shovel-ready.** Extend `Neo4jScoreHistoryService` with `recordMutationSize(cycleId, linesAdded, linesDeleted, fileCount)` — stores the three numbers as properties on the existing `Cycle` node (or on a linked `MutationSize` node if preferred for cleanliness). Call from `CycleScoreRecorder` alongside the existing `recordGoalEvaluation` call when a mutation was produced.
    - Expose `getMutationSize(cycleId)` and `getRecentMutationSizes(goalId, limit)` query methods for Phase 2b's calibration job and any other downstream analysis.
    - Add `lastAcceptedMutationLinesChanged` (and a delta counterpart) to `CycleContextData`, populated on merge, mirroring the existing `lastAcceptedMutationFileCount` pattern from T2-AJ. Available to `ScoreRegressionHeuristic` at the point the next-cycle heuristic runs.
    - **No calibration logic yet.** Purpose is only to start accumulating the corpus needed for T3-G2.
    - **Depends on:** None substantive — uses existing `Neo4jScoreHistoryService` pattern and `DiffReport` line counts.

  - [ ] **T3-G2: Calibrate regression tolerance from observed score history** (`ScoreRegressionHeuristic`, new `RegressionToleranceCalibrator`)
    - Requires sufficient corpus from T3-G1 (estimate: 100+ accepted mutations across varied diff sizes to regress meaningfully). If the corpus is too small, hand-tuned constants stay in place.
    - Query `(score, linesAdded, linesDeleted, fileCount)` tuples from Neo4j. Fit a simple model for expected score variance as a function of diff complexity (start with ordinary least squares on |Δscore| vs. lines changed; consider robust regression if outliers dominate). Replace T2-AJ's `SHIFT_PER_FILE` / `SCALE_REDUCTION_PER_FILE` with coefficients derived from the fit.
    - Validate by back-testing: would the calibrated thresholds have correctly accepted/rejected historical mutations? If back-test accuracy is worse than T2-AJ's hand-tuned version, keep the hand-tuned values and log the divergence for manual review.
    - **Depends on:** T3-G1 (data). Probably also T4-J (tuning parameter store) so calibrated values can be persisted and swapped live without code changes.


- [x] **T3-J: Implement InterpretationAgent LLM call** (`DefaultInterpretationAgent`)
  - Replaced hardcoded summary template with real LLM call using assembled substrate
  - Output writes `REPLAN_REQUESTED` / `LOW_CONFIDENCE` signals when patterns detected
  - Makes interpretation an active steering participant, not just a log
  - **⚠ Behavior flaw observed (2026-03-10):** All 5 interpretation calls in a clean 5-cycle session returned the same paragraph verbatim with zero signals emitted. The implementation is technically complete (LLM call wired) but the output was boilerplate — fixed by T2-AB.

- [x] **T3-A: Implement `#SubGoalRemove`** (`PlannerResponseParser`, `GoalPlannerService`)
  - Parse marker and call `markGoalGivenUp()` / new `removeSubGoal()` on referenced goal
  - Lets planner prune dead-end subgoals; unblocks completion detection

- [x] **T3-C: Implement divergent evolution prompt mode** (`EvolutionLLMAdapter`, `EvolutionProtocolContract`)
  - Add `divergent: boolean` param that changes system prompt to explore a fundamentally different approach
  - Hook into stagnation recovery (T1-A) to trigger after N failed cycles

- [x] **T3-E: Wire `MetaOverrideLearningStore`** (`AdaptiveExecutionEngine.publishMetaOverride()`)
  - Construct `MetaSkipPatternKey(mode, targetRole, reason)` and call `learningStore.record(key)`
  - `MetaDeliberationAgent.buildMetaLearningsBlock()` already consumed `learningStore.top(10)` and passed it to LLM
  - Only missing piece was the `record()` call in `publishMetaOverride()`
  - [>] Store is in-memory — resets on restart. For cross-session durability, pre-populate from Neo4j on startup by querying recent skipped meta-step actions. Revisit after T2-G.

- [x] **T3-F: Upgrade `GoalKindClassifier` to LLM classification**
  - `classify(GoalEntity)` overload: checks `analysisClassifiedKind` metadata (highest trust) → cached `goalKind` metadata → keyword match → LLM fallback (when cog=false; result cached)
  - Analysis agent outputs `GoalKind:` in its response; persisted as `analysisClassifiedKind` in goal metadata
  - `ExecutionEligibilityExplainer` uses entity-aware `classify(sg)` + `REPLAN_REQUESTED` bypass for analysis gate
  - [>] LLM classification not yet applied to proposed subgoal strings in `autoInjectExecutableSubGoals` — keyword-only there allows "Verify by executing a test case" to be auto-injected (T3-K addresses this upstream)
  - **⚠ Gate inconsistency → extracted as T1-BB** (HYBRID classification not unlocking Analysis agent)

- [x] **T3-I: Deprecate `#SubGoalUpdate`** (`PlannerResponseParser`, `PlannerProtocolContract`)
  - Chose Option C: removed `SUBGOAL_UPDATE` rule from contract; response format clarified as "to replace: use Remove + Append"
  - Parser: `#SubGoalUpdate` still accepted as graceful fallback (treated as Append) but logs a deprecation warning

- [x] **T3-K: System capability manifest + prompt injection** (`SystemCapabilityManifest`, `PlannerProtocolContract`, `AnalysisProtocolContract`)
  - Created `SystemCapabilityManifest` Spring bean declaring CAN (write/read files, call LLMs) and CANNOT (compile, run code, observe runtime output) as first-class data
  - Injected a rendered capability block into planner and analysis system prompts so LLM agents stop proposing goals the system cannot satisfy
  - **Extendable hook for T4-F**: when capability registry + handshake arrives, `SystemCapabilityManifest` gains dynamic entries from external providers
  - **⚠ Not firing (overnight run 2026-03-11):** Zero log output from `SystemCapabilityManifest` across 319K lines. System created 95 GIVEN_UP goals including unsatisfiable refinement goals — exactly the scenario the manifest should prevent. Needs investigation: check wiring in `PlannerLLMAdapter` and `AnalysisLLMAdapter`.

- [x] **T3-BL: Criteria-banked FULFILLED early exit** (`CycleExecutor`, `AdaptiveExecutionEngine`, `GoalPlannerService`)
  - **Origin:** T1-CO Part A (demoted 2026-04-14 — scaffold exists via T1-BG, needs wiring).
  - **Problem:** When all acceptance criteria have been individually confirmed (banked as `true` in `cumulativeCriteriaStatus`) across prior evaluation cycles, the subgoal is functionally done — but the loop continues trying to generate new mutations because there is no early-exit path that consults the banked state before entering the diff/evaluation flow.
  - **Fix:** At the start of each cycle in `CycleExecutor` (or `AdaptiveExecutionEngine`, before meta steps execute), call `goalPlannerService.allCriteriaDefinedAndBanked(currentSubGoalId)`. If all criteria are banked as `true` AND `mergeCount > 0` (at least one real merge occurred — prevents a brand-new subgoal from self-fulfilling), call `applyGoalProgress(FULFILLED)` and skip the rest of the cycle. Guard: criteria list must be non-empty (criteria-less subgoals are handled by T2-CD).
  - **Changes:** `CycleExecutor.java` or `AdaptiveExecutionEngine.java` — add early-exit check (~10 lines). `GoalPlannerService.java` — expose `allCriteriaDefinedAndBanked(String goalId)`: criteria list non-empty AND all indices in `criteriaPassStatus` are `true`.
  - **Tests:** (a) all criteria banked + mergeCount > 0 → FULFILLED, rest of cycle skipped; (b) all criteria banked + mergeCount == 0 → no early exit; (c) criteria list empty → no early exit (falls through to T2-CD path).
  - **Depends on:** T1-BG (cumulativeCriteriaStatus persisted in GoalEntity). T1-CF (the FULFILLED promotion mechanism this reuses). T1-CN (prerequisite — stale REPLAN must be cleared first or it masks the fulfilled state).
  - **Enables:** T2-CC's `#NoDiff` evolution path can shortcut to this check when evolution self-reports no change needed.

- [>] **T3-BM: Stateless LLM criteria verification on repeated no-diff cycles — SUPERSEDED by T3-BR** (`StatelessCriteriaChecker`, `CycleExecutor`, `AutobuilderService`)
  - **Deferred 2026-04-21:** Reframed rather than implemented as originally designed. The ticket proposed a NEW `StatelessCriteriaChecker` component — a parallel LLM call reading current output + criteria — whose sole purpose is to bypass the evaluator's `already evaluated current evolution fingerprint` guard when criteria exist but aren't all banked. On re-analysis this is a redundancy-layer smell: the evaluator *already* scores against criteria (T3-AZ/T3-BC); the root cause is that the fingerprint-repeat guard is too coarse, not that a second verifier is needed.
  - **Root cause (correctly identified):** The fingerprint guard decides "skip the evaluator — same fingerprint, nothing new." But fingerprint identity is not a sufficient cache key — the *completeness* of the evaluator's prior verdict also depends on (a) the set of currently-defined criteria (which can grow via T3-BE analysis injection mid-subgoal) and (b) which of those criteria have been banked so far. If criteria are defined but not all banked on the same fingerprint, the evaluator's prior verdict is incomplete, and re-running against the unbanked criteria is warranted.
  - **Supersession path:** See **T3-BR** — criteria-aware fingerprint-repeat guard. This removes the need for a parallel verifier and keeps the evaluator as the single authority on criteria satisfaction.
  - **Preserved for reference (original design):** Add `StatelessCriteriaChecker` that, on fingerprint-repeat with unbanked criteria, runs a minimal LLM call to confirm/deny each criterion and promotes FULFILLED if all pass. The concern that killed this approach: it introduces a second LLM-call path with its own prompt/parser for work the evaluator already knows how to do, and leaves the evaluator's gating bug unfixed for all other consumers.

- [ ] **T3-BR: Criteria-aware fingerprint-repeat guard — let the evaluator re-run when unbanked criteria exist** (`EvaluationAgent`, `AdaptiveExecutionEngine` or wherever the fingerprint-repeat check lives) *(supersedes T3-BM, 2026-04-21)*
  - **Problem:** The fingerprint-repeat guard (`already evaluated current evolution fingerprint`) currently skips evaluation whenever the evolution output hash matches a prior cycle's — treating fingerprint identity as a sufficient cache key. It isn't. The evaluator's prior verdict can be *incomplete* relative to the criteria that are currently in force: criteria may have been added via T3-BE analysis injection after the first evaluation, or the first evaluation may have failed to assess some criteria (LLM partial compliance). In both cases the cumulative-status map still has unbanked criteria for this subgoal, but the guard blocks the evaluator from re-checking them. The subgoal stalls and eventually exhausts even though the needed verification was cheap.
  - **Fix:** Replace the boolean fingerprint-identity guard with a conjunction:
    - skip evaluation if `fingerprintRepeats && allDefinedCriteriaBanked`
    - otherwise run evaluation (the fingerprint may be the same but the open questions are not).
    `allDefinedCriteriaBanked` is already computable via `goalPlannerService.allCriteriaDefinedAndBanked(currentSubGoalId)` (introduced by T3-BL). Subgoals with no criteria defined fall through to T2-CD's existing criteria-less handling — unchanged.
  - **Effect:** Once the evaluator re-runs and banks the remaining criteria, T3-BL's early-exit fires naturally on the next cycle and promotes FULFILLED. No new LLM-call component, no new prompt/parser, no parallel verifier. The evaluator remains the single authority on criteria satisfaction.
  - **Edge cases:**
    - **Evaluator keeps returning partial results (LLM non-determinism):** Could produce repeated re-evaluations on every cycle with no progress. Guard with a per-subgoal `evaluatorRetriesOnSameFingerprint` counter; cap at N (e.g. 3) and then fall through to T2-CD or emit `REPLAN_REQUESTED`.
    - **Evaluator says "everything fails" repeatedly:** Handled by the existing stagnation/exhaustion machinery — criteria that legitimately cannot be satisfied should time out through normal paths, not be force-banked.
  - **Test plan:** (a) fingerprint repeats + all criteria banked → skip evaluation (unchanged); (b) fingerprint repeats + some criteria unbanked → evaluator runs; (c) evaluator runs and banks remaining criteria → next cycle T3-BL fires FULFILLED; (d) evaluator runs 3× on same fingerprint without progress → fall through with REPLAN_REQUESTED.
  - **Depends on:** T3-BL (`allCriteriaDefinedAndBanked` helper). T1-BG (`cumulativeCriteriaStatus`). T3-BE (analysis-injected criteria are the primary consumer — they are the main reason criteria can appear mid-subgoal).

- [ ] **T3-BO: Audit T3-BH (plan rationale / sibling context) — empirical effectiveness of the evolution-side PLAN CONTEXT block** (`EvolutionProtocolContract`)
  - **Scope narrowed (2026-04-21):** Originally bundled empirical audit + mechanical cleanup. Cleanup items split into **T3-BQ** because the audit outcome gates what cleanup is needed — if the evolution-side block is removed entirely, dedup and reload-simplification collapse to "delete, don't refactor."
  - **Motivation (2026-04-21 log analysis):** T3-BH is shipped but its real-world impact is ambiguous. The evaluation-side PLAN CONTEXT (do not penalise for upcoming siblings) appears correct — the evaluator in cycle-1776735068286 scored the return-endpoint mutation accurately without penalising for absent validation. The evolution-side instruction is restrictive: "Focus ONLY on what this subgoal requires. Do not implement functionality covered by upcoming siblings." Its purpose is to prevent scope bleed — evolution implementing work reserved for later subgoals. But in the 2026-04-21 session a 14-file merge happened because `LoanService` already `@Autowired BookService` and `MemberService` (which didn't exist); the LLM had to create them for the code to compile. It ignored the restrictive instruction — which is the *correct* behaviour here, because the cross-file work was structurally necessary.
  - **Core tension:** The evolution-side instruction is only beneficial in the subset of cases where (a) subgoals are genuinely self-contained (no inter-subgoal file dependencies) AND (b) the LLM would otherwise over-implement without being told to stop. If either condition fails — dependencies exist, or the LLM stays in scope naturally — the instruction is either ignored (neutral) or obeyed at the cost of blocking required cross-cutting work (harmful). There is no middle ground where it "slows" convergence; it either helps, is inert, or blocks.
  - **Hypotheses to test:**
    1. **Self-contained case (no inter-subgoal deps):** Does the instruction reduce over-implementation observably (fewer files touched per mutation, less work belonging to other subgoals)? Or does the LLM already stay in scope without it?
    2. **Dependency-carrying case:** Does the instruction ever successfully suppress a cross-file creation that was actually needed? If yes, the instruction is net-harmful in this regime — it leaves the code uncompilable until a later subgoal fills the gap.
    3. **Rationale sentence alone vs. full sibling lists:** Is the "Plan rationale: …" line the useful part (carries intent), and the sibling lists the redundant/harmful part (triggers the restrictive reflex)?
  - **Method:** Run paired sessions on the same root goal with and without the evolution-side `--- PLAN CONTEXT ---` block (two branches differ only in whether `planContextBlock()` is appended in `EvolutionProtocolContract`). Compare: cycles-to-first-merge, count of mutations per subgoal, whether cross-resource files were created during "wrong" subgoals, and frequency of uncompilable interim output states. Use **T3-BP**'s resolution rationale to measure give-up reasons per subgoal — a subgoal given up as "stagnation" when its dependencies were missing is evidence for the harmful-obedience case.
  - **Decision outputs:**
    - **Keep as-is** (only if hypothesis 1 confirms benefit AND hypothesis 2 finds no harm).
    - **Remove evolution-side block entirely** (keep evaluation-side only).
    - **Keep rationale sentence, drop sibling lists** (if hypothesis 3 isolates the useful part).
    - **Weaken wording** (e.g. "prefer to focus on this subgoal" instead of "do not implement"), making it a suggestion rather than a restriction.
    The decision determines T3-BQ's scope.
  - **Depends on:** T3-BH (precondition — shipped). **T3-BP** (resolution rationale enriches the audit data — ship first).

- [ ] **T3-BQ: T3-BH cleanup — shared helper, reload simplification, rationale scope** (`EvolutionProtocolContract`, `EvaluationProtocolContract`, `PlannerAgent`, `CycleContextCoordinator`) *(split from T3-BO on 2026-04-21)*
  - **Gated by T3-BO outcome.** Scope depends on whether the evolution-side `planContextBlock()` survives the audit:
    - **If evolution-side block is removed:** no dedup needed (only one copy remains in `EvaluationProtocolContract`); `PlannerAgent.refreshGoalState()` no longer needs to reload `planRationale` for bootstrap evolution — only for the evaluator, which runs later in the cycle after `CycleContextCoordinator` has already populated the fields via the graph. Reload drops from three sites to zero.
    - **If evolution-side block is kept:** do the full cleanup below.
  - **Cleanup items (assuming block is kept):**
    1. **Extract shared helper.** `planContextBlock()` is copy-pasted verbatim between `EvaluationProtocolContract:527` and `EvolutionProtocolContract:577` with only the final instruction sentence differing. Extract to `ProtocolContextBlocks.planContextBlock(CycleContextData, PlanContextRole)` where `PlanContextRole` is `EVOLUTION` or `EVALUATION` and controls only the trailing instruction.
    2. **Simplify `PlannerAgent` same-cycle reload.** Currently `refreshGoalState()` reloads `planRationale` (line 497) by re-querying the parent goal from the graph. This is needed because bootstrap-cycle evolution runs after the planner in the same cycle and `CycleContextCoordinator` only populates `planRationale` at cycle start. Simplify by making `setPlanRationale()` directly mutate the in-memory `CycleContextData` the planner holds (or by having `injectOutcomeGoals()` return the rationale so the caller wires it into the context). Goal: eliminate the three-places reload pattern.
    3. **Rationale scope for single-subgoal replans.** Current behaviour: `setPlanRationale` is only called when `injected >= 2`. The rationale on the parent goal therefore reflects the FIRST planning wave that injected ≥2 subgoals, and subsequent stagnation-replan single-injection waves inherit it silently (good: first-wave intent persists; bad: a later narrower replan has no way to override with its own rationale). Decide:
       - **Option A:** Leave as-is. First-wave rationale is the durable intent; single-subgoal replans are recovery, not a new plan.
       - **Option B:** Let single injections supply a rationale too (parser accepts `PlanRationale:` for `injected >= 1`). Downside: noisy overwrites.
       - **Option C:** Only set rationale on the first-wave (lowest `planningRunId`) batch; ignore later overwrites entirely. Makes intent immutable for the subgoal's lifetime.
       - Recommendation pending audit: Option C aligns with the "first-wave is special" pattern already established by T1-CQ.
  - **Depends on:** **T3-BO** (audit outcome determines scope). Do not start before T3-BO concludes.

- [x] **T3-BN: Goal-tree coverage retrospective before GOAL_EXHAUSTED** (`GoalPlannerService`, `CoverageRetrospectiveService`) *(formerly T1-CY)*
  - **Observed (2026-04-20 23:33 session):** Planner removed 4 unstarted siblings during stagnation-recovery. GOAL_EXHAUSTED fired, system halted with 3/8 endpoints.
  - **Observed (2026-04-21 00:27 session):** 6/8 ratio → COMPLETED path taken, T3-BN (GIVEN_UP-only) never fired. GET /books/{id} and GET /loans?memberId unimplemented.
  - **Fix v1 (2026-04-21 initial):** `CoverageRetrospectiveService` — LLM comparing root description vs terminal subgoals. Triggered in GIVEN_UP branch only.
  - **Fix v2 (2026-04-21, extended):** Extended to COMPLETED path (ratio > threshold but GIVEN_UP siblings exist). Retrospective logic extracted to shared `runCoverageRetrospective(root, context)` helper. Coverage-retro goals injected with `planningRunId=0` (temporarily overrides `currentPlanningRunId`) so they join the first-wave minimum, become ACTIVE → `allTerminal=false` → auto-completion defers naturally, and count in the ratio when done.
  - **Changes:** `CoverageRetrospectiveService.java` (new). `GoalPlannerService.java` — `MAX_COVERAGE_RETRO_COUNT`, `@Setter @Autowired(required=false)` field, T2-CH active-descendants guard, `runCoverageRetrospective()` helper, `hasActiveDescendantsOf()` helper, both paths in `autoCompleteRootIfSatisfied()`. Tests: `CoverageRetrospectiveServiceTest` (8), 9 new tests in `GoalPlannerServiceTest`.

---

## Tier 4 — New systems

- [ ] **T4-J: Tuning parameter store — configurable system thresholds with DB persistence** (new `TuningParameterStore`, `TuningParameter` entity, `autobuilder.tuning.*` config namespace)
  - **Motivation:** Score thresholds (merge floor, subgoal closure, plateau override, etc.) are currently scattered as constants across heuristics and protocol contracts. Hardcoding them into prompt strings (T2-BB) trades one form of rigidity for another. Exposing them as named, DB-persisted parameters lets configuration change without code, and opens the door for the system to autonomously calibrate them over time.
  - **Read path (initial scope):**
    - `TuningParameterStore` service: `get(String name)` returns a `double`. Checks DB first; falls back to `application.yml` default under `autobuilder.tuning.<name>` (e.g. `autobuilder.tuning.score.merge-acceptance-floor: 0.75`).
    - `TuningParameter` Neo4j node (or JPA entity): fields `name`, `value`, `source` (`DEFAULT` / `CONFIGURED` / `SYSTEM_UPDATED`), `updatedAt`.
    - Initial named parameters: `score.merge-acceptance-floor` (0.75), `score.subgoal-closure` (0.75), `score.plateau-override-floor` (0.65).
    - Heuristics and protocol contracts inject `TuningParameterStore` and call `get(name)` instead of using inline constants. `DeterministicScoreHeuristic`, `SubGoalFulfillmentHeuristic`, `EvaluationInterpreter` are the first consumers.
  - **Write path (stub only — no autonomous updates yet):**
    - `TuningParameterStore.set(String name, double value, TuningParameterSource source)` persists to DB with timestamp. No callers wired in this ticket.
    - Future: an analysis or interpretation agent proposes a `TuningParameterUpdate`; it is validated and committed via `set()` with source `SYSTEM_UPDATED`.
  - **Downstream:** T2-BB (interpretation prompt reads live values), T4-E (dynamic heuristics may propose threshold updates via write path), T4-H (level-relative scoring thresholds become parameters).

- [ ] **T4-AI: Meta steps and deliberation — unify dual orchestration** (`AdaptiveExecutionEngine`, `DeliberationService`, `DeliberationExecutionGate`, `MetaDeliberationAgent`)
  - **Problem statement:** `AdaptiveExecutionEngine.runCycle()` currently runs a two-layer orchestration: (1) LLM meta-agent proposes 1–5 ordered steps executed sequentially, then (2) signal-driven fitness pass runs remaining roles. The two layers have no explicit hand-off protocol, fragile serialization, and unclear authority boundaries. This dual-orchestrator design is the prerequisite seam that T4-F (dynamic sub-agents) will plug into, so it must be solid.
  - **Core design decision:** Unify meta steps and fitness pass into a single orchestration model where the meta-agent emits **intentions and signal adjustments** rather than a separate execution plan. The fitness pass becomes the sole executor; the meta-agent steers it by modifying signal weights and proposing constraints.
  - **Sub-tasks:**
    - **T4-AI-1: Replace pipe-delimited meta step serialization with typed model.**
      Replace `String` serialization (`index|Need|TargetRole|Request|StopGuard`) in `DeliberationService:130` and parsing in `AdaptiveExecutionEngine:459–492` with a proper `MetaStep` record passed through `DeliberationResult.meta()` as a typed list. Eliminates lossy round-trip (stopIf/stopCycleIf merged into one field) and fragile `String.contains()` stop-condition parsing (`AdaptiveExecutionEngine:427–457`).
    - **T4-AI-2: Meta-agent emits signal adjustments instead of execution steps.**
      Redesign `MetaDeliberationPlan` to output `proposedSignals` (signal name + target strength + rationale) and `constraints` (role suppression, ordering hints) rather than explicit step sequences. The fitness function in `AdaptiveExecutionEngine.fitnessFor()` (line 124) consumes these as temporary signal overlays for the current cycle. This collapses the two-phase model into one pass.
    - **T4-AI-3: Formalize cycle termination conditions beyond `context.merged()`.**
      `shouldEndCycle()` (`AdaptiveExecutionEngine:621–623`) currently only checks `ctx.merged()`. Add explicit termination for: goal exhaustion signal above threshold, SYSTEM_PAUSED signal, meta-proposed halt. Each termination path should be a named `CycleTerminationReason` for observability.
    - **T4-AI-4: Re-evaluate eligibility dynamically during execution.**
      Currently `MetaDeliberationAgent.buildEligibilityBlock()` (line 48) is called once at cycle start. If prior agent execution changes context (e.g., evolution produces a mutation making evaluation eligible), later agents should see updated eligibility. Add a lightweight `canExecute()` re-check before each agent in the fitness pass.
    - **T4-AI-5: Retire `DeliberationExecutionGate` mode-to-role mapping in favor of signal constraints.**
      The static mode→allowed-roles mapping (`DeliberationExecutionGate:12–30`) and deadlock injection (`AdaptiveExecutionEngine:546–587`) become unnecessary once the meta-agent steers via signals. Replace with: meta-agent can emit a `SUPPRESS_ROLE` signal (strength 1.0, decay 1.0 = single-cycle) for roles it wants to block.
  - **Invariants to preserve:**
    - Each role executes at most once per cycle (`executedRoles` guard)
    - Cycle terminates immediately on merge (existing behavior)
    - Stagnation circuit breaker continues to suppress REPLAN_REQUESTED from interpretation
  - **Migration path:** T4-AI-1 and T4-AI-4 can land independently as hardening fixes. T4-AI-2, T4-AI-3, and T4-AI-5 form the core unification and should land together.
  - **Downstream:** T4-F (dynamic sub-agents) depends on this — dynamic agents plug into the unified signal+intention model.

- [ ] **T4-A: Implement alignment layer** (`alignment/` package — currently 0% built)
  - `AlignmentVector`: populate `centralityScore` (Neo4j degree), `editDistance` (DiffService), `deletedReferencedNodes`, `layerSpanDelta` (JavaStructureParser AST)
  - `TrueAlignmentAnalyzer`: apply configurable thresholds, reject structurally unsafe proposals
  - Wire as pre-gate in `EvaluationAgent` before LLM call — fail fast without burning tokens

- [ ] **T4-C: Signal trait/pattern spawning** (`AdaptiveExecutionEngine.attenuateSignal()`)
  - Re-enable `Signal.mutate()` for non-`ROLE_*` signals only
  - Spawn derived pattern signals (e.g., `EVOLUTION_REGRESSIONS`, `PLANNER_THRASH`) on repeated failure
  - Feed into heuristics and meta-deliberation for richer steering

- [ ] **T4-E: Dynamic heuristics — LLM-proposed evaluation rules** (`HeuristicNegotiator`, new `DynamicHeuristicStore`)
  - Currently all `EvaluationHeuristic` implementations are hardcoded; the LLM has no way to introduce new scoring rules
  - After a merge or evaluation cycle, allow an LLM agent (e.g. `InterpretationAgent` or a dedicated `HeuristicProposalAgent`) to propose new heuristic rules as structured config (condition + weight + score adjustment)
  - `DynamicHeuristicStore` persists proposed rules (in-memory first, Neo4j later); `HeuristicNegotiator` loads and applies them alongside static ones
  - Guardrails needed: rule validation, confidence threshold before activation, TTL/expiry to prevent drift accumulation
  - Longer-term: opens the door to self-modifying evaluation criteria — the loop can tune what "good" means over time

- [ ] **T4-F: Capability registry, handshake protocol, and dynamic sub-agents** (new `CapabilityProvider` SPI, `SystemCapabilityRegistry`, `AgentRegistry`, protocol contracts)
  - **Depends on T4-AI** (meta as signal+intention emitter).
  - **Part 1 — Capability registry** (extends T3-K's `SystemCapabilityManifest`):
    - `Capability` descriptor: `id`, human/LLM-readable `description`, trigger `verbs`, `available()` runtime check, optional `constraints`
    - `CapabilityProvider` SPI: Spring beans declare what they offer. Built-in: `FileSystemCapabilityProvider`, `LLMCapabilityProvider`. Optional: `SubprocessCapabilityProvider` (T4-E), `DatabaseCapabilityProvider`, etc.
    - External service handshake: providers expose a `/capabilities` endpoint; `SystemCapabilityRegistry` queries at startup and on a health interval. Unavailable services degrade gracefully.
    - `GoalKindClassifier` resolves required capabilities per goal; flags unsatisfiable goals at classification time.
    - Rendered block distinguishes CAN / CANNOT / UNAVAILABLE-NOW for conditional planner proposals.
  - **Part 2 — Dynamic sub-agents** (new `AgentRegistry`, `AgentSpec`, transient `ExecutionAgent` lifecycle):
    - Meta LLM may include an `agentSpec` field in a step when no static agent matches `targetRole`.
    - `AgentSpec`: `{ prompt: string, role: string, capabilities: string[] }` — minimal descriptor the meta LLM can emit.
    - `AgentRegistry` instantiates a transient `ExecutionAgent` from spec (wraps `LLMAgentProtocolInvoker` with the spec prompt as the system prompt). Agents are scoped to the current cycle and discarded after.
    - Engine checks static agent list first; if no match and a MetaIntention exists for the role, consult `AgentRegistry`.
    - Capability check: before instantiating, verify all `agentSpec.capabilities` are present in `SystemCapabilityRegistry`. If any are unavailable, skip the step and log.

- [ ] **T4-I: Cross-cycle behavioral pattern heuristics** (new `EvolutionPatternHeuristic`, `CyclePatternTracker`, `CycleContextData`)
  - **Motivation:** T2-AX catches byte-identical structural no-ops. But the heuristics are blind to softer patterns that span multiple cycles: the LLM producing *similar-but-not-identical* mutations that accumulate no net progress, or a subgoal where scores are consistently ≥ threshold but the goal marker never advances. These patterns are not detectable in any single cycle.
  - **Patterns to detect:**
    1. **File churn without progress:** the same file(s) keep appearing in mutations across N cycles, but the structural diff against the pre-subgoal baseline grows by ≤ M lines per cycle on average. Evidence of rewriting rather than building.
    2. **Score-progress divergence:** `goalProgress == PROGRESSING` but no merge has occurred in the last K cycles. The LLM believes it is making progress but nothing sticks.
    3. **Subgoal stagnation by file coverage:** every mutation in the last N cycles touches the same subset of files, leaving other referenced files untouched. Evidence of fixation.
  - **Design:** A new `CyclePatternTracker` component accumulates per-file change history (file path → list of (cycleId, linesAdded, linesDeleted, merged)) on `CycleContextData`, reset on subgoal change (like `criticalHeuristicStreaks`). `EvolutionPatternHeuristic` reads this at evaluation time to score the mutation against the patterns above. Persist to graph via a lightweight artifact (reuse `EvaluationActionRegistrar` pattern) so patterns survive restarts.
  - **Scope:** New component + new heuristic + `CycleContextData` field + graph artifact. No changes to existing heuristics.

- [ ] **T4-AJ: Semantic goal adoption — re-parent orphaned children to compatible active goals** (`GoalPlannerService`, `GoalGraphService`, new `GoalAdoptionService`)
  - **Motivation:** When a parent goal is given up (T2-AL or direct exhaustion), T1-CA cascades GIVEN_UP to all its ACTIVE children. This is correct for correctness, but discards children that produced real output (`mergeCount > 0`) and whose objective may still be relevant to another active branch of the plan.
  - **Core idea:** Before cascading GIVEN_UP to a child, check whether any other ACTIVE goal in the tree is a semantically compatible adoptive parent. If found, re-parent the child there so it can continue contributing under a new context. Only cascade GIVEN_UP when no suitable adoptive parent exists.
  - **Adoption eligibility:**
    - Candidate adoptive parents: ACTIVE goals that are not descendants of the dying parent (to avoid cycles) and not the root goal.
    - Children eligible for adoption: ACTIVE children with `mergeCount > 0` (they have demonstrated output worth preserving). Children with zero merges fall through to cascade immediately.
    - Compatibility: LLM-scored semantic similarity between the child's description and the candidate parent's description + existing children. A lightweight prompt ("does this sub-task logically belong under this goal?") with a yes/no + confidence response.
  - **Implementation shape:**
    - `GoalAdoptionService.findAdoptiveParent(GoalEntity orphan, List<GoalEntity> activeCandidates)` — returns `Optional<GoalEntity>`. Makes one LLM call per orphan against a ranked shortlist of candidates (ranked by description similarity heuristic to bound token cost).
    - `GoalPlannerService.markGoalGivenUp()` — after marking the parent, before cascading to a child with `mergeCount > 0`, call `GoalAdoptionService`. If adopted: update `child.parentId`, re-wire `goalMap` + graph edge, log adoption. If not adopted: cascade normally.
    - `GoalGraphService` — new `reparent(String childId, String newParentId)` method to update the Neo4j relationship.
  - **Fallback invariant:** if `GoalAdoptionService` is unavailable or returns empty, cascade GIVEN_UP as before. Adoption is best-effort — correctness must not depend on it.
  - **Observability:** log adoptions with old/new parent; persist an adoption event node in the graph for analysis.
  - **Prerequisites:** T1-CA (cascade as fallback), T4-AI (LLM call budget awareness).
  - **Note:** Does not affect `getRemainingGoals()` — adopted children remain ACTIVE under their new parent, which is a valid ACTIVE node, so no filter changes needed.

- [x] **T4-AK: Goal graph explorer — interactive browsing of subgoals, criteria, and cross-goal synergies** (new REST/UI layer over `GoalGraphService`, `OrchestrationSnapshotService`)
  - **Shipped (2026-04-22):** read-only MVP. New `GoalExplorerController` under `/autobuilder/goals` exposing `GET /tree`, `GET /{id}`, `GET /{id}/criteria`, `GET /{id}/synergies`, and `GET /file-overlay`. `GoalExplorerService` composes `GoalPlannerService` (tree, criteria metadata, fail streaks), `OrchestrationSnapshotService.fetchCriteriaProgress` (now public — per-criterion history from Neo4j), and `GoalSimilarityService.similarity` (synergy scoring above 0.5 threshold, ranked, capped at 50). Cross-goal file overlay is an inverted index over `mergedFiles` metadata, filtered to files touched by 2+ subgoals. Frontend at `src/main/resources/static/goals.html` — ~200 lines of plain JS+CSS, no build pipeline. Write actions deferred to T4-AM per the original note.
  - **Motivation (2026-04-15):** The goal tree and per-criterion pass history are rich structured data sitting in Neo4j — but they're currently only surfaced through logs and the meta-deliberation snapshot. A developer browsing an active run has no way to see which subgoals are stuck, which criteria are partially banked, or whether two sibling subgoals are doing redundant work. An explorer would make the loop observable and steerable at the micro level.
  - **Core capabilities:**
    - **Goal tree view:** render the full `GOAL → SUBGOAL → …` tree with status badges (ACTIVE, COMPLETED, GIVEN_UP, FULFILLED) and per-node metadata (`mergeCount`, `cumulativeStagnatingCycles`, `planningRunId`).
    - **Criteria panel:** for a selected subgoal, show each acceptance criterion with its cumulative pass/fail status, consecutive-fail streak, and the raw evaluator text that last assessed it.
    - **Synergy detection:** given a selected criterion (or a set of unmet criteria), query semantically similar criteria across sibling and cousin subgoals. Highlight where the same implementation artefact (file, method, HTTP path) could satisfy criteria from multiple subgoals simultaneously — surfaces merge-once opportunities the planner misses.
    - **Cross-goal diff overlay:** show which output files are touched by each subgoal's accepted mutations, so the user can spot redundant or conflicting evolutions side by side.
  - **Implementation shape:**
    - New REST endpoints under `/autobuilder/goals` (tree, node detail, criteria status, synergy candidates).
    - `GoalGraphService` — add `findCriteriaByKeyTerms(List<String> terms)` for cross-goal synergy lookup (Cypher full-text or similarity query).
    - `OrchestrationSnapshotService` — expose per-subgoal criteria history (already partially available via `fetchRecurringCriticalHeuristics`).
    - Frontend: lightweight read-only UI (plain HTML + JS or minimal React) served from the existing Spring Boot app; no build pipeline required for the MVP.
  - **Prerequisites:** T1-BG (cumulativeCriteriaStatus in GoalEntity), T3-BB (per-criterion Neo4j nodes — makes criteria history queryable), T3-BJ (criteria progress in snapshot).
  - **Note:** MVP can be read-only. A second pass could add write actions (manually mark a criterion as met, pin a subgoal as priority, inject a custom subgoal description) to close the human-in-the-loop gap identified in T2-D.

- [x] **T4-AM: Real-time 3D goal graph browser** (new SSE endpoint, Three.js / `3d-force-graph` frontend, write-action REST layer)
  - **Landed 2026-04-22:** `GoalStateEventPublisher` Spring component holds a `CopyOnWriteArrayList<SseEmitter>`. On `register()` a fresh subscriber immediately receives a `hello` event with the current flat goal list + signal map so the UI can paint without round-tripping `/tree`. `CycleExecutor.runCycle()` calls `publishCycleEnd(ctx)` just before returning (best-effort — wrapped in a try/catch so streaming never blocks the control loop). The publisher is injected via `@Autowired(required=false)` + `@Setter` so existing unit tests (e.g. `CycleExecutorGoalLoopTest`) that construct `CycleExecutor` directly continue to work unchanged.
  - **Stream endpoint:** `GET /autobuilder/goals/stream` returns `text/event-stream`. Three event names: `hello` (on connect), `cycle-end` (after every cycle), `tree-changed` (after any write action). Payload is `GoalStateEvent` — `{ kind, emittedAt, cycleName, cycleResultState, merged, currentGoalId, currentSubGoalId, signals: Map<String,Double>, goals: List<GoalStateSnapshotDto> }`. Emitter timeout is `Long.MAX_VALUE` (client-controlled lifetime); dead emitters are pruned on the next broadcast.
  - **Write actions:** added to `GoalExplorerController` on top of the T4-AK read-only endpoints:
    - `POST /{goalId}/criteria/{idx}/mark-met` — flips cumulative pass status + clears fail streak
    - `POST /{goalId}/pin` — `activateSubGoal(...)` + emits `REPLAN_REQUESTED` via new `AutobuilderService.injectSignal(...)` helper
    - `POST /{parentId}/inject-subgoal` — body `{description, setAsCurrent, criteria, role}`; seeds new subgoal and optional criteria
    - `POST /{goalId}/force-given-up` — `applyGoalProgress(..., GIVEN_UP, resolution)`
    - `PATCH /{goalId}` — in-place description edit (persists via `saveGoalMetadata`)
    - `DELETE /{goalId}` — alias for `force-given-up` with default resolution
    Every action broadcasts a `TREE_CHANGED` event so the live graph reconciles without waiting for the next cycle.
  - **Frontend:** single 600-line `src/main/resources/static/graph.html`. Loads `3d-force-graph` 1.73.4 from jsDelivr CDN (no build pipeline). Three.js bundled in that lib. Auto-orbit camera, click-to-select with camera fly-to, directional particles flowing along the edge into the current subgoal, per-status node colours, current-subgoal node painted white and enlarged. Right-side panel has three tabs (Node / Signals / Inject) — Node tab renders the `GET /{id}` detail payload with an editable `<textarea>` for the description, a criteria table with per-row "mark met" buttons, Pin/Force-GIVEN_UP/Inject-child action row, and a Synergies loader (`GET /{id}/synergies`). Signals tab renders the live signal map as labelled bars normalised to the current max strength. Inject tab posts to `/{parentId}/inject-subgoal`. Toast-style notifications on every action. Connection pill in header flips `live` / `connecting` / `disconnected` based on `EventSource` readyState.
  - **Signal overlay:** toggle in the header maps the current signal map to a per-node size boost — ROLE_* and evaluation-related signals boost the current-subgoal node, whereas top-level signals (REPLAN_REQUESTED, GOAL_EXHAUSTED, SYSTEM_PAUSED, POTENTIALLY_COMPLETE) boost the root GOAL node. Effect is continuous; re-computed every cycle event.
  - **Tests:** `GoalStateEventPublisherTest` (5 tests) covers register / hello / publish / dead-subscriber pruning / null-context path; `GoalExplorerServiceTest` grew by 14 write-action tests covering every endpoint plus branch coverage for validation failures and the TREE_CHANGED broadcast hook. Full suite 766 green.
  - **Entry points:** `http://localhost:8080/graph.html` is the new live view; `http://localhost:8080/goals.html` is the T4-AK static tree view and now links to the 3D view in its header.
  - **Sequencing note for future tickets:** T4-AL saturation work will surface through the Signals tab — once decay is per-signal-type, the bars should stop clustering at the cap.

- [ ] **T4-AL: Signal dynamics redesign — saturation, decay, and fitness normalisation** (`Signal`, `AdaptiveExecutionEngine`, `BasicSignals`)
  - **Origin:** T2-BP (promoted 2026-04-15 — infrastructure-level, not a steering tweak).
  - **Problem:** By cycle 10, every ROLE_* signal hits the 10.0 cap — fitness ranking collapses and all agents fire every cycle with no differentiation. Root cause: `Signal.reinforce()` picks `Math.min(decayPerCycle, other.decayPerCycle)`, so the slowest emitter locks the decay rate and signals accumulate indefinitely up to the cap.
  - **Design decision required — candidate approaches:**
    - **A — Per-signal configurable caps:** replace the global 10.0 ceiling with per-`SignalType` caps defined in `BasicSignals`. ROLE_* signals cap lower (e.g. 3.0); event signals (EVOLUTION_OCCURRED) cap higher. Smallest change, most surgical.
    - **B — Exponential decay:** decay rate scales with current strength — `newStrength = strength * (1 - decayPerCycle * strengthFactor)`. High-strength signals fall faster, keeping the range meaningful. No cap needed.
    - **C — Fitness normalisation:** in `AdaptiveExecutionEngine.fitnessFor()`, divide each agent's score by the sum of all agent scores. Ranking becomes relative regardless of absolute saturation. Zero-cost fix — no Signal changes — but hides the underlying inflation rather than fixing it.
    - **D — Differentiated decay per source:** `cycle-start` baseline emissions decay in 1–2 cycles; event-driven emissions (EVOLUTION_OCCURRED, REPETITION_DETECTED) decay slowly. Requires `Signal` to carry source metadata more explicitly.
  - **Recommendation:** C (normalisation in `fitnessFor()`) as a cheap immediate fix; B (exponential decay) as the principled long-term fix. Together they address both the symptom and the root cause.
  - **Relationship to T4-AI:** T4-AI-2 redesigns the meta-agent to emit signal adjustments rather than execution steps — its design assumes signals carry meaningful relative weights. If saturation makes all weights equal, T4-AI-2's steering is inert. Implementing T4-AL (at minimum option C) before T4-AI-2 ensures the signal layer the unified orchestrator depends on actually differentiates agents. **Implement T4-AL before or alongside T4-AI-2.**
  - **Relationship to T4-C:** T4-C (signal trait/pattern spawning) introduces new derived signal types. Those new signals will immediately saturate if the decay/cap problem is unresolved. T4-AL is a prerequisite for T4-C being meaningful.
  - **Relationship to T4-H:** T4-H introduces `localFitnessScore` per goal level — also meaningless if the signal layer that drives agent prioritisation is flat. T4-AL is a soft prerequisite.
  - **Changes (option C + B combined):** `AdaptiveExecutionEngine.fitnessFor()` — normalise scores before returning. `Signal.reinforce()` — replace `Math.min(decay)` lock with exponential decay formula. `BasicSignals` — optionally annotate per-signal max strength.

- [ ] **T4-H: Hierarchical goal fitness — level-relative scoring and sub-agents** (`GoalPlannerService`, `GoalGraphService`, `EvaluationAgent`, `DeliberationService`)
  - **Motivation:** The current 2-level `GOAL → SUBGOAL` tree evaluates every mutation against the root goal context. At depth the LLM must bridge too much abstraction, producing noisy scores. Score plateaus (T2-AP) are a direct symptom.
  - **Core idea (organic, not fixed levels):** Let the planner create arbitrary-depth subtrees. Each node carries a `localFitnessScore` computed relative to its *parent's* scope. Scores aggregate upward through a configurable function (weighted average, pessimistic min, etc.) to give each node a `subtreeHealth` value.
  - **Two approaches to agent-level evaluation:**
    - **A — Contextual enrichment (simpler):** single `EvaluationAgent` receives the active subgoal's local scope (parent goal description + siblings) as additional context.
    - **B — Sub-agents per goal level (deeper, requires T4-F):** dedicated evaluation sub-agent scoped to the active branch, with system prompt built around the parent goal. Multiple branches could be evaluated in parallel.
  - **Shared changes (both approaches):**
    - `DeliberationService` / `OrchestrationSnapshotService` produce a **subtree health snapshot** giving the meta-agent branch-level visibility.
    - `PlannerAgent` uses `subtreeHealth` per parent to decide whether to expand, prune, or abandon a branch.
  - **Signal extensions:** emit `BRANCH_STAGNANT` when a subtree accumulates K exhausted children with zero merges.
  - **Neo4j schema:** add `localFitnessScore`, `subtreeHealthScore`, `depth` fields to `GoalEntity`; `GoalGraphService` computes aggregation on promotion / merge events.
  - **Prerequisites:** T2-AL (per-parent exhaustion), T4-AI (meta as signal+intention emitter); approach B additionally requires T4-F.


- [ ] **T4-AN: Build/test execution as grounded evaluation signal** (new `BuildExecutor`, `BuildResultHeuristic`, `autobuilder.build.*` config) — **split into T4-AN-1 + T4-AN-2 (2026-04-22)**
  - **Motivation (2026-04-21):** Every evaluation signal currently comes from LLM judgment or static diff analysis. The loop has no ground truth — it cannot tell whether the code it merged actually compiles or passes tests. A mutation that looks structurally plausible to the LLM may produce a broken build. Build execution closes this gap: pass/fail is unambiguous and requires no LLM.
  - **Why split (2026-04-22, after run 20260422_2 analysis):** Compile gate alone catches the highest-leverage failure modes seen so far (Iterable→List typing bugs, jakarta↔javax import drift). Test execution is a much larger surface area (test fixtures, smoke test authoring, runtime resource availability). Shipping the compile gate first delivers the bulk of the value with a fraction of the integration cost; the test runner can follow once the compile gate is validated in a live session.

- [ ] **T4-AN-1: Compile gate post-merge** (new `BuildExecutor`, `BuildResultHeuristic`, `autobuilder.build.*` config)
  - **Scope:** Run `mvn compile` (or configured equivalent) against `file.base-path` after every successful merge. Capture exit code + stderr. Store as `BuildResult.COMPILE_OK | COMPILE_ERROR | TIMEOUT | SKIPPED` on `CycleContextData.buildResult`.
  - **Feedback paths:**
    - **`BuildResultHeuristic`** (new, registered in `HeuristicNegotiator` only when `autobuilder.build.enabled=true`): `COMPILE_ERROR` → REJECTED, CRITICAL confidence (overrides every other heuristic). `COMPILE_OK` → neutral.
    - **Evaluation prompt enrichment:** surface compiler error lines (first ~20 from stderr) in a `--- BUILD RESULT ---` block in `EvaluationProtocolContract`. The LLM can reason about the error and propose a targeted fix next cycle.
  - **Configuration (`application.yml`):**
    - `autobuilder.build.enabled: false` (default off — target project may not be a Maven project)
    - `autobuilder.build.command: mvn compile` (overridable)
    - `autobuilder.build.timeout-ms: 120000`
    - `autobuilder.build.working-dir: ${file.base-path}`
  - **Implementation shape:** `BuildExecutor` Spring bean wraps `ProcessBuilder`; invoked from `MergeAgent` after filesystem write or from `AdaptiveExecutionEngine` as a post-merge hook. `BuildResultHeuristic` implements `EvaluationHeuristic`. Heuristic runs on a *follow-up* evaluation pass after merge, so the REJECTED verdict can roll back the merge or feed into next-cycle evolution context.
  - **Catches (validated against run 20260422_2):** jakarta↔javax import drift; Iterable<T> vs List<T> typing mismatches; missing imports / typos. Does NOT catch: stub methods returning empty lists (compile fine), runtime broken-shared-state bugs (Layer A/D heuristics handle these).
  - **Relationship to T4-F:** `BuildExecutor` is the first concrete `SubprocessCapabilityProvider`. Once T4-F's capability registry exists, `BuildExecutor` registers as `capability: "build/maven"` and the planner can reason about build availability. For now it is a standalone bean with no registry dependency.
  - **Prerequisites:** None (standalone). Recommended before self-improvement runs.

- [ ] **T4-AN-2: Test runner extension** (extends `BuildExecutor`, adds `TESTS_PASSING / TESTS_FAILING` states)
  - **Scope:** Add `mvn test` (or configured equivalent) as an optional second pass after `COMPILE_OK`. Capture failed test names and surface them in the evaluation prompt. `TESTS_FAILING` → REJECTED, HIGH confidence. `TESTS_PASSING` → modest ACCEPTED boost (LOW confidence — passing tests are necessary but not sufficient).
  - **Configuration:** `autobuilder.build.run-tests: false` (default off). When enabled, `autobuilder.build.command` is split into compile-command + test-command, or a single command that does both.
  - **Open work beyond config:** the target project may have no tests (firestarter currently doesn't), in which case the test-runner pass needs a "no tests found" branch that abstains rather than rejects. Smoke test authoring (autogenerating a minimum smoke test per controller) is a separate ticket if needed.
  - **Prerequisites:** T4-AN-1 (extends the same `BuildExecutor` and heuristic).
  - **Note:** Most of run 20260422_2's regressions would be caught by T4-AN-1 alone. T4-AN-2's marginal value comes from catching stubs and runtime bugs that compile cleanly — important but lower priority than the compile gate.

- [ ] **T4-AO: Parallel subgoal evolution — concurrent exploration of independent goal branches** (`AdaptiveExecutionEngine`, `CycleContextData`, `GoalPlannerService`, new `SubgoalLane`, `LaneCoordinator`)
  - **Motivation (2026-04-21):** The loop is strictly sequential — one active subgoal at a time. For a goal with 8 independent subgoals (e.g. 8 REST endpoints with no shared state), the system works on them one by one, each taking multiple cycles. Independent subgoals could be evolved, evaluated, and merged concurrently, multiplying throughput proportionally to the number of lanes.
  - **Independence criterion:** Two subgoals are independent if their criteria reference no shared output files and neither lists the other as a prerequisite. `GoalPlannerService` detects independence at planning time by comparing file-level coverage from prior mutations and explicit `DependsOn` markers the planner can optionally emit. Conservative default: any overlap → treat as dependent.
  - **Lane model:**
    - `SubgoalLane` wraps a `CycleContextData` scoped to a single subgoal. Each lane runs its own evolution→evaluation→merge sequence.
    - `LaneCoordinator` manages up to `autobuilder.parallel.max-lanes` (default 1 = current behavior) active lanes. On each engine tick, eligible lanes are dispatched concurrently via a thread pool. Merge writes are serialized at the file level (per-file lock) to prevent interleave.
    - Signal space remains shared — a merge in any lane emits `EVOLUTION_OCCURRED` into the shared context. Lane-local signals (e.g. per-subgoal exhaustion counters) live on the lane's `CycleContextData` and do not bleed across lanes.
  - **Goal tree integration:** `GoalPlannerService` maintains an `activeLaneCount` counter. When a lane completes (FULFILLED or GIVEN_UP), the coordinator picks the next eligible independent subgoal and opens a new lane. The planner's `REPLAN_REQUESTED` signal drains all lanes before replanning (safety gate).
  - **Neo4j / snapshot:** `OrchestrationSnapshotService` aggregates across lanes for the meta-deliberation prompt. Each lane's evolution history is tagged with `laneId` in the graph.
  - **Build integration (T4-AN-1):** each lane runs its own `BuildExecutor` check post-merge. A `COMPILE_ERROR` in one lane does not block other lanes — it raises a lane-local `BUILD_FAILED` signal and marks the lane's mutation REJECTED.
  - **Prerequisites:** T4-AN-1 (build verification per lane is the primary safety mechanism preventing one lane's broken merge from corrupting another lane's compile result). T4-AL (signal dynamics must handle multi-lane reinforcement without immediate saturation). T4-AK optional but recommended (the 3D graph becomes significantly more interesting with multiple active subgoals visible simultaneously).
  - **Sequencing note:** Start with `max-lanes=2` on a goal with clearly independent subgoals. Measure cycle throughput and correctness before increasing.

---

## Tier 5 — Quality / observability

- [x] **T5-AZ: `EVOLUTION_OCCURRED` signal carry-over causes false-positive merge hook invocations** (`CycleContextCoordinator`, `applyMergeOnAcceptedMutation`)
  - **Problem:** `CycleContextCoordinator.prepareRuntimeContext()` uses `previous.toBuilder()` to seed the new context, carrying `EVOLUTION_OCCURRED` from the prior cycle. On evaluation-only cycles, `applyMergeOnAcceptedMutation` checks `changedThisCycle = ctx.hasSignal(EVOLUTION_OCCURRED)` — returns `true` even though evolution never ran. The merge hook fires, calls `tryExecuteRole("merge", ...)`, which correctly fails with `missing variant`. Result: spurious eligibility check on every evaluation-only cycle.
  - **Root cause observed:** 2026-04-02 session — cycles 2, 4, 6 all showed `EVOLUTION_OCCURRED` signal despite no evolution running.
  - **Fix:** Introduce a `boolean evolutionRanThisCycle` field on `CycleContextData`, explicitly reset to `false` in `CycleContextCoordinator` (alongside `merged(false)`), and set to `true` only by `EvolutionAgent.runEvolution()`. Replace `ctx.hasSignal(EVOLUTION_OCCURRED)` check in `applyMergeOnAcceptedMutation` with `ctx.evolutionRanThisCycle()`.
  - **Note:** Not a functional bug (`hasAnyVariant` check correctly blocks the spurious attempt), but wasted work and a misleading signal.

- [x] **T5-BC: `ProjectDiffService` crashes on syntactically invalid Java — `IllegalArgumentException` not caught** (`ProjectDiffService`)
  - **Problem (observed 2026-04-11 session):** When the LLM generates syntactically invalid Java (e.g. `BookController.java`, `LoanService.java`), `JavaStructureParser.parse()` throws `IllegalArgumentException: Invalid Java source`. This propagates through `StructuralJavaDiffBuilder.diffJavaFile` and `ProjectDiffService.computeDiff`, logging an `ERROR` and losing the structural diff for that file. The outer `try/catch(Exception)` in `computeDiff` absorbed it — returning an empty diff result for the whole cycle — but the inner `try` per file entry only caught `IOException`, leaving `IllegalArgumentException` to bypass the per-file fallback.
  - **Fix:** Wrap the `StructuralJavaDiffBuilder.diffJavaFile` call in a nested `try/catch(IllegalArgumentException)` inside the per-file block. On catch, log at WARN and fall back to a file-level `StructuralDiffNode` (same as the non-Java path), so the diff continues for other files and the evolution context is not silently discarded.
  - **Changes:** `ProjectDiffService.java` — nested try/catch around `diffJavaFile` (~8 lines).

- [x] **T5-BA: `CyclePersistenceService.persistSnapshot()` creates empty mutation folders for evaluation-only cycles** (`CyclePersistenceService`, `MutationSnapshotService`, `FileVariantCollector`)
  - **Problem:** `persistSnapshot(context)` calls `mutationSnapshotService.writeMutationMeta(context)` unconditionally at the end of every cycle. On evaluation-only cycles, this creates `mutations/<cycleId>/meta.json` with no accompanying content files. `FileVariantCollector.getAllVariantPaths()` scans all subfolders of `mutations/` on every merge eligibility check — O(N) wasted I/O per merge check as cycles accumulate.
  - **Fix:** In `CyclePersistenceService.persistSnapshot()` (or `MutationSnapshotService.writeMutationMeta()`), only write `mutations/<cycleId>/meta.json` if evolution actually ran this cycle (i.e., `context.evolutionRanThisCycle()` — see T5-AZ).
  - **Observed:** 2026-04-02 session — cycles 1775152916594, 1775152953125, 1775152992090 each produced `mutations/<id>/meta.json` with no content, then appeared in subsequent meta deliberation eligibility reports as "missing variant" candidates.

- [x] **T5-F: Per-link observability logs for the acceptance-criteria chain** (`EvaluationAgent`)
  - **Motivation (observed 2026-04-08):** Both live failures in the T3-AW→T3-AY→T3-AZ→T3-BA chain were silent — no log line indicated that criteria were absent from the evaluation prompt or that the `CriteriaCheck:` block was incomplete. Diagnosing the failures required a full post-hoc log trawl.
  - **Fix:** Add two `INFO`/`WARN` log lines in `EvaluationAgent.execute()`:
    1. After building the evaluation context: `"Criteria delivered to evaluator: N"` (where N = `context.currentSubGoalCriteria().size()`, or 0 if null). Log at `INFO` when N > 0, `WARN` when N == 0 and a subgoal is active (hydration may have failed).
    2. After `parseCriteriaAssessments()`: `"CriteriaCheck lines parsed: M of N expected"` (M = `criteriaAssessments.size()`, N = criteria count). Log at `INFO` when M == N, `WARN` when M < N (partial LLM compliance), `WARN` when M == 0 and N > 0 (complete omission).
  - **Scope:** Two log lines, no logic change. The count-mismatch warning is the safety signal T3-BC needs to be actionable without a manual log trawl.
  - **Depends on:** T3-AZ (criteriaAssessments), T3-AW (currentSubGoalCriteria).

- [ ] **T5-G: Investigate persistent SCOPE_OUT_OF_SCOPE signal (decayPerCycle=0.0)** (`CycleStartSignalSeeder`, `DefaultSubgoalScopeGuard`, `BasicSignals`)
  - **Observed (2026-04-21 02:00–02:18 session):** `SCOPE_OUT_OF_SCOPE` signal held at `strength=1.0, decayPerCycle=0.0` for every cycle throughout the session. A zero-decay signal that persists for the entire run suggests it is being re-seeded each cycle (intentional) or that the decay is misconfigured (bug).
  - **Questions to answer:** (1) Who emits this signal and with what decay value? (2) Is zero decay intentional for this signal type? (3) Does it correctly suppress evaluation/merge in scope-mismatch cycles, or is it a false positive that throttles the loop unnecessarily? (4) Is there a mechanism to clear it when the scope guard stops firing?
  - **Investigation entry points:** `CycleStartSignalSeeder`, `DefaultSubgoalScopeGuard`, `BasicSignals.SCOPE_OUT_OF_SCOPE` definition, and any call site that emits the signal.

- [ ] **T5-C: SMS Phase 2 — richer semantic model** (`SmsCycleIngestor`)
  - Emit `INTERPRETATION(type=ACCEPTED_MUTATION)` / `REJECTED_MUTATION` with evaluation score as confidence
  - Link to diff artifact
  - Makes SMS queryable for learning patterns over accepted vs rejected mutations

- [x] **T5-P: Unify cycle reference field name across artifact types**
  - `planner_outcome` artifacts use `cycleName`; other artifact types use `cycleId` for the same concept
  - Fix: `PlannerActionRegistrar` payload key `"cycleName"` → `"cycleId"`; `PlannerActionSnapshot.cycleName` → `cycleId`; Cypher alias + row getter in `Neo4jAgentGraphService` updated to match

- [x] **T5-A: Fix `GraphQueryService` attempt ID lookup + diagnostic coverage**
  - `ctx.parentAttemptId()` → `ctx.lastAttemptId()` for `DIAGNOSTIC_SCAN` query
  - Added `targetsGoalId = $scopeGoalId` fallback OR clause to queries A0–A2 (matching A3's existing pattern) — robust when attempt ID is stale after restart/subgoal promotion
  - Added **A4 rejection-loop query**: fires when ≥3 evaluations exist and ≥60% are REJECTED
  - Added **scan history** to `GraphQueryService` (bounded deque, last 5 results); `DiagnosticScanFormatter.toLLMBlock(r, history)` annotates each finding with recurrence count across prior scans

- [x] **T5-D: Persist scope assessments to Neo4j** (new `graph/scope/ScopeActionRegistrar`, `DefaultSubgoalScopeGuard`)
  - `ScopeActionRegistrar` created (follows `EvaluationActionRegistrar` pattern): action type=`"scope"`, status=`fit.name()`, artifact type=`"scope_payload"` with JSON payload: `{ fit, confidence, message, bestMatchingSubGoalId, subGoalId, goalId, cycleId, attemptId }`.
  - Injected into `DefaultSubgoalScopeGuard`; called after every `assess()` return (all scope assessment types: IN_SCOPE, PARTIAL, OUT_OF_SCOPE), fail-open.
  - `scope` nodes appear automatically in the "Recent Actions" block of `OrchestrationSnapshotFormatter` LLM context.
  - **Prerequisite for T2-AM** (scope thrash detection).

- [x] **T5-E: Expose last scope assessment explicitly in OrchestrationSnapshot** (`OrchestrationSnapshotService`, `OrchestrationSnapshot`, `OrchestrationSnapshotFormatter`)
  - **Depends on T5-D.**
  - Add a dedicated Cypher fetch in `OrchestrationSnapshotService` for the most recent `scope_assessment` action node in the current attempt/subgoal scope.
  - Add `lastScopeAssessment` (`ActionEvidence` + payload fields: `fit`, `confidence`, `bestMatchingSubGoalId`) to the `OrchestrationSnapshot` record.
  - Add a `--- Last Scope Assessment ---` section to `OrchestrationSnapshotFormatter.toLLMBlock()` so the meta-deliberation agent sees it without having to parse the raw "Recent Actions" list.

---

## Proposed chains

Chains are ordered sequences of tickets that should be implemented together or in strict dependency order. Each ticket within a chain is a pre-requisite or strong enabler for the next.

---

### Chain A — Merge hygiene (from 2026-04-13 session)
**Goal:** Stop writing wrong/stale/cross-subgoal mutations to the output directory. Reduces batch size and makes each merge intentional.

```
T1-CD ✓  →  T1-CE
T1-CG ✓  (independent — fingerprint bleed on subgoal promotion)
```

- **T1-CD done:** filter non-ACCEPTED cycles out of `FileVariantCollector.collect()` and `getAllVariantPaths()`. Also required: SKIPPED merge registration in `MergeAgent` when no ACCEPTED variants exist (keeps graph in sync); `hasAnyVariant()` guard on evolution blocker (backstop against graph/filesystem drift); fixed dead-code path in `CycleExecutor.applyMergeOnAcceptedMutation()`.
- **T1-CG done:** clear evolution fingerprints on subgoal change in `ContextLineageTracker`. Prevented evaluation from being permanently blocked on every newly promoted subgoal — was causing immediate GIVEN_UP cascade after each promotion.
- **T1-CE done:** scope merge batches to the active subgoal ID. `collect()` and `getAllVariantPaths()` now take/use `activeSubGoalId` and skip cycles whose `currentSubGoalId` doesn't match. Bootstrap cycles (null/blank active subgoal) are unaffected. Cross-subgoal contamination eliminated.

---

### Chain B — False GIVEN_UP on fulfilled subgoals (from 2026-04-13 session)
**Goal:** When evolution runs out of diffs because the code is already correct, the system should recognise completion rather than abandoning the subgoal.

```
T1-CF  →  T2-CC  →  T2-CB (safety net only)
```

- **T1-CF first:** in the `SUBGOAL_EXHAUSTED` handler, consult `cumulativeCriteriaStatus` before calling `GIVEN_UP`. If all criteria are met, promote to `FULFILLED`. Requires T1-BG + T3-BI to be in place (both already done). This is the primary fix — removes the false-GIVEN_UP at the decision point.
- **T2-CC next:** add a `#NoDiff` short-circuit to `EvolutionAgent`. When evolution detects the implementation is already present, it returns `noOp=true` instead of generating an empty diff. Stops `REPETITION_DETECTED` from accumulating in the first place. Builds on T1-CF by giving the signal for a criteria-only evaluation.
- **T2-CB last (safety net):** caps the empty-backlog injection bypass so even if T1-CF and T2-CC miss a case, the loop terminates after 3 failed recovery attempts rather than churning indefinitely.

---

### Chain E — Endless subgoal creation on GIVEN_UP cascade (from 2026-04-13 23:03 session)
**Goal:** Stop T1-CA injections from immediately stagnating and re-triggering further injections due to a stale diff context and weak re-injection guard.

```
T1-CH  (fingerprint clearing on T1-CA injection)
T1-CI  (lower GIVEN_UP_RETRY_LIMIT from 2 → 1)
```

These two tickets are independent; either can ship first, but both are needed together for full protection.

- **T1-CH first (higher impact):** After T1-CA injects new subgoals at root, clear `lastEvolutionFingerprint` and `lastEvaluatedEvolutionFingerprint` on the live context. Without this, the stale diff from the dead subgoal is scope-checked against every new subgoal → always `OUT_OF_SCOPE` → immediate `SUBGOAL_SCOPE_MISMATCH` → `mut=null` cascade → `ScopeThrashSignalAnalyzer` gives up the new subgoal → T1-CA fires again. T1-CH gives each injected subgoal a clean evolution slate.
- **T1-CI second (belt-and-suspenders):** Lower `GIVEN_UP_RETRY_LIMIT` from `2` to `1` in `PlannerAgent`. `GoalSimilarityService.countGivenUpMatches()` already uses Monge-Elkan semantic similarity at 0.88, so paraphrased re-injections are already detected — the only gap is the retry count. One prior GIVEN_UP is sufficient evidence of unachievability; the double-attempt only doubles churn. One-line constant change.

---

### Chain C — Isolated in-memory state (T2-BZ, layers A+B done)
**Goal:** Detect and prevent controllers from declaring their own private copies of data owned by another controller.

- Layer A (`IsolatedStateHeuristic` — CRITICAL reject on shadow list) done 2026-04-15.
- Layer B (SHARED_STATE_RULE in PlannerProtocolContract) done 2026-04-13.
- Layer C (broaden T2-BX activation gate) removed — the @RequestMapping noun-extraction approach was too Spring/REST-specific. Needs a general-purpose design.

---

### Chain D — Criteria quality across subgoals (already partially shipped)
**Goal:** Ensure every subgoal carries criteria that enforce both its own requirements AND the root goal's cross-cutting invariants.

```
T2-CA Stage 1 [x done]  →  T2-CA Stage 2 [x done]  →  T2-BY (open)  →  T3-BH (open)
```

- T2-CA Stages 1+2 are shipped. The remaining open work:
- **T2-BY:** split subgoals only when criteria span independent concerns. Prevents multi-endpoint criteria from creating unresolvable evaluation conflicts.
- **T3-BH:** plan rationale and sibling context in prompts — agents see what siblings are doing, preventing re-implementation of already-complete sibling work.


---

### Chain F — Merge pipeline correctness (from 2026-04-16 03:47 session)
**Goal:** Ensure every accepted evolution mutation reaches `output/` before the subgoal is declared FULFILLED. The 03:47 session showed 7/9 cycles with `merged=false` — only the initial Book files ever reached output despite accepted mutations for Members and Loans.

```
T1-CT  (meta.json double-write fix)  →  T1-CU  (merge-before-fulfillment gate)  →  T1-CV  (root auto-completion guard)
                                                                                     ↑
T1-CS  (EVOLUTION_NOOP output-term guard)  ──────────────────────────────────────────┘
```

**T1-CT first (prerequisite for T1-CU):** The mutation `meta.json` must accurately reflect the subgoalId and merged state of the mutation itself, not the post-promotion cycle context. Without this, `FileVariantCollector` cannot find orphaned variants even if we make merge run before fulfillment.

**T1-CU second (the core fix):** Score-based closure must not fire until merge has occurred. This ensures the evolve→evaluate→merge→complete sequence is maintained. The merge-gate is the single change that breaks the "accepted but never merged" pattern.

**T1-CS in parallel (independent):** The `previousOutput` surface-term guard prevents false EVOLUTION_NOOP promotions (a different pathway to false FULFILLED). Can be implemented in parallel with T1-CT/CU.

**T1-CV last (depends on T1-CU):** Root auto-completion should not fire when replacement children under GIVEN_UP parents have no merges. Depends on T1-CU ensuring `mergeCount` is accurately incremented.

**T2-CG (independent, lower priority):** Signal cap prevents ROLE_EVOLUTION from drowning other priorities. Quality improvement; no functional dependency on the others.

---

### Chain G — Run 20260422_2 follow-ups
**Goal:** Close the correctness and steering gaps surfaced by the run 20260422_2 vs 20260422 comparison (analysis-cascade false GIVEN_UP, `new ServiceClass()` shadow state, jakarta↔javax merge regression, no compile ground truth).

```
T1-CZ  →  T2-BZ Layer D  →  T4-AN-1  ┐
                                      ├─→  resume T3-BR / T3-BO audit / T3-BQ / T3-G1/G2
T2-CI  ──────────────────────────────┘
```

- **T1-CZ first:** narrow state-machine fix; unblocks correctness whenever analysis injects under a near-complete parent.
- **T2-BZ Layer D:** cheap regex extension to the existing heuristic — slots in immediately after T1-CZ.
- **T4-AN-1 (compile gate):** the highest-leverage backstop. Catches javax/jakarta drift and Iterable<T>/List<T> typing bugs regardless of LLM judgment.
- **T2-CI in parallel with T4-AN-1:** different heuristic slot. Cheap preventative check fires before compile; T4-AN-1 is the harder ground-truth backstop.
- T4-AN-2 (test runner) and the broader T4 agenda (T4-J / T4-AI / T4-AL) keep their existing priority — no evidence from these two runs reorders them.

