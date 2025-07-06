package com.walpro.autobuilder.agent.evaluation.model;

import com.walpro.autobuilder.evaluation.model.GoalProgressState;
import com.walpro.autobuilder.evaluation.model.MutationAcceptanceState;

public record EvaluationOutcome(
		double score,
		MutationAcceptanceState mutationState,
		GoalProgressState goalProgress,
		String feedbackMessage,
		String recommendation
) {}
