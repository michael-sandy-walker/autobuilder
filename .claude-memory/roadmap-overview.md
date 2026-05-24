# Autobuilder Roadmap — Active Queue

**Snapshot:** 2026-05-24 · **Open:** 48 tickets · **Source of truth:** `roadmap.md`.

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

### Chain M — Deferred-mutation ledger (T4-BE) · make it actually record entries
T4-BE shipped 2026-05-23 but the deferred-mutation ledger recorded zero entries in run `autobuilder-2026-05-23_03-45-48.log` (38 cycles, 28 rejections). Two follow-ups + one parallel resolver lift close the gap. **T5-Y shipped 2026-05-24** (label/key preheat + `state` index + `Blocker(fingerprint)` constraint; killed the ~4453 warnings/run, also formalised two latent uniqueness invariants).

```
T4-BF                              T2-DT
BlockerExtractor robustness    →  compile-diagnostic
(classifier + behaviour event,     evolution steering
 single-scope, no T5 split)        (consumes Outcome.authorErrors)

T4-BG (parallel — highest single-ticket leverage)
well-known package map + cache eviction + coverage-guard relaxation
```
T4-BF bundles the extractor rewrite + `EvaluationAgent` migration + `blocker_extraction_outcome` behaviour event in one scope (per `feedback_capability_ticket_includes_delete` — no separate Tier 5 observability follow-up). T4-BF unblocks T2-DT. T4-BG lands independently; it eliminates 16 of 28 rejections in the diagnostic run by giving the resolver a path for `javax.validation` / `jakarta.validation` / Spring-security package failures + evicting stale `SymbolImportCache` entries for registry-covered names.

---

## Tier 1 — Correctness (0 open)

`T1-EK` shipped 2026-05-22 (adoption on cascade-skip). Run analysis of `behaviour-2026-05-22_16-03-43.ndjson` surfaced two ledger-drift sites; consolidated into capability-replacement ticket **T4-BC** instead of being filed as separate T1/T2 patches.

## Tier 2 — Steering quality (8 open + 2 superseded)

- `T2-DT` — Compile-diagnostic-aware evolution steering: feed AUTHOR_ERROR diagnostics from rolled-back probes (`non-static method ... static context`, `incompatible types`, ...) into the next evolution prompt for the same subgoal. Free-rider on T4-BF's classifier. **Chain M.**

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

## Tier 4 — New systems (28 open)

**Chain H (adaptive build layer):**
- `T4-AN-5` — Build-tool diagnostic probe (yaml-extensible to any ecosystem).
- `T4-AN-6` — Inner build-fix retry loop (intra-cycle, deterministic).
- `T4-AN-7` — Source-vs-Config category partition (trivial after T4-AN-4). **Now bundles the formerly-T5-W consumer migration to typed `Classification`.**
- `T4-AN-2` — Test runner extension (TESTS_PASSING/TESTS_FAILING).

**Critic / output verification:**
- `T4-BA` — Package-fragmentation detection — type split across two packages compiles clean but breaks runtime dispatch. **Step 1 (interim MergeAgent guard: reject + LLM feedback + graph record) shipped 2026-05-22.** Remaining: `PackageFragmentationCritic` for existing on-disk splits — steps 2–3, gated on T4-AW SPI. Was T1-EQ.
- `T4-AW` — Output critic SPI (PathCritic / SymbolCritic / EndpointCritic / DependencyCritic + optional LlmCritic). *(T4-BA may justify accelerating this.)*
- `T4-AQ` — Stub-body detection + automatic follow-up injection.
- `T4-AU` — Ecosystem-aware file-role classification (single source of truth).

**Mutation lifecycle:**
- `T4-BE` — Deferred-mutation ledger: re-stage rolled-back mutations when their compile blockers are resolved by later merges. Graph-backed (`DeferredMutation` + `Missing*` nodes); drift-check + re-probe before re-merge; new `RESTAGE_DEFERRED` agent-step kind. **Shipped 2026-05-23 but the ledger recorded zero entries** in the 38-cycle diagnostic run — completion tracked under **Chain M** (T5-Y → T4-BF → T2-DT; T4-BG in parallel). **Sibling open:** call-site-vs-receiver-type confusion in `AnalysisAgent` (recommended adding `setReturned` to the call site, not the receiver type) — not yet filed.
- `T4-BF` — BlockerExtractor robustness: extend `BANNER_LINE` for Maven footers, replace binary attribution with per-line `DiagnosticClass` (DEFERRABLE / AUTHOR_ERROR / UNKNOWN), emit `blocker_extraction_outcome` behaviour event with normalised `errorShapes`. Bundles capability + caller migration + observability in one scope. Unblocks T2-DT. **Chain M.**
- `T4-BG` — Well-known external package → Maven coordinate map (`javax.validation`, `jakarta.validation.*`, `org.hibernate.validator`, Spring-security/data-jpa, JUnit) + `SymbolImportCache` boot-time eviction of registry-covered names + EvolutionAgent coverage-guard (T2-DN) relaxation for resolver-covered missing symbols. Eliminates 16/28 rejections in the diagnostic run — highest single-ticket leverage. **Chain M.**


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
