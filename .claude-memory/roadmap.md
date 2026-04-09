# Autobuilder Roadmap

Last updated: 2026-04-09 (added T1-BG, T2-BK/BL, T3-BI/BJ/BK — cumulative criteria tracking + graph persistence + signal isolation; GoalEntity @JsonIgnore/@JsonIgnoreProperties hotfix for merge deadlock)
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

- [~] **T2-AE: Evaluation LLM score anchoring at 0.85** (`EvaluationProtocolContract`)
  - Root cause: `INCLUDE_SCORE` example was literally `"Score: 0.85"` — LLM anchored to it every cycle.
  - Fix: replaced example with `"Score: 0.63 (partial implementation...)"` + added `SCORE_FROM_EVIDENCE`
    rule requiring derivation from diff observations; explicitly warns against defaulting to round values.
  - **Observed (2026-04-08 session):** Partial improvement — scores 0.75 and 0.82 appeared, but 0.85 was still the most common value (3 of 4 evaluations). Anchoring is reduced but not eliminated.

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

- [ ] **T2-BK: Reset subgoal-scoped signals on subgoal switch** (`CycleContextCoordinator`)
  - **Root cause (2026-04-09 analysis):** `CycleContextCoordinator.prepareRuntimeContext()` calls `previous.toBuilder()`, which copies the full signal map to the new cycle. Signals such as `STAGNATION`, `REPETITION_DETECTED`, `DIVERGENCE_REQUEST`, `LOW_CONFIDENCE`, and `SCORE_PLATEAU` describe a pattern *for a specific subgoal* and become poison when inherited by a freshly injected one. In the 2026-04-09 session, `STAGNATION` (strength=0.7, decayPerCycle=0.2) was carried into the first cycle of `goal-be7104f6`; it decayed to 0.5 but still exceeded `GoalExhaustionDetector`'s threshold of 0.50. Combined with a low-entropy first evolution (1 file changed), Rule A fired and the new subgoal was GIVEN_UP in its first cycle.
  - **Fix:** In `CycleContextCoordinator.prepareRuntimeContext()`, detect when `previous.currentSubGoalId()` differs from the newly resolved subgoal ID. When a switch is detected, strip the following signals from the carried-over context before returning: `STAGNATION`, `REPETITION_DETECTED`, `DIVERGENCE_REQUEST`, `LOW_CONFIDENCE`, `SCORE_PLATEAU`. Keep structural signals: `ROLE_*`, `REPLAN_REQUESTED`, `EVOLUTION_OCCURRED`, `PLANNING_THROTTLED` (these describe system state, not subgoal-local history).
  - **Note:** `cumulativeStagnatingCycles` and `cumulativeCriteriaStatus` are already reset in `prepareRuntimeContext()` on subgoal change; this ticket extends that pattern to the signal map.
  - **Independent of T1-BG** — can be implemented immediately.

- [ ] **T2-BL: Criteria-aware exhaustion suppression** (`GoalExhaustionDetector`, `CycleContextData`)
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

- [ ] **T3-BH: Plan rationale and sibling coordination context** (`PlannerProtocolContract`, `PlannerResponseParser`, `PlannerOutcome`, `GoalEntity.metadata`, `EvolutionProtocolContract`, `EvaluationProtocolContract`)
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

- [ ] **T3-BB: Persist per-evaluation `CriterionResult` nodes to Neo4j** (`EvaluationActionRegistrar`, `EvaluationGraphFacade`)
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

