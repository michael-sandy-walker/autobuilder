package com.walpro.autobuilder.agent.evaluation.graph;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.meta.graph.AgentGraphService;
import com.walpro.autobuilder.graph.model.ActionEntity;
import com.walpro.autobuilder.graph.model.ArtifactEntity;
import com.walpro.autobuilder.lifecycle.CycleContextData;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class EvaluationActionRegistrar {

    private final AgentGraphService agentGraphService;

    public void registerEvaluation(String agentId, CycleContextData context, EvaluationOutcome outcome) {
        if (outcome == null) {
        	return;
        }
        
        String actionId = UUID.randomUUID().toString();
        
        ActionEntity action = new ActionEntity(
            actionId,
            "evaluate",
            outcome.mutationState().name(),
            agentId,
            context.currentGoalId(),
            context.cycleName(),
            Instant.now()
        );
        agentGraphService.registerAction(action);       
        
        double last = context.evaluationInterpretation() != null
                ? context.evaluationInterpretation().previous()
                : -1.0;
        double delta = outcome.score() - last;

        String summary = """
                goalProgress: %s
                mutationState: %s
                scoreNow: %.4f
                scoreBefore: %.4f
                delta: %.4f
                """.formatted(
                outcome.goalProgress(),
                outcome.mutationState(),
                outcome.score(),
                last,
                delta
        );

        ArtifactEntity artifact = new ArtifactEntity(
            UUID.randomUUID().toString(),
            "evaluation_summary",
            null,
            summary,
            null
        );

        agentGraphService.linkActionOutput(actionId, artifact);
    }
}
