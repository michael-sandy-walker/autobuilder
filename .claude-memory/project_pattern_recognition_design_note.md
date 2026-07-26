---
name: project-pattern-recognition-design-note
description: "Design plan for making pattern recognition / self-recognition MEANINGFUL (2026-07-25, user direction). Diagnosis of the 4 existing analyzer/detector classes with field firing counts (2 of 4 never fired in 235 runs), the key asymmetry (real pattern recognition already exists offline in run_profile.py/drift_aggregate.py while the loop has 4 if/else predicates), the 4 properties meaningful recognition needs, a 6-slice gated plan (P0 replay harness -> P1 disposition -> P2 relative thresholds -> P3 in-loop incremental features -> P4 consultation+action -> P5 derived vocabulary), and per-class keep/refactor/delete dispositions. Read before touching analysis/pattern/ or filing any pattern/self-model ticket."
metadata: 
  node_type: memory
  type: project
  originSessionId: fbdbdb0d-e713-4951-bf51-5004e9098596
  modified: 2026-07-25T21:38:49.825Z
---

# Making pattern recognition meaningful

**Filed 2026-07-25 (user direction):** *"make pattern analysis/recognition meaningful. We really need to
think about the way the system can recognize and improve its runs/processes of itself instead of just
writing some simple classes and write them off as 'built'."*

This note is the plan. It is NOT a queue — tickets are the queue ([[feedback-roadmap-is-the-queue]]).

---

## 1. Diagnosis — what exists today, measured

Four classes in `analysis/pattern/` (601 lines). Field firing counts over **235 substantial runs**:

| class | lines | firings | verdict |
|---|---|---|---|
| `PatternSignalAnalyzer` | 151 | 79 | fires; 4 unrelated single-cycle predicates in one class |
| `ScopeThrashSignalAnalyzer` | 110 | 3 | fires rarely, but ACTS (declares SUBGOAL_EXHAUSTED) |
| `ReplanPatternDetector` | 107 | **0** | wired to a consumer, never triggered |
| `PlanningThrashSignalAnalyzer` | 233 | **0** | inert BY CONSTRUCTION (see below) |

Only 21 of 235 runs emit any `pattern_detected` event at all.

**Three structural defects:**

1. **`PatternSignalAnalyzer` analyses neither signals nor patterns.** It reads cycle-state fields
   (`lastEvolutionFingerprint`, `projectDiff`, `evaluationOutcome`, `problemContext`); the only signal it
   READS is one `hasStrongRequest(STAGNATION)` conjunct. It emits signals; it does not analyse them. Each
   rule is one scalar compared to a constant, mapped to a fixed signal name at a fixed strength.

2. **`PlanningThrashSignalAnalyzer` is dead by arithmetic.** `WINDOW=45min` × `BASELINE_WINDOWS=8` requires
   **6 hours** of run history. Measured: median run **30 min**; 31% reach one window; 6% have one baseline
   window; **0% fill the baseline.** With an empty baseline `hasBaseline` is false, killing `inflowOutlier`
   and `ratioOutlier`, and `baselineUsuallyCompletesSomething` is false, killing `stuckOutflow` — all three
   triggers unreachable. Confirmed: its log line `"Planning throttled (adaptive)"` appears in **0** run logs,
   while `PLANNING_THROTTLED` appears in 1267 — emitted by two plain rules in `PlannerAgent`
   (`planner-skip-ratio`, `goal-backlog-cap`). 233 lines of median/MAD statistics; the signal it nominally
   owns is produced entirely by simpler code elsewhere.
   **Wall-clock is also the wrong UNIT:** cycles are the unit of work and cycle duration varies ~15s→60s+
   with host load, so 45 minutes means a different amount of work every run
   ([[feedback-relative-thresholds]] violated twice: fixed constant, and constant in the wrong dimension).

3. **No outcome feedback anywhere.** Nothing measures whether a firing IMPROVED anything. The two dead
   detectors were never calibrated because they never ran. Unexplained constants throughout: severity
   weights `0.65/0.35`, `SEVERITY_THRESHOLD=0.50`, `LOOKBACK=6`, `MIN_SAMPLES=4`,
   `CONSECUTIVE_THRESHOLD=3`, strengths `1.0/0.7/0.8/0.6`, decays `0.15/0.20/0.10/0.12`.

