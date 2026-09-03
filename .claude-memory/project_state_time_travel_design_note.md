---
name: project-state-time-travel-design-note
description: "T4-DQ design note and BUILD RECORD (2026-09-02, user-directed; ALL FOUR SLICES SHIPPED the same day) — checkpoint/restore/rewind as REAL FUNCTIONALITY, not a test script: the full state surface inventoried with evidence, the two structural blockers (goal status mutated in place, decision-bearing trackers never persisted), the event-log-plus-snapshot decision and why the user's challenge to snapshot-only was right, the three-bucket state manifest (capture / already-persisted / derived-do-not-restore), two cache modes whose difference is itself diagnostic, and differential replay as the falsifiable completeness test. Read before any checkpoint, rewind, backtracking, or T4-CR work."
metadata:
  node_type: memory
  type: project
---

# State time travel — design note (T4-DQ)

**Status: ALL FOUR SLICES SHIPPED 2026-09-02** (design written the same day, after a user-directed
exchange that corrected two of my positions). This is rationale, never a queue
([[feedback-roadmap-is-the-queue]]); `T4-DQ` in `roadmap.md` is the ticket and carries the
as-built record. What follows is the design as reasoned, with a build section at the end recording
where reality differed.

## What this is

Capture the loop's complete state at a point, restore it later, and continue forward from there —
including forward along a DIFFERENT path than the one originally taken. Rewind plus replay plus
branch. **A product capability, not a harness**: the loop itself should be able to back out of a
bad path, which is a different thing from us being able to reproduce one.

**Not** [[project-project-portability-vision]]'s `T4-CR`, which moves a phase between machines.
This is time travel *within* a lineage. They share machinery and should not be conflated.

## Why it is worth building — the case is in our own data

Run `2026-09-01_23-11-25`: 53 cycles, 25 rollbacks, **3 merges**, zero controllers, dead on the
`inference variable T has incompatible bounds` grind. Two earlier runs died the same way
(`2026-08-31_05-47-22`, `2026-08-31_23-26-24`). A loop that could rewind to its last good
checkpoint and take a different path saves those runs outright. `T6-M` records that **86% of
failing runs never reach a working artifact at all**.

Secondary payoffs: speculative exploration WITHOUT `T4-AO`'s blocker (sequential rewind needs no
second GPU); on-demand reach into rare live conditions (`T2-FH` is unexercised in 16 runs because
its trigger is a run SHAPE); and "what state was I in when this went wrong" becomes answerable
in-loop, which is [[project-introspection-as-north-star]].

## The state surface, measured 2026-09-02

| Layer | Captured today | Rewindable |
|---|---|---|
| `output/` tree | ✅ EVERY cycle → `snapshots/<cycleId>/` + `autobuilder-snapshot-state.json` stamp | yes |
| `mutations/<cycleId>/meta.json` | ✅ the ENTIRE `CycleContextData` serialized per evolution cycle | yes |
| Neo4j orchestration history | ✅ append-only, `cycleId`-tagged on evolution/evaluation/planner/merge | yes, by selection |
| Neo4j **goal state** | ⚠️ mutated in place (`SET g.status = 'COMPLETED'`), no transition history | **BLOCKER 1** |
| `cache/` | ✅ on disk, deliberately survives resets (`SystemResetConfig`) | yes, and see modes |
| decision-bearing trackers | ❌ never persisted; `RunResumeService` resets them deliberately | **BLOCKER 2** |

**The expensive half already runs.** Full-tree copies every cycle plus the complete cycle context
in `meta.json` means most of the volume is already being written. This is far closer than it looks.

## Decision 1 — event log as source of truth, snapshots as fold accelerators

**The user challenged my snapshot-only recommendation and was right.** I had claimed event sourcing
risks the fold diverging from live state. Correction, recorded because the reasoning generalises:

- The completeness hazard is **symmetric**. "Every state field enumerated" and "every transition
  emitted" are the same requirement in dual form. Neither design escapes it.
- Divergence is not a property of event sourcing. It is a property of **dual-writing an event log
  beside authoritative mutable state** — what a retrofit looks like DURING migration. Where the
  fold is authoritative there is no second model to diverge from.
