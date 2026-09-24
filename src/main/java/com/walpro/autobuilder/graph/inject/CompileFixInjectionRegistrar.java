package com.walpro.autobuilder.graph.inject;

import com.walpro.autobuilder.graph.core.GraphActions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.build.inject.InjectorFixRecord;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.agent.AgentGraphService;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

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
@Component
@RequiredArgsConstructor
public class CompileFixInjectionRegistrar {
    private final AgentGraphService agentGraphService;
    private final ObjectMapper objectMapper;

    public void registerInjection(String agentId, CycleContextData context, InjectorFixRecord fix) {
        if (fix == null) return;

        String actionId = GraphActions.register(agentGraphService, "compile_fix_injection",
                "INJECTED",
                agentId, context);

        Map<String, Object> payload = GraphActions.basePayload(context);
        payload.put("rule", fix.rule());
        payload.put("kind", fix.kind() != null ? fix.kind().name() : null);
        payload.put("missingToken", fix.missingToken());
        payload.put("touchedPaths", fix.touchedPaths() != null ? fix.touchedPaths() : List.of());
        payload.put("description", fix.description());

        String jsonPayload = GraphActions.json(objectMapper, payload, "injection_payload");

        GraphActions.link(agentGraphService, actionId, "compile_fix_injection_payload",
                jsonPayload, null);
    }
}
