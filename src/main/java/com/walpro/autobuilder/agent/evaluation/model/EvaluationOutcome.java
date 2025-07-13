package com.walpro.autobuilder.agent.evaluation.model;

import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

public record EvaluationOutcome(
		double score,
		MutationAcceptanceState mutationState,
		GoalProgressState goalProgress,
		String feedbackMessage,
		String recommendation
) {}
