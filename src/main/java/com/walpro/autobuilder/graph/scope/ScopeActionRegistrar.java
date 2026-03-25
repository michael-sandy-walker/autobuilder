package com.walpro.autobuilder.graph.scope;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import com.walpro.autobuilder.analysis.scope.ScopeAssessment;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.graph.core.ActionEntity;
import com.walpro.autobuilder.graph.core.ArtifactEntity;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScopeActionRegistrar {

    private static final String AGENT_ID = "StaticScopeGuard";

    private final AgentGraphService agentGraphService;
    private final ObjectMapper objectMapper;

    public void registerScope(CycleContextData context, ScopeAssessment assessment) {
        if (assessment == null) {
            return;
        }

        String actionId = UUID.randomUUID().toString();
        String activeGoalId = context.currentSubGoalId() != null && !context.currentSubGoalId().isBlank()
                ? context.currentSubGoalId()
                : context.currentGoalId();

        ActionEntity action = new ActionEntity(
                actionId,
                "scope",
                assessment.fit().name(),
                AGENT_ID,
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
            payload.put("fit", assessment.fit().name());
            payload.put("confidence", assessment.confidence());
            payload.put("message", assessment.message());
            payload.put("bestMatchingSubGoalId", assessment.bestMatchingSubGoalId());
            payload.put("subGoalId", context.currentSubGoalId());
            payload.put("goalId", context.currentGoalId());
            payload.put("cycleId", context.cycleName());
            payload.put("attemptId", context.lastAttemptId());

            jsonPayload = objectMapper.writeValueAsString(payload);
        } catch (Exception ignored) {
            jsonPayload = "{\"error\":\"failed_to_serialize_scope_payload\"}";
        }

        ArtifactEntity artifact = new ArtifactEntity(
                UUID.randomUUID().toString(),
                "scope_payload",
                null,
                jsonPayload,
                assessment.confidence()
        );

        agentGraphService.linkActionOutput(actionId, artifact);
    }
}
