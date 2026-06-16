package com.walpro.autobuilder.graph.agent;

import java.time.Instant;
import java.util.List;

import com.walpro.autobuilder.graph.core.ActionEntity;
import com.walpro.autobuilder.graph.core.AgentEntity;
import com.walpro.autobuilder.graph.core.ArtifactEntity;
import com.walpro.autobuilder.graph.evaluation.CriterionResultEntity;
import com.walpro.autobuilder.graph.planner.PlannerActionSnapshot;
import com.walpro.autobuilder.graph.planner.PlannerInflowStats;
import com.walpro.autobuilder.graph.scope.ScopeActionSnapshot;

public interface AgentGraphService {
    void registerAgent(AgentEntity agent);

    void linkToSubgoal(String agentId, String goalId);

    void linkSpawn(String parentAgentId, String spawnedAgentId);

    List<AgentEntity> getAllAgents();

    void updateAgentStatus(String agentId, String status);

    List<AgentEntity> getActiveUnassignedAgents();

    void registerAction(ActionEntity action);

    void linkActionOutput(String actionId, ArtifactEntity artifact);

    List<AgentEntity> getAgentsByRoleAndGoal(String roleType, String goalId);

    long countAllAgents();

    long countActiveAgents();

    long countActiveUnassignedAgents();

    long countActiveAgentsByRoleAndGoal(String roleType, String goalId);

    long pruneInactiveUnassignedAgents(int limit);

    /**
     * Used by InterpretationAgent to read the last N relevant summaries for the active goal.
     */
    List<ArtifactEntity> findRecentArtifactsForGoal(String goalId, List<String> artifactTypes, int limit);

    void attachPlannerMetadata(String actionId, String fingerprint, boolean updated, int newGoalsCount);

    List<PlannerActionSnapshot> findRecentPlannerActions(String scopeGoalId, int limit);

    PlannerInflowStats getPlannerInflowSince(String scopeGoalId, Instant since);

    List<ScopeActionSnapshot> findRecentScopeActions(String subGoalId, int limit);

    /**
     * T3-BB: Persists each {@link CriterionResultEntity} as a {@code CriterionResult} node
     * in Neo4j linked from its parent evaluation action via {@code HAS_CRITERION}.
     */
    void linkCriterionResults(List<CriterionResultEntity> results);

    /**
     * T4-BR: completion-verification verdict cache, graph-backed. Returns the recorded pass/fail
     * for each of {@code criterionKeys} that already has a {@code CriterionResult} at the given
     * {@code codeDigest} (criterionKey &rarr; passed). Keys with no verdict at that code state are
     * absent from the map — the caller LLM-verifies only those. Empty on no input or no matches.
     */
    java.util.Map<String, Boolean> findCriterionVerdicts(java.util.Collection<String> criterionKeys, String codeDigest);

    /**
     * T4-BR: persist completion-verification verdicts as the graph-backed cache read by
     * {@link #findCriterionVerdicts}. Each node is MERGEd on {@code (criterionKey, codeDigest)} —
     * one verdict per criterion per code state — replacing the former in-memory map. Records with
     * a blank {@code codeDigest} are skipped (they belong to the action-linked #NoDiff path).
     */
    void recordCompletionVerdicts(List<CriterionResultEntity> verdicts);

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

