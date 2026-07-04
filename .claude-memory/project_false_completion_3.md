---
name: project-false-completion-3
description: "2026-07-04 run 13 — THIRD false completion: GOAL_FULFILLED with unbootable artifact (LoanService lost @Service in the last merge, 4 min before closure); StoreCoherenceHeuristic correctly voted REJECTED and was outvoted; fixed-annotation ground truth only 12/23."
metadata: 
  node_type: memory
  type: project
  originSessionId: 8cf15cc5-16fe-402c-b91c-b13a9ea3ddfa
---

**2026-07-04 run 13 (08:15–09:11, 92 cycles, fresh bootstrap) — the THIRD false completion, a new defect class.**

Formal chain fully passed: 170/170 criteria verified (T4-BP-3, 1 LLM call), package gate COMPILE_OK, test gate COMPILE_OK, closure ledger clear (74 subgoals, 42 subsumed), T1-DJ returned GOAL_FULFILLED. Ground truth (smoke `~/Documents/autobuilder/smoke_library_spec.sh`): **jar builds and is launchable but the Spring context fails to refresh** — `LoanController` autowires `LoanService`, which lost its `@Service` annotation. App never serves; 0/23 behavioral.

**Mechanism.** The last merged mutation (cycle-1783148822310, merged 09:07:39 — 4 min before closure) was a full-file rewrite of `LoanService` during the late stagnation loop on "remove ValidationException handler from LoanController". It dropped `@Service` AND replaced the shared store with private empty `HashMap`s plus never-called `setBooks`/`setMembers` setters. **`StoreCoherenceHeuristic` caught the parallel store and voted REJECTED (weight 0.75, HIGH)** but was outvoted by LLM ACCEPTED (0.67) + DeterministicScoreHeuristic ACCEPTED (0.739) → finalState ACCEPTED (0.664), no overrides.

**Even with `@Service` restored** (probe on a /tmp copy): only **12/23** — all loan-path checks 404 (empty parallel store, exactly the heuristic's warning), dup-email 400-not-409, POST /books 200-not-201, GET /loans?memberId 405. Run 12 scored 22/23; run 13's terminal artifact is a large regression despite identical closure formalities.

**Why every gate missed it:** compile gates don't need `@Service`; criteria verification is text-over-source (none of the 170 criteria mentions annotations or wiring); the exhaustion-promotion path (`SUBGOAL_EXHAUSTED but output verification passed`) closed 3 of the last subgoals on the same text-only verification. The boot gate that would have caught it is T4-BV — **stood down by the user 2026-07-03; do not rebuild without direction** — this run is direct field evidence to surface, not a license to rebuild. See [[project-false-completion-unmasked]] and [[project-false-completion-2]]; the boot-check ritual from [[project-completion-edge-gates]] earned its keep again.

Open leads from this run: (1) a correct HIGH-confidence REJECTED vote was averaged away at the exact moment it mattered — the negotiation has no notion of a runtime-fatal defect class; (2) merges landing minutes before the completion edge get no re-ground-truthing; (3) late-run stagnation loops producing full-file rewrites of previously-working files are the delivery vector (same file merged 7× in the run).
