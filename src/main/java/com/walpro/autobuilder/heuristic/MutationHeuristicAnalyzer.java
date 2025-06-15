package com.walpro.autobuilder.heuristic;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface MutationHeuristicAnalyzer {
    List<HeuristicFinding> analyze(Map<String, String> mutatedFiles, Map<String, String> referenceFiles, Set<String> deletedFiles);
    public double calculateScore(Map<String, String> oldFiles, Map<String, String> newFiles, Set<String> deletedFiles, List<HeuristicFinding> findings);
}
