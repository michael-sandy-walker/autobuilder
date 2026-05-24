package com.walpro.autobuilder.core.mutation;

/**
 * T4-BE: lifecycle states for a {@link DeferredMutation}. State transitions only forward:
 * DEFERRED → READY_TO_RESTAGE → (RESTAGED | DROPPED_DRIFT | DROPPED_REPROBE_FAILED |
 * DROPPED_PACKAGE_FRAGMENTATION), and any open state → (DROPPED_INTENT_CLOSED | EVICTED).
 */
public enum DeferredMutationState {
    /** Initial state. At least one open BLOCKED_BY edge remains. */
    DEFERRED,
    /** All blockers cleared. Eligible for restage at next cycle_start subject to scope/drift/suppression. */
    READY_TO_RESTAGE,
    /** Restage succeeded; mutation merged. Terminal. */
    RESTAGED,
    /** Drift check failed at restage time — a TOUCHES file was modified between defer and restage. Terminal. */
    DROPPED_DRIFT,
    /** Re-probe at restage time still produced COMPILE_ERROR (something else broke). Terminal. */
    DROPPED_REPROBE_FAILED,
    /**
     * T4-BH: re-probe at restage time was rejected by the probe-time fragmentation gate — the
     * deferred file set would introduce a same-simple-name-different-package collision against
     * the current {@code output/} tree (typically because a later merge landed a flat version of
     * the same type). Terminal — different remediation story from a compile failure.
     */
    DROPPED_PACKAGE_FRAGMENTATION,
    /** Intent goal closed (FULFILLED / FAILED / SUBSUMED) before restage. Terminal. */
    DROPPED_INTENT_CLOSED,
    /** TTL expired or LRU cap evicted. Terminal. */
    EVICTED;

    public boolean isOpen() {
        return this == DEFERRED || this == READY_TO_RESTAGE;
    }
}
