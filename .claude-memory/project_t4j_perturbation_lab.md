---
name: project-t4j-perturbation-lab
description: "T4-J Phase 3 (2026-07-09): frozen-state perturbation lab BUILT + first full sweep. Instrument: FrozenCycleObserver/FrozenStateFixtures/SignalPerturbationSweepTest (test scope, com.walpro.autobuilder.core.engine), 14 canonical + 14 log-derived states, fidelity 12/14. HEADLINES: planner/analysis fitness are frequently EXACTLY equal (saturation + fully-shared set members) — the Phase-0 sub-jitter coin-flips are exact ties, not near-ties; PLANNING_THROTTLED blocks planner eligibility while BOOSTING planner fitness; decay constants are single-cycle-inert (multi-cycle replay needed for the Q7 decay answers); eligibility, not rank, often picks the executor (evolution ranks last yet executes). Read before Phase 3d/4/5 work."
metadata: 
  node_type: memory
  type: project
  originSessionId: a3509f13-fdba-438b-97a0-874862704b9b
---

# T4-J Phase 3 — perturbation lab, first sweep results (2026-07-09)

## The instrument
`FrozenCycleObserver` + `FrozenStateFixtures` + `SignalPerturbationSweepTest` (all test-scope,
package `com.walpro.autobuilder.core.engine`; committed `a5db130`). For one frozen
`CycleContextData` it recomputes, with production code paths: mode (real
`RuleBasedDeliberationProposer`+validator), gate-allowed roles, fitness ranking (real
`AdaptiveExecutionEngine`, `explorationJitter=0`, captured via the BehaviourLogger callback
before any agent executes), per-role eligibility (real `ExecutionEligibilityExplainer` over a
mocked `StubbedServices` service backdrop), ticks-to-death (real `Signal.tick()`).
Held constant, documented: GoalExhaustionDetector (place SUBGOAL_EXHAUSTED in the state instead),
registrar, agent emissions. Regenerate the boundary map with
`mvn test -Dtest=SignalPerturbationSweepTest` → `target/signal-perturbation-report.md` (~4 s).
States: 14 canonical (bootstrap, pending-eval, stagnation±divergence, repetition, plateau 1.0/3.0,
throttle±override, build-broken, halted, missing-symbol, subgoal-exhausted ±executable) +
14 log-derived (`src/test/resources/frozen-states/log_derived_states.json`, harvested from the 13-run corpus
+ post-deletion run: 5 closest-margin boundary states, 6 most frequent type-sets, 3 targeted).
Log-state limits: strengths exact, age=1 assumed, decay=modal per type, context fields synthetic.
Fidelity: 12/14 recorded production rankings compatible (order among sub-jitter ties is
production coin-flip, carries no information). The 2 mismatches: log-11 = mode divergence
(uncaptured context fields took an earlier proposer branch in production), log-12 = genuine
age-reconstruction failure at margin 0.96.

**End-to-end field validation (user-prompted, pinned as a standing test the same day):** the
fixture also carries each harvested cycle's recorded deliberation mode and first
`agent_selected` role; `logDerivedStates_observerPredictsProductionsFirstExecutedRoleAndMode`
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
StubbedServices — treat eligibility deltas on log states with caution, canonical states are the
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
   constant can express a preference that isn't in the sets. (Instrument consequence: ranked
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
   confirms the Phase-1 zero-consumer finding behaviorally. EVOLUTION_FINGERPRINT_CHANGED:
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
   Phase 3d (mocked-agent replay over N cycles) is where the decay answers must come from.
   Strength and age are the same-cycle levers (age acts via the recency term e^(-age/5):
   ageing EVOLUTION_OCCURRED or MISSING_SYMBOL by 3-10 cycles flips real decisions).

6. **ROLE_MERGE strength boundary sits at ~2.0.** Merge takes the top rank at strength ≥2.0 in
   several states (build-broken, missing-symbol) and drops below evolution at ≤0.5. The
   score-plateau boost emits exactly 2.0 — right at the takeover threshold, i.e. the proposer's
   plateau boost is calibrated to actually put merge on top (evidence the 2.0/0.10 site is
   intentional, supporting the Phase-1 reading).

7. **Eligibility, not rank, often picks the executor.** In log-02/09/12/13 the first ELIGIBLE
   role is evolution while evolution ranks LAST — everything above it is blocked (no diff,
   evaluation fingerprint repeat, throttle...). Mechanistic confirmation of Phase 0's "evolution
   is never in a close race": evolution executes by eligibility walk-down, not by winning the
   ranking. Any Q7 tuning story that only moves rank order without touching eligibility will
   often not change the executed role at all.

