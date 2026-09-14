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

## 2026-09-14 — the first CROSS-LINEAGE rewind, and the two gaps it found

Attempting to restore run `2026-09-13_09-44-03` (a different run AND a different lineage) to
re-run a defect on a new build. It worked, but only after two fixes; both are the same shape —
**machinery that is correct within a run and silently under-powered across one.**

- **🔴 GOAL EXISTENCE IS NOT IN THE TRANSITION LOG. Measured: of 4982 goal nodes, 64 carry a
  `CREATED` transition — 1.3%.** Within a run this is invisible, because the live goal map already
  holds every goal and the fold only has to decide which to REMOVE. Across a lineage the map
  belongs to a different run, so existence must come from the record — and the record does not
  have it. First attempt restored the right tree, rewound out 17 goals, restored ONE status, and
  left the loop with **no current subgoal at all**; the checkpoint's own `currentSubGoalId` had a
  single transition, at seq 14, four past the cut, so the fold never knew it existed.
  - **Fixed by reading existence from the fact that was always written.** Every goal node carries
    `created`, so `goalsCreatedAtOrBefore(lineageId, at)` answers "what existed at the cut" from
    the graph, and the fold is widened with it. Status at the cut comes from recorded facts only:
    the last transition at or before the cut; else the `fromStatus` of the earliest transition
    AFTER it (a goal given up at seq 14 was ACTIVE at seq 11, and the record says so); else the
    goal's own status. A goal with no `created` stamp is EXCLUDED rather than assumed old —
    excluding it loses a goal, including it invents one.
  - **Result: divergences 6 → 1**, goal statuses restored 0 → 3, and the pointer came back.
- **🔴 RESURRECTION WAS KEYED ON STATUS WHERE REMOVAL WAS KEYED ON EXISTENCE.** `foldOver` adds
  every goal it sees to `existing` but records a status only when the transition carries one, so
  the sets differ — and `restoreGoalStateAsOf` resurrected over `statuses.keySet()` while removing
  over `existing`. **The fold could delete a goal it was unable to bring back.** Now a union.
- **🔴 The cross-run index claimed a completeness it never had** — the known-and-unfixed note above
  said "persisting it is a schema change". It is two properties: `captureFailuresJson` is now
  written and read back, so `complete()` stops being hardcoded true for every point read from the
  graph. A point that predates the property returns empty, which is the honest answer for a record
  that never wrote one — not a completeness claim.
- **⚠ STILL DIVERGING, not chased: `holder:fixAttempts`.** One decision-bearing holder does not
  come back identically. Reported rather than hidden, which is the fingerprint working.
- **📋 Operational, learned the hard way:** `POST /autobuilder/evolve` is a **no-op while the
  scheduler is disabled** — it answers *"Evolution cycle triggered."* and runs nothing, so
  `dev-local` cannot drive cycles by hand. Use `dev-auto`, and rewind through
  **pause → rewind → resume**: the scheduler takes the control-loop lock within seconds of boot
  and the rewind is correctly refused while it is held. An explicit user-pause survives the rewind
  by design, so the resume is required.

## 2026-09-14 later — a rewind restores a STATE only if it restores what the next tick READS

Staging `T2-ES` one cycle short of its threshold (the user's framing: start a run just before
the ticket can be tested, not hours before) found that every rewind so far had settled the loop
from the WRONG context, and two smaller things underneath.

- **🔴 The carried cycle context was never part of a rewind.** Holders, goals, tree and cache
  came back; then `settleAfterRewind(context, ...)` cleared the halt fields on the CURRENT context
  — the record of the cycle just backed out of — because the carried context is not a holder and
  nothing had captured it. `meta.json` looked like the capture (bucket 2 above says "reuse it") and
  is not: it is written after evolution and before evaluation, so it carries the PREVIOUS cycle's
  verdict, and it is cleared at the run boundary. Consequence of a year of rewinds: every
  consecutive-cycle streak restarted at zero, and the first prompt after a rewind described the
  abandoned tree's rejection reasons and build state. **The rule: a restore is only as complete as
  what the scheduler reads on its next tick, and "not a holder" is not "not state".** Now the
  checkpoint writes `context.json`, the archive keeps it as `__context.json`, the rewind returns it
  and both paths settle from it; absence and supplied-not-captured are both reported.
- **🔴 A `Map` keyed by a record does not survive JSON.** `RequestChannel.open` keys serialized as
  `SignalType[name=X]` and deserialized through the single-String constructor, so one round trip
  made every open request invisible to `open.get(kind)` until the post-cycle tick re-keyed the map
  by each value's kind. Every `meta.json` on disk carries `SIGNALTYPE[NAME=SIGNALTYPE[NAME=X]]` from
  repeated trips. It only mattered once a context was READ back — the first cycle after a rewind,
  blind to its own signals. Fixed with a key (de)serializer that also unwraps the legacy forms.
  **Check any persisted `Map<RecordType, ...>` the same way before trusting a restore of it.**
- **The reserved prefix is a rule.** The restore deleted `__cache` and two named files from the
  staged tree; the first supplied context came with a third `__` file and it was planted into
  `output/`. Strip by prefix, never by list.
- **A reconstructed context is checked by what the model was SHOWN.** The 09-13 defect run's
  cut-point context had to be rebuilt from its logs (55 fields log-derived, 43 recomputed at the
  next tick anyway, 8 cleared, one RE-KEYED). The check that made it trustworthy was a block-by-block
  diff of the rewound cycle's evolution prompt against the original run's next prompt: same
  warning, same interpretation, same diagnostics, same open problems, same two goals subsumed by
  the planner. How the file was assembled is provenance; what rendered from it is evidence.
- **What the staged rewind then proved:** the escalation fired one cycle after the cut, the model
  had already taken one of the offered paths in that very proposal, and the loop could not see the
  `#Delete` that took it — `T1-GB`. Two cycles after the cut the grind was over. **One rewind with
  the right context replaced the "wait for the next natural grind" watch item; that is what the
  time machine is for, and it is the first time it has been used that way.**

## 2026-09-14 latest — decision 4 had stopped covering the state, one change after it proved itself

The carried context above was added as a fifth piece of captured state. `StateFingerprint`
digested four: tree, pointer, goals, holders. Nobody extended it, so for the length of one
session a context captured partially, corrupted on disk, or lost to a lossy round trip would
have restored silently — the exact failure decision 4 exists to prevent, arriving immediately
after decision 4 was credited with catching a real defect on its first field outing.

**The rule this earns: a new state component and the instrument that checks it ship in the same
change.** An instrument that is not extended does not fail loudly; it just quietly stops
describing the state, and it keeps reporting clean while doing so. Ask of any addition to the
capture: which component of the fingerprint covers this, and if none, the change is not finished.

As built:

- **The digest is over the serialised context**, not id-canonicalised the way holder digests are.
  Those are compared across replays that re-create goals under fresh ids; this asks the narrower
  and stricter question of whether THIS context survived a write and a read.
- **Compared only when both sides carry one.** A pre-2026-09-14 point has no claim to check, so it
  is not reported as diverging; a point whose record claims a context that is gone is named as a
  loss instead. Presence is the rewind's business, equality is the fingerprint's.
- **The fixed-point property was measured before shipping and is now pinned in the suite.** All
  fifteen contexts on disk round-trip byte-identically. A `CycleContextData` field that stops
  doing so now turns the suite red rather than surfacing at someone's restore.
- **Field-verified both ways:** the pre-fix reconstructed point restores with no false context
  divergence, and a point this build wrote restores with no divergences at all.

