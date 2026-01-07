package com.walpro.autobuilder.graph.planner;

import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.agent.planner.model.PlannerOutcome;
import com.walpro.autobuilder.graph.core.ActionEntity;
import com.walpro.autobuilder.graph.core.ArtifactEntity;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class PlannerActionRegistrar {

    private final AgentGraphService agentGraphService;

    public void registerPlannerAction(String agentId, CycleContextData context, PlannerOutcome outcome) {
        String activeGoalId = context.currentSubGoalId() != null && !context.currentSubGoalId().isBlank()
                ? context.currentSubGoalId()
                : context.currentGoalId();
        ActionEntity action = new ActionEntity(
            UUID.randomUUID().toString(),
            "plan",
            "applied",
            agentId,
            activeGoalId,
            context.currentGoalId(),
            context.cycleName(),
            Instant.now()
        );
        agentGraphService.registerAction(action);

        if (outcome != null && outcome.updated() && !outcome.newGoals().isEmpty()) {
            String goalSummary = String.join("\n", outcome.newGoals());
            ArtifactEntity artifact = new ArtifactEntity(
                UUID.randomUUID().toString(),
                "goalList",
                null,
                goalSummary,
                null
            );
            agentGraphService.linkActionOutput(action.id(), artifact);
        }
    }
}
