package com.walpro.autobuilder.agent.evaluation.heuristic.strategy.score;

import com.walpro.autobuilder.agent.evaluation.heuristic.HeuristicOpinion;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;

import java.util.List;

public interface ScoreAdjustmentStrategy {
    double adjustScore(EvaluationOutcome original, List<HeuristicOpinion> opinions);
}
