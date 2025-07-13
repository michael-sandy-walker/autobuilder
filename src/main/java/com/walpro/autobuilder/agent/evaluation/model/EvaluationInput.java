package com.walpro.autobuilder.agent.evaluation.model;

import com.walpro.autobuilder.heuristic.HeuristicFinding;
import com.walpro.autobuilder.tools.diff.ChangedMethod;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record EvaluationInput(
    double score,
    Double lastScore,
    Set<String> updatedFiles,
    Set<String> deletedFiles,
    String currentGoal,
    String currentSubGoal,
    String currentGoalId,
    String currentSubGoalId,
    Map<String, String> projectState,
    List<HeuristicFinding> findings,
    Map<String, List<Double>> scoreHistoryByFile,
    Map<String, Double> recentDeltas,
    List<ChangedMethod> changedMethods
) {}
