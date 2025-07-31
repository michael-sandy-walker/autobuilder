package com.walpro.autobuilder.agent.meta.graph;

import java.util.List;

import com.walpro.autobuilder.graph.model.AgentEntity;

public interface AgentGraphService {
    void registerAgent(AgentEntity agent);
    void linkToSubgoal(String agentId, String goalId);
    void linkSpawn(String parentAgentId, String spawnedAgentId);
    List<AgentEntity> getAllAgents();
    void updateAgentStatus(String agentId, String status);
    List<AgentEntity> getActiveUnassignedAgents();

}
