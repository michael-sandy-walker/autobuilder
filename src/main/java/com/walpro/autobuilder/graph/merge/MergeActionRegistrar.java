package com.walpro.autobuilder.graph.merge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.service.merge.MergeResult;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.agent.merge.model.MergeOutcome;
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
public class MergeActionRegistrar {

    private final AgentGraphService agentGraphService;
    private final ObjectMapper objectMapper;

    public void registerMerge(String agentId, CycleContextData context, MergeResult result) {
        String actionId = UUID.randomUUID().toString();
        String activeGoalId = context.currentSubGoalId() != null && !context.currentSubGoalId().isBlank()
                ? context.currentSubGoalId()
                : context.currentGoalId();

        String state = result != null && result.wasSuccessful() ? "MERGED" : "SKIPPED";

        ActionEntity action = new ActionEntity(
            actionId,
            "merge",
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
            payload.put("merged", result.wasSuccessful());
            payload.put("reasoning", result.reasoning());
            payload.put("mergedContent", result.currentContent());

            jsonPayload = objectMapper.writeValueAsString(payload);
        } catch (Exception ignored) {
            jsonPayload = "{\"error\":\"failed_to_serialize_merge_payload\"}";
        }

        ArtifactEntity artifact = new ArtifactEntity(
                UUID.randomUUID().toString(),
                "merge_payload",
                null,
                jsonPayload,
                null
        );

        agentGraphService.linkActionOutput(actionId, artifact);
    }
}
