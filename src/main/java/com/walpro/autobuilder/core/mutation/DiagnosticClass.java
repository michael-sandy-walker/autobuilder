package com.walpro.autobuilder.core.mutation;

/**
 * T4-BF: per-line classification of a compile-probe diagnostic that decides the fate of the
 * surrounding mutation.
 *
 * <ul>
 *   <li>{@link #DEFERRABLE} — a missing-symbol / missing-member / missing-constructor shape
 *       that a later merge could realistically resolve. These feed the deferred-mutation
 *       ledger (T4-BE).</li>
 *   <li>{@link #AUTHOR_ERROR} — a programming mistake in the mutation itself
 *       (static-context misuse, type mismatch, ...). Re-staging cannot fix these; the
 *       classified diagnostic is surfaced upstream as steering for the next evolution
 *       prompt (T2-DT).</li>
 *   <li>{@link #UNKNOWN} — a diagnostic-shaped line the classifier doesn't recognise. The
 *       extractor refuses to defer when present so we never re-stage into a partially
 *       understood failure; the first unknown line is captured for observability.</li>
 * </ul>
 */
public enum DiagnosticClass {
    DEFERRABLE,
    AUTHOR_ERROR,
    UNKNOWN
}
