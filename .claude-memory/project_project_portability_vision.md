---
name: project-project-portability-vision
description: "The vision for reading, continuing, storing and merging PROJECTS (the code layer) as the sibling of the cross-run graph knowledge base: import an existing codebase with or without its graph, store a project phase, restore it elsewhere, and merge two lineages' code. Filed as T4-CQ / T4-CR / T4-CS. Read before touching any import/export/bundle work."
metadata: 
  node_type: memory
  type: project
  originSessionId: 7473ce0f-7921-4a8b-924c-ddafeab47306
  modified: 2026-07-26T19:56:08.092Z
---

# Project portability — read, continue, store, merge (the CODE layer)

**User vision, 2026-07-26 (verbatim intent):** the code layer needs an input/merge function too. The
loop should be able to **continue or import already-existing projects** — with or without an
accompanying graph and other data — i.e. to *read* a project and continue work on it. It should be
able to **store** a project (phase) somewhere, and the same should hold for **graphs** (read and
store).

This is the code-side sibling of [[project-cross-run-knowledge-base]]. That chain merges GRAPHS
across runs; this one moves and merges the PROJECTS those graphs describe. They meet at the bundle:
a stored phase is a code tree *plus* its lineage subgraph.

## Why it is newly tractable (2026-07-26)

The graph half of "store" was blocked on there being no boundary to cut along: every run's nodes sat
in one undifferentiated database that a reset wiped. As of tonight (T1-D + T4-CJ slice 2) a lineage
is a **selectable, self-contained subgraph** — measured on the first real two-lineage fixture:
2169 + 1433 nodes with **0 edges crossing between them**. So:

- **store a graph** = select by `lineageId` (a clean cut exists for the first time)
- **read a graph** = import those nodes under a preserved or freshly-minted lineage id

## What already exists (do not rebuild)

| primitive | what it gives | what it lacks |
|---|---|---|
| `snapshots/<cycleId>/` | per-cycle code states | not portable, not named, not a unit |
| `preserved-artifact/` + provenance JSON (T4-CA/T6-A) | the run's best verified code state | code only; no graph, no restore path |
| `backup/<date>_<label>/` | whole-state copies (mutations, output, responses, meta, logs) | curated **by hand**, no restore, no graph |
| `scripts/dump_graph.sh` (T5-BD) | forensic graph capture — readable/diffable | explicitly **NOT reload-into-a-fresh-graph**; that half is a residual left inside a CLOSED ticket |
| `FILE_BASE_PATH` / `PREFLIGHT_TARGET` (T4-CK) | point the loop at another target | the target was still bootstrapped BY the loop — not a foreign project |
| `FileVariantCollector`, `ProjectDiffService`, structural diff, `MergeAgent` | file/AST-level reconciliation within one run | no notion of merging two lineages' trees |

## The hard parts (name them before building)

1. **Cold read.** Importing a project with no `meta/` and no graph means deriving the starting state
   from CODE ALONE — what exists, what is wired, what is missing — and synthesising a grounded
   backlog. Reusable: `EndpointSurfaceQuery`, `WiringFactsAnalyzer`, the `DiffSummarizer` declaration
   tree, the analysis agent, the build/package probes. This is the same evidence-from-structure
   discipline as [[feedback-activity-not-progress]] ("source what's missing from structure").
2. **Restore identity.** Importing a graph must decide: continue that lineage, or fork a CHILD
   lineage from it? Forking is the safer default and matches the T1-FM origin-stamp discipline —
   never re-stamp a parent's nodes.
3. **Code-layer equivalence.** Merging two trees is easy where paths match and hard where the same
   *capability* was implemented under different decompositions. This mirrors T4-CN's cross-graph
   entity resolution and inherits its difficulty; do not assume path equality is enough.
4. **What "better" means.** A merge needs an outcome measure per part. The T6-E ledger (per-run
   smoke/terminal) plus per-criterion results give one — but only at run granularity today, not per
   subtree.

## Gate before building any of it

Same altitude test as the graph chain: does the slice operate on ≥2 different projects/lineages and
move toward reuse? A single-run export that nothing can import is not this work.

And before the MERGE half specifically: measure whether two same-seed lineages actually overlap
enough to gain anything (criteria by digest, goal descriptions by similarity, shared ecosystem
facts, contradictions). If the overlap is thin, the honest outcome is that merging buys little —
recorded as evidence rather than assumed either way.

## Filed as

- **T4-CQ** — import and continue an existing project (cold read; with or without graph)
- **T4-CR** — portable project+graph bundle: store a phase, restore it elsewhere
- **T4-CS** — code-layer merge across lineages (sibling of T4-CM)

## Cold read — slice 1 shipped 2026-09-03 (user-directed)

**The user's correction, and it reframed rung 1:** the SEED must be derived by the loop too. Handing
it a hand-written spec means a person did the comprehension, which is exactly what will not be
available when the loop reads its own source. `T4-CQ`'s recorded design question ("where does the
GOAL come from on a cold read?") is that same question, and the ticket's warning is the real
constraint: **a loop that writes its own specification and grades itself against it always scores
well, because both sides come from the same guess** — the false-completion shape, four times over.

**The way out is anchoring.** A derived account is checkable only where it rests on something the
loop did not invent: someone else's TESTS (executable intent), the running application's OBSERVED
responses (fact), the project's own PROSE (quotable, not composable), and the code's declared
structure. An account built from the loop's opinion of what the project ought to do is not.

**Shipped:** `core/coldread/ColdReadService` + `ColdRead` + `ColdReadReport`, `GET /autobuilder/cold-read`.
Deterministic, no model call, read-only. Reports build/runtime/prose/surface/types/tests, plus what
it SKIPPED and what it could NOT establish — an imported tree contains wrapper binaries and scripts
the loop's whole-tree reader treated as source (it read a wrapper jar as text every cycle and pulled
a Windows batch file into prompts). Routes print with the context path applied, because the
controllers never mention it and the annotation-derived paths would all 404.

**Deliberately NOT done:** turning the account into a seed. That is where the circularity enters and
it is its own decision. Slice 1 stops at "what is there", which a person can check line by line.
