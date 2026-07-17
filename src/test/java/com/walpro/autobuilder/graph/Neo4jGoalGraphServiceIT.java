package com.walpro.autobuilder.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.goal.GoalType;
import com.walpro.autobuilder.graph.goal.Neo4jGoalGraphService;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Neo4jGoalGraphServiceIT {

    @DynamicPropertySource
    static void neo4jProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.neo4j.uri", SharedNeo4jTestContainer.NEO4J::getBoltUrl);
        registry.add("spring.data.neo4j.username", () -> "neo4j");
        registry.add("spring.data.neo4j.password", () -> "test-password");
    }

    @Autowired
    private Driver driver;

    @Autowired(required = false)
    private Neo4jGoalGraphService goalGraph;

    @BeforeEach
    void cleanDb() {
        Assumptions.assumeTrue(goalGraph != null, "Neo4jGoalGraphService bean not present; skipping.");
        try (Session s = driver.session()) {
            s.run("MATCH (n) DETACH DELETE n").consume();
        }
    }

    @Test
    void save_findById_findSubgoals_roundTrip_includingParentRelationship() {
        GoalEntity root = new GoalEntity(
                "root",
                "Root goal",
                "planner",
                GoalType.GOAL,
                GoalStatus.ACTIVE,
                Instant.parse("2025-01-01T00:00:00Z"),
                null,
                Map.of("k", "v", "n", 1)
        );

        GoalEntity child = new GoalEntity(
                "child",
                "Child subgoal",
                "planner",
                GoalType.SUBGOAL,
                GoalStatus.ACTIVE,
                Instant.parse("2025-01-02T00:00:00Z"),
                "root",
                Map.of("x", true)
        );

        goalGraph.save(root);
        goalGraph.save(child);

        Optional<GoalEntity> loadedChild = goalGraph.findById("child");
        assertThat(loadedChild).isPresent();
        assertThat(loadedChild.get().getParentId()).isEqualTo("root");

        List<GoalEntity> subgoals = goalGraph.findSubgoals("root");
        assertThat(subgoals).hasSize(1);
        assertThat(subgoals.get(0).getId()).isEqualTo("child");

        // Sanity check relationship exists in DB
        try (Session s = driver.session()) {
            long relCount = s.run("""
                    MATCH (:Goal {id:$c})-[:SUBGOAL_OF]->(:Goal {id:$p})
                    RETURN count(*) AS c
                    """, Values.parameters("c", "child", "p", "root"))
                    .single().get("c").asLong();
            assertThat(relCount).isEqualTo(1);
        }

        // metadata round-trip (stored as JSON string; parsed back to map)
        assertThat(loadedChild.get().getMetadata())
                .containsEntry("x", true);

    }

    @Test
    void save_stampsRunAndLineageIdentity() {
        // T4-CJ slice 1: every graph write carries the run/lineage identity (write-only —
        // no read path consults it yet; the stamps make dumps distinguishable across runs).
        GoalEntity g = new GoalEntity("stamped", "Stamped goal", "planner", GoalType.GOAL,
                GoalStatus.ACTIVE, Instant.now(), null, Map.of());

        goalGraph.save(g);

        try (Session s = driver.session()) {
            var record = s.run("""
                    MATCH (g:Goal {id:$id})
                    RETURN g.lineageId AS lineageId, g.runId AS runId
                    """, Values.parameters("id", "stamped"))
                    .single();
            assertThat(record.get("lineageId").asString()).startsWith("lineage-");
            assertThat(record.get("runId").asString()).startsWith("run-");
        }
    }

    @Test
    void save_update_shouldRebindSubgoalRelationship_and_notLeaveOldEdgeBehind() {
        GoalEntity a = new GoalEntity("A", "A", "planner", GoalType.GOAL, GoalStatus.ACTIVE, Instant.now(), null, Map.of());
        GoalEntity b = new GoalEntity("B", "B", "planner", GoalType.GOAL, GoalStatus.ACTIVE, Instant.now(), null, Map.of());

        goalGraph.save(a);
        goalGraph.save(b);

        GoalEntity child = new GoalEntity("C", "C", "planner", GoalType.SUBGOAL, GoalStatus.ACTIVE, Instant.now(), "A", Map.of());
        goalGraph.save(child);

        // update: change parent A -> B (same id => update path in service)
        GoalEntity updated = new GoalEntity("C", "C", "planner", GoalType.SUBGOAL, GoalStatus.ACTIVE, Instant.now(), "B", Map.of("m", "changed"));
        goalGraph.save(updated);

        try (Session s = driver.session()) {
            long toA = s.run("""
                    MATCH (:Goal {id:$c})-[:SUBGOAL_OF]->(:Goal {id:$p})
                    RETURN count(*) AS c
                    """, Values.parameters("c", "C", "p", "A"))
                    .single().get("c").asLong();

            long toB = s.run("""
                    MATCH (:Goal {id:$c})-[:SUBGOAL_OF]->(:Goal {id:$p})
                    RETURN count(*) AS c
                    """, Values.parameters("c", "C", "p", "B"))
                    .single().get("c").asLong();

            assertThat(toA).isEqualTo(0);
            assertThat(toB).isEqualTo(1);
        }
    }

    @Test
    void markCompleted_shouldPersistStatus() {
        GoalEntity g = new GoalEntity("X", "X", "planner", GoalType.SUBGOAL, GoalStatus.ACTIVE, Instant.now(), null, Map.of());
        goalGraph.save(g);

        goalGraph.markCompleted("X");

        // Validate via query (and via service if preferred)
        try (Session s = driver.session()) {
            String status = s.run("""
                    MATCH (g:Goal {id:$id})
                    RETURN g.status AS status
                    """, Values.parameters("id", "X"))
                    .single().get("status").asString();

            assertThat(status).isEqualTo("COMPLETED");
        }
    }

    @Test
    void save_givenUpGoal_persistsAndRoundTripsGivenUpTimestamp() {
        GoalEntity g = new GoalEntity("gu1", "A subgoal", "planner", GoalType.SUBGOAL, GoalStatus.ACTIVE, Instant.now(), null, Map.of());
        goalGraph.save(g);

        Instant before = Instant.now();
        g.markGivenUp();
        Instant after = Instant.now();
        goalGraph.save(g);

        Optional<GoalEntity> loaded = goalGraph.findById("gu1");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getStatus()).isEqualTo(GoalStatus.GIVEN_UP);
        assertThat(loaded.get().getGivenUp())
                .isNotNull()
                .isBetween(before.minusSeconds(1), after.plusSeconds(1));
        assertThat(loaded.get().getCompleted()).isNull();
    }

    @Test
    void findAllDescriptions_returnsAllGoalsUpToLimit_includingGivenUp() {
        goalGraph.purgeAll();

        goalGraph.save(new GoalEntity("g1", "Implement Persistence Layer", "planner", GoalType.SUBGOAL, GoalStatus.ACTIVE, null, null, Map.of()));
        goalGraph.save(new GoalEntity("g2", "Add persistence tests", "planner", GoalType.SUBGOAL, GoalStatus.GIVEN_UP, null, null, Map.of()));
        goalGraph.save(new GoalEntity("g3", "Unrelated task", "planner", GoalType.SUBGOAL, GoalStatus.ACTIVE, null, null, Map.of()));

        List<String> all = goalGraph.findAllDescriptions(200);

        assertThat(all).hasSize(3);
        assertThat(all).contains("Implement Persistence Layer", "Add persistence tests", "Unrelated task");

        // GIVEN_UP goals must be included
        assertThat(all).contains("Add persistence tests");

        // Limit is respected
        List<String> limited = goalGraph.findAllDescriptions(2);
        assertThat(limited).hasSize(2);
    }
}
