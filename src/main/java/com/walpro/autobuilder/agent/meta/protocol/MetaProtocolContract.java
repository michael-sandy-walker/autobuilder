package com.walpro.autobuilder.agent.meta.protocol;

import com.walpro.autobuilder.graph.model.AgentEntity;
import com.walpro.autobuilder.lifecycle.CycleContextData;
import com.walpro.autobuilder.protocol.ContractRule;
import com.walpro.autobuilder.protocol.ProtocolUtils;

import java.util.List;

public class MetaProtocolContract {

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

//    public static String getUserPrompt(CycleContextData context) {
//        return """
//            The system is preparing to evaluate whether a new agent should be spawned.
//
//            - Current cycle: %s
//            - Active goal: %s
//            - Known issues: %s
//
//            Based on the current structure and agent coverage, decide whether a new agent is needed.
//
//            If yes, respond in YAML:
//            ```yaml
//            name: <AgentName>
//            roleType: <evaluation|evolution|planner|merge|meta>
//            goalId: <optional>
//            ```
//            """.formatted(
//                context.cycleName(),
//                context.currentGoalDescription(),
//                "N/A" // ← You can later inject missing-coverage or heuristics summary here
//            );
//    }
    
    public static String getUserPrompt(
            CycleContextData context,
            MetaPromptBuilder.MetaPromptMode mode,
            List<AgentEntity> existingAgents
    ) {
        return MetaPromptBuilder.buildUserPrompt(context, mode, existingAgents);
    }


    public static boolean isValidResponse(String content) {
        return content.contains("name:") && content.contains("roleType:");
    }
}
