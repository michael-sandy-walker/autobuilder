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
nonempty) · `SCOPE_OUT_OF_SCOPE` / `SUBGOAL_SCOPE_MISMATCH` (= scope-guard verdict for the
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

## Invariants (each keeps or gains a regression test)

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
