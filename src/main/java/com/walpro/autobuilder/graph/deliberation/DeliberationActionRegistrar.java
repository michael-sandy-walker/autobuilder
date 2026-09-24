package com.walpro.autobuilder.graph.deliberation;

import com.walpro.autobuilder.graph.core.GraphActions;


import org.springframework.stereotype.Component;

import com.walpro.autobuilder.core.deliberation.DeliberationResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.agent.AgentGraphService;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class DeliberationActionRegistrar {
    private final AgentGraphService agentGraphService;

    public void registerDeliberation(String agentId, CycleContextData context, DeliberationResult result) {
        if (context == null || result == null) {
            return;
        }

        String actionId = GraphActions.register(agentGraphService, "deliberate",
                result.mode().name(),
                agentId, context);

        String summary = """
                mode: %s
                confidence: %.4f
                rationale: %s
                considerations: %s
                meta: %s
                """.formatted(
                result.mode(),
                result.confidence(),
                result.rationale(),
                String.join(" | ", result.considerations() == null ? java.util.List.of() : result.considerations()),
                String.valueOf(result.meta())
        );

        GraphActions.link(agentGraphService, actionId, "deliberation_summary", summary, null);
    }
}
