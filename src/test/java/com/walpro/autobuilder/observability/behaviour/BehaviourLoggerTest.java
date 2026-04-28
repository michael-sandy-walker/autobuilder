package com.walpro.autobuilder.observability.behaviour;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T5-H: shape-locking tests for the behaviour event JSON envelope and per-event payloads.
 * Each test asserts the canonical {@code { timestamp, cycleId, event, payload }} structure
 * plus the keys the consumer (NDJSON file or SSE client) is contractually expecting.
 */
class BehaviourLoggerTest {

    private ObjectMapper mapper;
    private CapturingPublisher publisher;
    private BehaviourLogger logger;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        publisher = new CapturingPublisher();
        logger = new BehaviourLogger(mapper, publisher);
    }

    @Test
    void cycleStart_envelopeAndPayloadKeys() {
        logger.cycleStart("cycle-1", "g-root", "g-sub", "EVOLVE");
        JsonNode env = capturedAsJson();
        assertEnvelope(env, "cycle-1", "cycle_start");
        JsonNode p = env.get("payload");
        assertThat(p.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "currentGoalId", "currentSubGoalId", "deliberationMode");
        assertThat(p.get("currentGoalId").asText()).isEqualTo("g-root");
        assertThat(p.get("currentSubGoalId").asText()).isEqualTo("g-sub");
        assertThat(p.get("deliberationMode").asText()).isEqualTo("EVOLVE");
    }

    @Test
    void agentFitnessRanked_payloadShape() {
        var rankings = List.of(
                new BehaviourEvent.AgentRanking("evolution", 2.5, Map.of("signal", 2.0, "priority", 0.5)),
                new BehaviourEvent.AgentRanking("merge", 0.4, Map.of("signal", 0.0, "priority", 0.4))
        );
        logger.agentFitnessRanked("cycle-2", rankings);
        JsonNode env = capturedAsJson();
        assertEnvelope(env, "cycle-2", "agent_fitness_ranked");
        JsonNode rs = env.get("payload").get("rankings");
        assertThat(rs.isArray()).isTrue();
        assertThat(rs).hasSize(2);
        assertThat(rs.get(0).get("role").asText()).isEqualTo("evolution");
        assertThat(rs.get(0).get("score").asDouble()).isEqualTo(2.5);
        assertThat(rs.get(0).get("breakdown").get("signal").asDouble()).isEqualTo(2.0);
    }

    @Test
    void agentSelected_payloadShape() {
        logger.agentSelected("cycle-3", "evolution", 1.8);
        JsonNode env = capturedAsJson();
        assertEnvelope(env, "cycle-3", "agent_selected");
        assertThat(env.get("payload").fieldNames()).toIterable().containsExactlyInAnyOrder("role", "fitness");
        assertThat(env.get("payload").get("role").asText()).isEqualTo("evolution");
        assertThat(env.get("payload").get("fitness").asDouble()).isEqualTo(1.8);
    }

    @Test
    void agentSkipped_payloadShape() {
        logger.agentSkipped("cycle-4", "merge", "can_execute_false");
        JsonNode env = capturedAsJson();
        assertEnvelope(env, "cycle-4", "agent_skipped");
        assertThat(env.get("payload").fieldNames()).toIterable().containsExactlyInAnyOrder("role", "reason");
        assertThat(env.get("payload").get("reason").asText()).isEqualTo("can_execute_false");
    }

    @Test
    void cycleEnd_payloadShape() {
        logger.cycleEnd("cycle-5", "CONTINUE", true, null);
        JsonNode env = capturedAsJson();
        assertEnvelope(env, "cycle-5", "cycle_end");
        assertThat(env.get("payload").fieldNames()).toIterable().containsExactlyInAnyOrder(
                "resultState", "merged", "endReason");
        assertThat(env.get("payload").get("merged").asBoolean()).isTrue();
        assertThat(env.get("payload").get("resultState").asText()).isEqualTo("CONTINUE");
    }

    @Test
    void mutationProposed_payloadShape() {
        logger.mutationProposed("cycle-6", "fp-abc", List.of("a.java", "b.java"));
        JsonNode env = capturedAsJson();
        assertEnvelope(env, "cycle-6", "mutation_proposed");
        assertThat(env.get("payload").fieldNames()).toIterable().containsExactlyInAnyOrder("mutationId", "files");
        assertThat(env.get("payload").get("mutationId").asText()).isEqualTo("fp-abc");
        assertThat(env.get("payload").get("files")).hasSize(2);
    }

    @Test
    void mutationScored_payloadShape() {
        logger.mutationScored("cycle-7", "fp-1", 0.78, "ACCEPTED", List.of("[1] ✓ ok"));
        JsonNode env = capturedAsJson();
        assertEnvelope(env, "cycle-7", "mutation_scored");
        assertThat(env.get("payload").fieldNames()).toIterable().containsExactlyInAnyOrder(
                "mutationId", "llmScore", "llmState", "criteriaAssessments");
        assertThat(env.get("payload").get("llmScore").asDouble()).isEqualTo(0.78);
        assertThat(env.get("payload").get("llmState").asText()).isEqualTo("ACCEPTED");
    }

    @Test
    void heuristicVerdict_payloadShape() {
        logger.heuristicVerdict("cycle-8", "fp-1", "ProblemReductionHeuristic", 0.62, "ACCEPTED", 0.9);
        JsonNode env = capturedAsJson();
        assertEnvelope(env, "cycle-8", "heuristic_verdict");
        assertThat(env.get("payload").fieldNames()).toIterable().containsExactlyInAnyOrder(
                "mutationId", "heuristic", "score", "state", "weight");
        assertThat(env.get("payload").get("heuristic").asText()).isEqualTo("ProblemReductionHeuristic");
        assertThat(env.get("payload").get("weight").asDouble()).isEqualTo(0.9);
    }

    @Test
    void mutationNegotiated_payloadShape() {
        logger.mutationNegotiated("cycle-9", "fp-1", 0.7, "ACCEPTED", List.of("ScoreRegressionHeuristic"));
        JsonNode env = capturedAsJson();
        assertEnvelope(env, "cycle-9", "mutation_negotiated");
        assertThat(env.get("payload").fieldNames()).toIterable().containsExactlyInAnyOrder(
                "mutationId", "finalScore", "finalState", "overrides");
        assertThat(env.get("payload").get("overrides")).hasSize(1);
    }

    @Test
    void mutationMerged_payloadShape() {
        logger.mutationMerged("cycle-10", "fp-1", List.of("src/main/java/X.java"));
        JsonNode env = capturedAsJson();
        assertEnvelope(env, "cycle-10", "mutation_merged");
        assertThat(env.get("payload").fieldNames()).toIterable().containsExactlyInAnyOrder("mutationId", "files");
    }

    @Test
    void mutationRejected_payloadShape() {
        logger.mutationRejected("cycle-11", "fp-1", "score below threshold");
        JsonNode env = capturedAsJson();
        assertEnvelope(env, "cycle-11", "mutation_rejected");
        assertThat(env.get("payload").fieldNames()).toIterable().containsExactlyInAnyOrder("mutationId", "reason");
    }

    @Test
    void deliberationOutcome_payloadShape() {
        logger.deliberationOutcome("cycle-12", "EVOLVE",
                List.of("ROLE_EVOLUTION=1.0"),
                List.of("1|EvolveNeeded|evolution|run|Never"));
        JsonNode env = capturedAsJson();
        assertEnvelope(env, "cycle-12", "deliberation_outcome");
        assertThat(env.get("payload").fieldNames()).toIterable().containsExactlyInAnyOrder(
                "mode", "signalAdjustments", "agentSteps");
        assertThat(env.get("payload").get("mode").asText()).isEqualTo("EVOLVE");
    }

    @Test
    void emit_doesNotThrowWhenPublisherIsNull() {
        BehaviourLogger noPub = new BehaviourLogger(mapper, null);
        noPub.cycleStart("cycle-x", "g", null, null); // must not throw
    }

    // ---------------------------------------------------------------------------------

    private void assertEnvelope(JsonNode env, String expectedCycleId, String expectedEvent) {
        assertThat(env.get("timestamp").asText()).isNotBlank();
        assertThat(env.get("cycleId").asText()).isEqualTo(expectedCycleId);
        assertThat(env.get("event").asText()).isEqualTo(expectedEvent);
        assertThat(env.get("payload").isObject()).isTrue();
    }

    private JsonNode capturedAsJson() {
        Object captured = publisher.last.get();
        assertThat(captured).isNotNull();
        return mapper.valueToTree(captured);
    }

    /** Captures the last broadcast envelope for shape inspection. */
    private static final class CapturingPublisher extends BehaviourEventPublisher {
        final AtomicReference<Object> last = new AtomicReference<>();

        @Override
        public void publish(String eventName, Object envelope) {
            last.set(envelope);
        }
    }
}
