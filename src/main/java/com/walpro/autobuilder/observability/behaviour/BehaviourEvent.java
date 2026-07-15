package com.walpro.autobuilder.observability.behaviour;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * T5-H: sealed type hierarchy for the behaviour trace. Every event carries the common
 * envelope fields ({@code timestamp}, {@code cycleId}, {@code event}); the remaining
 * record components form the event-specific payload that {@link BehaviourLogger}
 * serialises under the {@code payload} JSON key.
 */
public sealed interface BehaviourEvent {

    Instant timestamp();
    String cycleId();
    String event();

    // ---------------------------------------------------------------------------------
    // Core loop
    // ---------------------------------------------------------------------------------

    // T5-T: descriptions carried alongside the ID handles so the trace is readable without a
    // goal-graph lookup by UUID. subGoalDescription is absent (Jackson NON_NULL) on bootstrap
    // cycles where no subgoal has been planned yet. The old deliberationMode slot was dropped —
    // it was always null at cycle_start emit time (deliberation runs later in the cycle) and the
    // mode is carried authoritatively on deliberation_outcome.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CycleStart(
            Instant timestamp,
            String cycleId,
            String currentGoalId,
            String currentSubGoalId,
            String goalDescription,
            String subGoalDescription
    ) implements BehaviourEvent {
        public String event() { return "cycle_start"; }
    }

    record AgentRanking(String role, double score, Map<String, Double> breakdown) {}

    record AgentFitnessRanked(
            Instant timestamp,
            String cycleId,
            List<AgentRanking> rankings
    ) implements BehaviourEvent {
        public String event() { return "agent_fitness_ranked"; }
    }

    record AgentSelected(
            Instant timestamp,
            String cycleId,
            String role,
            double fitness
    ) implements BehaviourEvent {
        public String event() { return "agent_selected"; }
    }

    record AgentSkipped(
            Instant timestamp,
            String cycleId,
            String role,
            String reason
    ) implements BehaviourEvent {
        public String event() { return "agent_skipped"; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CycleEnd(
            Instant timestamp,
            String cycleId,
            String resultState,
            boolean merged,
            String endReason,
            // T5-M: post-merge compile gate outcome (BuildOutcome.result().name()).
            // Null when build gate is disabled or no compile ran this cycle — Jackson omits
            // the field, preserving backward compatibility with existing log consumers.
            String buildOutcome,
            // T5-P: build wall-clock this cycle and cumulative for the session. Always emitted
            // (0 when no build ran) — the session total lets offline analysis see "this run has
            // spent N minutes building" without summing durations across build_fix_attempt events.
            long cycleBuildMs,
            long sessionBuildMs
    ) implements BehaviourEvent {
        public String event() { return "cycle_end"; }
    }

    // ---------------------------------------------------------------------------------
    // T4-BO: signal-decay observability (measure before tuning)
    // ---------------------------------------------------------------------------------

    /**
     * T4-BO: one signal type's presence/absence at fitness-ranking time this cycle (before any
     * agent executes) — the causality-preserving snapshot for signal→action correlation. Joined
     * offline with {@link AgentSelected}/{@link AgentSkipped} events sharing the same
     * {@code cycleId} to compute P(role selected | signal present) - P(role selected | signal
     * absent) per (signalType, role) — near-zero across a run flags a candidate-dead signal.
     */
    record SignalSnapshot(
            Instant timestamp,
            String cycleId,
            Map<String, Double> signals
    ) implements BehaviourEvent {
        public String event() { return "signal_snapshot"; }
    }

    /**
     * T4-BO: one signal-type lifetime episode ended this cycle — either it decayed below the
     * survival threshold on this cycle's {@code tickSignals()} call ({@code endReason =
     * "decayed"}), or it was already absent before the tick ran, meaning some agent explicitly
     * cleared it during this cycle's execution ({@code endReason = "cleared"}).
     * {@code emitCycle}/{@code endCycle} are the tracker's own monotonic cycle counter (not
     * {@code cycleId}, which is a timestamp string), so {@code endCycle - emitCycle} is
     * directly the lifetime in cycles. A signal type still alive when the JVM stops never gets
     * one of these — the offline analyser infers {@code endReason = "sessionEnd"} for those
     * from the absence of a terminal event in the {@link SignalSnapshot} timeline.
     */
    record SignalLifetimeObserved(
            Instant timestamp,
            String cycleId,
            String signalType,
            int emitCycle,
            int endCycle,
            String endReason,
            String emittedBySource
    ) implements BehaviourEvent {
        public String event() { return "signal_lifetime_observed"; }
    }

    // ---------------------------------------------------------------------------------
    // Mutation flow
    // ---------------------------------------------------------------------------------

    record MutationProposed(
            Instant timestamp,
            String cycleId,
            String mutationId,
            List<String> files
    ) implements BehaviourEvent {
        public String event() { return "mutation_proposed"; }
    }

    record MutationScored(
            Instant timestamp,
            String cycleId,
            String mutationId,
            Double llmScore,
            String llmState,
            List<String> criteriaAssessments
    ) implements BehaviourEvent {
        public String event() { return "mutation_scored"; }
    }

    record HeuristicResult(
            Instant timestamp,
            String cycleId,
            String mutationId,
            String heuristic,
            Double score,
            String state,
            double weight
    ) implements BehaviourEvent {
        public String event() { return "heuristic_result"; }
    }

    record MutationNegotiated(
            Instant timestamp,
            String cycleId,
            String mutationId,
            Double finalScore,
            String finalState,
            List<String> overrides
    ) implements BehaviourEvent {
        public String event() { return "mutation_negotiated"; }
    }

    record MutationMerged(
            Instant timestamp,
            String cycleId,
            String mutationId,
            List<String> files
    ) implements BehaviourEvent {
        public String event() { return "mutation_merged"; }
    }

    record MutationRejected(
            Instant timestamp,
            String cycleId,
            String mutationId,
            String reason
    ) implements BehaviourEvent {
        public String event() { return "mutation_rejected"; }
    }

    // T1-DW: emitted when a post-merge COMPILE_ERROR triggers filesystem rollback.
    // T1-DX: payload extended with stderrSummary + errorKind so post-hoc analysis doesn't
    // need to cross-reference the build log to know what triggered the rollback.
    record MergeRolledBack(
            Instant timestamp,
            String cycleId,
            List<String> restoredPaths,
            String stderrSummary,
            String errorKind
    ) implements BehaviourEvent {
        public String event() { return "merge_rolled_back"; }
    }

    // T2-EF: emitted when the run-level RecurringDefectTracker reports a failure shape dominating
    // the run's residual failure mix across ≥2 subgoals, forcing the targeted build-fix
    // redirection even when the per-subgoal same-failure streak has reset.
    record RecurringDefectDominant(
            Instant timestamp,
            String cycleId,
            String errorKind,
            String missingToken,
            double share,
            int subgoalSpan,
            boolean hadGreenFix
    ) implements BehaviourEvent {
        public String event() { return "recurring_defect_dominant"; }
    }

    /**
     * T4-CA: the best verified snapshot was copied to {@code meta/preserved-artifact/} at an
     * in-process halt. {@code fromCycle} names the snapshot that won selection;
     * {@code entryPointRequired} records whether the run's own history demanded an entry point.
     */
    record ArtifactPreserved(
            Instant timestamp,
            String cycleId,
            String fromCycle,
            boolean entryPointPresent,
            boolean entryPointRequired,
            String buildResult,
            int snapshotsConsidered
    ) implements BehaviourEvent {
        public String event() { return "artifact_preserved"; }
    }

    // ---------------------------------------------------------------------------------
    // Invariant guards — structural regression checks
    // ---------------------------------------------------------------------------------

    record GuardViolated(
            Instant timestamp,
            String cycleId,
            String guard,
            String detail
    ) implements BehaviourEvent {
        public String event() { return "guard_violated"; }
    }

    /**
     * T4-BA: emitted by the MergeAgent interim package-fragmentation guard when a mutation
     * would create a new source file whose top-level type name already exists under a
     * different package in {@code output/}. A simple type name must map to exactly one
     * package — a split produces two distinct types that compile cleanly but break runtime
     * dispatch ({@code instanceof}, exception → HTTP status mapping). The guard rejects the
     * fragmenting file; {@code existingPath} and {@code attemptedPath} are relative to the
     * output root.
     */
    record PackageFragmentationDetected(
            Instant timestamp,
            String cycleId,
            String typeName,
            String existingPath,
            String attemptedPath
    ) implements BehaviourEvent {
        public String event() { return "package_fragmentation_detected"; }
    }

    /**
     * T4-BL (lighter fix): emitted when a fragmenting write was consolidated to the package its
     * type already lives in instead of being rejected — the LLM's content lands at
     * {@code existingPath}; {@code attemptedPath} is where the mutation originally targeted it.
     */
    record PackageConsolidationApplied(
            Instant timestamp,
            String cycleId,
            String typeName,
            String attemptedPath,
            String existingPath
    ) implements BehaviourEvent {
        public String event() { return "package_consolidation_applied"; }
    }

    // ---------------------------------------------------------------------------------
    // Steering
    // ---------------------------------------------------------------------------------

    // T5-T: rationale added so the trace shows WHY the meta-deliberation picked a mode, not just
    // which. Absent (Jackson NON_NULL) when the deliberation produced no rationale text.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record DeliberationOutcome(
            Instant timestamp,
            String cycleId,
            String mode,
            List<String> signalAdjustments,
            String rationale
    ) implements BehaviourEvent {
        public String event() { return "deliberation_outcome"; }
    }

    // T1-ED: emitted when EvaluationAgent or CycleExecutor fails to persist mutationState to
    // meta.json. High rates indicate a structural ordering problem (evaluator runs before
    // mutation files are on disk) or a filesystem issue.
    record MutationMetaPersistFailed(
            Instant timestamp,
            String cycleId,
            String metaPath,
            String reason
    ) implements BehaviourEvent {
        public String event() { return "mutation_meta_persist_failed"; }
    }

    // T1-EB: emitted when a build-blocked deadlock halt fires (N consecutive zero-work cycles).
    record SystemPaused(
            Instant timestamp,
            String cycleId,
            String reason,
            int consecutiveZeroWorkCycles
    ) implements BehaviourEvent {
        public String event() { return "system_paused"; }
    }

    // T2-CR observability gate: emitted when T3-BR's evaluator-repeat-cap fires. The cap path is
    // the precondition for T2-CR's demote-and-narrow refinement; T2-CR itself is gated on
    // observing this event in a real run. Capturing it makes the gate falsifiable — if this
    // event never appears in production behaviour logs, T2-CR remains correctly parked; if it
    // starts firing, the gate has been met and T2-CR can be shipped.
    record EvaluatorRepeatCapFired(
            Instant timestamp,
            String cycleId,
            String subGoalId,
            int retries,
            int cap,
            String fingerprint,
            int bankedCriteria,
            int unbankedCriteria
    ) implements BehaviourEvent {
        public String event() { return "evaluator_repeat_cap_fired"; }
    }

    /**
     * T2-DO: emitted when a candidate subgoal description is blocked because it matches a
     * GIVEN_UP sibling, and a rehabilitation vote was registered against the matched goal.
     * {@code source} identifies the injecting agent (e.g. {@code "analysis"}, {@code "planner"}).
     */
    record SubgoalDedupVote(
            Instant timestamp,
            String cycleId,
            String source,
            String matchedGoalId,
            String matchedStatus,
            String candidateDescription
    ) implements BehaviourEvent {
        public String event() { return "subgoal_dedup_vote"; }
    }

    /**
     * T4-AJ Part B: emitted when {@code GoalConsolidationService} folds a goal into an
     * equivalent goal that is kept. {@code kind} is {@code active-duplicate} (two ACTIVE
     * near-duplicates) or {@code given-up-covered} (a GIVEN_UP root-closure blocker whose work
     * an equivalent goal carried out). {@code criteriaCarried} counts acceptance criteria
     * appended to the kept goal (deduped by normalized criterion identity).
     */
    record GoalConsolidation(
            Instant timestamp,
            String cycleId,
            String keptGoalId,
            String subsumedGoalId,
            double confidence,
            String kind,
            int criteriaCarried
    ) implements BehaviourEvent {
        public String event() { return "goal_consolidation"; }
    }

    /**
     * T1-EL: emitted when {@code GoalPlannerService.repairCurrentSubGoalIfStale} advances the
     * pointer because it referenced a non-ACTIVE goal (cascade aftermath, lost promotion, etc.).
     * {@code reason} is one of {@code missing|completed|given_up}; {@code newId} may be null when
     * no active leaf remains under the root.
     */
    record CurrentSubgoalRepaired(
            Instant timestamp,
            String cycleId,
            String oldId,
            String newId,
            String reason
    ) implements BehaviourEvent {
        public String event() { return "current_subgoal_repaired"; }
    }

    /**
     * Deterministic compile fix applied by CompileFixInjector after the LLM's mutation failed
     * to compile. Distinct from {@code mutation_proposed}/{@code mutation_merged} so log analysis
     * can separate LLM-authored edits from injector-authored edits.
     */
    record MutationInjected(
            Instant timestamp,
            String cycleId,
            String rule,
            String kind,
            String missingToken,
            List<String> touchedPaths,
            String reProbeResult
    ) implements BehaviourEvent {
        public String event() { return "mutation_injected"; }
    }

    /**
     * T5-R: one completed LLM call. {@code role} is the ambient agent role
     * ({@code LlmCallContext}, or {@code "unknown"} for auxiliary/startup calls). Token fields are
     * absent (Jackson NON_NULL) when the provider omitted the usage block. Lets offline analysis
     * see per-role token cost and which cycles were expensive without instrumenting each adapter.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record LlmCall(
            Instant timestamp,
            String cycleId,
            String role,
            String model,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            long durationMs
    ) implements BehaviourEvent {
        public String event() { return "llm_call"; }
    }

    /**
     * T5-N: one build execution, attempt-level. Lets offline analysis reconstruct a
     * build-fix chain (initial probe → inner-loop passes → post-merge gate) with outcomes
     * and durations without grepping {@code autobuilder.log}. {@code CycleEnd.buildOutcome}
     * (T5-M) stays the cycle-level summary; {@code mutation_injected} stays the
     * fix-provenance event on the evaluation path.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record BuildFixAttempt(
            Instant timestamp,
            String cycleId,
            // "probe" (initial pre-merge build), "inner-loop" (T4-AN-6 chained fix re-probe),
            // or "post-merge" (T4-AN-1 gate).
            String source,
            // 0 for probe/post-merge executions; 1..N for inner-loop passes.
            int attemptNumber,
            // Injector rule that produced this attempt's fix; null outside the inner loop.
            String rule,
            // Classifier kind of the diagnostic the attempt reacted to; null when the build
            // succeeded or no classification applies.
            String errorKind,
            String missingToken,
            // Paths the injector touched for this attempt; empty outside the inner loop.
            List<String> mutatedFiles,
            // BuildResult name: COMPILE_OK / COMPILE_ERROR / TIMEOUT / SKIPPED / ...
            String outcome,
            long durationMs
    ) implements BehaviourEvent {
        public String event() { return "build_fix_attempt"; }
    }

    // ---------------------------------------------------------------------------------
    // T4-BE: deferred-mutation ledger lifecycle
    // ---------------------------------------------------------------------------------

    /** Compile probe rejected the mutation; the rejection was fully explained by extractable
     * blockers so the mutation was deferred (rather than discarded) for potential re-stage. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record DeferredMutationRecorded(
            Instant timestamp,
            String cycleId,
            String deferredId,
            int blockerCount,
            int touchedFileCount,
            String intentSubgoalId,
            Double llmScore,
            Double heuristicScore
    ) implements BehaviourEvent {
        public String event() { return "deferred_mutation_recorded"; }
    }

    /** A merge cleared the last open blocker on the deferred mutation. State → READY_TO_RESTAGE. */
    record DeferredMutationReady(
            Instant timestamp,
            String cycleId,
            String deferredId,
            int clearedFingerprintCount
    ) implements BehaviourEvent {
        public String event() { return "deferred_mutation_ready"; }
    }

    /** Deferred mutation was re-staged this cycle; standard eval/merge path took over. */
    record DeferredMutationRestaged(
            Instant timestamp,
            String cycleId,
            String deferredId
    ) implements BehaviourEvent {
        public String event() { return "deferred_mutation_restaged"; }
    }

    /** Deferred mutation hit a terminal DROP state (drift, reprobe failure, intent closed). */
    record DeferredMutationDropped(
            Instant timestamp,
            String cycleId,
            String deferredId,
            String reason
    ) implements BehaviourEvent {
        public String event() { return "deferred_mutation_dropped"; }
    }

    /** TTL or LRU cap evicted the deferred mutation. */
    record DeferredMutationEvicted(
            Instant timestamp,
            String cycleId,
            String deferredId,
            String reason
    ) implements BehaviourEvent {
        public String event() { return "deferred_mutation_evicted"; }
    }

    /** Engine actively promoted a deferred mutation's intent subgoal into active scope
     * (completion-bias re-focus) so the restage could fire. */
    record DeferredRestageScopePromoted(
            Instant timestamp,
            String cycleId,
            String fromSubGoalId,
            String toSubGoalId,
            String candidateId,
            String reason
    ) implements BehaviourEvent {
        public String event() { return "deferred_restage_scope_promoted"; }
    }

    /**
     * T4-BF: per-rejection extractor outcome. Emitted once per compile-probe rejection
     * regardless of whether the mutation was deferred — converts a silent extractor code
     * path into the canonical post-hoc analysis surface. {@code result} is one of
     * {@code DEFERRED|REFUSED_AUTHOR_ERROR|REFUSED_UNKNOWN|NO_DEFERRABLE}.
     * {@code errorShapes} is deduplicated and prefix-normalised so cross-cycle
     * aggregation groups related errors. {@code firstUnknownLine} is present only on
     * {@code REFUSED_UNKNOWN}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record BlockerExtractionOutcome(
            Instant timestamp,
            String cycleId,
            String mutationId,
            String subgoalId,
            String result,
            int deferrableCount,
            int authorErrorCount,
            int unknownCount,
            List<String> errorShapes,
            String firstUnknownLine
    ) implements BehaviourEvent {
        public String event() { return "blocker_extraction_outcome"; }
    }

    /**
     * T4-BG: emitted when {@code WellKnownPackageRegistry} deterministically resolves a missing
     * package or simple name to its Maven coordinates, ahead of the LLM lookup. Lets us measure
     * registry coverage per run (how often the deterministic path fires vs the LLM fallback).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SymbolResolverRegistryHit(
            Instant timestamp,
            String cycleId,
            String symbol,
            String groupId,
            String artifactId,
            String version
    ) implements BehaviourEvent {
        public String event() { return "symbol_resolver_registry_hit"; }
    }

    /**
     * T4-BM: emitted when {@code PackageDiscoveryService} resolves a missing symbol or package
     * from project-derived observations (the resolved classpath, the project's own imports, or a
     * coordinate-index query) instead of a hardcoded registry row or an LLM lookup. {@code source}
     * carries the provenance so runs show which discovery layer is doing the work.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PackageDiscoveryHit(
            Instant timestamp,
            String cycleId,
            String symbol,
            String source,
            String fqn,
            String groupId,
            String artifactId,
            String version
    ) implements BehaviourEvent {
        public String event() { return "package_discovery_hit"; }
    }

    /**
     * T4-AN-7: emitted when {@code BuildErrorClassifierAgent} produces an LLM (adaptive)
     * classification rather than falling back to the T2-CT pattern matcher. Lets a run show
     * whether the adaptive path is actually exercised, and whether the LLM-chosen
     * {@code category} diverges from {@code defaultCategoryFor(kind)} (the only case where
     * category carries information the regex kind does not). One event per distinct stderr
     * (cache miss); identical re-probes are served from cache and do not re-emit.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record BuildClassifierAdaptiveHit(
            Instant timestamp,
            String cycleId,
            String category,
            String kind,
            String missingSymbol,
            String missingArtifact
    ) implements BehaviourEvent {
        public String event() { return "build_classifier_adaptive_hit"; }
    }

    /**
     * T2-DT: emitted by EvolutionAgent when the PREVIOUS COMPILE ERRORS prompt block fires.
     * {@code errorCount} is the total number of author-error diagnostics carried into this
     * cycle; {@code rendered} is the number actually surfaced (capped at MAX_ENTRIES);
     * {@code hintsMatched} counts how many entries matched the static hint map.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record EvolutionSteeringCompileDiagnosticsFedBack(
            Instant timestamp,
            String cycleId,
            String subgoalId,
            int errorCount,
            int rendered,
            int hintsMatched
    ) implements BehaviourEvent {
        public String event() { return "evolution_steering_compile_diagnostics_fed_back"; }
    }

    /**
     * T2-DT: emitted when the PREVIOUS COMPILE ERRORS block was suppressed despite author-error
     * diagnostics being present on context. {@code reason} is currently {@code meta-agent}
     * (STEERING_SUPPRESS signal). Subgoal-shift suppression is a no-op state, not a suppression
     * event, so it does not emit.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record EvolutionSteeringSuppressed(
            Instant timestamp,
            String cycleId,
            String subgoalId,
            String reason
    ) implements BehaviourEvent {
        public String event() { return "evolution_steering_suppressed"; }
    }

    /**
     * T2-DY: emitted when {@link com.walpro.autobuilder.core.build.inject.FixAttemptLedger} skips
     * a fix proposal because the same {@code (rule, missingToken, targetPaths)} was attempted
     * before with a COMPILE_ERROR re-probe and no target file has changed since.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record FixProposalSkippedIdempotent(
            Instant timestamp,
            String cycleId,
            String rule,
            String missingToken,
            List<String> touchedPaths,
            int priorAttempts,
            int cyclesSinceFirstAttempt
    ) implements BehaviourEvent {
        public String event() { return "fix_proposal_skipped_idempotent"; }
    }

    /**
     * T2-DY: emitted when a resolver's proposed {@code touchedPaths} include a path that does not
     * exist in the output tree at proposal-construction time. The resolver chain falls through.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record FixProposalSkippedMissingTarget(
            Instant timestamp,
            String cycleId,
            String rule,
            String missingPath
    ) implements BehaviourEvent {
        public String event() { return "fix_proposal_skipped_missing_target"; }
    }
}
