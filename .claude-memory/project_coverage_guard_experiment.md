---
name: project-coverage-guard-experiment
description: "Runbook for the autobuilder.evolution.coverage-guard.enabled=false experiment shipped 2026-05-28 — which behaviour events to count, primary metric, decision rule, expected duration, what to do on each outcome"
metadata: 
  node_type: memory
  type: project
  originSessionId: b1f99192-77ac-48ce-ba2a-ec6e18f838f6
---

The pre-probe coverage-guard kill switch was shipped 2026-05-28 (commit `1b766f6`). This runbook is what to do *next* — actually run the comparison and decide whether to delete the guard family (T2-DN through T2-DU, with T2-DW + T2-DX still open).

## Hypothesis

The pre-probe coverage guard is net-negative on merge rate per cycle. Removing it does not decrease merge rate and may increase it, because probe rejections produce structured diagnostics that drive T2-DT/T2-DV steering on the next cycle, while guard rejections produce no learnable signal.

## Control vs treatment

| Arm | `autobuilder.evolution.coverage-guard.enabled` |
|---|---|
| Control | `true` (default) |
| Treatment | `false` |

Same goal seed, same model, same scheduler cadence. Single yaml edit between arms; restart the autobuilder; no code change.

## Goal seed

Use the library-management goal (Book/Member/Loan + controllers + services). That's where the guard historically rejected the most — see run `behaviour-2026-05-28_00-21-26.ndjson` for the cascade pattern. A trivial goal (single-file utility) does not stress the comparison.

## Per-run metrics — events to count in `behaviour-*.ndjson`

| Event / pattern | What it measures |
|---|---|
| `mutation_merged` | successful merges (numerator of primary metric) |
| `cycle_start` | cycle count (denominator of primary metric) |
| `mutation_rejected` with reason starting `incomplete_symbol_coverage:` | guard rejections — should be 0 in treatment arm |
| `mutation_rejected` with reason `Compile probe failed` | probe rejections — exists in both arms |
| `evolution_steering_compile_diagnostics_fed_back` | T2-DT/T2-DV steering events — expected to rise in treatment |
| first and last cycle timestamps | wall-clock duration |

**Treatment-arm-only signature in `autobuilder-*.log`:**

```
Evolution: coverage guard DISABLED — bypassing pre-probe rejection branch (N missing symbols carried; letting probe see the proposal)
```

Each such line corresponds to a cycle that *would have been* a guard rejection in the control arm. The count of these lines is the would-have-been-rejected proposal count — useful for sizing the experiment's effect.

## Primary metric

```
merge_rate = mutation_merged_count / cycle_count    (per session)
```

Compare arm means across ≥3 sessions per arm.

## Secondary metrics

- `cycles_to_first_merge` — how quickly the system starts producing value
- `steering_events_per_session` — does treatment produce more T2-DT signal? Expected yes
- `wall_clock_per_cycle` — treatment cycles are slightly longer because probes run on rejected proposals too
- `total_llm_tokens` — treatment may use more tokens (more proposals fully scored). Track separately; if the gap is large, it's an argument for T4-BN (fast probe) ahead of guard deletion

## Sample size

Minimum 3 runs per arm. Recommended 5. Each long-form session is ~20–40 min, so a 10-run comparison is ~5–7 hours of wall-clock.

## Decision rule

| Outcome | Action |
|---|---|
| treatment merge_rate ≥ control ± noise band | hypothesis confirmed → delete guard family (T2-DN/T2-DU shipped → mark "deleted, subsumed by guard removal"; close T2-DW + T2-DX as obsolete). T2-DT/T2-DV remain — they're useful regardless of the guard |
| treatment merge_rate < control by more than noise | hypothesis falsified → restore default; file investigation ticket for the specific failure mode that justified the guard. Keep the kill switch — useful for debugging |
| treatment finishes goal in fewer cycles but lower per-cycle rate | still a win — completion time dominates per-cycle rate |
| treatment produces more steering events but no rate improvement | run a third comparison on a more complex goal — benefit may appear only at higher cascading-error rates |

"Noise band" is a judgement call, not a p-value claim. The LLM is stochastic; same-config runs vary 10–30% on basic metrics. Look for effect sizes large enough that a casual reader would call them "obvious" before declaring a winner. If the means cross zero on resampling 3 of 5 runs, you don't have a result yet — run more.

## Confounds to watch

- **LLM stochasticity.** Different responses per run change everything. Multiple runs help; same goal seed helps; same model helps.
- **Planner decomposition variance.** The planner can take different subgoal paths each session. Inspect goal-tree shape across runs to catch outliers (e.g. a run that only got to the Book entity vs one that reached Loan).
- **Goal complexity drift.** If the comparison runs hit "easy day" responses where compile diagnostics never matter, the experiment doesn't measure what it claims to. Spot-check: does each run produce at least 1 `Compile probe failed` rejection? If not, the comparison is uninformative.
- **Token cost.** Treatment may burn 20–50% more probe time per session and 5–15% more tokens. Acceptable cost as long as merge_rate is at least equal — but track it, because it's an argument for T4-BN (fast probe) ahead of guard deletion.

## After the experiment

Either way, write a result memo with actual numbers (merge rates, cycle counts, token costs per arm). Save as `project_coverage_guard_experiment_results.md` and cross-link from here.

**If hypothesis confirmed:**
- T4-BN (fast compile probe) becomes high-leverage — with the guard gone, probe latency dominates cycle wall-clock and any speed-up directly improves interactive feedback.
- T2-DU's `STATIC_METHOD_TO_PREFIX` map becomes deletable along with the rest of the guard (per [[feedback-no-hardcoded-ecosystem-data]] — this is one of the row-add patterns the rule warns about).
- T4-BM (discovery) loses its "support the guard" use case but keeps its "support the resolver chain" use case. Still needed; smaller scope.

**If hypothesis falsified:**
- The kill switch stays as an opt-in for diagnostic debugging — e.g. "I want to see what the LLM is *actually* trying so I can fix the guard's rejection rule." Don't delete the switch even though the default stays `true`.
- File a ticket on the specific failure mode: probably one of (a) a known compile-error shape that benefits from pre-LLM rejection (e.g. malformed pom, syntax errors that fingerprint identically across cycles), or (b) a token-cost concern that overwhelms steering value.

## Rollback

Single yaml edit: `autobuilder.evolution.coverage-guard.enabled: true`. Restart the autobuilder. No code revert needed; nothing in the codebase changed under flag-off.

## Why this experiment matters beyond itself

The recurring failure pattern across long-form runs (run `00-21-26` being the latest evidence) is **cascade → row-add → cascade on the next missing row** — see [[feedback-no-hardcoded-ecosystem-data]]. The coverage guard is the architectural site where that pattern lives. If the experiment confirms the guard is net-negative, an entire chain of tickets (T2-DN/T2-DU/T2-DW/T2-DX/parts of T4-BM) deletes in one move, and the system gets simpler. If it falsifies, we at least *know* the guard earns its existence and can stop second-guessing it. Either outcome is high-information.
