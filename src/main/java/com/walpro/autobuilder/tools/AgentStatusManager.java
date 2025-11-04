package com.walpro.autobuilder.tools;

import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.graph.core.AgentEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentStatusManager {

    private final AgentGraphService agentGraphService;

    public void printAgentsByStatus() {
        List<AgentEntity> allAgents = agentGraphService.getAllAgents();

        var grouped = allAgents.stream()
            .collect(Collectors.groupingBy(AgentEntity::status));

        grouped.forEach((status, agents) -> {
            log.info("🔎 Agents with status '{}':", status);
            for (AgentEntity agent : agents) {
                log.info("- {} ({}, lineage: {})", agent.name(), agent.roleType(), agent.lineagePath());
            }
        });
    }

    public void toggleAgentStatusByName(String name) {
        List<AgentEntity> matching = agentGraphService.getAllAgents().stream()
            .filter(a -> a.name().equalsIgnoreCase(name))
            .collect(Collectors.toList());

        if (matching.isEmpty()) {
            log.warn("❌ No agent found with name '{}'", name);
            return;
        }

        for (AgentEntity agent : matching) {
            String newStatus = "active".equalsIgnoreCase(agent.status()) ? "inactive" : "active";
            agentGraphService.updateAgentStatus(agent.id(), newStatus);
            log.info("🔄 Toggled agent '{}' → {}", agent.name(), newStatus);
        }
    }
}