---

## 2. The key asymmetry (this is what makes the plan cheap)

**Real pattern recognition already exists in this project — offline, on the harness side.**

- `scripts/run_profile.py` (T4-CI): an **11-feature vector per run** + a labeled healthy envelope +
  **median/MAD outlier detection**, direction-aware. Validated: 3/3 known-defect corpora separated
  (phase 1); on 2026-07-25 it read **0/11 flags on both GOAL_FULFILLED runs vs 6/11 on the build-failed
  churn run**.
- `scripts/drift_aggregate.py` (T6-B): post-productivity tail distribution over 237 runs; independently
  flagged the same churn run T4-CI's envelope flagged.

**The feature vocabulary is already the pattern vocabulary — and it is already trajectory-shaped:**

```
merge_rate · reject_rate · inject_rate · build_fix_rate · skip_rate · proposal_rate
planner_only_share · max_inject_refires · inject_refire_share · merge_drought_share · tail_share
```

`merge_drought_share`, `tail_share`, `inject_refire_share`, `planner_only_share`, `max_inject_refires`
are exactly the "am I in a bad shape?" patterns the in-loop classes lack. **The loop cannot see any of
them.** They are computed post-hoc, per run, by a script, for a human.

So the work is NOT "write better detector classes". It is: **close the gap between what the harness
already knows about a run and what the loop knows about itself, and make every detector earn its place
against recorded outcomes.**

---

## 3. What "meaningful" requires — four properties

1. **Relative, not absolute.** Thresholds derived from this run's own history and/or the run population,
   never fixed constants. (The envelope machinery exists.)
2. **Temporal.** A pattern is a trajectory over a window of cycles, not a predicate on one instant.
   "Peaked then slid", "fix-then-revert loop", "K cycles on this subgoal without promotion",
   "injections re-firing the same token".
3. **Outcome-linked.** A pattern is only worth recognizing if it PREDICTS something. Ground truth exists
   at scale: terminal, smoke, `score_delta`, drift tail, per-run labels. Every detector must show that
   firing predicts a worse outcome — otherwise it is decoration.
4. **Eventually derived, not authored.** The end state discovers recurring shapes from the corpus instead
   of a human writing one class per pattern. This is T4-C's north-star half, and it is the LAST rung, not
   the first ([[project-introspection-as-north-star]]).

---

## 4. The plan — 6 slices, each with its own gate

**P0 — Detector replay harness (the instrument; no gate, it IS the deliverable).**
Replay any detector predicate over the recorded corpus offline: reconstruct the per-cycle inputs from
`behaviour.ndjson`, produce a firing timeline per run, and join to that run's outcome (terminal · smoke ·
drift fields · label). Answers, for any candidate or existing rule: *how often would this fire, on which
runs, and did those runs end worse?* Deterministic corpus replay is this project's preferred validation
([[feedback-replay-over-batches]]). Precedents to reuse: `drift_aggregate.py`'s event decoding,
`run_profile.py`'s feature extraction + envelope, `run_labels.json` ground truth.
*Deliverable: `scripts/detector_replay.py` + stdlib tests.*

**P1 — Disposition pass on the existing four (gate: P0 exists).**
Evaluate **per RULE, not per class** (`PatternSignalAnalyzer` is four independent rules). For each:
precision/recall against bad outcomes, firing rate, and whether the signal it emits has a consumer that
changes behaviour. Then keep / recalibrate / delete, with the evidence recorded on the ticket. Rename what
survives to what it actually does. **No new detectors in this slice.**

**P2 — Population-relative thresholds (gate: P1 says a rule survives).**
Replace the surviving rules' fixed constants with envelope percentiles from the frozen baseline
(`run_health_baseline.json`), the way `run_profile.py` already does. Constants only where they encode a
measurable environmental fact.

