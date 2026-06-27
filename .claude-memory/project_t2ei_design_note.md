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

## First slice — SHIPPED 2026-06-25 (slice 1a, awaiting re-run validation)
**Implemented:** origin marking + hierarchical closure behind the `depth-first-decomposition` flag.
- `GoalPlannerService.addSubGoal` stamps `subgoalOrigin` = required (top-level planner decomposition, `parentId==root` & role planner) | extra (nested under a subgoal, or analysis/graduation/residue). D1 origin-based.
- `autoCompleteRootIfSatisfied`: under depth-first, a failed **EXTRA** subgoal is **absorbed** (logged, not a root blocker) so closure falls through to the clean branch; **REQUIRED** failures still block. Directly fixes the lone-leaf→root-GIVEN_UP death.
- Tests: 3 new in `GoalPlannerServiceClosureTest` (absorb-extra/block-required, breadth-first control unchanged, origin stamping). All unit suites green (3 pre-existing `Neo4jGoalGraphServiceIT` failures are unrelated — identical at clean HEAD).

**Deliberately deferred (honest scope):**
- **slice 1b — earned per-leaf retry budget** (P3): a leaf is still GIVEN_UP on first exhaustion; absorbing EXTRA failures makes that harmless for now, so 1b is separable. Lever is `GoalExhaustionDetector` / the AutobuilderService exhaustion path.
- **Coverage-gate NOT yet dropped:** slice 1a still relies on the (re-armed) coverage gate as the continuation driver after absorption. Dropping it for a deterministic "required resources unbuilt?" enumerator (P5) needs the structure/skeleton layer (D2 intended structure) — a later slice. Noted so the re-run result is read with this in mind.
- Structure-layer refactor (concrete model #1–2), T4-BT traceability, adoption (parking lot) — later.

**Next:** re-run capped depth-first to see whether the run survives past the cyc-19 death, a resource formally completes, and the coverage gate drives the next resource. Watch for the promotion-freeze (0 subgoals reached COMPLETED last run) as the likely next wall.

### Slice-1a re-run result (2026-06-25, depth-first + coverage-gate, capped 45) — MIXED, fix UNFIRED
- **Survived to CAP_45** (vs death at cyc-19) — but `blocker-branch-hits=0`, `absorbed_extra=0`: **slice-1a's absorption code NEVER executed.** Closure always reached the clean branch; the lone-leaf-at-closure death path simply did not recur. So slice 1a is committed + unit-tested but **field-unvalidated** — the survival is NOT attributable to it ([[feedback-activity-not-progress]]: activation ≠ help; here it didn't even activate).
- **Coverage gate worked as the continuation driver:** Book→Member built (2 controllers, properly packaged: controller/model/service/exception/repository), closure detected Loan missing and **blocked completion, injecting 17 Loan features** (Loan.java + repos started). The "what's next" loop functions — but visibly via the auto-injection the user dislikes, including duplicate/paraphrased Loan subgoals ("Implement the Loan domain model" AND "Create the Loan class in the model package") — reinforces P5 (replace with deterministic enumerator).
- **CORRECTION — there is NO promotion-freeze.** My first read ("16 merges, 0 COMPLETED") was a **bad grep** (`applyGoalProgress: COMPLETED|markGoalCompleted` is not the string the code logs). Truth: `promoted=true` fired **8×**, and the planner-prompt "Completed Goals (N)" block **peaked at 12** (0→2→3→5→6→9→10→12). 31 SUBSUMED. The promotion mechanism works fine. I made this same grep error on run-B AND retroactively suspect the run-A "0 COMPLETED" call — distrust both prior "promotion-freeze" assertions ([[feedback-verify-population-before-claim]], [[feedback-no-defensive-recast]]). **Verify completions via "Completed Goals (N)" / `promoted=true`, NOT markGoalCompleted greps.**
- **What actually happened: the run is HEALTHY and was cut off by the CAP, not a wall.** Final cycle `result=CONTINUE` (still progressing, not halted). Clean resource-by-resource progression: **Book done → Member done → Loan in progress** (Loan controller/exception/model/repository/service dirs all present). 12 completions in 45 cycles. The coverage gate correctly sequenced the resources and refused premature completion. **This is the strongest depth-first run yet** — breadth-first churned 131 cycles without finishing; depth-first got 2 of 3 resources fully done + the third underway in 45.
- **slice 1a unfired** (no lone-leaf death this run) — committed + unit-tested, field-UNvalidated, but not needed here.
- **Variance:** run-A maxActive 3 / OK 0.92 / died-19; run-B maxActive 9(transient) / OK 0.59 / survived-45 healthy. Same config — model planner behaviour varies; don't over-claim from one run ([[feedback-calibrate-not-flip]]).
- **THE REAL OPEN QUESTION:** does a LONGER-cap depth-first run cross the line to GOAL_FULFILLED? Run-B was cut off mid-Loan while healthy. That is the next experiment (raise the cap), and the most promising path to the north-star in weeks.

