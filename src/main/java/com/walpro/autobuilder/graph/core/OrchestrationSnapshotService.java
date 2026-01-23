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
            OPTIONAL MATCH (evo:Action {type: 'evolution', targetAttemptId: $attemptId})
            WITH ea, evo
            ORDER BY evo.timestamp DESC
            WITH ea, collect(evo)[0] AS lastEvolution

            // ---- Latest evaluation action for this attempt ----
            OPTIONAL MATCH (ev:Action {type: 'evaluation', targetAttemptId: $attemptId})
            WITH ea, lastEvolution, ev
            ORDER BY ev.timestamp DESC
            WITH ea, lastEvolution, collect(ev)[0] AS lastEval
            
            OPTIONAL MATCH (lastEval)-[:OUTPUT]->(eart:Artifact {type:'evaluation_summary'})
            WITH ea, lastEvolution, lastEval, eart

            // ---- Latest merge action for this attempt ----
            OPTIONAL MATCH (ma:Action {type: 'merge', targetAttemptId: $attemptId})
            WITH ea, lastEvolution, lastEval, eart, ma
            ORDER BY ma.timestamp DESC
            WITH ea, lastEvolution, lastEval, eart, collect(ma)[0] AS lastMergeAction
        
            // ---- Merge evidence via merge graph (fallback) ----
            OPTIONAL MATCH (c:Cycle {id: ea.cycleId})-[:MERGED_INTO]->(m:Merge)
            WITH ea, lastEvolution, lastEval, eart, lastMergeAction, (count(m) > 0) AS mergedViaGraph
        
            // ---- Recent actions in scope (goal/subgoal) ----
            OPTIONAL MATCH (ra:Action)
            WHERE ra.targetAttemptId = $attemptId OR ra.targetsGoalId = $scopeGoalId
            WITH ea, lastEvolution, lastEval, eart, lastMergeAction, mergedViaGraph, ra
            ORDER BY ra.timestamp DESC
            WITH ea, lastEvolution, lastEval, eart, lastMergeAction, mergedViaGraph,
                 collect(ra)[0..$limit] AS recent

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
              eart.score AS evalScore,
              eart.content AS evalJson,
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
                attemptId: x.targetAttemptId,
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
