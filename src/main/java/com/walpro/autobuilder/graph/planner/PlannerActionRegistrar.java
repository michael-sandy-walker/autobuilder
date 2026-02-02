package com.walpro.autobuilder.graph.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;
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
public class PlannerActionRegistrar {

    private final AgentGraphService agentGraphService;
    private final ObjectMapper objectMapper;

    public void registerPlannerAction(String agentId, CycleContextData context, PlannerOutcome outcome) {
        String actionId = UUID.randomUUID().toString();
        String activeGoalId = context.currentSubGoalId() != null && !context.currentSubGoalId().isBlank()
                ? context.currentSubGoalId()
                : context.currentGoalId();

        boolean updated = outcome != null && outcome.updated();
        String state = updated ? "UPDATED" : "NOOP";

        ActionEntity action = new ActionEntity(
            actionId,
            "planner",
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
            payload.put("updated", updated);
            payload.put("newGoals", outcome != null ? outcome.newGoals() : null);
            payload.put("message", outcome != null ? outcome.message() : null);

            jsonPayload = objectMapper.writeValueAsString(payload);
        } catch (Exception ignored) {
            jsonPayload = "{\"error\":\"failed_to_serialize_planner_payload\"}";
        }

        ArtifactEntity artifact = new ArtifactEntity(
                UUID.randomUUID().toString(),
                "planner_payload",
                null,
                jsonPayload,
                null
        );

        agentGraphService.linkActionOutput(actionId, artifact);
    }
}