## Phase 3c — prompt-diff results (2026-07-09, same day; committed `4d434ba`)
`AgentPromptRenderer` (test scope, same package) renders the five signal-relevant prompts
(planner/evolution/evaluation/analysis/interpretation) from a frozen context exactly as the
production adapters do — including EvolutionAgent's signal-driven divergent-path decision
(RECOMBINATION_REQUEST || DIVERGENCE_REQUEST || STAGNATION; the score-regression leg held
constant) — and line-diffs two renders with changes attributed to their enclosing prompt block.
The sweep now emits a per-signal × per-prompt reach table. Findings:
- **Interpretation prompt: reached by 100% of perturbations of every type** — it embeds the
  full signal map including age and decay. Notably, **decay changes reach ONLY this prompt**:
  the sole same-cycle consumer of decayPerCycle anywhere is the interpretation LLM's input text.
- **Analysis prompt: presence/strength reach only** — it renders NAME=strength, so age/decay
  changes are invisible to it.
- **Dedicated prompt paths confirmed:** REPLAN_REQUESTED → planner prompt (12/12);
  DIVERGENCE_REQUEST / REPETITION_DETECTED / SCORE_PLATEAU / STAGNATION → evolution prompt
  (presence-based; e.g. ablating DIVERGENCE_REQUEST changes nothing where STAGNATION also holds
  the divergent flag).
- **The evaluation prompt is signal-blind** — no tested signal perturbation changes it; the
  evaluator LLM never sees signal state (its prompt blocks are field-driven: diff, criteria,
  build results). Whether that's a gap is a Q6 question, not obviously a defect.
- Zero render failures across all 28 states — the contracts are null-safe against sparse states.
- Phase-4 routing: the five dedicated-path signals above + the analysis/interpretation full-set
  renders are the probe targets; decay probes only make sense against the interpretation prompt.
Naming note: the original "Lab"-suffixed class names and the "blanket render" coinage were
user-flagged as vague ([[feedback-no-invented-labels]]); renamed 2026-07-09 to
FrozenCycleObserver / FrozenStateFixtures / StubbedServices / AgentPromptRenderer /
SignalPerturbationSweepTest, resources at `/frozen-states/`.