- [ ] **T3-BJ: Surface criteria progress in orchestration snapshot** (`OrchestrationSnapshotService`, `OrchestrationSnapshotFormatter`)
  - **Problem:** The meta-deliberation agent and interpretation agent currently have no visibility into *which* criteria have been satisfied and which have persistently failed for the current subgoal. They can observe that the subgoal is PROGRESSING, but they cannot distinguish "3 of 4 criteria satisfied — one stubborn criterion remaining" from "0 of 4 criteria ever passed — subgoal is fundamentally stuck." These require completely different steering responses (targeted evolution vs. replanning), but the snapshot gives the agents no data to reason from.
  - **Fix:** In `OrchestrationSnapshotService`, for the active subgoal:
    1. Load `cumulativeCriteriaStatus` from `GoalEntity.metadata["criteriaPassStatus"]` (T3-BI) — the rolled-up per-criterion pass/fail across all cycles.
    2. Query the N most recent `CriterionResult` nodes for this subgoal (T3-BB) to determine *how long* each unmet criterion has been failing (streak count).
    3. Build a `criteriaProgressBlock`: per-criterion summary formatted as `[✓/✗/–] <text> (passed N cycles ago | failed M consecutive cycles | never assessed)`.
  - **Surface in `OrchestrationSnapshotFormatter`** under `--- CRITERIA PROGRESS ---`, between the evaluation summary and the signal list. Visible to the meta-deliberation agent, interpretation agent, and the planner's `CURRENT SUBGOAL STATUS` block.
  - **Expected steering impact:** Meta-agent proposes targeted evolution when 3/4 criteria pass (one remaining). Interpretation agent emits `REPLAN_REQUESTED` when a criterion has failed 5+ consecutive cycles with no improvement. Planner's coaching note becomes criterion-specific ("criterion 2 has never passed — the implementation of X is missing").
  - **Depends on:** T3-BB (event log for streak counts), T3-BI (cumulative status for rolled-up view). Can be partially implemented with T3-BI alone (no streak counts) and upgraded to full streaks when T3-BB is ready.

- [ ] **T3-BK: Partial graduation — force-fulfill at high criteria pass rate** (`SubGoalFulfillmentHeuristic`, `GoalPlannerService`, `PlannerAgent`)
  - **Problem:** A single stubborn criterion can hold an otherwise-complete subgoal hostage indefinitely. If 3 of 4 criteria have passed across multiple cycles but criterion 4 has failed N consecutive times (likely due to a structural gap in evolution rather than a solvable incremental problem), the subgoal will exhaust and be GIVEN_UP — discarding the real progress made on the other three.
  - **Fix:** In `SubGoalFulfillmentHeuristic.decide()`, after the normal fulfillment path fails (unmet criterion veto), check graduation: if `passedCount / totalCount >= GRADUATION_RATIO` (default 0.80, configurable via `autobuilder.criteria.graduation-ratio`) AND the remaining unmet criteria have each failed for at least `GRADUATION_MIN_FAIL_STREAK` cycles (default 3, read from `cumulativeCriteriaStatus` + `lastCriteriaPassCount` history), return `FulfillmentDecision.yes(score, "graduation: M/N criteria satisfied, remaining injected as micro-subgoal")`.
  - **On graduation:** `CycleExecutor.applyCompletion()` detects the "graduation" rationale and calls `PlannerAgent` (or directly `GoalPlannerService`) to inject one micro-subgoal per unmet criterion, each with a single-criterion `Criteria:` block. The parent subgoal is then marked FULFILLED.
  - **Effect:** Progress is banked; the system moves forward rather than exhausting. The micro-subgoal is a focused, smaller target that evolution can hit in fewer cycles.
  - **Configurable:** `autobuilder.criteria.graduation-ratio: 0.80`, `autobuilder.criteria.graduation-min-fail-streak: 3`.
  - **Depends on:** T1-BG (`cumulativeCriteriaStatus`), T3-BI (streak history), T2-BL (exhaustion suppression should fire before graduation is needed — graduation is the last resort).

- [ ] **T3-B: Activate `GoalEntity.expectedArtifacts`**
  - Define schema (file path + existence check or content assertion)
  - Populate from planner LLM output
  - Add artifact check to `SubGoalFulfillmentHeuristic.decide()`

- [ ] **T3-G: Complexity-adaptive regression tolerance — Phase 2 (lines changed composite)** (`ScoreRegressionHeuristic`, `DiffService` or `FileMutation`)
  - Follow-up to T2-AJ once Phase 1 is validated empirically. Replace or augment the file-count proxy with actual diff size (insertions + deletions).
  - Lines changed correlates more directly with LLM evaluation variance than file count (a 1-line change to 4 files is not complex; a 500-line rewrite of 1 file is).
  - Design options: (A) replace file-count breakpoints with line-count breakpoints; (B) composite score combining both; (C) weighted product.
  - Requires diff size to be available on `CycleContextData` at the point the next-cycle heuristic runs.
  - **Also revisit T2-AJ constants** (`SHIFT_PER_FILE`, `SCALE_REDUCTION_PER_FILE`, etc.): hand-picked, not empirically derived. T3-G should calibrate them from observed score history.

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

