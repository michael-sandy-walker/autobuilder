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

    @Override
    public InterpretationOutcome interpret(CycleContextData context) {
        if (context == null || context.currentGoalId() == null || context.currentGoalId().isBlank()) {
            return InterpretationOutcome.noop("No goal set; skipping interpretation.");
        }

        List<ArtifactEntity> recent = agentGraphService.findRecentArtifactsForGoal(
                context.currentGoalId(),
                List.of(
                        "planner_summary",
                        "evaluation_summary",
                        "merge_summary",
                        "deliberation_summary",
                        "interpretation_summary"
                ),
                12
        );

        String substrate = buildSubstrate(context, recent);

        // Placeholder interpretation output (replace later with LLM call).
        String summary = """
            goal: %s
            cycle: %s
            gatheredSummaries: %d
            goalProgress: %s
            mutationState: %s
            """.formatted(
                context.currentGoalId(),
                context.cycleName(),
                recent.size(),
                String.valueOf(context.goalProgress()),
                String.valueOf(context.mutationState())
        );

        String directives = """
            Directives:
            - Avoid repeating the same subgoal/mutation pattern unless conditions changed.
            - If stagnating persists, prioritize REPLAN and refine subgoal granularity.
            - If merge regressions appear, prefer targeted merge instead of broad recombination.
            """;

        return new InterpretationOutcome("APPLIED", summary + "\n\n" + substrate, directives, List.of());
    }

    private String buildSubstrate(CycleContextData ctx, List<ArtifactEntity> artifacts) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== recent artifacts (goal-scoped) ===\n");
        for (ArtifactEntity a : artifacts) {
            sb.append("\n--- ").append(a.type()).append(" ---\n");
            if (a.content() != null) sb.append(a.content()).append("\n");
        }
        sb.append("\n=== context snapshot ===\n");
        sb.append("cycleResult=").append(String.valueOf(ctx.cycleResultState())).append("\n");
        sb.append("signals=").append(String.valueOf(ctx.signals())).append("\n");
        return sb.toString();
    }
}
