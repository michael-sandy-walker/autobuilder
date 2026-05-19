package com.walpro.autobuilder.graph.inject;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.build.inject.InjectorFixRecord;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.graph.core.ActionEntity;
import com.walpro.autobuilder.graph.core.ArtifactEntity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Records deterministic compile fixes produced by {@code CompileFixInjector} into the
 * orchestration graph. Mirrors {@code MergeActionRegistrar}: one {@code ActionEntity} per
 * injected fix (state = {@code "INJECTED"}), linked to a JSON {@code ArtifactEntity} that
 * carries the structured provenance (rule, kind, missing token, touched paths).
 *
 * <p>The graph layer is the cross-cycle queryable provenance: it lets analysis / planner /
 * future agents answer "how often did the injector fire?" or "which subgoals depended on an
 * injector fix?" without scraping behaviour logs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompileFixInjectionRegistrar {

    private final AgentGraphService agentGraphService;
    private final ObjectMapper objectMapper;

    public void registerInjection(String agentId, CycleContextData context, InjectorFixRecord fix) {
        if (fix == null) return;
        String actionId = UUID.randomUUID().toString();
        String activeGoalId = context.currentSubGoalId() != null && !context.currentSubGoalId().isBlank()
                ? context.currentSubGoalId()
                : context.currentGoalId();

        ActionEntity action = new ActionEntity(
                actionId,
                "compile_fix_injection",
                "INJECTED",
                agentId,
                activeGoalId,
                context.currentGoalId(),
                context.cycleName(),
                Instant.now(),
                context.lastAttemptId(),
                context.parentAttemptId()
        );
        agentGraphService.registerAction(action);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attemptId", context.lastAttemptId());
        payload.put("cycleId", context.cycleName());
        payload.put("goalId", context.currentGoalId());
        payload.put("subGoalId", context.currentSubGoalId());
        payload.put("rule", fix.rule());
        payload.put("kind", fix.kind() != null ? fix.kind().name() : null);
        payload.put("missingToken", fix.missingToken());
        payload.put("touchedPaths", fix.touchedPaths() != null ? fix.touchedPaths() : List.of());
        payload.put("description", fix.description());

        String jsonPayload;
        try {
            jsonPayload = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("CompileFixInjectionRegistrar: failed to serialize payload: {}", e.getMessage());
            jsonPayload = "{\"error\":\"failed_to_serialize_injection_payload\"}";
        }

        ArtifactEntity artifact = new ArtifactEntity(
                UUID.randomUUID().toString(),
                "compile_fix_injection_payload",
                null,
                jsonPayload,
                null
        );
        agentGraphService.linkActionOutput(actionId, artifact);
    }
}
