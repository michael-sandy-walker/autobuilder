package com.walpro.autobuilder.agent.evaluation.heuristic.strategy.score;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.agent.evaluation.heuristic.HeuristicOpinion;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;

import java.util.Comparator;
import java.util.List;

@Component("maxConfidenceScoreStrategy")
public class MaxConfidenceScoreAdjustmentStrategy implements ScoreAdjustmentStrategy {

    @Override
    public double adjustScore(EvaluationOutcome original, List<HeuristicOpinion> opinions) {
        return opinions.stream()
                .filter(o -> o.suggestedScore() != null)
                .max(Comparator.comparingDouble(HeuristicOpinion::confidence))
                .map(HeuristicOpinion::suggestedScore)
                .orElse(original.score());
    }
}