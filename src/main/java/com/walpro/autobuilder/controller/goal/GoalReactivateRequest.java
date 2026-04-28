package com.walpro.autobuilder.controller.goal;

/**
 * Request body for {@code POST /autobuilder/goals/{id}/reactivate}. {@code setAsCurrent}
 * pins the reactivated node as the planner's current subgoal when it is a SUBGOAL.
 */
public record GoalReactivateRequest(boolean setAsCurrent) {}
