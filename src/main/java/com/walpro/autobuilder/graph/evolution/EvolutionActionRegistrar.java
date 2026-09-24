package com.walpro.autobuilder.graph.evolution;

import com.walpro.autobuilder.graph.core.GraphActions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class EvolutionActionRegistrar {
    private final AgentGraphService agentGraphService;
    private final ObjectMapper objectMapper;

    public void registerEvolution(String agentId, CycleContextData context, EvolutionOutcome outcome) {
        String state = (outcome != null && outcome.recombined())
                ? "RECOMBINATION"
                : "MUTATION_APPLIED";

        String actionId = GraphActions.register(agentGraphService, "evolution", state,
                agentId, context);

        Map<String, Object> payload = GraphActions.basePayload(context);
        payload.put("summary", outcome != null ? outcome.summary() : null);
//            payload.put("fingerprint", outcome != null ? outcome.fingerprint() : null); // TODO: add fingerprint?

        String jsonPayload = GraphActions.json(objectMapper, payload, "evolution_payload");

        GraphActions.link(agentGraphService, actionId, "evolution_payload", jsonPayload, null);
    }
}
