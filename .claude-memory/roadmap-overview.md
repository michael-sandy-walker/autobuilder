# Autobuilder Roadmap — Active Queue

**Snapshot:** 2026-05-25 · **Open:** 48 tickets · **Source of truth:** `roadmap.md`.

This file is a thin index over the long roadmap — *one-liner per open ticket*, grouped by tier and active chain. Click through to `roadmap.md` for the full ticket body (root cause, fix, depends-on). If the two ever disagree, `roadmap.md` wins.

**Regenerate by:** `grep -nE "^- \[ \] \*\*T[1-5]-[A-Z]+" memory/roadmap.md` and re-walking the *Proposed chains* section.

---

## Active chains (open work, in execution order)

### Chain H — Build-First Discipline · adaptive layer + observability
Minimum viable subset + escalation shipped. Remaining work makes the build-error path technology-agnostic.

```
T4-AN-4  →  T4-AN-7  →  T4-AN-5  →  T2-CW  →  T4-AN-6
adaptive   category    build-tool  config-   inner-loop
classifier partition   diagnostic  list      (default off)
                                   unify
```
Parallel observability (land T5-N before T4-AN-6 ships):
`T5-N` build_fix_attempt events · `T5-O` drop Maven-specific stderr summariser (after T4-AN-4) · `T5-P` build-cost telemetry

### Chain G — Run 20260422_2 follow-ups
Only the structural-residue tickets remain. Pair `T2-CM` with `T3-BS`.

```
T2-CM  →  T2-CN     (re-evaluate: T2-CI · T2-CO · T2-CR · T5-J)
orphan-    annotation-
aware      key collisions
```

### Chain I — Pre-evaluation compile ground truth
Largely closed; `T2-DG` (rollback-recurrence escalation) subsumed by T2-DD shipped 2026-05-20. Re-evaluate before doing T2-DG separately.

### Chains J, K — fully shipped (2026-05-20, 2026-05-21).

### Chain L — Injector compile-fix SPI · all four resolver modes shipped
The SPI deterministically patches high-confidence build errors instead of routing them through the LLM. Each mode handles one `CANNOT_FIND_SYMBOL` shape.

```
T4-AX   →  T4-AY    →  T4-AZ    →  T1-EN     →  T4-BB        →  T4-AN-4 + T5-W
pom-dep    missing     missing     idiom        missing         adaptive classifier
resolver   classes     imports     deferral     members         + Kind-dispatch swap
[shipped]  [shipped]   [shipped]   [shipped]    [shipped*]      [T4-AN-4 shipped]
```
**T4-BB shipped as the steering-only variant** (extractor + evolution-prompt block, no LLM-backed injector); upgrade to the full `MissingMemberResolver` only if steering underperforms. **T4-AN-4 shipped 2026-05-22** — `Classification.category` is now populated on both adaptive and pattern paths. `T5-W` is the residual cleanup: swap the `Kind`-based deterministic resolver dispatch for `category()`-based dispatch once the inner-loop consumers land.

**T4-BI shipped 2026-05-24** — `SymbolImportResolver` import-rewrite mode for registry-known package replacements. New `WellKnownPackageRegistry.replacedBy(pkg, pomXml)` returns the modern prefix when an SB3+ project hits a deprecated-package diagnostic (`javax.validation` → `jakarta.validation`, `javax.persistence` → `jakarta.persistence`); `resolveMissingPackage` bundles pom edit + source-file import rewrites in one `FixProposal` with rule `package-replace:<old>→<new>`. SB2 projects are not rewritten. 11 new tests (7 in `SymbolImportResolverTest`, 4 in `WellKnownPackageRegistryTest`).

