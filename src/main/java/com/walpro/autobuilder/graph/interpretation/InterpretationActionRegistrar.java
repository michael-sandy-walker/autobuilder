package com.walpro.autobuilder.graph.interpretation;

import com.walpro.autobuilder.graph.core.GraphActions;


import org.springframework.stereotype.Component;

import com.walpro.autobuilder.agent.interpretation.model.InterpretationOutcome;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.agent.AgentGraphService;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class InterpretationActionRegistrar {
    private final AgentGraphService agentGraphService;

    public void registerInterpretation(String agentId, CycleContextData context, InterpretationOutcome outcome) {
        if (context == null || outcome == null) {
            return;
        }

        String actionId = GraphActions.register(agentGraphService, "interpret",
                outcome.status() != null ? outcome.status() : "APPLIED",
                agentId, context);

        GraphActions.link(agentGraphService, actionId, "interpretation_summary",
                outcome.summary() != null ? outcome.summary() : "(no summary)", null);

        // This is the “communication back to underlying agents”
        if (outcome.directives() != null && !outcome.directives().isBlank()) {
            GraphActions.link(agentGraphService, actionId, "interpretation_directives",
                    outcome.directives(), null);
        }
    }
}
