---
name: project-t4j-design-note
description: "T4-J execution plan (2026-07-09) — an OPEN signal-system audit whose purpose is: which signals should decay and which should not (lifetime semantics: event-like/state-like/fading-relevance), which are obsolete and which are MISSING — and only then, calibration of the survivors. Via layered cheap-first experimentation: retrospective corpus mining (layer 0, seeded with real findings) → source-grounded consumer map + emission-site audit → frozen-state deterministic perturbation lab incl. ablation probes → prompt-diff + controlled single LLM-call probes → live pause-and-poke → one confirming field run. Key facts: decay constants are per-EMISSION-SITE not per-type (ROLE_MERGE has a 10x split); 4 decay-driven candidates not 2; 31% of rankings sit within one decay step of flipping, 84% of those on the analysis-planner boundary; ~10% of orderings are sub-jitter coin-flips. Per-signal outcomes may be tune / delete / merge / redesign-consumption — not tuning-only. READ BEFORE any signal-tuning work."
metadata:
  node_type: memory
  type: project
  originSessionId: 5661e26d-d787-4634-abae-4806a4d508e8
---

# T4-J design note — signal tuning as layered, cheap-first experimentation (2026-07-09)

**Origin.** After the T4-AI-2+5 deletion made the signal-driven fitness pass the sole orchestrator, T4-J
(tuning store + signal-decay tuning) became the natural next structural item. Three user steers shaped
this plan away from the naive "tune 2 constants, validate with field runs" scope:
(1) *"only 2 decaying signals feels off — what about the rest?"* → the full 35-type census below;
(2) *"map what caused a signal to what actions it drives"* → the consumer map (Phase 1);
(3) *"isn't there a more elegant approach than runs after runs — freeze a situation, adjust values, observe"*
plus *"mine the recorded runs first"* and *"probe the LLM directly, controlled, not via whole runs"*
→ the layered experiment stack (Phases 0/3/4);
(4) *"we also want to find out if signals have uses at all, are oversteered, etc. — this is an open
experiment"* → this is an **audit, not a calibration exercise**. The question set (§2b) treats
"tune the constant" as only one of several per-signal outcomes; "delete it — it does nothing" and
"three signals redundantly steer this one decision" are equally first-class findings, aligned with
[[project-three-disciplines-lens]] (delete-subsumed) and the introspection north star (a system that
knows what its own signals actually do).
The unifying principle: **the signal→decision layer is
deterministic — enumerate it; spend statistics (and wall-clock) only where model variance genuinely lives.**

## 1. Evidence base (all measured 2026-07-09 on the 13-run / ~1180-cycle A/B corpus, `/tmp/t4bo_aggregate/`)

**Census of all 35 signal types** (not ~20 as older notes said), by observed lifetime behavior:

- **Decay-driven — the real tuning candidates (4, not 2):**
  `MISSING_SYMBOL` (22 deaths, 100% decayed) · `COMPILE_DIAGNOSTIC_FED_BACK` (12, 100%) ·
  `PLANNING_THROTTLED` (51, 65% decayed) · `ROLE_MERGE` (58, 55% decayed).
  The earlier "only MISSING_SYMBOL + COMPILE_DIAGNOSTIC_FED_BACK" framing undercounted: at the full
  13-run sample, PLANNING_THROTTLED and ROLE_MERGE resolved from "genuine mixes, look closer" into
  decay-dominant.
- **Clear-dominant — decay constant confirmed inert, do NOT tune (11):** `DIVERGENCE_REQUEST` (170 deaths,
  0% decayed), `REPETITION_DETECTED` (165, 0%), `STAGNATION` (139, 0%), `REPLAN_REQUESTED` (128, 0%),
  `EVOLUTION_NOOP` (100, 0%), `SUBGOAL_EXHAUSTED` (91, 0%), `SCOPE_OUT_OF_SCOPE` (65, 0%),
  `SUBGOAL_SCOPE_MISMATCH` (39, 0%), `POTENTIALLY_COMPLETE` (15, 0%), `LOW_CONFIDENCE` (77, 9% decayed),
  plus thin-n `BUILD_BROKEN` (2) / `RECURRING_DEFECT_DOMINANT` (1). Their lifetime lever is consumption
  logic, not decay math — out of scope here.
