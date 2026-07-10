---
name: project-signal-dispositions
description: "T4-J final deliverable (2026-07-09): decided disposition per signal type — the audit's answer sheet. 5 deleted (AGENT_EXHAUSTED, EXPLORATION_REQUESTED, BACKGROUND_TICK, RECOMBINATION_REQUEST, COMPILE_DIAGNOSTIC_FED_BACK); 2 documented operator switches; 3 rebranded observability markers with truthful comments; 25 leave (with per-signal evidence); calibration outcome = the census shortlist dissolved (diagnostic decays sit on inert plateaus; the decision-sensitive decay surface is the ROLE-identity family, flagged not tuned). Open items: Q3 analysis/planner tie-break, Q6 evaluation-prompt signal-blindness, ROLE-decay live confirmation."
metadata: 
  node_type: memory
  type: project
  originSessionId: a3509f13-fdba-438b-97a0-874862704b9b
---

# T4-J signal dispositions (2026-07-09)

The audit's deliverable: a decided outcome per signal type, with the evidence chain.
Instruments and measurements: [[project-t4j-perturbation-lab]] (Phases 3/3c/3d),
[[project-signal-consumer-map]] (Phase 1), `scripts/signal_boundary_report.py` (Phase 0).
Commits: `d0bb748` (3 dead deletions), `a84df52` (RECOMBINATION_REQUEST + operator switches +
emission-decay result), `c08d86f` (Q4 pairwise), `3d25047` (CDFB deletion + marker comments +
this table), `ff9a23c` (Phase 2 store).

## Deleted (5)

| Signal | Why | Commit |
|---|---|---|
| AGENT_EXHAUSTED | zero emitters, readers, set memberships | d0bb748 |
| EXPLORATION_REQUESTED | zero emitters, readers, set memberships | d0bb748 |
| BACKGROUND_TICK | zero emitters/readers; noise in all five fitness sets | d0bb748 |
| RECOMBINATION_REQUEST | no emitter; one consumer waiting forever (documented-legacy reservation) | a84df52 |
| COMPILE_DIAGNOSTIC_FED_BACK | zero readers; duplicated a RICHER behaviour event (evolutionSteeringCompileDiagnosticsFedBack); 0 decision flips in the sweep | (this commit) |

## Documented operator switches (2) — emitter-less BY DESIGN
`STEERING_SUPPRESS`, `RESTAGE_SUPPRESS`: settable only via `PUT /autobuilder/signals/{name}`;
live consumers honor them. Declaration comments no longer attribute them to the deleted
meta-agent. (User delegated; my call, reviewable.)

## Rebranded observability markers (3) — comments now truthful
`BUILD_FIX_LOCUS_STUCK` (decision carrier = ctx.sameFailureStreak fields),
`IRREDUCIBLE_GAP` (carrier = rehabilitation plumbing/goal metadata),
`RECURRING_DEFECT_DOMINANT` (actuator = BuildBlockGate consulting RecurringDefectTracker).
All three: consumers are signal snapshots + the analysis prompt's signal render; their old
comments claimed code consumption that never existed. Kept because the observability and the
weak LLM-visibility channel are real; deleting them would blind snapshots to these moments.

