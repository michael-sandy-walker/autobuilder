package com.walpro.autobuilder.protocol.meta;

import com.walpro.autobuilder.graph.core.AgentEntity;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.protocol.core.ContractRule;
import com.walpro.autobuilder.protocol.core.ProtocolUtils;

import java.util.List;

public class MetaSpawnAgentProtocolContract {

    public static final String VERSION = "1.0-meta";

    private static final List<ContractRule> RULES = List.of(
        new ContractRule(
            "SPAWN_AGENT",
            "Return a new agent proposal if any role appears missing or too weak for current goal progress.",
            """
            ```yaml
            name: ScoreOptimizer
            roleType: evaluation
            goalId: G-9876
            ```
            """
        ),
        new ContractRule(
            "FORMAT",
            "Use YAML format including 'name' and 'roleType'. 'goalId' is optional.",
            """
            ```yaml
            name: PlannerAlpha
            roleType: planner
            ```
            """
        )
    );

    public static List<ContractRule> rules() {
        return RULES;
    }

    public static String getSystemPrompt() {
        return ProtocolUtils.formatRulesBlock("MetaAgent Protocol v" + VERSION, rules());
    }
    
    public static String getUserPrompt(
            CycleContextData context,
            MetaSpawnAgentPromptBuilder.MetaPromptMode mode,
            List<AgentEntity> existingAgents
    ) {
        return MetaSpawnAgentPromptBuilder.buildUserPrompt(context, mode, existingAgents);
    }


    public static boolean isValidResponse(String content) {
        return content.contains("name:") && content.contains("roleType:");
    }
}
