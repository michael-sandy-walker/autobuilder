package com.walpro.autobuilder.controller.goal;

import java.util.List;

/**
 * T4-AM: request body for {@code POST /autobuilder/goals/{parentId}/inject-subgoal}.
 * Lets a human-in-the-loop inject a custom subgoal under a chosen parent; optionally
 * pins it as the current subgoal and seeds acceptance criteria.
 */
public record GoalInjectSubgoalRequest(
        String description,
        boolean setAsCurrent,
        List<String> criteria,
        String role
) {}
