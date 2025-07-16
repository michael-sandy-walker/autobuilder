package com.walpro.autobuilder.agent.evolution.model;

import com.walpro.autobuilder.diff.DiffReport;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.model.CycleContextData;
import com.walpro.autobuilder.tools.diff.ChangedMethod;

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
		DiffReport diff,
		String lastRejectionFeedback,
		String recommendation,
		boolean recombineFlag,
		String lastRawEvolutionOutput,
		List<ChangedMethod> changedMethods
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
				ctx.diffReport(),
				ctx.rejectionFeedback(),
				ctx.recommendation(),
				ctx.recombined(),
				ctx.evolutionOutcome() != null ? ctx.evolutionOutcome().rawResponse() : null,
				ctx.changedMethods() != null ? ctx.changedMethods() : List.of()
				);
	}
}

