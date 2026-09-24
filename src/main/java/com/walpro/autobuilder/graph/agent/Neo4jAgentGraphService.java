package com.walpro.autobuilder.graph.agent;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.walpro.autobuilder.graph.evaluation.CriterionResultEntity;
import com.walpro.autobuilder.graph.scope.ScopeActionSnapshot;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.walpro.autobuilder.graph.core.ActionEntity;
import com.walpro.autobuilder.graph.core.ArtifactEntity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@Transactional("transactionManager")
@RequiredArgsConstructor
public class Neo4jAgentGraphService implements AgentGraphService {

    private final Neo4jClient neo4jClient;
    private final com.walpro.autobuilder.core.init.RunIdentityService runIdentity;

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
            a.timestamp = datetime($timestamp),
            a.lineageId = $lineageId,
            a.runId = $runId
        WITH a
        MERGE (c:Cycle { id: $cycle })
        SET c.lineageId = $lineageId,
            c.runId = $runId
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
                .bindAll(new HashMap<>() {{
                        put("id", action.id());
                        put("type", action.type());
                        put("status", action.status());
                        put("agentId", action.createdByAgentId());
                        put("goalId", action.targetsGoalId());
                        put("rootGoalId", action.rootGoalId());
                        put("cycle", action.occurredInCycle() != null ? action.occurredInCycle() : "");
                        put("attemptId", action.attemptId() != null ? action.attemptId() : "");
                        put("parentAttemptId", action.parentAttemptId() != null ? action.parentAttemptId() : "");
                        put("timestamp", action.timestamp().toString());
                        put("lineageId", runIdentity.lineageId());
                        put("runId", runIdentity.runId());
                }})
                .run();
    }
    
    @Override
    public void linkActionOutput(String actionId, ArtifactEntity artifact) {
        neo4jClient.query("""
            MERGE (art:Artifact { id: $id })
            SET art.type = $type,
                art.path = $path,
                art.content = $content,
                art.score = $score,
                art.lineageId = $lineageId,
                art.runId = $runId
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
            put("lineageId", runIdentity.lineageId());
            put("runId", runIdentity.runId());
        }})
        .run();
    }
    
    @Override
    public List<ArtifactEntity> findRecentArtifactsForGoal(String goalId, List<String> artifactTypes, int limit) {
        if (goalId == null || goalId.isBlank()) {
            return List.of();
        }
        int safeLimit = Math.max(1, limit);
        // We traverse: (Action {targetsGoalId})-[:PRODUCES]->(Artifact)
        // Ordered by action timestamp descending.
        // T4-CJ slice 2: RUN-SCOPED. This feeds DefaultInterpretationAgent's "recent artifacts"
        // prompt block; a previous run's artifacts rendered as this cycle's recent history is the
        // same mislabelling the snapshot marker fixes, without a marker to catch it.
        return neo4jClient.query("""
            MATCH (a:Action { rootGoalId: $goalId })-[:PRODUCES]->(art:Artifact)
            WHERE a.runId = $runId
              AND ($types IS NULL OR art.type IN $types)
            RETURN art.id AS id, art.type AS type, art.path AS path, art.content AS content, art.score AS score
            ORDER BY a.timestamp DESC
            LIMIT $limit
        """)
                .bindAll(Map.of(
                        "goalId", goalId,
                        "runId", runIdentity.runId(),
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
    public void linkCriterionResults(List<CriterionResultEntity> results) {
        if (results == null || results.isEmpty()) return;
        for (CriterionResultEntity cr : results) {
            neo4jClient.query("""
                MERGE (cr:CriterionResult { actionId: $actionId, criterionIndex: $criterionIndex })
                SET cr.criterionText = $criterionText,
                    cr.passed = $passed,
                    cr.cycleId = $cycleId,
                    cr.subgoalId = $subgoalId,
                    cr.criterionKey = $criterionKey,
                    cr.codeDigest = $codeDigest,
                    cr.lineageId = $lineageId,
                    cr.runId = $runId
                WITH cr
                OPTIONAL MATCH (a:Action { id: $actionId })
                FOREACH (_ IN CASE WHEN a IS NULL THEN [] ELSE [1] END |
                    MERGE (a)-[:HAS_CRITERION]->(cr))
            """)
            .bindAll(Map.of(
                "actionId", cr.actionId(),
                "criterionIndex", cr.criterionIndex(),
                "criterionText", cr.criterionText() != null ? cr.criterionText() : "",
                "passed", cr.passed(),
                "cycleId", cr.cycleId() != null ? cr.cycleId() : "",
                "subgoalId", cr.subgoalId() != null ? cr.subgoalId() : "",
                "criterionKey", cr.criterionKey(),
                "codeDigest", cr.codeDigest() != null ? cr.codeDigest() : "",
                "lineageId", runIdentity.lineageId(),
                "runId", runIdentity.runId()
            ))
            .run();
        }
    }

    @Override
    public Map<String, Boolean> findCriterionResults(java.util.Collection<String> criterionKeys, String codeDigest) {
        if (criterionKeys == null || criterionKeys.isEmpty() || codeDigest == null || codeDigest.isBlank()) {
            return Map.of();
        }
        Map<String, Boolean> out = new java.util.HashMap<>();
        neo4jClient.query("""
                MATCH (cr:CriterionResult)
                WHERE cr.criterionKey IN $keys AND cr.codeDigest = $digest
                RETURN cr.criterionKey AS key, cr.passed AS passed
            """)
            .bindAll(Map.of("keys", new java.util.ArrayList<>(criterionKeys), "digest", codeDigest))
            .fetch().all()
            .forEach(row -> out.put((String) row.get("key"), Boolean.TRUE.equals(row.get("passed"))));
        return out;
    }

    @Override
    public void recordCompletionResults(List<CriterionResultEntity> results) {
        if (results == null || results.isEmpty()) return;
        for (CriterionResultEntity cr : results) {
            if (cr.codeDigest() == null || cr.codeDigest().isBlank()) continue;
            neo4jClient.query("""
                    MERGE (cr:CriterionResult { criterionKey: $criterionKey, codeDigest: $codeDigest })
                    SET cr.criterionText = $criterionText,
                        cr.passed = $passed,
                        cr.cycleId = $cycleId,
                        cr.criterionIndex = $criterionIndex,
                        cr.subgoalId = $subgoalId,
                        cr.lineageId = $lineageId,
                        cr.runId = $runId
                """)
                .bindAll(Map.of(
                    "criterionKey", cr.criterionKey(),
                    "codeDigest", cr.codeDigest(),
                    "criterionText", cr.criterionText() != null ? cr.criterionText() : "",
                    "passed", cr.passed(),
                    "cycleId", cr.cycleId() != null ? cr.cycleId() : "",
                    "criterionIndex", cr.criterionIndex(),
                    "subgoalId", cr.subgoalId() != null ? cr.subgoalId() : "",
                    "lineageId", runIdentity.lineageId(),
                    "runId", runIdentity.runId()
                ))
                .run();
        }
    }

    @Override
    public String findLatestEvaluationStatusForSubgoal(String subGoalId) {
        return neo4jClient.query("""
            MATCH (a:Action)
            WHERE a.type = 'evaluation'
              AND a.targetsGoalId = $subGoalId
            RETURN a.status AS status
            ORDER BY a.timestamp DESC
            LIMIT 1
        """)
                .bindAll(Map.of("subGoalId", subGoalId))
                .fetch()
                .one()
                .map(row -> (String) row.get("status"))
                .orElse(null);
    }

    @Override
    public java.util.Optional<Double> findLatestAcceptedScoreForSubgoal(String subGoalId) {
        if (subGoalId == null || subGoalId.isBlank()) return java.util.Optional.empty();
        return neo4jClient.query("""
            MATCH (a:Action)-[:PRODUCES]->(art:Artifact)
            WHERE a.type = 'evaluation'
              AND a.status = 'ACCEPTED'
              AND a.targetsGoalId = $subGoalId
              AND art.score IS NOT NULL
            RETURN art.score AS score
            ORDER BY a.timestamp DESC
            LIMIT 1
        """)
                .bindAll(Map.of("subGoalId", subGoalId))
                .fetch()
                .one()
                .map(row -> row.get("score") == null ? null : ((Number) row.get("score")).doubleValue());
    }

    @Override
    public java.util.List<Double> findScoreHistoryForSubgoal(String subGoalId, int limit) {
        if (subGoalId == null || subGoalId.isBlank()) return List.of();
        int safeLimit = Math.max(1, limit);
        return neo4jClient.query("""
            MATCH (a:Action)-[:PRODUCES]->(art:Artifact)
            WHERE a.type = 'evaluation'
              AND a.status = 'ACCEPTED'
              AND a.targetsGoalId = $subGoalId
              AND art.score IS NOT NULL
            RETURN art.score AS score
            ORDER BY a.timestamp DESC
            LIMIT $limit
        """)
                .bindAll(Map.of("subGoalId", subGoalId, "limit", safeLimit))
                .fetch()
                .all()
                .stream()
                .filter(row -> row.get("score") != null)
                .map(row -> ((Number) row.get("score")).doubleValue())
                .toList();
    }

    public List<ScopeActionSnapshot> findRecentScopeActions(String subGoalId, int limit) {
        // T4-CJ slice 2: RUN-SCOPED. ScopeThrashSignalAnalyzer counts CONSECUTIVE out-of-scope
        // assessments; a streak that reaches back into a previous run is not a streak.
        return neo4jClient.query("""
            MATCH (a:Action)
            WHERE a.type = 'scope'
              AND a.targetsGoalId = $subGoalId
              AND a.runId = $runId
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
                        "runId", runIdentity.runId(),
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
