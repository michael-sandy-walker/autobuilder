package com.walpro.autobuilder.graph.agent;

import java.time.Instant;
import java.util.List;

import com.walpro.autobuilder.graph.core.ActionEntity;
import com.walpro.autobuilder.graph.core.AgentEntity;
import com.walpro.autobuilder.graph.core.ArtifactEntity;
import com.walpro.autobuilder.graph.planner.PlannerActionSnapshot;
import com.walpro.autobuilder.graph.planner.PlannerInflowStats;

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
}

