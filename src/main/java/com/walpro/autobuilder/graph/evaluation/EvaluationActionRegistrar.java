package com.walpro.autobuilder.graph.evaluation;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.graph.core.ActionEntity;
import com.walpro.autobuilder.graph.core.ArtifactEntity;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

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
        
        Double last = context.evaluationInterpretation() != null
                ? context.evaluationInterpretation().previous()
                : null;
        Double delta = last == null ? null : outcome.score() - last;

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
