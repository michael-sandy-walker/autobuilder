---
name: project-false-completion-4
description: "2026-08-28 — fourth false completion, first by BOOKKEEPING rather than verification: the closure ledger counts subgoal dispositions (GIVEN_UP/SUBSUMED pass it), never the seed; T1-DJ then relabeled exhaustion as fulfilment. Filed as T4-DK."
metadata: 
  node_type: memory
  type: project
  originSessionId: 08a0057e-31eb-4f9d-a1db-8717c69fbf03
  modified: 2026-08-28T04:33:31.353Z
---

**Run `2026-08-28_05-27-49`** (flightsim from seed, 44 cycles, 51.2 min): `terminal=GOAL_FULFILLED`
on an artifact missing two of the seed's four explicit requirements — `render/OpenGL.java` is a
placeholder comment, no HUD file exists, the pom declares no GL dependency. Closure line:
*"closure ledger clear: 21 subgoal(s) accounted for (13 subsumed)"*. Dispositions run-wide:
**23 GIVEN_UP · 15 SUBSUMED · 1 COMPLETED**.

**What makes it the fourth KIND, not a repeat:** [[project-false-completion-unmasked]],
[[project-false-completion-2]] and [[project-false-completion-3]] were verification failures — a
checker blind to a defect in work presented as done. Here nothing was mis-verified: **the
requirements were dissolved before anything checked them.** T4-BP-3 re-verifies only PASSING
criteria; criteria on given-up/subsumed subgoals exit the ledger unchecked against the seed. The
plan was reshaped (47 `SubGoalRemove` events) until what remained was satisfiable.

**The exhausted/fulfilled flip is part of the defect surface** (user-raised): `CycleExecutor`
T1-DJ returns `GOAL_FULFILLED` when a `GOAL_EXHAUSTED` signal coexists with a COMPLETED root. It
was built for a narrative-ordering case (root completed before the signal was consumed); here the
order was exhaustion → ledger-dissolution → relabel. Historical `GOAL_FULFILLED` terminals that
arrived exhaustion-first belong to this population, which taints the
[[project-goal-fulfilled-is-real-happy-path]] yardstick count until audited.

**Specimen preserved IN GIT** (sources, pom, GoalStack, WHY.md):
`design/specimens/false-completion-4/` — user-corrected 2026-08-28: the run archive has no
retention TODAY but is unversioned and machine-local, and this project's measured survival record
for unversioned evidence is 4 of 28 runs. A copy also sits in
`~/.autobuilder/run-archive/2026-08-28_05-27-49/`, treated as expendable.

**Filed as `T4-DK`.** The causal chain upstream is `T4-DJ` (self-contradicting constraint) plus
the missing source mover. Standing lesson, same as [[feedback-verify-at-decision-timestamp]]: a
terminal label is a CLAIM about the seed, and this run shows the label can be manufactured by
plan surgery alone.
