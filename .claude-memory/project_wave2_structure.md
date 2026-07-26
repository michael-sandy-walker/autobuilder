---
name: project-wave2-structure
description: "Wave 2 restructured 2026-07-26 around the through-line the session exposed: from disposable runs to an accumulating system. Five layers (substrate → portability → reconciliation → consultation → yardsticks), what is done, what is next, and the gates. RATIONALE ONLY — the roadmap is the queue; every buildable item here is a filed ticket."
metadata: 
  node_type: memory
  type: project
  originSessionId: 7473ce0f-7921-4a8b-924c-ddafeab47306
  modified: 2026-07-26T20:07:23.101Z
---

# Wave 2 — from disposable runs to an accumulating system

**Status of the old plan:** wave 2's spine has been OPEN since 2026-07-25 (T4-AP closed at audit,
T2-EP closed, and the stated fallback T4-CE slice 2 → T4-E was dead on arrival). This note proposes
the replacement, derived from what the 2026-07-26 session actually built rather than from a fresh
guess.

**This file is RATIONALE, never a queue** ([[feedback-roadmap-is-the-queue]]). Every buildable item
below is a filed ticket in `roadmap.md`; if the two disagree, the roadmap wins.

## The through-line

Until tonight every run started blind and every reset erased the record. Three things changed:
runs became **attributable** (lineage/run stamps on every node), history became **durable** (a reset
severs the lineage instead of deleting it), and outcomes became **recorded** (the T6-E ledger).

That turns a scattered set of tickets into one arc: **the loop is acquiring memory across runs.**
Its destination is [[project-introspection-as-north-star]] — a loop with a self-model it consults
before acting. Wave 2 is the substrate for that, and it now has a spine.

## The five layers

### A. Substrate — make experience durable and separable ✅ largely DONE (2026-07-26)
`T4-CJ` slice 2 (run/lineage scoping) · `T1-D` (reset keeps history) · `T6-E` (durable outcome
ledger) · attribution of every node including the ones written by the `semantic-memory-substrate`
dependency. Verified in the field: two lineages, 2169 + 1433 nodes, **0 crossing edges**.

**Residual:** growth is a watch, not a subsystem — one 4-run lineage ≈ 9,600 nodes, so ~100 lineages
≈ 1M, comfortable. Revisit past a few million nodes or if query latency appears in run telemetry.

### B. Portability — move experience between places
`T4-CR` (bundle: store a phase + its lineage subgraph, restore elsewhere) · `T4-CQ` (import and
continue a project the loop did not create). Unblocked by layer A: a lineage is now a selectable
subgraph, so "store a graph" finally has a clean cut to make.

### C. Reconciliation — combine experience
`T4-CN` (cross-graph compatibility) · `T4-CM` (merge workbench) · `T4-CS` (code-layer merge).
All three are heavy, and all three are gated on the same unanswered question: **is there enough
overlap between two lineages for a merge to gain anything?** → `T6-G`.

### D. Consultation — the loop USES what accumulated ⚠️ THE THINNEST LAYER, AND THE POINT
`T4-CJ` slice 3 (planner/analysis consult prior lineages) · `T4-CI` phase 3 (the loop reads its own
health baseline as derived facts) · pattern-plan P3/P4 ([[project-pattern-recognition-design-note]]).

**Nothing in the running loop consumes cross-run knowledge today.** Everything in layers A–C is
plumbing. If the wave stops before D, we have built a museum: a beautifully attributed archive that
changes no decision. [[feedback-activity-not-progress]] is the standing warning — a mechanism
firing is not the same as it helping.

### E. Yardsticks — know whether any of it helps
Shipped: `T6-A` (honest artifact scoring) · `T6-B` (drift aggregation) · `T6-C` (health envelope) ·
`T6-D` (detector replay) · `T6-E` (outcome ledger) · `T6-F` (preserved-copy freshness).
Missing, and filed now: `T6-G` (cross-lineage overlap) · `T6-H` (subtree-granularity outcome
attribution) · `T6-I` (does accumulated knowledge actually improve outcomes?).

