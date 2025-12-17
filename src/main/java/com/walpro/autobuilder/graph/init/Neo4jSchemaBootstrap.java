package com.walpro.autobuilder.graph.init;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Slf4j
@Component
@RequiredArgsConstructor
public class Neo4jSchemaBootstrap {

    private final Neo4jClient neo4jClient;

    @PostConstruct
    public void ensureRelationshipTypesExist() {

        // Create ASSIGNED_TO relationship type once
        neo4jClient.query("""
            MERGE (a:__BootstrapAgent { id: '__bootstrap_agent__' })
            MERGE (g:__BootstrapGoal  { id: '__bootstrap_goal__' })
            MERGE (a)-[:ASSIGNED_TO]->(g)
        """).run();

        // Clean up bootstrap nodes, relationship type stays
        neo4jClient.query("""
            MATCH (a:__BootstrapAgent)-[:ASSIGNED_TO]->(g:__BootstrapGoal)
            DETACH DELETE a, g
        """).run();

        log.info("✅ Neo4j relationship type ASSIGNED_TO bootstrapped");
    }
}
