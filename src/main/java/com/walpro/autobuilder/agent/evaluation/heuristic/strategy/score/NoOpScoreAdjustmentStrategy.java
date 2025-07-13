package com.walpro.autobuilder.agent.evaluation.heuristic.strategy.score;

import org.springframework.stereotype.Component;

import com.walpro.autobuilder.agent.evaluation.heuristic.HeuristicOpinion;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;

import java.util.List;

@Component
public class NoOpScoreAdjustmentStrategy implements ScoreAdjustmentStrategy {
    @Override
    public double adjustScore(EvaluationOutcome original, List<HeuristicOpinion> opinions) {
        return original.score();
    }
}
