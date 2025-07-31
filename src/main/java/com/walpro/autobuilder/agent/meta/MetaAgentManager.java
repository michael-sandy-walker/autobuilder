package com.walpro.autobuilder.agent.meta;

import com.walpro.autobuilder.graph.model.AgentEntity;
import com.walpro.autobuilder.agent.meta.graph.AgentGraphService;
import com.walpro.autobuilder.agent.meta.graph.util.AgentLineageUtil;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class MetaAgentManager {

    private final AgentGraphService agentGraphService;

    public AgentEntity spawnAgent(
        String parentLineagePath,
        String name,
        String roleType,
        String spawnedInCycle
    ) {
        String lineage = AgentLineageUtil.extendPath(parentLineagePath, name);
        String id = UUID.randomUUID().toString();

        AgentEntity agent = new AgentEntity(id, name, lineage, roleType, spawnedInCycle, "active");
        agentGraphService.registerAgent(agent);

        log.info("🧬 Spawned new agent: {} (lineage: {})", name, lineage);
        return agent;
    }

    public void linkSpawn(String parentId, String childId) {
        agentGraphService.linkSpawn(parentId, childId);
    }

    public void assignToGoal(String agentId, String goalId) {
        agentGraphService.linkToSubgoal(agentId, goalId);
    }
}
