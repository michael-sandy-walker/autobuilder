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
Commits: `d0bb748` (3 dead deletions), `a84df52` (RECOMBINATION_REQUEST + operator switches),
disposition-table commit (CDFB deletion + marker comments).

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
