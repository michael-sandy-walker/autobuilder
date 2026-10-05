package com.walpro.autobuilder.agent.evolution.model;

import lombok.With;

import java.util.List;

@With
public record EvolutionOutcome(
        List<FileMutation> addedOrChangedFiles,
        List<String> deletedFiles,
        String summary,
        String rawResponse,
        boolean recombined,
        // T2-CC: true when the LLM responded with #NoDiff (implementation already present)
        boolean noOp
) {
    /** The summary of an answer that was discarded unread (its format or an edit did not apply). */
    public static final String INVALID_PREFIX = "Invalid response: ";

    /** True when the answer was discarded unread: nothing it wrote reached the tree. */
    public boolean discarded() {
        return summary != null && summary.startsWith(INVALID_PREFIX)
                && (addedOrChangedFiles == null || addedOrChangedFiles.isEmpty());
    }

    public EvolutionOutcome(List<FileMutation> addedOrChangedFiles, List<String> deletedFiles, String summary, String rawResponse) {
        this(addedOrChangedFiles, deletedFiles, summary, rawResponse, false, false);
    }

    public EvolutionOutcome(List<FileMutation> addedOrChangedFiles, List<String> deletedFiles, String summary, String rawResponse, boolean recombined) {
        this(addedOrChangedFiles, deletedFiles, summary, rawResponse, recombined, false);
    }
}
