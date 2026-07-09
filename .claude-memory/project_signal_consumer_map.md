---
name: project-signal-consumer-map
description: "T4-J Phase 1 (2026-07-09): source-grounded map of all 35 signal types — emitters/clearers/readers/fitness-membership/prompt-reach — with lifetime-model and disposition per signal. HEADLINES: COMPILE_DIAGNOSTIC_FED_BACK (a census 'tuning candidate') has ZERO consumers; 3 signals fully dead; 3 consumer-without-emitter (incl. 2 plausible operator switches); 3 emit-only markers whose real carrier is a context field; calibration shortlist = MISSING_SYMBOL, PLANNING_THROTTLED, ROLE_MERGE. PLUS: the LLM is itself a consumer AND emitter — analysis prompt renders all signal NAMES, interpretation prompt renders the FULL map and the LLM emits 3 signal types via InterpretationResponseParser; defect found: analysis #InjectionThrottle instruction conditions on strength the prompt never renders."
metadata:
  node_type: memory
  type: project
  originSessionId: 5661e26d-d787-4634-abae-4806a4d508e8
---

# Signal consumer map — T4-J Phase 1 (2026-07-09)

**Method.** Mechanical grep extraction over `src/main/java` (all `BasicSignals.<TYPE>` references,
deduped, classified as Emitter `Signal.of(...)` / Clearer `withoutSignal(...)` / Reader
`hasSignal|getSignal|hasStrongSignal|resolveSignalIfWeak|strength(...)`), joined with role-fitness-set
membership parsed from `BasicSignals.java` and prompt-reach (reader inside `protocol/`). Raw site list:
scratchpad `consumer_map_raw.txt` (regenerable; the extraction commands are in this note's history).
Census classes from `scripts/signal_boundary_report.py` over the 13-run A/B corpus + the 2026-07-09
post-deletion run (1163 joined cycles). Lifetime models per [[project-t4j-design-note]] Q5:
**event** (cleared on consumption) / **state** (persists while condition holds) / **fading**
(staleness is the semantics — decay IS the mechanism) / **identity** (ROLE_* reinforcement machinery).

## Summary table

| Type | E | C | R | Fitness sets | Prompt | Census | Lifetime model | Disposition lead |
|---|--:|--:|--:|---|---|---|---|---|
| AGENT_EXHAUSTED | 0 | 0 | 0 | – | – | never emitted | – | **DELETE — fully dead** |
| BACKGROUND_TICK | 0 | 0 | 0 | all 5 (!) | – | never emitted | – | **DELETE — dead, but referenced by every role set** |
| BUILD_BROKEN | 1 | 2 | 2 | analysis, evolution, merge | – | thin | state (decay 0.0 ✓) | leave; also read by engine's `buildBrokenAdjustment` |
| BUILD_FIX_LOCUS_STUCK | 1 | 1 | 0 | – | – | never emitted† | – | **emit-only marker** — real carrier is `ctx.sameFailureStreak()` field (planner's locus-stuck prompt block reads the FIELD, not the signal); delete signal or wire it |
| COMPILE_DIAGNOSTIC_FED_BACK | 1 | 0 | 0 | – | – | DECAY_DRIVEN | – | **⚠ ZERO consumers — decay tuning is provably pointless.** Emit-only marker for "diagnostics reached the prompt". Drop from calibration shortlist; delete or keep as observability marker |
| DIVERGENCE_REQUEST | 10 | 1 | 4 | evolution | Y | clear-dominant | event (cleared when fingerprint actually changes, T2-AO) | leave; healthiest event signal |
| EVOLUTION_FINGERPRINT_CHANGED | 1 | 0 | 2 | – | – | immortal | event-ish, never cleared | keep — NOT redundant with EVOLUTION_OCCURRED despite 1.00 co-occurrence: distinct readers (evaluator repeat-cap `EvaluationAgent:159`, `AutobuilderService:851`) |
| EVOLUTION_NOOP | 1 | 3 | 4 | – | – | clear-dominant | event (decay 0.0 + explicit clears ✓) | leave |
| EVOLUTION_OCCURRED | 1 | 0 | 1 | evaluation, merge | – | immortal | state-ish, never cleared | **most load-bearing signal in the corpus** (462 ablation flips — it is what lifts evaluation/merge after evolution writes); Q5 question: should it clear on merge instead of persisting forever? |
| EXPLORATION_REQUESTED | 0 | 0 | 0 | – | – | never emitted | – | **DELETE — fully dead** |
| GOAL_EXHAUSTED | 11 | 2 | 8 | – | – | never snapshot† | state/halt (decay 0.0 ✓) | leave (snapshot absence is the T1-DL early-return artifact) |
| IRREDUCIBLE_GAP | 1 | 0 | 0 | – | – | never emitted† | – | **emit-only marker** — real carrier is rehab plumbing (`irreducibleGoalIds` / goal metadata); delete signal or wire it |
| LOW_CONFIDENCE | 4 | 8 | 4 | analysis, evaluation, planner | – | clear-dominant | event | leave |
| MISSING_SYMBOL | 2 | 0 | 0 | analysis, planner | – | DECAY_DRIVEN | **fading — the archetype** | **calibration candidate #1**: consumed ONLY via fitness; decay alone decides how long it boosts analysis/planner (p50 lifetime 30 cycles — is that right?) |
| PLANNING_THROTTLED | 2 | 1 | 4 | analysis, planner | – | DECAY_DRIVEN | fading + readers (eligibility override, planner skip) | **calibration candidate #2**; two sites with different constants (0.15@0.6 skip-ratio vs 0.10@0.9 backlog-cap) — plausibly intentional (harder cap lingers longer), adjudicate before unifying |
| POTENTIALLY_COMPLETE | 1 | 1 | 1 | analysis | – | clear-dominant | event | leave |
| RECOMBINATION_REQUEST | 0 | 1 | 1 | evolution | – | never emitted | – | **consumer-without-emitter** (documented legacy/reserved; `EvolutionAgent:67` waits forever) — delete type+consumer or leave documented-reserved |
| RECURRING_DEFECT_DOMINANT | 1 | 1 | 0 | – | – | thin | – | emit-only marker (T2-EF's actuator is the injected subgoal, not the signal); near-100% strength-pinned |
| REPETITION_DETECTED | 4 | 9 | 9 | evaluation, evolution | Y | clear-dominant | event | leave; widely consumed, healthy |
| REPLAN_REQUESTED | 20 | 2 | 13 | analysis, planner | Y | clear-dominant | event | leave; largest emitter fan-in (20 sites) — worth a Q2 look at whether all 20 intents are distinct |
| REPLAN_THRASH_DETECTED | 1 | 0 | 2 | planner | – | never emitted† | event | situational, healthy wiring — awaiting field opportunity |
| RESTAGE_SUPPRESS | 0 | 0 | 1 | – | – | never emitted | state | **operator switch?** Only settable via `PUT /autobuilder/signals` (no code emitter); `DeferredRestageScheduler:86` honors it. Document as operator knob or delete |
| ROLE_ANALYSIS | 1 | 0 | 1 | analysis | – | immortal | identity | leave (see saturation note) |
| ROLE_EVALUATION | 3 | 0 | 1 | evaluation | – | immortal | identity | leave |
| ROLE_EVOLUTION | 5 | 0 | 1 | evolution | – | immortal | identity | leave |
| ROLE_MERGE | 2 | 2 | 3 | merge | – | DECAY_DRIVEN | identity, but mortal — only role signal without cycle-start reinforcement | **calibration candidate #3** + Q5 design question: is merge's missing cycle-start reinforcement intentional (merge only matters after an accept) or drift? The 1.0-vs-0.10 per-site decay split is intentional-looking (consume-now vs plateau-linger) |
| ROLE_PLANNER | 9 | 0 | 1 | planner | – | immortal | identity | leave |
| SCOPE_OUT_OF_SCOPE | 1 | 3 | 8 | planner | – | clear-dominant | state (suppression while mismatch holds) | leave |
| SCORE_PLATEAU | 1 | 9 | 2 | evolution | Y | never emitted† | event | situational, healthy wiring (9 clearers!) — awaiting a plateau run |
| STAGNATION | 2 | 10 | 9 | all 4 non-eval sets | – | clear-dominant | event | leave |
| STEERING_SUPPRESS | 0 | 0 | 1 | – | – | never emitted | state | **operator switch?** Same shape as RESTAGE_SUPPRESS; `CompileDiagnosticPromptBlock:98` honors it. Document or delete |
| SUBGOAL_EXHAUSTED | 2 | 9 | 5 | – | – | clear-dominant | event | leave |
| SUBGOAL_SCOPE_MISMATCH | 2 | 1 | 4 | merge | – | clear-dominant | state | leave |
| SYSTEM_PAUSED | 10 | 2 | 7 | – | – | never snapshot† | state/halt (decay 0.0 ✓) | leave (same snapshot artifact as GOAL_EXHAUSTED) |
| TEST_REPAIR_FOCUS | 1 | 1 | 1 | evolution | Y | never emitted† | state (re-derived each cycle) | situational, healthy wiring — needs a test-requiring-spec run |

† "never emitted" in the census means *in this corpus*; wiring exists in code — situational signals
awaiting their trigger, or (marked cases) structurally unobservable in snapshots.

## Findings (feed into the design note's questions)

**Q1 — dead weight, confirmed at source level (no field data needed):**
- `AGENT_EXHAUSTED`, `EXPLORATION_REQUESTED`: zero emitters, zero readers, zero fitness membership.
- `BACKGROUND_TICK`: zero emitters/readers but a member of **all five** role fitness sets — pure noise
  in every `getSupportedSignals()` set. Deleting these three is pure win, blocked on nothing.

**Q1/Q5 — emit-only markers (signal exists; the real decision carrier is elsewhere):**
- `COMPILE_DIAGNOSTIC_FED_BACK` — the big catch: listed as a census "decay-driven tuning candidate,"
  but nothing reads it. Its decay constant is dead arithmetic. Either delete, or explicitly rebrand as
  an observability marker (and then decay is still irrelevant — set 0.0 or leave, nothing changes).
- `BUILD_FIX_LOCUS_STUCK` — planner's locus-stuck prompt block reads `ctx.sameFailureStreak()` (field),
  not the signal. Also a **Q6 case-iii sighting**: steering that bypasses the signal system (the field is
  invisible to fitness/observability).
- `IRREDUCIBLE_GAP` — rehab plumbing carries the actual decision; signal is a marker.
- `RECURRING_DEFECT_DOMINANT` — T2-EF's actuator is the injected subgoal; signal is a marker.

**Q6 — consumers waiting on emitters that don't exist:**
- `RECOMBINATION_REQUEST` (documented legacy) — delete or leave reserved, but decide.
- `STEERING_SUPPRESS` / `RESTAGE_SUPPRESS` — plausible **intentional operator switches**: the only way
  to set them is `PUT /autobuilder/signals/{name}`, and live consumers honor them. If that's the intent,
  document it at the declaration; if not, they're dead consumers.

**Q7 — the calibration shortlist SHRINKS from 4 to 3:** `MISSING_SYMBOL` (purest fading-relevance case,
fitness-only consumption), `PLANNING_THROTTLED` (fading + real readers; per-site split to adjudicate
first), `ROLE_MERGE` (identity-but-mortal; per-site split looks intentional; the real Q5 question is the
missing cycle-start reinforcement). `COMPILE_DIAGNOSTIC_FED_BACK` is out (no consumers).

**Q2 note (from Phase 0, recorded here for adjacency):** role signals spend 80–93% of snapshots pinned
at MAX_STRENGTH=3.0 — reinforcement saturates the cap, erasing strength differences between the top
roles; ranking then hinges on the recency term and unpinned signals. `EVOLUTION_OCCURRED` is the single
most decisive signal in recorded rankings (462/1163 definite ablation flips). Sub-jitter coin-flips:
14.1% of rankings.

**Natural-experiments result (Phase 0 §6):** 79 of 153 repeated signal-type-sets produced different top
roles — the type set alone underdetermines the decision (strengths/ages matter), which is exactly why
the frozen-state lab (Phase 3) is needed; layer-0 alone cannot finish the job.

## The LLM as a signal consumer AND emitter (added 2026-07-09, user-prompted — the table above undercounts)

The `Prompt` column above only counted **dedicated** signal reads inside `protocol/`. The LLM actually
participates in the signal loop through three further channels:

1. **Full-set presence render (analysis):** `AnalysisProtocolContract:95` renders
   `ctx.signals().keySet().toString()` into a `--- SIGNALS ---` prompt block — the analysis LLM sees the
   NAME of every present signal, every cycle. Consequence for Phase 3c: ANY perturbation that changes
   which signals exist changes the analysis prompt; effectively all signals are weakly prompt-reaching.
2. **Full-state render (interpretation):** `InterpretationProtocolContract:193` renders the whole
   `ctx.signals()` map (full `Signal` toString incl. strength/decay/age) into the interpretation prompt.
   The interpretation LLM is the one consumer that sees signal STRENGTHS as text.
3. **LLM as EMITTER:** the interpretation prompt instructs the model to emit signal lines ("Emit
   REPLAN_REQUESTED when the same subgoal/mutation pattern repeats… Emit LOW_CONFIDENCE when evaluation
   scores are consistently low… Emit DIVERGENCE_REQUEST when the evolution direction shows no progress"),
   and `InterpretationResponseParser:41-46` converts those lines into real `Signal` objects
   (`REPLAN_REQUESTED`, `LOW_CONFIDENCE`, `DIVERGENCE_REQUEST` — each with fixed strength/decay). For
   these three types, one emission site's ENTIRE decision logic is the LLM's judgment, steered by prose
   emission criteria in the prompt — those prose criteria are as much "signal system configuration" as
   any decay constant, and belong in scope for the audit (they are the natural Phase-4 probe target).
4. **Instructed reactions:** `AnalysisProtocolContract` (#InjectionThrottle rule) and
   `PlannerProtocolContract:134` (repetition/stagnation keyword rule) instruct the model how to REACT to
   named signals — behavioral consumption encoded in prose, invisible to the code-level reader count.

**⚠ Defect-class finding — FIXED same day (user-directed):** the analysis prompt's #InjectionThrottle
instruction conditions on "REPETITION_DETECTED or STAGNATION … present at significant strength", but the
block rendered only `keySet()` — names WITHOUT strengths; the model was asked to judge a quantity it
could not see. Fix: `AnalysisProtocolContract.signalsBlock` now renders `NAME=strength` pairs sorted by
strength descending (strength only — the sole field the instruction references; full Signal toString
would waste tokens). Two regression tests pin the render and the `(none)` fallback. Note for run
analysis: analysis-LLM behavior around #InjectionThrottle before this fix was de-facto presence-based.

**Map corrections implied:** `LOW_CONFIDENCE`, `REPLAN_REQUESTED`, `DIVERGENCE_REQUEST` gain "LLM
(interpretation)" as an emitter class; every signal gains the analysis/interpretation full-set renders as
weak readers; `REPETITION_DETECTED`/`STAGNATION` gain instructed-reaction consumption. The Phase-3c
prompt-diff driver must include the analysis + interpretation prompts for EVERY signal perturbation, not
just the dedicated-read signals.
