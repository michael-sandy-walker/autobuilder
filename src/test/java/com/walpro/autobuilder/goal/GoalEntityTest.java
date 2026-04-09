package com.walpro.autobuilder.goal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;
import java.time.temporal.ChronoUnit;

class GoalEntityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    // ── Serialization safety: computed getters must not leak into JSON ──────────

    /**
     * Computed getters (e.g. getMergeCount) must carry @JsonIgnore so Jackson
     * doesn't serialise them as top-level fields. If a field appears in JSON but
     * has no setter, deserialisation of every snapshot that contains it will fail,
     * which blocks FileVariantCollector and deadlocks the merge pipeline.
     *
     * Add an assertion here for every computed getter added to GoalEntity.
     */
    @Test
    void serialization_doesNotIncludeComputedGetters() throws Exception {
        GoalEntity g = new GoalEntity("id", "desc", "planner", GoalType.SUBGOAL, "parent");
        JsonNode tree = MAPPER.valueToTree(g);

        assertThat(tree.has("mergeCount"))
                .as("mergeCount is a computed accessor into metadata — must not appear as a top-level JSON field")
                .isFalse();
    }

    /**
     * GoalEntity must survive deserialisation of JSON that contains unknown fields.
     * Unknown fields appear when old snapshots are read after a code change, or when
     * a computed getter was previously serialised (see bug fixed alongside this test).
     */
    @Test
    void deserialization_toleratesUnknownFields() {
        String json = """
                {
                  "id": "goal-1",
                  "description": "do something",
                  "status": "ACTIVE",
                  "unknownFutureField": "some-value",
                  "mergeCount": 3
                }
                """;

        assertThatCode(() -> MAPPER.readValue(json, GoalEntity.class))
                .as("GoalEntity must not throw on unknown JSON fields — snapshots must remain readable after code changes")
                .doesNotThrowAnyException();
    }

    /**
     * Full round-trip: a GoalEntity serialised to JSON must deserialise back
     * to an equivalent object without data loss or exceptions.
     */
    @Test
    void roundTrip_preservesCoreFields() throws Exception {
        GoalEntity original = new GoalEntity("goal-42", "my subgoal", "evolution", GoalType.SUBGOAL, "parent-1");
        original.getMetadata().put("mergeCount", 2);

        String json = MAPPER.writeValueAsString(original);
        GoalEntity restored = MAPPER.readValue(json, GoalEntity.class);

        assertThat(restored.getId()).isEqualTo("goal-42");
        assertThat(restored.getDescription()).isEqualTo("my subgoal");
        assertThat(restored.getType()).isEqualTo(GoalType.SUBGOAL);
        assertThat(restored.getMergeCount()).isEqualTo(2);
    }

    @Test
    void markGivenUp_setsStatusAndRecordsTimestamp() {
        GoalEntity g = new GoalEntity("id", "desc", "planner", GoalType.SUBGOAL, "parent");

        Instant before = Instant.now();
        g.markGivenUp();
        Instant after = Instant.now();

        assertThat(g.getStatus()).isEqualTo(GoalStatus.GIVEN_UP);
        assertThat(g.getGivenUp())
                .isNotNull()
                .isBetween(before, after);
    }

    @Test
    void markCompleted_setsStatusAndRecordsTimestamp() {
        GoalEntity g = new GoalEntity("id", "desc", "planner", GoalType.SUBGOAL, "parent");

        Instant before = Instant.now();
        g.markCompleted();
        Instant after = Instant.now();

        assertThat(g.getStatus()).isEqualTo(GoalStatus.COMPLETED);
        assertThat(g.getCompleted())
                .isNotNull()
                .isBetween(before, after);
    }

    @Test
    void markGivenUp_doesNotSetCompletedTimestamp() {
        GoalEntity g = new GoalEntity("id", "desc", "planner", GoalType.SUBGOAL, "parent");
        g.markGivenUp();
        assertThat(g.getCompleted()).isNull();
    }

    @Test
    void markCompleted_doesNotSetGivenUpTimestamp() {
        GoalEntity g = new GoalEntity("id", "desc", "planner", GoalType.SUBGOAL, "parent");
        g.markCompleted();
        assertThat(g.getGivenUp()).isNull();
    }
}
