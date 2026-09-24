package com.walpro.autobuilder.graph;

/** T2-ER: how much accumulated history a {@link HistoryRead} answers over. */
public enum HistorySpan {

    /** Filtered to the current run's {@code runId}. */
    RUN,

    /**
     * Filtered to one {@code lineageId}: this run and the runs it continues. That is the current
     * lineage unless the caller names another, which only an operator-directed rewind does.
     */
    LINEAGE,

    /**
     * Keyed by an id minted inside one lineage (a goal, an attempt, a cycle). The key cannot name
     * another lineage's node, so the read spans exactly the runs of the lineage that owns the key,
     * which is the current one whenever the key came from the live tree.
     */
    KEY_OWNER,

    /** Unfiltered: every lineage the graph has retained. */
    ALL_LINEAGES
}
