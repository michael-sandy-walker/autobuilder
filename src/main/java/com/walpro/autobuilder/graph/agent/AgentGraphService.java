package com.walpro.autobuilder.graph.agent;

import java.util.List;

import com.walpro.autobuilder.graph.core.ActionEntity;
import com.walpro.autobuilder.graph.core.ArtifactEntity;
import com.walpro.autobuilder.graph.evaluation.CriterionResultEntity;
import com.walpro.autobuilder.graph.scope.ScopeActionSnapshot;

public interface AgentGraphService {

    void updateAgentStatus(String agentId, String status);

    void registerAction(ActionEntity action);

    void linkActionOutput(String actionId, ArtifactEntity artifact);

    /**
     * Used by InterpretationAgent to read the last N relevant summaries for the active goal.
     */
    List<ArtifactEntity> findRecentArtifactsForGoal(String goalId, List<String> artifactTypes, int limit);

    void attachPlannerMetadata(String actionId, String fingerprint, boolean updated, int newGoalsCount);

    List<ScopeActionSnapshot> findRecentScopeActions(String subGoalId, int limit);

    /**
     * T3-BB: Persists each {@link CriterionResultEntity} as a {@code CriterionResult} node
     * in Neo4j linked from its parent evaluation action via {@code HAS_CRITERION}.
     */
    void linkCriterionResults(List<CriterionResultEntity> results);

    /**
     * T4-BR: completion-verification result cache, graph-backed. Returns the recorded pass/fail
     * for each of {@code criterionKeys} that already has a {@code CriterionResult} at the given
     * {@code codeDigest} (criterionKey &rarr; passed). Keys with no answer at that code state are
     * absent from the map — the caller LLM-verifies only those. Empty on no input or no matches.
     */
    java.util.Map<String, Boolean> findCriterionResults(java.util.Collection<String> criterionKeys, String codeDigest);

    /**
     * T4-BR: persist completion-verification results as the graph-backed cache read by
     * {@link #findCriterionResults}. Each node is MERGEd on {@code (criterionKey, codeDigest)} —
     * one result per criterion per code state — replacing the former in-memory map. Records with
     * a blank {@code codeDigest} are skipped (they belong to the action-linked #NoDiff path).
     */
    void recordCompletionResults(List<CriterionResultEntity> results);

    /**
     * Returns the status of the most recent evaluation action recorded for the given subgoal,
     * or {@code null} when no evaluation has been registered yet.
     */
    String findLatestEvaluationStatusForSubgoal(String subGoalId);

    /**
     * T2-CE: Returns the score of the most recent ACCEPTED evaluation for the given subgoal,
     * or empty if no accepted evaluation has been recorded yet.
     * Used as the within-subgoal regression baseline by {@code ScoreRegressionHeuristic} and
     * {@code ProblemReductionHeuristic} to avoid cross-subgoal score contamination.
     */
    java.util.Optional<Double> findLatestAcceptedScoreForSubgoal(String subGoalId);

    /**
     * T2-CE: Returns up to {@code limit} accepted evaluation scores for the given subgoal,
     * ordered newest-first. Used for trend analysis in meta-deliberation.
     */
    java.util.List<Double> findScoreHistoryForSubgoal(String subGoalId, int limit);

}

