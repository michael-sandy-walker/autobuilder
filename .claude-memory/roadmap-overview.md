# Autobuilder Roadmap — Active Queue

**Snapshot:** 2026-06-04 · **Open:** 52 tickets (T1-EX + T2-DY SHIPPED 2026-06-05, commit `2fac099` — T1 now 0 open; T2-DY verified end-to-end against the 06-05 run, T1-EX's closure arm still field-unvalidated) (T5-AE SHIPPED 2026-06-03 — stable `autobuilder.log` / `behaviour.ndjson` live targets restored via `StartupLogRotator` listener firing before logback init; `<rollingPolicy>` blocks removed; retention tightened to 10 days. T1-EX + T2-DY originally filed 2026-06-01 with unverifiable evidence; **Observed sections rewritten 2026-06-04** against the 2026-06-03 validation run — both mechanisms verified, originally cited `behaviour-2026-06-01_13-27-47.ndjson` does not exist in the corpus. T1-EX is milder than originally framed (1 ACTIVE phantom + 1 SUBSUMED-via-replan vs claimed 10 phantoms; the run terminated via POTENTIALLY_COMPLETE at cycle 68, not "no termination in sight"). T2-DY is more severe (9× `symbol-import:spring-web` refire over 4 minutes vs originally claimed 3× `symbol-import:local`). T4-BN Phase 1 shipped 2026-06-01 — `javac`-direct compile probe with classpath cache, opt-in via `autobuilder.build.executor`; T4-BN-2 filed as the Phase 2 follow-up. T5-AD shipped 2026-06-01 — Neo4j preheat sentinel kills 421 warnings/run. T1-EW shipped 2026-05-29 — `CompileAuthorErrors` carrier identity fix unblocked T2-DT/T2-DV silent failure. T2-DN + T2-DU code DELETED 2026-05-29 after the off-switch experiment; T2-DW + T2-DX closed obsolete). See `memory/project_coverage_guard_experiment.md` for the guard-deletion result memo. · **Source of truth:** `roadmap.md` · **Tier criteria:** [`tier_definitions.md`](tier_definitions.md).

This file is a thin index over the long roadmap — *one-liner per open ticket*, grouped by tier and active chain. Click through to `roadmap.md` for the full ticket body (root cause, fix, depends-on). If the two ever disagree, `roadmap.md` wins. For tier criteria see [`tier_definitions.md`](tier_definitions.md).

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

## Tier 1 — Correctness (0 open)

- ✅ **SHIPPED 2026-06-05** (`2fac099`; closure arm field-UNVALIDATED — 0 fires across 06-03/06-05, replan/T1-DR win first; wiring tested, necessity unproven, delete-watch if it stays at zero) — `T1-EX` — **`[BUILD_FIX]` subgoals never close when their referenced diagnostic disappears.** Re-verified 2026-06-04 against `behaviour-2026-06-03_01-43-00.ndjson` + live `behaviour.ndjson` (68 cycles, terminated via POTENTIALLY_COMPLETE): 1 ACTIVE phantom (`[BUILD_FIX] Create BookService [UNDEFINED].java with getBooks() method` — `BookService.java` exists, closure condition satisfied), 1 SUBSUMED-via-replan (`[BUILD_FIX] Resolve missing symbol getBooks() in LoanController.java` — proves replanning is the *only* closure path). `BlockerSubgoalInjector` is half-built — creation side ships, closure side doesn't exist. Fix: new `BuildFixClosureGate` post-probe hook that walks active `[BUILD_FIX]` subgoals, checks current diagnostics + file content, marks COMPLETED when reference is gone; repairs `currentSubGoalId` via `resolveNextActiveLeaf` (shared with T1-EL, which fires 2× in the same run). Stricter pre-injection guard prevents duplicate phantoms at the source. Originally cited `behaviour-2026-06-01_13-27-47.ndjson` could not be located; see `memory/feedback_verify_population_before_claim.md`. ~150 LOC + 8 tests. **Filed 2026-06-01; Observed section rewritten 2026-06-04.**

`T1-EW` shipped 2026-05-29 — **`CompileAuthorErrors` carrier identity (subgoal → probe state).** Original `(subgoalId, diagnostics)` shape was a category error: the carrier describes compile-probe errors on disk, not a goal-hierarchy attribute. Subgoal-shift suppression in `ContextLineageTracker` + `CompileDiagnosticPromptBlock` collided with T1-DM BUILD_FIX subgoal injection (fired on the very rejection that wrote the carrier), silently disabling T2-DT/T2-DV across all probe rejections since 2026-05-25. Fix removes the stamp and both suppressions; carrier lifecycle now natural (write on probe-rejection, overwrite on next probe, clear on merge). ~30 LOC + 4 test updates. Verified against `behaviour-2026-05-29_00-44-49.ndjson` (5 candidate REFUSED_AUTHOR_ERROR situations all silently suppressed).

`T1-EU` shipped 2026-05-27 — **`ScoreRegressionHeuristic` abstain on `NOT_EVALUATED`.** When the LLM evaluator returns `llmState=NOT_EVALUATED, llmScore=0.0` (typically a no-diff re-feed of an already-merged mutation), the heuristic now short-circuits to 0.0 confidence instead of computing Δ vs prior accepted baseline and firing CRITICAL → REJECTED. ~10 LOC + 1 test. Verified in `behaviour-2026-05-27_22-30-18.ndjson` cycle 5.

`T1-EV` shipped 2026-05-27 — **`CompileAuthorErrors` Jackson round-trip.** `@JsonIgnore` on `isEmpty()` stops Jackson from emitting JSON property `"empty"` that the canonical record constructor can't accept. Pre-fix: every cycle after cycle 1 silently failed `meta.json` reads (8 `mutation_meta_persist_failed` events / 12 cycles in run `22-30-18`); T2-DT's cross-cycle steering was dead-on-arrival. Post-fix: run `22-54-03` shows 0 persist failures + first observed `evolution_steering_compile_diagnostics_fed_back` events. Filed retroactively the same session.

`T1-ET` shipped 2026-05-25 — **2026-05-24/05-25 ship-cycle bugfix bundle.** Part A closed the `BlockerExtractor.classify()` gap on `PACKAGE_DOES_NOT_EXIST` (T4-BG/T4-BI half-implementation), making T4-BI's `package-replace:*` rewrite path reachable for the first time. Part B closed the `promoteCurrentSubGoal` pointer-clobber loop (T1-DD migration gap exposed under the new gate pressure) — `markGoalCompleted` returns `MarkCompletedOutcome`, callers handle `REFUSED_ACTIVE_DESCENDANTS` instead of clobbering the T1-DD re-point. T3-BL early-exit now requires `hasNoActiveDescendants`; livelock invariant added as defence-in-depth.

`T1-EK` shipped 2026-05-22 (adoption on cascade-skip). Run analysis of `behaviour-2026-05-22_16-03-43.ndjson` surfaced two ledger-drift sites; consolidated into capability-replacement ticket **T4-BC** instead of being filed as separate T1/T2 patches. A would-have-been `T1-ES` patching `MergeAgent`'s broken `!fileExistsInOutput` ordering on 2026-05-24 was similarly consolidated into capability ticket **T4-BH** (shared `PackageFragmentationCheck` called from the probe — covers the merge step and the restage path together) — **shipped 2026-05-24**.

## Tier 2 — Steering quality (7 open + 4 superseded)

- ✅ **SHIPPED + VERIFIED 2026-06-05** (`2fac099`; shipped the *inverse* of the filed mtime-bump design — content-digest + record-after-rollback, because mtime self-clears on the rollback; verified: LoanService skeleton skipped 5 consecutive cycles, counter frozen at 2, no deadlock) — `T2-DY` — **`SymbolImportResolver` / `IdiomSkeletonResolver` no-progress detection — same fix re-fires every cycle when re-probe still fails.** Re-verified 2026-06-04 against `autobuilder-2026-06-03_01-42-56.log`: `symbol-import:spring-web — Added import RestController + dep org.springframework:spring-web (touched [LoanController.java, pom.xml])` fired **9 identical times** between 01:53:39 → 01:57:57, every re-probe COMPILE_ERROR. Same run: `skeleton-repository — created skeleton .../LoanRepository.java (JpaRepository<Object, Long>)` fired 2× with placeholder generics, both COMPILE_ERROR. Resolver has no idempotency / no-progress ledger. Fix: new `FixAttemptLedger` keyed by `(rule, symbol, sortedTargetPathsHash)`; skip-on-repeat-failure with mtime-bump invalidation; target-path existence guard on `FixProposal` construction; `proposalHash()` stamp on `InjectorFixRecord`. Two new behaviour events. Originally cited `autobuilder-2026-06-01_13-27-47.log` could not be located. **Filed 2026-06-01; Observed section rewritten 2026-06-04. Pairs with T4-BE / T4-BH / T4-AN-6.**


`T2-DN` (shipped 2026-05-21) and `T2-DU` (shipped 2026-05-27) **DELETED 2026-05-29** — coverage-guard family removed entirely after the off-switch experiment confirmed it was net-negative. The 30-row `STATIC_METHOD_TO_PREFIX` map and the `org.mockito` + `org.assertj` TABLE entries went with the guard per `feedback_ship_full_scope`. `T2-DW` + `T2-DX` closed as obsolete (their motivation was the guard).

`T2-DV` shipped 2026-05-28 — **Compile-diagnostic prompt block: bundle + relative cap.** Diagnostics now group by `BlockerExtractor.normaliseShape(...)` (T4-BF's key, promoted public); first occurrence per shape renders with `[×N]` suffix when N > 1. Fixed `MAX_ENTRIES = 5` replaced by relative cap (later simplified to `min(8, distinctShapes)` on 2026-05-28). Truncation footer reports dropped *shape* count.

- `T2-CM` — Orphan-aware acceptance bias: reward consumer wiring, penalise new orphans. **Chain G.**

- `T2-CN` — Registration-collision heuristic: flag annotation-key duplicates (framework-neutral). **Chain G.**
- `T2-CO` — Spec-literal presence audit: extract literals from goal text, verify in output. *(re-evaluate)*
- `T2-CP` — Structural audit driver: bundle T2-CM/CN/CO outputs into one evaluator signal.
- `T2-CR` — Criteria-preserving refinement when REPLAN fires from evaluator-repeat-cap. *(gated on observation — wait for `EvaluatorRepeatCapFired`)*
- `T2-CW` — Data-drive build-config file detection from `BuildToolProbe`. **Chain H.**
- `T2-DG` — Compile-rollback circuit-breaker for **non-BUILD_FIX feature subgoals** (T2-DD covers BUILD_FIX subgoals only — not subsumed). T1-EC now catches most such failures pre-merge; *verify the gap still occurs in a post-T1-EC run before scheduling.*
- `T2-DP` — Root auto-completes at first-wave ratio while later-wave features sit outside the denominator. **Superseded by T2-DR (shipped 2026-05-22).** Do not schedule. (Was T1-EP.)

## Tier 3 — Feature completion (6 open)

- `T3-BT` — **Re-probe complexity escalation in `DeferredMutationRestager`.** Restager scaffold exists; resolver-retry path on re-probe failure isn't wired end-to-end. When re-probe fails on a *new* `DEFERRABLE` / `MISSING_PACKAGE` shape, run one resolver pass + retry (cap 2) before discarding. *(Originally filed as T2-DW, re-tiered 2026-05-27.)* ~50 LOC + 2 tests.
- `T3-BU` — **`WellKnownPackageRegistry` framework-version jargon removal.** Registry scaffold exists; ecosystem-agnostic abstraction isn't wired end-to-end (method names + Javadoc still carry `SpringBoot2` / `pomXml` / `javax` / `jakarta` literals). Completes the abstraction the registry's existence already promises. Blocks **T4-BJ**. *(Originally filed as T5-Z, re-tiered 2026-05-27.)* ~50 LOC + Javadoc rewrites + test signature updates.
- `T3-G` — Complexity-adaptive regression tolerance — Phase 2 (lines-changed composite + calibration).
- `T3-BO` — Audit T3-BH (plan rationale / sibling context): empirical effectiveness of the PLAN CONTEXT block.
- `T3-BQ` — T3-BH cleanup: shared helper, reload simplification, rationale scope. *(split from T3-BO 2026-04-21)*
- `T3-BS` — Output-structure summary in evolution prompt: surface unused types + dangling references. *(pair with T2-CM)*

## Tier 4 — New systems (27 open + 2 deferred)

**Strategic backstops:**

`T4-BN` Phase 1 shipped 2026-06-01 — **Fast `javac`-direct compile probe.** In-process `javax.tools.JavaCompiler`, classpath cached via `mvn dependency:build-classpath` keyed on `(workingDir, pomMtime)`. Targets ≤1.5s typical probe time vs ~5-15s for the maven path. Opt-in via `autobuilder.build.executor: javac-direct` (default `maven`); off-switch pattern for validation. Annotation processors (Lombok, MapStruct) auto-discovered from classpath. 8 new tests + 3 dispatch tests; 1410/1410 full suite.

- `T4-BN-2` (Phase 2) — **Incremental javac probe + test-compile coverage.** Recompile only files whose mtime changed since the last probe; add `src/test/java` compile pass. Targets <500ms per probe. Filed 2026-06-01 as the Phase 1 follow-up.
- `T4-BO` — **Signal-decay observability — measure before tuning.** ~20 signal types each have hand-tuned `decayPerCycle` constants. No data exists on whether the constants correlate with the actions they should drive. New `SignalLifetimeTracker` + correlation analyser produce per-signal lifetime distributions and signal→action correlation. Prerequisite for `T4-J` (tuning store) being useful. Design only; no code. Pairs with `T4-J`, `T5-I`.

**Strategic resolver follow-ups:**
- `T4-BM` — **Replace `WellKnownPackageRegistry` hardcoded tables with project-aware discovery.** Cure for the recurring debt pattern: every long-form run since 2026-05-22 has terminated on a guard-cascade triggered by a missing registry row, and the short-term fix cycle (T4-BG → T4-BI → T2-DU) closes the immediate cascade but freezes more worldview into framework code. Discovery sources, cheap → expensive: project pom dependency tree → project source imports → Maven Central REST → OpenRewrite recipes (under T4-BJ if adopted) → LLM lookup. Registry becomes a fallback. Three hardcoded maps shrink to zero. Filed 2026-05-27 after user pushback on T2-DU's 30-row static-method map: *"That fixed code is doom. It will fight against error correction in the future."* See [[feedback-no-hardcoded-ecosystem-data]].
- `T4-BJ` — **OpenRewrite integration — design placeholder.** Strategic answer to the short-term `WellKnownPackageRegistry.PACKAGE_REPLACED_BY` map (T4-BG + T4-BI). Survey OpenRewrite recipe coverage, sketch a `RewriteRecipeRunner` SPI fitting the resolver chain, recommend adopt / defer / reject. Design only — no code in this scope. Blocked behind **T3-BU** if adopted. **Now also a candidate discovery source under T4-BM.** Filed 2026-05-27.
- `T4-BK` *(deferred)* — Partial restage of multi-file deferred mutations. Conflicts with whole-mutation atomicity unless a structural-cohesion analysis identifies safe partitions. Re-evaluate when **T4-AW** lands.
- `T4-BL` *(deferred)* — Package-hierarchy refactor recommendation on fragmentation conflict (recommend refactoring existing class into new layout rather than rejecting new write). Re-evaluate when AnalysisAgent or T4-AW reviewer SPI exposes a "structural-improvement" surface.


**Chain H (adaptive build layer):**
- `T4-AN-5` — Build-tool diagnostic probe (yaml-extensible to any ecosystem).
- `T4-AN-6` — Inner build-fix retry loop (intra-cycle, deterministic).
- `T4-AN-7` — Source-vs-Config category partition (trivial after T4-AN-4). **Now bundles the formerly-T5-W consumer migration to typed `Classification`.**
- `T4-AN-2` — Test runner extension (TESTS_PASSING/TESTS_FAILING).

**Reviewer / output verification:**
- `T4-BA` — Package-fragmentation detection — type split across two packages compiles clean but breaks runtime dispatch. **Step 1 (interim MergeAgent guard: reject + LLM feedback + graph record) shipped 2026-05-22**, subsumed by **T4-BH** (shipped 2026-05-24 — shared helper + probe-time gate; broken `!fileExistsInOutput` ordering deleted, merge-time guard re-pointed at the shared helper as defence-in-depth). Remaining: `PackageFragmentationReviewer` for existing on-disk splits — steps 2–3, gated on T4-AW SPI. Was T1-EQ.
- `T4-AW` — Output reviewer SPI (PathReviewer / SymbolReviewer / EndpointReviewer / DependencyReviewer + optional LlmReviewer). *(T4-BA may justify accelerating this.)*
- `T4-AQ` — Stub-body detection + automatic follow-up injection.
- `T4-AU` — Ecosystem-aware file-role classification (single source of truth).
- ~~`T4-BI` — `SymbolImportResolver` import-statement rewrite mode~~ — **shipped 2026-05-24** (see Chain L).

**Mutation lifecycle:**
- `T4-BE` — Deferred-mutation ledger: re-stage rolled-back mutations when their compile blockers are resolved by later merges. Graph-backed (`DeferredMutation` + `Missing*` nodes); drift-check + re-probe before re-merge; new `RESTAGE_DEFERRED` agent-step kind. **Shipped 2026-05-23.** T5-Y + T4-BF + T4-BG + T4-BH (all shipped 2026-05-24) closed the "ledger records zero entries on real Maven output" gap, eliminated the upstream javax.validation cascade, and added the probe-time package-fragmentation gate (restage now drops to `DROPPED_PACKAGE_FRAGMENTATION` instead of leaving duplicate types on disk). Remaining Chain M work: T2-DT consumer. **Sibling open:** call-site-vs-receiver-type confusion in `AnalysisAgent` — not yet filed. **Known design gap (surfaced 2026-05-24):** ledger underestimates mutation dependencies — captures only fingerprints from the first stderr batch, so `READY_TO_RESTAGE` candidates may fail re-probe on unfingerprinted transitive missing symbols. Two small follow-up bugs still in the body: `clearedFingerprintCount` reports merge-total instead of per-candidate intersection (`DeferredMutationLedger.java:122`); T5-Y preheat misses `m.llmScore` warnings on null-score readbacks.


**Goal/agent infrastructure:**
- `T4-AI` — Unify meta-steps and deliberation orchestration.
- `T4-AJ` — Semantic goal-equivalence **LLM reviewer** + 2 consumers: Part A orphan adoption (re-parent merged-bearing children at give-up), Part B ACTIVE-tree consolidation (fold overlapping ACTIVE siblings during stagnation → SUBSUMED/COVERED). Shared reviewer because MongeElkan @ 0.88 demonstrably can't separate same-work-different-words from different-work-similar-words (measured 2026-06-04). Absorbs the would-be "T4-AK consolidation pass." Ship reviewer + Part A first; Part B additive.
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

## Tier 5 — Quality / observability (10 open + 2 deferred + 6 superseded)

`T5-AE` shipped 2026-06-03 — **Restored stable `autobuilder.log` / `behaviour.ndjson` live targets via `StartupLogRotator` listener + tightened `logs/` retention to 10 days.** Spring `ApplicationListener<ApplicationStartingEvent>` registered via `META-INF/spring.factories` fires before logback init: renames existing `autobuilder.log` → `autobuilder-<itsOwnStartTime>.log` (extracted from its first-line timestamp), then logback opens fresh. `<rollingPolicy>` blocks dropped. Verified by 2026-06-03 run: live `autobuilder.log` + `behaviour.ndjson` co-exist with the per-run timestamped files.

`T5-AD` shipped 2026-06-01 — **Extend T5-Y preheat: sentinel write-and-delete in `Neo4jDeferredMutationGraphService.ensureSchema()`.** Constraint/index alone wasn't enough — Neo4j emits `UnknownPropertyKeyWarning` until property keys appear in actual node data. Sentinel `__autobuilder_preheat_sentinel__` writes one `:DeferredMutation` + `:Blocker` + `:DeferredFile` with every persisted property and immediately `DETACH DELETE`s them. Suppresses the 421 warnings/run observed in `autobuilder-2026-05-28_00-21-26.log`.
- `T5-AC` — **`MarkCompletedOutcome` → sealed `MarkCompletedResult` — exhaustive-switch enforcement.** T1-ET Part B shipped a record + enum kind; callers branch on `outcome.kind() == ...` and a new variant wouldn't break the existing call sites. Compile-time safety / debuggability — no behaviour change. *(Originally filed as T1-EV, re-tiered 2026-05-27 — T1 reserved for "observably broken / produces wrong output", which this is not.)* ~30 LOC + 2-3 caller migrations.
- `T5-AA` *(deferred)* — Cross-cycle aggregation of `REFUSED_UNKNOWN` author-error shapes. Re-evaluate when `firstUnknownLine` fires on >10 distinct shapes per long-form run.
- `T5-AB` *(deferred)* — Telemetry-driven `mutation.deferred.ttlCycles` tuning. Re-evaluate when ≥30 long-form runs with ≥1 clearance event each are in the corpus.


**Chain H observability:**
- `T5-N` — `build_fix_attempt` events for outer + inner loop.
- `T5-P` — Build-execution cost telemetry per session + per subgoal.

**Chain M operational:** *(`T5-Y` shipped 2026-05-24; `blocker_extraction_outcome` event folded into T4-BF per `feedback_ship_full_scope`.)*

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

**T5 deletions folded into parent capability tickets 2026-05-22** (per `feedback_ship_full_scope`): `T5-U` + `T5-O` + `T5-V` superseded by **T4-BD** (Maven stderr scaffolding retirement); `T5-W` folded into **T4-AN-7** (consumers migrate to typed `Classification` as part of the partition). Don't schedule these — they're tracked in their parent tickets now.

Older deferrals: `T1-D` (Neo4j purge scope — intentional during hello-world) · `T1-BL` / `T1-BM` (withdrawn — T1-BN removed root cause) · plus 11 more — see `roadmap.md` for the full list.
