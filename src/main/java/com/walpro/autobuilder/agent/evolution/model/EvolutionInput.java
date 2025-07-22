package com.walpro.autobuilder.agent.evolution.model;

import com.walpro.autobuilder.diff.ProjectDiffResult;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.lifecycle.CycleContextData;
import lombok.With;

import java.util.List;

@With
public record EvolutionInput(
        String currentGoal,
        String currentSubGoal,
        String currentGoalId,
        String currentSubGoalId,
        List<GoalEntity> remainingGoals,
        List<GoalEntity> completedGoals,
        String structureSummary,
        ProjectDiffResult projectDiff,
        String lastRejectionFeedback,
        String recommendation,
        boolean recombineFlag,
        String lastRawEvolutionOutput
) {
    public static EvolutionInput from(CycleContextData ctx) {
        return new EvolutionInput(
                ctx.currentGoalDescription(),
                ctx.currentSubGoalDescription(),
                ctx.currentGoalId(),
                ctx.currentSubGoalId(),
                ctx.remainingGoals(),
                ctx.completedGoals(),
                ctx.projectSummary(),
                ctx.projectDiff(),
                ctx.rejectionFeedback(),
                ctx.recommendation(),
                ctx.recombined(),
                ctx.evolutionOutcome() != null ? ctx.evolutionOutcome().rawResponse() : null
        );
    }
}
