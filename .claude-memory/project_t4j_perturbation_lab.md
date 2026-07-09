---
name: project-t4j-perturbation-lab
description: "T4-J Phase 3 (2026-07-09): frozen-state perturbation lab BUILT + first full sweep. Instrument: SignalPerturbationLab/LabStateLibrary/SignalPerturbationLabReportTest (test scope, com.walpro.autobuilder.core.engine), 14 canonical + 14 log-derived states, fidelity 12/14. HEADLINES: planner/analysis fitness are frequently EXACTLY equal (saturation + fully-shared set members) — the Phase-0 sub-jitter coin-flips are exact ties, not near-ties; PLANNING_THROTTLED blocks planner eligibility while BOOSTING planner fitness; decay constants are single-cycle-inert (multi-cycle replay needed for Q7 decay verdicts); eligibility, not rank, often picks the executor (evolution ranks last yet executes). Read before Phase 3d/4/5 work."
metadata: 
  node_type: memory
  type: project
  originSessionId: a3509f13-fdba-438b-97a0-874862704b9b
---

# T4-J Phase 3 — perturbation lab, first sweep results (2026-07-09)

## The instrument
`SignalPerturbationLab` + `LabStateLibrary` + `SignalPerturbationLabReportTest` (all test-scope,
package `com.walpro.autobuilder.core.engine`; committed `a5db130`). For one frozen
`CycleContextData` it recomputes, with production code paths: mode (real
`RuleBasedDeliberationProposer`+validator), gate-allowed roles, fitness ranking (real
`AdaptiveExecutionEngine`, `explorationJitter=0`, captured via the BehaviourLogger callback
before any agent executes), per-role eligibility (real `ExecutionEligibilityExplainer` over a
mocked `LabWorld` service backdrop), ticks-to-death (real `Signal.tick()`).
Held constant, documented: GoalExhaustionDetector (place SUBGOAL_EXHAUSTED in the state instead),
registrar, agent emissions. Regenerate the boundary map with
`mvn test -Dtest=SignalPerturbationLabReportTest` → `target/perturbation-lab-report.md` (~4 s).
States: 14 canonical (bootstrap, pending-eval, stagnation±divergence, repetition, plateau 1.0/3.0,
throttle±override, build-broken, halted, missing-symbol, subgoal-exhausted ±executable) +
14 log-derived (`src/test/resources/lab/log_derived_states.json`, harvested from the 13-run corpus
+ post-deletion run: 5 closest-margin boundary states, 6 most frequent type-sets, 3 targeted).
Log-state limits: strengths exact, age=1 assumed, decay=modal per type, context fields synthetic.
Fidelity: 12/14 recorded production rankings compatible (order among sub-jitter ties is
production coin-flip, carries no information). The 2 mismatches: log-11 = mode divergence
(uncaptured context fields took an earlier proposer branch in production), log-12 = genuine
age-reconstruction failure at margin 0.96.

