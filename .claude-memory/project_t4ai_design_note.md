---
name: project-t4ai-design-note
description: "T4-AI design note (2026-07-08) — unify the dual orchestration (meta steps + fitness pass) into signal-steered single-pass execution; as-built map, authority findings, invariants, slicing plan, open questions. Read before touching AdaptiveExecutionEngine/DeliberationService."
metadata: 
  node_type: memory
  type: project
  originSessionId: 528096bd-6c47-419f-b5f1-3995d268575d
---

# T4-AI design note — unify meta-steps and deliberation orchestration (2026-07-08)

Structural-lane entry per the 2026-07-08 re-triage (user: pursue significant-advancement tickets; refinement tail parked). Design-only was unlocked 2026-06-12. This note maps the AS-BUILT state (the ticket's line refs and one sub-task have drifted), fixes the target model, and slices the work.

## As-built map (what actually happens per cycle)

`CycleExecutor.runCycle` → `AdaptiveExecutionEngine.runCycle`:

1. **Halt short-circuit**: GOAL_EXHAUSTED / SYSTEM_PAUSED present → return (cycle start only, never mid-cycle).
2. **DeliberationService.deliberate** (one call, four stages):
   a. `GoalExhaustionDetector` → may add SUBGOAL_EXHAUSTED signal (strength from confidence, floor 0.55).
   b. `RuleBasedDeliberationProposer.propose` → `DeliberationResult(mode, confidence, rationale, considerations, evidence, meta, proposedSignals)` — deterministic rules pick the MODE.
   c. `MetaDeliberationAgent.decide` (LLM): eligibility computed ONCE (`computeEligibleRoles`, T2-DF — same computation feeds the prompt block and the post-LLM filter), orchestration ledger from the graph snapshot, meta-learnings block from `MetaOverrideLearningStore`. Produces `MetaDeliberationPlan(stop, mode, rationale, steps)`. Steps are FILTERED (dropped) against cycle-start eligibility.
   d. Plan flattened into `meta` map: `metaControl.steps` as pipe-strings `index|Need|TargetRole|Request|StopGuard` (stopIf/stopCycleIf collapsed into one string by `toEngineStopGuard`).
3. **Engine**: applies `proposedSignals`; re-parses the pipe-strings (`parseMetaStepLine`, `stopDecision` via `String.contains`); executes meta steps as an imperative PREFIX (per-step: role dedup honoring T2-DJ, mode gate, canExecute re-check, MetaIntention carrying the request text to the agent, reinforce/attenuate, stop guards); then the signal-driven fitness pass over remaining roles; T1-DK second pass for canExecute-blocked roles; exploration nudge.
4. **Termination**: `shouldEndCycle` = `ctx.merged()` only, plus the meta stop_cycle path returning early.

## Findings that sharpen the ticket

- **F1 — the meta-agent's mode AND stop flag are DECORATIVE (grep-verified 2026-07-08).** Of `MetaDeliberationPlan(stop, mode, rationale, steps)`, only `steps` has a production consumer (`metaControl.steps` → engine + behaviour log). `metaControl.stop` and `metaControl.mode` are written by `DeliberationService` and read by NOTHING except two serialization-contract tests — a plan-level `stop=true` cannot actually stop anything (only per-step stop guards can). The "unclear authority boundary" is worse than filed: half the meta-agent's declared authority is silently ignored. The unification makes this explicit — mode stays rule-derived (deterministic), the meta-agent steers only via signals/intentions, and the dead half of the LLM response contract is either deleted from the prompt (cheaper calls, less confabulation surface) or given real semantics in slice 4; decide there, delete the meta-map entries + contract-test assertions in the same scope either way. (Q1 hereby resolved.)
- **F2 — T4-AI-4 as filed is ~done by accident.** Per-step `canExecute` re-checks exist in BOTH execution loops, and T1-DK adds a second full pass. The real residual staleness: `filterIneligibleSteps` DROPS plan steps against cycle-start eligibility, so a step that would become eligible after an earlier step ran is pre-emptively discarded (a mid-cycle-eligibility variant of the T2-DJ poisoning bug, on the plan side). Re-scope T4-AI-4 to: defer, don't drop — keep the step, let the executor's existing re-check decide at execution time; the prompt's eligibility block stays as-is (it is advisory).
- **F3 — the pipe-string round-trip is exactly as filed** (T4-AI-1): typed `MetaActionStep` → string → regex-ish re-parse, losing the stopIf/stopCycleIf distinction into `contains()` heuristics. Pure hardening to carry the typed list through (new `DeliberationResult` field or a typed meta object), then DELETE `parseMetaStepLine`/`toEngineStopGuard`/`stopDecision`-string-matching in the same scope.
- **F4 — role-signal semantics are already the steering surface.** `applyProposedSignals`, `injectRequiredRoleSignalForModeIfDeadlocked`, AnalysisUnlock, and BUILD_BROKEN fitness adjustment all already steer via signals. The unified model is not a new paradigm for the engine — it is deleting the imperative prefix in favor of the surface four mechanisms already use.

## Target model (T4-AI-2/3/5 as one reviewed piece)

- The meta-agent emits **per-role intentions** (role → request text + urgency) and **constraint signals** (single-cycle SUPPRESS_<ROLE> at decay 1.0), not an execution plan. The fitness pass is the sole executor; intention urgency becomes a temporary role-signal overlay (this-cycle only — decay 1.0, never persisted to subgoal signal storage), and the request text rides the existing `MetaIntention` channel keyed by role, consumed when that role executes.
- Step ORDER is expressed by relative urgency; strict sequencing guarantees disappear — acceptable because meta steps already execute best-effort (any step can be skipped by gates today, so downstream steps never had a hard guarantee about upstream completion).
- `DeliberationExecutionGate`'s static mode→roles map retires into constraint signals emitted by the same rule layer that picks the mode today (T4-AI-5). PAUSE = suppress all; EVOLVE = suppress all-but-evolution; etc. The mode enum survives as vocabulary/telemetry, not as a parallel gate.
- **T4-AI-3**: named `CycleTerminationReason` (MERGED, META_STOP_CYCLE, HALT_SIGNAL_PRESENT, …) with `shouldEndCycle` consulting halt signals MID-cycle too (today only at cycle start — a mid-cycle GOAL_EXHAUSTED/SYSTEM_PAUSED is honored one cycle late). Behavior change; needs its own test row.
- Stop guards become typed conditions evaluated by the engine after each agent (they already are, just string-matched — T4-AI-1 fixes the carrier, T4-AI-3 names the outcomes).

## Invariants to preserve (each has a regression test or gets one)

1. One execution per role per cycle (`executedRoles`), with T2-DJ's "skipped ≠ executed".
2. Immediate cycle end on merge.
3. Stagnation circuit breaker suppression of REPLAN_REQUESTED (interpretation side).
4. T2-DF single-computation eligibility (prompt and filter/defer must not diverge).
5. T1-DK second-pass re-check survives (or is subsumed by the unified pass with a test proving the same rescue).
6. Deadlock injection semantics (single-allowed-role mode with weak signal) — replaced by constraint signals + a floor overlay, with the run-20-family non-halt guards untouched.
7. `MetaOverrideLearningStore` telemetry keeps recording skip patterns (the meta-agent's own learning input).

## Slicing plan

1. **T4-AI-1 (hardening, land first):** typed steps end-to-end; delete the string round-trip. No behavior change; suite + scenario tests prove identical execution on canned plans.
2. **T4-AI-4 re-scoped (hardening):** filter → defer in `MetaDeliberationAgent.filterIneligibleSteps` (keep the behaviour event, reason becomes `deferred_not_eligible_at_planning`); executor re-checks decide. Small behavior change in the step-conservation direction.
3. **T4-AI-3 (small, reviewed):** `CycleTerminationReason` + mid-cycle halt honoring. Behavior change; own tests.
4. **T4-AI-2+5 (core, one piece, flag-gated):** `autobuilder.engine.unified-orchestration` default OFF; meta prompt/parser emit intentions+constraints (LLM contract change — validate via scenario harness with canned responses AND a logging-proxy live capture, the T4-BN-3 method); A/B against the imperative-prefix path on preflight live runs; on validation, DELETE the meta-step execution path + gate map in the same scope ([[feedback-ship-full-scope]]).

## Open questions (resolve before slice 4, none block slices 1-3)

- ~~Q1~~ RESOLVED (see F1): no production consumers; two contract tests to update at deletion time.
- Q2: overlay arithmetic — reuse `withSignalReplaceRoleSignals` (replace) or additive reinforce? Replace matches today's deadlock-inject semantics; decide with a table of the four existing steering sites.
- Q3: does the deliberation LLM contract change need its own eval corpus first (T5-AG family-1 flagged the same gap: NO eval covers deliberation)? Cheap option: capture N real deliberation calls via the logging proxy pre-change as a fixture baseline.
- Q4: `RuleBasedDeliberationProposer` (334 lines) stays the deterministic mode/rule layer in all slices — only its OUTPUT vocabulary changes in slice 4 (constraints instead of gate-consulted mode). Confirm no rule depends on meta steps executing before the fitness pass.

## Downstream

T4-F (dynamic sub-agents) plugs into the unified signal+intention surface; T4-BO's signal-lifetime measurement instrument should land while slices 1-3 are in review so slice 4's A/B has signal-dynamics telemetry to read.
