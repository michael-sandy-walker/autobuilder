package com.walpro.autobuilder.analysis.heuristic.strategy.score;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.analysis.heuristic.HeuristicOpinion;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;

import java.util.List;

@Component
public class NoOpScoreAdjustmentStrategy implements ScoreAdjustmentStrategy {
    @Override
    public Double adjustScore(EvaluationOutcome original, List<HeuristicOpinion> opinions) {
        if (original == null) return null;

        return original.score();
    }
}