## Leave — identity family (5)
- `ROLE_PLANNER`, `ROLE_ANALYSIS`: **the actually decision-sensitive decay constants**
  (3d replay: ROLE_ANALYSIS diverges at every grid point in both scenarios — its decay IS
  analysis's participation window; ROLE_PLANNER wherever planner is eligible). Left at current
  values; flagged as the ONLY Q7 surface worth field-testing. Any future "analysis runs too
  long/short" steering should tune these, through layers 5-6, not offline-only.
- `ROLE_EVOLUTION`, `ROLE_EVALUATION`: re-emitted every relevant cycle; decay ratchet-immune
  (reinforce=min) and measured inert at both state and emission sites. Leave.
- `ROLE_MERGE`: per-site 1.0/0.10 split confirmed intentional (the plateau boost's 2.0 sits
  exactly at merge's rank-takeover boundary); missing cycle-start reinforcement is harmless —
  eligibility (variants pending), not rank, gates merge (replay: death cycle moves, zero
  decision divergence). Leave.

## Leave — event/state family (17)
`EVOLUTION_OCCURRED` (most load-bearing signal on record; 462 corpus flips + 13/15 sweep-state
flips; Q5 note: never cleared — persists as "work exists"; no observed harm) ·
`EVOLUTION_FINGERPRINT_CHANGED` (selection-layer inert BY DESIGN — consumers are the evaluator
repeat-cap and AutobuilderService; correctly in no fitness set; NOT redundant with
EVOLUTION_OCCURRED despite 1.00 co-occurrence) · `LOW_CONFIDENCE` · `STAGNATION` ·
`DIVERGENCE_REQUEST` (healthiest event signal; T2-AO verified-consumption) ·
`REPETITION_DETECTED` · `REPLAN_REQUESTED` (largest fan-in, 20 sites; dedicated planner-prompt
path; NOTE: whether all 20 intents are distinct remains an open Q2 observation, no action) ·
`SUBGOAL_SCOPE_MISMATCH` (pre-existing TODO in the merge blocker about single-cycle survival
stands as a note) · `SCOPE_OUT_OF_SCOPE` · `SCORE_PLATEAU` (9 clearers, healthy; boost
calibration confirmed) · `REPLAN_THRASH_DETECTED` · `SUBGOAL_EXHAUSTED` · `GOAL_EXHAUSTED` ·
`SYSTEM_PAUSED` (halt semantics, decay 0.0 correct) · `POTENTIALLY_COMPLETE` ·
`EVOLUTION_NOOP` · `BUILD_BROKEN` (state, decay 0.0 correct; engine fitness adjustment).

## Leave — fading-relevance family (3)
- `MISSING_SYMBOL`: presence/strength is the lever (14/15 ablation flips); decay is a free
  parameter on [0.05, 0.25] (3d: zero decision divergence in three scenarios) — calibration
  DISSOLVED, not answered with a number. Leave 0.25.
- `PLANNING_THROTTLED`: the block-planner-while-boosting-planner duality is COHERENT under the
  REPLAN_REQUESTED≥1.0 override path (boosted rank means the planner runs first the moment the
  override unblocks it) — membership stays. Two-site constant split (0.15@0.6 vs 0.10@0.9)
  reads as intentional (harder cap lingers longer). Leave.
- `TEST_REPAIR_FOCUS`: state-like, re-derived each cycle, decay 0.0 correct. Leave.
  (Needs a test-shaped fixture before the lab can exercise its focused-prompt path — noted.)

## Calibration outcome (Q7) — the honest summary
The census shortlist ("MISSING_SYMBOL, PLANNING_THROTTLED, ROLE_MERGE decays need tuning")
DISSOLVED under measurement: diagnostic-signal decays sit on decision-inert plateaus; re-emitted
signals are ratchet-immune to decay entirely; the only decay constants that steer decisions are
ROLE_ANALYSIS/ROLE_PLANNER — and those are flagged for live confirmation, not tuned offline,
because their effect (role participation windows) is exactly what field runs exist to validate.
No constant was changed on offline evidence alone. TuningParameterStore (Phase 2) therefore
wires the ROLE-decay sites, not the census shortlist.

## Phase 2 wiring (shipped `ff9a23c`)
`TuningParameterStore` (Environment-backed, `autobuilder.tuning.<name>`, cached per run, write
path a loud stub). Wired: the ticket's three score constants (merge-acceptance-floor into
DeterministicScoreHeuristic + EvaluationInterpreter; subgoal-closure + plateau-override-floor
into SubGoalFulfillmentHeuristic) and the audit-measured decay authorities
(signal-decay.role-analysis.seed / role-planner.seed in CycleStartSignalSeeder;
signal-decay.reinforce-default in the engine — the reinforce-min ratchet makes seed+floor the
effective decay constants). Census-shortlist sites deliberately NOT wired (measured inert).

## Phase 4 outcome — resolved by dissolution
The probes existed to measure model-response deltas for calibration finalists; the audit ended
with zero proposed constant changes, so no probe had a decision to inform. LLM-facing signal
surfaces (analysis NAME=strength render, interpretation full-map render + prose emission
criteria) stay documented as probe targets if ever changed.

## Phase 5 outcome — field confirmation (2026-07-09 22:27-22:54)
No A/B (nothing changed to compare); the field question was "does HEAD with 5 signals deleted +
store wired run cleanly end-to-end". PREFLIGHT: PASS — GOAL_FULFILLED in ~27 min (cap 60),
22 merges, smoke 17/23, artifact builds+boots, corpus /tmp/preflight_runs/2026-07-09_22-27-12.
Run shape: healthy convergence through the completion edge (coverage review injected 1 missing
feature; package gate correctly blocked root closure on missing main class and injected the fix
item; three subgoals closed via output verification after exhaustion). Smoke 17/23 is below the
recent best runs (22-23/23) — runs are variant by design, single run, no regression claim; noted
as a data point only. Post-run observations for later triage (not signal-related): planner asked
twice to activate a COMPLETED subgoal ("scope repair" reason, guard correctly refused) — stale
scope pointer smell.

## Combination campaign (user-prompted; commit 22a30ac) — the multi-factor check on this table
500 seeded combinations (all signals' strength+decay at once) x 3 scenarios x 20 replay cycles,
each split into main-effect vs interaction-only vs the constituent single-factor runs. Matrix:
target/signal-combination-matrix.csv. Findings: rejection/cognitive ~100% divergence, ALL
explained by constituent main effects; steady 185/500 with 33 interaction-only combos whose
structure is a PAIR-ORDERING effect — every cycle-2 diverger has ROLE_MERGE strength >=2.0 AND
ROLE_EVALUATION strength <=1.0 (merge outranks evaluation, executes first), merge=2.0 with
eval>=1.0 never diverges; ROLE_EVOLUTION strength 0.05 diverges 115/115 (too weak to lead).
Table refinement: deletions/lifetime findings unaffected (presence/consumer facts); calibration
addendum — the plateau ROLE_MERGE boost (2.0) and the ROLE_EVALUATION floor (0.5 re-injection)
are a COUPLED pair; if either is ever tuned, tune them together.

## Successor ticket
**T4-BZ (filed 2026-07-10)**: signal-system re-architecture — signals as derived readout over
mechanism events (messages / derived views / attention windows). The architectural conclusion
this audit earned; the audit's instruments are its migration equivalence harness. This table's
"leave" dispositions are leave-AS-IS-until-T4-BZ, not leave-forever.

## Open questions carried forward (notes, not tickets)
- **Q3/Q6 — analysis↔planner exact ties** (fully-shared common set members + saturation): if
  their order ever needs to matter, the fix is a set-differentiating signal or explicit
  tie-break; no constant can express it. Await a concrete field need.
- **Q6 — evaluation prompt is signal-blind**: the evaluator LLM sees no signal state. Possibly
  correct (its inputs are field-driven: diff, criteria, build results); flag for a Phase-4
  probe only if an evaluation-quality gap points here.
- **Q6 case-iii — steering outside the signal system**: ctx.sameFailureStreak (and friends)
  steer prompts/injectors invisibly to fitness. Documented at the declarations; systemic
  decision (promote fields to signals vs accept two channels) deferred until it costs something.
- **LLM emission sites** (interpretation parser emits REPLAN_REQUESTED/LOW_CONFIDENCE/
  DIVERGENCE_REQUEST): prose emission criteria are signal-system configuration; Phase-4 probe
  target when touched.
