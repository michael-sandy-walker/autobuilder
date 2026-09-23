package com.walpro.autobuilder.graph.mutation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;

import com.walpro.autobuilder.graph.SharedNeo4jTestContainer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T5-Y: Verifies that {@link Neo4jDeferredMutationGraphService} preheats its labels and
 * property keys at boot, so subsequent {@code findOpen()}-style queries against an empty
 * ledger do not emit {@code UnknownLabelWarning} / {@code UnknownPropertyKeyWarning}.
 * The mechanism is a one-shot idempotent set of {@code CREATE CONSTRAINT ... IF NOT EXISTS}
 * / {@code CREATE INDEX ... IF NOT EXISTS} statements that also enforce schema invariants
 * which were previously only implicit in the persist queries.
 */
@Testcontainers
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Neo4jDeferredMutationGraphServiceIT {

    private static final String NEO4J_WARN_LOGGER = "org.springframework.data.neo4j.cypher.unrecognized";

    @DynamicPropertySource
    static void neo4jProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.neo4j.uri", SharedNeo4jTestContainer.NEO4J::getBoltUrl);
        registry.add("spring.data.neo4j.username", () -> "neo4j");
        registry.add("spring.data.neo4j.password", () -> "test-password");
    }

    @Autowired
    private Driver driver;

    @Autowired(required = false)
    private Neo4jDeferredMutationGraphService ledger;

    @BeforeEach
    void cleanData() {
        Assumptions.assumeTrue(ledger != null, "Neo4jDeferredMutationGraphService bean not present; skipping.");
        try (Session s = driver.session()) {
            s.run("MATCH (n) DETACH DELETE n").consume();
        }
    }

    /** T1-FO: remove the ledger schema so a following ensureSchema() hits the fresh-database path. */
    private void dropDeferredMutationSchema() {
        try (Session s = driver.session()) {
            s.run("DROP CONSTRAINT deferred_mutation_id_unique IF EXISTS").consume();
            s.run("DROP INDEX deferred_mutation_state_idx IF EXISTS").consume();
            s.run("DROP CONSTRAINT blocker_fingerprint_unique IF EXISTS").consume();
        }
    }

    @Test
    void postConstructInvocationIsIdempotent() {
        // T1-FO: exercise the FRESH-schema path. The shared, reused container is warm by the
        // time this runs, so its constraints already exist and CREATE CONSTRAINT no-ops —
        // which is exactly what masked the schema-DDL / data-write transaction mix. Drop the
        // schema first so ensureSchema() performs REAL schema modifications, the condition
        // under which the pre-fix code threw ForbiddenDueToTransactionType on the sentinel write.
        dropDeferredMutationSchema();

        assertThatCode(ledger::ensureSchema).doesNotThrowAnyException();
        assertThatCode(ledger::ensureSchema).doesNotThrowAnyException();

        try (Session s = driver.session()) {
            long mutationConstraints = s.run(
                    "SHOW CONSTRAINTS YIELD name WHERE name = 'deferred_mutation_id_unique' "
                            + "RETURN count(*) AS c")
                    .single().get("c").asLong();
            assertThat(mutationConstraints).isEqualTo(1L);

            long blockerConstraints = s.run(
                    "SHOW CONSTRAINTS YIELD name WHERE name = 'blocker_fingerprint_unique' "
                            + "RETURN count(*) AS c")
                    .single().get("c").asLong();
            assertThat(blockerConstraints).isEqualTo(1L);

            long stateIndexes = s.run(
                    "SHOW INDEXES YIELD name WHERE name = 'deferred_mutation_state_idx' "
                            + "RETURN count(*) AS c")
                    .single().get("c").asLong();
            assertThat(stateIndexes).isEqualTo(1L);
        }
    }

    @Test
    void labelsAndPropertyKeysArePreheated() {
        try (Session s = driver.session()) {
            List<String> labels = s.run("CALL db.labels() YIELD label RETURN label")
                    .list(rec -> rec.get("label").asString());
            assertThat(labels).contains("DeferredMutation", "Blocker");

            List<String> propertyKeys = s.run("CALL db.propertyKeys() YIELD propertyKey RETURN propertyKey")
                    .list(rec -> rec.get("propertyKey").asString());
            assertThat(propertyKeys).contains("id", "state", "fingerprint");
        }
    }

    @Test
    void emptyLedgerQueriesEmitNoUnknownSchemaWarnings() {
        Logger neo4jWarnLogger = (Logger) LoggerFactory.getLogger(NEO4J_WARN_LOGGER);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setName("t5y-warn-capture");
        appender.start();
        neo4jWarnLogger.addAppender(appender);
        try {
            List<?> open = ledger.findOpen();
            List<?> ready = ledger.findReady();
            assertThat(open).isEmpty();
            assertThat(ready).isEmpty();
        } finally {
            neo4jWarnLogger.detachAppender(appender);
        }

        List<String> offending = appender.list.stream()
                .map(e -> e.getFormattedMessage() == null ? "" : e.getFormattedMessage())
                .filter(msg -> msg.contains("UnknownLabelWarning")
                        || msg.contains("UnknownPropertyKeyWarning"))
                .toList();
        assertThat(offending)
                .as("findOpen/findReady against an empty ledger should not emit unknown-schema warnings")
                .isEmpty();
    }

    @Test
    void idUniquenessConstraintIsEnforced() {
        try (Session s = driver.session()) {
            s.run("CREATE (m:DeferredMutation { id: 'dup-1' })").consume();
            assertThatCode(() -> s.run("CREATE (m:DeferredMutation { id: 'dup-1' })").consume())
                    .as("DeferredMutation.id-uniqueness constraint should reject a duplicate")
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Autowired
    private com.walpro.autobuilder.core.init.RunIdentityService runIdentity;

    /**
     * T2-ER: parked work is this lineage's. Unscoped, a fresh lineage's first sweep found another
     * lineage's parked mutations, judged their intent goal missing, and rewrote that lineage's
     * records as dropped.
     */
    @Test
    void anotherLineagesParkedMutationsAreInvisible() {
        try (Session s = driver.session()) {
            for (String[] row : new String[][]{{"mine", runIdentity.lineageId()}, {"theirs", "lineage-elsewhere"}}) {
                s.run("""
                        CREATE (m:DeferredMutation { id: $id, state: 'DEFERRED', lineageId: $lineage,
                            attemptId: 'a', cycleId: 'c', intentSubgoalId: 'g', intentGoalId: 'g',
                            llmScore: 0.5, heuristicScore: 0.5, deferredAtCycleIndex: 1,
                            deferredAt: '2026-01-01T00:00:00Z', mutationDir: 'd' })
                        """, java.util.Map.of("id", row[0], "lineage", row[1])).consume();
            }
        }

        assertThat(ledger.findOpen()).extracting(d -> d.id()).containsExactly("mine");
        assertThat(ledger.dropBlockersAndFindCleared(java.util.Set.of("any-fingerprint")))
                .as("an unblocked parked mutation of another lineage is not this lineage's to restage")
                .containsExactly("mine");
    }
}
