package com.walpro.autobuilder.evaluation.strategy;

import java.util.Map;
import java.util.Set;

import com.walpro.autobuilder.evaluation.model.GoalProgressState;

public interface GoalProgressStrategy {
    GoalProgressState evaluate(Set<String> filesChanged, double score, String goal, Map<String, String> projectState);
}