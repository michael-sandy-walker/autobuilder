package com.walpro.autobuilder.graph.input;

import com.walpro.autobuilder.core.init.RunIdentityService;
import com.walpro.autobuilder.goal.purpose.InputEntry;
import com.walpro.autobuilder.graph.HistoryRead;
import com.walpro.autobuilder.graph.HistorySpan;
import com.walpro.autobuilder.graph.HistoryUse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * T4-EC slice 1: {@code :Input} nodes. Stamped with the lineage like every other node, so a lineage bundle carries
 * them and a reset leaves the old lineage's inputs as history.
 */
@Transactional("transactionManager")
@Service
@RequiredArgsConstructor
public class Neo4jInputGraphService implements InputGraphService {

    private final Neo4jClient neo4jClient;
    private final RunIdentityService runIdentity;

    @Override
    public void save(InputEntry e) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", e.id());
        p.put("sequence", e.sequence());
        p.put("kind", e.kind());
        p.put("content", e.content());
        p.put("sourceDigest", e.sourceDigest());
        p.put("addedBy", e.addedBy());
        p.put("addedAt", e.addedAt() == null ? null : e.addedAt().toString());
        p.put("lineageId", e.lineageId() != null ? e.lineageId() : runIdentity.lineageId());
        p.put("runId", e.runId() != null ? e.runId() : runIdentity.runId());
        p.put("sourceRunId", e.sourceRunId());
        p.put("sourceLineageId", e.sourceLineageId());
        neo4jClient.query("""
                MERGE (i:Input {id: $id})
                ON CREATE SET
                    i.sequence = $sequence,
                    i.kind = $kind,
                    i.content = $content,
                    i.sourceDigest = $sourceDigest,
                    i.addedBy = $addedBy,
                    i.addedAt = $addedAt,
                    i.lineageId = $lineageId,
                    i.createdInRunId = $runId,
                    i.sourceRunId = $sourceRunId,
                    i.sourceLineageId = $sourceLineageId
                SET i.runId = $runId
                """)
                .bindAll(p)
                .run();
    }

    @Override
    public void link(String inputId, String goalId, String sourceRootGoalId) {
        Map<String, Object> p = new HashMap<>();
        p.put("inputId", inputId);
        p.put("goalId", goalId);
        p.put("sourceRootGoalId", sourceRootGoalId);
        neo4jClient.query("""
                MATCH (i:Input {id: $inputId})
                MATCH (g:Goal {id: $goalId})
                MERGE (i)-[:LANDED_AS]->(g)
                WITH i
                OPTIONAL MATCH (s:Goal {id: $sourceRootGoalId})
                FOREACH (x IN CASE WHEN s IS NULL THEN [] ELSE [s] END | MERGE (i)-[:CAME_FROM]->(x))
                """)
                .bindAll(p)
                .run();
    }

    @HistoryRead(span = HistorySpan.LINEAGE, use = HistoryUse.BINDING,
            note = "The project's inputs loaded at boot; the purpose every reader works from is read from them.")
    @Override
    public List<InputEntry> findAll() {
        return neo4jClient.query("""
                MATCH (i:Input)
                WHERE i.lineageId = $lineageId
                RETURN i.id AS id, i.sequence AS sequence, i.kind AS kind, i.content AS content,
                       i.sourceDigest AS sourceDigest, i.addedBy AS addedBy, i.addedAt AS addedAt,
                       i.createdInRunId AS runId, i.lineageId AS lineageId,
                       i.sourceRunId AS sourceRunId, i.sourceLineageId AS sourceLineageId
                ORDER BY i.sequence
                """)
                .bind(runIdentity.lineageId()).to("lineageId")
                .fetch()
                .all()
                .stream()
                .map(r -> new InputEntry((String) r.get("id"), ((Number) r.get("sequence")).intValue(),
                        (String) r.get("kind"), (String) r.get("content"), (String) r.get("sourceDigest"),
                        (String) r.get("addedBy"), r.get("addedAt") == null ? null : Instant.parse((String) r.get("addedAt")),
                        (String) r.get("runId"), (String) r.get("lineageId"),
                        (String) r.get("sourceRunId"), (String) r.get("sourceLineageId")))
                .toList();
    }
}
