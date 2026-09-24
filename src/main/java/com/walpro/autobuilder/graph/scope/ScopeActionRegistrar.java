package com.walpro.autobuilder.graph.scope;

import com.walpro.autobuilder.graph.core.GraphActions;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import com.walpro.autobuilder.analysis.scope.ScopeAssessment;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import lombok.RequiredArgsConstructor;

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

        String actionId = GraphActions.register(agentGraphService, "scope", assessment.fit().name(),
                AGENT_ID, context);

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

        GraphActions.link(agentGraphService, actionId, "scope_payload",
                jsonPayload, assessment.confidence());
    }
}
