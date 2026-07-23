---
name: project-cross-run-knowledge-base
description: "The cross-run knowledge-base vision and its three-ticket chain (T4-CJ slice 2 retention → T4-CN cross-graph compatibility → T4-CM merge workbench). Anti-drift anchor: these tickets are about reconciling graphs from DIFFERENT runs/seeds into one durable knowledge base — NOT single-tree goal folding or single-graph browsing. Read before touching T4-CM / T4-CN / T4-CJ slice 2."
metadata: 
  node_type: memory
  type: project
  originSessionId: 5796ce03-2a72-4b4d-97e0-1ef53fea290d
---

# Cross-run knowledge base — the vision, the chain, and the drift trap

## The vision (user, confirmed 2026-07-23)
The graph workbench and the compatibility model exist to **merge graphs from different runs and seeds into one durable, higher-level knowledge base**. Many independent runs each learn things — goals decomposed, ecosystem facts discovered (package→coordinate, classpath resolutions), artifacts built. Today each run's graph is isolated (and usually purged on the next reset). The target is to **reconcile equivalent/complementary nodes ACROSS those graphs** so that what many runs learned accumulates into ONE growing structure the loop can later draw on. This is [[project-introspection-as-north-star]] at the graph level: the system builds and reuses a self-accumulated knowledge base.

## The three-ticket chain and its TRUE ordering (dependency-first)
1. **T4-CJ slice 2 — retention (the real first step).** End purge-per-run so ≥2 run/seed graphs COEXIST with provenance stamps. Without this there is nothing to merge across. As of 2026-07-23 reset still purges other runs (confirmed by the T4-CO data-side investigation: the live graph holds a single lineage; run 3 wiped runs 1+2). **This gate was repeatedly glossed as "slice ④, later" — it is actually the prerequisite for everything else here.**
2. **T4-CN — cross-graph compatibility.** `checkCompatibility(nodeFromGraphA, nodeFromGraphB) → {sameThing?, reconciliationKind, provenanceAfterMerge, conflict?, reason}`. Cross-run identity/equivalence, provenance, conflict, knowledge-value. UI-independent; also a headless API.
3. **T4-CM — the merge workbench.** View multiple run/seed graphs together, provenance-tagged; the T4-CN check surfaces mergeable pairs; the operator confirms cross-graph merges that UNION into the base (provenance preserved, conflicts surfaced); the accumulated base becomes reusable run context.

## The drift trap (what went wrong 2026-07-23 — do not repeat)
Both tickets were first filed "abstract, slices concretize on pickup," with cross-run buried as a late/gated slice. On pickup that invited the TRIVIAL reading:
- **T4-CN was built as same-tree goal folding** — `ConnectionCompatibilityService.checkConnection` with every rule delegating to `GoalConsolidationService` (a dry-run of machinery the loop already runs), and the cross-run case deferred. The ticket was CLOSED on its redundant half. Reverted.
- **T4-CM was built as a flat single-graph node browser** (`OrchestrationGraphExplorerService` + `graph-explorer.html`) and labeled "slice ① shipped / wave-2 Session 1 complete." A single-graph reader shows nothing about compatibility or merging and accumulates no knowledge. Walked back.

**Altitude test before building any slice here:** does this operate on nodes from ≥2 DIFFERENT run/seed graphs and move toward a merged knowledge base? If it only touches one run's graph/tree, it is NOT this work — it is either existing machinery (single-run fold = `GoalConsolidationService`) or a mislabeled trivial slice. Building the trivial single-graph piece and calling it progress is [[feedback-activity-not-progress]].

## Resolution of the 2026-07-23 off-target code (decided)
- **Same-tree fold preview (`ConnectionCompatibilityService` + `/connection-check` + 16 tests): DELETED** (revert of `6fa9991`) — near-redundant with `GoalConsolidationService`, no consumer after the rewrite. Recoverable from git history if ever wanted.
- **Single-graph reader (`OrchestrationGraphExplorerService` + `graph-explorer.html` + `/autobuilder/graph/*` + 5 tests): KEPT, re-homed as T5-BG** (Quality/observability) on its own merits — it is the only surface that browses the FULL persisted orchestration graph (all node labels, provenance-filtered), distinct from `goals.html` (goal tree) and `graph.html` (goal cockpit). It is single-graph observability, NOT a T4-CM slice; its provenance-filter patterns may inform T4-CM ② but the workbench is a separate, retention-gated build.
