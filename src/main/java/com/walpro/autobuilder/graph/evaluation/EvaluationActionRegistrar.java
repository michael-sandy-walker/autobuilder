package com.walpro.autobuilder.graph.evaluation;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.heuristic.HeuristicOpinion;
import com.walpro.autobuilder.analysis.heuristic.NegotiationResult;
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

    public void registerEvaluation(String agentId, CycleContextData context, NegotiationResult negotiationResult) {
        if (negotiationResult == null) {
            return;
        }
        EvaluationOutcome outcome = negotiationResult.outcome();
        if (outcome == null) {
        	return;
        }
        
        String actionId = UUID.randomUUID().toString();
        String activeGoalId = context.currentSubGoalId() != null && !context.currentSubGoalId().isBlank()
                ? context.currentSubGoalId()
                : context.currentGoalId();

        String state = outcome.mutationState() != null
                ? outcome.mutationState().name()
                : "NOT_EVALUATED";
        
        ActionEntity action = new ActionEntity(
            actionId,
            "evaluation",
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
            payload.put("goalProgress", outcome.goalProgress() != null ? outcome.goalProgress().name() : null);
            payload.put("mutationState", outcome.mutationState() != null ? outcome.mutationState().name() : null);
            payload.put("scoreNow", outcome.score());
            payload.put("attemptId", context.lastAttemptId());
            payload.put("cycleId", context.cycleName());
            payload.put("goalId", context.currentGoalId());
            payload.put("subGoalId", context.currentSubGoalId());
            payload.put("message", outcome.feedbackMessage());
            payload.put("recommendation", outcome.recommendation());

            List<HeuristicOpinion> allOpinions = negotiationResult.allOpinions();
            if (allOpinions != null && !allOpinions.isEmpty()) {
                List<Map<String, Object>> opinionsPayload = allOpinions.stream()
                        .map(op -> {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("name", op.name());
                            entry.put("confidence", op.confidence());
                            entry.put("level", op.level() != null ? op.level().name() : null);
                            entry.put("rationale", op.rationale());
                            entry.put("critical", negotiationResult.criticalOpinions().contains(op));
                            return entry;
                        })
                        .toList();
                payload.put("heuristicOpinions", opinionsPayload);
                payload.put("criticalOpinionCount", negotiationResult.criticalOpinions().size());
            }

            jsonPayload = objectMapper.writeValueAsString(payload);
        } catch (Exception ignored) {
            // fail open: keep metadataJson null
            jsonPayload = "{\"error\":\"failed_to_serialize_evaluation_payload\"}";
        }

        ArtifactEntity artifact = new ArtifactEntity(
            UUID.randomUUID().toString(),
            "evaluation_payload",
            null,
            jsonPayload,
            outcome.score()
        );

        agentGraphService.linkActionOutput(actionId, artifact);
    }
}
