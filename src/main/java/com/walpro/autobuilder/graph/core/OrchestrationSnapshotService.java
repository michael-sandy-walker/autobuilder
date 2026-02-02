package com.walpro.autobuilder.graph.core;

import lombok.RequiredArgsConstructor;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@Transactional("transactionManager")
@RequiredArgsConstructor
public class OrchestrationSnapshotService {

    private final Neo4jClient neo4j;

    public OrchestrationSnapshot fetchSnapshot(String goalId, String subGoalId, String attemptId, int recentLimit) {
        if (attemptId == null) {
            return null;
        }

        String scopeGoalId = (subGoalId != null && !subGoalId.isBlank()) ? subGoalId : goalId;
        int limit = Math.max(1, recentLimit);

        return neo4j.query("""
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
        
            // ---- Recent actions in scope (goal/subgoal) ----
            CALL (ea, lastEvolution, lastEval, evalScoreNow, evalContentNow, evalScoreBefore, evalDelta, lastMergeAction, mergedViaGraph) {
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
    }
}
