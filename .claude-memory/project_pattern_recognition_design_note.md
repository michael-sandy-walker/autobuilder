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
