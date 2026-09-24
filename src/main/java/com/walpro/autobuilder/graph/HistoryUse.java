package com.walpro.autobuilder.graph;

/** T2-ER: what a {@link HistoryRead}'s answer is allowed to do. */
public enum HistoryUse {

    /**
     * The answer can by itself block, promote, dedup away, restage or change the state of work,
     * or gate an accept/reject decision.
     */
    BINDING,

    /**
     * The answer is rendered for a reader (an agent's prompt, the operator's view, a log) or
     * weighed among other evidence; it does not decide anything alone.
     */
    ADVISORY
}
