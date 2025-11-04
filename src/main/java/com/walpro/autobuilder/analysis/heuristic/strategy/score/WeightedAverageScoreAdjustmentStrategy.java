package com.walpro.autobuilder.analysis.heuristic.strategy.score;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.analysis.heuristic.HeuristicOpinion;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;

import java.util.List;

@Component
public class WeightedAverageScoreAdjustmentStrategy implements ScoreAdjustmentStrategy {

    @Override
    public double adjustScore(EvaluationOutcome original, List<HeuristicOpinion> opinions) {
        double totalWeighted = 0.0;
        double totalWeight = 0.0;

        for (HeuristicOpinion opinion : opinions) {
            if (opinion.suggestedScore() != null && opinion.confidence() > 0) {
                totalWeighted += opinion.suggestedScore() * opinion.confidence();
                totalWeight += opinion.confidence();
            }
        }

        if (totalWeight > 0) {
            return totalWeighted / totalWeight;
        }

        return original.score();
    }
}
