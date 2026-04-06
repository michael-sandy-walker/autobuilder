package com.walpro.autobuilder.graph.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@Transactional("transactionManager")
@RequiredArgsConstructor
public class OrchestrationSnapshotService {

    private static final int HEURISTIC_LOOKBACK = 10;
    private static final int HEURISTIC_STREAK_THRESHOLD = 2;

    private static final ObjectMapper OM = new ObjectMapper();

    private final Neo4jClient neo4j;

    public OrchestrationSnapshot fetchLatestSnapshot(String goalId, String subGoalId, int recentLimit) {
        String scopeGoalId = (subGoalId != null && !subGoalId.isBlank()) ? subGoalId : goalId;
        if (scopeGoalId == null || scopeGoalId.isBlank()) {
            return null;
        }

        String latestAttemptId = neo4j.query("""
            MATCH (a:Action)
            WHERE a.attemptId IS NOT NULL
              AND (a.targetsGoalId = $scopeGoalId OR a.targetsGoalId = $goalId)
            RETURN a.attemptId AS attemptId
            ORDER BY a.timestamp DESC
            LIMIT 1
            """)
                .bindAll(Map.of(
                        "scopeGoalId", scopeGoalId,
                        "goalId", goalId
                ))
                .fetch()
                .one()
                .map(row -> (String) row.get("attemptId"))
                .orElse(null);

        if (latestAttemptId == null || latestAttemptId.isBlank()) {
            return null;
        }

        return fetchSnapshot(goalId, subGoalId, latestAttemptId, recentLimit);
    }

    public OrchestrationSnapshot fetchSnapshot(String goalId, String subGoalId, String attemptId, int recentLimit) {
        if (attemptId == null) {
            return null;
        }

        String scopeGoalId = (subGoalId != null && !subGoalId.isBlank()) ? subGoalId : goalId;
        int limit = Math.max(1, recentLimit);

        OrchestrationSnapshot snapshot = neo4j.query("""
            // ---- Attempt (optional) ----
            OPTIONAL MATCH (ea:EvolutionAttempt {id: $attemptId})

            // ---- Latest evolution action for this attempt ----
            CALL (ea) {
              WITH $attemptId AS attemptId
              OPTIONAL MATCH (evo:Action {type:'evolution', attemptId: attemptId})
              RETURN evo AS lastEvolution
              ORDER BY lastEvolution.timestamp DESC
              LIMIT 1
            }

            // ---- Get evaluation artifact for lastEval (relationship type is unknown => match any) ----
            CALL (ea, lastEvolution) {
              WITH $attemptId AS attemptId
              OPTIONAL MATCH (ev:Action {type:'evaluation', attemptId: attemptId})
              RETURN ev AS lastEval
              ORDER BY lastEval.timestamp DESC
              LIMIT 1
            }

            // ---- Previous evaluation score in goal/subgoal scope (exclude lastEval) ----
            CALL (ea, lastEvolution, lastEval) {
              WITH lastEval
              OPTIONAL MATCH (lastEval)-[]->(eNow:Artifact {type:'evaluation_payload'})
              RETURN eNow.score AS evalScoreNow,
                     eNow.content       AS evalContentNow
              LIMIT 1
            }

            CALL (ea, lastEvolution, lastEval, evalScoreNow, evalContentNow) {
              WITH $scopeGoalId AS scopeGoalId, lastEval
              OPTIONAL MATCH (ev2:Action {type:'evaluation', targetsGoalId: scopeGoalId})-[]->(ePrev:Artifact {type:'evaluation_payload'})
              WHERE lastEval IS NULL OR ev2.id <> lastEval.id
              RETURN ePrev.score AS evalScoreBefore
              ORDER BY ev2.timestamp DESC
              LIMIT 1
            }

            WITH ea, lastEvolution, lastEval, evalScoreNow, evalContentNow, evalScoreBefore,
                 CASE
                   WHEN evalScoreNow IS NOT NULL AND evalScoreBefore IS NOT NULL
                   THEN (evalScoreNow - evalScoreBefore)
                   ELSE NULL
                 END AS evalDelta

            // ---- Latest merge action for this attempt ----
            CALL (ea, lastEvolution, lastEval, evalScoreNow, evalContentNow, evalScoreBefore, evalDelta) {
              WITH $attemptId AS attemptId
              OPTIONAL MATCH (ma:Action {type:'merge', attemptId: attemptId})
              RETURN ma AS lastMergeAction
              ORDER BY lastMergeAction.timestamp DESC
              LIMIT 1
            }
        
            // ---- Merge evidence via merge graph (fallback) ----
            CALL (ea, lastEvolution, lastEval, evalScoreNow, evalContentNow, evalScoreBefore, evalDelta, lastMergeAction) {
              WITH ea
              OPTIONAL MATCH (c:Cycle {id: ea.cycleId})-[:MERGED_INTO]->(m:Merge)
              RETURN (count(m) > 0) AS mergedViaGraph
            }
        
            // ---- Most recent scope assessment in subgoal/goal scope ----
            CALL (ea, lastEvolution, lastEval, evalScoreNow, evalContentNow, evalScoreBefore, evalDelta, lastMergeAction, mergedViaGraph) {
              WITH $scopeGoalId AS scopeGoalId
              OPTIONAL MATCH (sc:Action {type:'scope', targetsGoalId: scopeGoalId})-[]->(sp:Artifact {type:'scope_payload'})
              RETURN sc AS lastScopeAction, sp AS lastScopeArtifact
              ORDER BY lastScopeAction.timestamp DESC
              LIMIT 1
            }

            // ---- Recent actions in scope (goal/subgoal) ----
            CALL (ea, lastEvolution, lastEval, evalScoreNow, evalContentNow, evalScoreBefore, evalDelta, lastMergeAction, mergedViaGraph, lastScopeAction, lastScopeArtifact) {
              WITH $attemptId AS attemptId, $scopeGoalId AS scopeGoalId
              OPTIONAL MATCH (ra:Action)
              WHERE ra.attemptId = attemptId OR ra.targetsGoalId = scopeGoalId
              ORDER BY ra.timestamp DESC
              RETURN collect(ra)[0..$limit] AS recent
            }

            RETURN
              ea.id AS attemptId,
              ea.cycleId AS attemptCycleId,
              ea.changedFilesCount AS changedFilesCount,
              ea.deletedFilesCount AS deletedFilesCount,
              ea.fingerprint AS fingerprint,

              // evolution evidence (may be null)
              lastEvolution.id AS evoActionId,
              lastEvolution.status AS evoStatus,
              toString(lastEvolution.timestamp) AS evoTs,

              // evaluation evidence (may be null)
              lastEval.id AS evalActionId,
              lastEval.status AS evalStatus,
              evalScoreNow AS evalScoreNow,
              evalScoreBefore AS evalScoreBefore,
              evalDelta AS evalDelta,
              evalContentNow AS evalContentNow,
              toString(lastEval.timestamp) AS evalTs,

              // merge evidence (may be null)
              lastMergeAction.id AS mergeActionId,
              lastMergeAction.status AS mergeStatus,
              toString(lastMergeAction.timestamp) AS mergeTs,
              mergedViaGraph AS mergedViaGraph,

              // scope assessment evidence (may be null)
              lastScopeAction.id AS scopeActionId,
              lastScopeAction.status AS scopeStatus,
              toString(lastScopeAction.timestamp) AS scopeTs,
              lastScopeArtifact.content AS scopePayload,

              // recent actions (list of maps)
              [x IN recent | {
                id: x.id,
                type: x.type,
                status: x.status,
                cycleId: x.occurredInCycle,
                attemptId: x.attemptId,
                ts: toString(x.timestamp)
              }] AS recentActions
            """)
                .bindAll(Map.of(
                        "attemptId", attemptId,
                        "scopeGoalId", scopeGoalId,
                        "limit", limit
                ))
                .fetch()
                .one()
                .map(row -> OrchestrationSnapshot.fromRow(goalId, subGoalId, row))
                .orElseGet(() -> OrchestrationSnapshot.empty(goalId, subGoalId, attemptId));

        List<OrchestrationSnapshot.HeuristicWarning> warnings =
                fetchRecurringCriticalHeuristics(scopeGoalId, HEURISTIC_LOOKBACK);
        return snapshot.withRecurringCriticalHeuristics(warnings);
    }

