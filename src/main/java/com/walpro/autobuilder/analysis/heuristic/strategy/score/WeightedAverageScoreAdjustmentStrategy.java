package com.walpro.autobuilder.analysis.heuristic.strategy.score;

import com.walpro.autobuilder.analysis.heuristic.HeuristicOpinion;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class WeightedAverageScoreAdjustmentStrategy implements ScoreAdjustmentStrategy {

    // Make LLM the anchor unless missing/invalid.
    private static final double BASE_WEIGHT = 1.0;

    @Override
    public Double adjustScore(EvaluationOutcome original, List<HeuristicOpinion> opinions) {
        Double base = original != null ? original.score() : null;

        double totalWeighted = 0.0;
        double totalWeight = 0.0;

        if (base != null && Double.isFinite(base)) {
            totalWeighted += base * BASE_WEIGHT;
            totalWeight += BASE_WEIGHT;
        }

        if (opinions != null) {
            for (HeuristicOpinion opinion : opinions) {
                Double s = opinion.suggestedScore();
                double w = opinion.confidence();
                if (s != null && Double.isFinite(s) && w > 0) {
                    totalWeighted += s * w;
                    totalWeight += w;
                }
            }
        }

        if (totalWeight == 0.0) return base; // may be null; caller should handle
        return totalWeighted / totalWeight;
    }
}