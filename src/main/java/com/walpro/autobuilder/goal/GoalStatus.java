package com.walpro.autobuilder.goal;

public enum GoalStatus {
    ACTIVE,
    COMPLETED,
    GIVEN_UP,
    /**
     * T2-DR: a subgoal that was given up but whose own acceptance criteria
     * were already banked-true at give-up time. Distinguishes "abandoned because
     * other work made it redundant" from "abandoned because we failed", so the
     * evidence-ledger closure rule can pass over it without blocking root completion.
     */
    SUBSUMED
}
