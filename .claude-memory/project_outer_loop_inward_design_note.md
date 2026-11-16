---
name: project-outer-loop-inward-design-note
description: "2026-11-16 design note (Opus 5.5, session 40, at the user's word): move the outer loop (user + Claude) into Autobuilder -- (1) problem memory across runs AND projects, (2) a run that ends in a draft diagnosis, (3) building itself, measured by replaying closed tickets. Knowledge = run records + .claude-memory, with provenance. Proposes tickets; files none. Read before any cross-run, self-diagnosis or self-application work."
metadata:
  type: project
---

# Moving the outer loop inward

**Status: design note, 2026-11-16 (Opus 5.5, session 40). Nothing filed, nothing built.** Written at the user's
"Yes, write the design note" after the user's direction: *"Atm we're still busy a lot with the external loop between
you and me, while in the long term, Autobuilder should take over that part more and more. This can only be done with
an introspection capability regarding the question 'have I addressed or solved any similar problems already in other
similar runs?'. Also, Autobuilder should get more into the direction of building itself in the mid-future. The open
tickets do not address these things sufficiently anymore."* Two refinements by the user on the first draft of the
idea: similar problems means **other projects too**, not only other runs of the same project; and the knowledge is
**a combination of run knowledge and memory knowledge** (`.claude-memory`).

A ChatGPT-written handoff (`~/Downloads/Autobuilder-North-Star-Handoff.md`) was shown to me and NOT adopted
(the user: "don't take over anything"); it mostly restates [[project-introspection-as-north-star]] and
[[project-living-project-vision]].

## Why now -- the diagnosis of the queue

Every open ticket on 2026-11-16 (T2-FN, T4-DD, T4-DS, T4-EU, T4-EY, T6-H) was found by the outer loop: the user and
Claude reading a run, finding the grind, naming the invented fact, filing the ticket, building the fix. The loop wrote
none of them. Each fix makes the loop more reliable and leaves the outer loop exactly as large as before. The
north-star note already names the destination (a loop that consults a self-model before acting; the fixpoint
`improve(Autobuilder, "improve how you improve")` with the referee OUTSIDE the copy). What is missing is a path on the
roadmap from here to there. Three directions, one chain:

```
  (1) problem memory          (2) draft diagnosis            (3) building itself
  "have I met this before,    "what went wrong in this run,  "fix it -- on a copy of me,
   here or elsewhere?"   -->   and which ticket is it?" -->   judged from outside"
```

(1) is the record, (2) is the outer loop's analysis done by the loop with that record, (3) is the outer loop's
building done by the loop. Each is useful alone; each makes the next one cheaper.

## What already exists (do not rebuild)

- **T4-CT (closed 2026-09-30, `df903902`): problem memory across runs of the SAME TASK.** `core/stuck/StuckPriors`,
  `graph/stuck/Neo4jStuckPriors`: each stuck-ledger entry that ENDED, RECURRED or stayed UNANSWERED is a
  `stuck_outcome` action keyed by the SHA-256 of the seed; a later run asks first the question that ended the
  signature before. Measured: 3 of 3 pairs shorter grind, failed builds 13/13/9 -> 4/3/5, all six GOAL_FULFILLED.
  Limits stated there: one toy case, one signature, n = 3. **It keeps which question ended a signature, never the
  answer.**
- **The stuck layer (T4-DY, T4-DZ, T4-EU):** sources BUILD, PROTECTED_WRITE, CLOSURE_REFUSAL, FLAT_ROOT, EXHAUSTION,
  TEST_FAILURES, VETO, BOOT; authorities structure -> observation (`TerminalAgent`, read-only copy, allow-listed
  commands) -> model last; `EvidenceReader` / `LogInvestigator` for closure refusals; the diagnosis request and the
  pause. See [[project-t4dy-meta-layer-design-note]].
- **Records to learn from:** stuck ledgers in 63 of 70 project folders (sightings on 2026-11-16: BUILD 18,
  TEST_FAILURES 18, CLOSURE_REFUSAL 17, FLAT_ROOT 11, VETO 8, BOOT 1, PROTECTED_WRITE 1), 176 behaviour logs under
  `data/*/logs`, 599 archived runs under `~/.autobuilder/run-archive`, 651 rows in `scripts/run_outcomes.json`, the
  graph with every lineage since 2026-09-24 attributed.
- **Self-application precedents:** structdiff and `core.lifecycle`, two modules of Autobuilder extracted and adopted
  as targets (T4-CQ, [[project-project-portability-vision]]); a foreign tree as a first-class project (T4-EK).
- **The outer loop's own record:** `.claude-memory/roadmap.md` (each ticket: Observed, cause, fix, the commit, the
  DoD and how it was verified), the feedback and project notes, git history. 643 commits named for a ticket, **534 of
  them carry tests**.

