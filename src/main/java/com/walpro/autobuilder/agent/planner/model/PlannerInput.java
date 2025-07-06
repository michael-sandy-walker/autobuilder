package com.walpro.autobuilder.agent.planner.model;

import java.util.List;

public record PlannerInput(
        String currentGoal,
        String currentSubGoal,
        List<GoalInfo> remainingGoals,
        List<GoalInfo> completedGoals,
        String evaluationFeedback,
        String recommendation
) {}


