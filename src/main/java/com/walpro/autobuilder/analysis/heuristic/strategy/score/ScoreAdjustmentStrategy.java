package com.walpro.autobuilder.analysis.heuristic.strategy.score;

import java.util.List;

import com.walpro.autobuilder.analysis.heuristic.HeuristicOpinion;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;

public interface ScoreAdjustmentStrategy {
    double adjustScore(EvaluationOutcome original, List<HeuristicOpinion> opinions);
}