- **Immortal — present ~always, never die (6):** `ROLE_PLANNER`/`ROLE_EVOLUTION`/`ROLE_EVALUATION`/
  `ROLE_ANALYSIS` (100% of 1147 snapshots) and `EVOLUTION_OCCURRED`/`EVOLUTION_FINGERPRINT_CHANGED` (99%) —
  re-emitted/reinforced faster than any decay. Decay tuning affects their *strength trajectory between
  reinforcements* (and hence ranking margins), not their existence. Notable: **ROLE_MERGE is the only
  role-identity signal that ever dies** — the other four are kept alive by cycle-start reinforcement it
  doesn't get.
- **Never emitted in this corpus (13):** `SCORE_PLATEAU`, `TEST_REPAIR_FOCUS`, `IRREDUCIBLE_GAP`,
  `STEERING_SUPPRESS`, `RESTAGE_SUPPRESS`, `BUILD_FIX_LOCUS_STUCK`, `AGENT_EXHAUSTED`,
  `EXPLORATION_REQUESTED`, `REPLAN_THRASH_DETECTED`, `RECOMBINATION_REQUEST` (documented legacy/reserved),
  `BACKGROUND_TICK`, and `GOAL_EXHAUSTED`/`SYSTEM_PAUSED` — the last two are an observability artifact,
  not absence: halted cycles skip the engine via the T1-DL early return, so the snapshot (taken at
  fitness-ranking time) never sees them. The rest are situational signals whose trigger didn't arise in
  these 13 runs — **unobserved ≠ inert**; they are not-yet-tunable, and must be flagged as such rather
  than silently ignored.

**Decay constants are per-EMISSION-SITE, not per-type — and sites already disagree:**

| Signal | Site (source tag) | strength | decayPerCycle |
|---|---|---|---|
| MISSING_SYMBOL | planner-grounding (`PlannerAgent:117`) | 1.0 | 0.25 |
| MISSING_SYMBOL | analysis-grounding (`AnalysisAgent:76`) | 1.0 | 0.25 |
| COMPILE_DIAGNOSTIC_FED_BACK | evolution-prompt (`EvolutionAgent:457`) | 1.0 | 0.20 |
| PLANNING_THROTTLED | planner-skip-ratio (`PlannerAgent:259`) | 0.6 | 0.15 |
| PLANNING_THROTTLED | goal-backlog-cap (`PlannerAgent:267`) | 0.9 | 0.10 |
| ROLE_MERGE | evaluation-accepted (`EvaluationAgent:427`) | 1.0 | **1.0** |
| ROLE_MERGE | score-plateau (`RuleBasedDeliberationProposer:201`) | 2.0 | **0.10** |

The ROLE_MERGE 10x split is very likely intentional (consume-now vs linger-awhile semantics). Consequence:
**there is no single "ROLE_MERGE decay constant" to tune — the tunable unit is the emission site (or a
named intent), not the SignalType.** Corpus-wide there are ~90 `Signal.of(BasicSignals.*)` emission sites;
constant distribution: 0.10×~30, 0.0×23, 0.05×13, 0.20×16, 0.25×4, 0.15×3, plus outliers 1.0/0.5/0.12.

**Retrospective decision-boundary findings (layer-0 seed, 638 rankings from the 7 OFF-arm runs):**
- Margin top-1 vs top-2: p10=0.004, p25=0.197, p50=0.820, p75=2.819.
- **31% of rankings (198/638) sit within 0.25 of flipping** — one typical decay step. Tuning is NOT inert.
- **84% of those close calls (167/198) are on the analysis↔planner boundary.** Evolution is essentially
  never in a close race (4 occurrences) — decay tuning will re-time analysis-vs-planner, not evolution.
- **~10% of orderings are sub-jitter (margin < explorationJitter=0.01) — effectively coin-flips today.**
  For those cycles the engine has no real preference; no constant will give it one. A standing question:
  is coin-flipping the right behavior there, or should a deliberate tie-break exist?
- Data availability: `agent_fitness_ranked` (score + per-component breakdown per role) exists in OLD
  corpora too (e.g. run 24); `signal_snapshot` (raw strengths, pairable with rankings per cycle) exists
  from 2026-07-08 (`e8a3d8d`) onward.

## 2. Design principles

