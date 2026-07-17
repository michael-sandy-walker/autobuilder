package com.walpro.autobuilder.graph.evolution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@Transactional("transactionManager")
@RequiredArgsConstructor
public class Neo4jEvolutionGraphService implements EvolutionGraphService {

    private final Neo4jClient neo4jClient;
    private final ObjectMapper objectMapper;
    private final com.walpro.autobuilder.core.init.RunIdentityService runIdentity;

    @Override
    public void saveAttempt(EvolutionAttemptNode n) {
        Map<String, Object> params = new HashMap<>();
        params.put("lineageId", runIdentity.lineageId());
        params.put("runId", runIdentity.runId());
        params.put("id", n.id());
        params.put("cycleId", n.cycleId());
        params.put("divergent", n.divergent());
        params.put("fingerprint", n.fingerprint());
        params.put("changedFilesCount", n.changedFilesCount());
        params.put("deletedFilesCount", n.deletedFilesCount());
        params.put("changedPaths", n.changedPaths());
        params.put("deletedPaths", n.deletedPaths());
        params.put("goalId", n.goalId());
        params.put("subGoalId", n.subGoalId());
        params.put("created", n.created() != null ? n.created().toString() : null);

        try {
            params.put("metaJson", objectMapper.writeValueAsString(n.meta() != null ? n.meta() : Map.of()));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize meta for EvolutionAttempt {}: {}", n.id(), e.getMessage());
            params.put("metaJson", "{}");
        }

        // Single statement; avoid FOREACH + MATCH chaining rules by using subqueries.
        neo4jClient.query("""
            MERGE (a:EvolutionAttempt {id: $id})
            SET a.cycleId = $cycleId,
                a.divergent = $divergent,
                a.fingerprint = $fingerprint,
                a.changedFilesCount = $changedFilesCount,
                a.deletedFilesCount = $deletedFilesCount,
                a.changedPaths = $changedPaths,
                a.deletedPaths = $deletedPaths,
                a.goalId = $goalId,
                a.subGoalId = $subGoalId,
                a.meta = $metaJson,
                a.created = $created,
                a.lineageId = $lineageId,
                a.runId = $runId
            WITH a
            MERGE (c:Cycle {id: $cycleId})
            SET c.lineageId = $lineageId,
                c.runId = $runId
            MERGE (c)-[:EVOLVED]->(a)
            WITH a
            CALL (a) {
              MATCH (g:Goal {id: $goalId})
              MERGE (a)-[:UNDER_GOAL]->(g)
            }
            CALL (a) {
              MATCH (sg:Goal {id: $subGoalId})
              MERGE (a)-[:UNDER_SUBGOAL]->(sg)
            }
            """)
                .bindAll(params)
                .run();
    }
}
