package com.walpro.autobuilder.graph.agent;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.walpro.autobuilder.graph.planner.PlannerActionSnapshot;
import com.walpro.autobuilder.graph.planner.PlannerInflowStats;
import com.walpro.autobuilder.graph.scope.ScopeActionSnapshot;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.walpro.autobuilder.graph.core.ActionEntity;
import com.walpro.autobuilder.graph.core.AgentEntity;
import com.walpro.autobuilder.graph.core.ArtifactEntity;

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

        log.info("Registered agent: {} ({})", agent.name(), agent.id());
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

        log.info("Linked spawn: {} → {}", parentAgentId, spawnedAgentId);
    }
    
    @Override
    public List<AgentEntity> getAllAgents() {
        return neo4jClient.query("""
                MATCH (a:Agent)
                RETURN a.id AS id, a.name AS name, a.lineagePath AS lineagePath, a.roleType AS roleType, a.spawnedInCycle AS cycle, a.status AS status
           \s""")
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

        log.info("Updated agent status: {} → {}", agentId, status);
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
            a.rootGoalId = $rootGoalId,
            a.occurredInCycle = $cycle,
            a.attemptId = $attemptId,
            a.parentAttemptId = $parentAttemptId,
            a.timestamp = datetime($timestamp)
        WITH a
        MERGE (c:Cycle { id: $cycle })
        MERGE (c)-[:HAS_ACTION]->(a)

        // ACTIVE goal link (this should be the subgoal when present)
        WITH a, c
        FOREACH (_ IN CASE WHEN $goalId IS NULL OR $goalId = '' THEN [] ELSE [1] END |
            MERGE (g:Goal { id: $goalId })
            MERGE (a)-[:TARGETS_GOAL]->(g)
            MERGE (c)-[:ABOUT_GOAL]->(g)
        )

        // ROOT goal link (optional, but useful)
        WITH a
        FOREACH (_ IN CASE WHEN $rootGoalId IS NULL OR $rootGoalId = '' THEN [] ELSE [1] END |
            MERGE (rg:Goal { id: $rootGoalId })
            MERGE (a)-[:ROOT_GOAL]->(rg)
        )
    """)
                .bindAll(Map.of(
                        "id", action.id(),
                        "type", action.type(),
                        "status", action.status(),
                        "agentId", action.createdByAgentId(),
                        "goalId", action.targetsGoalId(),
                        "rootGoalId", action.rootGoalId(),
                        "cycle", action.occurredInCycle() != null ? action.occurredInCycle() : "",
                        "attemptId", action.attemptId() != null ? action.attemptId() : "",
                        "parentAttemptId", action.parentAttemptId() != null ? action.parentAttemptId() : "",
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

    @Override
    public long countAllAgents() {
        return neo4jClient.query("""
            MATCH (a:Agent)
            RETURN count(a) AS c
        """).fetchAs(Long.class).one().orElse(0L);
    }

    @Override
    public long countActiveAgents() {
        return neo4jClient.query("""
            MATCH (a:Agent)
            WHERE a.status = 'active'
            RETURN count(a) AS c
        """).fetchAs(Long.class).one().orElse(0L);
    }

    @Override
    public long countActiveUnassignedAgents() {
        // NOTE: this query is the one that produced the warning earlier when ASSIGNED_TO never existed.
        // If we add the bootstrap initializer, we can keep it typed like this.
        return neo4jClient.query("""
            MATCH (a:Agent)
            WHERE a.status = 'active' AND NOT (a)-[:ASSIGNED_TO]->(:Goal)
            RETURN count(a) AS c
        """).fetchAs(Long.class).one().orElse(0L);
    }

    @Override
    public long countActiveAgentsByRoleAndGoal(String roleType, String goalId) {
        return neo4jClient.query("""
            MATCH (a:Agent)-[:ASSIGNED_TO]->(g:Goal { id: $goalId })
            WHERE a.status = 'active' AND a.roleType = $roleType
            RETURN count(a) AS c
        """)
                .bindAll(Map.of("roleType", roleType, "goalId", goalId))
                .fetchAs(Long.class).one().orElse(0L);
    }

    @Override
    public long pruneInactiveUnassignedAgents(int limit) {
        // Deletes up to {limit} agents that are inactive AND have no goal assignment.
        // This is intentionally conservative and avoids needing a "lastSeen" field.
        return neo4jClient.query("""
            MATCH (a:Agent)
            WHERE a.status = 'inactive' AND NOT (a)-[:ASSIGNED_TO]->(:Goal)
            WITH a LIMIT $limit
            DETACH DELETE a
            RETURN count(*) AS c
        """)
                .bind(limit).to("limit")
                .fetchAs(Long.class).one().orElse(0L);
    }

    @Override
    public List<ArtifactEntity> findRecentArtifactsForGoal(String goalId, List<String> artifactTypes, int limit) {
        if (goalId == null || goalId.isBlank()) {
            return List.of();
        }
        int safeLimit = Math.max(1, limit);
        // We traverse: (Action {targetsGoalId})-[:PRODUCES]->(Artifact)
        // Ordered by action timestamp descending.
        return neo4jClient.query("""
            MATCH (a:Action { rootGoalId: $goalId })-[:PRODUCES]->(art:Artifact)
            WHERE ($types IS NULL OR art.type IN $types)
            RETURN art.id AS id, art.type AS type, art.path AS path, art.content AS content, art.score AS score
            ORDER BY a.timestamp DESC
            LIMIT $limit
        """)
                .bindAll(Map.of(
                        "goalId", goalId,
                        "types", (artifactTypes == null || artifactTypes.isEmpty()) ? "" : artifactTypes,
                        "limit", safeLimit
                ))
                .fetch()
                .all()
                .stream()
                .map(row -> new ArtifactEntity(
                        (String) row.get("id"),
                        (String) row.get("type"),
                        (String) row.get("path"),
                        (String) row.get("content"),
                        (Double) row.get("score")
                ))
                .toList();
    }

    public void attachPlannerMetadata(
            String actionId,
            String fingerprint,
            boolean updated,
            int newGoalsCount
    ) {
        neo4jClient.query("""
        MATCH (a:Action { id: $actionId })
        SET a.fingerprint = $fingerprint,
            a.plannerUpdated = $updated,
            a.plannerNewGoalsCount = $newGoalsCount
    """)
                .bindAll(Map.of(
                        "actionId", actionId,
                        "fingerprint", fingerprint,
                        "updated", updated,
                        "newGoalsCount", newGoalsCount
                ))
                .run();
    }

    @Override
    public List<PlannerActionSnapshot> findRecentPlannerActions(String scopeGoalId, int limit) {
        return neo4jClient.query("""
            MATCH (a:Action)
            WHERE a.type = 'planner'
              AND a.targetsGoalId = $scopeGoalId
              AND a.fingerprint IS NOT NULL
            RETURN a.id            AS actionId,
                   a.targetsGoalId AS scopeGoalId,
                   a.fingerprint   AS fingerprint,
                   a.plannerUpdated AS updated,
                   a.plannerNewGoalsCount AS newGoalsCount,
                   a.occurredInCycle AS cycleId,
                   a.attemptId     AS attemptId,
                   a.timestamp     AS ts
            ORDER BY a.timestamp DESC
            LIMIT $limit
        """)
                .bindAll(Map.of(
                        "scopeGoalId", scopeGoalId,
                        "limit", limit
                ))
                .fetch()
                .all()
                .stream()
                .map(row -> new PlannerActionSnapshot(
                        (String) row.get("actionId"),
                        (String) row.get("scopeGoalId"),
                        (String) row.get("fingerprint"),
                        (Boolean) row.get("updated"),
                        row.get("newGoalsCount") == null ? null : ((Number) row.get("newGoalsCount")).intValue(),
                        (String) row.get("cycleId"),
                        (String) row.get("attemptId"),
                        row.get("ts") == null ? null : Instant.parse(row.get("ts").toString().replace("Z", "Z"))
                ))
                .toList();
    }

    @Override
    public PlannerInflowStats getPlannerInflowSince(String scopeGoalId, Instant since) {
        Map<String, Object> row = neo4jClient.query("""
            MATCH (a:Action)
            WHERE a.type = 'planner'
              AND a.targetsGoalId = $scopeGoalId
              AND a.timestamp >= datetime($since)
            RETURN
              sum(CASE WHEN coalesce(a.plannerUpdated,false) = true THEN 1 ELSE 0 END) AS updatedActions,
              sum(CASE WHEN coalesce(a.plannerUpdated,false) = true THEN coalesce(a.plannerNewGoalsCount,0) ELSE 0 END) AS totalNewGoals
        """)
                .bindAll(Map.of(
                        "scopeGoalId", scopeGoalId,
                        "since", since.toString()
                ))
                .fetch()
                .one()
                .orElse(Map.of());

        int updatedActions = row.get("updatedActions") == null ? 0 : ((Number) row.get("updatedActions")).intValue();
        int totalNewGoals = row.get("totalNewGoals") == null ? 0 : ((Number) row.get("totalNewGoals")).intValue();

        return new PlannerInflowStats(updatedActions, totalNewGoals);
    }

    @Override
    public List<ScopeActionSnapshot> findRecentScopeActions(String subGoalId, int limit) {
        return neo4jClient.query("""
            MATCH (a:Action)
            WHERE a.type = 'scope'
              AND a.targetsGoalId = $subGoalId
            RETURN a.id            AS actionId,
                   a.targetsGoalId AS subGoalId,
                   a.status        AS status,
                   a.occurredInCycle AS cycleName,
                   a.timestamp     AS ts
            ORDER BY a.timestamp DESC
            LIMIT $limit
        """)
                .bindAll(Map.of(
                        "subGoalId", subGoalId,
                        "limit", limit
                ))
                .fetch()
                .all()
                .stream()
                .map(row -> new ScopeActionSnapshot(
                        (String) row.get("actionId"),
                        (String) row.get("subGoalId"),
                        (String) row.get("status"),
                        (String) row.get("cycleName"),
                        row.get("ts") == null ? null : Instant.parse(row.get("ts").toString())
                ))
                .toList();
    }

}
