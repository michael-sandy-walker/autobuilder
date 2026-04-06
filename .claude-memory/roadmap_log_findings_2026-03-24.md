# Roadmap — Log Findings 2026-03-24

Findings from live log analysis of session started at **2026-03-24 22:26:47** (dev-auto profile, system reset, 8 cycles, result: GOAL_FULFILLED).

Status legend: `[ ]` open · `[~]` in progress · `[x]` done · `[>]` deferred

---

## Confirmed fix (this session)

- [x] **LF-1: `MergeActionRegistrar` never fired for single-file adoptions**
  - **Root cause:** `registerMerge` was called inside `MergeLLMAdapter.merge()`, which is only reached
    for multi-variant LLM merges. `MergeProcessor.process()` returns early with `asAdopted()` for
    single-variant files without ever calling the adapter — so those adoptions left no graph record.
  - **Fix (2026-03-24):** Moved `registerMerge` call to `MergeAgent` after `cycleMergeTracker.track()`,
    using `MergeResult` instead of `MergeOutcome`. Now covers both adopted and LLM-merged files.
  - **Evidence:** cycle-1774387610495 adopts `TaskController.java` + `Task.java`; both now registered.

---

## Open findings

- [ ] **LF-2: `context.merged` false-negative when evolution and merge run in different cycles**
  - **Root cause:** `CycleMergeTracker.markCyclesAsMerged()` sets `context.merged=true` only when
    `context.cycleName()` is in `mergedCycles` (the contributing evolution cycle IDs). When the merge
    agent runs in cycle N+1 to process cycle N's mutation, `mergedCycles = {cycle-N}` and
    `cycle-N+1` is absent → `context.merged` stays `false`.
  - **Observed:** cycle-1774387726461 logs `Merged file: TaskController.java` and
    `Marked cycle ...697068 as merged` but CYCLE log shows `merged=false, Merged: false`.
  - **Impact:**
    - `AutobuilderService` logs `Merged: false` when a file was physically written — misleading.
    - `AdaptiveExecutionEngine.shouldEndCycle()` checks `ctx.merged()` — cycle cannot terminate via
      the merge path in cross-cycle scenarios; relies on other termination conditions instead.
    - Any future logic gating on `context.merged()` will misfire in this pattern.
  - **Contrast:** When evolution and merge run in the same cycle (the common bootstrap case), the
    current cycle's own mutation is in `contributingCycles` → `merged=true` correctly.
  - **Proposed fix:** Track whether the merge agent actually wrote any file this cycle separately from
    whether the current cycle's own mutation was merged. Options:
    - (A) Set `context.merged=true` whenever at least one successful `MergeResult` was produced
      (simplest, but loses the "my mutation was merged" semantics).
    - (B) Rename `merged` to `mutationMerged` (semantic clarity) and add a separate
      `mergeAgentRan` / `mergeOccurred` flag on `CycleContextData`.
    - (C) Add the current cycle's own ID to `contributingCycles` explicitly when the cycle evolves
      a mutation that passes into the merge agent in the same or a subsequent cycle.

- [ ] **LF-3: Unevaluated mutations silently merged (`mut=null` but mutation reaches output)**
  - **Observed:** cycle-1774387697068 ran evolution at 22:28:19 but CYCLE log shows `mut=null`
    (no `MutationAcceptanceState`). The next cycle (cycle-1774387726461) merged that mutation
    without any evaluation record: `Marked cycle ...697068 as merged`.
  - **Root cause:** `FileVariantCollector` collects all unmerged mutation snapshots regardless of
    whether an acceptance state was ever written. A mutation stored on disk but never evaluated
    can reach the output filesystem.
  - **Risk:** Rejected or pending mutations may be merged. The `mut=null` pattern specifically
    suggests evaluation was skipped entirely (no `ROLE_EVALUATION` signal in cycle-1774387697068).
  - **Proposed fix:** `FileVariantCollector.collect()` (or `MergeProcessor.process()`) should skip
    variants whose meta.json has no acceptance state, or whose `mutationState != ACCEPTED`.
    Alternatively, gate `ROLE_MERGE` eligibility on `mutationState` being set.

