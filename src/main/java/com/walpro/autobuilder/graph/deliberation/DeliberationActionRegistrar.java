package com.walpro.autobuilder.graph.deliberation;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.core.deliberation.DeliberationResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.graph.core.ActionEntity;
import com.walpro.autobuilder.graph.core.ArtifactEntity;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class DeliberationActionRegistrar {

    private final AgentGraphService agentGraphService;

    public void registerDeliberation(String agentId, CycleContextData context, DeliberationResult result) {
        if (context == null || result == null) {
            return;
        }

        String actionId = UUID.randomUUID().toString();

        ActionEntity action = new ActionEntity(
                actionId,
                "deliberate",
                result.mode().name(),
                agentId,
                context.currentGoalId(),
                context.cycleName(),
                Instant.now()
        );
        agentGraphService.registerAction(action);

        String summary = """
                mode: %s
                confidence: %.4f
                rationale: %s
                considerations: %s
                meta: %s
                """.formatted(
                result.mode(),
                result.confidence(),
                result.rationale(),
                String.join(" | ", result.considerations() == null ? java.util.List.of() : result.considerations()),
                String.valueOf(result.meta())
        );

        ArtifactEntity artifact = new ArtifactEntity(
                UUID.randomUUID().toString(),
                "deliberation_summary",
                null,
                summary,
                null
        );

        agentGraphService.linkActionOutput(actionId, artifact);
    }
}
