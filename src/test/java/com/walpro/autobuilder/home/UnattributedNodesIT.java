package com.walpro.autobuilder.home;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.testcontainers.containers.Neo4jContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T5-BX on a graph of its own -- not the shared test container, since the erase removes every node without a lineage
 * there is, whoever wrote it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UnattributedNodesIT {

    private final Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:5").withAdminPassword("test-password");
    private UnattributedNodes nodes;

    @BeforeAll
    void start() {
        neo4j.start();
        nodes = new UnattributedNodes(neo4j.getBoltUrl(), "neo4j", "test-password");
    }

    @AfterAll
    void stop() {
        neo4j.stop();
    }

    @Test
    void removesOnlyTheNodesWithoutALineageAndSaysHowManyOfEach() {
        run("""
                CREATE (e:Episode {lineageId: 'lin-a'})-[:LINKS_TO]->(:Artifact {lineageId: 'lin-a'}),
                       (:Relation {lineageId: 'lin-b'}),
                       (s:SystemState {id: 'state'}),
                       (o:Episode)-[:LINKS_TO]->(:Artifact),
                       (o)-[:LINKS_TO]->(:Artifact),
                       (:Relation)-[:LINKS_TO]->(:Interpretation),
                       (o)-[:LINKS_TO]->(e)
                """);
        Map<String, Long> perLineageBefore = perLineage();

        assertThat(nodes.count()).containsExactlyInAnyOrderEntriesOf(
                Map.of("Episode", 1L, "Artifact", 2L, "Relation", 1L, "Interpretation", 1L));
        Map<String, Long> removed = nodes.erase();

        assertThat(removed).containsExactlyInAnyOrderEntriesOf(
                Map.of("Episode", 1L, "Artifact", 2L, "Relation", 1L, "Interpretation", 1L));
        assertThat(nodes.count()).isEmpty();
        assertThat(perLineage()).isEqualTo(perLineageBefore);
        assertThat(single("MATCH (n:SystemState) RETURN count(n) AS c")).isEqualTo(1L);
        assertThat(single("MATCH (:Episode {lineageId:'lin-a'})-[r:LINKS_TO]->(:Artifact) RETURN count(r) AS c"))
                .isEqualTo(1L);
        assertThat(nodes.erase()).isEmpty();
    }

    private Map<String, Long> perLineage() {
        Map<String, Long> out = new java.util.TreeMap<>();
        try (Driver d = driver(); Session s = d.session()) {
            s.run("MATCH (n) WHERE n.lineageId IS NOT NULL RETURN n.lineageId AS l, count(*) AS c").list()
                    .forEach(r -> out.put(r.get("l").asString(), r.get("c").asLong()));
        }
        return out;
    }

    private long single(String cypher) {
        try (Driver d = driver(); Session s = d.session()) {
            return s.run(cypher).single().get("c").asLong();
        }
    }

    private void run(String cypher) {
        try (Driver d = driver(); Session s = d.session()) {
            s.run(cypher).consume();
        }
    }

    private Driver driver() {
        return GraphDatabase.driver(neo4j.getBoltUrl(), AuthTokens.basic("neo4j", "test-password"));
    }
}
