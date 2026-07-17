package com.walpro.autobuilder.graph.diagnostic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@Transactional("transactionManager")
@RequiredArgsConstructor
public class Neo4jDiagnosticGraphService {

    private final Neo4jClient neo4jClient;
    private final ObjectMapper objectMapper;
    private final com.walpro.autobuilder.core.init.RunIdentityService runIdentity;

    /**
     * Persists a scan + its matches to Neo4j.
     *
     * Nodes:
     *  - (:DiagnosticScan {id})
     *  - (:DiagnosticFinding {id})
     *  - (:Diagnostic {id})  // the rule node already exists (enabled diagnostics)
     *
     * Relationships:
     *  - (scan)-[:FOR_ATTEMPT]->(EvolutionAttempt)
     *  - (scan)-[:ABOUT_GOAL]->(Goal {id: scopeGoalId})
     *  - (scan)-[:HAS_FINDING]->(finding)
     *  - (finding)-[:FROM_DIAGNOSTIC]->(Diagnostic)
     */
    public String persistScan(String goalId, String subGoalId, String attemptId, int limit, List<DiagnosticMatch> matches) {
        if (attemptId == null || attemptId.isBlank()) return null;

        String scopeGoalId = (subGoalId != null && !subGoalId.isBlank()) ? subGoalId : goalId;
        String created = Instant.now().toString();

        // One scan node per call (unique id)
        String scanId = "diagscan::" + attemptId + "::" + created;

        neo4jClient.query("""
            MERGE (s:DiagnosticScan {id:$scanId})
            SET s.attemptId = $attemptId,
                s.scopeGoalId = $scopeGoalId,
                s.goalId = $goalId,
                s.subGoalId = $subGoalId,
                s.limit = $limit,
                s.created = $created,
                s.lineageId = $lineageId,
                s.runId = $runId
            WITH s
            OPTIONAL MATCH (a:EvolutionAttempt {id:$attemptId})
            FOREACH (_ IN CASE WHEN a IS NULL THEN [] ELSE [1] END |
              MERGE (s)-[:FOR_ATTEMPT]->(a)
            )
            WITH s
            FOREACH (_ IN CASE WHEN $scopeGoalId IS NULL OR $scopeGoalId = '' THEN [] ELSE [1] END |
              MERGE (g:Goal {id:$scopeGoalId})
              MERGE (s)-[:ABOUT_GOAL]->(g)
            )
            """)
                .bindAll(Map.of(
                        "scanId", scanId,
                        "attemptId", attemptId,
                        "scopeGoalId", scopeGoalId,
                        "goalId", goalId,
                        "subGoalId", subGoalId,
                        "limit", limit,
                        "created", created,
                        "lineageId", runIdentity.lineageId(),
                        "runId", runIdentity.runId()
                ))
                .run();

        if (matches == null || matches.isEmpty()) {
            return scanId;
        }

        for (DiagnosticMatch m : matches) {
            if (m == null) continue;

            String diagnosticId = m.diagnosticId();
            if (diagnosticId == null || diagnosticId.isBlank()) continue;

            String observationNorm = normalizeObservation(m.observation());
            String fp = sha1(diagnosticId + "::" + scopeGoalId + "::" + observationNorm);

            // Deterministic within scan (prevents duplicate findings inside same scan)
            String findingId = scanId + "::" + diagnosticId + "::" + fp;

            neo4jClient.query("""
                MATCH (s:DiagnosticScan {id:$scanId})
                MERGE (f:DiagnosticFinding {id:$findingId})
                SET f.diagnosticId = $diagnosticId,
                    f.name = $name,
                    f.severity = $severity,
                    f.score = $score,
                    f.observation = $observation,
                    f.observationFp = $observationFp,
                    f.meta = $metaJson,
                    f.created = $created,
                    f.lineageId = $lineageId,
                    f.runId = $runId
                MERGE (s)-[:HAS_FINDING]->(f)
                WITH f
                MERGE (d:Diagnostic {id:$diagnosticId})
                MERGE (f)-[:FROM_DIAGNOSTIC]->(d)
                """)
                    .bindAll(new java.util.HashMap<>() {{
                            put("scanId", scanId);
                            put("findingId", findingId);
                            put("diagnosticId", diagnosticId);
                            put("name", m.name());
                            put("severity", m.severity());
                            put("score", m.score());
                            put("observation", m.observation());
                            put("observationFp", fp);
                            put("metaJson", toJsonSafe(m.meta()));
                            put("created", created);
                            put("lineageId", runIdentity.lineageId());
                            put("runId", runIdentity.runId());
                    }})
                    .run();
        }

        return scanId;
    }

    private String toJsonSafe(Map<String, Object> meta) {
        try {
            return objectMapper.writeValueAsString(meta != null ? meta : Map.of());
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize diagnostic meta: {}", e.getMessage());
            return "{}";
        }
    }

    private static String normalizeObservation(String s) {
        if (s == null) return "";
        return s.replace("\r", " ")
                .replace("\n", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (Exception e) {
            return Integer.toHexString(Objects.hashCode(s));
        }
    }
}