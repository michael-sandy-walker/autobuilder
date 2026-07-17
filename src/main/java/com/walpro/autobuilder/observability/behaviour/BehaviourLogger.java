package com.walpro.autobuilder.observability.behaviour;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T5-H: dedicated behaviour-trace logger. Each public method takes an immutable
 * {@link BehaviourEvent} record, wraps it in the canonical envelope
 * {@code { timestamp, cycleId, event, payload }} and fans the result out to:
 * <ol>
 *   <li>The dedicated SLF4J logger {@code autobuilder.behaviour}, wired in
 *       {@code logback-spring.xml} to {@code ${ROOT_PATH}/logs/behaviour.ndjson}
 *       (one JSON object per line, no console output).</li>
 *   <li>The {@link BehaviourEventPublisher} SSE fan-out so that
 *       {@code GET /events/behaviour} subscribers see the same payload in real time.</li>
 * </ol>
 *
 * <p>Behaviour publishing MUST never throw — every emit is wrapped in a try/catch
 * and any failure is logged at WARN to the regular logger so the control loop is
 * never blocked by an instrumentation hiccup.
 */
@Slf4j
@Component
public class BehaviourLogger {

    /** SLF4J logger writing the NDJSON file appender (configured in logback-spring.xml). */
    private static final Logger BEHAVIOUR = LoggerFactory.getLogger("autobuilder.behaviour");

    private final ObjectMapper mapper;
    private final BehaviourEventPublisher publisher;

    // T5-BF: session-scoped tally of everything emitted, so a late-joining SSE subscriber can
    // seed its view (hello envelope) instead of starting every counter at zero. One home: this
    // is the chokepoint every behaviour event already passes through.
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.LongAdder>
            sessionCounts = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile String lastCycleId;
    private volatile String lastMode;
    private volatile String lastSubGoalDescription;
    private volatile Instant sessionStart;

    /**
     * SSE publisher is optional so unit tests can construct a logger without standing up
     * the publisher bean (mirrors the {@code @Autowired(required = false)} pattern used
     * by {@link com.walpro.autobuilder.core.lifecycle.CycleExecutor} for the goal publisher).
     */
    @Autowired
    public BehaviourLogger(ObjectMapper mapper, BehaviourEventPublisher publisher) {
        this.mapper = mapper;
        this.publisher = publisher;
    }

    public void emit(BehaviourEvent event) {
        if (event == null) return;
        try {
            recordSessionStats(event);
            Map<String, Object> envelope = wrap(event);
            String line = mapper.writeValueAsString(envelope);
            BEHAVIOUR.info(line);
            if (publisher != null) {
                publisher.publish(event.event(), envelope);
            }
        } catch (Exception e) {
            log.warn("BehaviourLogger emit failed for event={} cycleId={}: {}",
                    event.event(), event.cycleId(), e.getMessage());
        }
    }

    private void recordSessionStats(BehaviourEvent event) {
        if (sessionStart == null) {
            sessionStart = event.timestamp() != null ? event.timestamp() : Instant.now();
        }
        sessionCounts.computeIfAbsent(event.event(), k -> new java.util.concurrent.atomic.LongAdder())
                .increment();
        if (event.cycleId() != null) {
            lastCycleId = event.cycleId();
        }
        if (event instanceof BehaviourEvent.DeliberationOutcome outcome && outcome.mode() != null) {
            lastMode = outcome.mode();
        }
        if (event instanceof BehaviourEvent.CycleStart start
                && start.subGoalDescription() != null && !start.subGoalDescription().isBlank()) {
            lastSubGoalDescription = start.subGoalDescription();
        }
    }

    /**
     * T5-BF: session summary for the SSE hello envelope — per-event-type counts plus the last
     * observed cycle id and deliberation mode. The cycle ordinal is {@code counts[cycle_start]};
     * the client derives it rather than this map carrying a duplicate field.
     */
    public Map<String, Object> sessionSummary() {
        Map<String, Long> counts = new LinkedHashMap<>();
        sessionCounts.forEach((k, v) -> counts.put(k, v.sum()));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("counts", counts);
        summary.put("lastCycleId", lastCycleId);
        summary.put("lastMode", lastMode);
        summary.put("lastSubGoalDescription", lastSubGoalDescription);
        summary.put("sessionStartTimestamp", sessionStart != null ? sessionStart.toString() : null);
        return summary;
    }

