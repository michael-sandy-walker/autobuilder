package com.walpro.autobuilder.graph.stuck;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.init.RunIdentityService;
import com.walpro.autobuilder.core.stuck.StuckPriors;
import com.walpro.autobuilder.graph.SharedNeo4jTestContainer;
import com.walpro.autobuilder.graph.agent.Neo4jAgentGraphService;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-CT: a stuck outcome written by one run is read back by a later run of the same task -- and by no other
 * task, and never by the run that wrote it. In Cypher, so tested against a real graph.
 */
@Testcontainers
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Neo4jStuckPriorsIT {

    private static final String TASK = StuckPriors.taskKey("grow the quill library");
    private static final String SIGNATURE = "the build fails: Quill.java: cannot find symbol variable NIB in class Quill";

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

    private Neo4jStuckPriors forRun(String runId) {
        RunIdentityService identity = Mockito.mock(RunIdentityService.class);
        Mockito.when(identity.runId()).thenReturn(runId);
        Mockito.when(identity.lineageId()).thenReturn("lineage-of-" + runId);
        return new Neo4jStuckPriors(new Neo4jAgentGraphService(neo4jClient, identity), neo4jClient, identity, new ObjectMapper());
    }

    private static StuckPriors.Recorded outcome(String task, int rung, StuckPriors.Outcome o) {
        return new StuckPriors.Recorded(task, "BUILD", SIGNATURE, rung, "What does the code declare?", o);
    }

    @Test
    void aLaterRunOfTheSameTaskReadsHowEarlierRunsTurnedOut() {
        forRun("run-A").record(outcome(TASK, 0, StuckPriors.Outcome.RECURRED), "cycle-a1");
        forRun("run-A").record(outcome(TASK, 1, StuckPriors.Outcome.ENDED), "cycle-a2");
        forRun("run-B").record(outcome(TASK, 1, StuckPriors.Outcome.ENDED), "cycle-b1");

        StuckPriors.Prior seen = forRun("run-C").priorFor(TASK, "BUILD", SIGNATURE);

        assertThat(seen.runs()).isEqualTo(2);
        assertThat(seen.endedRuns()).isEqualTo(2);
        assertThat(seen.endingRung()).isEqualTo(1);
    }

    @Test
    void theRunThatWroteItAnotherTaskAndAnotherSignatureSeeNothing() {
        forRun("run-A").record(outcome(TASK, 0, StuckPriors.Outcome.ENDED), "cycle-a1");

        assertThat(forRun("run-A").priorFor(TASK, "BUILD", SIGNATURE).seenBefore()).as("its own record").isFalse();
        assertThat(forRun("run-B").priorFor(StuckPriors.taskKey("another task"), "BUILD", SIGNATURE).seenBefore()).isFalse();
        assertThat(forRun("run-B").priorFor(TASK, "BUILD", SIGNATURE + " elsewhere").seenBefore()).isFalse();
        assertThat(forRun("run-B").priorFor(TASK, "PROTECTED_WRITE", SIGNATURE).seenBefore()).isFalse();
        assertThat(forRun("run-B").priorFor(TASK, "BUILD", SIGNATURE).seenBefore()).isTrue();
    }
}
