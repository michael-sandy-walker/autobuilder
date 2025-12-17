package com.walpro.autobuilder.graph;

import org.testcontainers.containers.Neo4jContainer;

public final class SharedNeo4jTestContainer {

    public static final Neo4jContainer<?> NEO4J =
            new Neo4jContainer<>("neo4j:5")
                    .withAdminPassword("test-password")
                    .withReuse(true);

    static {
        NEO4J.start();
    }

    private SharedNeo4jTestContainer() {}
}
