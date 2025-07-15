package com.walpro.autobuilder.agent.evaluation.model;

import com.walpro.autobuilder.heuristic.HeuristicFinding;
import com.walpro.autobuilder.model.CycleContextData;
import com.walpro.autobuilder.tools.diff.ChangedMethod;
import lombok.With;

import java.util.List;
import java.util.Map;
import java.util.Set;

@With
public record EvaluationInput(
		double score,
		Double lastScore,
		Set<String> updatedFiles,
		Set<String> deletedFiles,
		String currentGoal,
		String currentSubGoal,
		String currentGoalId,
		String currentSubGoalId,
		Map<String, String> projectState,
		List<HeuristicFinding> findings,
		Map<String, List<Double>> scoreHistoryByFile,
		Map<String, Double> recentDeltas,
		List<ChangedMethod> changedMethods
		) {
	public static EvaluationInput from(CycleContextData ctx) {
		return new EvaluationInput(
				ctx.evaluationOutcome() != null ? ctx.evaluationOutcome().score() : -1.0,
						ctx.evaluationInterpretation() != null ? ctx.evaluationInterpretation().previous() : null,
								ctx.diffReport().getUniqueFilesTouched(),
								Set.copyOf(ctx.diffReport().getDeletedFiles()),
								ctx.currentGoalDescription(),
								ctx.currentSubGoalDescription(),
								ctx.currentGoalId(),
								ctx.currentSubGoalId(),
								ctx.previousOutput(),
								ctx.findings(),
								ctx.scoreHistoryByFile(),
								ctx.recentDeltas(),
								ctx.changedMethods()
				);
	}
}