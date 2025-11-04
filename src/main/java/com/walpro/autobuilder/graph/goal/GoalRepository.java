package com.walpro.autobuilder.graph.goal;

import org.springframework.data.neo4j.repository.Neo4jRepository;

public interface GoalRepository extends Neo4jRepository<GoalNode, String> {
}
