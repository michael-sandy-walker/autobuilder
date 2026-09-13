package com.walpro.autobuilder.analysis.heuristic;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface MutationHeuristicAnalyzer {
    List<HeuristicFinding> analyze(Map<String, String> mutatedFiles, Map<String, String> referenceFiles, Set<String> deletedFiles);

    /**
     * Compute per-file scores for the given mutation, using the analyzer's heuristics.
     * Default impl: just score all newFiles at neutral baseline.
     */
    default Map<String, Double> scorePerFile(Map<String, String> oldFiles,
                                             Map<String, String> newFiles,
                                             Set<String> deletedFiles,
                                             List<HeuristicFinding> findings) {
        return newFiles.keySet().stream()
                .collect(java.util.stream.Collectors.toMap(p -> p, p -> HeuristicScoreScale.NEUTRAL));
    }

    /**
     * Compute a single global score (0..1). Default impl uses the per-file average.
     */
    default double scoreGlobal(Map<String, String> oldFiles,
                               Map<String, String> newFiles,
                               Set<String> deletedFiles,
                               List<HeuristicFinding> findings) {
        Map<String, Double> perFile = scorePerFile(oldFiles, newFiles, deletedFiles, findings);
        return perFile.values().stream().mapToDouble(Double::doubleValue)
                .average().orElse(HeuristicScoreScale.NEUTRAL);
    }
}