## Direction 1 -- "have I met this problem before, here or in another project?"

**The gap.** T4-CT matches a signature by its exact text within one task. Signatures are written in the project's own
words -- *"the build fails: TestRunGate.java: local variables referenced from a lambda expression must be final"*,
*"the same tests keep failing: PantryLabelTest.labelOf..."* -- so nothing matches across projects, though the
underlying problem (a captured variable that is reassigned; a check that misreads a test it was shown) recurs on any
target.

**Similarity in levels, cheapest authority first** ([[project-invented-facts-have-authorities]]):
- **L0 -- the same signature, same task.** T4-CT, built.
- **L1 -- the same SHAPE, any project.** The signature with the project's identifiers taken out structurally: the
  compiler's message template, the gate and its ground of refusal, the veto's heuristic and its kind, the test
  failure's assertion kind. No model. Most BUILD, VETO, FLAT_ROOT and CLOSURE_REFUSAL signatures already have a
  stable template under their names.
- **L2 -- the same CAUSE, worded differently.** Needs a reading: a model proposes "this is the same as that", and only
  evidence confirms it (the same question ended both; the same kind of change ended both). Last, and only if L1
  leaves a measured gap.

**What can transfer across projects** -- never the answer (it names the other project's code), but:
- (a) **which question / authority ended this shape** (T4-CT's prior, widened from task to shape);
- (b) **the form of the change that ended it** -- the diff of the merge after which the signature stopped, reduced
  to its shape ("a local copy of the variable before the lambda"; "an equality assertion beside the contains");
  needs a link from an ENDED ledger entry to that merge, which the graph has and the ledger does not keep;
- (c) **"this was the framework, not the work"** -- the shape matches a defect fixed in Autobuilder itself (a
  closed ticket), so a recurrence means a regression, not a hard task. This is where memory knowledge comes in.

**Run knowledge and memory knowledge, combined (the user's decision).**
- *Run knowledge:* ledger outcomes, the merges that ended signatures, rejection reasons, terminals -- written by the
  loop, structured, every project. It answers (a) and (b).
- *Memory knowledge:* the closed tickets' Observed / cause / fix and the lessons -- written by the outer loop, prose,
  framework-level. It answers (c), and it is the only place where "this kind of problem was the CHECK's fault, not the
  work's" (T4-EU kind (b)) is recorded at all.
- **How they meet:** the shape (L1) is the join key -- a ticket's Observed quotes the signature that led to it (T1-GM
  quotes the refusal text; T2-FN the criteria), so a closed ticket can be indexed by the shapes it quotes.
- **Rules that hold for both** (from existing decisions): arrives as measurements with provenance, never as fact
  (T4-CT's constraint; [[project-ticket-premises-expire]]: a ticket's claim is DATED); read-only and ADVISORY under
  T2-ER (`@HistoryRead`); a ticket id reaches a prompt only if the prompt defines it
  ([[feedback-no-internal-ids-in-model-facing-text]]); no target names in framework strings
  ([[feedback-no-target-domain-in-framework-strings]]); imported knowledge is judged for applicability before it
  binds -- T2-FN was exactly a brought project's history doing harm.

**First step -- measure, offline, rule fixed before counting** ([[feedback-measure-before-widening-a-working-rule]]):
normalise every recorded signature to its L1 shape (63 ledgers + the `stuck_outcome` actions), then count
(i) shapes seen in >= 2 projects, (ii) of those, how often the same question ended them in each project,
(iii) how many shapes match a signature quoted in a closed ticket. **Decision rule (relative to the one baseline that
already paid off, [[feedback-relative-thresholds]]):** T4-CT acted on a within-task recurrence of 10 of 19
signatures. If cross-project recurrence of shapes with a consistent ending is of the same order, widen T4-CT's key
from task to shape (a small change: the port stays, the key changes). If it is far smaller, the cross-project value
is in (c), the framework-level match, and direction 1 folds into direction 2. Either outcome is recorded.

## Direction 2 -- a run that ends in a draft diagnosis

**The gap.** The outer loop's analysis is repetitive and has a recipe ([[feedback-run-analysis-log-order]],
[[feedback-escalate-unrecoverable-stagnation]], [[project-invented-facts-have-authorities]]): read the behaviour log,
find where it ground, find the claim nobody checked and its cheap authority, see whether a closed ticket already
covers it, classify (work wrong / check wrong / requirement unmeetable / cause outside -- T4-EU's four kinds), tier by
mechanism ([[feedback-ticket-tiering]]), write Observed + cause + proposed fix + DoD. The loop has most of the
pieces (stuck ledger, authorities, `LogInvestigator`, the narrator, T4-EU's evidence read) and none of them produce
that artifact. T4-EU already says its contradiction record "would have been a ticket written by the run".

**The artifact:** `meta/diagnosis.md` (or a graph record with a rendered view) at the end of a run and at every
diagnosis request: each grind episode with its signature, the evidence lines read, the kind, the similar past
problems from direction 1 with their provenance (run or ticket), and a draft ticket in the roadmap's form. The
operator reads it instead of the log; we write the ticket from it or say why it is wrong.

**The instrument -- our own history is the yardstick.** Tickets filed from a field run whose logs are still on disk
(T1-GM on `pantry-rev3`, T1-GP / T1-GQ on `gradient`, T2-FN on the gradient trio, T4-EU on `pantry-rev3`, more in the
roadmap) are held-out cases: the draft is generated from the archived run as it stood when we read it, then compared
with the ticket's Observed and cause. Score fixed before the first replay: same episode found; same evidence named;
same kind; same component; a closed ticket correctly named when one applies. The ratio of drafts that match our
diagnosis is the measure of how much of the outer loop has moved inward -- it can be reported per build, like the
suite count.

**First step:** list the tickets of the last two months that name their field run, check which runs' logs and trees
are still on disk, and fix the scoring sheet. Offline.

## Direction 3 -- building itself, measured before it is trusted

**The material is already there: 534 ticket commits with tests.** For each, the commit before the fix is the starting
state, the ticket's body AS IT STOOD IN THAT PARENT COMMIT (`git show <parent>:.claude-memory/roadmap.md` -- so
nothing written after the fix leaks in) is the input, and the tests the commit added are the judge, together with the
suite of the parent (nothing that passed may fail). The judge lives outside the copy, as the north-star note
requires: the copy never sees the tests that grade it, and nothing in it can change them.

**Ladder, smallest first:**
- **Rung 1 -- a ticket inside an extractable module** (structdiff-sized: the module as a project, the ticket as the
  seed). The loop already completed such projects (T4-CQ). Measures: does the loop make the commit's tests pass
  without breaking the module's suite.
- **Rung 2 -- a small ticket in the whole tree** (586 files, 106k lines): the test gate cannot run 4,679 tests per
  cycle; it needs the affected tests (by package or by the classes the change touches). This is where the local
  model's limits ([[project-local-first-constraint]]) will show -- measured, not assumed.
- **Rung 3 -- the loop's own draft ticket from direction 2 as the input**, built on a copy, judged by tests WE write
  before the run. That is the outer loop closed: run -> diagnosis -> fix on a copy -> independent check -> the user
  decides whether it lands.

**Guardrails (from [[project-introspection-as-north-star]]):** the framework that runs and the referee are never the
copy; a self-edit lands only through the user (as every port does now, [[feedback-operator-reviews-ports]]); the
measure is not one the copy can change (reward-hacking the base case); the base case is the suite green.

**First step:** a corpus script: per ticket commit, the parent, the ticket body at the parent, the tests added, the
size of the change, the modules touched; then pick five rung-1 candidates and run ONE as a pilot with the judge fixed
before the run.

## Order proposed

1. Direction 1's measurement and direction 3's corpus -- both offline, no runs, no model; they decide how large the
   rest is.
2. Direction 2's scoring sheet and a first replay of the diagnosis on 3-5 held-out runs.
3. Direction 3's rung-1 pilot (one ticket, one run).
Then tickets for what the measurements support. T4-EU's next slice already lies on direction 2 and could move up;
the rest of the queue keeps its order ([[feedback-roadmap-is-the-queue]]: these become tickets only after the user's
review of this note).

## Open questions for the user

- **Memory knowledge, which parts and how read.** Only closed tickets (Observed / cause / fix), or the feedback lessons
  too? Read from the checkout at run start as a snapshot (`.claude-memory` changes under branch operations), and is a
  product reading the outer loop's notes compatible with [[feedback-process-apart-from-product]] -- or should the
  closed tickets be exported into a product-side record the loop owns?
- **Where the draft diagnosis appears:** a file in `meta/`, a view on the home screen, the narrator's text, all three?
- **Direction 3's first target:** a module extracted like structdiff (cheaper, proven), or the whole tree from the start
  with an affected-tests gate (closer to the goal, more unknowns)?
- **Tier.** Direction 1's widening is T4 (capability); direction 2's artifact T4; direction 3's corpus and replay T6
  (harness). Or one umbrella ticket per direction with slices, the way T4-EC was cut?

## Related

[[project-introspection-as-north-star]] (the destination), [[project-living-project-vision]] (projects take inputs;
an imported project's history is knowledge -- and, T2-FN, can be harm), [[project-cross-run-knowledge-base]] (graph
reconciliation of different runs; T6-G found same-seed lineages share little CODE -- problems are a different axis,
which is why direction 1 measures first), [[project-t4dy-meta-layer-design-note]], [[project-wave2-structure]].
