package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;

import java.util.List;

/**
 * A deterministic fix a {@link CompileFixResolver} proposes when classification produced a
 * known build-error kind. Pairs the concrete on-disk change ({@link FileMutation}s) with the
 * provenance metadata ({@link InjectorFixRecord}) that explains where the change came from.
 *
 * <p>The two halves move together: {@code mutations} is what gets written; {@code record} is
 * what gets persisted/logged/surfaced to LLM agents.
 */
public record FixProposal(
        List<FileMutation> mutations,
        InjectorFixRecord record
) {
    public FixProposal {
        if (mutations == null || mutations.isEmpty())
            throw new IllegalArgumentException("at least one mutation required");
        if (record == null) throw new IllegalArgumentException("provenance record required");
        mutations = List.copyOf(mutations);
    }

    public Kind kind() {
        return record.kind();
    }
}
