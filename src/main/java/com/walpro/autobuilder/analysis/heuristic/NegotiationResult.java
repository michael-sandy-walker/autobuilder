package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;

import java.util.List;

/**
 * Result of {@link HeuristicNegotiator#negotiate}, bundling the final {@link EvaluationOutcome}
 * with the full list of opinions that fired (non-NONE confidence) and the subset that fired at
 * {@link ConfidenceLevel#CRITICAL} confidence.
 * Callers use the critical-opinion list to track recurring patterns across cycles.
 * {@code allOpinions} is persisted to the graph for post-hoc debugging.
 */
public record NegotiationResult(EvaluationOutcome outcome, List<HeuristicOpinion> criticalOpinions,
                                List<HeuristicOpinion> allOpinions) {}
