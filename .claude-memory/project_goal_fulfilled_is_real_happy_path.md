---
name: project-goal-fulfilled-is-real-happy-path
description: GOAL_FULFILLED is rare-but-real. ~27 unique fulfilled runs across the 5-week corpus as of 2026-06-03; use them as length-matched yardsticks when investigating non-terminating runs.
metadata: 
  node_type: memory
  type: project
  originSessionId: 862bd440-cef5-42c8-9544-b744753d7c48
---

`result=GOAL_FULFILLED` is the system's happy-path terminal state, defined in `core/lifecycle/CycleExecutor.java` (T1-BH / T1-DJ branches around lines 340–390). It fires when `autoCompleteRootIfSatisfied` succeeds, when the leaf-completion-with-empty-remaining path holds with a COMPLETED root, or via the T1-DJ "GOAL_EXHAUSTED signal but root COMPLETED" reinterpretation.

**It is rare but consistent.** As of 2026-06-03, across the full corpus (firestarter/logs + backups, deduplicated by base timestamp) **~27 unique runs reached GOAL_FULFILLED** spanning 2026-04-29 → 2026-06-01. Most are short (3–16 cycles); a handful are longer.

**Notable fulfilled-run yardsticks** (use as length-matched comparisons when a similar-length run won't terminate):

| Run                          | Cycles | Era                                |
|------------------------------|--------|-------------------------------------|
| 2026-05-03_23-15-20           | 31     | pre-compile-gate                    |
| 2026-05-03_23-40-45           | 25     | pre-compile-gate                    |
| 2026-05-19_21-44-30           | 18     | post-compile-gate (T4-AN-1+)       |
| 2026-05-21_18-17-17           |  9     | "                                   |
| **2026-05-21_23-08-50**       | **51** | **longest fulfilled run on record** |
| 2026-05-22_02-47-13           | 23     | "                                   |
| 2026-06-01_01-59-56           |  9     | last fulfilled run on the maven path; T4-BN shipped ~40 min later |

`2026-05-21_23-08-50` (51 cycles, fulfilled) is the closest peer to any 40-cycle+ run that isn't terminating. The whole-state snapshot lives in many `backup/<date>_…/` directories (e.g. `backup/20260524_2_fulfilled/`) — see [[project-log-and-backup-conventions]] for why those backups overlap.

**False-positive caveat.** One historical fulfillment (`2026-05-27_23-58-48`, 4-cycle run) looks suspect: `prog=STAGNATING mut=null merged=false promoted=true result=GOAL_FULFILLED`. The `CycleExecutor.java:373–390` `T1-BH` guard exists specifically to suppress the false-positive class where leaf-completed-and-empty-remaining fires when the planner gave up all subgoals rather than completing them. Treat single-event fulfillments with `mut=null` + `STAGNATING` as candidates for re-examination, not as evidence the system succeeded.

**Anti-pattern this memory prevents:** I previously claimed "GOAL_FULFILLED almost never fires" based on a 52-log window (firestarter/logs/ only). The full corpus has 27 fulfilled runs. Don't repeat — see [[feedback-verify-population-before-claim]] and [[project-log-and-backup-conventions]] for the corpus-scanning procedure.

**How to apply:** when investigating a long non-terminating run, find the length-matched fulfilled peer first. Compare goal-tree shape, cycle-result histogram, and subgoal-promotion velocity — that diff is more informative than the failing run alone.
