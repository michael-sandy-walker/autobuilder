---
name: project-t4bz-design-note
description: "T4-BZ design note (2026-07-10): signal-system re-architecture — signals stop being stored control objects; mechanisms with occurrences/urgencies are the real signals, the signal MAP becomes a derived readout (the self-model surface). Per-signal family map (attention windows / derived views / requests / staleness-carrying / operator switches), target carriers, 6 slices (readout extraction FIRST, deletion LAST), equivalence gates via the T4-J instruments, invariants I1-I12, open questions. READ BEFORE any T4-BZ slice; the T4-J notes ([[project-signal-dispositions]], [[project-t4j-perturbation-lab]]) are its evidence base."
metadata: 
  node_type: memory
  type: project
  originSessionId: a3509f13-fdba-438b-97a0-874862704b9b
---

# T4-BZ design note — signals as derived readout over mechanism events (2026-07-10)

Successor to the completed T4-J audit. User model (session steer): a "signal" should not be a
first-class stored object with its own coded manifestation — the mechanisms, with their
occurrences and urgencies, ARE the signals; the signal *map* is a derived, grouped readout the
system and its LLMs consult (the meta-benchmark / self-model surface,
[[project-introspection-as-north-star]]). Control flows through the mechanisms directly; a
computed readout cannot go stale. The T4-J measurements support this: the stored-signal layer is
three unrelated concerns in one abstraction, and its analog machinery (strength cap-pinning,
decay, reinforce-min ratchet, mutate) is measured mostly inert.

## As-built map (what the stored signal layer touches today)

One store: `CycleContextData.signals: Map<SignalType, Signal>` (strength/decay/age/source/meta).
Writers: ~85 `Signal.of` emission sites + ~70 `withoutSignal` clearers + engine
reinforce/attenuate + `CycleExecutor.tickSignalsTracked` (decay + death at 0.05, three sites) +
`InterpretationResponseParser` (LLM emits REPLAN_REQUESTED / LOW_CONFIDENCE / DIVERGENCE_REQUEST)
+ REST `PUT/DELETE /autobuilder/signals/{name}` (operator injection, pause-and-poke).
Readers: `RuleBasedDeliberationProposer` (mode rules, strength thresholds 0.5/0.75/1.0/2.0/3.0)
· `DeliberationExecutionGate` (mode→roles) · `AdaptiveExecutionEngine.fitnessFor` (strength ×
recency over per-role sets + BUILD_BROKEN adjustment) · `ExecutionEligibilityExplainer`
(presence + 0.10 floors + throttle/override rules) · detectors/heuristics
(`GoalExhaustionDetector`, `BuildFixAcceptanceHeuristic`, `BuildFixEscalator`,
`DeferredRestageScheduler`, `CompileDiagnosticPromptBlock`) · prompts (analysis NAME=strength
block; interpretation full-map render; dedicated blocks for divergence/repetition/test-repair)
· observability (`signal_snapshot`, `SignalLifetimeTracker` T4-BO, SSE cycle-end, graph.html)
· per-subgoal persistence (`SUBGOAL_SCOPED_SIGNALS` ↔ GoalEntity metadata, T2-BK).

## The family map (every surviving type, its true nature, its target carrier)

**Attention windows (5)** — the only family where continuous dynamics measurably matter
(participation windows via decay/attenuation; T4-J 3d):
`ROLE_PLANNER, ROLE_EVOLUTION, ROLE_EVALUATION, ROLE_MERGE, ROLE_ANALYSIS`.
→ Target: explicit per-role **participation state** — `active-until / budget / boost` granted by
the same mechanisms that emit role signals today (seeder grants at cycle 1, proposer grants on
mode decisions, evaluation-accept grants merge a window, analysis-unlock grants evolution).
Selection reads windows + open-request urgencies, not strength arithmetic.

**Derived views (9)** — recomputable from context/trackers at read time; storing them is what
creates the stale-signal pathology class:
`BUILD_BROKEN` (= buildOutcome carries COMPILE_ERROR) · `TEST_REPAIR_FOCUS` (= resolved
test-only failure + spec-requires-tests) · `BUILD_FIX_LOCUS_STUCK` (= sameFailureStreak ≥ 2) ·
`RECURRING_DEFECT_DOMINANT` (= tracker dominant present) · `IRREDUCIBLE_GAP` (= rehab-cap ids
nonempty) · `SCOPE_OUT_OF_SCOPE` / `SUBGOAL_SCOPE_MISMATCH` (= scope-guard result for the
current cycle) · `GOAL_EXHAUSTED` (= root COMPLETED/GIVEN_UP or detector terminal) ·
`EVOLUTION_OCCURRED` (= unmerged evolution work exists: fingerprint newer than last merge —
NOTE: this *defines* the audit's open Q5 "should it clear on merge" as YES by derivation; the
frozen-state harness must show where that changes decisions vs today's never-cleared signal,
and the change is accepted only if every flip is at a state where "work exists" is factually
false today).
→ Target: **predicates on `CycleContextData`/trackers** (a `DerivedFacts` view), computed per
read; they appear in the readout, never in a store.

**Requests (10)** — messages from one mechanism to another; today cleared by consumers with
decay as a backstop for missed clears:
`REPLAN_REQUESTED, DIVERGENCE_REQUEST, LOW_CONFIDENCE, STAGNATION, REPETITION_DETECTED,
SCORE_PLATEAU, REPLAN_THRASH_DETECTED, SUBGOAL_EXHAUSTED, EVOLUTION_NOOP, POTENTIALLY_COMPLETE`.
→ Target: a **request channel** — typed entries `(kind, urgency, source, subgoal-scope,
issued-cycle)` with consume-on-read semantics per addressed mechanism; re-issuing raises
urgency (replaces reinforce). A request nobody consumes within its scope is a VISIBLE fact in
the readout (today: silent decay). The T2-AO verified-consumption pattern (DIVERGENCE_REQUEST
survives until the fingerprint actually changes) becomes first-class: consumption can be
provisional-until-verified.

