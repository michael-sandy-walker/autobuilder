package com.walpro.autobuilder.agent.planner.model;

import java.util.List;

import com.walpro.autobuilder.agent.evaluation.EvaluationInterpreter.InterpretationResult;

public record PlannerInput(
        String currentGoal,
        String currentSubGoal,
        List<GoalInfo> remainingGoals,
        List<GoalInfo> completedGoals,
        String evaluationFeedback,
        String recommendation,
        InterpretationResult interpretationResult
) {}


