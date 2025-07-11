package com.walpro.autobuilder.agent.evolution.model;

import com.walpro.autobuilder.diff.DiffReport;
import com.walpro.autobuilder.model.ProjectInput;

import lombok.With;

import java.util.List;

@With
public record EvolutionInput(
        String currentGoal,
        String currentSubGoal,
        String currentGoalId,
        String currentSubGoalId,
        List<String> remainingGoals,
        List<String> completedGoals,
        String structureSummary,
        DiffReport diff,
        String lastRejectionFeedback,
        String recommendation,
        boolean recombineFlag
) {
    public static EvolutionInput from(ProjectInput p) {
        return new EvolutionInput(
                p.currentGoalDescription(),
                p.currentSubGoalDescription(),
                p.currentGoalId(),
                p.currentSubGoalId(),
                p.remainingGoals(),
                p.completedGoals(),
                p.structureSummary(),
                p.diff(),
                p.lastRejectionFeedback(),
                p.recommendation(),
                false
        );
    }
}

