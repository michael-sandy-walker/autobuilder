---
name: project-resume-state
description: "Where the 2026-06-23/24 session left off: T4-BN-2 regression found+fixed on feature/completion-correctness; constraint moved to planner-convergence; fast profile added; the .claude-memory=loading-memory git gotcha. Start-here for the next session."
metadata:
  node_type: memory
  type: project
  originSessionId: 609aab02-3d49-46ec-874f-e42f8a4aa75f
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
The binding constraint has **moved to planner-coverage / drive-to-formal-completion**: why a near-complete artifact pauses (T1-EI/T4-I) instead of being marked COMPLETED. Filed as **T2-EH** (verify-before-pause at the convergence-stall edge). Investigate that terminal — NOT more build-coherence work (fixed) and NOT high-variance full-run A/Bs (variance swamps signal — use the deterministic harness/targeted inspection per [[feedback-calibrate-not-flip]]).
- Open residual: even bare main (06-17-good) didn't fulfill in 90 min → a second factor (env/variance/eager-brake), unresolved. Two-factor, don't single-cause it.

## Tools added this session
- **`fast` Spring profile** (`--spring.profiles.active=dev-auto,fast`): overhead-trim only (`inter-cycle-delay-ms: 1` — NOT 0, which crashes startup: invalid @Scheduled fixedDelay). ~10-15% faster; LLM-inference is the floor. For fast CORRECTNESS checks use the deterministic harness (single-call, seconds), not a loop.
- Run-health probe pattern: `/tmp/bisect_run.sh` (build + capped plain + OK-ratio/cfs/controllers). Run backups under `/tmp/*-ab/`, `/tmp/drive-iter*`, `/tmp/t4bn2-validation/`.

## CRITICAL infra gotcha (cost us memory this session)
**`.claude-memory/` IS the loading memory** — `~/.claude/projects/.../memory` resolves to the repo's `.claude-memory/`. So `git checkout`/branch/cherry-pick that changes `.claude-memory` **mutates the live memory that loads next session.** Branching from main (which lacks session memos) silently deleted `project_completion_binding_constraint.md` + `project_t4bq_naming_consistency_ab.md` from the loading memory until recovered via `git checkout checkpoint -- .claude-memory/`. **Before switching branches, ensure `.claude-memory` is committed on the target branch, or memory will appear to vanish.**