### Slice 2 — count-based admission queue (SHIPPED 2026-06-25, the deterministic "run-B every time")
Built per the user's "make run B reliable" steer + the "isn't it already a queue?" challenge. **Verified mechanism (run-C log): NOT a clean queue — the current pointer thrashed across 50 distinct subgoals / ~58 switches in 120 cycles, finishing almost none.** Why: one shared `output/` + compile gate across ALL active subgoals → many active resources → cross-resource half-built files on disk → build red → current subgoal's mutation rejected → exhausts → pointer jumps → thrash. "One current per cycle" doesn't stop the disk accumulating 3 resources' incoherent files.
- **Config `autobuilder.goal.admission-cap`** (int, default 0=off; independent of depth-first flag). Caps the number of ACTIVE leaf subgoals SURFACED as workable. Excess (newest by creation) are HELD: keep ACTIVE status (still block premature root completion via the existing anyActive check) but are excluded from current-subgoal selection → never worked → their files never reach the shared disk. As admitted leaves terminate, held ones surface. Deterministic — bounds the worked set regardless of how many the planner/coverage-gate propose in one wave.
- Implementation in `GoalPlannerService`: `heldByAdmission()` (oldest-fill, current always admitted) gates `resolveNextActiveLeaf` (held-aware recursion), `findNextSiblingOrAncestor` (skip held sibling leaves), and the T1-DB null-recovery. `getRemainingGoals` intentionally NOT filtered (the backlog-non-empty guard + closure must still see held leaves).
- Tests: 1 new in `GoalPlannerServiceClosureTest` (cap=2 holds newest 2 of 4, selection lands on admitted, held block completion, cap=0 control). All non-IT suites green (the 3 `Neo4jGoalGraphServiceIT` failures are pre-existing).
- **Next:** long-cap run with `--autobuilder.goal.admission-cap=6` (~one resource's layers) + depth-first + coverage-gate → does the bounded working set reproduce run-B coherence reliably and cross to GOAL_FULFILLED? HYPOTHESIS, one run won't prove reliability ([[feedback-calibrate-not-flip]]).

### ⭐ RESULT: GOAL_FULFILLED at cycle 62 (2026-06-25, depth-first + admission-cap=6 + coverage-gate) — GROUND-TRUTH VERIFIED
**The session's north-star goal is met: the first real GOAL_FULFILLED in this work-line.** `T2-DR: root marked COMPLETED — evidence ledger clear: 61 subgoal(s) accounted for (33 subsumed)` → `T1-DJ: ... root is COMPLETED — returning GOAL_FULFILLED`.
- **All completion gates fired AND worked (not hollow):** package gate first blocked (COMPILE_ERROR, no main class) → fixed; T4-BP-3 criteria verification first blocked (2 unconfirmed criteria of 81) → fixed; then package OK + test OK + 85 criteria verified → COMPLETED. The gates that previously caught false completions ([[project-false-completion-unmasked]]) blocked twice and the loop repaired both.
- **GROUND-TRUTH VERIFIED (not log-trust):** independent `mvn clean package` on `firestarter/output/` → BUILD SUCCESS, exit 0, 20MB bootable Spring-Boot fat jar (BOOT-INF + main class). All 3 controllers + 3 services + 3 repos; 8 endpoints matching the spec (POST/GET/GET-id books, POST/GET-id members, POST/return/GET loans).
- Metrics: 62 cyc, OK-ratio **0.75**, cfs **1.6/cyc** (vs run-C 6.3), admission_holds=23 / max_held=2.
- **HONEST ATTRIBUTION + CAVEATS ([[feedback-calibrate-not-flip]], [[feedback-verify-population-before-claim]]):**
  - **ONE run.** Variance is large (A/B/C differed wildly). One fulfillment ≠ reliable. Reliability needs N repeats of this exact config → measure the fulfillment RATE. That is the next experiment.
  - **Admission-cap's specific contribution is UNCLEAR** — max_held was only 2, so it held gently this run; cannot cleanly credit slice 2 alone. The win is the *combination* (depth-first prompt + admission-cap + slice-1a closure + existing gates + variance).
  - **slice-1a absorption still never fired** (absorbed=0) across all 4 runs — field-unvalidated.
  - **Artifact coherence is poor:** everything dumped in `com/example/demo/model/` (controllers + services in the model package). Compiles/boots/passes, but packaging is incoherent — the separate T2-DI/T4-AV artifact-coherence concern, NOT a completion defect.
- **The session goal ("get a real GOAL_FULFILLED again") is achieved + verified.** Next: reliability (repeat-rate), then artifact coherence; P2 deterministic structure layer still the durable direction.

### Reliability batch (2026-06-27, 5× the winning config) — REAL rate 1/5, NOT 4/5
**Batch script reported 4/5 — WRONG (my 3rd grep error this session): its terminal check matched "Suppressing false GOAL_FULFILLED" as success.** Re-classified by definitive signals (`T2-DR root marked COMPLETED` + `returning GOAL_FULFILLED`, excluding `Suppressing false GOAL_FULFILLED`):
| run | root | genuine FF | completed peak | verdict |
|---|---|---|---|---|
| 1 | COMPLETED | yes | 13 | ✅ REAL fulfill |
| 2 | GIVEN_UP | no (suppressed) | 3 | ❌ |
| 3 | GIVEN_UP | no (suppressed) | 29 | ❌ |
| 4 | (running) | no | 24 | ⏱ deadline |
| 5 | GIVEN_UP | no (suppressed) | 3 | ❌ |
**REAL fulfillment rate ≈ 1/5** (2 total verified across 6 runs incl. the cycle-62 run). The config WORKS but is NOT reliable. The "Suppressing false GOAL_FULFILLED" guard correctly caught all 3 false ones (no false positive leaked) — but the runs still FAILED (root GIVEN_UP). **Detection lesson: classify on `returning GOAL_FULFILLED`+root COMPLETED, never loose substrings ([[feedback-verify-population-before-claim]]).**

**DOMINANT FAILURE MODE = eager per-leaf give-up (= deferred slice 1b; the user predicted this exactly):**
- Runs 2 & 5: a REQUIRED subgoal ("Implement POST/GET /books") was **"exhausted after 1 cycle without merge"** → GIVEN_UP → root GIVEN_UP. One bad cycle on a core subgoal kills the run.
- Run 3: slice-1a fired correctly (absorbed an EXTRA failure) but 1 REQUIRED subgoal with unmet criterion [2] still doomed the root after 29 completions.
- slice-1a (EXTRA absorption) is now field-VALIDATED (fired in run 3) but insufficient — it doesn't cover REQUIRED-subgoal give-up.
**→ NEXT LEVER = slice 1b: earned per-leaf retry budget.** A required subgoal must not be GIVEN_UP after a single unproductive cycle — give it N attempts (ideally relative, [[feedback-relative-thresholds]]) before give-up. Lever is `GoalExhaustionDetector` / the AutobuilderService SUBGOAL_EXHAUSTED→GIVEN_UP path (`consecutiveSubgoalExhaustionsWithoutMerge` increments to 1 then immediately gives up). This directly targets the 3/5 root-GIVEN_UP failures. Plus consider: closure re-attempts a lone REQUIRED blocker rather than giving up the root (run-3 case).

### Slice 1b — earned per-leaf retry budget (SHIPPED 2026-06-27)
- Config `autobuilder.goal.subgoal-retry-budget` (int, default 0=off — preserves prior eager give-up). When >0, a REQUIRED subgoal exhausted by `GoalExhaustionDetector` gets up to N fresh attempts before GIVEN_UP: reset its `cumulativeStagnatingCycles` (so exhaustion Rule D doesn't instantly re-fire) + clear stagnation/repetition/low-confidence signals, keep it current. EXTRA-origin subgoals skip the budget (absorbed at closure by slice 1a anyway). Per-leaf counter cleared on any merged cycle (a merge = progress = refreshed budget).
- Implementation: `AutobuilderService` SUBGOAL_EXHAUSTED handler (retry block before the give-up); `subgoalRetryCount` map; `GoalPlannerService.isExtraOriginSubgoal(id)` + `resetGoalMetadataInt(id,key)`.
- Tests: 3 new in `AutobuilderServiceTest` (required→retry-not-giveup + reset called + signal cleared; over-budget→giveup; extra-origin→eager-giveup-no-reset). All non-IT suites green; 4 pre-existing Neo4j-Testcontainer IT failures confirmed unrelated (identical at clean HEAD).
- **Caveat:** budget default 3 for the experiment is a behaviour-judgment constant — relativizing it ([[feedback-relative-thresholds]]) is a refinement. Next: reliability re-run with admission-cap=6 + subgoal-retry-budget=3 → does the rate move off 1/5? (And fix the batch detection bug: classify on `returning GOAL_FULFILLED`+root COMPLETED.)

### Slice-1b reliability batch (2026-06-27, 3 runs — stopped at user's request; config df+admission-cap=6+retry-budget=3)
Batch detection bug FIXED (classifies on `returning GOAL_FULFILLED`+root COMPLETED + re-verifies from saved log).
| run | verdict | cycles | completed peak | retries |
|---|---|---|---|---|
| 1 | ✅ REAL fulfill | 108 | 38 | 1 |
| 2 | ❌ model ceiling (see below) | 94 | 33 | 4 |
| 3 | ⏱ harness deadline (NOT a real failure) | 106 | 50 | 0 |
- **Genuine rate 1/3 — but the honest re-read is "1 fulfilled, 1 would-fulfill-with-more-time, 1 model ceiling."**
- **Slice 1b WORKED at its target:** the early one-strike deaths (prior batch: 8/15-cycle deaths) are GONE — all 3 runs went 94–108 cycles; retries fired (runs 1,2). The failure mode moved early→late.
- **Run 3 = harness artifact, not a failure:** completed-goals climbed steadily 0→50 (no plateau), ~6-subgoal focused working set (admission cap working), 10 merges in last 20 cycles — cut off by MY `DEADLINE=5400`/`CAP=120`, NOT a product limit (production has no cycle/time cap; T4-I brake wouldn't fire on a steadily-merging run). Would likely fulfill in prod.
- **Run 2 = local-model ceiling on the hardest subgoal → T2-EJ filed.** Fatal blocker = "Implement POST /loans endpoint", `5 criteria, 0 passed` — bundled 404+409+422 business rules into ONE subgoal; model couldn't satisfy any even across 4 retries → required blocker → root GIVEN_UP ([[project-local-first-constraint]]). **Lever (user steer): divide-and-conquer fallback AFTER retries exhaust — split the multi-rule subgoal into per-rule sub-subgoals, with anti-churn guards (≥2 unmet criteria, no re-division, post-retry-only). Filed as T2-EJ.**
- **SESSION PAUSED here (2026-06-27).** Slices 1a/2/1b committed on `feature/completion-correctness` (NOT pushed). First verified GOAL_FULFILLED achieved + ground-truth-built. Open: T2-EJ (divide-and-conquer), P2 merge/push decision, artifact coherence (T2-DI/T4-AV), relativize retry budget.

### Run-C (2026-06-25, depth-first + coverage-gate, capped 120) — DID NOT FULFILL; variance pins the lever on P2
- **TERMINAL=CAP_120, no fulfillment.** And WORSE than breadth on churn: okRatio **0.27** (vs breadth 0.55, run-A 0.92, run-B 0.59), cfs **6.28/cyc** (vs breadth 4.9), maxActiveSubgoals **14**, backlog-cap-throttle 17, throttle_total 204. controllers=3 (all built but incoherent).
- **ROOT CAUSE: the model IGNORED the one-resource directive this run** — the FIRST decomposition emitted ALL THREE resources up front (3 PlanRationale blocks Books+Members+Loans, 8+ subgoals spanning Book model/repo/POST/GET/GET-by-id + Member model/repo/POST). Breadth-like working set (14 active) → the same churn depth-first was meant to avoid, worse.
- **THE DECISIVE FINDING (3-run variance):** run-A obeyed → tight (maxActive 3) but died-19; run-B mostly obeyed (sequential via coverage gate) → healthy, 12 completions; run-C did NOT obey → churned (maxActive 14, OK 0.27), no fulfill. **Prompt-directive depth-first is UNRELIABLE — it helps only when the model happens to comply, and it doesn't reliably.** This is the empirical proof of P2 ([[project-t4bq-naming-consistency-ab]] redux: the model ignores prompt directives) and of the user's Q1: **depth-first must be a DETERMINISTIC orchestration constraint (orchestrator admits one resource's subgoals at a time), not a prompt the model may ignore.**
- The prompt-directive probe has now served its purpose: (1) shrinking the working set DOES help (run-A/B) — mechanism real; (2) the prompt is not a reliable way to shrink it (run-C). Next build = the deterministic admission (P2 + structure layer, concrete model #1–2), NOT more prompt tuning ([[feedback-activity-not-progress]], [[feedback-stabilize-before-tuning-signals]]).
- slice 1a still unfired (absorbed=0) across all 3 runs — field-unvalidated.

## State of the code right now
Experiment scaffolding is in place, **default-off, 58 planner tests green, uncommitted/unpushed**: `autobuilder.goal.depth-first-decomposition` flag + `DEPTH_FIRST_RESOURCE` contract rule + depth-first empty-backlog NOTE (`PlannerProtocolContract`, threaded via `PlannerLLMAdapter`). This is the *probe*, not the design above — the design supersedes the prompt-directive approach (P2).
