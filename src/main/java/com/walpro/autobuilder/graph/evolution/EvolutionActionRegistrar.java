package com.walpro.autobuilder.graph.evolution;

import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.graph.core.ActionEntity;
import com.walpro.autobuilder.graph.core.ArtifactEntity;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class EvolutionActionRegistrar {

    private final AgentGraphService agentGraphService;

    public void registerEvolution(String agentId, CycleContextData context, EvolutionOutcome outcome) {
        String actionId = UUID.randomUUID().toString();

        ActionEntity action = new ActionEntity(
            actionId,
            "evolve",
            "MUTATION", // or "RECOMBINATION" if branching later
            agentId,
            context.currentGoalId(),
            context.cycleName(),
            Instant.now()
        );
        agentGraphService.registerAction(action);

        String summary = outcome != null && outcome.summary() != null && !outcome.summary().isBlank()
            ? outcome.summary()
            : "(no evolution summary)";

        ArtifactEntity artifact = new ArtifactEntity(
            UUID.randomUUID().toString(),
            "evolution_summary",
            null,
            summary,
            null
        );

        agentGraphService.linkActionOutput(actionId, artifact);
    }
}
