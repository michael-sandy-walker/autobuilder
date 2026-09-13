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
        logger.cycleStart("cycle-1", "g-root", "g-sub", "Build a REST API", "Implement Book model");
        JsonNode env = capturedAsJson();
        assertEnvelope(env, "cycle-1", "cycle_start");
        JsonNode p = env.get("payload");
        // T5-T: deliberationMode dropped; goal/subgoal descriptions added.
        assertThat(p.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "currentGoalId", "currentSubGoalId", "goalDescription", "subGoalDescription");
        assertThat(p.get("currentGoalId").asText()).isEqualTo("g-root");
        assertThat(p.get("currentSubGoalId").asText()).isEqualTo("g-sub");
        assertThat(p.get("goalDescription").asText()).isEqualTo("Build a REST API");
        assertThat(p.get("subGoalDescription").asText()).isEqualTo("Implement Book model");
    }

    @Test
    void cycleStart_omitsSubGoalDescriptionWhenNull() {
        // T5-T: bootstrap cycle — no subgoal planned yet; NON_NULL omits the field rather than null.
        logger.cycleStart("cycle-1", "g-root", null, "Build a REST API", null);
        JsonNode p = capturedAsJson().get("payload");
        assertThat(p.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "currentGoalId", "goalDescription");
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
        logger.cycleEnd("cycle-5", "CONTINUE", true, "normal_end", null, 0L, 0L, 0, 0L);
        JsonNode env = capturedAsJson();
        assertEnvelope(env, "cycle-5", "cycle_end");
        // T5-P: cycleBuildMs/sessionBuildMs always present (primitive long).
        // T5-BF: cycleBuildCount/sessionBuildCount likewise.
        assertThat(env.get("payload").fieldNames()).toIterable().containsExactlyInAnyOrder(
                "resultState", "merged", "endReason", "cycleBuildMs", "sessionBuildMs",
                "cycleBuildCount", "sessionBuildCount");
        assertThat(env.get("payload").get("merged").asBoolean()).isTrue();
        assertThat(env.get("payload").get("resultState").asText()).isEqualTo("CONTINUE");
    }

    @Test
    void cycleEnd_emitsBuildOutcomeWhenPresent() {
        logger.cycleEnd("cycle-bo", "CONTINUE", false, null, "COMPILE_ERROR", 0L, 0L, 0, 0L);
        JsonNode env = capturedAsJson();
        assertEnvelope(env, "cycle-bo", "cycle_end");
        assertThat(env.get("payload").get("buildOutcome").asText()).isEqualTo("COMPILE_ERROR");
    }

    @Test
    void cycleEnd_omitsBuildOutcomeWhenNull() {
        logger.cycleEnd("cycle-no-bo", "CONTINUE", false, null, null, 0L, 0L, 0, 0L);
        JsonNode env = capturedAsJson();
        assertThat(env.get("payload").fieldNames()).toIterable()
                .doesNotContain("buildOutcome");
    }

    @Test
    void cycleEnd_emitsBuildCost() {
        // T5-P: build wall-clock this cycle + cumulative session total.
        // T5-BF: plus how many invocations produced it.
        logger.cycleEnd("cycle-cost", "CONTINUE", true, null, "COMPILE_OK", 2600L, 41000L, 2, 9L);
        JsonNode p = capturedAsJson().get("payload");
        assertThat(p.get("cycleBuildMs").asLong()).isEqualTo(2600L);
        assertThat(p.get("sessionBuildMs").asLong()).isEqualTo(41000L);
        assertThat(p.get("cycleBuildCount").asInt()).isEqualTo(2);
        assertThat(p.get("sessionBuildCount").asLong()).isEqualTo(9L);
    }

    @Test
    void sessionSummary_tallyCountsAndLastSeen() {
        // T5-BF: the hello-envelope seed — per-event counts + last cycle id + last mode.
        logger.cycleStart("cycle-1", "g", "sg-1", null, "Implement Books resource");
        logger.deliberationOutcome("cycle-1", "UNDIRECTED", List.of(), null);
        logger.mutationMerged("cycle-1", "fp-1", List.of("A.java"));
        logger.cycleStart("cycle-2", "g", null, null, null); // bootstrap-shaped: no subgoal yet
        logger.deliberationOutcome("cycle-2", "REPLAN", List.of(), "stalled");

        Map<String, Object> summary = logger.sessionSummary();
        @SuppressWarnings("unchecked")
        Map<String, Long> counts = (Map<String, Long>) summary.get("counts");
        assertThat(counts.get("cycle_start")).isEqualTo(2L);
        assertThat(counts.get("mutation_merged")).isEqualTo(1L);
        assertThat(counts.get("deliberation_outcome")).isEqualTo(2L);
        assertThat(summary.get("lastCycleId")).isEqualTo("cycle-2");
        assertThat(summary.get("lastMode")).isEqualTo("REPLAN");
        // a null/blank description does not erase the last known subgoal
        assertThat(summary.get("lastSubGoalDescription")).isEqualTo("Implement Books resource");
        // total-runtime seed: first emit's timestamp
        assertThat(summary.get("sessionStartTimestamp")).isNotNull();
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
    void heuristicResult_payloadShape() {
        logger.heuristicResult("cycle-8", "fp-1", "ProblemReductionHeuristic", 0.62, "ACCEPTED", 0.9);
        JsonNode env = capturedAsJson();
        assertEnvelope(env, "cycle-8", "heuristic_result");
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
        // T1-GA: the relay-exclusion inputs are part of the shape. The three-argument overload is
        // for callers with no probe in hand, so they arrive null rather than absent — "this
        // rejection ran no probe" is a fact worth recording, not a gap.
        assertThat(env.get("payload").fieldNames()).toIterable().containsExactlyInAnyOrder(
                "mutationId", "reason", "probeResult", "filesOnDisk", "relayedReasons");
        assertThat(env.get("payload").get("probeResult").isNull()).isTrue();
        assertThat(env.get("payload").get("relayedReasons").isNull()).isTrue();
    }

    /**
     * T1-GA: a rejection that ran a probe records what the probe said and how many authored
     * reasons it handed forward — the two facts run 2026-09-13_09-44-03 could not supply when
     * seven rejections relayed nothing and the cause had to be guessed at by elimination.
     */
    @Test
    void mutationRejected_carriesTheRelayDecision() {
        logger.mutationRejected("cycle-11", "fp-1", "parallel store introduced",
                "COMPILE_OK", true, 2);
        JsonNode env = capturedAsJson();
        assertEnvelope(env, "cycle-11", "mutation_rejected");
        assertThat(env.get("payload").get("probeResult").asText()).isEqualTo("COMPILE_OK");
        assertThat(env.get("payload").get("filesOnDisk").asBoolean()).isTrue();
        assertThat(env.get("payload").get("relayedReasons").asInt()).isEqualTo(2);
    }

    @Test
    void deliberationOutcome_payloadShape() {
        logger.deliberationOutcome("cycle-12", "EVOLVE",
                List.of("ROLE_EVOLUTION=1.0"), "planner is the only executable role");
        JsonNode env = capturedAsJson();
        assertEnvelope(env, "cycle-12", "deliberation_outcome");
        assertThat(env.get("payload").fieldNames()).toIterable().containsExactlyInAnyOrder(
                "mode", "signalAdjustments", "rationale");
        assertThat(env.get("payload").get("mode").asText()).isEqualTo("EVOLVE");
        assertThat(env.get("payload").get("rationale").asText()).isEqualTo("planner is the only executable role");
    }

    @Test
    void deliberationOutcome_omitsRationaleWhenNull() {
        // T5-T: NON_NULL omits rationale when the deliberation produced none.
        logger.deliberationOutcome("cycle-12", "EVOLVE", List.of("ROLE_EVOLUTION=1.0"), null);
        assertThat(capturedAsJson().get("payload").fieldNames()).toIterable().containsExactlyInAnyOrder(
                "mode", "signalAdjustments");
    }

    @Test
    void emit_doesNotThrowWhenPublisherIsNull() {
        BehaviourLogger noPub = new BehaviourLogger(mapper, null);
        noPub.cycleStart("cycle-x", "g", null, null, null); // must not throw
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
