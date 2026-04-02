package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;

import java.util.List;

/**
 * Result of {@link HeuristicNegotiator#negotiate}, bundling the final {@link EvaluationOutcome}
 * with the list of opinions that fired at {@link ConfidenceLevel#CRITICAL} confidence.
 * Callers use the critical-opinion list to track recurring patterns across cycles.
 */
public record NegotiationResult(EvaluationOutcome outcome, List<HeuristicOpinion> criticalOpinions) {}