- The asymmetry runs the OTHER way: an event log can be folded continuously and compared to live
  state every cycle, so completeness is tested constantly. **Snapshot completeness is only ever
  exercised at restore — the worst possible moment to discover a gap.**

So: event log authoritative, periodic snapshots as fold accelerators (folding from cycle 0 gets
expensive). This also COMPLETES AN EXISTING PATTERN rather than importing one — the orchestration
graph is already append-only and cycle-tagged; only the state side is mutable.

## Decision 2 — the manifest has THREE buckets, not one

"Persist the in-memory holders" needs refining, or we reintroduce bugs the design already fixed.

1. **Decision-bearing — MUST capture.** `RecurringDefectTracker` (shape map, `missingTypeTotal`,
   `sequence`), `FixAttemptLedger` (`runLevelFires` + `ledger`), `RootConvergenceTracker`
   (`lastProgressCount`, `cycleLimit`, `budgetMinutes`), `RejectedProposalLedger`,
   `DeferredMutationLedger`. These GATE BEHAVIOUR, so a wrong restore silently changes decisions —
   the dangerous failure mode, not the obvious one.
2. **Already persisted — reuse, do not rebuild.** Hash trackers (own store file), the cycle context
   (`meta.json`), the tree (`snapshots/`), graph history.
3. **🔴 Deliberately derived per cycle — RESTORING THESE IS A BUG.** `ContextLineageTracker` states
   it outright: "per-cycle derived-fact carriers — never carried forward" (scope-guard results,
   fingerprint-changed flag, T2-DD streak math). **"Derived — do not restore" must be a DECLARABLE
   category in the manifest**, not an omission, or the next author re-adds them as a "fix".

**Registration contract:** a state holder that declares no bucket fails the build. That is how
blocker 2 stops recurring — the inventory cannot silently fall behind the code.

## Decision 3 — two cache modes, and their DIFFERENCE is the instrument

- **faithful** — cache restored with the checkpoint; for reproducing a run exactly.
- **fresh** — cache reset; for testing whether behaviour depends on it.

If outcomes differ between modes, the cache is **decision-bearing rather than a pure
optimisation** — which nobody currently knows and which is worth knowing on its own. Do not assume
neutrality: cached resolutions avoid re-deriving, so a miss means an LLM call or a registry lookup.

## Decision 4 — completeness must be FALSIFIABLE, not asserted

An enumerated inventory you hope is complete is exactly the failure this project keeps re-learning.
Make it a property instead:

> Record LLM responses per cycle. Checkpoint at N, run to M, restore N, replay the SAME recorded
> responses, assert the state at M is identical.

Any uncaptured state **that matters** shows up as divergence. Response recording also buys
deterministic replay of live runs on its own, which is worth having regardless
([[feedback-replay-over-batches]]).

## Slicing, each gated on the last

1. **Goal-status transition history** (blocker 1) + **decision-bearing holders persisted, in the
   graph** (blocker 2). Nothing else is possible until state is capturable at all.
2. **Round-trip proof** — differential replay above. **Backtracking is unsafe without it**; a
   restore that is subtly wrong is worse than no restore.
3. **First consumer — rewind-on-grind.** One policy, measured against the three known catastrophic
   runs. ([[feedback-instrument-needs-an-actuator]]: the capability ships with its actuator.)
4. Only then: branching, forking, speculative exploration.

## Open questions, deliberately unsettled

- Checkpoint cadence: every cycle, or decision points only? Full graph exports per cycle on top of
  existing tree copies is real storage.
- Does a rewind fork a lineage id (`T4-CJ`) or continue the existing one? Forking is safer and
  matches `T1-FM`'s origin-stamp discipline, but multiplies lineages.
- How does a rewind interact with the run budget and the convergence brake — is rewound time spent?
- What does the loop consult to DECIDE to rewind? That is slice 3's real content and it is a
  self-model question, not a plumbing one.

## What building it changed — recorded 2026-09-02

- **The state surface was 40 holders, not 5.** A reflection scan over the compiled beans (Spring
  stereotype + mutable non-injected instance field) found 40 singletons carrying run state; 13 are
  decision-bearing. `AutobuilderService` alone holds eleven brake counters plus the pause flags —
  **more decision-bearing state than the five named trackers combined**, and nothing had ever
  persisted any of it. The design's list was a starting point, not an inventory; that is exactly
  why the registration contract, not the list, is the deliverable.
