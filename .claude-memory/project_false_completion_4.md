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

**The exhausted/fulfilled flip was suspected and is EXONERATED** (user-raised, audited same day).
`CycleExecutor` T1-DJ returns `GOAL_FULFILLED` when a `GOAL_EXHAUSTED` signal coexists with a
COMPLETED root. Audit of all **7 archived fulfilled runs**: **4 arrived via the relabel, including
BOTH 23/23 firestarter runs**, while a direct fulfilment scored 18/23 — the correlation runs
backwards. The ordering is identical in a good run and this one (`T2-DR marked COMPLETED` then the
relabel ~1s later), so there is no variant to scope it back to. **T1-DJ faithfully reports that the
root is COMPLETED; whether COMPLETED was EARNED is the ledger's job.** The
[[project-goal-fulfilled-is-real-happy-path]] population is therefore NOT tainted by arrival path —
though it does contain this run, identifiable by dispositions + `criteria_executable_pct=0`.
⚠ N=7, only 5 scoreable.

**Specimen preserved IN GIT** (sources, pom, GoalStack, WHY.md):
`design/specimens/false-completion-4/` — user-corrected 2026-08-28: the run archive has no
retention TODAY but is unversioned and machine-local, and this project's measured survival record
for unversioned evidence is 4 of 28 runs. A copy also sits in
`~/.autobuilder/run-archive/2026-08-28_05-27-49/`, treated as expendable.

**Filed as `T4-DK`.** The causal chain upstream is `T4-DJ` (self-contradicting constraint) plus
the missing source mover. Standing lesson, same as [[feedback-verify-at-decision-timestamp]]: a
terminal label is a CLAIM about the seed, and this run shows the label can be manufactured by
plan surgery alone.