    // ---------------------------------------------------------------------------------
    // Convenience accessors — one method per event type so call sites stay readable
    // ---------------------------------------------------------------------------------

    public void cycleStart(String cycleId, String currentGoalId, String currentSubGoalId,
                           String goalDescription, String subGoalDescription) {
        emit(new BehaviourEvent.CycleStart(Instant.now(), cycleId, currentGoalId, currentSubGoalId,
                goalDescription, subGoalDescription));
    }

    public void agentFitnessRanked(String cycleId, List<BehaviourEvent.AgentRanking> rankings) {
        emit(new BehaviourEvent.AgentFitnessRanked(Instant.now(), cycleId, rankings));
    }

    public void agentSelected(String cycleId, String role, double fitness) {
        emit(new BehaviourEvent.AgentSelected(Instant.now(), cycleId, role, fitness));
    }

    public void agentSkipped(String cycleId, String role, String reason) {
        emit(new BehaviourEvent.AgentSkipped(Instant.now(), cycleId, role, reason));
    }

    public void cycleEnd(String cycleId, String resultState, boolean merged, String endReason,
                         String buildOutcome, long cycleBuildMs, long sessionBuildMs,
                         int cycleBuildCount, long sessionBuildCount) {
        emit(new BehaviourEvent.CycleEnd(Instant.now(), cycleId, resultState, merged, endReason,
                buildOutcome, cycleBuildMs, sessionBuildMs, cycleBuildCount, sessionBuildCount));
    }

    /** T4-BO: raw signal-type presence at fitness-ranking time (before any agent executes this
     *  cycle) — pairs with agent_selected/agent_skipped for signal->action correlation. */
    public void signalSnapshot(String cycleId, Map<String, Double> signals) {
        emit(new BehaviourEvent.SignalSnapshot(Instant.now(), cycleId, signals));
    }

    /** T4-BO: one signal-type lifetime episode ended this cycle (decayed or explicitly cleared). */
    public void signalLifetimeObserved(String cycleId, String signalType, int emitCycle, int endCycle,
                                       String endReason, String emittedBySource) {
        emit(new BehaviourEvent.SignalLifetimeObserved(Instant.now(), cycleId, signalType, emitCycle,
                endCycle, endReason, emittedBySource));
    }

    public void mutationProposed(String cycleId, String mutationId, List<String> files) {
        emit(new BehaviourEvent.MutationProposed(Instant.now(), cycleId, mutationId, files));
    }

    public void mutationScored(String cycleId, String mutationId, Double llmScore, String llmState,
                               List<String> criteriaAssessments) {
        emit(new BehaviourEvent.MutationScored(Instant.now(), cycleId, mutationId, llmScore, llmState,
                criteriaAssessments));
    }

    public void heuristicResult(String cycleId, String mutationId, String heuristic, Double score,
                                 String state, double weight) {
        emit(new BehaviourEvent.HeuristicResult(Instant.now(), cycleId, mutationId, heuristic, score, state, weight));
    }

    public void mutationNegotiated(String cycleId, String mutationId, Double finalScore, String finalState,
                                   List<String> overrides) {
        emit(new BehaviourEvent.MutationNegotiated(Instant.now(), cycleId, mutationId, finalScore, finalState,
                overrides));
    }

    public void mutationMerged(String cycleId, String mutationId, List<String> files) {
        emit(new BehaviourEvent.MutationMerged(Instant.now(), cycleId, mutationId, files));
    }

    public void mutationRejected(String cycleId, String mutationId, String reason) {
        emit(new BehaviourEvent.MutationRejected(Instant.now(), cycleId, mutationId, reason));
    }

    public void mutationInjected(String cycleId, String rule, String kind, String missingToken,
                                 List<String> touchedPaths, String reProbeResult) {
        emit(new BehaviourEvent.MutationInjected(Instant.now(), cycleId, rule, kind, missingToken,
                touchedPaths, reProbeResult));
    }

