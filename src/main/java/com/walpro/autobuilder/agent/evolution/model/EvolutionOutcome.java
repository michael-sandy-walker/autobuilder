package com.walpro.autobuilder.agent.evolution.model;

import lombok.With;

import java.util.List;

@With
public record EvolutionOutcome(
        List<FileMutation> addedOrChangedFiles,
        List<String> deletedFiles,
        String summary,
        String rawResponse,
        boolean recombined
) {
    public EvolutionOutcome(List<FileMutation> addedOrChangedFiles, List<String> deletedFiles, String summary, String rawResponse) {
        this(addedOrChangedFiles, deletedFiles, summary, rawResponse, false);
    }
}
