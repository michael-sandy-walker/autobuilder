package com.walpro.autobuilder.core.lifecycle;

/**
 * T4-BZ-2: the scope guard's per-cycle result for the current mutation, previously stored as
 * the SCOPE_OUT_OF_SCOPE / SUBGOAL_SCOPE_MISMATCH signals. Absence (null field) means in scope
 * or not assessed this cycle.
 */
public enum ScopeGuardResult {
    /** Diff matches a different subgoal better than the current one (blocker for eval/merge/completion). */
    OUT_OF_SCOPE,
    /** Diff doesn't match the current subgoal and no better match exists (replan-flavored blocker). */
    MISMATCH
}