---

## Tier 5 — Quality / observability

- [x] **T5-AZ: `EVOLUTION_OCCURRED` signal carry-over causes false-positive merge hook invocations** (`CycleContextCoordinator`, `applyMergeOnAcceptedMutation`)
  - **Problem:** `CycleContextCoordinator.prepareRuntimeContext()` uses `previous.toBuilder()` to seed the new context, carrying `EVOLUTION_OCCURRED` from the prior cycle. On evaluation-only cycles, `applyMergeOnAcceptedMutation` checks `changedThisCycle = ctx.hasSignal(EVOLUTION_OCCURRED)` — returns `true` even though evolution never ran. The merge hook fires, calls `tryExecuteRole("merge", ...)`, which correctly fails with `missing variant`. Result: spurious eligibility check on every evaluation-only cycle.
  - **Root cause observed:** 2026-04-02 session — cycles 2, 4, 6 all showed `EVOLUTION_OCCURRED` signal despite no evolution running.
  - **Fix:** Introduce a `boolean evolutionRanThisCycle` field on `CycleContextData`, explicitly reset to `false` in `CycleContextCoordinator` (alongside `merged(false)`), and set to `true` only by `EvolutionAgent.runEvolution()`. Replace `ctx.hasSignal(EVOLUTION_OCCURRED)` check in `applyMergeOnAcceptedMutation` with `ctx.evolutionRanThisCycle()`.
  - **Note:** Not a functional bug (`hasAnyVariant` check correctly blocks the spurious attempt), but wasted work and a misleading signal.

- [x] **T5-BA: `CyclePersistenceService.persistSnapshot()` creates empty mutation folders for evaluation-only cycles** (`CyclePersistenceService`, `MutationSnapshotService`, `FileVariantCollector`)
  - **Problem:** `persistSnapshot(context)` calls `mutationSnapshotService.writeMutationMeta(context)` unconditionally at the end of every cycle. On evaluation-only cycles, this creates `mutations/<cycleId>/meta.json` with no accompanying content files. `FileVariantCollector.getAllVariantPaths()` scans all subfolders of `mutations/` on every merge eligibility check — O(N) wasted I/O per merge check as cycles accumulate.
  - **Fix:** In `CyclePersistenceService.persistSnapshot()` (or `MutationSnapshotService.writeMutationMeta()`), only write `mutations/<cycleId>/meta.json` if evolution actually ran this cycle (i.e., `context.evolutionRanThisCycle()` — see T5-AZ).
  - **Observed:** 2026-04-02 session — cycles 1775152916594, 1775152953125, 1775152992090 each produced `mutations/<id>/meta.json` with no content, then appeared in subsequent meta deliberation eligibility reports as "missing variant" candidates.

- [ ] **T5-F: Per-link observability logs for the acceptance-criteria chain** (`EvaluationAgent`)
  - **Motivation (observed 2026-04-08):** Both live failures in the T3-AW→T3-AY→T3-AZ→T3-BA chain were silent — no log line indicated that criteria were absent from the evaluation prompt or that the `CriteriaCheck:` block was incomplete. Diagnosing the failures required a full post-hoc log trawl.
  - **Fix:** Add two `INFO`/`WARN` log lines in `EvaluationAgent.execute()`:
    1. After building the evaluation context: `"Criteria delivered to evaluator: N"` (where N = `context.currentSubGoalCriteria().size()`, or 0 if null). Log at `INFO` when N > 0, `WARN` when N == 0 and a subgoal is active (hydration may have failed).
    2. After `parseCriteriaAssessments()`: `"CriteriaCheck lines parsed: M of N expected"` (M = `criteriaAssessments.size()`, N = criteria count). Log at `INFO` when M == N, `WARN` when M < N (partial LLM compliance), `WARN` when M == 0 and N > 0 (complete omission).
  - **Scope:** Two log lines, no logic change. The count-mismatch warning is the safety signal T3-BC needs to be actionable without a manual log trawl.
  - **Depends on:** T3-AZ (criteriaAssessments), T3-AW (currentSubGoalCriteria).

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