    /**
     * T2-AW: Queries the last {@code evalLimit} evaluation artifact payloads for
     * {@code scopeGoalId}, parses the {@code heuristicOpinions} JSON array from each,
     * and returns heuristics that fired at CRITICAL confidence on ≥ {@value #HEURISTIC_STREAK_THRESHOLD}
     * of those payloads. This extends T2-AT's in-memory streak counter across restarts.
     */
    List<OrchestrationSnapshot.HeuristicWarning> fetchRecurringCriticalHeuristics(
            String scopeGoalId, int evalLimit) {
        List<String> payloads = neo4j.query("""
                MATCH (ev:Action {type:'evaluation', targetsGoalId: $scopeGoalId})
                      -[]->(art:Artifact {type:'evaluation_payload'})
                RETURN art.content AS content
                ORDER BY ev.timestamp DESC
                LIMIT $limit
                """)
                .bindAll(Map.of("scopeGoalId", scopeGoalId, "limit", evalLimit))
                .fetch()
                .all()
                .stream()
                .map(row -> (String) row.get("content"))
                .filter(c -> c != null && !c.isBlank())
                .toList();

        // tally CRITICAL hits per heuristic name; remember last rationale
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, String> lastRationale = new LinkedHashMap<>();

        for (String payload : payloads) {
            List<Map<String, Object>> opinions = parseOpinions(payload);
            for (Map<String, Object> op : opinions) {
                Object critical = op.get("critical");
                if (!Boolean.TRUE.equals(critical)) continue;
                String name = (String) op.get("name");
                if (name == null) continue;
                counts.computeIfAbsent(name, k -> new int[]{0})[0]++;
                lastRationale.put(name, (String) op.getOrDefault("rationale", null));
            }
        }

        List<OrchestrationSnapshot.HeuristicWarning> warnings = new ArrayList<>();
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            if (e.getValue()[0] >= HEURISTIC_STREAK_THRESHOLD) {
                warnings.add(new OrchestrationSnapshot.HeuristicWarning(
                        e.getKey(), e.getValue()[0], lastRationale.get(e.getKey())));
            }
        }
        return warnings;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseOpinions(String payload) {
        try {
            Map<String, Object> map = OM.readValue(payload, new TypeReference<>() {});
            Object opinions = map.get("heuristicOpinions");
            if (opinions instanceof List<?> list) {
                return (List<Map<String, Object>>) list;
            }
        } catch (Exception e) {
            log.debug("Failed to parse heuristicOpinions from payload: {}", e.getMessage());
        }
        return List.of();
    }
}
