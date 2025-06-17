package com.walpro.autobuilder.evaluation.model;

public record EvaluationOutcome(
	    MutationAcceptanceState mutationState,
	    GoalProgressState goalProgress,
	    String feedbackMessage,
	    String recommendation
	) {}