    public void llmCall(String cycleId, String role, String model, Integer promptTokens,
                        Integer completionTokens, Integer totalTokens, long durationMs) {
        emit(new BehaviourEvent.LlmCall(Instant.now(), cycleId, role, model, promptTokens,
                completionTokens, totalTokens, durationMs));
    }

    public void buildFixAttempt(String cycleId, String source, int attemptNumber, String rule,
                                String errorKind, String missingToken, List<String> mutatedFiles,
                                String outcome, long durationMs) {
        emit(new BehaviourEvent.BuildFixAttempt(Instant.now(), cycleId, source, attemptNumber,
                rule, errorKind, missingToken, mutatedFiles, outcome, durationMs));
    }

    public void mergeRolledBack(String cycleId, List<String> restoredPaths,
                                String stderrSummary, String errorKind) {
        emit(new BehaviourEvent.MergeRolledBack(Instant.now(), cycleId, restoredPaths,
                stderrSummary, errorKind));
    }

    // T2-EF: a failure shape dominates the run's residual failure mix across ≥2 subgoals.
    public void recurringDefectDominant(String cycleId, String errorKind, String missingToken,
                                        double share, int subgoalSpan, boolean hadGreenFix) {
        emit(new BehaviourEvent.RecurringDefectDominant(Instant.now(), cycleId, errorKind,
                missingToken, share, subgoalSpan, hadGreenFix));
    }

    public void deliberationOutcome(String cycleId, String mode, List<String> signalAdjustments,
                                    String rationale) {
        emit(new BehaviourEvent.DeliberationOutcome(Instant.now(), cycleId, mode, signalAdjustments, rationale));
    }

    public void artifactPreserved(String cycleId, String fromCycle, boolean entryPointPresent,
                                  boolean entryPointRequired, String buildResult, int snapshotsConsidered) {
        emit(new BehaviourEvent.ArtifactPreserved(Instant.now(), cycleId, fromCycle, entryPointPresent,
                entryPointRequired, buildResult, snapshotsConsidered));
    }

    public void mutationMetaPersistFailed(String cycleId, String metaPath, String reason) {
        emit(new BehaviourEvent.MutationMetaPersistFailed(Instant.now(), cycleId, metaPath, reason));
    }

    public void guardViolated(String cycleId, String guard, String detail) {
        emit(new BehaviourEvent.GuardViolated(Instant.now(), cycleId, guard, detail));
    }

    public void packageFragmentationDetected(String cycleId, String typeName,
                                             String existingPath, String attemptedPath) {
        emit(new BehaviourEvent.PackageFragmentationDetected(Instant.now(), cycleId, typeName,
                existingPath, attemptedPath));
    }

    public void packageConsolidationApplied(String cycleId, String typeName,
                                            String attemptedPath, String existingPath) {
        emit(new BehaviourEvent.PackageConsolidationApplied(Instant.now(), cycleId, typeName,
                attemptedPath, existingPath));
    }

    public void systemPaused(String cycleId, String reason, int consecutiveZeroWorkCycles) {
        emit(new BehaviourEvent.SystemPaused(Instant.now(), cycleId, reason, consecutiveZeroWorkCycles));
    }

    public void systemResumed(String cycleId, String reason) {
        emit(new BehaviourEvent.SystemResumed(Instant.now(), cycleId, reason));
    }

    /** T4-CD slice 1: boot resumed a preserved prior lineage (cycleId null — fires before cycle 1). */
    public void runResumed(String fromCycle, String verifiedBuildResult, boolean rootReactivated,
                           int regroundedGoals, boolean testamentLoaded) {
        emit(new BehaviourEvent.RunResumed(Instant.now(), null, fromCycle, verifiedBuildResult,
                rootReactivated, regroundedGoals, testamentLoaded));
    }

    public void currentSubgoalRepaired(String cycleId, String oldId, String newId, String reason) {
        emit(new BehaviourEvent.CurrentSubgoalRepaired(Instant.now(), cycleId, oldId, newId, reason));
    }

