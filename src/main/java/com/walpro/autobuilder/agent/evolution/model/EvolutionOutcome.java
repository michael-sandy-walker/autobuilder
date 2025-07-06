package com.walpro.autobuilder.agent.evolution.model;

import java.util.List;

public record EvolutionOutcome(
        List<FileMutation> addedOrChangedFiles,
        List<String> deletedFiles,
        String summary
) {}
