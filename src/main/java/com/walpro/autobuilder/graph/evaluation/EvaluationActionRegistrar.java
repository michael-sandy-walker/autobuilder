package com.walpro.autobuilder.graph.evaluation;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.graph.core.ActionEntity;
import com.walpro.autobuilder.graph.core.ArtifactEntity;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class EvaluationActionRegistrar {

    private final AgentGraphService agentGraphService;

    private final ObjectMapper objectMapper;

    public void registerEvaluation(String agentId, CycleContextData context, EvaluationOutcome outcome) {
        if (outcome == null) {
        	return;
        }
        
        String actionId = UUID.randomUUID().toString();
        String activeGoalId = context.currentSubGoalId() != null && !context.currentSubGoalId().isBlank()
                ? context.currentSubGoalId()
                : context.currentGoalId();
        
        ActionEntity action = new ActionEntity(
            actionId,
            "evaluation",
            outcome.mutationState().name(),
            agentId,
            activeGoalId,
            context.currentGoalId(),
            context.cycleName(),
            Instant.now(),
            context.lastAttemptId()
        );
        agentGraphService.registerAction(action);

        String jsonPayload;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("goalProgress", outcome.goalProgress() != null ? outcome.goalProgress().name() : null);
            payload.put("mutationState", outcome.mutationState() != null ? outcome.mutationState().name() : null);
            payload.put("scoreNow", outcome.score());
            payload.put("attemptId", context.lastAttemptId());
            payload.put("cycleId", context.cycleName());
            payload.put("goalId", context.currentGoalId());
            payload.put("subGoalId", context.currentSubGoalId());

            jsonPayload = objectMapper.writeValueAsString(payload);
        } catch (Exception ignored) {
            // fail open: keep metadataJson null
            jsonPayload = "{\"error\":\"failed_to_serialize_evaluation_payload\"}";
        }

        ArtifactEntity artifact = new ArtifactEntity(
            UUID.randomUUID().toString(),
            "evaluation_summary",
            null,
            jsonPayload,
            outcome.score()
        );

        agentGraphService.linkActionOutput(actionId, artifact);
    }
}