**P3 — In-loop incremental features (the real gap; gate: P2 landed for ≥1 rule).**
Compute the run-profile feature vector **incrementally, per cycle, inside the loop** instead of post-hoc in
a script. This is mostly a re-homing of existing arithmetic: the loop already emits every event the
features are derived from. Output = derived facts the loop can consult ("merge drought at p90 of the
healthy envelope"), not new stored signals ([[project-t4bz-design-note]]: derived views, not a signal store).
**Harvest from the corpse:** `PlanningThrashSignalAnalyzer`'s inflow/outflow-ratio idea is GOOD — it is
only dead because it is measured in wall-clock over an unreachable baseline. Recomputed per-cycle against
the run's own history + the population envelope, it becomes exactly a P3 feature.

**P4 — Consultation + action (gate: P3 + evidence it changes an OUTCOME, not that it fires).**
The loop reads its own position against the envelope at deliberation/analysis time and acts (replan,
preserve peak, early-stop, re-scope). This is **T4-CI phase 3**, whose gate was measured OPEN on
2026-07-25 (0/11 vs 6/11 separation). Apply the T4-CP lesson explicitly: a mechanism must show it moved an
outcome, not merely that it fired ([[feedback-activity-not-progress]]).
**Goodhart guard:** the loop must never select on the independent referee (smoke). Internal features only;
smoke stays the outside judge ([[project-first-perfect-smoke-score]] / T4-CP's filed rationale).

**P5 — Derived vocabulary (research; gate: P4 shows a closed loop that helps).**
Discover recurring shapes from the corpus — cluster run/cycle feature trajectories or event n-grams — and
surface candidates a human promotes into named patterns. Only here does the system stop needing a
hand-authored class per pattern, which is T4-C's live residue. Do NOT start here.

---

## 5. Disposition of the four existing classes (preliminary — P1 decides with evidence)

| class | preliminary disposition | rationale |
|---|---|---|
| `PatternSignalAnalyzer` | **KEEP, split, rename** — validate each of its 4 rules separately in P1 | it actually fires (79); but it is 4 unrelated rules in one class, and the name describes its output type, not its operation. Its own comment `"reuse existing stagnation framing until you introduce a dedicated signal type"` is the codebase admitting T4-C's gap. |
| `ScopeThrashSignalAnalyzer` | **KEEP as-is (maybe rename)** | honest and bounded: counts consecutive `OUT_OF_SCOPE` and routes to the rescue ladder (T2-EK). Low firing (3) but high consequence. It is a counter with an actuator — no pretence of pattern analysis. |
| `ReplanPatternDetector` | **RECALIBRATE or DELETE (P1 decides)** | 0/235 despite being fully wired to `RuleBasedDeliberationProposer`. P0 replay can compute what `SEVERITY_THRESHOLD`/`LOOKBACK` WOULD have fired and whether those runs ended worse. If no setting both fires and predicts, delete. |
| `PlanningThrashSignalAnalyzer` | **DELETE the implementation, HARVEST the idea into P3** | inert by construction; `PLANNING_THROTTLED` is already owned by `PlannerAgent`'s two rules, so deletion loses no capability ([[project-three-disciplines-lens]] delete-subsumed). The inflow/outflow ratio is worth keeping — as a P3 per-cycle feature, not as a 6-hour wall-clock baseline. |

### 5b. P1 RESOLVED for `ReplanPatternDetector` — field-measured 2026-07-26 (verdict: DELETE + HARVEST)

The P1 table above left this class as "RECALIBRATE or DELETE (P1 decides)" and expected P0 replay to
decide. **Replay could not** (T6-D's stated limit: graph-side inputs), so T5-BH instrumented the five
early-outs instead and two live runs answered it. Full evidence on the T5-BH ticket; the parts that
change THIS plan:

- **The decisive number is a zero, not a firing:** across 135 cycles in two runs, the fingerprint
  filter and the severity threshold were reached **0 times**. So no P0-style "what would threshold X
  have fired" question was ever answerable — the run never gets that far. A recognizer can be inert
  because of a gate BEFORE its logic, and a threshold sweep would have silently modelled a code path
  the loop never enters.
- **Which gate blocks is run-dependent** (run 2: 60 sample-minimum / 5 throttle · run 3: 14 / 53), so
  "the cause" is not one constant. Two independent structural blocks, neither tunable into relevance.
- **A sibling gave a free control arm:** `PlannerRepetitionHeuristic` runs the same query at floor 3
  and fires 1 time in 97 runs. **Look for an existing near-duplicate mechanism before building a
  calibration experiment** — it may already have run the experiment for years. Added to §6.
- **The harvest (for P3/P5), and a substrate gap it names:** planner thrash is repetition against the
  same *planning target*, but the graph records only the goal that was CURRENT when the planner ran
  (`PlannerActionRegistrar:33-35` → `Action.targetsGoalId`), never the goal planned INTO. Live graph:
  15 planner actions over 9 target goals, max 3 each. So per-target thrash is **not expressible from
  the graph today** — a P3 feature needs the parent id persisted first. This is the concrete form of
  §3's "patterns must be derived, not hand-written per class".

**P2 status (population-relative thresholds):** still gated on ground truth, now **9 runs** in
`scripts/run_outcomes.json` (6 at the start of 2026-07-26, +3 from that day's series). Countdown, not
a wall — re-run `detector_replay.py` as it fills.

Also housekeeping found 2026-07-25: **all four classes import `core.lifecycle.Signal` and none use it** —
dead imports left by the T4-BZ migration. `Signal` itself legitimately survives as a 23-line read-only
readout carrier (mutate/decay/reinforce deleted), so the import is stale, not the class.

---

## 6. Anti-patterns this plan exists to prevent

- **Writing another detector class as the first move.** That is what produced 601 lines of which 340 have
  never executed.
- **Shipping a detector without an outcome check.** "It fires" is not evidence
  ([[feedback-activity-not-progress]]); worse, two of these never even fire.
- **Fixed constants in the wrong unit.** Wall-clock where cycles are the unit of work.
- **Calling it "built" because a class exists.** The capability test, not the code-identity test
  ([[project-introspection-as-north-star]] 2026-07-25 rule).
- **Letting the loop optimise its own referee.** Internal features only; smoke stays outside.

---

**Added 2026-07-26 (from the `ReplanPatternDetector` verdict):**

- **Never sweep a threshold before proving the code path is reached.** A gate earlier in the method
  can make the whole calibration question moot; instrument the early-outs first. An inert recognizer
  usually fails *before* its logic, not inside it.
- **Look for an existing near-duplicate mechanism before designing a calibration experiment.** Two
  consumers of one query with different constants are a natural control arm that has already been
  running in the field for months, for free.
- **A field instrument must log at INFO or emit a behaviour event, never `log.debug`** —
  `com.walpro.autobuilder` is pinned to INFO, so 183 `debug` calls across 65 files reach no field log.
  See [[project-log-and-backup-conventions]]. This plan's whole premise is noticing silence; an
  instrument that cannot speak is the failure it exists to prevent.

## 7. What the YOU+ME outer loop actually did — and what that specifies

*(Added 2026-07-25 on user request: "analyze what YOU+ME did in the previous months/weeks and maybe use
that for the overall design we're heading at." The north-star note already argues the outer loop IS the
inner loop one level up; this section makes that concrete with measurements instead of intuition.)*

### 7.1 The measured shape of our own process

**Effort split (git, last 6 weeks):** docs/chore share of all commits rose **26% → 40% → 51% → 55% → 51%
→ 63%**. More than half of the outer loop's work product is *recording state*, and the share is growing.
**Deletion discipline:** only 25 of 477 commits (5%) removed/reverted/retired anything.

**The 35 distilled `feedback_*` rules — grouped by the error class each prevents:**

| class | n | examples |
|---|---|---|
| **Epistemic hygiene (don't fool yourself)** | 8 | verify-population-before-claim · verify-at-decision-timestamp · verify-diff-is-substantive · activity-not-progress · replay-over-batches · llm-probe-realistic-context · calibrate-not-flip · no-defensive-recast |
| **Record integrity** | 7 | append-dont-rewrite · ticket-definition-of-done · roadmap-is-the-queue · no-per-run-memory-files · ticket-tiering · no-co-mention-pairing · no-invented-labels |
| **Abstraction / generality** | 9 | ship-full-scope · one-invariant-one-home · relative-thresholds · capability-gap-not-prohibition · ecosystem-gated-heuristics · no-hardcoded-ecosystem-data |
| **Execution protocol** | 6 | sonnet-for-batch-tickets · run-analysis-log-order · no-auto-commit |
| **Domain / style** | 5 | runs-are-variant-by-design · stabilize-before-tuning-signals · neutral-vocabulary |

**43% of everything we learned (15/35) is about epistemic hygiene and record integrity** — not about
algorithms, architecture, or the target domain. The dominant lesson of months of work is *how to avoid
believing things that are not so, and how to keep the record reconcilable with reality.*

### 7.2 The moves that actually worked

Recurring, and each one earned its place by preventing waste:

1. **Gate before building.** State a decision rule, measure, let it decide. On 2026-07-25 alone: T4-CP
   (negative — mechanism not built), T4-H (premise refuted, 1672 obs), T4-CI phase 3 (gate open, but the
   measurement exposed an inert instrument), T4-BP (already built). **Four pickups, zero builds needed.**
   Historic precedents: T3-G (1151 samples), T4-CE slice 2, T2-CR, T4-BQ.
2. **Verify against the population before claiming.** Dedupe first, then assert (4132 file copies → 648
   runs). Repeatedly caught false claims, including four of my own regex passes in one evening.
3. **Verify at the decision timestamp, not the final artifact.** Separated live defects from
   already-fixed history (the RootConvergenceTracker tails).
4. **Dispose with evidence, and keep the negative.** A refuted premise is a deliverable; record it with
   the numbers and a re-entry condition.
5. **Delete what does not earn its place** — but rarely (5% of commits), which is why inert code
   accumulated (two pattern detectors, 340 lines, 0 firings in 235 runs).
6. **Reconcile the record with reality.** M-D audited 360 closed tickets; found overstatement (`[x]` on
   unbuilt work) AND understatement (T4-BP shipped but marked `[~]` for 7 weeks).

### 7.3 The failure modes we kept hitting

- **Claiming from partial evidence** (the single most frequent correction).
- **Reading state from prose instead of reconciling it against code** — the whole closure-kind thread; the
  code-identity vs capability test error on T4-C.
- **Instruments going inert unnoticed** — T4-CI phase 2 produced *zero* comparisons for 8 days because its
  baseline lived in `/tmp`; `PlanningThrashSignalAnalyzer` never fired in 235 runs. **An instrument that
  abstains looks exactly like one with nothing to say.**
- **Activity mistaken for progress** — a mechanism firing, or existing, taken as it helping.

### 7.4 The mapping — this is the design input

| move that worked for YOU+ME | system analogue | status in the loop today |
|---|---|---|
| observe the run through a structured summary, never raw logs | behaviour events → derived facts | **exists** |
| **gate: measure before acting** | — | **MISSING (highest value)** |
| verify a claim against the population | run-profile envelope | **exists, but OFFLINE only** |
| dispose with evidence; keep the negative | criteria / `CriterionResult` verdicts | partial |
| delete what does not earn its place | — | **MISSING** |
| reconcile belief against reality | — | **MISSING** |
| detect that an instrument has gone silent | — | **MISSING** |

**Three consequences for the plan in §4:**

1. **P0 (the replay harness) is confirmed as the right first slice** — it is the mechanization of our
   single most valuable move (gate before building). Not a coincidence: it is the move we used four times
   in one evening to avoid four unnecessary builds.
2. **Add a liveness property to every detector, at P1.** Our worst recurring failure is silent
   inertness. Any recognizer must report *"I evaluated N times, fired K"*, and K=0 over a population must
   be a visible state, not silence. Cheap, and it would have caught both dead detectors and the empty
   health envelope years earlier in loop-time.
3. **Do NOT internalize our bookkeeping overhead.** 63% docs share is the cost of maintaining a
   hand-written record. The system should internalize the *decisions* and **derive** its record from
   observed state rather than storing prose about it — the T4-BZ derived-views principle applied to the
   self-model ([[project-t4bz-design-note]]). If the loop ends up writing its own roadmap prose, we have
   copied the wrong half of our process.

**The honest limit of this analogy:** our loop works largely because a human supplies the epistemic
hygiene — the "prove it" reflex — and the local model demonstrably cannot supply it yet (T4-CE slice 2:
6/11 classification, 0/3 mechanism localization). So the near-term target is NOT "the loop reasons like
us." It is: **encode the moves that are mechanical (gate, envelope comparison, liveness, disposition
records) and leave the judgment calls escalated to the human** — the same split M-C drew for the
development loop.
