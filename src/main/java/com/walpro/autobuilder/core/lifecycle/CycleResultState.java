package com.walpro.autobuilder.core.lifecycle;

public enum CycleResultState {
    GOAL_FULFILLED,
    SUBGOAL_FULFILLED,
    SUBGOAL_EXHAUSTED,
    GOAL_EXHAUSTED,
    STAGNATING,
    CONTINUE
}
