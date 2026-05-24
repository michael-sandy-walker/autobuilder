package com.walpro.autobuilder.core.mutation;

import java.util.List;

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

    public boolean isEmpty() {
        return diagnostics.isEmpty();
    }
}
