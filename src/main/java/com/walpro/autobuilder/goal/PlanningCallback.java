package com.walpro.autobuilder.goal;

@FunctionalInterface
public interface PlanningCallback {
    void planInto(String parentGoalId);
}