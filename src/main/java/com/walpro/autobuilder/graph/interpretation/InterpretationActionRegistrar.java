package com.walpro.autobuilder.graph.interpretation;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.agent.interpretation.model.InterpretationOutcome;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.graph.core.ActionEntity;
import com.walpro.autobuilder.graph.core.ArtifactEntity;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class InterpretationActionRegistrar {

    private final AgentGraphService agentGraphService;

    public void registerInterpretation(String agentId, CycleContextData context, InterpretationOutcome outcome) {
        if (context == null || outcome == null) {
            return;
        }

        String actionId = UUID.randomUUID().toString();
        String activeGoalId = context.currentSubGoalId() != null && !context.currentSubGoalId().isBlank()
                ? context.currentSubGoalId()
                : context.currentGoalId();

        ActionEntity action = new ActionEntity(
                actionId,
                "interpret",
                outcome.status() != null ? outcome.status() : "APPLIED",
                agentId,
                activeGoalId,
                context.currentGoalId(),
                context.cycleName(),
                Instant.now(),
                context.lastAttemptId()
        );
        agentGraphService.registerAction(action);

        ArtifactEntity summary = new ArtifactEntity(
                UUID.randomUUID().toString(),
                "interpretation_summary",
                null,
                outcome.summary() != null ? outcome.summary() : "(no summary)",
                null
        );
        agentGraphService.linkActionOutput(actionId, summary);

        // This is the “communication back to underlying agents”
        if (outcome.directives() != null && !outcome.directives().isBlank()) {
            ArtifactEntity directives = new ArtifactEntity(
                    UUID.randomUUID().toString(),
                    "interpretation_directives",
                    null,
                    outcome.directives(),
                    null
            );
            agentGraphService.linkActionOutput(actionId, directives);
        }
    }
}
