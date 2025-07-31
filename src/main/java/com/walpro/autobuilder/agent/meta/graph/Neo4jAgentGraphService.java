package com.walpro.autobuilder.agent.meta.graph;

import com.walpro.autobuilder.graph.model.AgentEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@Transactional("transactionManager")
@RequiredArgsConstructor
public class Neo4jAgentGraphService implements AgentGraphService {

    private final Neo4jClient neo4jClient;

    @Override
    public void registerAgent(AgentEntity agent) {
        neo4jClient.query("""
            MERGE (a:Agent { id: $id })
            SET a.name = $name,
                a.lineagePath = $lineagePath,
                a.roleType = $roleType,
                a.spawnedInCycle = $cycle,
                a.status = $status
        """)
        .bindAll(Map.of(
            "id", agent.id(),
            "name", agent.name(),
            "lineagePath", agent.lineagePath(),
            "roleType", agent.roleType(),
            "cycle", agent.spawnedInCycle(),
            "status", agent.status()
        ))
        .run();

        log.info("✅ Registered agent: {} ({})", agent.name(), agent.id());
    }

    @Override
    public void linkToSubgoal(String agentId, String goalId) {
        neo4jClient.query("""
            MATCH (a:Agent { id: $agentId })
            MERGE (g:Goal { id: $goalId })
            MERGE (a)-[:ASSIGNED_TO]->(g)
        """)
        .bind(agentId).to("agentId")
        .bind(goalId).to("goalId")
        .run();

        log.info("🔗 Linked agent {} → goal {}", agentId, goalId);
    }

    @Override
    public void linkSpawn(String parentAgentId, String spawnedAgentId) {
        neo4jClient.query("""
            MATCH (parent:Agent { id: $parentId })
            MATCH (child:Agent { id: $childId })
            MERGE (parent)-[:SPAWNED]->(child)
        """)
        .bind(parentAgentId).to("parentId")
        .bind(spawnedAgentId).to("childId")
        .run();

        log.info("🌱 Linked spawn: {} → {}", parentAgentId, spawnedAgentId);
    }
    
    @Override
    public List<AgentEntity> getAllAgents() {
        return neo4jClient.query("""
                MATCH (a:Agent)
        		RETURN a.id AS id, a.name AS name, a.lineagePath AS lineagePath, a.roleType AS roleType, a.spawnedInCycle AS cycle, a.status AS status
            """)
            .fetch()
            .all()
            .stream()
            .map(row -> new AgentEntity(
                (String) row.get("id"),
                (String) row.get("name"),
                (String) row.get("lineagePath"),
                (String) row.get("roleType"),
                (String) row.get("cycle"),
                (String) row.get("status")
            ))
            .toList();
    }
    
    @Override
    public void updateAgentStatus(String agentId, String status) {
        neo4jClient.query("""
            MATCH (a:Agent { id: $agentId })
            SET a.status = $status
        """)
        .bind(agentId).to("agentId")
        .bind(status).to("status")
        .run();

        log.info("🔄 Updated agent status: {} → {}", agentId, status);
    }
    
    @Override
    public List<AgentEntity> getActiveUnassignedAgents() {
        return neo4jClient.query("""
            MATCH (a:Agent)
            WHERE a.status = 'active' AND NOT (a)-[:ASSIGNED_TO]->(:Goal)
            RETURN a.id AS id, a.name AS name, a.lineagePath AS lineagePath, a.roleType AS roleType, a.spawnedInCycle AS cycle, a.status AS status
        """)
        .fetch()
        .all()
        .stream()
        .map(row -> new AgentEntity(
            (String) row.get("id"),
            (String) row.get("name"),
            (String) row.get("lineagePath"),
            (String) row.get("roleType"),
            (String) row.get("cycle"),
            (String) row.get("status")
        ))
        .toList();
    }


}