**Staleness-carrying (2)** — the only genuinely fading information; age IS semantics:
`MISSING_SYMBOL` (diagnostic goes stale as code changes), `PLANNING_THROTTLED` (pressure
condition, re-measured but lingering).
→ Target: requests **with expiry** (issued-cycle + validity horizon expressed in cycles of the
issuing mechanism's own cadence, per [[feedback-relative-thresholds]]). MISSING_SYMBOL ideally
graduates to a derived view once grounding can recompute cheaply per cycle — decide in slice 3,
measure the cost first.

**Operator switches (2)**: `STEERING_SUPPRESS, RESTAGE_SUPPRESS` → plain runtime toggles
(REST-settable booleans), not signals at all. `SYSTEM_PAUSED` is the same shape (pause flag) —
today it doubles as a halt signal; it becomes the pause toggle read wherever the signal is read.

**The readout** — the piece that IS the user's model: `SignalReadout` composes
windows + open requests + derived facts into the named/weighted map that snapshots, the
analysis/interpretation prompts, SSE, graph.html, and `GET /status` render. Same information
as today's map, but computed. The interpretation prompt's emission instructions stay — the
parser enqueues requests instead of storing signals (the LLM-as-emitter channel survives).

## Findings that shape the design (all measured, T4-J)

- **F1 — ranking is substantially decorative; eligibility selects.** Exact analysis/planner
  ties (saturation + shared sets), evolution executing while ranked last, jitter as the real
  tie-break. Consequence: the replacement selection = eligibility predicates + explicit
  ordering (windows/urgency/base priority) — no strength arithmetic to preserve. The audit's Q3
  tie-break question gets ANSWERED here by an explicit rule instead of a coin flip.
- **F2 — the reinforce-min ratchet means decay constants were never really tunable state**
  — birth site wins forever. Windows/expiry make lifetime a stated fact instead of an
  arithmetic accident.
- **F3 — thresholds are the real semantics** (0.10 eligibility floors, REPLAN≥1.0 override,
  plateau 2.0 boost, proposer 0.5/0.75/2.0/3.0 rules). Each becomes an explicit predicate on
  requests/urgency — same behavior, honest form. The outcome probe recovered exactly these
  thresholds from data, so the equivalence harness will catch any drift in them.
- **F4 — the coupled pair**: plateau ROLE_MERGE boost (2.0) × ROLE_EVALUATION floor (0.5) is a
  pair-ordering interaction. In the new model this is ONE explicit statement ("plateau: merge
  before evaluation this cycle"), not two constants that accidentally interact.
- **F5 — three signals already re-derive every cycle** (locus-stuck, defect-dominant,
  test-repair-focus) — slice 2 generalizes an existing in-repo pattern, not an invention.

## Invariants and migration pins (each keeps or gains a regression test)

**Two kinds, deliberately distinguished (user steer 2026-07-10: "not sure all those invariants
really MUST be implemented like they are now"):**
- **Hard invariants** — requirements with run-history receipts; must survive in some form:
  I1 (halt), I2 (per-role execution / merge ends cycle), I5 (deadlock non-halt guards — the
  run-20 livelock family), I6 (subgoal isolation), I11 (verified consumption), I12 (the
  harness itself).
- **Migration pins** — CURRENT behavior held fixed only to control variables during the
  migration, individually revisable on evidence after their slice lands: I3 (threshold
  values), I4 (per-state mode outcomes), I7 (prompt render shape), I8 (REST details),
  I9 (event schema), I10 (second-pass mechanics). The harness certifies "unchanged," not
  "correct" — the audit proved parts of current behavior are accidental, and the plan already
  deliberately changes two (the slice-4 tie-break rule; possibly EVOLUTION_OCCURRED's
  never-clearing). Each pin release is its own small decision with its own evidence, cheap in
  the new model because the behavior becomes an explicit statement rather than arithmetic.


- I1 halt semantics: pause/exhaustion honored at cycle start AND mid-cycle (T1-DL, T4-AI-3).
- I2 one execution per role per cycle; merge ends the cycle immediately.
- I3 eligibility behavior at today's thresholds (0.10 floors, throttle + REPLAN≥1.0 override,
  scope-mismatch merge block, evaluator fingerprint/criteria rules) — re-expressed, not changed.
- I4 proposer mode outcomes on the 28-state library (BOOTSTRAP/EVALUATE/REPLAN/UNDIRECTED/PAUSE
  per recorded state).
- I5 deadlock non-halt guards: evalFloor re-injection under pending evaluation, analysis-unlock,
  circuit-breaker UNDIRECTED+diverge — as window/request grants.
- I6 subgoal isolation (T2-BK): requests and staleness carriers are subgoal-scoped where their
  signals were; switching subgoals gives a clean slate, restarts restore.
- I7 LLM channels: analysis prompt gets an equivalent NAME=strength-shaped readout block;
  interpretation prompt gets the full readout; parser emissions become requests. Prompt-surface
  changes gated by AgentPromptRenderer diffs + a layer-2 eval before/after the readout slice.
- I8 REST: GET readout equivalent; PUT/DELETE keep working for pause-and-poke (inject = enqueue
  request / set toggle / grant window — decide the mapping in slice 1 and keep the endpoint
  contract).
- I9 observability: signal_snapshot keeps emitting (from the readout); T4-BO lifetime tracking
  re-scoped to request lifecycles (issued/consumed/expired — richer than decayed/cleared);
  behaviour-log event names stay parseable by existing scripts or scripts updated in the same
  scope.
- I10 T1-DK second-pass rescue and T2-DJ skipped≠executed survive in the new selection pass.
- I11 verified consumption (T2-AO): divergence request outlives an ignored hint.
- I12 the T4-J equivalence harness itself keeps compiling/running at every slice — it is the
  migration net; it must never be deleted-with-the-old-code until the final slice re-pins it
  against the new model.

## Slices (each ships full-scope: build + rewire every caller + delete subsumed machinery)

- **T4-BZ-0 — family map + carrier decisions.** THIS NOTE plus: a per-emission-site table
  (which of the ~85 sites becomes window-grant / request-issue / derived-fact / toggle),
  the readout contract (exact fields snapshots/prompts get), and the slice-4 selection rule
  written down BEFORE code. Deliverable: table committed to this note; no production change.
- **T4-BZ-1 — readout extraction (pure refactor, land first).** `SignalReadout` renderer over
  the CURRENT stored map; all render-only consumers (analysis+interpretation prompt blocks,
  snapshot, SSE, graph payload, GET status) switch to it. Zero behavior change —
  AgentPromptRenderer diff empty, snapshot byte-equal on the state library. This pins the
  readout surface so every later slice changes semantics behind a stable face.
- **T4-BZ-2 — derived views.** The 9 state-like types leave the store; `DerivedFacts`
  predicates; consumers (eligibility, gate rules, engine BUILD_BROKEN adjustment, detectors,
  restage/steering blocks) read predicates; readout renders them with a derived marker.
  EVOLUTION_OCCURRED's derivation decision (see family map) resolved here with harness evidence.
  Equivalence: frozen-observer decisions identical on all 28 states (or each flip individually
  justified); outcome-probe distributions unchanged.
- **T4-BZ-3 — request channel.** The 10 request types + 2 staleness carriers move to the
  channel; consumers consume-on-read (verified where T2-AO applies); proposer rules re-read
  urgency; interpretation parser enqueues; per-subgoal scoping via the channel (replaces
  SUBGOAL_SCOPED_SIGNALS persistence for these types); T4-BO re-scoped per I9.
  Equivalence: same gates as slice 2 + multi-cycle replay trajectories on the three scenario
  scripts.
- **T4-BZ-4 — participation windows + the selection pass.** ROLE family replaced; fitness
  arithmetic (strength×recency, reinforce/attenuate, jitter) replaced by the slice-0 selection
  rule (eligibility → explicit ordering by window/urgency/priority → tie-break RULE, closing
  audit-Q3). The flagged ROLE-decay live question is answered by construction (windows are
  stated). THIS is the behavior-riskiest slice: full harness + one confirming field run before
  the old path is deleted.
- **T4-BZ-5 — deletion + re-pin.** `Signal` decay/tick/reinforce/attenuate/mutate machinery,
  MAX_STRENGTH, SIGNAL_SURVIVAL_THRESHOLD, the stored map itself (if nothing remains), seeder
  strength constants, tuning-store decay parameters (re-pointed at window/expiry parameters);
  operator switches to toggles; the harness re-pinned against the new model as the standing
  regression net. Field run + smoke; resume-state entry.

Ordering: 0 → 1 → 2 → 3 → 4 → 5 strictly; 2 and 3 are independently shippable after 1 but land
2-first (smaller, pattern already exists in-repo — F5). Nothing lands as a coexisting second
path ([[project-t4ai-design-note]]'s operative lesson: delete the duplicate, don't keep both).

## Open questions (resolve in the named slice, not before)

- Q-a (slice 0): exact selection rule for slice 4 — proposal: eligibility predicates first,
  then order by (explicit constraint > open-request urgency for the role > window boost > base
  priority), deterministic tie-break by pipeline order (planner→analysis→evolution→evaluation→
  merge)? Write it down, sanity-check against the 28-state recorded decisions BEFORE building.
- Q-b (slice 3): MISSING_SYMBOL — expiring request or cheap derived view? Measure grounding
  recompute cost first.
- Q-c (slice 3): urgency scale — reuse today's strength numbers 1:1 initially (safest for the
  proposer thresholds) vs a small ordinal set? Start 1:1, revisit only after slice 5.
- Q-d (slice 1): REST PUT mapping for arbitrary names during migration (pause-and-poke
  compatibility window).
- Q-e (slice 4): does evaluation-prompt signal-blindness (audit Q6) get an entry in the readout
  contract, or stay deliberately blind? Default: stay blind; revisit on evidence.

## Risks, stated

Prompt-text drift at slice 1/3 boundaries (gated by renderer diff + layer-2 eval); T4-BO/event
schema consumers in scripts (update in-scope, [[project-log-and-backup-conventions]]); the
run-20-family deadlock guards live exactly in the machinery slice 4 replaces (I5 tests first);
outcome benefit unproven — the claim is truthfulness + deletion of a pathology class + a
consultable self-model, not smoke-score improvement; if slice-4 field runs regress, the slice
reverts (old path is deleted only after its field run passes).

---

# T4-BZ-0 deliverables (2026-07-10, line numbers pinned at HEAD 64f76dd)

## Per-emission-site table

Every live production `Signal.of` site, one row each, grouped by target carrier. Migrating
slice in [brackets]. Excluded from rows, listed at the end: commented-out sites, engine-internal
machinery, the seeder's `put` helper (its ten call rows appear instead).

### A — Window grants → per-role participation state [slice 4]

| # | Site | Emits | str/decay | Trigger → carrier action |
|---|---|---|---|---|
| A1-A5 | `CycleStartSignalSeeder:62-66` (no-goal branch) | ROLE_PLANNER 0.80/0.25ᵗ · ROLE_EVOLUTION 0.20/0.15 · ROLE_EVALUATION 0.20/0.15 · ROLE_MERGE 0.20/0.15 · ROLE_ANALYSIS 0.20/0.0ᵗ | | empty signal map, no goal → bootstrap grants, planner-dominant |
| A6-A10 | `CycleStartSignalSeeder:69-73` (goal branch) | ROLE_EVOLUTION 0.80/0.25 · ROLE_EVALUATION 0.50/0.15 · ROLE_PLANNER 0.20/0.15ᵗ · ROLE_MERGE 0.20/0.15 · ROLE_ANALYSIS 0.20/0.0ᵗ | | empty signal map, goal exists → forward-motion grants |
| A11 | `CycleContextCoordinator:201` | ROLE_PLANNER | 1.0/0.1 | no root goal → planner grant |
| A12 | `CycleContextCoordinator:226` | ROLE_PLANNER | 1.0/0.1 | no active subgoal, root not terminal → planner grant (one replan attempt) |
| A13 | `AdaptiveExecutionEngine:172` | ROLE_EVOLUTION | 1.0/0.10 | AnalysisUnlock → evolution grant (I5 guard) |
| A14 | `AdaptiveExecutionEngine:173` | ROLE_PLANNER | 0.0/0.10 | AnalysisUnlock → planner window REVOKE (strength-0 emission = explicit revocation in the new model) |
| A15 | `RuleBasedDeliberationProposer:71` | ROLE_EVOLUTION | 1.0/0.20 | analysis unlocked executable steps → evolution grant |
| A16 | `RuleBasedDeliberationProposer:90` | ROLE_PLANNER | 1.0/0.20 | replan-thrash pre-emption → planner grant |
| A17 | `RuleBasedDeliberationProposer:113` | ROLE_PLANNER | 1.0/0.20 | repetition ≥2.0 breakout → planner grant |
| A18 | `RuleBasedDeliberationProposer:152` | ROLE_EVOLUTION | 0.75/0.10 | subgoal exhausted, executable siblings remain → evolution grant |
| A19 | `RuleBasedDeliberationProposer:166` | ROLE_PLANNER | 1.0/0.20 | subgoal exhausted, none remaining → planner grant |
| A20 | `RuleBasedDeliberationProposer:188` | ROLE_PLANNER | 1.0/0.20 | plateau ≥3.0 escalation → planner grant |
| A21 | `RuleBasedDeliberationProposer:201` | ROLE_MERGE | 2.0/0.10 | plateau ≥1.0 → the F4 coupled pair: becomes ONE constraint "plateau: merge before evaluation this cycle" (with A22) |
| A22 | `RuleBasedDeliberationProposer:291` | ROLE_EVALUATION | 0.5/0.10 | pending evaluation, evalFloor deadlock guard (I5) → evaluation grant |
| A23 | `RuleBasedDeliberationProposer:228` | ROLE_EVOLUTION | 1.0/0.10 | stagnating + divergence ≥0.5 → evolution grant (divergent path, not replan) |
| A24 | `RuleBasedDeliberationProposer:242` | ROLE_PLANNER | 0.90/0.20 | stagnating, no divergence → planner grant |
| A25 | `RuleBasedDeliberationProposer:274` | ROLE_PLANNER | 1.0/0.20 | candidate pending, no diff, replan ≥0.5 → planner grant |
| A26 | `EvolutionAgent:203` | ROLE_EVALUATION | 1.0/0.05 | evolution wrote files → evaluation grant |
| A27 | `EvaluationAgent:427` | ROLE_MERGE | 1.0/1.0 | mutation ACCEPTED → merge same-cycle window (decay 1.0 = dies at tick; already window-shaped) |
| A28 | `CycleExecutor:613` | ROLE_MERGE | 1.0/1.0 | accepted/pending mutation post-cycle hook → merge same-cycle window |
| A29 | `DeferredMutationRestager:151` | ROLE_EVALUATION | 3.0/0.05 | deferred mutation re-probed COMPILE_OK → evaluation grant (max-strength routing) |
| A30 | `BuildFixEscalator:75` | ROLE_ANALYSIS | 1.0/0.05 | buildFixStuck stage 2 → analysis grant |

ᵗ = decay routed through `TuningParameterStore` (analysis + planner seeds) — slice 5 re-points
these at window parameters.

### B — Request issues → typed request channel [slice 3]

| # | Site | Emits | str/decay | Trigger |
|---|---|---|---|---|
| B1 | `GoalExplorerService:459` | REPLAN_REQUESTED | 1.0/0.25 | operator pin-as-priority (REST) |
| B2 | `GoalExplorerService:502` | REPLAN_REQUESTED | 1.0/0.25 | operator inject-current (REST) |
| B3 | `GoalExplorerService:568` | REPLAN_REQUESTED | 1.0/0.25 | operator reactivate (REST) |
| B4 | `DefaultSubgoalScopeGuard:101` | REPLAN_REQUESTED | 1.0/0.05 | scope mismatch, no better match |
| B5 | `EvaluationAgent:188` | REPLAN_REQUESTED | 1.0/0.2 | T3-BR retry cap on repeated fingerprint |
| B6 | `GoalPlannerService:1125` | REPLAN_REQUESTED | 1.0/0.2 | planInto fully deduped |
| B7 | `GoalPlannerService:2852` | REPLAN_REQUESTED | 1.0/0.2 | closed-but-unmet reopen |
| B8 | `GoalPlannerService:3117` | REPLAN_REQUESTED | 1.0/0.2 | coverage retro-check |
| B9 | `AutobuilderService:605` | REPLAN_REQUESTED | 1.0/0.20 | exhaustion recovery (guarded: only if <0.50 present) |
| B10 | `AutobuilderService:661` | REPLAN_REQUESTED | 1.0/0.20 | stagnation recovery (same guard) |
| B11 | `BuildFixEscalator:70` | REPLAN_REQUESTED | 1.0/0.10 | buildFixStuck stage 1 |
| B12 | `CycleExecutor:339` | REPLAN_REQUESTED | 1.0/0.5 | T1-ET livelock suspected |
| B13 | `InterpretationResponseParser:42` | REPLAN_REQUESTED | 0.8/0.05 | LLM-emitted (I7 channel — parser enqueues) |
| B14 | `RuleBasedDeliberationProposer:89` | REPLAN_REQUESTED | 0.80/0.10 | thrash pre-emption |
| B15 | `RuleBasedDeliberationProposer:112` | REPLAN_REQUESTED | 0.85/0.10 | repetition breakout |
| B16 | `RuleBasedDeliberationProposer:165` | REPLAN_REQUESTED | 0.90/0.10 | subgoal exhausted, none remaining |
| B17 | `RuleBasedDeliberationProposer:187` | REPLAN_REQUESTED | 0.90/0.10 | plateau ≥3.0 |
| B18 | `RuleBasedDeliberationProposer:241` | REPLAN_REQUESTED | 0.75/0.10 | stagnating |
| B19 | `RuleBasedDeliberationProposer:273` | REPLAN_REQUESTED | =current/0.10 | pending-no-diff re-issue (urgency carry) |
| B20 | `EvolutionAgent:145` | DIVERGENCE_REQUEST | 1.0/0.1 | #NoDiff on blocker subgoal |
| B21 | `EvolutionAgent:160` | DIVERGENCE_REQUEST | 1.0/0.1 | T1-CS #NoDiff without evidence |
| B22 | `EvolutionAgent:178` | DIVERGENCE_REQUEST | 1.0/0.1 | unverified #NoDiff |
| B23 | `EvolutionAgent:297` | DIVERGENCE_REQUEST | 1.0/0.10 | fingerprint repetition |
| B24 | `AutobuilderService:663` | DIVERGENCE_REQUEST | 1.0/0.10 | stagnation recovery |
| B25 | `AutobuilderService:740` | DIVERGENCE_REQUEST | 1.0/0.1 | T1-DN #NoDiff trust-gate refusal |
| B26 | `RuleBasedDeliberationProposer:51` | DIVERGENCE_REQUEST | 1.0/0.10 | stagnation circuit breaker |
| B27 | `RuleBasedDeliberationProposer:111` | DIVERGENCE_REQUEST | 1.0/0.10 | repetition breakout |
| B28 | `RuleBasedDeliberationProposer:202` | DIVERGENCE_REQUEST | 1.0/0.10 | plateau ≥1.0 |
| B29 | `InterpretationResponseParser:46` | DIVERGENCE_REQUEST | 0.7/0.10 | LLM-emitted (I7) |
| B30 | `PatternSignalAnalyzer:90` | LOW_CONFIDENCE | 0.8/0.10 | thrash pattern |
| B31 | `PatternSignalAnalyzer:109` | LOW_CONFIDENCE | 0.6/0.12 | focus drift |
| B32 | `EvaluationAgent:381` | LOW_CONFIDENCE | var/0.15 | low LLM eval confidence (strength = f(confidence)) |
| B33 | `InterpretationResponseParser:44` | LOW_CONFIDENCE | 0.7/0.10 | LLM-emitted (I7) |
| B34 | `PatternSignalAnalyzer:64` | STAGNATION | 0.7/0.20 | empty diff |
| B35 | `EvaluationAgent:467` | STAGNATION | 1.0/0.2 | evaluation-detected stagnation |
| B36 | `PatternSignalAnalyzer:49` | REPETITION_DETECTED | 1.0/0.15 | fingerprint repeat (analyzer) |
| B37 | `EvolutionAgent:144` | REPETITION_DETECTED | 1.0/0.05 | #NoDiff on blocker |
| B38 | `EvolutionAgent:177` | REPETITION_DETECTED | 1.0/0.05 | unverified #NoDiff |
| B39 | `EvolutionAgent:296` | REPETITION_DETECTED | 1.0/0.05 | own-fingerprint repeat |
| B40 | `AutobuilderService:945` | SCORE_PLATEAU | 1.0/0.05 | consecutive flat accepted scores |
| B41 | `ReplanPatternDetector:77` | REPLAN_THRASH_DETECTED | 1.0/0.25 | consecutive replans without progress |
| B42 | `ScopeThrashSignalAnalyzer:79` | SUBGOAL_EXHAUSTED | 1.0/0.05 | consecutive OUT_OF_SCOPE assessments |
| B43 | `DeliberationService:107` | SUBGOAL_EXHAUSTED | conf/0.05 | GoalExhaustionDetector report (strength = clamped confidence) |
| B44 | `EvolutionAgent:166` | EVOLUTION_NOOP | 1.0/0.0 | LLM #NoDiff (meta: subGoalId — already subgoal-scoped) |
| B45 | `AnalysisAgent:70` | POTENTIALLY_COMPLETE | 1.0/0.0 | analysis judges subgoal possibly complete |

### C — Requests with expiry (staleness carriers) [slice 3, Q-b decides MISSING_SYMBOL]

| # | Site | Emits | str/decay | Trigger |
|---|---|---|---|---|
| C1 | `PlannerAgent:117` | MISSING_SYMBOL | 1.0/0.25 | planner grounding, per undefined symbol (source carries symbol) |
| C2 | `AnalysisAgent:76` | MISSING_SYMBOL | 1.0/0.25 | analysis grounding, per undefined symbol |
| C3 | `PlanningThrashSignalAnalyzer:139` | PLANNING_THROTTLED | 1.0/0.10 | planning-thrash pattern |
| C4 | `PlannerAgent:259` | PLANNING_THROTTLED | 0.6/0.15 | skip-ratio throttle |
| C5 | `PlannerAgent:267` | PLANNING_THROTTLED | 0.9/0.10 | backlog-cap throttle |

### D — Derived facts → predicates on context/trackers; emission sites DELETED [slice 2]

| # | Site | Emits | Derivation replacing it |
|---|---|---|---|
| D1 | `ContextLineageTracker:50` | BUILD_BROKEN | `buildOutcome == COMPILE_ERROR` (set/clear pair already mirrors this 1:1) |
| D2 | `ContextLineageTracker:65` | TEST_REPAIR_FOCUS | test-only failure resolved ∧ spec requires tests (F5: already re-derived per cycle) |
| D3 | `ContextLineageTracker:77` | BUILD_FIX_LOCUS_STUCK | `sameFailureStreak >= 2` (F5) |
| D4 | `ContextLineageTracker:92` | RECURRING_DEFECT_DOMINANT | defect tracker dominant present (F5) |
| D5 | `EvolutionAgent:202` | EVOLUTION_OCCURRED | unmerged evolution work exists: fingerprint newer than last merge (audit-Q5 resolved YES-by-derivation in slice 2, harness-gated) |
| D6 | `EvolutionAgent:300` | EVOLUTION_FINGERPRINT_CHANGED | **family-map addition (was unlisted):** `lastEvolutionFingerprint != previous` from fingerprint history; consumers `EvaluationAgent:159` + `AutobuilderService:851` (T2-AO verified consumption) read the predicate |
| D7 | `DefaultSubgoalScopeGuard:100` | SUBGOAL_SCOPE_MISMATCH | scope-guard result for the current cycle (paired B4 request stays a request) |
| D8 | `DefaultSubgoalScopeGuard:118` | SCOPE_OUT_OF_SCOPE | scope-guard result, out-of-scope branch |
| D9 | `PlannerAgent:244` | IRREDUCIBLE_GAP | rehabilitation-cap ids nonempty (per-id source string preserved in fact detail) |
| D10 | `CycleContextCoordinator:205` | GOAL_EXHAUSTED | root COMPLETED, no subgoals → pure derivation from root status |
| D11 | `CycleContextCoordinator:209` | GOAL_EXHAUSTED | root GIVEN_UP → pure derivation |
| D12 | `CycleContextCoordinator:220` | GOAL_EXHAUSTED | T1-DH pre-plan auto-complete → pure derivation |
| D13 | `CycleContextCoordinator:223` | GOAL_EXHAUSTED | T1-DH pre-plan auto-given-up → pure derivation |
| D14 | `GoalPlannerService:2811` | GOAL_EXHAUSTED | root given up → pure derivation |
| D15 | `PlannerAgent:210` | GOAL_EXHAUSTED | empty-backlog injection limit → **halt-reason record** (see below) |
| D16 | `RootConvergenceTracker:134` | GOAL_EXHAUSTED | T4-I convergence stall → halt-reason record |
| D17 | `AutobuilderService:426` | GOAL_EXHAUSTED | null-subgoal halt → halt-reason record |
| D18 | `AutobuilderService:594` | GOAL_EXHAUSTED | exhaustion runaway → halt-reason record |
| D19 | `AutobuilderService:787` | GOAL_EXHAUSTED | build-blocked deadlock → halt-reason record |
| D20 | `AutobuilderService:826` | GOAL_EXHAUSTED | planner-output amputation → halt-reason record |

GOAL_EXHAUSTED splits: D10-D14 are already encoded in the goal tree (derive from root status,
delete emission). D15-D20 are detector CONCLUSIONS not yet in the tree — each writes an explicit
halt reason (one new fact carrier, set once, never decays — exactly today's
decay 0.0), and the readout derives `GOAL_EXHAUSTED = rootTerminal ∨ haltReasonPresent`. I1 halt
semantics keep their regression tests against the derived form.

### E — Toggles [slice 5 finalizes; every site pairs with a GOAL_EXHAUSTED row above]

| # | Site | Emits | Trigger → toggle action |
|---|---|---|---|
| E1 | `AutobuilderController:96` | SYSTEM_PAUSED | REST pause → `paused = true (reason=user-pause)` |
| E2 | `PlannerAgent:211` | SYSTEM_PAUSED | empty-backlog limit → pause(reason) |
| E3 | `RootConvergenceTracker:135` | SYSTEM_PAUSED | T4-I stall → pause(reason) |
| E4 | `AutobuilderService:427` | SYSTEM_PAUSED | null-subgoal halt → pause(reason) |
| E5 | `AutobuilderService:595` | SYSTEM_PAUSED | exhaustion runaway → pause(reason) |
| E6 | `AutobuilderService:624` | SYSTEM_PAUSED | root exhaustion → pause(reason) |
| E7 | `AutobuilderService:650` | SYSTEM_PAUSED | stagnation runaway → pause(reason) |
| E8 | `AutobuilderService:677` | SYSTEM_PAUSED | LLM circuit breaker → pause(reason) |
| E9 | `AutobuilderService:788` | SYSTEM_PAUSED | build-blocked deadlock → pause(reason) |
| E10 | `AutobuilderService:827` | SYSTEM_PAUSED | planner amputation → pause(reason) |
| E11 | `AutobuilderController:134` | (arbitrary type, REST PUT) | Q-d migration mapping: name resolves to enqueue-request / set-toggle / grant-window by the type's family; unknown names rejected with the family list (decided in slice 1) |

`STEERING_SUPPRESS` / `RESTAGE_SUPPRESS` have no production emitters (operator-only via E11) —
they become REST-settable booleans in slice 5, no table rows needed.

### Non-rows (machinery and dead text)

- `AdaptiveExecutionEngine:368-369` — reinforce path (`Signal.of` as reinforcement carrier), replaced wholesale by the slice-4 selection pass.
- `AdaptiveExecutionEngine:401` — exploration nudge on the weakest signal, deleted in slice 4 (jitter's sibling).
- Commented-out sites: `DefaultSubgoalScopeGuard:106-107`, `AdaptiveExecutionEngine:181` — delete on sight in their slices.
- Application channels (not emissions, must be rewired in the emitting slice): proposer signals ride `DeliberationResult.proposedSignals` (validator passes through, engine applies); interpretation parser returns a `List<Signal>` its caller applies; `SUBGOAL_SCOPED_SIGNALS` save/restore at `CycleExecutor:146,235` + `CycleContextCoordinator:187` (replaced by channel scoping in slice 3).

## Readout contract (the fields T4-BZ-1's `SignalReadout` exposes)

One entry per visible signal/fact, composed per read:

```
SignalReadout {
  cycleId: String
  entries: List<Entry>            // ordered: windows, requests, derived, toggles; stable within family by name
  Entry {
    name: String                  // today's SignalType name, unchanged (I9: scripts keep parsing)
    family: WINDOW | REQUEST | DERIVED | TOGGLE   // slice 1 renders family from the T4-BZ-0 classification; purely additive
    strength: double              // today: stored strength. End state: window boost / request urgency / 1.0 for true derived facts & set toggles
    age: int                      // cycles since grant/issue; 0 for derived facts
    decayPerCycle: double         // today: stored decay. End state: windows/expiring requests render remaining-validity-derived equivalent; 0.0 otherwise
    source: String                // issuing mechanism, verbatim today's source strings
    meta: Map<String,String>      // subGoalId scope etc., verbatim
  }
}
```

Renderers (all consumers go through these; nothing else touches the map after slice 1):
- `strengthLine()` — analysis prompt's NAME=strength block, byte-identical in slice 1.
- `fullMap()` — interpretation prompt's full render (incl. age/decay), byte-identical in slice 1.
- `snapshot()` — `signal_snapshot` event payload, byte-equal on the 28-state library in slice 1.
- `json()` — SSE cycle-end, graph.html payload, `GET /autobuilder/status`.
Plus, from slice 3 on: `unconsumedRequests()` — requests past their scope with no consumer, the
today-silent-decay pathology made visible (new information, additive only).

Contract rule: slices 2-4 may change how an entry's numbers are COMPUTED, never the entry shape
or name; additions are new fields/sections, never renames (I7/I9).

## Q-a — the slice-4 selection rule (final form, checked against the 28-state library)

Per selection pass:
0. **Halt**: pause toggle set or terminal fact true → no pass (I1).
1. **Candidates** = deliberation-mode gate roles (unchanged, I4).
2. **Eligibility** predicates per role, evaluated at execution time during walk-down
   (today's explainer semantics, I3; preserves I10 second-pass re-checks).
3. **Ordering** of candidates, descending lexicographic:
   (a) **explicit constraint** — each one a stated production intent: currently exactly
       "build broken ⇒ analysis first" (T1-DU/T2-DE, comment-documented intent);
   (b) **max urgency over open requests addressed to the role** (addressing map = today's
       per-role attention sets ∩ the 12 channel types; strengths 1:1 per Q-c);
   (c) **window boost** (participation-window grant strength, 1:1 with today's ROLE_*);
   (d) **base priority** (planner 1.0, analysis 1.5, evaluation 2.0, evolution 3.0, merge 4.0);
   (e) **tie-break**: pipeline order planner→analysis→evolution→evaluation→merge — the
       audit-Q3 coin-flip closed by a stated rule.
4. First eligible role in that order executes; walk-down continues as today (I2: one execution
   per role per cycle, merge ends the cycle).
F4's plateau statement is a PAIR pin, not a constraint: plateau ≥1.0 ⇒ merge orders before
evaluation — carried naturally by the plateau window grant (merge 2.0 > evaluation's floor) and
asserted as its own check.

**Check result (`SelectionRulePreviewTest`, test-scope, committed with T4-BZ-0): 27/28 states
reproduce today's first-executed role** (26 match + 1 halt-agree), one accepted divergence:
- `log-11: frequent-set-6` (REPLAN, saturated late-run state): today picks planner by SUMMING
  five co-present near-cap attention signals — the exact F1 arithmetic this redesign deletes;
  the rule serves the strongest open request (REPETITION_DETECTED at cap → evolution). The
  field cycle behind the state actually selected analysis (observer mode fidelity limit), so
  today's planner-pick is a reconstruction, not ground truth. Slice-4's gate re-adjudicates.

## Q-d — REST PUT/DELETE mapping during migration (decided in T4-BZ-1)

`PUT/DELETE /autobuilder/signals/{name}` keeps its contract through every slice. Slice 1: the
stored map still exists — endpoints keep writing it unchanged. From the slice that moves a
family out of the store, the endpoint resolves `name` by `SignalReadout.familyOf`:
REQUEST → enqueue (strength = urgency), WINDOW → grant/revoke a window, TOGGLE → set/clear the
toggle, DERIVED → rejected with a message naming the fact's derivation (a computed fact cannot
be injected; the operator pokes the underlying state instead). Names outside every family read
as requests — operator poke = ask. DELETE mirrors: consume/expire the request, revoke the
window, clear the toggle.

**Two refinements the first preview run forced (recorded so slice 4 doesn't re-learn them):**
1. "Plateau ⇒ merge-first" as an absolute constraint is WRONG — today's 2.0 boost does not
   outrank an active evolution (state `canon: plateau-first`); only the merge>evaluation pair
   is the real F4 semantics.
2. BUILD_BROKEN's ordering effect (engine `buildBrokenAdjustment`) is an intentional,
   comment-documented production rule — it must become constraint (a), not be dropped with the
   derived-facts-don't-order principle. Derived facts otherwise stay out of ordering; their
   selection influence rides explicit window grants (A26-A30) — measured sufficient across the
   rest of the library.

## T4-BZ-2 implementation plan (settled 2026-07-10 after the reader sweep; execute in order)

**Derivation sources, confirmed in source:**
- BUILD_BROKEN = `buildOutcome.result()==COMPILE_ERROR ∨ lastRollback!=null` (pure ctx —
  exactly `ContextLineageTracker:43`).
- TEST_REPAIR_FOCUS = `TestRunGate.specRequiresTests(rootDesc) ∧ TestOnlyCompileFailure.isTestOnly(ctx)`;
  rootDesc comes from `ctx.currentGoalDescription()` — verify it matches the coordinator's
  passed-in root description in tests before relying on it.
- BUILD_FIX_LOCUS_STUCK = `sameFailureStreak>=2 ∧ streakKey!=null` (pure ctx).
- RECURRING_DEFECT_DOMINANT = `RecurringDefectTracker.dominantDefect().isPresent()` (bean).
- SCOPE_OUT_OF_SCOPE / SUBGOAL_SCOPE_MISMATCH = new per-cycle ctx enum field `scopeGuardResult`
  (guard already clears+re-derives per cycle — the signals were always per-cycle results).
- IRREDUCIBLE_GAP = new ctx field carrying the rehab-cap ids the planner currently only
  renders into signal sources.
- GOAL_EXHAUSTED = root COMPLETED/GIVEN_UP (goal service) ∨ new ctx field
  `goalExhaustedReason` (written by the 6 detector sites D15-D20; carried forward by
  lineage — a recorded halt reason is sticky; REST resume paths must CLEAR the field where they clear the signal).
- EVOLUTION_OCCURRED = `lastEvolutionFingerprint != null` (lineage already nulls it on merge
  AND subgoal change — the Q5 clear-on-merge change arrives by derivation; harness adjudicates).
- EVOLUTION_FINGERPRINT_CHANGED = new per-cycle ctx boolean set by
  `EvolutionAgent.updateFingerprint`; both consumers (`EvaluationAgent:159` skip-LLM guard,
  `AutobuilderService:851` same-fingerprint counter reset) read per-cycle semantics; the
  stored signal's ~19-cycle lingering window is accidental — documented behavior change,
  harness-adjudicated like Q5.

**Fitness-set participation (the tricky part):** BUILD_BROKEN sits in ANALYSIS/EVOLUTION/MERGE
sets, EVOLUTION_OCCURRED in EVALUATION/MERGE, SCOPE pair in PLANNER/MERGE. `fitnessFor` gets a
derived-facts bridge: presence contributes strength 1.0 + recency 1.0 (constants — identical
to today for the always-1.0/decay-0 lineage facts re-emitted at age 0 every cycle; a CHANGE
for EVOLUTION_OCCURRED, which today reinforces to 3.0 — every frozen-observer flip this causes
is examined individually; slice 4 deletes the arithmetic anyway).

**Order (suite green after each):** A ctx fields (scopeGuardResult, goalExhaustedReason,
evolutionFingerprintChangedThisCycle + lineage carry rules) → B `DerivedFacts` (@Component,
optional tracker+goal-service collaborators, statics where ctx-only) → C consumer rewires +
emission deletions per type-group (lineage-4 → scope pair → irreducible → EVOLUTION_OCCURRED →
fingerprint-changed → GOAL_EXHAUSTED last) → D fitness bridge → E readout renders derived
entries computed (equivalence baseline updated same commit; prompt-pin re-gated) → F harness
migration (fixtures express derived state via ctx fields; pre/post decision comparison across
the library; outcome-probe distributions) → G docs + commit. SUBGOAL_SCOPED_SIGNALS contains
none of the derived types — no persistence deletion in this slice (slice 3's concern).

## T4-BZ-3 implementation plan (settled 2026-07-10 after the reader sweep; execute in order)

### Q-b DECIDED: MISSING_SYMBOL = expiring request with verified expiry, not a derived view

Measured recompute cost: a full declaration scan over the live target (21 java files, 24MB
output/) runs ~5ms — cheap. Cost is not the decider: the derivation's INPUT (LLM citations from
a past planner/analysis output) is itself an event that goes stale; a derived view would need a
cited-symbols ctx field with its own staleness policy — the expiry question one level down, no
gain. Decision: expiring request, horizon 1:1 from today's decay-implied lifetime, PLUS verified
expiry at the tick: a request whose symbol (meta) now has a declaration under output/ expires
early with reason "resolved" (the measured-cheap scan, memoized once per tick, optional
collaborator so plain unit constructions skip it). "Diagnostic goes stale as code changes"
implemented literally.

### Carrier data model (core.lifecycle)

- `ChannelRequest` record: `kind (SignalType), urgency (double), source (String),
  subGoalId (String, nullable = global), age (int), validForCycles (Integer, nullable = until
  consumed), meta (Map<String,Object>)`.
  - `raise(other)`: urgency = min(sum, 3.0), age = min, sources merged — mirrors
    `Signal.reinforce` EXACTLY (Q-c: strengths 1:1; the proposer's ≥2.0/≥3.0 thresholds depend
    on the additive raise across cycles — REPETITION_DETECTED breakout and SCORE_PLATEAU
    escalation only ever fire via accumulation).
  - `tick()`: age+1 only. NO urgency decay — mid-life threshold reads see issue-strength, not
    aged strength; every frozen-state flip this causes is adjudicated individually (the aged
    value doing semantic work is the audit's "threshold accident" class).
- `RequestChannel` immutable value object: `Map<SignalType, ChannelRequest>` (LinkedHashMap
  order). Ops: `issue` (absent→put, present→raise), `consume(kind)` (remove+return),
  `consumeIfVerified` (T2-AO/I11: caller passes the verification predicate; unverified leaves
  the request open), `peek/urgency/hasOpen`, `tick(expiryVerifier)` → returns expired entries
  for T4-BO.
- New `CycleContextData` field `requests` + unified read API **with the migration bridge**:
  `hasOpenRequest(kind)` = channel ∨ stored-map entry; `requestUrgency(kind)` = channel urgency,
  else stored strength (REST-recorded fixtures / recorded states keep working until T4-BZ-5,
  same bridge shape as slice 2). `issueRequest`/`consumeRequest` write the channel AND clear any
  stored twin.

### Expiry horizons (migration pins — computed from each site's (strength, decay) as
cycles-alive-after-issue: first tick ages without decay, then k = ceil((u−0.05)/d) more)

Every request kind gets an expiry backstop equal to its dominant emission site's decay-implied
lifetime, so an unconsumed request dies at the same age it dies today — but VISIBLY (T4-BO
"expired" vs today's silent decay). Consume-on-read handles the normal path (T4-BO aggregate:
the 9-type request family is "always cleared" in the field; expiry should be rare).
Site-variant horizons ride the emission (the channel stores per-request validForCycles):
e.g. MISSING_SYMBOL 1.0/0.25 → 4; PLANNING_THROTTLED 0.6/0.15 → 4, 0.9/0.10 → 9;
REPLAN_THRASH_DETECTED 1.0/0.25 → 4; REPLAN_REQUESTED 1.0/0.2 → 5, 1.0/0.05 → 20, 1.0/0.5 → 2;
STAGNATION 0.7/0.20 → 4; the decay-0.0 pair (EVOLUTION_NOOP, POTENTIALLY_COMPLETE) →
validForCycles = null (until consumed, exactly today's never-decays).

### Consumption map (addressed mechanism per kind; today's clear sites become consume sites)

- REPLAN_REQUESTED → planner: `PlannerAgent:138` consume-on-execute; `AdaptiveExecutionEngine:173`
  AnalysisUnlock revoke = consume; `GoalPlannerService:891-904` dedup-source check = peek
  (source preserved); breaker suppression stays at enqueue (InterpretationService).
- DIVERGENCE_REQUEST → evolution: `EvolutionAgent:96` = `consumeIfVerified(fingerprint
  changed)` — I11 first-class.
- STAGNATION → evolution (`EvolutionAgent:81` divergent path) + AutobuilderService recovery
  bundles (consume).
- REPETITION_DETECTED → evolution (`EvolutionAgent:306` fingerprint-changed clear) + bundles;
  PlannerAgent:156 subgoal-switch clear becomes scope drop.
- SCORE_PLATEAU → plateau-reset sites in AutobuilderService (consume wherever
  `withPlateauCycles(0)` clears it today).
- SUBGOAL_EXHAUSTED → the AutobuilderService:401 handler (consume at the eviction decision);
  DeliberationService:102 re-emission guard = "don't re-issue when open ≥0.5" (peek);
  proposer ≥0.75 read = peek (mode decision does not consume — the handler does).
- EVOLUTION_NOOP → AutobuilderService:701 FULFILLED-promotion check + GoalPlannerService:1068,
  1114 (consume); CycleExecutor:512 + ExecutionEligibilityExplainer:166 = peek.
- POTENTIALLY_COMPLETE → AutobuilderService:374 (consume).
- LOW_CONFIDENCE → recovery bundles (consume); GoalExhaustionDetector / explainer trigger /
  GraphQueryService = peek.
- REPLAN_THRASH_DETECTED → proposer: consume-on-action at the thrash pre-emption rule (today:
  no clearer, decay-only death — the pre-emption fired up to ~4 consecutive cycles while it
  decayed; consume-once + expiry-4 backstop is the honest form; trajectory replay adjudicates).
- PLANNING_THROTTLED → `PlanningThrashSignalAnalyzer:122` re-measure clear = consume with
  reason; PlannerAgent throttle check / explainer / ReplanPatternDetector:50 = peek.
- MISSING_SYMBOL → no consumer today (decay-only): expiry + verified early expiry (Q-b above);
  planner/analysis re-cite = raise.
- `DefaultProblemDetector.resolveSignalIfWeak` (STAGNATION/REPETITION/LOW_CONFIDENCE/
  SUBGOAL_EXHAUSTED): "signal decayed weak → resolve problem" re-expresses as "request no longer
  open" (consumed or expired) — read its exact weak-threshold semantics before rewiring.

### Structural decisions

- **Seeder**: `seedIfEmpty` condition becomes "signal map empty AND no open requests" —
  equivalence-preserving restatement of today's "map empty" (requests leaving the map must not
  make bootstrap re-seeding more frequent).
- **Fitness bridge** (until slice 4 deletes the arithmetic): `requestScore` term in
  `fitnessFor`/`fitnessBreakdown` — for each open scope-visible request of a supported kind:
  W_SIGNAL×urgency + W_RECENCY×exp(−age/5), identical formula, urgency for decayed strength;
  stored-twin double-count guard like slice 2's derivedFactScore.
- **Subgoal scoping (I6)**: the 5 scoped types (STAGNATION, REPETITION_DETECTED,
  DIVERGENCE_REQUEST, LOW_CONFIDENCE, SCORE_PLATEAU) migrate as ONE block; requests carry
  subGoalId; coordinator swap + CycleExecutor save points keep their choreography but the
  GoalEntity metadata slot becomes `subgoalRequests` (requests serialized, old `subgoalSignals`
  read dropped in the same change — restarts restore, clean slate on switch).
- **Proposer emissions**: `DeliberationResult` gains `proposedRequests`; the proposer's
  request-family emissions (REPLAN_REQUESTED/DIVERGENCE_REQUEST) move there; ROLE_* proposals
  stay on `proposedSignals` until slice 4; validator passes both through.
- **Interpretation (I7)**: parser returns requests; InterpretationService enqueues (breaker
  suppression unchanged); DefaultInterpretationAgent's sparse-history strip filters the same
  kinds on the request list.
- **REST (Q-d, decided in slice 1)**: PUT resolves familyOf(name)==REQUEST → enqueue
  (strength=urgency, decayPerCycle translated to a horizon via the lifetime formula); DELETE →
  consume. Other families unchanged this slice.
- **T4-BO re-scope (I9)**: `SignalLifetimeTracker.observe` additionally takes pre/post OPEN-
  request maps + the tick's expired list; request deaths attribute to "consumed" (gone, not
  expired) or "expired". Same `signal_lifetime_observed` event, new reason values —
  `scripts/signal_lifetime_report.py` updated in the same scope.
- **Readout**: channel entries render as REQUEST-family entries (strength=urgency, age=age,
  decay=0.0, source verbatim, meta carries validForCycles); stored-twin wins (bridge). New
  additive `unconsumedRequests()` — open requests with age ≥ 1. `strengthLine`/`fullMap`/
  `snapshot`/`strengthsInOrder` shapes unchanged; content changes only where a request now
  renders issue-urgency instead of aged strength — SignalReadoutEquivalenceTest baseline
  updated in the same commit, prompt surface re-gated (I7).

### Execution order (suite green after each step)

1. Carrier (ChannelRequest/RequestChannel) + ctx field/API/bridge + executor tick wiring +
   seeder condition + fitness/explainer-compatible unified reads + readout composition.
2. Reader rewire — mechanical: every `hasSignal/hasStrongSignal/getSignal` of the 12 kinds goes
   through the unified API (zero behavior change while emitters still write the map).
3. Emitter+consumer migration per type group, smallest first: POTENTIALLY_COMPLETE →
   EVOLUTION_NOOP → REPLAN_THRASH_DETECTED → SUBGOAL_EXHAUSTED → REPLAN_REQUESTED (19 emitters)
   → the scoped-5 block (STAGNATION, REPETITION_DETECTED, DIVERGENCE_REQUEST, LOW_CONFIDENCE,
   SCORE_PLATEAU) + persistence swap → PLANNING_THROTTLED → MISSING_SYMBOL (verified expiry
   last). Each group: emitters → issueRequest, clears → consume, fixtures re-expressed, suite
   green.
4. Parser/interpretation + proposer proposedRequests + REST routing.
5. T4-BO re-scope + script update; readout baseline re-pin + prompt gate.
6. Harness migration: FrozenStateFixtures express request kinds via the channel; frozen-observer
   pre/post decision comparison across the 28-state library (flips individually adjudicated);
   outcome-probe distributions; multi-cycle trajectory replays on the three scenario scripts.
7. Docs: roadmap closure APPEND (per [[feedback-append-dont-rewrite-tickets]]), resume-state
   block, this note updated with deviations.

### As-built deviations (T4-BZ-3 shipped 2026-07-10; plan-vs-delivered)

- Execution collapsed steps 2+3: readers went through the unified API first (sed pass, zero
  behavior change via the bridge), then ALL emitters migrated in one sweep rather than per
  type group — safe because the bridge made each intermediate state read-equivalent and the
  suite was green at both boundaries.
- REPLAN_THRASH_DETECTED consume-on-action at the proposer NOT implemented — the thrash
  pre-emption rule only reads urgency; the request expires on its 4-cycle horizon exactly as
  the decay killed it. The consume-once refinement was optional ("the honest form"), deferred:
  today's re-fire-while-decaying behavior is preserved 1:1, revisit as its own pin release.
- `GoalExhaustionDetector`'s STAGNATION/REPETITION/LOW_CONFIDENCE reads take the context now
  (`hasStrongRequest`), not the copied signal set — its `hasSignal(Set,...)` helper stays for
  the remaining stored types.
- `saveSubgoalSignals` with an empty scoped map now REMOVES the metadata slot (was: silent
  no-op) — without this, a pre-migration `subgoalSignals` blob would re-restore stale stored
  entries every cycle on a graph-continuation run and the bridge would honor them forever.
- Harness: `FrozenCycleObserver` applies `proposedRequests` next to `proposedSignals` and
  renders both in one NAME=value list; new `RequestChannelEquivalenceTest` pins store-form ≡
  channel-form decisions across the full state library (the slice's dedicated gate).
