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

    // ---------------------------------------------------------------------------------
    // Convenience accessors — one method per event type so call sites stay readable
    // ---------------------------------------------------------------------------------

    public void cycleStart(String cycleId, String currentGoalId, String currentSubGoalId, String deliberationMode) {
        emit(new BehaviourEvent.CycleStart(Instant.now(), cycleId, currentGoalId, currentSubGoalId, deliberationMode));
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

    public void cycleEnd(String cycleId, String resultState, boolean merged, String endReason) {
        emit(new BehaviourEvent.CycleEnd(Instant.now(), cycleId, resultState, merged, endReason));
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

    public void mergeRolledBack(String cycleId, List<String> restoredPaths,
                                String stderrSummary, String errorKind) {
        emit(new BehaviourEvent.MergeRolledBack(Instant.now(), cycleId, restoredPaths,
                stderrSummary, errorKind));
    }

    public void metaStepFiltered(String cycleId, int step, String originalRole, String reason) {
        emit(new BehaviourEvent.MetaStepFiltered(Instant.now(), cycleId, step, originalRole, reason));
    }

    public void deliberationOutcome(String cycleId, String mode, List<String> signalAdjustments, List<String> agentSteps) {
        emit(new BehaviourEvent.DeliberationOutcome(Instant.now(), cycleId, mode, signalAdjustments, agentSteps));
    }

    public void mutationMetaPersistFailed(String cycleId, String metaPath, String reason) {
        emit(new BehaviourEvent.MutationMetaPersistFailed(Instant.now(), cycleId, metaPath, reason));
    }

    public void guardViolated(String cycleId, String guard, String detail) {
        emit(new BehaviourEvent.GuardViolated(Instant.now(), cycleId, guard, detail));
    }

    public void systemPaused(String cycleId, String reason, int consecutiveZeroWorkCycles) {
        emit(new BehaviourEvent.SystemPaused(Instant.now(), cycleId, reason, consecutiveZeroWorkCycles));
    }

    public void evaluatorRepeatCapFired(String cycleId, String subGoalId, int retries, int cap,
                                        String fingerprint, int bankedCriteria, int unbankedCriteria) {
        emit(new BehaviourEvent.EvaluatorRepeatCapFired(Instant.now(), cycleId, subGoalId, retries, cap,
                fingerprint, bankedCriteria, unbankedCriteria));
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