**End-to-end field validation (user-prompted, pinned as a standing test the same day):** the
fixture also carries each harvested cycle's recorded deliberation mode and first
`agent_selected` role; `logDerivedStates_labPredictsProductionsFirstExecutedRoleAndMode`
asserts the lab reproduces both. Result: mode 13/14; first-executed role 12/14 exact +
1 tie-compatible (log-04: planner~analysis jitter-tied — the lab declares the coin-flip set,
production's jitter-pick is a member) + 1 known mismatch (log-11, excluded with rationale in
the test comment; hypothesis: stagnation circuit breaker active — a context field snapshots
don't record — whose proposer branch returns UNDIRECTED and pre-empts the repetition rule).
Evidence inventory for "the lab works": (a) this end-to-end check against field data;
(b) 12/14 ranking compatibility; (c) 8 pinned decision tests on full-fidelity canonical states
guarding the harness wiring (the lab reuses production components — the wiring, stub-agent
surface, and capture point are what these validate); (d) freeze pin (user-prompted): observe()
proven pure over all 28 states — input signals map untouched, repeat observation identical even
with an interleaved perturbed observation (the sweep's validity rests on this; records are
immutable but their Map components could be mutated in place by the call graph). NOT yet validated: ticks-to-death vs
T4-BO lifetime data (doable later); per-blocker eligibility reasons on log states (synthetic
LabWorld — treat eligibility deltas on log states with caution, canonical states are the
eligibility-grade instrument); any multi-cycle claim (3d not built).

## Findings (first sweep, single-cycle observables)

1. **Exact-tie mechanism found (explains Phase-0's boundary findings).** Planner and analysis
   fitness are often MATHEMATICALLY IDENTICAL: role signals pin at MAX_STRENGTH=3.0
   (80–93% of snapshots) and PLANNER_SIGNALS/ANALYSIS_SIGNALS share every non-role member that
   commonly fires (LOW_CONFIDENCE, MISSING_SYMBOL, PLANNING_THROTTLED, REPLAN_REQUESTED,
   STAGNATION) — the sets differ only in rarely-present types (REPLAN_THRASH_DETECTED,
   SCOPE_OUT_OF_SCOPE vs POTENTIALLY_COMPLETE, BUILD_BROKEN). So the "84% of close calls are
   analysis↔planner" and "14% sub-jitter coin-flips" from Phase 0 are EXACT ties decided purely
   by jitter, not near-ties a constant could fix. Q3 answer taking shape: if analysis-vs-planner
   order matters, the fix is a set-differentiating signal or an explicit tie-break — no decay
   constant can express a preference that isn't in the sets. (Lab plumbing consequence: ranked
   order is canonicalized into epsilon-tiers; without it, float summation order (last ULP) reads
   as decision flips — an instrument bug caught by hand-checking one implausible flip.)

2. **PLANNING_THROTTLED duality (Q5/Q7 design question).** Its presence blocks planner
   eligibility (the throttle) while simultaneously BOOSTING planner fitness (it sits in
   PLANNER_SIGNALS). Net effect in throttled states: planner ranks top, gets skipped, execution
   passes to analysis. Ablating it flips planner eligibility false→true AND drops the top rank in
   the same move (log-01/03/05/08/10/14). Membership of a planner-suppression signal in the
   planner's own attention set is at minimum unexamined design — candidate outcome: keep the
   ANALYSIS_SIGNALS membership ("throttled → prefer analysis"), drop the PLANNER_SIGNALS one.

3. **Q1 behavioral confirmations.** COMPILE_DIAGNOSTIC_FED_BACK: 0 decision flips (3 states) —
   confirms the Phase-1 zero-consumer verdict behaviorally. EVOLUTION_FINGERPRINT_CHANGED:
   0 flips across all 15 states carrying it — fully inert in the SELECTION layer; its real
   consumers (evaluator repeat-cap, AutobuilderService) act elsewhere, consistent with the map;
   it correctly belongs to no fitness set.

4. **Load-bearing ranking signals (ablation flips/inert across states).** MISSING_SYMBOL 14/1 —
   its presence is what keeps the grounding roles (analysis+planner jointly) above evaluation;
   REPLAN_REQUESTED 12/0 — both fitness and the analysis-eligibility trigger; EVOLUTION_OCCURRED
   13/2 — evaluation-top and merge-vs-evolution order (matches the corpus's 462 recorded flips);
   PLANNING_THROTTLED 9/3. ROLE_* flips are identity-expected (ROLE_EVALUATION lowest at 9/18).

5. **Decay constants are single-cycle-inert.** No decayPerCycle grid point flips any same-cycle
   decision — ranking reads strength+age only; decay's sole same-cycle observable is
   ticks-to-death. Q7 decay calibration is therefore inherently a MULTI-CYCLE question:
   Phase 3d (mocked-agent replay over N cycles) is where decay verdicts must come from.
   Strength and age are the same-cycle levers (age acts via the recency term e^(-age/5):
   ageing EVOLUTION_OCCURRED or MISSING_SYMBOL by 3-10 cycles flips real decisions).

6. **ROLE_MERGE strength boundary sits at ~2.0.** Merge takes the top rank at strength ≥2.0 in
   several states (build-broken, missing-symbol) and drops below evolution at ≤0.5. The
   score-plateau boost emits exactly 2.0 — right at the takeover threshold, i.e. the proposer's
   plateau boost is calibrated to actually put merge on top (evidence the 2.0/0.10 site is
   intentional, supporting the Phase-1 adjudication).

7. **Eligibility, not rank, often picks the executor.** In log-02/09/12/13 the first ELIGIBLE
   role is evolution while evolution ranks LAST — everything above it is blocked (no diff,
   evaluation fingerprint repeat, throttle...). Mechanistic confirmation of Phase 0's "evolution
   is never in a close race": evolution executes by eligibility walk-down, not by winning the
   ranking. Any Q7 tuning story that only moves rank order without touching eligibility will
   often not change the executed role at all.

## What the lab does NOT cover (route accordingly)
Single-cycle only (3d replay pending); prompt-diff driver (3c) not built yet — analysis prompt
renders NAME=strength pairs so EVERY signal perturbation is weakly prompt-reaching per
[[project-signal-consumer-map]]; LLM-emitted signals (interpretation parser) out of scope until
Phase 4. Pairwise ablations (Q4 redundancy) not yet in the grid.
