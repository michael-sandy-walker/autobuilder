package com.walpro.autobuilder.analysis.scope;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.goal.GoalEntity;

import java.util.List;

public interface SubgoalScopeReviewer {
    ScopeAssessment assess(CycleContextData ctx, List<GoalEntity> remainingGoals, List<GoalEntity> completedGoals);
}