## Recommended sequence — cheap measurement before heavy build

1. **`T6-G` — measure the overlap.** The fixture exists for the first time (two same-seed lineages
   sitting in the database). Cheap, offline, and it either justifies layer C or closes it on
   evidence.
2. **One thin consumer in layer D**, whichever `T6-G` says has signal. The cheapest candidate is
   already proven to work cross-run: ecosystem facts (symbol→package resolutions) accumulate on disk
   and are dependency-fingerprint keyed. `T4-CJ` slice 3 reference reads is the graph-side version.
3. **`T6-I` — does it help?** Paired runs on one seed, with and without the accumulated base, N and
   the decision rule stated in advance ([[feedback-replay-over-batches]] permits a live batch only
   for a rare strategic question stated this way — this is one).
4. **Only then** invest in `T4-CR` / `T4-CN` / `T4-CM` / `T4-CS`. Building the merge workbench before
   step 3 is exactly the trap this project keeps re-learning.

`T6-H` slots in wherever "which PART was better" is needed — it blocks `T4-CS` and sharpens P2/P4.

## Tier mapping — the wave is NOT a T6 programme

An early cut of this note filed every gap as a T6 yardstick, which was tier-anchoring: framing
everything as "measure first" is the right discipline but it hides the capability and policy work.
Per layer:

| layer | tier | tickets |
|---|---|---|
| A substrate | T1 / T4 | `T1-D` ✅, `T4-CJ` slice 2 ✅ |
| B portability | T4 | `T4-CR`, `T4-CQ` |
| C reconciliation | T4 | `T4-CN`, `T4-CM`, `T4-CS` |
| **D consultation** | **T4 + T2** | **`T4-CT`** (the capability, previously homeless) + **`T2-ER`** (how far to trust prior-lineage evidence) |
| E yardsticks | T6 | `T6-G`, `T6-H`, `T6-I` |

Two checks that changed the filing:
- **The payoff layer had no owner.** Consultation existed only as `T4-CJ` slice 3, `T4-CI` phase 3
  and pattern P3/P4 — always someone else's last slice, which is exactly why it never gets built.
  `T4-CT` gives it a home, scoped to ONE consumer shipped end to end, not a framework.
- **A T5 explorer ticket was NOT filed because the capability already ships.**
  `OrchestrationGraphExplorerService` already exposes `stampCounts("lineageId")` and composes
  label/lineage/run filters (T5-BG). Coexisting lineages are already browsable there — the raw Neo4j
  browser is what shows them as one blob.

`T6-H` sits on a boundary worth naming: as filed it is offline attribution for comparison (T6). The
moment the LOOP consumes per-subtree outcomes to make a decision, that consumption is T4 work under
`T4-CT`, not an extension of the yardstick.

## Lanes running beside the spine (not part of it)

- **`T2-EQ`** — the runnable-entry-point precondition. Strongest-evidenced open ticket in the queue
  (5 of 5 recorded `artifact-build-failed` runs die on `Unable to find main class`), and independent
  of this arc. It improves the loop's output today; the spine improves its memory.
- **`T5-BH` residue** — the decided-but-unexecuted delete-and-harvest of `ReplanPatternDetector`.
- **Pattern plan P2+** — gated on the ledger filling, which is a countdown, not a wall.

## The design principle this session earned

**A capability change that widens what is VISIBLE must be preceded by scoping what is CONSULTED.**
Keeping history was safe only because the goal-dedup and GIVEN_UP reads were scoped first; reversing
that order would have let a dead lineage's abandoned goals suppress a live one's subgoals. The same
rule applies to every layer above: before the loop can SEE more (imported projects, merged graphs,
prior lineages), the reads that DECIDE must say which evidence they are entitled to use.