    public void subgoalDedupVote(String cycleId, String source, String matchedGoalId,
                                 String matchedStatus, String candidateDescription) {
        emit(new BehaviourEvent.SubgoalDedupVote(Instant.now(), cycleId, source,
                matchedGoalId, matchedStatus, candidateDescription));
    }

    /** T4-AJ Part B: consolidation pass subsumed a goal in favour of an equivalent kept goal. */
    public void goalConsolidation(String cycleId, String keptGoalId, String subsumedGoalId,
                                  double confidence, String kind, int criteriaCarried) {
        emit(new BehaviourEvent.GoalConsolidation(Instant.now(), cycleId, keptGoalId,
                subsumedGoalId, confidence, kind, criteriaCarried));
    }

    public void evaluatorRepeatCapFired(String cycleId, String subGoalId, int retries, int cap,
                                        String fingerprint, int bankedCriteria, int unbankedCriteria) {
        emit(new BehaviourEvent.EvaluatorRepeatCapFired(Instant.now(), cycleId, subGoalId, retries, cap,
                fingerprint, bankedCriteria, unbankedCriteria));
    }

    // T4-BE: deferred-mutation ledger lifecycle

    public void deferredMutationRecorded(String cycleId, String deferredId, int blockerCount,
                                         int touchedFileCount, String intentSubgoalId,
                                         Double llmScore, Double heuristicScore) {
        emit(new BehaviourEvent.DeferredMutationRecorded(Instant.now(), cycleId, deferredId,
                blockerCount, touchedFileCount, intentSubgoalId, llmScore, heuristicScore));
    }

    public void deferredMutationReady(String cycleId, String deferredId, int clearedFingerprintCount) {
        emit(new BehaviourEvent.DeferredMutationReady(Instant.now(), cycleId, deferredId, clearedFingerprintCount));
    }

    public void deferredMutationRestaged(String cycleId, String deferredId) {
        emit(new BehaviourEvent.DeferredMutationRestaged(Instant.now(), cycleId, deferredId));
    }

    public void deferredMutationDropped(String cycleId, String deferredId, String reason) {
        emit(new BehaviourEvent.DeferredMutationDropped(Instant.now(), cycleId, deferredId, reason));
    }

    public void deferredMutationEvicted(String cycleId, String deferredId, String reason) {
        emit(new BehaviourEvent.DeferredMutationEvicted(Instant.now(), cycleId, deferredId, reason));
    }

    public void deferredRestageScopePromoted(String cycleId, String fromSubGoalId, String toSubGoalId,
                                             String candidateId, String reason) {
        emit(new BehaviourEvent.DeferredRestageScopePromoted(Instant.now(), cycleId, fromSubGoalId,
                toSubGoalId, candidateId, reason));
    }

    public void blockerExtractionOutcome(String cycleId, String mutationId, String subgoalId,
                                         String result, int deferrableCount, int authorErrorCount,
                                         int unknownCount, List<String> errorShapes,
                                         String firstUnknownLine) {
        emit(new BehaviourEvent.BlockerExtractionOutcome(Instant.now(), cycleId, mutationId, subgoalId,
                result, deferrableCount, authorErrorCount, unknownCount, errorShapes, firstUnknownLine));
    }

    /** T4-BG: registry-hit event. {@code cycleId} is null when the hit happens outside a cycle
     *  context (e.g. compile-fix injection) — the envelope tolerates null cycle id. */
    public void symbolResolverRegistryHit(String symbol, String groupId, String artifactId, String version) {
        emit(new BehaviourEvent.SymbolResolverRegistryHit(Instant.now(), null, symbol, groupId, artifactId, version));
    }

    /** T4-BM: package discovery resolved a symbol from a project-derived observation; {@code source}
     *  is the provenance layer (classpath / project-imports / coordinate-index). */
    public void packageDiscoveryHit(String symbol, String source, String fqn,
                                    String groupId, String artifactId, String version) {
        emit(new BehaviourEvent.PackageDiscoveryHit(Instant.now(), null, symbol, source, fqn, groupId, artifactId, version));
    }

