package com.walpro.autobuilder.core.mutation;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * T2-DT: typed forward-signal carried on {@link com.walpro.autobuilder.core.lifecycle.CycleContextData}
 * after a compile probe rolled back a mutation with author-error diagnostics. Pairs the
 * structured per-line {@link BlockerExtractor.ClassifiedDiagnostic} list with the originating
 * {@code subgoalId} so the evolution prompt block can suppress steering when the subgoal has
 * shifted (author-errors are tied to the attempt's intent — stale diagnostics from a closed
 * subgoal must not contaminate a fresh attempt).
 */
public record CompileAuthorErrors(
        String subgoalId,
        List<BlockerExtractor.ClassifiedDiagnostic> diagnostics
) {
    public CompileAuthorErrors {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    // Jackson treats this as a bean-style getter for property "empty" and serialises it on
    // write — but the canonical record constructor only accepts (subgoalId, diagnostics), so
    // every meta.json read fails with UnrecognizedPropertyException. Hide it from Jackson.
    @JsonIgnore
    public boolean isEmpty() {
        return diagnostics.isEmpty();
    }
}