- [ ] **LF-4: `registerMerge` called with stale context (before `markCyclesAsMerged`)**
  - **Observed:** The TODO comment in `MergeAgent:98`:
    ```java
    mergeActionRegistrar.registerMerge(AGENT_ID, context, result);
    // TODO: here, we do not have the ultimate status, including markCyclesAsMerged
    // and withoutSignal ROLE_MERGE, think about this
    ```
  - `registerMerge` is called inside the per-file loop, before `markCyclesAsMerged()` and
    `withoutSignal(ROLE_MERGE)` run. The context snapshot recorded in the graph still has
    `merged=false` (even for cycles where it will become true) and still carries `ROLE_MERGE`.
  - `result.wasSuccessful()` in the payload is correct, but the context state is intermediate.
  - **Proposed fix:** Move `registerMerge` to after `markCyclesAsMerged().withoutSignal(...)`,
    passing the final returned context. Requires collecting results across the file loop first
    (or registering once after the loop with an aggregated summary rather than per-file).

- [ ] **LF-5: `ROLE_EVOLUTION` signal grows unboundedly across cycles**
  - **Observed:** `ROLE_EVOLUTION` strength: 1.80 → 2.80 → 3.80 → 4.80 → 5.55 → 6.55 → 7.55.
    The signal is never fully consumed; evolution runs every cycle and the residual accumulates.
  - **Impact:** In longer sessions, the ever-growing evolution priority could crowd out other
    agents (evaluation, analysis, planner re-trigger) by making evolution always highest-ranked.
    Also inflates `EVOLUTION_OCCURRED` (reaches 6.90) and `EVOLUTION_FINGERPRINT_CHANGED` (6.95).
  - **Proposed fix:** Cap `ROLE_EVOLUTION` strength at a configurable maximum (e.g. 3.0) or
    apply a stronger decay when the signal exceeds a threshold. Alternatively, fully consume it
    each time evolution runs (set strength to 0 post-execution) and rely on re-injection from
    deliberation rather than accumulation.

- [ ] **LF-6: `ROLE_ANALYSIS=0.20` signal never triggers analysis agent**
  - **Observed:** Analysis signal appears at 22:27:57 (cycle 3) at strength 0.20 and remains
    at exactly 0.20 through all 5 remaining cycles. The analysis agent never executes.
  - **Root cause unknown:** Either (a) 0.20 is below the execution eligibility threshold,
    (b) the analysis agent's `getSupportedSignals()` doesn't match `ROLE_ANALYSIS`,
    (c) other agents always rank higher so analysis never reaches execution order, or
    (d) a `canExecute` blocker prevents it.
  - **Proposed fix:** Investigate eligibility check for `AnalysisAgent`. Add a `canExecute=false`
    diagnostic log for analysis (same pattern as `MERGE: canExecute=false; blockers=...` already
    emitted for merge). Determine whether 0.20 is simply too weak or whether there is a structural
    block.

- [ ] **LF-7: Redundant re-merging of the same file across consecutive cycles**
  - **Observed:** `TaskController.java` is merged (via LLM) in 5 out of 7 cycles, each time
    combining progressively similar variants (inline validation → extracted method → helper method).
    The evolution agent keeps generating new variants of an already-merged file every cycle.
  - **Root cause:** After a file is merged into the output, evolution has no awareness of the
    current output content; it proposes changes relative to the base snapshot, leading to
    variants that overlap heavily with already-merged content.
  - **Impact:** Wasted LLM calls (multi-variant merge is expensive), and the LLM merger must
    reconcile near-identical versions each cycle rather than building incrementally.
  - **Proposed fix:** Feed the current output file content into the evolution prompt as "current
    state" so the evolution agent proposes genuinely new changes rather than re-deriving already
    merged improvements. Related to the `mergedContent` now stored in `MergeResult` and
    registered in the graph — this data is available; it just needs to reach the evolution prompt.
