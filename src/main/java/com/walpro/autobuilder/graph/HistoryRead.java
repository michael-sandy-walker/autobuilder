package com.walpro.autobuilder.graph;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * T2-ER: declares how much of the graph's accumulated history a read spans, and what its answer
 * is allowed to do.
 *
 * <p><b>Why a declaration.</b> Since the graph stopped being wiped per run (T4-CJ slice 2) every
 * read answers over several runs and several lineages unless it says otherwise, and the right
 * answer differs per read: on 2026-07-26 two reads had to be scoped to the lineage because a dead
 * lineage's goals were rejecting live work, while a third was deliberately left spanning runs
 * because scoping it would have removed a safeguard. That call was being made ad hoc, query by
 * query, and recorded nowhere a later reader would look. {@code HistoryReadContractTest} finds
 * every read query in the source and fails the build for one whose method does not carry this.
 *
 * <p><b>The rule the test enforces.</b> What a previous lineage measured may INFORM (it is
 * labelled with where it came from, and it can be outvoted), but it must not by itself block,
 * promote, dedup away, restage or change the state of this lineage's work. So a read spanning
 * {@link HistorySpan#ALL_LINEAGES} is {@link HistoryUse#ADVISORY}, or it states in
 * {@link #crossLineageBecause()} why a binding use is safe.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface HistoryRead {

    /** How far back and how wide the answer reaches. */
    HistorySpan span();

    /** Whether the answer decides something on its own, or only informs. */
    HistoryUse use();

    /** Who consumes the answer and why this span is the right one. Must be non-blank. */
    String note();

    /**
     * Required, and only meaningful, for a {@link HistoryUse#BINDING} read that spans
     * {@link HistorySpan#ALL_LINEAGES}: why another lineage's answer is safe to act on here —
     * for example a cache keyed by the complete input, where the same key is the same question.
     */
    String crossLineageBecause() default "";
}
