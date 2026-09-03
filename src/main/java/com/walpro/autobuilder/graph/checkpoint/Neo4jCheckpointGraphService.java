package com.walpro.autobuilder.graph.checkpoint;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.checkpoint.CheckpointRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * T4-DQ: Neo4j adapter for the cross-run checkpoint index.
 *
 * <p>One {@code (:Checkpoint)} node per point, carrying everything the record needs to be rebuilt
 * plus the archive path of its source-only tree copy. Append-only and never purged by a reset,
 * exactly like the goal-status transitions it sits beside.
 *
 * <p>The captured holder state is stored as a JSON string rather than as properties: it is an
 * opaque per-holder payload the graph has no business modelling, and the manifest is free to
 * change its shape without a schema migration. The fingerprint is stored the same way and read
 * leniently for the same reason a checkpoint file is — a check that cannot be parsed must not cost
 * the point it was checking.
 */
@Slf4j
@Service
@Transactional("transactionManager")
@RequiredArgsConstructor
public class Neo4jCheckpointGraphService implements CheckpointGraphService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final Neo4jClient neo4jClient;
    private final ObjectMapper objectMapper;

    @Override
    public void record(CheckpointRecord record, String archivePath) {
        if (record == null || record.cycleId() == null) return;
        try {
            neo4jClient.query("""
                    MERGE (c:Checkpoint {cycleId: $cycleId})
                    SET c.runId = $runId,
                        c.lineageId = $lineageId,
                        c.at = $at,
                        c.merged = $merged,
                        c.complete = $complete,
                        c.rootGoalId = $rootGoalId,
                        c.currentSubGoalId = $currentSubGoalId,
                        c.goalTransitionSeq = $goalTransitionSeq,
                        c.snapshotPath = $snapshotPath,
                        c.mutationMetaPath = $mutationMetaPath,
                        c.archivePath = $archivePath,
                        c.cacheCaptured = $cacheCaptured,
                        c.holdersJson = $holdersJson,
                        c.fingerprintJson = $fingerprintJson
                    """)
                    .bind(record.cycleId()).to("cycleId")
                    .bind(record.runId()).to("runId")
                    .bind(record.lineageId()).to("lineageId")
                    .bind(record.at() != null ? record.at().toString() : null).to("at")
                    .bind(record.merged()).to("merged")
                    .bind(record.complete()).to("complete")
                    .bind(record.rootGoalId()).to("rootGoalId")
                    .bind(record.currentSubGoalId()).to("currentSubGoalId")
                    .bind(record.goalTransitionSeq()).to("goalTransitionSeq")
                    .bind(record.snapshotPath()).to("snapshotPath")
                    .bind(record.mutationMetaPath()).to("mutationMetaPath")
                    .bind(archivePath).to("archivePath")
                    .bind(record.cacheCaptured()).to("cacheCaptured")
                    .bind(writeJson(record.holders())).to("holdersJson")
                    .bind(writeJson(record.fingerprint())).to("fingerprintJson")
                    .run();
        } catch (Exception e) {
            log.warn("T4-DQ: could not index checkpoint {} in the graph (the local point is "
                    + "unaffected): {}", record.cycleId(), e.getMessage());
        }
    }

    @Override
    public List<StoredCheckpoint> forLineage(String lineageId, int limit) {
        try {
            return neo4jClient.query("""
                    MATCH (c:Checkpoint)
                    WHERE $lineageId IS NULL OR c.lineageId = $lineageId
                    RETURN c ORDER BY c.cycleId DESC LIMIT $limit
                    """)
                    .bindAll(everyLineageWhenNull(lineageId, limit))
                    .fetch().all().stream()
                    .map(row -> toStored(row.get("c")))
                    .filter(java.util.Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            log.warn("T4-DQ: could not read checkpoints from the graph: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public Optional<StoredCheckpoint> byCycleId(String cycleId) {
        if (cycleId == null || cycleId.isBlank()) return Optional.empty();
        try {
            return neo4jClient.query("MATCH (c:Checkpoint {cycleId: $cycleId}) RETURN c")
                    .bind(cycleId).to("cycleId")
                    .fetch().one()
                    .map(row -> toStored(row.get("c")));
        } catch (Exception e) {
            log.warn("T4-DQ: could not read checkpoint {} from the graph: {}", cycleId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<StoredRun> runsWithCheckpoints(String lineageId, int limit) {
        try {
            List<StoredRun> runs = new ArrayList<>();
            neo4jClient.query("""
                    MATCH (c:Checkpoint)
                    WHERE $lineageId IS NULL OR c.lineageId = $lineageId
                    RETURN c.runId AS runId, c.lineageId AS lineageId, count(*) AS n,
                           min(c.cycleId) AS firstCycleId, max(c.cycleId) AS lastCycleId,
                           max(c.at) AS lastAt
                    ORDER BY lastCycleId DESC LIMIT $limit
                    """)
                    .bindAll(everyLineageWhenNull(lineageId, limit))
                    .fetch().all()
                    .forEach(row -> runs.add(new StoredRun(
                            (String) row.get("runId"),
                            (String) row.get("lineageId"),
                            ((Number) row.getOrDefault("n", 0)).intValue(),
                            (String) row.get("firstCycleId"),
                            (String) row.get("lastCycleId"),
                            parseInstant((String) row.get("lastAt")))));
            return runs;
        } catch (Exception e) {
            log.warn("T4-DQ: could not read checkpoint runs from the graph: {}", e.getMessage());
            return List.of();
        }
    }

    private StoredCheckpoint toStored(Object node) {
        if (!(node instanceof org.neo4j.driver.types.Node c)) return null;
        try {
            Map<String, Object> holders = readMap(str(c, "holdersJson"));
            com.walpro.autobuilder.core.checkpoint.StateFingerprint fingerprint = null;
            String fp = str(c, "fingerprintJson");
            if (fp != null && !fp.isBlank()) {
                try {
                    fingerprint = objectMapper.readValue(fp,
                            com.walpro.autobuilder.core.checkpoint.StateFingerprint.class);
                } catch (Exception ignored) {
                    // A fingerprint this build cannot read costs the CHECK, never the point.
                    fingerprint = null;
                }
            }
            CheckpointRecord record = new CheckpointRecord(
                    str(c, "cycleId"), parseInstant(str(c, "at")), str(c, "runId"), str(c, "lineageId"),
                    str(c, "rootGoalId"), str(c, "currentSubGoalId"),
                    c.containsKey("goalTransitionSeq") ? c.get("goalTransitionSeq").asLong(0L) : 0L,
                    str(c, "snapshotPath"), str(c, "mutationMetaPath"),
                    c.containsKey("merged") && c.get("merged").asBoolean(false),
                    c.containsKey("cacheCaptured") && c.get("cacheCaptured").asBoolean(false),
                    holders, List.of(), fingerprint);
            return new StoredCheckpoint(record, str(c, "archivePath"));
        } catch (Exception e) {
            log.debug("T4-DQ: unreadable checkpoint node: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parameters for the two lineage-scoped reads, where a null lineage legitimately means EVERY
     * lineage — both queries have always said {@code $lineageId IS NULL OR ...}, and the operator
     * listing is the first caller to use it, so that a reset does not hide the points the archive
     * deliberately kept. Bound through a map because a map tolerates a null value; the fluent
     * {@code bind(null)} leaves that to the driver.
     */
    private static java.util.Map<String, Object> everyLineageWhenNull(String lineageId, int limit) {
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("lineageId", lineageId);
        params.put("limit", Math.max(1, limit));
        return params;
    }

    private static String str(org.neo4j.driver.types.Node n, String key) {
        return n.containsKey(key) ? n.get(key).asString(null) : null;
    }

    private static Instant parseInstant(String s) {
        try {
            return s == null ? null : Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String writeJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }
}
