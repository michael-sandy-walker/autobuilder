package com.walpro.autobuilder.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.walpro.autobuilder.heuristic.HeuristicFinding;

public record EvaluationInput(
    double score,
    Double lastScore,
    Set<String> updatedFiles,
    Set<String> deletedFiles,
    String currentGoal,
    Map<String, String> projectState,
    List<HeuristicFinding> findings
) {}
