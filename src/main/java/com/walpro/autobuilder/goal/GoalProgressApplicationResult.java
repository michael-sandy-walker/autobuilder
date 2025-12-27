package com.walpro.autobuilder.goal;

public record GoalProgressApplicationResult(
        String goalId,
        boolean promoted,
        String previousSubGoalId,
        String newSubGoalId,
        boolean rootAutoCompleted
) {}