- **The three buckets held, but one needed widening.** No fourth bucket was required (the cache is
  handled by `CacheMode` over the on-disk `cache/`; in-memory memos are DERIVED). But
  `DERIVED_DO_NOT_RESTORE` had to be widened from "derived per cycle" to also cover **state a
  rewind may not REFUND** — wall clock, the run budget, and the rewind budget itself. Found by
  `RewindPolicy` contradicting itself: declared CAPTURE, with a `restoreState` that deliberately
  did nothing, which the fingerprint then correctly reported as a divergence on every rewind.
  **State can be real, durable and decision-bearing and still belong in the do-not-restore bucket.**
- **Decision 4 came out stronger than designed.** Instead of a one-off round-trip harness, the
  per-component `StateFingerprint` is recorded in every checkpoint and **recompared at every
  restore**, so completeness is exercised continuously in production rather than at a test's
  convenience. Both halves were proved falsifiable by breaking them on purpose: removing one
  `@StateHolder` annotation turns the contract test red, and a holder that captures but does not
  restore is caught as `state diverged after restore: holder:<key>`.
- **Blocker 1 needed a half the design did not name: EXISTENCE.** Restoring statuses is not enough
  — a goal the planner thought of after the cut point had not been thought of yet. It is rewound
  OUT (`Kind.REMOVED`, graph tombstone, excluded from `findAll`), never deleted.
- **A plain rewind would deterministically repeat itself.** Prompts go out at temperature 0, so the
  same restored state asks the same question and gets the same answer. What breaks the repeat is
  that rewound-out goals leave the live tree but stay in the graph's description corpus, which is
  what the planner's duplicate check reads — the plan that led into the grind stays known and stays
  refused. Worth remembering before slice 4: **backtracking needs something to differ, and state
  restoration alone guarantees the opposite.**
