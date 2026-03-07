package com.walpro.autobuilder.agent.interpretation;

import java.util.List;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.agent.interpretation.model.InterpretationOutcome;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.agent.AgentGraphService;
import com.walpro.autobuilder.graph.core.ArtifactEntity;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class DefaultInterpretationAgent implements InterpretationAgent {

    private final AgentGraphService agentGraphService;
    private final InterpretationLLMAdapter llmAdapter;

    @Override
    public InterpretationOutcome interpret(CycleContextData context) {
        if (context == null || context.currentGoalId() == null || context.currentGoalId().isBlank()) {
            return InterpretationOutcome.noop("No goal set; skipping interpretation.");
        }

        List<ArtifactEntity> recent = agentGraphService.findRecentArtifactsForGoal(
                context.currentGoalId(),
                List.of(
                        "planner_outcome",
                        "evaluation_payload",
                        "merge_payload",
                        "deliberation_summary",
                        "interpretation_summary"
                ),
                12
        );

        return llmAdapter.interpret(context, recent);
    }
}
