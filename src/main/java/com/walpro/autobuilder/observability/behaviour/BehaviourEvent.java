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
            long sessionBuildMs,
            // T5-BF: how many build invocations produced that wall-clock (cycle + session) —
            // wall-clock alone cannot distinguish one slow build from many fast ones.
            int cycleBuildCount,
            long sessionBuildCount
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

    /**
     * T1-GA: {@code probeResult} / {@code filesOnDisk} are the two inputs to the relay exclusion in
     * {@code EvaluationAgent} — a rejection whose authored reasons are NOT relayed to the next
     * evolution prompt is excluded on exactly these, and until 2026-09-13 neither was recorded, so
     * a run could not say why a diagnosis failed to travel. {@code relayedReasons} is the decision
     * itself: how many authored reasons this rejection actually handed forward.
     */
    record MutationRejected(
            Instant timestamp,
            String cycleId,
            String mutationId,
            String reason,
            String probeResult,
            Boolean filesOnDisk,
            Integer relayedReasons
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
     * T2-ES: one heuristic reported the same structural {@code subject} at CRITICAL confidence for
     * {@code streak} consecutive cycles, and the loop stopped re-asking. Carries the subject so a
     * repeat is countable across cycles in which the rationale text changed (line numbers move when
     * the model rewrites the file), and the paths so a later reading can tell an escalation that
     * offered real alternatives from one that offered none.
     */
    /** T2-ES: the #NoDiff-vs-criteria-unmet disagreement escalated instead of re-asked. */
    record NoDiffDisagreementEscalated(
            Instant timestamp,
            String cycleId,
            String subGoalId,
            int streak,
            List<String> unmetCriteria,
            List<String> structuralDifferences,
            String injectedGoalId,
            /** {@code injected}, or {@code skipped: <why>} — crossing the threshold is not the act. */
            String outcome
    ) implements BehaviourEvent {
        public String event() { return "nodiff_disagreement_escalated"; }
    }

    record StructuralDefectEscalated(
            Instant timestamp,
            String cycleId,
            String heuristic,
            String subject,
            int streak,
            List<String> resolutionPaths,
            /** The work item the escalation injected, or null when it injected none. */
            String injectedGoalId,
            /** {@code injected}, or {@code skipped: <why>} — the threshold crossing alone is not an escalation. */
            String outcome
    ) implements BehaviourEvent {
        public String event() { return "structural_defect_escalated"; }
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

    // T1-FL: emitted when the loop leaves a breaker-caused pause on its own — the breaker's
    // timed cooldown elapsed, so the pause episode is over and cycling continues.
    record SystemResumed(
            Instant timestamp,
            String cycleId,
            String reason
    ) implements BehaviourEvent {
        public String event() { return "system_resumed"; }
    }

    /**
     * T4-CD slice 1: emitted once at boot when the run resumed a preserved prior lineage instead
     * of starting fresh — {@code output/} restored from {@code meta/preserved-artifact/}, goal
     * state kept, banked criteria reads dropped (loaded state is hypothesis, not fact).
     * {@code fromCycle} names the parent run's preserved snapshot; {@code verifiedBuildResult}
     * is the restore-time compile probe's own result on the restored tree.
     * {@code endReportLoaded} (T4-CD slice 2): the parent's end report was found, ownership-checked
     * against the loaded root goal, and will be consulted in the first prompts.
     */
    /**
     * T4-DP slice 3: how this run began. Emitted once, after the start actions have settled, so a
     * trace answers "what state did this run start from?" without anyone having to reconstruct it
     * from which flags happened to be on the command line.
     */
    record RunStarted(
            Instant timestamp,
            String cycleId,
            String mode,
            String situation,
            String targetName,
            boolean treeCleared
    ) implements BehaviourEvent {
        public String event() { return "run_started"; }
    }

    record RunResumed(
            Instant timestamp,
            String cycleId,
            String fromCycle,
            String verifiedBuildResult,
            boolean rootReactivated,
            int regroundedGoals,
            boolean endReportLoaded
    ) implements BehaviourEvent {
        public String event() { return "run_resumed"; }
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

    /**
     * T5-BO: an agent's answer was thrown away because it did not match its protocol, even after
     * the one format retry. Emitted so the operator and the watches can see it: the ninth adopted
     * run discarded fifteen answers in a row and nothing outside the application log knew.
     */
    record LlmResponseDiscarded(
            Instant timestamp,
            String cycleId,
            String role,
            String reason,
            String firstLine,
            int consecutive
    ) implements BehaviourEvent {
        public String event() { return "llm_response_discarded"; }
    }

    /**
     * T4-CQ: the same failure has recurred across cycles -- noted at its third occurrence and at
     * every doubling after, so a grind that goes on forever keeps being reported.
     */
    record GrindDetected(
            Instant timestamp,
            String cycleId,
            String signature,
            int occurrences,
            String firstCycleId
    ) implements BehaviourEvent {
        public String event() { return "grind_detected"; }
    }

    /**
     * T4-DY slice B: the diagnostic layer asked a question about a repeated failure. {@code answered}
     * false means the authority had no answer and the next question follows.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record StuckQuestionAsked(
            Instant timestamp,
            String cycleId,
            String source,
            String signature,
            int rung,
            String question,
            String authority,
            boolean answered,
            String answer,
            String deliveredToSubGoal
    ) implements BehaviourEvent {
        public String event() { return "stuck_question_asked"; }
    }

    /** T4-DY slice B: a signature came back after its answer was delivered; the next question is due. */
    record StuckRecurred(
            Instant timestamp,
            String cycleId,
            String source,
            String signature,
            int recurrencesAfterDelivery,
            int nextRung
    ) implements BehaviourEvent {
        public String event() { return "stuck_recurred"; }
    }

    /** T4-DY slice B: a build succeeded after an answer was delivered and the signature did not come back. */
    record StuckEnded(
            Instant timestamp,
            String cycleId,
            String source,
            String signature,
            String deliveredCycleId,
            int recurrencesAfterDelivery,
            int questionsAsked
    ) implements BehaviourEvent {
        public String event() { return "stuck_ended"; }
    }

    /**
     * T4-DY slice B: the operator's hand-off. No authority in the loop answered this stuck observation,
     * or the answer names a requirement only the operator may bind: what it is, how often, what was asked.
     */
    record DiagnosisRequest(
            Instant timestamp,
            String cycleId,
            String source,
            String signature,
            int occurrences,
            String firstCycleId,
            String why,
            java.util.List<String> asked,
            java.util.List<String> detail
    ) implements BehaviourEvent {
        public String event() { return "diagnosis_request"; }
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

    // ---------------------------------------------------------------------------------
    // T5-I: goal-tree lifecycle + pattern-detector events (v2 — cross-cycle dynamics)
    // ---------------------------------------------------------------------------------

    /**
     * T5-I: the current-subgoal pointer advanced past a completed leaf via the standard
     * promotion path. {@code toId} is absent when no active leaf remains (the tree is done or
     * awaiting a re-plan). Pointer moves with other semantics keep their own events
     * ({@link CurrentSubgoalRepaired} for staleness repair; subsumption advances ride
     * {@link GoalStatusChanged}).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SubgoalPromoted(
            Instant timestamp,
            String cycleId,
            String fromId,
            String toId
    ) implements BehaviourEvent {
        public String event() { return "subgoal_promoted"; }
    }

    /**
     * T5-I: a goal's status changed since the last persist. Observed by diffing a shadow map at
     * the {@code GoalPlannerService.persist()} durability chokepoint, so every mutation path
     * (terminal transitions, cascades, T2-DR promotion, reactivation) is covered by ONE home.
     * {@code reason} is the goal's resolution text at observation time (absent when none set).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record GoalStatusChanged(
            Instant timestamp,
            String cycleId,
            String goalId,
            String from,
            String to,
            String reason
    ) implements BehaviourEvent {
        public String event() { return "goal_status_changed"; }
    }

    /**
     * T4-CU: a closure gate refused to close a goal.
     *
     * <p>{@code gate} is the refusing gate's own TICKET ID — "T4-BV", "T4-BP-3", "T4-CU" — which
     * is the identifier this codebase already uses for these gates in every one of their log lines
     * and on the roadmap. Deliberately NOT a {@code BlockerTypes} constant: those name what kind
     * of work item a gate RAISES and several gates share one, so stamping the event with them
     * would make it unable to tell the two criteria gates apart, which is most of what the event
     * exists to answer.
     *
     * <p>Eleven gates can block closure and each reported it with a {@code log.warn} alone, so
     * "which gate refused this closure, and on what" could only be answered by a regex over an
     * archived log — which is why the refusal rate went 25 runs unmeasured.
     *
     * <p><b>{@code gate} is for operators and scripts, and must not reach a prompt untranslated.</b>
     * A ticket id is the right join key for a reader who also has the roadmap, and noise to a model
     * that does not: "T4-BV" tells it nothing a boot failure's own prose would not say better.
     * The test is whether a prompt DEFINES the token -- three signal names are listed in the
     * interpretation contract and parsed back out, which makes those an interface; nothing defines
     * a gate id, so it stays on this side of the boundary.
     * Today nothing relays it — this sink is the NDJSON log and the dashboard's SSE stream, agents
     * only emit, and no contract reads a behaviour event. But relaying a gate's diagnosis INTO a
     * prompt is a move this codebase has already made once for evaluator rejection reasons (T4-BX),
     * so if refusal information ever takes that route, send {@code reason} — which describes the
     * artifact — and leave this field behind.
     *
     * <p>{@code criteria} carries the criterion texts the refusal was attributed to. Empty for the
     * nine gates that judge no criteria, and for a criteria refutation nothing could attribute
     * (see {@code ClaimRefutation}).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ClosureRefused(
            Instant timestamp,
            String cycleId,
            String gate,
            String goalId,
            String reason,
            java.util.List<String> criteria
    ) implements BehaviourEvent {
        public String event() { return "closure_refused"; }
    }

    /**
     * T2-FL: one sweep of the observed-leaf promotion — the whole sweep, not one leaf.
     *
     * <p>It exists because the first shipped version reported its refusals with {@code log.debug}
     * alone, and the runs log at INFO: a sweep that declined on every leaf and a sweep that never
     * had a candidate produced BYTE-IDENTICAL silence. Run 2026-09-23_17-47-43 closed its root
     * with zero promotions and there was no way to tell, from the run's own output, which of the
     * two had happened. A per-leaf event would have the same hole — it emits nothing when there
     * are no leaves, which is exactly the case that needs saying out loud — so the sweep is the
     * unit and {@code candidates} is the field that separates the two.
     *
     * <p>{@code candidates} counts ACTIVE leaves carrying at least one stated criterion, i.e. the
     * leaves this sweep could in principle have promoted. {@code candidates == 0} is NO
     * OPPORTUNITY; {@code candidates > 0 && promoted == 0} is an opportunity that did not fire and
     * is the shape worth chasing; {@code guardRefused} counts leaves this sweep judged satisfied
     * where an existing guard then refused the promotion, which is a DIFFERENT finding from a
     * refusal of its own and must not be pooled with {@code declined}.
     *
     * <p>{@code reasons} carries one {@code goalId — reason} line per declined leaf, capped, so a
     * reader learns what the sweep objected to without an archived log. Reasons describe the
     * artifact and the criteria, never a ticket id.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ObservedLeafPromotion(
            Instant timestamp,
            String cycleId,
            int candidates,
            int promoted,
            int declined,
            int guardRefused,
            java.util.List<String> reasons
    ) implements BehaviourEvent {
        public String event() { return "observed_leaf_promotion"; }
    }

    /**
     * T5-I: one planner wave finished applying its proposals. {@code deduped} counts proposals
     * blocked before injection (similarity dedup, given-up-retry limit, missing criteria).
     * {@code trigger} distinguishes the in-cycle planner pass ({@code cycle-planning}) from the
     * promotion-time re-plan callback ({@code promotion-replan}).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PlanningRun(
            Instant timestamp,
            String cycleId,
            String goalId,
            String trigger,
            int injected,
            int deduped
    ) implements BehaviourEvent {
        public String event() { return "planning_run"; }
    }

    /**
     * T5-I: a pattern detector fired (emitted only on firing, never on quiet evaluation).
     * {@code detector} is the class's simple name or rule id, {@code evidence} a concise
     * human-readable summary of what tripped it, {@code signalEmitted} the request/result it
     * produced. Answers "is the meta-system stuck?" without re-deriving detector state from
     * prose logs.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PatternDetected(
            Instant timestamp,
            String cycleId,
            String detector,
            String evidence,
            String signalEmitted
    ) implements BehaviourEvent {
        public String event() { return "pattern_detected"; }
    }

    // ---------------------------------------------------------------------------------
    // T4-DQ: checkpoint, rewind, replay
    // ---------------------------------------------------------------------------------

    /**
     * T4-DQ: a checkpoint was taken at the end of a cycle. {@code restorable} is false when the
     * cycle wrote no tree snapshot, so the point exists but cannot be returned to;
     * {@code captureFailures} is non-zero when a registered holder refused capture, which makes
     * the checkpoint knowingly incomplete rather than quietly so.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CheckpointWritten(
            Instant timestamp,
            String cycleId,
            int holders,
            long goalTransitionSeq,
            boolean restorable,
            int captureFailures
    ) implements BehaviourEvent {
        public String event() { return "checkpoint_written"; }
    }

    /**
     * T4-DQ: the loop returned to an earlier state. {@code trigger} names what asked for it
     * (a policy id, or "operator"), {@code goalTransitionsUndone} is how much goal history the
     * fold rolled back, and {@code cacheMode} records whether the resolution cache came back with
     * the checkpoint — a difference in outcome between the two modes is what tells us whether the
     * cache is decision-bearing at all.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RewindPerformed(
            Instant timestamp,
            String cycleId,
            String toCycleId,
            String trigger,
            String cacheMode,
            int holdersRestored,
            int holdersReset,
            int goalsRestored,
            int goalTransitionsUndone,
            boolean treeRestored,
            /** Where the carried cycle context came from: checkpoint, archive, or none. */
            String carriedContext,
            List<String> failures
    ) implements BehaviourEvent {
        public String event() { return "rewind_performed"; }
    }

    /**
     * T4-DQ slice 2: during a replay the loop produced a prompt the recording does not contain,
     * so state at this point differs from the recorded run. This is the falsifiable half of the
     * round-trip proof — uncaptured state that MATTERS shows up here and nowhere else.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ReplayDivergence(
            Instant timestamp,
            String cycleId,
            String role,
            String promptDigest,
            String resolution
    ) implements BehaviourEvent {
        public String event() { return "replay_divergence"; }
    }

    /**
     * T4-DQ slice 4: the loop opened a new path forward from an earlier checkpoint. The branch it
     * was on is sealed in the same moment (see {@link BranchSealed}).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record BranchForked(
            Instant timestamp,
            String cycleId,
            String branchId,
            String forkedFromCycleId,
            String label
    ) implements BehaviourEvent {
        public String event() { return "branch_forked"; }
    }

    /**
     * T4-DQ slice 4: a path the loop has finished with, and what it achieved.
     * {@code subgoalsCompletedWithMerge} is the convergence brake's root-progress measure — the
     * one a planner cannot inflate by re-decomposing — and is the primary ranking key.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record BranchSealed(
            Instant timestamp,
            String cycleId,
            String branchId,
            int cycles,
            int merges,
            int subgoalsCompletedWithMerge,
            String tipCycleId
    ) implements BehaviourEvent {
        public String event() { return "branch_sealed"; }
    }

    /**
     * T4-DQ slice 4: the run returned to the tip of the branch that got furthest, so the state it
     * carries forward — and that a T4-CD resume continues from — is its best explored one rather
     * than the last one tried.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record BranchAdopted(
            Instant timestamp,
            String cycleId,
            String adoptedBranchId,
            String leftBranchId,
            String tipCycleId,
            int subgoalsCompletedWithMerge,
            int merges
    ) implements BehaviourEvent {
        public String event() { return "branch_adopted"; }
    }
}
