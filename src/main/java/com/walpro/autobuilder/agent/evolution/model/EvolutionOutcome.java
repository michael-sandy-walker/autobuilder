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
    public EvolutionOutcome(List<FileMutation> addedOrChangedFiles, List<String> deletedFiles, String summary, String rawResponse) {
        this(addedOrChangedFiles, deletedFiles, summary, rawResponse, false, false);
    }

    public EvolutionOutcome(List<FileMutation> addedOrChangedFiles, List<String> deletedFiles, String summary, String rawResponse, boolean recombined) {
        this(addedOrChangedFiles, deletedFiles, summary, rawResponse, recombined, false);
    }
}