1. **Determinism first.** `RuleBasedDeliberationProposer` (mode) → `DeliberationExecutionGate` (allowed
   roles) → fitness ranking → eligibility → tick/decay arithmetic contain no LLM call. With
   `explorationJitter=0` the whole selection layer is a pure function of `CycleContextData`. Enumerate it;
   never sample it.
2. **Statistics only at the model boundary.** LLM-response variance is the only irreducible noise; it is
   probed with controlled single calls (temp 0.0, repeated ≥3×), and whole field runs exist solely to
   confirm finalists, never to search.
3. **Site-level tuning, intent-named.** Parameters are named per emission intent, not per SignalType
   (the ROLE_MERGE lesson). Naming: `autobuilder.tuning.signal-decay.<type>.<source-tag>`.
4. **Relative justification for chosen values** ([[feedback-relative-thresholds]]): a tuned decay should
   be expressible as "survives K typical agent-turns" or "dies within one subgoal's median cycle span" —
   an environmental fact — not a fresh magic constant.
5. **No autonomous write path.** T4-J's own scope keeps `set()` a stub; every experiment here is
   human-triggered. Self-tuning is a later, separate decision.
6. **Gate check passed:** [[feedback-stabilize-before-tuning-signals]] (2026-06-24) held signal-dynamics
   tuning until convergence was reproducible. It now is (fulfilled runs routine; 22–23/23 smoke twice;
   substrate stable post-T4-AI-deletion). The hold is lifted for *measured* tuning — blind tuning stays out.

## 2b. The question set (what the audit actually asks, per signal / per emission site)

Every signal gets run through these questions; the phases below are the instruments that answer them.
The honest possible outcomes per signal are: **tune** (constant is load-bearing and miscalibrated) ·
**leave** (load-bearing and fine) · **delete** (demonstrably does nothing — emitted but changes no
decision anywhere, or never emitted and its trigger is vestigial) · **merge** (redundant with another
signal steering the same decisions) · **change lifetime model** (decay↔clear-only↔permanent — the signal
is right, its expiry mechanism is wrong) · **redesign consumption** (the signal is fine; what reads it is
the problem) · **not-yet-answerable** (never observed; needs a trigger fixture or more corpus). And one
outcome at the SYSTEM level rather than per-signal: **add** — a missing signal surfaced by Q6.

- **Q1 Usefulness (ablation):** if this signal were absent in state S, does ANY decision change — mode,
  gate, ranking, eligibility, prompt content? A signal that survives ablation across the whole state
  library with zero decision deltas is dead weight regardless of how carefully it is emitted and cleared.
  (Suspects from the census already: `RECOMBINATION_REQUEST` (documented legacy), `BACKGROUND_TICK`,
  possibly `EXPLORATION_REQUESTED`; and the whole reinforce/attenuate/decay machinery is plausibly
  vestigial for the always-cleared family.)
- **Q2 Oversteering (dominance & saturation):** how often does one signal's contribution alone determine
  the top rank no matter what the others say? Are there decisions where several signals redundantly push
  the same direction (any one of them would suffice)? Are strengths saturating at MAX_STRENGTH=3.0 so
  that differences stop mattering (visible in the corpus: many signals pinned at 3.00)? Oversteering also
  covers conflict: simultaneous signals pushing opposite directions where the outcome is decided by
  arbitrary constants rather than intent.
- **Q3 Understeering (indifference):** the ~10% sub-jitter coin-flips — decisions the signal system
  currently expresses no preference about. Correct indifference, or a missing signal/tie-break?
- **Q4 Redundancy & co-occurrence:** which signals always travel together (from `signal_snapshot`
  co-occurrence)? Perfectly co-occurring signals steering the same consumers are one signal wearing two
  names.
- **Q5 Lifetime semantics — should this signal decay AT ALL?** A design question the census begs, never
  answered explicitly when the signals were written. Three legitimate lifetime models: **event-like**
  (consumed and cleared by whoever acts on it — decay is at most a backstop against a missed clear),
  **state-like** (persists while a condition holds, cleared when it ends — decay is wrong; a decayed-away
  BUILD_BROKEN while the build is still broken would be a bug), **fading-relevance** (staleness is the
  semantics — decay IS the mechanism, e.g. MISSING_SYMBOL/COMPILE_DIAGNOSTIC_FED_BACK, whose diagnostic
  grows stale as the code changes). For each signal, assign the intended model from the Phase-1 map, then
  check the observed lifetime data against it. The always-cleared family's inert decay constants then
  resolve into one of two verdicts: "decay is a correct backstop, leave it" or "decay is a lie about the
  semantics, set 0 and rely on the clearer" — and the backstop case needs the follow-up "what happens
  when the clearer misses? has that ever occurred in the corpus?"
