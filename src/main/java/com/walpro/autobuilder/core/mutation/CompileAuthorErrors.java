package com.walpro.autobuilder.core.mutation;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * T2-DT: structured per-line author-error diagnostics from the previous cycle's compile probe,
 * carried on {@link com.walpro.autobuilder.core.lifecycle.CycleContextData} so the next cycle's
 * evolution prompt can render the PREVIOUS COMPILE ERRORS block.
 *
 * <p>T1-EW: the carrier describes a property of the *codebase* — "the previous compile probe
 * produced these errors at these file:line locations." It is NOT tied to the goal hierarchy.
 * Original T2-DT shipped this as `(subgoalId, diagnostics)` with subgoal-shift suppression in
 * the lineage tracker and prompt block; that identity was a category error and silently broke
 * T2-DT/T2-DV via collision with T1-DM's BUILD_FIX subgoal injection (the very subgoal change
 * that the suppression vetoed was a consequence of the rejection the carrier described). The
 * carrier's natural lifecycle now handles staleness: write on probe-rejection, overwrite on
 * the next probe, clear on merge — nothing else needs to manipulate it.
 */
public record CompileAuthorErrors(
        List<BlockerExtractor.ClassifiedDiagnostic> diagnostics
) {
    public CompileAuthorErrors {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    // Jackson treats this as a bean-style getter for property "empty" and serialises it on
    // write — but the canonical record constructor only accepts (diagnostics), so every
    // meta.json read fails with UnrecognizedPropertyException. Hide it from Jackson.
    @JsonIgnore
    public boolean isEmpty() {
        return diagnostics.isEmpty();
    }
}
