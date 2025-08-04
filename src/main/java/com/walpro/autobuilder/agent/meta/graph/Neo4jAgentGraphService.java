package com.walpro.autobuilder.agent.meta.graph;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.walpro.autobuilder.graph.model.ActionEntity;
import com.walpro.autobuilder.graph.model.AgentEntity;
import com.walpro.autobuilder.graph.model.ArtifactEntity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
    
    @Override
    public void registerAction(ActionEntity action) {
        neo4jClient.query("""
            MERGE (a:Action { id: $id })
            SET a.type = $type,
                a.status = $status,
                a.createdByAgentId = $agentId,
                a.targetsGoalId = $goalId,
                a.occurredInCycle = $cycle,
                a.timestamp = datetime($timestamp)
        """)
        .bindAll(Map.of(
            "id", action.id(),
            "type", action.type(),
            "status", action.status(),
            "agentId", action.createdByAgentId(),
            "goalId", action.targetsGoalId(),
            "cycle", action.occurredInCycle(),
            "timestamp", action.timestamp().toString()
        ))
        .run();
    }
    
    @Override
    public void linkActionOutput(String actionId, ArtifactEntity artifact) {
        neo4jClient.query("""
            MERGE (art:Artifact { id: $id })
            SET art.type = $type,
                art.path = $path,
                art.content = $content,
                art.score = $score
            WITH art
            MATCH (a:Action { id: $actionId })
            MERGE (a)-[:PRODUCES]->(art)
        """)
        .bindAll(new HashMap<>() {{
            put("id", artifact.id());
            put("type", artifact.type());
            put("path", artifact.path());
            put("content", artifact.content());
            put("score", artifact.score());
            put("actionId", actionId);
        }})
        .run();
    }
    
    @Override
    public List<AgentEntity> getAgentsByRoleAndGoal(String roleType, String goalId) {
        String query = """
            MATCH (a:Agent)-[:ASSIGNED_TO]->(g:Goal {id: $goalId})
            WHERE a.roleType = $roleType
            RETURN a.id AS id, a.name AS name, a.lineagePath AS lineagePath,
                   a.roleType AS roleType, a.spawnedInCycle AS spawnedInCycle, a.status AS status
            """;

        Map<String, Object> params = Map.of(
            "roleType", roleType,
            "goalId", goalId
        );

        return neo4jClient.query(query)
                .bindAll(params)
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
                )).toList();
    }


}
