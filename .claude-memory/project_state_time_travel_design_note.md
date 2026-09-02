---
name: project-state-time-travel-design-note
description: "T4-DQ design note (2026-09-02, user-directed) — checkpoint/restore/rewind as REAL FUNCTIONALITY, not a test script: the full state surface inventoried with evidence, the two structural blockers (goal status mutated in place, decision-bearing trackers never persisted), the event-log-plus-snapshot decision and why the user's challenge to snapshot-only was right, the three-bucket state manifest (capture / already-persisted / derived-do-not-restore), two cache modes whose difference is itself diagnostic, and differential replay as the falsifiable completeness test. Read before any checkpoint, rewind, backtracking, or T4-CR work."
metadata:
  node_type: memory
  type: project
---

# State time travel — design note (T4-DQ)

**Status: DESIGN, no code.** Written 2026-09-02 after a user-directed exchange that corrected two
of my positions. This is rationale, never a queue ([[feedback-roadmap-is-the-queue]]); `T4-DQ` in
`roadmap.md` is the ticket.

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