- **Q6 Completeness — which signals are MISSING?** The constructive counterpart to Q1's deletion. Three
  detection channels: (i) Q3's understeered decisions (the system has no preference where it plausibly
  should); (ii) consumer-map orphans — consumers that read a signal which (nearly) never fires, where the
  right fix may be a missing/broken EMITTER rather than an obsolete consumer; (iii) steering that bypasses
  the signal system entirely — hardcoded condition checks inside agents/gates that behave like signals but
  are invisible to fitness ranking, observability, and this audit (candidates surface naturally while
  building the Phase-1 map).
- **Q7 Calibration (the original T4-J question):** for the load-bearing, correctly-lifetimed survivors of
  Q1–Q6 only — are the strength/decay constants right, per emission site, justified relative to
  environmental facts?

Q7 is deliberately LAST: tuning a constant on a signal that Q1 would have deleted, Q4 would have merged,
or Q5 would have switched to clear-only is wasted precision. The phases feed the questions in this order.
**In one sentence, the ticket's purpose: determine which signals should decay and which should not, which
are obsolete and which are missing — and only then, what the surviving constants should be.**

## 3. The layer stack (cheap → expensive; search lives at the top)

| # | Layer | Cost/probe | Answers | Infrastructure |
|---|---|---|---|---|
| 0 | Retrospective corpus mining | free | Where are the decision boundaries in real recorded states? Which signals are immortal/never-emitted? | existing corpora + `agent_fitness_ranked`/`signal_snapshot`; extend `scripts/signal_lifetime_report.py` |
| 1 | Frozen-state deterministic sweep | ms | Does value X change ANY decision (mode/gate/order/eligibility/ticks-to-death) in this exact state? | new harness; `explorationJitter=0`; states from snapshots + fixtures |
| 2 | Prompt-diff | free | Does the perturbation even REACH the model? (Protocol contracts are pure functions of context.) | render + diff, no LLM |
| 3 | Multi-cycle mocked replay | s | Does it change the N-cycle trajectory (reinforce/attenuate feedback, gate re-arming)? | existing fixture-scenario machinery (run-20 fixture, `CycleExecutorGoalLoopTest` pattern) |
| 4 | Controlled single LLM calls | s–min | What does the model DO differently with the changed prompt? | layer-2 eval pattern (temp 0.0) + T4-BN-3 logging-proxy pattern; ≥3 repeats |
| 5 | Live pause-and-poke | min | Does the live system behave as layers 1–3 predicted? | existing REST: `POST /autobuilder/pause`, `PUT /signals/{name}`, `POST /cycle`, `/resume` |
| 6 | Field run confirm | ~1h | Does the finalist improve quality/time/errors end-to-end? | `preflight.sh live` + `smoke23.sh` + error census |

## 4. Phased execution plan

