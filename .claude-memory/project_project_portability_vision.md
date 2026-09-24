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

## Giving an adopted project a goal — 2026-09-05

- **The gap was total: `addInitialGoal` was reachable only from `bootstrap/seed.txt`.** An adopted
  project could be read and never worked on. `POST /autobuilder/goal` sets the root goal and writes
  it to `bootstrap/seed.txt`, so it is durable, sits where every other target keeps its
  specification, and a restart reproduces the run. Refused when a root goal already exists —
  replacing what a run is pursuing is a different run, not an edit.
- **🔴 ADOPTION MUST WAIT, NOT CYCLE.** The first adopted run died at boot with
  `Parent goal not found: create-basic-structure`. `PlannerAgent.ensureFallbackGoalState` injects
  `bootstrap-project-structure` / `create-basic-structure` when there is no current goal AND no
  remaining goals — a fallback for a fresh project, which invents ids that do not exist in the tree;
  the first call treating one as a real parent throws. **An adopted project has no goal BY DESIGN,
  so it is exactly the state that fallback cannot survive.** `awaitingGoal` now holds the scheduler
  until a goal arrives. Distinct from a refused start: the start succeeded and the tree is adopted,
  only the purpose is missing. (The hardcoded ids are also target-domain strings in framework code
  — [[feedback-no-target-domain-in-framework-strings]] — left alone here, worth a ticket.)
- **The first goal for an adopted project should be CHARACTERISATION, not correction.** The one
  given: extend the suite so every served route is exercised, asserting what the code does TODAY,
  changing nothing. It is anchored in a fact from the cold read (5 tests cover 1 of 8 types, no
  route is exercised), it cannot be graded against the loop's own guess, and it builds the
  regression baseline before any behaviour changes — the ladder's rung 2 in miniature.

## 🔴 The diff could not survive a tree the loop did not write (2026-09-05)

**First real adopted run: 18 cycles, 16 mutations proposed, ZERO scored, zero merged, zero
rejected.** `DiffService.loadFiles` reads every file in the tree as UTF-8; the Maven wrapper ships
`maven-wrapper.jar`; the read threw `MalformedInputException` and `ProjectDiffService.computeDiff`
failed on **every cycle**. With no diff nothing is judged, so no mutation reached evaluation, no
subgoal ever completed, and the planner replanned the same six subgoals three times over — 24 goal
nodes, 21 SUBSUMED, for six routes.

- **The exclusion list could not have saved it: it matched path PREFIXES.** `target/...` matched;
  `.mvn/wrapper/...` did not. Fine for a tree the loop wrote, where excluded folders sit at the
  root; useless for an imported one, which puts tooling anywhere. Now matched by path SEGMENT.
- **And one unreadable file must never cost the whole diff.** Binaries are skipped, counted and
  named in the log, instead of throwing.
- **`test` REMOVED from the exclusions.** It was there for the loop's own scratch dir at
  `data/<target>/test` — a SIBLING of `output/`, so the diff never saw it and the entry matched
  nothing. Under segment matching it would have started excluding `src/test/java`, making a
  project's own tests invisible to the loop asked to write them.
- **Lesson, the same shape as the cold read's:** every assumption that the tree contains only what
  the loop put there is a defect waiting for the first imported project. Audit the others.

**Seed lesson (user-raised, and correct).** The first seed I wrote for the adopted project stated
perishable facts ("five tests", "seven routes") that its own success would falsify, framed the goal
as a task ("write tests") rather than the project's purpose, and had no yardstick. Replaced with:
the project's own README line quoted, its declared surface, one durable objective, and a yardstick
that asserts ONLY what the seed states — that every listed route is served and the project's own
suite passes, never what a route should return, because the seed asks for behaviour to be
characterised rather than corrected.

## A declared path and a reachable path are two facts (2026-09-05)

The first adopted run landed 11 real MockMvc tests covering all 7 routes, in the project's own
packages and framework — **and 10 of them failed 404 because the seed listed the routes with the
servlet context path applied.** That form is correct over HTTP and wrong under MockMvc, which
bypasses the container and applies no prefix. Proven by removing only the prefix from a copy:
11 failures became 1, 15 of 16 passing, nothing else touched.

- **The cold read had the fact and threw half of it away.** It printed only the reachable form with
  a note. It now prints BOTH columns — as the controllers declare it, and as it answers over HTTP —
  because a reader handed one of them uses it in the wrong place. This matters most for the
  eventual GENERATED seed: a derivation that keeps only the reachable form bakes in the same defect.
- **The 11th failure was the loop's own and is the more interesting one:** it asserted a negative
  factorial returns 400 without ever exercising it. The seed said to observe and assert what
  happens. Assuming a status code is the characterisation-goal failure mode.
- **It also rewrote a main-code service to match a test it had written** (exception type changed
  `ArithmeticException` -> `IllegalArgumentException`, which the controller catches specifically).
  That is the inversion of a characterisation goal, and the seed now forbids touching `src/main`
  outright with "if a test does not pass, the test is what is wrong".

**Process failure worth keeping: two loops ran on one target for 23 hours.** `kill` returned
success, the session reported the run stopped, and the JVM survived; a second run started 30 minutes
later and both wrote the same tree and goal stack. Goal-churn numbers from that window are not
attributable. **Report a process stopped only after re-listing and finding it gone**
([[project-run-lifecycle-runbook]], ninth variant).

**Status 2026-09-24: T4-CQ slice 1 is fielded on two adopted projects.** `data/rung1` (a REST
calculator someone else wrote) reached two true completions; `data/structdiff` (a module of
Autobuilder itself, extracted by `scripts/extract_structdiff.sh`) is under way. The ticket's
running record is in `roadmap.md` under T4-CQ; the how-to is in the newest block of
[[project-resume-state]].