### Chain M — Deferred-mutation ledger (T4-BE) · resolver path shipped; classifier gap closed under T1-ET (shipped)
T4-BE shipped 2026-05-23 but the deferred-mutation ledger recorded zero entries in run `autobuilder-2026-05-23_03-45-48.log` (38 cycles, 28 rejections). **T5-Y shipped 2026-05-24** (label/key preheat — killed the ~4453 warnings/run). **T4-BF shipped 2026-05-24** (per-line classifier + `Outcome` API + `blocker_extraction_outcome` behaviour event; ledger now records entries on real Maven output for `cannot find symbol` shapes). **T4-BG shipped 2026-05-24** (well-known package registry + coverage-guard relaxation + `KnownStarterResolver` deletion — resolver side only; classifier-side reclassification deferred to T1-ET). **T4-BH shipped 2026-05-24** (single output-write package-fragmentation invariant: shared `PackageFragmentationCheck` called from `CompileProbeService` before any write; `MergeAgent`'s broken `!fileExistsInOutput` ordering and its private `findPackageFragmentation` deleted; `BuildResult.FRAGMENTATION_REJECTED` + `DeferredMutationState.DROPPED_PACKAGE_FRAGMENTATION` route the restage path through the new gate). **T4-BI shipped 2026-05-25** (`SymbolImportResolver` import-rewrite mode for registry-known replacements — designed for the deferred-restage path; unreachable on real runs through 05-25 morning until T1-ET shipped). **T2-DT shipped 2026-05-25** (compile-diagnostic evolution steering: new `CompileAuthorErrors` carrier + `CompileDiagnosticPromptBlock` + `COMPILE_DIAGNOSTIC_FED_BACK` / `STEERING_SUPPRESS` signals + 13 tests). **T1-ET shipped 2026-05-25** — Part A closes the classifier-side gap (`PACKAGE_DOES_NOT_EXIST` regex + DEFERRABLE classification + `MutationBlocker.missingPackage` + registry-aware `packageRewrite` upgrade), making T4-BI's `package-replace:*` path reachable for the first time. Part B closes the latent T1-DD migration gap (`markGoalCompleted` → `MarkCompletedOutcome`; `promoteCurrentSubGoal` early-returns on refusal; T3-BL `hasNoActiveDescendants` guard; livelock invariant). Chain M is complete.

---

## Tier 1 — Correctness (1 open)

- `T1-EU` — **`ScoreRegressionHeuristic` mis-scores `NOT_EVALUATED` as catastrophic regression.** When the LLM evaluator returns `llmState=NOT_EVALUATED, llmScore=0.0` (typically a no-diff re-feed of an already-merged mutation), the heuristic computes Δ vs the prior accepted score and fires CRITICAL → REJECTED. Observed in three consecutive audit windows (`2026-05-23_03-45-48` ×2, `2026-05-25_15-37-52` cycle 4, `2026-05-27_01-45-17` cycles 9+10). ~10 LOC fix in `ScoreRegressionHeuristic.score(...)` — short-circuit to neutral on NOT_EVALUATED. Filed 2026-05-27.

`T1-ET` shipped 2026-05-25 — **2026-05-24/05-25 ship-cycle bugfix bundle.** Part A closed the `BlockerExtractor.classify()` gap on `PACKAGE_DOES_NOT_EXIST` (T4-BG/T4-BI half-implementation), making T4-BI's `package-replace:*` rewrite path reachable for the first time. Part B closed the `promoteCurrentSubGoal` pointer-clobber loop (T1-DD migration gap exposed under the new gate pressure) — `markGoalCompleted` returns `MarkCompletedOutcome`, callers handle `REFUSED_ACTIVE_DESCENDANTS` instead of clobbering the T1-DD re-point. T3-BL early-exit now requires `hasNoActiveDescendants`; livelock invariant added as defence-in-depth.

`T1-EK` shipped 2026-05-22 (adoption on cascade-skip). Run analysis of `behaviour-2026-05-22_16-03-43.ndjson` surfaced two ledger-drift sites; consolidated into capability-replacement ticket **T4-BC** instead of being filed as separate T1/T2 patches. A would-have-been `T1-ES` patching `MergeAgent`'s broken `!fileExistsInOutput` ordering on 2026-05-24 was similarly consolidated into capability ticket **T4-BH** (shared `PackageFragmentationCheck` called from the probe — covers the merge step and the restage path together) — **shipped 2026-05-24**.

## Tier 2 — Steering quality (8 open + 2 superseded)

- `T2-DU` — **`EvolutionAgent` coverage-guard relaxation: credit proposal-internal declarations + recognised static-import sources; fix method-shape leaf parsing.** The same guard-mis-fingerprint cascade has driven every long-form run since 2026-05-22 to T1-EI pause — only the trigger varies (`javax.validation` baseline, `setReturned(boolean)` on 05-25, Mockito `when(...)` / JUnit `assertEquals(...)` on 05-27). T4-BG closed it for *type* symbols; this closes it for *method-shape* symbols and proposal-internal declarations. ~70 LOC + 6 tests. Filed 2026-05-27.

- `T2-CM` — Orphan-aware acceptance bias: reward consumer wiring, penalise new orphans. **Chain G.**

- `T2-CN` — Registration-collision heuristic: flag annotation-key duplicates (framework-neutral). **Chain G.**
- `T2-CO` — Spec-literal presence audit: extract literals from goal text, verify in output. *(re-evaluate)*
- `T2-CP` — Structural audit driver: bundle T2-CM/CN/CO outputs into one evaluator signal.
- `T2-CR` — Criteria-preserving refinement when REPLAN fires from evaluator-repeat-cap. *(gated on observation — wait for `EvaluatorRepeatCapFired`)*
- `T2-CW` — Data-drive build-config file detection from `BuildToolProbe`. **Chain H.**
- `T2-DG` — Compile-rollback circuit-breaker for **non-BUILD_FIX feature subgoals** (T2-DD covers BUILD_FIX subgoals only — not subsumed). T1-EC now catches most such failures pre-merge; *verify the gap still occurs in a post-T1-EC run before scheduling.*
- `T2-DP` — Root auto-completes at first-wave ratio while later-wave features sit outside the denominator. **Superseded by T2-DR (shipped 2026-05-22).** Do not schedule. (Was T1-EP.)

## Tier 3 — Feature completion (4 open)

- `T3-G` — Complexity-adaptive regression tolerance — Phase 2 (lines-changed composite + calibration).
- `T3-BO` — Audit T3-BH (plan rationale / sibling context): empirical effectiveness of the PLAN CONTEXT block.
- `T3-BQ` — T3-BH cleanup: shared helper, reload simplification, rationale scope. *(split from T3-BO 2026-04-21)*
- `T3-BS` — Output-structure summary in evolution prompt: surface unused types + dangling references. *(pair with T2-CM)*

## Tier 4 — New systems (26 open)

**Chain H (adaptive build layer):**
- `T4-AN-5` — Build-tool diagnostic probe (yaml-extensible to any ecosystem).
- `T4-AN-6` — Inner build-fix retry loop (intra-cycle, deterministic).
- `T4-AN-7` — Source-vs-Config category partition (trivial after T4-AN-4). **Now bundles the formerly-T5-W consumer migration to typed `Classification`.**
- `T4-AN-2` — Test runner extension (TESTS_PASSING/TESTS_FAILING).

**Critic / output verification:**
- `T4-BA` — Package-fragmentation detection — type split across two packages compiles clean but breaks runtime dispatch. **Step 1 (interim MergeAgent guard: reject + LLM feedback + graph record) shipped 2026-05-22**, subsumed by **T4-BH** (shipped 2026-05-24 — shared helper + probe-time gate; broken `!fileExistsInOutput` ordering deleted, merge-time guard re-pointed at the shared helper as defence-in-depth). Remaining: `PackageFragmentationCritic` for existing on-disk splits — steps 2–3, gated on T4-AW SPI. Was T1-EQ.
- `T4-AW` — Output critic SPI (PathCritic / SymbolCritic / EndpointCritic / DependencyCritic + optional LlmCritic). *(T4-BA may justify accelerating this.)*
- `T4-AQ` — Stub-body detection + automatic follow-up injection.
- `T4-AU` — Ecosystem-aware file-role classification (single source of truth).
- ~~`T4-BI` — `SymbolImportResolver` import-statement rewrite mode~~ — **shipped 2026-05-24** (see Chain L).

**Mutation lifecycle:**
- `T4-BE` — Deferred-mutation ledger: re-stage rolled-back mutations when their compile blockers are resolved by later merges. Graph-backed (`DeferredMutation` + `Missing*` nodes); drift-check + re-probe before re-merge; new `RESTAGE_DEFERRED` agent-step kind. **Shipped 2026-05-23.** T5-Y + T4-BF + T4-BG + T4-BH (all shipped 2026-05-24) closed the "ledger records zero entries on real Maven output" gap, eliminated the upstream javax.validation cascade, and added the probe-time package-fragmentation gate (restage now drops to `DROPPED_PACKAGE_FRAGMENTATION` instead of leaving duplicate types on disk). Remaining Chain M work: T2-DT consumer. **Sibling open:** call-site-vs-receiver-type confusion in `AnalysisAgent` — not yet filed. **Known design gap (surfaced 2026-05-24):** ledger underestimates mutation dependencies — captures only fingerprints from the first stderr batch, so `READY_TO_RESTAGE` candidates may fail re-probe on unfingerprinted transitive missing symbols. Two small follow-up bugs still in the body: `clearedFingerprintCount` reports merge-total instead of per-candidate intersection (`DeferredMutationLedger.java:122`); T5-Y preheat misses `m.llmScore` warnings on null-score readbacks.


**Goal/agent infrastructure:**
- `T4-AI` — Unify meta-steps and deliberation orchestration.
- `T4-AJ` — Semantic goal adoption: re-parent orphaned children to compatible active goals.
- `T4-AO` — Parallel subgoal evolution (concurrent exploration of independent branches).
- `T4-AP` — State-tagged prompt routing (OrchestrationPattern enum + PatternRouter).
- `T4-H` — Hierarchical goal fitness: level-relative scoring + sub-agents.
- `T4-F` — Capability registry + handshake protocol + dynamic sub-agents.

**Signals / heuristics greenfield:**
- `T4-A` — Alignment layer (currently 0% built).
- `T4-C` — Signal trait/pattern spawning.
- `T4-E` — Dynamic heuristics: LLM-proposed evaluation rules.
- `T4-I` — Cross-cycle behavioural pattern heuristics.
- `T4-AL` — Signal dynamics redesign: saturation, decay, fitness normalisation.

**Operational:**
- `T4-J` — Tuning parameter store (DB-persisted `autobuilder.tuning.*`).
- `T4-AR` — Mission-control overlay: phase-level event stream + drill-down modals.
- `T4-AS` — Multi-provider LLM fallback *(design placeholder)*.
- `T4-AT` — Session resume after SYSTEM_PAUSED *(design placeholder)*.
- `T4-AN` — Build/test execution as grounded evaluation signal *(parent — split into T4-AN-1..7)*.

## Tier 5 — Quality / observability (9 open + 5 superseded)

**Chain H observability:**
- `T5-N` — `build_fix_attempt` events for outer + inner loop.
- `T5-P` — Build-execution cost telemetry per session + per subgoal.

**Chain M operational:** *(`T5-Y` shipped 2026-05-24; `blocker_extraction_outcome` event folded into T4-BF per `feedback_capability_ticket_includes_delete`.)*

**Other:**
- `T5-T` — Surface goal/subgoal description on `cycle_start` + rationale on `deliberation_outcome`.
- `T5-R` — LLM token usage + cost tracking.
- `T5-I` — Behaviour logger v2: goal-tree + pattern-detector events.
- `T5-J` — Skip (or contextualise) evaluation meta-step after `#NoDiff`. *(cosmetic)*
- `T5-G` — Investigate persistent SCOPE_RESULT_OUT_OF_SCOPE signal (decayPerCycle=0.0).
- `T5-C` — SMS Phase 2: richer semantic model.
- `T5-L` — Security hardening for non-local / multi-tenant deployment.

---

## Open follow-ups outside the chains

- **Per-link telemetry for the criteria chain (AW→BK).** Mechanically complete but no per-link telemetry; only the downstream veto fires when upstream misfires. ~30 LOC + tests. *No ticket filed.*
- **`autobuilder.build.enabled` TODO** in `application.yml:50` — comment says "currently not working correctly" but `enabled: true`. Audit & file or delete.
- **`PlannerAgent` PLANNING_THROTTLED clear path** (lines ~121–132) — commented out with rationale. Either uncomment + test or delete the comment.

## Deferred (`[>]`) — kept here so they don't get re-filed

**T5 deletions folded into parent capability tickets 2026-05-22** (per `feedback_capability_ticket_includes_delete`): `T5-U` + `T5-O` + `T5-V` superseded by **T4-BD** (Maven stderr scaffolding retirement); `T5-W` folded into **T4-AN-7** (consumers migrate to typed `Classification` as part of the partition). Don't schedule these — they're tracked in their parent tickets now.

Older deferrals: `T1-D` (Neo4j purge scope — intentional during hello-world) · `T1-BL` / `T1-BM` (withdrawn — T1-BN removed root cause) · plus 11 more — see `roadmap.md` for the full list.
