package com.walpro.autobuilder.core.checkpoint;

/**
 * T4-DQ decision 3: how a rewind treats {@code cache/} — and the difference between the two is
 * itself the instrument.
 *
 * <p>Nobody currently knows whether the resolution cache is a pure optimisation or decision-bearing.
 * A miss is not free: it means an LLM call or a registry lookup, and either can answer differently
 * from what the cache holds. So the modes are not a convenience switch — running the same rewind
 * both ways and comparing outcomes ANSWERS the question. Do not assume neutrality.
 */
public enum CacheMode {

    /** Cache restored as it was at the checkpoint. For reproducing a run exactly. */
    FAITHFUL,

    /** Cache cleared. For testing whether behaviour depends on it at all. */
    FRESH
}
