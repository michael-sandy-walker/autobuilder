package com.walpro.autobuilder.goal;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;

@FunctionalInterface
public interface PlanningCallback {
    void planInto(CycleContextData ctx, String parentGoalId);
}