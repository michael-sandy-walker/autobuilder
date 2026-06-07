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

    record CycleStart(
            Instant timestamp,
            String cycleId,
            String currentGoalId,
            String currentSubGoalId,
            String deliberationMode
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
            String buildOutcome
    ) implements BehaviourEvent {
        public String event() { return "cycle_end"; }
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

    // ---------------------------------------------------------------------------------
    // Steering
    // ---------------------------------------------------------------------------------

    // T2-DF: emitted when a meta plan step is dropped because its targetRole is not eligible
    // this cycle. High filter rates mean the eligibility-block instruction needs strengthening.
    record MetaStepFiltered(
            Instant timestamp,
            String cycleId,
            int step,
            String originalRole,
            String reason
    ) implements BehaviourEvent {
        public String event() { return "meta_step_filtered"; }
    }

    record DeliberationOutcome(
            Instant timestamp,
            String cycleId,
            String mode,
            List<String> signalAdjustments,
            List<String> agentSteps
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
     * T1-EX: emitted when {@code BuildFixClosureGate} closes a phantom {@code [BUILD_FIX]} subgoal
     * whose referenced diagnostic is no longer present (or whose compile state is COMPILE_OK).
     * {@code trigger} is one of {@code compile_ok} | {@code diagnostic_disappeared}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record BuildFixSubgoalClosed(
            Instant timestamp,
            String cycleId,
            String subgoalId,
            String symbol,
            String file,
            String trigger
    ) implements BehaviourEvent {
        public String event() { return "build_fix_subgoal_closed"; }
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