    /** T4-AN-7: the adaptive (LLM) build-error classifier produced this classification.
     *  {@code cycleId} is null — classification happens outside a cycle context (compile probe). */
    public void buildClassifierAdaptiveHit(String category, String kind, String missingSymbol, String missingArtifact) {
        emit(new BehaviourEvent.BuildClassifierAdaptiveHit(Instant.now(), null, category, kind, missingSymbol, missingArtifact));
    }

    /** T2-DT: PREVIOUS COMPILE ERRORS block fired in the evolution prompt. */
    public void evolutionSteeringCompileDiagnosticsFedBack(String cycleId, String subgoalId,
                                                           int errorCount, int rendered, int hintsMatched) {
        emit(new BehaviourEvent.EvolutionSteeringCompileDiagnosticsFedBack(
                Instant.now(), cycleId, subgoalId, errorCount, rendered, hintsMatched));
    }

    /** T2-DT: meta-agent suppressed the steering block despite author-errors being present. */
    public void evolutionSteeringSuppressed(String cycleId, String subgoalId, String reason) {
        emit(new BehaviourEvent.EvolutionSteeringSuppressed(Instant.now(), cycleId, subgoalId, reason));
    }

    /** T2-DY: FixAttemptLedger blacklisted the proposal — same (rule, symbol, paths) failed earlier. */
    public void fixProposalSkippedIdempotent(String cycleId, String rule, String missingToken,
                                             List<String> touchedPaths, int priorAttempts,
                                             int cyclesSinceFirstAttempt) {
        emit(new BehaviourEvent.FixProposalSkippedIdempotent(Instant.now(), cycleId, rule, missingToken,
                touchedPaths, priorAttempts, cyclesSinceFirstAttempt));
    }

    /** T2-DY: resolver's touched path does not exist on disk. */
    public void fixProposalSkippedMissingTarget(String cycleId, String rule, String missingPath) {
        emit(new BehaviourEvent.FixProposalSkippedMissingTarget(Instant.now(), cycleId, rule, missingPath));
    }

    // T5-I: goal-tree lifecycle + pattern-detector events (v2)

    /** T5-I: current-subgoal pointer advanced past a completed leaf (standard promotion path). */
    public void subgoalPromoted(String cycleId, String fromId, String toId) {
        emit(new BehaviourEvent.SubgoalPromoted(Instant.now(), cycleId, fromId, toId));
    }

    /** T5-I: a goal's status changed since the last persist (shadow-diff at the persist chokepoint). */
    public void goalStatusChanged(String cycleId, String goalId, String from, String to, String reason) {
        emit(new BehaviourEvent.GoalStatusChanged(Instant.now(), cycleId, goalId, from, to, reason));
    }

    /** T5-I: one planner wave finished applying its proposals. */
    public void planningRun(String cycleId, String goalId, String trigger, int injected, int deduped) {
        emit(new BehaviourEvent.PlanningRun(Instant.now(), cycleId, goalId, trigger, injected, deduped));
    }

    /** T5-I: a pattern detector fired (emitted on firing only, never on quiet evaluation). */
    public void patternDetected(String cycleId, String detector, String evidence, String signalEmitted) {
        emit(new BehaviourEvent.PatternDetected(Instant.now(), cycleId, detector, evidence, signalEmitted));
    }

    // ---------------------------------------------------------------------------------
    // Envelope rendering: { timestamp, cycleId, event, payload }
    // ---------------------------------------------------------------------------------

    private Map<String, Object> wrap(BehaviourEvent event) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        // Render with the system zone offset so timestamps line up with the
        // local-time prefix written by the regular logback file appender.
        envelope.put("timestamp", OffsetDateTime.ofInstant(event.timestamp(), ZoneId.systemDefault()).toString());
        envelope.put("cycleId", event.cycleId());
        envelope.put("event", event.event());
        ObjectNode tree = mapper.valueToTree(event);
        // Strip envelope-level fields so they don't appear inside the payload.
        tree.remove("timestamp");
        tree.remove("cycleId");
        tree.remove("event");
        envelope.put("payload", mapper.convertValue(tree, Map.class));
        return envelope;
    }
}
