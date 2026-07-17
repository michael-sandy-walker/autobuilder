package com.walpro.autobuilder.observability.behaviour;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T5-I: shape-locking tests for the v2 goal-tree + pattern-detector events. Same contract
 * style as {@link BehaviourLoggerTest} — the canonical envelope plus the payload keys the
 * NDJSON/SSE consumers (preflight watches, cockpit, testament) rely on.
 */
class BehaviourLoggerV2GoalTreeEventsTest {

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
    void subgoalPromoted_envelopeAndPayload() {
        logger.subgoalPromoted("cycle-1", "goal-done", "goal-next");
        JsonNode env = capturedAsJson();
        assertEnvelope(env, "cycle-1", "subgoal_promoted");
        JsonNode p = env.get("payload");
        assertThat(p.fieldNames()).toIterable().containsExactlyInAnyOrder("fromId", "toId");
        assertThat(p.get("fromId").asText()).isEqualTo("goal-done");
        assertThat(p.get("toId").asText()).isEqualTo("goal-next");
    }

    @Test
    void subgoalPromoted_omitsToIdWhenNoLeafRemains() {
        logger.subgoalPromoted("cycle-1", "goal-done", null);
        JsonNode p = capturedAsJson().get("payload");
        assertThat(p.fieldNames()).toIterable().containsExactlyInAnyOrder("fromId");
    }

    @Test
    void goalStatusChanged_envelopeAndPayload() {
        logger.goalStatusChanged("cycle-2", "goal-x", "ACTIVE", "GIVEN_UP", "scope-thrash rationale");
        JsonNode env = capturedAsJson();
        assertEnvelope(env, "cycle-2", "goal_status_changed");
        JsonNode p = env.get("payload");
        assertThat(p.fieldNames()).toIterable().containsExactlyInAnyOrder("goalId", "from", "to", "reason");
        assertThat(p.get("goalId").asText()).isEqualTo("goal-x");
        assertThat(p.get("from").asText()).isEqualTo("ACTIVE");
        assertThat(p.get("to").asText()).isEqualTo("GIVEN_UP");
        assertThat(p.get("reason").asText()).isEqualTo("scope-thrash rationale");
    }

    @Test
    void goalStatusChanged_omitsReasonWhenNoneRecorded() {
        logger.goalStatusChanged("cycle-2", "goal-x", "ACTIVE", "COMPLETED", null);
        JsonNode p = capturedAsJson().get("payload");
        assertThat(p.fieldNames()).toIterable().containsExactlyInAnyOrder("goalId", "from", "to");
    }

    @Test
    void planningRun_envelopeAndPayload() {
        logger.planningRun("cycle-3", "goal-parent", "cycle-planning", 4, 2);
        JsonNode env = capturedAsJson();
        assertEnvelope(env, "cycle-3", "planning_run");
        JsonNode p = env.get("payload");
        assertThat(p.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "goalId", "trigger", "injected", "deduped");
        assertThat(p.get("goalId").asText()).isEqualTo("goal-parent");
        assertThat(p.get("trigger").asText()).isEqualTo("cycle-planning");
        assertThat(p.get("injected").asInt()).isEqualTo(4);
        assertThat(p.get("deduped").asInt()).isEqualTo(2);
    }

    @Test
    void patternDetected_envelopeAndPayload() {
        logger.patternDetected("cycle-4", "ReplanPatternDetector",
                "severity=0.72 repetitionDensity=0.83 maxRun=3 scope=goal-x", "REPLAN_THRASH_DETECTED");
        JsonNode env = capturedAsJson();
        assertEnvelope(env, "cycle-4", "pattern_detected");
        JsonNode p = env.get("payload");
        assertThat(p.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "detector", "evidence", "signalEmitted");
        assertThat(p.get("detector").asText()).isEqualTo("ReplanPatternDetector");
        assertThat(p.get("signalEmitted").asText()).isEqualTo("REPLAN_THRASH_DETECTED");
    }

    private JsonNode capturedAsJson() {
        assertThat(publisher.published).hasSize(1);
        return mapper.valueToTree(publisher.published.get(0));
    }

    private void assertEnvelope(JsonNode env, String expectedCycleId, String expectedEvent) {
        assertThat(env.get("timestamp").asText()).isNotBlank();
        assertThat(env.get("cycleId").asText()).isEqualTo(expectedCycleId);
        assertThat(env.get("event").asText()).isEqualTo(expectedEvent);
        assertThat(env.get("payload").isObject()).isTrue();
    }

    private static final class CapturingPublisher extends BehaviourEventPublisher {
        final CopyOnWriteArrayList<Object> published = new CopyOnWriteArrayList<>();

        @Override
        public void publish(String eventName, Object envelope) {
            published.add(envelope);
        }
    }
}
