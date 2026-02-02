package com.walpro.autobuilder.graph.evolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.graph.core.ActionEntity;
import com.walpro.autobuilder.graph.core.ArtifactEntity;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class EvolutionActionRegistrar {

    private final AgentGraphService agentGraphService;
    private final ObjectMapper objectMapper;

    public void registerEvolution(String agentId, CycleContextData context, EvolutionOutcome outcome) {
        String actionId = UUID.randomUUID().toString();
        String activeGoalId = context.currentSubGoalId() != null && !context.currentSubGoalId().isBlank()
                ? context.currentSubGoalId()
                : context.currentGoalId();

        String state = (outcome != null && outcome.recombined())
                ? "RECOMBINATION"
                : "MUTATION_APPLIED";

        ActionEntity action = new ActionEntity(
            actionId,
            "evolution",
            state,
            agentId,
            activeGoalId,
            context.currentGoalId(),
            context.cycleName(),
            Instant.now(),
            context.lastAttemptId(),
            context.parentAttemptId()
        );
        agentGraphService.registerAction(action);

        String jsonPayload;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("attemptId", context.lastAttemptId());
            payload.put("cycleId", context.cycleName());
            payload.put("goalId", context.currentGoalId());
            payload.put("subGoalId", context.currentSubGoalId());
            payload.put("summary", outcome != null ? outcome.summary() : null);
//            payload.put("fingerprint", outcome != null ? outcome.fingerprint() : null); // TODO: add fingerprint?

            jsonPayload = objectMapper.writeValueAsString(payload);
        } catch (Exception ignored) {
            jsonPayload = "{\"error\":\"failed_to_serialize_evolution_payload\"}";
        }

        ArtifactEntity artifact = new ArtifactEntity(
            UUID.randomUUID().toString(),
            "evolution_payload",
            null,
            jsonPayload,
            null
        );

        agentGraphService.linkActionOutput(actionId, artifact);
    }
}
