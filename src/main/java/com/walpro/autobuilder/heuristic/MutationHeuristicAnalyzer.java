package com.walpro.autobuilder.heuristic;

import java.util.List;
import java.util.Map;

public interface MutationHeuristicAnalyzer {
    List<HeuristicFinding> analyze(Map<String, String> mutatedFiles, Map<String, String> referenceFiles);
}
