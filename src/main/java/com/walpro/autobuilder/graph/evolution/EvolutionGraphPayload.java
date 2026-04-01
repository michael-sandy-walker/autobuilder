package com.walpro.autobuilder.graph.evolution;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;

import java.util.List;

/**
 * Deserialized evolution artifact payload retrieved from the graph.
 * Used to restore mutation files to the filesystem when they are missing.
 */
public record EvolutionGraphPayload(
        String cycleId,
        List<FileMutation> mutations,
        List<String> deletedFiles
) {}