## Phase 3d — multi-cycle replay results (2026-07-09, same evening)
`SignalTrajectoryReplay` + `SignalTrajectoryReplayTest` (same test package): N-cycle replays with
scripted role effects mirroring cited production emission sites (evolution fresh-diff hand-off,
evaluation accept ROLE_MERGE 1.0/1.0, reject+REPLAN, reject+LOW_CONFIDENCE). Real per cycle:
proposer+validator mode, gate, ranking, engine reinforce/attenuate, eligibility over
StubbedServices, end-of-cycle tick. Held constant: CycleExecutor post-hooks, coordinator
hydration (minimal per-cycle reset of merged/evolutionRanThisCycle/subgoalPromoted/projectDiff/
mutationState). Report: `mvn test -Dtest=SignalTrajectoryReplayTest` →
`target/signal-trajectory-report.md`. Findings:
- **MISSING_SYMBOL decay result (Q7, calibration candidate #1): a wide inert plateau.**
  Decay 0.05/0.10/0.25 shift the death cycle (20/11/5) yet flip NOT ONE decision over 15-20
  cycles in all three scenarios (rejection loop on the canonical grounding state, rejection loop
  on the recorded log-01 boundary state, cognitive-analysis loop). Only decay 0.5 (≈3-cycle
  lifetime, faster than the role-participation window) diverges (cognitive loop, cycle 4).
  Mechanism: the signal boosts planner+analysis EQUALLY (cannot reorder the pair), and their
  participation windows are bounded by their OWN role-signal lifecycle, identical across
  variants. Pinned as a standing test (0.25 vs 0.10 = inert). Calibration implication: within
  [0.05, 0.25] the constant is a free parameter in these dynamics — deprioritize tuning it;
  the earlier "p50 lifetime 30 cycles — is that right?" question dissolves unless a
  decay-sensitive channel is found.
- **Role-signal lifecycle dominates trajectory shape.** Executed-set transitions come from
  ROLE_* attenuation/decay (identity-script agents attenuate x0.9 per execution and their role
  dies while they still execute every cycle — analysis drops out at c7-c8, planner at c13-c14),
  not from the diagnostic signals' tails. In production, agents that produce context changes get
  reinforced instead — but roles that execute without progress genuinely erode this way.
- **Instrument lesson: a static variant stub livelocks the replay** — with hasAnyVariant
  frozen true, merge executes first every cycle, ends it, and the loop deadlocks in EVALUATE
  with everything blocked (a livelock shape reproduced from an unrealistic world, itself a
  demonstration the dynamics are real). StubbedServices.withVariantsFollowingAcceptance() models
  variants existing exactly while an ACCEPTED unmerged mutation is pending.
- Steady-progress scenario pins the healthy loop: evolve → evaluate → merge every cycle,
  ROLE_MERGE emitted per accept and consumed by its own 1.0 decay.

## Phase 3d addendum — decay grid broadened to EVERY signal (user steer, same night)
The user rejected restricting decay experiments to the census shortlist ("experiment instead if
other decays also would make sense"). Sweep grids (strength/age/decay) now run over every present
signal; the replay decay grid runs over every start-state signal in both scenarios, including
decay 0.0 (never dies) and 1.0 (one-shot). Results (report table):
- **The shortlist INVERTED: the decision-sensitive decay constants are the ROLE-identity
  signals, not the census's "decay-driven" diagnostics.** ROLE_ANALYSIS decay diverges decisions
  at EVERY grid point in BOTH scenarios (cycles 3-8; even 0.0-immortal changes late executed
  sets) — its decay directly sets analysis's participation window, and nothing re-emits it in
  these dynamics. ROLE_PLANNER: sensitive wherever planner is eligible (rejection loop, div 3-9),
  inert where it is not (cognitive loop). ROLE_MERGE: death cycle moves (11/3/2) but zero
  divergence — an ineligible role's rank does not matter. MISSING_SYMBOL: inert plateau
  [0.0-0.25], diverges only at one-shot lifetimes (0.5/1.0).
- **Reinforce ratchets decay DOWNWARD — re-emitted signals' decay cannot be perturbed from the
  state.** Signal.reinforce takes Math.min of the two decays, so any re-emission with a smaller
  constant permanently lowers it. This is why ROLE_EVALUATION/ROLE_EVOLUTION are fully
  decay-inert in the replay (the script's per-cycle re-emission washes the perturbed start value
  out via min()) — and it is a Q5-relevant design property in its own right: under reinforcement,
  decay only ever gets stickier, never faster. Varying EMISSION-site decays (REPLAN_REQUESTED,
  EVOLUTION_OCCURRED, LOW_CONFIDENCE re-emitted by scripts each cycle) needs script-emission
  variants — the stated follow-up experiment.

## Outcome probe + combination campaign (2026-07-10, both user-steered)
Two combination instruments, opposite weights:
- **SignalOutcomeProbeTest — the lightweight one (user: "much more lightweight harness probing
  for different outcomes").** One probe = one (strength, age) combination per signal -> ONE
  frozen-cycle observation -> outcome tuple (mode/executor/topRanked/halted). 2000 probes x 5
  states in ~10 s; report = outcome distribution + per-outcome factor-level enrichment matrix
  (target/signal-outcome-probe.md/.csv). Findings: pending-evaluation is value-proof (1 outcome
  in 2000 combos); stagnating has 3 outcomes (REPLAN gate); grounding has 9 with a ~20%
  "exec=null" dead-cycle region reached exactly when ROLE_EVOLUTION strength < the 0.10
  eligibility threshold; throttled/boundary have 17 outcomes each, structured precisely by the
  REPLAN_REQUESTED thresholds (0.05 / 0.5 / >=1.0-override). The probe recovers the eligibility
  code's threshold structure from data alone — good instrument-validity signal.
- **SignalCombinationReplayTest — the heavyweight one (user: "where are the several hundred
  combination runs?").** 500 seeded (strength+decay) combinations x 3 scenarios x 20-cycle
  replays x 5 states, split main-effect vs interaction-only against constituent single-factor
  runs (target/signal-combination-matrix.csv). Findings: interaction-only effects exist ONLY
  where margins are wide AND an ordering boundary is live (steady/grounding: 33 combos, all
  ROLE_MERGE>=2.0 AND ROLE_EVALUATION<=1.0 pair-ordering); pending-evaluation flattens the
  entire value space (0/500 diverge — mode reads fingerprints, gate ranks one role, evalFloor
  re-injection absorbs the rest); thin-margin states diverge ~100% via main effects alone.
  Dispositions refinement: plateau ROLE_MERGE boost (2.0) and ROLE_EVALUATION floor are a
  COUPLED pair — tune together or not at all.
Honest sampling limits (stated, not hidden): scripted scenarios only; 500-2000 draws of a
~10^8-cell space per state (main effects + gross pairwise, not the full surface); outcomes are
selection-layer facts, not run quality.

## What the instruments do NOT cover (route accordingly)
LLM-emitted signals (interpretation parser) out of scope until Phase 4. Pairwise ablations
(Q4 redundancy) not yet in the grid. TEST_REPAIR_FOCUS's focused-prompt path needs a fixture
with a test-shaped probe outcome (falls through otherwise). Replay scenarios are scripted
shapes, not agent emulations — decay results hold FOR THESE DYNAMICS; a scenario where
analysis/planner margins are thin AND nothing re-emits their triggers could still be
decay-sensitive (none found in three attempts). Replay runs jitter-0; production coin-flips
sub-jitter margins.
