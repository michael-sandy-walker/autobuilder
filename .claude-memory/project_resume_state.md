---
name: project-resume-state
description: "Where the 2026-06-23/24 session left off: T4-BN-2 regression found+fixed on feature/completion-correctness; constraint moved to planner-convergence; fast profile added; the .claude-memory=loading-memory git gotcha. Start-here for the next session."
metadata:
  node_type: memory
  type: project
  originSessionId: 609aab02-3d49-46ec-874f-e42f8a4aa75f
---

## ⭐ 2026-06-27 UPDATE — START HERE (supersedes the 06-24 snapshot below)
**First REAL, ground-truth-verified GOAL_FULFILLED achieved this session** (the long-standing north star). Full detail: [[project-t2ei-design-note]] + [[project-t2ei-rule-interaction-map]].
- **What landed (T2-EI line, on `feature/completion-correctness`, NOT pushed):** `762adf9` depth-first probe + design · `32f36c1` slice 1a (hierarchical closure: absorb EXTRA-origin subgoal failures, don't doom root) · `d872235` slice 2 (count-based admission queue: cap ACTIVE leaves SURFACED as workable, `autobuilder.goal.admission-cap`) · `1c12673` slice 1b (earned per-leaf retry budget, `autobuilder.goal.subgoal-retry-budget`). All default-off flags; suites green (only pre-existing Neo4j-Testcontainer ITs fail).
- **The fulfilling config:** `--autobuilder.goal.depth-first-decomposition=true --autobuilder.goal.completion-coverage-gate-enabled=true --autobuilder.goal.admission-cap=6 --autobuilder.goal.subgoal-retry-budget=3`. Verified: root COMPLETED via all gates (package+test+T4-BP-3, which blocked twice + got fixed) → `mvn clean package` on output = BUILD SUCCESS + bootable jar.
- **Reliability: 1/3 genuine** (stopped at 3 on user request) — but honest re-read = "1 fulfilled, 1 would-fulfill-with-more-time (run 3 = MY harness 90-min deadline, NOT a product limit — prod has no cycle/time cap), 1 model ceiling (run 2: POST /loans bundled 5 business rules, 0/5 passed)". NOT reliable yet; variance real.
- **Key correction this session:** distrust grep-based completion counts — classify on `returning GOAL_FULFILLED`+`T2-DR root marked COMPLETED`, never loose substrings (3 grep errors caught, incl. a "promotion-freeze" that didn't exist + a "4/5" that was 1/5).
- **Open / next:** **T2-EJ** (divide-and-conquer fallback after retries exhaust — split multi-rule subgoals per-rule, anti-churn guards; filed, targets run-2) · **P2 merge/push decision** (this line has a verified fulfillment — user's call) · artifact coherence (T2-DI/T4-AV: output dumped flat in one package) · relativize retry budget ([[feedback-relative-thresholds]]) · slice-1a is field-validated, slice-1b removed early-deaths.
- **Scripts:** `/tmp/t2ei_depthfirst_run.sh` (single capped run, takes label df cg cap deadline; `ADMIT=` env), `/tmp/t2ei_reliability_batch.sh` (N-run batch, detection-fixed). Fulfilled artifact preserved at `/tmp/t2ei_fulfilled_artifact_2026-06-25`.

---
**Start-here snapshot for the session after 2026-06-24.**

## Git / branch state
- `main` = `1108cb5` (T4-BQ slice 1) — **PUSHED to origin** (user-authorized; the only push).
- **`feature/completion-correctness`** (branched off main) = the curated line and the active branch. Contains: validated core (T1-FA, T1-FB, T4-BP-3, T4-BR, T5-AF chain, T4-BN-2 coverage) + session keepers (T4-AN-2b, T4-BN-2 steer fix, T4-BN-3 test-dep ensurer) + **the T4-BN-2 revert (`0c98742`)** + the `fast` Spring profile. **Dropped as proven-inert: naming-consistency, prompt-compaction.** Suite 1642 green. NOT pushed.
- `checkpoint/2026-06-16-completion-correctness` = the original full work line (39 commits incl. the dropped experiments + complete memory history). Kept as the archive.

## The headline result
- **T4-BN-2 (per-cycle `mvn compile`→`mvn test-compile`) was the regression** behind the 06-17→06-24 "nothing fulfills" cliff — git-bisect-confirmed, single-variable proof (cfs 1534→61). Reverted. See [[project-completion-binding-constraint]] (RESOLVED block).
- After the revert, curated runs are healthy + near-complete (3 controllers, 9 endpoints, ISBN, OK 0.66) but **pause before formal fulfillment via T1-EI (planner empty) + T4-I (convergence brake)**.

## Next lever (where to start)
**T2-EH SHIPPED 2026-06-24** (`6166075`, suite 1649) — verify-before-pause at the T4-I/T1-EI halt edges (re-verify ACTIVE leaf subgoals vs current code, promote those that pass, then `autoCompleteRootIfSatisfied`; brake resets on progress = T4-I tuning; explicit-criteria `verifyAndRecord` overload = T4-BP promoting direction). **T2-CR stood down** (0/3788 cap fires). See [[project-completion-binding-constraint]] UPDATE 2026-06-24.
- **Field status: ACTIVATION CONFIRMED.** Run 2 (130 cyc) gave the FIRST field firing — at the brake limit, `verify-before-pause promoted 1 of 2 active leaf subgoal(s)`, `broke the convergence stall (rootCompleted=false, promoted=1, 34→35) — not halting`; the real `root convergence stall` halt fired 0×. Unit-test-correct + regression-free (run 1, healthy) + field-activated (run 2). NOT shown: a full GOAL_FULFILLED via this path (both runs deadline-cut still progressing).
- **The residual** (T2-EH only helps a run that STALLS, not one that is merely slow): even bare main (06-17-good) didn't fulfill in 90 min → a second factor (env/model slowness, run-variance). Two-factor, don't single-cause it.
- **DO NOT reach for T4-BO/T4-AL (signal redesign) for the residual** — user steer 2026-06-24: that is a *fundamental* signal change; tuning signals while the system produces incoherent artifacts is fitting to noise. Stabilize output first, tune dynamics only once convergence is reproducible. (My earlier "pair with T4-BO before the brake limit" was wrong — the brake limit isn't even a T4-BO/decay constant, and T2-EH already de-fanged the hard halt.) The brake limit: leave it.
- **The agreed next direction is artifact COHERENCE — homed in T2-DI (deterministic) + T4-AV (LLM), NOT T4-AW.** (T4-AW reviews analyzer *criteria*, not produced source — I mis-filed it first, then corrected.) The 2026-06-24 run-2 artifact was full of minted-synonym duplicates + a real throw/catch **500 bug** (`MemberMaxLoansException` thrown, `MemberHasTooManyLoansException` handled → catch-all → 500 not 422) + missing `BookService` + fragmented packages. **T2-DI** (shipped, parallel-store-only) gets the new STATIC patterns (unreachable-handler, orphan-exception, layer-bypass, duplicate same-name); **T4-AV** (un-park trigger now MET) gets the LLM-judge synonym reconciliation. **Precursor slice SHIPPED 2026-06-24 (`3cc9579`):** manufacture-prevention guard in `IdiomSkeletonResolver` (CamelCase concept match) + planner reuse-first — stops NEW duplicates; repair of green ones is T2-DI/T4-AV. **Honest caveat:** coherence is OUTPUT-CORRECTNESS, NOT a proven completion lever — [[project-t4bq-naming-consistency-ab]] showed synonym reduction = 0/6 completion benefit; convergence residual is most likely local-model capability. See [[project-completion-binding-constraint]] + T2-DI/T4-AV tickets. Deterministic harness/targeted inspection, not high-variance A/Bs ([[feedback-calibrate-not-flip]]).

## Tools added this session
- **`fast` Spring profile** (`--spring.profiles.active=dev-auto,fast`): overhead-trim only (`inter-cycle-delay-ms: 1` — NOT 0, which crashes startup: invalid @Scheduled fixedDelay). ~10-15% faster; LLM-inference is the floor. For fast CORRECTNESS checks use the deterministic harness (single-call, seconds), not a loop.
- Run-health probe pattern: `/tmp/bisect_run.sh` (build + capped plain + OK-ratio/cfs/controllers). Run backups under `/tmp/*-ab/`, `/tmp/drive-iter*`, `/tmp/t4bn2-validation/`.

## CRITICAL infra gotcha (cost us memory this session)
**`.claude-memory/` IS the loading memory** — `~/.claude/projects/.../memory` resolves to the repo's `.claude-memory/`. So `git checkout`/branch/cherry-pick that changes `.claude-memory` **mutates the live memory that loads next session.** Branching from main (which lacks session memos) silently deleted `project_completion_binding_constraint.md` + `project_t4bq_naming_consistency_ab.md` from the loading memory until recovered via `git checkout checkpoint -- .claude-memory/`. **Before switching branches, ensure `.claude-memory` is committed on the target branch, or memory will appear to vanish.**
