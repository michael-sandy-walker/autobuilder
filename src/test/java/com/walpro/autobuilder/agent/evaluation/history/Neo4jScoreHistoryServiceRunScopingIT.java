package com.walpro.autobuilder.agent.evaluation.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.walpro.autobuilder.core.init.RunIdentityService;
import com.walpro.autobuilder.graph.SharedNeo4jTestContainer;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T1-FS: score reads must see only the run that wrote them.
 *
 * <p>The writers always stamped {@code runId}; every reader dropped it. While a reset wiped the
 * graph that was accidentally harmless, and once {@code T1-D} made lineages durable (2026-07-26)
 * it became a leak: measured on 2026-08-23, one evaluation prompt carried 9,216 scores from 71
 * runs, listed 269 file paths of which 259 did not exist, and the model called a 10-file project
 * "severely fragmented" as a direct result.
 *
 * <p>This is an integration test rather than a unit test on purpose — the defect lived in Cypher,
 * not in Java, so a mocked client would have reproduced the bug happily.
 */
@Testcontainers
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Neo4jScoreHistoryServiceRunScopingIT {

    private static final String FILE = "src/main/java/com/example/demo/Book.java";

    @DynamicPropertySource
    static void neo4jProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.neo4j.uri", SharedNeo4jTestContainer.NEO4J::getBoltUrl);
        registry.add("spring.data.neo4j.username", () -> "neo4j");
        registry.add("spring.data.neo4j.password", () -> "test-password");
    }

    @Autowired
    private Driver driver;

    @Autowired(required = false)
    private Neo4jClient neo4jClient;

    @BeforeEach
    void cleanData() {
        Assumptions.assumeTrue(neo4jClient != null, "Neo4jClient bean not present; skipping.");
        try (Session s = driver.session()) {
            s.run("MATCH (n) DETACH DELETE n").consume();
        }
    }

    /** A service pinned to one run id, as the real one is for the life of a JVM. */
    private Neo4jScoreHistoryService serviceForRun(String runId) {
        RunIdentityService identity = Mockito.mock(RunIdentityService.class);
        Mockito.when(identity.runId()).thenReturn(runId);
        Mockito.when(identity.lineageId()).thenReturn(runId == null ? null : "lineage-of-" + runId);
        return new Neo4jScoreHistoryService(neo4jClient, identity);
    }

    @Test
    void anEarlierRunsScoresAreInvisibleToALaterOne() {
        serviceForRun("run-A").recordScores("cycle-a1", Map.of(FILE, 0.20));
        serviceForRun("run-A").recordScores("cycle-a2", Map.of(FILE, 0.25));

        Neo4jScoreHistoryService runB = serviceForRun("run-B");
        runB.recordScores("cycle-b1", Map.of(FILE, 0.90));

        assertThat(runB.getScoreHistory(FILE))
                .as("run B must see only its own score, not run A's two")
                .containsExactly(0.90);
        assertThat(runB.getAllScoreHistories().get(FILE))
                .as("the prompt block is built from this map")
                .containsExactly(0.90);
    }

    @Test
    void aFileOnlyAnotherRunEverTouchedDoesNotAppearAtAll() {
        // The 259-phantom-paths case: paths from other runs must not enter the prompt at all,
        // because their mere presence is what produced the "severely fragmented" diagnosis.
        serviceForRun("run-A").recordScores("cycle-a1",
                Map.of("src/main/java/com/example/other/Ghost.java", 0.5));

        Neo4jScoreHistoryService runB = serviceForRun("run-B");
        runB.recordScores("cycle-b1", Map.of(FILE, 0.9));

        assertThat(runB.getAllScoreHistories().keySet())
                .as("only this run's own files")
                .containsExactly(FILE);
    }

    @Test
    void recentDeltasNeverSpanTwoRuns() {
        // computeRecentDeltas takes the last two entries; unscoped, those could be from
        // different runs, making the "delta" a comparison between strangers.
        serviceForRun("run-A").recordScores("cycle-a1", Map.of(FILE, 0.10));

        Neo4jScoreHistoryService runB = serviceForRun("run-B");
        runB.recordScores("cycle-b1", Map.of(FILE, 0.80));

        assertThat(runB.computeRecentDeltas())
                .as("one score in this run means no delta at all, not 0.80 - 0.10")
                .doesNotContainKey(FILE);

        runB.recordScores("cycle-b2", Map.of(FILE, 0.85));
        assertThat(runB.computeRecentDeltas().get(FILE))
                .as("the delta is within run B")
                .isCloseTo(0.05, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void aFreshRunHasNoPreviousGlobalScoreFromAStranger() {
        // getLastGlobalScore feeds lastScore, which feeds ScoreRegressionHeuristic — unscoped, a
        // new run could be penalised for "regressing" against a run it has nothing to do with.
        Neo4jScoreHistoryService runA = serviceForRun("run-A");
        runA.recordGlobalScore("cycle-a1", 0.70);
        runA.recordGlobalScore("cycle-a2", 0.75);

        assertThat(serviceForRun("run-B").getLastGlobalScore())
                .as("a run that has scored nothing yet has no previous score")
                .isEmpty();
    }

    @Test
    void withoutARunIdentityNothingIsReturnedRatherThanEverything() {
        // The safety default: falling back to an unscoped read on a missing run id would restore
        // the defect silently. No history is safe; a stranger's history is not.
        serviceForRun("run-A").recordScores("cycle-a1", Map.of(FILE, 0.42));
        serviceForRun("run-A").recordGlobalScore("cycle-a1", 0.42);

        Neo4jScoreHistoryService anonymous = serviceForRun(null);
        assertThat(anonymous.getAllScoreHistories()).isEmpty();
        assertThat(anonymous.getScoreHistory(FILE)).isEmpty();
        assertThat(anonymous.getLastGlobalScore()).isEmpty();
    }

    @Test
    void aRunStillSeesItsOwnFullHistory() {
        // The fix must not throw the baby out: within one run, history accumulates as before.
        Neo4jScoreHistoryService runA = serviceForRun("run-A");
        runA.recordScores("cycle-1", Map.of(FILE, 0.10));
        runA.recordScores("cycle-2", Map.of(FILE, 0.20));
        runA.recordScores("cycle-3", Map.of(FILE, 0.30));

        assertThat(runA.getScoreHistory(FILE)).containsExactly(0.10, 0.20, 0.30);
        assertThat(runA.getAllScoreHistories()).containsOnlyKeys(FILE);
        assertThat(List.copyOf(runA.getAllScoreHistories().get(FILE))).hasSize(3);
    }
}