- **Slice 3 needed no new constant.** The convergence brake already computes the relative judgement
  (`ConvergenceTrend`, merges in the window against the run's own earlier rate) and had exactly one
  response available. Rewind-on-grind is a second response taken before the terminal one.
- **The ticket's own figures were wrong and are corrected on it.** Run `2026-09-01_23-11-25`:
  5 merges and ZERO rollback events, not "25 rollbacks, 3 merges". The real shape is starker —
  42 of 53 cycles (79%) landing nothing, and 60 of 65 (92%) in `2026-08-31_05-47-22`.
- **Still unanswered, and now ASKABLE:** whether the resolution cache is decision-bearing. Both
  modes are implemented; nobody has yet run the same rewind `FAITHFUL` and `FRESH` and compared.
  That experiment is the design's decision 3 and it has not been done.

## Slice 4 as built — branching, and the two questions it settled

- **A branch is a CYCLE RANGE.** Ids are monotonic and a branch is contiguous in them, so every
  checkpoint, snapshot and behaviour event already records membership. No new identity column.
- **A rewind does NOT fork a lineage** — the design left this open and building it settled it.
  `T4-CJ`'s lineageId means "which evolutionary line is this target on" across runs; within-run
  exploration is a different question, and overloading the id would multiply lineages and break
  `T1-FM`'s resume ownership check.
- **The ranking rule was fixed before measurement**: subgoals completed WITH a merge, then merges,
  then a compiling tip, then recency. Merge RATE is recorded and never ranked on — a two-cycle
  branch that merged once would outrank a thirty-cycle branch that merged ten times.
- **The actuator is "end on the best path", and it is NOT `T4-CA` again.** T4-CA picks the best
  tree to ship. Nothing picked the best state to CARRY ON from, so a run that backed out of a good
  path handed its `T4-CD` successor the worse one's goal state.
- **A switch that reads as an absence is a trap.** Disabling branching first made "no fork point"
  indistinguishable from "everything explored", silently removing slice 3's rewind instead of
  slice 4's bookkeeping. **A component's off-switch must be asked about directly, never inferred
  from its answers.**
- **2026-09-03, the operator rewind field defect: the carried cycle context is NOT a holder.** Four
  operator rewinds each restored cleanly and were each undone within ten milliseconds. The holders
  put `systemPaused` back to false, but `AutobuilderService.context` still said `GOAL_FULFILLED`
  from the cycle just backed out of, and the next tick re-ran the fulfilled terminal (re-pause,
  re-preserve, a goal-fulfilled end report written over the REWOUND tree). No cycle ever ran from the
  restored state. The in-loop rewind had the same latent gap for `GOAL_EXHAUSTED`. Fix: one
  `settleAfterRewind` (reasons cleared, result `CONTINUE`, episode flags reset; an explicit
  `user-pause` survives) called by both `attemptRewind` and the new `rewindAsOperator`; the controller
  goes through the service. **Rule: a rewind is restore PLUS settle — anything the scheduler reads on
  its next tick that is not a holder must be reset by hand.** Also added `CheckpointService.position()`
  (the point the loop's state IS, distinct from the newest point on disk; in-memory, rewind-aware),
  exposed on `/autobuilder/checkpoints` as `position` + per-row `here`; the dialog opens on that run
  and badges the row, and Mission Control consumes `rewind_performed` (it used to drop it) and
  re-reads subgoal/mode from `/autobuilder/status`.
- **2026-09-03, the second field defect: RESTORABLE and CONTINUABLE are different facts.** The
  grind policy chose the newest point that had landed a merge; its root goal was already COMPLETED
  because it came from an earlier finished run of the lineage. The restore was faultless and the
  next cycle halted on a finished root, with five older merged points whose root was still ACTIVE
  passed over to reach it. **`merged == true` was the entire candidate test — "this cycle landed
  work" is not on its own a reason to go back somewhere; a point is somewhere to CONTINUE from only
  while something is still left to do there.** One home: `CheckpointService.canContinueFrom`,
  derived from the same goal fold the restore applies, so the prediction cannot disagree with the
  outcome. Unknown status is not treated as settled. **The operator surface deliberately shows what
  the policy may not choose** — returning to a finished state is a legitimate thing for a person to
  want, so it is badged and warned about rather than hidden, the same treatment `unverified` gets.
- **The cross-run index forgets a checkpoint's capture failures.** `Neo4jCheckpointGraphService`
  rebuilds a `CheckpointRecord` with `captureFailures` hardcoded empty, so any point read back from
  the graph claims to be complete whatever it was on disk, and `restorableAcrossRuns` readmits
  points the local listing correctly excluded. Not fixed: persisting it is a schema change.
- **An automatic rewind must not cross a run boundary (user-directed, 2026-09-03; switch off).**
  The finished-root rule alone was not enough: replaying the same decision showed it would still
  have chosen an EARLIER run's point, just a different one. Two separate reaches were happening at
  once, because the checkpoint directory and the cross-run index both outlive the run that filled
  them, so "the newest point that landed work" walks backwards past the run boundary without
  anything marking it. **Crossing a run boundary changes whose work the loop is continuing, and
  that is a judgement for a person looking at the runs, not for a grind detector.** One home:
  `CheckpointService.autoRewindCandidates()` holds BOTH conditions (still-open root, own run), and
  the three policy call sites draw from it; the operator listing applies neither and marks the run
  each point belongs to. `autobuilder.rewind.auto-cross-run-enabled: false`. Replayed on the run
  that failed: the old rule picks a finished earlier point, the finished-root rule picks an open
  earlier point, and the shipped rule finds no candidate at all and halts honestly, which is
  correct because that run had landed nothing of its own.
- **A cut point means nothing without its lineage (2026-09-03).** A reset clears `meta/` and starts
  the sequence at 1, while the tree archive keeps the per-cycle copies — so a pre-reset checkpoint's
  `goalTransitionSeq` indexes into an unrelated history. Folding a pre-reset seq of 125 against a
  77-entry log returns the CURRENT run's goals at their latest state and reports **zero transitions
  undone**, so the restore reads clean and hands back one lineage's tree beside another's goals.
  Neither the finished-root rule nor the empty-fold guard catches it. Fixed with
  `GoalTransitionLog.foldAtForLineage`, which folds a foreign point against **that lineage's own
  transitions from the graph** (they survive the reset, unlike the file) and returns
  `historyAvailable=false` when there are none — which the rewind turns into a refusal. This is what
  made the operator listing safe to widen beyond the current lineage.
