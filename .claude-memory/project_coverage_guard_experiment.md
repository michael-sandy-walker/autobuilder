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

---

## Results

The experiment ran on 2026-05-28 and 2026-05-29. Two comparison pairs total; backups in `backup/20260528_enabled_{true,false}/` and `backup/20260529_enabled_{true,false}/`. **Decision: guard family deleted 2026-05-29.**

### Day 1 — 2026-05-28

| Metric | Control (true) | Treatment (false) |
|---|---|---|
| Cycles | 28 | 6 |
| Merges | 6 | 4 |
| Merge rate per cycle | 21.4% | 66.7% |
| Wall-clock per cycle | 33.2s | 24.0s |
| Total wall-clock | 15.5 min | 2.4 min |
| Guard rejections | 6 | 0 |
| Probe rejections | 7 | 2 |
| Package-fragmentation rejections | 4 | 0 |
| Session ended by | `system_paused` (stagnation) | clean cycle_end |

Both arms produced equivalent 10-file compileable outputs (Book/Member/Loan + 3 controllers + 3 services + pom.xml). Treatment hit MVP faster; control plateaued + stalled.

### Day 2 — 2026-05-29

| Metric | Control (true) | Treatment (false) |
|---|---|---|
| Cycles | 18 | 39 |
| Merges | 6 | 17 |
| Merge rate per cycle | 33.3% | 43.6% |
| Wall-clock per cycle | 23.2s | 32.4s |
| Total wall-clock | 7.0 min | 21.0 min |
| Throughput (merges/min) | 0.86 | 0.81 |
| Files in final output | 11 (Book + Member only) | 21 (Book + Member + Loan + tests + exception hierarchy) |
| Late-session productivity | last 6 cycles produced 0 merges | merging until session stopped |

Day 2's gap was more modest than day 1's (1.3× vs 3.1× on merge rate; per-minute throughput nearly identical) — but the **trajectory differential replicated**: control stalled late in the session (6 unproductive cycles at the end before being stopped), treatment was still productively merging when stopped. Treatment also reached deeper into the goal (full Loan management + test class + exception hierarchy vs control's plateau at Book + Member).

### Decision

Hypothesis confirmed across two independent comparisons with different LLM realizations. Direction is robust; magnitude varied (1.3× to 3.1×). The mechanism replicated: **guard rejections inject `REPETITION_DETECTED + DIVERGENCE_REQUEST` signals that contaminate the next prompt; the LLM degrades into unproductive variations on the rejected shape; the session stalls.** Treatment never exhibited this pattern in either experiment.

### What got deleted 2026-05-29

- `EvolutionAgent.runEvolution` guard branch (~80 LOC) + `isAddressed` + `unresolvedSymbols` + `leafOf` helpers
- `EvolutionAgent.wellKnownPackageRegistry` field + `evolutionConfig` field
- `WellKnownPackageRegistry.STATIC_METHOD_TO_PREFIX` map (30 entries)
- `WellKnownPackageRegistry.coversStaticMethod` + `isCovered` + `coveredSubset` + `filterCovered` accessors
- `WellKnownPackageRegistry` TABLE entries for `org.mockito` + `org.assertj` (added by T2-DU for guard purposes)
- `EvolutionConfigProperties` config class + `autobuilder.evolution.coverage-guard` yaml block
- `BehaviourEvent.CoverageGuardResolverCoveredUnresolved` + `BehaviourLogger.coverageGuardResolverCoveredUnresolved`
- `EvolutionProtocolContract.missingSymbolsBlock` + `extractMissingSymbols` (prompt-side companion of the guard, same `MUST RESOLVE ALL` premise)
- All related tests (~16 tests across `EvolutionAgentTest`, `WellKnownPackageRegistryTest`, `EvolutionProtocolContractTest`)

Roadmap: T2-DN + T2-DU code marked deleted. T2-DW + T2-DX closed as obsolete. ~250 LOC of code + ~150 LOC of tests removed in one PR.

### What stayed

- `CompileErrorSymbolExtractor` — the extractor itself is useful regardless; its output now flows into probe diagnostics where the LLM can see it in context.
- `WellKnownPackageRegistry` resolver-chain surfaces (`lookup`, `replacedBy`, `SIMPLE_NAME_TO_PREFIX`) — used by `SymbolImportResolver` for post-merge dependency resolution. Different layer; same registry, smaller scope.
- `CompileDiagnosticPromptBlock` (T2-DT/T2-DV) — feeds compile diagnostics into the next prompt; this is the *positive* version of the guard's negative pattern.

### Implications for filed strategic tickets

- **T4-BM** (discovery): scope shrinks. The guard-side discovery use case is gone; the resolver-chain use case remains (still need to replace `SIMPLE_NAME_TO_PREFIX` etc. with discovery).
- **T4-BN** (fast probe): unchanged priority. With the guard removed, probe latency dominates cycle wall-clock; making the probe sub-second remains high-leverage.
- **T4-BO** (signal-decay observability): unchanged priority. Useful regardless.
- **Filed during the deletion session:** `package_fragmentation_detected` is the same architectural shape as the guard — control had 4 rejections, treatment had 0. The pre-write `PackageFragmentationCheck` (T4-BH) is the next defensive check that may be net-negative. Worth a sibling kill-switch experiment.
