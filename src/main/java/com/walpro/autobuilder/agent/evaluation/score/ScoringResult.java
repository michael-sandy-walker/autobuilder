package com.walpro.autobuilder.agent.evaluation.score;

import com.walpro.autobuilder.heuristic.HeuristicFinding;

import java.util.List;
import java.util.Map;

public record ScoringResult(
        double globalScore,
        Double lastGlobalScore,
        Map<String, Double> perFileScores,
        List<HeuristicFinding> findings,
        Map<String, List<Double>> scoreHistory,
        Map<String, Double> scoreDeltas
) {}