### Phase 0 — Retrospective mining report (no production code; pure analysis)
Extend the T4-BO analyser (or a sibling script, `scripts/signal_boundary_report.py`) to produce, over ALL
available corpora (including `backup/*/logs`, deduped per [[feedback-verify-population-before-claim]]):
- The 35-type census table (auto-generated version of §1's hand census; refreshes as corpora grow).
- Margin distributions per role-pair; boundary-density per corpus; sub-jitter fraction.
- For post-`e8a3d8d` corpora: joined `signal_snapshot`×`agent_fitness_ranked` per-cycle records → for each
  of the 4 candidate signals, the distribution of "Δstrength needed to flip the ranking" — the direct
  empirical decision-boundary distance.
- Natural experiments: cycles with near-identical signal states but different outcomes (exist or not? —
  answers how far layer-0 alone can carry us).
- **Audit queries (Q2/Q4):** dominance stats from the ranking breakdowns (how often does the top role's
  margin exceed the combined contribution of all non-dominant signals — i.e., one signal decided it
  alone); strength-saturation rates (fraction of snapshot entries pinned at 3.0 per type); co-occurrence
  matrix over `signal_snapshot` (candidate redundant pairs = high co-occurrence + same consumer set per
  the Phase-1 map).
**Deliverable:** one report; classifies every emission site as boundary-adjacent / plateau / unobserved,
plus the dominance/saturation/co-occurrence tables feeding Q2/Q4.
**DoD:** report generated + committed; candidate shortlist named with numbers.

### Phase 1 — Consumer map + emission-site audit (source-grounded, the "why" layer)
For each of the 35 types, from code reading (grep-driven, file:line cited):
- **Emitters:** every `Signal.of(...)` site with strength/decay/source-tag (the §1 table, completed for
  all types).
- **Clearers:** every `withoutSignal(...)` site (~70 exist) + which agent/service owns it.
- **Consumers:** fitness (via `getSupportedSignals` role sets in `BasicSignals`), eligibility
  (`ExecutionEligibilityExplainer`), mode rules (`RuleBasedDeliberationProposer`), detectors
  (`GoalExhaustionDetector` Rule A etc.), heuristics (`BuildFixAcceptanceHeuristic`, `BuildFixEscalator`),
  **and prompt blocks** — the subset of signals that alter LLM input (`DIVERGENCE_REQUEST`→divergent path,
  `REPETITION_DETECTED`→repeated flag, `TEST_REPAIR_FOCUS`→focused prompt,
  `COMPILE_DIAGNOSTIC_FED_BACK`→diagnostics block, `BUILD_BROKEN`→build blocks, …complete this list).
- **Site-disagreement adjudication:** for each type with differing per-site constants, decide from the
  surrounding code whether intentional (two intents → two parameters) or drift (unify).
**Deliverable:** the map as a committed doc; it is simultaneously (a) the justification layer for any
tune, (b) the parameter-naming source for Phase 2, (c) the router deciding which signals need layer-4
LLM probes at all, (d) the resolution of the 13 never-emitted types (structurally-exempt vs
situational-awaiting-opportunity).
**DoD:** all 35 types mapped; every never-emitted type dispositioned; tunable-unit decision recorded.

### Phase 2 — T4-J read path (the enabling build; needed for layers 5–6 only, NOT for 0–4)
As originally filed, plus the decay extension:
- `TuningParameterStore.get(name)` → DB first, yml fallback `autobuilder.tuning.<name>`, code default last.
- Original scope: `score.merge-acceptance-floor` / `score.subgoal-closure` / `score.plateau-override-floor`
  wired into `DeterministicScoreHeuristic`, `SubGoalFulfillmentHeuristic`, `EvaluationInterpreter`.
- Extension: the audited candidate emission sites (Phase 1's naming) read their `decayPerCycle` (and
  possibly strength) through the store. Wire ONLY the shortlisted candidates — not all ~90 sites
  ([[feedback-ship-full-scope]] applies to the capability, not to speculative mass-migration).
- Write path stays a stub per the ticket.
**DoD:** store + tests + candidate sites wired; a value changed in yml provably changes live behavior
(one pause-and-poke check); suite green.

### Phase 3 — The perturbation lab (offline, deterministic; the workhorse)
- **3a State harvesting:** two sources, honestly different fidelity: (i) *partial* reconstruction from
  logs — `signal_snapshot` gives the exact signal map; sufficient for fitness-ranking sweeps (ranking
  reads only signals + evalBias + priority); (ii) *full* contexts from the existing fixture machinery
  (stage fixtures, run-20 GoalStack, scenario builders) — required for gate/eligibility/prompt sweeps,
  since eligibility reads many context fields logs don't fully capture. Build a small state-library
  (~dozens of states) spanning: early bootstrap, mid-build-broken, endgame convergence, the run-20
  livelock shape.
- **3b Sweep driver:** for (state, parameter, value-grid) → recompute mode, allowed roles, full ranking,
  eligibility, ticks-to-death; `explorationJitter=0`. **The value grid always includes the ablation point
  (signal absent entirely) — Q1's instrument** — and, for Q4, pairwise ablations of suspected-redundant
  signals (does removing one change anything the other doesn't already cover?). Output per parameter: the
  decision-flip boundaries across the state library. Constants with no flip anywhere in reach = provably
  inert in observed states; signals whose full ablation flips nothing anywhere = Q1 deletion candidates.
- **3c Prompt-diff driver:** for prompt-reaching signals (Phase 1's list), render agent prompts from a
  full-fidelity state under value A vs B; structural diff. Zero LLM cost; routes to Phase 4 only on a
  non-empty diff.
- **3d Multi-cycle replay:** fixture-scenario runs with mocked agent outcomes over N cycles, comparing
  signal trajectories (reinforce/attenuate feedback) under parameter variants — catches second-order
  dynamics single-cycle sweeps miss.
**DoD:** boundary map over the state library; shortlist (expected: a handful of load-bearing site
constants) with predicted behavioral deltas, ready for live/field confirmation.

### Phase 4 — Controlled LLM probes (only where Phase 3c shows a prompt delta)
- Reuse layer-2 eval pattern: local model, temp 0.0, ≥3 repeats per variant to bound residual variance.
- Measure response-level deltas per signal: e.g., does DIVERGENCE_REQUEST strength actually change the
  mutation fingerprint? Does the COMPILE_DIAGNOSTIC block's presence change fix correctness on a known
  broken fixture?
- Optionally reuse the T4-BN-3 logging-proxy to capture exact request bodies for the record.
**DoD:** per prompt-reaching candidate, a measured "model responds differently: yes/no/how" verdict.

### Phase 5 — Live validation + field confirmation (the only wall-clock-expensive phase)
- **5a Pause-and-poke:** on a live run — `POST /pause` → `PUT /signals/{name}` with a Phase-3 finalist
  value → `POST /cycle` → compare actual selection/behavior against the harness prediction → `/resume`.
  Known limit, accepted: no rewind — sequential probes on drifting state; this validates *predictions*,
  it does not compare variants (that's what layers 1–3 were for).
- **5b Field confirm:** for the surviving finalist configuration only, the established A/B pattern
  (`preflight.sh live` + `PREFLIGHT_EXTRA_FLAGS`/yml override, alternating arms). **Pre-committed decision
  rule, written BEFORE the runs** (per [[project-coverage-guard-experiment]]'s runbook pattern): metrics =
  smoke23 score, wall-clock to terminal, error census, thrash-signal rates; ≥3 pairs before any claim
  ([[project-t4bq-naming-consistency-ab]] lesson); a tie at lower complexity favors the simpler/default
  value.
**DoD:** finalist adopted into yml defaults (with the relative-justification comment) or explicitly
rejected with the numbers recorded here.

## 5. Ordering and dependencies
- Phases 0 and 1 are independent, both free — **start with both, in either order or interleaved.**
- Phase 3 needs 0+1 (state selection + which consumers to recompute); does NOT need Phase 2 (the offline
  harness constructs Signals directly).
- Phase 2 is needed only before Phase 5 (live/field need the running system to pick values up from
  config). Build it in parallel whenever convenient.
- Phase 4 needs 3c's routing. Phase 5 needs everything prior.

## 6. Open questions (stay questions until the phases answer them — no tickets, per
[[feedback-ticket-definition-of-done]])
- Sub-jitter coin-flips (~10% of orderings): correct indifference or missing tie-break? Phase 0's
  natural-experiment query may show whether flip direction correlates with outcomes at all.
- `ROLE_MERGE`'s missing cycle-start reinforcement (only mortal role signal): intentional design or
  historical accident? Phase 1 adjudicates from source.
- The 13 never-emitted signals: after Phase 1 disposition, do any deserve a deliberate trigger-fixture in
  the state library so they stop being blind spots?
- Does an "elegant systematic rule" emerge (the user's hope)? Withhold judgment until ≥2 candidates have
  been through Phases 3–5; if their boundary structures rhyme, write the generalization then — not before.

## 7. What this plan deliberately does NOT do
- No tuning of the clear-dominant family's decay (proven inert) — but that family IS in scope for
  Q1/Q4 (usefulness/redundancy): "your decay is inert" and "you are useful" are different questions,
  and the second is still open for them.
- No autonomous/self-tuning write path (T4-J scope keeps it a stub).
- No mass-migration of all ~90 emission sites through the store — candidates only.
- No claim that field runs are obsolete: they remain the final arbiter for outcome claims — demoted from
  search instrument to confirmation, which is the entire point.
