package com.walpro.autobuilder.core.lifecycle;

public enum CycleResultState {
    GOAL_FULFILLED,
    SUBGOAL_EXHAUSTED,
    GOAL_EXHAUSTED,
    STAGNATING,
    CONTINUE,
    /** T1-DX: post-merge build broke and rollback restored pre-merge state — no progress this cycle. */
    ROLLED_BACK
}
